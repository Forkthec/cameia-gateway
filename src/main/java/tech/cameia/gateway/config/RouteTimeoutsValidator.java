package tech.cameia.gateway.config;

import jakarta.annotation.PostConstruct;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Valida al arrancar los tiempos máximos de respuesta de las rutas.
 *
 * <p>Los dos valores se leen como texto de las variables {@code GATEWAY_TIMEOUT_MS} (global) y
 * {@code GATEWAY_INTERVIEWS_TIMEOUT_MS} (ruta de entrevistas). La ruta interpreta su metadato
 * recién al atender una petición, así que un valor inválido solo se notaría con un usuario
 * esperando; aquí se rechaza antes de que el Gateway reciba tráfico y sin aplicar un valor por
 * omisión en silencio.
 *
 * <p>Una variable ausente conserva el valor por omisión de {@code application.yml}. Una variable
 * definida pero vacía no: el marcador de posición solo aplica el valor por omisión cuando la
 * variable no existe, así que la ruta recibiría un texto vacío y fallaría en cada petición; por eso
 * se rechaza aquí con un mensaje claro. El valor tampoco se recorta de espacios: uno sobrante en una
 * variable de entorno es un error de despliegue que debe verse. El mensaje nombra solo la variable
 * validada y recorta el valor recibido para no volcar un texto largo al registro.
 */
@Component
public class RouteTimeoutsValidator {

    private static final String GLOBAL_VARIABLE = "GATEWAY_TIMEOUT_MS";
    private static final String INTERVIEWS_VARIABLE = "GATEWAY_INTERVIEWS_TIMEOUT_MS";

    /**
     * Tope de las dos variables: 10 minutos. Evita que un descuido retenga conexiones y que un
     * valor enorme desborde el reloj de Reactor, que convierte el tiempo a nanosegundos y fallaría
     * con un 500 genérico en cada petición.
     */
    static final long MAX_MILLIS = 600_000L;

    private static final int MAX_REPORTED_CODE_POINTS = 20;

    /** Solo dígitos ASCII, con signo opcional para poder rechazar los negativos con su mensaje. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");

    private final Environment environment;

    /**
     * @param environment entorno del que se leen las dos variables
     */
    public RouteTimeoutsValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * Comprueba las dos variables y detiene el arranque si alguna es inválida.
     *
     * @throws IllegalStateException si una variable definida no es un entero en el rango permitido
     */
    @PostConstruct
    void validate() {
        check(GLOBAL_VARIABLE);
        check(INTERVIEWS_VARIABLE);
    }

    private void check(String variable) {
        String value = environment.getProperty(variable);
        if (value != null && !isPositiveWithin(value)) {
            throw new IllegalStateException(
                    variable + " debe ser un entero entre 1 y " + MAX_MILLIS
                            + " (milisegundos); valor recibido: '" + reported(value) + "'");
        }
    }

    private static boolean isPositiveWithin(String value) {
        if (!INTEGER.matcher(value).matches()) {
            return false;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 && parsed <= MAX_MILLIS;
        } catch (NumberFormatException overflow) {
            // No cabe en un long: es un valor inválido, no un error del Gateway
            return false;
        }
    }

    /** Primeros 20 puntos de código del valor, para que el mensaje no vuelque un texto largo. */
    private static String reported(String value) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= MAX_REPORTED_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_REPORTED_CODE_POINTS));
    }
}
