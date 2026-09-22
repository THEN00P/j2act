package com.example.counter.services;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/** Stands in for a slow repository. Counts calls so the page can show when the cache saved one. */
@Service
public class Directory {

  private static final List<String> PEOPLE = Arrays.asList(
    "Ada Lovelace", "Alan Turing", "Barbara Liskov", "Donald Knuth", "Edsger Dijkstra",
    "Frances Allen", "Grace Hopper", "John Backus", "Ken Thompson", "Margaret Hamilton");

  private final AtomicInteger calls = new AtomicInteger();

  public List<String> search(String filter) throws InterruptedException {
    calls.incrementAndGet();
    Thread.sleep(300);
    String needle = filter.toLowerCase(Locale.ROOT);
    return PEOPLE.stream()
      .filter(p -> p.toLowerCase(Locale.ROOT).contains(needle))
      .collect(Collectors.toList());
  }

  public String profile(String name) throws InterruptedException {
    calls.incrementAndGet();
    Thread.sleep(300);
    return name + " — " + name.length() + " characters of legend";
  }

  public int calls() {
    return calls.get();
  }
}
