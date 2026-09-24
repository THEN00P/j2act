package j2act;

/**
 * Marks a client module (ADR 0022): an interface whose optional mount(...) carries props
 * and whose other methods are actions, implemented by a plain object exported from a
 * sibling *.client.ts or *.client.js file. The build-time processor writes its TS types.
 *
 * <pre>{@code
 * interface Camera extends Client {
 *   Mount<VideoTag> mount(String device, Consumer<Size> onReady);
 *   CompletionStage<String> snapshot();
 * }
 * }</pre>
 *
 * mount returns Mount; actions return void or CompletionStage. Parameters are JSON values,
 * DomContent slots, Upload targets and Runnable, Consumer or BiConsumer callbacks.
 */
public interface Client {
}
