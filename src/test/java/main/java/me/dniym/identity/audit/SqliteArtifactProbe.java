package main.java.me.dniym.identity.audit;

import java.sql.Driver;
import java.util.Properties;

/** Runs with only the final plugin JAR, not Gradle's unshaded SQLite dependency. */
public final class SqliteArtifactProbe {
    public static void main(String[] args) throws Exception {
        Driver driver = (Driver) Class.forName("org.sqlite.JDBC").getConstructor().newInstance();
        try (var connection = driver.connect("jdbc:sqlite::memory:", new Properties());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE verification (id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO verification VALUES (1,'ok')");
            try (var rows = statement.executeQuery("SELECT sqlite_version(),value FROM verification")) {
                if (!rows.next() || !"ok".equals(rows.getString(2))) throw new AssertionError("SQLite failed");
                System.out.println("Embedded JDBC + native SQLite " + rows.getString(1) + ": OK");
            }
        }
    }
}
