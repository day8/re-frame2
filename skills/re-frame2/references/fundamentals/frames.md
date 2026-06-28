# Frames

## When to load

Working with multi-frame apps: registering a non-default frame, targeting a dispatch / subscribe at a specific frame, using `frame-provider`'s `{:frame …}` shape to scope a React subtree to an existing frame (or its `{:id …}` shape to ensure a named frame for a subtree), or carrying the current frame into an async callback via `capture-frame`.

## The teaching model: frame identity is carried, not found

The one rule (EP-0002 carried invariant): **frame identity is a value that travels with every causal token; an operation reads its frame from the scope it runs under and never synthesises one from absence.** A single-frame app establishes exactly one frame at its root (`reg-frame` it, then scope it with `frame-provider {:frame …}` / `with-frame`); *inside that scope* `rf/dispatch` / `rf/subscribe` stay ambient and ergonomic — no frame talk. There is **no implicit `:rf/default` floor**: a frame-scoped call issued under no scope fails loudly with `:rf.error/no-frame-context`. Reach for a frame affordance only when one of these intents applies:

| Intent | Affordance |
|---|---|
| Inside an established frame scope (the common case) | `dispatch` / `dispatch-sync` / `subscribe` — no frame talk at all |
| Pin a lexical scope to an existing frame | `with-frame` |
| Create + own + destroy a frame for a scope (tests / SSR) | `with-new-frame` |
| Scope a React subtree to an **existing** frame | `frame-provider {:frame …}` (SCOPE-only) |
| Ensure a **named** frame for a subtree (create-if-absent, reuse-no-reseed) | `frame-provider {:id …}` (ENSURE) |
| Hold a frame's ops as a value (async / closures) | `capture-frame` — the one public carry primitive |
| One-off explicit routing (`dispatch`) | `{:frame …}` trailing opt on `dispatch` / `dispatch-sync` |
| One-off explicit routing (`subscribe`) | leading `frame-id` arg — `(rf/subscribe :frame-id query-v)` (NOT a `{:frame …}` opt) |
| Read a frame's app-db / its id | `app-db-value` / `current-frame-id` |
| Read a frame's runtime-db / whole frame-state (tools, SSR) | `runtime-db-value` / `frame-state-value` |
| Install state from outside a cascade (tools, tests, SSR) | `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!` |

A `reg-view` body needs none of these for ordinary dispatch / subscribe — the macro injects frame-aware `dispatch` and `subscribe` locals automatically. A plain (non-`reg-view`) Reagent / UIx / Helix fn that needs to dispatch asks for `(rf/capture-frame)`. An arbitrary async callback captures `(rf/capture-frame)` in scope and calls its `:dispatch` / `:subscribe` ops.

## What a frame is

A **frame** is an isolated runtime boundary holding **two durable partitions** plus its own router queue and sub-cache. Frames are identified by keywords. Every re-frame2 app establishes at least one — registered explicitly with `reg-frame` and scoped at the root with `frame-provider {:frame …}` / `with-frame`. `init!` installs adapters and runtime capabilities but creates **no** frame; there is no auto-registered `:rf/default`.

The two partitions:

- **app-db** (`:db`) — the application's data, and nothing else. A `reg-event` handler reads app-db from its coeffects (`{:keys [db]}`) and returns the next state under `:db`; an ordinary `:db` effect replaces app-db only.
- **runtime-db** (`:rf.db/runtime`) — the framework's partition: machine snapshots at `[:rf.runtime/machines :snapshots]`, the route slice at `[:rf.runtime/routing :current]`, elision declarations at `[:rf.runtime/elision]`, SSR metadata. Reserved **by convention** under `:rf.runtime/*` keys. App code reads it through framework subs (`[:rf/machine <id>]`, `[:rf.route/*]`), never through `:db`; it's a separate partition, so a fresh `:db` return can't clobber a machine or the route by accident.

