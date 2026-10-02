package com.conversive.aep.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/** Types that travel in workflow input/output must stay usable from {@code engine.workflow}. */
class PureModelArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.conversive.aep");

    static final ArchRule PURE_MODEL = noClasses()
            .that().resideInAnyPackage("..definition.model..", "com.conversive.aep.execution")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "..persistence..",
                    "io.temporal.client..", "io.temporal.worker..", "java.net..")
            .allowEmptyShould(true)
            .because("definition.model and execution types are part of the workflow contract");

    @Test
    void workflowContractTypesAreFreeOfInfrastructure() {
        PURE_MODEL.check(PRODUCTION);
    }
}
