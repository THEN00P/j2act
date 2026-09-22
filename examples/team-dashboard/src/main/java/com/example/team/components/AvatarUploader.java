package com.example.team.components;

import static j2act.html.TagCreator.*;

import javax.inject.Inject;

import com.example.team.db.Users;
import com.example.team.stores.AppStores;
import j2act.ComponentTag;
import j2act.ContainerTag;
import j2act.Mutation;
import j2act.MutationStatus;
import j2act.UploadRef;

/**
 * Headless upload wired to visible UI purely through mutation signals.
 * Temp assembly, resume, server-enforced limits and failure cleanup are
 * framework-owned; the DB commit is a one-shot onSuccess, not an effect.
 */
public final class AvatarUploader {

  private AvatarUploader() {}

  public static AvatarUploaderTag avatarUploader() {
    return new AvatarUploaderTag();
  }

  public static final class AvatarUploaderTag extends ComponentTag {
    @Inject private Users users;

    @Override protected ContainerTag render() {
      long userId = AppStores.currentUser.select(u -> u.userId);
      Mutation<UploadRef> up = upload()
        .withTarget("/var/app/uploads/avatars")
        .withNaming((orig, ctx) -> ctx.username() + "_" + ctx.timestamp() + "_" + orig)
        .withAccept("image/*")
        .withMaxFileSize("10MB")
        .withMaxFiles(1)
        .withInvalidates("user:" + userId)
        .onSuccess(ref -> users.updateAvatar(userId, ref.path()));

      return div(
        input()
          .withType("file")
          .onChange(e -> up.mutate(e.file())),
        up.status().get() == MutationStatus.UPLOADING
          ? progress()
              .withValue(up.progress().get())
          : null,
        up.error().get() == null
          ? null
          : p(up.error().get()),
        up.data().get() == null
          ? null
          : img()
              .withSrc(up.data().get().previewUrl())
      );
    }
  }
}
