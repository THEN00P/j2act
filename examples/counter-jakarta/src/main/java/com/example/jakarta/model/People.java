package com.example.jakarta.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/** Host-owned repository on WildFly's ExampleDS. j2act never sees an EntityManager (ADR 0004). */
@ApplicationScoped
public class People {

  @PersistenceContext
  private EntityManager em;

  private final AtomicInteger searches = new AtomicInteger();

  @Transactional
  void seed(@Observes Startup startup) {
    for (String name : Arrays.asList("Ada Lovelace", "Alan Turing", "Barbara Liskov", "Donald Knuth",
      "Edsger Dijkstra", "Frances Allen", "Grace Hopper", "Ken Thompson", "Margaret Hamilton")) {
      em.persist(new Person(name));
    }
  }

  /** Slow on purpose, so the page shows fetching and fast typing supersedes runs. */
  public List<String> search(String filter) throws InterruptedException {
    searches.incrementAndGet();
    Thread.sleep(300);
    return em.createQuery("select p.name from Person p where lower(p.name) like :f order by p.name", String.class)
      .setParameter("f", "%" + filter.toLowerCase(Locale.ROOT) + "%")
      .getResultList();
  }

  public int searches() {
    return searches.get();
  }
}
