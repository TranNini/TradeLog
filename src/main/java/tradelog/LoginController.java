package tradelog;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;

@Controller
public class LoginController {
    private final AccessCode accessCode;

    public LoginController(AccessCode accessCode) {
        this.accessCode = accessCode;
    }

    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String code, HttpServletResponse response) {
        if (!accessCode.tryLogin(code)) {
            return "redirect:/login.html?wrong";
        }
        ResponseCookie cookie = ResponseCookie.from(AccessFilter.COOKIE, accessCode.getCode())
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(365))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        return "redirect:/";
    }
}
