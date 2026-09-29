package com.example.vite;

import static j2act.Routes.*;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.example.vite.pages.HomePage;
import j2act.PageResolver;

@SpringBootApplication
public class ViteApp {

  public static void main(String[] args) {
    SpringApplication.run(ViteApp.class, args);
  }

  @Bean
  public PageResolver router() {
    return routes(page("/", HomePage.class));
  }
}
