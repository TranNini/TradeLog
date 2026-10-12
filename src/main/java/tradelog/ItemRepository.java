package tradelog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

// Reads and writes the items table (see schema.sql)
@Repository
public class ItemRepository {
    private final JdbcClient jdbc;

    public ItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Newest first; items without a date last
    public List<Item> findAll() {
        return jdbc.sql("SELECT * FROM items ORDER BY date DESC, id DESC")
                .query(ItemRepository::map)
                .list();
    }

    public Optional<Item> findById(long id) {
        return jdbc.sql("SELECT * FROM items WHERE id = :id")
                .param("id", id)
                .query(ItemRepository::map)
                .optional();
    }

    public Item insert(Item item) {
        KeyHolder keys = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO items (type, name, category, price_cents, place, date, notes, image)
                VALUES (:type, :name, :category, :priceCents, :place, :date, :notes, :image)
                """), item)
                .update(keys);
        return item.withId(keys.getKey().longValue());
    }

    // Changes everything except the picture, see setImage
    public void update(Item item) {
        bind(jdbc.sql("""
                UPDATE items SET type = :type, name = :name, category = :category, price_cents = :priceCents,
                                 place = :place, date = :date, notes = :notes
                WHERE id = :id
                """), item)
                .param("id", item.id())
                .update();
    }

    public void setImage(long id, String image) {
        jdbc.sql("UPDATE items SET image = :image WHERE id = :id")
                .param("image", image)
                .param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM items WHERE id = :id").param("id", id).update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Item item) {
        return statement
                .param("type", item.type().name())
                .param("name", item.name())
                .param("category", item.category())
                .param("priceCents", item.price().movePointRight(2).longValueExact())
                .param("place", item.place())
                .param("date", item.date() == null ? null : item.date().toString())
                .param("notes", item.notes())
                .param("image", item.image());
    }

    private static Item map(ResultSet row, int rowNumber) throws SQLException {
        return new Item(
                row.getLong("id"),
                ItemType.valueOf(row.getString("type")),
                row.getString("name"),
                row.getString("category"),
                BigDecimal.valueOf(row.getLong("price_cents"), 2),
                row.getString("place"),
                row.getString("date") == null ? null : YearMonth.parse(row.getString("date")),
                row.getString("notes"),
                row.getString("image"));
    }
}
