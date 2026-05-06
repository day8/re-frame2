# EP 004 — Views

> Status: Drafting. **v1-required.** `reg-view` is the boundary where re-frame inserts frame awareness; without it, views inside non-default `frame-provider` subtrees can't see their frame. EP 004 was originally a Placeholder (a nice-to-have for tooling and hiccup-as-data); the multi-frame goal in [002-Frames.md](002-Frames.md) lifts it to load-bearing v1.
>
> **Reframed per [reorient.md](reorient.md):** the **pattern-level view contract is `(state, props) → render-tree`** — pure, with the render-tree as a serialisable data structure. The `reg-view`-with-lexical-injection design below is the CLJS reference implementation of that contract. EP 011 (SSR & Hydration) requires that views can render on the server without a React runtime, which forces explicit-frame addressing as the underlying contract. Hiccup is the CLJS render-tree shape; other implementations choose differently. A subsequent SSR audit pass will surface what is server-renderable, what is pure render-time input, and what requires a client-only runtime.

## Abstract

### Pattern-level view contract

A view is a **pure function `(state, props) → render-tree`**. Three commitments:

1. **Pure.** No render-time side-effects, no implicit reads from ambient state beyond what's declared in inputs.
2. **Frame-explicit.** The view targets a specific frame; that frame is part of the render-time inputs (via parameter, closure, or implementation-specific injection that resolves to the same observable).
3. **Render-tree is serialisable data.** The output is a nested data structure (hiccup, JSX-as-data, virtual-DOM nodes — implementation choice) that the runtime can render to a string for SSR (per [011](011-SSR.md)) or to whatever client-side substrate the implementation uses.

These are pattern-level commitments; they hold across CLJS, TS, Python, etc.

### CLJS reference: `reg-view`

In the CLJS reference, a **registered view** is a render fn associated with a keyword via `reg-view`. Registration:

- Captures source coords and `:doc`/`:spec` metadata for tooling.
- Wraps the fn so that, on each render, frame-bound `dispatch` and `subscribe` are injected as lexical locals — the body reads exactly like today's re-frame.
- Returns the wrapped fn so the user can `def` it as a Var if they want Reagent-style hiccup invocation.

The lexical-injection style is the CLJS *realisation* of the explicit-frame contract: instead of threading the frame as a parameter, the macro injects it as a closed-over local resolved from React context. Other-language implementations would resolve the same contract differently (a hooks-flavoured `useFrame()`, a function argument, dependency injection — see [002-Frames.md §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part)).

Registered views can be invoked in hiccup three ways. Plain (unregistered) Reagent functions continue to work in v1 with a documented frame-routing limitation; `reg-view` is the staged-adoption path for multi-frame correctness.

The frame-routing mechanics that `reg-view` consumes (React-context resolution, `frame-provider`, `bound-fn`/`bound-dispatcher` for callbacks crossing the render→callback boundary) live in [002-Frames.md §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part). EP 004 owns the view-side API surface; 002 owns the frame-side mechanics.

### What is server-renderable, what is client-only

Per [011](011-SSR.md):

- **Server-renderable:** the view function itself (pure `(state, props) → render-tree`); the render-tree as a string (pure hiccup → string is JVM-runnable); the subscription computation (pure `state → value`); machine transitions (pure).
- **Pure render-time input:** the frame's `app-db` value; the frame id; the props passed to the view.
- **Client-only:** the React mount/commit lifecycle; reactive sub *tracking* (auto-subscribe-on-deref); the React-context-driven `frame-provider` machinery; DOM-mutation effects; browser APIs (clipboard, IndexedDB, etc.).

The view function's *body* — including `subscribe` calls that yield values, and `dispatch` calls that close over the frame — runs on the server given a frame and an `app-db`. The reactive *tracking* of subs (auto-rerender on change) is a client concern. SSR computes subs against a static `app-db`; hydration on the client wires up the reactive tracking.

## `reg-view` is the multi-frame contract

`reg-view` registers a render function under a keyword. Inside the registered view's body, `dispatch` and `subscribe` are **frame-bound locals** injected by `reg-view` (the implicit-lexical-injection style):

```clojure
(rf/reg-view :counter
  {:doc "Counter widget."}
  (fn [label]
    (let [n @(subscribe [:count])]
      [:button {:on-click #(dispatch [:inc])}
       (str label ": " n)])))
```

