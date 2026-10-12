package tradelog;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/api")
public class ItemController {
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000");

    private final ItemRepository items;
    private final ImageStore images;

    public ItemController(ItemRepository items, ImageStore images) {
        this.items = items;
        this.images = images;
    }

    // date may be left out (null): the item then has no date
    record ItemRequest(ItemType type, String name, String category, BigDecimal price, String place,
                       LocalDate date, String notes) {
    }

    // imageUrl is null when the item has no picture
    record ItemDto(long id, ItemType type, String name, String category, BigDecimal price, String place,
                   LocalDate date, String notes, String imageUrl) {
        static ItemDto of(Item item) {
            // the file name in the URL makes browsers load a replaced picture again
            String imageUrl = item.image() == null ? null
                    : "/api/items/" + item.id() + "/image?v=" + item.image();
            return new ItemDto(item.id(), item.type(), item.name(), item.category(), item.price(), item.place(),
                    item.date(), item.notes(), imageUrl);
        }
    }

    // balance = sold - bought
    record Totals(BigDecimal sold, BigDecimal bought, BigDecimal balance, int soldCount, int boughtCount) {
    }

    record ItemList(List<ItemDto> items, Totals totals) {
    }

    // What was entered before, to suggest it again
    record Options(List<String> categories, List<String> places) {
    }

    static class ApiException extends RuntimeException {
        final HttpStatus status;

        ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    // type = null for sold and bought; category = "" for all; q = search text, "" for no search.
    // The totals add up the items in the category and search, whatever the type, so both sums
    // stay visible when only sold or only bought items are listed.
    @GetMapping("/items")
    public ItemList list(@RequestParam(required = false) ItemType type,
                         @RequestParam(defaultValue = "") String category,
                         @RequestParam(defaultValue = "") String q) {
        List<Item> matching = items.findAll().stream()
                .filter(item -> category.isBlank() || category.equalsIgnoreCase(item.category()))
                .filter(item -> matches(item, q))
                .toList();
        List<ItemDto> shown = matching.stream()
                .filter(item -> type == null || item.type() == type)
                .map(ItemDto::of)
                .toList();
        return new ItemList(shown, totals(matching));
    }

    @GetMapping("/options")
    public Options options() {
        TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> places = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Item item : items.findAll()) {
            if (item.category() != null) categories.add(item.category());
            if (item.place() != null) places.add(item.place());
        }
        return new Options(List.copyOf(categories), List.copyOf(places));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto create(@RequestBody ItemRequest request) {
        return ItemDto.of(items.insert(toItem(null, request, null)));
    }

    @PutMapping("/items/{id}")
    public ItemDto update(@PathVariable long id, @RequestBody ItemRequest request) {
        Item item = toItem(id, request, find(id).image());
        items.update(item);
        return ItemDto.of(item);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) throws IOException {
        Item item = find(id);
        items.delete(id);
        images.delete(item.image());
    }

    // Adds or replaces the picture
    @PostMapping(value = "/items/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ItemDto uploadImage(@PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
        Item item = find(id);
        if (!ImageStore.isSupported(file.getContentType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please choose a JPG, PNG, GIF or WebP picture.");
        }
        String name = images.save(file);
        items.setImage(id, name);
        images.delete(item.image());
        return ItemDto.of(item.withImage(name));
    }

    @DeleteMapping("/items/{id}/image")
    public ItemDto deleteImage(@PathVariable long id) throws IOException {
        Item item = find(id);
        items.setImage(id, null);
        images.delete(item.image());
        return ItemDto.of(item.withImage(null));
    }

    @GetMapping("/items/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable long id) {
        Item item = find(id);
        if (item.image() == null || !Files.exists(images.path(item.image()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "This item has no picture.");
        }
        Path path = images.path(item.image());
        MediaType type = MediaTypeFactory.getMediaType(path.toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(path));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    // e.g. a date or type the JSON can't be read as
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Please check the entered values."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "That picture is too big (25 MB at most)."));
    }

    private Item find(long id) {
        return items.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This item doesn't exist (anymore)."));
    }

    private static Item toItem(Long id, ItemRequest request, String image) {
        if (request.type() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please choose sold or bought.");
        }
        String name = clean(request.name());
        if (name == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please enter a name.");
        }
        BigDecimal price = request.price();
        if (price == null || price.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please enter a price of 0 or more.");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That price is too high.");
        }
        return new Item(id, request.type(), name, clean(request.category()), price.setScale(2, RoundingMode.HALF_UP),
                clean(request.place()), request.date(), clean(request.notes()), image);
    }

    // Trimmed, or null when empty
    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static boolean matches(Item item, String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        for (String field : new String[]{item.name(), item.category(), item.place(), item.notes()}) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static Totals totals(List<Item> items) {
        BigDecimal sold = BigDecimal.ZERO.setScale(2);
        BigDecimal bought = BigDecimal.ZERO.setScale(2);
        int soldCount = 0;
        int boughtCount = 0;
        for (Item item : items) {
            if (item.type() == ItemType.SOLD) {
                sold = sold.add(item.price());
                soldCount++;
            } else {
                bought = bought.add(item.price());
                boughtCount++;
            }
        }
        return new Totals(sold, bought, sold.subtract(bought), soldCount, boughtCount);
    }
}
