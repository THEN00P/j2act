plugins {
  war
  id("dev.j2act")
}

java {
  // WildFly 29 on Java 11. The toolchain, not options.release: Buildship takes Eclipse's
  // compiler level from it, and WildFly on 11 rejects classes built for a newer Java.
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

repositories {
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation("dev.j2act:j2act-jakarta:0.1.0-SNAPSHOT")
  implementation("dev.j2act:j2act-html:0.1.0-SNAPSHOT")
  implementation("dev.j2act:j2act-router:0.1.0-SNAPSHOT")
  compileOnly("jakarta.platform:jakarta.jakartaee-api:10.0.0")
}

tasks.war {
  archiveFileName = "vite-gradle-wildfly.war"
}
