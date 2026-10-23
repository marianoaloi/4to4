# 4sharedCallAccessToken

Resolves credentials for the active login catalog by calling `CredentialProvider.resolve(login)`. Existing accessors are read from MongoDB; missing or stale accessors trigger the provider's OAuth authorization flow and are saved back to MongoDB. OAuth values are never printed.

## Build and run

From the `4shared_connection` root:

```powershell
mvn -pl 4sharedCallAccessToken -am clean package
java -jar .\4sharedCallAccessToken\target\4sharedCallAccessToken-1.0.0-jar-with-dependencies.jar --limit 1
```

The default run processes all active entries from `4sharedCallAccessToken/logins.json`. This file contains private email addresses and is excluded by the repository `.gitignore`. Create it by copying `4sharedCallAccessToken/logins.example.json`, then add the addresses to the `banned`, `current`, `download`, and `old` arrays. `--dry-run` prints the number of logins without connecting to MongoDB, `--limit N` restricts the number processed, and `--login EMAIL` resolves one catalog entry.

Configure `FOURSHARED_MONGODB_URI`, `FOURSHARED_CONSUMER_KEY`, and `FOURSHARED_CONSUMER_SECRET` as described in the `4sharedTokenProvider` README. The command delegates account lookup, authorization, token exchange, and storage to `CredentialProvider.resolve(login)`.
