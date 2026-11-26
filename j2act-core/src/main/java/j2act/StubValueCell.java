package j2act;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A value of a shared store held on another server (ADR 0037), on this server's stub of it: a memo fed by what the
 * holder publishes, and whose writes go to the holder, encoded where they are made, so one that cannot go fails there.
 */
final class StubValueCell extends ValueCell implements Stub.Stubbed {

  private final Stub stub;
  private final Feed feed;

  static StubValueCell of(Tab tab, String address, Stub stub) {
    Feed feed = new Feed(tab.host, () -> { });
    return new StubValueCell(tab, address, stub, feed);
  }

  private StubValueCell(Tab tab, String address, Stub stub, Feed feed) {
    super(tab, address, cell -> prev -> feed.read(),
      new j2act.internal.signals.Runtime.Options<Object>().equals((a, b) -> a == b));
    this.stub = stub;
    this.feed = feed;
    stub.cells.put(address, this);
  }

  @Override public Feed feed() {
    return feed;
  }

  @Override public Cell cell() {
    return this;
  }

  @Override boolean isAsync() {
    return true;
  }

  @Override boolean readFailed() {
    return feed.readFailed();
  }

  @Override boolean committedFailed() {
    return feed.failed();
  }

  @Override Runnable writeOp(Object next) {
    Map<String, Object> op = Ops.set(this, next, tab.engine);
    return () -> stub.op(op);
  }

  @Override @SuppressWarnings("rawtypes") Runnable updateOp(UnaryOperator fn) {
    Map<String, Object> op = Ops.update(this, fn, tab.engine);
    return () -> stub.op(op);
  }

  @Override void applyWrite(Object next) {
    stub.op(Ops.set(this, next, tab.engine));
  }

  @Override @SuppressWarnings("rawtypes") void applyUpdate(UnaryOperator fn) {
    stub.op(Ops.update(this, fn, tab.engine));
  }

  @Override void dispose() {
    super.dispose();
    rt.disposeNode(node);
  }
}
