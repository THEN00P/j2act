package fixtures;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.Getter;
import j2act.View;

public class Stores extends ComponentTag {

  @Override protected View setup() {
    var user = createStore(new User());
    var name = user.at(User::getName);
    var city = user.at(User::getAddress, Address::getCity);
    var lambda = user.at(u -> u.getName()); // expect: at(...) takes a reference to a public getter
    Getter<User, String> held = User::getName;
    var passed = user.at(held); // expect: at(...) takes a reference to a public getter
    return () -> div(p(name.get()), p(city.get()), p(lambda.get()), p(passed.get()));
  }

  public static final class User {
    private String name = "";
    private Address address = new Address();

    public String getName() {
      return name;
    }

    public Address getAddress() {
      return address;
    }
  }

  public static final class Address {
    private String city = "";

    public String getCity() {
      return city;
    }
  }
}
