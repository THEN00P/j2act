package com.example.tspnpm;

import static j2act.Routes.*;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.example.tspnpm.pages.HomePage;
import j2act.PageResolver;

/** TypeScript client modules, bundled from pnpm packages with esbuild (ADR 0022). */
@SpringBootApplication
public class TsPnpmApp {

  public static void main(String[] args) {
    SpringApplication.run(TsPnpmApp.class, args);
  }

  @Bean
  public PageResolver router() {
    return routes(page("/", HomePage.class));
  }
}
