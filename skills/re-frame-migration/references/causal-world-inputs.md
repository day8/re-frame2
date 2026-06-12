# causal-world-inputs

**The rule (EP-0010, `final`):** if a host fact can affect a *durable write*, the transition MUST read that fact from a **causal token** (the dispatch / reply / restore envelope) or from a **recordable coeffect** captured in that token — never by reading the host *ambiently* at the durable-write site. This is a **semantic tightening**, not an API break: handlers that don't touch the host need no change. It is the one v2 rule the v1 *"stub the clock in tests"* habit does not satisfy — stubbing reduces flakes but the event log still can't explain why a state value has the timestamps, ids, locations, and random choices it has.

Normative home: [`spec/002-Frames.md` §Causal world inputs](../../../spec/002-Frames.md#causal-world-inputs) (the `:rf.world/inputs` envelope field + the framework coeffect) and [`spec/Spec-Schemas.md`](../../../spec/Spec-Schemas.md) (`:rf.world/inputs`). The full rationale and the worked before→after examples are in [`docs/EP/EP-0010-causal-world-inputs.md`](../../../docs/EP/EP-0010-causal-world-inputs.md).

> **Why this matters for a migration.** Most of this skill's rules are loud-or-mechanical. This one is **silent**: an ambient host read in a handler *compiles clean, boots fine, and passes its own live-session tests* — it only betrays itself under replay, restore, SSR hydration, or a fixture re-run, where the same token now folds a different value. So it does not surface on the Phase-4 boot smoke-test the way a dropped dispatch does. Treat it as a **structural up-front grep** (below), and surface durable ambient reads as a report line even when the app runs correctly today.

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
# v1 ambient time coeffect
rg -n 'inject-cofx\s+:now|:now\b' src
```

> **Keep these aligned with the framework's own check.** `scripts/check_ambient_durable_reads.py` is the authoritative ambient-durable-read detector for the re-frame2 codebase; its clock/random pattern list (`now-ms` / `js/Date.now` / `.now js/Date` / `random-uuid` / `getRandomValues`) is the canonical core. The extra raw-JS forms above (`(js/Date.)` + `.getTime`, `js/Math.random` / `.random js/Math`, `crypto.randomUUID` / `.randomUUID js/crypto`) are the ones a **v1 consumer app** commonly uses that the framework code does not — a migration grep that only covers the framework's set under-reports and can falsely conclude EP-0010 is clean. Include both.

For each hit: if the value flows into a durable write → it migrates (below). If it's diagnostic or host-transient → leave it, and note in the report *why* it's allowed to stay ambient.

## Route time → the envelope, or a compatibility cofx

The dispatch envelope carries `:rf.world/inputs`, a plain-EDN map whose one required key is `:time-ms` (wall-clock epoch millis). The router stamps it once at the causal boundary when the caller omits it; tests, replay, and SSR supply it. Handlers read it as a framework coeffect:

```clojure
(rf/reg-event-fx
  :article/load-succeeded
  (fn [{:keys [db] :rf.world/keys [inputs]} [_ {:keys [id article]}]]
    {:db (assoc-in db [:articles id]
                   {:body article
                    :loaded-at (:time-ms inputs)})}))   ;; was (interop/now-ms)
```

Two migration paths:

1. **Direct (preferred for new/edited handlers):** replace `(interop/now-ms)` / `js/Date.now` / `(.now js/Date)` at the durable write with `(:time-ms (:rf.world/inputs cofx))` (or the `:rf.world/keys [inputs]` destructure).
2. **Compatibility cofx (keeps a v1 `inject-cofx :now`-style call site stable):** a v1 app that injects an ambient `:now` cofx can keep its custom cofx **name** while moving its **source** from the host to the envelope. The cofx reads `:rf.world/inputs` and so becomes replay-correct without rewriting every consumer:

   ```clojure
   (rf/reg-cofx
     :app/now-ms
     (fn [ctx]
       (let [world (get-in ctx [:coeffects :rf.world/inputs])]
         (assoc-in ctx [:coeffects :app/now-ms] (:time-ms world)))))
   ```

   Handlers still `(inject-cofx :app/now-ms)` and read `:app/now-ms`; the value now comes from the causal token, so replay returns the captured time instead of re-reading the host. This is the smallest-diff route for an app with a pervasive `:now` cofx.

`:rf.world/inputs` is the durable time contract. The old dev-only `:dispatched-at` envelope field is **not** a durable surface — durable code reads `:rf.world/inputs`.

## UUID / random / browser / storage → recordable inputs or event payloads

These follow the same boundary. If the generated or read value becomes durable, it is a world input.

- **Generated identity (UUID).** A durable entity id minted with `random-uuid` inside a handler is a world input. Supply it on the token under a domain slot, or carry it in the event payload from the call site:

  ```clojure
  (rf/dispatch [:todo/create {:text text}]
               {:rf.world/inputs
                {:time-ms 1781078400123
                 :uuid    {:todo/id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}}})
  ;; handler: (get-in inputs [:uuid :todo/id])  — not (random-uuid)
  ```

- **Random choices.** Record the **chosen value**, not a seed (host RNG algorithms and collection ordering are not portable). A seed is acceptable only when the algorithm and input order are named and stable. **Crypto-grade randomness — session tokens, keys, nonces — is excluded entirely:** it must NOT flow through recordable world inputs (a recorded choice would *be* the secret, durably embedded in epoch history / replay fixtures). Secrets are generated in effects on the host side; only derived or server-issued facts become durable.

- **Browser + storage facts.** A handler reading `js/location` / `navigator` / `localStorage` while writing durable state is the violation. The host adapter reads the browser **at the boundary** and dispatches a causal token carrying normalized EDN (strings/booleans/numbers/keywords — never the live host object):

  ```clojure
  (rf/dispatch [:route/location-changed
                {:location {:path "/articles/welcome" :query "?preview=true"}}]
               {:source :router
                :rf.world/inputs {:time-ms 1781078400123}})
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

World-input migration is a **report-worthy** class even when the app runs: list each durable ambient read found, the bucket you assigned it, and the route taken (envelope / payload / recordable cofx) or — for diagnostic/host-transient — the reason it stayed ambient. Because the failure is silent (replay/restore-only), the author cannot see it in a boot smoke-test; the report is the only record that the boundary was checked.
