package fixtures;

/** A bean as an app keeps one in a store: the enhancer makes it track its own reads. */
public final class Profile {
  private String name;
  private String city;
  private Pet pet;

  public Profile() {
  }

  public Profile(String name, String city) {
    this.name = name;
    this.city = city;
    this.pet = new Pet();
    this.pet.setKind("cat");
    this.pet.setName("Tom");
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public Pet getPet() {
    return pet;
  }

  public void setPet(Pet pet) {
    this.pet = pet;
  }
}
