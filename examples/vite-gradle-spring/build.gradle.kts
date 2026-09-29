plugins {
  java
  id("org.springframework.boot") version "3.5.16"
  id("io.spring.dependency-management") version "1.1.7"
  id("dev.j2act")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

repositories {
  mavenLocal()
  mavenCentral()
}

dependencies {
  implementation("dev.j2act:j2act-spring:0.1.0-SNAPSHOT")
  implementation("dev.j2act:j2act-html:0.1.0-SNAPSHOT")
  implementation("dev.j2act:j2act-router:0.1.0-SNAPSHOT")
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
