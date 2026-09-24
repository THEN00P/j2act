package clients;

import static j2act.html.TagCreator.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import j2act.Client;
import j2act.ComponentTag;
import j2act.DomContent;
import j2act.Mount;
import j2act.Tag;
import j2act.Upload;
import j2act.html.tags.VideoTag;

public class Webcam extends ComponentTag {

  @interface Nullable {
  }

  @interface JsonIgnore {
  }

  @interface JsonProperty {
    String value();
  }

  /** The camera's picture size. */
  public record Size(int width, int height) {
  }

  public enum Facing { USER, ENVIRONMENT }

  public static class Frame {
    public long index;
  }

  public static class Shot<T> extends Frame {
    public T meta;
    public transient String cache;
    private Instant takenAt;
    private String secret;
    private String url;

    public Instant getTakenAt() {
      return takenAt;
    }

    public boolean isBlurry() {
      return false;
    }

    @JsonIgnore
    public String getSecret() {
      return secret;
    }

    @JsonProperty("link")
    public String getURL() {
      return url;
    }

    @Nullable
    public String getNote() {
      return null;
    }
  }

  /** Streams a camera into its video element. */
  interface Camera extends Client {

    /** Starts the stream from this device. */
    Mount<VideoTag> mount(String device, Facing facing, Consumer<Size> onReady, BiConsumer<String, Integer> onError);

    /** A JPEG data URL of the current frame. */
    CompletionStage<String> snapshot();

    void snapshot(Upload into);

    CompletionStage<Shot<Map<String, List<Integer>>>> shot(@Nullable String label, Optional<Size> size);

    void pause(Runnable onPaused, DomContent overlay, byte[] thumbnail, List<Size[]> sizes);
  }

  interface Beeper extends Client { // expect: Webcam.client.ts does not export beeper
    CompletionStage<Void> beep(long millis);
  }

  @Override protected Tag<?> render() {
    return div();
  }
}
