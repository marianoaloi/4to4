	package br.com.aloi.tokenprovider;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("Usage: java -jar 4shared-token-provider-1.0.0-jar-with-dependencies.jar <login>");
            System.out.println("Resolves account tokens from the configured MongoDB accounts collection.");
            System.out.println("OAuth values are never printed.");
            return;
        }
        if (args.length != 1) {
            System.err.println("Usage: java -jar 4shared-token-provider-1.0.0-jar-with-dependencies.jar <login>");
            System.exit(2);
        }
        try {
            AccountCredentials credentials = new CredentialProvider(ProviderSettings.fromEnvironment())
                    .resolve(args[0]);
            System.out.printf("OAuth credentials are available for %s; secret values are redacted.%n",
                    credentials.login());
        } catch (RuntimeException e) {
            System.err.println("Could not resolve OAuth credentials: " + e.getMessage());
            System.exit(1);
        }
    }
}
