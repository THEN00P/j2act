plugins {
  java
  id("org.springframework.boot") version "3.5.16"
  id("io.spring.dependency-management") version "1.1.7"
  id("io.github.then00p.j2act")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
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
  implementation("io.github.then00p.j2act:j2act-spring:0.1.0-SNAPSHOT")
  implementation("io.github.then00p.j2act:j2act-html:0.1.0-SNAPSHOT")
  implementation("io.github.then00p.j2act:j2act-router:0.1.0-SNAPSHOT")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-websocket")
}

dependencies {
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}
