package me.Evan52098.genscore.pets;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PetsModule implements GensModule, Listener {

    public static final String USE = "genscore.pets";
    public static final String ADMIN = "genscore.pets.admin";

    private static volatile @Nullable PetsModule instance;

    private GensCore plugin;
    private File file;
    private volatile PetsSettings settings;
    private PlayerDataService players;
    private final PetData.Section section = new PetData.Section();
    private PetFollower follower;
    private PetsMenu menus;
    private PetShowcase showcase;

    @Override
    public String id() {
        return "pets";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.players = plugin.playerData();
        File folder = new File(plugin.getDataFolder(), "modules/" + id());
        this.file = new File(folder, "pets.yml");
        if (!file.isFile()) {
            plugin.saveResource("modules/pets/pets.yml", false);
        }
        this.settings = PetsSettings.load(file, plugin.getLogger());
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_pets (uuid TEXT NOT NULL, pet_id TEXT NOT NULL, type TEXT NOT NULL, "
                        + "level INTEGER NOT NULL DEFAULT 1, xp INTEGER NOT NULL DEFAULT 0, golden INTEGER NOT NULL DEFAULT 0, "
                        + "equipped INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, pet_id));"
                        + "CREATE TABLE IF NOT EXISTS gens_pet_eggs (uuid TEXT PRIMARY KEY, common INTEGER NOT NULL DEFAULT 0, "
                        + "rare INTEGER NOT NULL DEFAULT 0, epic INTEGER NOT NULL DEFAULT 0, legendary INTEGER NOT NULL DEFAULT 0, "
                        + "hidden INTEGER NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS gens_pet_pending (" + plugin.database().autoIdColumn("id") + ", "
                        + "uuid TEXT NOT NULL, rarity INTEGER NOT NULL, amount INTEGER NOT NULL, created_at BIGINT NOT NULL)");
        players.register(section);
        this.follower = new PetFollower(this::icons);
        this.menus = new PetsMenu(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(this);
        Tasks.async.timer(t -> tickXp(), 60, 60, TimeUnit.SECONDS);
        this.showcase = new PetShowcase(this, plugin, file);
        showcase.start();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> follower.start(p));
        }
        instance = this;
    }

    @Override
    public void reload() {
        settings = PetsSettings.load(file, plugin.getLogger());
        for (Player p : Bukkit.getOnlinePlayers()) {
            PetData d = data(p.getUniqueId());
            if (d != null) {
                d.recompute(settings);
            }
        }
        if (showcase != null) {
            showcase.reload();
        }
    }

    @Override
    public void disable() {
        if (showcase != null) {
            showcase.stop();
        }
        instance = null;
        if (follower != null) {
            follower.stopAll();
        }
    }

    PetsSettings settings() {
        return settings;
    }

    @Nullable PetData data(UUID uuid) {
        PlayerData d = players.get(uuid);
        if (d == null) {
            return null;
        }
        PetData pets = d.get(section);
        if (pets != null && pets.bonus().isEmpty() && pets.equippedCount() > 0) {
            pets.recompute(settings);
        }
        return pets;
    }

    public static double bonus(UUID player, String key) {
        PetsModule m = instance;
        if (m == null || player == null) {
            return 0;
        }
        PlayerData d = m.players.get(player);
        if (d == null) {
            return 0;
        }
        PetData pets = d.get(m.section);
        if (pets == null) {
            return 0;
        }
        Double v = pets.bonus().get(key);
        return v == null ? 0 : v;
    }

    public static @Nullable String cacheEgg(UUID player, int cacheRarity) {
        PetsModule m = instance;
        if (m == null || cacheRarity < 0 || cacheRarity > 3) {
            return null;
        }
        PetsSettings s = m.settings;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= s.cacheEggChance()[cacheRarity]) {
            return null;
        }
        int rarity = cacheRarity < 3 && r.nextDouble() < s.eggUpgradeChance() ? cacheRarity + 1 : cacheRarity;
        PetData d = m.data(player);
        if (d == null) {
            return null;
        }
        d.addEggs(rarity, 1);
        return "<light_purple>+1 " + PetsSettings.RARITY_NAMES[rarity] + " pet egg</light_purple>";
    }

    public static boolean giveEggs(UUID player, int rarity, int amount, String reason) {
        PetsModule m = instance;
        if (m == null || rarity < 0 || rarity > 3 || amount <= 0 || amount > 1000) {
            return false;
        }
        PetData d = m.data(player);
        if (d != null) {
            d.addEggs(rarity, amount);
            return true;
        }
        m.plugin.database().async(c -> {
            try (var ps = c.prepareStatement("INSERT INTO gens_pet_pending (uuid, rarity, amount, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setInt(2, rarity);
                ps.setInt(3, amount);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, e) -> {
            if (e != null) {
                m.plugin.getLogger().log(Level.WARNING, "[pets] Could not save " + amount + " egg(s) for " + player + " (" + reason + ")", e);
            }
        });
        return true;
    }

    int slots(Player p) {
        PetsSettings s = settings;
        int slots = s.defaultSlots();
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (info.getValue() && node.startsWith("genscore.pets.slots.")) {
                try {
                    slots = Math.max(slots, Integer.parseInt(node.substring("genscore.pets.slots.".length())));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
        }
        return Math.min(s.maxSlots(), slots);
    }

    private List<Material> icons(Player p) {
        PetData d = data(p.getUniqueId());
        if (d == null || d.hidden()) {
            return List.of();
        }
        List<Material> out = new ArrayList<>();
        for (PetData.Pet pet : d.pets()) {
            PetsSettings.PetDef def = pet.equipped ? settings.pet(pet.type) : null;
            if (def != null) {
                out.add(def.icon());
            }
        }
        return out;
    }

    private void tickXp() {
        PetsSettings s = settings;
        if (s.xpPerMinute() <= 0) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            PetData d = data(p.getUniqueId());
            if (d == null) {
                continue;
            }
            List<String> levelled = d.tick(s.xpPerMinute(), s);
            if (levelled.isEmpty()) {
                continue;
            }
            d.recompute(s);
            Tasks.onEntity(p, () -> {
                for (String id : levelled) {
                    PetData.Pet pet = d.pet(id);
                    PetsSettings.PetDef def = pet == null ? null : s.pet(pet.type);
                    if (def != null) {
                        p.sendMessage(Msg.good(Msg.white(def.name()) + " reached level " + Msg.white(String.valueOf(pet.level)) + "!"));
                        p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
                    }
                }
            });
        }
    }

    boolean spendTokens(UUID uuid, long amount) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        GensPlayer g = plots == null ? null : plots.gensPlayer(uuid);
        if (g == null || !g.takeTokens(amount)) {
            return false;
        }
        plots.saveSoon(uuid);
        return true;
    }

    long credits(UUID uuid) {
        ShardsService s = ShardsModule.service();
        return s == null ? 0 : s.balance(uuid);
    }

    boolean spendCredits(UUID uuid, long amount, String reason) {
        ShardsService s = ShardsModule.service();
        return s != null && amount > 0 && s.withdraw(uuid, amount, reason);
    }

    void giveTokens(UUID uuid, long amount) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        GensPlayer g = plots == null ? null : plots.gensPlayer(uuid);
        if (g != null && amount > 0) {
            g.addTokens(amount);
            plots.saveSoon(uuid);
        }
    }

    long tokens(UUID uuid) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        GensPlayer g = plots == null ? null : plots.gensPlayer(uuid);
        return g == null ? 0 : g.tokens();
    }

    void announce(Component line) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Tasks.entity.later(p, () -> {
            PetData d = data(p.getUniqueId());
            if (d != null) {
                d.recompute(settings);
            }
            follower.start(p);
        }, 20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        follower.stop(event.getPlayer().getUniqueId());
    }

    @Command({"pets", "pet"})
    @CommandPermission(USE)
    @Description("Your pets")
    public void pets(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        menus.open(p, 0);
    }

    @Command({"pets shop", "pet shop"})
    @CommandPermission(USE)
    @Description("Buy pet eggs with credits")
    public void shop(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        menus.shop(p, 0);
    }

    @Command("pets showcase here")
    @CommandPermission(ADMIN)
    @Description("Move the hub pet display in front of you")
    public void showcaseHere(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Location loc = p.getLocation();
        double yaw = Math.toRadians(loc.getYaw());
        Location at = new Location(loc.getWorld(), loc.getX() - Math.sin(yaw) * 3, loc.getBlockY(), loc.getZ() + Math.cos(yaw) * 3,
                loc.getYaw() + 180f, 0f);
        p.sendMessage(showcase.moveTo(at) ? Msg.good("Pet display moved in front of you.") : Msg.bad("Could not save pets.yml."));
    }

    @Command({"pets hide", "pet hide"})
    @CommandPermission(USE)
    @Description("Show or hide your floating pets")
    public void hide(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        PetData d = data(p.getUniqueId());
        if (d == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        d.setHidden(!d.hidden());
        p.sendMessage(Msg.neutral("Your pets are now " + Msg.white(d.hidden() ? "hidden" : "shown") + ". Their boosts still work."));
    }

    @Command("pets egg give")
    @CommandPermission(ADMIN)
    @Description("Give pet eggs (crates and the store run this)")
    public void eggGive(BukkitCommandActor actor, @Named("player") String name,
                        @Named("rarity") @SuggestWith(Rarities.class) String rarityName,
                        @Named("amount") @Optional Integer amount) {
        int rarity = PetsSettings.rarity(rarityName);
        int n = amount == null ? 1 : amount;
        if (rarity < 0) {
            actor.sender().sendMessage(Msg.bad("Rarity must be common, rare, epic or legendary."));
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(name).getUniqueId();
        if (!giveEggs(uuid, rarity, n, "command by " + actor.sender().getName())) {
            actor.sender().sendMessage(Msg.bad("Could not give eggs (amount 1-1000)."));
            return;
        }
        actor.sender().sendMessage(Msg.good("Gave " + n + " " + PetsSettings.RARITY_NAMES[rarity] + " pet egg(s) to " + name
                + (online == null ? " (delivered when they next join)." : ".")));
        if (online != null) {
            Tasks.onEntity(online, () -> online.sendMessage(Msg.good("You got " + Msg.white(n + " " + PetsSettings.RARITY_NAMES[rarity]
                    + " pet egg" + (n == 1 ? "" : "s")) + "! Hatch with " + Msg.white("/pets") + ".")));
        }
    }

    @Command("pets give")
    @CommandPermission(ADMIN)
    @Description("Give a pet")
    public void petGive(BukkitCommandActor actor, @Named("player") Player target, @Named("pet") @SuggestWith(PetIds.class) String type) {
        PetsSettings.PetDef def = settings.pet(type.toLowerCase(Locale.ROOT));
        PetData d = data(target.getUniqueId());
        if (def == null || d == null) {
            actor.sender().sendMessage(Msg.bad(def == null ? "Unknown pet." : "Their data is still loading."));
            return;
        }
        if (d.count() >= settings.storage()) {
            actor.sender().sendMessage(Msg.bad("Their pet storage is full."));
            return;
        }
        d.add(def.id());
        actor.sender().sendMessage(Msg.good("Gave " + def.name() + " to " + target.getName() + "."));
    }

    @Command("pets reload")
    @CommandPermission(ADMIN)
    @Description("Reload pets.yml")
    public void reloadCommand(BukkitCommandActor actor) {
        reload();
        actor.sender().sendMessage(Msg.good("Pets reloaded: " + settings.pets().size() + " pets."));
    }

    public static final class Rarities implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            return List.of(PetsSettings.RARITIES);
        }
    }

    public static final class PetIds implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            PetsModule m = instance;
            return m == null ? List.of() : List.copyOf(m.settings.pets().keySet());
        }
    }

    static Map<String, Double> bonuses(PetData d) {
        return d.bonus();
    }
}
