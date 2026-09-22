package com.example.team.db;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;

import org.springframework.stereotype.Repository;

/**
 * Host-owned persistence. J2ACT defines no em(): components get this bean
 * through the members-injector seam, and transactions are the host's own.
 */
@Repository
public class Users {

  @PersistenceContext
  private EntityManager em;

  public List<User> search(String like) {
    return em.createQuery(
        "select u from User u where u.name like :n order by u.name", User.class)
      .setParameter("n", like)
      .setMaxResults(100)
      .getResultList();
  }

  public Optional<User> find(long id) {
    return Optional.ofNullable(em.find(User.class, id));
  }

  @Transactional
  public void rename(long id, String name) {
    em.createQuery("update User u set u.name = :n where u.id = :id")
      .setParameter("n", name)
      .setParameter("id", id)
      .executeUpdate();
  }

  @Transactional
  public void updateAvatar(long id, String path) {
    em.createQuery("update User u set u.avatarPath = :p where u.id = :id")
      .setParameter("p", path)
      .setParameter("id", id)
      .executeUpdate();
  }

  @Transactional
  public void delete(long id) {
    em.createQuery("delete from User u where u.id = :id")
      .setParameter("id", id)
      .executeUpdate();
  }

  /** Streams straight into the download response; nothing is buffered. */
  public void writeCsv(String like, OutputStream out) throws IOException {
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write("id,name,email\n");
    for (User u : search(like)) {
      w.write(u.getId() + "," + u.getName() + "," + u.getEmail() + "\n");
    }
    w.flush();
  }

  public static class User {
    private long id;
    private String name;
    private String email;

    public long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }

    // Id-based equality, the usual JPA guidance.
    @Override public boolean equals(Object o) {
      return o instanceof User && ((User) o).id == id;
    }

    @Override public int hashCode() {
      return Objects.hash(id);
    }
  }
}
