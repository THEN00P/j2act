package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import j2act.ComponentTag;
import j2act.Download;
import j2act.Query;
import j2act.html.tags.DivTag;

/**
 * A component with its own @PersistenceContext, no repository in between: CDI's
 * injection target on WildFly performs EE resource injection into the component
 * (ADR 0004). Shows the seam works, not a recommended layering. The export streams from
 * JPA on the servlet thread that serves the download (ADR 0012).
 */
public final class PeopleCount extends ComponentTag {

  @PersistenceContext
  private EntityManager em;

  private final Query<Long> count = query(() -> em
    .createQuery("select count(p) from Person p", Long.class)
    .getSingleResult());

  private final Download<Void> export = download((Void none, OutputStream out) -> {
    out.write("name\n".getBytes(StandardCharsets.UTF_8));
    for (String name : em.createQuery("select p.name from Person p order by p.name", String.class).getResultList()) {
      out.write((name + "\n").getBytes(StandardCharsets.UTF_8));
    }
  })
    .withFileName("people.csv")
    .withContentType("text/csv;charset=UTF-8");

  public static PeopleCount peopleCount() {
    return new PeopleCount();
  }

  @Override protected DivTag render() {
    return div(
      p(count.isPending() ? "counting…" : "people in ExampleDS: " + count.get()),
      button("Export CSV").withId("people-export").onClick(e -> export.mutate(null)),
      small("export: " + export.status()).withId("export-status")
    );
  }
}
