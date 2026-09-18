package tech.cameia.gateway.config;

import com.google.firebase.auth.FirebaseAuth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifica que {@code GATEWAY_CORS_ALLOWED_ORIGIN} admite varios orígenes separados por coma en
 * un único valor, sin sintaxis de lista en el YAML (REQ-09).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
class GlobalCorsConfigTest {

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @Autowired
    FirebaseAuth firebaseAuth;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("CAMEIA_CUENTAS_URL", () -> "http://localhost:1");
        registry.add("CAMEIA_PERFIL_URL", () -> "http://localhost:1");
        registry.add("CAMEIA_ENTREVISTA_URL", () -> "http://localhost:1");
        registry.add("GATEWAY_CORS_ALLOWED_ORIGIN",
                () -> "https://cameia.app,https://cameia-e245f.web.app");
    }

    @BeforeEach
    void initWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void preflight_firstOriginInCommaSeparatedList_isAllowed() {
        webTestClient.options()
                .uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://cameia.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://cameia.app");
    }

    @Test
    void preflight_secondOriginInCommaSeparatedList_isAllowed() {
        webTestClient.options()
                .uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://cameia-e245f.web.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://cameia-e245f.web.app");
    }

    @Test
    void preflight_originNotInList_getsNoAllowOriginHeader() {
        webTestClient.options()
                .uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
