// buildSrc is its own Gradle build. Without this file it resolves the Kotlin
// compiler that the `kotlin-dsl` plugin needs from the Gradle Plugin Portal,
// which answers with a redirect to github.com/.../releases - and that endpoint
// regularly returns 504, killing the build before compilation starts.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        google()
        mavenCentral()
    }
}
