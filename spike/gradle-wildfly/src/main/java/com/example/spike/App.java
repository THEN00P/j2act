package com.example.spike;

import static j2act.Routes.*;

import jakarta.servlet.annotation.WebListener;

import com.example.spike.pages.HomePage;
import j2act.PageResolver;
import j2act.jakarta.J2ActListener;

@WebListener
public class App extends J2ActListener {

  @Override protected PageResolver router() {
    return routes(
      page("/", HomePage.class)
    );
  }
}
