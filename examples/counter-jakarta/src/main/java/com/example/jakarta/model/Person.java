package com.example.jakarta.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class Person {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  protected Person() {
  }

  public Person(String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
