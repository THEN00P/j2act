package com.example.jakarta.components;

import static j2act.html.TagCreator.*;

import j2act.ComponentTag;
import j2act.Upload;
import j2act.html.tags.DivTag;

/**
 * upload() (ADR 0006): the file travels in resumable chunks, limits hold on the server,
 * and with no withTarget the stored file belongs to the session and goes away with it.
 */
public final class Attachment extends ComponentTag {

  private final Upload file = upload()
    .withAccept(".txt", "image/*")
    .withMaxFileSize("4MB");

  public static Attachment attachment() {
    return new Attachment();
  }

  @Override protected DivTag render() {
    return div(
      label(
        text("Attach a .txt or image, up to 4 MB "),
        input().withId("attach").withType("file").withAccept(".txt,image/*").onChange(e -> file.mutate(e.file()))
      ),
      p(file.isPending() ? "uploading " + file.progress() + "%"
        : file.isSuccess() ? "stored " + file.data().originalName() + " (" + file.data().size() + " bytes)"
        : file.isError() ? "refused: " + file.error().getMessage()
        : "no file yet").withId("attach-status")
    );
  }
}
