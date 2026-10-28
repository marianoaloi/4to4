package br.com.aloi.posts;

import br.com.aloi.callaccesstoken.LoginCatalog;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.tokenprovider.CredentialProvider;
import br.com.aloi.tokenprovider.ProviderSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

@Slf4j
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                printUsage();
                return;
            }
            List<LoginEntry4to4> catalog = LoginCatalog.allActiveLogins();
            List<LoginEntry4to4> selected = options.logins() == null ? catalog
                    : Arrays.stream(options.logins().split(","))
                            .map(String::trim).map(login -> catalog.stream()
                                    .filter(active -> active.login().equalsIgnoreCase(login)).findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "--login values must match active entries in LoginCatalog")))
                            .toList();
            if (options.limit() > 0) selected = selected.stream().limit(options.limit()).toList();
            boolean fullCatalogRun = options.logins() == null && options.limit() == 0;
            if (options.dryRun()) {
                log.info("Dry run: {} login(s) would be synchronized.", selected.size());
                return;
            }

            ProviderSettings settings = ProviderSettings.fromEnvironment();
            try (MongoClient client = MongoClients.create(settings.mongoUri())) {
                PostsSynchronizer synchronizer = new PostsSynchronizer(
                        client.getDatabase(settings.database()).getCollection("posts"),
                        new CredentialProvider(settings, client));
                PostsSynchronizer.RunSummary summary = synchronizer.run(selected, fullCatalogRun);
                log.info("Finished: {}/{} login(s), {} file ID(s), {} thumbnail(s), {} failure(s).",
                        summary.syncedLogins(), summary.requestedLogins(), summary.currentFiles(),
                        summary.thumbnailsWritten(), summary.failures());
                if (summary.failures() > 0) System.exit(1);
            }
        } catch (RuntimeException e) {
            log.error("Posts synchronization stopped ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            log.error("Use --help for usage.");
            System.exit(2);
        }
    }

    private static void printUsage() {
        System.out.println("4sharedPosts");
        System.out.println("Usage: java -jar 4shared-posts-1.0.0-jar-with-dependencies.jar [--dry-run] [--limit N] [--login EMAIL[,EMAIL...]]");
        System.out.println("Reads active accounts from LoginCatalog, stores files in MongoDB collection posts, and downloads thumbnails.");
        System.out.println("Set FOURSHARED_MONGODB_URI, FOURSHARED_CONSUMER_KEY, FOURSHARED_CONSUMER_SECRET, and optionally FOURSHARED_THUMBNAIL_DIR.");
    }

    private record Options(boolean help, boolean dryRun, int limit, String logins) {
        private static Options parse(String[] args) {
            boolean help = false;
            boolean dryRun = false;
            int limit = 0;
            String logins = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> help = true;
                    case "--dry-run" -> dryRun = true;
                    case "--limit" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--limit requires a positive integer");
                        try { limit = Integer.parseInt(args[i]); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("--limit requires a positive integer", e); }
                        if (limit < 1) throw new IllegalArgumentException("--limit must be at least 1");
                    }
                    case "--login" -> {
                        if (++i >= args.length || args[i].isBlank()) throw new IllegalArgumentException("--login requires an email");
                        logins = args[i].trim();
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new Options(help, dryRun, limit, logins);
        }
    }
}
