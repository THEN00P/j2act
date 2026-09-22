package com.example.team;

import static j2act.Routes.*;

import com.example.team.auth.AppIdentity;
import com.example.team.auth.Auth;
import com.example.team.pages.AdminLayout;
import com.example.team.pages.HomePage;
import com.example.team.pages.SettingsPage;
import com.example.team.pages.UsersPage;
import j2act.Router;

/** Single place where paths, guards, mounts and assets are registered. */
public class App {

  public static Router router() {
    return routes(
      page("/", HomePage.class),
      scope("/admin",
        middleware(Auth.class),
        layout(AdminLayout.class,
          page("/", UsersPage.class),
          page("/settings", SettingsPage.class))),
      scope("/projects",
        page("/", ProjectsPage.class),
        page("/{id}", ProjectPage.class),
        page("/{id}/settings", ProjectSettingsPage.class)),
      redirect("/old-users", "/admin"),
      fallback(NotFoundPage.class)
    );
  }

  // Spring Boot standalone (Boot 2.7, javax.servlet, Java 11):
  //   J2ActSpring.mount(springApp, router()).withIdentity(AppIdentity::resolve)
  //
  // Jakarta/WildFly WAR (jakarta.servlet): same Router via j2act-jakarta ServletContextListener.
  // web.xml note: WildFly max-post-size caps plain POSTs; chunked upload endpoint streams,
  // so no container tuning is needed for large files.
  //
  // Island inside an existing app:
  //   mount(router(), island().withIdentity(AppIdentity::resolve))
  //
  // No-auth internal tool: omit withIdentity entirely — AuthCtx.anonymous() flows through.
  //
  // Assets, traditional mode (no node): pages emit link()/script() tags directly.
  // Managed mode: swap to bundleCss/bundleJs("/assets/manifest.json") for rslib output.
  public static void main(String[] args) {
    throw new UnsupportedOperationException("inspection example — not runnable until core exists");
  }
}
