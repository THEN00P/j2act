package j2act.internal.signals;

/** Port of @solidjs/signals core/constants.ts at 9449845. Bits not used by what is ported keep their values. */
final class Constants {
  private Constants() {
  }

  static final int REACTIVE_NONE = 0;
  static final int REACTIVE_CHECK = 1 << 0;
  static final int REACTIVE_DIRTY = 1 << 1;
  static final int REACTIVE_RECOMPUTING_DEPS = 1 << 2;
  static final int REACTIVE_IN_HEAP = 1 << 3;
  static final int REACTIVE_IN_HEAP_HEIGHT = 1 << 4;
  static final int REACTIVE_ZOMBIE = 1 << 5;
  static final int REACTIVE_DISPOSED = 1 << 6;
  static final int REACTIVE_JOINED = 1 << 7;
  static final int REACTIVE_LAZY = 1 << 9;
  static final int REACTIVE_MANUAL_WRITE = 1 << 10;
  static final int REACTIVE_FRAME_READ = 1 << 11;
  static final int REACTIVE_MISSED_WAKE = 1 << 12;
  static final int REACTIVE_REASK = 1 << 13;
  static final int REACTIVE_STAGED_READ = 1 << 14;
  static final int REACTIVE_PROBED = 1 << 15;
  static final int REACTIVE_LANE_DIRTY = 1 << 16;
  static final int REACTIVE_LANE_READ = 1 << 17;
  static final int REACTIVE_SCREEN_READ = 1 << 18;
  static final int REACTIVE_PROBE_UNANSWERED = 1 << 19;
  static final int REACTIVE_WINDOW = 1 << 21;
  static final int REACTIVE_RELEASE_AFTER = 1 << 22;

  static final int CONFIG_OWNED_WRITE = 1 << 0;
  static final int CONFIG_CHILDREN_FORBIDDEN = 1 << 4;
  static final int CONFIG_AUTO_DISPOSE = 1 << 5;
  static final int CONFIG_SYNC = 1 << 6;
  static final int CONFIG_STAGED = 1 << 7;
  static final int CONFIG_HELD = 1 << 8;
  static final int CONFIG_HELD_FRAME = 1 << 20;
  static final int CONFIG_INPUTS_PUBLISHED = 1 << 10;
  static final int CONFIG_OVERRIDE = 1 << 11;
  static final int CONFIG_MANUAL_WRITE = 1 << 18;
  static final int CONFIG_STAGED_ERROR = 1 << 21;
  static final int CONFIG_GUESS = 1 << 13;
  static final int CONFIG_VERDICT = 1 << 15;
  static final int CONFIG_REDERIVE = 1 << 14;
  static final int CONFIG_LAGS = 1 << 19;
  static final int CONFIG_AUTHORITATIVE = 1 << 17;
  static final int CONFIG_FRESH_READ = 1 << 16;
  static final int CONFIG_PROMOTED = 1 << 22;
  static final int CONFIG_FRAME = 1 << 23;
  static final int CONFIG_ASYNC = 1 << 29;

  static final int STATUS_NONE = 0;
  static final int STATUS_PENDING = 1 << 0;
  static final int STATUS_ERROR = 1 << 1;
  static final int STATUS_UNINITIALIZED = 1 << 2;
  static final int STATUS_NO_VALUE = 1 << 3;

  static final int CAUGHT_REARMED = 2;

  static final int EFFECT_PURE = 0;
  static final int EFFECT_RENDER = 1;
  static final int EFFECT_USER = 2;
  static final int EFFECT_TRACKED = 3;

  /** A node's staged slot holds nothing. */
  static final Object NOT_PENDING = new Object() {
    @Override public String toString() {
      return "NOT_PENDING";
    }
  };
}
