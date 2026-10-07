package tech.cameia.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de {@link ActuatorCharsetWebFilter}: solo completa el charset en
 * {@code /actuator/**} y solo cuando el tipo de contenido no lo declara.
 */
class ActuatorCharsetWebFilterTest {

    private static final MediaType ACTUATOR_TYPE =
            MediaType.parseMediaType("application/vnd.spring-boot.actuator.v3+json");

    private final ActuatorCharsetWebFilter filter = new ActuatorCharsetWebFilter();

    @Test
    void actuatorWithoutCharset_addsUtf8() {
        assertThat(contentTypeAfter("/actuator/health", ACTUATOR_TYPE))
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json;charset=UTF-8");
    }

    @Test
    void actuatorRoot_addsUtf8() {
        assertThat(contentTypeAfter("/actuator", ACTUATOR_TYPE))
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json;charset=UTF-8");
    }

    @Test
    void actuatorWithCharset_isUnchanged() {
        MediaType latin1 = MediaType.parseMediaType("application/json;charset=ISO-8859-1");
        assertThat(contentTypeAfter("/actuator/health", latin1)).isEqualTo("application/json;charset=ISO-8859-1");
    }

    @Test
    void actuatorWithoutContentType_staysWithout() {
        assertThat(contentTypeAfter("/actuator/health", null)).isNull();
    }

    @Test
    void otherPath_isUnchanged() {
        assertThat(contentTypeAfter("/api/v1/profiles/me", MediaType.APPLICATION_JSON))
                .isEqualTo("application/json");
    }

    @Test
    void pathThatOnlyStartsWithTheSameLetters_isUnchanged() {
        assertThat(contentTypeAfter("/actuators", MediaType.APPLICATION_JSON)).isEqualTo("application/json");
    }

    /** Ejecuta el filtro, simula el momento previo a confirmar la respuesta y devuelve el tipo resultante. */
    private String contentTypeAfter(String path, MediaType initialType) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        if (initialType != null) {
            exchange.getResponse().getHeaders().setContentType(initialType);
        }
        filter.filter(exchange, e -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();
        MediaType type = exchange.getResponse().getHeaders().getContentType();
        return type == null ? null : type.toString();
    }
}
