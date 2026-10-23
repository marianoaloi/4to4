package br.com.aloi.callaccesstoken;

import org.bson.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Loads the private login catalog from a local JSON file. */
public final class LoginCatalog {
    private static final Path CATALOG_PATH = Path.of("4sharedCallAccessToken", "logins.json");

    private LoginCatalog() { }

    public static List<String> allActiveLogins() {
        Document catalog = readCatalog();
        List<String> banned = readLogins(catalog, "banned");
        var unique = new LinkedHashSet<String>();
        unique.addAll(readLogins(catalog, "current"));
        unique.addAll(readLogins(catalog, "old"));
        unique.addAll(readLogins(catalog, "download"));
        unique.removeAll(Set.copyOf(banned));
        return List.copyOf(unique);
    }

    private static Document readCatalog() {
        if (!Files.isRegularFile(CATALOG_PATH)) {
            throw new IllegalStateException("Login catalog not found at " + CATALOG_PATH
                    + ". Copy 4sharedCallAccessToken/logins.example.json to logins.json and fill in the private email lists.");
        }
        try {
            return Document.parse(Files.readString(CATALOG_PATH));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read login catalog at " + CATALOG_PATH + ". Check that it is valid JSON.", e);
        }
    }

    private static List<String> readLogins(Document catalog, String key) {
        Object value = catalog.get(key);
        if (!(value instanceof List<?> entries)) {
            throw new IllegalStateException("Login catalog field '" + key + "' must be a JSON array of email strings.");
        }
        List<String> logins = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String login) || login.isBlank()) {
                throw new IllegalStateException("Login catalog field '" + key + "' must contain only non-empty strings.");
            }
            logins.add(login);
        }
        return List.copyOf(logins);
    }
}
