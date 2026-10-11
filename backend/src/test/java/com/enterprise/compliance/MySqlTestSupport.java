package com.enterprise.compliance;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class MySqlTestSupport {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/compliance_platform_test(?:\\?.*)?")) {
            throw new IllegalStateException("Tests require an isolated local compliance_platform_test database");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DB_PASSWORD"));
        registry.add("logging.file.name", () -> "target/test-logs/application.log");
        registry.add("app.jwt.secret", () -> java.util.Base64.getEncoder().encodeToString("stage2-test-key-not-for-deployment".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        registry.add("app.initial-admin.enabled", () -> false);
    }
}
