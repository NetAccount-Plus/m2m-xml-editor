package hu.gov.nav.xsdparsertool.web.githubupdater.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.gov.nav.xsdparsertool.core.support.ExceptionSafeOperations;
import hu.gov.nav.xsdparsertool.core.support.SecureFileOperations;
import hu.gov.nav.xsdparsertool.web.githubupdater.config.GitHubSchemaUpdaterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * NetAccounting-specifikus GitHub kliens a publikus NAV sablonrepository-khoz.
 *
 * <p>A WEB_ARCHIVE letöltés szándékosan nem küld GitHub Authorization headert.
 * A nav-gov-hu-templates repository-k publikusak, ezért a web/codeload ZIP
 * letöltéshez nincs szükség tokenre. Ez egyben elkerüli azt is, hogy egy üres,
 * lejárt vagy nem megfelelő scope-pal rendelkező token a publikus archívum
 * letöltését 401/404 válasszal megzavarja.</p>
 *
 * <p>A GitHub REST API hívások első körben továbbra is a közös kliensen keresztül,
 * tehát a konfigurált tokennel futnak. Ha a GitHub erre HTTP 401 választ ad és
 * van konfigurált token, a publikus művelet pontosan egyszer újrapróbálódik
 * Authorization fejléc nélkül. A további hibák változatlanul továbbterjednek.</p>
 *
 * <p>A hibaüzenet és a log tartalmazza a ténylegesen meghívott URL-t, így a
 * böngészőből/curlból közvetlenül összehasonlítható a szerver által használt
 * címmel.</p>
 */
