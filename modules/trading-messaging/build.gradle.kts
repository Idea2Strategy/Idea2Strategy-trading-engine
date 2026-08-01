plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(project(":modules:trading-application"))

    testFixturesApi(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testFixturesApi("com.fasterxml.jackson.core:jackson-databind")
    testFixturesApi("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testFixturesImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
