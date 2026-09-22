package com.example.jakarta;

import static j2act.Routes.*;

import jakarta.servlet.annotation.WebListener;

import com.example.jakarta.pages.HomePage;
import j2act.PageResolver;
import j2act.jakarta.J2ActListener;

/** The whole mount: routes on a @WebListener. CDI, the managed executor and sockets are wired by the adapter. */
@WebListener
public class App extends J2ActListener {

  @Override protected PageResolver router() {
    return routes(
      page("/", HomePage.class)
    );
  }
}
