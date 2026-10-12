package tradelog;

import java.math.BigDecimal;
import java.time.YearMonth;

// Something that was sold or bought. place = where (eBay, Vinted …); image = file name in the
// images folder, or null. date is only a month and year (2026-10).
// category, place, date and notes are null when left empty.
public record Item(Long id, ItemType type, String name, String category, BigDecimal price, String place,
                   YearMonth date, String notes, String image) {

    Item withId(long id) {
        return new Item(id, type, name, category, price, place, date, notes, image);
    }

    Item withImage(String image) {
        return new Item(id, type, name, category, price, place, date, notes, image);
    }
}
