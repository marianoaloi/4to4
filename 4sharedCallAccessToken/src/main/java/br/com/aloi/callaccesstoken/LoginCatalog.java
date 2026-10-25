package br.com.aloi.callaccesstoken;

import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.domain.LoginType;
import org.bson.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Loads the private login catalog from a local JSON file. */
public final class LoginCatalog {

    private static final Path CATALOG_PATH = Path.of("4sharedCallAccessToken", "logins.json");

    private LoginCatalog() { }

    public static List<LoginEntry4to4> allActiveLogins() {
        return activeLogins(readCatalog());
    }

    static List<LoginEntry4to4> activeLogins(Document catalog) {
        Set<String> bannedLogins = readLogins(catalog, LoginType.BANNED.key()).stream()
                .map(LoginEntry4to4::login)
                .collect(java.util.stream.Collectors.toSet());
        var unique = new LinkedHashMap<String, LoginEntry4to4>();
        for (LoginType type : List.of(LoginType.CURRENT, LoginType.OLD, LoginType.DOWNLOAD)) {
            for (LoginEntry4to4 entry : readLogins(catalog, type.key())) {
                if (!bannedLogins.contains(entry.login())) {
                    unique.putIfAbsent(entry.login(), entry);
                }
            }
        }
        return List.copyOf(unique.values());
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

    private static List<LoginEntry4to4> readLogins(Document catalog, String key) {
        Object value = catalog.get(key);
        if (!(value instanceof List<?> entries)) {
            throw new IllegalStateException("Login catalog field '" + key + "' must be a JSON array of email strings.");
        }
        List<LoginEntry4to4> logins = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof String login) || login.isBlank()) {
                throw new IllegalStateException("Login catalog field '" + key + "' must contain only non-empty strings.");
            }
            logins.add(new LoginEntry4to4(login, LoginType.valueOf(key.toUpperCase())));
        }
        return List.copyOf(logins);
    }
}
