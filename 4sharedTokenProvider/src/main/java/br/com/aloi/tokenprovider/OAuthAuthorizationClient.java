package br.com.aloi.tokenprovider;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Runs the interactive OAuth 1.0a flow when no usable accessor is stored. */
final class OAuthAuthorizationClient {
    private static final String CALLBACK_PATH = "/oauth/callback";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final ProviderSettings settings;
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();

    OAuthAuthorizationClient(ProviderSettings settings) {
        this.settings = settings;
    }

    TokenPair authorize(String login) {
        return authorize(login, List.of());
    }

    TokenPair authorize(String login, List<Document> accountCookies) {
        HttpServer callbackServer = null;
        CompletableFuture<Callback> callbackResult = new CompletableFuture<>();
        try {
            callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            callbackServer.createContext(CALLBACK_PATH, exchange -> handleCallback(exchange, callbackResult));
            callbackServer.start();

            URI callbackUri = URI.create("http://127.0.0.1:" + callbackServer.getAddress().getPort() + CALLBACK_PATH);
            Map<String, String> requestParameters = Map.of("oauth_callback", callbackUri.toString());
            Map<String, String> requestTokenResponse = getForm(oauthRequestUri(
                    settings.requestTokenUri(), requestParameters, null, null));
            String requestToken = required(requestTokenResponse, "oauth_token");
            String requestSecret = required(requestTokenResponse, "oauth_token_secret");
            URI authorizationUri = withQuery(settings.authorizationUri(), Map.of("oauth_token", requestToken));

            Callback callback = null;
            if (accountCookies != null && !accountCookies.isEmpty()) {
                try {
                    callback = authorizeWithCookies(authorizationUri, callbackUri, accountCookies);
                } catch (IOException | RuntimeException e) {
                    System.out.printf("Saved-cookie authorization request failed for %s; opening browser fallback.%n", login);
                }
            }
            if (callback == null) {
                System.out.printf("Saved cookies could not complete authorization for %s; opening browser.%n", login);
                System.out.printf("If the browser does not open, visit:%n%s%n", authorizationUri);
                // Node/Puppeteer is only needed when saved cookies cannot complete authorization.
                InteractiveLogin.authorize(login, authorizationUri);
                callback = callbackResult.get(settings.authorizationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
            if (!requestToken.equals(callback.requestToken())) {
                throw new CredentialException("4shared returned a callback for a different authorization request");
            }
            Map<String, String> exchangeParameters = new LinkedHashMap<>();
            exchangeParameters.put("oauth_token", requestToken);
            if (callback.verifier() != null && !callback.verifier().isBlank()) {
                exchangeParameters.put("oauth_verifier", callback.verifier());
            }
            Map<String, String> accessResponse = getForm(oauthRequestUri(
                    settings.accessTokenUri(), exchangeParameters, requestToken, requestSecret));
            return new TokenPair(required(accessResponse, "oauth_token"),
                    required(accessResponse, "oauth_token_secret"), requestToken);
        } catch (TimeoutException e) {
            throw new CredentialException("Timed out waiting for the 4shared authorization callback", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CredentialException("Interrupted while waiting for 4shared authorization", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CredentialException credentialException) {
                throw credentialException;
            }
            throw new CredentialException("Could not receive the 4shared authorization callback", cause);
        } catch (IOException | GeneralSecurityException e) {
            throw new CredentialException("Could not complete the 4shared OAuth authorization request", e);
        } finally {
            if (callbackServer != null) {
                callbackServer.stop(0);
            }
        }
    }

    /** Try the authorization redirects with the stored 4shared cookies, without starting a browser. */
    private Callback authorizeWithCookies(URI authorizationUri, URI callbackUri, List<Document> cookieDocuments)
            throws IOException, InterruptedException {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        addCookies(cookieManager, authorizationUri, cookieDocuments);
        if (cookieManager.getCookieStore().getCookies().isEmpty()) {
            return null;
        }

        java.net.http.HttpClient cookieHttp = java.net.http.HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .cookieHandler(cookieManager)
                .build();
        URI current = authorizationUri;
        for (int redirects = 0; redirects < 12; redirects++) {
            var request = java.net.http.HttpRequest.newBuilder(current)
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            var response = cookieHttp.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                // A rendered login/consent page needs the interactive browser flow.
                return null;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) {
                return null;
            }
            URI next = current.resolve(location);
            if (isCallback(next, callbackUri)) {
                Map<String, String> parameters = parseForm(next.getRawQuery());
                if (parameters.containsKey("oauth_problem")) {
                    return null;
                }
                String token = parameters.get("oauth_token");
                if (token == null || token.isBlank()) {
                    return null;
                }
                String verifier = parameters.getOrDefault("oauth_verifier", "");
                return new Callback(token, verifier);
            }
            if (!isFoursharedHttps(next)) {
                // Never forward account cookies to a third-party redirect.
                return null;
            }
            current = next;
        }
        return null;
    }

    private static void addCookies(CookieManager manager, URI target, List<Document> documents) {
        String targetHost = target.getHost();
        long nowSeconds = Instant.now().getEpochSecond();
        for (Document document : documents) {
            String name = document.getString("name");
            String value = document.getString("value");
            if (name == null || name.isBlank() || value == null) continue;

            String domain = document.getString("domain");
            if (domain != null && !isFoursharedCookieDomain(domain)) continue;
            String path = document.getString("path");
            if (Boolean.TRUE.equals(document.get("secure")) && !"https".equalsIgnoreCase(target.getScheme())) continue;

            Object expiresValue = document.get("expires");
            if (expiresValue instanceof Number expires && expires.doubleValue() > 0) {
                long maxAge = (long) expires.doubleValue() - nowSeconds;
                if (maxAge <= 0) continue;
            }

            try {
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setDomain(domain == null || domain.isBlank() ? targetHost : domain);
                cookie.setPath(path == null || path.isBlank() ? "/" : path);
                cookie.setSecure(Boolean.TRUE.equals(document.get("secure")));
                cookie.setHttpOnly(Boolean.TRUE.equals(document.get("httpOnly")));
                if (expiresValue instanceof Number expires && expires.doubleValue() > 0) {
                    cookie.setMaxAge((long) expires.doubleValue() - nowSeconds);
                }
                String originHost = domain == null || domain.isBlank()
                        ? targetHost : domain.replaceFirst("^\\.+", "");
                manager.getCookieStore().add(URI.create("https://" + originHost + "/"), cookie);
            } catch (IllegalArgumentException ignored) {
                // Skip malformed/unsupported cookie records and let interactive login handle them.
            }
        }
    }

    private static boolean isFoursharedCookieDomain(String domain) {
        String normalizedDomain = domain.toLowerCase().replaceFirst("^\\.+", "");
        return "4shared.com".equals(normalizedDomain) || normalizedDomain.endsWith(".4shared.com");
    }

    private static boolean isFoursharedHttps(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && ("4shared.com".equalsIgnoreCase(host) || host.toLowerCase().endsWith(".4shared.com"));
    }

    private static boolean isCallback(URI candidate, URI callbackUri) {
        return "http".equalsIgnoreCase(candidate.getScheme())
                && callbackUri.getHost().equalsIgnoreCase(candidate.getHost())
                && callbackUri.getPort() == candidate.getPort()
                && callbackUri.getPath().equals(candidate.getPath());
    }

    Document getUser(TokenPair tokens) {
        try {
            URI signedUri = oauthRequestUri(settings.userUri(), Map.of(),
                    tokens.accessToken(), tokens.accessTokenSecret());
            var request = java.net.http.HttpRequest.newBuilder(signedUri)
                    .timeout(HTTP_TIMEOUT).GET().build();
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CredentialException("4shared user lookup returned HTTP " + response.statusCode());
            }
            return Document.parse(response.body());
        } catch (IOException | InterruptedException | GeneralSecurityException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CredentialException("Could not read the authorized 4shared user profile", e);
        }
    }

    private Map<String, String> getForm(URI uri)
            throws IOException, InterruptedException {
        var request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Never include the OAuth response body: providers may echo sensitive token data.
            throw new CredentialException("4shared OAuth endpoint returned HTTP " + response.statusCode());
        }
        return parseForm(response.body());
    }

