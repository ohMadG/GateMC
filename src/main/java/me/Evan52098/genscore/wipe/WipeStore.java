package me.Evan52098.genscore.wipe;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Wipe backups on disk and the SQL that reads, deletes and puts back rows (BlockvilleCore's WipeBackupStore,
 * WipeBackup, WipeRowExporter and WipeValueCodec in one place).
 *
 * <p>A backup is one JSON file in {@code plugins/GensCore/modules/wipe/backups/}: a header (wipe id, kind, scope, player,
 * actor, time) and, per table, the column names and JDBC types and every row the wipe was about to change. Files are
 * streamed (a season wipe can hold every player's rows), written to a temp file, flushed to disk and only then renamed
 * into place, so a crash never leaves a half file under the real name. Nothing ever deletes these files.
 *
 * <p>Player backups are named {@code <uuid>-<scope>-<stamp>.json}; season backups {@code season-<scope>-<stamp>.json}.
 */
final class WipeStore {

    static final String SEASON_PREFIX = "season-";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final int FORMAT = 1;

    /** Backup header. {@code player} is null for a season backup. */
    record Header(int format, String kind, String wipeId, String scope, @Nullable UUID player, String playerName, String actor, long createdAt,
                  List<String> values) {
    }

    record Column(String name, int type, boolean generated) {
    }

    /** One table's rows in a backup. {@code key} is the player for a player backup, null for a season backup. */
    record TableRows(List<String> sections, String label, String table, String keyColumn, @Nullable String key, List<Column> columns, List<Object[]> rows) {
    }

    record Written(File file, long bytes, int tables, long rows) {
    }

    private final File folder;

    WipeStore(File folder) {
        this.folder = folder;
        folder.mkdirs();
    }

    File folder() {
        return folder;
    }

    static String stem(@Nullable UUID player, String scope, long createdAt) {
        String stamp = STAMP.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()));
        return (player == null ? "season" : player.toString()) + "-" + scope + "-" + stamp;
    }

    List<File> list(UUID player) {
        return listMatching(name -> name.startsWith(player.toString()) && name.endsWith(".json"));
    }

    List<File> listSeason() {
        return listMatching(name -> name.startsWith(SEASON_PREFIX) && name.endsWith(".json"));
    }

    private List<File> listMatching(java.util.function.Predicate<String> filter) {
        File[] found = folder.listFiles((dir, name) -> filter.test(name));
        if (found == null) {
            return List.of();
        }
        List<File> files = new ArrayList<>(Arrays.asList(found));
        files.sort(Comparator.comparing(File::getName).reversed());
        return files;
    }

    Set<UUID> players() {
        Set<UUID> out = new LinkedHashSet<>();
        File[] found = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (found != null) {
            for (File f : found) {
                if (f.getName().length() >= 36) {
                    try {
                        out.add(UUID.fromString(f.getName().substring(0, 36)));
                    } catch (IllegalArgumentException notAPlayerBackup) {
                        // season file
                    }
                }
            }
        }
        return out;
    }

    /** A backup of this player (or a season backup when player is null) by file name; null if unsafe or missing. */
    @Nullable File resolve(@Nullable UUID player, String fileName) {
        String name = fileName.endsWith(".json") ? fileName : fileName + ".json";
        if (!SAFE_FILE_NAME.matcher(name).matches()) {
            return null;
        }
        if (player == null ? !name.startsWith(SEASON_PREFIX) : !name.startsWith(player.toString())) {
            return null;
        }
        File file = new File(folder, name);
        return file.isFile() ? file : null;
    }

    // ------------------------------------------------------------------ SQL helpers

    static boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        for (String name : new String[]{table, table.toUpperCase()}) {
            try (ResultSet rs = meta.getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Rows of the player (or of everyone when key is null); -1 when the table does not exist. */
    static long count(Connection c, WipeSection section, @Nullable String key) throws SQLException {
        if (!section.isTable() || !section.safe()) {
            return 0;
        }
        if (!tableExists(c, section.table())) {
            return -1;
        }
        String sql = "SELECT COUNT(*) FROM " + section.table() + (key == null ? "" : " WHERE " + section.keyColumn() + " = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (key != null) {
                ps.setString(1, key);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Deletes or resets the section's rows for the player (everyone when key is null). Returns rows changed. */
    static int apply(Connection c, WipeSection section, @Nullable String key) throws SQLException {
        String where = key == null ? "" : " WHERE " + section.keyColumn() + " = ?";
        if (section.isReset()) {
            StringJoiner set = new StringJoiner(", ");
            for (String column : section.reset().keySet()) {
                set.add(column + " = ?");
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + section.table() + " SET " + set + where)) {
                int i = 1;
                for (Object value : section.reset().values()) {
                    bindPlain(ps, i++, value);
                }
                if (key != null) {
                    ps.setString(i, key);
                }
                return ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + section.table() + where)) {
            if (key != null) {
                ps.setString(1, key);
            }
            return ps.executeUpdate();
        }
    }

    private static void bindPlain(PreparedStatement ps, int index, @Nullable Object value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            ps.setLong(index, ((Number) value).longValue());
        } else if (value instanceof Number n) {
            ps.setDouble(index, n.doubleValue());
        } else if (value instanceof Boolean b) {
            ps.setInt(index, b ? 1 : 0);
        } else {
            ps.setString(index, String.valueOf(value));
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * Streams the rows the wipe is about to change into a new backup file. Tables shared by several sections are
     * written once. Missing tables are skipped (and named in {@code missing}). Runs on a database thread.
     */
    Written export(Connection c, Header header, List<WipeSection> sections, Set<String> missing) throws SQLException, IOException {
        String stem = stem(header.player(), header.scope(), header.createdAt());
        File target = new File(folder, stem + ".json");
        File temp = new File(folder, stem + ".json.tmp");
        Map<String, List<WipeSection>> byTable = new LinkedHashMap<>();
        for (WipeSection section : sections) {
            if (section.isTable() && section.safe()) {
                byTable.computeIfAbsent(section.table() + "|" + section.keyColumn(), k -> new ArrayList<>()).add(section);
            }
        }
        String key = header.player() == null ? null : header.player().toString();
        int tables = 0;
        long rows = 0;
        try (FileOutputStream out = new FileOutputStream(temp);
             JsonWriter json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)))) {
            json.setIndent(" ");
            json.beginObject();
            json.name("format").value(FORMAT);
            json.name("kind").value(header.kind());
            json.name("wipeId").value(header.wipeId());
            json.name("scope").value(header.scope());
            json.name("player").value(header.player() == null ? null : header.player().toString());
            json.name("playerName").value(header.playerName());
            json.name("actor").value(header.actor());
            json.name("createdAt").value(header.createdAt());
            json.name("values").beginArray();
            for (String value : header.values()) {
                json.value(value);
            }
            json.endArray();
            json.name("tables").beginArray();
            for (List<WipeSection> group : byTable.values()) {
                WipeSection first = group.get(0);
                if (!tableExists(c, first.table())) {
                    missing.add(first.table());
                    continue;
                }
                tables++;
                rows += exportTable(c, json, group, key);
            }
            json.endArray();
            json.endObject();
            json.flush();
            out.getFD().sync();
        } catch (IOException | SQLException | RuntimeException e) {
            Files.deleteIfExists(temp.toPath());
            throw e;
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return new Written(target, target.length(), tables, rows);
    }

    private static long exportTable(Connection c, JsonWriter json, List<WipeSection> group, @Nullable String key) throws SQLException, IOException {
        WipeSection first = group.get(0);
        String sql = "SELECT * FROM " + first.table() + (key == null ? "" : " WHERE " + first.keyColumn() + " = ?");
        long count = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (key != null) {
                ps.setString(1, key);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                json.beginObject();
                json.name("sections").beginArray();
                for (WipeSection s : group) {
                    json.value(s.key());
                }
                json.endArray();
                StringJoiner labels = new StringJoiner(", ");
                group.forEach(s -> labels.add(s.label()));
                json.name("label").value(labels.toString());
                json.name("table").value(first.table());
                json.name("keyColumn").value(first.keyColumn());
                json.name("key").value(key);
                json.name("columns").beginArray();
                int[] types = new int[n + 1];
                for (int i = 1; i <= n; i++) {
                    types[i] = meta.getColumnType(i);
                    json.beginObject();
                    json.name("name").value(meta.getColumnName(i).toLowerCase());
                    json.name("type").value(types[i]);
                    json.name("generated").value(meta.isAutoIncrement(i));
                    json.endObject();
                }
                json.endArray();
                json.name("rows").beginArray();
                while (rs.next()) {
                    json.beginArray();
                    for (int i = 1; i <= n; i++) {
                        writeCell(json, rs, i, types[i]);
                    }
                    json.endArray();
                    count++;
                }
                json.endArray();
                json.endObject();
            }
        }
        return count;
    }

    private static void writeCell(JsonWriter json, ResultSet rs, int i, int type) throws SQLException, IOException {
        switch (kind(type)) {
            case INTEGRAL -> {
                long v = rs.getLong(i);
                if (rs.wasNull()) {
                    json.nullValue();
                } else {
                    json.value(v);
                }
            }
            case FLOATING -> {
                double v = rs.getDouble(i);
                if (rs.wasNull()) {
                    json.nullValue();
                } else {
                    json.value(v);
                }
            }
            case BOOLEAN -> {
                boolean v = rs.getBoolean(i);
                if (rs.wasNull()) {
                    json.nullValue();
                } else {
                    json.value(v);
                }
            }
            case BINARY -> {
                byte[] v = rs.getBytes(i);
                if (v == null) {
                    json.nullValue();
                } else {
                    json.value(Base64.getEncoder().encodeToString(v));
                }
            }
            default -> json.value(rs.getString(i));
        }
    }

    private enum Kind { INTEGRAL, FLOATING, BOOLEAN, BINARY, TEXT }

    private static Kind kind(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Kind.INTEGRAL;
            case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> Kind.FLOATING;
            case Types.BOOLEAN, Types.BIT -> Kind.BOOLEAN;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Kind.BINARY;
            default -> Kind.TEXT;
        };
    }

    // ------------------------------------------------------------------ read

    interface TableVisitor {
        void visit(TableRows table) throws SQLException, IOException;
    }

    /** Reads the header only (stops before the rows). */
    Header readHeader(File file) throws IOException {
        Header[] out = new Header[1];
        try {
            read(file, out, null);
        } catch (SQLException impossible) {
            throw new IOException(impossible);
        }
        return out[0];
    }

    /** Reads the header and hands each table's rows to the visitor, one table at a time. */
    Header read(File file, TableVisitor visitor) throws IOException, SQLException {
        Header[] out = new Header[1];
        read(file, out, visitor);
        return out[0];
    }

    private void read(File file, Header[] headerOut, @Nullable TableVisitor visitor) throws IOException, SQLException {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8); JsonReader json = new JsonReader(reader)) {
            int format = 0;
            String kind = "player";
            String wipeId = "";
            String scope = "";
            UUID player = null;
            String playerName = "";
            String actor = "";
            long createdAt = 0;
            List<String> values = new ArrayList<>();
            json.beginObject();
            while (json.hasNext()) {
                String name = json.nextName();
                switch (name) {
                    case "format" -> format = json.nextInt();
                    case "kind" -> kind = json.nextString();
                    case "wipeId" -> wipeId = json.nextString();
                    case "scope" -> scope = json.nextString();
                    case "player" -> {
                        if (json.peek() == JsonToken.NULL) {
                            json.nextNull();
                        } else {
                            player = UUID.fromString(json.nextString());
                        }
                    }
                    case "playerName" -> playerName = json.nextString();
                    case "actor" -> actor = json.nextString();
                    case "createdAt" -> createdAt = json.nextLong();
                    case "values" -> {
                        json.beginArray();
                        while (json.hasNext()) {
                            values.add(json.nextString());
                        }
                        json.endArray();
                    }
                    case "tables" -> {
                        headerOut[0] = new Header(format, kind, wipeId, scope, player, playerName, actor, createdAt, List.copyOf(values));
                        if (visitor == null) {
                            return;
                        }
                        json.beginArray();
                        while (json.hasNext()) {
                            visitor.visit(readTable(json));
                        }
                        json.endArray();
                    }
                    default -> json.skipValue();
                }
            }
            json.endObject();
            if (headerOut[0] == null) {
                headerOut[0] = new Header(format, kind, wipeId, scope, player, playerName, actor, createdAt, List.copyOf(values));
            }
        }
    }

    private static TableRows readTable(JsonReader json) throws IOException {
        List<String> sections = new ArrayList<>();
        String label = "";
        String table = null;
        String keyColumn = null;
        String key = null;
        List<Column> columns = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "sections" -> {
                    json.beginArray();
                    while (json.hasNext()) {
                        sections.add(json.nextString());
                    }
                    json.endArray();
                }
                case "label" -> label = json.nextString();
                case "table" -> table = json.nextString();
                case "keyColumn" -> keyColumn = json.nextString();
                case "key" -> {
                    if (json.peek() == JsonToken.NULL) {
                        json.nextNull();
                    } else {
                        key = json.nextString();
                    }
                }
                case "columns" -> {
                    json.beginArray();
                    while (json.hasNext()) {
                        String name = null;
                        int type = Types.VARCHAR;
                        boolean generated = false;
                        json.beginObject();
                        while (json.hasNext()) {
                            switch (json.nextName()) {
                                case "name" -> name = json.nextString();
                                case "type" -> type = json.nextInt();
                                case "generated" -> generated = json.nextBoolean();
                                default -> json.skipValue();
                            }
                        }
                        json.endObject();
                        columns.add(new Column(name, type, generated));
                    }
                    json.endArray();
                }
                case "rows" -> {
                    json.beginArray();
                    while (json.hasNext()) {
                        Object[] row = new Object[columns.size()];
                        json.beginArray();
                        int i = 0;
                        while (json.hasNext()) {
                            Object cell = readCell(json, i < columns.size() ? columns.get(i).type() : Types.VARCHAR);
                            if (i < row.length) {
                                row[i] = cell;
                            }
                            i++;
                        }
                        json.endArray();
                        rows.add(row);
                    }
                    json.endArray();
                }
                default -> json.skipValue();
            }
        }
        json.endObject();
        return new TableRows(sections, label, table, keyColumn, key, columns, rows);
    }

    private static @Nullable Object readCell(JsonReader json, int type) throws IOException {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return null;
        }
        return switch (kind(type)) {
            case INTEGRAL -> json.nextLong();
            case FLOATING -> json.nextDouble();
            case BOOLEAN -> json.nextBoolean();
            case BINARY -> Base64.getDecoder().decode(json.nextString());
            default -> json.nextString();
        };
    }

    // ------------------------------------------------------------------ restore

    /**
     * Puts one table's rows back: for each player key in it (the backup's player, or every key found in a season
     * backup's rows) the current rows are deleted and the backup's rows inserted. Call inside a transaction.
     */
    static int restore(Connection c, TableRows table) throws SQLException {
        if (!WipeSection.isSafe(table.table()) || !WipeSection.isSafe(table.keyColumn())) {
            throw new SQLException("Backup names a table this build refuses to touch: " + table.table());
        }
        int keyIndex = -1;
        for (int i = 0; i < table.columns().size(); i++) {
            Column column = table.columns().get(i);
            if (!WipeSection.isSafe(column.name())) {
                throw new SQLException("Backup names a column this build refuses to touch: " + column.name());
            }
            if (column.name().equals(table.keyColumn())) {
                keyIndex = i;
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        if (table.key() != null) {
            keys.add(table.key());
        } else if (keyIndex >= 0) {
            for (Object[] row : table.rows()) {
                if (row[keyIndex] != null) {
                    keys.add(String.valueOf(row[keyIndex]));
                }
            }
        }
        try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + table.table() + " WHERE " + table.keyColumn() + " = ?")) {
            for (String key : keys) {
                delete.setString(1, key);
                delete.addBatch();
            }
            delete.executeBatch();
        }
        List<Integer> kept = new ArrayList<>();
        StringJoiner names = new StringJoiner(", ");
        StringJoiner marks = new StringJoiner(", ");
        for (int i = 0; i < table.columns().size(); i++) {
            if (!table.columns().get(i).generated()) {
                kept.add(i);
                names.add(table.columns().get(i).name());
                marks.add("?");
            }
        }
        if (kept.isEmpty() || table.rows().isEmpty()) {
            return 0;
        }
        int inserted = 0;
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table.table() + " (" + names + ") VALUES (" + marks + ")")) {
            for (Object[] row : table.rows()) {
                int p = 1;
                for (int index : kept) {
                    bindCell(insert, p++, row[index], table.columns().get(index).type());
                }
                insert.addBatch();
                inserted++;
                if (inserted % 500 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
        return inserted;
    }

    private static void bindCell(PreparedStatement ps, int index, @Nullable Object value, int type) throws SQLException {
        if (value == null) {
            ps.setNull(index, type);
            return;
        }
        switch (kind(type)) {
            case INTEGRAL -> ps.setLong(index, ((Number) value).longValue());
            case FLOATING -> ps.setDouble(index, ((Number) value).doubleValue());
            case BOOLEAN -> ps.setBoolean(index, (Boolean) value);
            case BINARY -> ps.setBytes(index, (byte[]) value);
            default -> ps.setString(index, String.valueOf(value));
        }
    }

    /** Applies a statement timeout on PostgreSQL so a restore cannot hang forever (BlockvilleCore did the same). */
    static void statementTimeout(Connection c, boolean postgres, long seconds) {
        if (!postgres) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL statement_timeout = " + Math.max(1, seconds) * 1000L);
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
