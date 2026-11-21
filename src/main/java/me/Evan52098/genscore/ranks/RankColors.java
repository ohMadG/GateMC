package me.Evan52098.genscore.ranks;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

final class RankColors {

    record Preset(String id, String name, Material icon, int[] stops, Motion motion) {
        Preset(String id, String name, Material icon, int[] stops) {
            this(id, name, icon, stops, Motion.NONE);
        }

        boolean animated() {
            return motion != Motion.NONE;
        }
    }

    enum Motion {
        NONE, WAVE, PULSE
    }

    private static final long WAVE_PERIOD = 2500;
    private static final long PULSE_PERIOD = 2000;

    static final List<Preset> ALL = List.of(
            solid("red", "Red", Material.RED_WOOL, 0xFF5555),
            solid("gold", "Gold", Material.ORANGE_WOOL, 0xFFAA00),
            solid("yellow", "Yellow", Material.YELLOW_WOOL, 0xFFFF55),
            solid("lime", "Lime", Material.LIME_WOOL, 0x55FF55),
            solid("green", "Green", Material.GREEN_WOOL, 0x00AA00),
            solid("aqua", "Aqua", Material.LIGHT_BLUE_WOOL, 0x55FFFF),
            solid("cyan", "Cyan", Material.CYAN_WOOL, 0x00AAAA),
            solid("blue", "Blue", Material.BLUE_WOOL, 0x5555FF),
            solid("purple", "Purple", Material.PURPLE_WOOL, 0xAA00AA),
            solid("pink", "Pink", Material.PINK_WOOL, 0xFF55FF),
            solid("white", "White", Material.WHITE_WOOL, 0xFFFFFF),
            solid("gray", "Gray", Material.LIGHT_GRAY_WOOL, 0xAAAAAA),
            new Preset("sunset", "Sunset", Material.ORANGE_STAINED_GLASS, new int[]{0xFF7A18, 0xFFD54F}),
            new Preset("fire", "Fire", Material.RED_STAINED_GLASS, new int[]{0xFF2A2A, 0xFFB000}),
            new Preset("ocean", "Ocean", Material.LIGHT_BLUE_STAINED_GLASS, new int[]{0x3AE0FF, 0x2B6BFF}),
            new Preset("toxic", "Toxic", Material.LIME_STAINED_GLASS, new int[]{0xB4F07B, 0x1FAA59}),
            new Preset("royal", "Royal", Material.PURPLE_STAINED_GLASS, new int[]{0x8A2BE2, 0xFF4DD2}),
            new Preset("ice", "Ice", Material.WHITE_STAINED_GLASS, new int[]{0xFFFFFF, 0x7FC1E2}),
            new Preset("cherry", "Cherry", Material.PINK_STAINED_GLASS, new int[]{0xFFB7D5, 0xE0115F}),
            new Preset("molten", "Molten", Material.MAGMA_BLOCK, new int[]{0xFFE066, 0xFF7A00, 0xC00010}),
            new Preset("rainbow", "Rainbow", Material.PRISMARINE_CRYSTALS,
                    new int[]{0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF}),
            new Preset("rainbow_wave", "Rainbow Wave", Material.NETHER_STAR,
                    new int[]{0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF}, Motion.WAVE),
            new Preset("fire_wave", "Inferno", Material.BLAZE_POWDER, new int[]{0xFF2A2A, 0xFF7A00, 0xFFE066}, Motion.WAVE),
            new Preset("ocean_wave", "Tide", Material.HEART_OF_THE_SEA, new int[]{0x2B6BFF, 0x3AE0FF, 0xBFF3FF}, Motion.WAVE),
            new Preset("aurora", "Aurora", Material.AMETHYST_SHARD, new int[]{0x55FF99, 0x3AE0FF, 0xA13BE9}, Motion.WAVE),
            new Preset("sunset_wave", "Dusk", Material.GLOWSTONE_DUST, new int[]{0xFF4DD2, 0xFF7A18, 0xFFD54F}, Motion.WAVE),
            new Preset("toxic_wave", "Radiation", Material.SLIME_BALL, new int[]{0x1FAA59, 0xB4F07B, 0xF0FF7B}, Motion.WAVE),
            new Preset("gold_pulse", "Gold Pulse", Material.GOLD_INGOT, new int[]{0xFFAA00, 0xFFF3B0}, Motion.PULSE)
    );

    private RankColors() {
    }

    private static Preset solid(String id, String name, Material icon, int rgb) {
        return new Preset(id, name, icon, new int[]{rgb});
    }

    static @Nullable Preset get(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        for (Preset p : ALL) {
            if (p.id().equals(key)) {
                return p;
            }
        }
        return null;
    }

    static String apply(Preset preset, String badgeText) {
        boolean bold = badgeText.contains("&l") || badgeText.contains("§l");
        String plain = strip(badgeText).trim();
        if (plain.isEmpty()) {
            return badgeText;
        }
        return paint(preset, plain, bold) + " &r";
    }

    static String paint(Preset preset, String plain, boolean bold) {
        int[] stops = preset.stops();
        int letters = 0;
        for (int i = 0; i < plain.length(); i++) {
            if (plain.charAt(i) != ' ') {
                letters++;
            }
        }
        long now = System.currentTimeMillis();
        double phase = switch (preset.motion()) {
            case WAVE -> (now % WAVE_PERIOD) / (double) WAVE_PERIOD;
            case PULSE -> (now % PULSE_PERIOD) / (double) PULSE_PERIOD;
            case NONE -> 0;
        };
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == ' ') {
                out.append(' ');
                continue;
            }
            int rgb;
            if (preset.motion() == Motion.WAVE) {
                double t = letters <= 0 ? 0 : (double) n / letters;
                rgb = loop(stops, t * 0.6 - phase);
            } else if (preset.motion() == Motion.PULSE) {
                rgb = at(stops, (1 - Math.cos(phase * 2 * Math.PI)) / 2);
            } else {
                rgb = at(stops, letters <= 1 ? 0 : (double) n / (letters - 1));
            }
            out.append(hex(rgb));
            if (bold) {
                out.append("&l");
            }
            out.append(c);
            n++;
        }
        return out.toString();
    }

    private static int loop(int[] stops, double t) {
        double u = t - Math.floor(t);
        int[] closed = new int[stops.length + 1];
        System.arraycopy(stops, 0, closed, 0, stops.length);
        closed[stops.length] = stops[0];
        return at(closed, u);
    }

    private static int at(int[] stops, double t) {
        if (stops.length == 1) {
            return stops[0];
        }
        double pos = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) Math.floor(pos));
        double f = pos - i;
        int a = stops[i];
        int b = stops[i + 1];
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static String hex(int rgb) {
        return String.format("&#%06X", rgb & 0xFFFFFF);
    }

    private static String strip(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                if (s.charAt(i + 1) == '#' && i + 7 < s.length()) {
                    i += 7;
                } else {
                    i++;
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
