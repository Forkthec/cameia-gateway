package tech.cameia.gateway.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import org.springframework.http.MediaType;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "tech.cameia.gateway")
class GatewayArchTest {

    @ArchTest
    static final ArchRule noJpa =
            noClasses()
                    .should().accessClassesThat().resideInAPackage("jakarta.persistence..")
                    .because("El gateway no tiene persistencia — ningún paquete puede importar JPA");

    @ArchTest
    static final ArchRule noSpringDataJpa =
            noClasses()
                    .should().accessClassesThat().resideInAPackage("org.springframework.data.jpa..")
                    .because("El gateway no usa Spring Data JPA");

    @ArchTest
    static final ArchRule filterDoesNotImportConfig =
            noClasses()
                    .that().resideInAPackage("..filter..")
                    .should().accessClassesThat().resideInAPackage("..config..")
                    .because("El filtro recibe sus dependencias por inyección de constructor, no importa el paquete config directamente");

    /**
     * El código de producción no usa las constantes de {@link MediaType} de texto que no declaran charset
     * (ASVS 4.1.1): cada respuesta se escribe con un tipo construido con charset UTF-8.
     *
     * <p>Se excluyen las clases de prueba, que usan estas constantes para construir solicitudes. Límite
     * conocido: las constantes {@code *_VALUE} son {@code String} que el compilador copia en el código que
     * las usa, y un literal como {@code "application/json"} tampoco deja rastro de acceso; esos casos los
     * detectan las pruebas que comprueban el {@code Content-Type} de cada respuesta.
     */
    @ArchTest
    static final ArchRule productionCodeDoesNotUseCharsetlessTextMediaTypes =
            noClasses()
                    .that().haveSimpleNameNotEndingWith("Test")
                    .should().accessField(MediaType.class, "APPLICATION_JSON")
                    .orShould().accessField(MediaType.class, "APPLICATION_PROBLEM_JSON")
                    .orShould().accessField(MediaType.class, "APPLICATION_NDJSON")
                    .orShould().accessField(MediaType.class, "TEXT_PLAIN")
                    .orShould().accessField(MediaType.class, "TEXT_HTML")
                    .because("Content-Type de texto sin charset incumple ASVS 4.1.1: usar un MediaType con charset UTF-8");
}
