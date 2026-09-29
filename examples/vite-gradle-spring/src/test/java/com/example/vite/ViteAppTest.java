package com.example.vite;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The app boots under a test runner without starting a Vite watcher (ADR 0024). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ViteAppTest {

  @Test
  void startsWithoutAWatcher() {
    boolean watcher = ProcessHandle.current().descendants()
      .anyMatch(p -> p.info().commandLine().orElse("").contains("dev.js"));
    assertFalse(watcher, "a test run started the Vite watcher");
  }
}
