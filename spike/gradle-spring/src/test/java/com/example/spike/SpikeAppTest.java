package com.example.spike;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Scenario 9: the app boots under a test runner without starting a Vite watcher. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpikeAppTest {

  @Test
  void startsWithoutAWatcher() {
    boolean watcher = ProcessHandle.current().descendants()
      .anyMatch(p -> p.info().commandLine().orElse("").contains("dev.js"));
    assertFalse(watcher, "a test run started the Vite watcher");
  }
}
