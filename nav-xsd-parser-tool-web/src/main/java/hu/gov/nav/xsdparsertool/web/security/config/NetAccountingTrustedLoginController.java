package hu.gov.nav.xsdparsertool.web.security.config;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NetAccountingTrustedLoginController {

    private final NetAccountingTrustedLoginTokenService tokenService;

    public NetAccountingTrustedLoginController(NetAccountingTrustedLoginTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping(value = "/api/netaccounting/trusted-login-ticket", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('API_KEY_FULL_ACCESS')")
    public Map<String, Object> issue(@RequestParam String userId,
                                     @RequestParam String org,
                                     @RequestParam(defaultValue = "/xml-files.html") String redirect) {
        if (!userId.matches("^[A-Za-z0-9._@+\\-]{1,80}$") || !org.matches("^[A-Za-z0-9._@+\\-]{1,80}$")) {
            throw new IllegalArgumentException("Érvénytelen NetAccounting felhasználó vagy szervezet azonosító.");
        }
        if (!redirect.startsWith("/") || redirect.startsWith("//") || redirect.contains("\\")
                || redirect.contains("\r") || redirect.contains("\n")) {
            throw new IllegalArgumentException("Érvénytelen trusted login redirect.");
        }
        String ticket = tokenService.issue(userId, org, redirect);
        return Map.of("success", true, "token", ticket, "expiresInSeconds", 60);
    }
}
