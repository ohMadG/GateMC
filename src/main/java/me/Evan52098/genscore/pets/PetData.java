package me.Evan52098.genscore.pets;

import me.Evan52098.genscore.player.PlayerSection;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PetData {

    static final class Pet {
        final String id;
        final String type;
        int level;
        int xp;
        boolean golden;
        boolean equipped;

        Pet(String id, String type, int level, int xp, boolean golden, boolean equipped) {
            this.id = id;
            this.type = type;
            this.level = level;
            this.xp = xp;
            this.golden = golden;
            this.equipped = equipped;
        }

        Pet copy() {
            return new Pet(id, type, level, xp, golden, equipped);
        }
    }

    private final List<Pet> pets = new ArrayList<>();
    private final int[] eggs = new int[4];
    private boolean hidden;
    private boolean dirty;
    private volatile Map<String, Double> bonus = Map.of();

    synchronized List<Pet> pets() {
        List<Pet> out = new ArrayList<>(pets.size());
        for (Pet p : pets) {
            out.add(p.copy());
        }
        return out;
    }

    synchronized @Nullable Pet pet(String id) {
        for (Pet p : pets) {
            if (p.id.equals(id)) {
                return p.copy();
            }
        }
        return null;
    }

    synchronized int count() {
        return pets.size();
    }

    synchronized int equippedCount() {
        int n = 0;
        for (Pet p : pets) {
            if (p.equipped) {
                n++;
            }
        }
        return n;
    }

    synchronized int eggs(int rarity) {
        return eggs[rarity];
    }

    synchronized void addEggs(int rarity, int amount) {
        eggs[rarity] = Math.max(0, eggs[rarity] + amount);
        dirty = true;
    }

    synchronized boolean takeEgg(int rarity) {
        if (eggs[rarity] <= 0) {
            return false;
        }
        eggs[rarity]--;
        dirty = true;
        return true;
    }

    synchronized boolean hidden() {
        return hidden;
    }

    synchronized void setHidden(boolean hidden) {
        this.hidden = hidden;
        dirty = true;
    }

    synchronized Pet add(String type) {
        Pet p = new Pet(UUID.randomUUID().toString().substring(0, 12), type, 1, 0, false, false);
        pets.add(p);
        dirty = true;
        return p.copy();
    }

    synchronized boolean remove(String id) {
        boolean removed = pets.removeIf(p -> p.id.equals(id));
        dirty |= removed;
        return removed;
    }

    synchronized boolean setEquipped(String id, boolean equipped) {
        for (Pet p : pets) {
            if (p.id.equals(id) && p.equipped != equipped) {
                p.equipped = equipped;
                dirty = true;
                return true;
            }
        }
        return false;
    }

    synchronized int addXp(String id, int xp, PetsSettings s) {
        for (Pet p : pets) {
            if (p.id.equals(id)) {
                return addXp(p, xp, s);
            }
        }
        return 0;
    }

    synchronized List<String> tick(int xp, PetsSettings s) {
        List<String> levelled = new ArrayList<>();
        for (Pet p : pets) {
            if (p.equipped && addXp(p, xp, s) > 0) {
                levelled.add(p.id);
            }
        }
        return levelled;
    }

    private int addXp(Pet p, int xp, PetsSettings s) {
        if (p.level >= s.maxLevel()) {
            return 0;
        }
        int gained = 0;
        p.xp += xp;
        while (p.level < s.maxLevel() && p.xp >= s.xpToNext(p.level)) {
            p.xp -= s.xpToNext(p.level);
            p.level++;
            gained++;
        }
        if (p.level >= s.maxLevel()) {
            p.xp = 0;
        }
        dirty = true;
        return gained;
    }

    synchronized @Nullable Pet fuse(String id) {
        Pet base = null;
        for (Pet p : pets) {
            if (p.id.equals(id)) {
                base = p;
            }
        }
        if (base == null || base.golden) {
            return null;
        }
        List<Pet> same = new ArrayList<>();
        for (Pet p : pets) {
            if (p != base && !p.golden && p.type.equals(base.type)) {
                same.add(p);
            }
        }
        if (same.size() < 2) {
            return null;
        }
        same.sort((a, b) -> Integer.compare(a.level, b.level));
        Pet a = same.get(0);
        Pet b = same.get(1);
        int level = Math.max(base.level, Math.max(a.level, b.level));
        pets.remove(a);
        pets.remove(b);
        base.golden = true;
        base.level = level;
        base.xp = 0;
        dirty = true;
        return base.copy();
    }

    synchronized int sameType(String type, String except) {
        int n = 0;
        for (Pet p : pets) {
            if (!p.golden && p.type.equals(type) && !p.id.equals(except)) {
                n++;
            }
        }
        return n;
    }

    synchronized void markDirty() {
        dirty = true;
    }

    Map<String, Double> bonus() {
        return bonus;
    }

    synchronized void recompute(PetsSettings s) {
        Map<String, Double> out = new HashMap<>();
        for (Pet p : pets) {
            PetsSettings.PetDef def = p.equipped ? s.pet(p.type) : null;
            if (def != null) {
                out.merge(def.perk(), s.value(def, p.level, p.golden), Double::sum);
            }
        }
        bonus = Map.copyOf(out);
    }

    static final class Section implements PlayerSection<PetData> {

        @Override
        public String id() {
            return "pets";
        }

        @Override
        public PetData load(Connection c, UUID player) throws SQLException {
            PetData d = new PetData();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pet_id, type, level, xp, golden, equipped FROM gens_pets WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        d.pets.add(new Pet(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5) != 0, rs.getInt(6) != 0));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT common, rare, epic, legendary, hidden FROM gens_pet_eggs WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        for (int i = 0; i < 4; i++) {
                            d.eggs[i] = rs.getInt(i + 1);
                        }
                        d.hidden = rs.getInt(5) != 0;
                    }
                }
            }
            List<long[]> pending = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, rarity, amount FROM gens_pet_pending WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pending.add(new long[]{rs.getLong(1), rs.getInt(2), rs.getInt(3)});
                    }
                }
            }
            if (!pending.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM gens_pet_pending WHERE id = ?")) {
                    for (long[] row : pending) {
                        if (row[1] >= 0 && row[1] < 4) {
                            d.eggs[(int) row[1]] += (int) row[2];
                        }
                        del.setLong(1, row[0]);
                        del.addBatch();
                    }
                    del.executeBatch();
                }
                d.dirty = true;
            }
            return d;
        }

        @Override
        public void save(Connection c, UUID player, PetData data) throws SQLException {
            List<Pet> pets;
            int[] eggs;
            boolean hidden;
            synchronized (data) {
                if (!data.dirty) {
                    return;
                }
                data.dirty = false;
                pets = data.pets();
                eggs = data.eggs.clone();
                hidden = data.hidden;
            }
            try {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM gens_pets WHERE uuid = ?")) {
                    del.setString(1, player.toString());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO gens_pets (uuid, pet_id, type, level, xp, golden, equipped) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Pet p : pets) {
                        ins.setString(1, player.toString());
                        ins.setString(2, p.id);
                        ins.setString(3, p.type);
                        ins.setInt(4, p.level);
                        ins.setInt(5, p.xp);
                        ins.setInt(6, p.golden ? 1 : 0);
                        ins.setInt(7, p.equipped ? 1 : 0);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO gens_pet_eggs (uuid, common, rare, epic, legendary, hidden) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (uuid) DO UPDATE SET common = excluded.common, rare = excluded.rare, "
                                + "epic = excluded.epic, legendary = excluded.legendary, hidden = excluded.hidden")) {
                    up.setString(1, player.toString());
                    for (int i = 0; i < 4; i++) {
                        up.setInt(i + 2, eggs[i]);
                    }
                    up.setInt(6, hidden ? 1 : 0);
                    up.executeUpdate();
                }
            } catch (SQLException e) {
                data.markDirty();
                throw e;
            }
        }
    }
}
