package clients;

import j2act.Client;
import j2act.Mount;
import j2act.html.tags.DivTag;

public class BadClients {

  interface Broken extends Client {
    String mount(String device); // expect: mount(...) must return Mount<SomeTag>
    Mount<DivTag> start(); // expect: only mount(...) returns Mount
    int count(); // expect: actions return void or CompletionStage<T>
    <T> void pick(T value); // expect: client methods cannot be generic
    void opaque(Object value); // expect: java.lang.Object has no TS mapping
    void wrapped(Wrapper value);
  }

  record Wrapper(Object inside) { // expect: java.lang.Object has no TS mapping
  }

  static class NotAnInterface implements Client { // expect: Client is for interfaces
  }
}
