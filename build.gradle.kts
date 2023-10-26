import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val junitJupiterVersion = "5.8.2"
val ktorVersion = "2.1.3"
val micrometerVersion = "1.3.20"
val kafkaVersion = "2.8.2"
val slf4jVersion = "1.7.36"
val logbackVersion = "1.3.8"
val logstashEncoderVersion = "7.4"
val serializerVersion = "1.3.3"
val nimbusJoseVersion = "9.15.2"

plugins {
    val kotlinVersion = "1.7.10"
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

val xerialSnappyOverriddenVersion = "1.1.10.4" // CVE-2023-34455++ TODO: Fjern når kafka-clients oppgraderes fra 2.8.2 (som drar inn 1.1.8.1)
val nettyHandlerOverriddenVersion = "4.1.94.Final" // CVE-2023-34462 TODO: Fjern når ktor oppgraderes fra 2.1.3 ?

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.10")

    api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializerVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializerVersion")

    api("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    implementation("io.netty:netty-handler:$nettyHandlerOverriddenVersion").also {
        if (ktorVersion != "2.1.3") throw RuntimeException("Slett nettyHandlerOverriddenVersion siden KTOR oppgradert?")
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
    implementation("org.xerial.snappy:snappy-java:$xerialSnappyOverriddenVersion").also {
        if (kafkaVersion != "2.8.2") throw RuntimeException("Slett xerialSnappyOverridden siden kafka oppgradert?")
    }

    api("org.slf4j:slf4j-api:$slf4jVersion")
    api("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("ch.qos.logback:logback-core:$logbackVersion")

    implementation("com.github.ben-manes.caffeine:caffeine:3.0.6")

    api("com.nimbusds:nimbus-jose-jwt:$nimbusJoseVersion")


    testImplementation("org.jetbrains.kotlin:kotlin-test:1.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion") {
        exclude(group = "junit")
    }
    testImplementation("no.nav:kafka-embedded-env:2.8.2") {
        // Dont need schema-registry and it drags in a lot of vulnerable dependencies:
        exclude(group = "io.confluent", module = "kafka-schema-registry")
    }
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("io.mockk:mockk:1.12.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    kotlinOptions.jvmTarget = "17"
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

