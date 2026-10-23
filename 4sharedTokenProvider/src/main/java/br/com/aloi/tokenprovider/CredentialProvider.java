package br.com.aloi.tokenprovider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;

/** Reads existing account accessor fields without depending on the legacy Java application. */
public final class CredentialProvider {
    private final ProviderSettings settings;
    private final MongoClient client;
    private final Clock clock;

    public CredentialProvider(ProviderSettings settings, MongoClient client) {
        this(settings, client, Clock.systemUTC());
    }

    CredentialProvider(ProviderSettings settings, MongoClient client, Clock clock) {
        this.settings = settings;
        this.client = client;
        this.clock = clock;
    }

    public AccountCredentials resolve(String login) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("A 4shared login is required");
        }
        String normalizedLogin = login.trim();
        MongoCollection<Document> accounts = client.getDatabase(settings.database())
                .getCollection(settings.accountCollection());
        Document account = accounts.find(eq("_id", normalizedLogin)).first();
        if (!requiresAuthorization(account, settings, clock.instant())) {
            return fromAccountDocument(normalizedLogin, account, settings, clock.instant());
        }

        OAuthAuthorizationClient authorization = new OAuthAuthorizationClient(settings);
        List<Document> cookies = account == null
                ? List.of()
                : account.getList("cookies", Document.class, List.of());
        OAuthAuthorizationClient.TokenPair tokens = authorization.authorize(normalizedLogin, cookies);
        Document user = authorization.getUser(tokens);
        String authorizedLogin = user.getString("email");
        if (authorizedLogin == null || !normalizedLogin.equalsIgnoreCase(authorizedLogin.trim())) {
            throw new CredentialException("The 4shared account authorized in the browser does not match the selected login");
        }
        Document accessor = new Document("created", Date.from(clock.instant()))
                .append("accessToken", tokens.accessToken())
                .append("tokenSecret", tokens.accessTokenSecret())
                .append("requestToken", tokens.requestToken())
                .append("user", user)
                .append("freeSize", user.get("freeSpace"));
        accounts.updateOne(eq("_id", normalizedLogin),
                new Document("$set", new Document("accessor", accessor)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
        return new AccountCredentials(normalizedLogin, settings.consumerKey(), settings.consumerSecret(),
                tokens.accessToken(), tokens.accessTokenSecret(), user.getString("rootFolderId"));
    }

    static boolean requiresAuthorization(Document account, ProviderSettings settings, Instant now) {
        if (account == null) {
            return true;
        }
        try {
            fromAccountDocument("account-check", account, settings, now);
            return false;
        } catch (RuntimeException unusableAccessor) {
            return true;
        }
    }

    static AccountCredentials fromAccountDocument(String login, Document account,
                                                  ProviderSettings settings, Instant now) {
        Document accessor = account.get("accessor", Document.class);
        if (accessor == null) {
            throw new CredentialException("Account record has no OAuth accessor");
        }
        String accessToken = accessor.getString("accessToken");
        String accessTokenSecret = accessor.getString("tokenSecret");
        if (blank(accessToken) || blank(accessTokenSecret)) {
            throw new CredentialException("Account record has no complete OAuth token pair");
        }

        Object createdValue = accessor.get("created");
        if (createdValue instanceof Date created) {
            Duration age = Duration.between(created.toInstant(), now);
            if (!age.isNegative() && age.compareTo(settings.maxTokenAge()) > 0) {
                throw new CredentialException("Stored OAuth accessor is older than the configured freshness window; "
                        + "reauthorization is required");
            }
        }

        String rootFolderId = account.getString("rootFolderId");
        Document user = accessor.get("user", Document.class);
        if (blank(rootFolderId) && user != null) {
            rootFolderId = user.getString("rootFolderId");
        }
        return new AccountCredentials(login, settings.consumerKey(), settings.consumerSecret(),
                accessToken, accessTokenSecret, rootFolderId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
