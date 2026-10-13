package tradelog;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// Brings databases created by older versions up to date with schema.sql
// (schema.sql only creates missing tables, it never changes existing ones)
@Component
public class SchemaMigration implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public SchemaMigration(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dateIsRequired() || !forSaleIsAllowed()) {
            rebuildTable();
        }
        dropDays();
    }

    // Until 2026-10-12 dates had a day (2026-10-12); now they are only a month and year (2026-10)
    private void dropDays() {
        jdbc.sql("UPDATE items SET date = substr(date, 1, 7) WHERE length(date) > 7").update();
    }

    // Until 2026-10-12 every item needed a date (date TEXT NOT NULL)
    boolean dateIsRequired() {
        return jdbc.sql("SELECT \"notnull\" FROM pragma_table_info('items') WHERE name = 'date'")
                .query(Integer.class)
                .optional()
                .orElse(0) == 1;
    }

    // Until 2026-10-13 items could only be SOLD or BOUGHT (CHECK (type IN ('SOLD', 'BOUGHT')))
    boolean forSaleIsAllowed() {
        return jdbc.sql("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'items'")
                .query(String.class)
                .optional()
                .map(sql -> sql.contains("'FOR_SALE'"))
                .orElse(true);
    }

    // SQLite can't change a column's NOT NULL or CHECK, so the table is copied into a new one as in schema.sql
    private void rebuildTable() {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                    CREATE TABLE items_new (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        type        TEXT    NOT NULL CHECK (type IN ('SOLD', 'BOUGHT', 'FOR_SALE')),
                        name        TEXT    NOT NULL,
                        category    TEXT,
                        price_cents INTEGER NOT NULL,
                        place       TEXT,
                        date        TEXT,
                        notes       TEXT,
                        image       TEXT
                    )
                    """).update();
            jdbc.sql("""
                    INSERT INTO items_new (id, type, name, category, price_cents, place, date, notes, image)
                    SELECT id, type, name, category, price_cents, place, date, notes, image FROM items
                    """).update();
            jdbc.sql("DROP TABLE items").update();
            jdbc.sql("ALTER TABLE items_new RENAME TO items").update();
        });
    }
}
