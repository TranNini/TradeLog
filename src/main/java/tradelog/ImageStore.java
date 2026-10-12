package tradelog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

// The item pictures, one file each in the images folder. The database only stores the file name.
@Component
public class ImageStore {
    // The types every browser can show
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp");

    private final Path dir;

    public ImageStore(@Value("${tradelog.images-dir}") Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public static boolean isSupported(String contentType) {
        return EXTENSIONS.containsKey(contentType);
    }

    // Returns the new file name. Every picture gets a new name, so browsers never show an old one.
    public String save(MultipartFile file) throws IOException {
        String name = UUID.randomUUID() + "." + EXTENSIONS.get(file.getContentType());
        file.transferTo(dir.resolve(name).toAbsolutePath());
        return name;
    }

    public Path path(String name) {
        return dir.resolve(name);
    }

    public void delete(String name) throws IOException {
        if (name != null) {
            Files.deleteIfExists(dir.resolve(name));
        }
    }
}
