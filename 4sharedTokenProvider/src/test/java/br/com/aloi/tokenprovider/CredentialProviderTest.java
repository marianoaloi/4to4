package br.com.aloi.tokenprovider;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class CredentialProviderTest {
    private final ProviderSettings settings = new ProviderSettings(
            "mongodb://localhost:27017", "4shared", "accounts", "consumer-key", "consumer-secret",
            Duration.ofMinutes(50));

    @Test
    void readsLegacyAccessorFieldsAndRootFolder() {
        Document account = new Document("rootFolderId", "root-123")
                .append("accessor", new Document("accessToken", "token-value")
                        .append("tokenSecret", "token-secret")
                        .append("created", Date.from(Instant.parse("2026-10-16T12:00:00Z"))));

        AccountCredentials result = CredentialProvider.fromAccountDocument("user@example.test", account,
                settings, Instant.parse("2026-10-16T12:20:00Z"));

        assertEquals("token-value", result.accessToken());
        assertEquals("token-secret", result.accessTokenSecret());
        assertEquals("root-123", result.rootFolderId());
        assertEquals("consumer-key", result.consumerKey());
    }

    @Test
    void rejectsIncompleteOAuthPair() {
        Document account = new Document("accessor", new Document("accessToken", "only-token"));
        assertThrows(CredentialException.class, () -> CredentialProvider.fromAccountDocument(
                "user@example.test", account, settings, Instant.now()));
    }

    @Test
    void rejectsStaleAccessorUsingLegacyFiftyMinuteRule() {
        Document account = new Document("accessor", new Document("accessToken", "token")
                .append("tokenSecret", "secret")
                .append("created", Date.from(Instant.parse("2026-10-16T12:00:00Z"))));

        CredentialException error = assertThrows(CredentialException.class,
                () -> CredentialProvider.fromAccountDocument("user@example.test", account, settings,
                        Instant.parse("2026-10-16T12:51:00Z")));
        assertTrue(error.getMessage().contains("reauthorization"));
    }

    @Test
    void credentialStringNeverContainsSecretValues() {
        AccountCredentials result = new AccountCredentials("user", "key", "consumer-secret", "token",
                "token-secret", "root");
        assertFalse(result.toString().contains("consumer-secret"));
        assertFalse(result.toString().contains("token-secret"));
        assertFalse(result.toString().contains("token"));
    }

    @Test
    void parsesOAuthCallbackQueryWithoutLosingPlusCharacters() {
        var callback = OAuthAuthorizationClient.parseForm("oauth_token=a%2Bb&oauth_verifier=x%2Fy%3D");
        assertEquals("a+b", callback.get("oauth_token"));
        assertEquals("x/y=", callback.get("oauth_verifier"));
    }
}
