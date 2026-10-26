package br.com.aloi.uploader;

import br.com.aloi.domain.AccountCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MainSimple {
    private static final int MAX_CONCURRENT_UPLOADS = 10;

    private MainSimple() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            log.warn("Uploader received invalid command-line arguments.");
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Upload was interrupted.");
            System.err.println("Upload interrupted.");
            System.exit(130);
        } catch (Exception e) {
            log.error("Uploader failed ({}).", e.getClass().getSimpleName());
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
        String moveTo = System.getenv("FOURSHARED_POST_UPLOAD_DIRECTORY");
        Path input = null;
        for (int i = 0; i < args.length; i++) {
            if ("--name".equals(args[i])) {
                if (++i >= args.length || args[i].isBlank()) {
                    throw new IllegalArgumentException("--name requires a non-empty remote filename");
                }
                remoteName = args[i];
            } else if ("--move-to".equals(args[i])) {
                if (++i >= args.length || args[i].isBlank()) {
                    throw new IllegalArgumentException("--move-to requires a destination directory");
                }
                moveTo = args[i];
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
        Path moveDestination = moveTo == null || moveTo.isBlank()
                ? null : Path.of(moveTo).toAbsolutePath().normalize();

        List<Path> files = collectFiles(input);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No regular files found under: " + input);
        }

        String mongoUri = System.getenv("FOURSHARED_MONGODB_URI");
        if (mongoUri != null && !mongoUri.isBlank()) {
            uploadFromAccounts(files, input, moveDestination, remoteName);
        } else {
            uploadWithDirectCredentials(files, input, moveDestination, remoteName, Config.fromEnvironment());
        }
    }

    private static void uploadFromAccounts(List<Path> files, Path input, Path moveDestination,
                                          String remoteName) throws Exception {
        AccountPool pool = AccountPool.getInstance();
        uploadFiles(files, input, moveDestination, remoteName, file -> {
            AccountPool.Reservation reservation = pool.reserve(Files.size(file));
            try {
                AccountCredentials account = reservation.credentials();
                return new SelectedUpload(Config.forCredentials(account), account.login(), reservation);
            } catch (RuntimeException e) {
                rollback(reservation, e);
                throw e;
            }
        });
    }

    private static void uploadWithDirectCredentials(List<Path> files, Path input, Path moveDestination,
                                                    String remoteName, Config config) throws Exception {
        uploadFiles(files, input, moveDestination, remoteName,
                file -> new SelectedUpload(config, "direct credentials", null));
    }

    private static void uploadFiles(List<Path> files, Path input, Path moveDestination, String remoteName,
                                    UploadConfigProvider configProvider) throws Exception {
        int uploaded = 0;
        int failed = 0;
        int moveFailed = 0;
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS);
        CompletionService<UploadOutcome> completion = new ExecutorCompletionService<>(executor);
        int next = 0;
        int active = 0;
        try {
            while (next < files.size() || active > 0) {
                while (next < files.size() && active < MAX_CONCURRENT_UPLOADS) {
                    Path file = files.get(next++);
                    completion.submit(() -> uploadOne(file, input, moveDestination, remoteName, configProvider));
                    active++;
                }
                UploadOutcome outcome;
                try {
                    outcome = completion.take().get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("An upload worker failed unexpectedly", e.getCause());
                }
                active--;
                if (outcome.uploaded()) uploaded++; else failed++;
                if (outcome.moveFailed()) moveFailed++;
            }
        } finally {
            executor.shutdownNow();
        }
        System.out.printf("Finished: %d uploaded, %d failed, %d backup move failures.%n",
                uploaded, failed, moveFailed);
        log.info("Uploader finished: {} uploaded, {} failed, {} backup move failures.",
                uploaded, failed, moveFailed);
        if (failed > 0 || moveFailed > 0) {
            System.exit(1);
        }
    }

    @FunctionalInterface
    private interface UploadConfigProvider {
        SelectedUpload forFile(Path file) throws Exception;
    }

    private static UploadOutcome uploadOne(Path file, Path input, Path moveDestination, String remoteName,
                                           UploadConfigProvider configProvider) {
        String name = remoteName == null ? FourSharedUploader.generateRemoteName(file) : remoteName;
        SelectedUpload selected = null;
        try {
            long size = Files.size(file);
            selected = configProvider.forFile(file);
            FourSharedUploader.UploadResult result = new FourSharedUploader(selected.config()).upload(file, name);
            if (selected.reservation() != null) selected.reservation().complete();
            log.info("Upload succeeded ({} bytes).", size);
            System.out.printf("Uploaded %s -> %s using %s (%d bytes)%n",
                    file, name, selected.accountLogin(), size);
            System.out.println(result.responseBody());
            if (moveDestination != null) {
                try {
                    Path destination = moveUploadedFile(input, file, moveDestination);
                    if (destination == null) {
                        log.info("Uploaded file was already in the backup directory.");
                        System.out.printf("Already in backup directory: %s%n", file);
                    } else {
                        log.info("Uploaded file was moved to the backup directory.");
                        System.out.printf("Moved %s -> %s%n", file, destination);
                    }
                } catch (IOException e) {
                    log.warn("Upload succeeded but backup move failed ({}).", e.getClass().getSimpleName());
                    System.err.printf("Uploaded %s, but could not move it to backup: %s%n", file, e.getMessage());
                    return new UploadOutcome(true, true);
                }
            }
            return new UploadOutcome(true, false);
        } catch (InterruptedException e) {
            rollback(selected.reservation(), e);
            Thread.currentThread().interrupt();
            System.err.printf("Upload interrupted for %s.%n", file);
            return new UploadOutcome(false, false);
        } catch (Exception e) {
            rollback(selected.reservation(), e);
            log.warn("Upload failed; local file was retained ({}).", e.getClass().getSimpleName());
            System.err.printf("Failed %s: %s%n", file, e.getMessage());
            return new UploadOutcome(false, false);
        }
    }

    private record SelectedUpload(Config config, String accountLogin, AccountPool.Reservation reservation) { }
    private record UploadOutcome(boolean uploaded, boolean moveFailed) { }

    private static void rollback(AccountPool.Reservation reservation, Throwable originalFailure) {
        if (reservation == null) return;
        try {
            reservation.rollback();
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            log.error("Could not restore the in-memory free-space reservation after an unsuccessful upload ({}).",
                    rollbackFailure.getClass().getSimpleName());
        }
    }

    private static Path moveUploadedFile(Path input, Path file, Path backupDirectory) throws IOException {
        Path relativePath = Files.isDirectory(input) ? input.relativize(file) : file.getFileName();
        Path destination = backupDirectory.resolve(relativePath).normalize();
        if (!destination.startsWith(backupDirectory)) {
            throw new IOException("Destination escapes the configured backup directory");
        }
        if (destination.equals(file)) {
            return null;
        }
        Files.createDirectories(destination.getParent());
        Files.move(file, destination);
        return destination;
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
        System.out.println("Usage: java -cp 4shared-uploader-1.0.0-jar-with-dependencies.jar br.com.aloi.uploader.MainSimple [--name REMOTE_NAME] [--move-to DIRECTORY] <file-or-directory>");
        System.out.println();
        System.out.println("A directory uploads readable regular files recursively, with up to 10 concurrent uploads.");
        System.out.println("Local files are kept unless --move-to or FOURSHARED_POST_UPLOAD_DIRECTORY is configured.");
        System.out.println("Configured files move only after a successful upload; directory structure is preserved.");
        System.out.println("Remote names are randomized unless --name is provided.");
        System.out.println("Set FOURSHARED_MONGODB_URI and the consumer key/secret to route each file to the account");
        System.out.println("with the least free space that can fit it, or provide all five direct credential variables:");
        System.out.println("  FOURSHARED_CONSUMER_KEY, FOURSHARED_CONSUMER_SECRET");
        System.out.println("  FOURSHARED_ACCESS_TOKEN, FOURSHARED_ACCESS_TOKEN_SECRET, FOURSHARED_ROOT_FOLDER_ID");
        System.out.println("Optional environment variables:");
        System.out.println("  FOURSHARED_UPLOAD_URL (default: https://upload.4shared.com/v1_2/files)");
        System.out.println("  FOURSHARED_TIMEOUT_SECONDS (default: 120)");
        System.out.println("  FOURSHARED_POST_UPLOAD_DIRECTORY (optional backup directory; --move-to overrides it)");
    }
}
