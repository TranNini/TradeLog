package tradelog;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

// Prints where to open the app and the access code once the server is running
// (on the NAS: docker logs tradelog)
@Component
public class StartupInfo implements ApplicationListener<WebServerInitializedEvent> {
    private final AccessCode accessCode;

    public StartupInfo(AccessCode accessCode) {
        this.accessCode = accessCode;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        System.out.println();
        System.out.println("=== TradeLog is running on port " + event.getWebServer().getPort() + " ===");
        System.out.println("Access code: " + accessCode.getCode());
        System.out.println();
    }
}
