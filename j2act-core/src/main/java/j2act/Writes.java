package j2act;

/**
 * Where a write to a graph's state runs (ADR 0037), for what writes no single value: at once on that graph's lane;
 * with the job's or the action's writes when another graph's code makes it, sent when the job or the action ends; in
 * the action's transaction from its body; as a write from outside from any other thread (ADR 0014).
 */
final class Writes {
  private Writes() {
  }

  static void to(Tab target, Runnable apply) {
    if (target.lane.isCurrent()) {
      apply.run();
      return;
    }
    Runnable guarded = () -> {
      if (!target.disposed) {
        apply.run();
      }
    };
    Tab reader = Tab.current();
    if (reader != null && reader.host != target.host && reader.lane.isCurrent()) {
      reader.host.queueWrite(target.host, guarded);
      return;
    }
    Action.Call call = Action.Call.current();
    if (call != null && call.tab.host != target.host) {
      call.queueTo(target, guarded);
      return;
    }
    if (call != null) {
      call.enter(target, () -> {
        guarded.run();
        return null;
      });
      return;
    }
    target.host.writeFromOutside(() -> Tracking.in(target, null, () -> {
      guarded.run();
      return null;
    }));
  }
}
