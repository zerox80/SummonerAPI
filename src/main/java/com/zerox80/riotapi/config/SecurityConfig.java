package com.zerox80.riotapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF-Schutz aktivieren und Token in einem nicht-HttpOnly-Cookie bereitstellen,
            // damit Thymeleaf es ins Formular rendern kann
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            )
            // Strikte Content-Security-Policy mit Nonce-Unterstützung
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(String.join(" ",
                        "default-src 'self';",
                        "base-uri 'self';",
                        "object-src 'none';",
                        "frame-ancestors 'self';",
                        // Inline-Skripte nur mit Nonce zulassen; externe Quellen explizit whitelisten
                        "script-src 'self' 'nonce-{nonce}' https://cdn.jsdelivr.net https://cdnjs.cloudflare.com;",
                        // Styles: erlauben wir 'unsafe-inline' (für Attribute/Bootstrap), plus externe Styles
                        "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com https://cdnjs.cloudflare.com;",
                        // Bilder: lokal, data: (für Chart.js/Icons), sowie externe HTTPS (Profil-Icons etc.)
                        "img-src 'self' data: https://*;",
                        // Schriften: lokale + Google Fonts + FontAwesome (cdnjs)
                        "font-src 'self' https://fonts.gstatic.com https://cdnjs.cloudflare.com;",
                        // XHR/Fetch nur zur eigenen Origin
                        "connect-src 'self';"
                    ))
                )
            )
            // Alle Endpunkte öffentlich
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }
}

