# Production observability

The **normal** way an app ships event + error records to Datadog / Sentry / Honeycomb / a custom pipeline is **declarative, frame-owned**: declare a sink under a frame's `:observability` policy, register its fn with `rf/register-observability-sink!`, and let the runtime route one **already-projected** record per event/error to it. This EP-0015 egress path composes the owning frame's classification (`:sensitive` / `:large`) with the entry's `:rf.egress/profile`, so your sink never sees raw sensitive values and never re-walks for privacy / size. Reach for the low-level listener APIs only when you need finer control.

```clojure
;; The normal path: frame :observability + a registered sink fn.
(rf/make-frame
  {:id :rf/default
   :observability {:handled-events [{:sink :my-app.sinks/datadog
                                     :rf.egress/profile :rf.egress/off-box-observability}]
                   :errors         [{:sink :my-app.sinks/sentry
                                     :rf.egress/profile :rf.egress/off-box-observability}]}})

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [record] (datadog/track-event! record)))   ;; record is ALREADY projected

(rf/register-observability-sink! :my-app.sinks/sentry
  (fn [record] (sentry/capture! record)))
```

Both routes ride the **same two always-on substrates** that survive `goog.DEBUG=false` and `:advanced` — **parallel to** (not a fallback from) the dev-only trace bus, which DCEs in CLJS production builds. The frame `:observability` path is the projection-and-routing layer **on top of** those substrates; the listener APIs below (`register-listener!` on the `:events` / `:errors` streams) are the **advanced low-level hooks beneath** it. Either way the framework runs `elide-wire-value` against each record's `:event` vector before fan-out — neither sinks nor listeners re-walk for privacy / size. Egress composition: [`privacy-and-elision.md`](privacy-and-elision.md) §Choosing where observations go.

## The mental model: three channels, three production guarantees

Frame everything below against the **three observability channels** the runtime has — they answer different questions and survive production differently:

1. **The causal channel** — effects-as-data (`:dispatch`, `:fx`). It *is* the program, not a log line about it. **Never elided** — deleting it deletes the app.
2. **The diagnostic channel** — `register-listener!` on the `:trace` stream and the whole trace bus (every trace event, the per-frame rings, source-coord enrichment, correlation ids). Ambient, for dev eyes and tools. **Production-elided**: Closure-DCE'd under `:advanced` + `goog.DEBUG=false`; runtime-gated on the JVM (see below).
3. **The always-on error axis** — `register-listener!` on the `:errors` stream. It is **production-survivable**: NOT gated by `debug-enabled?`, so it survives elision, fanning one tight `:rf.error/*` record per production-reachable failure to your shipper (Sentry / Rollbar / Honeybadger). The `:events` stream is the throughput/latency sibling on the same survives-production footing.

The JS-ecosystem anchor: this is the equivalent of "Sentry/Rollbar SDK for the error axis, an APM SDK for the event axis, and a Redux-DevTools-style time-travel bus for the diagnostic channel" — except the diagnostic bus is compiled *out* of production rather than tree-shaken at the edges, and the error axis is a first-class framework substrate rather than a global `window.onerror` hook. **Divergence to flag:** unlike a JS error SDK, you do not steer recovery from the listener (see below); recovery is framework-owned.

## When to load

Wiring a production observability shipper, declaring a frame `:observability` sink, writing a `register-listener! :events` / `register-listener! :errors` body, or asking "what's the prod-survivable equivalent of the `:trace` stream?".

## `register-listener! :events` — one record per dispatched event (advanced low-level hook)

