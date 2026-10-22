package br.com.aloi.tokenprovider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;

/** Entry point for other modules that need the configured Node login flow. */
public final class InteractiveLogin {
    private InteractiveLogin() { }

    public static void authorize(String login, URI authorizationUri) {
        try {
            NodeSettings configured = NodeSettings.fromEnvironment();
            if (!Files.isRegularFile(configured.script()) || !Files.isReadable(configured.script())) {
                throw new IllegalArgumentException("Node script is not readable: " + configured.script());
            }
            // Authentication must run when the provider starts, without waiting for a debugger.
            NodeSettings settings = new NodeSettings(configured.executable(), configured.script(),
                    configured.loginPage(), configured.tokenProviderLink(), false);
            NodeLoginRunner.RunResult result = new NodeLoginRunner(settings)
                    .run(login, authorizationUri.toString());
            if (result.exitCode() != 0  || result.reportedFailure()) {
                throw new IllegalStateException("Node login did not report success for " + login
                        + " (exit " + result.exitCode() + ")");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the Node login flow", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Node login flow", e);
        }
    }
}
