import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    `java-library`
}

val grpcVersion: String by extra
val protobufVersion: String by extra

// This module is excluded from the default build.
// Run with: ./gradlew :paygate-integration-tests:test -Pintegration
// Spring Security tests: ./gradlew :paygate-integration-tests:securityTest -Pintegration
// Live Wavelength spike: ./gradlew :paygate-integration-tests:wavelengthSpike -PwavelengthSpike
// Offline Wavelength tests: ./gradlew :paygate-integration-tests:wavelengthSpikeTest -Pintegration

// Separate source sets keep overlapping Spring applications and the funded Wavelength spike out
// of each other's test discovery and out of the ordinary test lifecycle.
val securityTestSourceSet = sourceSets.create("securityTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val wavelengthSpikeSourceSet = sourceSets.create("wavelengthSpike") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val wavelengthSpikeTestSourceSet = sourceSets.create("wavelengthSpikeTest") {
    compileClasspath += sourceSets.main.get().output + wavelengthSpikeSourceSet.output
    runtimeClasspath += sourceSets.main.get().output + wavelengthSpikeSourceSet.output
}

val securityTestImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val securityTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
    extendsFrom(configurations.testRuntimeOnly.get())
}
val wavelengthSpikeImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val wavelengthSpikeRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}
val wavelengthSpikeTestImplementation by configurations.getting {
    extendsFrom(wavelengthSpikeImplementation)
}
val wavelengthSpikeTestRuntimeOnly by configurations.getting {
    extendsFrom(wavelengthSpikeRuntimeOnly)
}

dependencies {
    testImplementation(project(":paygate-example-app"))
    testImplementation(project(":paygate-core"))
    testImplementation(project(":paygate-api"))
    testImplementation(project(":paygate-spring-autoconfigure"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")

    securityTestImplementation(project(":paygate-example-app-spring-security"))
    securityTestImplementation(project(":paygate-spring-autoconfigure"))
    securityTestImplementation(project(":paygate-lightning-lnbits"))
    securityTestImplementation(project(":paygate-lightning-lnd"))
    securityTestImplementation("org.springframework.boot:spring-boot-starter-test")
    securityTestImplementation("org.springframework.boot:spring-boot-starter-web")
    securityTestImplementation("org.springframework.boot:spring-boot-starter-security")
    securityTestImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    securityTestImplementation("io.grpc:grpc-stub:$grpcVersion")
    securityTestImplementation("com.google.protobuf:protobuf-java:$protobufVersion")

    wavelengthSpikeImplementation("fr.acinq.lightning:lightning-kmp-core-jvm:1.13.0")
}

tasks.test {
    useJUnitPlatform {
        includeTags("integration")
    }
}

val securityTest = tasks.register<Test>("securityTest") {
    description = "Runs Spring Security integration tests"
    group = "verification"
    testClassesDirs = securityTestSourceSet.output.classesDirs
    classpath = securityTestSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}

val requireWavelengthSpikeOptIn = tasks.register("requireWavelengthSpikeOptIn") {
    description = "Requires explicit opt-in before the funded Wavelength spike can run"

    doLast {
        if (!providers.gradleProperty("wavelengthSpike").isPresent) {
            throw GradleException(
                "wavelengthSpike requires explicit -PwavelengthSpike opt-in; module inclusion alone cannot run live work."
            )
        }
    }
}

val wavelengthSpikeRunId = AtomicReference<String>()
val wavelengthSpikeRunDirectory = AtomicReference<File>()
val wavelengthSpikeManifestSha256 = AtomicReference<String>()
val wavelengthSpikeTestOutcomes =
    Collections.synchronizedList(mutableListOf<Pair<String, String>>())
val wavelengthSpikeManifest =
    rootProject.layout.projectDirectory.file("docs/wavelength-spike/compatibility.json")

val prepareWavelengthSpikeRun = tasks.register("prepareWavelengthSpikeRun") {
    description = "Allocates a fresh identity and evidence directory for the live Wavelength spike"
    dependsOn(requireWavelengthSpikeOptIn)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    notCompatibleWithConfigurationCache("A live payment run requires a new identity per invocation")

    doLast {
        val runId = UUID.randomUUID().toString()
        val runRoot = layout.buildDirectory.dir("wavelength-spike").get().asFile.toPath()
        Files.createDirectories(runRoot)
        val runDirectory = runRoot.resolve(runId)
        Files.createDirectory(runDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        val manifestSha256 =
            HexFormat.of().formatHex(digest.digest(Files.readAllBytes(wavelengthSpikeManifest.asFile.toPath())))

        wavelengthSpikeRunId.set(runId)
        wavelengthSpikeRunDirectory.set(runDirectory.toFile())
        wavelengthSpikeManifestSha256.set(manifestSha256)
        wavelengthSpikeTestOutcomes.clear()
    }
}

val wavelengthSpike = tasks.register<Test>("wavelengthSpike") {
    description = "Runs the explicitly opted-in live Wavelength signet capability spike"
    group = "verification"
    dependsOn(prepareWavelengthSpikeRun)
    testClassesDirs = wavelengthSpikeSourceSet.output.classesDirs
    classpath = wavelengthSpikeSourceSet.runtimeClasspath
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    notCompatibleWithConfigurationCache("A live payment run must execute and validate fresh evidence")
    filter {
        isFailOnNoMatchingTests = true
    }
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    addTestListener(
        object : TestListener {
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                val className = testDescriptor.className ?: return
                val methodName = testDescriptor.name.substringBefore('(')
                wavelengthSpikeTestOutcomes.add(
                    "$className#$methodName" to result.resultType.name
                )
            }

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent != null) {
                    return
                }
                val runDirectory = wavelengthSpikeRunDirectory.get()?.toPath()
                    ?: throw GradleException("Wavelength spike run context is unavailable")
                val snapshot = synchronized(wavelengthSpikeTestOutcomes) {
                    wavelengthSpikeTestOutcomes.toList()
                }
                val summary = buildString {
                    append("test.count=").append(snapshot.size).append('\n')
                    snapshot.forEachIndexed { index, (id, outcome) ->
                        append("test.").append(index).append(".id=").append(id).append('\n')
                        append("test.").append(index).append(".outcome=").append(outcome).append('\n')
                    }
                }
                Files.writeString(
                    runDirectory.resolve("execution.properties"),
                    summary,
                    StandardCharsets.UTF_8
                )
            }
        }
    )

    doFirst {
        val runId = wavelengthSpikeRunId.get()
            ?: throw GradleException("Wavelength spike run context is unavailable")
        val runDirectory = wavelengthSpikeRunDirectory.get()
            ?: throw GradleException("Wavelength spike run context is unavailable")
        val manifestSha256 = wavelengthSpikeManifestSha256.get()
            ?: throw GradleException("Wavelength spike run context is unavailable")
        systemProperty("wavelength.spike.run-id", runId)
        systemProperty("wavelength.spike.run-directory", runDirectory.absolutePath)
        systemProperty("wavelength.spike.manifest-path", wavelengthSpikeManifest.asFile.absolutePath)
        systemProperty("wavelength.spike.manifest-sha256", manifestSha256)
    }
}

