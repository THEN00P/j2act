package j2act;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolved identity of a session: principal, roles and claims. Produced by the host's
 * identity function (ADR 0004); j2act never looks up users itself. Immutable, with
 * value equality so a re-resolved identity that did not change is not a change.
 */
public final class AuthCtx {

  private static final AuthCtx ANONYMOUS = new AuthCtx(null, Collections.emptySet(), Collections.emptyMap());

  private final String name;
  private final Set<String> roles;
  private final Map<String, Object> claims;

  private AuthCtx(String name, Set<String> roles, Map<String, Object> claims) {
    this.name = name;
    this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    this.claims = Collections.unmodifiableMap(new LinkedHashMap<>(claims));
  }

  public static AuthCtx anonymous() {
    return ANONYMOUS;
  }

  public static AuthCtx of(String name, Set<String> roles) {
    return of(name, roles, Collections.emptyMap());
  }

  public static AuthCtx of(String name, Set<String> roles, Map<String, Object> claims) {
    return new AuthCtx(Objects.requireNonNull(name, "name"), roles, claims);
  }

  public boolean isAnonymous() {
    return name == null;
  }

  /** The principal name, or null when anonymous. */
  public String name() {
    return name;
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public Set<String> roles() {
    return roles;
  }

  public Object claim(String key) {
    return claims.get(key);
  }

  @Override public boolean equals(Object o) {
    if (!(o instanceof AuthCtx)) {
      return false;
    }
    AuthCtx other = (AuthCtx) o;
    return Objects.equals(name, other.name) && roles.equals(other.roles) && claims.equals(other.claims);
  }

  @Override public int hashCode() {
    return Objects.hash(name, roles, claims);
  }

  @Override public String toString() {
    return isAnonymous() ? "AuthCtx(anonymous)" : "AuthCtx(" + name + ", " + roles + ")";
  }
}
