pluginManagement {
  // SPIKE: the j2act Gradle plugin comes from the sibling build until it is published.
  includeBuild("../gradle-plugin")
  repositories {
    gradlePluginPortal()
    mavenLocal()
  }
}

rootProject.name = "gradle-wildfly"
