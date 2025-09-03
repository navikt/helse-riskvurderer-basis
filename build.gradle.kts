val junitJupiterVersion = "5.8.2"
val ktorVersion = "3.1.2"
val micrometerVersion = "1.3.20"
val kafkaVersion = "3.9.1"
val slf4jVersion = "1.7.36"
val logbackVersion = "1.3.15"
val logstashEncoderVersion = "7.4"
val serializerVersion = "1.8.1"
val nimbusJoseVersion = "10.3.1"
val kotlinVersion = "2.1.20"

plugins {
    val kotlinVersion = "2.1.20"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    id("maven-publish")
}

apply(plugin = "org.jetbrains.kotlin.jvm")

group = "no.nav.helse.risk"
version = properties["version"].let { if (it == null || it == "unspecified") "local-build" else it }

repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven/")
}

val nettyHandlerOverriddenVersion = "4.1.124.Final"

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")

    api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializerVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializerVersion")

    api("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    implementation("io.netty:netty-codec-http2:$nettyHandlerOverriddenVersion").also {
        if (ktorVersion != "3.1.2") throw RuntimeException("Slett nettyHandlerOverriddenVersion siden KTOR oppgradert?")
    }

    api("io.ktor:ktor-server-netty:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core") {
        isTransitive = true
    }

    implementation("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    api("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")
    api("io.prometheus:simpleclient") {
        isTransitive = true
    }

    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")

    api("org.slf4j:slf4j-api:$slf4jVersion")
    api("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("ch.qos.logback:logback-core:$logbackVersion")

    implementation("com.github.ben-manes.caffeine:caffeine:3.0.6")

    api("com.nimbusds:nimbus-jose-jwt:$nimbusJoseVersion")


    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion") {
        exclude(group = "junit")
    }

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("io.mockk:mockk:1.12.4")
    testImplementation("org.testcontainers:kafka:1.20.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    kotlin {
        jvmToolchain(21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    //classifier = "sources"
    from(sourceSets.main.get().allSource)
}

val githubUser: String? by project
val githubPassword: String? by project

publishing {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/navikt/helse-riskvurderer-basis")
            credentials {
                username = githubUser
                password = githubPassword
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {

            pom {
                name.set("helse-riskvurderer-basis")
                description.set("Helse Riskvurderer Basis")
                url.set("https://github.com/navikt/helse-riskvurderer-basis")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/navikt/helse-riskvurderer-basis.git")
                    developerConnection.set("scm:git:https://github.com/navikt/helse-riskvurderer-basis.git")
                    url.set("https://github.com/navikt/helse-riskvurderer-basis")
                }
            }
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }
}

