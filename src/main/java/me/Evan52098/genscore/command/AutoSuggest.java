package me.Evan52098.genscore.command;

import me.Evan52098.genscore.announce.AnnouncementsModule;
import me.Evan52098.genscore.cosmetics.CosmeticsModule;
import me.Evan52098.genscore.kits.KitsModule;
import me.Evan52098.genscore.leaderboards.LeaderboardsModule;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.seasonpass.Objective;
import me.Evan52098.genscore.teleport.TeleportModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class AutoSuggest implements SuggestionProvider.Factory<BukkitCommandActor> {

    private static final List<String> MONEY = List.of("100", "1k", "10k", "100k", "1m");
    private static final List<String> COUNT = List.of("1", "5", "10", "64");
    private static final List<String> ON_OFF = List.of("on", "off");
    private static final List<String> OPEN_CLOSE = List.of("open", "close");

    @Override
    public @Nullable SuggestionProvider<BukkitCommandActor> create(Type type, AnnotationList annotations, Lamp<BukkitCommandActor> lamp) {
        if (annotations.contains(SuggestWith.class)) {
            return null;
        }
        if (type != String.class && type != int.class && type != Integer.class && type != long.class && type != Long.class) {
            return null;
        }
        String name = annotations.mapOr(Named.class, Named::value, "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        boolean number = type != String.class;
        return context -> suggest(context, name, number);
    }

    private static Collection<String> suggest(ExecutionContext<BukkitCommandActor> context, String name, boolean number) {
        String path = context.command().path().toLowerCase(Locale.ROOT);
        if (number) {
            return switch (name) {
                case "amount", "count" -> COUNT;
                case "page", "number", "line" -> List.of("1", "2", "3");
                case "hours" -> List.of("1", "6", "24");
                case "radius" -> List.of("10", "25", "50");
                case "slot" -> List.of("0", "1", "2", "3");
                default -> List.of();
            };
        }
        switch (name) {
            case "player", "target", "voter", "owner", "page|player" -> {
                return players(context);
            }
            case "amount" -> {
                return path.startsWith("gensreward") ? List.of("10", "30", "60", "common", "rare", "epic", "legendary") : MONEY;
            }
            case "on|off" -> {
                return ON_OFF;
            }
            case "action" -> {
                return path.contains("gate") ? OPEN_CLOSE : List.of();
            }
            case "seconds|cancel" -> {
                return List.of("60", "300", "600", "cancel");
            }
            case "count|clear" -> {
                return List.of("1", "5", "10", "clear");
            }
            case "which" -> {
                return List.of("factory", "hub");
            }
            case "what" -> {
                if (path.contains("tokens")) {
                    List<String> out = new ArrayList<>(List.of("tokens"));
                    out.addAll(Arrays.asList(GensPlayer.RARITIES));
                    return out;
                }
                return List.of("data");
            }
            case "type" -> {
                if (path.startsWith("gensreward")) {
                    return List.of("income", "tokens", "cache", "boost");
                }
                List<String> out = new ArrayList<>();
                for (Objective o : Objective.values()) {
                    out.add(o.name().toLowerCase(Locale.ROOT));
                }
                return out;
            }
            case "mode" -> {
                return List.of("survival", "creative", "adventure", "spectator");
            }
            case "time" -> {
                return List.of("day", "noon", "night", "midnight", "reset");
            }
            case "weather" -> {
                return List.of("clear", "rain", "reset");
            }
            case "value" -> {
                return List.of("1", "2", "5", "10", "reset");
            }
            case "day" -> {
                LocalDate today = LocalDate.now();
                return List.of(today.toString(), today.minusDays(1).toString());
            }
            case "item" -> {
                return path.startsWith("storeadmin") ? List.of() : materials(context);
            }
            case "board" -> {
                LeaderboardsModule m = Suggest.module(LeaderboardsModule.class);
                return m == null ? List.of() : m.boardIds();
            }
            case "kit" -> {
                KitsModule m = Suggest.module(KitsModule.class);
                return m == null ? List.of() : m.kitNames();
            }
            case "category" -> {
                CosmeticsModule m = Suggest.module(CosmeticsModule.class);
                return m == null ? List.of() : m.cosmeticCategories();
            }
            case "reason" -> {
                return List.of("hacking", "spam", "scamming", "griefing", "toxic");
            }
            case "nickname" -> {
                return path.startsWith("realname") ? displayNames(context) : List.of();
            }
            case "home", "from" -> {
                return homes(context);
            }
            case "id" -> {
                if (path.startsWith("announce")) {
                    AnnouncementsModule m = Suggest.module(AnnouncementsModule.class);
                    return m == null ? List.of() : m.announcementIds();
                }
                if (path.startsWith("cosmetic")) {
                    CosmeticsModule m = Suggest.module(CosmeticsModule.class);
                    return m == null ? List.of() : m.cosmeticIds();
                }
                return List.of();
            }
            case "name" -> {
                if (path.equals("home") || path.equals("delhome")) {
                    return homes(context);
                }
                if (path.equals("warp") || path.equals("delwarp")) {
                    TeleportModule m = Suggest.module(TeleportModule.class);
                    return m == null ? List.of() : m.warpNames();
                }
                if (path.equals("sethome")) {
                    return List.of("home");
                }
                if (path.startsWith("kit")) {
                    KitsModule m = Suggest.module(KitsModule.class);
                    return m == null ? List.of() : m.kitNames();
                }
                if (path.startsWith("gensreward")) {
                    return players(context);
                }
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private static List<String> players(ExecutionContext<BukkitCommandActor> context) {
        List<String> out = new ArrayList<>();
        Player viewer = context.actor().sender() instanceof Player p ? p : null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewer == null || viewer.canSee(p)) {
                out.add(p.getName());
            }
        }
        return out;
    }

    private static List<String> displayNames(ExecutionContext<BukkitCommandActor> context) {
        List<String> out = new ArrayList<>();
        Player viewer = context.actor().sender() instanceof Player p ? p : null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewer == null || viewer.canSee(p)) {
                out.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(p.displayName()));
            }
        }
        return out;
    }

    private static List<String> homes(ExecutionContext<BukkitCommandActor> context) {
        if (!(context.actor().sender() instanceof Player p)) {
            return List.of();
        }
        TeleportModule m = Suggest.module(TeleportModule.class);
        return m == null ? List.of() : m.homeNames(p);
    }

    private static List<String> materials(ExecutionContext<BukkitCommandActor> context) {
        List<String> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isItem() && !m.isAir()) {
                out.add(m.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
