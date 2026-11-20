package me.Evan52098.genscore.essentials;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Locale;
import java.util.function.Function;

/**
 * Commands that change a player's own state: heal, feed, fly, god, speed, gamemode, suicide, exp, ptime, pweather,
 * hat, ci. Ported from BlockvilleCore with the same messages and permission layout (genscore.X and genscore.X.others),
 * minus cross-server relays.
 *
 * <p>Folia: a command runs on the sender's thread. Changes to another player run on that player's thread, and the
 * reply comes back on the sender's thread.
 */
public final class PlayerStateCommands {

    private final boolean healExtinguish;
    private final boolean healClearEffects;
    private final double speedMin;
    private final double speedMax;

    PlayerStateCommands(boolean healExtinguish, boolean healClearEffects, double speedMin, double speedMax) {
        this.healExtinguish = healExtinguish;
        this.healClearEffects = healClearEffects;
        this.speedMin = speedMin;
        this.speedMax = speedMax;
    }

    /**
     * Applies {@code change} to the target (or the sender when no target was given) on the target's thread.
     * {@code change} returns the message for the target; {@code othersReply} builds the sender's reply from the
     * target's name. Returns without doing anything if the sender lacks {@code othersPermission}.
     */
    private void apply(BukkitCommandActor actor, @Nullable Player target, String othersPermission,
                       Function<Player, String> change, @Nullable Function<String, String> othersReply) {
        CommandSender sender = actor.sender();
        Player subject = target != null ? target : actor.requirePlayer();
        boolean other = !(sender instanceof Player p && p.getUniqueId().equals(subject.getUniqueId()));
        if (other && !sender.hasPermission(othersPermission)) {
            sender.sendMessage(Msg.bad("You don't have permission to do that."));
            return;
        }
        Tasks.onEntity(subject, () -> {
            String result = change.apply(subject);
            if (result != null) {
                subject.sendMessage(Msg.good(result));
            }
            if (other && othersReply != null) {
                String name = subject.getName();
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.good(othersReply.apply(name))));
            }
        });
    }

    // ------------------------------------------------------------------ heal, feed

    @Command("heal")
    @CommandPermission("genscore.heal")
    @Description("Restore health")
    public void heal(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        apply(actor, target, "genscore.heal.others", p -> {
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                p.setHealth(max.getValue());
            }
            p.setFoodLevel(20);
            p.setSaturation(20f);
            if (healExtinguish) {
                p.setFireTicks(0);
            }
            if (healClearEffects) {
                for (PotionEffect effect : p.getActivePotionEffects()) {
                    p.removePotionEffect(effect.getType());
                }
            }
            return "You have been healed.";
        }, name -> "Healed " + Msg.white(name) + ".");
    }

    @Command("feed")
    @CommandPermission("genscore.feed")
    @Description("Restore hunger")
    public void feed(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        apply(actor, target, "genscore.feed.others", p -> {
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setExhaustion(0f);
            return "Your hunger has been restored.";
        }, name -> "Restored hunger for " + Msg.white(name) + ".");
    }

    // ------------------------------------------------------------------ fly, god, speed

    @Command("fly")
    @CommandPermission("genscore.fly")
    @Description("Toggle flight")
    public void fly(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        boolean[] enabled = new boolean[1];
        apply(actor, target, "genscore.fly.others", p -> {
            enabled[0] = !p.getAllowFlight();
            p.setAllowFlight(enabled[0]);
            if (!enabled[0]) {
                p.setFlying(false);
            }
            return enabled[0] ? "Fly mode enabled." : "Fly mode disabled.";
        }, name -> (enabled[0] ? "Flight enabled for " : "Flight disabled for ") + Msg.white(name) + ".");
    }

    @Command("god")
    @CommandPermission("genscore.god")
    @Description("Toggle invulnerability")
    public void god(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        boolean[] enabled = new boolean[1];
        apply(actor, target, "genscore.god.others", p -> {
            enabled[0] = !p.isInvulnerable();
            p.setInvulnerable(enabled[0]);
            return enabled[0] ? "God mode enabled - you are invulnerable." : "God mode disabled.";
        }, name -> (enabled[0] ? "Enabled god mode for " : "Disabled god mode for ") + Msg.white(name) + ".");
    }

    @Command("ungod")
    @CommandPermission("genscore.god")
    @Description("Turn invulnerability off")
    public void ungod(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        apply(actor, target, "genscore.god.others", p -> {
            p.setInvulnerable(false);
            return "God mode disabled.";
        }, name -> "Disabled god mode for " + Msg.white(name) + ".");
    }

    @Command({"speed", "flyspeed", "walkspeed"})
    @CommandPermission("genscore.speed")
    @Description("Set your walk or fly speed (1 is normal)")
    public void speed(BukkitCommandActor actor, @Named("value") String value, @Named("player") @Optional @Nullable Player target) {
        double multiplier;
        try {
            multiplier = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            multiplier = Double.NaN;
        }
        if (Double.isNaN(multiplier) || multiplier < speedMin || multiplier > speedMax) {
            actor.sender().sendMessage(Msg.bad("Speed must be between " + Msg.white(fmt(speedMin)) + " and "
                    + Msg.white(fmt(speedMax)) + " (decimals like " + Msg.white("0.5") + " allowed)."));
            return;
        }
        double m = multiplier;
        String[] type = new String[1];
        apply(actor, target, "genscore.speed.others", p -> {
            if (p.isFlying()) {
                p.setFlySpeed(clamp(0.1f * (float) m));
                type[0] = "fly";
            } else {
                p.setWalkSpeed(clamp(0.2f * (float) m));
                type[0] = "walk";
            }
            return "Set " + Msg.white(type[0]) + " speed to " + Msg.white(fmt(m)) + ".";
        }, name -> "Set " + Msg.white(type[0]) + " speed to " + Msg.white(fmt(m)) + " for " + Msg.white(name) + ".");
    }

    @Command("speed reset")
    @CommandPermission("genscore.speed")
    @Description("Reset walk and fly speed")
    public void speedReset(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        apply(actor, target, "genscore.speed.others", p -> {
            p.setFlySpeed(0.1f);
            p.setWalkSpeed(0.2f);
            return "Your speed has been reset.";
        }, name -> "Reset the speed of " + Msg.white(name) + ".");
    }

    private static float clamp(float v) {
        return Math.max(0.005f, Math.min(1.0f, v));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    // ------------------------------------------------------------------ gamemode

    @Command({"gamemode", "gm"})
    @Description("Change game mode")
    public void gamemode(BukkitCommandActor actor, @Named("mode") String mode, @Named("player") @Optional @Nullable Player target) {
        GameMode resolved = switch (mode.toLowerCase(Locale.ROOT).trim()) {
            case "survival", "s", "0" -> GameMode.SURVIVAL;
            case "creative", "c", "1" -> GameMode.CREATIVE;
            case "adventure", "a", "2" -> GameMode.ADVENTURE;
            case "spectator", "spec", "sp", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
        if (resolved == null) {
            actor.sender().sendMessage(Msg.bad("Unknown gamemode: " + Msg.white(mode) + "."));
            return;
        }
        setMode(actor, target, resolved);
    }

    @Command("gmc")
    public void gmc(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        setMode(actor, target, GameMode.CREATIVE);
    }

    @Command("gms")
    public void gms(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        setMode(actor, target, GameMode.SURVIVAL);
    }

    @Command("gma")
    public void gma(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        setMode(actor, target, GameMode.ADVENTURE);
    }

    @Command("gmsp")
    public void gmsp(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        setMode(actor, target, GameMode.SPECTATOR);
    }

    private void setMode(BukkitCommandActor actor, @Nullable Player target, GameMode mode) {
        String modeName = mode.name().toLowerCase(Locale.ROOT);
        if (!actor.sender().hasPermission("genscore.gamemode." + modeName)) {
            actor.sender().sendMessage(Msg.bad("You don't have permission to use " + Msg.white(modeName) + " mode."));
            return;
        }
        apply(actor, target, "genscore.gamemode.others", p -> {
            p.setGameMode(mode);
            return "Gamemode set to " + Msg.white(modeName) + ".";
        }, name -> "Set " + Msg.white(name) + "'s gamemode to " + Msg.white(modeName) + ".");
    }

    // ------------------------------------------------------------------ misc

    @Command("suicide")
    @CommandPermission("genscore.kill")
    @Description("Kill yourself")
    public void suicide(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        player.setHealth(0);
        player.sendMessage(Msg.good("You have been killed."));
    }

    @Command("exp")
    @CommandPermission("genscore.exp")
    @Description("Show experience")
    public void exp(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        CommandSender sender = actor.sender();
        Player subject = target != null ? target : actor.requirePlayer();
        if (target != null && !(sender instanceof Player p && p.equals(target)) && !sender.hasPermission("genscore.exp.others")) {
            sender.sendMessage(Msg.bad("You don't have permission to do that."));
            return;
        }
        Tasks.onEntity(subject, () -> {
            int total = subject.calculateTotalExperiencePoints();
            int level = subject.getLevel();
            Tasks.forSender(sender, () -> sender.sendMessage(Msg.neutral("Experience: " + Msg.white(String.valueOf(total))
                    + " (Level " + Msg.white(String.valueOf(level)) + ")")));
        });
    }

    @Command("ptime")
    @CommandPermission("genscore.ptime")
    @Description("Set your own time of day")
    public void ptime(BukkitCommandActor actor, @Named("time") String time) {
        Player player = actor.requirePlayer();
        String t = time.toLowerCase(Locale.ROOT);
        if (t.equals("reset")) {
            player.resetPlayerTime();
            player.sendMessage(Msg.good("Personal time reset."));
            return;
        }
        Long ticks = switch (t) {
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "sunset" -> 12000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            case "sunrise" -> 23000L;
            default -> {
                try {
                    long v = Long.parseLong(t);
                    yield v >= 0 && v <= 24000 ? v : null;
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
        if (ticks == null) {
            player.sendMessage(Msg.bad("Invalid time. Use a number (0-24000), or: day, noon, sunset, night, midnight, sunrise, reset."));
            return;
        }
        player.setPlayerTime(ticks, false);
        player.sendMessage(Msg.good("Personal time set to " + Msg.white(time) + "."));
    }

    @Command("pweather")
    @CommandPermission("genscore.pweather")
    @Description("Set your own weather")
    public void pweather(BukkitCommandActor actor, @Named("weather") String weather) {
        Player player = actor.requirePlayer();
        switch (weather.toLowerCase(Locale.ROOT)) {
            case "reset" -> {
                player.resetPlayerWeather();
                player.sendMessage(Msg.good("Personal weather reset."));
            }
            case "sun", "clear" -> {
                player.setPlayerWeather(WeatherType.CLEAR);
                player.sendMessage(Msg.good("Personal weather set to " + Msg.white("sun") + "."));
            }
            case "rain", "storm" -> {
                player.setPlayerWeather(WeatherType.DOWNFALL);
                player.sendMessage(Msg.good("Personal weather set to " + Msg.white("rain") + "."));
            }
            default -> player.sendMessage(Msg.bad("Invalid weather. Use: sun, rain, reset."));
        }
    }

    @Command("hat")
    @CommandPermission("genscore.hat")
    @Description("Wear the item in your hand")
    public void hat(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(Msg.bad("You must be holding an item."));
            return;
        }
        // Wear exactly one item; the rest of the stack stays in the hand and the old helmet goes to the inventory
        // (or the feet when full), so nothing is lost or multiplied.
        ItemStack oldHelmet = inv.getHelmet();
        if (oldHelmet != null && oldHelmet.containsEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE)
                && player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            player.sendMessage(Msg.bad("Your helmet has Curse of Binding and can't be taken off."));
            return;
        }
        ItemStack wear = hand.asOne();
        hand.setAmount(hand.getAmount() - 1);
        inv.setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        inv.setHelmet(wear);
        if (oldHelmet != null && !oldHelmet.isEmpty()) {
            inv.addItem(oldHelmet).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        player.sendMessage(Msg.good("You are now wearing that item as a hat."));
    }

    @Command("ci")
    @CommandPermission("genscore.ci")
    @Description("Clear an inventory")
    public void clearInventory(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        apply(actor, target, "genscore.ci.others", p -> {
            p.getInventory().clear();
            return "Your inventory has been cleared.";
        }, name -> "Cleared " + Msg.white(name) + "'s inventory.");
    }
}
