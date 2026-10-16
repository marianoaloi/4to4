package br.com.aloi.callaccesstoken;

import java.nio.file.Path;

record NodeSettings(String executable, Path script, String loginPage,
                    String tokenProviderLink, boolean inspectAtStart) {
    static NodeSettings fromEnvironment() {
        String script = value("FOURSHARED_NODE_SCRIPT",
                "C:\\Users\\maria\\Documents\\4sharednode\\index.js");
        String inspect = value("FOURSHARED_NODE_INSPECT_BRK", "true");
        if (!"true".equalsIgnoreCase(inspect) && !"false".equalsIgnoreCase(inspect)) {
            throw new IllegalArgumentException("FOURSHARED_NODE_INSPECT_BRK must be true or false");
        }
        return new NodeSettings(value("FOURSHARED_NODE_EXECUTABLE", "node"),
                Path.of(script).toAbsolutePath().normalize(),
                value("FOURSHARED_LOGIN_URL", "https://www.4shared.com/web/login"),
                value("FOURSHARED_TOKEN_PROVIDER_LINK", ""),
                Boolean.parseBoolean(inspect));
    }

    private static String value(String key, String fallback) {
        String value = System.getenv(key);
        return value == null ? fallback : value.trim();
    }
}
