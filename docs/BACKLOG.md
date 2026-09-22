# Backlog

Deferred until core is solid (avoid rewrite hell). Signals plus button callbacks cover forms for now.

## Deferred libraries

- **Forms (react-hook-form port)**: field states, validation display, submit wiring. Built on signals/mutations later, by us or anyone.
- **Base UI + Shadcn port**: copy-own components on withClass/cn/cva. Same area as below view transitions.
- **Test harness**: headless session/event/guard/mutation testing. Core stays plain-Java testable (render-to-string) meanwhile.

## Nice-to-haves

- **In-page overlay devtools (not a browser extension)**: react-scan-style perf overlay (which scopes re-ran, patch sizes), slow-event log, component-tree inspector for our scope tree.
- **Optimistic onMutate helpers**: snapshot/rollback conventions on Mutation, TanStack-style.
- **S3-direct upload SPI**: StorageBackend behind the locked granular upload() shape (presigned chunks, no server bytes). Shape already covers it.
- **View transitions**: lands with the component library, deferred together.
