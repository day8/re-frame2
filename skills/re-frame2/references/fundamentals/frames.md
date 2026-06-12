# Frames

## When to load

Working with multi-frame apps: registering a non-default frame, targeting a dispatch / subscribe at a specific frame, using `frame-provider` to scope a React subtree, or carrying the current frame into an async callback via `frame-handle` / `frame-bound-fn`.

## The teaching model: frame identity is carried, not found

The one rule (EP-0002 carried invariant): **frame identity is a value that travels with every causal token; an operation reads its frame from the scope it runs under and never synthesises one from absence.** A single-frame app establishes exactly one frame at its root (a `frame-provider` / `with-frame`); *inside that scope* `rf/dispatch` / `rf/subscribe` stay ambient and ergonomic — no frame talk. There is **no implicit `:rf/default` floor**: a frame-scoped call issued under no scope fails loudly with `:rf.error/no-frame-context`. Reach for a frame affordance only when one of these intents applies:

| Intent | Affordance |
|---|---|
| Inside an established frame scope (the common case) | `dispatch` / `dispatch-sync` / `subscribe` — no frame talk at all |
| Pin a lexical scope to an existing frame | `with-frame` |
| Create + own + destroy a frame for a scope (tests / SSR) | `with-new-frame` |
| Scope a React subtree to a frame | `frame-provider` |
| Hold a frame's ops as a value (async / closures) | `frame-handle` (common); `frame-bound-fn` / `frame-bound-fn*` (advanced) |
| One-off explicit routing (`dispatch`) | `{:frame …}` trailing opt on `dispatch` / `dispatch-sync` |
| One-off explicit routing (`subscribe`) | leading `frame-id` arg — `(rf/subscribe :frame-id query-v)` (NOT a `{:frame …}` opt) |
| Read a frame's app-db / its id | `app-db-value` / `current-frame-id` |
| Read a frame's runtime-db / whole frame-state (tools, SSR) | `runtime-db-value` / `frame-state-value` |
| Install state from outside a cascade (tools, tests, SSR) | `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!` |

A `reg-view` body needs none of these for ordinary dispatch / subscribe — the macro injects frame-aware `dispatch` and `subscribe` locals automatically. A plain (non-`reg-view`) Reagent / UIx / Helix fn that needs to dispatch asks for `(rf/frame-handle)`. An arbitrary async callback uses `frame-bound-fn`.

## What a frame is

A **frame** is an isolated runtime boundary holding **two durable partitions** plus its own router queue and sub-cache. Frames are identified by keywords. Every re-frame2 app establishes at least one — registered explicitly with `reg-frame` and scoped at the root with a `frame-provider` / `with-frame`. `init!` installs adapters and runtime capabilities but creates **no** frame; there is no auto-registered `:rf/default`.

The two partitions:

- **app-db** (`:db`) — the application's data, and nothing else. Every `reg-event-db` handler receives and returns app-db; an ordinary `:db` effect replaces app-db only.
- **runtime-db** (`:rf.db/runtime`) — the framework's partition: machine snapshots at `[:rf.runtime/machines :snapshots]`, the route slice at `[:rf.runtime/routing :current]`, elision declarations at `[:rf.runtime/elision]`, SSR metadata. Reserved **by convention** under `:rf.runtime/*` keys. App code reads it through framework subs (`sub-machine`, `[:rf.route/*]`), never through `:db`; it's a separate partition, so a fresh `:db` return can't clobber a machine or the route by accident.

