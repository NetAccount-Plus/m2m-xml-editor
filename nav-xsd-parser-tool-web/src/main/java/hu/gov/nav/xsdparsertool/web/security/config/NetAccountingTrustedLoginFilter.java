package hu.gov.nav.xsdparsertool.web.security.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Rövid életű, HMAC-aláírt NetAccounting tokenből M2M webes munkamenetet hoz létre.
 */
public class NetAccountingTrustedLoginFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(NetAccountingTrustedLoginFilter.class);
    private static final String LOGIN_PATH = "/sso/trusted-login";
    private static final long MAX_TOKEN_LIFETIME_SECONDS = 120;
    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._@+\\-]{1,80}$");
    private static final Map<String, Long> USED_NONCES = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final String configuredSecret;
    private final HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public NetAccountingTrustedLoginFilter(ObjectMapper objectMapper, String configuredSecret) {
        this.objectMapper = objectMapper;
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String relative = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        return !LOGIN_PATH.equals(relative);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "A trusted login csak POST kéréssel használható.");
            return;
        }
        if (configuredSecret.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "A NetAccounting trusted login nincs konfigurálva.");
            return;
        }

        try {
            TrustedToken token = verifyToken(request.getParameter("token"));
            establishSession(request, response, token);
            String target = request.getContextPath() + token.redirect();
            response.setHeader("Cache-Control", "no-store");
            LOG.info("NetAccounting trusted login accepted: userId={}, org={}, sessionId={}, redirect={}",
                    token.userId(), token.org(), safeSessionId(request.getSession(false)), target);
            response.sendRedirect(target);
        } catch (TrustedLoginException e) {
            LOG.warn("NetAccounting trusted login rejected: {}", e.getMessage());
            response.setHeader("Cache-Control", "no-store");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
        }
    }

    private TrustedToken verifyToken(String rawToken) throws TrustedLoginException {
        if (rawToken == null || rawToken.isBlank()) {
            throw new TrustedLoginException("Hiányzó trusted login token.");
        }
        String[] parts = rawToken.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new TrustedLoginException("Érvénytelen trusted login token formátum.");
        }

        byte[] expected = hmac(parts[0]);
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new TrustedLoginException("Érvénytelen trusted login aláírás.");
        }
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new TrustedLoginException("Érvénytelen trusted login aláírás.");
        }

        final JsonNode payload;
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            payload = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new TrustedLoginException("Érvénytelen trusted login payload.");
        }

        int version = payload.path("v").asInt(0);
        long issuedAt = payload.path("iat").asLong(0);
        long expiresAt = payload.path("exp").asLong(0);
        String nonce = payload.path("nonce").asText("").trim();
        String userId = payload.path("userId").asText("").trim();
        String org = payload.path("org").asText("").trim();
        String redirect = normalizeRedirect(payload.path("redirect").asText("/xml-files.html"));

        long now = Instant.now().getEpochSecond();
        if (version != 1
                || issuedAt <= 0
                || expiresAt <= issuedAt
                || expiresAt - issuedAt > MAX_TOKEN_LIFETIME_SECONDS
                || issuedAt > now + CLOCK_SKEW_SECONDS
                || expiresAt < now - CLOCK_SKEW_SECONDS) {
            throw new TrustedLoginException("A trusted login token lejárt vagy időbélyege érvénytelen.");
        }
        if (!SAFE_ID.matcher(userId).matches() || !SAFE_ID.matcher(org).matches()) {
            throw new TrustedLoginException("Érvénytelen NetAccounting felhasználó vagy szervezet azonosító.");
        }
        if (nonce.length() < 16 || nonce.length() > 128 || !SAFE_ID.matcher(nonce).matches()) {
            throw new TrustedLoginException("Érvénytelen trusted login nonce.");
        }

        purgeExpiredNonces(now);
        Long previous = USED_NONCES.putIfAbsent(nonce, expiresAt);
        if (previous != null) {
            throw new TrustedLoginException("A trusted login token már felhasználásra került.");
        }

        return new TrustedToken(userId, org, redirect);
    }

    private void establishSession(HttpServletRequest request,
                                  HttpServletResponse response,
                                  TrustedToken token) {
        String principal = "netaccounting-" + token.userId();
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);
        session.setAttribute("netaccountingUserId", token.userId());
        session.setAttribute("netaccountingOrg", token.org());

        // Spring Security 6 esetén a SecurityContextHolderFilter nem menti el automatikusan
        // a később létrehozott contextet, ezért itt explicit perzisztáljuk a sessionbe.
        securityContextRepository.saveContext(context, request, response);
    }

    private String normalizeRedirect(String value) throws TrustedLoginException {
        String redirect = value == null || value.isBlank() ? "/xml-files.html" : value.trim();
        if (!redirect.startsWith("/") || redirect.startsWith("//") || redirect.contains("\\")
                || redirect.contains("\r") || redirect.contains("\n")) {
            throw new TrustedLoginException("Érvénytelen trusted login redirect.");
        }
        return redirect;
    }

    private byte[] hmac(String payloadPart) throws TrustedLoginException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(configuredSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payloadPart.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new TrustedLoginException("A trusted login aláírás nem ellenőrizhető.");
        }
    }

    private void purgeExpiredNonces(long now) {
        USED_NONCES.entrySet().removeIf(entry -> entry.getValue() < now - CLOCK_SKEW_SECONDS);
    }

    private String safeSessionId(HttpSession session) {
        if (session == null) return "none";
        String id = session.getId();
        if (id == null || id.length() < 8) return "present";
        return id.substring(0, 8) + "...";
    }

    private record TrustedToken(String userId, String org, String redirect) {}

    private static final class TrustedLoginException extends Exception {
        private static final long serialVersionUID = 1L;
        private TrustedLoginException(String message) {
            super(message);
        }
    }
}
