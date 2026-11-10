package me.arsali.duels;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Duels extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    static final MiniMessage MM = MiniMessage.miniMessage();
    static final Pattern PH = Pattern.compile("\\{(\\w+)\\}");

    enum State { COUNTDOWN, ACTIVE, ENDED }

    record Req(UUID from, String kit, long time) {}

    static class Party {
        UUID leader;
        final List<UUID> members = new ArrayList<>();
    }

    record PInv(Party party, long time) {}

    /** Kit selection GUI. mode = duel | queue | ffa | editor */
    static class KitMenu implements InventoryHolder {
        final String mode;
        final UUID target;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;
        KitMenu(String mode, UUID target) { this.mode = mode; this.target = target; }
        @Override public Inventory getInventory() { return inv; }
    }

    static class SettingsMenu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    class Match {
        final List<Player> t1, t2;
        final String kit;
        final boolean ffa;
        String arena;
        final Set<UUID> specs = new HashSet<>();
        final Set<UUID> alive = new HashSet<>();
        final Set<UUID> left = new HashSet<>();
        State state = State.COUNTDOWN;
        long start = 0;
        BoundingBox box;
        World world;
        BlockData[] snap;

        Match(List<Player> t1, List<Player> t2, String kit, boolean ffa) {
            this.t1 = t1; this.t2 = t2; this.kit = kit; this.ffa = ffa;
            for (Player p : t1) alive.add(p.getUniqueId());
            for (Player p : t2) alive.add(p.getUniqueId());
        }
        boolean inTeam1(Player p) {
            for (Player x : t1) if (x.getUniqueId().equals(p.getUniqueId())) return true;
            return false;
        }
        List<Player> team(Player p) {
            if (ffa) return List.of(p);
            return inTeam1(p) ? t1 : t2;
        }
        List<Player> enemy(Player p) {
            if (ffa) {
                List<Player> l = new ArrayList<>();
                for (Player x : t1) if (!x.getUniqueId().equals(p.getUniqueId())) l.add(x);
                return l;
            }
            return inTeam1(p) ? t2 : t1;
        }
        List<Player> all() { List<Player> l = new ArrayList<>(t1); l.addAll(t2); return l; }
    }

    final Map<UUID, Match> matches = new HashMap<>();
    final Map<UUID, Match> watching = new HashMap<>();
    final Set<Match> activeMatches = new HashSet<>();
    final Map<UUID, Req> requests = new HashMap<>();
    final Map<UUID, Scoreboard> boards = new HashMap<>();
    final Set<String> busyArenas = new HashSet<>();
    final Map<UUID, Party> parties = new HashMap<>();
    final Map<UUID, PInv> pinvites = new HashMap<>();
    final Map<UUID, String> queue = new LinkedHashMap<>();
    final Map<UUID, String> editing = new HashMap<>();

    File dataFile;
    YamlConfiguration data;
    NamespacedKey actionKey;

    // ───────────────────────── setup ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        actionKey = new NamespacedKey(this, "action");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        // migrate data written by older versions into data.yml
        boolean migrated = false;
        for (String k : List.of("lobby", "arenas", "kits", "layouts", "settings")) {
            if (!data.contains(k) && getConfig().isSet(k)) { data.set(k, getConfig().get(k)); migrated = true; }
        }
        if (migrated) saveData();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (String c : List.of("duel", "spectate", "leave", "lobby", "setlobby", "arena", "kit", "party",
                "settings", "kiteditor", "duels", "world")) {
            Objects.requireNonNull(getCommand(c)).setExecutor(this);
            Objects.requireNonNull(getCommand(c)).setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.getOnlinePlayers().forEach(this::update), 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) toLobby(p);
    }

    @Override
    public void onDisable() {
        for (Match m : new HashSet<>(activeMatches)) restore(m);
    }

    void saveData() {
        try { data.save(dataFile); } catch (IOException ex) { getLogger().warning("Could not save data.yml: " + ex.getMessage()); }
    }

    // ───────────────────────── messaging helpers ─────────────────────────

    void msg(CommandSender s, String mm) { s.sendMessage(MM.deserialize(mm)); }

    String fmt(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    String t(String key, String... kv) {
        String s = getConfig().getString("messages." + key);
        if (s == null) s = "<red>[missing message: " + key + "]";
        return fmt(s, kv);
    }

    void send(CommandSender s, String key, String... kv) { msg(s, t(key, kv)); }

    void sendList(CommandSender s, String path, String... kv) {
        for (String l : getConfig().getStringList(path)) msg(s, fmt(l, kv));
    }

    void title(Player p, String t, String sub) {
        p.showTitle(Title.title(MM.deserialize(t), MM.deserialize(sub),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(400))));
    }

    void titleK(Player p, String key, String... kv) {
        title(p, fmt(getConfig().getString("titles." + key + ".title", ""), kv),
                fmt(getConfig().getString("titles." + key + ".subtitle", ""), kv));
    }

    void sound(Player p, String key) {
        for (String line : getConfig().getStringList("sounds." + key)) {
            String[] a = line.trim().split("\\s+");
            if (a.length == 0 || a[0].isEmpty()) continue;
            float vol = 1f, pitch = 1f;
            try {
                if (a.length > 1) vol = Float.parseFloat(a[1]);
                if (a.length > 2) pitch = Float.parseFloat(a[2]);
            } catch (NumberFormatException ignored) { }
            p.playSound(p.getLocation(), a[0], SoundCategory.MASTER, vol, pitch);
        }
    }

    String clock(long s) { return String.format("%02d:%02d", s / 60, s % 60); }

    String label(List<Player> l) {
        if (l.isEmpty()) return "-";
        return l.get(0).getName() + (l.size() > 1 ? " +" + (l.size() - 1) : "");
    }

    String names(List<Player> l) {
        List<String> n = new ArrayList<>();
        for (Player p : l) n.add(p.getName());
        return String.join(", ", n);
    }

    ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    ItemStack glass() {
        Material m = Material.matchMaterial(getConfig().getString("gui.glass", "BLACK_STAINED_GLASS_PANE"));
        if (m == null) m = Material.BLACK_STAINED_GLASS_PANE;
        return named(m, " ");
    }

    // ───────────────────────── lobby / reset ─────────────────────────

    Location lobby() {
        Location l = data.getLocation("lobby");
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    void reset(Player p, GameMode gm) {
        p.setGameMode(gm);
        p.getInventory().clear();
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setCooldown(Material.WIND_CHARGE, 0);
    }

    void putItem(Player p, String key, String action) {
        String base = "lobby-items." + key;
        Material mat = Material.matchMaterial(getConfig().getString(base + ".material", "STONE"));
        if (mat == null) mat = Material.STONE;
        List<String> lore = getConfig().getStringList(base + ".lore");
        ItemStack it = named(mat, getConfig().getString(base + ".name", key), lore.toArray(new String[0]));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        p.getInventory().setItem(getConfig().getInt(base + ".slot", 0), it);
    }

    /** Lobby hotbar. Changes when you are in a party (lectern = FFA, barrier = disband/leave). */
    void lobbyItems(Player p) {
        Party pt = partyOf(p);
        if (pt == null) {
            putItem(p, "queue", "queue");
            putItem(p, "party-create", "party");
        } else if (pt.leader.equals(p.getUniqueId())) {
            putItem(p, "party-ffa", "ffa");
            putItem(p, "party-disband", "disband");
        } else {
            putItem(p, "party-leave", "leaveparty");
        }
        putItem(p, "editor", "editor");
        putItem(p, "settings", "settings");
    }

    void refreshLobby(Player p) {
        if (p == null || !p.isOnline()) return;
        if (busy(p) || editing.containsKey(p.getUniqueId()) || p.getGameMode() != GameMode.ADVENTURE) return;
        p.getInventory().clear();
        lobbyItems(p);
    }

    void refresh(Collection<UUID> ids) {
        for (UUID id : new ArrayList<>(ids)) refreshLobby(Bukkit.getPlayer(id));
    }

    void toLobby(Player p) {
        editing.remove(p.getUniqueId());
        reset(p, GameMode.ADVENTURE);
        p.teleport(lobby());
        lobbyItems(p);
        update(p);
    }

    boolean busy(Player p) {
        return matches.containsKey(p.getUniqueId()) || watching.containsKey(p.getUniqueId());
    }

    boolean anyBusy(List<Player> l) {
        for (Player p : l) if (busy(p)) return true;
        return false;
    }

    // ───────────────────────── kits / arenas ─────────────────────────

    String firstKit() {
        ConfigurationSection s = data.getConfigurationSection("kits");
        if (s == null || s.getKeys(false).isEmpty()) return null;
        return s.getKeys(false).iterator().next();
    }

    ItemStack[] kitBase(String kit) {
        List<?> raw = data.getList("kits." + kit + ".items");
        if (raw == null) return null;
        ItemStack[] arr = new ItemStack[raw.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = raw.get(i) instanceof ItemStack b ? b : null;
        return arr;
    }

    void giveKit(Player p, String kit) {
        ItemStack[] arr = kitBase(kit);
        if (arr == null) return;
        p.getInventory().setContents(arr);
        // player's own saved layout (Kit Editor)
        List<?> lay = data.getList("layouts." + p.getUniqueId() + "." + kit);
        if (lay != null && lay.size() == 36 && arr.length >= 36) {
            ItemStack[] ls = new ItemStack[36];
            for (int i = 0; i < 36; i++) ls[i] = lay.get(i) instanceof ItemStack li ? li : null;
            if (sameMulti(merge(ls, 0, 36), merge(arr, 0, 36))) {
                for (int i = 0; i < 36; i++) p.getInventory().setItem(i, ls[i]);
            }
        }
    }

    List<Location> ffaSpawns(String arena) {
        List<Location> out = new ArrayList<>();
        List<?> raw = data.getList("arenas." + arena + ".ffa");
        if (raw != null) for (Object o : raw) if (o instanceof Location l) out.add(l);
        return out;
    }

    String freeArena(boolean ffa) {
        ConfigurationSection s = data.getConfigurationSection("arenas");
        if (s == null) return null;
        List<String> pref = new ArrayList<>(), any = new ArrayList<>();
        for (String n : s.getKeys(false)) {
            if (busyArenas.contains(n)) continue;
            boolean duelOk = s.getLocation(n + ".spawn1") != null && s.getLocation(n + ".spawn2") != null;
            if (ffa && ffaSpawns(n).size() >= 2) pref.add(n);
            else if (duelOk) any.add(n);
        }
        List<String> pool = (ffa && !pref.isEmpty()) ? pref : any;
        if (pool.isEmpty()) return null;
        return pool.get(new Random().nextInt(pool.size()));
    }

    // ───────────────────────── settings ─────────────────────────

    static final String[] SETTING_KEYS = {"scoreboard", "nametags", "players", "requests"};
    static final int[] SETTING_SLOTS = {10, 12, 14, 16};

    boolean setting(Player p, String key) {
        return data.getBoolean("settings." + p.getUniqueId() + "." + key, true);
    }

    void openSettings(Player p) {
        SettingsMenu h = new SettingsMenu();
        Inventory inv = Bukkit.createInventory(h, 27, MM.deserialize(getConfig().getString("gui.titles.settings", "Settings")));
        h.inv = inv;
        fillSettings(p, inv);
        p.openInventory(inv);
    }

    void fillSettings(Player p, Inventory inv) {
        ItemStack g = glass();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, g);
        for (int i = 0; i < SETTING_KEYS.length; i++) {
            String k = SETTING_KEYS[i];
            boolean on = setting(p, k);
            String nm = getConfig().getString("gui.settings." + k + ".name", k);
            String ds = getConfig().getString("gui.settings." + k + ".description", "");
            inv.setItem(SETTING_SLOTS[i], named(on ? Material.LIME_DYE : Material.GRAY_DYE,
                    (on ? "<green>" : "<red>") + "<bold>" + nm + ": " + (on ? "ON" : "OFF"),
                    "<gray>" + ds, "", "<yellow>Click to toggle"));
        }
    }

    void visibility(Player p) {
        Match pm = matches.get(p.getUniqueId());
        Match pw = watching.get(p.getUniqueId());
        Match pMatch = pm != null ? pm : pw;
        boolean hideLobby = !setting(p, "players");
        Party pa = parties.get(p.getUniqueId());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            Match om = matches.get(o.getUniqueId());
            Match ow = watching.get(o.getUniqueId());
            Match oMatch = om != null ? om : ow;
            boolean see;
            if (pMatch != null) see = pMatch == oMatch;
            else see = oMatch == null && (!hideLobby || (pa != null && pa == parties.get(o.getUniqueId())));
            boolean can = p.canSee(o);
            if (see && !can) p.showPlayer(this, o);
            else if (!see && can) p.hidePlayer(this, o);
        }
    }

    // ───────────────────────── kit editor ─────────────────────────

    boolean sim(ItemStack a, ItemStack b) {
        boolean ea = a == null || a.getType().isAir(), eb = b == null || b.getType().isAir();
        if (ea || eb) return ea && eb;
        return a.isSimilar(b) && a.getAmount() == b.getAmount();
    }

    List<ItemStack> merge(ItemStack[] arr, int from, int to) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = from; i < to && i < arr.length; i++) {
            ItemStack it = arr[i];
            if (it == null || it.getType().isAir()) continue;
            boolean found = false;
            for (ItemStack o : out) {
                if (o.isSimilar(it)) { o.setAmount(o.getAmount() + it.getAmount()); found = true; break; }
            }
            if (!found) out.add(it.clone());
        }
        return out;
    }

    boolean sameMulti(List<ItemStack> x, List<ItemStack> y) {
        if (x.size() != y.size()) return false;
        List<ItemStack> rest = new ArrayList<>(y);
        for (ItemStack a : x) {
            boolean ok = false;
            for (int i = 0; i < rest.size(); i++) {
                ItemStack b = rest.get(i);
                if (a.isSimilar(b) && a.getAmount() == b.getAmount()) { rest.remove(i); ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    void startEditor(Player p, String kit) {
        UUID id = p.getUniqueId();
        if (busy(p)) return;
        if (kitBase(kit) == null) { send(p, "kit-not-found"); return; }
        queue.remove(id);
        reset(p, GameMode.ADVENTURE);
        giveKit(p, kit);
        editing.put(id, kit);
        send(p, "editor-start", "kit", kit);
        send(p, "editor-buttons");
    }

    void editorSave(Player p) {
        UUID id = p.getUniqueId();
        String kit = editing.get(id);
        if (kit == null) { send(p, "editor-not-editing"); return; }
        ItemStack[] base = kitBase(kit);
        ItemStack[] cur = p.getInventory().getContents();
        if (base == null || base.length < 36 || cur.length < 36) {
            send(p, "kit-not-found");
            toLobby(p);
            return;
        }
        boolean ok = sameMulti(merge(cur, 0, 36), merge(base, 0, 36));
        for (int i = 36; i < base.length && i < cur.length && ok; i++) if (!sim(base[i], cur[i])) ok = false;
        if (!ok) { send(p, "editor-bad-layout"); return; }
        data.set("layouts." + id + "." + kit, new ArrayList<>(Arrays.asList(Arrays.copyOfRange(cur, 0, 36))));
        saveData();
        toLobby(p);
        send(p, "editor-saved", "kit", kit);
    }

    void kiteditor(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        UUID id = p.getUniqueId();
        switch (sub) {
            case "save" -> editorSave(p);
            case "cancel" -> {
                if (editing.containsKey(id)) { toLobby(p); send(p, "editor-closed"); }
                else send(p, "editor-not-editing");
            }
            case "reset" -> {
                String kit = editing.get(id);
                if (kit == null) { send(p, "editor-not-editing"); return; }
                data.set("layouts." + id + "." + kit, null);
                saveData();
                giveKit(p, kit);
                send(p, "editor-reset");
            }
            default -> {
                if (busy(p)) { send(p, "editor-busy"); return; }
                openKitMenu(p, "editor", null);
            }
        }
    }

    // ───────────────────────── kit GUI ─────────────────────────

    ItemStack kitIcon(String name, String mode) {
        Material mat = null;
        String ic = data.getString("kits." + name + ".icon");
        if (ic != null) mat = Material.matchMaterial(ic);
        if (mat == null) {
            ItemStack[] raw = kitBase(name);
            if (raw != null) {
                for (ItemStack is : raw) {
                    if (is == null) continue;
                    String n = is.getType().name();
                    if (n.contains("SWORD") || n.contains("AXE") || n.contains("MACE") || n.contains("SPEAR")
                            || n.contains("BOW") || n.contains("TRIDENT")) { mat = is.getType(); break; }
                }
                if (mat == null) {
                    for (ItemStack is : raw) if (is != null && !is.getType().isAir()) { mat = is.getType(); break; }
                }
            }
        }
        if (mat == null) mat = Material.PAPER;
        String pretty = name.substring(0, 1).toUpperCase() + name.substring(1);
        int queued = 0, fighting = 0;
        for (String q : queue.values()) if (q.equals(name)) queued++;
        for (Match m : activeMatches) if (m.kit.equals(name)) fighting += m.all().size();
        List<String> lore = new ArrayList<>();
        for (String l : getConfig().getStringList("gui.lore." + mode)) {
            lore.add(fmt(l, "queued", String.valueOf(queued), "fighting", String.valueOf(fighting)));
        }
        return named(mat, fmt(getConfig().getString("gui.kit-name", "<yellow><bold>{kit}"), "kit", pretty),
                lore.toArray(new String[0]));
    }

    void openKitMenu(Player p, String mode, UUID target) {
        List<String> kits = new ArrayList<>();
        ConfigurationSection ks = data.getConfigurationSection("kits");
        if (ks != null) for (String k : ks.getKeys(false)) if (data.getList("kits." + k + ".items") != null) kits.add(k);
        if (kits.isEmpty()) { send(p, "no-kits"); return; }
        int per = 7;
        if (kits.size() > per * 4) kits = new ArrayList<>(kits.subList(0, per * 4));
        int kitRows = (kits.size() + per - 1) / per;
        int rows = Math.min(6, kitRows + 2);

        KitMenu h = new KitMenu(mode, target);
        Inventory inv = Bukkit.createInventory(h, rows * 9,
                MM.deserialize(getConfig().getString("gui.titles." + mode, "<dark_gray>Select a kit")));
        h.inv = inv;
        ItemStack g = glass();
        for (int i = 0; i < rows * 9; i++) inv.setItem(i, g);   // glass on all sides
        int idx = 0;
        for (int r = 0; r < kitRows; r++) {
            int n = Math.min(per, kits.size() - idx);
            int startCol = 1 + (per - n) / 2;                    // centered row
            for (int c = 0; c < n; c++) {
                String k = kits.get(idx++);
                int slot = (1 + r) * 9 + startCol + c;
                inv.setItem(slot, kitIcon(k, mode));
                h.slots.put(slot, k);
            }
        }
        p.openInventory(inv);
    }

    void choose(Player p, KitMenu h, String kit) {
        sound(p, "click");
        switch (h.mode) {
            case "duel" -> {
                Player tg = Bukkit.getPlayer(h.target);
                if (tg == null) { send(p, "player-offline"); return; }
                sendRequest(p, tg, kit);
            }
            case "queue" -> joinQueue(p, kit);
            case "ffa" -> startPartyFfa(p, kit);
            case "editor" -> startEditor(p, kit);
            default -> { }
        }
    }

    // ───────────────────────── queue ─────────────────────────

    void toggleQueue(Player p) {
        UUID id = p.getUniqueId();
        if (queue.remove(id) != null) { send(p, "queue-left"); update(p); return; }
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        openKitMenu(p, "queue", null);
    }

    void joinQueue(Player p, String kit) {
        UUID id = p.getUniqueId();
        if (busy(p)) return;
        if (parties.containsKey(id)) { send(p, "queue-party-block"); return; }
        UUID found = null;
        for (Map.Entry<UUID, String> en : queue.entrySet()) {
            if (!en.getKey().equals(id) && en.getValue().equals(kit)) {
                Player o = Bukkit.getPlayer(en.getKey());
                if (o != null && !busy(o)) { found = en.getKey(); break; }
            }
        }
        if (found != null) {
            if (freeArena(false) == null) { send(p, "queue-no-arena"); return; }
            Player o = Bukkit.getPlayer(found);
            queue.remove(found);
            queue.remove(id);
            send(o, "queue-found"); sound(o, "queue-found");
            send(p, "queue-found"); sound(p, "queue-found");
            start(List.of(o), List.of(p), kit);
            return;
        }
        queue.put(id, kit);
        send(p, "queue-searching", "kit", kit);
        update(p);
    }

    // ───────────────────────── arena regen ─────────────────────────

    void snapshot(Match m, ConfigurationSection s) {
        Location c1 = s.getLocation("corner1"), c2 = s.getLocation("corner2");
        if (c1 == null || c2 == null || c1.getWorld() == null || !c1.getWorld().equals(c2.getWorld())) return;
        BoundingBox box = BoundingBox.of(c1.getBlock(), c2.getBlock());
        if (box.getVolume() > getConfig().getInt("general.max-arena-volume", 300000)) {
            getLogger().warning("Arena " + m.arena + " is too big for regen (general.max-arena-volume).");
            return;
        }
        World w = c1.getWorld();
        BlockData[] snap = new BlockData[(int) box.getVolume()];
        int i = 0;
        for (int x = (int) box.getMinX(); x < (int) box.getMaxX(); x++)
            for (int y = (int) box.getMinY(); y < (int) box.getMaxY(); y++)
                for (int z = (int) box.getMinZ(); z < (int) box.getMaxZ(); z++)
                    snap[i++] = w.getBlockAt(x, y, z).getBlockData();
        m.box = box; m.world = w; m.snap = snap;
    }

    void restore(Match m) {
        if (m.snap == null || m.world == null) return;
        int i = 0;
        for (int x = (int) m.box.getMinX(); x < (int) m.box.getMaxX(); x++)
            for (int y = (int) m.box.getMinY(); y < (int) m.box.getMaxY(); y++)
                for (int z = (int) m.box.getMinZ(); z < (int) m.box.getMaxZ(); z++) {
                    Block b = m.world.getBlockAt(x, y, z);
                    BlockData d = m.snap[i++];
                    if (!b.getBlockData().equals(d)) b.setBlockData(d, false);
                }
        for (Entity e : m.world.getNearbyEntities(m.box, en -> en instanceof Item || en instanceof Projectile)) {
            e.remove();
        }
        m.snap = null;
    }

    boolean inBox(Match m, Block b) {
        return m.box != null && m.box.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    boolean inAnyRegion(Block b) {
        for (Match m : activeMatches) if (inBox(m, b)) return true;
        return false;
    }

    // ───────────────────────── match flow ─────────────────────────

    void start(List<Player> t1, List<Player> t2, String kit) {
        launch(new Match(new ArrayList<>(t1), new ArrayList<>(t2), kit, false));
    }

    void startFfa(List<Player> all, String kit) {
        launch(new Match(new ArrayList<>(all), new ArrayList<>(), kit, true));
    }

    void launch(Match m) {
        List<Player> everyone = m.all();
        String arena = freeArena(m.ffa);
        if (arena == null) {
            for (Player p : everyone) send(p, "no-free-arena");
            return;
        }
        m.arena = arena;
        ConfigurationSection s = Objects.requireNonNull(data.getConfigurationSection("arenas." + arena));
        busyArenas.add(arena);
        activeMatches.add(m);
        for (Player p : everyone) {
            matches.put(p.getUniqueId(), m);
            queue.remove(p.getUniqueId());
            editing.remove(p.getUniqueId());
        }
        snapshot(m, s);

        if (m.ffa) {
            List<Location> spots = ffaSpawns(arena);                 // /arena ffa [name]
            if (spots.size() < 2) {
                spots = new ArrayList<>();
                spots.add(s.getLocation("spawn1"));
                spots.add(s.getLocation("spawn2"));
            }
            Collections.shuffle(spots);
            int i = 0;
            for (Player p : m.t1) prepare(p, m.kit, spots.get(i++ % spots.size()));
        } else {
            for (Player p : m.t1) prepare(p, m.kit, s.getLocation("spawn1"));
            for (Player p : m.t2) prepare(p, m.kit, s.getLocation("spawn2"));
        }

        int secs = Math.max(1, getConfig().getInt("general.countdown-seconds", 5));
        new BukkitRunnable() {
            int c = secs;
            @Override public void run() {
                if (m.state == State.ENDED) { cancel(); return; }
                if (c == 0) {
                    m.state = State.ACTIVE;
                    m.start = System.currentTimeMillis();
                    for (Player p : m.all()) { titleK(p, "fight"); sound(p, "fight"); }
                    cancel();
                    return;
                }
                for (Player p : m.all()) {
                    titleK(p, m.ffa ? "countdown-ffa" : "countdown",
                            "count", String.valueOf(c), "opponent", m.ffa ? "" : label(m.enemy(p)));
                    sound(p, "countdown");
                }
                c--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    void prepare(Player p, String kit, Location l) {
        reset(p, GameMode.SURVIVAL);
        p.teleport(l);
        giveKit(p, kit);
        update(p);
    }

    void eliminate(Match m, Player p, boolean quit) {
        if (m.state == State.ENDED) return;
        if (!m.alive.remove(p.getUniqueId())) return;

        if (m.ffa) {
            if (m.alive.size() <= 1) {
                List<Player> win = new ArrayList<>(), lose = new ArrayList<>();
                for (Player x : m.all()) {
                    if (m.alive.contains(x.getUniqueId())) win.add(x); else lose.add(x);
                }
                end(m, win, lose);
            } else {
                for (Player x : m.all()) send(x, "eliminated-ffa", "player", p.getName(), "alive", String.valueOf(m.alive.size()));
                if (!quit) {
                    p.setGameMode(GameMode.SPECTATOR);
                    titleK(p, "eliminated", "player", p.getName());
                    sound(p, "eliminated");
                }
            }
            return;
        }

        List<Player> mine = m.team(p);
        boolean teamDead = true;
        for (Player x : mine) if (m.alive.contains(x.getUniqueId())) { teamDead = false; break; }
        if (teamDead) {
            end(m, m.enemy(p), mine);
        } else {
            for (Player x : m.all()) send(x, "eliminated-team", "player", p.getName());
            if (!quit) {
                p.setGameMode(GameMode.SPECTATOR);
                titleK(p, "eliminated", "player", p.getName());
                sound(p, "eliminated");
            }
        }
    }

    void end(Match m, List<Player> winners, List<Player> losers) {
        if (m.state == State.ENDED) return;
        m.state = State.ENDED;
        long secs = m.start == 0 ? 0 : (System.currentTimeMillis() - m.start) / 1000;
        String wn = names(winners), ln = names(losers);
        double hp = 0;
        for (Player w : winners) if (w.isOnline()) { hp = w.getHealth() / 2.0; break; }
        String[] vars = {"winner", wn, "loser", ln, "time", clock(secs), "kit", m.kit, "hp", String.format("%.1f", hp)};

        for (Player w : winners) {
            if (!w.isOnline() || m.left.contains(w.getUniqueId())) continue;
            titleK(w, "victory", vars);
            sound(w, "victory");
            sendList(w, "messages.duel-summary", vars);
        }
        for (Player l : losers) {
            if (!l.isOnline() || m.left.contains(l.getUniqueId())) continue;
            titleK(l, "defeat", vars);
            sound(l, "defeat");
            sendList(l, "messages.duel-summary", vars);
            l.setGameMode(GameMode.SPECTATOR);
        }
        for (UUID id : m.specs) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) sendList(sp, "messages.duel-summary", vars);
        }
        long delay = Math.max(1, getConfig().getInt("general.end-delay-seconds", 3)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> finish(m), delay);
    }

    void finish(Match m) {
        for (Player p : m.all()) {
            if (p.isOnline() && matches.get(p.getUniqueId()) == m) {
                matches.remove(p.getUniqueId());
                toLobby(p);
            }
        }
        for (UUID id : new ArrayList<>(m.specs)) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null && watching.get(id) == m) {
                watching.remove(id);
                toLobby(sp);
            }
        }
        restore(m);
        activeMatches.remove(m);
        busyArenas.remove(m.arena);
    }

    void leave(Player p) {
        UUID id = p.getUniqueId();
        queue.remove(id);
        Match m = matches.get(id);
        if (m != null) {
            m.left.add(id);
            eliminate(m, p, true);
            matches.remove(id);
        }
        Match w = watching.remove(id);
        if (w != null) w.specs.remove(id);
        toLobby(p);
        send(p, "back-to-lobby");
    }

    // ───────────────────────── parties ─────────────────────────

    Party partyOf(Player p) { return parties.get(p.getUniqueId()); }

    List<Player> online(Party pt) {
        List<Player> l = new ArrayList<>();
        for (UUID id : pt.members) {
            Player x = Bukkit.getPlayer(id);
            if (x != null) l.add(x);
        }
        return l;
    }

    void pmsg(Party pt, String key, String... kv) { for (Player x : online(pt)) send(x, key, kv); }

    List<Player> team(Player leader) {
        Party pt = partyOf(leader);
        if (pt == null) return new ArrayList<>(List.of(leader));
        return online(pt);
    }

    void partyLeave(Player p) {
        UUID id = p.getUniqueId();
        Party pt = parties.remove(id);
        if (pt == null) return;
        List<UUID> aff = new ArrayList<>(pt.members);
        pt.members.remove(id);
        pmsg(pt, "party-left", "player", p.getName());
        if (pt.members.size() <= 1) {
            for (UUID m : new ArrayList<>(pt.members)) {
                parties.remove(m);
                Player x = Bukkit.getPlayer(m);
                if (x != null) { send(x, "party-disbanded"); titleK(x, "party-disbanded"); }
            }
            pt.members.clear();
        } else if (pt.leader.equals(id)) {
            pt.leader = pt.members.get(0);
            Player nl = Bukkit.getPlayer(pt.leader);
            pmsg(pt, "party-new-leader", "player", nl != null ? nl.getName() : "?");
        }
        refresh(aff);
    }

    void startPartyFfa(Player p, String kit) {
        Party pt = partyOf(p);
        if (pt == null || !pt.leader.equals(p.getUniqueId())) { send(p, "party-leader-only"); return; }
        List<Player> all = online(pt);
        if (all.size() < 2) { send(p, "party-ffa-min"); return; }
        if (anyBusy(all)) { send(p, "party-ffa-busy"); return; }
        startFfa(all, kit);
    }

    void party(Player p, String[] a) {
        UUID id = p.getUniqueId();
        Party pt = parties.get(id);
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        int max = getConfig().getInt("general.party-max", 4);
        switch (sub) {
            case "create" -> {
                if (pt != null) { send(p, "party-already-in"); return; }
                queue.remove(id);
                Party n = new Party();
                n.leader = id;
                n.members.add(id);
                parties.put(id, n);
                send(p, "party-created");
                titleK(p, "party-created");
                sound(p, "party-created");
                refresh(List.of(id));
            }
            case "invite" -> {
                if (pt == null) { send(p, "party-need-create"); return; }
                if (!pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (a.length < 2) { send(p, "party-invite-usage"); return; }
                Player tg = Bukkit.getPlayerExact(a[1]);
                if (tg == null || tg.equals(p)) { send(p, "player-not-found"); return; }
                if (parties.containsKey(tg.getUniqueId())) { send(p, "party-in-other"); return; }
                if (pt.members.size() >= max) { send(p, "party-full"); return; }
                pinvites.put(tg.getUniqueId(), new PInv(pt, System.currentTimeMillis()));
                send(p, "party-invite-sent", "player", tg.getName());
                send(tg, "party-invite-received", "player", p.getName());
                sound(tg, "party-invite");
            }
            case "accept" -> {
                PInv inv = pinvites.remove(id);
                if (inv == null || System.currentTimeMillis() - inv.time() > 60_000) { send(p, "party-no-invite"); return; }
                if (pt != null) { send(p, "party-leave-first"); return; }
                Party target = inv.party();
                if (parties.get(target.leader) != target) { send(p, "party-gone"); return; }
                if (target.members.size() >= max) { send(p, "party-full"); return; }
                queue.remove(id);
                target.members.add(id);
                parties.put(id, target);
                pmsg(target, "party-joined", "player", p.getName());
                refresh(target.members);
            }
            case "leave" -> {
                if (pt == null) { send(p, "party-not-in"); return; }
                send(p, "party-left-self");
                partyLeave(p);
            }
            case "kick" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (a.length < 2) { send(p, "party-kick-usage"); return; }
                Player tg = Bukkit.getPlayerExact(a[1]);
                if (tg == null || tg.equals(p) || !pt.members.contains(tg.getUniqueId())) { send(p, "party-not-member"); return; }
                send(tg, "party-kicked");
                partyLeave(tg);
            }
            case "disband" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                List<UUID> aff = new ArrayList<>(pt.members);
                for (Player x : online(pt)) { send(x, "party-disbanded"); titleK(x, "party-disbanded"); }
                for (UUID m : aff) parties.remove(m);
                refresh(aff);
            }
            case "ffa" -> {
                if (pt == null || !pt.leader.equals(id)) { send(p, "party-leader-only"); return; }
                if (online(pt).size() < 2) { send(p, "party-ffa-min"); return; }
                openKitMenu(p, "ffa", null);
            }
            case "chat" -> {
                if (pt == null) { send(p, "party-not-in"); return; }
                if (a.length < 2) { send(p, "party-chat-usage"); return; }
                String text = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                Component c = MM.deserialize(t("party-chat-format", "player", p.getName())).append(Component.text(text));
                for (Player x : online(pt)) x.sendMessage(c);
            }
            default -> {
                if (pt == null) { send(p, "party-list-none"); return; }
                List<String> n = new ArrayList<>();
                for (Player x : online(pt)) n.add((x.getUniqueId().equals(pt.leader) ? "★" : "") + x.getName());
                send(p, "party-list", "size", String.valueOf(pt.members.size()), "max", String.valueOf(max),
                        "members", String.join(", ", n));
            }
        }
    }

    // ───────────────────────── commands ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) {
            if (c.getName().equalsIgnoreCase("duels") && a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                s.sendMessage("SimpleDuels config reloaded.");
            } else s.sendMessage("Players only.");
            return true;
        }
        String cn = c.getName().toLowerCase();
        if (List.of("setlobby", "arena", "kit", "world", "duels").contains(cn)
                && !p.isOp() && !p.hasPermission("duels.admin")) {
            msg(p, "<red>Admin only. <gray>Server says you are NOT op (isOp=false) and don't have duels.admin. "
                    + "Run <white>op " + p.getName() + " <gray>in the server console.");
            return true;
        }
        switch (cn) {
            case "duel" -> duel(p, a);
            case "spectate" -> spec(p, a);
            case "leave", "lobby" -> leave(p);
            case "party" -> party(p, a);
            case "settings" -> openSettings(p);
            case "kiteditor" -> kiteditor(p, a);
            case "world" -> world(p, a);
            case "setlobby" -> {
                data.set("lobby", p.getLocation());
                saveData();
                msg(p, "<green>Lobby set!");
            }
            case "arena" -> arena(p, a);
            case "kit" -> kit(p, a);
            case "duels" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    msg(p, "<green>Config reloaded.");
                } else msg(p, "<red>/duels reload");
            }
            default -> { }
        }
        return true;
    }

    void duel(Player p, String[] a) {
        if (a.length == 0) { send(p, "duel-usage"); return; }
        Party mp = partyOf(p);
        if (mp != null && !mp.leader.equals(p.getUniqueId())) { send(p, "duel-party-leader-only"); return; }
        long expire = getConfig().getLong("general.request-expire-seconds", 60) * 1000L;
        if (a[0].equalsIgnoreCase("accept")) {
            Req r = requests.remove(p.getUniqueId());
            if (r == null || System.currentTimeMillis() - r.time() > expire) { send(p, "request-none"); return; }
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) { send(p, "player-offline"); return; }
            List<Player> t1 = team(from), t2 = team(p);
            if (t1.size() != t2.size()) { send(p, "accept-size-mismatch"); return; }
            if (anyBusy(t1) || anyBusy(t2)) { send(p, "someone-busy"); return; }
            start(t1, t2, r.kit());
            return;
        }
        if (a[0].equalsIgnoreCase("deny")) {
            Req r = requests.remove(p.getUniqueId());
            if (r != null) {
                Player f = Bukkit.getPlayer(r.from());
                if (f != null) send(f, "request-declined", "player", p.getName());
            }
            send(p, "request-denied-self");
            return;
        }
        Player tg = Bukkit.getPlayerExact(a[0]);
        if (tg == null || tg.equals(p)) { send(p, "player-not-found"); return; }
        if (a.length > 1) sendRequest(p, tg, a[1].toLowerCase());
        else openKitMenu(p, "duel", tg.getUniqueId());
    }

    void sendRequest(Player p, Player tg, String kit) {
        if (!setting(tg, "requests")) { send(p, "requests-off"); return; }
        Party tp = partyOf(tg);
        if (tp != null && !tp.leader.equals(tg.getUniqueId())) {
            Player lead = Bukkit.getPlayer(tp.leader);
            send(p, "party-challenge-leader", "leader", lead != null ? lead.getName() : "?");
            return;
        }
        List<Player> mine = team(p), theirs = team(tg);
        if (mine.size() != theirs.size()) { send(p, "size-mismatch"); return; }
        if (anyBusy(mine) || anyBusy(theirs)) { send(p, "busy-any"); return; }
        if (kitBase(kit) == null) { send(p, "kit-not-found"); return; }
        requests.put(tg.getUniqueId(), new Req(p.getUniqueId(), kit, System.currentTimeMillis()));
        String size = mine.size() > 1 ? " <gray>(" + mine.size() + "v" + mine.size() + ")" : "";
        send(p, "request-sent", "player", tg.getName(), "kit", kit, "size", size);
        send(tg, "request-received", "player", p.getName(), "kit", kit, "size", size);
        sound(tg, "request");
    }

    void spec(Player p, String[] a) {
        if (a.length == 0) { send(p, "spectate-usage"); return; }
        UUID id = p.getUniqueId();
        if (matches.containsKey(id)) { send(p, "spec-while-dueling"); return; }
        Player tg = Bukkit.getPlayerExact(a[0]);
        Match m = tg == null ? null : matches.get(tg.getUniqueId());
        if (m == null) { send(p, "spec-not-dueling"); return; }
        queue.remove(id);
        Match old = watching.remove(id);
        if (old != null) old.specs.remove(id);
        m.specs.add(id);
        watching.put(id, m);
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(tg);
        send(p, "spectating", "player", tg.getName());
    }

    void arena(Player p, String[] a) {
        String help = "<red>/arena [create|spawn1|spawn2|corner1|corner2|ffa|ffaclear|tp|delete|list] [name]";
        if (a.length == 0) { msg(p, help); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("list")) {
            ConfigurationSection s = data.getConfigurationSection("arenas");
            if (s == null) { msg(p, "<yellow>Arenas: <white>none"); return; }
            List<String> out = new ArrayList<>();
            for (String n : s.getKeys(false)) {
                int f = ffaSpawns(n).size();
                out.add(n + (f > 0 ? " <gray>[ffa spawns: " + f + "]<white>" : ""));
            }
            msg(p, "<yellow>Arenas: <white>" + String.join(", ", out));
            return;
        }
        if (a.length < 2) { msg(p, "<red>Give an arena name."); return; }
        String n = a[1].toLowerCase().replace(".", "");
        String path = "arenas." + n;
        if (sub.equals("tp")) {   // teleport to an arena
            Location to = data.getLocation(path + ".spawn1");
            if (to == null) { List<Location> f = ffaSpawns(n); if (!f.isEmpty()) to = f.get(0); }
            if (to == null) { msg(p, "<red>Arena not found."); return; }
            p.teleport(to);
            msg(p, "<green>Teleported to arena <white>" + n);
            return;
        }
        switch (sub) {
            case "create" -> {
                data.set(path + ".spawn1", p.getLocation());
                data.set(path + ".spawn2", p.getLocation());
            }
            case "spawn1" -> data.set(path + ".spawn1", p.getLocation());
            case "spawn2" -> data.set(path + ".spawn2", p.getLocation());
            case "corner1" -> data.set(path + ".corner1", p.getLocation());
            case "corner2" -> data.set(path + ".corner2", p.getLocation());
            case "ffa" -> {
                List<Location> l = ffaSpawns(n);
                l.add(p.getLocation());
                data.set(path + ".ffa", l);
                msg(p, "<green>FFA spawn #" + l.size() + " added to <white>" + n + " <gray>(need at least 2)");
            }
            case "ffaclear" -> data.set(path + ".ffa", null);
            case "delete" -> data.set(path, null);
            default -> { msg(p, help); return; }
        }
        saveData();
        msg(p, "<green>Done.");
        if (sub.startsWith("corner")) {
            Location c1 = data.getLocation(path + ".corner1");
            Location c2 = data.getLocation(path + ".corner2");
            if (c1 != null && c2 != null && c1.getWorld() != null && c1.getWorld().equals(c2.getWorld())) {
                BoundingBox box = BoundingBox.of(c1.getBlock(), c2.getBlock());
                msg(p, "<gray>Arena region: <white>" + (int) box.getVolume() + " blocks <gray>(max "
                        + getConfig().getInt("general.max-arena-volume", 300000) + ")");
            }
        }
    }

    /** /world list | /world [name]  - teleport to any world on the server (to build arenas) */
    void world(Player p, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
            List<String> out = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) out.add(w.getName() + " <gray>(" + w.getPlayers().size() + ")<white>");
            msg(p, "<yellow>Worlds: <white>" + String.join(", ", out) + " <gray>| /world [name]");
            return;
        }
        if (busy(p)) { msg(p, "<red>Leave your duel first (/lobby)."); return; }
        World w = Bukkit.getWorld(a[0]);
        if (w == null) { msg(p, "<red>World not found. Use /world list"); return; }
        p.teleport(w.getSpawnLocation());
        msg(p, "<green>Teleported to world <white>" + w.getName());
    }

    void kit(Player p, String[] a) {
        if (a.length == 0) { msg(p, "<red>/kit [save|load|delete|icon|list] [name]"); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("list")) {
            ConfigurationSection s = data.getConfigurationSection("kits");
            msg(p, "<yellow>Kits: <white>" + (s == null ? "none" : String.join(", ", s.getKeys(false))));
            return;
        }
        if (a.length < 2) { msg(p, "<red>Give a kit name."); return; }
        String n = a[1].toLowerCase().replace(".", "");
        switch (sub) {
            case "save" -> {
                data.set("kits." + n + ".items", Arrays.asList(p.getInventory().getContents()));
                saveData();
                msg(p, "<green>Kit <white>" + n + " <green>saved from your inventory (armor + offhand included).");
            }
            case "load" -> giveKit(p, n);
            case "delete" -> {
                data.set("kits." + n, null);
                saveData();
                msg(p, "<green>Kit deleted.");
            }
            case "icon" -> {
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand.isAir() || kitBase(n) == null) { msg(p, "<red>Hold an item and use an existing kit name."); return; }
                data.set("kits." + n + ".icon", hand.name());
                saveData();
                msg(p, "<green>Menu icon for <white>" + n + " <green>set to <white>" + hand.name());
            }
            default -> msg(p, "<red>/kit [save|load|delete|icon|list] [name]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String name = c.getName().toLowerCase();
        if (a.length == 1) {
            switch (name) {
                case "duel" -> { out.addAll(List.of("accept", "deny")); Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName())); }
                case "spectate" -> matches.values().forEach(m -> m.all().forEach(p -> out.add(p.getName())));
                case "arena" -> out.addAll(List.of("create", "spawn1", "spawn2", "corner1", "corner2", "ffa", "ffaclear", "tp", "delete", "list"));
                case "kit" -> out.addAll(List.of("save", "load", "delete", "icon", "list"));
                case "party" -> out.addAll(List.of("create", "invite", "accept", "leave", "kick", "disband", "list", "chat", "ffa"));
                case "kiteditor" -> out.addAll(List.of("save", "reset", "cancel"));
                case "duels" -> out.add("reload");
                case "world" -> { out.add("list"); Bukkit.getWorlds().forEach(w -> out.add(w.getName())); }
                default -> { }
            }
        } else if (a.length == 2) {
            switch (name) {
                case "duel", "kit" -> {
                    ConfigurationSection k = data.getConfigurationSection("kits");
                    if (k != null) out.addAll(k.getKeys(false));
                }
                case "arena" -> {
                    ConfigurationSection k = data.getConfigurationSection("arenas");
                    if (k != null) out.addAll(k.getKeys(false));
                }
                case "party" -> {
                    if (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("kick"))
                        Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
                default -> { }
            }
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    // ───────────────────────── scoreboard + tab ─────────────────────────

    static final int LINES = 15;

    Scoreboard board(Player p) {
        return boards.computeIfAbsent(p.getUniqueId(), k -> {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = sb.registerNewObjective("duels", Criteria.DUMMY, MM.deserialize("DUELS"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());   // hide the red 1..15 numbers
            for (int i = 0; i < LINES; i++) {
                sb.registerNewTeam("l" + i).addEntry("§" + Integer.toHexString(i) + "§r");
            }
            Team nt = sb.registerNewTeam("nt");
            nt.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            p.setScoreboard(sb);
            return sb;
        });
    }

    int aliveCount(Match m, List<Player> team) {
        int n = 0;
        for (Player x : team) if (m.alive.contains(x.getUniqueId())) n++;
        return n;
    }

    /** Replaces {vars}; returns null when the line uses a var that doesn't apply. */
    String subst(String line, Map<String, String> v) {
        Matcher mt = PH.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String val = v.get(mt.group(1));
            if (val == null) return null;
            mt.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    List<String> sbLines(Player p) {
        UUID id = p.getUniqueId();
        Match m = matches.get(id);
        Match w = watching.get(id);
        Map<String, String> v = new HashMap<>();
        v.put("footer", getConfig().getString("general.footer", ""));
        v.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        v.put("fighting", String.valueOf(matches.size()));
        v.put("ping", String.valueOf(p.getPing()));
        String path;
        if (m != null) {
            long s = m.state == State.ACTIVE ? (System.currentTimeMillis() - m.start) / 1000 : 0;
            v.put("time", clock(s));
            v.put("kit", m.kit);
            if (m.ffa) {
                path = "scoreboard.ffa";
                v.put("alive", String.valueOf(m.alive.size()));
                v.put("total", String.valueOf(m.t1.size()));
            } else {
                List<Player> en = m.enemy(p);
                v.put("opponent", label(en));
                if (m.t1.size() > 1) {
                    path = "scoreboard.team";
                    v.put("team_alive", String.valueOf(aliveCount(m, m.team(p))));
                    v.put("enemy_alive", String.valueOf(aliveCount(m, en)));
                } else {
                    path = "scoreboard.duel";
                    v.put("you_ping", String.valueOf(p.getPing()));
                    v.put("them_ping", String.valueOf(en.get(0).getPing()));
                }
            }
        } else if (w != null) {
            path = "scoreboard.spectate";
            v.put("kit", w.kit);
            v.put("alive", String.valueOf(w.alive.size()));
            v.put("players", w.ffa ? "FFA (" + w.alive.size() + " alive)" : label(w.t1) + " vs " + label(w.t2));
        } else {
            path = "scoreboard.lobby";
            Party pt = parties.get(id);
            if (pt != null) v.put("party", String.valueOf(pt.members.size()));
            String q = queue.get(id);
            if (q != null) v.put("queue", q);
        }
        List<String> out = new ArrayList<>();
        for (String line : getConfig().getStringList(path)) {
            String r = subst(line, v);
            if (r != null) out.add(r);
        }
        return out;
    }

    void update(Player p) {
        UUID id = p.getUniqueId();
        Match m = matches.get(id);
        Match w = watching.get(id);
        Scoreboard sb = board(p);
        Objective obj = sb.getObjective("duels");
        if (obj != null) {
            obj.displayName(MM.deserialize(getConfig().getString("scoreboard.title", "DUELS")));
            obj.setDisplaySlot(setting(p, "scoreboard") ? DisplaySlot.SIDEBAR : null);
            List<String> L = sbLines(p);
            for (int i = 0; i < LINES; i++) {
                String entry = "§" + Integer.toHexString(i) + "§r";
                Team tm = sb.getTeam("l" + i);
                if (tm == null) continue;
                if (i < L.size()) {
                    tm.prefix(MM.deserialize(L.get(i)));
                    obj.getScore(entry).setScore(Math.min(L.size(), LINES) - i);
                } else {
                    sb.resetScores(entry);
                }
            }
        }

        // nametags above heads (per viewer)
        Team nt = sb.getTeam("nt");
        if (nt != null) {
            boolean show = setting(p, "nametags");
            for (String en : new ArrayList<>(nt.getEntries())) {
                if (show || Bukkit.getPlayerExact(en) == null || en.equals(p.getName())) nt.removeEntry(en);
            }
            if (!show) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!o.equals(p) && !nt.hasEntry(o.getName())) nt.addEntry(o.getName());
                }
            }
        }
        visibility(p);

        // TAB
        String online = String.valueOf(Bukkit.getOnlinePlayers().size()), ping = String.valueOf(p.getPing());
        List<String> hl = new ArrayList<>(), fl = new ArrayList<>();
        for (String l : getConfig().getStringList("tab.header")) hl.add(fmt(l, "online", online, "ping", ping));
        for (String l : getConfig().getStringList("tab.footer")) fl.add(fmt(l, "online", online, "ping", ping));
        p.sendPlayerListHeaderAndFooter(MM.deserialize(String.join("<newline>", hl)),
                MM.deserialize(String.join("<newline>", fl)));
        String key = m != null ? "tab.name-fighting" : w != null ? "tab.name-spectating" : "tab.name-lobby";
        p.playerListName(MM.deserialize(fmt(getConfig().getString(key, "{player}"), "player", p.getName())));
    }

    // ───────────────────────── events ─────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { toLobby(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        queue.remove(id);
        editing.remove(id);
        Match m = matches.get(id);
        if (m != null) {
            m.left.add(id);
            eliminate(m, p, true);
            matches.remove(id);
        }
        Match w = watching.remove(id);
        if (w != null) w.specs.remove(id);
        partyLeave(p);
        pinvites.remove(id);
        boards.remove(id);
        requests.remove(id);
    }

    // lobby hotbar items (identified by a hidden tag, not by material)
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (busy(p) || editing.containsKey(p.getUniqueId())) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String act = im.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (act == null) return;
        e.setCancelled(true);
        sound(p, "click");
        switch (act) {
            case "queue" -> toggleQueue(p);
            case "party" -> party(p, new String[]{"create"});
            case "ffa" -> party(p, new String[]{"ffa"});
            case "disband" -> party(p, new String[]{"disband"});
            case "leaveparty" -> party(p, new String[]{"leave"});
            case "editor" -> openKitMenu(p, "editor", null);
            case "settings" -> openSettings(p);
            default -> { }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof KitMenu h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            String kit = h.slots.get(e.getSlot());
            if (kit == null) return;
            p.closeInventory();
            choose(p, h, kit);
            return;
        }
        if (e.getView().getTopInventory().getHolder() instanceof SettingsMenu) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            for (int i = 0; i < SETTING_SLOTS.length; i++) {
                if (e.getSlot() == SETTING_SLOTS[i]) {
                    String key = SETTING_KEYS[i];
                    data.set("settings." + p.getUniqueId() + "." + key, !setting(p, key));
                    saveData();
                    sound(p, "click");
                    fillSettings(p, e.getView().getTopInventory());
                    update(p);
                }
            }
            return;
        }
        if (!matches.containsKey(p.getUniqueId()) && !editing.containsKey(p.getUniqueId()) && !admin(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder hd = e.getView().getTopInventory().getHolder();
        if (hd instanceof KitMenu || hd instanceof SettingsMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!matches.containsKey(e.getPlayer().getUniqueId()) && !admin(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Match m = matches.get(e.getPlayer().getUniqueId());
        if (m != null && m.state == State.COUNTDOWN && e.hasChangedPosition()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player d = null;
        if (e.getDamager() instanceof Player pl) d = pl;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2) d = pl2;
        if (d == null) return;
        Match m = matches.get(v.getUniqueId());
        if (m != null && !m.ffa && m.team(v) == m.team(d)) e.setCancelled(true);
    }

    boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = matches.get(p.getUniqueId());
        if (m == null) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) p.teleport(lobby());
            return;
        }
        if (m.state != State.ACTIVE || !m.alive.contains(p.getUniqueId())) { e.setCancelled(true); return; }
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            // totem in hand -> let vanilla pop it (void / kill still kill)
            if (getConfig().getBoolean("general.totems-pop", true) && hasTotem(p)
                    && e.getCause() != EntityDamageEvent.DamageCause.VOID
                    && e.getCause() != EntityDamageEvent.DamageCause.KILL) return;
            e.setCancelled(true);
            eliminate(m, p, false);
        }
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!getConfig().getBoolean("general.wind-charge-reset", true)) return;
        if (e.getEntity() instanceof WindCharge wc && wc.getShooter() instanceof Player p
                && matches.containsKey(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> p.setCooldown(Material.WIND_CHARGE, 0));
        }
    }

    boolean admin(Player p) { return p.isOp() && p.getGameMode() == GameMode.CREATIVE; }

    boolean canBuild(Player p, Block b) {
        if (admin(p)) return true;
        Match m = matches.get(p.getUniqueId());
        return m != null && m.state == State.ACTIVE && m.alive.contains(p.getUniqueId()) && inBox(m, b);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onBucketEmpty(PlayerBucketEmptyEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }
    @EventHandler public void onBucketFill(PlayerBucketFillEvent e) { if (!canBuild(e.getPlayer(), e.getBlock())) e.setCancelled(true); }

    @EventHandler public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> !inAnyRegion(b)); }
    @EventHandler public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> !inAnyRegion(b)); }

    @EventHandler
    public void onFlow(BlockFromToEvent e) {
        if (activeMatches.isEmpty()) return;
        for (Match m : activeMatches) {
            if (inBox(m, e.getBlock()) && !inBox(m, e.getToBlock())) { e.setCancelled(true); return; }
        }
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!admin(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onFood(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !admin(p)) e.setCancelled(true);
    }
}
