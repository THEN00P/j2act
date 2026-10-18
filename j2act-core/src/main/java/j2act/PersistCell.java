package j2act;

/** Backs onPersisting(): holds the latest render's callback for Tab.persisting(). */
final class PersistCell extends Cell {

  Runnable callback;

  PersistCell(Tab tab, String address) {
    super(tab, address);
  }

  @Override Object peek() {
    return null;
  }
}
