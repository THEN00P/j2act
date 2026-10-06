package com.example.counter;

import static j2act.Routes.*;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.counter.pages.AboutPage;
import com.example.counter.pages.AppLayout;
import com.example.counter.pages.HomePage;
import com.example.counter.pages.ItemPage;
import com.example.counter.pages.NotFoundPage;
import com.example.counter.pages.ReportPage;
import j2act.PageResolver;
import j2act.spring.J2ActCustomizer;

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
      layout(AppLayout.class,
        page("/", HomePage.class),
        page("/about", AboutPage.class),
        page("/items/{id}", ItemPage.class),
        page("/report", ReportPage.class),
        fallback(NotFoundPage.class))
    );
  }

  /** tools/retained-check.mjs starts the demo with --demo.idle-timeout=PT3S to see an evicted session restore. */
  @Bean
  public J2ActCustomizer demoTimeouts(@Value("${demo.idle-timeout:PT12H}") Duration idleTimeout) {
    return builder -> builder.withIdleTimeout(idleTimeout).withSweepInterval(Duration.ofSeconds(1));
  }
}
