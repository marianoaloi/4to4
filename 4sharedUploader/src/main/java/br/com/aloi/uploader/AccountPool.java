package br.com.aloi.uploader;

import br.com.aloi.domain.AccountCredentials;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.domain.LoginType;
import br.com.aloi.tokenprovider.CredentialProvider;
import br.com.aloi.tokenprovider.ProviderSettings;
import br.com.aloi.tokenprovider.ResolvedAccount;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** JVM-wide in-memory view of account tokens and available upload capacity. */
@Slf4j
final class AccountPool {
    private static AccountPool instance;

    private final ProviderSettings settings;
    private final CredentialProvider provider;
    private final List<AccountState> accounts;

    static synchronized AccountPool getInstance() {
        if (instance == null) {
            ProviderSettings settings = ProviderSettings.fromEnvironment();
            MongoClient client = MongoClients.create(settings.mongoUri());
            try {
                instance = new AccountPool(settings, client);
                Runtime.getRuntime().addShutdownHook(new Thread(client::close, "4shared-account-pool-shutdown"));
            } catch (RuntimeException error) {
                client.close();
                throw error;
            }
        }
        return instance;
    }

    private AccountPool(ProviderSettings settings, MongoClient mongoClient) {
        this.settings = settings;
        this.provider = new CredentialProvider(settings, mongoClient);
        this.accounts = provider.loadAccounts().stream().map(AccountState::new).toList();
        log.info("Loaded {} eligible account(s) into the in-memory upload pool.", accounts.size());
    }

    Reservation reserve(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Upload size must be greater than zero");
        }
        List<AccountState> refreshed = new ArrayList<>(accounts);
        for (AccountState account : refreshed) {
            account.refreshIfExpired(provider, settings.maxTokenAge());
        }
        refreshed.sort(Comparator.comparingLong(AccountState::availableBytes));
        for (AccountState account : refreshed) {
            Reservation reservation = account.reserve(sizeBytes);
            if (reservation != null) {
                return reservation;
            }
        }
        throw new IllegalStateException("No pooled account has enough available space for this file");
    }

    private static final class AccountState {
        private AccountCredentials credentials;
        private java.time.Instant tokenUpdatedAt;
        private long availableBytes;
        private long inFlightBytes;

        AccountState(ResolvedAccount resolved) {
            this.credentials = resolved.credentials();
            this.tokenUpdatedAt = resolved.tokenUpdatedAt();
            this.availableBytes = credentials.freeSizeBytes() == null ? 0 : credentials.freeSizeBytes();
        }

        synchronized void refreshIfExpired(CredentialProvider provider, java.time.Duration maxAge) {
            if (java.time.Duration.between(tokenUpdatedAt, java.time.Instant.now()).compareTo(maxAge) <= 0) {
                return;
            }
            try {
                ResolvedAccount refreshed = provider.resolveSnapshot(
                        new LoginEntry4to4(credentials.login(), LoginType.CURRENT));
                credentials = refreshed.credentials();
                tokenUpdatedAt = refreshed.tokenUpdatedAt();
                long remoteFree = credentials.freeSizeBytes() == null ? 0 : credentials.freeSizeBytes();
                availableBytes = Math.max(0, remoteFree - inFlightBytes);
                log.info("Refreshed a pooled account token and its free-space value.");
            } catch (RuntimeException refreshFailure) {
                availableBytes = 0;
                log.warn("A pooled account could not be refreshed and was skipped ({}).",
                        refreshFailure.getClass().getSimpleName());
            }
        }

        synchronized long availableBytes() {
            return availableBytes;
        }

        synchronized Reservation reserve(long sizeBytes) {
            if (availableBytes < sizeBytes) {
                return null;
            }
            availableBytes -= sizeBytes;
            inFlightBytes = Math.addExact(inFlightBytes, sizeBytes);
            return new Reservation(this, credentials, sizeBytes);
        }

        synchronized void finish(long sizeBytes, boolean success) {
            inFlightBytes -= sizeBytes;
            if (!success) {
                availableBytes = Long.MAX_VALUE - availableBytes < sizeBytes
                        ? Long.MAX_VALUE : availableBytes + sizeBytes;
            }
        }
    }

    static final class Reservation {
        private final AccountState owner;
        private final AccountCredentials credentials;
        private final long sizeBytes;
        private boolean completed;

        private Reservation(AccountState owner, AccountCredentials credentials, long sizeBytes) {
            this.owner = owner;
            this.credentials = credentials;
            this.sizeBytes = sizeBytes;
        }

        AccountCredentials credentials() {
            return credentials;
        }

        synchronized void complete() {
            finish(true);
        }

        synchronized void rollback() {
            finish(false);
        }

        private void finish(boolean success) {
            if (!completed) {
                owner.finish(sizeBytes, success);
                completed = true;
            }
        }
    }
}
