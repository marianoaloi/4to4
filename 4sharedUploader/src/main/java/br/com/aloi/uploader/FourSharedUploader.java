package br.com.aloi.uploader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

final class FourSharedUploader {
    private static final char[] NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Config config;
    private final HttpClient httpClient;

    FourSharedUploader(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    UploadResult upload(Path file, String remoteName) throws IOException, InterruptedException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Not a readable regular file");
        }
        long size = Files.size(file);
        if (size == 0) {
            throw new IOException("Empty files are not uploaded");
        }

        URI requestUri = withQuery(config.uploadUri(), Map.of(
                "folderId", config.rootFolderId(),
                "fileName", remoteName));
        String authorization;
        try {
            authorization = OAuth1Signer.authorizationHeader("POST", requestUri, Map.of(), config);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign the 4shared request", e);
        }

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(config.requestTimeout())
                .header("Authorization", authorization)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("4shared returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return new UploadResult(response.statusCode(), response.body());
    }

    static String generateRemoteName(Path file) {
        String original = file.getFileName().toString();
        int dot = original.lastIndexOf('.');
        String extension = dot > 0 ? original.substring(dot) : ".outtype";
        StringBuilder name = new StringBuilder(14 + extension.length());
        for (int i = 0; i < 14; i++) {
            name.append(NAME_CHARS[RANDOM.nextInt(NAME_CHARS.length)]);
        }
        return name.append(extension).toString();
    }

    private static URI withQuery(URI base, Map<String, String> parameters) {
        StringBuilder query = new StringBuilder(base.toASCIIString());
        query.append(base.getRawQuery() == null ? '?' : '&');
        parameters.forEach((key, value) -> {
            if (query.charAt(query.length() - 1) != '?' && query.charAt(query.length() - 1) != '&') {
                query.append('&');
            }
            query.append(OAuth1Signer.encode(key)).append('=').append(OAuth1Signer.encode(value));
        });
        return URI.create(query.toString());
    }

    record UploadResult(int statusCode, String responseBody) {
    }
}
