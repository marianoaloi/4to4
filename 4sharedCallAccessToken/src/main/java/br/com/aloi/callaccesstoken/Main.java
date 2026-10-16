package br.com.aloi.callaccesstoken;

import java.io.IOException;
import java.nio.file.Files;
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
                System.out.printf("Dry run: %d active login(s) would be processed.%n", logins.size());
                return;
            }

            NodeSettings settings = NodeSettings.fromEnvironment();
            if (!Files.isRegularFile(settings.script()) || !Files.isReadable(settings.script())) {
                throw new IllegalArgumentException("Node script is not readable: " + settings.script());
            }
            NodeLoginRunner runner = new NodeLoginRunner(settings);
            int failures = 0;
            for (String login : logins) {
                System.out.printf("Starting Node login flow for %s%n", login);
                try {
                    NodeLoginRunner.RunResult result = runner.run(login);
                    if (result.exitCode() == 0 && result.reportedSuccess() && !result.reportedFailure()) {
                        System.out.printf("Node process completed for %s%n", login);
                    } else {
                        failures++;
                        System.err.printf("Node login did not report success for %s (exit %d)%n",
                                login, result.exitCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the Node login flow", e);
                }
            }
            System.out.printf("Finished: %d processed, %d Node process failure(s).%n",
                    logins.size(), failures);
            if (failures > 0) {
                System.exit(1);
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use --help for usage.");
            System.exit(2);
        }
    }

    private static void printUsage() {
        System.out.println("4sharedCallAccessToken");
        System.out.println("Usage: java -jar 4sharedCallAccessToken-1.0.0-jar-with-dependencies.jar [--dry-run] [--limit N] [--login EMAIL]");
        System.out.println("By default, runs the copied LOGIN, LOGIN_OLD, and LOGIN_DOWNLOAD lists once each.");
        System.out.println("BANIDOS entries are excluded. Node inspector pauses at startup by default.");
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
