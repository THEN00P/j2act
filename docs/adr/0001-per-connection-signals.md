# Per-connection isolated signals

Preact signals assume single-threaded JS for one user, but J2ACT runs multi-user/multi-threaded on the server. We scope every Signal graph to one browser connection/island session and serialize mutations per session, so user A can never see user B's counter and the Preact mental model holds without manual locking.
