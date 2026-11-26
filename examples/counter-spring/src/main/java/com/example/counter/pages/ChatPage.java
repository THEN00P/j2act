package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import com.example.counter.chat.ChatRoom;
import j2act.LiveComponent;
import j2act.View;

/**
 * /chat/{room}?name=...: a room shared by everyone who opens it, on any server (ADR 0037). Each reader sees the
 * messages with their own buttons: the demo's "admin" may delete any message, everyone else only their own. The
 * delete has no check of its own: a button that is not on a page cannot be clicked.
 */
public class ChatPage extends LiveComponent {

  @Override protected View setup() {
    ChatRoom room = useSharedStore(ChatRoom.class, pathParam("room"));
    String name = queryParam("name") == null ? "guest" : queryParam("name");
    return () -> html(
      head(title("Chat " + pathParam("room") + " · j2act")),
      body(
        h1("Room " + pathParam("room")),
        div(each(room.messages(), message -> div(
          span(message.getAuthor() + ": " + message.getText()),
          name.equals("admin") || name.equals(message.getAuthor())
            ? button("delete " + message.getId()).onClick(e -> room.delete(message.getId())) : null
        ).withKey(message.getId()))).withId("messages"),
        input().withId("say").withPlaceholder("Say something as " + name)
          .onChange(e -> room.post(name, e.value()))
      )
    );
  }
}
