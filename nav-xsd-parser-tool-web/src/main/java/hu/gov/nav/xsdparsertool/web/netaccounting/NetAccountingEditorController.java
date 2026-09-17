package hu.gov.nav.xsdparsertool.web.netaccounting;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/netaccounting/editor-sessions")
public class NetAccountingEditorController {

    private final NetAccountingEditorSessionService sessionService;
    private final String netAccountingBaseUrl;

    public NetAccountingEditorController(NetAccountingEditorSessionService sessionService,
            @Value("${netaccounting.base-url:}") String netAccountingBaseUrl) {
        this.sessionService = sessionService;
        this.netAccountingBaseUrl = normalizeBaseUrl(netAccountingBaseUrl);
    }

    /** Szerver-szerver hívás: a NetAccounting átadja a riportból előállított XML-t. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('API_KEY_FULL_ACCESS')")
    public Map<String, Object> create(@RequestParam("xmlFile") MultipartFile xmlFile,
                                      @RequestParam("userId") String userId,
                                      @RequestParam("orgId") String orgId,
                                      @RequestParam(name = "fileName", required = false) String fileName,
                                      @RequestParam(name = "returnPath", required = false) String returnPath) throws Exception {
        String effectiveFileName = fileName;
        if (effectiveFileName == null || effectiveFileName.isBlank()) {
            effectiveFileName = xmlFile.getOriginalFilename();
        }
        NetAccountingEditorSessionService.Entry entry = sessionService.create(
                xmlFile.getBytes(), effectiveFileName, userId, orgId, returnPath);
        return Map.of(
                "success", true,
                "editorSessionId", entry.id(),
                "fileName", entry.fileName(),
                "expiresInSeconds", 1800);
    }

    /** A már SSO-val beléptetett böngésző innen kapja meg a saját ideiglenes XML-jét. */
    @GetMapping(value = "/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> xml(@PathVariable String id) {
        NetAccountingEditorSessionService.Entry entry = requireOwnedSession(id);
        return xmlResponse(entry);
    }

    /**
     * Szerver-szerver eredménylekérés. Csak befejezett munkamenet XML-je adható vissza,
     * és az endpoint kizárólag FULL_ACCESS API kulccsal érhető el.
     */
    @GetMapping(value = "/{id}/result", produces = MediaType.APPLICATION_XML_VALUE)
    @PreAuthorize("hasAuthority('API_KEY_FULL_ACCESS')")
    public ResponseEntity<byte[]> result(@PathVariable String id) {
        return xmlResponse(sessionService.requireCompleted(id));
    }

    /**
     * Böngészőoldali editor művelet: eltárolja az aktuálisan megszerkesztett XML-t,
     * és befejezettnek jelöli a NetAccounting editor sessiont.
     */
    @PostMapping(value = "/{id}/complete", consumes = MediaType.APPLICATION_XML_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> complete(@PathVariable String id, @RequestBody byte[] xml) {
        requireOwnedSession(id);
        NetAccountingEditorSessionService.Entry entry = sessionService.complete(id, xml);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("editorSessionId", entry.id());
        result.put("completed", true);
        result.put("completedAt", entry.completedAt().toString());
        result.put("fileName", entry.fileName());
        String returnUrl = buildReturnUrl(entry);
        if (returnUrl != null) {
            result.put("returnUrl", returnUrl);
        }
        return result;
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info(@PathVariable String id) {
        NetAccountingEditorSessionService.Entry entry = requireOwnedSession(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.id());
        result.put("fileName", entry.fileName());
        result.put("expiresAt", entry.expiresAt().toString());
        result.put("completed", entry.completed());
        result.put("completedAt", entry.completedAt() == null ? null : entry.completedAt().toString());
        return result;
    }

    private ResponseEntity<byte[]> xmlResponse(NetAccountingEditorSessionService.Entry entry) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + entry.fileName() + "\"")
                .body(entry.xml());
    }

    private String buildReturnUrl(NetAccountingEditorSessionService.Entry entry) {
        if (netAccountingBaseUrl.isBlank() || entry.returnPath() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUriString(netAccountingBaseUrl)
                .path(entry.returnPath())
                .queryParam("editorSessionId", entry.id())
                .build()
                .encode()
                .toUriString();
    }

    private String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private NetAccountingEditorSessionService.Entry requireOwnedSession(String id) {
        NetAccountingEditorSessionService.Entry entry = sessionService.require(id);
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Nincs aktív M2M felhasználói munkamenet.");
        }
        String expectedPrincipal = "netaccounting-" + entry.userId();
        if (!expectedPrincipal.equals(authentication.getName())) {
            throw new org.springframework.security.access.AccessDeniedException("A NetAccounting editor munkamenet más felhasználóhoz tartozik.");
        }
        return entry;
    }
}
