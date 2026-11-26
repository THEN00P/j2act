package j2act.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Several servers sharing @SharedScoped stores (ADR 0037): a store lives on one of them, the holder of its lease, and
 * the others' pages read and write it through that server. Servers reach each other at their j2act socket, the one
 * pages use, and prove to each other that they share the secret.
 *
 * <pre>{@code
 * J2Act.builder(resolver).withCluster(Cluster.builder()
 *     .self("http://10.0.0.5:8080")
 *     .discovery(new DnsDiscovery("app-headless", "http", 8080, ""))
 *     .secret(System.getenv("J2ACT_CLUSTER_SECRET"))
 *     .build());
 * }</pre>
 *
 * With j2act-retained-jdbc, DatabaseDiscovery and DatabaseLeases keep the members, the secret and the leases in the
 * app's DataSource, and nothing needs configuring but the URL. Without a database, a store's lease is kept by its
 * home server, the one its id hashes to among those up; across a network split each side then has a home, so a store
 * can have a holder on each side until it heals, which DatabaseLeases rules out.
 */
public final class Cluster {
  final Node self;
  final ClusterDiscovery discovery;
  /** The secret given, or null for one the discovery keeps, read when a link first needs it. */
  private volatile String secret;
  final SharedLeases leases;
  final Duration term;
  final Duration callTimeout;
  final List<String> remoteCode;

  private Cluster(Builder b) {
    this.self = Objects.requireNonNull(b.self, "self(url): the URL other servers reach this one at");
    this.discovery = Objects.requireNonNull(b.discovery, "discovery(...)");
    this.secret = b.secret;
    if (secret == null ? !(b.discovery instanceof SecretSource) : secret.length() < 16) {
      throw new IllegalArgumentException("a cluster needs a secret of at least 16 characters, from secret(...) or a"
        + " discovery that keeps one, such as DatabaseDiscovery (ADR 0037)");
    }
    this.leases = b.leases != null ? b.leases : b.discovery instanceof SharedLeases ? (SharedLeases) b.discovery : null;
    this.term = b.term;
    this.callTimeout = b.callTimeout;
    this.remoteCode = new ArrayList<>(b.remoteCode);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Node self() {
    return self;
  }

  public ClusterDiscovery discovery() {
    return discovery;
  }

  /** The secret servers prove to each other; not for logs. */
  public String secret() {
    String s = secret;
    if (s == null) {
      s = secret = ((SecretSource) discovery).secret();
    }
    return s;
  }

  /** The leases, or null for leases kept by each store's home server. */
  public SharedLeases leases() {
    return leases;
  }

  public Duration term() {
    return term;
  }

  public Duration callTimeout() {
    return callTimeout;
  }

  /** Packages whose code other servers may run here, besides stores' and components' own (ADR 0037). */
  public List<String> remoteCode() {
    return remoteCode;
  }

  /** What keeps the cluster's secret, such as DatabaseDiscovery's table. */
  public interface SecretSource {
    String secret();
  }

  public static final class Builder {
    private Node self;
    private ClusterDiscovery discovery;
    private String secret;
    private SharedLeases leases;
    private Duration term = Duration.ofSeconds(15);
    private Duration callTimeout = Duration.ofSeconds(10);
    private final List<String> remoteCode = new ArrayList<>();

    private Builder() {
    }

    /** The URL other servers reach this one's j2act at, with its context path. */
    public Builder self(String url) {
      this.self = new Node(java.util.UUID.randomUUID().toString(), url);
      return this;
    }

    public Builder discovery(ClusterDiscovery discovery) {
      this.discovery = discovery;
      return this;
    }

    /** The secret every server of the cluster has, at least 16 characters. */
    public Builder secret(String secret) {
      this.secret = secret;
      return this;
    }

    public Builder leases(SharedLeases leases) {
      this.leases = leases;
      return this;
    }

    /** How long a lease lasts without renewal: how long a store is gone after its server dies. 15 s by default. */
    public Builder term(Duration term) {
      this.term = term;
      return this;
    }

    /**
     * How long a write to a store on another server may take to be applied there before what made it fails, with
     * its outcome unknown; it is not applied twice, however often it went. 10 s by default.
     */
    public Builder callTimeout(Duration timeout) {
      this.callTimeout = timeout;
      return this;
    }

    /** Packages whose lambdas other servers may run here, besides those of stores and components. */
    public Builder allowRemoteCode(String... packages) {
      remoteCode.addAll(java.util.Arrays.asList(packages));
      return this;
    }

    public Cluster build() {
      return new Cluster(this);
    }
  }
}
