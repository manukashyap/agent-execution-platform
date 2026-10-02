package com.conversive.aep.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowDeterminismArchTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.conversive.aep");

    private static final JavaClasses FIXTURE = new ClassFileImporter()
            .importPackages("com.conversive.aep.archfixture");

    static Stream<ArchRule> rules() {
        return Stream.of(DeterminismRules.ONLY_WORKFLOW_SAFE_DEPENDENCIES,
                DeterminismRules.NO_INFRASTRUCTURE, DeterminismRules.NO_NONDETERMINISM);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void productionWorkflowCodeIsDeterministic(ArchRule rule) {
        rule.check(PRODUCTION);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void ruleCatchesAViolatingWorkflowClass(ArchRule rule) {
        assertThat(rule.evaluate(FIXTURE).hasViolation()).isTrue();
    }
}
