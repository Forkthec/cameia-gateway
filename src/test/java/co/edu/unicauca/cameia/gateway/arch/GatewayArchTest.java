package co.edu.unicauca.cameia.gateway.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "co.edu.unicauca.cameia.gateway")
class GatewayArchTest {

    @ArchTest
    static final ArchRule sinJpa =
            noClasses()
                    .should().accessClassesThat().resideInAPackage("jakarta.persistence..")
                    .because("El gateway no tiene persistencia — ningún paquete puede importar JPA");

    @ArchTest
    static final ArchRule sinSpringDataJpa =
            noClasses()
                    .should().accessClassesThat().resideInAPackage("org.springframework.data.jpa..")
                    .because("El gateway no usa Spring Data JPA");

    @ArchTest
    static final ArchRule filterNoImportaConfig =
            noClasses()
                    .that().resideInAPackage("..filter..")
                    .should().accessClassesThat().resideInAPackage("..config..")
                    .because("El filtro recibe sus dependencias por inyección de constructor, no importa el paquete config directamente");
}
