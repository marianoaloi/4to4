package br.com.aloi.tokenprovider;

import br.com.aloi.domain.AccountCredentials;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.domain.LoginType;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;

/** Reads existing account accessor fields without depending on the legacy Java application. */
@Slf4j
public final class CredentialProvider {
    private static final int FREE_SPACE_UPDATE_ATTEMPTS = 5;

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

    public AccountCredentials resolve(LoginEntry4to4 login) {
        return resolveSnapshot(login).credentials();
    }

    /** Loads eligible accounts with one collection scan and refreshes stale token pairs once. */
    public List<ResolvedAccount> loadAccounts() {
        MongoCollection<Document> accounts = client.getDatabase(settings.database())
                .getCollection(settings.accountCollection());
        List<ResolvedAccount> loaded = new ArrayList<>();
        for (Document account : accounts.find()) {
            Object id = account.get("_id");
            Document accessor = account.get("accessor", Document.class);
            if (!(id instanceof String login) || login.isBlank() || !hasTokenPair(accessor)) {
                continue;
            }
            Object createdValue = accessor.get("created");
            if (!(createdValue instanceof Date created)) {
                continue;
            }
            try {
                Instant updatedAt = created.toInstant();
                if (Duration.between(updatedAt, clock.instant()).compareTo(settings.maxTokenAge()) > 0) {
                    loaded.add(resolveSnapshot(new LoginEntry4to4(login, LoginType.CURRENT)));
                } else {
                    loaded.add(new ResolvedAccount(
                            fromAccountDocument(login, account, settings, clock.instant()), updatedAt));
                }
            } catch (RuntimeException refreshFailure) {
                log.warn("Could not load account credentials into the upload pool ({}).",
                        refreshFailure.getClass().getSimpleName());
            }
        }
        return List.copyOf(loaded);
    }

    public Duration maxTokenAge() {
        return settings.maxTokenAge();
    }

    public ResolvedAccount resolveSnapshot(LoginEntry4to4 login) {
        if (login == null || login.login() == null || login.login().isBlank()) {
            throw new IllegalArgumentException("A 4shared login is required");
        }
        String normalizedLogin = login.login().trim();
        MongoCollection<Document> accounts = client.getDatabase(settings.database())
                .getCollection(settings.accountCollection());
        Document account = accounts.find(eq("_id", normalizedLogin)).first();
        if (!requiresAuthorization(account, settings, clock.instant())) {
            AccountCredentials credentials = fromAccountDocument(normalizedLogin, account, settings, clock.instant());
            Date created = account.get("accessor", Document.class).getDate("created");
            return new ResolvedAccount(credentials, created.toInstant());
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
        log.info("OAuth credentials were refreshed and stored.");
        String rootFolderId = user.getString("rootFolderId");
        if (blank(rootFolderId) && account != null) {
            rootFolderId = account.getString("rootFolderId");
            Document previousAccessor = account.get("accessor", Document.class);
            Document previousUser = previousAccessor == null ? null : previousAccessor.get("user", Document.class);
            if (blank(rootFolderId) && previousUser != null) {
                rootFolderId = previousUser.getString("rootFolderId");
            }
        }
        AccountCredentials credentials = new AccountCredentials(normalizedLogin, settings.consumerKey(), settings.consumerSecret(),
                tokens.accessToken(), tokens.accessTokenSecret(), rootFolderId,
                sizeInBytes(user.get("freeSpace")));
        return new ResolvedAccount(credentials, clock.instant());
    }

    private static boolean hasTokenPair(Document accessor) {
        return accessor != null && !blank(accessor.getString("accessToken"))
                && !blank(accessor.getString("tokenSecret"));
    }

    /** Refreshes stale accessors, then chooses the fitting account with the least free space. */
    public AccountCredentials resolveBestFit(long uploadSizeBytes) {
        if (uploadSizeBytes <= 0) {
            throw new IllegalArgumentException("Upload size must be greater than zero");
        }

        MongoCollection<Document> accounts = client.getDatabase(settings.database())
                .getCollection(settings.accountCollection());
        List<String> candidates = new ArrayList<>();
        for (Document account : accounts.find()) {
            Object id = account.get("_id");
            Document accessor = account.get("accessor", Document.class);
            Long freeSize = accessor == null ? null : sizeInBytes(accessor.get("freeSize"));
            if (id instanceof String login && !login.isBlank() && freeSize != null) {
                candidates.add(login);
            }
        }

        // Resolve before selection so an accessor older than the configured 50-minute window
        // is renewed and its updated freeSize is used in the plan.
        List<AccountCredentials> refreshed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String candidate : candidates) {
            try {
                AccountCredentials credentials = resolve(new LoginEntry4to4(candidate, LoginType.CURRENT));
                if (credentials.freeSizeBytes() != null) {
                    refreshed.add(credentials);
                }
            } catch (RuntimeException error) {
                failures.add(candidate);
            }
        }

        List<AccountCredentials> fitting = refreshed.stream()
                .filter(credentials -> credentials.freeSizeBytes() >= uploadSizeBytes)
                .sorted(Comparator.comparingLong(AccountCredentials::freeSizeBytes))
                .toList();
        for (AccountCredentials candidate : fitting) {
            AccountCredentials reserved = reserveFreeSpace(accounts, candidate, uploadSizeBytes);
            if (reserved != null) {
                return reserved;
            }
        }

        throw new CredentialException(failures.isEmpty()
                ? "No account has enough free space for this file"
                : "No account has enough available space; credential refresh failed for "
                        + String.join(", ", failures));
    }

