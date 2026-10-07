package tech.cameia.gateway.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import org.springframework.http.MediaType;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Impide declarar respuestas JSON sin charset en el código de producción (ASVS 4.1.1).
 *
 * <p>Analiza solo el código de producción: las pruebas pueden usar {@code MediaType.APPLICATION_JSON}
 * para construir solicitudes.
 */
@AnalyzeClasses(packages = "tech.cameia.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ContentTypeArchTest {

    @ArchTest
    static final ArchRule productionCodeDoesNotUseBareApplicationJson =
            noClasses()
                    .should().accessField(MediaType.class, "APPLICATION_JSON")
                    .because("Content-Type JSON sin charset incumple ASVS 4.1.1: usar un MediaType con charset UTF-8");
}
