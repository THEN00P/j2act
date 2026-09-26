plugins {
  `java-gradle-plugin`
}

group = "dev.j2act"
version = "0.1.0-SNAPSHOT"

repositories {
  gradlePluginPortal()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

gradlePlugin {
  plugins {
    create("j2act") {
      id = "dev.j2act"
      implementationClass = "dev.j2act.gradle.J2ActPlugin"
    }
  }
}