Together they compose a **frame-state** value: `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. The composite is what serialises for SSR, reverts on time-travel, and hydrates as one unit. `reg-app-schema` validates the app-db partition only — keep teaching it as "the app-db schema"; it describes a *pure* application contract with no framework state mixed in.

> **Where any value lives — the four storage classes.** The two durable partitions plus the per-frame sub-cache are the **storage classes** re-frame2 names for any declared value: `:app-db` (user-owned durable, the `:db` partition), `:runtime-db` (framework-owned durable, the `:rf.db/runtime` partition), `:ephemeral` (sub-cache / reaction value — recomputable, never written to durable frame state), `:host-transient` (handles *outside* frame state — an `AbortController`, a timer — cleared at a lifecycle boundary, never the only copy of a fact). You don't declare it — it falls out of *which* `reg-*` form you reach for (a sub is `:ephemeral`, a flow materialises to `:app-db`, a resource caches in `:runtime-db`) — but it's the vocabulary inspection tools and `SKILL-REDIRECT.md` → *Derivations and processes (the algebra)* use. A *remote* fact still has a **local** storage class (`:runtime-db` cache entry) — "remote" is its *authority*, never where it's stored.

A frame is a mutable runtime boundary, and public code targets it by one of two identity forms. **Mounted / app code** addresses a frame by a **registered keyword id** (`reg-frame`); **anonymous tests / harnesses / per-mount lifetimes** target the **live frame value** returned from `make-frame` (a lifecycle token — read its id with `frame-value->id`). Prefer an id when other code must address the frame by name; prefer the direct value when the creator owns the lifecycle. `dispatch` / `subscribe` / `destroy-frame!` accept either form.

## Canonical signatures

```clojure
;; Register / re-register a named frame.
(rf/reg-frame :frame-id metadata)

;; Per-instance frame. The ONE constructor (EP-0024) — accepts image-selection
;; opts AND record-config opts in one call; returns the live frame VALUE
;; (a lifecycle token; read its id with (rf/frame-value->id f)). dispatch /
;; subscribe / destroy-frame! accept the value OR its id.
(rf/make-frame opts)            ;; e.g. {:id :todo :images [todo-image] :initial-events [[:rf/set-db {}] [...]]}

;; Destroy / reset.
(rf/destroy-frame! :frame-id)
(rf/reset-frame!   :frame-id)        ;; destroy + re-register: clears BOTH partitions, re-dispatches :initial-events
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

Verified in `re-frame.frame` (`reg-frame`, `make-frame`, `destroy-frame!`). The public macro layer is in `re-frame.core`. `replace-app-db!` replaces only the app-db partition; `replace-frame-state!` is the distinct full-frame surface, so a db-shaped name never silently overwrites runtime-db.

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

When you `setTimeout` or hand a callback to a promise, the frame scope (dynamic var → React context) has unwound by the time it runs — so a bare `dispatch` inside it would raise `:rf.error/no-frame-context`. The keystone affordance is **`capture-frame`**, which returns a **frame api** captured at creation time. Its ops carry the captured frame as a value and survive async boundaries:

```clojure
(let [{:keys [dispatch]} (rf/capture-frame)]        ;; captures the scope's frame as a value, now
  (.then promise #(dispatch [:result-arrived %])))
```

`(rf/capture-frame)` captures `(current-frame-id)`; `(rf/capture-frame :frame-id)` locks to an explicit id. It returns `{:frame :dispatch :dispatch-sync :subscribe}` — a **frame api**, not a container. Read the frame's app-db value via `(rf/app-db-value (:frame frame-api))`, never off the frame api itself. A per-call `:frame` opt cannot override the captured frame; the frame api is locked to one frame.

`capture-frame` is the **one public carry primitive** — every async / callback / tooling boundary captures it (or routes with an explicit `{:frame …}` opt). `frame-bound-fn` / `frame-bound-fn*` are not app API.

## Canonical mini-example

Per-test isolated frame, from `examples/core/login/core.cljs`:

```clojure
(with-new-frame [f (rf/make-frame
                 {:fx-overrides {:rf.http/managed :auth.login/test-canned-success}})]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                       {:email "user@example.com"
                                        :password "correct-horse"}]]
                    {:frame f})
  (assert (= :authed (rf/compute-sub [:auth.login/state] (rf/app-db-value f)))))
```

Each test gets its own frame with its own app-db and its own fx-override map — concurrent tests can run with no cross-contamination. Under EP-0024 `make-frame` returns the live frame **value** (a lifecycle token); holding it and passing it directly (`{:frame f}`, `(rf/app-db-value f)`) is the sanctioned tests-and-harness pattern — `dispatch` / `subscribe` / `app-db-value` accept the value or its id, and you read the id with `(rf/frame-value->id f)`. The frame is born in the test scope and dies with it (`with-new-frame` destroys it on block exit), with no entry in the named frame registry. That is the right shape for tests and harnesses — reach for an `:id`-registered frame only when mounted code must address it by id (route public ops by id elsewhere).

And establishing the app frame at boot (the runtime infers no frame, so you register it explicitly and scope it at the root):

