package tech.cameia.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lee la configuración real de {@code application.yml} (sin propiedades de tiempo en la prueba)
 * y comprueba qué tiempo máximo de respuesta tiene cada ruta.
 */
@SpringBootTest(
        classes = TestFirebaseConfig.class,
        properties = {
                "CAMEIA_CUENTAS_URL=http://localhost:9001",
                "CAMEIA_PERFIL_URL=http://localhost:9002"
        })
@ActiveProfiles("test")
class RouteTimeoutConfigTest {

    private static final String RESPONSE_TIMEOUT = "response-timeout";

    @Autowired
    RouteLocator routeLocator;

    @Autowired
    Environment environment;

    @Test
    @DisplayName("La ruta de entrevistas declara 120000 ms por omisión")
    void interviewsRoute_shouldDeclare120000_byDefault() {
        Route route = routeById("cameia-entrevista");

        assertThat(route.getMetadata()).containsEntry(RESPONSE_TIMEOUT, "120000");
    }

    @Test
    @DisplayName("Ninguna otra ruta declara tiempo propio")
    void otherRoutes_shouldNotDeclareResponseTimeout() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).hasSizeGreaterThan(1);
        assertThat(routes)
                .filteredOn(route -> !"cameia-entrevista".equals(route.getId()))
                .allSatisfy(route -> assertThat(route.getMetadata()).doesNotContainKey(RESPONSE_TIMEOUT));
    }

    @Test
    @DisplayName("El tiempo global sigue en 30000 ms por omisión")
    void globalTimeout_shouldStayAt30000_byDefault() {
        Duration global = environment.getProperty(
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout", Duration.class);

        assertThat(global).isEqualTo(Duration.ofMillis(30000));
    }

    private Route routeById(String id) {
        return routeLocator.getRoutes().filter(route -> id.equals(route.getId()))
                .blockFirst();
    }
}
