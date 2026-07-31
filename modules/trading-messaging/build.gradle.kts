plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(project(":modules:trading-application"))

    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
