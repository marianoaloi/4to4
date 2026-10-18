package br.com.aloi.tokenprovider;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeLoginRunnerTest {
    @Test
    void placesNodeInspectorBeforeScriptAndPassesRequestedScriptArguments() {
        var settings = new NodeSettings("node", Path.of("C:/4sharednode/index.js"),
                "https://www.4shared.com/web/login", "provider-link", true);
        var command = new NodeLoginRunner(settings).commandFor("user@example.test");
        assertEquals(List.of("node", "--inspect-brk", "C:\\4sharednode\\index.js",
                "https://www.4shared.com/web/login", "user@example.test", "provider-link", "querover"), command);
    }

    @Test
    void excludesInspectorRuntimeFlagWhenDisabled() {
        var settings = new NodeSettings("node", Path.of("index.js"), "https://www.4shared.com/web/login", "", false);
        var command = new NodeLoginRunner(settings).commandFor("user@example.test");
        assertFalse(command.contains("--inspect-brk"));
        assertEquals("querover", command.get(command.size() - 1));
    }
}
