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

## Client runtime (TypeScript)

Functions and plain objects, not classes. A module with state exports `createX(deps)`, which keeps its state in closure variables and returns an object of the functions others call, plus `export type X = ReturnType<typeof createX>`; one with nothing to return is a function, like `listenForEvents`. Use a class only for something the runtime makes many of. Server messages go through the `Handlers` map in `main.ts`, so a message type without a handler does not compile.
