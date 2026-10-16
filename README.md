# 4shared Connection

Independent Java 21 Maven project for retrieving existing 4shared OAuth credentials and uploading files. The project has two modules:

- `4sharedTokenProvider` reads an account's stored OAuth accessor directly from MongoDB.
- `4sharedUploader` signs and sends uploads and creates a runnable JAR containing its runtime dependencies.

Neither module imports, builds, or starts the legacy `4sharedjava`, `4sharedOauthOld`, or `ClientMaloi` application. The account document is read directly using the existing data shape: `_id` is the login, OAuth values are in `accessor.accessToken` and `accessor.tokenSecret`, and the optional root folder is `accessor.user.rootFolderId`. The prior 50-minute freshness window is preserved. If no usable accessor exists, the provider runs OAuth 1.0a in a browser, waits for a localhost callback, exchanges the verifier, and saves the resulting accessor with an upsert. The user completes sign-in and authorization in the browser; no old password or Node/Puppeteer dependency is used.

## Build

Requires JDK 21 and Maven 3.8 or newer.

```powershell
mvn clean verify
```

The standalone uploader is produced at:

```text
4sharedUploader/target/4shared-uploader-1.0.0-jar-with-dependencies.jar
```

## Upload

For database-backed credentials, configure these environment variables (use secure environment injection for secrets):

```powershell
$env:FOURSHARED_LOGIN = 'account-login'
$env:FOURSHARED_MONGODB_URI = 'mongodb://user:password@host:27017/?authSource=admin'
$env:FOURSHARED_CONSUMER_KEY = 'consumer-key'
$env:FOURSHARED_CONSUMER_SECRET = 'consumer-secret'
```

Then run:

```powershell
java -jar .\4sharedUploader\target\4shared-uploader-1.0.0-jar-with-dependencies.jar 'C:\path\to\file-or-folder'
```

Database defaults are database `4shared`, collection `accounts`, and a 50-minute token lifetime. Override with `FOURSHARED_DATABASE`, `FOURSHARED_ACCOUNTS_COLLECTION`, and `FOURSHARED_TOKEN_MAX_AGE_MINUTES`. `FOURSHARED_ROOT_FOLDER_ID` can override the account's root folder.

For a new account, the first run opens the configured 4shared authorization page and waits up to five minutes for the browser redirect to localhost. Configure `FOURSHARED_AUTH_TIMEOUT_MINUTES` and OAuth endpoint variables if needed. Before saving, the provider confirms that the 4shared profile email matches `FOURSHARED_LOGIN`. If the profile does not include a root-folder ID, set `FOURSHARED_ROOT_FOLDER_ID` to upload.

Alternatively, provide `FOURSHARED_CONSUMER_KEY`, `FOURSHARED_CONSUMER_SECRET`, `FOURSHARED_ACCESS_TOKEN`, `FOURSHARED_ACCESS_TOKEN_SECRET`, and `FOURSHARED_ROOT_FOLDER_ID` directly, without setting `FOURSHARED_LOGIN`.

The uploader accepts one file or directory, walks directories recursively, keeps local files, and uses a randomized remote filename unless `--name REMOTE_NAME` is provided for one file. Upload endpoint and timeout can be configured with `FOURSHARED_UPLOAD_URL` and `FOURSHARED_TIMEOUT_SECONDS`.

## Modules

Each module README documents its focused command/configuration surface. Unit tests exercise credential parsing and OAuth URL-encoding behavior without contacting MongoDB or 4shared.
