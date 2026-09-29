pluginManagement {
  // The j2act Gradle plugin, from this repository until it is published.
  includeBuild("../../j2act-gradle-plugin")
  repositories {
    gradlePluginPortal()
    mavenLocal()
  }
}

rootProject.name = "vite-gradle-spring"