```clojure
;; User-defined fxs sit under a user-feature prefix per
;; spec/Conventions.md §Reserved namespaces — never under `:rf.<feature>/…`,
;; which is reserved for framework-owned surfaces.
(rf/reg-frame :app/login                      ;; an explicit app-frame id (a migration may pick :rf/default)
  {:doc          "Login demo frame."
   :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})

;; ...then scope it at the root so bare dispatch/subscribe resolve to it
;; (frame-provider {:frame …} — the frame already exists from reg-frame above):
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
- `:initial-events` — an ordered vector of event vectors dispatched synchronously at construction (seed app-db with a leading `[:rf/set-db {…}]`); `:on-destroy` — an event vector fired synchronously at teardown.

User-supplied keys win on conflict with preset expansion.

## The merged `frame-provider` in views (EP-0024)

`frame-provider` is **one** per-adapter React-context component with **two config shapes**, selected by the prop map (`:frame` vs `:id`) — choose by intent:

- **`frame-provider {:frame existing-id}`** — the **SCOPE-only** shape. Wraps a Reagent / Helix / UIx subtree so descendants resolve `current-frame-id` to a frame that **already exists** (created elsewhere by `reg-frame`, a direct `make-frame`, a tool runtime, or an enclosing provider). It creates / refreshes / destroys nothing, and **fails loud if the frame is absent** (`:rf.error/frame-provider-frame-absent`):

  ```clojure
  [rf/frame-provider {:frame :stories} [my-story-shell]]
  ```

- **`frame-provider {:id the-id …}`** — the **ENSURE** shape. **Creates the frame if absent, reuses it without re-seeding if present** (idempotent re-mount preserves durable state and does NOT replay `:initial-events`), and provides its id to descendants; **no destroy-on-unmount**. It takes the **same constructor opts as `make-frame`** (`:id` / `:images` / `:initial-events` / record-config) — for view-driven named-frame lifetimes: comparison pages, Story canvases, embedded widgets:

  ```clojure
  [rf/frame-provider {:id :todo/left :images [todo-image] :initial-events [[:rf/set-db {...}]]} [todo-pane]]
  ```

(`with-frame` remains for lexical / non-React ambient scoping — it binds a dynamic var, which cannot cross React's render boundary, which is why scope-into-React needs a context component rather than `with-frame`. True lifetime *ownership* — destroy-on-unmount — is now explicit: `make-frame` + `destroy-frame!` inside a `create-class`, where the component declares it owns the frame's life.)

`reg-view`-wrapped components participate in the provider automatically (the wrapper carries `:contextType`). A plain Reagent fn carries no `:contextType`, so it **cannot read the provider's frame** from React context; its ambient `rf/subscribe` / `rf/dispatch` resolve to nil and raise `:rf.error/no-frame-context` (EP-0002) rather than silently routing to a default. Use `reg-view`, or capture a `(rf/capture-frame)` at render time and call its bound ops. (Per spec/004 §Plain Reagent fns.)

## Common gotchas

- **`reg-frame` is atomic and hot-reload safe.** First call creates and runs `:initial-events`; subsequent calls perform a **surgical update** of metadata only — existing app-db, sub-cache, queue, machine snapshots all preserved (`reg-frame` in `re-frame.frame`). Use `reset-frame!` for a full destroy+recreate.
- **`destroy-frame!` cascades.** Per active machine snapshot, the runtime emits *one* `:rf.machine.lifecycle/destroyed` trace carrying `:reason :parent-frame-destroyed` under `:tags` (the unified lifecycle channel — same op-type used at `reg-machine`'s `:created` emit); in-flight HTTP requests get an abort hook; sub-cache reactions all dispose. **Subsequent dispatch / subscribe does NOT throw** — the runtime recovers (a teardown / hot-reload race must not crash the caller) but still emits an observable `:rf.error/frame-destroyed` so a genuine use-after-destroy bug stays visible (the `recover-but-emit` contract). The two outcomes differ: a **dispatch** to a destroyed frame **no-ops** (the envelope is dropped, the drain continues) and emits the error; a **subscribe** **returns `nil`** (no reaction) and emits the error. Tests must assert the no-op / `nil` outcome plus the emitted trace — **never** a thrown exception or a try/catch path. See [009 §`:op-type` vocabulary](../../../../spec/009-Instrumentation.md#op-type-vocabulary).
- **`with-frame` works on both CLJS and the JVM.** The `re-frame.core/with-frame` macro expands on both platforms — use it from JVM tests / SSR / REPL as well as CLJS. For programmatic frame-pinning where a macro is awkward, bind the current-frame dynamic var directly (see the frame-resolution chain above).
- **Wrapping plain Reagent fns in a `frame-provider` doesn't bind the frame.** A plain fn can't read the provider's frame from context, so its ambient `subscribe`/`dispatch` raise `:rf.error/no-frame-context`. Use `reg-view` so the `:contextType` wiring picks up the provider, or capture a `capture-frame`.
- **`:rf/default` is an ordinary id, not a fallback.** The runtime never creates or infers it; it carries no privilege. You may register and scope it explicitly like any other frame (a migration sometimes picks it for familiarity), but a single-frame app is freer choosing a descriptive id like `:app/main` and establishing it at the root.

## Images, frames, and the event stream (EP-0023 — the multi-frame public model)

The mental model: **`image -> frame -> event stream`**, like a VM. An **image** is the *selected registration set* a frame runs (instruction set); a **frame** is the *isolated execution context* (its memory + the one image generation it resolves against); the **event stream** is the ordered events a frame processes over its life (the program). Events are instructions, the six-domino cascade is the ISA, your `reg-*` forms supply the instruction meanings, the image is the loaded instruction set, the frame is the VM executing the stream.

The everyday rule that falls out:

```text
same behaviour, different memory  -> same image, different frames
different behaviour               -> different images
```

You almost certainly do not need to name an image. The two facts an author should hold:

- **The ordinary `reg-*` path is unchanged — the default image is implicit.** `reg-*` writes to the process-wide registration source; a frame created with no explicit `:images` resolves against the *default image* projected over that source. A single-frame app never spells `image` or `make-frame` `:images`; the zero-ceremony path stays zero-ceremony. The image concept becomes visible only when the default process-wide registration set stops being the right boundary.
- **Registration ids are scoped to an image; frame ids are process-local.** Two images may both contain `:counter/inc`; two live frames may **not** both register as `:counter/main`. That split is the heart of the multi-frame story: a docs page can reuse teaching-friendly registration ids across examples, while each mounted example still needs a distinct frame id. (An anonymous `make-frame` value — created with no `:id` — is born and dies in a test/harness scope without claiming a name in the frame registry.)

**When you reach for explicit images:** two unrelated surfaces on one page (a todo surface beside a counter, each with its own local ids), a tool surface beside the thing it inspects (so their ids never collide), progressive doc examples that reuse one teaching vocabulary, library packaging, and isolated test/story frames. Each case is "different instruction set, isolated memory" — so each gets its own image, and each live instance gets its own frame.

> **The landed public surface.** `rf/image` is exported on `re-frame.core` today: `(rf/image {:select-ns {:include [<ns-glob> …]}})` returns an **inert image value** — pure data, no registrar, no side effect. `:select-ns :include` selects already-loaded registrations by their *source* namespace (`:rf.provenance/ns`), not by the registration-id namespace; the glob grammar is `*` (one segment) / `**` (zero or more), case-sensitive, whole-namespace match, and a pattern that matches **zero** descriptors fails image assembly loud; the optional `:exclude` leg subtracts. Inline `:registrations` (registrar-keyed sections mirroring `:reg-event` / `:reg-sub` / …) round out the spec map — `:id` / `:select-ns` / `:registrations` are the only three public keys (EP-0026). Frame creation resolves one or more image values (always supplied as a vector under `:images`) into one sealed **image generation** the frame runs; composition resolves by **image order** (the later image wins; read what it shadowed via `rf/frame-shadows`), and reload swaps that generation while preserving frame memory. There are no `:include-ns` / `:exclude-ns` / `:replace` / `:replace-standard` / `:rf.image/requires` keys — passing them fails loud.
>
> **`make-frame` is one constructor returning the frame value (EP-0024).** It accepts image-selection opts (`:images`) AND record-config opts (`:id` / `:initial-events` / `:fx-overrides` / …) in one call and **returns the live frame value** (a lifecycle token; read its id via `frame-value->id`). The advanced `re-frame.frame/make-frame` is internal. A frame-targeted `reload-images!` swaps a live frame's image generation while preserving its memory. For a callable frame at the app root, `reg-frame` it and scope with `frame-provider {:frame …}`; construct image *values* with `rf/image`; for a view-driven named-frame lifetime use `frame-provider {:id …}` (ensure), or explicit `make-frame` + `destroy-frame!` when a component must own teardown.

### Frame isolation is the whole isolation story

You target a **frame** — a process-local frame id in mounted code, or a direct frame value from `make-frame` in tests/harnesses (EP-0024). The frame determines the image generation used for registration resolution; image assembly plus frame isolation are everything. There is **no public realm / app / module composition vocabulary**: a single-product SPA targets its one frame; a multi-frame app reaches for explicit `rf/image` values, not a container address. There is no `rf/migration-map` / `rf/migration-explain` data surface — those names do not exist.

Frame ids are **process-local and unique** — two live frames may not both claim `:counter/main` (registration ids, by contrast, are image-scoped and may repeat across images — see above). A frame id already live elsewhere surfaces as a loud error rather than a silent collision; the fix is distinct frame ids, or a direct frame value kept in local scope.

## Deeper material

Frame presets in detail, machine-instance teardown contract, the React-context chain through Reagent / Helix / UIx, `dispatch-to-system`: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — State machines (005)**.

---

*Derived from `re-frame.frame`, `re-frame.core`, and `re-frame.adapter.reagent` @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after frame-resolution or adapter-late-bind changes.*
