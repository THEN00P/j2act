# Merging server changes into text fields

ADR 0013 made a controlled field's value the server's, and a focused field's value the user's. So the server could not clear a field after a send or turn `:smile:` into an emoji while the user was in it, and a blurred field could still be rewound by a late echo. Focus was never the question. A server has real reasons to change a field that has focus. What it must not do is rewind what it has not seen yet.

So the rule is: **the server has authority over what its view declares, and the user has authority over what the server has not seen yet.** It applies to a text field's value whether it has focus or not. A field the render leaves unset stays the user's, as in ADR 0013.

```java
input()
  .withValue(text.get())                                   // declares it: the server's
  .onInput(e -> text.set(e.isUndo() ? e.value() : rewrite(e.value())));
```

## How others do it

| | A focused field | The catch |
|---|---|---|
| LiveView | "will never overwrite" it; `phx-patch-focused` opts in to patching | The server loses by default |
| Blazor Server | The server wins: it updates its own tree to what the browser sent, then diffs | A late echo overwrites newer typing |
| Idiomorph's `ignoreActiveValue` | Leaves what has focus alone | Everything or nothing |

Blazor's idea of treating an input as a change to what the page has is kept, as a rejected letter needs an answer. LiveView's tracking of what is in flight is kept, sharpened from "is anything in flight" to which text it covers.

## A three-way merge

The client knows three versions of a field: what the server had seen when it rendered, what it answered, and what the field holds now. Each patch says the last event the server had handled (`handled`), and the client remembers the value it sent with each event, so the first is known exactly.

The server's change goes in unless it touches what the user changed since. If it does, the user wins for now.

| Seen by the server | Its answer | The field now | Result |
|---|---|---|---|
| `hi :smile:` | `hi 😄` | `hi :smile: and mo` | `hi 😄 and mo` |
| `hello` | empty | `hellonext` | `next` |
| `hi :smile:` | `hi 😄` | `hi :smi-le:` | Stays: the user is in that text |
| `12a` | `12` | `12a` | `12` |

Any event that carries what the field holds counts as the server seeing it: a blur or a key as much as an input, so a server that tidies a field on blur gets its answer in. An input still waiting on a debounce goes before a blur or a key, or the server would answer them without it. Only a field the server controls is remembered, since only its answers come back to be merged.

Each side's change is one span, from the first difference to the last. A simulation found a real diff kept no more than that.

What the client merged goes back to the server as input when the server has not got it, or the two stay apart. Without this they disagreed at the end of over half the simulated runs. It settles: once the server has seen everything, base and field are the same, and the server's answer goes in whole.

A rewrite the user's text blocks comes back with the next render, since each event sends the whole value. A one-off change the server makes can be lost if it touches what the user changed: the user wins, and the event in flight overwrites it on the server. Closing that would take merging on the server too, which is operational transformation, and a server that must not lose something should hold it apart from the field.

## `handled` must be true

A render during a hold can read a value from before the last event, as in Solid. Told that it had handled that event, the client would take the old value for a decision and rewind the user. So while anything is held, a patch says the last event from when nothing was, and the scope gets its patch again when the hold is over.

## Undo

The change goes in with `document.execCommand("insertText")`, which keeps it in the browser's undo history, as a step of its own. In Chrome, setting `value` or `setRangeText` stopped undo at the server's change and made redo produce garbage. `execCommand` is deprecated with no replacement for this (MDN says so), and where it fails the value is set directly, which loses undo. It only works on what has focus, so a field without it gets the value set, as no undo is under way there.

Undoing a rewrite sends what it undid back as input, which the server would rewrite straight back. So a handler can ask `e.isUndo()`, from the browser's `inputType`. The framework cannot decide this for it, as it cannot tell a rewrite from validation.

Nothing is applied during IME composition, which a change would break. The `input` event ending it brings a new answer.

## What this leaves out

- `contenteditable` keeps its contents while it has focus. Nothing there declares that the server owns them, and merging around a caret in rich content is another problem. Editors stay client modules that the server drives through actions (ADR 0022).
- Other input types, such as a range or a date, keep the rule of ADR 0013: left alone while they have focus. They have no text to merge.
- Fields with no selection API, such as `number` and `email`, take the server's value only when the server has seen everything, since there is no caret to keep.