    private static void handleCallback(HttpExchange exchange, CompletableFuture<Callback> callbackResult)
            throws IOException {
        String responseText = "Authorization received. You may close this browser tab and return to the uploader.";
        int status = 200;
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                throw new CredentialException("The OAuth callback must use GET");
            }
            Map<String, String> parameters = parseForm(exchange.getRequestURI().getRawQuery());
            if (parameters.containsKey("oauth_problem")) {
                throw new CredentialException("4shared authorization was denied or cancelled");
            }
            String token = required(parameters, "oauth_token");
            System.out.printf("Received 4shared OAuth callback for token %s%n", token);
            String verifier = parameters.get("oauth_verifier");
            if (verifier == null || verifier.isBlank()) {
               verifier = "";
            }
            callbackResult.complete(new Callback(token, verifier));
        } catch (RuntimeException e) {
            status = 400;
            responseText = "Authorization failed. Return to the command window for details.";
            callbackResult.completeExceptionally(e instanceof CredentialException ? e
                    : new CredentialException("Invalid 4shared authorization callback"));
        }
        byte[] body = ("<!doctype html><html><meta charset=\"utf-8\"><title>4shared authorization</title>"
                + "<p>" + responseText + "</p></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private URI oauthRequestUri(URI uri, Map<String, String> oauthParameters,
                                String token, String tokenSecret) throws GeneralSecurityException {
        Map<String, String> oauth = new TreeMap<>();
        oauth.put("oauth_consumer_key", settings.consumerKey());
        oauth.put("oauth_nonce", nonce());
        oauth.put("oauth_signature_method", "HMAC-SHA1");
        oauth.put("oauth_timestamp", Long.toString(Instant.now().getEpochSecond()));
        oauth.put("oauth_version", "1.0");
        if (token != null) {
            oauth.put("oauth_token", token);
        }
        oauth.putAll(oauthParameters);

        List<Parameter> parameters = new ArrayList<>();
        parseForm(uri.getRawQuery()).forEach((key, value) -> parameters.add(new Parameter(key, value)));
        oauth.forEach((key, value) -> parameters.add(new Parameter(key, value)));
        parameters.sort(Comparator.comparing((Parameter p) -> encode(p.name()))
                .thenComparing(p -> encode(p.value())));
        String normalized = parameters.stream().map(p -> encode(p.name()) + "=" + encode(p.value()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String path = uri.getRawPath();
        String baseUri = uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                + ((uri.getPort() == -1 || ("https".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 443))
                ? "" : ":" + uri.getPort()) + (path == null || path.isEmpty() ? "/" : path);
        String baseString = "GET&" + encode(baseUri) + "&" + encode(normalized);
        String signingKey = encode(settings.consumerSecret()) + "&" + encode(tokenSecret == null ? "" : tokenSecret);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        oauth.put("oauth_signature", Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8))));
        return withQuery(uri, oauth);
    }

    static Map<String, String> parseForm(String form) {
        Map<String, String> values = new LinkedHashMap<>();
        if (form == null || form.isEmpty()) {
            return values;
        }
        for (String part : form.split("&")) {
            String[] pair = part.split("=", 2);
            String name = URLDecoder.decode(pair[0].replace("+", "%2B"), StandardCharsets.UTF_8);
            String value = pair.length == 1 ? "" : URLDecoder.decode(pair[1].replace("+", "%2B"), StandardCharsets.UTF_8);
            values.put(name, value);
        }
        return values;
    }

    private static URI withQuery(URI base, Map<String, String> parameters) {
        String query = parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        return URI.create(base.toASCIIString() + (base.getRawQuery() == null ? "?" : "&") + query);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new CredentialException("4shared OAuth response is missing " + key);
        }
        return value;
    }

    private static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append("0123456789ABCDEF".charAt(c >>> 4))
                        .append("0123456789ABCDEF".charAt(c & 0x0f));
            }
        }
        return encoded.toString();
    }

    private static String nonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record TokenPair(String accessToken, String accessTokenSecret, String requestToken) { }
    private record Callback(String requestToken, String verifier) { }
    private record Parameter(String name, String value) { }
}
