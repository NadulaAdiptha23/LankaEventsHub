package com.sliit.eventsphere;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replaces the old @SpringBootTest context-load test.
 *
 * That test needed a live MySQL matching whoever's application.properties
 * happened to be checked out, so it failed on any machine without the
 * database - which is exactly the "tests depend on a developer's personal
 * database" problem. Starting the whole context is still worth doing, but
 * as an explicitly opt-in integration test (see the README), not as part
 * of the default `mvn test` run.
 *
 * What is left here are checks that need no database at all but do catch
 * regressions that matter: nobody re-introducing a TODO-shaped hole, and
 * nobody hard-coding a password back into the configuration.
 */
class ArchitectureSmokeTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");

    private Stream<Path> javaSources() throws IOException {
        return Files.walk(MAIN).filter(p -> p.toString().endsWith(".java"));
    }

    @Test
    void noUnfinishedWorkMarkersInProductionCode() throws IOException {
        List<String> offenders = javaSources()
                .filter(p -> {
                    try {
                        String body = Files.readString(p);
                        return body.contains("TODO")
                                || body.contains("FIXME")
                                || body.contains("UnsupportedOperationException")
                                || body.contains("not implemented");
                    } catch (IOException e) {
                        return false;
                    }
                })
                .map(Path::toString)
                .toList();

        assertTrue(offenders.isEmpty(),
                "Production code should not contain unfinished-work markers: " + offenders);
    }

    @Test
    void configurationHasNoHardCodedDatabasePassword() throws IOException {
        String properties = Files.readString(RESOURCES.resolve("application.properties"));

        // Every credential must come from an environment variable with a
        // local-development fallback, never a literal in the file.
        assertTrue(properties.contains("${DB_PASSWORD:"),
                "The datasource password must be read from an environment variable");
        assertTrue(properties.contains("${DB_USERNAME:"),
                "The datasource username must be read from an environment variable");
        assertFalse(properties.contains("spring.datasource.password=changeme"),
                "The old hard-coded password must not come back");
    }

    @Test
    void openInViewIsDisabledSoTemplatesCannotTriggerQueries() throws IOException {
        String properties = Files.readString(RESOURCES.resolve("application.properties"));
        assertTrue(properties.contains("spring.jpa.open-in-view=false"),
                "Pages are rendered from DTOs; open-in-view must stay off");
    }

    @Test
    void errorResponsesDoNotIncludeStackTraces() throws IOException {
        String properties = Files.readString(RESOURCES.resolve("application.properties"));
        assertTrue(properties.contains("server.error.include-stacktrace=never"));
    }

    @Test
    void passwordResetDevModeDefaultsToOff() throws IOException {
        // The safe behaviour has to be the one you get by forgetting to set
        // anything. With this defaulted to true, deploying without setting
        // the variable would hand a working reset link to anyone who typed
        // in an email address.
        String properties = Files.readString(RESOURCES.resolve("application.properties"));
        assertTrue(properties.contains("${APP_PASSWORD_RESET_DEV_MODE:false}"),
                "dev-mode must default to false, not true");
        assertFalse(properties.contains("APP_PASSWORD_RESET_DEV_MODE:true"));
    }

    @Test
    void controllersDoNotInjectRepositoriesDirectly() throws IOException {
        // Controller -> Service -> Repository. A controller that reaches
        // straight for a repository is how business rules end up duplicated
        // in the web layer, and how "this discount belongs to this event"
        // ends up being enforced in one place but not the other.
        List<String> offenders = javaSources()
                .filter(p -> p.toString().contains("/controller/"))
                .filter(p -> {
                    try {
                        return Files.readString(p).contains("import com.sliit.eventsphere.repository.");
                    } catch (IOException e) {
                        return false;
                    }
                })
                .map(Path::toString)
                .toList();

        assertTrue(offenders.isEmpty(),
                "Controllers must go through a service, never a repository: " + offenders);
    }

    @Test
    void noDeadLinksOrPlaceholderControlsInTheTemplates() throws IOException {
        // href="#" is the shape unfinished functionality takes in a
        // Thymeleaf page: a control that looks live and does nothing.
        List<String> offenders;
        try (Stream<Path> templates = Files.walk(RESOURCES.resolve("templates"))) {
            offenders = templates
                    .filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> {
                        try {
                            String body = Files.readString(p);
                            return body.contains("href=\"#\"") || body.contains("TODO");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }

        assertTrue(offenders.isEmpty(),
                "Templates must not contain dead links or unfinished markers: " + offenders);
    }

    // =================================================================
    // The interface
    //
    // The design system's claim is that the pages compose it rather than
    // each inventing its own styling. That decays silently, so the two
    // rules most likely to be broken first are enforced here as well as
    // in verification/check_ui.py - this way `mvn test` catches them
    // even if nobody runs the Python harness.
    // =================================================================

    @Test
    void thereIsOneStylesheetAndItDefinesTheTokens() throws IOException {
        Path theme = RESOURCES.resolve("static/css/theme.css");
        assertTrue(Files.exists(theme), "theme.css is the design system; it must exist");

        String css = Files.readString(theme);
        for (String token : new String[]{"--primary:", "--muted:", "--heading:",
                                          "--s-4:", "--r-lg:", "--t-body:"}) {
            assertTrue(css.contains(token), "theme.css must define " + token);
        }
    }

    @Test
    void templatesDoNotHardCodeTheBrandColour() throws IOException {
        // A stray #2563EB is how a re-theme ends up half-applied.
        List<String> offenders;
        try (Stream<Path> templates = Files.walk(RESOURCES.resolve("templates"))) {
            offenders = templates
                    .filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> {
                        try {
                            // The theme-color meta tag is read before any
                            // stylesheet, so it cannot use a token.
                            String body = Files.readString(p)
                                    .replaceAll("(?s)<!--.*?-->", "")
                                    .replaceAll("<meta[^>]*theme-color[^>]*>", "");
                            return body.toLowerCase().contains("#2563eb");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
        assertTrue(offenders.isEmpty(),
                "Use var(--primary) rather than the literal brand hex: " + offenders);
    }

    @Test
    void everyDashboardPageCanOpenItsNavigationOnAPhone() throws IOException {
        // Under 992px the sidebar is an off-canvas drawer, and the only
        // thing that opens it is the mobileBar fragment. A dashboard page
        // that includes the sidebar but not the bar is unnavigable on a
        // phone - and it looks completely fine on a desktop.
        List<String> offenders;
        try (Stream<Path> templates = Files.walk(RESOURCES.resolve("templates"))) {
            offenders = templates
                    .filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> {
                        try {
                            String body = Files.readString(p);
                            return body.contains("fragments/sidebar :: side(")
                                    && !body.contains("fragments/sidebar :: mobileBar");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
        assertTrue(offenders.isEmpty(),
                "These pages render the sidebar with no way to open it on a phone: " + offenders);
    }

    @Test
    void testsDoNotDependOnADevelopersPersonalDatabase() throws IOException {
        Path testResources = Path.of("src/test/resources/application-test.properties");
        assertTrue(Files.exists(testResources),
                "The test run must have its own isolated configuration");

        // Only the settings are examined, not the comments. This used to
        // read the whole file, so the paragraph at the top of
        // application-test.properties explaining that there must be no
        // localhost:3306 was itself enough to fail the assertion - the
        // test reported a violation that did not exist, and would have
        // gone on doing so no matter how the settings were written.
        String properties = Files.readAllLines(testResources).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#") && !line.startsWith("!"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertFalse(properties.contains("localhost:3306"),
                "Tests must not point at a local MySQL instance");
        assertFalse(properties.toLowerCase().contains("password=root"),
                "Tests must not carry anybody's local database password");
    }
}
