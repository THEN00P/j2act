package com.example.counter;

import static j2act.Routes.*;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.counter.pages.HomePage;
import j2act.PageResolver;

/** Declaring the route bean is all the mount needs; j2act-spring auto-configures the rest. */
@SpringBootApplication
@EnableScheduling
public class CounterApp {

  public static void main(String[] args) {
    SpringApplication.run(CounterApp.class, args);
  }

  @Bean
  public PageResolver router() {
    return routes(
      page("/", HomePage.class)
    );
  }
}
