# Frames

## When to load

Working with multi-frame apps: registering a non-default frame, targeting a dispatch / subscribe at a specific frame, using `frame-provider` to scope a React subtree, or carrying the current frame into an async callback via `frame-handle` / `frame-bound-fn`.

## The teaching model: choose by intent

Most apps never touch any of this — a single-frame app just calls `rf/dispatch` / `rf/subscribe` and the framework resolves `:rf/default`. Reach for a frame affordance only when one of these intents applies:

| Intent | Affordance |
|---|---|
| Single frame (the default) | `dispatch` / `dispatch-sync` / `subscribe` — no frame talk at all |
| Pin a lexical scope to an existing frame | `with-frame` |
| Create + own + destroy a frame for a scope (tests / SSR) | `with-new-frame` |
| Scope a React subtree to a frame | `frame-provider` |
| Hold a frame's ops as a value (async / closures) | `frame-handle` (common); `frame-bound-fn` / `frame-bound-fn*` (advanced) |
| One-off explicit routing | `{:frame …}` opt on `dispatch` / `subscribe` |
| Read a frame's app-db / its id | `app-db-value` / `current-frame-id` |

A `reg-view` body needs none of these for ordinary dispatch / subscribe — the macro injects frame-aware `dispatch` and `subscribe` locals automatically. A plain (non-`reg-view`) Reagent / UIx / Helix fn that needs to dispatch asks for `(rf/frame-handle)`. An arbitrary async callback uses `frame-bound-fn`.

## What a frame is

A **frame** is an isolated runtime boundary: its own `app-db`, its own router queue, its own sub-cache. Frames are identified by keywords. Every re-frame2 app has at least one — `:rf/default` — registered automatically on `init!`.

Frames are mutable runtime objects, not values. User code holds keywords and lets the framework resolve them.

## Canonical signatures

```clojure
;; Register / re-register a named frame.
(rf/reg-frame :frame-id metadata)

;; Anonymous instance (gensym'd id under :rf.frame/*). Returns the id.
(rf/make-frame metadata)

;; Destroy / reset.
(rf/destroy-frame! :frame-id)
(rf/reset-frame!   :frame-id)        ;; destroy + re-register with same config

;; Inspect.
(rf/current-frame-id)               ;; returns the active frame id
(rf/app-db-value :frame-id)             ;; current app-db VALUE (plain map, no deref)
```

Verified in `re-frame.frame` (`reg-frame`, `make-frame`, `destroy-frame!`). The public macro layer is in `re-frame.core`.

## Frame resolution chain

Three tiers (the frame-resolution chain in `re-frame.frame` / `re-frame.core`):

1. **`*current-frame*` dynamic var** — bound by `with-frame`.
2. **React context** (CLJS only) — read via the `:adapter/current-frame` late-bind hook, populated by the installed adapter under a `frame-provider`.
3. **`:rf/default`** — fallback when neither of the above applies.

`dispatch` and `subscribe` default `:frame` to `(rf/current-frame-id)`. To target an explicit frame, use the `{:frame …}` opt — the first-class explicit-routing surface (tools, tests, SSR, fx handlers), not a workaround:

```clojure
(rf/dispatch  [:foo]      {:frame :stories})
(rf/subscribe [:my-sub])                          ;; uses current-frame-id
```

## Carrying the frame into async callbacks

When you `setTimeout` or hand a callback to a promise, the ambient frame binding (dynamic var → React context) is gone by the time it runs. The keystone affordance is **`frame-handle`** — a per-frame OPERATION BUNDLE captured at creation time. Its ops always target the captured frame and survive async boundaries:

```clojure
(let [{:keys [dispatch]} (rf/frame-handle)]        ;; captures the ambient frame now
  (.then promise #(dispatch [:result-arrived %])))
```

`(rf/frame-handle)` captures `(current-frame-id)`; `(rf/frame-handle :frame-id)` locks to an explicit id. It returns `{:frame :dispatch :dispatch-sync :subscribe}`. The handle is an OPERATION BUNDLE, not a container — read the frame's app-db value via `(rf/app-db-value (:frame handle))`, never off the handle. A per-call `:frame` opt cannot override the captured frame; the handle is locked to one frame.

For an arbitrary callback body (not just dispatch / subscribe), wrap it so `*current-frame*` is re-established inside:

```clojure
;; macro: fn-syntax sugar
(.then promise (rf/frame-bound-fn [result] (rf/dispatch [:result-arrived result])))

;; *-twin: wrap an existing fn value (HoF / programmatic)
(.then promise (rf/frame-bound-fn* on-result))
```

