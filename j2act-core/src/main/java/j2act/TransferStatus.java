package j2act;

/** Lifecycle of an Upload or a Download: nothing yet, running, succeeded, failed. */
public enum TransferStatus {
  IDLE,
  PENDING,
  SUCCESS,
  ERROR
}
