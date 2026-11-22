package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class RaidTools implements Listener {

    enum Tool {
        SPEED("speed_coil", "&b&lSpeed Coil", Material.RABBIT_FOOT, 15, 20, "Speed III for 8 seconds.", "Get in and out fast."),
        GRAPPLE("grapple", "&6&lGrapple Hook", Material.LEAD, 15, 6, "Launch yourself the way", "you are looking."),
        CLOAK("cloak", "&8&lShadow Cloak", Material.PHANTOM_MEMBRANE, 40, 45, "Turn invisible for 8 seconds.", "Turrets can't see you."),
        EMP("emp", "&3&lEMP Blast", Material.ECHO_SHARD, 60, 60, "Knock out the turrets and", "traps of the factory you", "are in for 12 seconds."),
        WEB("web_bomb", "&f&lWeb Bomb", Material.COBWEB, 30, 25, "Everyone within 7 blocks is", "stuck in webs for 4 seconds."),
        STUN("stun_gun", "&e&lStun Gun", Material.LIGHTNING_ROD, 25, 20, "Zap the player you aim at.", "They freeze for 2.5 seconds.");

        final String id;
        final String name;
        final Material icon;
        final int price;
        final int cooldown;
        final String[] lore;

        Tool(String id, String name, Material icon, int price, int cooldown, String... lore) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.price = price;
            this.cooldown = cooldown;
            this.lore = lore;
        }

        static @Nullable Tool of(@Nullable String id) {
            for (Tool t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    static final int ARMOR_MAX = 10;
    private static final String[] ARMOR_TIERS = {"None", "Leather", "Leather", "Chain", "Chain", "Iron", "Iron", "Diamond", "Diamond", "Netherite", "Netherite"};
    private static final Material[] ARMOR_ICONS = {Material.LEATHER_CHESTPLATE, Material.LEATHER_CHESTPLATE, Material.LEATHER_CHESTPLATE,
            Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.IRON_CHESTPLATE, Material.IRON_CHESTPLATE,
            Material.DIAMOND_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_CHESTPLATE};

    private final FactoryModule module;
    private final NamespacedKey toolKey;
    private final NamespacedKey armorKey;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cloakUntil = new ConcurrentHashMap<>();

    RaidTools(FactoryModule module, org.bukkit.plugin.Plugin plugin) {
        this.module = module;
        this.toolKey = new NamespacedKey(plugin, "raid_tool");
        this.armorKey = new NamespacedKey(plugin, "raid_armor");
    }

    ItemStack item(Tool t, int amount) {
        ItemStack item = Lore.item(t.icon, t.name, Lore.builder(Lore.Accent.WHITE).description(t.lore).divider()
                .stat("Use", "right-click inside an enemy factory")
                .stat("Cooldown", t.cooldown + "s").build());
        item.setAmount(Math.max(1, Math.min(64, amount)));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, t.id));
        return item;
    }

    @Nullable Tool toolOf(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return Tool.of(item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING));
    }

    boolean cloaked(UUID id) {
        Long until = cloakUntil.get(id);
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            cloakUntil.remove(id);
            return false;
        }
        return true;
    }

    int armorLevel(Player p) {
        return Math.min(ARMOR_MAX, p.getPersistentDataContainer().getOrDefault(armorKey, PersistentDataType.INTEGER, 0));
    }

    double turretFactor(Player p) {
        return 1 - 0.05 * armorLevel(p);
    }

    int trapCut(Player p) {
        return armorLevel(p) / 4;
    }

    boolean noShove(Player p) {
        return armorLevel(p) >= 6;
    }

    static long armorPrice(int level) {
        return level >= ARMOR_MAX ? -1 : Math.round(5000 * Math.pow(1.75, level));
    }

    static String armorPerks(int level) {
        if (level <= 0) {
            return "none";
        }
        String s = "-" + (level * 5) + "% turret damage";
        if (level / 4 > 0) {
            s += ", traps " + (level / 4) + " weaker";
        }
        if (level >= 6) {
            s += ", traps can't throw you";
        }
        return s;
    }

    ItemStack armorIcon(Player p) {
        int level = armorLevel(p);
        long price = armorPrice(level);
        Lore lore = Lore.builder(Lore.Accent.RED).description("Armour for raids. Always on", "while you are in someone's factory.").divider()
                .stat("Level", level + " / " + ARMOR_MAX + " (" + ARMOR_TIERS[level] + ")")
                .stat("Now", armorPerks(level));
        if (price < 0) {
            lore.description("", "&aFully upgraded.");
        } else {
            lore.stat("Next", armorPerks(level + 1)).stat("Price", Money.compact(price * 100)).clickPrompt("upgrade");
        }
        return Lore.item(ARMOR_ICONS[level], "&c&lRaid Armour", lore.build());
    }

    void upgradeArmor(Player p) {
        int level = armorLevel(p);
        long price = armorPrice(level);
        if (price < 0) {
            p.sendMessage(Msg.neutral("Your raid armour is fully upgraded."));
            return;
        }
        if (!module.withdraw(p, price * 100, "raid armour level " + (level + 1))) {
            return;
        }
        p.getPersistentDataContainer().set(armorKey, PersistentDataType.INTEGER, level + 1);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 1f);
        p.sendMessage(Msg.good("Raid armour is now " + Msg.white("level " + (level + 1)) + ": " + armorPerks(level + 1) + "."));
    }

    void menu(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null) {
            return;
        }
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Raid tools"));
        menu.set(4, Lore.item(Material.SUNFLOWER, "&eTokens: &f" + g.tokens(), Lore.builder(Lore.Accent.WHITE)
                .description("Raid tools are paid in tokens.", "Each one is used up when you use it.").build()), null);
        int slot = 10;
        for (Tool t : Tool.values()) {
            Lore lore = Lore.builder(Lore.Accent.WHITE).description(t.lore).divider()
                    .stat("Price", t.price + " tokens")
                    .stat("Cooldown", t.cooldown + "s")
                    .stat("You have", String.valueOf(count(p, t)))
                    .description("Shift-click: buy 5.").clickPrompt("buy one");
            menu.set(slot++, Lore.item(t.icon, t.name, lore.build()), e -> {
                buy(p, t, e.isShiftClick() ? 5 : 1);
                menu(p);
            });
        }
        menu.set(22, Lore.item(Material.ARROW, "&fBack", Lore.builder(Lore.Accent.WHITE).clickPrompt("go back").build()), e -> module.menus().gear(p));
        menu.open(p);
    }

    private int count(Player p, Tool t) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (toolOf(it) == t) {
                n += it.getAmount();
            }
        }
        return n;
    }

    private void buy(Player p, Tool t, int amount) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        long cost = (long) t.price * amount;
        if (g == null || !g.takeTokens(cost)) {
            p.sendMessage(Msg.bad("You need " + Msg.white(cost + " tokens") + "."));
            return;
        }
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(item(t, amount));
        int refused = left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (refused > 0) {
            g.addTokens((long) t.price * refused);
        }
        module.saveSoon(p.getUniqueId());
        int got = amount - refused;
        if (got <= 0) {
            p.sendMessage(Msg.bad("Your inventory is full."));
            return;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.sendMessage(Msg.good("Bought " + Msg.white(got + "x " + t.name.replaceAll("&.", "")) + " for " + Msg.white((long) t.price * got + " tokens") + "."));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player p = event.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        Tool t = toolOf(held);
        if (t == null) {
            return;
        }
        event.setCancelled(true);
        Factory f = module.at(p.getLocation());
        if (f == null || p.getUniqueId().equals(f.view.owner()) || module.trusted(f.view.owner(), p)) {
            p.sendActionBar(Msg.bad("Raid tools only work inside an enemy factory."));
            return;
        }
        String key = p.getUniqueId() + ":" + t.id;
        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(key, 0L);
        if (ready > now) {
            p.sendActionBar(Msg.bad(t.name.replaceAll("&.", "") + " is ready in " + ((ready - now) / 1000 + 1) + "s."));
            return;
        }
        if (!use(p, t, f)) {
            return;
        }
        cooldowns.put(key, now + t.cooldown * 1000L);
        held.setAmount(held.getAmount() - 1);
    }

    private boolean use(Player p, Tool t, Factory f) {
        Location at = p.getLocation();
        switch (t) {
            case SPEED -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 160, 2, false, true, true));
                p.getWorld().spawnParticle(Particle.CLOUD, at, 15, 0.3, 0.1, 0.3, 0.05);
                p.playSound(at, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1.4f);
            }
            case GRAPPLE -> {
                Vector v = at.getDirection().multiply(1.8);
                v.setY(Math.max(0.5, v.getY() + 0.4));
                p.setVelocity(v);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 50, 0, false, false, true));
                p.playSound(at, Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 0.8f);
            }
            case CLOAK -> {
                cloakUntil.put(p.getUniqueId(), System.currentTimeMillis() + 8000);
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 160, 0, false, false, true));
                p.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.02);
                p.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1f);
                p.sendActionBar(Msg.good("Cloaked: turrets can't see you for 8s."));
            }
            case EMP -> {
                f.emp(System.currentTimeMillis() + 12_000);
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), 120, 4, 2, 4, 0.2);
                p.getWorld().playSound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.6f);
                p.sendActionBar(Msg.good("EMP! Turrets and traps are down for 12s."));
                Player owner = Bukkit.getPlayer(f.view.owner());
                if (owner != null) {
                    String name = p.getName();
                    Tasks.entity.run(owner, () -> owner.sendMessage(Msg.bad(Msg.white(name) + " hit your factory with an EMP. Turrets and traps are down for 12s!")));
                }
            }
            case WEB -> {
                int hit = 0;
                for (Player o : p.getWorld().getNearbyPlayers(at, 7)) {
                    if (o.equals(p) || module.bypasses(o)) {
                        continue;
                    }
                    hit++;
                    Tasks.entity.run(o, () -> {
                        o.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 5, false, true, true));
                        o.getWorld().spawnParticle(Particle.BLOCK, o.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0,
                                Material.COBWEB.createBlockData());
                        o.sendActionBar(Msg.bad("You're stuck in a web!"));
                    });
                }
                p.getWorld().playSound(at, Sound.ENTITY_SPIDER_AMBIENT, 1.2f, 0.7f);
                p.sendActionBar(hit == 0 ? Msg.neutral("Web Bomb thrown. Nobody was close enough.") : Msg.good("Webbed " + hit + " player" + (hit == 1 ? "" : "s") + "!"));
            }
            case STUN -> {
                Location eye = p.getEyeLocation();
                RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 25, 0.5,
                        e -> e instanceof Player o && !o.equals(p) && !module.bypasses(o));
                Entity hitEntity = r == null ? null : r.getHitEntity();
                if (!(hitEntity instanceof Player target)) {
                    p.sendActionBar(Msg.bad("No player in your sights."));
                    return false;
                }
                Vector step = target.getEyeLocation().toVector().subtract(eye.toVector());
                double len = step.length();
                step.normalize().multiply(0.5);
                Location point = eye.clone();
                for (double d = 0; d < len; d += 0.5) {
                    p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, point, 2, 0.02, 0.02, 0.02, 0);
                    point.add(step);
                }
                p.getWorld().playSound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 2f);
                Tasks.entity.run(target, () -> {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 9, false, true, true));
                    target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 50, 4, false, false, true));
                    target.sendActionBar(Msg.bad("Stunned!"));
                });
                p.sendActionBar(Msg.good("Stunned " + target.getName() + "!"));
            }
        }
        return true;
    }
}
