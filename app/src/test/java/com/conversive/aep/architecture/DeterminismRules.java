package com.conversive.aep.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.conversive.aep.nodes.DryRunOptions;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

/** 06 §2 rule 1: workflow code must replay deterministically. */
final class DeterminismRules {

    static final String WORKFLOW_PACKAGE = "..engine.workflow..";

    /**
     * P1 additions: {@code definition.model..} and the exact {@code execution} package (pure workflow
     * contract types, kept pure by {@code PureModelArchTest}) and the single record
     * {@code nodes.DryRunOptions} (part of {@code ExecutionRequest}).
     */
    static final ArchRule ONLY_WORKFLOW_SAFE_DEPENDENCIES = classes()
            .that().resideInAPackage(WORKFLOW_PACKAGE)
            .should().onlyDependOnClassesThat(JavaClass.Predicates.resideInAnyPackage(
                    "java.lang..", "java.util", "java.util.function..", "java.util.stream..", "java.time..",
                    "java.math..", "java.io..", "java.lang.invoke..",
                    "..engine..", "com.conversive.aep.common",
                    "com.conversive.aep.definition.model..", "com.conversive.aep.execution",
                    "io.temporal.workflow..", "io.temporal.activity..", "io.temporal.common..",
                    "io.temporal.failure..", "io.temporal.api.enums..",
                    "com.fasterxml.jackson..", "org.slf4j..")
                    .or(JavaClass.Predicates.equivalentTo(DryRunOptions.class)))
            .allowEmptyShould(true)
            .because("engine.workflow may use only engine, common, the workflow contract types and Temporal "
                    + "workflow APIs");

    static final ArchRule NO_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage(WORKFLOW_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "java.sql..", "javax.sql..", "..persistence..",
                    "..common.http..", "..common.config..", "..engine.temporal..",
                    "io.temporal.client..", "io.temporal.worker..", "io.temporal.serviceclient..",
                    "java.util.concurrent..", "java.net..")
            .allowEmptyShould(true)
            .because("workflow code must not touch Spring, JDBC, I/O or threads");

    static final ArchRule NO_NONDETERMINISM = noClasses()
            .that().resideInAPackage(WORKFLOW_PACKAGE)
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .orShould().callMethod(UUID.class, "randomUUID")
            .orShould().callMethod(Math.class, "random")
            .orShould().dependOnClassesThat().areAssignableTo(java.util.Random.class)
            .orShould().dependOnClassesThat().areAssignableTo(Thread.class)
            .orShould().dependOnClassesThat().areAssignableTo(Clock.class)
            .allowEmptyShould(true)
            .because("use Workflow.currentTimeMillis / Workflow.randomUUID / Workflow.sleep instead");

    private DeterminismRules() {
    }
}
