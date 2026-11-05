package tech.cameia.gateway.filter;

import reactor.core.publisher.Mono;

/**
 * Entrega un token OIDC firmado por Google para un audience (CM-104-correcciones-OIDC).
 *
 * <p>Existe como interfaz para que la suite pruebe la firma sin credenciales de Google
 * (REQ-NF-OIDC-03): las pruebas inyectan una fuente falsa y la implementación real es
 * {@link GoogleIdTokenSource}.
 *
 * <p>Vive en {@code filter} y no en {@code config}: el filtro no puede importar ese paquete
 * (ArchUnit {@code filterDoesNotImportConfig}).
 */
public interface OidcTokenSource {

    /**
     * @param audience URL base del microservicio destino, {@code esquema://host[:puerto]}
     * @return el token OIDC, o un error si no se pudo obtener
     */
    Mono<String> tokenFor(String audience);
}
