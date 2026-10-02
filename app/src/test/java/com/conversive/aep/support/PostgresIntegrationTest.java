package com.conversive.aep.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for Spring integration tests that need the app DB. One Postgres 16 container is shared by
 * every subclass (and every Spring context) for the whole test JVM; Flyway migrates it on startup.
 * The Temporal worker is not started (profile {@code test}); stubs connect lazily.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    /** Every cached Spring context keeps its own Hikari pool open against the shared container. */
    private static final int MAX_CONNECTIONS = 400;

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);

    static {
        POSTGRES.start();
    }
}
