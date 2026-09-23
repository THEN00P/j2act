package j2act;

/** Lifecycle of a Mutation: nothing yet, running, succeeded, failed. */
public enum MutationStatus {
  IDLE,
  PENDING,
  SUCCESS,
  ERROR
}
