package me.Evan52098.genscore.wipe;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * blockville-common's WipeScope. FULL takes everything. SEASON ("custom" wherever a person sees it) honours a
 * keep-list: the menu's pick-and-choose wipes and the server-wide season reset use it. RESTORE tags the copy taken
 * before a restore.
 */
public enum WipeScope {
    FULL(true),
    SEASON(false),
    RESTORE(false);

    private final boolean wipeable;

    WipeScope(boolean wipeable) {
        this.wipeable = wipeable;
    }

    public static @Nullable WipeScope parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("CUSTOM".equals(normalized)) {
            return SEASON;
        }
        for (WipeScope scope : values()) {
            if (scope.name().equals(normalized)) {
                return scope;
            }
        }
        return null;
    }

    /** Only scopes the wipeplayer command accepts (FULL). */
    public static @Nullable WipeScope parseWipe(@Nullable String value) {
        WipeScope scope = parse(value);
        return scope != null && scope.wipeable ? scope : null;
    }

    public boolean wipeable() {
        return wipeable;
    }

    public String id() {
        return this == SEASON ? "custom" : name().toLowerCase(Locale.ROOT);
    }
}
