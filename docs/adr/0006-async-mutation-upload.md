# Async primitives: query, generic mutation, upload factory

Signals cover synchronous state only; pending/error/retry/dedup need their own shape, borrowed from TanStack and hardened against Vaadin/LiveView/Uppy flaws. Query keys are cache identity (dedup + share); mutation keys scope writes and wire withInvalidates refetching — no auto-linking. Mutation<T> is generic over any write; upload() preloads it with chunked resumable transport, server-enforced restrictions (never client-hint-only), UploadCtx naming over sanitized inputs, and framework-owned temp cleanup.
