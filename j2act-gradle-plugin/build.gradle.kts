plugins {
  `java-gradle-plugin`
  // The Plugin Portal, for plugins {} blocks; Maven Central as well, with the rest of j2act (ADR 0029).
  id("com.gradle.plugin-publish") version "2.2.1"
  id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.then00p.j2act"
description = "The j2act Gradle plugin: annotation processor, client modules, Vite and Tailwind through Node, " +
  "and the Eclipse and VS Code setup, as defaults a build script can override."
// The release workflow passes the tag's version; every other build is a snapshot.
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
  gradlePluginPortal()
  // j2act-processor comes from ./mvnw install until j2act is published; exploded-hotswap from Maven Central.
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
  implementation("io.github.then00p:exploded-hotswap-gradle-plugin:0.1.0")
  testImplementation(platform("org.junit:junit-bom:5.10.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  // Compiles and runs the TestKit tests on 21, which runs Gradle 9 and still runs Gradle 8 (8.14 stops
  // at Java 24); foojay downloads it when missing. Gradle itself may run on 21 or 25.
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

tasks.withType<JavaCompile>().configureEach {
  // Gradle 8 still runs on Java 11, and so do many builds of WildFly apps.
  options.release = 11
}

// The plugin adds j2act-processor at its own version, which it reads from this resource.
val versionResource = tasks.register("versionResource") {
  val out = layout.buildDirectory.dir("generated/version")
  val v = version.toString()
  inputs.property("version", v)
  outputs.dir(out)
  doLast {
    val file = out.get().file("dev/j2act/gradle/version.txt").asFile
    file.parentFile.mkdirs()
    file.writeText(v)
  }
}
sourceSets.main {
  resources.srcDir(versionResource)
}

gradlePlugin {
  website = "https://github.com/THEN00P/j2act"
  vcsUrl = "https://github.com/THEN00P/j2act.git"
  plugins {
    create("j2act") {
      id = "io.github.then00p.j2act"
      implementationClass = "dev.j2act.gradle.J2ActPlugin"
      displayName = "j2act"
      description = "j2act's annotation processor, client modules, Vite and Tailwind through Node, " +
        "and the Eclipse and VS Code setup, as defaults a build script can override."
      tags = listOf("j2act", "web", "server-side-rendering", "vite", "typescript", "tailwind")
    }
  }
}

mavenPublishing {
  publishToMavenCentral()
  // A release signs in-process with the armored key in signingInMemoryKey and its passphrase in
  // signingInMemoryKeyPassword (ORG_GRADLE_PROJECT_ variables in CI). Snapshots stay unsigned.
  if (!version.toString().endsWith("SNAPSHOT")) {
    check(providers.gradleProperty("signingInMemoryKey").isPresent) {
      "a release needs signingInMemoryKey and signingInMemoryKeyPassword"
    }
    signAllPublications()
  }
  pom {
    name = "j2act-gradle-plugin"
    description = project.description
    url = "https://github.com/THEN00P/j2act"
    inceptionYear = "2026"
    licenses {
      license {
        name = "The Apache License, Version 2.0"
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        distribution = "repo"
      }
    }
    developers {
      developer {
        id = "THEN00P"
        name = "THEN00P"
        url = "https://github.com/THEN00P"
      }
    }
    scm {
      url = "https://github.com/THEN00P/j2act"
      connection = "scm:git:https://github.com/THEN00P/j2act.git"
      developerConnection = "scm:git:ssh://git@github.com/THEN00P/j2act.git"
    }
  }
}

tasks.test {
  useJUnitPlatform()
  // The functional tests run Gradle 8 and 9 through TestKit.
  systemProperty("gradle.versions", "8.14.5,9.8.1")
}
