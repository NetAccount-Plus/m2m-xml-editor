package hu.gov.nav.xsdparsertool.web.githubupdater.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.gov.nav.xsdparsertool.core.support.ExceptionSafeOperations;
import hu.gov.nav.xsdparsertool.core.support.SecureFileOperations;
import hu.gov.nav.xsdparsertool.web.githubupdater.config.GitHubSchemaUpdaterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * NetAccounting-specifikus GitHub kliens a publikus NAV sablonrepository-khoz.
 *
 * <p>A WEB_ARCHIVE letöltés szándékosan nem küld GitHub Authorization headert.
 * A nav-gov-hu-templates repository-k publikusak, ezért a web/codeload ZIP
 * letöltéshez nincs szükség tokenre. Ez egyben elkerüli azt is, hogy egy üres,
 * lejárt vagy nem megfelelő scope-pal rendelkező token a publikus archívum
 * letöltését 401/404 válasszal megzavarja.</p>
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

    public NetAccountingPublicGitHubApiClient(GitHubSchemaUpdaterProperties properties,
                                               ObjectMapper objectMapper,
                                               GitHubProxySettingsService proxySettingsService) {
        super(properties, objectMapper, proxySettingsService);
        this.properties = properties;
    }

    @Override
    public void downloadWebArchive(String repositoryName,
                                   String tagName,
                                   Path targetZip) throws IOException, InterruptedException {
        URI uri = URI.create(buildPublicArchiveUrl(repositoryName, tagName));

        LOGGER.info("Public GitHub web archive download: repository={}, tag={}, url={}",
                repositoryName, tagName, uri);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(safeTimeout())
                .build();

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

        HttpResponse<InputStream> response = client.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            try (InputStream ignored = response.body()) {
                // csak biztosítjuk a stream lezárását
            }
            String finalUrl = response.uri() == null ? uri.toString() : response.uri().toString();
            LOGGER.warn("Public GitHub web archive download failed: repository={}, tag={}, requestUrl={}, finalUrl={}, httpStatus={}",
                    repositoryName, tagName, uri, finalUrl, status);
            throw new IOException("GitHub web archive download failed for "
                    + repositoryName + "/" + tagName
                    + ": HTTP " + status
                    + ", requestUrl=" + uri
                    + ", finalUrl=" + finalUrl);
        }

        ExceptionSafeOperations.createDirectories(targetZip.getParent());
        try (InputStream input = response.body()) {
            SecureFileOperations.copyPrivate(
                    input,
                    targetZip,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        LOGGER.info("Public GitHub web archive download completed: repository={}, tag={}, url={}, target={}",
                repositoryName, tagName, response.uri(), targetZip);
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

    private java.time.Duration safeTimeout() {
        java.time.Duration configured = properties.getRequestTimeout();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return java.time.Duration.ofSeconds(60);
        }
        return configured;
    }
}
