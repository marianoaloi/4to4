# 4shared Token Provider

Independent token access module. It has no dependency on `4sharedjava`, `ClientMaloi`, `GetPullConnections`, or the old OAuth library.

Token values are stored per login in MongoDB under `accounts.accessor.accessToken` and `accounts.accessor.tokenSecret`. This provider reads those fields directly using the MongoDB Java Sync Driver and keeps the old 50-minute accessor freshness rule. If the account/accessor is missing, incomplete, or stale, it starts OAuth 1.0a: requests a temporary token, opens the 4shared authorization page, receives the verifier on a short-lived localhost callback, exchanges it for access credentials, and upserts the accessor into MongoDB. The browser step requires the user to sign in and approve access; no password is embedded or submitted by this project.

Consumer key/secret, MongoDB URI, and account login are configuration values and are not embedded in source. OAuth secrets are redacted from object `toString()` and CLI output.

## Configuration

Required environment variables:

```powershell
$env:FOURSHARED_MONGODB_URI = 'mongodb://user:password@host:27017/4shared?authSource=admin'
$env:FOURSHARED_CONSUMER_KEY = 'your-consumer-key'
$env:FOURSHARED_CONSUMER_SECRET = 'your-consumer-secret'
```

Optional:

| Variable | Default |
|---|---|
| `FOURSHARED_DATABASE` | `4shared` |
| `FOURSHARED_ACCOUNTS_COLLECTION` | `accounts` |
| `FOURSHARED_TOKEN_MAX_AGE_MINUTES` | `50` |
| `FOURSHARED_AUTH_TIMEOUT_MINUTES` | `5` |
| `FOURSHARED_REQUEST_TOKEN_URL` | `https://api.4shared.com/v1_2/oauth/initiate` |
| `FOURSHARED_AUTHORIZATION_URL` | `https://api.4shared.com/v1_2/oauth/authorize` |
| `FOURSHARED_ACCESS_TOKEN_URL` | `https://api.4shared.com/v1_2/oauth/token` |
| `FOURSHARED_USER_URL` | `https://api.4shared.com/v1_2/user` |

Use a secret manager or shell-level secure injection for values. Do not commit credentials or put them in command arguments. The provider verifies that the profile email returned by 4shared matches the selected login before saving the token pair and profile under that account's `accessor`.

## Build and check one login

From the project root:

```powershell
mvn -pl 4sharedTokenProvider -am package
java -jar 4sharedTokenProvider/target/4shared-token-provider-1.0.0-jar-with-dependencies.jar 'login-from-the-accounts-collection'
```

The check reports whether credentials are available but never prints token values. Unit tests cover legacy document mapping, missing fields, freshness, and redaction.
