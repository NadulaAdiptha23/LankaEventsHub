package com.sliit.eventsphere.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

/**
 * Two filter chains, because this application genuinely has two kinds of
 * client and mixing them is how CSRF holes get created.
 *
 *  1. /api/**  - a stateless REST API for non-browser clients (Swagger UI,
 *                Postman, curl). Authenticated with HTTP Basic, no session
 *                is created or read, so there are no ambient cookie
 *                credentials for a cross-site request to ride on. That -
 *                not convenience - is why CSRF protection is disabled on
 *                this chain only.
 *
 *  2. everything else - the Thymeleaf application. Session cookie + form
 *                login, and CSRF protection ON for every state-changing
 *                request, including the JSON calls the payment page makes
 *                (which is why those now go to /registrations/** page
 *                endpoints instead of /api/**).
 *
 * The previous single chain did the dangerous thing: session cookies
 * everywhere plus csrf().ignoringRequestMatchers("/api/**"), which meant
 * any website could make a logged-in user's browser POST to /api/payments
 * or DELETE /api/events/{id}.
 *
 * Role rules below are a coarse first filter. Ownership ("is this YOUR
 * event / YOUR registration") is enforced in the service layer, since
 * only the services can see the loaded entity - see EventAccessPolicy.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize on controllers and services
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Keeps track of which sessions belong to which principal, so that
     * ActiveSessionService can end a user's sessions when an administrator
     * changes their role or suspends them. Without a registry there is no
     * way to reach an already-authenticated session from outside it.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * SessionRegistryImpl only learns that a session has ended if servlet
     * session events are published, so this listener is required - without
     * it the registry slowly fills with sessions that no longer exist.
     */
    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    /**
     * Chain 1: the JSON API. Ordered first so /api/** never falls through
     * to the browser chain.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable()) // safe here ONLY because the chain is stateless
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public reads. Declared with the HTTP method, so a PUT or
                // DELETE to the same path is NOT public - the old config
                // used a method-less matcher here, which made
                // "DELETE /api/events/5" reachable without logging in.
                .requestMatchers(HttpMethod.GET, "/api/events/published").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/events/{id:[0-9]+}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/events/{id:[0-9]+}/sessions").permitAll()
                // Role gates; ownership is checked again in the services.
                .requestMatchers("/api/reports/**").hasRole("ADMIN")
                // Saved reports live under their OWN path rather than
                // under /api/reports, deliberately: that prefix is
                // admin-only and must stay that way, while a saved report
                // is something an attendee keeps too. Ownership is the
                // real gate here and SavedReportService applies it - this
                // line only insists on being signed in.
                .requestMatchers("/api/saved-reports/**").authenticated()
                // Writing an announcement is a management action, so the
                // URL rule fails fast on role; AnnouncementService then
                // checks ownership of the specific event, and approval is
                // admin-only inside the service rather than by URL,
                // because approve and submit share the same path shape.
                .requestMatchers("/api/announcements/**").hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers("/api/events/{id:[0-9]+}/announcements/**")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers("/api/refunds/**").hasAnyRole("ADMIN", "ORGANIZER")
                .requestMatchers(HttpMethod.POST, "/api/events").hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/events/{id:[0-9]+}").hasAnyRole("ORGANIZER", "ADMIN")
                // Pinned to the numeric id and nothing deeper. These were
                // "/api/events/**", which also matched
                // DELETE /api/events/{id}/registrations - the endpoint an
                // ATTENDEE uses to cancel their OWN registration - so the
                // URL rule returned 403 before the controller ever ran,
                // making that endpoint unreachable for the only role that
                // needs it. The registration and session sub-paths now
                // fall through to anyRequest().authenticated(), and the
                // services authorize them by ownership, which they
                // already did.
                .requestMatchers(HttpMethod.DELETE, "/api/events/{id:[0-9]+}").hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/events/{id:[0-9]+}/sessions").hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/events/{id:[0-9]+}/sessions/**").hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/events/{id:[0-9]+}/sessions/**").hasAnyRole("ORGANIZER", "ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /** Chain 2: the server-rendered application. */
    @Bean
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF stays ON for every page and page-endpoint POST.
            .csrf(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                // --- public ---
                .requestMatchers(HttpMethod.GET, "/", "/login", "/register",
                        "/forgot-password", "/reset-password").permitAll()
                // The marketing pages. Public by definition - they are what
                // somebody reads BEFORE they have an account.
                .requestMatchers(HttpMethod.GET, "/services", "/about", "/contact").permitAll()
                .requestMatchers(HttpMethod.POST, "/register", "/forgot-password",
                        "/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/events").permitAll()
                .requestMatchers(HttpMethod.GET, "/events/{id:[0-9]+}").permitAll()
                // Static assets. /img holds the site's own imagery and /vendor
                // the locally served Bootstrap, Font Awesome and brand faces -
                // without these two a signed-out visitor is redirected to the
                // login page for every stylesheet on the homepage.
                .requestMatchers("/css/**", "/js/**", "/img/**", "/vendor/**",
                        "/images/**", "/webjars/**").permitAll()
                // Spring's error dispatch must stay reachable, or any
                // exception while rendering a page becomes a redirect to
                // /login that hides the real cause and can loop.
                .requestMatchers("/error", "/favicon.ico").permitAll()
                // Swagger / OpenAPI docs
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // --- admin ---
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // --- organizer / admin ---
                .requestMatchers("/events/new", "/my-events").hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers("/events/*/edit", "/events/*/attendees",
                        "/events/*/invitations/**", "/events/*/cancel-event",
                        "/events/*/complete").hasAnyRole("ORGANIZER", "ADMIN")
                // The agenda's browser routes. The matching /api/events/*/sessions
                // rules are further up; these are the form-post equivalents, and
                // EventAccessPolicy still decides WHICH event may be touched.
                .requestMatchers("/events/*/sessions", "/events/*/sessions/**")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers("/events/*/invitations").hasAnyRole("ORGANIZER", "ADMIN")
                // Discount management. /admin/discounts is already covered
                // by the /admin/** rule above; this is the organizer's own
                // route to the same screen. Which events they may actually
                // touch is decided by EventAccessPolicy in the service -
                // a URL rule can only say "some organizer", never "this
                // organizer".
                .requestMatchers("/discounts", "/discounts/**").hasAnyRole("ORGANIZER", "ADMIN")
                // Announcements. The approval queue is /admin/announcements
                // and is already covered above; these are the composing
                // routes. Only an Administrator can actually approve or
                // publish, and that is enforced in AnnouncementService
                // rather than here, because /announcements/{id}/submit and
                // /announcements/{id}/approve are the same shape to a URL
                // matcher and only one of them is admin-only.
                .requestMatchers("/events/*/announcements", "/events/*/announcements/**",
                        "/announcements/**").hasAnyRole("ORGANIZER", "ADMIN")

                // --- any signed-in user ---
                .requestMatchers("/my-registrations", "/notifications/**", "/profile/**",
                        "/registrations/**").authenticated()
                // Saved reports belong to whoever saved them, including
                // attendees - so this is authenticated, not a role gate,
                // and SavedReportService scopes every read and write to
                // the owner. Note this is /reports, not /admin/reports:
                // the platform dashboard stays administrator-only.
                .requestMatchers("/reports", "/reports/**").authenticated()

                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                // maximumSessions(-1) means "no limit"; it is set here purely
                // to install the strategy that registers each new session
                // with the SessionRegistry above.
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry())
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/events", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/events")
                .permitAll()
            );

        return http.build();
    }
}
