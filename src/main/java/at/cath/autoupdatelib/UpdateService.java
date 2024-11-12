package at.cath.autoupdatelib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateService {
    private final HttpClient httpClient;
    private final Logger logger;
    private final ObjectMapper objectMapper;
    private static final Pattern GITHUB_URL_PATTERN =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)");

    private final String githubUrl;

    public UpdateService(String githubUrl, Logger logger) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(githubUrl);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid GitHub URL");
        }
        this.githubUrl = githubUrl;

        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.objectMapper = new ObjectMapper();
    }

    public record ReleaseInfo(
            String version,
            String downloadUrl
    ) {
    }

    public CompletableFuture<ReleaseInfo> getLatestReleaseInfo() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var repoInfo = extractRepoInfo(this.githubUrl);
                if (repoInfo.isEmpty()) {
                    logger.error("Failed to extract repo info from GitHub URL: {}", githubUrl);
                    return null;
                }

                var owner = repoInfo.get()[0];
                var repo = repoInfo.get()[1];
                logger.debug("Fetching release info for {}/{}", owner, repo);

                var apiUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", owner, repo);
                var request = HttpRequest.newBuilder()
                        .uri(new URI(apiUrl))
                        .header("Accept", "application/vnd.github.v3+json")
                        .GET()
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    logger.error("Failed to fetch release info: HTTP {}", response.statusCode());
                    return null;
                }

                var releaseData = objectMapper.readTree(response.body());
                return new ReleaseInfo(releaseData.get("tag_name").asText(), getMainAssetDownloadUrl(releaseData));
            } catch (Exception e) {
                logger.error("Exception fetching release info", e);
                return null;
            }
        });
    }

    public CompletableFuture<Boolean> downloadRelease(ReleaseInfo releaseInfo, Path oldJarPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("Downloading release from: {}", releaseInfo.downloadUrl);

                var request = HttpRequest.newBuilder()
                        .uri(new URI(releaseInfo.downloadUrl))
                        .GET()
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    logger.error("Failed to download release: HTTP {}", response.statusCode());
                    return false;
                }

                try (InputStream in = response.body()) {
                    Path tempJar = Files.createTempFile("latest", ".jar");
                    Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            copyFileWithStreams(tempJar, oldJarPath);
                            Files.deleteIfExists(tempJar);
                            logger.info("Successfully deleted old jar: {}", oldJarPath);
                        } catch (IOException e) {
                            logger.error("Failed to delete old jar: {}", oldJarPath, e);
                        }
                    }));
                    return true;
                }
            } catch (Exception e) {
                logger.error("Exception during downloadRelease", e);
                return false;
            }
        });
    }

    // Workaround for Windows file locking. Thanks Wynntils!
    private static void copyFileWithStreams(Path source, Path target) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(source.toFile());
             FileChannel sourceChannel = inputStream.getChannel();
             FileOutputStream outputStream = new FileOutputStream(target.toFile());
             FileChannel destinationChannel = outputStream.getChannel()) {

            destinationChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    private Optional<String[]> extractRepoInfo(String githubUrl) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(githubUrl);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new String[]{matcher.group(1), matcher.group(2)});
    }

    private String getMainAssetDownloadUrl(JsonNode releaseData) {
        // Look for the first .jar file that's not a checksum
        for (JsonNode asset : releaseData.get("assets")) {
            String name = asset.get("name").asText().toLowerCase();
            if (name.endsWith(".jar")) {
                return asset.get("browser_download_url").asText();
            }
        }
        return null;
    }
}