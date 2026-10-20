package com.yqsoft.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "zhiheng")
public class AppProperties {

    private String environment = "DEV";
    private boolean jobsEnabled = true;
    private Security security = new Security();
    private Storage storage = new Storage();
    private Ai ai = new Ai();
    private List<String> corsAllowedOrigins = new ArrayList<>();

    @Data
    public static class Security {
        private String encryptionKey = "";
        private String hmacKey = "";
        private Path keyFile = Path.of(".local/zhiheng.keys");
        private boolean requireExternalKeys;
        private String jwtIssuer = "zhiheng";
    }

    @Data
    public static class Storage {
        private Path root = Path.of(".local/files");
        private long uploadMaxBytes = 20 * 1024 * 1024;
    }

    @Data
    public static class Ai {
        private boolean enabled;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int timeoutSeconds = 60;
    }
}
