# Non-goals and seams

J2ACT is a Next.js-shaped framework, not a Laravel-shaped one. It owns only what the runtime alone can do: rendering, the socket, session lifetime, routing, and enforcement points. Everything else is brought by the user through a seam, and a feature that would need us to pick a vendor stays out of core. Optional modules (ui, a forms port) may exist beside core but core never depends on them.

| Brought by the user | Seam |
|---|---|
| Auth and identity | Exchange-to-AuthCtx fn on the mount (ADR 0004) |
| DB, ORM, REST, GraphQL, transactions | Injected fields via the members-injector seam; @Transactional or JTA on the user's own beans (ADR 0004, 0017) |
| Pub/sub, schedulers, CDC (Debezium), jobs | Foreign-thread State.set() from inside an Effect (ADR 0014) |
| Mail, notifications | Plain calls from handlers |
| Validation and forms | State plus Mutation; a forms port may ship as an optional module |
| i18n | Locale in a Store; bundles and formatting are the user's |
| Logging backend | System.Logger routed by the host (ADR 0015) |
| Metrics and tracing | Event, render and error hooks on the mount |
| Thread pools | withExecutor(...), defaulting to the container's (ADR 0017) |
| HTTP caching, CDN, compression | The host container and proxy |
| Business rate limits | The user's; only socket abuse limits are ours (ADR 0013) |
| Non-HTML endpoints (webhooks, JSON APIs, sitemap) | The host's Spring or Jakarta controllers |

Out entirely: static generation and ISR, session replication across nodes (sticky sessions instead, ADR 0004), no-JS fallback (ADR 0003), React-style HMR (ADR 0017), and form recovery after re-mount (ADR 0010).
