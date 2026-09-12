package hu.gov.nav.xsdparsertool.web.setup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * NetAccounting/Tomcat környezetben a mar letezo BASE_PROPERTIES_DIR valtozobol
 * tolti be az M2M XML Editor kulso konfiguraciojat.
 */
public class BasePropertiesDirEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String BASE_PROPERTIES_DIR = "BASE_PROPERTIES_DIR";
    public static final String MAIN_CONFIG_FILE = "nav-xsd-parser-tool-paths.properties";
    public static final String PROPERTY_SOURCE = "netAccountingBaseProperties";
    public static final String DATABASE_PROPERTY_SOURCE = "netAccountingDatabaseProperties";
    public static final String DEFAULTS_PROPERTY_SOURCE = "netAccountingServerDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configuredBaseDir = firstNonBlank(
                environment.getProperty(BASE_PROPERTIES_DIR),
                System.getenv(BASE_PROPERTIES_DIR));
        if (configuredBaseDir == null) {
            return;
        }

        Path baseDir = Path.of(configuredBaseDir + File.separator + "m2m").toAbsolutePath().normalize();
        Path mainConfig = baseDir.resolve(MAIN_CONFIG_FILE);
        if (!Files.isRegularFile(mainConfig)) {
            return;
        }

        Properties mainProperties = load(mainConfig, "A NetAccounting M2M konfiguracio nem olvashato: ");

        Map<String, Object> baseDirProperty = new LinkedHashMap<>();
        baseDirProperty.put(BASE_PROPERTIES_DIR, baseDir.toString());
        addAfterSystemEnvironment(environment.getPropertySources(),
                new MapPropertySource(PROPERTY_SOURCE + "Location", baseDirProperty));

        addAfter(environment.getPropertySources(), PROPERTY_SOURCE + "Location",
                new PropertiesPropertySource(PROPERTY_SOURCE, mainProperties));

        String databaseType = firstNonBlank(
                environment.getProperty("nav.xsdparsertool.database.type"),
                mainProperties.getProperty("nav.xsdparsertool.database.type"));

        if (databaseType != null) {
            Path databaseConfig = baseDir.resolve("database")
                    .resolve(databaseType.trim().toUpperCase() + ".properties");
            if (Files.isRegularFile(databaseConfig)) {
                Properties databaseProperties = load(databaseConfig,
                        "Az M2M adatbazis-konfiguracio nem olvashato: ");
                MutablePropertySources sources = environment.getPropertySources();
                sources.remove(DATABASE_PROPERTY_SOURCE);
                sources.addBefore(PROPERTY_SOURCE,
                        new PropertiesPropertySource(DATABASE_PROPERTY_SOURCE, databaseProperties));
            }
        }

        // A NAV konfiguracios katalogusa ezeket bootstrap kulcskent kotelezoen
        // validalja. Kulso NetAccounting/Tomcat telepitesnel adjunk biztonsagos
        // szerver alapertelmezest, de minden explicit beallitas elozze meg ezeket.
        Properties defaults = new Properties();
        defaults.setProperty("server.servlet.context-path", "/");
        defaults.setProperty("spring.jpa.show-sql", "false");
        defaults.setProperty("nav.xsdparsertool.database.schema", "m2m_xml_editor");
        defaults.setProperty("nav.xsdparsertool.database.encoding", "UTF-8");
        defaults.setProperty("spring.flyway.encoding", "UTF-8");
        defaults.setProperty("spring.h2.console.enabled", "false");
        defaults.setProperty("spring.h2.console.path", "/h2-console");
        defaults.setProperty("logging.pattern.console", "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n");
        defaults.setProperty("logging.pattern.file", "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n");

        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(DEFAULTS_PROPERTY_SOURCE);
        sources.addLast(new PropertiesPropertySource(DEFAULTS_PROPERTY_SOURCE, defaults));
    }

    private Properties load(Path file, String errorPrefix) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException(errorPrefix + file, ex);
        }
    }

    private void addAfterSystemEnvironment(MutablePropertySources sources, MapPropertySource propertySource) {
        sources.remove(propertySource.getName());
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
        } else {
            sources.addFirst(propertySource);
        }
    }

    private void addAfter(MutablePropertySources sources, String relativeName, PropertiesPropertySource propertySource) {
        sources.remove(propertySource.getName());
        if (sources.contains(relativeName)) {
            sources.addAfter(relativeName, propertySource);
        } else {
            sources.addFirst(propertySource);
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
