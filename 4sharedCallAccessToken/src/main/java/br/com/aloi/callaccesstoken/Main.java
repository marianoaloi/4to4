package br.com.aloi.callaccesstoken;

import br.com.aloi.tokenprovider.AccountCredentials;
import br.com.aloi.tokenprovider.CredentialProvider;
import br.com.aloi.tokenprovider.ProviderSettings;

import java.util.List;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                printUsage();
                return;
            }

            List<String> logins = options.login() == null
                    ? LoginCatalog.allActiveLogins()
                    : List.of(options.login());
            if (options.limit() > 0) {
                logins = logins.stream().limit(options.limit()).toList();
            }
            if (options.dryRun()) {
                System.out.printf("Dry run: %d login(s) would be resolved.%n", logins.size());
                return;
            }

            CredentialProvider credentialProvider = new CredentialProvider(ProviderSettings.fromEnvironment());
            int failures = 0;
            for (String login : logins) {
                try {
                    AccountCredentials credentials = credentialProvider.resolve(login);
                    System.out.printf("OAuth credentials are available for %s; secret values are redacted.%n",
                            credentials.login());
                } catch (RuntimeException e) {
                    failures++;
                    System.err.printf("Could not resolve credentials for %s: %s%n", login, e.getMessage());
                }
            }
            System.out.printf("Finished: %d processed, %d failure(s).%n", logins.size(), failures);
            if (failures > 0) {
                System.exit(1);
            }
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use --help for usage.");
            System.exit(2);
        }
    }

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
            if (login != null && !LoginCatalog.allActiveLogins().contains(login)) {
                throw new IllegalArgumentException("--login must match an active login in the copied catalog");
            }
            return new Options(help, dryRun, limit, login);
        }
    }
}
