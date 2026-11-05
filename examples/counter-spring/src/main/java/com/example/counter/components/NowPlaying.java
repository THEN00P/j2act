package com.example.counter.components;

import static j2act.Reactive.*;

import static j2act.Reactive.*;
import j2act.BrowserScoped;
import j2act.Signal;
import j2act.Store;

/**
 * What this browser is playing, shared live by all its tabs (ADR 0030): a track picked in one tab
 * shows in every other. The volume is retained, so it outlives the last tab.
 */
@BrowserScoped
public class NowPlaying extends Store {

  private final Signal<String> track = createSignal("");
  private final Signal<Integer> volume = createRetained(5);

  public String track() {
    return track.get();
  }

  public void play(String name) {
    track.set(name);
  }

  public int volume() {
    return volume.get();
  }

  public void setVolume(int level) {
    volume.set(Math.max(0, Math.min(10, level)));
  }
}
