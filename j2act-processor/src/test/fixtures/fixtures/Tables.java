package fixtures;

import static j2act.html.TagCreator.*;

import java.util.List;

import j2act.ComponentTag;
import j2act.View;

public class Tables extends ComponentTag {

  private final List<String> names = List.of("Ada", "Grace");

  @Override protected View setup() {
    return () -> div(
      table(
        tr(th("name")), // expect: tr in table(...) without tbody(...)
        each(names, name -> tr(td(name)).withClass("row"))), // expect: tr in table(...) without tbody(...)
      table().with(tr(td("one"))), // expect: tr in table(...) without tbody(...)
      table(
        thead(tr(th("name"))),
        tbody(each(names, name -> tr(td(name))))),
      table(caption("none"), each(names, name -> td(name))), // expect: td in table(...): the browser's parser puts it into a tr of its own
      p(div("block")), // expect: div in p(...): the browser's parser ends the p before it
      p(span(div("deep"))), // expect: div in span(...): the browser's parser ends the p before it
      ul(li(li("twice"))), // expect: li in li(...): the browser's parser ends the li before it
      table(tbody(tr(span("cell")))), // expect: span in tr(...): the browser's parser moves it out of the table
      template(p(div("inert"))),
      table(tbody(tr(td("kept")))));
  }
}
