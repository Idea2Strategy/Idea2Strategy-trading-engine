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

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val environmentExamples: ConfigurableFileCollection

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
            check(content.contains("ENV I2S_READINESS_FILE=/tmp/idea2strategy-ready")) {
                "$application must declare the readiness marker consumed by orchestration"
            }
            check(content.contains("STOPSIGNAL SIGTERM")) {
                "$application must deliver a graceful SIGTERM to the Java PID 1 process"
            }
            check(content.contains(
                    """HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD test -s "${'$'}I2S_READINESS_FILE" && kill -0 1""")) {
                "$application must check both semantic readiness and Java process liveness"
            }
        }

        val dockerignore = dockerignore.get().asFile
        check(dockerignore.isFile) { "Missing .dockerignore" }
        val ignored = dockerignore.readLines().map(String::trim).toSet()
        check(setOf(".git", ".gradle", "build", "**/build").all(ignored::contains)) {
            ".dockerignore must exclude Git and Gradle build state"
        }

        val requiredEnvironment = mapOf(
            "market-gateway" to setOf(
                "ALPACA_API_KEY",
                "ALPACA_API_SECRET",
                "MARKET_GATEWAY_REDIS_URI",
                "MARKET_GATEWAY_REDIS_KEY_PREFIX",
                "MARKET_GATEWAY_INSTRUMENT_MAPPING_PATH",
                "MARKET_GATEWAY_RIGHTS_EVIDENCE_PATH",
                "I2S_READINESS_FILE",
                "JAVA_TOOL_OPTIONS",
            ),
            "trading-worker" to setOf(
                "SPRING_DATASOURCE_URL",
                "SPRING_DATASOURCE_USERNAME",
                "SPRING_DATASOURCE_PASSWORD",
                "SPRING_FLYWAY_ENABLED",
                "TRADING_MARKET_EVENTS_REDIS_URI",
                "TRADING_MARKET_EVENTS_REDIS_KEY_PREFIX",
                "TRADING_MARKET_EVENTS_CONSUMER_NAME",
                "TRADING_WARMUP_BUNDLE_ROOT",
                "TRADING_BOT_CONTROL_WORKER_ID",
                "TRADING_RUNTIME_SHUTDOWN_DRAIN_TIMEOUT",
                "I2S_READINESS_FILE",
                "JAVA_TOOL_OPTIONS",
            ),
        )
        requiredEnvironment.forEach { (application, requiredKeys) ->
            val example = environmentExamples.files.singleOrNull {
                it.name == "$application.env.example"
            } ?: error("Missing deploy/$application.env.example")
            val entries = example.readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line ->
                    val separator = line.indexOf('=')
                    check(separator > 0) { "Malformed line in ${example.path}: $line" }
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            check(entries.keys.containsAll(requiredKeys)) {
                "$application environment example is missing ${requiredKeys - entries.keys}"
            }
            val secretKeys = entries.keys.filter {
                it.endsWith("_PASSWORD") || it == "ALPACA_API_KEY" || it == "ALPACA_API_SECRET"
            }
            check(secretKeys.all { entries.getValue(it).isEmpty() }) {
                "$application example secret values must stay blank"
            }
            check(entries.getValue("JAVA_TOOL_OPTIONS").contains("-XX:+ExitOnOutOfMemoryError")) {
                "$application must fail the container on an unrecoverable Java heap exhaustion"
            }
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
    environmentExamples.from(
        layout.projectDirectory.file("deploy/market-gateway.env.example"),
        layout.projectDirectory.file("deploy/trading-worker.env.example"),
    )
}

tasks.named("check") {
    dependsOn(verifyContainerBuildContracts)
}
