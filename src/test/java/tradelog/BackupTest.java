package tradelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class BackupTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataInTempDir(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dataDir.resolve("test.db"));
        registry.add("tradelog.images-dir", () -> dataDir.resolve("images").toString());
        registry.add("tradelog.access-code-file", () -> dataDir.resolve("access-code.txt").toString());
        registry.add("tradelog.backup-dir", () -> dataDir.resolve("backups").toString());
        registry.add("tradelog.backup-days", () -> "30");
    }

    @Autowired
    Backup backup;

    @Autowired
    JdbcClient jdbc;

    @Test
    void copiesTheDatabaseAndPicturesAndDeletesOldDays() throws Exception {
        jdbc.sql("DELETE FROM items").update();
        jdbc.sql("INSERT INTO items (type, name, price_cents, image) VALUES ('SOLD', 'Lamp', 1200, 'lamp.jpg')").update();
        Files.writeString(dataDir.resolve("images/lamp.jpg"), "picture");
        Path backups = dataDir.resolve("backups");
        LocalDate today = LocalDate.now();
        Path old = Files.createDirectories(backups.resolve(today.minusDays(30).toString()));
        Path kept = Files.createDirectories(backups.resolve(today.minusDays(29).toString()));
        Files.writeString(old.resolve("tradelog.db"), "old");

        Path day = backup.run();

        assertEquals(backups.resolve(today.toString()), day);
        try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + day.resolve("tradelog.db"));
             ResultSet row = copy.createStatement().executeQuery("SELECT name, image FROM items")) {
            assertTrue(row.next());
            assertEquals("Lamp", row.getString("name"));
            assertEquals("lamp.jpg", row.getString("image"));
        }
        assertEquals("picture", Files.readString(backups.resolve("images/lamp.jpg")));
        assertTrue(Files.exists(day.resolve("access-code.txt")));
        assertFalse(Files.exists(old), "older than 30 days");
        assertTrue(Files.exists(kept), "the 30th day is kept");

        // a second backup the same day replaces it; pictures deleted from the app stay in the backup
        jdbc.sql("DELETE FROM items").update();
        Files.delete(dataDir.resolve("images/lamp.jpg"));
        backup.run();
        try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + day.resolve("tradelog.db"));
             ResultSet count = copy.createStatement().executeQuery("SELECT count(*) FROM items")) {
            assertEquals(0, count.getInt(1));
        }
        assertTrue(Files.exists(backups.resolve("images/lamp.jpg")));
        assertFalse(Files.exists(day.resolve("tradelog.db.new")));
    }
}