    /** Restores a free-space reservation when the corresponding upload did not complete. */
    public void restoreReservedSpace(String login, long uploadSizeBytes) {
        if (blank(login)) {
            throw new IllegalArgumentException("A 4shared login is required to restore reserved space");
        }
        if (uploadSizeBytes <= 0) {
            throw new IllegalArgumentException("Restored upload size must be greater than zero");
        }

        MongoCollection<Document> accounts = client.getDatabase(settings.database())
                .getCollection(settings.accountCollection());
        String normalizedLogin = login.trim();
        for (int attempt = 0; attempt < FREE_SPACE_UPDATE_ATTEMPTS; attempt++) {
            Document account = accounts.find(eq("_id", normalizedLogin)).first();
            Document accessor = account == null ? null : account.get("accessor", Document.class);
            Object currentValue = accessor == null ? null : accessor.get("freeSize");
            Long currentSize = sizeInBytes(currentValue);
            if (currentSize == null) {
                throw new CredentialException("Could not restore reserved space because the account free-size value is unavailable");
            }

            final long restoredSize;
            try {
                restoredSize = Math.addExact(currentSize, uploadSizeBytes);
            } catch (ArithmeticException e) {
                throw new CredentialException("Could not restore reserved space because the account free-size value overflowed", e);
            }

            var result = accounts.updateOne(
                    and(eq("_id", normalizedLogin), eq("accessor.freeSize", currentValue)),
                    set("accessor.freeSize", restoredSize));
            if (result.getModifiedCount() == 1) {
                log.info("Reserved account free space was restored after an unsuccessful upload.");
                return;
            }
        }
        throw new CredentialException("Could not restore reserved space after concurrent account updates");
    }

    private AccountCredentials reserveFreeSpace(MongoCollection<Document> accounts,
                                                AccountCredentials candidate,
                                                long uploadSizeBytes) {
        for (int attempt = 0; attempt < FREE_SPACE_UPDATE_ATTEMPTS; attempt++) {
            Document account = accounts.find(eq("_id", candidate.login())).first();
            Document accessor = account == null ? null : account.get("accessor", Document.class);
            Object currentValue = accessor == null ? null : accessor.get("freeSize");
            Long currentSize = sizeInBytes(currentValue);
            if (currentSize == null || currentSize < uploadSizeBytes) {
                return null;
            }

            long remainingSize = currentSize - uploadSizeBytes;
            var result = accounts.updateOne(
                    and(eq("_id", candidate.login()), eq("accessor.freeSize", currentValue)),
                    set("accessor.freeSize", remainingSize));
            if (result.getModifiedCount() == 1) {
                return new AccountCredentials(candidate.login(), candidate.consumerKey(),
                        candidate.consumerSecret(), candidate.accessToken(), candidate.accessTokenSecret(),
                        candidate.rootFolderId(), remainingSize);
            }
        }
        return null;
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
        if (!(createdValue instanceof Date created)) {
            throw new CredentialException("Stored OAuth accessor has no valid creation timestamp; "
                    + "reauthorization is required");
        }
        Duration age = Duration.between(created.toInstant(), now);
        if (!age.isNegative() && age.compareTo(settings.maxTokenAge()) > 0) {
            throw new CredentialException("Stored OAuth accessor is older than the configured freshness window; "
                    + "reauthorization is required");
        }

        String rootFolderId = account.getString("rootFolderId");
        Document user = accessor.get("user", Document.class);
        if (blank(rootFolderId) && user != null) {
            rootFolderId = user.getString("rootFolderId");
        }
        return new AccountCredentials(login, settings.consumerKey(), settings.consumerSecret(),
                accessToken, accessTokenSecret, rootFolderId, sizeInBytes(accessor.get("freeSize")));
    }

    private static Long sizeInBytes(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric) || numeric < 0 || numeric > Long.MAX_VALUE) {
                return null;
            }
            return number.longValue();
        }
        if (value instanceof org.bson.types.Decimal128 decimal) {
            try {
                return decimal.bigDecimalValue().longValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.trim());
                return parsed < 0 ? null : parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
