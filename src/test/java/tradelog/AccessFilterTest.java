package tradelog;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccessFilterTest {
    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataInTempDir(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dataDir.resolve("test.db"));
        registry.add("tradelog.images-dir", () -> dataDir.resolve("images").toString());
        registry.add("tradelog.access-code-file", () -> dataDir.resolve("access-code.txt").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessCode accessCode;

    // A phone in the home network instead of this Mac
    private static final RequestPostProcessor PHONE = request -> {
        request.setRemoteAddr("192.168.178.50");
        return request;
    };

    @Test
    void otherDevicesNeedTheCode() throws Exception {
        mvc.perform(get("/api/items").with(PHONE)).andExpect(status().isUnauthorized());
        mvc.perform(get("/").with(PHONE)).andExpect(redirectedUrl("/login.html"));
        mvc.perform(get("/login.html").with(PHONE)).andExpect(status().isOk());
        mvc.perform(get("/apple-touch-icon.png").with(PHONE)).andExpect(status().isOk());
    }

    @Test
    void theRightCodeGivesACookie() throws Exception {
        mvc.perform(post("/login").with(PHONE).param("code", "wrong-code"))
                .andExpect(redirectedUrl("/login.html?wrong"));

        mvc.perform(post("/login").with(PHONE).param("code", accessCode.getCode().toUpperCase()))
                .andExpect(redirectedUrl("/"))
                .andExpect(cookie().value(AccessFilter.COOKIE, accessCode.getCookieKey()))
                .andExpect(cookie().secure(AccessFilter.COOKIE, true));

        mvc.perform(get("/api/items").with(PHONE).cookie(new Cookie(AccessFilter.COOKIE, accessCode.getCookieKey())))
                .andExpect(status().isOk());
    }

    // The cookie holds a long random key, not the code, so codes can't be tried there without the login limit
    @Test
    void theCodeItselfIsNoCookie() throws Exception {
        mvc.perform(get("/api/items").with(PHONE).cookie(new Cookie(AccessFilter.COOKIE, accessCode.getCode())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tooManyWrongCodesLockTheLogin() {
        AccessCode code = new AccessCode(dataDir.resolve("lock-test.txt").toString());
        for (int i = 0; i < 10; i++) {
            assertFalse(code.tryLogin("wrong"));
        }
        assertFalse(code.tryLogin(code.getCode()), "locked for 10 minutes, even with the right code");
    }

    @Test
    void anOldCodeFileKeepsItsCodeAndGetsAKey() throws Exception {
        Path file = dataDir.resolve("old-code.txt");
        Files.writeString(file, "abcd2345\n");
        AccessCode code = new AccessCode(file.toString());
        assertEquals("abcd2345", code.getCode());
        assertTrue(code.getCookieKey().length() >= 40);
        assertEquals(code.getCookieKey(), new AccessCode(file.toString()).getCookieKey(), "the key stays the same");
    }

    @Test
    void thisMacNeedsNoCode() throws Exception {
        mvc.perform(get("/api/items")).andExpect(status().isOk());
    }
}
