package br.com.aloi.tokenprovider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;

/** Reads existing account accessor fields without depending on the legacy Java application. */
public final class CredentialProvider {
    private final ProviderSettings settings;
    private final Clock clock;

    public CredentialProvider(ProviderSettings settings) {
        this(settings, Clock.systemUTC());
    }

    CredentialProvider(ProviderSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public AccountCredentials resolve(String login) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("A 4shared login is required");
        }
        String normalizedLogin = login.trim();
        try (MongoClient client = MongoClients.create(settings.mongoUri())) {
            MongoCollection<Document> accounts = client.getDatabase(settings.database())
                    .getCollection(settings.accountCollection());
            Document account = accounts.find(eq("_id", normalizedLogin)).first();
            if (account != null) {
                try {
                    return fromAccountDocument(normalizedLogin, account, settings, clock.instant());
                } catch (CredentialException missingOrExpired) {
                    // The legacy client creates an accessor and starts OAuth when the account or accessor is absent/stale.
                }
            }

            OAuthAuthorizationClient authorization = new OAuthAuthorizationClient(settings);
            OAuthAuthorizationClient.TokenPair tokens = authorization.authorize(normalizedLogin);
            Document accessor = new Document("created", Date.from(clock.instant()))
                    .append("accessToken", tokens.accessToken())
                    .append("tokenSecret", tokens.accessTokenSecret())
                    .append("requestToken", tokens.requestToken());
            accounts.updateOne(eq("_id", normalizedLogin),
                    new Document("$set", new Document("accessor", accessor)),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));

            String rootFolderId = null;
            try {
                Document user = authorization.getUser(tokens);
                accessor.append("user", user);
                rootFolderId = user.getString("rootFolderId");
                accounts.updateOne(eq("_id", normalizedLogin),
                        new Document("$set", new Document("accessor.user", user)));
            } catch (RuntimeException userLookupFailed) {
                System.err.println("OAuth tokens were saved, but 4shared user details could not be loaded. "
                        + "Set FOURSHARED_ROOT_FOLDER_ID before uploading.");
            }
            return new AccountCredentials(normalizedLogin, settings.consumerKey(), settings.consumerSecret(),
                    tokens.accessToken(), tokens.accessTokenSecret(), rootFolderId);
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
