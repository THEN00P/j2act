# Style

One builder per line, newline after every open bracket, 2-space indent. The Java must read like the HTML document it produces.

```java
return html(
  head(
    title("Users"),
    meta().withName("description").withContent("...")
  ),
  body(
    div(
      h1("Users"),
      input()
        .withPlaceholder("Filter by name")
        .withValue(filter.get())
        .onChange(e -> filter.set(e.value()))
    )
  )
);
```

Never chain builders on one line. Never pass non-child values as params.
