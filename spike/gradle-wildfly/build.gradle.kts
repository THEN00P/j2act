plugins {
  war
  id("dev.j2act")
}

java {
  // WildFly 29 on Java 11, built with any newer JDK.
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 11
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
  archiveFileName = "gradle-wildfly.war"
}
