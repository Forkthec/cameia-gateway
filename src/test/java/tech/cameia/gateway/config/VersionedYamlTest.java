package tech.cameia.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revisa los {@code application*.yml} versionados sin arrancar el contexto: lo que se comprueba es
 * lo que queda escrito en el repositorio, en todos los perfiles a la vez.
 */
class VersionedYamlTest {

    /**
     * Nombres verificados en {@code spring-configuration-metadata.json} de
     * {@code spring-cloud-gateway-server-webflux-5.0.3.jar}, ambos con {@code false} por defecto.
     */
    private static final String[] WIRETAP_PROPERTIES = {
            "spring.cloud.gateway.server.webflux.httpclient.wiretap",
            "spring.cloud.gateway.server.webflux.httpserver.wiretap"
    };

    /**
     * CM-14 REQ-REG-08, prueba 9 del plan: ningún YAML versionado activa el wiretap de Reactor
     * Netty, que escribiría en el log el cuerpo del registro, contraseña incluida.
     */
    @Test
    void versionedYaml_doesNotEnableWiretap() throws IOException {
        Resource[] files = versionedYamlFiles();
        assertThat(files).hasSizeGreaterThanOrEqualTo(3); // application, -local y -test

        for (Resource file : files) {
            Properties properties = load(file);
            for (String property : WIRETAP_PROPERTIES) {
                assertThat(properties.getProperty(property, "false"))
                        .as("%s en %s", property, file.getFilename())
                        .doesNotContainIgnoringCase("true");
            }
        }
    }

    /**
     * CM-14 REQ-REG-02: {@code application.yml} no activa el perfil {@code local} cuando falta
     * {@code SPRING_PROFILES_ACTIVE}.
     */
    @Test
    void applicationYaml_hasNoDefaultLocalProfile() {
        Properties properties = load(new PathMatchingResourcePatternResolver().getResource("classpath:application.yml"));

        assertThat(properties.getProperty("spring.profiles.active", "")).doesNotContain("local");
    }

    /**
     * REQ-OIDC-07, prueba 8 del plan: {@code application-prod.yml} fija la firma en {@code true}
     * literal. Con un {@code ${...}} una variable de entorno podría apagarla en despliegue.
     */
    @Test
    void prodYaml_declaresSigningEnabledAsLiteral() {
        Properties properties = load(new PathMatchingResourcePatternResolver().getResource("classpath:application-prod.yml"));

        assertThat(properties.getProperty("gateway.oidc.signing-enabled")).isEqualTo("true");
    }

    private Resource[] versionedYamlFiles() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:application*.yml");
    }

    private Properties load(Resource file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(file);
        return factory.getObject();
    }
}
