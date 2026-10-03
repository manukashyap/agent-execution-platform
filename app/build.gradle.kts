plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.micrometer.prometheus)
    implementation(libs.temporal.sdk)
    implementation(libs.json.schema.validator)
    implementation(libs.httpclient5)
    runtimeOnly(libs.postgresql)
    developmentOnly(libs.spring.boot.docker.compose)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.temporal.testing)
    testImplementation(libs.wiremock)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "dev")
}
