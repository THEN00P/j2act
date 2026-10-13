pluginManagement {
  repositories {
    gradlePluginPortal()
    // com.vanniktech.maven.publish is published to Maven Central.
    mavenCentral()
  }
}

plugins {
  // Downloads the JDK 21 toolchain when this machine has none Gradle can find.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "j2act-gradle-plugin"
