# 4sharedCallAccessToken

Runs `C:\Users\maria\Documents\4sharednode\index.js` sequentially for the active login lists copied from `ClientMaloi.Logins`. The source order is `LOGIN`, `LOGIN_OLD`, then `LOGIN_DOWNLOAD`; duplicate logins are only run once, and `BANIDOS` entries are excluded.

## Build

From the `4shared_connection` root:

```powershell
mvn -pl 4sharedCallAccessToken -am clean package
```

Runnable JAR: `4sharedCallAccessToken/target/4sharedCallAccessToken-1.0.0-jar-with-dependencies.jar`.

## Run

```powershell
java -jar .\4sharedCallAccessToken\target\4sharedCallAccessToken-1.0.0-jar-with-dependencies.jar
```

The default run processes the full active list. `--dry-run` lists the count without launching Node, `--limit N` runs the first N entries, and `--login EMAIL` runs one listed login.

## Node arguments and configuration

The runner starts Node with `--inspect-brk` before the script (the correct Node CLI position), then passes these script arguments in order:

1. `https://www.4shared.com/web/login`
2. the current login from the copied catalog
3. `FOURSHARED_TOKEN_PROVIDER_LINK` (empty if unset)
4. `querover`

The current `index.js` reads the login page and login from `process.argv[2]` and `process.argv[3]`; it does not currently consume the provider-link argument. `querover` is recognized by the existing `util.js` and enables visible Chrome.

| Environment variable | Default |
|---|---|
| `FOURSHARED_NODE_EXECUTABLE` | `node` |
| `FOURSHARED_NODE_SCRIPT` | `C:\Users\maria\Documents\4sharednode\index.js` |
| `FOURSHARED_LOGIN_URL` | `https://www.4shared.com/web/login` |
| `FOURSHARED_TOKEN_PROVIDER_LINK` | empty string |
| `FOURSHARED_NODE_INSPECT_BRK` | `true` |

With inspector mode enabled, Node pauses at startup until a debugger attaches. Runs are sequential so the default inspector port can be reused. The runner recognizes the current script's `funcionou` and `Deu ruim` output and treats missing/failed success output as a failure. This runner does not read or store OAuth token secrets.
