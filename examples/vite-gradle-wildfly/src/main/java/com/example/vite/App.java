package com.example.vite;

import static j2act.Routes.*;

import jakarta.servlet.annotation.WebListener;

import com.example.vite.pages.HomePage;
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
