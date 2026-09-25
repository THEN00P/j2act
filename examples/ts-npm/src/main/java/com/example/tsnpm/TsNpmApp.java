package com.example.tsnpm;

import static j2act.Routes.*;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.example.tsnpm.pages.HomePage;
import j2act.PageResolver;

/** TypeScript client modules, bundled from npm packages with esbuild (ADR 0022). */
@SpringBootApplication
public class TsNpmApp {

  public static void main(String[] args) {
    SpringApplication.run(TsNpmApp.class, args);
  }

  @Bean
  public PageResolver router() {
    return routes(page("/", HomePage.class));
  }
}
