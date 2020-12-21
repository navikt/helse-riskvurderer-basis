import org.jetbrains.kotlin.gradle.tasks.*

val junitJupiterVersion = "5.6.3"
val ktorVersion = "1.4.3"
val micrometerVersion = "1.3.16"
val kafkaVersion = "2.4.0"
val slf4jVersion = "1.7.30"
val logbackVersion = "1.2.3"
val logstashEncoderVersion = "6.5"
val serializerVersion = "1.0.1"
val nimbusJoseVersion = "8.20.1"

val snykImplementationDependencyOverrides = arrayOf(
    "io.netty:netty-codec-http2:4.1.46.Final"
)

plugins {
    val kotlinVersion = "1.4.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    id("maven-publish")
}

apply(plugin = "org.jetbrains.kotlin.jvm")


group = "no.nav.helse.risk"
version = properties["version"].let { if (it == null || it == "unspecified") "local-build" else it }

repositories {
    jcenter()
    mavenCentral()
    maven("http://packages.confluent.io/maven/")
}

dependencies {
    api(kotlin("stdlib-jdk8"))

    snykImplementationDependencyOverrides.forEach { dependencyNotation ->
        implementation(dependencyNotation)
    }

    api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializerVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializerVersion")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    implementation("io.ktor:ktor-metrics-micrometer:$ktorVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")

    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
    implementation("com.github.ben-manes.caffeine:caffeine:2.8.1")

    api("com.nimbusds:nimbus-jose-jwt:$nimbusJoseVersion")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testImplementation("org.awaitility:awaitility:4.0.1")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion") {
        exclude(group = "junit")
    }

    testImplementation("no.nav:kafka-embedded-env:$kafkaVersion") {
        // Dont need schema-registry and it drags in a lot of vulnerable dependencies:
        exclude(group = "io.confluent", module = "kafka-schema-registry")
    }

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testImplementation("io.mockk:mockk:1.10.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_12
    targetCompatibility = JavaVersion.VERSION_12
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    kotlinOptions.jvmTarget = "1.8"
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

tasks.withType<Wrapper> {
    gradleVersion = "6.1.1"
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


