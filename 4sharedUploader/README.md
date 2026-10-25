# 4shared Uploader

Java 21 command-line uploader. See the repository root README for credential configuration and build instructions.

```powershell
java -jar target\4shared-uploader-1.0.0-jar-with-dependencies.jar [--name REMOTE_NAME] [--move-to DIRECTORY] <file-or-directory>
```

Directories are traversed recursively. Files are uploaded one at a time and kept locally by default. To move each file to `C:\tmp\backup` only after a successful upload, pass `--move-to C:\tmp\backup` or set the `FOURSHARED_POST_UPLOAD_DIRECTORY` environment variable to that path. The command-line option overrides the environment variable. When uploading a directory, its relative directory structure is preserved in the backup folder. Existing destination files are not overwritten.

With MongoDB credentials configured, each file uses the account with the least `accessor.freeSize` that has enough space. Accessors older than 50 minutes are refreshed through `CredentialProvider.resolve` before account selection. Remote names are randomized by default. OAuth 1.0a request signing is implemented in this module. Direct credential environment variables remain available for a single-account upload.
