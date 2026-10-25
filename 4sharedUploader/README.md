# 4shared Uploader

Java 21 command-line uploader. See the repository root README for credential configuration and build instructions.

```powershell
java -jar target\4shared-uploader-1.0.0-jar-with-dependencies.jar [--name REMOTE_NAME] <file-or-directory>
```

Directories are traversed recursively. Files are uploaded one at a time and kept locally. With MongoDB credentials configured, each file uses the account with the least `accessor.freeSize` that has enough space. Accessors older than 50 minutes are refreshed through `CredentialProvider.resolve` before account selection. Remote names are randomized by default. OAuth 1.0a request signing is implemented in this module. Direct credential environment variables remain available for a single-account upload.