Together they compose a **frame-state** value: `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. The composite is what serialises for SSR, reverts on time-travel, and hydrates as one unit. `reg-app-schema` validates the app-db partition only — keep teaching it as "the app-db schema"; it describes a *pure* application contract with no framework state mixed in.

> **Where any value lives — the four storage classes.** The two durable partitions plus the per-frame sub-cache are the same three homes re-frame2 names as **storage classes** when it describes any declared value: `:app-db` (user-owned durable, this `:db` partition), `:runtime-db` (framework-owned durable, this `:rf.db/runtime` partition), `:ephemeral` (the sub-cache / a reaction value — recomputable, never written to durable frame state), and `:host-transient` (handles *outside* frame state — an `AbortController`, a timer — that must be cleared at a lifecycle boundary, never the only copy of a fact). When you ask "is this durable or ephemeral? app-owned or framework-owned?", that's the storage-class axis. You don't declare it — it falls out of *which* `reg-*` form you reach for (a sub is `:ephemeral`, a flow materialises to `:app-db`, a resource caches in `:runtime-db`) — but the named vocabulary is the one inspection tools and `SKILL-REDIRECT.md` → *Derivations and processes (the algebra)* use, so it's worth recognising. Reminder: a *remote* fact (a server-owned resource) still has a **local** storage class (`:runtime-db` cache entry) — "remote" is its *authority*, never where it's stored.

Frames are mutable runtime objects, not values. User code holds keywords and lets the framework resolve them.

## Canonical signatures

```clojure
;; Register / re-register a named frame.
(rf/reg-frame :frame-id metadata)

