package tradelog;

import java.math.BigDecimal;
import java.time.LocalDate;

// Something that was sold or bought. place = where (eBay, Vinted …); image = file name in the
// images folder, or null. category, place and notes are null when left empty.
public record Item(Long id, ItemType type, String name, String category, BigDecimal price, String place,
                   LocalDate date, String notes, String image) {

    Item withId(long id) {
        return new Item(id, type, name, category, price, place, date, notes, image);
    }

    Item withImage(String image) {
        return new Item(id, type, name, category, price, place, date, notes, image);
    }
}
