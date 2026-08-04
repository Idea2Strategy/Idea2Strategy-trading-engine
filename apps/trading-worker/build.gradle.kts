plugins {
    java
    id("org.springframework.boot")
}

abstract class VerifyNoFlywayRuntime : DefaultTask() {
    @get:Classpath
    abstract val flywayArtifacts: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val artifactNames = flywayArtifacts.files.map { it.name }.sorted()
        check(artifactNames.isEmpty()) {
            "trading-worker runtimeClasspath must not contain Flyway artifacts: $artifactNames"
        }
    }
}

val runtimeClasspath by configurations.getting
val flywayRuntimeFiles = runtimeClasspath.incoming.artifactView {
    componentFilter {
        it is org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.group == "org.flywaydb"
    }
}.files

val verifyRuntimeDatabaseBoundary by tasks.registering(VerifyNoFlywayRuntime::class) {
    group = "verification"
    description = "Verifies that the trading-worker runtime cannot execute Flyway migrations."
    flywayArtifacts.from(flywayRuntimeFiles)
}

tasks.named("check") {
    dependsOn(verifyRuntimeDatabaseBoundary)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(project(":modules:trading-application"))
    implementation(project(":modules:strategy-runtime"))
    implementation(project(":modules:market-data-adapter"))
    implementation(project(":modules:trading-persistence"))
    implementation(project(":modules:trading-messaging"))
    implementation("io.lettuce:lettuce-core")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The pinned canonical baseline. Stores are being migrated to the canonical schema one at a
    // time, so this app's tests need both it and the private compatibility migrations standing.
    testImplementation(testFixtures(project(":modules:trading-persistence")))
    testImplementation(testFixtures(project(":modules:trading-messaging")))
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
