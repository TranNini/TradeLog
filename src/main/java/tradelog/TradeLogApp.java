package tradelog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Starts the app on http://localhost:8081
@SpringBootApplication
@EnableScheduling // the nightly Backup
public class TradeLogApp {
    public static void main(String[] args) {
        SpringApplication.run(TradeLogApp.class, args);
    }
}
