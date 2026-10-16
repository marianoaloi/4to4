package br.com.aloi.uploader;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class OAuth1Signer {
    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuth1Signer() {
    }

    static String authorizationHeader(String method, URI uri, Map<String, String> requestParameters,
                                     Config config) throws GeneralSecurityException {
        Map<String, String> oauth = new TreeMap<>();
        oauth.put("oauth_consumer_key", config.consumerKey());
        oauth.put("oauth_nonce", nonce());
        oauth.put("oauth_signature_method", "HMAC-SHA1");
        oauth.put("oauth_timestamp", Long.toString(Instant.now().getEpochSecond()));
        oauth.put("oauth_token", config.accessToken());
        oauth.put("oauth_version", "1.0");

        List<Parameter> parameters = new ArrayList<>();
        requestParameters.forEach((key, value) -> parameters.add(new Parameter(key, value)));
        addQueryParameters(uri, parameters);
        oauth.forEach((key, value) -> parameters.add(new Parameter(key, value)));
        parameters.sort(Comparator.comparing((Parameter p) -> encode(p.name()))
                .thenComparing(p -> encode(p.value())));

        String normalized = parameters.stream()
                .map(p -> encode(p.name()) + "=" + encode(p.value()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String baseUri = normalizedBaseUri(uri);
        String baseString = method.toUpperCase() + "&" + encode(baseUri) + "&" + encode(normalized);
        String signingKey = encode(config.consumerSecret()) + "&" + encode(config.accessTokenSecret());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        oauth.put("oauth_signature", Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8))));

        return "OAuth " + oauth.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=\"" + encode(entry.getValue()) + "\"")
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static void addQueryParameters(URI uri, List<Parameter> parameters) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return;
        for (String pair : query.split("&")) {
            String[] pieces = pair.split("=", 2);
            parameters.add(new Parameter(decode(pieces[0]), pieces.length == 2 ? decode(pieces[1]) : ""));
        }
    }

    private static String normalizedBaseUri(URI uri) {
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port)
                + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
    }

    static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%');
                result.append("0123456789ABCDEF".charAt(c >>> 4));
                result.append("0123456789ABCDEF".charAt(c & 0x0f));
            }
        }
        return result.toString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String nonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Parameter(String name, String value) {
    }
}
