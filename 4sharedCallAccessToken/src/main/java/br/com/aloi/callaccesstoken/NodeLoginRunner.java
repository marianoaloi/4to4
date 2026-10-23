package br.com.aloi.callaccesstoken;

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
        Process process = new ProcessBuilder(commandFor(login))
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
        List<String> command = new ArrayList<>();
        command.add(settings.executable());
        if (settings.inspectAtStart()) {
            // A random inspector port allows up to six Node login processes to run together.
            command.add("--inspect-brk=0");
        }
        command.add(settings.script().toString());
        command.add(settings.loginPage());
        command.add(login);
        command.add(settings.tokenProviderLink());
        command.add("querover");
        return List.copyOf(command);
    }

    record RunResult(int exitCode, boolean reportedSuccess, boolean reportedFailure) { }
}
