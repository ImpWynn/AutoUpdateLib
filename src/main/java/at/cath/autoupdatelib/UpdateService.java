package at.cath.autoupdatelib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
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

    public Optional<ReleaseInfo> getLatestReleaseInfo()
            throws IOException, URISyntaxException, InterruptedException {
        var repoInfo = extractRepoInfo(this.githubUrl);
        if (repoInfo.isEmpty()) {
            return Optional.empty();
        }

        var owner = repoInfo.get()[0];
        var repo = repoInfo.get()[1];
        logger.debug("Initialized UpdateService for {}/{}", owner, repo);

        var apiUrl = String.format(
                "https://api.github.com/repos/%s/%s/releases/latest",
                owner, repo
        );

        logger.debug("Fetching release info from: {}", apiUrl);

        var request = HttpRequest.newBuilder()
                .uri(new URI(apiUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        var response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            return Optional.empty();
        }

        var releaseData = objectMapper.readTree(response.body());

        return Optional.of(new ReleaseInfo(
                releaseData.get("tag_name").asText(),
                getMainAssetDownloadUrl(releaseData)));
    }

    /**
     * Downloads the release asset to the specified location
     *
     * @param releaseInfo Release information from getLatestReleaseInfo
     * @param targetPath  Where to save the downloaded file
     * @throws IOException        If there's an error downloading or saving the file
     * @throws URISyntaxException If the download URL is malformed
     */
    public void downloadRelease(ReleaseInfo releaseInfo, Path targetPath, Path oldJarPath)
            throws IOException, URISyntaxException, InterruptedException {
        logger.debug("Downloading release from: {}", releaseInfo.downloadUrl);
        logger.debug("Target path: {}", targetPath);

        var request = HttpRequest.newBuilder()
                .uri(new URI(releaseInfo.downloadUrl))
                .GET()
                .build();

        var response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download release: HTTP " +
                    response.statusCode());
        }

        try (InputStream in = response.body()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(oldJarPath);
                    logger.debug("Deleted old jar file: {}", oldJarPath);
                } catch (IOException e) {
                    logger.error("Failed to delete old jar file: {}, {}", oldJarPath, e);
                }
            }));
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