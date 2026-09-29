plugins {
  `java-gradle-plugin`
  // publishToMavenLocal, for builds outside this repository before the plugin is on the Plugin Portal.
  `maven-publish`
}

group = "dev.j2act"
version = "0.1.0-SNAPSHOT"

repositories {
  gradlePluginPortal()
  // exploded-hotswap (github.com/THEN00P/exploded-hotswap) and j2act-processor come from
  // publishToMavenLocal and ./mvnw install until they are published.
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
  implementation("io.github.then00p:exploded-hotswap-gradle-plugin:0.1.0-SNAPSHOT")
  testImplementation(platform("org.junit:junit-bom:5.10.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

tasks.withType<JavaCompile>().configureEach {
  // Gradle 8 still runs on Java 11, and so do many builds of WildFly apps.
  options.release = 11
}

gradlePlugin {
  plugins {
    create("j2act") {
      id = "dev.j2act"
      implementationClass = "dev.j2act.gradle.J2ActPlugin"
      displayName = "j2act"
      description = "j2act's annotation processor, client modules, Vite and Tailwind through Node, " +
        "and the Eclipse and VS Code setup, as defaults a build script can override."
    }
  }
}

tasks.test {
  useJUnitPlatform()
  // The functional tests run Gradle 8 and 9 through TestKit.
  systemProperty("gradle.versions", "8.14.5,9.8.0")
}
