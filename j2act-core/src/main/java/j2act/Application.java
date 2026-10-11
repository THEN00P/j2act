package j2act;

/**
 * The deployment's j2act, as components see it: what spans every page, as opposed to what a
 * component or its page owns (ADR 0027). Reached with application() in a component; host code
 * has the J2Act itself from its DI.
 */
public final class Application {

  private final J2Act engine;

  Application(J2Act engine) {
    this.engine = engine;
  }

  /**
   * Asks every connected page to pause (ADR 0026), such as before a deploy: each runs its
   * j2act.onPausing handlers, then pauses, saving its Retained State and freeing its session.
   * Returns how many pages were asked. A component's pauseTab() asks only its own tab.
   */
  public int requestPause() {
    return engine.requestPause();
  }
}
