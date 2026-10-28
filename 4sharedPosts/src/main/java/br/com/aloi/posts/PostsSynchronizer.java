package br.com.aloi.posts;

import br.com.aloi.domain.AccountCredentials;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.tokenprovider.CredentialProvider;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
final class PostsSynchronizer {
    private static final int MAX_PARALLEL_LOGINS = 6;
    private static final int MAX_PARALLEL_THUMBNAILS = 20;

    private final MongoCollection<Document> posts;
    private final CredentialProvider credentials;
    private final Path thumbnailDirectory;

    PostsSynchronizer(MongoCollection<Document> posts, CredentialProvider credentials) {
        this.posts = posts;
        this.credentials = credentials;
        this.thumbnailDirectory = Path.of(value("FOURSHARED_THUMBNAIL_DIR", "/opt/maloi/staticimages/"))
                .toAbsolutePath().normalize();
    }

    RunSummary run(List<LoginEntry4to4> selectedLogins, boolean fullCatalogRun) {
        Map<String, List<Document>> inventoryByLogin = new HashMap<>();
        Map<String, AccountCredentials> credentialsByLogin = new HashMap<>();
        int failures = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_LOGINS,
                Math.max(1, selectedLogins.size())))) {
            List<Future<LoginInventory>> jobs = new ArrayList<>();
            for (LoginEntry4to4 login : selectedLogins) {
                jobs.add(executor.submit(() -> fetch(login)));
            }
            for (Future<LoginInventory> job : jobs) {
                try {
                    LoginInventory result = job.get();
                    inventoryByLogin.put(result.login(), result.files());
                    credentialsByLogin.put(result.login(), result.credentials());
                    log.info("Fetched {} files for {}", result.files().size(), result.login());
                } catch (Exception e) {
                    failures++;
                    log.error("Could not fetch file inventory for one login ({}).", rootCause(e).getClass().getSimpleName());
                }
            }
        }

        Set<String> currentIds = persistNewDocuments(inventoryByLogin);
        boolean pruned = fullCatalogRun && failures == 0 && inventoryByLogin.size() == selectedLogins.size();
        if (pruned) pruneStaleDocuments(inventoryByLogin);
        int thumbnails = downloadMissingThumbnails(credentialsByLogin);
        return new RunSummary(selectedLogins.size(), inventoryByLogin.size(), failures,
                currentIds.size(), thumbnails, pruned);
    }

    private LoginInventory fetch(LoginEntry4to4 login) throws IOException, InterruptedException {
        AccountCredentials account = credentials.resolve(login);
        List<Document> files = new FourSharedApiClient(account).getAllFiles();
        return new LoginInventory(login.login(), account, files);
    }

    private Set<String> persistNewDocuments(Map<String, List<Document>> inventoryByLogin) {
        Supplier<Stream<Document>> documents = () -> inventoryByLogin.entrySet().stream().flatMap(
            entry -> entry.getValue().stream().map(file -> {
                String rawId = file.getString("id");
                if (rawId == null || rawId.toString().isBlank()) return null;
                String id = rawId.toString();
                Document stored = new Document(file).append("_id", id).append("login", entry.getKey());
                return stored;
            })
        );


        var result = posts.bulkWrite(
                documents.get().map(doc -> new UpdateOneModel<Document>(eq("_id", doc.get("_id")), doc,
                    new UpdateOptions().upsert(true)))
            .toList());
        log.info("Persisted {} new post documents ({} modified, {} upserted).",
                result.getInsertedCount() + result.getModifiedCount(), result.getModifiedCount(),
                result.getUpserts().size());


        
        return documents.get().map(file -> file.get("id").toString()).collect(Collectors.toSet());
    }

    private void pruneStaleDocuments(Map<String, List<Document>> inventoryByLogin) {
        long removed = 0;
        for (Map.Entry<String, List<Document>> inventory : inventoryByLogin.entrySet()) {
            Set<String> actualIds = new HashSet<>();
            for (Document file : inventory.getValue()) {
                if (file.get("id") != null) actualIds.add(file.get("id").toString());
            }
            List<String> staleIds = new ArrayList<>();
            for (Document stored : posts.find(Filters.eq("login", inventory.getKey()))
                    .projection(new Document("_id", 1))) {
                String id = String.valueOf(stored.get("_id"));
                if (!actualIds.contains(id)) staleIds.add(id);
            }
            for (int start = 0; start < staleIds.size(); start += 500) {
                List<String> chunk = staleIds.subList(start, Math.min(start + 500, staleIds.size()));
                removed += posts.deleteMany(Filters.and(Filters.eq("login", inventory.getKey()),
                        Filters.in("_id", chunk))).getDeletedCount();
            }
        }
        log.info("Removed {} stale post documents after a complete catalog run", removed);
    }

    private int downloadMissingThumbnails(Map<String, AccountCredentials> credentialsByLogin) {
        try {
            Files.createDirectories(thumbnailDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create thumbnail directory " + thumbnailDirectory, e);
        }
        List<Document> documents = posts.find(Filters.and(
                Filters.in("login", credentialsByLogin.keySet()),
                Filters.exists("id"), Filters.exists("mimeType"), Filters.exists("login"))).into(new ArrayList<>());
        int downloaded = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_THUMBNAILS)) {
            List<Future<Boolean>> jobs = new ArrayList<>();
            for (Document document : documents) {
                String id = document.getString("id");
                String login = document.getString("login");
                if (id == null || login == null || !credentialsByLogin.containsKey(login)) continue;
                Path target = thumbnailDirectory.resolve(id).normalize();
                if (!target.getParent().equals(thumbnailDirectory) || Files.exists(target)) continue;
                jobs.add(executor.submit(() -> downloadThumbnail(document, credentialsByLogin.get(login), target)));
            }
            for (Future<Boolean> job : jobs) {
                try {
                    if (job.get()) downloaded++;
                } catch (Exception e) {
                    log.warn("A thumbnail worker failed ({})", rootCause(e).getClass().getSimpleName());
                }
            }
        }
        return downloaded;
    }

    private boolean downloadThumbnail(Document document, AccountCredentials account, Path target) {
        String id = document.getString("id");
        try {
            byte[] image = new FourSharedApiClient(account).getThumbnail(id, document.getString("name"),
                    document.getString("mimeType"));
            Files.write(target, image);
            String maloiType = document.getString("mimeType") != null
                    && document.getString("mimeType").toLowerCase().contains("video") ? "VIDEO" : "IMG";
            posts.updateOne(Filters.eq("_id", id), new Document("$unset", new Document("errorImg", ""))
                    .append("$set", new Document("maloiType", maloiType)));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                Files.write(target, placeholder(id));
                posts.updateOne(Filters.eq("_id", id), new Document("$set",
                        new Document("errorImg", reason).append("maloiType", "IMG")));
            } catch (IOException writeFailure) {
                log.warn("Could not write thumbnail or placeholder for {}", id);
                return false;
            }
        }
        return true;
    }

    private static byte[] placeholder(String id) throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(100, 100,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.BLACK);
            String text = id == null ? "unknown" : id;
            graphics.drawString(text.substring(0, Math.min(text.length(), 14)), 4, 52);
        } finally {
            graphics.dispose();
        }
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private record LoginInventory(String login, AccountCredentials credentials, List<Document> files) { }
    record RunSummary(int requestedLogins, int syncedLogins, int failures, int currentFiles,
                      int thumbnailsWritten, boolean staleDocumentsPruned) { }
}
