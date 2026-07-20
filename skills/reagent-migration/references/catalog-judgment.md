# D-tier — "here's how to DECIDE" (judgment)

> This is where the skill earns its keep. The shelved codemod could only
> *flag* these; an AI **reasons** about the right shape. For each rule: the
> decision the source cannot answer, and how to make it. A view that trips
> any D rule is **held whole** — decide it with the author, then convert the
> whole view, or leave the whole view on Reagent (cardinal rule 2).

## MIG-16 — Form-2 / `with-let` view-local state

```clojure
;; before
(defn dropdown []
  (let [open? (r/atom false)]
    (fn [] [:div {:on-click #(reset! open? true)} (when @open? …)])))
```

**The decision: is this state PRODUCT meaning, or ephemeral UI state?** The source can't tell you; the domain can.

- **Product state** (a selected filter, a draft the app acts on, anything another view or an event cares about) → move it to **app-db**: a `reg-event` to write it and a `reg-sub` to read it. This is a dataflow change the skill *names* for the author (cardinal rule 5), not a view rewrite.
- **Ephemeral UI state** (a dropdown's open/closed, a hover flag — meaningful only to this one view) → the compiled host-local `local`:

```clojure
;; after (ephemeral)
(ui/defview dropdown []
  (let [[open? set-open! update-open!] (local false)]
    [:div {:on-click [... ]} (when open? …)]))    ; @open? → open?, (reset! open? v) → (set-open! v)
```

`local` returns a **three-tuple** `[value set! update!]`. Map `@a` → `value`, `(reset! a v)` → `(set! v)`, and — load-bearing — `(swap! a f args)` → `(update! f args)`, which applies `(f current & args)` to the *latest* host state so multiple same-turn writers compose. **Never** emit `(set-open! (f open? …))` over a committed render value — that is last-write-wins. `with-let`'s `finally` clause maps to `effect` cleanup. Setter/updater are host-only; a render-phase mutation fails loud.

## MIG-17 — Form-3 / `r/create-class` lifecycle

```clojure
;; before
(r/create-class {:component-did-mount #(init! …)
                 :should-component-update …
                 :reagent-render (fn [] [:div …])})
```

**The decision: decompose each lifecycle body into host work vs domain work.** Mechanical parts first: delete `:should-component-update` (memo-by-default makes it dead), and extract `:reagent-render` as the view body (the other rules apply to it). Then, per lifecycle body:

- **Host / DOM work** (focus a node, wire a listener, measure) → `(effect :connect …)`. Note: `:connect` cleanup runs at each *disconnect*, not once at unmount — dev StrictMode replays connect/disconnect, so the cleanup must be disconnect-idempotent.
- **Domain work** ("mark viewed", "load on mount") → a route/domain **event** through the dataflow (name it for the author). There is deliberately **no** `:on-mount` primitive; domain-on-mount is a dispatch, not a lifecycle hook.

## MIG-18 — non-conforming `:on-*` handlers

A DOM/custom-element `:on-*` handler whose body **fails the three clean shapes** (MIG-04/05/06) — mixed local work plus a dispatch, a *guarded* dispatch, a dispatch of a non-literal / helper-routed vector, or pure imperative work with no dispatch at all:

```clojure
;; before — mixed local work + dispatch
{:on-click (fn [e] (.preventDefault e) (when ok? (dispatch [:save])))}
```

**The decision: split local work from app intent, then pick the compiled form.** A converted `defview` has **no ambient `dispatch`** in scope, so this is not a one-line lift:

- **A bare fn stays legal** on a DOM `:on-*` (the narrow bare-fn law). But imperative dispatch *inside* that fn obtains the committed dispatcher via **`(ui/dispatch-fn)`** in the view body (per-view stable; reads the committed frame at call time; fails loud with `:rf.error/dispatch-disconnected` when the view is disconnected).
- **A guarded or payload-extracting dispatch** → **`ui/event`**: a `nil` return means *no dispatch*, which is exactly the guard (`when ok?`) expressed as data-with-a-filter.
- **Pure imperative work** whose return is irrelevant → **`ui/handler`**; work that computes and **returns a vector to dispatch** → **`ui/event`**.
- **Mixed**: the local work stays a fn; the app intent becomes a vector on the natural element.

Coupled to MIG-16's state decision (local work often reads/writes view-local state), which is why a MIG-18 hit **gates the view** — decide it whole with the author.

## MIG-23 — SSR (`render-to-string` / hydrate)

```clojure
;; before
(reagent.dom.server/render-to-string [app])
```

**The decision: which SSR path?** re-frame.ui ships both — pick by intent:

- **Static-page (non-hydrating) HTML** — the compiled counterpart of React's `renderToStaticMarkup` → **`(ui/render-static [app-root])`**. Pure `:server` phase, no manifest, no hydration payload; a `ui/client-only` site renders its capability-free fallback and stops. JVM/server only; the root form must be literal.
- **SSR-then-hydrate** — server-emit then adopt on the client → **`re-frame.ssr/hydrate!`** (seed state, no `:render-tree-fn` for a compiled root) **then `ui/hydrate-root`** (per Spec 011). The `reagent.dom.client/hydrate-root` mount from MIG-15 routes here.

Keep the caveat: views using refs/effects need `ui/client-only` (or restructure) for the server phase. (The genuinely-still-unshipped SSR pieces are none of the above — `render-static` and `hydrate-root` shipped; verify any exotic SSR helper against `ui.cljc`'s exports before assuming it exists.)

## MIG-19 — derived state (`r/track` / `r/cursor` / `reaction`)

```clojure
;; before
(let [x (r/track compute a)] [:div @x])
```

**The decision: name the sub, design its query, and rewire the call sites.** The compute fn copies almost verbatim into a `(reg-sub :ns/name …)`; the read site becomes `(sub [:ns/name a])`. You gain caching + Xray visibility. Two things need real thought:

- **A `r/cursor` has a WRITE side** the read-rewrite must not leave behind. `(reset! the-cursor v)` is silent mutation from anywhere; each write site needs a named event (`[:ns/set-b v]`). Enumerate the write sites alongside the reads — naming them is the point of the move.
- **A `track` capturing a component-local value** can't become a global sub verbatim — parameterise the captured value into the query vector.

## MIG-20 — ratom-as-store (a second state model)

```clojure
;; before
(def app-state (r/atom {}))         ; top-level, read by many views
(add-watch app-state :k (fn [_ _ _ v] …))
```

**The decision: this is a *second state model* — restructure it into app-db.** There is no direct re-frame.ui equivalent, and that is deliberate: a shared top-level ratom (and `add-watch`/`track!`/`run!` reactions driving effects) was a **latent concurrency bug in Reagent too**. The move is a *causal-event* restructure: the reads become subs, the writes become events, and an `add-watch` becomes the event handler that made the change (the handler carries the consequence) — **not** a mechanical rewrite to `effect`. A watcher fires *synchronously mid-`swap!`, before render*; an effect runs *after commit* — swapping one for the other is a behaviour change no diff review catches. **Compat escape:** the views on this store stay on Reagent until the store is restructured. This is a dataflow re-model the skill scopes and names; the author does it.

## MIG-28 — computed / dynamic DOM props → `ui/spread`

```clojure
;; before → after
[:input (merge props {:type "text" :value (or draft "")})]
=> [:input (ui/spread (merge props {:type "text" :value (or draft "")}))]
```

The rewrite is emitted (`ui/spread` is the one generic runtime prop-map conversion, DOM elements only) — but it carries a **named check the human weighs**: a spread site forfeits the static manifest row *and* the controlled-input synchrony door (which needs a provably-literal `:value`/`:checked` co-present on the element). Decision: shrink the spread to genuinely pass-through props and lift `:value`/handlers back to literals, or accept the dynamic site knowingly. **Component call sites stay literal-map (MIG-01), never `ui/spread`.** See the **bare-symbol trap** ([`gotchas.md`](gotchas.md)) — a bare symbol *child* is content, never a spread.

## MIG-22 — third-party Reagent wrapper components (re-com et al.)

```clojure
;; before
[rc/single-dropdown {:choices cs}]
```

**The decision is per-library, and these are the LAST movers.** A third-party Reagent component (re-com, a charting wrapper) has no compiled counterpart. Two honest options, chosen with the author:

- **Keep the subtree on Reagent** (the default) — a fully-supported mixed page.
- **Embed it under the boundary form** `(ui/raw (r/as-element [rc/single-dropdown {…}]))` — same root, with the frame-scoping/teardown rules of the boundary contract.

The outward direction (a compiled view *inside* a Reagent tree) needs the `ui/->react` bridge, which is not shipped → R-tier.

## The other judgment calls (decide, then hold-or-convert whole)

| MIG | Construct | The decision |
|---|---|---|
| **MIG-03** | `@(subscribe [:q] {:frame f})` / explicit-frame op | No arity-1 `sub` frame-pin is exported yet. Scope the subtree with `ui/frame-provider {:frame f}`, or hold the view. (Capability gap → R-tier.) |
| **MIG-08** | unkeyed `for`; `sub`/`lease` in a loop; loop-capturing handler | Extract a **keyed child view** (per-row instances). Missing key = build failure; a `sub`/capture in a loop = compile error. The tool never does this structural move — you do, with the author. |
| **MIG-10** | fn-valued prop at a **foreign** component boundary | Choose the callback form: identity/ref → `(ui/raw-fn f)`; needs the event/payload → `ui/event`; imperative/stable-identity → `ui/handler`; pure render prop → `ui/render-fn`. Event *vectors* are not a foreign-boundary form. |
| **MIG-13** | markup-returning `(map (fn …) xs)` in child position | Rewrite to a keyed `for` (`(for [t ts] [item {:key (:id t) :t t}])`) — mechanical only when the fn is a literal with a keyed hiccup body; confirm the candidate. |
| **MIG-26** | ambient `subscribe`/`dispatch` in a plain unregistered `defn` | Grep for these — they throw `:rf.error/no-frame-context`. Preference order: (1) register as a view; (2) hoist the op to the nearest registered ancestor and pass values down; (3) explicit `{:frame f}`. |
| **MIG-27** | fn-valued prop on an **internal-view** call site | **Legal and opaque** (a plain fn prop is an identity-compared value — *not* a compile error; non-gating). *Recommend*, don't force: forward a data vector where you want tool-visibility (`:on-commit [:commit]`, child places it at its DOM `:on-*` site), or `ui/handler`/`ui/render-fn` where a phase/stable-identity is genuinely needed. |
| **MIG-30** | runtime-built markup helper (`(md/render …)` walking an AST) | No compiled spelling for runtime hiccup *data*. Template-ise the callee into `defview` branches (then MIG-01 applies), or route genuinely data-driven markup to `re-frame.ui.data` (a separate artifact), or hold the view. |

Every row above is a view the skill leaves whole until the decision is made. Decide it, then convert the whole view or hold the whole view — never a partial body.
