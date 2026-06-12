# Coeffects (cofx)

## When to load

Registering a coeffect supplier (current time, a localStorage value, a generated id, a sub's value, …) with `reg-cofx`; or declaring a handler's coeffect dependencies with `:rf.cofx/requires`.

## The one mental model (EP-0017)

A coeffect is a **fact the causal run consumed** — data from outside the event. There is **one registrar** (`reg-cofx`, a value-returning supplier) and **one declaration surface** (`:rf.cofx/requires`, registration metadata). A handler receives `:db`, `:event` (the fold's own arguments) **plus exactly the facts it declares**, delivered flat under their ids. Nothing is delivered implicitly — including the time.

> **`inject-cofx` is REMOVED (EP-0017, no alias).** Calling it is the hard error `:rf.error/inject-cofx-removed`. Declare the coeffect on the handler's registration metadata instead. If you are migrating a v1 app, see [`skills/re-frame-migration/references/causal-world-inputs.md`].

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
- `:schema` — Malli schema validating supplied / replayed values. (The `:schema` *validation step* is built in slice B; declare it now so the contract is recorded.)
- `:platforms` — `#{:client :server}`; defaults to both. A supplier tagged `:platforms #{:client}` is **skipped** on server-side frames (emits `:rf.cofx/skipped-on-platform`; the declaring handler sees no value for that id). Mirrors `reg-fx`'s contract per `spec/011-SSR.md`. Example: `(rf/reg-cofx :browser-locale {:platforms #{:client}} (fn [] js/navigator.language))` is safe to register on both platforms; under an `:ssr-server` frame the supplier is skipped.

> **`:platforms` lives in the metadata-map, NOT reader metadata.** `reg-cofx` reads `:platforms` only from the optional metadata-map argument. Reader metadata on the form (`^{:platforms #{:client}} (rf/reg-cofx ...)`) is **never consulted** — it would register a browser-only supplier for both platforms.

## Declaration — `:rf.cofx/requires`

A handler takes delivery by declaring the ids it consumes in `:rf.cofx/requires` (Spec 001 standard metadata key, on `reg-event-fx` and `reg-event-ctx`). The declared values arrive **flat** in the coeffects map under their ids — never a nested `:cofx` sub-map:

```clojure
(rf/reg-event-fx :counter/inc
  {:doc "Increment by a delta, stamping the durable update time."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (-> db (update :count inc) (assoc :last-updated-at time-ms))}))
```

- A parameterized id appears as `[id arg]` (mirroring the binary supplier arity): `:rf.cofx/requires [[:ui/local-theme "theme"]]`.
- Delivery is **declared-only**: a leaf on the token but not declared by this handler is **not staged** (`handler-meta` is the complete consumption record). Forgetting to declare what you destructure is a lint target ("consuming without declaring").
- On `reg-event-db`, `:rf.cofx/requires` is a registration-time error — a db handler receives only the db and cannot take delivery. Needing the world is what graduates a handler to the `fx` form.

## The two grades — ambient vs recordable

The grade is a property of the registration, not a namespace:

- **Ambient** (default) — the supplier runs at context assembly, the value is delivered to declaring handlers and **never recorded**; replay re-runs the supplier. Legal **only where no durable write depends on the value** — display preferences, diagnostics, host-transient measurements, or a read of state that is *already* recorded durable state.
- **Recordable** (`:recordable? true`) — the fact is **ensured onto the causal token**, recorded, and re-presented verbatim by replay. Required for any fact that can affect durable frame-state. A `:provided? true` recordable fact has **no generator** — its owner (framework, subsystem, dispatch boundary) stamps the value. App-owned generator-backed recordable suppliers are slice B.

**The durable-write rule (the whole discipline in one sentence): durable state folds facts, never reads.** A transition that performs a durable write — anything written to app-db, runtime-db, a resource, a machine snapshot, a work-ledger row, or a hydration payload — MUST consume world facts from the token's recordable coeffects (or the event payload), never from an ambient read at the write site. Otherwise the same event replays to a different value (epoch restore / SSR hydration / time-travel all diverge).

### `:rf/time-ms` — the framework's one built-in coeffect

The framework ships exactly **one** registration: `:rf/time-ms` — recordable, provided, stamped onto every dispatch and reply envelope at enqueue. It is the canonical durable wall-clock fact. A handler that folds a timestamp into durable state declares `:rf.cofx/requires [:rf/time-ms]` and reads `time-ms` flat. **Do not register a `:now` cofx that reads `js/Date` for a durable timestamp** — declare `:rf/time-ms`.

```clojure
(rf/reg-event-fx :todo/create
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id text]}]]
    ;; the durable id rides the event payload (the caller pinned it);
    ;; the durable time rides the recorded :rf/time-ms fact.
    {:db (assoc-in db [:todos id] {:text text :created-at time-ms})}))
```

(A bare `(random-uuid)` or `(js/Date.now)` at the write site would re-roll on every replay. Durable ids ride the event payload from a caller that pinned them, or — slice B — a recordable generator cofx; durable time rides `:rf/time-ms`.)

## The minting ladder (where does X come from?)

"My handler needs X from the world" resolves in preference order:

1. **Derive from recorded state** where possible (e.g. a monotone counter already in a snapshot). No new fact recorded.
2. **Ride the event payload** where the dispatch site owns the fact's meaning (an optimistic-create id the view must render now).
3. **Recorded coeffect** (`:rf/time-ms`, or a slice-B generator) only for genuinely fold-internal facts.

Recorded coeffects are the *last* rung, not the default.

## Canonical mini-example — an ambient supplier

From `examples/reagent/todomvc/db.cljs` (the boot localStorage read):

```clojure
(rf/reg-cofx :todo.storage/todos
  {:doc "Ambient localStorage read for the saved TodoMVC items."}
  (fn [] (some-> (.-localStorage js/globalThis)
                 (.getItem ls-key)
                 (storage->todos))))
```

And the handler that ingests it:

```clojure
(rf/reg-event-fx :todo/initialise
  {:rf.cofx/requires [:todo.storage/todos]}
  (fn [{:keys [todo.storage/todos]} _]
    {:db (assoc db/default-db :todos todos)}))
```

The supplier returns the value; the handler declares the id and reads it flat off the coeffects map.

> **Is this read durable?** `:todo/initialise` writes the localStorage value straight into `:db`, so on a strict reading the boot read decides a durable write. In practice the localStorage value *is* the persisted durable state — there is no recorded epoch before boot to diverge from — so an ambient supplier is the right shape for a boot/rehydrate read. A storage read that feeds durable state *mid-session* (where a recorded epoch exists to replay against) would instead need to arrive as recorded data. The fork is about whether a prior recorded epoch can diverge, not about the call being impure.

## Reading a sub from a handler — `subscribe-once`, wrapped as an ambient cofx

A handler that needs a sub's current value **must not** open a **reactive** subscription (`@(rf/subscribe ...)`) from its body — that leaks a reaction. The shipped one-shot read is **`rf/subscribe-once`** (Spec 006): it subscribes, derefs, and unsubscribes in one call, leaving no reaction behind. A bare `(rf/subscribe-once [:some/sub])` in a handler body is correct and supported.

The **preferred** shape, when the read should be reusable / parameterised / stubbable / named, is to wrap the `subscribe-once` read as an ambient cofx:

```clojure
(rf/reg-cofx :user/current
  {:doc "Ambient read of the [:user/current] sub value."}
  (fn [] (rf/subscribe-once [:user/current])))

(rf/reg-event-fx :order/place
  {:rf.cofx/requires [:user/current]}
  (fn [{:keys [db user/current]} [_ order]]
    {:db (assoc-in db [:orders (:id order)] (assoc order :placed-by current))}))
```

Parameterise with the binary supplier + `[id arg]` declaration when the sub takes args:

```clojure
(rf/reg-cofx :sub/value
  (fn [query-v] (rf/subscribe-once query-v)))

(rf/reg-event-fx :order/cancel
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

;; 2. Dispatch-level — supply recordable facts in the :rf.cofx opt.
(rf/dispatch-sync [:todo/create {:id "t1" :text "x"}]
                  {:rf.cofx {:rf/time-ms 1781078400123}})

;; 3. Ambient stub — the seam is RE-REGISTRATION (visible, greppable), never
;;    a monkey-patch; legal only because ambient facts never feed durable state.
(rf/reg-cofx :ui/local-theme {:doc "Test stub."} (fn [_k] "dark"))
```

The dispatch-opts key is `:rf.cofx` (`(rf/dispatch [:e] {:rf.cofx {...}})`); supplied values are preserved verbatim and never overwritten. The retired `:rf.world/inputs` dispatch opt is a hard error (`:rf.error/world-inputs-renamed`) naming `:rf.cofx`.

## Common gotchas

- **`reg-cofx` is value-returning now.** `(fn [] value)` or `(fn [arg] value)`. A ctx→ctx supplier (`(fn [ctx] (assoc-in ctx ...))`) is wrong shape — the returned ctx would be delivered as the value.
- **`:rf.cofx/requires` is registration metadata, not an interceptor.** It goes in the metadata-map slot (`(reg-event-fx :id {:rf.cofx/requires [...]} handler)`), not the positional interceptors vector.
- **Declared-only delivery.** You receive exactly what you declare. An undeclared leaf on the token is never staged — destructuring it gives `nil`.
- **A durable write folds facts.** A timestamp / generated id / persisted host fact written into app-db must be a recorded fact (`:rf/time-ms`, the event payload, or a slice-B recordable generator) — never an ambient read. Diagnostic / host-transient reads (deciding no durable write) stay ambient.
- **`:platforms #{:client}` skips the supplier under an SSR-server frame** (`:rf.cofx/skipped-on-platform` warning trace). The declaring handler sees no value for that id. Check this first if a server-side cofx mysteriously delivers nothing. Spec: `spec/011-SSR.md`.
- **A declared id with no registration is `:rf.error/unregistered-cofx`** (the typo case). A declared **provided** fact absent from the token is `:rf.error/missing-required-cofx` in every mode. A supplier that throws emits `:rf.error/coeffect-exception` and the handler is skipped.

## Deeper material

Full coeffect-map shape, the satisfaction algorithm, the error family, mint policies: `spec/002-Frames.md` §Recordable coeffects, `spec/001-Registration.md` §Coeffects, `docs/EP/EP-0017-recordable-coeffects.md`.

---

*Derived from `implementation/core/src/re_frame/cofx.cljc` + `spec/002-Frames.md` / `spec/001-Registration.md` (EP-0017 slice A). Citations are symbol-level; re-verify symbol homes after the slice-B generation/validation work lands.*
