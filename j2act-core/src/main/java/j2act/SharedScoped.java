package j2act;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One instance per key for everyone (ADR 0037): put it on a class that extends {@link Store}, and reach the instance
 * for a key, such as a chat room's id, with {@code useSharedStore(ChatRoom.class, roomId)}, or the one for the whole
 * application with {@code useSharedStore(Settings.class)}. Every page that reads it, of any user, sees its values
 * live, and a write from one page shows on the others once the store has it.
 *
 * <pre>{@code
 * @SharedScoped
 * public class ChatRoom extends Store {
 *   private final Signal<List<Message>> messages = createSignal(List.of());
 *
 *   public List<Message> messages() { return messages.get(); }
 *   public void post(Message m) { messages.update(list -> plus(list, m)); }
 * }
 * }</pre>
 *
 * Its values live on a lane of its own, and its computeds, queries and effects run once for the store. They cannot
 * read what differs between readers: auth(), pathParam(), a tab or browser store. Its key is {@link Store#sharedKey()}.
 * It lives while a component that looked it up is on a page, or a page reads it, and a grace window after; its
 * Retained State outlives it and comes back with the next one for the same key.
 *
 * Unlike {@link TabScoped} and {@link BrowserScoped} it is no container bean, since a key is no context a container
 * knows: j2act makes it with its no-argument constructor and injects its members, as for stores without a container.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SharedScoped {
}
