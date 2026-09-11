package hu.gov.nav.xsdparsertool.web.setup;

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
 * NetAccounting/Tomcat környezetben a mar letezo base_properties_dir valtozobol
 * tolti be az M2M XML Editor kulso konfiguraciojat.
 *
 * <p>A betoltes az alapertelmezett bootstrap feldolgozo elott tortenik, igy a
 * kulso MySQL datasource mar a H2 fallback kiertekelese elott rendelkezesre all.</p>
 */
public class BasePropertiesDirEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String BASE_PROPERTIES_DIR = "base_properties_dir";
    public static final String MAIN_CONFIG_FILE = "nav-xsd-parser-tool-paths.properties";
    public static final String PROPERTY_SOURCE = "netAccountingBaseProperties";
    public static final String DATABASE_PROPERTY_SOURCE = "netAccountingDatabaseProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configuredBaseDir = firstNonBlank(
                environment.getProperty(BASE_PROPERTIES_DIR),
                System.getenv(BASE_PROPERTIES_DIR));
        if (configuredBaseDir == null) {
            return;
        }

        Path baseDir = Path.of(configuredBaseDir).toAbsolutePath().normalize();
        Path mainConfig = baseDir.resolve(MAIN_CONFIG_FILE);
        if (!Files.isRegularFile(mainConfig)) {
            return;
        }

        Properties mainProperties = load(mainConfig, "A NetAccounting M2M konfiguracio nem olvashato: ");

        // Tegyuk a kornyezeti valtozot Spring property-kent is elerhetove, hogy a
        // properties fajlokban ${base_properties_dir} helyettesites hasznalhato legyen.
        Map<String, Object> baseDirProperty = new LinkedHashMap<>();
        baseDirProperty.put(BASE_PROPERTIES_DIR, baseDir.toString());
        addAfterSystemEnvironment(environment.getPropertySources(),
                new MapPropertySource(PROPERTY_SOURCE + "Location", baseDirProperty));

        addAfter(environment.getPropertySources(), PROPERTY_SOURCE + "Location",
                new PropertiesPropertySource(PROPERTY_SOURCE, mainProperties));

        String databaseType = firstNonBlank(
                environment.getProperty("nav.xsdparsertool.database.type"),
                mainProperties.getProperty("nav.xsdparsertool.database.type"));
        if (databaseType == null) {
            return;
        }

        Path databaseConfig = baseDir.resolve("database")
                .resolve(databaseType.trim().toUpperCase() + ".properties");
        if (!Files.isRegularFile(databaseConfig)) {
            return;
        }

        Properties databaseProperties = load(databaseConfig,
                "Az M2M adatbazis-konfiguracio nem olvashato: ");

        // A DB-specifikus fajl ertekei elozzek meg a fo M2M konfiguraciot, de a
        // JVM/system/environment beallitasok tovabbra is felul tudjak irni oket.
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(DATABASE_PROPERTY_SOURCE);
        sources.addBefore(PROPERTY_SOURCE,
                new PropertiesPropertySource(DATABASE_PROPERTY_SOURCE, databaseProperties));
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
