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

    public Entry create(byte[] xml, String fileName, String userId, String orgId) {
        if (xml == null || xml.length == 0) {
            throw new IllegalArgumentException("Az XML tartalom üres.");
        }
        if (xml.length > MAX_XML_SIZE) {
            throw new IllegalArgumentException("Az XML állomány túl nagy. Maximum 16 MB engedélyezett.");
        }
        String safeFileName = normalizeFileName(fileName);
        String safeUserId = requireId(userId, "felhasználó");
        String safeOrgId = requireId(orgId, "szervezet");
        purgeExpired();

        String id = UUID.randomUUID().toString();
        Entry entry = new Entry(id, xml.clone(), safeFileName, safeUserId, safeOrgId,
                Instant.now().plus(TTL));
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

    private String normalizeFileName(String value) {
        String result = value == null ? "netaccounting.xml" : value.trim();
        result = result.replace('\\', '_').replace('/', '_').replace('\r', '_').replace('\n', '_').replace('"', '_');
        if (result.isBlank()) result = "netaccounting.xml";
        if (!result.toLowerCase().endsWith(".xml")) result += ".xml";
        if (result.length() > 180) result = result.substring(result.length() - 180);
        return result;
    }

    public record Entry(String id, byte[] xml, String fileName, String userId, String orgId, Instant expiresAt) {
    }
}