Inside the body:

- `subscribe` and `dispatch` are **lexically bound** to closures that know the surrounding frame.
- The `:on-click` lambda closes over the local `dispatch` — it carries the frame into the callback automatically. No render-time-binding-vs-callback-time problem.
- Code reads identically to today's re-frame view (per G1 — no cultural shift).

**A view always obtains its frame from React context.** The injected `dispatch`/`subscribe` resolve to whatever frame the surrounding `frame-provider` puts in scope; views never target a frame explicitly. Qualified `re-frame.core/dispatch` exists for use *outside* views (REPL, event-handler effects, plain non-rendering helpers) where there is no React context to read.

The injection mechanism is detailed in [002-Frames.md §What `reg-view` injects](002-Frames.md#what-reg-view-injects).

## Three injected names

Inside the body, the macro injects three names:

- `dispatch` — frame-bound `(fn [event] ...)` building an envelope tagged with the surrounding frame's id.
- `subscribe` — frame-bound `(fn [query-v] ...)` consulting the surrounding frame's sub-cache.
- `frame-id` — the keyword itself, useful for debugging, logging, or passing to non-frame-aware children.

## How registered views are used in hiccup

> **Per the [AI-First Audit](AI-Audit.md) (G-E):** v1 ships three forms for invoking a registered view. To honour P1 (one obvious way), one of them is **canonical** and the others are **alternatives** with documented use cases.

### The canonical form: Var reference

```clojure
(def counter (rf/reg-view :counter {…} (fn [label] ...)))

;; ... elsewhere in hiccup
[counter "Hello"]
```

`reg-view` returns the wrapped (frame-aware) render fn. The user `def`s a Var from that return value and uses it Reagent-style in hiccup. This is the canonical form because:

- It reads exactly like today's Reagent code — no cultural shift (Goal 11).
- The Var is a stable, named binding — anonymous closures are avoided (P2).
- It works without macro magic at the call site (P3 / P8 — no hidden expansion).
- IDE / static analysis tooling treats it as a normal Var.

### Alternative forms (use only when the canonical form is awkward)

```clojure
;; Alt 1 — Explicit function-position lookup. Use when:
;;        - the view id is computed at runtime (dynamic dispatch);
;;        - the calling code doesn't have access to the Var (e.g., across module boundaries
;;          where the Var isn't exported but the registration is).
[(rf/get-view :counter) "Hello"]

;; Alt 2 — h macro. Walks hiccup at compile time, substitutes registered keywords.
;;        Use when:
;;        - you want hiccup to read as pure data (highest data-orientation, P3);
;;        - the view set is known at compile time;
;;        - you don't mind opting into the h wrapper at every call site.
(rf/h [:counter "Hello"])
```

**Bare `[:counter "Hello"]` in raw hiccup** (without an `h` wrapper, where Reagent itself would have to interpret the keyword as a registered view) is **not supported in v1**. It requires modifying or extending Reagent's keyword-tag interpretation, which is deferred to the substrate-decoupling work in EP 006 / [011](011-SSR.md). It can ship later as a non-breaking addition once the substrate decision is settled.

**Construction-prompt guidance ([CP-4](Construction-Prompts.md)):** AI-generated views default to the Var-reference form. The other two are escape hatches for specific situations.

### The `h` macro

`(rf/h hiccup-form)` walks the hiccup at compile time, substituting `[:registered-keyword args ...]` with `[(rf/get-view :registered-keyword) args ...]` for every keyword present in the view registry at expansion time. Unregistered keywords pass through unchanged (so DOM tags like `:div` are untouched).

```clojure
;; user writes
(rf/h
  [:div.page
   [:counter "Left"]
   [:counter "Right"]
   [:label "Hello"]])              ;; :label is a DOM tag; passes through

;; expands to
[:div.page
 [(rf/get-view :counter) "Left"]
 [(rf/get-view :counter) "Right"]
 [:label "Hello"]]
```

Compile-time expansion means the registry must be populated when the macro fires — typically at REPL or build time, after `reg-view` calls have run. For dynamic dispatch (where the keyword isn't known at compile time), use form (1) directly.

`h` is opt-in: hiccup outside `(rf/h ...)` retains today's Reagent semantics (DOM tags + Var references + anonymous fns).

## Plain Reagent fns: staged adoption (with a loud footgun warning)

Plain Reagent fns (`(defn my-view [args] ...)`) continue to work in re-frame2. They are not registered, so they do not get frame-injection. Their `subscribe`/`dispatch` calls (qualified `rf/`) target `:re-frame/default`.

This means plain fns are safe in single-frame apps (no different from today) and in default-frame portions of multi-frame apps. But if a plain fn is rendered **inside a non-default `frame-provider`** subtree, its `subscribe`/`dispatch` calls **silently route to `:re-frame/default`** — almost certainly not what the author intended.

### The footgun is now loud (per [AI-First Audit](AI-Audit.md) G-D)

Previously documented as a known limitation. Now: **the runtime emits a warning trace event the first time a plain Reagent fn renders inside a non-default frame**:

```clojure
{:operation :rf.warning/plain-fn-under-non-default-frame
 :op-type   :warning
 :tags      {:fn-name        "my-app.cart.views/render-summary"
             :rendered-under :user-session-7
             :routed-to      :re-frame/default
             :reason         "Plain Reagent fns do not pick up the surrounding frame; their dispatch/subscribe targets :re-frame/default. To capture the surrounding frame, register the view via reg-view."}
 :recovery  :warned-and-replaced}
```

The warning fires once per `(plain-fn, frame)` pair (not per render — that would flood). 10x and re-frame-pair surface the warning. In dev, the runtime also `console.warn`s the first occurrence. In production, the warning code path is elided (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).

### Migration path

For any plain Reagent fn that may render under a non-default frame, replace with `reg-view`:

```clojure
;; before
(defn my-summary [label] ...)

;; after — same body, registered, frame-aware
(def my-summary (rf/reg-view :feature/summary {:doc "..."} (fn [label] ...)))
```

The CLJS reference's `re-frame.core-legacy` namespace continues to allow plain fns indefinitely; the warning is a *quality-of-life* signal, not a deprecation.

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

> **Per [AI-First Audit](AI-Audit.md) (G-F):** Form-1 is the **canonical** form for AI-first scaffolded views. Form-2 and Form-3 exist for Reagent compatibility but the audit flags Form-2's outer-fn-side-effects pattern as a P8 (low hidden context) hit — a side-effect at mount time that doesn't appear at the call site. AI-generated views default to Form-1 + an explicit setup event.

### Form-1 (canonical — simple render fn)

```clojure
(rf/reg-view :counter
  (fn render-counter [label]
    [:button (str label)]))
```

Each render invocation runs the body fresh. No setup ceremony, no closure subtleties. **This is what construction-prompt-generated views look like.**

For setup-on-mount work that *would* go in a Form-2 outer fn, use a separate event dispatched explicitly:

```clojure
;; preferred over Form-2
(rf/reg-view :counter
  (fn render-counter [label]
    [:button {:on-click #(dispatch [:counter/inc])}
     (str label ": " @(subscribe [:count]))]))

;; setup happens in :on-create on the frame, or via a dedicated init event:
(rf/reg-frame :counter-frame {:on-create [:counter/initialise]})
```

The setup event is named, registered, queryable — visible. The Form-2 outer-fn pattern hides setup behind a lambda that fires once per mount with no call-site indication.

### Form-2 (closure — supported, prefer Form-1 + explicit setup event)

A view returning a fn (Form-2) closes over the outer scope, so the injected locals are captured by both inner-render invocations and any callbacks created in either form:

```clojure
(rf/reg-view :counter-with-init
  (fn outer-counter-with-init [label]
    (dispatch [:counter/initialise label])           ;; outer fires once on mount — hidden side-effect at call site
    (fn render-counter-with-init [label]              ;; render fn, called on each render
      (let [n @(subscribe [:count])]
        [:button {:on-click #(dispatch [:inc])}
         (str label ": " n)]))))
```

The `dispatch` and `subscribe` in both the outer and inner fn refer to the same locals — Clojure lexical closure does the right thing.

**Use Form-2 only when the setup work genuinely depends on per-mount props.** For stable setup, use Form-1 + a frame-level `:on-create` event.

### Form-3 (class — supported, rare)

Class-form views that return a map of lifecycle methods (`:reagent-render`, `:component-did-mount`, etc.) — supported, but the injected locals are only in scope for the lifecycle methods themselves, not for any user code outside the registered fn. Consistent treatment with Form-2; the macro injects the `let` once around the entire returned map's body.

Required only when interop with stateful third-party React components needs explicit lifecycle hooks (`componentDidMount`, `componentWillUnmount`). Construction-prompt-generated views never use Form-3 by default.

## View registry — tooling surface

Registered views live in re-frame's handler registrar (kind `:view`). The public registrar query API ([002-Frames.md §The public registrar query API](002-Frames.md#the-public-registrar-query-api)) provides:

- `(rf/handlers :view)` — map of view-id → metadata (`:doc`, `:ns`/`:line`/`:file`, args info, source).
- `(rf/handler-meta :view :counter)` — single view's metadata.

Tools (10x, story tools, agents) read these to render view inspectors, pick views for stories, generate documentation. Source coords let tools navigate to the view's source.

## Composing registered views

Registered views referenced from hiccup inherit the surrounding frame from React context:

```clojure
(rf/reg-view :outer
  (fn []
    [:div
     [counter "Inner"]                  ;; or [:counter "Inner"] under EP 004's `h` macro
     [rf/frame-provider {:frame :other}
      [counter "Other-frame inner"]]])) ;; nested provider re-points
```

Nested `frame-provider`s re-point children. The deepest provider in scope wins.

## EP 003 (Reusable Components) subsumption

The original EP 003 wanted React-context-style sharing for reusable components. Two concerns motivated it:

1. **Reusable widgets need to subscribe and dispatch** — solved by `reg-view`'s frame-bound injection.
2. **Reusable widgets need access to surrounding context** (theme, locale, router, frame) — solved by [002's `frame-provider`](002-Frames.md#what-frame-provider-is) plus user-defined React contexts for non-frame state.

EP 003 is therefore subsumed by EP 002 + EP 004; no separate doc planned.

## Open questions

### V-1. Should `reg-view` def the Var by default?

Currently `reg-view` returns the wrapped fn, and the user opts into Var-style hiccup by writing `(def counter (rf/reg-view ...))`. Alternative: `reg-view` is a macro that defs the Var as a side effect, the way `defn` does. Trade-off: less ceremony for users who want Var-style; more magic; potential conflict with namespace-level `def` discipline. Currently leaning toward "user opts in explicitly" for explicitness; revisit if the ecosystem prefers the auto-def form.

### V-2. The `h` macro's expansion strategy

`h` walks hiccup at compile time. Two questions: (a) does it walk recursively into nested vectors, or only the top level? (b) does it look up keywords against the *current* registry at expansion time, or use a deferred runtime lookup? Recommend recursive walk + compile-time lookup for the common case (deterministic; faster) with a runtime escape hatch for dynamic cases.

### V-3. Form-3 (`reagent.core/create-class`) — full lifecycle exposure

Supported, but the locked design says injected locals are scoped to the entire returned-map's body. Whether all lifecycle methods (`:component-did-mount`, `:component-did-update`, `:component-will-unmount`, etc.) see the injected `dispatch`/`subscribe` consistently — including across Reagent's various class-creation paths (legacy `r/create-class` vs newer hooks-flavoured) — needs an implementation walk-through before locking.

### V-4. Hot-reload behaviour for re-registered views

When `reg-view` is re-evaluated against the same keyword (developer saved a file), the new render fn replaces the old. Mounted components automatically pick up the new fn on next render via the registry indirection (`get-view`). Confirm: does this work cleanly with Reagent's component cache? Probably yes (Reagent re-creates components on render); empirical confirmation needed.

### V-5. `h` macro and dynamic keyword tags

`(rf/h [first-tag-var label])` where `first-tag-var` is a runtime-resolved keyword — does the macro fall through gracefully (treating the keyword as a DOM tag) or error? Recommendation: fall through; runtime substitution is the user's responsibility.

## Disposition

**v1.** `reg-view` ships in v1 as the multi-frame contract. The three hiccup invocation forms ship in v1. The `h` macro ships in v1 as opt-in sugar. Plain Reagent fns continue to work; staged adoption.

The bare `[:my-view "args"]` form in raw hiccup is deferred to OQ-7 (substrate decoupling work) — it requires Reagent extension and is out of scope for v1.
