# SSR first, then Idiomorph morphs plus typed js() bridge

LiveView's JS hooks drift from markup and full re-renders lose focus/scroll. We serve full SSR HTML (complete and readable for first paint and crawlers; interactivity requires JS and the socket, no no-JS fallback), then patch over WS via Idiomorph from a tiny fixed runtime, with one explicit typed js() bridge for Base UI/Shadcn cases so interop is first-class, not an afterthought.

No retained HTML snapshots: per session we keep only the dependency graph and the bounded query cache. Dirty scopes re-run and resend full HTML against a stable anchor; Idiomorph resolves minimal DOM churn on the client. Server diffing is out — rebuild is cheap, bandwidth is fine.

List identity: no keys by default — positional plus id-set matching suffices for stateless rows like text cells. Stateful or reorderable rows use an opt-in morph-only withKey(...) hint that is never rendered as HTML. withId stays a real page-unique HTML id, never a morph key.
