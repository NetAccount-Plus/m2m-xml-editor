package hu.gov.nav.xsdparsertool.web.netaccounting;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Rövid életű, csak memóriában tárolt XML munkamenetek a NetAccounting integrációhoz.
 * Ezek nem kerülnek az M2M XML állománytárába, így nem igényelnek partnert,
 * archiválást vagy külön fájlkezelési jogosultságot.
 */
@Service
public class NetAccountingEditorSessionService {

    public static final int MAX_XML_SIZE = 16 * 1024 * 1024;
    private static final Duration TTL = Duration.ofMinutes(30);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Entry create(byte[] xml, String fileName, String userId, String orgId, String returnPath) {
        validateXml(xml);
        String safeFileName = normalizeFileName(fileName);
        String safeUserId = requireId(userId, "felhasználó");
        String safeOrgId = requireId(orgId, "szervezet");
        String safeReturnPath = normalizeReturnPath(returnPath);
        purgeExpired();

        String id = UUID.randomUUID().toString();
        Entry entry = new Entry(id, xml.clone(), safeFileName, safeUserId, safeOrgId,
                safeReturnPath, Instant.now().plus(TTL), null);
        entries.put(id, entry);
        return entry;
    }

    public Entry require(String id) {
        purgeExpired();
        Entry entry = entries.get(id);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            if (entry != null) entries.remove(id);
            throw new IllegalArgumentException("A NetAccounting editor munkamenet nem található vagy lejárt.");
        }
        return entry;
    }

    public Entry requireCompleted(String id) {
        Entry entry = require(id);
        if (!entry.completed()) {
            throw new IllegalStateException("A NetAccounting editor munkamenet még nincs befejezve.");
        }
        return entry;
    }

    /**
     * Az editor aktuális XML-jét a meglévő NetAccounting sessionhöz menti és
     * a munkamenetet befejezettnek jelöli. Az azonosító és a tulajdonosi adatok
     * változatlanok maradnak, így később ugyanezzel az editorSessionId-val kérhető le az eredmény.
     */
    public Entry complete(String id, byte[] xml) {
        validateXml(xml);
        Entry current = require(id);
        Entry completed = new Entry(
                current.id(),
                xml.clone(),
                current.fileName(),
                current.userId(),
                current.orgId(),
                current.returnPath(),
                current.expiresAt(),
                Instant.now());
        entries.put(id, completed);
        return completed;
    }

    private void validateXml(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw new IllegalArgumentException("Az XML tartalom üres.");
        }
        if (xml.length > MAX_XML_SIZE) {
            throw new IllegalArgumentException("Az XML állomány túl nagy. Maximum 16 MB engedélyezett.");
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(item -> item.getValue().expiresAt().isBefore(now));
    }

    private String requireId(String value, String label) {
        String result = value == null ? "" : value.trim();
        if (!result.matches("^[A-Za-z0-9._@+\\-]{1,80}$")) {
            throw new IllegalArgumentException("Érvénytelen NetAccounting " + label + " azonosító.");
        }
        return result;
    }

    private String normalizeReturnPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (!result.startsWith("/") || result.startsWith("//") || result.contains("://")
                || result.contains("?") || result.contains("#") || result.contains("\r") || result.contains("\n")) {
            throw new IllegalArgumentException("Érvénytelen NetAccounting returnPath. Csak relatív /... útvonal engedélyezett.");
        }
        if (result.length() > 500) {
            throw new IllegalArgumentException("A NetAccounting returnPath túl hosszú.");
        }
        return result;
    }

    private String normalizeFileName(String value) {
        String result = value == null ? "netaccounting.xml" : value.trim();
        result = result.replace('\\', '_').replace('/', '_').replace('\r', '_').replace('\n', '_').replace('"', '_');
        if (result.isBlank()) result = "netaccounting.xml";
        if (!result.toLowerCase().endsWith(".xml")) result += ".xml";
        if (result.length() > 180) result = result.substring(result.length() - 180);
        return result;
    }

    public record Entry(String id, byte[] xml, String fileName, String userId, String orgId,
                        String returnPath, Instant expiresAt, Instant completedAt) {
        public boolean completed() {
            return completedAt != null;
        }
    }
}
