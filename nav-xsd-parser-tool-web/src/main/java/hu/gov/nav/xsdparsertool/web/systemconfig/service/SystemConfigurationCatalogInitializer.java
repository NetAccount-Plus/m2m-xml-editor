package hu.gov.nav.xsdparsertool.web.systemconfig.service;

import hu.gov.nav.xsdparsertool.web.secret.service.SystemSecretService;
import hu.gov.nav.xsdparsertool.web.support.RepositoryAccess;
import hu.gov.nav.xsdparsertool.web.systemconfig.entity.SystemConfigurationEntity;
import hu.gov.nav.xsdparsertool.web.systemconfig.repository.SystemConfigurationRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A Flyway migráció után gondoskodik arról, hogy a system_configuration tábla
 * a katalógus minden DATABASE tárolású kulcsát tartalmazza.
 *
 * <p>Friss telepítésnél a Flyway egyes kulcsokat már létrehozhat üres értékkel.
 * Ilyenkor a külső szerverkonfigurációból származó, használható értéket is
 * bemásoljuk a rendszerkonfigurációba. Meglévő nem üres értéket soha nem írunk
 * felül.</p>
 *
 * <p>Az érzékeny DATABASE beállítások normál konfigurációs rekordja üres marad;
 * ha azonban külső konfigurációban tényleges érték érkezik, azt a
 * SystemSecretService titkosítva menti el.</p>
 */
@Component
@Order(100)
public class SystemConfigurationCatalogInitializer implements ApplicationRunner {
    private final SystemConfigurationRepository repository;
    private final SystemSecretService secrets;
    private final Environment environment;

    public SystemConfigurationCatalogInitializer(SystemConfigurationRepository repository,
                                                 SystemSecretService secrets,
                                                 Environment environment) {
        this.repository = repository;
        this.secrets = secrets;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        List<SystemConfigurationEntity> changed = new ArrayList<>();

        for (ConfigurationCatalog.Spec spec : ConfigurationCatalog.ITEMS) {
            if (!"DATABASE".equals(spec.storage())) {
                continue;
            }

            if (ConfigurationCatalog.ENCRYPTED_SECRET_KEYS.contains(spec.key())) {
                initializeEncryptedSecret(spec);
            }

            SystemConfigurationEntity existing = RepositoryAccess.findById(repository, spec.key()).orElse(null);
            if (existing == null) {
                SystemConfigurationEntity entity = new SystemConfigurationEntity();
                entity.setKey(spec.key());
                entity.setValue(initialValue(spec));
                entity.setUpdatedAt(now);
                entity.setUpdatedBy("catalog-initializer");
                changed.add(entity);
                continue;
            }

            // A migrációk által előre létrehozott üres rekordokat töltsük fel a
            // tényleges külső konfigurációból. Ez különösen a könyvtáraknál fontos.
            if (!spec.sensitive() && !StringUtils.hasText(existing.getValue())) {
                String candidate = initialValue(spec);
                if (StringUtils.hasText(candidate)) {
                    existing.setValue(candidate);
                    existing.setUpdatedAt(now);
                    existing.setUpdatedBy("catalog-initializer-external-config");
                    changed.add(existing);
                }
            }
        }

        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
    }

    private void initializeEncryptedSecret(ConfigurationCatalog.Spec spec) {
        if (secrets.exists(spec.key())) {
            return;
        }
        String configured = environment.getProperty(spec.key());
        if (StringUtils.hasText(configured)) {
            secrets.save(spec.key(), configured, "catalog-initializer-external-config");
        }
    }

    private String initialValue(ConfigurationCatalog.Spec spec) {
        if (spec.sensitive()) {
            return "";
        }
        String configured = environment.getProperty(spec.key());
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        return spec.defaultValue();
    }
}
