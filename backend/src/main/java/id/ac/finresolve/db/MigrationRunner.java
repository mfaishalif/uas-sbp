package id.ac.finresolve.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Small transactional SQL migration runner.
 *
 * <p>Production uses a fixed ordered resource list. Tests supply their own ordered list so
 * checksum, rollback, and multi-statement migration behavior are exercised without mocks.
 */
public final class MigrationRunner {

    private static final List<String> PRODUCTION_RESOURCES = List.of(
            "db/migration/V001__initial.sql",
            "db/migration/V002__immutable_provenance_and_timestamps.sql");

    private static final String CREATE_SCHEMA_VERSIONS =
            """
            CREATE TABLE IF NOT EXISTS schema_versions (
                version INTEGER PRIMARY KEY,
                checksum TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
            """;

    private final Database database;
    private final List<Migration> migrations;

    public MigrationRunner(Database database, List<Migration> migrations) {
        this.database = database;
        this.migrations = List.copyOf(migrations);
        int previousVersion = 0;
        for (Migration migration : this.migrations) {
            if (migration.version() <= previousVersion) {
                throw new MigrationException(
                        "Migrations must be strictly ordered by increasing version.");
            }
            previousVersion = migration.version();
        }
    }

    public static List<Migration> productionMigrations() {
        return loadClasspathMigrations(PRODUCTION_RESOURCES);
    }

