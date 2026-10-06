package j2act;

/**
 * How one type of Retained State value is written to a snapshot and read back, in place of
 * the app's JsonBinding (ADR 0026), like .NET's PersistentComponentStateSerializer. Register
 * it with J2Act.Builder.withRetainedCodec for the exact type the field declares.
 */
public interface RetainedCodec<T> {

  String write(T value);

  T read(String text);
}