;; Anonymous instance (gensym'd id under :rf.frame/*). Returns the id.
(rf/make-frame metadata)

;; Destroy / reset.
(rf/destroy-frame! :frame-id)
(rf/reset-frame!   :frame-id)        ;; destroy + re-register: clears BOTH partitions, re-fires :on-create
                                     ;; (for app-db-only reset that keeps live machines/routes: reset-app-db!)

;; Inspect.
(rf/current-frame-id)               ;; returns the active frame id
(rf/app-db-value :frame-id)         ;; current app-db partition VALUE (plain map, no deref)
(rf/runtime-db-value :frame-id)     ;; current runtime-db partition VALUE (tools / privileged runtime)
(rf/frame-state-value :frame-id)    ;; {:rf.db/app … :rf.db/runtime …} — the whole frame (SSR / epoch / tools)

;; Install from outside a cascade (tools / tests / SSR — NOT app code).
(rf/replace-app-db!     :frame-id app-db)        ;; replace ONLY app-db (state injection)
(rf/reset-app-db!       :frame-id)               ;; app-db → {}, runtime-db (live machines/routes) preserved
(rf/replace-runtime-db! :frame-id runtime-db)    ;; replace ONLY runtime-db
(rf/replace-frame-state! :frame-id frame-state)  ;; replace BOTH partitions atomically (full-frame install)
```

Verified in `re-frame.frame` (`reg-frame`, `make-frame`, `destroy-frame!`). The public macro layer is in `re-frame.core`. The partition mutators replace the former `reset-frame-db!` — `replace-app-db!` is its direct rename; `replace-frame-state!` is the distinct full-frame surface so a db-shaped name never silently overwrites runtime-db.

## Frame resolution chain

Two scope tiers (the frame-resolution chain in `re-frame.frame` / `re-frame.core`), **with no `:rf/default` fallback rung** (EP-0002):

1. **`*current-frame*` dynamic var** — bound by `with-frame`.
2. **React context** (CLJS only) — read via the `:adapter/current-frame` late-bind hook, populated by the installed adapter under a `frame-provider`.

If neither tier names a frame, the reader returns `nil` and a public frame-scoped operation raises `:rf.error/no-frame-context` — the chain never invents a default. (The explicit `{:frame …}` *override* / leading `frame-id` arg bypasses the chain entirely.)

`dispatch` and `subscribe` both resolve the target frame from the established scope (via `require-current-frame!`, which raises outside any scope), but their explicit-routing surfaces differ — **`dispatch` / `dispatch-sync` take a trailing `{:frame …}` opts map; `subscribe` / `subscribe-once` / `unsubscribe` take a *leading* `frame-id` argument** (no opts map). The explicit form is first-class (tools, tests, SSR, fx handlers), not a workaround:

```clojure
(rf/dispatch  [:foo] {:frame :stories})           ;; dispatch: trailing {:frame …} opt
(rf/subscribe :stories [:my-sub])                 ;; subscribe: LEADING frame-id arg
(rf/subscribe [:my-sub])                          ;; no frame-id → resolves the established scope (raises if none)
```

A trailing `{:frame …}` map passed to `subscribe` is **not** an opts map — it would be read as the `query-v`, silently subscribing to the wrong query. To read a non-default frame's app-db as a plain value (no reaction), use `(rf/app-db-value :frame-id)`.

## Carrying the frame into async callbacks

When you `setTimeout` or hand a callback to a promise, the frame scope (dynamic var → React context) has unwound by the time it runs — so a bare `dispatch` inside it would raise `:rf.error/no-frame-context`. The keystone affordance is **`frame-handle`** — a per-frame OPERATION BUNDLE captured at creation time. Its ops carry the captured frame as a value and survive async boundaries:

```clojure
(let [{:keys [dispatch]} (rf/frame-handle)]        ;; captures the scope's frame as a value, now
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

And establishing the app frame at boot (the runtime infers no frame, so you register it explicitly and scope it at the root):

```clojure
;; User-defined fxs sit under a user-feature prefix per
;; spec/Conventions.md §Reserved namespaces — never under `:rf.<feature>/…`,
;; which is reserved for framework-owned surfaces.
(rf/reg-frame :app/login                      ;; an explicit app-frame id (a migration may pick :rf/default)
  {:doc          "Login demo frame."
   :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})

;; ...then establish it at the root so bare dispatch/subscribe resolve to it:
(rdc/render root
  [rf/frame-provider {:frame :app/login}
   [app-root]])
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

`reg-view`-wrapped components participate automatically (the wrapper carries `:contextType`). A plain Reagent fn carries no `:contextType`, so it **cannot read the provider's frame** from React context; its ambient `rf/subscribe` / `rf/dispatch` resolve to nil and raise `:rf.error/no-frame-context` (EP-0002) rather than silently routing to a default. Use `reg-view`, or capture a `(rf/frame-handle)` at render time and call its bound ops. (Per spec/004 §Plain Reagent fns; the old `:rf.warning/plain-fn-under-non-default-frame-once` warning is superseded by the loud error.)

## Common gotchas

- **`reg-frame` is atomic and hot-reload safe.** First call creates and runs `:on-create`; subsequent calls perform a **surgical update** of metadata only — existing app-db, sub-cache, queue, machine snapshots all preserved (`reg-frame` in `re-frame.frame`). Use `reset-frame!` for a full destroy+recreate.
- **`destroy-frame!` cascades.** Per active machine snapshot, the runtime emits *one* `:rf.machine.lifecycle/destroyed` trace carrying `:reason :parent-frame-destroyed` under `:tags` (the unified lifecycle channel — same op-type used at `reg-machine`'s `:created` emit); in-flight HTTP requests get an abort hook; sub-cache reactions all dispose. **Subsequent dispatch / subscribe does NOT throw** — the runtime recovers (a teardown / hot-reload race must not crash the caller) but still emits an observable `:rf.error/frame-destroyed` so a genuine use-after-destroy bug stays visible (the `recover-but-emit` contract). The two outcomes differ: a **dispatch** to a destroyed frame **no-ops** (the envelope is dropped, the drain continues) and emits the error; a **subscribe** **returns `nil`** (no reaction) and emits the error. Tests must assert the no-op / `nil` outcome plus the emitted trace — **never** a thrown exception or a try/catch path. See [009 §`:op-type` vocabulary](../../../../spec/009-Instrumentation.md#op-type-vocabulary).
- **`with-frame` works on both CLJS and the JVM.** The `re-frame.core/with-frame` macro expands on both platforms — use it from JVM tests / SSR / REPL as well as CLJS. For programmatic frame-pinning where a macro is awkward, bind the current-frame dynamic var directly (see the frame-resolution chain above).
- **Wrapping plain Reagent fns in a `frame-provider` doesn't bind the frame.** A plain fn can't read the provider's frame from context, so its ambient `subscribe`/`dispatch` raise `:rf.error/no-frame-context`. Use `reg-view` so the `:contextType` wiring picks up the provider, or capture a `frame-handle`.
- **`:rf/default` is an ordinary id, not a fallback.** The runtime never creates or infers it; it carries no privilege. You may register and scope it explicitly like any other frame (a migration sometimes picks it for familiarity), but a single-frame app is freer choosing a descriptive id like `:app/main` and establishing it at the root.

## Realms — the container a frame lives in (advanced public API)

The mental model: **the program is a value; the runtime is a container you install it into.** By default your registrations (`reg-event-db`, `reg-sub`, `reg-fx`, …) update one process-wide table, and your frames all share it. EP-0013 names that table's owner a **realm** — the operational environment holding the registered behaviour, the installed adapter, runtime capabilities (HTTP, clock, schema validation), and the frame registry. A frame belongs to exactly one realm; the durable app-db / runtime-db partitions a frame owns are unchanged.

You almost certainly do not need to think about this. The two facts an author should hold:

- **A single-realm app sees nothing new.** The process you already have is one realm — the **default realm** — and every `reg-*` / `dispatch` / `subscribe` call targets it implicitly. This is the same refinement EP-0002 makes for frames: the default realm is *explicit machinery the runtime creates*, not ambient magic, and the zero-ceremony path stays zero-ceremony. The realm is the analogue of `:rf/default` one level up: a real, runtime-created thing, never synthesised from absence.
- **Realms are carried, never ambient — the EP-0002 rule, one level up.** When more than one realm exists, a frame-scoped operation reads its realm from the same carrier that identifies its frame (a frame is registered into a realm; an operation runs under that frame's scope). There is no `with-runtime`-style dynamic binding to search — that would re-introduce the exact ambient-context trap EP-0002 deleted for frames, and it breaks for the same async reason (a captured callback outlives the binding). Absence fails loudly with the same no-frame-context family; it never selects a realm.

The payoff lands only when one process must run **two programs side by side** — independent tenants, a feature pack with its own handler graph, or **two adapters at once** (a legacy Reagent root next to a new UIx root, impossible under one-adapter-per-process). Each gets its own realm; the same event id can carry different behaviour in each without collision. A single-product SPA never reaches for it.

> **Now an advanced public API.** `rf/realm`, `rf/app`, `rf/module`, `rf/install!`, and `rf/reinstall!` have **shipped** (EP-0013) as the `re-frame.core` **advanced** tier — they are callable today, not reserved vocabulary. A single-realm app can ignore the whole surface: never spell a realm and the implicit default realm backs every `reg-*` / `dispatch` / `subscribe` byte-identically. When you do reach for it: `rf/module` lowers a feature's registrations + ownership + capability requirements into an inert, composable **module value**; `rf/app` composes module values into an inert **app value** (a same-id collision across modules throws, never last-writer-wins); `rf/install!` seats an app value into a realm (capability-checked first); `rf/reinstall!` hot-reloads a realm by diffing the new app value against the installed one. `rf/realm` constructs an explicit **hermetic** realm (its own registrar atom) to install into — the multi-tenant / parallel-app / hermetic-test container; `rf/dispose-realm!` is its teardown counterpart. Use `rf/realm` for the container concept (never `rf/runtime` — "runtime" already names runtime-db and the runtime subsystems). `rf/realm-ids` enumerates the installed realms and `rf/frame-realm` reads the realm a frame lives in (the `(realm, frame)` address pair).

A composed `module` value on its own is inert data with no registration side effect until an `install!` seats it. One migration accident is worth flagging: registering the same handler *both* via a top-level `reg-*` (which targets the default realm) *and* by listing it in a module installed into that same realm is a same-id collision, caught loudly — not a silent merge.

## Deeper material

Frame presets in detail, machine-instance teardown contract, the React-context chain through Reagent / Helix / UIx, `dispatch-to-system`: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — State machines (005)**.

---

*Derived from `re-frame.frame`, `re-frame.core`, and `re-frame.adapter.reagent` @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after frame-resolution or adapter-late-bind changes.*