    public static List<Migration> loadClasspathMigrations(List<String> resourceNames) {
        List<Migration> loaded = new ArrayList<>();
        for (String resourceName : resourceNames) {
            ClassPathResource resource = new ClassPathResource(resourceName);
            if (!resource.exists()) {
                throw new MigrationException("Missing migration resource: " + resourceName);
            }
            try (InputStream input = resource.getInputStream()) {
                loaded.add(new Migration(
                        versionOf(resourceName),
                        nameOf(resourceName),
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (IOException failure) {
                throw new MigrationException("Cannot read migration resource: " + resourceName, failure);
            }
        }
        return List.copyOf(loaded);
    }

    public void migrate() {
        JdbcTemplate jdbc = database.jdbcTemplate();
        database.write(() -> {
            jdbc.execute(CREATE_SCHEMA_VERSIONS);
            return null;
        });

        Map<Integer, String> applied = database.read(() -> {
            List<Map.Entry<Integer, String>> rows = jdbc.query(
                    "SELECT version, checksum FROM schema_versions ORDER BY version",
                    (resultSet, rowNumber) -> Map.entry(
                            resultSet.getInt("version"), resultSet.getString("checksum")));
            Map<Integer, String> result = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> row : rows) {
                result.put(row.getKey(), row.getValue());
            }
            return result;
        });

        Map<Integer, Migration> byVersion = new LinkedHashMap<>();
        for (Migration migration : migrations) {
            byVersion.put(migration.version(), migration);
        }

        for (Map.Entry<Integer, String> entry : applied.entrySet()) {
            Migration migration = byVersion.get(entry.getKey());
            if (migration == null) {
                throw new MigrationException(
                        "Database has applied migration version " + entry.getKey()
                                + " that this application does not know about.");
            }
            if (!checksum(migration).equals(entry.getValue())) {
                throw new MigrationException(
                        "Checksum changed for already-applied migration " + migration.version()
                                + " (" + migration.name() + ").");
            }
        }

        for (Migration migration : migrations) {
            if (applied.containsKey(migration.version())) {
                continue;
            }
            String checksum = checksum(migration);
            try {
                database.write(() -> {
                    for (String statement : SqlSplitter.split(migration.sql())) {
                        jdbc.execute(statement);
                    }
                    jdbc.update(
                            "INSERT INTO schema_versions (version, checksum, applied_at) VALUES (?, ?, ?)",
                            migration.version(),
                            checksum,
                            Instant.now().toString());
                    return null;
                });
            } catch (RuntimeException failure) {
                throw new MigrationException(
                        "Migration " + migration.version() + " (" + migration.name()
                                + ") failed; its DDL and version row were rolled back.",
                        failure);
            }
        }
    }

    private static String checksum(Migration migration) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(migration.sql().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private static int versionOf(String resourceName) {
        String fileName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        int separator = fileName.indexOf("__");
        if (!fileName.startsWith("V") || separator < 2) {
            throw new MigrationException("Invalid migration resource name: " + resourceName);
        }
        try {
            return Integer.parseInt(fileName.substring(1, separator));
        } catch (NumberFormatException failure) {
            throw new MigrationException("Invalid migration version in: " + resourceName, failure);
        }
    }

    private static String nameOf(String resourceName) {
        String fileName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        int separator = fileName.indexOf("__");
        String name = fileName.substring(separator + 2);
        return name.endsWith(".sql") ? name.substring(0, name.length() - 4) : name;
    }

    public record Migration(int version, String name, String sql) {
        public Migration {
            if (version <= 0) {
                throw new IllegalArgumentException("Migration version must be positive.");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Migration name is required.");
            }
            if (sql == null || sql.isBlank()) {
                throw new IllegalArgumentException("Migration SQL is required.");
            }
        }
    }

    /**
     * Splits a script into individual statements while keeping a trigger's BEGIN/END body intact.
     * SQLite JDBC executes only the first statement when a trigger body contains semicolons.
     */
    static final class SqlSplitter {

        private SqlSplitter() {
        }

        static List<String> split(String sql) {
            return new Parser().parse(sql);
        }

        private static final class Parser {
            private final StringBuilder current = new StringBuilder();
            private final StringBuilder word = new StringBuilder();
            private final List<String> statements = new ArrayList<>();
            private boolean singleQuote;
            private boolean doubleQuote;
            private boolean backtick;
            private boolean bracket;
            private boolean lineComment;
            private boolean blockComment;
            private int caseDepth;
            private boolean trigger;
            private boolean triggerEndSeen;

            List<String> parse(String sql) {
                for (int index = 0; index < sql.length(); index++) {
                    char character = sql.charAt(index);

                    if (lineComment) {
                        current.append(character);
                        if (character == '\n') {
                            lineComment = false;
                        }
                        continue;
                    }
                    if (blockComment) {
                        current.append(character);
                        if (character == '*'
                                && index + 1 < sql.length()
                                && sql.charAt(index + 1) == '/') {
                            current.append('/');
                            index++;
                            blockComment = false;
                        }
                        continue;
                    }
                    if (singleQuote) {
                        current.append(character);
                        if (character == '\'') {
                            if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                                current.append('\'');
                                index++;
                            } else {
                                singleQuote = false;
                            }
                        }
                        continue;
                    }
                    if (doubleQuote) {
                        current.append(character);
                        if (character == '"') {
                            if (index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                                current.append('"');
                                index++;
                            } else {
                                doubleQuote = false;
                            }
                        }
                        continue;
                    }
                    if (backtick) {
                        current.append(character);
                        if (character == '`') {
                            if (index + 1 < sql.length() && sql.charAt(index + 1) == '`') {
                                current.append('`');
                                index++;
                            } else {
                                backtick = false;
                            }
                        }
                        continue;
                    }
                    if (bracket) {
                        current.append(character);
                        if (character == ']') {
                            bracket = false;
                        }
                        continue;
                    }

                    if (character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                        flushWord();
                        current.append("--");
                        index++;
                        lineComment = true;
                        continue;
                    }
                    if (character == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                        flushWord();
                        current.append("/*");
                        index++;
                        blockComment = true;
                        continue;
                    }
                    if (character == '\'') {
                        flushWord();
                        current.append(character);
                        singleQuote = true;
                        continue;
                    }
                    if (character == '"') {
                        flushWord();
                        current.append(character);
                        doubleQuote = true;
                        continue;
                    }
                    if (character == '`') {
                        flushWord();
                        current.append(character);
                        backtick = true;
                        continue;
                    }
                    if (character == '[') {
                        flushWord();
                        current.append(character);
                        bracket = true;
                        continue;
                    }
                    if (Character.isLetterOrDigit(character) || character == '_') {
                        current.append(character);
                        word.append(character);
                        continue;
                    }

                    flushWord();
                    if (character == ';') {
                        if (trigger && !triggerEndSeen) {
                            current.append(character);
                            continue;
                        }
                        addStatement();
                        continue;
                    }
                    current.append(character);
                }
                flushWord();
                addStatement();
                return List.copyOf(statements);
            }

            private void flushWord() {
                if (word.isEmpty()) {
                    return;
                }
                String wordText = word.toString();
                String before = current.substring(0, current.length() - wordText.length()).trim();
                String upper = wordText.toUpperCase(Locale.ROOT);
                if (!trigger && upper.equals("TRIGGER") && isCreatePrefix(before)) {
                    trigger = true;
                } else if (upper.equals("CASE")) {
                    caseDepth++;
                } else if (upper.equals("END")) {
                    if (caseDepth > 0) {
                        caseDepth--;
                    } else if (trigger) {
                        triggerEndSeen = true;
                    }
                }
                word.setLength(0);
            }

            private void addStatement() {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                word.setLength(0);
                caseDepth = 0;
                trigger = false;
                triggerEndSeen = false;
            }

            private static boolean isCreatePrefix(String before) {
                String withoutComments = before
                        .replaceAll("(?s)/\\*.*?\\*/", " ")
                        .replaceAll("(?m)--[^\\n]*", " ")
                        .trim();
                return withoutComments.equalsIgnoreCase("CREATE")
                        || withoutComments.equalsIgnoreCase("CREATE TEMP")
                        || withoutComments.equalsIgnoreCase("CREATE TEMPORARY");
            }
        }
    }
}
