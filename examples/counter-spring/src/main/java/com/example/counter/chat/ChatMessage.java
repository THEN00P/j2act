package com.example.counter.chat;

/** One message of a chat room: a store bean, so a page reading its text follows only its text (ADR 0035, 0036). */
public class ChatMessage {
  private long id;
  private String author;
  private String text;

  public ChatMessage() {
  }

  public ChatMessage(long id, String author, String text) {
    this.id = id;
    this.author = author;
    this.text = text;
  }

  public long getId() {
    return id;
  }

  public String getAuthor() {
    return author;
  }

  public String getText() {
    return text;
  }
}
