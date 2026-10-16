package com.example.counter.components;

import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;

import j2act.ComponentTag;
import j2act.html.tags.DivTag;

/** Reads and writes the browser's NowPlaying store; every tab of the browser shows the same track. */
public final class PlayerBar extends ComponentTag {

  @Inject private NowPlaying nowPlaying;

  public static PlayerBar playerBar() {
    return new PlayerBar();
  }

  @Override protected DivTag render() {
    return div(
      label(
        text("Track "),
        input()
          .withId("player-track")
          .withAutocomplete("off")
          .withValue(nowPlaying.track())
          .onChange(e -> nowPlaying.play(e.value()))
      ),
      p("Playing in this browser: " + (nowPlaying.track().isEmpty() ? "nothing" : nowPlaying.track())).withId("player-playing"),
      p("Volume " + nowPlaying.volume()).withId("player-volume"),
      button("Louder").withId("player-louder").onClick(e -> nowPlaying.setVolume(nowPlaying.volume() + 1))
    );
  }
}
