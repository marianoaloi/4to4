package br.com.aloi.tokenprovider;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class NodeLoginRunner {
    private final NodeSettings settings;

    NodeLoginRunner(NodeSettings settings) {
        this.settings = settings;
    }

    RunResult run(String login) throws IOException, InterruptedException {
        return run(login, settings.tokenProviderLink());
    }

    RunResult run(String login, String tokenProviderLink) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(commandFor(login, tokenProviderLink))
                .directory(settings.script().getParent().toFile())
                .redirectErrorStream(true)
                .start();
        boolean reportedSuccess = false;
        boolean reportedFailure = false;
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                reportedSuccess |= line.contains("funcionou ");
                reportedFailure |= line.contains("Deu ruim ");
            }
        }
        return new RunResult(process.waitFor(), reportedSuccess, reportedFailure);
    }

    List<String> commandFor(String login) {
        return commandFor(login, settings.tokenProviderLink());
    }

    List<String> commandFor(String login, String tokenProviderLink) {
        List<String> command = new ArrayList<>();
        command.add(settings.executable());
        if (settings.inspectAtStart()) {
            command.add("--inspect-brk");
        }
        command.add(settings.script().toString());
        command.add(settings.loginPage());
        command.add(login);
        command.add(tokenProviderLink);
        command.add("querover");
        var result = List.copyOf(command);
        System.out.printf("Node command: %s%n",result.stream().collect(java.util.stream.Collectors.joining(" ")))  ;
        return result;
    }

    record RunResult(int exitCode, boolean reportedSuccess, boolean reportedFailure) { }
}
