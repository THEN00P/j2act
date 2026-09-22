# team-dashboard (design sketch)

Not in the Maven reactor and does not compile. It shows where the API is heading, written in the shapes that already exist wherever they do. `examples/counter-spring` is the runnable demo.

Real today, and used here as they are: `ComponentTag`/`LiveComponent`, `state`/`prop`/`query`/`effect`, `withKey`, the generated typed tags (`DivTag`, `HtmlTag`, `TrTag`, ...) and their attributes, `withDebounce`, `withPending`, `onClick`/`onInput`/`onChange`/`onSubmit`/`onKeyDown`/`onFocus`/`onBlur`, `withKeyFilter`, `pathParam`, and DI through `@Inject` fields.

Still design targets: `j2act.ui.Ui` (`UI.button`, `spinner`, `Variant`), `withPreload`, `Layout`, `scope`/`layout`/`middleware`/`redirect`/`fallback` routes, `notFound`, `auth()`/`AuthCtx`/`Middleware`, `Mutation`/`upload`/`download`, page `loading()`/`error()` boundaries, and `Store`/`createStore`.
