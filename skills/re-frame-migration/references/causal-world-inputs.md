# causal-world-inputs

**The rule (EP-0010 recording semantics + EP-0017 authoring surface):** if a host fact can affect a *durable write*, the transition MUST read that fact from a **causal token** (the dispatch / reply / restore envelope's `:rf.cofx` map) or from a **declared recordable coeffect** captured in that token — never by reading the host *ambiently* at the durable-write site. The whole discipline in one sentence: **durable state folds facts, never reads.** It is the one v2 rule the v1 *"stub the clock in tests"* habit does not satisfy — stubbing reduces flakes but the event log still can't explain why a state value has the timestamps, ids, locations, and random choices it has.

> **Scope — host facts, not db-derived reads.** This reference owns the durable-read judgment for **host facts** (clock, URL, window, storage, random). An in-handler read whose value is **derived from app-db** — an `@(rf/subscribe …)` deref, or any recompute of derived state inside the handler body — is *not* a host read and does *not* route through the host-fact buckets below; it is answered by the [source triage](#triage-in-handler-reads-by-source): read it from the `:db` coeffect the handler already holds. **db is a coeffect.**

> **EP-0017 changed the authoring surface, not the rule.** The recorded map was renamed `:rf.world/inputs` → **`:rf.cofx`** (flat, one fact per owner-qualified key); `reg-cofx` is now **value-returning**; coeffect delivery is the **`:rf.cofx/requires`** registration declaration; and **`inject-cofx` is removed**. A migrating app touches all four — see [M-72](../../../migration/from-re-frame-v1/README.md#m-72-inject-cofx-removed--rfworldinputs--rfcofx-rename) for the mechanical reshape; this reference owns the **durable-read judgment** (which bucket each host read falls into).

Normative home: [`spec/002-Frames.md` §Recordable coeffects](../../../spec/002-Frames.md#recordable-coeffects) (the `:rf.cofx` envelope field + the satisfaction algorithm), [`spec/001-Registration.md` §Coeffects](../../../spec/001-Registration.md) (the `reg-cofx` grades + `:rf.cofx/requires`), and [`spec/Spec-Schemas.md`](../../../spec/Spec-Schemas.md) (`:rf.cofx`). The recording rationale is in [`docs/EP/EP-0010-causal-world-inputs.md`](../../../docs/EP/EP-0010-causal-world-inputs.md); the authoring surface in [`docs/EP/EP-0017-recordable-coeffects.md`](../../../docs/EP/EP-0017-recordable-coeffects.md).

> **Why this matters for a migration.** Most of this skill's rules are loud-or-mechanical. This one is **silent**: an ambient host read in a handler *compiles clean, boots fine, and passes its own live-session tests* — it only betrays itself under replay, restore, SSR hydration, or a fixture re-run, where the same token now folds a different value. So it does not surface on the Phase-4 boot smoke-test the way a dropped dispatch does. Treat it as a **structural up-front grep** (below), and surface durable ambient reads as a report line even when the app runs correctly today.

<a id="triage-in-handler-reads-by-source"></a>

## Triage — split an in-handler ambient read by source

Before routing a host fact below, classify an ambient read found **inside a `reg-event` handler** by its **source** — the two sources take different routes, and only one of them touches this reference's host-fact buckets:

- **DB-derived** — an `@(rf/subscribe …)` deref, or any value recomputed from app-db, sitting in the handler body. The handler already receives app-db as the **`:db` coeffect** (`(fn [{:keys [db]} _] …)`), so **read the slice / recompute the derived value from `:db`**. This is the trivial first answer — no coeffect, no causal token, no payload rewrite. Do **not** reach for a recordable `reg-cofx` (a value-returning supplier sees only its own arg and **cannot read app-db**) or an event-payload rewrite (it forces editing the dispatch site in another file); both fail for a db-derived value. **db is a coeffect** — that is where a derived read belongs.
- **Host fact** — clock / URL / window / storage / random. These have no app-db source; route them through the host-fact buckets below (a recordable `reg-cofx`, a causal token, or the event payload).

The split mirrors the cofx doctrine: production host-reads come from a recordable `reg-cofx`; db-derived reads come from the `:db` coeffect the handler already holds. The rest of this reference is the **host-fact** half — a db-derived in-handler read is handled by the triage above, never by the host-fact routes.

## The classification — three buckets, only one is forced

Every host read the migration touches falls into exactly one bucket. The rewrite is forced **only** for the durable bucket.

- **Durable** — the read's value is written into `:db`, `:rf.db/runtime`, a resource entry, a work-ledger row, a machine snapshot, durable routing state, an epoch snapshot, or a hydration payload. **MUST move to a causal token / recordable coeffect.** This is the only bucket that changes.
- **Diagnostic** — the read feeds a trace row, a performance span, a log line, a local devtool view, or an always-on error record that does **not** change frame-state. **May stay ambient.** (`now-ms` for a dev-only elapsed-time log is fine.)
- **Host-transient** — the read manages a timer handle, an `AbortController`, a socket, a promise, a DOM/listener handle, a cache cell, or a monotonic high-water allocator that lives *outside* frame-state. **May stay ambient**, but if its later result will affect durable state it must dispatch a causal token first (e.g. a stale-sweep timer re-reads the durable entry and writes using the *timer-fire event's* `:time-ms`, not its own ambient clock).

The test for "durable": *does this value ride restore/replay/SSR?* If yes, it is durable, and an ambient read of it is the violation.

## Identify — the up-front grep

A v1 / early-v2 app expresses these durable host reads as direct calls inside handlers and reducers. Grep for them, then classify each hit by bucket:

```bash
# Time (incl. raw js/Date constructor + .getTime, common in v1 JS-interop code)
rg -n 'js/Date\.now|\(\.now js/Date\)|interop/now-ms|\(now-ms\)|\(js/Date\.\)|\(\.getTime\b' src
# Randomness + generated identity (incl. js/Math.random and crypto.randomUUID forms)
rg -n 'random-uuid|\brand\b|rand-int|rand-nth|crypto\.getRandomValues|\(\.getRandomValues js/crypto\)|js/Math\.random|\(\.random js/Math\)|crypto\.randomUUID|\(\.randomUUID js/crypto\)' src
# Browser + storage facts
rg -n 'js/location|js/navigator|localStorage|sessionStorage|matchMedia' src
# v1 ambient time coeffect + the removed inject-cofx delivery idiom + the
# renamed envelope field / ctx→ctx reg-cofx shape (all EP-0017 targets, M-72)
rg -n 'inject-cofx|:rf.world/inputs|:rf.world/keys|:now\b' src
```

> **Keep these aligned with the framework's own check.** `scripts/check_ambient_durable_reads.py` is the authoritative ambient-durable-read detector for the re-frame2 codebase; its clock/random pattern list (`now-ms` / `js/Date.now` / `.now js/Date` / `random-uuid` / `getRandomValues`) is the canonical core. The extra raw-JS forms above (`(js/Date.)` + `.getTime`, `js/Math.random` / `.random js/Math`, `crypto.randomUUID` / `.randomUUID js/crypto`) are the ones a **v1 consumer app** commonly uses that the framework code does not — a migration grep that only covers the framework's set under-reports and can falsely conclude EP-0010 is clean. Include both.

For each hit: if the value flows into a durable write → it migrates (below). If it's diagnostic or host-transient → leave it, and note in the report *why* it's allowed to stay ambient.

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

`:rf/time-ms` is the durable time contract. The old dev-only `:dispatched-at` envelope field is **gone** — durable code declares `:rf/time-ms`.

## UUID / random / browser / storage → event payloads or recordable facts

These follow the same boundary. If the generated or read value becomes durable, it must be a recorded fact. The `:rf.cofx` map is **flat** (one fact per owner-qualified key — no `:uuid {…}` / `:random […]` grouping sub-maps), and app-owned **recordable generators** are slice B, so in slice A the realistic routes are the **event payload** (preferred — the caller pins the id) or a **provided** recordable fact stamped by a boundary.

- **Generated identity (UUID).** A durable entity id minted with `random-uuid` inside a handler is a world fact. The minting ladder's preferred rung is the **event payload** — the caller pins the id and the view can render it optimistically:

  ```clojure
  (rf/dispatch [:todo/create {:todo/id (random-uuid) :text text}])  ;; minted at the call site
  ;; handler: read (:todo/id payload) — never (random-uuid) in the fold.
  ```

  When the id is genuinely fold-internal (no call site owns it), it rides a recordable fact: declare `:rf.cofx/requires [:my/entity-id]` and (slice B) register an app-owned recordable generator, or supply the value in the `:rf.cofx` dispatch opt:

  ```clojure
  (rf/dispatch [:todo/create {:text text}]
               {:rf.cofx {:rf/time-ms 1781078400123
                          :todo/id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}})
  ;; handler: {:rf.cofx/requires [:todo/id]} → read `todo/id` flat — not (random-uuid)
  ```

- **Random choices.** Record the **chosen value**, not a seed (host RNG algorithms and collection ordering are not portable). A seed is acceptable only when the algorithm and input order are named and stable. **Crypto-grade randomness — session tokens, keys, nonces — is excluded entirely:** it must NOT flow through recordable coeffects (a recorded choice would *be* the secret, durably embedded in epoch history / replay fixtures). Secrets are generated in effects on the host side; only derived or server-issued facts become durable.

- **Browser + storage facts.** A handler reading `js/location` / `navigator` / `localStorage` while writing durable state is the violation. The host adapter reads the browser **at the boundary** and dispatches a causal token carrying normalized EDN (strings/booleans/numbers/keywords — never the live host object) — either on the event payload, or as a recordable fact in `:rf.cofx`:

  ```clojure
  (rf/dispatch [:route/location-changed
                {:location {:path "/articles/welcome" :query "?preview=true"}}]
               {:source :router
                :rf.cofx {:rf/time-ms 1781078400123}})
  ```

  The same pattern covers `visibilitychange`, `online`, `storage`, and media-query changes — and `localStorage` reads that seed boot state arrive on the boot / restore token.

## Effect-handler boundary + reply completion facts

Effect handlers (HTTP, etc.) **may** touch the host freely — they are the boundary that turns effect data into external work. The rule bites only when their outcome returns to the fold. A managed async effect that can complete with durable writes dispatches a **reply token** carrying completion world facts (especially completion time). The reply handler reads `:completed-at` from the reply token — it does **not** re-read `(interop/now-ms)` while writing `:loaded-at` / `:settled-at`. (Resource `:loaded-at` / `:stale-at`, work-ledger `:started-at` / `:deadline-at` / `:completed-at`, and mutation `:started-at` / `:settled-at` are all durable runtime-db facts that come from token/reply world inputs. The standardized reply shape and these suffixless durable timestamp keys are EP-0011's surface.)

## What this does NOT touch (leave ambient)

Do not "fix" these — flagging an allowed ambient read as a violation is itself an error:

- dev-only trace + performance elapsed values;
- host timer scheduling / cancellation, request-timeout measurement by the effect interpreter;
- a host-transient sweep's wake decision (provided it dispatches a causal token before any durable write);
- server/client clock-skew diagnostics;
- animation jitter, decorative particle positions, local non-durable retry jitter, performance sampling (host-transient randomness);
- host-side monotonic allocators (generation counters, work-id high-water marks) — these only move forward and are host-transient by classification; restore does not rewind them.

## Reporting

World-input migration is **report-worthy even when the app runs** — the failure is silent (replay/restore-only), invisible to the boot smoke-test, so the report is the only record that the boundary was checked. List each durable ambient read found, the bucket you assigned it, and the route taken (envelope / payload / recordable cofx) or — for diagnostic/host-transient — the reason it stayed ambient.
