plugins {
    base
    id("org.springframework.boot") version "4.1.0" apply false
}

allprojects {
    group = "com.idea2strategy"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

abstract class VerifyContainerBuildContracts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dockerfiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dockerignore: RegularFileProperty

    @TaskAction
    fun verify() {
        val requiredApplications = mapOf(
            "market-gateway" to "apps/market-gateway/build/libs/market-gateway-0.1.0-SNAPSHOT.jar",
            "trading-worker" to "apps/trading-worker/build/libs/trading-worker-0.1.0-SNAPSHOT.jar",
        )

        requiredApplications.forEach { (application, jarPath) ->
            val dockerfile = dockerfiles.files.singleOrNull { it.invariantSeparatorsPath.endsWith("apps/$application/Dockerfile") }
                ?: error("Missing apps/$application/Dockerfile")
            val content = dockerfile.readText()
            check(Regex("""(?m)^# syntax=docker/dockerfile:1\.18@sha256:[0-9a-f]{64}${'$'}""").containsMatchIn(content)) {
                "$application must pin the Dockerfile frontend by digest"
            }
            check(Regex("""(?m)^FROM --platform=\${'$'}BUILDPLATFORM eclipse-temurin:21-jdk-jammy@sha256:[0-9a-f]{64} AS build${'$'}""").containsMatchIn(content)) {
                "$application builder must pin a multi-platform Java 21 image by digest"
            }
            check(Regex("""(?m)^FROM eclipse-temurin:21-jre-jammy@sha256:[0-9a-f]{64}${'$'}""").containsMatchIn(content)) {
                "$application runtime must pin a multi-platform Java 21 image by digest"
            }
            check(content.contains("./gradlew :apps:$application:bootJar --no-daemon")) {
                "$application must build its own Spring Boot executable jar"
            }
            check(content.contains("sed -i 's/\\r$//' gradlew")) {
                "$application build must tolerate a CRLF Gradle wrapper from Windows checkouts"
            }
            check(content.contains("COPY --from=build --chown=10001:10001 /workspace/$jarPath /opt/idea2strategy/application.jar")) {
                "$application must copy the expected executable jar"
            }
            check(Regex("""(?m)^USER [1-9][0-9]*:[1-9][0-9]*${'$'}""").containsMatchIn(content)) {
                "$application runtime must use an explicit non-root uid and gid"
            }
            check(content.contains("""ENTRYPOINT ["java","-jar","/opt/idea2strategy/application.jar"]""")) {
                "$application must launch the executable jar without a shell"
            }
        }

        val dockerignore = dockerignore.get().asFile
        check(dockerignore.isFile) { "Missing .dockerignore" }
        val ignored = dockerignore.readLines().map(String::trim).toSet()
        check(setOf(".git", ".gradle", "build", "**/build").all(ignored::contains)) {
            ".dockerignore must exclude Git and Gradle build state"
        }
    }
}

val verifyContainerBuildContracts by tasks.registering(VerifyContainerBuildContracts::class) {
    group = "verification"
    description = "Verifies pinned, multi-platform, non-root runtime image contracts."
    dockerfiles.from(
        layout.projectDirectory.file("apps/market-gateway/Dockerfile"),
        layout.projectDirectory.file("apps/trading-worker/Dockerfile"),
    )
    dockerignore.set(layout.projectDirectory.file(".dockerignore"))
}

tasks.named("check") {
    dependsOn(verifyContainerBuildContracts)
}
