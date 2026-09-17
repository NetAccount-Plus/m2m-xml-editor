package hu.gov.nav.xsdparsertool.web.netaccounting;

import java.util.Map;

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

@RestController
@RequestMapping("/api/netaccounting/editor-sessions")
public class NetAccountingEditorController {

    private final NetAccountingEditorSessionService sessionService;

    public NetAccountingEditorController(NetAccountingEditorSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** Szerver-szerver hívás: a NetAccounting átadja a riportból előállított XML-t. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('API_KEY_FULL_ACCESS')")
    public Map<String, Object> create(@RequestParam("xmlFile") MultipartFile xmlFile,
                                      @RequestParam("userId") String userId,
                                      @RequestParam("orgId") String orgId,
                                      @RequestParam(name = "fileName", required = false) String fileName) throws Exception {
        String effectiveFileName = fileName;
        if (effectiveFileName == null || effectiveFileName.isBlank()) {
            effectiveFileName = xmlFile.getOriginalFilename();
        }
        NetAccountingEditorSessionService.Entry entry = sessionService.create(
                xmlFile.getBytes(), effectiveFileName, userId, orgId);
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
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + entry.fileName() + "\"")
                .body(entry.xml());
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
        return Map.of(
                "success", true,
                "editorSessionId", entry.id(),
                "completed", true,
                "completedAt", entry.completedAt().toString(),
                "fileName", entry.fileName());
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info(@PathVariable String id) {
        NetAccountingEditorSessionService.Entry entry = requireOwnedSession(id);
        return Map.of(
                "id", entry.id(),
                "fileName", entry.fileName(),
                "expiresAt", entry.expiresAt().toString(),
                "completed", entry.completed(),
                "completedAt", entry.completedAt() == null ? "" : entry.completedAt().toString());
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
