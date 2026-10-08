package com.sliit.eventsphere.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI is available at /swagger-ui.html once the app is running -
 * useful so team members can see and try each other's REST endpoints
 * without reading through every controller class.
 *
 * The description below is not decoration. The single most common way to
 * misread this API is to assume that being authenticated is enough, and
 * then to be surprised by a 403; saying plainly that ownership is checked
 * server-side against the loaded entity is the fastest way to stop
 * someone "fixing" that 403 by widening a URL rule.
 */
@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    private static final String DESCRIPTION = """
            REST API for EventSphere, a web-based event management system.

            **Authentication.** Every `/api/**` endpoint requires HTTP Basic
            credentials. This chain is stateless and has CSRF disabled, which is
            only safe because it carries no cookie credentials; the
            server-rendered pages use a separate, session-based, CSRF-protected
            chain. Sign in with any seeded demo account (see the README).

            **Authorization.** URL rules and `@PreAuthorize` are a coarse first
            filter only - they can say "some organizer", never "*this*
            organizer". Ownership is enforced in the service layer against the
            entity actually loaded from the database. The acting user is always
            taken from the authenticated principal, never from a path variable,
            a query parameter or a field in the request body: sending
            `{"userId": 5}` does not let you act as user 5.

            **Access levels used below**
            - *public* - no authentication (published event listings only).
            - *authenticated* - any signed-in account, acting on its own data.
            - *owner* - the user the record belongs to.
            - *owning organizer* - the organizer of the event in question, not
              organizers in general.
            - *admin* - the ADMIN role.

            **Status codes.** `400` validation or business-rule failure ·
            `401` not authenticated · `403` authenticated but not allowed ·
            `404` not found · `409` conflict (duplicate registration, duplicate
            payment, venue clash, duplicate account) · `500` unexpected.
            Errors are returned as JSON on this chain.
            """;

    @Bean
    public OpenAPI eventSphereOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventSphere API")
                        .description(DESCRIPTION)
                        .version("v1"))
                // Applied at the document level: every operation needs
                // credentials unless it explicitly opts out.
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH))
                .components(new Components().addSecuritySchemes(BASIC_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("HTTP Basic. Use a seeded demo account; "
                                        + "the role on that account decides what it may do.")));
    }
}
