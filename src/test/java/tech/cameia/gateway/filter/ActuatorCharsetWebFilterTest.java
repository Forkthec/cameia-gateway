package tech.cameia.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de {@link ActuatorCharsetWebFilter}: solo completa el charset bajo la ruta base de
 * actuator, solo en tipos de texto y solo cuando el tipo de contenido no lo declara.
 */
class ActuatorCharsetWebFilterTest {

    private static final MediaType ACTUATOR_TYPE =
            MediaType.parseMediaType("application/vnd.spring-boot.actuator.v3+json");

    private final ActuatorCharsetWebFilter filter = new ActuatorCharsetWebFilter("/actuator");

    /** Un punto de actuator con el tipo propio de Spring Boot recibe {@code charset=UTF-8}. */
    @Test
    void actuatorWithoutCharset_addsUtf8() {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator/health"), ACTUATOR_TYPE))
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json;charset=UTF-8");
    }

    /** La ruta base sola (índice de actuator) también recibe el charset. */
    @Test
    void actuatorRoot_addsUtf8() {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator"), ACTUATOR_TYPE))
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json;charset=UTF-8");
    }

    /** Todo tipo de texto (JSON, {@code +json} y {@code text/*}) recibe el charset. */
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/problem+json", "text/plain"})
    void actuatorTextType_addsUtf8(String type) {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator/x"), MediaType.parseMediaType(type)))
                .isEqualTo(type + ";charset=UTF-8");
    }

    /** Un cuerpo binario (por ejemplo un volcado de memoria) no tiene charset y queda intacto. */
    @Test
    void actuatorBinaryType_isUnchanged() {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator/heapdump"),
                MediaType.APPLICATION_OCTET_STREAM))
                .isEqualTo("application/octet-stream");
    }

    /** Un charset ya declarado se respeta, aunque no sea UTF-8. */
    @Test
    void actuatorWithCharset_isUnchanged() {
        MediaType latin1 = MediaType.parseMediaType("application/json;charset=ISO-8859-1");
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator/health"), latin1))
                .isEqualTo("application/json;charset=ISO-8859-1");
    }

    /** Una respuesta sin tipo de contenido sigue sin él. */
    @Test
    void actuatorWithoutContentType_staysWithout() {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator/health"), null)).isNull();
    }

    /** Fuera de actuator el filtro no toca nada, ni una ruta que solo empieza con las mismas letras. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/profiles/me", "/actuators"})
    void otherPath_isUnchanged(String path) {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get(path), MediaType.APPLICATION_JSON))
                .isEqualTo("application/json");
    }

    /** Los parámetros de matriz no sacan la petición de actuator. */
    @Test
    void actuatorWithMatrixParameters_addsUtf8() {
        assertThat(contentTypeAfter(filter, MockServerHttpRequest.get("/actuator;x=1/health"), ACTUATOR_TYPE))
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json;charset=UTF-8");
    }

    /** Con prefijo de aplicación, la ruta se compara sin él. */
    @Test
    void applicationPrefix_isIgnored() {
        MockServerHttpRequest.BaseBuilder<?> request =
                MockServerHttpRequest.get("/gw/actuator/health").contextPath("/gw");
        assertThat(contentTypeAfter(filter, request, ACTUATOR_TYPE))
                .isEqualTo("application/vnd.spring-boot.actuator.v3+json;charset=UTF-8");
    }

    /** Con otra ruta base, actúa sobre ella y deja de actuar sobre {@code /actuator}. */
    @ParameterizedTest
    @CsvSource({"/manage/health,true", "/actuator/health,false"})
    void customBasePath_isRespected(String path, boolean changed) {
        ActuatorCharsetWebFilter custom = new ActuatorCharsetWebFilter("/manage/");
        String expected = changed
                ? "application/vnd.spring-boot.actuator.v3+json;charset=UTF-8"
                : "application/vnd.spring-boot.actuator.v3+json";
        assertThat(contentTypeAfter(custom, MockServerHttpRequest.get(path), ACTUATOR_TYPE)).isEqualTo(expected);
    }

    /** Una ruta base en la raíz se rechaza al arrancar: actuator se mezclaría con las rutas proxificadas. */
    @ParameterizedTest
    @ValueSource(strings = {"/", ""})
    void rootBasePath_failsAtStartup(String basePath) {
        assertThatThrownBy(() -> new ActuatorCharsetWebFilter(basePath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.endpoints.web.base-path");
    }

    /** Ejecuta el filtro, simula el momento previo a confirmar la respuesta y devuelve el tipo resultante. */
    private static String contentTypeAfter(ActuatorCharsetWebFilter target,
                                           MockServerHttpRequest.BaseBuilder<?> request, MediaType initialType) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (initialType != null) {
            exchange.getResponse().getHeaders().setContentType(initialType);
        }
        target.filter(exchange, e -> Mono.empty()).block();
        exchange.getResponse().setComplete().block();
        MediaType type = exchange.getResponse().getHeaders().getContentType();
        return type == null ? null : type.toString();
    }
}
