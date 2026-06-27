# Coeffects (cofx)

## When to load

Registering a coeffect supplier (current time, a localStorage value, a generated id, a sub's value, …) with `reg-cofx`; or declaring a handler's coeffect dependencies with `:rf.cofx/requires`.

## The one mental model (EP-0017)

A coeffect is a **fact the causal run consumed** — data from outside the event. There is **one registrar** (`reg-cofx`, a value-returning supplier) and **one declaration surface** (`:rf.cofx/requires`, registration metadata).

The coeffects map a handler receives has two layers. The **base framework coeffects are always staged** regardless of what the handler declares: `:db` and `:event` (the fold's own arguments), plus `:rf.db/runtime` (the runtime-db partition) and `:rf.frame/id` (the runtime-context frame stamp), plus `:rf.cofx` (the whole flat recordable-coeffect map — the envelope's canonical record, including `:rf/time-ms`). On top of that base, the handler's **user-declared leaves are declared-only**: the runtime delivers **exactly the facts named in `:rf.cofx/requires`**, flat under their ids, and stages nothing else from the token. So no *user* coeffect — not even the time — arrives without being declared; but the base framework coeffects (`:rf.db/runtime`, `:rf.frame/id`, `:rf.cofx`) are always present, and `:rf.cofx` is a framework/default coeffect filtered out of the Xray COEFFECTS lens (which shows only handler-declared leaves). Read the declared time-fact off `:rf/time-ms` (delivered flat when you declare it), not off `:rf.cofx`.

> **`inject-cofx` is REMOVED (EP-0017, no alias).** Calling it is the hard error `:rf.error/inject-cofx-removed`. Declare the coeffect on the handler's registration metadata instead. If you are migrating a v1 app, see [`causal-world-inputs.md`](../../../re-frame-migration/references/causal-world-inputs.md).

## Canonical signature — `reg-cofx` is value-returning

```clojure
(rf/reg-cofx :cofx-id           supplier)
(rf/reg-cofx :cofx-id metadata? supplier)

;; supplier :: (fn [] value)      ;; nullary — the ordinary supplier
;;           | (fn [arg] value)   ;; call-site-parameterized; declared [id arg]
;;
;; The supplier RETURNS the coeffect VALUE directly (the EP-0017 shape).
;; The retired ctx→ctx form ((fn [ctx] (assoc-in ctx [:coeffects k] v))) is gone.
```

Verified in `implementation/core/src/re_frame/cofx.cljc` (the `reg-cofx`, `parse-requires`, and `deliver-declared-cofx` fns). Metadata may carry:

- `:doc` — one-sentence what-and-why; surfaces via `(rf/handler-meta :cofx id)`.
- `:recordable?` — mark the fact **recordable** (see grades below). Default `false` (ambient).
- `:provided?` — (recordable only) the fact has **no generator**; its owner stamps the value onto the token.
- `:schema` — Malli schema validating supplied / replayed values. A generated value is validated against `:schema` at processing-start (a production hard error on mismatch) before it is written into the recorded token.
- `:platforms` — `#{:client :server}`; defaults to both. A supplier tagged `:platforms #{:client}` is **skipped** on server-side frames (emits `:rf.cofx/skipped-on-platform`; the declaring handler sees no value for that id). Mirrors `reg-fx`'s contract per `spec/011-SSR.md`. Example: `(rf/reg-cofx :browser-locale {:platforms #{:client}} (fn [] js/navigator.language))` is safe to register on both platforms; under an `:ssr-server` frame the supplier is skipped.

> **`:platforms` lives in the metadata-map, NOT reader metadata.** `reg-cofx` reads `:platforms` only from the optional metadata-map argument. Reader metadata on the form (`^{:platforms #{:client}} (rf/reg-cofx ...)`) is **never consulted** — it would register a browser-only supplier for both platforms.

## Declaration — `:rf.cofx/requires`

A handler takes delivery by declaring the ids it consumes in `:rf.cofx/requires` (Spec 001 standard metadata key, on `reg-event` — uniformly available to every event handler). The declared values arrive **flat** in the coeffects map under their ids — never a nested `:cofx` sub-map:

```clojure
(rf/reg-event :counter/inc
  {:doc "Increment by a delta, stamping the durable update time."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (-> db (update :count inc) (assoc :last-updated-at time-ms))}))
```

- A parameterized id appears as `[id arg]` (mirroring the binary supplier arity): `:rf.cofx/requires [[:ui/local-theme "theme"]]`.
- Delivery is **declared-only**: a leaf on the token but not declared by this handler is **not staged** (`handler-meta` is the complete consumption record). Forgetting to declare what you destructure is a lint target ("consuming without declaring").
- Every `reg-event` handler can carry `:rf.cofx/requires` — there is one event form, so there is no second-class handler that cannot declare its world facts (the EP-0018 collapse closed that gap).

## The two grades — ambient vs recordable

The grade is a property of the registration, not a namespace:

- **Ambient** (default) — the supplier runs at context assembly, the value is delivered to declaring handlers and **never recorded**; replay re-runs the supplier. Legal **only where no durable write depends on the value** — display preferences, diagnostics, host-transient measurements, or a read of state that is *already* recorded durable state.
- **Recordable** (`:recordable? true`) — the fact is **ensured onto the causal token**, recorded, and re-presented verbatim by replay. Required for any fact that can affect durable frame-state.

A recordable registration comes in two shapes, and the **generator shape is the normal one an application reaches for**:

- **Recordable GENERATOR** — a `:recordable? true` supplier *with* a value-returning generator (`(fn [] …)`). The generator **runs at processing-start**, its result is **validated** (against `:schema`, if declared) and **written into the recorded `:rf.cofx` token**, and replay **re-presents the recorded value verbatim** instead of re-running the generator. This is how an **application supplies a world-read coeffect** — a freshly-minted id, a localStorage / sessionStorage read, any host fact the app owns — that then feeds durable state. The app registers the supplier with `reg-cofx`; the framework records and replays it.
- **Recordable PROVIDED** (`:provided? true`) — a recordable fact with **no generator**. Its owner stamps the value onto the token at the boundary; the framework records and re-presents it. Provided is for facts the app **cannot compute itself** at processing-start — a subsystem/boundary fact (SSR server-stamp, the routing location) whose owner is the framework or a subsystem, not the application code. It is still a registered `reg-cofx` declaration, never an ad-hoc dispatch-site value in production.

> **Where does the application stamp a coeffect value at the dispatch site, then?** It does **not** — not in production. Carrying a coeffect on a dispatch (the `:rf.cofx` call-site opt, below) is **reserved for unit-test stubs**. A production app supplies its world-reads through `reg-cofx`: a generator for what it owns, a provided registration for boundary facts its owner stamps. The supplier *is* the coeffect source.

**The durable-write rule (the whole discipline in one sentence): durable state folds facts, never reads.** A transition that performs a durable write — anything written to app-db, runtime-db, a resource, a machine snapshot, a work-ledger row, or a hydration payload — MUST consume world facts from the token's recordable coeffects (or the event payload), never from an ambient read at the write site. Otherwise the same event replays to a different value (epoch restore / SSR hydration / time-travel all diverge).

### `:rf/time-ms` — the framework's one built-in coeffect

The framework ships exactly **one** registration: `:rf/time-ms` — recordable, provided, stamped onto every dispatch and reply envelope at enqueue. It is the canonical durable wall-clock fact. A handler that folds a timestamp into durable state declares `:rf.cofx/requires [:rf/time-ms]` and reads `time-ms` flat. **Do not register a `:now` cofx that reads `js/Date` for a durable timestamp** — declare `:rf/time-ms`.

```clojure
(rf/reg-event :todo/create
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id text]}]]
    ;; the durable id rides the event payload (the caller pinned it);
    ;; the durable time rides the recorded :rf/time-ms fact.
    {:db (assoc-in db [:todos id] {:text text :created-at time-ms})}))
```

(A bare `(random-uuid)` or `(js/Date.now)` at the write site would re-roll on every replay. Durable ids ride the event payload from a caller that pinned them, or — the usual app-owned shape — a **recordable generator cofx** (`(rf/reg-cofx :order/temp-id {:recordable? true} (fn [] (random-uuid)))`, declared and read flat); durable time rides `:rf/time-ms`.)

## The minting ladder (where does X come from?)

"My handler needs X from the world" resolves in preference order:

1. **Derive from recorded state** where possible (e.g. a monotone counter already in a snapshot). No new fact recorded.
2. **Ride the event payload** where the dispatch site owns the fact's meaning (an optimistic-create id the view must render now).
3. **Recorded coeffect** — `:rf/time-ms` for time, or a **`reg-cofx` recordable generator** for an app-owned world-read (a minted id, a localStorage read) that feeds durable state — for genuinely fold-internal facts the app owns but cannot pin at the call site.

Recorded coeffects are the *last* rung, not the default — but when you do need a world-read for a durable write, the recordable generator is the canonical way an app supplies it.

## Canonical mini-example — an app-owned recordable generator

A boot localStorage read fills durable app-db, so it is an **app-owned world-read that feeds durable state**. The application supplies it as a **`reg-cofx` recordable generator**: the generator runs at processing-start, its result is recorded onto the causal token, and replay re-presents the recorded value verbatim — so epoch-restore and SSR hydration re-fold the *captured* snapshot, never a live re-read of whatever localStorage holds now.

```clojure
;; The app REGISTERS the supplier. The generator reads the host once, at
;; processing-start; its result is recorded onto the token and re-presented
;; verbatim under replay / epoch-restore.
(rf/reg-cofx :todo.storage/todos
  {:recordable? true
   :doc "Saved TodoMVC items, read from localStorage (a recordable generator)."}
  (fn []
    (some-> (.-localStorage js/globalThis)
            (.getItem ls-key)
            (storage->todos))))
```

And the handler that ingests it — it declares the id and reads it flat, exactly as for any other coeffect:

```clojure
(rf/reg-event :todo/initialise
  {:rf.cofx/requires [:todo.storage/todos]}
  (fn [{:keys [db todo.storage/todos]} _]
    {:db (assoc db/default-db :todos todos)}))
```

The dispatch site is **plain** — `(rf/dispatch [:todo/initialise])`. It carries no cofx: the registered generator is the supplier, so the boot dispatch does not stamp the value. (`examples/patterns/websocket`, `examples/real-apps/realworld_http`, and `examples/patterns/nine_states` all ship this generator shape for their app-owned recordable ids.)

> **Why a generator, not an ambient read, and not a dispatch-site value?** The boot read decides a durable write (`:db`), and **durable state folds facts, never reads** — a live re-read at replay / SSR hydration would diverge from the recorded epoch. An *ambient* supplier re-runs on every replay (wrong for a durable write). A value stamped on the *dispatch* (`{:rf.cofx {:todo.storage/todos …}}`) is a **unit-test stub**, not a production shape — in production the app must register the supplier with `reg-cofx`. The recordable **generator** is the one production answer: registered by the app, run once at processing-start, recorded, and replayed verbatim.

## Decision tree — how does my handler get a world fact?

Pick the shape by what the fact is and whether the write that consumes it is durable. **In every production case the supplier is a `reg-cofx` registration** — the value is never stamped at the dispatch site outside a test.

- **App-owned world-read that feeds DURABLE state** (a minted id, a localStorage / sessionStorage read, any host fact the app itself can read) → **`reg-cofx` recordable generator** (`{:recordable? true}` + a value-returning `(fn [] …)`). The app registers the supplier; it runs at processing-start, is recorded, and replays verbatim. **This is the default for a world-read feeding durable state.** Durable-state world-reads MUST be recorded — a live re-read breaks cross-machine replay and SSR hydration.
- **Boundary / subsystem fact the app cannot compute from app-db** (an SSR server-stamp, the routing location, the wall-clock time) → **its own `reg-cofx` handler**, owned by the framework or subsystem. Time is the built-in `:rf/time-ms`; other boundary facts are registered by their owner (often `:provided? true`, stamped at the boundary). Still a registration — **not stamped at the app's dispatch site** in production.
- **Non-durable read** (a display preference, a diagnostic, a host-transient measurement, or a read of state that is *already* recorded durable state) → **ambient `reg-cofx`** (the default grade). It re-runs on replay, which is fine because no durable write depends on it.
- **Supplying a coeffect on the DISPATCH itself** (`(rf/dispatch [:e] {:rf.cofx {…}})`) → **unit-test stubs only.** This is how a test pins an exact recorded value for a recordable fact (a fixed id, a frozen clock) so the assertion is deterministic. **A production dispatch must never carry cofx handlers or values** — production world-reads come from `reg-cofx`, the supplier is the source.

The crux, restated: a **dispatch carrying cofx is a test seam**. Production coeffects are *registered*, not *dispatched*.

## Reading a sub from a handler — `subscribe-once`, wrapped as an ambient cofx

A handler that needs a sub's current value **must not** open a **reactive** subscription (`@(rf/subscribe ...)`) from its body — that leaks a reaction. The shipped one-shot read is **`rf/subscribe-once`** (Spec 006): it subscribes, derefs, and unsubscribes in one call, leaving no reaction behind. A bare `(rf/subscribe-once [:some/sub])` in a handler body is correct and supported.

The **preferred** shape, when the read should be reusable / parameterised / stubbable / named, is to wrap the `subscribe-once` read as an ambient cofx:

```clojure
(rf/reg-cofx :user/current
  {:doc "Ambient read of the [:user/current] sub value."}
  (fn [] (rf/subscribe-once [:user/current])))

(rf/reg-event :order/place
  {:rf.cofx/requires [:user/current]}
  (fn [{:keys [db user/current]} [_ order]]
    {:db (assoc-in db [:orders (:id order)] (assoc order :placed-by current))}))
```

Parameterise with the binary supplier + `[id arg]` declaration when the sub takes args:

```clojure
(rf/reg-cofx :sub/value
  (fn [query-v] (rf/subscribe-once query-v)))

(rf/reg-event :order/cancel
  {:rf.cofx/requires [[:sub/value [:order/by-id 42]]]}
  (fn [{:keys [db sub/value]} _] ...))
```

There is deliberately **no `cofx-from-sub` shortcut helper** in `re-frame.core` — the small `reg-cofx` wrapper is the canonical shape.

A sub value that feeds a **durable** write follows the durable-write rule: if the sub reads recorded durable state, the wrapped read is a read of already-recorded data (ambient is fine); if it reads ambient host state, the durable write needs that fact recorded instead.

## Testing — supply data, don't swap mechanisms

The testing story is *supply the facts; don't monkey-patch the clock or RNG*:

```clojure
;; 1. Pure handler test — no runtime, no mocks. The coeffects map is a
;;    literal; :rf.cofx/requires is the fixture checklist.
(deftest todo-create-pure-test
  (let [{:keys [db]} (todo-create {:db {} :rf/time-ms 1781078400123}
                                  [:todo/create {:id "t1" :text "x"}])]
    (is (= 1781078400123 (get-in db [:todos "t1" :created-at])))))

;; 2. Dispatch-level STUB — supply recordable facts in the :rf.cofx opt to pin
;;    an exact value (a frozen clock, a fixed id). This is the dispatch-site
;;    cofx seam, and it is a TEST-ONLY shape: a production dispatch never
;;    carries cofx — production world-reads come from `reg-cofx` suppliers.
(rf/dispatch-sync [:todo/create {:id "t1" :text "x"}]
                  {:rf.cofx {:rf/time-ms 1781078400123}})

;; 3. Ambient stub — the seam is RE-REGISTRATION (visible, greppable), never
;;    a monkey-patch; legal only because ambient facts never feed durable state.
(rf/reg-cofx :ui/local-theme {:doc "Test stub."} (fn [_k] "dark"))
```

The dispatch-opts key is `:rf.cofx` (`(rf/dispatch [:e] {:rf.cofx {...}})`); supplied values are preserved verbatim and never overwritten. **Reserve it for tests** — it is the seam that pins recordable facts for a deterministic assertion. A production dispatch carries no cofx; the app supplies its world-reads through `reg-cofx` (a generator for what it owns, a provided registration for boundary facts). The retired `:rf.world/inputs` dispatch opt is a hard error (`:rf.error/world-inputs-renamed`) naming `:rf.cofx`.

## Common gotchas

- **`reg-cofx` is value-returning now.** `(fn [] value)` or `(fn [arg] value)`. A ctx→ctx supplier (`(fn [ctx] (assoc-in ctx ...))`) is wrong shape — the returned ctx would be delivered as the value.
- **`:rf.cofx/requires` is registration metadata, not an interceptor.** It goes in the metadata-map slot (`(reg-event :id {:rf.cofx/requires [...]} handler)`). Actual interceptor chains also live in that map under `:interceptors`.
- **Declared-only delivery is about USER leaves.** You receive exactly the *user* coeffects you declare; an undeclared user leaf on the token is never staged — destructuring it gives `nil`. The base framework coeffects (`:db`, `:event`, `:rf.db/runtime`, `:rf.frame/id`, and the whole `:rf.cofx` map) are **always staged** on top of that and need no declaration — but they are filtered out of the Xray COEFFECTS lens, which shows only handler-declared leaves.
- **A durable write folds facts.** A timestamp / generated id / persisted host fact written into app-db must be a recorded fact (`:rf/time-ms`, the event payload, or a **`reg-cofx` recordable generator**) — never an ambient read, and never a value stamped on a production dispatch. Diagnostic / host-transient reads (deciding no durable write) stay ambient.
- **Production dispatches carry no cofx.** The `:rf.cofx` dispatch opt is a **unit-test stub seam** only. If you find yourself stamping a coeffect value onto a production `dispatch`, register a `reg-cofx` supplier instead — a recordable generator for an app-owned world-read, a provided registration for a boundary fact its owner stamps. The supplier is the coeffect source; the dispatch just names the event.
- **`:platforms #{:client}` skips the supplier under an SSR-server frame** (`:rf.cofx/skipped-on-platform` warning trace). The declaring handler sees no value for that id. Check this first if a server-side cofx mysteriously delivers nothing. Spec: `spec/011-SSR.md`.
- **A declared id with no registration is `:rf.error/unregistered-cofx`** (the typo case). A declared **provided** fact absent from the token is `:rf.error/missing-required-cofx` in every mode. A supplier that throws emits `:rf.error/coeffect-exception` and the handler is skipped.

## Deeper material

Full coeffect-map shape, the satisfaction algorithm, the error family, mint policies: `spec/002-Frames.md` §Recordable coeffects, `spec/001-Registration.md` §Coeffects, `docs/EP/EP-0017-recordable-coeffects.md`.

---

*Derived from `implementation/core/src/re_frame/cofx.cljc` + `spec/002-Frames.md` / `spec/001-Registration.md` (EP-0017). The recordable-generator machinery — generation at processing-start, schema + EDN validation, record/replay — is shipped and exercised by `examples/patterns/websocket`, `examples/real-apps/realworld_http`, and `examples/patterns/nine_states`. Citations are symbol-level.*
