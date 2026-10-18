package com.example.counter.components;

import j2act.State;
import j2act.Store;
import j2act.TabScoped;

/**
 * Who signs the draft, one per tab (ADR 0030): any component of the tab that injects it sees the
 * same signer. Retained, so it comes back with the draft after a pause or a restart (ADR 0026).
 */
@TabScoped
public class Signature extends Store {

  private final State<String> signer = retainedState("");

  public String signer() {
    return signer.get();
  }

  public void sign(String name) {
    signer.set(name);
  }
}
