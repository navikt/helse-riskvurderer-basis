val junitJupiterVersion = "6.1.3"
val prometheusSimpleclientVersion = "0.16.0"
val kafkaVersion = "4.4.0"
val slf4jVersion = "2.0.20"
val logbackVersion = "1.6.5"
val logstashEncoderVersion = "9.0"
val serializerVersion = "1.12.0"
val coroutinesVersion = "1.11.0"
val nimbusJoseVersion = "10.10"
val kotlinVersion = "2.4.21"

val jacksonVersionOverride = "2.22.4"

plugins {
    val kotlinVersion = "2.3.0"
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

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")

    api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializerVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializerVersion")

    api(platform("com.fasterxml.jackson:jackson-bom:$jacksonVersionOverride")) // Fordi gammel jackson dras inn av logstash-logback-encoder
    api("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion") {
        isTransitive = true
    }

    api("io.prometheus:simpleclient:$prometheusSimpleclientVersion")
    api("io.prometheus:simpleclient_common:$prometheusSimpleclientVersion")

    api("org.apache.kafka:kafka-clients:$kafkaVersion")

    api("org.slf4j:slf4j-api:$slf4jVersion")
    api("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("ch.qos.logback:logback-core:$logbackVersion")

    implementation("com.github.ben-manes.caffeine:caffeine:3.3.1")

    api("com.nimbusds:nimbus-jose-jwt:$nimbusJoseVersion")

    constraints {
        if (kafkaVersion != "4.1.2") {
            throw RuntimeException("Fjern lz4-constraint, siden kafka-clients oppgradert?")
        } else {
            implementation("at.yawk.lz4:lz4-java:1.12.1")
        }
    }


    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("io.ktor:ktor-client-cio:3.6.0")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")


    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:kafka:1.21.4")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
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

