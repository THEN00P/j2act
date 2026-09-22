package com.example.team.stores;

import static j2act.State.createStore;

import j2act.Store;

/** Handles created once, zustand-style. Each session holds an isolated copy. */
public final class AppStores {

  private AppStores() {}

  public static final Store<CurrentUser> currentUser = createStore(new CurrentUser(-1, "guest"));

  public static final class CurrentUser {
    public final long userId;
    public final String username;

    public CurrentUser(long userId, String username) {
      this.userId = userId;
      this.username = username;
    }
  }
}
