plugins {
    id("org.springframework.boot") version "4.0.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("jacoco-report-aggregation")
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.cyclonedx.bom") version "3.2.2" apply false
    id("com.diffplug.spotless") version "7.0.4" apply false
}

val springBootVersion = "4.0.5"
val caffeineVersion = "3.1.8"
val grpcVersion = "1.80.0"
val protobufVersion = "4.29.3"
val jacksonVersion = "2.18.2"
val assertjVersion = "3.27.3"
val mockWebServerVersion = "4.12.0"

allprojects {
    group = property("group") as String
    version = property("version") as String

    repositories {
        mavenCentral()
    }
}

dependencyAnalysis {
    issues {
        all {
            onUnusedDependencies {
                severity("fail")
                exclude(
                    // JUnit platform launcher is a runtime-only transitive required by the test engine
                    "org.junit.platform:junit-platform-launcher",
                    // JUnit Jupiter aggregator pulls in junit-jupiter-api + params; the aggregator itself is "unused"
                    "org.junit.jupiter:junit-jupiter",
                    // Spring Boot starter aggregators are used by convention — they aggregate transitives intentionally
                    "org.springframework.boot:spring-boot-starter-test",
                    "org.springframework.boot:spring-boot-starter-web",
                    "org.springframework.boot:spring-boot-starter-webmvc-test",
                    "org.springframework.boot:spring-boot-starter-actuator",
                    "org.springframework.boot:spring-boot-starter-security",
                    "org.springframework.security:spring-security-test",
                )
            }
            onUsedTransitiveDependencies {
                severity("warn")
            }
            onIncorrectConfiguration {
                severity("warn")
            }
        }

        // Example apps intentionally depend on starters which aggregate transitive deps
        project(":paygate-example-app") {
            onUnusedDependencies { severity("warn") }
        }
        project(":paygate-example-app-spring-security") {
            onUnusedDependencies { severity("warn") }
        }
        // Starter aggregator module — dependencies are intentionally re-exported for consumers
        project(":paygate-spring-boot-starter") {
            onUnusedDependencies { severity("warn") }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(providers.gradleProperty("sonatypeUsername")
                .orElse(providers.environmentVariable("SONATYPE_USERNAME"))
                .orNull)
            password.set(providers.gradleProperty("sonatypePassword")
                .orElse(providers.environmentVariable("SONATYPE_PASSWORD"))
                .orNull)
        }
    }
}

