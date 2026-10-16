# 4shared Uploader

Java 21 command-line uploader. See the repository root README for credential configuration and build instructions.

```powershell
java -jar target\4shared-uploader-1.0.0-jar-with-dependencies.jar [--name REMOTE_NAME] <file-or-directory>
```

Directories are traversed recursively. Files are uploaded one at a time and kept locally. Remote names are randomized by default. OAuth 1.0a request signing is implemented in this module; token loading is provided by the sibling module or by explicit environment variables.
