package br.com.aloi.uploader;

import br.com.aloi.domain.AccountCredentials;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.domain.LoginType;
import br.com.aloi.tokenprovider.CredentialProvider;
import br.com.aloi.tokenprovider.ProviderSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.net.URI;
import java.time.Duration;

record Config(String consumerKey, String consumerSecret, String accessToken,
              String accessTokenSecret, String rootFolderId, URI uploadUri,
              Duration requestTimeout) {

    @Override
    public String toString() {
        return "Config[consumerKey=[redacted], consumerSecret=[redacted], accessToken=[redacted], "
                + "accessTokenSecret=[redacted], rootFolderId=[redacted], uploadUri=" + uploadUri
                + ", requestTimeout=" + requestTimeout + "]";
    }

    static Config fromEnvironment() {
        String login = System.getenv("FOURSHARED_LOGIN");
        if (login != null && !login.isBlank()) {
            ProviderSettings settings = ProviderSettings.fromEnvironment();
            AccountCredentials credentials;
            try (MongoClient client = MongoClients.create(settings.mongoUri())) {
                credentials = new CredentialProvider(settings, client)
                        .resolve(new LoginEntry4to4(login, LoginType.CURRENT));
            }
            String rootFolderId = value("FOURSHARED_ROOT_FOLDER_ID", credentials.rootFolderId());
            if (rootFolderId == null || rootFolderId.isBlank()) {
                throw new IllegalArgumentException("Could not determine the 4shared root folder ID");
            }
            URI uploadUri = URI.create(value("FOURSHARED_UPLOAD_URL", "https://upload.4shared.com/v1_2/files"));
            long timeoutSeconds = timeoutSeconds();
            requireHttps(uploadUri, "FOURSHARED_UPLOAD_URL");
            return new Config(credentials.consumerKey(), credentials.consumerSecret(),
                    credentials.accessToken(), credentials.accessTokenSecret(), rootFolderId,
                    uploadUri, Duration.ofSeconds(timeoutSeconds));
        }

        String consumerKey = required("FOURSHARED_CONSUMER_KEY");
        String consumerSecret = required("FOURSHARED_CONSUMER_SECRET");
        String accessToken = required("FOURSHARED_ACCESS_TOKEN");
        String accessTokenSecret = required("FOURSHARED_ACCESS_TOKEN_SECRET");
        String rootFolderId = required("FOURSHARED_ROOT_FOLDER_ID");
        URI uploadUri = URI.create(value("FOURSHARED_UPLOAD_URL", "https://upload.4shared.com/v1_2/files"));
        long timeoutSeconds = timeoutSeconds();
        requireHttps(uploadUri, "FOURSHARED_UPLOAD_URL");
        return new Config(consumerKey, consumerSecret, accessToken, accessTokenSecret,
                rootFolderId, uploadUri, Duration.ofSeconds(timeoutSeconds));
    }

    private static long timeoutSeconds() {
        try {
            long timeoutSeconds = Long.parseLong(value("FOURSHARED_TIMEOUT_SECONDS", "120"));
            if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
                throw new IllegalArgumentException("FOURSHARED_TIMEOUT_SECONDS must be between 1 and 3600");
            }
            return timeoutSeconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("FOURSHARED_TIMEOUT_SECONDS must be an integer", e);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable " + name);
        }
        return value.trim();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void requireHttps(URI uri, String name) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTPS URL");
        }
    }
}
