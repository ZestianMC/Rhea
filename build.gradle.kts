plugins {
    `java-library`
    `maven-publish`
}

group = "de.erethon.rhea"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

val zlibJar = providers.gradleProperty("zlibJar")
require(zlibJar.isPresent) { "Pass -PzlibJar=/path/to/zlib.jar built with zLib PR #5 (knownProtocolOf); this API is not in the published 1.9.0 artifact." }

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly(files(zlibJar))
    compileOnly("com.github.retrooper:packetevents-api:2.11.1")
    testImplementation("io.papermc.paper:paper-api:26.3.build.205-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
configurations.testImplementation { extendsFrom(configurations.compileOnly.get()) }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

tasks.test { useJUnitPlatform() }

tasks.jar {
    from("LICENSE") { into("META-INF") }
}

publishing {
    repositories {
        maven {
            name = "erethon"
            url = uri("https://repo.erethon.de/snapshots/")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Rhea")
                description.set("HTML-authored dialogs for Paper")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
