plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// JDK 26 builds; bytecode targets 17 so Android (and the app module) can consume it.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { targetCompatibility = JavaVersion.VERSION_17; sourceCompatibility = JavaVersion.VERSION_17 }
tasks.withType<Test>().configureEach { maxHeapSize = "1g" }

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
