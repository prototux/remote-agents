pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "remoteagents"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// :api is pure Kotlin/JVM (HTTP + SSE client, message model, transcript reducer); :app is the Android UI.
include(":api", ":app")
