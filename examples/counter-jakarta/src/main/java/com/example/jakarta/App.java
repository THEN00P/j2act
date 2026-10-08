package com.example.jakarta;

import static j2act.Routes.*;

import java.time.Duration;

import javax.sql.DataSource;

import jakarta.annotation.Resource;
import jakarta.servlet.annotation.WebListener;

import com.example.jakarta.pages.HomePage;
import j2act.J2Act;
import j2act.PageResolver;
import j2act.jakarta.J2ActListener;
import j2act.jdbc.JdbcRetainedStateStorage;

/** The whole mount: routes on a @WebListener. CDI, the managed executor and sockets are wired by the adapter. */
@WebListener
public class App extends J2ActListener {

  /** The container's datasource, so credentials stay in the server's configuration (ADR 0026). */
  @Resource(lookup = "java:comp/DefaultDataSource")
  private DataSource dataSource;

  @Override protected PageResolver router() {
    return routes(
      page("/", HomePage.class)
    );
  }

  @Override protected void customize(J2Act.Builder builder) {
    builder.withRetainedStateStorage(JdbcRetainedStateStorage.of(dataSource));
    // The Retained State browser check evicts idle sessions quickly and pauses hidden tabs:
    // -Ddemo.idle-timeout=PT3S -Ddemo.auto-pause=PT1S.
    String idleTimeout = System.getProperty("demo.idle-timeout");
    if (idleTimeout != null) {
      builder.withIdleTimeout(Duration.parse(idleTimeout)).withSweepInterval(Duration.ofSeconds(1));
    }
    String autoPause = System.getProperty("demo.auto-pause");
    if (autoPause != null) {
      builder.withAutoPause(Duration.parse(autoPause));
    }
  }
}
