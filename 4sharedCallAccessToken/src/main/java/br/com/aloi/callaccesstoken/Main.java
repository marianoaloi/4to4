package br.com.aloi.callaccesstoken;

import br.com.aloi.domain.AccountCredentials;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.domain.LoginType;
import br.com.aloi.tokenprovider.CredentialProvider;
import br.com.aloi.tokenprovider.ProviderSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

public final class Main {
    private static final int MAX_PARALLEL_LOGINS = 6;

    private Main() { }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                printUsage();
                return;
            }

            List<LoginEntry4to4> logins = options.login() == null
                    ? LoginCatalog.allActiveLogins()
                    : Arrays.asList(options.login()).stream()
                        .map(login -> new LoginEntry4to4(login, LoginType.CURRENT))
                        .toList();
            if (options.limit() > 0) {
                logins = logins.stream().limit(options.limit()).toList();
            }
            if (options.dryRun()) {
                System.out.printf("Dry run: %d login(s) would be resolved.%n", logins.size());
                return;
            }

            ProviderSettings settings = ProviderSettings.fromEnvironment();
            int failures = 0;
            try (MongoClient client = MongoClients.create(settings.mongoUri())) {
                CredentialProvider credentialProvider = new CredentialProvider(settings, client);
                try (var executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_LOGINS, logins.size()))) {
            var completions = new ExecutorCompletionService<LoginResult>(executor);
                for (LoginEntry4to4 login : logins) {
                    completions.submit(() -> {
                        try {
                            return new LoginResult(login, credentialProvider.resolve(login), null);
                        } catch (RuntimeException e) {
                            return new LoginResult(login, null, e);
                        }
                    });
                }
                for (int i = 0; i < logins.size(); i++) {
                    LoginResult result = completions.take().get();
                    if (result.error() == null) {
                        System.out.printf("OAuth credentials are available for %s; secret values are redacted.%n",
                                result.credentials().login());
                    } else {
                        failures++;
                        System.err.printf("Could not resolve credentials for %s: %s%n",
                                result.login().login(), result.error().getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while resolving login credentials", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("A login worker failed unexpectedly", e.getCause());
                }
                System.out.printf("Finished: %d processed, %d failure(s).%n", logins.size(), failures);
            }
            if (failures > 0) {
                System.exit(1);
            }
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use --help for usage.");
            System.exit(2);
        }
    }

    private record LoginResult(LoginEntry4to4 login, AccountCredentials credentials, RuntimeException error) { }

    private static void printUsage() {
        System.out.println("4sharedCallAccessToken");
        System.out.println("Usage: java -jar 4sharedCallAccessToken-1.0.0-jar-with-dependencies.jar [--dry-run] [--limit N] [--login EMAIL]");
        System.out.println("Resolves existing credentials or authorizes and stores missing credentials via CredentialProvider.resolve(login).");
    }

    private record Options(boolean help, boolean dryRun, int limit, String login) {
        private static Options parse(String[] args) {
            boolean help = false;
            boolean dryRun = false;
            int limit = 0;
            String login = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> help = true;
                    case "--dry-run" -> dryRun = true;
                    case "--limit" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--limit requires a positive integer");
                        try {
                            limit = Integer.parseInt(args[i]);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--limit requires a positive integer", e);
                        }
                        if (limit < 1) throw new IllegalArgumentException("--limit must be at least 1");
                    }
                    case "--login" -> {
                        if (++i >= args.length || args[i].isBlank()) throw new IllegalArgumentException("--login requires an email");
                        login = args[i].trim();
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
                String selectedLogin = login;
                if (selectedLogin != null && LoginCatalog.allActiveLogins().stream()
                    .noneMatch(entry -> entry.login().equals(selectedLogin))) {
                throw new IllegalArgumentException("--login must match an active login in the copied catalog");
            }
                return new Options(help, dryRun, limit, selectedLogin);
        }
    }
}
