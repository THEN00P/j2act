package j2act;

/**
 * When a link pre-mounts its target (ADR 0011): on intent (hover, focus or touch), or
 * never. Set per link with withPreload, or for every link with the mount's default.
 */
public enum Preload {
  /** Hover, keyboard focus or touchstart pre-mounts the target: guards and queries run, Effects wait for the click. */
  INTENT,
  /** No preload, even under an INTENT default. */
  NONE
}
