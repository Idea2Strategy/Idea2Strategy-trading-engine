plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":modules:trading-application"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-jdbc")
    implementation("org.jooq:jooq")
    runtimeOnly("org.postgresql:postgresql")

    // The pinned canonical baseline is a test input only, shared with apps/trading-worker.
    // Flyway must never reach the runtime classpath; apps/trading-worker's
    // verifyRuntimeDatabaseBoundary task enforces that.
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testFixturesApi("org.flywaydb:flyway-core")
    testFixturesRuntimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("db/canonical-baseline"))
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
