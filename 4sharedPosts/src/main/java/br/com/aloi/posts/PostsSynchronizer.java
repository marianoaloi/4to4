package br.com.aloi.posts;

import br.com.aloi.domain.AccountCredentials;
import br.com.aloi.domain.LoginEntry4to4;
import br.com.aloi.tokenprovider.CredentialProvider;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertOneModel;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

@Slf4j
final class PostsSynchronizer {
    private static final int MAX_PARALLEL_LOGINS = 6;
    private static final int MAX_PARALLEL_THUMBNAILS = 20;

    private final Path thumbnailDirectory;
    private List<String> actualIds;
    private List<String> webIds;
    private List<String> insertIds;
    private List<String> removeIds;

    PostsSynchronizer() {
        this.thumbnailDirectory = Path.of(value("FOURSHARED_THUMBNAIL_DIR", "/opt/maloi/staticimages/"))
                .toAbsolutePath().normalize();
    }

    private Function<Future<LoginInventory>, ResumeDataBase> insertPrune = (job) -> {
        LoginInventory result;
        try {
            result = job.get();
        } catch (Exception ex) {
            log.error("Could not insert/prune for one login ({}).", rootCause(ex).getClass().getSimpleName());
            return new ResumeDataBase(null, null, null, null, null, new ProcessResume(1, 0, 0, 0, false));
        }
        AccountCredentials credential = result.credentials();
        String login = result.login();
        MongoCollection<Document> posts = result.posts();
        int fail = 0;
        try {
            List<Document> files = result.files();

            log.info("Fetched {} files for {}", files.size(), login);

            webIds = files.stream().map(doc -> doc.getString("id")).toList();

            actualIds = posts.find().filter(Filters.eq("login", login)).projection(new Document("_id", 1))
                    .into(new ArrayList<>()).stream().map(doc -> String.valueOf(doc.get("_id"))).toList();

            insertIds = webIds.stream().filter(id -> !actualIds.contains(id)).toList();
            removeIds = actualIds.stream().filter(id -> !webIds.contains(id)).toList();

            var resultBulk = posts.bulkWrite(files.stream().filter(doc -> {
                Object rawId = doc.get("id");
                return rawId != null && !rawId.toString().isBlank() && !actualIds.contains(rawId.toString());
            }).map(doc -> {
                Object rawId = doc.get("id");
                if (rawId == null || rawId.toString().isBlank())
                    return null;
                String id = rawId.toString();
                Document stored = new Document(doc).append("_id", id).append("login", login);
                return new InsertOneModel<Document>(stored);
            }).toList());
            log.info("Inserted {} new post documents for {}", resultBulk.getInsertedCount(), login);

            resultBulk = posts.bulkWrite(removeIds.stream().map(id -> {
                return new DeleteOneModel<Document>(Filters.eq("_id", id));
            }).toList());
            log.info("Removed {} stale post documents for {}", resultBulk.getDeletedCount(), login);

        } catch (Exception e) {
            log.error("Could not insert/prune for one login ({}).", rootCause(e).getClass().getSimpleName());
            fail = 1;
            throw new RuntimeException(e);
        }
        return new ResumeDataBase(insertIds, removeIds, login, credential, posts,
                new ProcessResume(fail, webIds.size(), actualIds.size(), 0, removeIds.size() != 0));
    };

    private Function<ResumeDataBase, ProcessResume> getThumbnail = (resume) -> {
        ProcessResume processResume = resume.processResume();
        int thumbnails = 0;
        if (processResume.failures == 0)
            thumbnails = downloadMissingThumbnails(resume.credentials(),

                    resume.posts());

        return new ProcessResume(processResume, thumbnails);
    };

    RunSummary run(List<LoginEntry4to4> selectedLogins, boolean fullCatalogRun, MongoCollection<Document> posts,
            CredentialProvider credentials) {
        // Map<String, List<Document>> inventoryByLogin = new HashMap<>();
        // Map<String, AccountCredentials> credentialsByLogin = new HashMap<>();
        // Integer failures = 0;
        List<ProcessResume> resumes = new ArrayList<>();
        try (ExecutorService executor = Executors
                .newFixedThreadPool(Math.min(MAX_PARALLEL_LOGINS, Math.max(1, selectedLogins.size())))) {

            Function<LoginEntry4to4, Future<LoginInventory>> executeFetch = (login) -> executor
                    .submit(() -> fetch(login, posts, credentials));

            resumes = selectedLogins.stream()
                    // fetch account for get posts
                    .map(executeFetch)
                    // insert/prune in the database
                    .map(insertPrune)
                    // get thumbnail of the new posts
                    .map(getThumbnail).toList();

        }
        var inventoryByLoginSize = resumes.stream().map(r -> r.syncedLogins()).reduce((a, b) -> a + b).get();
        var failures = resumes.stream().map(r -> r.failures()).reduce((a, b) -> a + b).get();
        var currentIdsSize = resumes.stream().map(r -> r.currentFiles()).reduce((a, b) -> a + b).get();
        var thumbnails = resumes.stream().map(r -> r.thumbnailsWritten()).reduce((a, b) -> a + b).get();
        var pruned = resumes.stream().map(r -> r.staleDocumentsPruned()).reduce((a, b) -> a || b).get();

        return new RunSummary(selectedLogins.size(), inventoryByLoginSize, failures, currentIdsSize, thumbnails,
                pruned);
    }

    private LoginInventory fetch(LoginEntry4to4 login, MongoCollection<Document> posts, CredentialProvider credentials)
            throws IOException, InterruptedException {
        AccountCredentials account = credentials.resolve(login);
        List<Document> files = new FourSharedApiClient(account).getAllFiles();
        return new LoginInventory(login.login(), account, files, posts);
    }

