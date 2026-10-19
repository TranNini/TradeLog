package tradelog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// A copy of the items and pictures every night, kept for tradelog.backup-days days:
//   <backup dir>/2026-10-20/tradelog.db   the database as it was that night (and the access code file)
//   <backup dir>/images/                  every picture ever uploaded. Pictures never change (a new picture gets
//                                         a new name), so the database of every day finds its pictures here.
// To restore a day, stop the app and copy that day's tradelog.db and the images it needs back (see CLAUDE.md).
// On the NAS the backups are on the same disk as the data: they help against mistakes (a deleted item, a broken
// update), not against a broken disk.
@Component
public class Backup {
    private static final Logger log = LoggerFactory.getLogger(Backup.class);

    private final JdbcClient jdbc;
    private final Path dir;
    private final Path imagesDir;
    private final Path accessCodeFile;
    private final int days;

    public Backup(JdbcClient jdbc,
                  @Value("${tradelog.backup-dir}") Path dir,
                  @Value("${tradelog.images-dir}") Path imagesDir,
                  @Value("${tradelog.access-code-file}") Path accessCodeFile,
                  @Value("${tradelog.backup-days}") int days) {
        this.jdbc = jdbc;
        this.dir = dir;
        this.imagesDir = imagesDir;
        this.accessCodeFile = accessCodeFile;
        this.days = days;
    }

    // 03:30, before the NAS updates and restarts the app at 05:00
    @Scheduled(cron = "0 30 3 * * *")
    public void nightly() {
        try {
            run();
        } catch (IOException | RuntimeException e) {
            log.error("Backup failed", e);
        }
    }

    // e.g. when the app was not running at 03:30
    @EventListener(ApplicationReadyEvent.class)
    public void afterStart() {
        if (!Files.exists(dayDir(LocalDate.now()).resolve("tradelog.db"))) {
            nightly();
        }
    }

    // Makes today's backup (again) and deletes the old ones; returns today's folder
    public synchronized Path run() throws IOException {
        LocalDate today = LocalDate.now();
        Path day = dayDir(today);
        Files.createDirectories(day);

        // VACUUM INTO writes a consistent copy even while the app is in use; it refuses an existing file
        Path copy = day.resolve("tradelog.db.new");
        Files.deleteIfExists(copy);
        jdbc.sql("VACUUM INTO :file").param("file", copy.toAbsolutePath().toString()).update();
        Files.move(copy, day.resolve("tradelog.db"), StandardCopyOption.REPLACE_EXISTING);

        if (Files.exists(accessCodeFile)) {
            Files.copy(accessCodeFile, day.resolve("access-code.txt"), StandardCopyOption.REPLACE_EXISTING);
        }
        int newPictures = copyNewPictures();
        int deleted = deleteDaysBefore(today.minusDays(days - 1));
        log.info("Backup saved in {} ({} new pictures, {} old days deleted)", day, newPictures, deleted);
        return day;
    }

    private Path dayDir(LocalDate date) {
        return dir.resolve(date.toString());
    }

    private int copyNewPictures() throws IOException {
        Path target = dir.resolve("images");
        Files.createDirectories(target);
        if (!Files.isDirectory(imagesDir)) {
            return 0;
        }
        int copied = 0;
        try (Stream<Path> pictures = Files.list(imagesDir)) {
            for (Path picture : pictures.filter(Files::isRegularFile).toList()) {
                Path backup = target.resolve(picture.getFileName());
                if (!Files.exists(backup)) {
                    Files.copy(picture, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        }
        return copied;
    }

    // Only folders named like a date are touched
    private int deleteDaysBefore(LocalDate first) throws IOException {
        int deleted = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.filter(Files::isDirectory).toList()) {
                LocalDate date;
                try {
                    date = LocalDate.parse(entry.getFileName().toString());
                } catch (DateTimeParseException e) {
                    continue; // images, or something that isn't ours
                }
                if (date.isBefore(first)) {
                    deleteFolder(entry);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private static void deleteFolder(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            List<Path> deepestFirst = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                Files.delete(path);
            }
        }
    }
}
