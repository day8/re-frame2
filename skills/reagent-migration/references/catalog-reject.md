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

Each of these encodes a Reagent-specific assumption about *this renderer's* component object and render scheduling, and has no compiled equivalent. **The dangerous part:** these are ordinary calls, so a converted view **still compiles** with them in it — and then fails or returns `nil` at runtime, *outside a Reagent render*. The build gate does **not** catch this; you must. Restructure the introspection: `props`/`children` are the props map and positional children the view already receives; `force-update` dissolves under memo-by-default + committed slots.

**The schedulers `next-tick`/`after-render` are NOT `ui/effect`** — do not equate them. Reagent's one-shot render-queue callbacks fire immediately *before the next flush* and immediately *after its queued renders* (even when nothing renders); `ui/effect` is **component-owned passive post-commit work keyed to connect / dependency changes**. Those are different phases, frequencies, and owners — a silent swap changes behaviour no diff review catches, and it **compiles**. So they stay **R-tier**: keep them on Reagent unless the author *explicitly* redesigns the work's phase, frequency, and ownership (a deliberate re-model, not a rename). A view that genuinely needs the introspection or a scheduler call stays on Reagent.

### MIG-21 — dynamic tag heads

```clojure
[(if big? :h1 :h2) props …]     ; head is a runtime expression
```

Rejected at compile time — the compiler resolves heads statically. Bind the attrs and split the branches (`(if big? [:h1 …] [:h2 …])`) — that fits most cases, a *finite* set of heads. For genuinely data-driven heads there is **no available escape today**: `re-frame.ui.data` (the runtime UI interpreter) is a **reserved future wave-2 artifact — not a namespace you can require now**. So template-ise the head into `defview` branches, or the view stays on Reagent. (`re-frame.ui.data` is named here only as a *possible future home*, for direction — do not emit a require for it.)

### MIG-25 — effectful subscription bodies

```clojure
(reg-sub :q (fn [db] (dispatch [:log]) (:x db)))    ; a side effect in a sub body
```

Subs are **pure** in re-frame.ui; the effectful part belongs in a lease/event, not the sub. This is a *dataflow-side* finding surfaced in the report — never a view rewrite. The view's own `@(subscribe …)` deref converts fine (MIG-02) once the sub body is made pure. (Purely a heads-up for the author's dataflow layer; not a reason to hold the view.)

### MIG-20 — a shared ratom store (recap)

The ratom-as-store second state model ([`catalog-judgment.md`](catalog-judgment.md) MIG-20) is a reject *as-written* — there is no direct re-frame.ui equivalent, by design. It appears under D because there is a real restructure decision (into app-db); but until that restructure happens, the views on the store **stay on Reagent**.

## Capability gaps — staged, not yet shipped ("wait")

These are the parts of re-frame.ui that are *declared but not landed*. The skill must **name the gap and hold the view** — never emit a placeholder for an unshipped stage. (Three former gaps have since shipped and moved out: **SSR** — `ui/render-static` for the static-page path and `ui/hydrate-root` + `re-frame.ssr/hydrate!` for SSR-then-hydrate — is now MIG-23 guidance in [`catalog-judgment.md`](catalog-judgment.md); the compiled **`route-link`** is now the MIG-32 head-rename in [`catalog-mechanical.md`](catalog-mechanical.md); and the outward **`ui/->react`** bridge is now the outward-embed transform in [`catalog-judgment.md`](catalog-judgment.md) MIG-22 (boundary directions). Always re-verify a "not shipped" claim against `implementation/ui/src/re_frame/ui.cljc`'s exports before making it.)

### The explicit-frame `sub` pin (arity-1 `sub`)

The shipped `sub` is **arity-1** — there is no exported spelling to pin a `sub`/op to an explicit non-committed frame (`@(subscribe [:q] {:frame f})`, MIG-03). Scope the subtree with `ui/frame-provider {:frame f}` where that fits; otherwise hold the view until the pin surface ships.

## How to phrase a hold to the author

Be specific and non-apologetic. Name the construct, name the gap, name the safe home:

> *"`grid-cell` reads `@(subscribe [:cell v] {:frame report})` — a subscription
> pinned to an explicit non-committed frame. re-frame.ui's `sub` is arity-1 today
> (no exported frame-pin, MIG-03), and this cell can't be scoped with a
> `ui/frame-provider`, so I'm keeping it on Reagent — a fully-supported
> configuration. We can revisit when the pin surface ships."*

A held view is not a failure of the migration. Holding the right views is what makes the migration *honest* — and re-frame.ui being experimental means there will be held views. That is expected.
