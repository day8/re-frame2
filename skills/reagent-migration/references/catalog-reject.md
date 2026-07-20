# R-tier — "don't migrate this" (stay on Reagent, or wait)

> This list is the honesty backbone of the whole skill. re-frame.ui is
> **experimental**; some views should not move — either because the construct
> has no compiled equivalent *by design*, or because the capability is
> **staged and hasn't shipped**. For every case here the answer is the same:
> **keep this view on Reagent** (a fully-supported configuration), and say so
> plainly. Never contort a view to force it across, and never emit a form for
> a stage that hasn't landed.

## Genuine rejects — no compiled equivalent, by design

### MIG-35 — Reagent component-introspection / scheduler API

```clojure
(r/current-component) · (r/props c) · (r/children c)
(r/force-update c) · (r/next-tick f) · (r/after-render f)
```

Each of these encodes a Reagent-specific assumption about *this renderer's* component object and render scheduling, and has no compiled equivalent. **The dangerous part:** these are ordinary calls, so a converted view **still compiles** with them in it — and then fails or returns `nil` at runtime, *outside a Reagent render*. The build gate does **not** catch this; you must. Restructure instead: `props`/`children` are the props map and positional children the view already receives; `force-update` dissolves under memo-by-default + committed slots; `next-tick`/`after-render` are `effect` (post-commit, with cleanup). A view that genuinely needs the introspection stays on Reagent.

### MIG-21 — dynamic tag heads

```clojure
[(if big? :h1 :h2) props …]     ; head is a runtime expression
```

Rejected at compile time — the compiler resolves heads statically. Bind the attrs and split the branches (`(if big? [:h1 …] [:h2 …])`), or, for genuinely data-driven UI, use `re-frame.ui.data` (a separate artifact). If neither fits, the view stays on Reagent.

### MIG-25 — effectful subscription bodies

```clojure
(reg-sub :q (fn [db] (dispatch [:log]) (:x db)))    ; a side effect in a sub body
```

Subs are **pure** in re-frame.ui; the effectful part belongs in a lease/event, not the sub. This is a *dataflow-side* finding surfaced in the report — never a view rewrite. The view's own `@(subscribe …)` deref converts fine (MIG-02) once the sub body is made pure. (Purely a heads-up for the author's dataflow layer; not a reason to hold the view.)

### MIG-20 — a shared ratom store (recap)

The ratom-as-store second state model ([`catalog-judgment.md`](catalog-judgment.md) MIG-20) is a reject *as-written* — there is no direct re-frame.ui equivalent, by design. It appears under D because there is a real restructure decision (into app-db); but until that restructure happens, the views on the store **stay on Reagent**.

## Capability gaps — staged, not yet shipped ("wait")

These are the parts of re-frame.ui that are *declared but not landed*. The skill must **name the gap and hold the view** — never emit a placeholder for an unshipped stage.

### MIG-23 — the SSR emit path

`reagent.dom.server/render-to-string` (and `hydrate-root` mounts) map to re-frame.ui's `emit-ui-tree` / `render-static` serialisation path, which is **staged (S5) and not yet shipped**. Until it lands, keep the frozen `re-frame.ssr` compat path for server-rendered views. Views using refs/effects additionally need `client-only`/restructure for SSR.

### The outward `ui/->react` bridge

A converted `ui/defview` **cannot be referenced from an unconverted Reagent body** — the outward bridge (`ui/->react`) is **staged (S6) and not yet shipped**. This is why the procedure migrates **closed subtrees** ([`procedure.md`](procedure.md)): leaf → root, so a converted view is only ever consumed by other converted views. A converting view that *must* be called from a view staying on Reagent is held until the bridge ships (or the caller converts too).

### The explicit-frame `sub` pin (arity-1 `sub`)

The shipped `sub` is **arity-1** — there is no exported spelling to pin a `sub`/op to an explicit non-committed frame (`@(subscribe [:q] {:frame f})`, MIG-03). Scope the subtree with `ui/frame-provider {:frame f}` where that fits; otherwise hold the view until the pin surface ships.

### The compiled `route-link` (MIG-32)

`re-frame.ui` ships no ruled compiled counterpart of the stock-Reagent `route-link` yet, and a plain `[:a {:href …}]` is **not** an equivalent (the router does not intercept plain anchors). Any view using `route-link` — which, in a routed app, is most of them — stays on Reagent pending the framework ruling.

## How to phrase a hold to the author

Be specific and non-apologetic. Name the construct, name the gap, name the safe home:

> *"`article-view` uses `render-to-string` for SSR. re-frame.ui's server-emit
> path is staged and hasn't shipped yet, so I'm keeping this view on Reagent —
> that's a fully-supported configuration. We can revisit when the SSR stage
> lands."*

A held view is not a failure of the migration. Holding the right views is what makes the migration *honest* — and re-frame.ui being experimental means there will be held views. That is expected.
