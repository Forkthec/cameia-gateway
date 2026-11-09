package tech.cameia.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tabla de valores de la validación de los tiempos máximos: cada fila de la especificación con su
 * valor literal y el mensaje exacto.
 */
class RouteTimeoutsValidatorTest {

    private static final String INTERVIEWS = "GATEWAY_INTERVIEWS_TIMEOUT_MS";
    private static final String GLOBAL = "GATEWAY_TIMEOUT_MS";

    private static final String INTERVIEWS_MESSAGE =
            "GATEWAY_INTERVIEWS_TIMEOUT_MS debe ser un entero entre 1 y 600000 (milisegundos); valor recibido: '%s'";
    private static final String GLOBAL_MESSAGE =
            "GATEWAY_TIMEOUT_MS debe ser un entero entre 1 y 600000 (milisegundos); valor recibido: '%s'";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1", "900", "120000", "600000", "+900"})
    @DisplayName("Valores válidos en las dos variables (ausente usa el valor por omisión)")
    void validate_shouldAccept_whenValueIsValidForBoth(String value) {
        assertThatCode(() -> validate(INTERVIEWS, value)).doesNotThrowAnyException();
        assertThatCode(() -> validate(GLOBAL, value)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"600001", "3600000", "9223372036854775807"})
    @DisplayName("Más de 600000 (incluido el mayor long, que desbordaría el reloj de Reactor): falla en las dos variables")
    void validate_shouldReject_whenAboveMaximum(String value) {
        assertThatThrownBy(() -> validate(INTERVIEWS, value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INTERVIEWS_MESSAGE.formatted(value));
        assertThatThrownBy(() -> validate(GLOBAL, value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(GLOBAL_MESSAGE.formatted(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "-120000", "abc", "12.5", "1e3", "120 s", "120000ms", " 900 ",
            "<script>", "٩٠٠", "9223372036854775808", "+", "-", "+0"})
    @DisplayName("Vacía, cero, negativos, no enteros y desbordes fallan en las dos variables")
    void validate_shouldReject_whenValueIsNotAPositiveInteger(String value) {
        assertThatThrownBy(() -> validate(INTERVIEWS, value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INTERVIEWS_MESSAGE.formatted(value));
        assertThatThrownBy(() -> validate(GLOBAL, value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(GLOBAL_MESSAGE.formatted(value));
    }

    @Test
    @DisplayName("Un texto de 10000 caracteres falla y el mensaje recorta el valor a 20")
    void validate_shouldTruncateValueInMessage_whenValueIsHuge() {
        String huge = "x".repeat(10_000);

        assertThatThrownBy(() -> validate(INTERVIEWS, huge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(INTERVIEWS_MESSAGE.formatted("x".repeat(20)));
        assertThatThrownBy(() -> validate(GLOBAL, huge))
                .hasMessage(GLOBAL_MESSAGE.formatted("x".repeat(20)));
    }

    @Test
    @DisplayName("El recorte cuenta puntos de código: no parte un carácter fuera del plano básico")
    void validate_shouldTruncateByCodePoints_whenValueHasSupplementaryCharacters() {
        String emojis = "😀".repeat(30);

        assertThatThrownBy(() -> validate(GLOBAL, emojis))
                .hasMessage(GLOBAL_MESSAGE.formatted("😀".repeat(20)));
    }

    @Test
    @DisplayName("Un valor justo de 20 caracteres no se recorta")
    void validate_shouldNotTruncate_whenValueHasExactlyTwentyCharacters() {
        String twenty = "a".repeat(20);

        assertThatThrownBy(() -> validate(GLOBAL, twenty))
                .hasMessage(GLOBAL_MESSAGE.formatted(twenty));
    }

    @Test
    @DisplayName("El mensaje solo incluye la variable validada, no el valor de la otra")
    void validate_shouldNotExposeOtherProperty_whenOneIsInvalid() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(GLOBAL, "123456")
                .withProperty(INTERVIEWS, "0");

        assertThatThrownBy(() -> new RouteTimeoutsValidator(environment).validate())
                .hasMessage(INTERVIEWS_MESSAGE.formatted("0"))
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("123456"));
    }

    @Test
    @DisplayName("Si la global es inválida, la falla aunque la de entrevistas sea válida")
    void validate_shouldFail_whenOnlyGlobalIsInvalid() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(GLOBAL, "0")
                .withProperty(INTERVIEWS, "900");

        assertThatThrownBy(() -> new RouteTimeoutsValidator(environment).validate())
                .hasMessage(GLOBAL_MESSAGE.formatted("0"));
    }

    private static void validate(String name, String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) {
            environment.setProperty(name, value);
        }
        new RouteTimeoutsValidator(environment).validate();
    }
}
