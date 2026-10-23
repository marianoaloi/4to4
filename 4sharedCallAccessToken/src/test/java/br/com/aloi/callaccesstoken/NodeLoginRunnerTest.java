package br.com.aloi.callaccesstoken;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeLoginRunnerTest {
    @Test
    void catalogContainsUniqueNonBannedAccounts() {
        Document catalog = Document.parse("""
                {"banned":["blocked@example.test"],
                 "current":["active@example.test","active@example.test"],
                 "old":["blocked@example.test","old@example.test"],
                 "download":["download@example.test"]}
                """);
        List<String> logins = LoginCatalog.activeLogins(catalog);
        assertFalse(logins.isEmpty());
        assertEquals(List.of("active@example.test", "old@example.test", "download@example.test"), logins);
    }

    @Test
    void placesNodeInspectorBeforeScriptAndPassesRequestedScriptArguments() {
        var settings = new NodeSettings("node", Path.of("C:/4sharednode/index.js"),
                "https://www.4shared.com/web/login", "provider-link", true);
        var command = new NodeLoginRunner(settings).commandFor("user@example.test");
        assertEquals(List.of("node", "--inspect-brk=0", "C:\\4sharednode\\index.js",
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
