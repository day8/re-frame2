# causal-world-inputs

**The rule (EP-0010 recording semantics + EP-0017 authoring surface):** if a host fact can affect a *durable write*, the transition MUST read that fact from a **causal token** (the dispatch / reply / restore envelope's `:rf.cofx` map) or from a **declared recordable coeffect** captured in that token — never by reading the host *ambiently* at the durable-write site. The whole discipline in one sentence: **durable state folds facts, never reads.** It is the one v2 rule the v1 *"stub the clock in tests"* habit does not satisfy — stubbing reduces flakes but the event log still can't explain why a state value has the timestamps, ids, locations, and random choices it has.

> **Scope — host facts, not db-derived reads.** This reference owns the durable-read judgment for **host facts** (clock, URL, window, storage, random). An in-handler read whose value is **derived from app-db** — an `@(rf/subscribe …)` deref, or any recompute of derived state inside the handler body — is *not* a host read and does *not* route through the host-fact buckets below; it is answered by the [source triage](#triage-in-handler-reads-by-source): read it from the `:db` coeffect the handler already holds — which under a path focus is the focused slice, so a root-derived value is read as the triage says. A framework sub such as `[:rf/machine <id>]` is the exception: its value is in runtime-db, which the handler holds as the `:rf.db/runtime` coeffect, not on `:db`. **db is a coeffect.**

> **The authoring surface is what a migrating app changes; the rule stays.** The recorded map is **`:rf.cofx`** (flat, one fact per owner-qualified key; a pre-release v2 build spelled it `:rf.world/inputs`); `reg-cofx` is now **value-returning**; coeffect delivery is the **`:rf.cofx/requires`** registration declaration; and **`inject-cofx` is removed**. A migrating app touches all four — see [M-72](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md#m-72-inject-cofx-removed--rfworldinputs--rfcofx-rename) for the mechanical reshape; this reference owns the **durable-read judgment** (which bucket each host read falls into).

Normative home: [`spec/002-Frames.md` §Recordable coeffects](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md#recordable-coeffects) (the `:rf.cofx` envelope field + the satisfaction algorithm), [`spec/001-Registration.md` §Coeffects](https://github.com/day8/re-frame2/blob/main/spec/001-Registration.md) (the `reg-cofx` grades + `:rf.cofx/requires`), and [`spec/Spec-Schemas.md`](https://github.com/day8/re-frame2/blob/main/spec/Spec-Schemas.md) (`:rf.cofx`). The recording rationale is in [`docs/EP/EP-0010-causal-world-inputs.md`](https://github.com/day8/re-frame2/blob/main/docs/EP/EP-0010-causal-world-inputs.md); the authoring surface in [`docs/EP/EP-0017-recordable-coeffects.md`](https://github.com/day8/re-frame2/blob/main/docs/EP/EP-0017-recordable-coeffects.md).

> **Why this matters for a migration.** Most of this skill's rules are loud-or-mechanical. This one is **silent**: an ambient host read in a handler *compiles clean, boots fine, and passes its own live-session tests* — it only betrays itself under replay, restore, SSR hydration, or a fixture re-run, where the same token now folds a different value. So it does not surface on the Phase-4 boot smoke-test the way a dropped dispatch does. Treat it as a **structural up-front grep** (below), and surface durable ambient reads as a report line even when the app runs correctly today.

<a id="triage-in-handler-reads-by-source"></a>

## Triage — split an in-handler ambient read by source

Before routing a host fact below, classify an ambient read found **inside a `reg-event` handler**, or an interceptor body on its chain, by its **source** — the two sources take different routes, and only one of them touches this reference's host-fact buckets:

- **DB-derived** — an `@(rf/subscribe …)` deref, or any value recomputed from app-db, sitting in the handler body. The handler already receives app-db as the **`:db` coeffect** (`(fn [{:keys [db]} _] …)`), so **read the slice / recompute the derived value from `:db`** — a registered sub's value with `(rf/compute-sub query-v db)`, which runs the sub's graph against the db you pass, with no reactive context ([API §Dispatch and subscribe](https://github.com/day8/re-frame2/blob/main/spec/API.md#dispatch-and-subscribe)). This is the trivial first answer — no coeffect, no causal token, no payload rewrite. Do **not** reach for a recordable `reg-cofx` (a value-returning supplier sees only its own arg and **cannot read app-db**) or an event-payload rewrite (it forces editing the dispatch site in another file); both fail for a db-derived value. **db is a coeffect** — that is where a derived read belongs.
  - **Framework state is not on `:db`.** A framework sub — `[:rf/machine <id>]`, `[:rf/route]`, any sub over runtime-db — reads the runtime-db partition, which the handler holds as the **`:rf.db/runtime`** coeffect. Compute it there: `(rf/compute-sub [:rf/machine <id>] (:rf.db/runtime cofx))` ([Conventions §Reserved sub-ids](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md#reserved-sub-ids)). Computed against `:db`, the same call returns `nil` with no error, so a producer guard reading a machine's tag off it never forwards and the converted boot stalls on its first await ([O-16 companion §Retarget the producers](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/async-flow-fx-to-reg-machine.md#retarget-the-producers--the-1-silent-stall-hazard), *Deliver only to a live, awaiting run*).
  - **Under a path focus, `:db` is the slice.** A `[:rf.interceptor/path …]` ref in the chain — the M-73 codemod lowers each `(rf/path …)` to one — stages the focused slice as the handler's `:db`. A value a v1 cofx derived from the ROOT db (the cofx sat ahead of `(rf/path …)` in the chain, or derefed `re-frame.db/app-db`) is therefore not on `:db` any more, and re-reading it there returns `nil` with no error. Read it from the root instead: `(rf/subscribe-once [:the/sub])` in the body, or wrapped as an ambient cofx ([`re-frame2` `cofx.md` §Reading a sub from a handler](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/cofx.md#reading-a-sub-from-a-handler--subscribe-once-wrapped-as-an-ambient-cofx)), or drop the path focus and `get-in` / `assoc-in` from the root. For each db-reading v1 cofx you remove, check its consumers' chains for a path ref.
  - **A helper shared with views.** `(defn current-user [] @(rf/subscribe [:user/current]))`, called from a view's render and from a handler, cannot simply switch to `:db`: the view has none. Give it a db arity — `([] @(rf/subscribe [:user/current])) ([db] (rf/compute-sub [:user/current] db))` — so views keep the 0-arity and handlers pass the root db.
  - **In an interceptor body.** A v1 `:before` / `:after` that subscribed reads the context instead. `(get-in ctx [:coeffects :db])` is the pre-handler app-db — what a v1 subscribe there saw, because v1's `do-fx` sits at the front of the chain and commits `:db` after every other `:after` — and `(get-in ctx [:effects :db])` is the handler's new one, when it returned one. For an interceptor placed after a path ref, both are the slice.
  - **Where none of these fits**, `(rf/subscribe-once query-v)` in the handler or interceptor body reads the sub on the event's frame, which the router binds for the whole run, and is supported ([`re-frame2` `cofx.md` §Reading a sub from a handler](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/cofx.md#reading-a-sub-from-a-handler--subscribe-once-wrapped-as-an-ambient-cofx)). A reactive `@(rf/subscribe …)` there is not: it leaks a reaction.
- **Host fact** — clock / URL / window / storage / random. These have no app-db source; route them through the host-fact buckets below (a recordable `reg-cofx`, a causal token, or the event payload).

The split mirrors the cofx doctrine: production host-reads come from a recordable `reg-cofx`; db-derived reads come from the state coeffects the handler already holds, `:db` and `:rf.db/runtime`. The rest of this reference is the **host-fact** half — a db-derived in-handler read is handled by the triage above, never by the host-fact routes.

## The classification — three buckets, only one is forced

Every host read the migration touches falls into exactly one bucket. The rewrite is forced **only** for the durable bucket.

- **Durable** — the read's value is written into `:db`, `:rf.db/runtime`, a resource entry, an attempt record, a machine snapshot, durable routing state, an epoch snapshot, or a hydration payload — **or it decides such a write without being stored anywhere**: a guard that picks which `:db` the handler returns, which effect it emits or suppresses, or which child event it dispatches. **MUST move to a causal token / recordable coeffect.** This is the only bucket that changes.
- **Diagnostic** — the read feeds a trace row, a performance span, a log line, a local devtool view, or an always-on error record that does **not** change frame-state. **May stay ambient.** (`now-ms` for a dev-only elapsed-time log is fine.)
- **Host-transient** — the read manages a timer handle, an `AbortController`, a socket, a promise, a DOM/listener handle, a cache cell, or a monotonic high-water allocator that lives *outside* frame-state. **May stay ambient**, but if its later result will affect durable state it must dispatch a causal token first (e.g. a stale-sweep timer re-reads the durable entry and writes using the *timer-fire event's* `:rf/time-ms`, not its own ambient clock).

The test for "durable": *does this value ride restore/replay/SSR, or would replaying the same token against different host state return a different result?* If yes, it is durable, and an ambient read of it is the violation.

## Identify — the up-front grep

A v1 / early-v2 app expresses these durable host reads as direct calls inside handlers and reducers. The searches below are **candidate searches** under [`inventory-and-plan.md` Step 5](inventory-and-plan.md#step-5--the-per-rule-completeness-gate): run them from the project root, so test, dev and shared source roots are covered (a source root outside the project directory goes beside `.`), accept a hit only where it resolves through the file's `ns` form in live code, then classify each accepted hit by bucket. An empty result is not a clean audit — the callee walk below still runs:

```bash
# Time (incl. raw js/Date constructor + .getTime, common in v1 JS-interop code)
rg -n -t clojure 'js/Date\.now|\(\.now js/Date\)|interop/now|\(now-ms\)|\(js/Date\.\)|\(new js/Date\b|\(\.getTime\b|/now\)' .
# Randomness + generated identity (incl. js/Math.random and crypto.randomUUID forms)
rg -n -t clojure 'random-uuid|\brand\b|rand-int|rand-nth|crypto\.getRandomValues|\(\.getRandomValues js/crypto\)|js/Math\.random|\(\.random js/Math\)|crypto\.randomUUID|\(\.randomUUID js/crypto\)' .
# Browser + storage facts
rg -n -t clojure 'js/location|js/window|js/document|\.-location|\.-href|js/navigator|localStorage|sessionStorage|matchMedia|innerWidth|innerHeight|cookie' .
# v1 ambient time coeffect + the removed inject-cofx delivery idiom + the
# renamed envelope field / ctx→ctx reg-cofx shape (all EP-0017 targets, M-72)
rg -n -t clojure 'inject-cofx|:rf.world/inputs|:rf.world/keys|:now\b' .
```

> **Keep these aligned with the framework's own check.** `scripts/check_ambient_durable_reads.py` is the authoritative ambient-durable-read detector for the re-frame2 codebase; its clock/random pattern list (`now-ms` / `js/Date.now` / `.now js/Date` / `random-uuid` / `getRandomValues`) is the canonical core. The extra raw-JS forms above (`(js/Date.)` + `.getTime`, `js/Math.random` / `.random js/Math`, `crypto.randomUUID` / `.randomUUID js/crypto`) are the ones a **v1 consumer app** commonly uses that the framework code does not — a migration grep that only covers the framework's set under-reports and can falsely conclude EP-0010 is clean. Include both.

For each hit: if the value flows into a durable write → it migrates (below). If it's diagnostic or host-transient → leave it, and note in the report *why* it's allowed to stay ambient.

**A hit inside a recordable supplier is the fix, not a site.** The generator this reference recommends, `(rf/reg-cofx :todo/id {:recordable? true} (fn [] (random-uuid)))`, still matches the randomness search, in live code. It is the durable bucket with its route already taken: the framework calls the supplier at processing-start and records its value in the token's `:rf.cofx`, and replay re-presents that value without calling it. So count each hit inside a value-returning `reg-cofx` supplier that is not registered `{:recordable? false}` as a **recorded generator**, apart from the sites, and a finished census reads zero sites beside its recorded generators. Two supplier hits are still sites: a hit in a v1 ctx→ctx supplier (`(fn [ctx] (assoc-in ctx …))`), which the M-72 reshape has not reached yet, and a hit in a `{:recordable? false}` supplier, which hands its consumers an ambient value — classify that one by what each consumer declaring the id does with it.

**Then walk the callees — a zero-hit census does not finish the audit.** The patterns match direct calls only. A handler that calls a helper over a host-owned atom reads the host just as ambiently, and nothing above matches it:

```clojure
(defonce host-editor (atom nil))                ; the host owner: holds the live JS editor
(defn editor-ready? [] (boolean @host-editor))

(rf/reg-event :document/maybe-save
  (fn [{:keys [db]} _]
    (if (editor-ready?)                         ; never stored, yet it picks the :db result
      {:db (assoc db :saved? true)}
      {})))
```

So walk the calls from each host read back up to the handlers that reach it (the procedure follows the fix below). A deref of an atom the host owns, or a host API reached through a helper, is a host read: classify it like a direct hit. Here the boolean selects the durable write, so it is durable although nothing stores it. Record the minimal normalized fact and leave the handle with its owner — a recordable coeffect whose supplier reduces the handle to EDN:

```clojure
(rf/reg-cofx :editor/ready? {:recordable? true} (fn [] (editor-ready?)))

(rf/reg-event :document/maybe-save
  {:rf.cofx/requires [:editor/ready?]}
  (fn [{:keys [db editor/ready?]} _]
    (if ready? {:db (assoc db :saved? true)} {})))
```

Live, the supplier runs at processing-start and `{:editor/ready? true}` is recorded in the token's `:rf.cofx`; replay re-presents it and takes the same branch whatever the editor is doing by then. Where the code that owns the editor also dispatches the event, the event payload (below) is the simpler route, at the cost of publishing the value with the event.

**Run the walk bottom-up, so it ends.** Seed it with every accepted hit that is not a recorded generator, and with every atom the host owns — a `defonce` holding a live JS object, as `host-editor` does. For each seed, name the top-level form that encloses it:

- a `reg-event*` body, or a machine's action or guard: a site, in the unit that owns that handler;
- a recordable supplier: a recorded generator, which ends that branch;
- a `defn`: search its name as a bare token across the project, which reaches an aliased call, a `:refer` and the fn passed as a value, and make each hit that resolves to it through its file's `ns` form a new seed.

Stop when no new fn appears. The walk is more than one command, so record it with the plan as [Step 5](inventory-and-plan.md#step-5--the-per-rule-completeness-gate) item 5 requires; a unit's check then re-runs it over that unit's files.

The walk does not mean recording every atom read. Leave these alone:

- a value derived from app-db or framework state, however many helpers it passes through — the [source triage](#triage-in-handler-reads-by-source) reads it from `:db` or `:rf.db/runtime`, and a helper views share takes a db arity;
- a read that feeds only a log line, a trace or a devtool view (diagnostic);
- a read that only manages the handle — creating, focusing or disposing the editor in an effect or a lifecycle hook — with no durable result (host-transient).

## Route time → declare `:rf/time-ms`

The dispatch envelope carries `:rf.cofx`, a flat plain-EDN map; the framework's one built-in fact is **`:rf/time-ms`** (wall-clock epoch millis), stamped once at enqueue when the caller omits it; tests, replay, and SSR supply it. A handler takes delivery by **declaring** it and reads it flat:

```clojure
(rf/reg-event
  :article/load-succeeded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [id article]}]]
    {:db (assoc-in db [:articles id]
                   {:body article
                    :loaded-at time-ms})}))   ;; was (interop/now-ms)
```

The migration path: replace `(interop/now-ms)` / `js/Date.now` / `(.now js/Date)` at a durable write with a `:rf.cofx/requires [:rf/time-ms]` declaration + a flat `rf/time-ms` read.

> **No compatibility-cofx shortcut anymore.** EP-0017 removed `inject-cofx` and made `reg-cofx` value-returning, so the v1 trick of wrapping a `:now` cofx that reads the envelope is gone — and unnecessary. A pervasive v1 `:now` cofx migrates by deleting the cofx and declaring `:rf/time-ms` on the consumers. If a consumer-side keyword churn is genuinely undesirable, an **ambient** wrapper supplier may re-expose the time under an app id — but it cannot read another coeffect (suppliers take only their own arg), so it would re-read the host and **break replay** for any durable consumer; do **not** use a wrapper for durable time. Declare `:rf/time-ms` directly.

**A v1 time cofx that returned a date object.** Not every v1 `:now` returned millis: many return a `js/Date`, a `goog.date.DateTime`, or a cljs-time, js-joda, Luxon or moment value, and their consumers call its API — fields, formatting, interval arithmetic. Before deleting such a cofx, grep the consumers of its id for those calls. Each one declares `:rf/time-ms` and builds the object at the read site — `(js/Date. time-ms)`, or the library's from-millis constructor — so the recorded fact stays a number. Keeping the cofx is no shortcut: a supplier returning a library date object fails `:rf.error/cofx-value-invalid`, and a `js/Date` supplier, which records as `#inst`, is a second clock, sampled at processing-start rather than at enqueue like `:rf/time-ms` ([Spec 002 §Recordable coeffects](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md#recordable-coeffects)).

`:rf/time-ms` is the durable time contract. The old dev-only `:dispatched-at` envelope field is **gone** — durable code declares `:rf/time-ms`.

## UUID / random / browser / storage → event payloads or recordable facts

These follow the same boundary. If the generated or read value becomes durable, it must be a recorded fact. The `:rf.cofx` map is **flat** (one fact per owner-qualified key — no `:uuid {…}` / `:random […]` grouping sub-maps). Use the **event payload** when the caller owns the value, a **recordable generator** when the fold needs a fresh fact, or a **provided** recordable fact when a boundary owns it. A value on the payload is published with the event: every interceptor on the chain, the trace stream and epoch history see the event vector, and a handler that stores the event, in a pending or retry list say, keeps a copy in app-db that needs its own commit-plane `:sensitive` mark ([Spec 015 §Durable app-db](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects)), because a mark on the `reg-event` ([§Registration-owned transient classification](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md#registration-owned-transient-classification)) does not propagate to it. The dev payload lint (`:rf.warning/non-serialisable-event-payload`) walks map values and vector and set elements only, so a fn or host object in the payload's metadata draws no warning. Both coeffect routes require a `reg-cofx` registration and a consumer's `:rf.cofx/requires` declaration; supplying an unregistered key on the token does not register it.

- **Generated identity (UUID).** A durable entity id minted with `random-uuid` inside a handler is a world fact. The minting ladder's preferred rung is the **event payload** — the caller pins the id and the view can render it optimistically:

  ```clojure
  (rf/dispatch [:todo/create {:todo/id (random-uuid) :text text}])  ;; minted at the call site
  ;; handler: read (:todo/id payload) — never (random-uuid) in the fold.
  ```

  When the id is genuinely fold-internal (no call site owns it), register a recordable generator and declare it on the consumer:

  ```clojure
  (rf/reg-cofx :todo/id {:recordable? true} (fn [] (random-uuid)))
  (rf/reg-event :todo/create
    {:rf.cofx/requires [:todo/id]}
    (fn [{:keys [db todo/id]} [_ {:keys [text]}]]
      {:db (assoc-in db [:todos id] {:todo/id id :text text})}))
  ```

  In live mode an absent declared fact is generated at processing-start and recorded in `:rf.cofx`; a supplied or replayed value wins without calling the generator. Strict mode (including the `:test` frame preset) rejects an absent fact with `:rf.error/missing-required-cofx`, so deterministic tests supply it. The `:rf/default` frame the reset fixture seats has no `:preset`, so it mints under the router's `:live` default: a declared recordable generator left unpinned runs fresh on each dispatch, so a test that never reads the value passes unchanged and only a test asserting it needs the `:rf.cofx` pin. A frame made with `:preset :test` is `:strict`: there an unpinned generator fails the dispatch with `:rf.error/missing-required-cofx` until the test supplies the fact or passes `{:rf.cofx/mint-policy :live}` in the dispatch opts. So after converting a handler, check its tests for assertions on the generated value and for frames made with `:preset :test`. See [Spec 002 §Mint policies](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md#mint-policies).

  Alternatively, when a boundary always supplies the id, use a **provided** registration instead of the generator above: `(rf/reg-cofx :todo/id {:recordable? true :provided? true})`. It has no supplier; omitting its declared value fails in every mode. The same consumer takes either registration's value flat as `todo/id`. Supply the fact through the dispatch opts:

  ```clojure
  (rf/dispatch [:todo/create {:text text}]
               {:rf.cofx {:rf/time-ms 1781078400123
                          :todo/id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}})
  ;; Works with either registration above; a supplied value bypasses generation.
  ```

  **One id per row.** A fold that mints an id for each row of a collection, `(mapv #(assoc % :id (random-uuid)) rows)`, cannot record one fact per row: a recordable coeffect delivers one value per declared id, and its supplier cannot read app-db to size a batch. Where the rows arrive in the payload, the caller knows the count and mints each row's id there, on the payload rung above. Where app-db decides the count, record ONE fresh id and derive each row's id from it as data, `[id i]`; when several kinds of row share one id space, give each kind its own lane, `[id :kind i]`, or two kinds both derive `[id 0]`. The recipe, and why a seed or a fixed batch of ids is wrong, is the [Coeffects guide §When the number of ids depends on state](https://github.com/day8/re-frame2/blob/main/docs/core/coeffects.md#when-the-number-of-ids-depends-on-state).

- **Random choices.** Record the **chosen value**, not a seed (host RNG algorithms and collection ordering are not portable). A seed is acceptable only when the algorithm and input order are named and stable. **A secret is excluded entirely** — key material, a session token, a nonce that must stay secret: it must NOT flow through recordable coeffects (a recorded choice would *be* the secret, durably embedded in epoch history / replay fixtures). Secrets are generated in effects on the host side; only derived or server-issued facts become durable. Draw that line by what the value is, not by its name or the generator that made it: an idempotency key, a request or correlation id, or a client-minted entity id is not secret, because the server sees, logs and echoes it, and replay needs the same value, or a replayed retry sends a different key and defeats the deduplication. It is generated identity: record it as the bullet above does.

- **Browser + storage facts.** A handler reading `js/location` / `navigator` / `localStorage` while writing durable state is the violation. The host adapter reads the browser **at the boundary** and dispatches a causal token carrying normalized EDN (strings/booleans/numbers/keywords — never the live host object) — either on the event payload, or as a recordable fact in `:rf.cofx`:

  ```clojure
  (rf/dispatch [:route/location-changed
                {:location {:path "/articles/welcome" :query "?preview=true"}}]
               {:source :router
                :rf.cofx {:rf/time-ms 1781078400123}})
  ```

  The same pattern covers `visibilitychange`, `online`, `storage`, and media-query changes — and `localStorage` reads that seed boot state arrive on the boot / restore token.

## Effect-handler boundary + reply completion facts

Effect handlers (HTTP, etc.) **may** touch the host freely — they are the boundary that turns effect data into external work. The rule bites only when their outcome returns to the fold. A managed async effect that can complete with durable writes dispatches a **reply token** carrying completion world facts (especially completion time). The reply handler reads `:completed-at` from the reply token — it does **not** re-read `(interop/now-ms)` while writing `:loaded-at` / `:settled-at`. (Resource `:loaded-at` / `:stale-at`, attempt `:started-at` / `:deadline-at` / `:completed-at`, and mutation `:started-at` / `:settled-at` are all durable runtime-db facts that come from token/reply world inputs. The standardized reply shape and these suffixless durable timestamp keys are EP-0011's surface.)

## What this does NOT touch (leave ambient)

Do not "fix" these — flagging an allowed ambient read as a violation is itself an error:

- dev-only trace + performance elapsed values;
- host timer scheduling / cancellation, request-timeout measurement by the effect interpreter;
- a host-transient sweep's wake decision (provided it dispatches a causal token before any durable write);
- server/client clock-skew diagnostics;
- animation jitter, decorative particle positions, local non-durable retry jitter, performance sampling (host-transient randomness);
- host-side monotonic allocators (generation counters, work-id high-water marks) — these only move forward and are host-transient by classification; restore does not rewind them.

A `reg-cofx` that supplies one of these registers `{:recordable? false}`. A v2 coeffect is recordable unless it says so, which a v1 cofx never was: an unmarked registration is recorded and replayed, and a supplier returning a host object fails `:rf.error/cofx-value-invalid` the first time it runs — a library date object included. A `js/Date` is the exception: it records as `#inst`.

## Reporting

World-input migration is **report-worthy even when the app runs** — the failure is silent (replay/restore-only), invisible to the boot smoke-test, so the report is the only record that the boundary was checked. List each durable ambient read found, the bucket you assigned it, and the route taken (envelope / payload / recordable cofx) or — for diagnostic/host-transient — the reason it stayed ambient.
