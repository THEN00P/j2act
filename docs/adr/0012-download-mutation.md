# Download as upload's twin

Handlers cannot return bytes over a morph, and every app needs "Export CSV". download() is the mirror of upload(): a Mutation flavor whose body writes to an OutputStream, e.g. download((filter, out) -> csv.write(filter, out)).withFileName("users.csv").withContentType("text/csv"), triggered with mutate(filter.get()) from any event. The body runs later on a servlet thread, so its inputs arrive as mutate() variables captured on the lane, never as State reads at download time.

mutate() mints a single-use, short-TTL token bound to the session and CSRF token, and tells the client to fetch it. The transport adapter serves it from one GET endpoint next to the socket mount, re-resolving AuthCtx on that request. The body runs on the servlet request thread and streams straight into the container's response, so no bytes are buffered by us and nothing Spring or Jakarta already does (compression, range, headers) is rebuilt. Content-Disposition keeps the user on the page.

Because the server sees the stream finish, the Mutation's State is honest: isPending until the last byte is written, error if the body throws, success after. A button can spin for the whole export with no extra wiring.
