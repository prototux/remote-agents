plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "net.prototux.remoteagents"
version = (findProperty("appVersion") as String?) ?: "0.0.0-dev"

// JDK 26 builds; bytecode targets 17 so Android (and the app module) can consume it.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { targetCompatibility = JavaVersion.VERSION_17; sourceCompatibility = JavaVersion.VERSION_17 }
tasks.withType<Test>().configureEach { maxHeapSize = "1g" }
java { withSourcesJar() }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

// Opt-in read-only test against a real server: ET_TOKEN=... ./gradlew :api:test --tests '*LiveServerTest*'
// (ET_SESSION=<id of a session with subagents> adds the agentd S11 check.)
tasks.withType<Test>().configureEach { listOf("ET_TOKEN", "ET_HOST", "ET_PORT", "ET_SESSION").forEach { k -> System.getenv(k)?.let { environment(k, it) } } }

// The pure-Kotlin even-terminal/agentd client, published to GitHub Packages by the release workflow.
publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "remote-agents-api"
            from(components["java"])
            pom {
                name.set("Remote Agents API client")
                description.set("Kotlin/JVM client for the even-terminal and agentd HTTP + SSE API")
                url.set("https://github.com/prototux/remote-agents")
                licenses { license { name.set("GPL-3.0-or-later"); url.set("https://www.gnu.org/licenses/gpl-3.0.html") } }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/prototux/remote-agents")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
