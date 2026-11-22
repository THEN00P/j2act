pluginManagement {
  // The j2act Gradle plugin, from this repository until it is published.
  includeBuild("../../j2act-gradle-plugin")
  repositories {
    // j2act's own modules come from ./mvnw install, and only from there: a snapshot is otherwise looked up in every
    // repository, and one answering 500 fails the build.
    exclusiveContent {
      forRepository { mavenLocal() }
      filter { includeGroup("io.github.then00p.j2act") }
    }
    gradlePluginPortal()
  }
}

plugins {
  // Downloads the JDK 17 toolchain when this machine has none Gradle can find.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vite-gradle-spring"
