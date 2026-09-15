package hu.gov.nav.xsdparsertool.web.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A külső konfiguráció kapcsolódó beállításait típusosan összefogó konfigurációs modell.
 *
 * <p>A {@code PathConfigurationProperties} osztály a web modul alkalmazási területéhez tartozik. A típus a réteg felelősségi határain belül tartja a hozzá tartozó adatokat és műveleteket, és nem helyettesíti az alacsonyabb szintű modulok üzleti szolgáltatásait.</p>
 */
@ConfigurationProperties(prefix = "nav.xsdparsertool.paths")
public class PathConfigurationProperties {
    @Schema(description = "HU: A űrlap-specifikus XSD gyökérkönyvtár. EN: Root directory of form-specific XSD files.")
    private String schemaDir;
    @Schema(description = "HU: A közös XSD-k könyvtára. EN: Directory of shared XSD files.")
    private String commonXsdDir;
    @Schema(description = "HU: A UIModel XML-ek könyvtára. EN: Directory containing UIModel XML files.")
    private String uiModelDir;

    @Autowired
    private Environment environment;

    private static final Logger log = LoggerFactory.getLogger(PathConfigurationProperties.class);

    /**
     * A {@code getSchemaDir} művelet lekéri vagy feloldja a kért adatot a rendelkezésre álló forrásokból.
     *
     * <p>A művelet a alkalmazási komponens hívási kontextusában fut; az eredményét a következő réteg közvetlenül használhatja, miközben a komponens saját ellenőrzési és fallback szabályai érvényben maradnak.</p>
     * @return a feloldott vagy lekért érték
     */
    public String getSchemaDir() { return schemaDir; }
    /**
     * A {@code setSchemaDir} művelet frissíti a kapcsolódó állapotot a megadott adatok alapján.
     *
     * <p>A művelet a alkalmazási komponens hívási kontextusában fut; az eredményét a következő réteg közvetlenül használhatja, miközben a komponens saját ellenőrzési és fallback szabályai érvényben maradnak.</p>
     * @param schemaDir a művelet bemeneti {@code schemaDir} értéke
     */
    public void setSchemaDir(String schemaDir) { this.schemaDir = schemaDir; }
    /**
     * A {@code getCommonXsdDir} művelet lekéri vagy feloldja a kért adatot a rendelkezésre álló forrásokból.
     *
     * <p>A művelet a alkalmazási komponens hívási kontextusában fut; az eredményét a következő réteg közvetlenül használhatja, miközben a komponens saját ellenőrzési és fallback szabályai érvényben maradnak.</p>
     * @return a feloldott vagy lekért érték
     */
    public String getCommonXsdDir() { return commonXsdDir; }
    /**
     * A {@code setCommonXsdDir} művelet frissíti a kapcsolódó állapotot a megadott adatok alapján.
     *
     * <p>A művelet a alkalmazási komponens hívási kontextusában fut; az eredményét a következő réteg közvetlenül használhatja, miközben a komponens saját ellenőrzési és fallback szabályai érvényben maradnak.</p>
     * @param commonXsdDir a művelet bemeneti {@code commonXsdDir} értéke
     */
    public void setCommonXsdDir(String commonXsdDir) { this.commonXsdDir = commonXsdDir; }
    /**
     * A {@code getUiModelDir} művelet lekéri vagy feloldja a kért adatot a rendelkezésre álló forrásokból.
     *
     * <p>A művelet a alkalmazási komponens hívási kontextusában fut; az eredményét a következő réteg közvetlenül használhatja, miközben a komponens saját ellenőrzési és fallback szabályai érvényben maradnak.</p>
     * @return a feloldott vagy lekért érték
     */
    public String getUiModelDir() { return uiModelDir; }
    /**
     * A {@code setUiModelDir} művelet frissíti a kapcsolódó állapotot a megadott adatok alapján.
     *
     * <p>A művelet a alkalmazási komponens hívási kontextusában fut; az eredményét a következő réteg közvetlenül használhatja, miközben a komponens saját ellenőrzési és fallback szabályai érvényben maradnak.</p>
     * @param uiModelDir a művelet bemeneti {@code uiModelDir} értéke
     */
    public void setUiModelDir(String uiModelDir) { this.uiModelDir = uiModelDir; }

    /**
     * A {@code getGeneralXsdPath} művelet lekéri vagy feloldja a kért adatot a rendelkezésre álló forrásokból.
     *
     * <p>A fájl- és útvonalkezelést a konfigurált tárhely és a biztonsági korlátok figyelembevételével végzi; a hívó számára csak a feloldott eredményt adja tovább.</p>
     * @return a feloldott vagy lekért érték
     */
    public String getGeneralXsdPath() { return commonXsdDir; }

    /**
     * A {@code logConfiguration} művelet a komponens felelősségi körébe tartozó feldolgozási lépést hajtja végre.
     *
     * <p>A UIModel gyökér konfigurációs könyvtár, nem kötelezően előre feltöltött artefaktumtár. A NAV sablon-repository-k
     * nem minden űrlaphoz publikálnak UIModelt, ezért a konfigurált UIModel gyökeret induláskor létrehozzuk, ha hiányzik.
     * Ettől az XSD-alapú általános űrlaprenderelés UIModel nélkül is használható marad, miközben a később letöltött
     * UIModel fájlok ugyanebbe a gyökérbe telepíthetők.</p>
     */
    @PostConstruct
    public void logConfiguration() {
        ensureUiModelDirectory();

        log.debug("==== PathConfigurationProperties LOADED ====");
        log.debug("schemaDir     = {}", schemaDir);
        log.debug("commonXsdDir  = {}", commonXsdDir);
        log.debug("uiModelDir    = {}", uiModelDir);
        log.debug("ENV nav.xsdparsertool.paths.schema-dir      = {}", environment.getProperty("nav.xsdparsertool.paths.schema-dir"));
        log.debug("ENV nav.xsdparsertool.paths.common-xsd-dir  = {}", environment.getProperty("nav.xsdparsertool.paths.common-xsd-dir"));
        log.debug("ENV nav.xsdparsertool.paths.ui-model-dir    = {}", environment.getProperty("nav.xsdparsertool.paths.ui-model-dir"));
        if (environment instanceof AbstractEnvironment ae) {
            ae.getPropertySources().forEach(ps -> log.debug("PROPERTY SOURCE: {} ({})", ps.getName(), ps.getClass().getName()));
        }
    }

    private void ensureUiModelDirectory() {
        if (uiModelDir == null || uiModelDir.isBlank()) {
            return;
        }
        Path directory = Path.of(uiModelDir.trim()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            log.info("UIModel gyökérkönyvtár elérhető: {}", directory);
        } catch (Exception ex) {
            log.warn("A konfigurált UIModel gyökérkönyvtár nem hozható létre: {}", directory, ex);
        }
    }
}
