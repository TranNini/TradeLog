package tradelog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataInTempDir(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dataDir.resolve("test.db"));
        registry.add("tradelog.images-dir", () -> dataDir.resolve("images").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.sql("DELETE FROM items").update();
    }

    private long add(String type, String name, String category, String price) throws Exception {
        String json = """
                {"type": "%s", "name": "%s", "category": "%s", "price": %s, "place": "Vinted", "date": "2026-10-01"}
                """.formatted(type, name, category, price);
        String response = mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void addsUpSoldAndBoughtItems() throws Exception {
        add("SOLD", "Winter jacket", "Clothes", "45.50");
        add("SOLD", "Headphones", "Electronics", "30");
        add("BOUGHT", "Sneakers", "Clothes", "20.25");

        mvc.perform(get("/api/items"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.totals.sold").value(75.50))
                .andExpect(jsonPath("$.totals.bought").value(20.25))
                .andExpect(jsonPath("$.totals.balance").value(55.25))
                .andExpect(jsonPath("$.totals.soldCount").value(2));
    }

    @Test
    void filtersByTypeCategoryAndSearch() throws Exception {
        add("SOLD", "Winter jacket", "Clothes", "45.50");
        add("SOLD", "Headphones", "Electronics", "30");
        add("BOUGHT", "Sneakers", "Clothes", "20.25");

        // the totals ignore the type, so the bought sum stays visible
        mvc.perform(get("/api/items").param("type", "SOLD").param("category", "clothes"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Winter jacket"))
                .andExpect(jsonPath("$.totals.sold").value(45.50))
                .andExpect(jsonPath("$.totals.bought").value(20.25));

        mvc.perform(get("/api/items").param("q", "PHONES"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Headphones"));

        mvc.perform(get("/api/options"))
                .andExpect(jsonPath("$.categories", hasSize(2)))
                .andExpect(jsonPath("$.places[0]").value("Vinted"));
    }

    @Test
    void updatesAndDeletesItems() throws Exception {
        long id = add("SOLD", "Lamp", "Home", "12");

        mvc.perform(put("/api/items/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "SOLD", "name": "Desk lamp", "category": " ", "price": 15.999}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Desk lamp"))
                .andExpect(jsonPath("$.category").value(nullValue()))
                .andExpect(jsonPath("$.price").value(16.00));

        mvc.perform(delete("/api/items/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/items")).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(delete("/api/items/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMissingValues() throws Exception {
        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "SOLD", "name": "", "price": 5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Please enter a name."));

        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "SOLD", "name": "Lamp", "price": -1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storesAndRemovesPictures() throws Exception {
        long id = add("SOLD", "Lamp", "Home", "12");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

        mvc.perform(multipart("/api/items/" + id + "/image")
                        .file(new MockMultipartFile("file", "lamp.png", "image/png", png)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(endsWith(".png")));

        mvc.perform(get("/api/items/" + id + "/image"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));

        mvc.perform(multipart("/api/items/" + id + "/image")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/items/" + id)).andExpect(status().isNoContent());
        try (var files = Files.list(dataDir.resolve("images"))) {
            assertEquals(0, files.count(), "deleting the item deletes its picture");
        }
    }
}
