package com.example.team.stores;

import j2act.State;
import j2act.Store;
import j2act.TabScoped;

/** Who is signed in, one per tab (ADR 0030); components @Inject it. */
@TabScoped
public class CurrentUser extends Store {

  private final State<Long> userId = state(-1L);
  private final State<String> username = state("guest");

  public long userId() {
    return userId.get();
  }

  public String username() {
    return username.get();
  }

  public void signIn(long id, String name) {
    userId.set(id);
    username.set(name);
  }
}
