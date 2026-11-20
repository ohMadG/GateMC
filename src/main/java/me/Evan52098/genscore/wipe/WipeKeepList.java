package me.Evan52098.genscore.wipe;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a season (custom) wipe leaves alone: module id to section ids, with "*" keeping the whole module
 * (blockville-common's WipeKeepList). New in GensCore: the key {@code categories} keeps whole wipe categories, so a
 * module added later whose sections declare, say, IDENTITY is kept without touching the list.
 */
public final class WipeKeepList {

    public static final String EVERYTHING = "*";
    public static final String CATEGORIES = "categories";

    public enum Source { CONFIG, BUILT_IN }

    private final Map<String, Set<String>> kept;
    private final Source source;

    private WipeKeepList(Map<String, Set<String>> kept, Source source) {
        this.kept = kept;
        this.source = source;
    }

    public static WipeKeepList empty() {
        return new WipeKeepList(Map.of(), Source.CONFIG);
    }

    public static WipeKeepList of(Map<String, ? extends Collection<String>> raw) {
        return of(raw, Source.CONFIG);
    }

    public static WipeKeepList of(Map<String, ? extends Collection<String>> raw, Source source) {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : raw.entrySet()) {
            String module = normalize(entry.getKey());
            if (module.isEmpty() || entry.getValue() == null) {
                continue;
            }
            Set<String> sections = normalized.computeIfAbsent(module, k -> new LinkedHashSet<>());
            for (String section : entry.getValue()) {
                if (section != null && !normalize(section).isEmpty()) {
                    sections.add(normalize(section));
                }
            }
        }
        return new WipeKeepList(normalized, source);
    }

    /** The configured list, or the built-in one when the config has none. */
    public static WipeKeepList resolve(@Nullable Map<String, ? extends Collection<String>> configured, Map<String, ? extends Collection<String>> builtIn) {
        return configured == null ? of(builtIn, Source.BUILT_IN) : of(configured, Source.CONFIG);
    }

    public Source source() {
        return source;
    }

    public boolean keepsNothing() {
        for (Set<String> sections : kept.values()) {
            if (!sections.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean keeps(WipeSection section) {
        Set<String> categories = kept.get(CATEGORIES);
        if (categories != null && categories.contains(section.category().id())) {
            return true;
        }
        Set<String> sections = kept.get(normalize(section.module()));
        return sections != null && (sections.contains(EVERYTHING) || sections.contains(normalize(section.id())));
    }

    public Map<String, Set<String>> asMap() {
        return kept;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
