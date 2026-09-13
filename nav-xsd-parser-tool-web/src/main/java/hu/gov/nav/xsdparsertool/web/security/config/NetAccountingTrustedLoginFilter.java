package hu.gov.nav.xsdparsertool.web.security.config;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** Egyszer használható, az M2M szerver által kiadott ticketből webes sessiont hoz létre. */
public class NetAccountingTrustedLoginFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(NetAccountingTrustedLoginFilter.class);
    private static final String LOGIN_PATH = "/sso/trusted-login";

    private final NetAccountingTrustedLoginTokenService tokenService;
    private final HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public NetAccountingTrustedLoginFilter(NetAccountingTrustedLoginTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String relative = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        return !LOGIN_PATH.equals(relative);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "A trusted login csak POST kéréssel használható.");
            return;
        }

        NetAccountingTrustedLoginTokenService.Entry ticket = tokenService.consume(request.getParameter("token"));
        if (ticket == null) {
            LOG.warn("NetAccounting trusted login rejected: hiányzó, lejárt vagy már felhasznált ticket.");
            response.setHeader("Cache-Control", "no-store");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "A trusted login ticket hiányzik, lejárt vagy már felhasználásra került.");
            return;
        }

        establishSession(request, response, ticket);
        String target = request.getContextPath() + ticket.redirect();
        response.setHeader("Cache-Control", "no-store");
        LOG.info("NetAccounting trusted login accepted: userId={}, org={}, sessionId={}, redirect={}",
                ticket.userId(), ticket.org(), safeSessionId(request.getSession(false)), target);
        response.sendRedirect(target);
    }

    private void establishSession(HttpServletRequest request, HttpServletResponse response,
                                  NetAccountingTrustedLoginTokenService.Entry ticket) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                "netaccounting-" + ticket.userId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);
        session.setAttribute("netaccountingUserId", ticket.userId());
        session.setAttribute("netaccountingOrg", ticket.org());
        securityContextRepository.saveContext(context, request, response);
    }

    private String safeSessionId(HttpSession session) {
        if (session == null) return "none";
        String id = session.getId();
        if (id == null || id.length() < 8) return "present";
        return id.substring(0, 8) + "...";
    }
}
