# Frames

## When to load

Working with multi-frame apps: registering a non-default frame, targeting a dispatch / subscribe at a specific frame, using `frame-provider` to scope a React subtree, or capturing the current frame in an async callback via `bound-dispatcher`.

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
(rf/destroy-frame :frame-id)
(rf/reset-frame   :frame-id)        ;; destroy + re-register with same config

;; Inspect.
(rf/current-frame)                  ;; returns the active frame id
(rf/get-frame-db :frame-id)         ;; underlying container (for tools / tests)
```

Verified in `implementation/core/src/re_frame/frame.cljc:146` (`reg-frame`), `:185` (`make-frame`), `:195` (`destroy-frame!`). The public macro layer is `core.cljc:287-304`.

## Frame resolution chain

Three tiers (`frame.cljc:38-51`, `core.cljc:899-909`):

1. **`*current-frame*` dynamic var** — bound by `with-frame`.
2. **React context** (CLJS only) — read via the `:adapter/current-frame` late-bind hook, populated by the installed adapter under a `frame-provider`.
3. **`:rf/default`** — fallback when neither of the above applies.

`dispatch` and `subscribe` default `:frame` to `(rf/current-frame)`. To target an explicit frame:

```clojure
(rf/dispatch  [:foo]      {:frame :stories})
(rf/subscribe [:my-sub])                          ;; uses current-frame
```

## Capturing the frame in async callbacks

When you `setTimeout` or hand a callback to a promise, the dynamic var binding is gone by the time it runs. Capture the frame at call time with `bound-dispatcher` / `bound-subscriber`:

```clojure
(let [d (rf/bound-dispatcher)]
  (.then promise #(d [:result-arrived %])))
```

`dispatcher` and `bound-dispatcher` are aliases (`core.cljc:940-945`); use whichever name reads better.

## Canonical mini-example

Per-test isolated frame, from `examples/reagent/login/core.cljs`:

```clojure
(with-frame [f (rf/make-frame
                 {:fx-overrides {:rf.http/managed :auth.login/test-canned-success}})]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                       {:email "user@example.com"
                                        :password "correct-horse"}]]
                    {:frame f})
  (assert (= :authed (rf/compute-sub [:auth.login/state] (rf/get-frame-db f)))))
```

Each test gets its own frame with its own app-db and its own fx-override map — concurrent tests can run with no cross-contamination.

And configuring `:rf/default` at app boot:

```clojure
(rf/reg-frame :rf/default
  {:doc          "Login demo frame."
   :fx-overrides {:rf.http/managed :rf.http/managed.login-demo}})
```

## Frame metadata — what goes in it

The metadata map (`frame.cljc:99-130`) accepts:

- `:doc` — one-sentence what-and-why.
- `:preset` — one of `:default :test :story :ssr-server`; expands at registration into a fixed metadata bundle.
- `:fx-overrides` — `{original-id replacement-id-or-fn}`. Two value shapes are honoured (`fx.cljc:62-142`): a **keyword** redirects the lookup to another registered fx (portable, SSR-safe pattern-level form), and a **function** `(fn [m args] ...)` runs inline with no registry lookup (one-shot CLJS-reference convenience for test fixtures and story decorators). The id-redirect form is preferred when the stub is reused; the fn form when one test wants a bespoke response without registering a parallel fx. Per-call `:fx-overrides` in `dispatch` / `dispatch-sync` opts accepts the same two shapes.
- `:platform` — `:client` or `:server`; gates fx whose `:platforms` set excludes the active platform.
- `:drain-depth` — bound on dispatch-cascade depth (default 100; `:story` preset tightens to 16).
- `:on-create` / `:on-destroy` — event vectors fired synchronously at lifecycle transitions.

User-supplied keys win on conflict with preset expansion.

## `frame-provider` in views

Wraps a Reagent / Helix / UIx subtree so descendants resolve `current-frame` to a chosen id:

```clojure
[rf/frame-provider {:frame :stories} [my-story-shell]]
```

`reg-view`-wrapped components participate automatically (the wrapper carries `:contextType`). Plain Reagent fns under a non-default `frame-provider` fall through to `:rf/default` — the runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` to flag the footgun (`adapter/reagent.cljs:140-148`).

## Common gotchas

- **`reg-frame` is atomic and hot-reload safe.** First call creates and runs `:on-create`; subsequent calls perform a **surgical update** of metadata only — existing app-db, sub-cache, queue, machine snapshots all preserved (`frame.cljc:152-183`). Use `reset-frame` for a full destroy+recreate.
- **`destroy-frame!` cascades.** Per active machine snapshot, the runtime emits *two* trace events — the reason event `:rf.machine/destroyed-on-frame-exit` (op-type `:machine`) and the uniform lifecycle event `:rf.machine.lifecycle/destroyed` (op-type `:rf.machine.lifecycle/destroyed`); in-flight HTTP requests get an abort hook; sub-cache reactions all dispose. Subsequent dispatch / subscribe raises `:rf.error/frame-destroyed`. See [009 §`:op-type` vocabulary](../../../../spec/009-Instrumentation.md#op-type-vocabulary).
- **`with-frame` is a CLJS macro AND a JVM-friendly fn.** The macro form (`re-frame.core/with-frame`) wraps an expression; the fn form (`core.cljc:932`) takes a thunk — use the fn from JVM tests / SSR / REPL.
- **Wrapping plain Reagent fns in a non-default `frame-provider` doesn't bind the frame.** Use `reg-view` so the `:contextType` wiring picks up the provider. Watch for the once-per-handler warning.
- **`:rf/default` is implicit.** Don't re-`reg-frame :rf/default` unless you specifically want to attach metadata to it — calling it without any is a no-op.

## Deeper material

Frame presets in detail, machine-instance teardown contract, the React-context chain through Reagent / Helix / UIx, `dispatch-to-system`: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — State machines (005)**.

---

*Derived from `implementation/core/src/re_frame/frame.cljc`, `implementation/core/src/re_frame/core.cljc`, and `implementation/reagent/src/re_frame/adapter/reagent.cljs` @ main `89bd9c3`. Re-verify line numbers after frame-resolution or adapter-late-bind changes (e.g. rf2-s36l).*
