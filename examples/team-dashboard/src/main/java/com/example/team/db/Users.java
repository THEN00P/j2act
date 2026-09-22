package com.example.team.db;

import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;

/**
 * Host-owned persistence. J2ACT never owns the EntityManager — it borrows a
 * request-scoped one per query/mutation execution and closes it after.
 */
public final class Users {

  private Users() {}

  public static List<User> search(EntityManager em, String like) {
    TypedQuery<User> q = em.createQuery(
      "select u from User u where u.name like :n order by u.name", User.class);
    q.setParameter("n", like);
    q.setMaxResults(100);
    return q.getResultList();
  }

  public static void updateAvatar(EntityManager em, long id, String path) {
    em.createQuery("update User u set u.avatarPath = :p where u.id = :id")
      .setParameter("p", path).setParameter("id", id).executeUpdate();
  }

  public static void delete(EntityManager em, long id) {
    em.createQuery("delete from User u where u.id = :id")
      .setParameter("id", id).executeUpdate();
  }

  public static class User {
    private long id;
    private String name;
    private String email;

    public long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
  }
}
