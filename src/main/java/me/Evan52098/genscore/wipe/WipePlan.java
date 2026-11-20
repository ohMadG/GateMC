package me.Evan52098.genscore.wipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which sections a wipe takes and which it keeps, per module (blockville-common's WipePlan). A FULL wipe takes every
 * section; a SEASON (custom) wipe keeps what the keep-list names. A season wipe whose keep-list keeps nothing is
 * refused: that is a full wipe and must be asked for as one.
 *
 * <p>BlockvilleCore also refused a plan when a module could not keep part of its data; every GensCore section is its
 * own table or its own columns (reset sections), so any split is honoured and that refusal never happens.
 */
public final class WipePlan {

    public record Line(String moduleId, List<WipeSection> wiped, List<WipeSection> kept) {
        public boolean skipped() {
            return wiped.isEmpty();
        }
    }

    private final WipeScope scope;
    private final WipeKeepList.Source keepListSource;
    private final List<Line> lines;
    private final boolean emptyKeepList;

    private WipePlan(WipeScope scope, WipeKeepList.Source keepListSource, List<Line> lines, boolean emptyKeepList) {
        this.scope = scope;
        this.keepListSource = keepListSource;
        this.lines = List.copyOf(lines);
        this.emptyKeepList = emptyKeepList;
    }

    public static WipePlan build(WipeScope scope, WipeKeepList keepList, List<WipeSection> sections) {
        Map<String, List<WipeSection>> wipedBy = new LinkedHashMap<>();
        Map<String, List<WipeSection>> keptBy = new LinkedHashMap<>();
        int kept = 0;
        for (WipeSection section : sections) {
            wipedBy.computeIfAbsent(section.module(), k -> new ArrayList<>());
            keptBy.computeIfAbsent(section.module(), k -> new ArrayList<>());
            if (scope == WipeScope.SEASON && keepList.keeps(section)) {
                keptBy.get(section.module()).add(section);
                kept++;
            } else {
                wipedBy.get(section.module()).add(section);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (String module : wipedBy.keySet()) {
            lines.add(new Line(module, List.copyOf(wipedBy.get(module)), List.copyOf(keptBy.get(module))));
        }
        boolean empty = scope == WipeScope.SEASON && (keepList.keepsNothing() || kept == 0);
        return new WipePlan(scope, keepList.source(), lines, empty);
    }

    public WipeScope scope() {
        return scope;
    }

    public WipeKeepList.Source keepListSource() {
        return keepListSource;
    }

    public List<Line> lines() {
        return lines;
    }

    public boolean refusedForEmptyKeepList() {
        return emptyKeepList;
    }

    public List<WipeSection> wiped() {
        List<WipeSection> out = new ArrayList<>();
        for (Line line : lines) {
            out.addAll(line.wiped());
        }
        return out;
    }

    public boolean wipes(String module, String id) {
        for (WipeSection section : wiped()) {
            if (section.module().equals(module) && section.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public int wipedSectionCount() {
        return wiped().size();
    }

    public int keptSectionCount() {
        int count = 0;
        for (Line line : lines) {
            count += line.kept().size();
        }
        return count;
    }
}
