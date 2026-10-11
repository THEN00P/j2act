package j2act;

/** Backs onPersisting(): holds the latest render's callback for Session.persisting(). */
final class PersistCell extends Cell {

  Runnable callback;

  PersistCell(Session session, String address) {
    super(session, address);
  }

  @Override Object peek() {
    return null;
  }
}
