# 4shared Uploader

Java 21 command-line uploader. See the repository root README for credential configuration and build instructions.

```powershell
java -jar target\4shared-uploader-1.0.0-jar-with-dependencies.jar [--name REMOTE_NAME] [--move-to DIRECTORY] <file-or-directory>
```

Directories are traversed recursively. Up to 10 files upload concurrently and files are kept locally by default. To move each file to `C:\tmp\backup` only after a successful upload, pass `--move-to C:\tmp\backup` or set the `FOURSHARED_POST_UPLOAD_DIRECTORY` environment variable to that path. The command-line option overrides the environment variable. When uploading a directory, its relative directory structure is preserved in the backup folder. Existing destination files are not overwritten.

With MongoDB credentials configured, the JVM-wide account pool loads eligible accounts once and reserves free space in memory before each upload. A reservation stays unavailable to other uploads until the request succeeds or fails. Failed uploads return their reserved bytes to the pool. Tokens and free space refresh together when a token reaches the configured freshness window (50 minutes by default). Remote names are randomized by default. OAuth 1.0a request signing is implemented in this module. Direct credential environment variables remain available for a single-account upload.
