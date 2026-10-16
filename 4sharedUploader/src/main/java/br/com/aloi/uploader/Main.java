package br.com.aloi.uploader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Upload interrupted.");
            System.exit(130);
        } catch (Exception e) {
            System.err.println("Upload failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0 || List.of(args).contains("--help") || List.of(args).contains("-h")) {
            printUsage();
            return;
        }

        String remoteName = null;
        Path input = null;
        for (int i = 0; i < args.length; i++) {
            if ("--name".equals(args[i])) {
                if (++i >= args.length || args[i].isBlank()) {
                    throw new IllegalArgumentException("--name requires a non-empty remote filename");
                }
                remoteName = args[i];
            } else if (args[i].startsWith("-")) {
                throw new IllegalArgumentException("Unknown option: " + args[i]);
            } else if (input == null) {
                input = Path.of(args[i]).toAbsolutePath().normalize();
            } else {
                throw new IllegalArgumentException("Only one file or directory may be specified");
            }
        }
        if (input == null) {
            throw new IllegalArgumentException("A file or directory path is required");
        }
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Path does not exist: " + input);
        }
        if (!Files.isReadable(input)) {
            throw new IllegalArgumentException("Path is not readable: " + input);
        }
        if (remoteName != null && Files.isDirectory(input)) {
            throw new IllegalArgumentException("--name can only be used with a single file");
        }

        Config config = Config.fromEnvironment();
        FourSharedUploader uploader = new FourSharedUploader(config);
        List<Path> files = collectFiles(input);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No regular files found under: " + input);
        }

        int failed = 0;
        for (Path file : files) {
            String name = remoteName == null ? FourSharedUploader.generateRemoteName(file) : remoteName;
            try {
                FourSharedUploader.UploadResult result = uploader.upload(file, name);
                System.out.printf("Uploaded %s -> %s (%d bytes)%n", file, name, Files.size(file));
                System.out.println(result.responseBody());
            } catch (IOException e) {
                failed++;
                System.err.printf("Failed %s: %s%n", file, e.getMessage());
            }
        }
        System.out.printf("Finished: %d uploaded, %d failed.%n", files.size() - failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static List<Path> collectFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Path is not a regular file or directory: " + input);
        }
        try (var paths = Files.walk(input)) {
            return paths.filter(Files::isRegularFile).filter(Files::isReadable)
                    .sorted(Comparator.comparing(Path::toString)).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
    }

    private static void printUsage() {
        System.out.println("4shared Uploader");
        System.out.println("Usage: java -jar 4shared-uploader-1.0.0-jar-with-dependencies.jar [--name REMOTE_NAME] <file-or-directory>");
        System.out.println();
        System.out.println("A directory uploads all readable regular files recursively, one at a time.");
        System.out.println("Local files are kept. Remote names are randomized unless --name is provided.");
        System.out.println("Credentials: set FOURSHARED_LOGIN to read a token from the configured MongoDB account,");
        System.out.println("or provide all five direct credential variables:");
        System.out.println("  FOURSHARED_CONSUMER_KEY, FOURSHARED_CONSUMER_SECRET");
        System.out.println("  FOURSHARED_ACCESS_TOKEN, FOURSHARED_ACCESS_TOKEN_SECRET, FOURSHARED_ROOT_FOLDER_ID");
        System.out.println("Mongo mode also requires FOURSHARED_MONGODB_URI and the consumer key/secret.");
        System.out.println("Optional environment variables:");
        System.out.println("  FOURSHARED_UPLOAD_URL (default: https://upload.4shared.com/v1_2/files)");
        System.out.println("  FOURSHARED_TIMEOUT_SECONDS (default: 120)");
    }
}
