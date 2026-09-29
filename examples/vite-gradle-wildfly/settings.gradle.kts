pluginManagement {
  // The j2act Gradle plugin, from this repository until it is published.
  includeBuild("../../j2act-gradle-plugin")
  repositories {
    gradlePluginPortal()
    mavenLocal()
  }
}

plugins {
  // Downloads the JDK 11 toolchain when this machine has none Gradle can find.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vite-gradle-wildfly"