@Component
@Primary
public class NetAccountingPublicGitHubApiClient extends GitHubApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetAccountingPublicGitHubApiClient.class);

    private final GitHubSchemaUpdaterProperties properties;
    private final ObjectMapper objectMapper;

    public NetAccountingPublicGitHubApiClient(GitHubSchemaUpdaterProperties properties,
                                               ObjectMapper objectMapper,
                                               GitHubProxySettingsService proxySettingsService) {
        super(properties, objectMapper, proxySettingsService);
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RepositorySummary> listOrganizationRepositorySummaries() throws IOException, InterruptedException {
        try {
            return super.listOrganizationRepositorySummaries();
        } catch (IOException ex) {
            if (!shouldRetryWithoutToken(ex)) {
                throw ex;
            }
            LOGGER.warn("GitHub API organization repository query returned HTTP 401 with configured token. Retrying once without Authorization header.");
            return listOrganizationRepositorySummariesWithoutToken();
        }
    }

    @Override
    public List<String> listOrganizationRepositories() throws IOException, InterruptedException {
        try {
            return super.listOrganizationRepositories();
        } catch (IOException ex) {
            if (!shouldRetryWithoutToken(ex)) {
                throw ex;
            }
            LOGGER.warn("GitHub API organization repository query returned HTTP 401 with configured token. Retrying once without Authorization header.");
            return listOrganizationRepositoriesWithoutToken();
        }
    }

    @Override
    public List<String> listRepositoryTags(String repositoryName) throws IOException, InterruptedException {
        try {
            return super.listRepositoryTags(repositoryName);
        } catch (IOException ex) {
            if (!shouldRetryWithoutToken(ex)) {
                throw ex;
            }
            LOGGER.warn("GitHub API tag query returned HTTP 401 with configured token for repository '{}'. Retrying once without Authorization header.", repositoryName);
            return listRepositoryTagsWithoutToken(repositoryName);
        }
    }

    @Override
    public void downloadApiZipball(String repositoryName,
                                   String tagName,
                                   Path targetZip) throws IOException, InterruptedException {
        try {
            super.downloadApiZipball(repositoryName, tagName, targetZip);
        } catch (IOException ex) {
            if (!shouldRetryWithoutToken(ex)) {
                throw ex;
            }
            LOGGER.warn("GitHub API zipball download returned HTTP 401 with configured token for {}/{}. Retrying once without Authorization header.", repositoryName, tagName);
            downloadApiZipballWithoutToken(repositoryName, tagName, targetZip);
        }
    }

    @Override
    public void downloadWebArchive(String repositoryName,
                                   String tagName,
                                   Path targetZip) throws IOException, InterruptedException {
        URI uri = URI.create(buildPublicArchiveUrl(repositoryName, tagName));

        LOGGER.info("Public GitHub web archive download: repository={}, tag={}, url={}",
                repositoryName, tagName, uri);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(safeTimeout())
                .header("Accept", "application/zip,application/octet-stream,*/*")
                .header("User-Agent", "M2M-XML-EDITOR")
                .GET();

        // Szándékosan NINCS Authorization header publikus web archive letöltésnél.
        // A konfigurált extra headereket viszont megtartjuk.
        properties.getWebArchiveHeaders().forEach((name, value) -> {
            if (StringUtils.hasText(name) && value != null) {
                requestBuilder.header(name, value);
            }
        });

        HttpResponse<InputStream> response = publicHttpClient().send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        copySuccessfulPublicResponse(response, uri, targetZip, repositoryName, tagName, "GitHub web archive download");

        LOGGER.info("Public GitHub web archive download completed: repository={}, tag={}, url={}, target={}",
                repositoryName, tagName, response.uri(), targetZip);
    }

    private List<RepositorySummary> listOrganizationRepositorySummariesWithoutToken() throws IOException, InterruptedException {
        List<RepositorySummary> repositories = new ArrayList<>();
        for (int page = 1; page <= Math.max(1, properties.getMaxPages()); page++) {
            URI uri = UriComponentsBuilder.fromUriString(properties.getApiBaseUrl())
                    .pathSegment("orgs", properties.getOrganization(), "repos")
                    .queryParam("type", "all")
                    .queryParam("sort", "updated")
                    .queryParam("direction", "desc")
                    .queryParam("per_page", 100)
                    .queryParam("page", page)
                    .build()
                    .toUri();
            JsonNode response = sendJsonWithoutToken(uri, "organization repository summary query");
            if (!response.isArray() || response.isEmpty()) {
                break;
            }
            for (JsonNode repo : response) {
                String name = repo.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String description = repo.path("description").isNull() ? "" : repo.path("description").asText("");
                String htmlUrl = repo.path("html_url").asText("");
                Instant updatedAt = null;
                String updated = repo.path("pushed_at").asText(repo.path("updated_at").asText(""));
                if (!updated.isBlank()) {
                    try {
                        updatedAt = Instant.parse(updated);
                    } catch (Exception ignored) {
                        // Invalid timestamp should not abort the public catalog refresh.
                    }
                }
                repositories.add(new RepositorySummary(name, description, updatedAt, htmlUrl, repo.path("archived").asBoolean(false)));
            }
            if (response.size() < 100) {
                break;
            }
        }
        return repositories;
    }

    private List<String> listOrganizationRepositoriesWithoutToken() throws IOException, InterruptedException {
        List<String> repositories = new ArrayList<>();
        for (int page = 1; page <= Math.max(1, properties.getMaxPages()); page++) {
            URI uri = UriComponentsBuilder.fromUriString(properties.getApiBaseUrl())
                    .pathSegment("orgs", properties.getOrganization(), "repos")
                    .queryParam("type", "all")
                    .queryParam("per_page", 100)
                    .queryParam("page", page)
                    .build()
                    .toUri();
            JsonNode response = sendJsonWithoutToken(uri, "organization repository query");
            if (!response.isArray() || response.isEmpty()) {
                break;
            }
            for (JsonNode repo : response) {
                JsonNode name = repo.get("name");
                if (name != null && name.isTextual()) {
                    repositories.add(name.asText());
                }
            }
            if (response.size() < 100) {
                break;
            }
        }
        return repositories;
    }

    private List<String> listRepositoryTagsWithoutToken(String repositoryName) throws IOException, InterruptedException {
        List<String> tags = new ArrayList<>();
        for (int page = 1; page <= Math.max(1, properties.getMaxPages()); page++) {
            URI uri = UriComponentsBuilder.fromUriString(properties.getApiBaseUrl())
                    .pathSegment("repos", properties.getOrganization(), repositoryName, "tags")
                    .queryParam("per_page", 100)
                    .queryParam("page", page)
                    .build()
                    .toUri();
            JsonNode response = sendJsonWithoutToken(uri, "repository tag query");
            if (!response.isArray() || response.isEmpty()) {
                break;
            }
            for (JsonNode tag : response) {
                JsonNode name = tag.get("name");
                if (name != null && name.isTextual()) {
                    tags.add(name.asText());
                }
            }
            if (response.size() < 100) {
                break;
            }
        }
        return tags;
    }

    private JsonNode sendJsonWithoutToken(URI uri, String operation) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(safeTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "M2M-XML-EDITOR")
                .GET()
                .build();

        HttpResponse<String> response = publicHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Unauthenticated GitHub API " + operation + " failed: "
                    + uri + " HTTP " + response.statusCode() + " body=" + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private void downloadApiZipballWithoutToken(String repositoryName,
                                                String tagName,
                                                Path targetZip) throws IOException, InterruptedException {
        URI uri = UriComponentsBuilder.fromUriString(properties.getApiBaseUrl())
                .pathSegment("repos", properties.getOrganization(), repositoryName, "zipball", tagName)
                .build()
                .toUri();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(safeTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "M2M-XML-EDITOR")
                .GET()
                .build();

        HttpResponse<InputStream> response = publicHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofInputStream());

        copySuccessfulPublicResponse(response, uri, targetZip, repositoryName, tagName, "GitHub API zipball download");
    }

    private void copySuccessfulPublicResponse(HttpResponse<InputStream> response,
                                              URI requestUri,
                                              Path targetZip,
                                              String repositoryName,
                                              String tagName,
                                              String operation) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            try (InputStream ignored = response.body()) {
                // csak biztosítjuk a stream lezárását
            }
            String finalUrl = response.uri() == null ? requestUri.toString() : response.uri().toString();
            LOGGER.warn("Public GitHub request failed: operation={}, repository={}, tag={}, requestUrl={}, finalUrl={}, httpStatus={}",
                    operation, repositoryName, tagName, requestUri, finalUrl, status);
            throw new IOException(operation + " failed for "
                    + repositoryName + "/" + tagName
                    + ": HTTP " + status
                    + ", requestUrl=" + requestUri
                    + ", finalUrl=" + finalUrl);
        }

        ExceptionSafeOperations.createDirectories(targetZip.getParent());
        try (InputStream input = response.body()) {
            SecureFileOperations.copyPrivate(
                    input,
                    targetZip,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean shouldRetryWithoutToken(IOException ex) {
        if (!properties.hasToken() || ex == null || ex.getMessage() == null) {
            return false;
        }
        String message = ex.getMessage();
        return message.contains("HTTP 401") || message.contains("Bad credentials");
    }

    private HttpClient publicHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(safeTimeout())
                .build();
    }

    private String buildPublicArchiveUrl(String repositoryName, String tagName) {
        String template = StringUtils.hasText(properties.getArchiveUrlTemplate())
                ? properties.getArchiveUrlTemplate()
                : "https://codeload.github.com/{owner}/{repo}/zip/refs/tags/{tag}";

        return template
                .replace("{owner}", encode(properties.getOrganization()))
                .replace("{repo}", encode(repositoryName))
                .replace("{tag}", encode(tagName));
    }

    private String encode(String value) {
        return UriUtils.encodePathSegment(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private Duration safeTimeout() {
        Duration configured = properties.getRequestTimeout();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return Duration.ofSeconds(60);
        }
        return configured;
    }
}
