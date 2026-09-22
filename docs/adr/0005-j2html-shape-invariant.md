# J2HTML shape invariant

Every builder in J2ACT mirrors J2HTML so it maps 1:1 to JSX/HTML: method params take only child nodes (text, elements, components, child routes), everything else is a with* builder, factories are static imports (div(), button(), route(), router()), never X.create().

We learned this by violating it: whenRole() custom conditionals instead of ternary/iff, positional (path, Page, children) route params, cn() at call sites instead of inside UI.*, and manual require() in handlers. Locked instead: ternary/iff with auth enforcement captured from render reads, static page()/scope()/layout() factories with positional (path, page), and UI.button("Delete").withVariant(...).withClass(...) alongside raw button("Delete").

Conditionals render absence as null (React's {cond ? <X/> : null}, J2HTML's iff returns null) — never a nothing() placeholder. The fork must skip null children.

Composition feels like tags with no new at call sites. Factories take children only — always; all props (data, State handles) arrive via with* builders, the same channel that already carries withClass strings and onClick lambdas: userRow().withUser(u), counter().withCount(count). State survives re-renders through the identity rules in ADR 0019.

Layouts render explicit children — render(DomContent) receiving the matched child as a plain child node, never an ambient outlet(). No named-slot system: React has no slots primitive (generic <Slot name> is discouraged as stringly-typed), Next.js reserves @slots for independently-navigated parallel regions only with sidebars as plain components, and Shadcn's sidebar is provider plus compound children. Parallel regions stay deferred; sidebar state is store().

No bound overloads (text(State), withClass(State), ...): they smuggle non-child references into params. Targeted updates come only from component scopes plus server diff — never from binding APIs. One static home per family: j2html.TagCreator tags, j2act.Routes route DSL, j2act.States reactive primitives, j2act.ui.Ui plus Variant styled tags. Events are typed after Vaadin Flow (.onClick(ClickEvent), .onChange with e.value(), file inputs with e.files()). with* component props write Prop<T> fields on a ComponentTag base, so reads are tracked (ADR 0020).

State placement: state() may be called as a field initializer or inside render(), bound to the component's tree slot by creation order (unconditional, hooks-like, ADR 0019). Props flow explicitly: factories take children only, all props (data, State handles) arrive via with* builders. Reads of enclosing state inside tag-building fns are for small private helpers only.

Instance identity: each tree slot gets its own State and its own stable scope anchor, so two counter() siblings never share state or morph targets. Held objects keep identity by reference, and repeated stateful rows take withKey(...) on the row itself, in each() or a stream alike (ADR 0019). Never hardcode withId inside a reusable component — derive ids from props.
