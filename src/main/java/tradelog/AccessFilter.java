package tradelog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;

// This Mac can always use the app. Other devices need the access code once;
// after that a cookie remembers it.
@Component
public class AccessFilter extends OncePerRequestFilter {
    public static final String COOKIE = "tradelog_access";
    // What the login page itself needs, plus what iOS fetches for the home screen icon
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/login", "/login.html", "/style.css", "/manifest.webmanifest",
            "/icon.svg", "/icon-192.png", "/icon-512.png", "/apple-touch-icon.png", "/favicon.ico");

    private final AccessCode accessCode;

    public AccessFilter(AccessCode accessCode) {
        this.accessCode = accessCode;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isFromThisMac(request) || PUBLIC_PATHS.contains(request.getRequestURI()) || hasValidCookie(request)) {
            chain.doFilter(request, response);
        } else if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Please enter the access code.\"}");
        } else {
            response.sendRedirect("/login.html");
        }
    }

    private static boolean isFromThisMac(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasValidCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(COOKIE) && accessCode.matches(cookie.getValue())) {
                return true;
            }
        }
        return false;
    }
}