val validateWavelengthSpikeAcceptance =
    tasks.register<JavaExec>("validateWavelengthSpikeAcceptance") {
        description = "Rejects incomplete, skipped, filtered, cached, or stale live spike evidence"
        classpath = wavelengthSpikeSourceSet.runtimeClasspath
        mainClass.set(
            "com.greenharborlabs.paygate.integration.wavelength.WavelengthSpikeRun"
        )
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        notCompatibleWithConfigurationCache("Acceptance is bound to the current live invocation")
        mustRunAfter(wavelengthSpike)

        doFirst {
            val runId = wavelengthSpikeRunId.get()
                ?: throw GradleException("Wavelength spike run context is unavailable")
            val runDirectory = wavelengthSpikeRunDirectory.get()
                ?: throw GradleException("Wavelength spike run context is unavailable")
            val manifestSha256 = wavelengthSpikeManifestSha256.get()
                ?: throw GradleException("Wavelength spike run context is unavailable")
            args = listOf(runDirectory.absolutePath, runId, manifestSha256)
        }
    }

wavelengthSpike.configure {
    finalizedBy(validateWavelengthSpikeAcceptance)
}

// T6 browser substeps must inherit the same fresh-execution boundary when they are added.
tasks.configureEach {
    if (name.startsWith("wavelengthSpikeBrowser")) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        notCompatibleWithConfigurationCache("Live browser work requires fresh execution")
    }
}

val wavelengthSpikeTest = tasks.register<Test>("wavelengthSpikeTest") {
    description = "Runs deterministic offline tests for the Wavelength spike"
    group = "verification"
    testClassesDirs = wavelengthSpikeTestSourceSet.output.classesDirs
    classpath = wavelengthSpikeTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

val wavelengthSpikeBrowserDirectory =
    layout.projectDirectory.dir("src/wavelengthSpike/browser").asFile

val wavelengthSpikeBrowserInstall = tasks.register<Exec>("wavelengthSpikeBrowserInstall") {
    description = "Installs the exact locked browser-spike dependencies"
    workingDir(wavelengthSpikeBrowserDirectory)
    commandLine("npm", "ci", "--ignore-scripts")
}

val wavelengthSpikeBrowserCheck = tasks.register<Exec>("wavelengthSpikeBrowserCheck") {
    description = "Runs deterministic browser-spike type, recovery, and bundle checks"
    group = "verification"
    dependsOn(wavelengthSpikeBrowserInstall)
    workingDir(wavelengthSpikeBrowserDirectory)
    commandLine("npm", "run", "check")
}

val wavelengthSpikeBrowserRuntime = tasks.register<Exec>("wavelengthSpikeBrowserRuntime") {
    description = "Downloads and hash-verifies the pinned local Wavelength browser runtime"
    dependsOn(wavelengthSpikeBrowserInstall)
    workingDir(wavelengthSpikeBrowserDirectory)
    commandLine("npm", "run", "runtime:fetch")
}

val wavelengthSpikeBrowserSmoke = tasks.register<Exec>("wavelengthSpikeBrowserSmoke") {
    description = "Loads the pinned local runtime in a temporary clean Chromium profile"
    group = "verification"
    dependsOn(wavelengthSpikeBrowserCheck)
    workingDir(wavelengthSpikeBrowserDirectory)
    commandLine("npm", "run", "test:browser")
}

// Wire securityTest into the check lifecycle when running with -Pintegration.
// The Wavelength tasks remain explicit-only and are deliberately not lifecycle dependencies.
tasks.named("check") {
    dependsOn(securityTest)
}

// Disable JaCoCo coverage verification — this module contains only integration tests
tasks.withType<JacocoCoverageVerification> {
    isEnabled = false
}