> The sections below document the always-on listener substrate the frame `:observability` path lowers onto. Reach for these raw registries only for lower-level control (a record the frame policy doesn't route, or a non-frame-scoped global hook).

Fires once per event the runtime processes — NOT per sub, NOT per fx, NOT per `:event/db-changed`. Registration is idempotent (re-registering the same id replaces); listener exceptions are caught (cascade continues).

```clojure
(rf/register-listener! :events
  :datadog/events
  (fn [event-record]
    (datadog/track-event! event-record)))

(rf/unregister-listener! :events :datadog/events)
```

**Record shape (tight — Spec 009 §Event-emit listener):**

```clojure
{:event      [:cart/checkout {:items [...]}]      ;; the dispatched event vector (elided)
 :event-id   :cart/checkout                        ;; (first event)
 :frame      :rf/default                           ;; resolved frame-id
 :time       1715600000000                         ;; emit timestamp (host clock, ms since epoch)
 :outcome    :ok                                   ;; :ok | :error | :rolled-back | :flow-error | :rejected
 :elapsed-ms 12}                                   ;; queue → settle, integer
```

`:outcome` covers **every** cascade-failure path, not just the interceptor-chain exception, so a dispatch that aborted is never mis-reported as a clean `:ok` (per `re-frame.event-emit` ns docstring §Record shape and Spec 009 §Event-emit listener):

- `:ok` — clean settle (db committed, flows ran, `:fx` walked).
- `:error` — the interceptor chain (handler or interceptor) threw.
- `:rolled-back` — `:db` schema validation rejected the candidate state before it installed, so the container kept its pre-handler value (Spec 010 §Per-step recovery row 4); flows and `:fx` were skipped.
- `:flow-error` — a flow's `:output` threw (Spec 013 §Failure semantics rule 3); the cascade halted before `:fx`.
- `:rejected` — the `:rf.schema/at-boundary` interceptor refused the event's payload against the handler's `:schema`. The handler never ran; entered interceptors still unwound in full. It is the lowest-priority discriminator: a chain throw, a flow throw or a candidate rollback during the unwind still wins.

**`:rejected` and `:rolled-back` are exact complements on the production question, and a shipper should treat them that way.** `:rolled-back` reports the `reg-app-schema` candidate check, which a release build elides — so its *absence* in production is not evidence that no schema was violated. `:rejected` reports the boundary interceptor, whose check Spec 010 keeps ungated — so it does have a producer in a release build, and it is the outcome to alert on for malformed or hostile input at an untrusted ingress. Each rejection is paired with one always-on `:rf.error/schema-validation-failure` record (`:source :boundary`) on the `:errors` stream carrying the identifiers; the offending value rides the dev-only trace and never egresses.

What decides which side a check falls on is **what the check is for, not who declared the schema it reads** (Spec 000 C-000.35): an ordinary registration diagnostic elides, while a check the framework relies on to keep a promise of its own holds in every build. So the boundary interceptor is not the only schema check that survives — a declared route's `:params` / `:query` shape, a recordable coeffect's `:schema`, the reserved `:rf.server/*` effects' checks on their own arguments and Managed HTTP's `:decode` all run in a release build too ([`../fundamentals/schemas.md`](../fundamentals/schemas.md#what-survives-is-settled-by-what-the-check-is-for-not-by-who-declared-it) is the full matrix). The recordable coeffect is the one to read carefully: that `:schema` is declared on the programmer's own `reg-cofx` and survives regardless, because the framework applies it where the value folds into the durable record. What is exclusive is narrower, and is the thing a shipper actually wires an alert to: those others throw or reject on their own paths, and a throw reports `:error` like any other, so `:rejected` is the only value in this vocabulary that names a surviving schema check as such.

No trace-bus keys (no `:dispatch-id`, `:parent-dispatch-id`, `:rf.trace/trigger-handler`, source coords) — those ride the dev-only trace surface. Verified: `re-frame.event-emit/dispatch-on-event!`; record shape per the ns docstring §Record shape.

**Privacy is path-based, applied at egress — the record always fans out.** Every surviving record's `:event` vector is walked by `elide-wire-value` with off-box defaults (large → `:rf.size/large-elided`; classified sensitive paths → `:rf/redacted`) *before* listeners run. Sensitivity is owner-classified by *path* (the registration's `:sensitive` paths for transient event args; the durable app-db `:sensitive` classification effect a handler returns alongside `:db`), **fail-open** — an unclassified path ships raw. **No** whole-record privacy drop at the handler boundary: `dispatch-on-event!` never suppresses a record for sensitivity — it redacts the payload per `[:rf.runtime/elision :sensitive-declarations]` (runtime-db) and ships the rest. (Handler-meta `:sensitive?` is **not** consulted — removed from the runtime, see `event_emit.cljc` docstring.) The only whole-record drop gate is `:rf.trace/no-emit?` on handler-meta, **framework-internal** (Xray / Story bookkeeping), not a user privacy knob.

## `register-listener! :errors` — one record per runtime error (advanced low-level hook)

Fires once per catalogued production-reachable `:rf.error/*` event the runtime emits through the error-emit substrate. This is the single error-observability surface; per-listener exceptions are isolated (one bad listener cannot affect siblings or the cascade).

```clojure
(rf/register-listener! :errors
  :sentry/errors
  (fn [error-record]
    (sentry/capture-exception
      (:exception error-record)
      {:tags {:event-id (:event-id error-record)
              :frame    (:frame error-record)}})))

(rf/unregister-listener! :errors :sentry/errors)
```

The listener payload is an **error-keyed union** of several record shapes — the per-event error record below, the frame-teardown report (§The first promoted category), and the EP-0008-promoted **non-event SSR records** (§The promoted-SSR records). **Always branch on `(:error record)` — never assume `:event` / `:event-id` / `:exception` are present.** Only the per-event records carry those slots; the teardown report and the SSR records do not (a teardown report carries `:hook-failures`; the SSR records carry `:frame` + category-specific slots, some with no `:event` at all). A listener that destructures `:event-id` / `:exception` off every record will NPE on a non-event record.

**Per-event error record (tight — Spec 009 §Error-emit listener):**

```clojure
{:error      :rf.error/handler-exception           ;; the error keyword
 :event      [:cart/checkout {...}]                ;; the dispatched event vector (elided)
 :event-id   :cart/checkout
 :frame      :rf/default
 :time       1715600000000                         ;; ms since epoch
 :exception  #error{...}                           ;; the thrown exception object
 :elapsed-ms 8}                                    ;; queue → throw, integer
```

Verified: `re-frame.error-emit/dispatch-on-error!`; record shape per the ns docstring §Record shape.

### Recovery is framework-owned — there is no app-steering policy

Error **recovery** is not an app-config concern. The runtime applies a **typed per-category default**: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app, no-such-handler / no-such-sub no-op. There is no per-frame `:on-error` recovery policy — recovery is framework-owned. Genuine recovery for **expected** failures is handled at the source — managed-HTTP `:retry`, optional-read fallback — where "recovery" actually has meaning. Off-box **observability** is `register-listener! :errors` (above). To re-run a failed event, dispatch a fresh one; the runtime never re-runs the failing handler.

### What rides the always-on axis: the promotion criterion

The error axis is small — an alert on it should *mean something*. Most failures stay diagnostic (dev-visible, production-elided); only a specific shape earns a production-survivable record. A category is on the axis only when **all three legs** hold:

1. **Production-reachable** — it can fire in a production build, not just a dev-time misuse caught at the boundary (a malformed-registration shape, a dev-only schema check: those stay diagnostic).
2. **A contract breach or resource leak the caller can't already see** — the load-bearing leg. A bad event vector throws at the call site; the caller sees it. A *leaked handle / skipped teardown / suppressed write / corrupted invariant* leaves the process in a state the next operation can't observe — that needs an off-box record.
3. **Silence compounds** — the cost of nobody hearing grows with process lifetime / request volume / retries. A leaked timer per SSR request is cheap once and fatal at ten million.

**The `:rf.error/*` namespace is framework-owned — do not mint app/domain categories under it.** `register-listener! :errors` is a *consume* surface, not *emit*: app code does not raise its own `:rf.error/*` records through it, the `:rf/*` namespace is reserved (Cardinal rule 7), and the catalogue of categories on this axis is fixed and framework-defined. App/domain telemetry has its own homes: an **app-owned namespace** for custom trace/log keys (`:myapp.error/payment-declined`), a framework **public API** where one exists, or your ordinary logging / observability pipeline addressed directly from your handler. Keeping domain failures off `:rf.error/*` keeps the stream pure signal — every record a framework-recognised production-reachable failure. The axis is `:rf.error/*`-only (never `:rf.warning/*`) and carries **structured data only** (ids/keys/frame, never raw values — egress redaction applies).

### The first promoted category: `:rf.error/frame-teardown-failed`

One always-on category beyond the per-failure errors is worth knowing because its record shape differs. When a frame is destroyed the runtime runs a best-effort recipe of teardown steps — optional late-bound cleanup hooks plus a few guarded direct steps (notably the `:frame/notify-machine-destruction!` machine cascade). A throwing step is a resource leak the next operation can't see and compounds per SSR request — all three legs hold. Rather than flood the shipper with one record per failed step, the runtime emits **one bounded report per destroy** carrying a `:hook-failures` vector. Each entry's `:hook` names the step that threw (either kind — the `:hook-failures` / `:hook` wire names are deliberately stable) and `:where` records the boundary that caught it: `:safe-call-hook!` for a late-bound hook, `:safe-teardown-step!` for a guarded direct step. Your `register-listener! :errors` body must handle this shape (note `:hook-failures` + `:reason`, and `:error` — not `:operation` — as the discriminator, same as every error-emit record):

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :app/per-request-42
 :recovery      :ignored                 ;; teardown stays best-effort
 :reason        "2 frame-teardown step(s) threw during destroy; ..."
 :time          1715600000000
 :hook-failures [{:hook :http/abort-inflight :exception #object[...] :where :safe-call-hook!}
                 {:hook :timers/clear        :exception #object[...] :where :safe-call-hook!}]}
```

In **development** the per-step detail still surfaces as `:rf.warning/teardown-hook-exception` traces on the diagnostic channel (at their causal positions, DCE'd in prod); the single always-on report is what a production shipper sees. A generic shipper body that maps `(:error record)` to the alert name and forwards the rest already handles this category without special-casing — the only gotcha is not assuming an `:event`/`:event-id` slot (a teardown report has neither; branch on `(:error record)` if you need the per-category shape).

### The promoted-SSR records: `:rf.error/ssr-*` (non-event)

The production-reachable **SSR error categories** ride this same always-on axis (EP-0008). On a long-lived JVM SSR host, a shipper registered via `register-listener! :errors` receives them **even under `-Dre-frame.debug=false`** — where the dev trace surface is elided, the off-box record is the only telemetry. The seven categories:

- **`:rf.error/ssr-render-failed`** — a render-time `Throwable` while building the response body (slots: `:frame`, `:exception`, `:exception-message`, `:ex-class`). Projection-eligible (the wire status is stamped), so promotion does not double-stamp.
- **`:rf.error/ssr-streaming-writer-failed`** — a streaming-SSR writer thread threw on a post-commit chunk (slots: `:frame`, `:exception`, `:ex-class`, `:phase`, `:boundary-id` on continuation phases, `:committed? true`). Non-projecting (the 200 already committed).
- **`:rf.error/malformed-hydration-payload`** — a bad hydration payload (the hydrate-handler path AND the pre-frame **frameless** parse sub-path, the latter carrying `:frame nil`).
- **`:rf.error/ssr-head-resolution-failed`** — the active route's `:head` fn threw; the host degrades to an empty head fragment (slots: `:frame`, `:exception`). Recoverable-degradation, non-projecting (still 200).
- **`:rf.error/sanitised-on-projection`** — the error projector itself threw / returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 (slots: `:projector-id`, `:original-operation`, `:projection-failure-reason`). Non-projecting + re-entry-guarded (one-shot, never re-projects).
- **`:rf.error/ssr-ring-error-view-failed`** — a caller-supplied `:error-view` threw; the host falls back to its locked default error template (slots: `:frame`, `:exception`, `:ex-class`). Non-projecting.
- **`:rf.error/hydration-frame-id-mismatch`** — the `:rf/hydrate` handler's direct-`dispatch-sync` guard: a payload `:rf/frame-id` present-and-different from the frame being hydrated into fails **closed** (app-db + runtime-db left unchanged, no compatibility-check fxs) and emits this record (slots: `:where`, `:frame`, `:failing-id` `:rf/hydrate`, `:target-frame`, `:payload-frame-id`, `:reason`). Non-projecting.

**These are NON-EVENT records — none carries `:event` / `:event-id`, and some (the frameless hydration-parse path) carry `:frame nil`.** A listener that assumes the per-event shape NPEs; branch on `(:error record)` and read each category's own slots. The recoverable-degradation members (`:rf.error/ssr-head-resolution-failed`, `:rf.error/ssr-ring-error-view-failed`) and the post-commit members (`:rf.error/ssr-streaming-writer-failed`, `:rf.error/sanitised-on-projection`) are **non-projecting** — their riding the always-on axis changes what off-box shippers see, never the wire outcome. (Keep these distinct from the `:rf.ssr/*` *compatibility* diagnostics — version/digest/hydration mismatch — which stay trace-channel and do NOT ride this axis. See [`ssr-authoring.md`](ssr-authoring.md).)

## Triple-gate registration pattern

The substrate is always-on; **registration sites** should belt-and-braces gate on explicit config + `goog.DEBUG=false` + a credential probe, so an accidental dev-bundle deploy with prod config doesn't quietly ship records to your back-end.

```clojure
(when (and (= "production" (:env config))
           (not ^boolean re-frame.interop/debug-enabled?)
           (:api-key config))
  (rf/register-listener! :events
    :datadog/events
    (fn [event-record]
      (datadog/track-event! event-record)))
  (rf/register-listener! :errors
    :sentry/errors
    (fn [error-record]
      (sentry/capture-exception (:exception error-record)
                                {:tags {:event-id (:event-id error-record)
                                        :frame    (:frame error-record)}}))))
```

Three independent conditions: **config env tag** (the app knows it's production), **`goog.DEBUG=false`** (the bundle is the production bundle), **api-key present** (credentials wired). Skip any leg and you get the dev path. Pattern documented in `re-frame.event-emit` ns docstring §goog.DEBUG framing.

## Why production has no trace bus

`re-frame.trace/emit!` and the `register-listener! :trace` plumbing are gated by `re-frame.interop/debug-enabled?`. Under `:advanced` + `goog.DEBUG=false`, the Closure compiler DCEs the entire trace surface — registrations, the ring buffer, the per-event allocation, every `tag/value` map. The bundle savings and per-event allocation savings are part of re-frame2's "production debugging is opt-out, not opt-in" stance.

The two always-on listener streams (`:events` / `:errors`) carve a minimal substrate that **survives** that elision: a tiny record shape, a `defonce` registry that hot reload won't blow away, fan-out gated on registry size (empty-map check short-circuits). Re-enable the full trace bus in production by flipping `:closure-defines {goog.DEBUG true}` if and only if the bundle cost is acceptable.

### The JVM production gate (`re-frame.debug` / `RE_FRAME_DEBUG`)

`goog.DEBUG=false` is the **CLJS** posture gate (Closure DCE). On the **JVM** — SSR hosts, headless tooling, test runners — there is no Closure DCE, so the diagnostic trace surface is gated at runtime by a separate switch, set BEFORE `re-frame.interop` loads:

- **`-Dre-frame.debug=false`** — the Java system property on the JVM command line, or
- **`RE_FRAME_DEBUG`** — the process environment variable.

**The JVM default is ON** ("production-elided" means *elidable*, not *elided by default*). A production JVM SSR / tooling process that does not set `-Dre-frame.debug=false` runs the **full dev diagnostic surface** — retaining user input in per-frame trace rings and epoch history. EP-0008 calls this out: a JVM artefact shipped for production **MUST set `-Dre-frame.debug=false` explicitly** in its deployment (the audit-finding posture — an SSR/headless process should not retain user input by default). These are build-time / process-start gates that select the posture; apps do not toggle them per-request. Critically, the gate suppresses the **diagnostic trace** surface only — the **always-on error-emit axis (surface #4) survives it**, so `register-listener! :errors` shippers (including the promoted SSR records above) keep delivering under `-Dre-frame.debug=false`. That is the whole point of the always-on split: event/error observability survives the production posture; the dev trace surface does not.

Full rationale: [`docs/core/observability.md`](../../../../docs/core/observability.md), [`spec/009-Instrumentation.md §What IS available in production`](../../../../spec/009-Instrumentation.md#what-is-available-in-production), and [`spec/009-Instrumentation.md §JVM builds`](../../../../spec/009-Instrumentation.md#jvm-builds).

## Generic shipper recipe (Datadog / Sentry / Honeycomb)

The record shapes are tight enough to ship verbatim — every observability vendor's wire format is a strict subset of "event-id + timestamp + tags + payload". The pattern:

```clojure
(rf/register-listener! :events
  :observability/events
  (fn [{:keys [event-id event time outcome elapsed-ms frame]}]
    (forward!
      {:name      (str event-id)
       :timestamp time
       :tags      {:outcome outcome :frame frame}
       :duration  elapsed-ms
       :payload   event})))                  ;; already elided — large→marker, sensitive→:rf/redacted
```

`:payload` (the `:event` slot) has **already** been passed through `rf/elide-wire-value` with off-box defaults (`:rf.size/include-large? false`, `:rf.size/include-sensitive? false`) by the time your listener runs. Do not re-walk unless you want to **widen** the policy (e.g. `:rf.size/include-digests? true` for a debug pipeline). See [`privacy-and-elision.md`](privacy-and-elision.md) for the elision composition rules.

Worked vendor recipes (Datadog tags, Sentry breadcrumbs, Honeycomb spans): [`docs/core/how-to/report-errors-in-production.md`](../../../../docs/core/how-to/report-errors-in-production.md).

## Common gotchas

- **Listeners block the drain step.** Bodies run synchronously after each event settles. Ship work to a background channel (`requestIdleCallback`, queueing fetch, `setTimeout 0`) if it can't fit inside the per-event wall-clock budget.
- **Don't re-run elision unless widening.** The record already has off-box defaults applied. A listener that re-walks with defaults is a no-op; one that flips `:include-large?` / `:include-sensitive?` to `true` exposes data you'd otherwise hide.
- **Sensitivity redacts the payload, it does NOT drop the record.** There is no handler-level "drop the whole record" privacy gate — every event record fans out, and classified sensitive paths (the registration's `:sensitive` event-arg paths; the durable app-db `:sensitive` classification effect's slices) in its `:event` payload arrive as `:rf/redacted` (per `elide-wire-value` egress). You always get the event-id, frame, outcome, and timing — a built-in audit trail of sensitive events without their secret values. The only whole-record drop (`:rf.trace/no-emit?`) is framework-internal and not yours to set.
- **Listener exceptions are swallowed.** The cascade catches; sibling listeners still run. You will NOT see a thrown listener error in the console; log inside the listener body if you want visibility.
- **Don't use the `:trace` stream for production observability.** `register-listener! :trace` dies under `:advanced` + `goog.DEBUG=false`. The `:events` / `:errors` streams are the prod-survivable channel.

## Cross-references

- Guide concept: [`docs/core/observability.md`](../../../../docs/core/observability.md) — narrative walkthrough of the one-wire substrate and what survives elision. Worked vendor recipes: [`docs/core/how-to/report-errors-in-production.md`](../../../../docs/core/how-to/report-errors-in-production.md).
- Spec normative: [`spec/009-Instrumentation.md §What IS available in production`](../../../../spec/009-Instrumentation.md) — substrate contracts.
- Privacy composition: [`privacy-and-elision.md`](privacy-and-elision.md) — owner-classified sensitive paths (the durable app-db `:sensitive` classification effect / registration `:sensitive`) are redacted to `:rf/redacted` by `elide-wire-value`; payload already walked at listener entry. No whole-record drop.

---

*Derived from `re-frame.event-emit` and `re-frame.error-emit` @ main. Verified surfaces: the `:events` stream / `dispatch-on-event!` (`event_emit.cljc`), the `:errors` stream / `dispatch-on-error!` / `dispatch-frame-teardown-report!` (`error_emit.cljc`); registration is via the stream-parameterized `register-listener!` / `unregister-listener!` verb; record shapes and the `:outcome` enum per each ns docstring §Record shape.*
