pluginManagement {
  // SPIKE: the j2act Gradle plugin comes from the sibling build until it is published.
  includeBuild("../gradle-plugin")
  repositories {
    gradlePluginPortal()
    mavenLocal()
  }
}

plugins {
  // Downloads the JDK 11 toolchain when this machine has none Gradle can find.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gradle-wildfly"