val exampleModules = setOf("paygate-example-app", "paygate-example-app-spring-security")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")
    apply(plugin = "pmd")
    apply(plugin = "com.diffplug.spotless")

    configure<PmdExtension> {
        toolVersion = "7.14.0"
        ruleSets = emptyList()
        ruleSetFiles = files(rootProject.file("config/pmd/cyclomatic-complexity.xml"))
        isConsoleOutput = true
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.35.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            targetExclude("build/**")
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        }
        dependencies {
            dependency("net.bytebuddy:byte-buddy:1.18.7")
            dependency("net.bytebuddy:byte-buddy-agent:1.18.7")
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:none", "-quiet")
            charSet = "UTF-8"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<Pmd> {
        if (name == "pmdTest") {
            enabled = false
        }
        classpath = files()
        exclude("**/generated/**", "**/lnrpc/**")
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    val coverageMinimum = when (project.name) {
        "paygate-core" -> "0.80"
        "paygate-example-app", "paygate-example-app-spring-security" -> "0.0"
        "paygate-integration-tests" -> "0.0"
        else -> "0.60"
    }
    tasks.withType<JacocoCoverageVerification> {
        violationRules {
            rule {
                limit {
                    minimum = coverageMinimum.toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.withType<JacocoCoverageVerification>())
    }

    // Wire subproject into aggregated JaCoCo report
    rootProject.dependencies {
        "jacocoAggregation"(project(path))
    }

    // Make dependency version constants available to subproject build files
    extra["caffeineVersion"] = caffeineVersion
    extra["grpcVersion"] = grpcVersion
    extra["protobufVersion"] = protobufVersion
    extra["jacksonVersion"] = jacksonVersion
    extra["assertjVersion"] = assertjVersion
    extra["mockWebServerVersion"] = mockWebServerVersion

    // CycloneDX SBOM generation (skip example app and starter aggregator)
    if (project.name !in exampleModules && project.name != "paygate-spring-boot-starter" && project.name != "paygate-integration-tests") {
        apply(plugin = "org.cyclonedx.bom")
    }

    // Publishing configuration (skip example app)
    if (project.name !in exampleModules && project.name != "paygate-integration-tests") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        val javaExt = the<JavaPluginExtension>()
        javaExt.withSourcesJar()
        javaExt.withJavadocJar()

        afterEvaluate {
            configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])

                        pom {
                            name.set(project.name)
                            description.set("Paygate payment gateway for Spring Boot - ${project.name}")
                            url.set("https://github.com/greenharborlabs/spring-boot-starter-l402")
                            licenses {
                                license {
                                    name.set("MIT License")
                                    url.set("https://opensource.org/licenses/MIT")
                                }
                            }
                            developers {
                                developer {
                                    id.set("greenharborlabs")
                                    name.set("Green Harbor Labs")
                                    url.set("https://github.com/greenharborlabs")
                                }
                            }
                            scm {
                                connection.set("scm:git:git://github.com/greenharborlabs/spring-boot-starter-l402.git")
                                developerConnection.set("scm:git:ssh://github.com/greenharborlabs/spring-boot-starter-l402.git")
                                url.set("https://github.com/greenharborlabs/spring-boot-starter-l402")
                            }
                        }
                    }
                }
            }

            configure<SigningExtension> {
                val signingKey = findProperty("signingKey") as String? ?: System.getenv("GPG_SIGNING_KEY")
                val signingPassword = findProperty("signingPassword") as String? ?: System.getenv("GPG_SIGNING_PASSWORD")
                isRequired = !version.toString().endsWith("SNAPSHOT")
                if (signingKey != null && signingPassword != null) {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                    sign(the<PublishingExtension>().publications["mavenJava"])
                } else if (isRequired) {
                    throw GradleException("GPG signing keys are required for release builds but not configured")
                }
            }
        }
    }
}

// Install git pre-commit hook that runs spotlessApply before each commit
tasks.register("installGitHook") {
    group = "verification"
    description = "Installs a git pre-commit hook that auto-formats code with Spotless."
    val hookFile = file(".git/hooks/pre-commit")
    outputs.file(hookFile)
    doLast {
        hookFile.writeText(
            """
            |#!/bin/sh
            |# Auto-installed by Gradle — runs spotlessApply before each commit
            |echo "Running spotlessApply..."
            |./gradlew spotlessApply --daemon -q
            |
            |# Re-stage any files that were reformatted
            |git diff --name-only | xargs -r git add
            |
            """.trimMargin()
        )
        hookFile.setExecutable(true)
        logger.lifecycle("Installed git pre-commit hook: ${hookFile.absolutePath}")
    }
}

// Auto-install the hook when any subproject is compiled
subprojects {
    tasks.matching { it.name == "compileJava" }.configureEach {
        dependsOn(rootProject.tasks.named("installGitHook"))
    }
}

// Aggregate Javadoc task: collects sources from all subprojects with Java sources
tasks.register<Javadoc>("aggregateJavadoc") {
    group = "documentation"
    description = "Generates aggregate Javadoc for all modules."

    val javadocSubprojects = subprojects.filter { sub ->
        sub.plugins.hasPlugin("java-library") && sub.name != "paygate-spring-boot-starter" && sub.name !in exampleModules
    }

    dependsOn(javadocSubprojects.map { it.tasks.named("classes") })

    source(javadocSubprojects.map { it.the<SourceSetContainer>()["main"].allJava })
    classpath = files(javadocSubprojects.map { it.the<SourceSetContainer>()["main"].compileClasspath })

    setDestinationDir(layout.buildDirectory.dir("docs/javadoc").get().asFile)

    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        charSet = "UTF-8"
    }
}
