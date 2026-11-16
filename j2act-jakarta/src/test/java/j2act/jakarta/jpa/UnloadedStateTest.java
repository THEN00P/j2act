package j2act.jakarta.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import j2act.internal.signals.Runtime;
import j2act.internal.store.JavaShape;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR 0035 with a real JPA provider: Hibernate over H2 in memory. A store never initializes what the entity it was
 * given did not load, through jakarta.persistence's PersistenceUtil.isLoaded, so a detached entity with lazy
 * associations goes into a store, is read and is written without a session.
 */
class UnloadedStateTest {

  @Entity(name = "Customer")
  @Table(name = "customers")
  public static class Customer {
    @Id
    private long id;
    private String name;

    protected Customer() {
    }

    Customer(long id, String name) {
      this.id = id;
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

  @Entity(name = "PurchaseOrder")
  @Table(name = "orders")
  public static class PurchaseOrder {
    @Id
    private long id;
    private String title;
    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<Line> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    PurchaseOrder(long id, String title, Customer customer) {
      this.id = id;
      this.title = title;
      this.customer = customer;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public Customer getCustomer() {
      return customer;
    }

    public List<Line> getLines() {
      return lines;
    }
  }

  @Entity(name = "Line")
  @Table(name = "lines")
  public static class Line {
    @Id
    private long id;
    private String label;
    @ManyToOne(fetch = FetchType.LAZY)
    private PurchaseOrder order;

    protected Line() {
    }

    Line(long id, String label, PurchaseOrder order) {
      this.id = id;
      this.label = label;
      this.order = order;
    }

    public String getLabel() {
      return label;
    }
  }

  /** No JSON columns here. */
  public static final class NoJson implements org.hibernate.type.format.FormatMapper {
    @Override public <T> T fromString(CharSequence chars, org.hibernate.type.descriptor.java.JavaType<T> type,
        org.hibernate.type.descriptor.WrapperOptions options) {
      throw new UnsupportedOperationException();
    }

    @Override public <T> String toString(T value, org.hibernate.type.descriptor.java.JavaType<T> type,
        org.hibernate.type.descriptor.WrapperOptions options) {
      throw new UnsupportedOperationException();
    }
  }

  private static SessionFactory factory;

  @BeforeAll
  static void database() {
    factory = new Configuration()
      .addAnnotatedClass(Customer.class).addAnnotatedClass(PurchaseOrder.class).addAnnotatedClass(Line.class)
      .setProperty("hibernate.connection.url", "jdbc:h2:mem:unloaded;DB_CLOSE_DELAY=-1")
      .setProperty("hibernate.hbm2ddl.auto", "create-drop")
      .setProperty("hibernate.show_sql", "false")
      // JSON-B's API is on this module's path with no provider, which Hibernate would try to use for JSON columns.
      .setProperty("hibernate.type.json_format_mapper", NoJson.class.getName())
      .buildSessionFactory();
    factory.inTransaction(session -> {
      Customer ada = new Customer(1, "Ada");
      PurchaseOrder order = new PurchaseOrder(1, "first", ada);
      session.persist(ada);
      session.persist(order);
      session.persist(new Line(1, "tea", order));
      session.persist(new Line(2, "cake", order));
    });
  }

  @AfterAll
  static void close() {
    factory.close();
  }

  /** The order as a query that fetched nothing else leaves it: its customer a proxy, its lines not loaded. */
  private static PurchaseOrder detached() {
    PurchaseOrder order = factory.fromSession(session -> session.get(PurchaseOrder.class, 1L));
    assertFalse(Hibernate.isInitialized(order.getCustomer()));
    assertFalse(Hibernate.isInitialized(order.getLines()));
    return order;
  }

  private final java.util.ArrayDeque<Runnable> microtasks = new java.util.ArrayDeque<>();
  private final Runtime rt = new Runtime(Runnable::run, microtasks::add);
  private final JavaShape shape = new JavaShape(rt);

  @Test
  void aDetachedEntityIsReadWithoutLoadingWhatItDidNotLoad() {
    PurchaseOrder given = detached();
    Object store = shape.create(given);
    PurchaseOrder read = (PurchaseOrder) shape.value(store);
    assertEquals("first", read.getTitle());
    assertSame(given.getCustomer(), read.getCustomer(), "an unloaded association is one value, the proxy itself");
    assertSame(given.getLines(), read.getLines());
    assertFalse(Hibernate.isInitialized(read.getCustomer()), "and reading the store loaded nothing");
    assertFalse(Hibernate.isInitialized(read.getLines()));
    // The snapshot for a retained store, say, reads the whole value: it loads nothing either.
    PurchaseOrder saved = (PurchaseOrder) shape.snapshot(store);
    assertSame(given.getLines(), saved.getLines());
    assertFalse(Hibernate.isInitialized(given.getLines()));
  }

  @Test
  void aLoadedFieldIsWrittenAndTheUnloadedOnesStayAsTheyWere() {
    PurchaseOrder given = detached();
    Object store = shape.create(given);
    shape.<PurchaseOrder>update(store, d -> d.setTitle("second"));
    rt.flush();
    PurchaseOrder read = (PurchaseOrder) shape.value(store);
    assertEquals("second", read.getTitle());
    assertEquals("first", given.getTitle(), "the entity the store was given is the application's");
    assertNotSame(given, read);
    assertSame(given.getCustomer(), read.getCustomer());
    assertFalse(Hibernate.isInitialized(read.getLines()));
  }

  @Test
  void editingInsideAnUnloadedAssociationSaysToFetchItInTheQuery() {
    PurchaseOrder given = detached();
    Object store = shape.create(given);
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> shape.<PurchaseOrder>update(store, d -> d.getLines().add(new Line(3, "jam", d))));
    assertTrue(e.getMessage().contains("fetch it in the query"), e.getMessage());
    assertTrue(e.getCause() instanceof org.hibernate.LazyInitializationException, String.valueOf(e.getCause()));
    assertFalse(Hibernate.isInitialized(given.getLines()));
    assertEquals("first", ((PurchaseOrder) shape.value(store)).getTitle(), "and the store is as it was");
  }

  @Test
  void anAssociationTheQueryFetchedIsTheStoresToTrack() {
    PurchaseOrder given = factory.fromSession(session -> session
      .createSelectionQuery("from PurchaseOrder o join fetch o.lines join fetch o.customer where o.id = 1",
        PurchaseOrder.class)
      .getSingleResult());
    assertTrue(Hibernate.isInitialized(given.getLines()));
    Object store = shape.create(given);
    @SuppressWarnings("unchecked")
    List<Line>[] lines = new List[1];
    rt.createRoot(owner -> {
      rt.<String>createEffect(prev -> {
        lines[0] = ((PurchaseOrder) shape.value(store)).getLines();
        return lines[0].get(0).getLabel() + "," + lines[0].size();
      }, (value, prev) -> null);
      return null;
    });
    rt.flush();
    assertEquals(2, lines[0].size());
    assertEquals("Ada", ((PurchaseOrder) shape.value(store)).getCustomer().getName());
  }
}
