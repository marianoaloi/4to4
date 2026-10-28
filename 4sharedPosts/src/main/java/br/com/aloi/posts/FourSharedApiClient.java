package br.com.aloi.posts;

import br.com.aloi.domain.AccountCredentials;
import org.bson.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OAuth signed file inventory and thumbnail access for one 4shared account. */
final class FourSharedApiClient {
    private static final URI API = URI.create("https://api.4shared.com/v1_2/");
    private static final URI WEB = URI.create("https://www.4shared.com/web/rest/v1_2/user/files?view=web&query=");
    private static final Pattern WEB_THUMBNAIL = Pattern.compile("https://dc\\d{3}\\.4shared\\.com/img/[^\\\"']+");
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final AccountCredentials credentials;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    FourSharedApiClient(AccountCredentials credentials) {
        this.credentials = credentials;
    }

    List<Document> getAllFiles() throws IOException, InterruptedException {
        List<Document> files = new ArrayList<>();
        for (int offset = 0; ; offset += 100) {
            URI uri = API.resolve("user/files?offset=" + offset);
            HttpResponse<String> response = signedGet(uri, Map.of());
            requireSuccess(response.statusCode(), "4shared file listing");
            Document page = Document.parse(response.body());
            List<Document> items = page.getList("files", Document.class, List.of());
            if (items.isEmpty()) return List.copyOf(files);
            files.addAll(items);
        }
    }

    byte[] getThumbnail(String id, String name, String mimeType) throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();
        byte[] image = trySignedImage(API.resolve("files/" + pathSegment(id) + "/thumbnails/m"), errors);
        if (image != null) return image;

        if (name != null && !name.isBlank()) {
            try {
                URI lookup = URI.create(WEB + URLEncoder.encode(name, StandardCharsets.UTF_8));
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(lookup).timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Matcher matcher = WEB_THUMBNAIL.matcher(response.body());
                    if (matcher.find()) {
                        image = tryPublicImage(URI.create(matcher.group()), errors);
                        if (image != null) return image;
                    }
                }
            } catch (IllegalArgumentException e) {
                errors.add("web preview lookup failed");
            }
        }

        image = trySignedImage(API.resolve("files/" + pathSegment(id) + "/download"), errors);
        if (image != null && isImage(mimeType)) return image;
        image = trySignedImage(API.resolve("files/" + pathSegment(id) + "/preview"), errors);
        if (image != null && isImage(mimeType)) return image;
        throw new IOException("No 4shared thumbnail could be downloaded for " + id + " ("
                + String.join("; ", errors) + ")");
    }

    private byte[] trySignedImage(URI uri, List<String> errors) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = signedGetBytes(uri);
        if (response.statusCode() >= 200 && response.statusCode() < 300 && isImage(response)) return response.body();
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            String location = response.headers().firstValue("location").orElse(null);
            if (location != null) {
                URI redirect = uri.resolve(location);
                if (isFoursharedHost(redirect)) {
                    byte[] image = tryPublicImage(redirect, errors);
                    if (image != null) return image;
                }
            }
        }
        errors.add(uri.getPath() + " returned HTTP " + response.statusCode());
        return null;
    }

    private byte[] tryPublicImage(URI uri, List<String> errors) throws IOException, InterruptedException {
        if (!isFoursharedHost(uri)) return null;
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300 && isImage(response)) return response.body();
        errors.add("public thumbnail returned HTTP " + response.statusCode());
        return null;
    }

    private HttpResponse<String> signedGet(URI uri, Map<String, String> params)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Authorization", OAuth1Signer.authorizationHeader("GET", uri, params, credentials))
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> signedGetBytes(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Authorization", OAuth1Signer.authorizationHeader("GET", uri, Map.of(), credentials))
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean isImage(HttpResponse<byte[]> response) {
        return response.headers().firstValue("content-type").orElse("").toLowerCase().startsWith("image/");
    }

    private static boolean isImage(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    private static boolean isFoursharedHost(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && (host.equalsIgnoreCase("4shared.com") || host.toLowerCase().endsWith(".4shared.com"));
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireSuccess(int status, String operation) throws IOException {
        if (status < 200 || status >= 300) throw new IOException(operation + " returned HTTP " + status);
    }
}

/** Local OAuth 1.0a signer, matching the uploader's HMAC-SHA1 request signing. */
final class OAuth1Signer {
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private OAuth1Signer() { }

    static String authorizationHeader(String method, URI uri, Map<String, String> requestParameters,
                                      AccountCredentials credentials) {
        try {
            java.util.Map<String, String> oauth = new java.util.TreeMap<>();
            oauth.put("oauth_consumer_key", credentials.consumerKey());
            byte[] nonce = new byte[18];
            RANDOM.nextBytes(nonce);
            oauth.put("oauth_nonce", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
            oauth.put("oauth_signature_method", "HMAC-SHA1");
            oauth.put("oauth_timestamp", Long.toString(java.time.Instant.now().getEpochSecond()));
            oauth.put("oauth_token", credentials.accessToken());
            oauth.put("oauth_version", "1.0");

            List<String[]> parameters = new ArrayList<>();
            requestParameters.forEach((key, value) -> parameters.add(new String[]{key, value}));
            String query = uri.getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] pair = part.split("=", 2);
                    parameters.add(new String[]{decode(pair[0]), pair.length == 2 ? decode(pair[1]) : ""});
                }
            }
            oauth.forEach((key, value) -> parameters.add(new String[]{key, value}));
            parameters.sort(java.util.Comparator.comparing((String[] p) -> encode(p[0])).thenComparing(p -> encode(p[1])));
            String normalized = parameters.stream().map(p -> encode(p[0]) + "=" + encode(p[1]))
                    .reduce((a, b) -> a + "&" + b).orElse("");
            int port = uri.getPort();
            String baseUri = uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (port == -1 || port == 443 ? "" : ":" + port)
                    + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            String base = method.toUpperCase() + "&" + encode(baseUri) + "&" + encode(normalized);
            String key = encode(credentials.consumerSecret()) + "&" + encode(credentials.accessTokenSecret());
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            oauth.put("oauth_signature", java.util.Base64.getEncoder()
                    .encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8))));
            return "OAuth " + oauth.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=\"" + encode(e.getValue()) + "\"")
                    .reduce((a, b) -> a + ", " + b).orElse("");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign 4shared OAuth request", e);
        }
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') encoded.append((char) c);
            else encoded.append('%').append("0123456789ABCDEF".charAt(c >>> 4))
                    .append("0123456789ABCDEF".charAt(c & 0xf));
        }
        return encoded.toString();
    }
}
