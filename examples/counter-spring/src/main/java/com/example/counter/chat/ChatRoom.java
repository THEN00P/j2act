package com.example.counter.chat;

import static j2act.Reactive.*;

import j2act.SharedScoped;
import j2act.Store;
import j2act.Stored;
import java.util.ArrayList;
import java.util.List;

/**
 * A chat room everyone shares (ADR 0037): one per room name, on whichever server of a cluster holds its lease, and the
 * pages of every server read it live. What a page writes goes as a lambda that captures only Strings and numbers.
 */
@SharedScoped
public class ChatRoom extends Store {
  private final Stored<List<ChatMessage>> messages = createStore(new ArrayList<ChatMessage>());

  public List<ChatMessage> messages() {
    return messages.get();
  }

  public void post(String author, String text) {
    messages.update(list -> {
      long id = list.isEmpty() ? 1 : list.get(list.size() - 1).getId() + 1;
      list.add(new ChatMessage(id, author, text));
    });
  }

  public void delete(long id) {
    messages.update(list -> list.removeIf(m -> m.getId() == id));
  }
}
