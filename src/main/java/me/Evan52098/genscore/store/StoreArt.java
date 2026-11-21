package me.Evan52098.genscore.store;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class StoreArt {

    static final int CARD = 0;
    static final int TAB = 1;
    static final int INSIDE = 2;
    static final int CART_ROW = 3;
    static final int REWARD = 10;
    static final int MARKER = 12;

    private static final int[] SIZES = {96, 32, 16, 16, 16, 16, 16, 16, 16, 16, 24, 24, 12};

    record Art(String name, int base, int visible, int frame) {
        char glyph(int slot) {
            return (char) (base + slot);
        }

        int advance(int slot) {
            return (int) (0.5f + visible * (SIZES[slot] / (float) frame)) + 1;
        }

        int size(int slot) {
            return SIZES[slot];
        }
    }

    private final Map<String, Art> byName = new HashMap<>();

    static StoreArt load(@Nullable InputStream in) throws IOException {
        StoreArt art = new StoreArt();
        if (in == null) {
            return art;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length != 4) {
                    continue;
                }
                Art a = new Art(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                art.byName.put(p[0], a);
                art.byName.put(p[0].substring(p[0].indexOf('/') + 1), a);
            }
        }
        return art;
    }

    @Nullable Art get(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return byName.get(name.trim().toLowerCase(Locale.ROOT));
    }
}