    // private Set<String> persistNewDocuments(Map<String, List<Document>>
    // inventoryByLogin) {
    // Set<String> actualIds = new HashSet<>();
    // Map<String, Document> byId = new HashMap<>();
    // inventoryByLogin.forEach((login, files) -> files.forEach(file -> {
    // Object rawId = file.get("id");
    // if (rawId == null || rawId.toString().isBlank())
    // return;
    // String id = rawId.toString();
    // actualIds.add(id);
    // Document stored = new Document(file).append("_id", id).append("login",
    // login);
    // byId.putIfAbsent(id, stored);
    // }));

    // Set<String> existing = new HashSet<>();
    // List<String> ids = new ArrayList<>(byId.keySet());
    // for (int start = 0; start < ids.size(); start += 500) {
    // List<String> chunk = ids.subList(start, Math.min(start + 500, ids.size()));
    // for (Document found : posts.find(Filters.in("_id", chunk)).projection(new
    // Document("_id", 1))) {
    // existing.add(String.valueOf(found.get("_id")));
    // }
    // }
    // List<Document> toInsert = byId.entrySet().stream().filter(entry ->
    // !existing.contains(entry.getKey()))
    // .map(Map.Entry::getValue).toList();
    // if (!toInsert.isEmpty()) {
    // posts.insertMany(toInsert);
    // log.info("Inserted {} new post documents", toInsert.size());
    // }
    // return actualIds;
    // }

    // private void pruneStaleDocuments(Map<String, List<Document>>
    // inventoryByLogin) {
    // long removed = 0;
    // for (Map.Entry<String, List<Document>> inventory :
    // inventoryByLogin.entrySet()) {
    // Set<String> actualIds = new HashSet<>();
    // for (Document file : inventory.getValue()) {
    // if (file.get("id") != null)
    // actualIds.add(file.get("id").toString());
    // }
    // List<String> staleIds = new ArrayList<>();
    // for (Document stored : posts.find(Filters.eq("login", inventory.getKey()))
    // .projection(new Document("_id", 1))) {
    // String id = String.valueOf(stored.get("_id"));
    // if (!actualIds.contains(id))
    // staleIds.add(id);
    // }
    // for (int start = 0; start < staleIds.size(); start += 500) {
    // List<String> chunk = staleIds.subList(start, Math.min(start + 500,
    // staleIds.size()));
    // removed += posts
    // .deleteMany(Filters.and(Filters.eq("login", inventory.getKey()),
    // Filters.in("_id", chunk)))
    // .getDeletedCount();
    // }
    // }
    // log.info("Removed {} stale post documents after a complete catalog run",
    // removed);
    // }

    private int downloadMissingThumbnails(AccountCredentials credentialsByLogin, MongoCollection<Document> posts) {
        try {
            Files.createDirectories(thumbnailDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create thumbnail directory " + thumbnailDirectory, e);
        }
        List<Document> documents = posts.find(Filters.and(Filters.eq("login", credentialsByLogin), Filters.exists("id"),
                Filters.exists("mimeType"), Filters.exists("login"))).into(new ArrayList<>());
        int downloaded = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_THUMBNAILS)) {
            List<Future<Boolean>> jobs = new ArrayList<>();
            for (Document document : documents) {
                String id = document.getString("id");
                String login = document.getString("login");
                if (id == null || login == null)
                    continue;
                Path target = thumbnailDirectory.resolve(id).normalize();
                if (!target.getParent().equals(thumbnailDirectory) || Files.exists(target))
                    continue;
                jobs.add(executor.submit(() -> downloadThumbnail(document, credentialsByLogin, target, posts)));
            }
            for (Future<Boolean> job : jobs) {
                try {
                    if (job.get())
                        downloaded++;
                } catch (Exception e) {
                    log.warn("A thumbnail worker failed ({})", rootCause(e).getClass().getSimpleName());
                }
            }
        }
        return downloaded;
    }

    private boolean downloadThumbnail(Document document, AccountCredentials account, Path target,
            MongoCollection<Document> posts) {
        String id = document.getString("id");
        try {
            byte[] image = new FourSharedApiClient(account).getThumbnail(id, document.getString("name"),
                    document.getString("mimeType"));
            Files.write(target, image);
            String maloiType = document.getString("mimeType") != null
                    && document.getString("mimeType").toLowerCase().contains("video") ? "VIDEO" : "IMG";
            posts.updateOne(Filters.eq("_id", id), new Document("$unset", new Document("errorImg", "")).append("$set",
                    new Document("maloiType", maloiType)));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                Files.write(target, placeholder(id));
                posts.updateOne(Filters.eq("_id", id),
                        new Document("$set", new Document("errorImg", reason).append("maloiType", "IMG")));
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
        while (current.getCause() != null)
            current = current.getCause();
        return current;
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private record LoginInventory(String login, AccountCredentials credentials, List<Document> files,
            MongoCollection<Document> posts) {
    }

    record ProcessResume(int failures, int syncedLogins, int currentFiles, int thumbnailsWritten,
            boolean staleDocumentsPruned) {

        public ProcessResume(ProcessResume processResume, int thumbnails) {
            this(processResume.failures, processResume.syncedLogins, processResume.currentFiles, thumbnails,
                    processResume.staleDocumentsPruned);
        }

    }

    record RunSummary(int requestedLogins, int syncedLogins, int failures, int currentFiles, int thumbnailsWritten,
            boolean staleDocumentsPruned) {
    }

    record ResumeDataBase(List<String> inserted, List<String> removed, String login, AccountCredentials credentials,
            MongoCollection<Document> posts, ProcessResume processResume) {
    }

}
