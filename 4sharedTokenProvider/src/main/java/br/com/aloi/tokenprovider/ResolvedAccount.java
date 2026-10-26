package br.com.aloi.tokenprovider;

import br.com.aloi.domain.AccountCredentials;
import java.time.Instant;

/** Credentials and the timestamp used to enforce the configured OAuth freshness window. */
public record ResolvedAccount(AccountCredentials credentials, Instant tokenUpdatedAt) {
}
