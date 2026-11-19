package com.gt6.mission;

import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class GT6Mission extends JavaPlugin implements Listener {

    enum Stage { CARGO, DELIVER, GUARDS, FREE, ESCAPE }
    static final String CRATE = "§6Tobacco Crate";
    static final int TOTAL_CRATES = 6;
    static final List<String> KEYS = List.of("start", "dealer", "hideout", "escape");

    static class Session {
        Player player; Stage stage = Stage.CARGO; int cargo = 0;
        Villager dealer; Phantom plane; Entity boat;
        List<Mob> guards = new ArrayList<>();
        BossBar bar; BukkitTask task;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("mission").setExecutor(this);
        getCommand("mission").setTabCompleter((s, c, a, args) -> args.length == 1
                ? List.of("start", "stop", "set") : args.length == 2 && args[0].equalsIgnoreCase("set") ? KEYS : List.of());
    }

    @Override public void onDisable() { new ArrayList<>(sessions.values()).forEach(s -> end(s, false, "Server stopping.")); }

    private Location loc(String k) { return getConfig().getLocation("locations." + k); }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (a.length == 0) { p.sendMessage("§e/mission <start|stop|set>"); return true; }
        switch (a[0].toLowerCase()) {
            case "set" -> {
                if (!p.hasPermission("gt6.admin")) { p.sendMessage("§cNo permission."); return true; }
                if (a.length < 2 || !KEYS.contains(a[1].toLowerCase())) { p.sendMessage("§cUse: start, dealer, hideout, escape"); return true; }
                getConfig().set("locations." + a[1].toLowerCase(), p.getLocation());
                saveConfig();
                p.sendMessage("§aSaved location: " + a[1]);
            }
            case "start" -> {
                if (sessions.containsKey(p.getUniqueId())) { p.sendMessage("§cMission already running."); return true; }
                for (String k : KEYS) if (loc(k) == null) { p.sendMessage("§cLocation not set: " + k); return true; }
                start(p);
            }
            case "stop" -> { Session s = sessions.get(p.getUniqueId()); if (s != null) end(s, false, "Mission cancelled."); }
            default -> p.sendMessage("§e/mission <start|stop|set>");
        }
        return true;
    }

    // ---------- Mission flow ----------
    private void start(Player p) {
        Session s = new Session();
        s.player = p;
        sessions.put(p.getUniqueId(), s);
        s.bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
        s.bar.addPlayer(p);
        setObjective(s, "Get in the boat and wait for the plane");

        Location start = loc("start");
        s.boat = start.getWorld().spawnEntity(start, EntityType.OAK_BOAT);
        p.teleport(start.clone().add(0, 1, 0));
        p.sendMessage("§e[Mission] §fA cargo plane will drop the tobacco soon. Be ready on the boat!");

        Bukkit.getScheduler().runTaskLater(this, () -> { if (sessions.get(p.getUniqueId()) == s) launchPlane(s); }, 100L);

        s.task = new BukkitRunnable() { public void run() { tick(s); } }.runTaskTimer(this, 10L, 10L);
    }

    private void launchPlane(Session s) {
        Location start = loc("start");
        Location from = start.clone().add(-70, 45, 0);
        Phantom plane = (Phantom) start.getWorld().spawnEntity(from, EntityType.PHANTOM);
        plane.setCustomName("§7Cargo Plane");
        plane.setCustomNameVisible(true);
        plane.setAI(false); plane.setGravity(false); plane.setSilent(true);
        plane.setInvulnerable(true); plane.setShouldBurnInDay(false);
        s.plane = plane;
        s.player.sendMessage("§e[Mission] §fPlane incoming! Collect the crates in the water.");
        new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (!plane.isValid() || sessions.get(s.player.getUniqueId()) != s) { plane.remove(); cancel(); return; }
                plane.teleport(plane.getLocation().add(1, 0, 0));
                t++;
                if (t >= 30 && (t - 30) % 20 == 0 && (t - 30) / 20 < TOTAL_CRATES) {
                    ItemStack crate = new ItemStack(Material.BARREL);
                    ItemMeta m = crate.getItemMeta(); m.setDisplayName(CRATE); crate.setItemMeta(m);
                    Item it = plane.getWorld().dropItem(plane.getLocation(), crate);
                    it.setGlowing(true); it.setUnlimitedLifetime(true);
                    it.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    plane.getWorld().playSound(plane.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 2f, 0.6f);
                }
                if (t > 170) { plane.remove(); cancel(); }
            }
        }.runTaskTimer(this, 0L, 1L);
        setObjective(s, "Collect the crates (" + s.cargo + "/" + TOTAL_CRATES + ")");
    }

    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.stage != Stage.CARGO) return;
        ItemMeta m = e.getItem().getItemStack().getItemMeta();
        if (m == null || !CRATE.equals(m.getDisplayName())) return;
        s.cargo += e.getItem().getItemStack().getAmount();
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        if (s.cargo >= TOTAL_CRATES) {
            s.stage = Stage.DELIVER;
            spawnDealer(s);
            p.sendMessage("§e[Mission] §fAll crates collected! Take them to the dealer.");
            setObjective(s, "Deliver the cargo to the Dealer");
            p.setCompassTarget(loc("dealer"));
        } else setObjective(s, "Collect the crates (" + s.cargo + "/" + TOTAL_CRATES + ")");
    }

    private void spawnDealer(Session s) {
        Location l = loc("dealer");
        Villager v = (Villager) l.getWorld().spawnEntity(l, EntityType.VILLAGER);
        v.setCustomName("§aDealer"); v.setCustomNameVisible(true);
        v.setAware(false); v.setRemoveWhenFarAway(false); v.setPersistent(true);
        s.dealer = v;
    }

    private void tick(Session s) {
        Player p = s.player;
        if (!p.isOnline()) { end(s, false, "Player left."); return; }
        switch (s.stage) {
            case DELIVER -> {
                if (near(p, s.dealer.getLocation(), 6)) {
                    removeCrates(p);
                    p.sendMessage("§e[Mission] §fThe buyer shows up... and grabs the dealer! He's being taken away!");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1f, 0.7f);
                    Location h = loc("hideout");
                    s.dealer.teleport(h);
                    s.dealer.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 20 * 600, 0));
                    spawnGuards(s, h);
                    s.stage = Stage.GUARDS;
                    p.setCompassTarget(h);
                    setObjective(s, "Go to the hideout and kill the guards");
                }
            }
            case GUARDS -> {
                for (Mob g : s.guards) if (g.isValid() && g.getTarget() == null && near(p, g.getLocation(), 30)) g.setTarget(p);
                s.guards.removeIf(g -> !g.isValid());
                if (s.guards.isEmpty()) {
                    s.stage = Stage.FREE;
                    p.sendMessage("§e[Mission] §fGuards down! Right-click the dealer to free him.");
                    setObjective(s, "Free the Dealer (right-click)");
                }
            }
            case ESCAPE -> {
                if (s.dealer == null || !s.dealer.isValid()) { end(s, false, "The dealer died."); return; }
                double d = s.dealer.getLocation().distance(p.getLocation());
                if (d > 40) s.dealer.teleport(p.getLocation());
                else if (d > 3) s.dealer.getPathfinder().moveTo(p, 1.3);
                Location esc = loc("escape");
                if (near(p, esc, 7) && near(s.dealer, esc, 9)) complete(s);
            }
            default -> {}
        }
    }

    @EventHandler public void onFree(PlayerInteractEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.stage != Stage.FREE || !e.getRightClicked().equals(s.dealer)) return;
        e.setCancelled(true);
        s.dealer.setAware(true);
        s.dealer.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
        s.stage = Stage.ESCAPE;
        s.player.sendMessage("§e[Mission] §fDealer freed! Get him to the escape point (boat/plane)!");
        s.player.setCompassTarget(loc("escape"));
        setObjective(s, "Escape with the Dealer");
    }

    private void spawnGuards(Session s, Location h) {
        int n = getConfig().getInt("guards", 4);
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            Location l = h.clone().add(Math.cos(ang) * 4, 0, Math.sin(ang) * 4);
            Vindicator g = (Vindicator) h.getWorld().spawnEntity(l, EntityType.VINDICATOR);
            g.setCustomName("§cKidnapper"); g.setCustomNameVisible(true);
            g.setRemoveWhenFarAway(false); g.setCanJoinRaid(false);
            s.guards.add(g);
        }
    }

    private void removeCrates(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            if (CRATE.equals(it.getItemMeta().getDisplayName())) p.getInventory().remove(it);
        }
    }

    private void complete(Session s) {
        s.player.sendTitle("§aMISSION PASSED", "Dealer rescued!", 10, 70, 20);
        s.player.playSound(s.player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        for (String c : getConfig().getStringList("rewards"))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", s.player.getName()));
        end(s, true, null);
    }

    private void end(Session s, boolean success, String msg) {
        sessions.remove(s.player.getUniqueId());
        if (s.task != null) s.task.cancel();
        if (s.bar != null) s.bar.removeAll();
        if (s.dealer != null) s.dealer.remove();
        if (s.plane != null) s.plane.remove();
        if (s.boat != null && !success) s.boat.remove();
        s.guards.forEach(Entity::remove);
        if (s.player.isOnline()) {
            removeCrates(s.player);
            if (!success && msg != null) s.player.sendMessage("§c[Mission failed] §f" + msg);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Session s = sessions.get(e.getEntity().getUniqueId());
        if (s != null) end(s, false, "You died.");
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) end(s, false, "Left the game.");
    }
    @EventHandler public void onDealerDeath(EntityDeathEvent e) {
        for (Session s : new ArrayList<>(sessions.values()))
            if (e.getEntity().equals(s.dealer)) end(s, false, "The dealer was killed.");
    }

    private void setObjective(Session s, String text) { s.bar.setTitle("§eObjective: §f" + text); }
    private boolean near(Entity e, Location l, double r) { return e.getWorld().equals(l.getWorld()) && e.getLocation().distanceSquared(l) <= r * r; }
    private boolean near(Player p, Location l, double r) { return near((Entity) p, l, r); }
}
