package hu.gov.nav.xsdparsertool.web.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import hu.gov.nav.xsdparsertool.web.security.SecurityMode;
import hu.gov.nav.xsdparsertool.web.security.PasswordPolicyProperties;
import hu.gov.nav.xsdparsertool.web.security.service.DatabaseUserDetailsService;
import hu.gov.nav.xsdparsertool.web.security.SecurityModeProperties;
import hu.gov.nav.xsdparsertool.web.security.apikey.ApiKeyAuthenticationFilter;
import hu.gov.nav.xsdparsertool.web.security.apikey.ApiKeySecurityProperties;
import hu.gov.nav.xsdparsertool.web.setup.SetupRequiredFilter;
import hu.gov.nav.xsdparsertool.web.setup.SetupStateService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(PasswordPolicyProperties.class)
public class SecurityConfiguration {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/setup.html",
            "/js/pages/setup.js",
            "/styles/setup.css",
            "/api/setup/**",
            "/login.html",
            "/access-denied.html",
            "/login",
            "/favicon.ico",
            "/images/SET_logo.png",
            "/images/SET_logo_dark.png",
            "/styles.css",
            "/styles/**",
            "/images/**",
            "/js/**",
            "/api/security/mode",
            "/api/health",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private final SecurityModeProperties securityModeProperties;
    private final AuditingAuthenticationHandlers auditingAuthenticationHandlers;
    private final ApiKeySecurityProperties apiKeySecurityProperties;

    public SecurityConfiguration(SecurityModeProperties securityModeProperties,
                                 AuditingAuthenticationHandlers auditingAuthenticationHandlers,
                                 ApiKeySecurityProperties apiKeySecurityProperties) {
        this.securityModeProperties = securityModeProperties;
        this.auditingAuthenticationHandlers = auditingAuthenticationHandlers;
        this.apiKeySecurityProperties = apiKeySecurityProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider localAuthenticationProvider(DatabaseUserDetailsService userDetailsService,
                                                                  PasswordEncoder passwordEncoder,
                                                                  VerifiedLoginCredentialHolder credentialHolder) {
        DaoAuthenticationProvider provider = new CapturingDaoAuthenticationProvider(credentialHolder);
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   DaoAuthenticationProvider localAuthenticationProvider,
                                                   SetupStateService setupStateService) throws Exception {
        if (securityModeProperties.getSecurityMode() == SecurityMode.STANDALONE) {
            configureStandalone(http, localAuthenticationProvider, setupStateService);
        } else {
            configureMultiUser(http, localAuthenticationProvider, setupStateService);
        }
        return http.build();
    }

    private void configureStandalone(HttpSecurity http,
                                     DaoAuthenticationProvider localAuthenticationProvider,
                                     SetupStateService setupStateService) throws Exception {
        http.authenticationProvider(localAuthenticationProvider);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'")))
                .sessionManagement(session -> session
                        .invalidSessionUrl("/login.html?sessionExpired=true"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(auditingAuthenticationHandlers)
                        .failureHandler(auditingAuthenticationHandlers)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler(auditingAuthenticationHandlers)
                        .logoutSuccessUrl("/login.html?logout=true")
                        .permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                        .accessDeniedHandler(new JsonAccessDeniedHandler()))
                .addFilterBefore(new SetupRequiredFilter(setupStateService), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeySecurityProperties), AnonymousAuthenticationFilter.class);
    }

    private void configureMultiUser(HttpSecurity http,
                                    DaoAuthenticationProvider localAuthenticationProvider,
                                    SetupStateService setupStateService) throws Exception {
        http.authenticationProvider(localAuthenticationProvider);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'")))
                .sessionManagement(session -> session
                        .invalidSessionUrl("/login.html?sessionExpired=true"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/xml-index-config.html", "/api/xml-index-config/**")
                        .hasAnyRole("ADMIN", "XML_INDEX_CONFIG_MANAGE")
                        .requestMatchers(
                                "/admin.html",
                                "/configuration.html",
                                "/console-log.html",
                                "/audit-log.html",
                                "/users.html",
                                "/user-edit.html",
                                "/api/admin/**",
                                "/api/github-templates/local-delete",
                                "/api/database/**",
                                "/api/proxy-settings/**",
                                "/api/m2m-proxy-settings/**",
                                "/api/users/**",
                                "/h2-console/**")
                        .hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(auditingAuthenticationHandlers)
                        .failureHandler(auditingAuthenticationHandlers)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler(auditingAuthenticationHandlers)
                        .logoutSuccessUrl("/login.html?logout=true")
                        .permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                        .accessDeniedHandler(new JsonAccessDeniedHandler()))
                .addFilterBefore(new SetupRequiredFilter(setupStateService), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeySecurityProperties), AnonymousAuthenticationFilter.class);
    }
}
