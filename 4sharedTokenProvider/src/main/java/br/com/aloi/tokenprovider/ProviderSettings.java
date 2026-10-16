package br.com.aloi.tokenprovider;

import java.time.Duration;
import java.net.URI;

public record ProviderSettings(
        String mongoUri,
        String database,
        String accountCollection,
        String consumerKey,
        String consumerSecret,
        Duration maxTokenAge) {

    public static ProviderSettings fromEnvironment() {
        String mongoUri = required("FOURSHARED_MONGODB_URI");
        if (!mongoUri.startsWith("mongodb://") && !mongoUri.startsWith("mongodb+srv://")) {
            throw new IllegalArgumentException("FOURSHARED_MONGODB_URI must use mongodb:// or mongodb+srv://");
        }
        long maxAgeMinutes;
        try {
            maxAgeMinutes = Long.parseLong(value("FOURSHARED_TOKEN_MAX_AGE_MINUTES", "50"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FOURSHARED_TOKEN_MAX_AGE_MINUTES must be a whole number", e);
        }
        if (maxAgeMinutes < 1 || maxAgeMinutes > 1440) {
            throw new IllegalArgumentException("FOURSHARED_TOKEN_MAX_AGE_MINUTES must be between 1 and 1440");
        }
        return new ProviderSettings(mongoUri,
                value("FOURSHARED_DATABASE", "4shared"),
                value("FOURSHARED_ACCOUNTS_COLLECTION", "accounts"),
                required("FOURSHARED_CONSUMER_KEY"),
                required("FOURSHARED_CONSUMER_SECRET"),
                Duration.ofMinutes(maxAgeMinutes));
    }

    URI requestTokenUri() {
        return endpoint("FOURSHARED_REQUEST_TOKEN_URL", "https://api.4shared.com/v1_2/oauth/initiate");
    }

    URI authorizationUri() {
        return endpoint("FOURSHARED_AUTHORIZATION_URL", "https://api.4shared.com/v1_2/oauth/authorize");
    }

    URI accessTokenUri() {
        return endpoint("FOURSHARED_ACCESS_TOKEN_URL", "https://api.4shared.com/v1_2/oauth/token");
    }

    URI userUri() {
        return endpoint("FOURSHARED_USER_URL", "https://api.4shared.com/v1_2/user");
    }

    Duration authorizationTimeout() {
        try {
            long minutes = Long.parseLong(value("FOURSHARED_AUTH_TIMEOUT_MINUTES", "5"));
            if (minutes < 1 || minutes > 30) {
                throw new IllegalArgumentException("FOURSHARED_AUTH_TIMEOUT_MINUTES must be between 1 and 30");
            }
            return Duration.ofMinutes(minutes);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FOURSHARED_AUTH_TIMEOUT_MINUTES must be a whole number", e);
        }
    }

    private static URI endpoint(String key, String fallback) {
        URI endpoint = URI.create(value(key, fallback));
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalArgumentException(key + " must be an absolute HTTPS URL");
        }
        return endpoint;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable " + key);
        }
        return value.trim();
    }

    private static String value(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
