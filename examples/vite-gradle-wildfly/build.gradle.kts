plugins {
  war
  id("io.github.then00p.j2act")
}

java {
  // WildFly 29 on Java 11. The toolchain, not options.release: Buildship takes Eclipse's
  // compiler level from it, and WildFly on 11 rejects classes built for a newer Java.
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

repositories {
  // As in settings.gradle.kts: j2act's snapshots only from ./mvnw install.
  exclusiveContent {
    forRepository { mavenLocal() }
    filter { includeGroup("io.github.then00p.j2act") }
  }
  mavenCentral()
}

dependencies {
  implementation("io.github.then00p.j2act:j2act-jakarta:0.1.0-SNAPSHOT")
  implementation("io.github.then00p.j2act:j2act-html:0.1.0-SNAPSHOT")
  implementation("io.github.then00p.j2act:j2act-router:0.1.0-SNAPSHOT")
  compileOnly("jakarta.platform:jakarta.jakartaee-api:10.0.0")
}

tasks.war {
  archiveFileName = "vite-gradle-wildfly.war"
}
