# Spec 004 — Views

> Status: Drafting. **v1-required.** A view is a pure function `(state, props) → render-tree`, with the render-tree as a serialisable nested data structure. The CLJS reference's `reg-view` is a **defn-shape macro** that auto-defs the symbol you supply, auto-derives the registered id from `(keyword *ns* sym)`, and lexically auto-injects frame-bound `dispatch`/`subscribe` — an ergonomic realisation of the explicit-frame contract. The plain-fn surface `reg-view*` is the runtime-callable escape hatch. Hiccup is the CLJS render-tree; other hosts use their own shape. SSR ([Spec 011](011-SSR.md)) renders the same views to a string on the server without React.

## Abstract

A view is a **pure function `(state, props) → render-tree`**. The pattern-level commitments:

1. **Pure.** No render-time side-effects, no implicit reads from ambient state beyond what's declared in inputs.
2. **Frame-explicit.** The view targets a specific frame; that frame is part of the render-time inputs (via parameter, closure, or implementation-specific injection that resolves to the same observable).
3. **Render-tree is serialisable data.** A nested data structure (hiccup, JSX-as-data, virtual-DOM nodes — host choice) that the runtime can render to a string for SSR (per [011](011-SSR.md)) or to a client-side substrate.

These are pattern-level commitments; they hold across the eight in-scope JS-cross-compile-to-React+VDOM languages (per [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic) — ClojureScript, TypeScript, Melange / ReScript / Reason, Fable (F#), Squint, Scala.js, PureScript, Kotlin/JS).

The render-tree shape is specified in [§The render-tree shape](#the-render-tree-shape-pattern-level-contract). The CLJS realisation of the frame-explicit commitment — `reg-view` and the hiccup invocation forms — is specified in [§`reg-view` is the multi-frame contract](#reg-view-is-the-multi-frame-contract) and [§How registered views are used in hiccup](#how-registered-views-are-used-in-hiccup). The frame-routing mechanics that `reg-view` consumes (React-context resolution, `frame-provider`, `bound-fn`/`bound-dispatcher`) live in [002-Frames.md §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part) — Spec 004 owns the view-side API surface; 002 owns the frame-side mechanics.

### What is server-renderable, what is client-only

Per [011](011-SSR.md):

- **Server-renderable:** the view function itself (pure `(state, props) → render-tree`); the render-tree as a string (pure hiccup → string is JVM-runnable); the subscription computation (pure `state → value`); machine transitions (pure).
- **Pure render-time input:** the frame's `app-db` value; the frame id; the props passed to the view.
- **Client-only:** the React mount/commit lifecycle; reactive sub *tracking* (auto-subscribe-on-deref); the React-context-driven `frame-provider` machinery; DOM-mutation effects; browser APIs (clipboard, IndexedDB, etc.).

The view function's *body* — including `subscribe` calls that yield values, and `dispatch` calls that close over the frame — runs on the server given a frame and an `app-db`. The reactive *tracking* of subs (auto-rerender on change) is a client concern. SSR computes subs against a static `app-db`; hydration on the client wires up the reactive tracking.

### The render-tree shape (pattern-level contract)

Three things are specified separately to avoid conflating them: the **conceptual node shape** (the data model every host shares), the **carrier** (the host-language data structure that holds the shape), and the **serialisation boundary** (which parts of the shape survive a print/read round-trip).

#### Conceptual node shape

A render-tree node is conceptually one of:

- A **literal value** — string, number, `nil` — rendered as text or empty.
- A **structured node** carrying three slots:
  - **`tag`** — either an *id* (resolves to a registered view; the host's identity primitive) **or** a *host-DOM tag* (`div`, `span`, etc., interpreted as raw DOM by the renderer).
  - **`attrs`** — an open map of key-value pairs: DOM attributes, event handlers, style, props.
  - **`children`** — zero or more child render-tree nodes.

This conceptual shape is **host-independent** — it is what every conformant view contract produces.

#### Carrier (host-specific)

The conceptual node shape is encoded into the host's idiomatic data structure:

| Host | Carrier | Example |
|---|---|---|
| ClojureScript (reference) / Squint | Hiccup vector — `[tag attrs? & children]` (attrs map is positional and optional) | `[:div {:class "x"} "hi"]` |
| TypeScript | JSX (compiled to React `createElement`) or array form `[tag, attrs?, ...children]` or VDOM-object `{type, props, children}` — all valid | `<div className="x">hi</div>` / `['div', {className: 'x'}, 'hi']` |
| Melange / ReScript / Reason | JSX (compiles to React `createElement`) | `<div className="x">{React.string("hi")}</div>` |
| Fable (F#) | Feliz DSL or `Fable.React` `createElement` calls | `Html.div [ prop.className "x"; prop.text "hi" ]` |
| Scala.js | Slinky / `scalajs-react` DSL trees | `div(cls := "x")("hi")` |
| PureScript | `React.Basic.DOM` element constructors | `R.div [ R.className "x" ] [ R.text "hi" ]` |
| Kotlin/JS | kotlin-react DSL trees | `div { className = ClassName("x"); +"hi" }` |

The carrier is the host's choice; the conceptual shape is what every host's renderer walks. **Template-string DSLs (Mustache, Jinja, etc.) are NOT a valid carrier** — strings don't compose, don't diff, don't lint, don't round-trip. The pattern requires structured data the runtime can walk.

The pattern does NOT commit to:
- A specific tag-name vocabulary for DOM (HTML elements vs custom).
- A specific attrs spelling (`:on-click` vs `onClick` vs `on_click`).
- A specific event-handler signature.
- Whether `children` is variadic, positional, or wrapped in an array.

These are carrier-level choices.

#### Serialisation boundary

The render-tree's *structure* — the tags, the children nesting, and the **non-function values** inside `attrs` (strings, numbers, keywords, plain maps, vectors of the same) — is **fully serialisable** and survives a print/read round-trip. SSR ([011](011-SSR.md)) relies on this for the server-side render-to-string path.

**Function values inside `attrs` (`:on-click` lambdas, `:ref` callbacks, custom prop closures) are NOT serialisable** and lie outside the serialisation boundary. SSR's discipline:

- The server's render-to-string path walks the render-tree and emits HTML for the structure; it ignores function-valued attrs (they would not survive the wire and have no client-side meaning until hydration).
- On the client, hydration re-renders the same view function under the same `app-db` value; the client-side render produces the function-valued attrs at render time and React/the substrate attaches them.

The contract therefore: **structure is serialisable; behaviour (functions) is not**. This is consistent with the broader spec position that the wire carries data and behaviour is registered at runtime per host.

### Render-tree primitives

Render-tree activity surfaces in the trace stream via `:view/render` events and in `:rf/epoch-record`'s `:renders` projection (per [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record)). The identity carried on each entry is the `:render-key` — the tuple shape every conformant view contract emits.

#### `:render-key` is the tuple `[<view-id> <instance-token>]`

Per rf2-t5tx Option C / rf2-piag, a `:render-key` is a two-element vector:

- **`view-id`** — the `reg-view` registry id (e.g. `:my-app.cart/row`, `:counter`). Names the *kind* of view. For renders that did not enter through `reg-view` / `reg-view*` (plain Reagent fns), the view-id slot is `:rf.view/anonymous` (see §Anonymous fallback below).
- **`instance-token`** — an integer, minted at mount time from a process-wide counter. Disambiguates concurrently-mounted instances of the same view-id. Tokens are monotonic within a single process run; they carry **no cross-run correlation guarantee** and are not stable across hot-reloads, page refreshes, or replay/restore. Tools that want cross-run identity must derive it elsewhere (positional path, parent-aware keys); the instance-token is for in-run discrimination only.

Tuple example:

```clojure
{:render-key   [:my-app.cart/row 1473]
 :triggered-by :sub.cart/items
 :elapsed-ms   1.2}
```

Two mounted instances of the same view-id share the view-id and differ in the instance-token:

```clojure
[:my-app.cart/row 1473]   ;; first row, mount-instance A
[:my-app.cart/row 1474]   ;; second row, mount-instance B (same kind, different mount)
```

#### Token lifecycle

- **Minted at mount.** The first render of a `reg-view`-registered component allocates one token from the runtime counter; subsequent re-renders of the same instance reuse it.
- **Discarded on unmount.** No bookkeeping; the next mount of an equivalent component gets a fresh token.
- **Process-scoped.** Counter resets when the process restarts. A token from one run never collides with a token from another run within the same run; across runs, equality is meaningless.
- **Replay-aware.** Tool-Pair's epoch restore (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)) does NOT preserve tokens — a fresh run mints fresh tokens. Restored `:db-after` is the contract; the `:renders` projection is a per-run derivation, not an identity continuation.

#### Anonymous fallback for plain Reagent fns

Plain Reagent fns (`(defn my-view [...] ...)`) do not enter through `reg-view`'s wrapper, so they do not bind `*render-key*`. When the trace recorder reads `:render-key` and finds the binding absent, it emits the documented fallback shape `[:rf.view/anonymous nil]`:

- **`:rf.view/anonymous`** — the canonical anonymous-view-id keyword.
- **`nil`** — the anonymous instance-token. Anonymous renders do not allocate per-instance tokens; tools that need to disambiguate anonymous renders must use other signals (call-site, parent context).

If the runtime can derive a cheap function-name hint (e.g. via `(.-displayName fn)`) it MAY emit `[:rf.view/<name> nil]` for tooling-friendliness, but `[:rf.view/anonymous nil]` is the safe default and the canonical fallback. The CLJS reference today emits `[:rf.view/anonymous nil]` unconditionally; the per-name optimisation is reserved as a future addition.

#### Production elision

`:render-key` is part of the trace surface; per [Spec 009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code), trace emission elides entirely under `^boolean ^:goog-define re-frame.interop.debug-enabled?`. The instance-token mint, the `*render-key*` binding, and the `:view/render` emission all sit behind that gate — production builds incur zero allocation, zero counter activity, zero binding-frame overhead.

### Loading state is explicit, not implicit

React Suspense lets a component "suspend" while async work runs; the framework shows a fallback; the component renders normally on resolve. Loading state is implicit, sitting inside the suspended-component machinery. **Re-frame2 takes the opposite approach: loading state is explicit data in `app-db`.** [Pattern-RemoteData](Pattern-RemoteData.md)'s `:status :loading` is the canonical place it lives; views read the state and branch on it. The choice is deliberate and worth calling out, because developers arriving from React (and other Suspense-using frameworks) would otherwise expect the implicit form.

#### Why explicit-loading-state wins for re-frame2

The choice falls out of the Single Store invariant and substrate-agnosticism:

1. **Single Store invariant.** Loading state IS state; it lives in `app-db` with everything else. [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility) requires this — a frame is fully revertible only when all its state is in the value.
2. **Substrate-agnostic.** Suspense is React-specific. Re-frame2 supports plain-atom (JVM / headless tests / SSR), Reagent (CLJS), and future substrates per [Spec 006](006-ReactiveSubstrate.md). A Suspense-based approach would couple the pattern to React.
3. **Testability.** Headless tests of "what does this view render when loading?" are straightforward when loading is just data. Suspense requires a React-aware test renderer.
4. **Inspectability.** Tools (10x, Tool-Pair, story tools) introspect `app-db` to see what is loading. Suspense state is not queryable from outside the React tree.
5. **SSR symmetry.** Loading state during SSR is just `app-db` state, loaded synchronously on the server. Suspense streaming-SSR has complex boundary semantics that re-frame2 sidesteps.
6. **AI-amenability.** "Is this view loading?" is a sub query an AI can reason about. Suspense's throws-promise machinery is harder to inspect, harder to mock, harder to scaffold from a construction prompt.
7. **Cross-component coordination.** Sibling views can ask "is this data loaded?" via subs. With Suspense, sibling components don't naturally know about each other's suspended state.
8. **Composes with state machines.** Loading is itself a state — a Pattern-RemoteData status; a state machine governs the transitions. Suspense doesn't compose with state machines; it's a parallel mechanism.

#### Trade-offs to acknowledge

- **Slightly more verbose at the call site.** A view reads the loading state and branches: `(if loading? [Loading-view] [Loaded-view ...])`. With Suspense the view code looks "synchronous"; the Suspense boundary handles the fallback elsewhere.
- **The user must surface loading state in `app-db`.** Pattern-RemoteData provides the slice; users register the four standard events; AI scaffolding (CP-N for remote-data) makes this mechanical.

These costs are small; the wins above justify them.

#### Anti-patterns

- **Hiding loading in component-local React state.** Defeats Single Store and inspectability; tools cannot see what is loading; SSR cannot replay it.
- **Building a Suspense-equivalent in re-frame2** — e.g., events that "throw" until data arrives. Opposite of the spec; collides with run-to-completion drain semantics; collides with state machines.
- **Forgetting to surface loading state at all.** Silent loading; users wait for "done" with no UI feedback; tests cannot assert the loading branch.

#### What this composes with

Pattern-RemoteData's `:status` field is the canonical home for loading state. Pattern-Boot makes the boot phase visible via `:rf/boot {:phase :authenticating}`. The Nine States example renders all loading states exhaustively. State machines naturally have `:loading` as a state with `:on` transitions to `:loaded` / `:error`. All of these compose cleanly because they share the explicit-state approach; a Suspense-based machinery would collide with all of them.

## `reg-view` is the multi-frame contract

`reg-view` is a **defn-shape macro**. It registers a render function under an auto-derived id, defs the symbol you supply to the wrapped (frame-aware) fn, and auto-injects two lexical bindings — `dispatch` and `subscribe` — at every call to the rendered fn.

### Shape

```clojure
(reg-view sym [args] body+)
(reg-view sym docstring [args] body+)
(reg-view ^{:rf/id :explicit/id} sym [args] body+)
```

- **Auto-id derivation.** The registered id is `(keyword (str *ns*) (str sym))` — the same shape Clojure uses for `defn` Vars. Override by attaching `^{:rf/id :explicit/id}` metadata to the symbol.
- **Auto-defs the symbol.** `reg-view` defs `sym` to the wrapped render fn. There is no separate `(def sym (reg-view …))` step. Hiccup heads can be Var references (`[sym args]`) or `(rf/view :id)` results — both resolve to the same wrapped fn.
- **Auto-injects `dispatch` and `subscribe`.** Inside the body, `dispatch` and `subscribe` are lexical bindings, bound at render-time to `(rf/dispatcher)` / `(rf/subscriber)` of the surrounding frame. They pick up the active frame on every render — there is no render-time-binding-vs-callback-time problem; the `:on-click` lambda below closes over the local `dispatch` and carries the frame into the callback automatically.

```clojure
(rf/reg-view counter [label]
  (let [n @(subscribe [:count])]
    [:button {:on-click #(dispatch [:inc])}
     (str label ": " n)]))

;; … and elsewhere in hiccup:
[counter "Hello"]
```

### Compile-time error contract

`reg-view` enforces the defn-shape at macroexpand time. The second argument (after an optional docstring) MUST be the args vector. Anything else throws with a stable error pointing the user at `re-frame.core/reg-view*`:

| Bad call | Why it fails |
|---|---|
| `(reg-view foo my-render)` | `my-render` is a Var reference, not an args vector. |
| `(reg-view foo (reagent.core/create-class {...}))` | Form-3 (a Reagent class component) is a list, not an args vector. Form-3 is out of scope for the macro per the Reagent-v2 directive. |
| `(reg-view foo (some-fn-returning-a-fn))` | A computed body. |

The error message names the kind of value supplied and points at `reg-view*` — the plain-fn surface for runtime registration with computed ids or non-defn-shape bodies.

### `reg-view*` — the plain-fn escape hatch

```clojure
(re-frame.core/reg-view* id render-fn)
(re-frame.core/reg-view* id metadata render-fn)
```

A plain function. No auto-def. No auto-inject. No compile-time check. Use when:
- The id is computed at runtime (dynamic dispatch).
- The render fn is computed (a library that generates views from data).
- The body is Reagent Form-3 (`reagent.core/create-class`) — out of scope for the macro.
- The view is registered without a Var binding (e.g. a `defn` that registers as a side effect).

Inside a `reg-view*` body the user must capture the frame explicitly via `(rf/dispatcher)` / `(rf/subscriber)` if they need frame-bound dispatch — the macro's auto-inject is exactly the convenience `reg-view*` does NOT provide.

The `*` suffix is the standard Clojure idiom for the unsweetened, runtime-callable surface beneath a macro (`let` / `let*`, `fn` / `fn*`). Per [Conventions §`*`-suffix naming](Conventions.md), this convention applies wherever a macro has a fn partner; `reg-view` / `reg-view*` is the only such pair in v1.

Both `reg-view` and `reg-view*` return the registered **id** per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention). For `reg-view`, the macro also defs the supplied symbol as a side effect (per §The canonical form below) — that's an additional behaviour, not a substitute for the return value. Programmatic callers that need both the id and the Var have the id via the return value and the Var via the auto-def.

**Inside a `reg-view` body, the unqualified `dispatch`/`subscribe` always come from the surrounding frame** — the injected closures resolve to whatever frame the surrounding `frame-provider` puts in scope. The underlying contract is explicit-frame addressing (per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part) and OQ-F-12): views can also target a different frame via `(rf/dispatch event {:frame other})` / `(rf/subscribe query {:frame other})` — the qualified two-arg form bypasses the injection. The injected unqualified form is canonical; the explicit form is the escape hatch for cross-frame work (e.g. a story-tool variant that controls a sibling variant).

The injection mechanism is detailed in [002-Frames.md §What `reg-view` injects](002-Frames.md#what-reg-view-injects).

## Calling a registered view

Render trees invoke a registered view by **Var-reference**: `[my-view args]`. This is the native Reagent component-call shape and the only supported render-time form. Keyword vectors at render time are HTML elements (Reagent's existing semantics) — the runtime does **not** intercept `:keyword` vectors and dispatch via the views registry.

The keyword id assigned to a view by `reg-view` (or `reg-view*`) is reserved for **runtime lookup and introspection**: trace events, devtools, error frames, `(rf/view id)` lookups, registry-only views without a Var binding.

This is the family-asymmetry rule applied to views: **render trees use Vars; runtime lookups use ids.** `reg-view` bridges them — auto-defs the symbol AND auto-derives the registry id. See [Conventions §`reg-view` auto-id derivation rule](Conventions.md#reg-view-auto-id-derivation-rule) and [Cross-Spec-Interactions §21](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-has-a-macro-tier).

```clojure
(rf/reg-view counter [label] [:button label])

;; render tree — Var reference (canonical)
[counter "Hello"]

;; runtime lookup — id
(rf/view :my.ns/counter)             ;; → the wrapped (frame-aware) render fn — see §`view` below

;; bare [:my.ns/counter "Hello"] in a render tree is NOT a view call —
;; it renders as a custom HTML element <my.ns:counter>...</my.ns:counter>
;; (or whatever Reagent's hiccup interpretation produces for that tag).
```

An earlier draft included an opt-in `(rf/h ...)` macro that walked hiccup at compile time and rewrote namespaced-keyword heads into runtime view lookups. It has been **dropped from the v1 surface** (rf2-n4um). See [§`h` macro dropped](#h-macro-dropped-rf2-n4um) below — the Var idiom plus the function-position `[(rf/view :id) args]` form cover every previous use case without a compile-time hiccup walker.

## How registered views are used in hiccup

v1 ships three forms for invoking a registered view. To honour the principle of "one obvious way", one of them is **canonical** and the others are **alternatives** with documented use cases.

### The canonical form: `reg-view` auto-defs the symbol

```clojure
(rf/reg-view counter [label] ...)
;; ⇒ defs `counter` in the current namespace, bound to the wrapped
;;    (frame-aware) fn. The id is auto-derived: (keyword *ns* "counter").

;; ... elsewhere in hiccup
[counter "Hello"]
```

`reg-view` *defs* the symbol you supply. There is no separate `(def counter (reg-view …))` step — the macro is the one form that registers + binds. The id is auto-derived from `*ns*` + symbol; override via `^{:rf/id :explicit/id}` metadata on the symbol.

### `view` — the canonical post-registration lookup

Whatever the call-site shape, `(rf/view :counter)` is the **canonical lookup** for a registered view's render-fn. It returns the **wrapped (frame-aware) fn that `reg-view` produced** — *not* the raw user fn the caller wrote. Specifically the wrapper carries the frame-injection, source-coord annotation (per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1)), and the lexical `dispatch` / `subscribe` bindings described in [§Shape](#shape). The lookup is re-resolved on every call so hot-reload re-registration is picked up immediately — calling `(rf/view :counter)` twice after a swap returns the new wrapped fn the second time. The other call-site forms are sugar over `view`.

```clojure
(rf/view :counter)               ;; → wrapped (frame-aware) fn — observably equivalent to the Var bound by reg-view
((rf/view :counter) "label")     ;; identical observably to: [counter "label"]
```

`view` returning `nil` for an unregistered keyword is a normal lookup miss (no error trace).

### Alternative form (use only when the canonical form is awkward)

```clojure
;; Explicit function-position lookup. Use when:
;;        - the view id is computed at runtime (dynamic dispatch);
;;        - the calling code doesn't have access to the Var (e.g., across module boundaries
;;          where the Var isn't exported but the registration is);
;;        - hot-reload semantics matter — `view` re-resolves on every call, so
;;          re-registration is picked up without re-evaluating the call site.
[(rf/view :counter) "Hello"]
```

**Bare `[:counter "Hello"]` in raw hiccup** (where Reagent itself would have to interpret the keyword as a registered view) is **not supported in v1**. It requires modifying or extending Reagent's keyword-tag interpretation, which is deferred to the substrate-decoupling work in Spec 006 / [011](011-SSR.md). It can ship later as a non-breaking addition once the substrate decision is settled.

## Plain Reagent fns: staged adoption (with a loud footgun warning)

Plain Reagent fns (`(defn my-view [args] ...)`) continue to work in re-frame2. They are not registered, so they do not get frame-injection. Their `subscribe`/`dispatch` calls (qualified `rf/`) target `:rf/default`.

This means plain fns are safe in single-frame apps (no different from today) and in default-frame portions of multi-frame apps. But if a plain fn is rendered **inside a non-default `frame-provider`** subtree, its `subscribe`/`dispatch` calls **silently route to `:rf/default`** — almost certainly not what the author intended.

### The footgun is loud, but at most once per (component, non-default-frame) pair

The runtime emits a warning trace event the **first** time a plain Reagent fn renders inside a non-default frame, then suppresses repeats for that pair:

```clojure
{:operation :rf.warning/plain-fn-under-non-default-frame-once
 :op-type   :warning
 :tags      {:fn-name        "my-app.cart.views/render-summary"
             :rendered-under :user-session-7
             :routed-to      :rf/default
             :reason         "Plain Reagent fns do not pick up the surrounding frame; their dispatch/subscribe targets :rf/default. To capture the surrounding frame, register the view via reg-view."}
 :recovery  :warned-and-replaced}
```

Suppression key: the `(component-id, non-default-frame-id)` pair, where `component-id` is the plain fn's stable identity (Var name in CLJS; equivalent fingerprint elsewhere). This deliberately bounds noise: a v1 app that adopts a single non-default frame for one feature gets one warning per plain component that ever renders under that frame, **not** one warning per render and **not** N warnings for N existing components in unrelated parts of the tree (which would amount to a hard footgun). 10x and re-frame-pair surface the warning. In dev, the runtime also `console.warn`s the first occurrence. In production, the warning code path is elided (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).

The suppression cache is per-frame-instance: destroying and re-creating a frame resets the warning history for that frame (so hot-reloaded development sessions don't accumulate stale entries). The `:rf.warning/plain-fn-under-non-default-frame-once` op-type is reserved; consumers branch on it.

### Migration path

For any plain Reagent fn that may render under a non-default frame, replace with `reg-view`:

```clojure
;; before
(defn my-summary [label] ...)

;; after — same body, registered, frame-aware
(rf/reg-view ^{:doc "..."} my-summary [label] ...)
```

Plain fns are allowed indefinitely; the warning is a *quality-of-life* signal, not a deprecation.

A future re-frame2.x or v2 may make `reg-view` mandatory if the ecosystem follows. Not in v1.

### Affordance for plain fns: `(rf/dispatcher)` / `(rf/subscriber)`

For users who want frame awareness in a plain fn without registering it, two render-time helpers:

```clojure
(defn my-plain-view [label]
  (let [d (rf/dispatcher)              ;; reads context now (during render), returns frame-bound fn
        s (rf/subscriber)              ;; ditto for subscribe
        n @(s [:count])]
    [:button {:on-click #(d [:inc])}
     (str label ": " n)]))
```

Same closure mechanic as `reg-view`, just opt-in per-call. Slightly more verbose; useful as an escape hatch.

## Form-1, Form-2, Form-3 components

Form-1 is the **canonical** form. Form-2 and Form-3 exist for Reagent compatibility, but Form-2's outer-fn-side-effects pattern hides a mount-time side-effect that doesn't appear at the call site — Form-1 + an explicit setup event is preferred.

### Form-1 (canonical — simple render fn)

```clojure
(rf/reg-view counter [label]
  [:button (str label)])
```

Each render invocation runs the body fresh. No setup ceremony, no closure subtleties.

For setup-on-mount work that *would* go in a Form-2 outer fn, use a separate event dispatched explicitly:

```clojure
;; preferred over Form-2
(rf/reg-view counter [label]
  [:button {:on-click #(dispatch [:counter/inc])}
   (str label ": " @(subscribe [:count]))])

;; setup happens in :on-create on the frame, or via a dedicated init event:
(rf/reg-frame :counter-frame {:on-create [:counter/initialise]})
```

The setup event is named, registered, queryable — visible. The Form-2 outer-fn pattern hides setup behind a lambda that fires once per mount with no call-site indication.

### Form-2 (closure — supported, prefer Form-1 + explicit setup event)

A view body that yields a fn (Form-2) closes over the outer scope, so the injected `dispatch` / `subscribe` locals are captured by both inner-render invocations and any callbacks created in either form:

```clojure
(rf/reg-view counter-with-init [label]
  (dispatch [:counter/initialise label])           ;; outer fires once on mount — hidden side-effect at call site
  (fn render-counter-with-init [label]             ;; inner render fn, called on each render
    (let [n @(subscribe [:count])]
      [:button {:on-click #(dispatch [:inc])}
       (str label ": " n)])))
```

The `dispatch` and `subscribe` in both the outer body and the inner fn refer to the same lexical bindings — Clojure lexical closure does the right thing.

**Use Form-2 only when the setup work genuinely depends on per-mount props.** For stable setup, use Form-1 + a frame-level `:on-create` event.

### Form-3 (class — out of scope for the macro)

Reagent Form-3 (`reagent.core/create-class`) is **not supported by the `reg-view` macro** in v1. The macro's compile-time check rejects calls whose body is a `(reagent.core/create-class …)` form; the error message points the user at `re-frame.core/reg-view*` — the plain-fn surface, where the body can be any callable.

```clojure
;; instead of (rf/reg-view my-view (reagent.core/create-class …)) — compile error,
;; use:
(re-frame.core/reg-view* :feature/my-view
  (reagent.core/create-class
    {:reagent-render        (fn [] ...)
     :component-did-mount   (fn [] ...)
     :component-will-unmount (fn [] ...)}))
```

The Reagent-v2 directive (rf2-25aq) constrains the canonical surface to Form-1 + Form-2; Form-3 ships through the escape hatch.

## View registry — tooling surface

Registered views live in re-frame's handler registrar (kind `:view`). The public registrar query API ([002-Frames.md §The public registrar query API](002-Frames.md#the-public-registrar-query-api)) provides:

- `(rf/handlers :view)` — map of view-id → metadata (`:doc`, `:ns`/`:line`/`:file`, args info, source).
- `(rf/handler-meta :view :counter)` — single view's metadata.

Tools (10x, story tools, agents) read these to render view inspectors, pick views for stories, generate documentation. Source coords let tools navigate to the view's source.

### Source-coord (rf2-z7f7 / rf2-z9n1)

Every `reg-view` call captures full source coordinates at macro-expansion time. The metadata stamped onto the registry slot includes `:ns` / `:file` / `:line` / `:column`:

- `:ns` and `:file` come from the compile-time `*ns*` / `*file*` (captured by the macro and embedded as literals in the expansion — necessary for the CLJS path, where `cljs.core/*ns*` is nil at runtime).
- `:line` and `:column` come from `(meta &form)`.

The captured tuple is consumed by:

- **Tools** that need to navigate from a view-id to source (re-frame-pair, re-frame-10x, IDE jump-to-source) via `(rf/handler-meta :view id)`.
- **Substrate adapters** that inject the `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` attribute on the rendered root DOM element. Per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) this is **mandatory** for any substrate adapter whose host has a DOM-attribute concept (Reagent, UIx, Helix); CLJS-only and gated on `interop/debug-enabled?` so production builds elide the attribute.
- **JVM SSR** the same way — see [Spec 011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr).

Pair-shaped consumers parse the attribute string as `<ns>:<sym>:<line>:<col>` (segments are `?` when a coord component was not captured — for example, programmatic `reg-view*` registrations that bypassed the macro path). The `<ns>:<sym>` portion mirrors the registry id's namespace + name, so parsing is the inverse of `(keyword ns sym)`.

## Composing registered views

Registered views referenced from hiccup inherit the surrounding frame from React context:

```clojure
(rf/reg-view outer []
  [:div
   [counter "Inner"]                  ;; or [(rf/view :counter) "Inner"] for late-binding by id
   [rf/frame-provider {:frame :other}
    [counter "Other-frame inner"]]])  ;; nested provider re-points
```

Nested `frame-provider`s re-point children. The deepest provider in scope wins.

## Reusable components

Reusable-component concerns are addressed by:

1. **Reusable widgets need to subscribe and dispatch** — `reg-view`'s frame-bound injection.
2. **Reusable widgets need access to surrounding context** (theme, locale, router, frame) — [002's `frame-provider`](002-Frames.md#what-frame-provider-is-cljs-reference) plus user-defined React contexts for non-frame state.

## View antipatterns

Views are pure functions of state to a render-tree. The following are normative prohibitions — call sites that violate them lose the frame-explicit contract that the rest of the spec assumes.

### Views MUST NOT dispatch from their render bodies

A view's render body computes hiccup; it does not advance state. Dispatching during render couples reads to writes and (in Reagent's reactive case) loops the render — the dispatch invalidates a sub the view just deref'd, the view re-renders, dispatches again. Setup work that needs to fire once per mount belongs in a frame-level `:on-create` event ([§Form-1](#form-1-canonical--simple-render-fn)) or, for per-mount setup that genuinely depends on props, in a Form-2 outer fn ([§Form-2](#form-2-closure--supported-prefer-form-1--explicit-setup-event)) — both of which name the dispatch site so the trace stream and tooling see it.

### Views MUST NOT attach native DOM event listeners from render bodies

Hiccup attrs like `:on-click`, `:on-change`, `:on-input` are the **substrate's synthetic-event surface**: the substrate adapter wraps each fn-valued attr at render time so the eventual callback closes over the frame in scope. This is what gives `(dispatch [:inc])` inside an `:on-click` lambda its frame-correctness (per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part) and [006 §Frame-provider via React context](006-ReactiveSubstrate.md#frame-provider-via-react-context)).

Native imperative attach — `(.addEventListener el "click" ...)`, `(js/setTimeout ...)`, raw `requestAnimationFrame` — bypasses that wrapper. The callback fires on a fresh stack with no `*current-frame*` binding and no React-context resolution path; a bare `(rf/dispatch [...])` from inside it **silently routes to `:rf/default`** (per [002 §Dispatches issued from inside a handler body](002-Frames.md#dispatches-issued-from-inside-a-handler-body): "Async callbacks escape the binding. … bare `(rf/dispatch [:child])` from inside the callback falls through to `:rf/default`. This is a fundamental property of dynamic scope — not a bug.").

The right shapes:

- **For DOM events the substrate already wraps** (`click`, `change`, `keydown`, drag/drop, pointer/touch, focus/blur), attach via the hiccup attr surface. The adapter handles the framing for you.
- **For host primitives the substrate does not wrap** (`setTimeout`, `Promise.then`, `fetch`, `requestAnimationFrame`, WebSocket / SSE listeners, `IntersectionObserver`, `MutationObserver`, `ResizeObserver`, `animationend` / `transitionend` listeners attached to specific elements), model the work as a **registered fx** (per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)). The fx captures the frame in a closure at registration ([002 §Async fx capture the frame in a closure](002-Frames.md)); the per-tick / per-event reply is a registered event the fx dispatches with `{:frame frame-id}` already resolved.

### Views MUST NOT own imperative library lifecycles directly

When a view wraps a stateful JS library that owns its own DOM subtree (D3 charts, Mapbox, CodeMirror, Three.js, Framer Motion, React-Spring, GSAP, AutoAnimate), the imperative attach / detach belongs in the substrate's Form-3-equivalent lifecycle hook — **not** in the render body. Per-adapter spelling:

| Adapter | Escape-hatch surface |
|---|---|
| Reagent / Reagent-slim | `reagent.core/create-class` Form-3 via `reg-view*` ([§Form-3](#form-3-class--out-of-scope-for-the-macro)) |
| UIx | `uix.core/use-effect` inside a `defui` |
| Helix | `helix.hooks/use-effect` inside a `defnc` |

The lifecycle hook runs after commit; cleanup is mandatory (the returned teardown fn). Inside the hook, the dispatcher closure was already built during render — calls to `(rf/dispatcher)` / `(rf/subscriber)` from the hook body close over `*current-frame*` at mount time and carry the frame correctly through any subsequent imperative callbacks the library registers.

## Animations

Animation is a view-layer concern, but views are derivative — they compute hiccup from state. The portable principle: **state is the truth; the view animates the transition; animation completion is silent unless explicitly modelled in state via fx-paced timing.** Three regimes cover the space; the regime is chosen by what the state actually needs to know.

### Regime A — Transition animations (the 95% case)

State changes; the view re-renders with a different `:class` or `:style`; CSS (or the substrate's animation engine) completes the visual transition silently. **No completion dispatch is needed** — by the time the animation kicks off, `app-db` has already moved on; the visual is catching up.

Examples: opacity fades, slide-in / slide-out, accordion expand, list reorder, button press-down, modal scrim, route transitions. The state-shaped truth is "this card is :open"; the view renders `{:class (when open? "open")}`; CSS `transition` interpolates the property.

Sequencing belongs in CSS (`animation-delay`, `:nth-child` selectors, parallel keyframes) or in a small `:dispatch-later` chain that advances a `:phase` key in state at known intervals. Either way, the visual catches up to state — the view never blocks on `transitionend`.

### Regime B — Continuous animations (RAF loops, physics, games)

Per-frame state mutation IS the truth. The right shape is a registered `reg-fx` (e.g. `:ui/raf-loop`) that owns the `requestAnimationFrame` cycle and dispatches a per-frame event carrying delta-time. The fx captures the frame at registration (per [Pattern-AsyncEffect](Pattern-AsyncEffect.md) + [002 §Async fx capture the frame in a closure](002-Frames.md)); the event handler updates state; the view renders the new state. Cancellation is a sibling fx that cancels the RAF handle.

This is Pattern-AsyncEffect with `requestAnimationFrame` substituted for HTTP — same six-step shape, same frame-capture discipline, same `:dispatch` reply contract. Particle systems, infinite-scroll inertia, physics simulations, game loops all fit.

### Regime C — Library-bridged animations (Framer Motion, React-Spring, GSAP, AutoAnimate)

The animation library is component-shaped — it owns its own imperative timing inside its own component tree. The wrapping pattern is the **outer/inner split**: the outer is a registered view that reads subs and produces props; the inner is a Form-3 / `use-effect` wrapper that hands the library the state-derived props. The view layer never imperatively dispatches; the library's internal callbacks (e.g. Framer Motion's `onAnimationComplete`) are bridged at the inner boundary using the same lifecycle-hook discipline as [§Views MUST NOT own imperative library lifecycles directly](#views-must-not-own-imperative-library-lifecycles-directly).

This regime subsumes into the general outer/inner pattern for wrapping stateful JS components (D3, Mapbox, CodeMirror also fit) — animation libraries are not a special case.

### Choosing a regime

Most animations are Regime A. Reach for B only when the state genuinely advances per-frame (games, physics, scroll-momentum). Reach for C only when a third-party library owns the timing. Genuine "completion-sensing" cases — "the state must wait for an exact `animationend`" — are rare, and usually signal that Regime A's "state is truth, visual catches up" approach was not fully exploited. When the case is genuine, the lifecycle hook (Form-3 / `use-effect`) is the escape hatch: it attaches `addEventListener "animationend"` after commit, cleans up on unmount, and carries the frame correctly because the dispatcher closure was built during render.

## Open questions

The bare `[:my-view "args"]` form in raw hiccup requires Reagent extension and is out of scope for v1; it is deferred to the substrate-decoupling work in [Spec 006](006-ReactiveSubstrate.md).

## Resolved decisions

### `reg-view` defs the Var by default

`reg-view` is defn-shape: the symbol you supply IS the Var name; the id is auto-derived from `(keyword *ns* sym)`. No need to write `(def x (rf/reg-view :x ...))` — `(rf/reg-view x [args] body)` is enough; a Var named `x` is created in the surrounding namespace and registered under `(keyword *ns* "x")`.

```clojure
(rf/reg-view counter [label] [:button label])
;; ⇒ defs `counter` in the current namespace, bound to the wrapped fn.
;; ⇒ registers under (keyword *ns* "counter").
;; You can now write [counter "Hi"] in hiccup directly.

(rf/reg-view ^{:rf/id :cart.item/row} item-row [item] [:tr ...])
;; ⇒ defs `item-row`; registers under the explicit override id :cart.item/row.
;; Use the qualified namespace prefix when reading: [cart.item.row item]
```

This matches `defn`'s shape (registers + binds a Var) and removes a redundant naming step. Other registration kinds don't need this because their values aren't called as Reagent components in hiccup — only views are.

For the rare case where the user wants no Var binding (e.g. views generated programmatically, or registered without a Var by a library), use `re-frame.core/reg-view*` directly — the plain-fn surface registers under the supplied id without defing anything.

```clojure
;; Programmatic registration — no Var, computed id.
(doseq [variant [:summary :detail]]
  (re-frame.core/reg-view*
    (keyword "feature/article" (name variant))
    (fn [_props] ...)))
```

### `h` macro dropped (rf2-n4um)

An earlier draft of this spec included an `h` macro that walked hiccup at compile time and rewrote namespaced-keyword heads (`[:my-app/widget args]`) into runtime `(rf/view :my-app/widget)` lookups. It has been removed from the v1 surface. The Var idiom — `(reg-view counter [...] ...)` defs `counter`; users write `[counter "Hello"]` — is the canonical call-site form. For late-binding by id (cross-module reference, runtime-computed ids, hot-reload-sensitive call sites) the canonical handle is the explicit function-position form `[(rf/view :counter) "Hello"]`. Two surfaces, both data-friendly, no compile-time hiccup walker to learn or reason about.

### Form-3 (`reagent.core/create-class`) — out of scope for the macro

The `reg-view` macro rejects bodies whose top-level form is `(reagent.core/create-class …)` — Form-3 is the canonical escape-hatch case. Per the Reagent-v2 directive (rf2-25aq) the canonical surface targets Form-1 + Form-2; Form-3 ships through `re-frame.core/reg-view*`:

```clojure
(re-frame.core/reg-view* :feature/widget
  (reagent.core/create-class
    {:reagent-render        (fn [props] ...)
     :component-did-mount   (fn [this] ...)
     :component-will-unmount (fn [this] ...)}))
```

Inside a Form-3 body, the user captures frame-bound dispatch/subscribe explicitly via `(rf/dispatcher)` / `(rf/subscriber)` if needed — the macro's auto-inject is exactly the convenience `reg-view*` does NOT provide.

### Hot-reload behaviour for re-registered views

Re-registering a view replaces the registry entry; `(view :id)` re-resolves on every render, so mounted components pick up the new render fn on next render with no further action. Reagent's component cache keys on the wrapper fn the macro emits; because that wrapper delegates to `(view view-id)` on each call, the cache hit returns the *new* render fn body once the registry is updated. No explicit invalidation needed.

The runtime emits `:rf.registry/handler-replaced` (per [009](009-Instrumentation.md)) when re-registration overwrites an existing view; tools branch on the trace event to refresh their UI.
