package hu.gov.nav.xsdparsertool.web.xmlfile.service;

import hu.gov.nav.xsdparsertool.web.githubupdater.dto.GitHubTemplateCatalogDtos;
import hu.gov.nav.xsdparsertool.web.githubupdater.service.GitHubTemplateCatalogService;
import hu.gov.nav.xsdparsertool.schemaregistry.service.FileSystemSchemaRegistryService;
import hu.gov.nav.xsdparsertool.web.config.PathConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Központi frissítési őr az új XML létrehozási folyamatokhoz.
 *
 * <p>Minden új-nyomtatvány használat előtt ellenőrzi a NAV/GitHub katalógust,
 * szükség esetén frissíti a lokális katalógusmetaadatokat, majd a már helyben
 * ismert nyomtatványtípusok aktuális, aktív release-eit automatikusan telepíti.
 * Teljesen új NAV nyomtatványtípusokat nem tölt le automatikusan; azokat admin
 * felhasználó teheti elérhetővé az Űrlapsablonok felületről.</p>
 */
@Service
public class FormTemplateFreshnessService {

    private static final Duration CHECK_INTERVAL = Duration.ofMinutes(5);
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration REFRESH_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration REGISTRY_RELOAD_TIMEOUT = Duration.ofSeconds(45);

    private final GitHubTemplateCatalogService catalogService;
    private final FileSystemSchemaRegistryService schemaRegistryService;
    private final PathConfigurationProperties pathProperties;

    private final Object lock = new Object();
    private volatile Instant lastSuccessfulCheck;

    public FormTemplateFreshnessService(GitHubTemplateCatalogService catalogService,
                                        FileSystemSchemaRegistryService schemaRegistryService,
                                        PathConfigurationProperties pathProperties) {
        this.catalogService = catalogService;
        this.schemaRegistryService = schemaRegistryService;
        this.pathProperties = pathProperties;
    }

    /**
     * Biztosítja, hogy az új XML létrehozásakor használt helyi sablonkészlet
     * legfeljebb néhány perces legyen. A rövid cache megakadályozza, hogy több,
     * egymás utáni képernyőhívás feleslegesen terhelje a publikus katalógust.
     */
    public void ensureFresh() {
        Instant last = lastSuccessfulCheck;
        if (last != null && Duration.between(last, Instant.now()).compareTo(CHECK_INTERVAL) < 0) {
            return;
        }

        synchronized (lock) {
            last = lastSuccessfulCheck;
            if (last != null && Duration.between(last, Instant.now()).compareTo(CHECK_INTERVAL) < 0) {
                return;
            }

            try {
                GitHubTemplateCatalogDtos.ChangeCheckResponse check = catalogService.checkForChanges();
                if (check.changesDetected()) {
                    GitHubTemplateCatalogDtos.RefreshStartResponse started = catalogService.startRefresh();
                    if (started.started() || catalogService.refreshStatus().running()) {
                        waitForCatalogRefresh();
                    }
                }

                boolean installed = installMissingLatestActiveReleases();
                if (installed) {
                    reloadSchemaRegistry();
                }
                lastSuccessfulCheck = Instant.now();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("A NAV nyomtatványsablonok frissítésének ellenőrzése megszakadt.", ex);
            } catch (IOException ex) {
                throw new IllegalStateException("A NAV nyomtatványsablonok frissítésének ellenőrzése sikertelen.", ex);
            }
        }
    }

    private void waitForCatalogRefresh() throws InterruptedException {
        Instant deadline = Instant.now().plus(REFRESH_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            GitHubTemplateCatalogDtos.RefreshStatusResponse status = catalogService.refreshStatus();
            if (!status.running()) {
                if (status.completed() && !status.successful()) {
                    throw new IllegalStateException("A NAV nyomtatványkatalógus frissítése sikertelen: "
                            + status.errorMessage());
                }
                return;
            }
            Thread.sleep(REFRESH_POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("A NAV nyomtatványkatalógus frissítése nem fejeződött be időben.");
    }

    private boolean installMissingLatestActiveReleases() {
        GitHubTemplateCatalogDtos.CatalogResponse catalog = catalogService.catalog(false);
        List<GitHubTemplateCatalogDtos.DownloadItem> downloads = new ArrayList<>();
        Set<String> handledRepositories = new HashSet<>();

        for (GitHubTemplateCatalogDtos.TemplateRow row : catalog.rows()) {
            if (row == null || row.repository() == null || row.repository().isBlank()) {
                continue;
            }
            if (row.disabled() || row.releaseTag() == null || row.releaseTag().isBlank()) {
                continue;
            }

            // Automatikus frissítés csak olyan nyomtatványtípusra futhat,
            // amelyből már van helyben ismert/telepített verzió. Így egy teljesen
            // új NAV formType csak admin döntés után válik használhatóvá.
            if (!row.locallyKnown()) {
                continue;
            }

            if (!handledRepositories.add(row.repository())) {
                continue;
            }

            if (!row.locallyAvailable()) {
                downloads.add(new GitHubTemplateCatalogDtos.DownloadItem(row.repository(), row.releaseTag()));
            }
        }

        if (downloads.isEmpty()) {
            return false;
        }

        catalogService.download(new GitHubTemplateCatalogDtos.DownloadRequest(downloads, false));
        return true;
    }

    private void reloadSchemaRegistry() throws InterruptedException {
        Path schemaRoot = configuredPath(pathProperties.getSchemaDir());
        Path commonRoot = configuredPath(pathProperties.getCommonXsdDir());
        schemaRegistryService.reloadAsync(schemaRoot, commonRoot);

        Instant deadline = Instant.now().plus(REGISTRY_RELOAD_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var status = schemaRegistryService.getStatus();
            if (!status.isLoading()) {
                if (!status.isReady()) {
                    throw new IllegalStateException("A séma-regiszter frissítése sikertelen: " + status.getPhase());
                }
                return;
            }
            Thread.sleep(REFRESH_POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("A séma-regiszter frissítése nem fejeződött be időben.");
    }

    private Path configuredPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }
}
