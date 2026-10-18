package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;

import j2act.Client;
import j2act.ComponentTag;
import j2act.Mount;
import j2act.State;
import j2act.html.tags.DivTag;

/**
 * Retained State (ADR 0026): the draft outlives the in-memory tab for this page load, such as a
 * laptop lid closed past the grace window, a pause or a restart, while a reload still clears it.
 * So does the signer, kept in the Signature store that any component of the tab can inject.
 */
public final class DraftNote extends ComponentTag {

  /** Pause and Resume buttons (DraftNote.client.js): pausing is a browser call, j2act.pause(). */
  interface PauseControls extends Client {
    Mount<DivTag> mount();
  }

  @Inject private Signature signature;

  private final State<String> draft = retainedState("");
  private final PauseControls pauseControls = client(PauseControls.class);

  public static DraftNote draftNote() {
    return new DraftNote();
  }

  @Override protected DivTag render() {
    return div(
      label(
        text("Draft "),
        input()
          .withId("draft")
          .withAutocomplete("off")
          .withValue(draft.get())
          .onChange(e -> draft.set(e.value()))
      ),
      p("Saved draft: " + (draft.get().isEmpty() ? "none" : draft.get())).withId("draft-echo"),
      label(
        text("Signed by "),
        input()
          .withId("signer")
          .withAutocomplete("off")
          .withValue(signature.signer())
          .onChange(e -> signature.sign(e.value()))
      ),
      p("Signer: " + (signature.signer().isEmpty() ? "none" : signature.signer())).withId("signer-echo"),
      div().withId("pause-controls").withClient(pauseControls.mount()),
      // The server can ask too: this tab, or every tab as before a deploy. Each then pauses as if Pause was clicked.
      button("Pause this tab from the server").withId("pause-tab").onClick(e -> tab().requestPause()),
      button("Pause every tab from the server").withId("pause-all").onClick(e -> application().requestPause())
    );
  }
}
