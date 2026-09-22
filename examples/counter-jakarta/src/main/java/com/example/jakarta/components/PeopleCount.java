package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import j2act.ComponentTag;
import j2act.Query;
import j2act.html.tags.PTag;

/**
 * A component with its own @PersistenceContext, no repository in between: CDI's
 * injection target on WildFly performs EE resource injection into the component
 * (ADR 0004). Shows the seam works, not a recommended layering.
 */
public final class PeopleCount extends ComponentTag {

  @PersistenceContext
  private EntityManager em;

  private final Query<Long> count = query(() -> em
    .createQuery("select count(p) from Person p", Long.class)
    .getSingleResult());

  public static PeopleCount peopleCount() {
    return new PeopleCount();
  }

  @Override protected PTag render() {
    return p(count.isPending() ? "counting…" : "people in ExampleDS: " + count.get());
  }
}