`frame-bound-fn*` takes `(f)` (capture `current-frame-id` at wrap time) or `(frame-id f)` (explicit). Prefer `frame-handle` for the common dispatch / subscribe case; reach for `frame-bound-fn` / `frame-bound-fn*` when the callback body itself needs the ambient binding (e.g. it calls `current-frame-id` or nested registrations).

## Canonical mini-example

Per-test isolated frame, from `examples/reagent/login/core.cljs`:

```clojure
(with-new-frame [f (rf/make-frame
                 {:fx-overrides {:rf.http/managed :auth.login/test-canned-success}})]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                       {:email "user@example.com"
                                        :password "correct-horse"}]]
                    {:frame f})
  (assert (= :authed (rf/compute-sub [:auth.login/state] (rf/app-db-value f)))))
```

Each test gets its own frame with its own app-db and its own fx-override map — concurrent tests can run with no cross-contamination.

And configuring `:rf/default` at app boot:

```clojure
;; User-defined fxs sit under a user-feature prefix per
;; spec/Conventions.md §Reserved namespaces — never under `:rf.<feature>/…`,
;; which is reserved for framework-owned surfaces.
(rf/reg-frame :rf/default
  {:doc          "Login demo frame."
   :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})
```

## Frame metadata — what goes in it

The metadata map (`reg-frame` in `re-frame.frame`) accepts:

- `:doc` — one-sentence what-and-why.
- `:preset` — one of `:default :test :story :ssr-server`; expands at registration into a fixed metadata bundle.
- `:fx-overrides` — `{original-id replacement-id-or-fn}`. Two active override forms (resolved by `fx/resolve-fx-with-overrides`): a **keyword** redirects the lookup to another registered fx (portable, SSR-safe pattern-level form), and a **function** `(fn [m args] ...)` runs inline with no registry lookup (one-shot CLJS-reference convenience for test fixtures and story decorators). A value that is neither (and an absent key) is treated as no override — the original fx-id flows through. The id-redirect form is preferred when the stub is reused; the fn form when one test wants a bespoke response without registering a parallel fx. Per-call `:fx-overrides` in `dispatch` / `dispatch-sync` opts accepts the same forms.
- `:platform` — `:client` or `:server`; gates fx whose `:platforms` set excludes the active platform.
- `:drain-depth` — bound on dispatch-cascade depth (default 100; `:story` preset tightens to 16).
- `:on-create` / `:on-destroy` — event vectors fired synchronously at lifecycle transitions.

User-supplied keys win on conflict with preset expansion.

## `frame-provider` in views

Wraps a Reagent / Helix / UIx subtree so descendants resolve `current-frame-id` to a chosen id:

```clojure
[rf/frame-provider {:frame :stories} [my-story-shell]]
```

`reg-view`-wrapped components participate automatically (the wrapper carries `:contextType`). Plain Reagent fns under a non-default `frame-provider` fall through to `:rf/default` — the runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` to flag the footgun (the Reagent adapter's view-wiring; behaviour locked by `cross_spec_dom_cljs_test`).

## Common gotchas

- **`reg-frame` is atomic and hot-reload safe.** First call creates and runs `:on-create`; subsequent calls perform a **surgical update** of metadata only — existing app-db, sub-cache, queue, machine snapshots all preserved (`reg-frame` in `re-frame.frame`). Use `reset-frame!` for a full destroy+recreate.
- **`destroy-frame!` cascades.** Per active machine snapshot, the runtime emits *one* `:rf.machine.lifecycle/destroyed` trace carrying `:reason :parent-frame-destroyed` under `:tags` (the unified lifecycle channel — same op-type used at `reg-machine`'s `:created` emit); in-flight HTTP requests get an abort hook; sub-cache reactions all dispose. Subsequent dispatch / subscribe raises `:rf.error/frame-destroyed`. See [009 §`:op-type` vocabulary](../../../../spec/009-Instrumentation.md#op-type-vocabulary).
- **`with-frame` works on both CLJS and the JVM.** The `re-frame.core/with-frame` macro expands on both platforms — use it from JVM tests / SSR / REPL as well as CLJS. For programmatic frame-pinning where a macro is awkward, bind the current-frame dynamic var directly (see the frame-resolution chain above).
- **Wrapping plain Reagent fns in a non-default `frame-provider` doesn't bind the frame.** Use `reg-view` so the `:contextType` wiring picks up the provider. Watch for the once-per-handler warning.
- **`:rf/default` is implicit.** Don't re-`reg-frame :rf/default` unless you specifically want to attach metadata to it — calling it without any is a no-op.

## Deeper material

Frame presets in detail, machine-instance teardown contract, the React-context chain through Reagent / Helix / UIx, `dispatch-to-system`: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — State machines (005)**.

---

*Derived from `re-frame.frame`, `re-frame.core`, and `re-frame.adapter.reagent` @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after frame-resolution or adapter-late-bind changes.*
