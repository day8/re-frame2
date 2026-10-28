# Production observability

The **normal** way an app ships event + error records to Datadog / Sentry / Honeycomb / a custom pipeline is **declarative, frame-owned**: declare a sink under a frame's `:observability` policy, register its fn with `rf/register-observability-sink!`, and let the runtime route one **already-projected** record per event/error to it. This EP-0015 egress path composes the classification the frame resolves (`reg-classification` + registration `:sensitive` / `:large`) with the entry's `:rf.egress/profile`, so **on the off-box default** your sink never sees raw sensitive values and never has to re-walk for privacy / size. (The guarantee is the default's, not the route's: a sink whose entry opts into `:rf.egress/local-raw` is *asking* for classified values in the clear, which is that profile's whole purpose.) It is the **only** production door — the corpus-wide `register-listener!` `:events` / `:errors` streams were retired, per [`spec/API.md` §Observation listeners](https://github.com/day8/re-frame2/blob/main/spec/API.md#observation-listeners).

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

**Declare it once per process when the policy is a deployment property** — which it usually is. `(rf/configure! {:observability …})` takes the same grammar and every frame inherits it, so a multi-frame app stops restating its Sentry entry on every `make-frame`:

```clojure
(rf/configure! {:observability {:errors [{:sink :my-app.sinks/sentry}]}})
```

Precedence is **per stream**, read by key PRESENCE rather than truthiness: a frame declaring `:errors` uses its own entries for errors and still inherits the default's `:handled-events`; `{:errors []}` on a frame declares the stream with no sinks and is that frame's opt-out. Exactly one source is consulted per record per stream, so a sink id in both is invoked once. Inheritance moves the **sink list**, never the redaction authority — an inheriting frame's records still project under *its own* classification. `{:observability nil}` clears the default; a malformed policy throws `:rf.error/bad-frame-classification` (with `:where 'rf/configure!`) at the call rather than installing quietly.

The process default is also the only thing that can reach a record with **no frame to ask**: an error raised with no frame in scope, a pre-frame SSR hydration parse, or a teardown report from an already-dissociated frame. Those go to the default alone, projected as though no frame vouched for them — summary ids intact, tree slots `:rf/redacted` — and a dead frame's id rides along as a diagnostic that is never re-resolved, so it cannot land in a same-id successor's sink.

Both sink routes survive `goog.DEBUG=false` and `:advanced`, **parallel to** (not a fallback from) the dev-only trace bus, and each builds its record from the original dispatched vector and projects it once, for the entry's profile. Egress composition: [`privacy-and-elision.md`](privacy-and-elision.md) §Choosing where observations go.

## The mental model: three channels, three production guarantees

1. **The causal channel** — effects-as-data (`:dispatch`, `:fx`). It *is* the program, not a log line about it. **Never elided** — deleting it deletes the app.
2. **The diagnostic channel** — `register-listener!` on the `:trace` stream and the whole trace bus (every trace event, the per-frame rings, source-coord enrichment, correlation ids). For dev eyes and tools. **Production-elided**: Closure-DCE'd under `:advanced` + `goog.DEBUG=false`; runtime-gated on the JVM (see below).
3. **The always-on error axis** — the `:observability :errors` sink route. **Production-survivable**: NOT gated by `debug-enabled?`, delivering one `:rf.error/*` record per production-reachable failure to your shipper. The `:handled-events` sink is the throughput/latency sibling on the same footing.

**Divergence to flag:** unlike a JS error SDK, you do not steer recovery from the sink (see below); recovery is framework-owned.

## When to load

Wiring a production observability shipper, declaring a frame `:observability` sink or the `(rf/configure! {:observability …})` process default, writing a sink fn body, or asking "what's the prod-survivable equivalent of the `:trace` stream?".

## `:handled-events` — one record per dispatched event

Fires once per event the runtime processes — NOT per sub, NOT per fx, NOT per `:event/db-changed`. Registration is idempotent (re-registering the same sink id replaces); sink exceptions are caught (cascade continues).

```clojure
(rf/configure! {:observability {:handled-events [{:sink :datadog/events}]}})

(rf/register-observability-sink! :datadog/events
  (fn [event-record]
    (datadog/track-event! event-record)))

(rf/unregister-observability-sink! :datadog/events)
```

**Sink record shape (`:rf.observe/handled-event`)** — the same record on every profile:

```clojure
{:kind        :rf.observe/handled-event            ;; always present; branch on it
 :frame       :rf/default                          ;; resolved frame-id
 :event-id    :cart/checkout
 :time        1715600000000                        ;; ms since epoch, when the event settled
 :outcome     :ok                                  ;; :ok | :error | :rolled-back | :flow-error | :rejected
 :elapsed-ms  12                                   ;; queue → settle, integer
 :effects     [:fx :dispatch]                      ;; the effect keys the cascade walked (when any)
 :correlation {:work-id … :dispatch-id …}          ;; when the cascade carried one
 :event       [:cart/checkout {:items [...]}]}     ;; ONLY under a sensitive-opted profile; see below
```

> **A profile chooses projection OPTIONS, not a different record.** `:rf.egress/local-raw` is the trusted-local option set (`:rf.egress/include-sensitive? true`, `:rf.egress/include-large? true`); its one visible effect on this record is that the `:event` args slot is **kept** — still projected — where the off-box default omits it entirely. `:time` and `:outcome` are summary slots, present on every profile.

`:outcome` covers **every** cascade-failure path, not just the interceptor-chain exception, so a dispatch that aborted is never mis-reported as a clean `:ok`:

- `:ok` — clean settle (db committed, flows ran, `:fx` walked).
- `:error` — the interceptor chain (handler or interceptor) threw.
- `:rolled-back` — `:db` schema validation rejected the candidate state before it installed, so the container kept its pre-handler value (Spec 010 §Per-step recovery row 4); flows and `:fx` were skipped.
- `:flow-error` — a flow's `:output` threw (Spec 013 §Failure semantics rule 3); the cascade halted before `:fx`.
- `:rejected` — a `:boundary? true` handler's `:schema` refused the event's payload. The handler never ran; entered interceptors still unwound in full. It is the lowest-priority discriminator: a chain throw, a flow throw or a candidate rollback during the unwind still wins.

**`:rejected` and `:rolled-back` are exact complements on the production question.** `:rolled-back` reports the `reg-app-schema` candidate check, which a release build elides — so its *absence* in production is not evidence that no schema was violated. `:rejected` reports the `:boundary? true` check, which stays ungated — it is the outcome to alert on for malformed or hostile input at an untrusted ingress. Each rejection is paired with one always-on `:rf.error/schema-validation-failure` record (`:source :boundary`) on the `:errors` stream carrying the identifiers; the offending value rides the dev-only trace and never egresses. Other schema checks also survive a release build (route `:params` / `:query`, a recordable coeffect's `:schema`, `:rf.server/*` arguments, HTTP `:decode` — [`../fundamentals/schemas.md`](../fundamentals/schemas.md#what-survives-is-settled-by-what-the-check-is-for-not-by-who-declared-it) is the matrix), but they throw or reject on their own paths and report `:error`, so `:rejected` is the only outcome that names a surviving schema check as such.

No trace-bus keys (no `:dispatch-id`, `:parent-dispatch-id`, `:rf.trace/trigger-handler`, source coords) — those ride the dev-only trace surface. Privacy is **path-based, applied at egress, and fail-open**: classified paths (the registration's `:sensitive` paths for event args; a `reg-classification` beside the feature for durable app-db) arrive as `:rf/redacted`, large ones as `:rf.size/large-elided`, and an unclassified path ships raw. Handler-meta `:sensitive?` is not consulted.

## `:errors` — one record per runtime error

Fires once per catalogued production-reachable `:rf.error/*` event. This is the single error-observability surface; per-sink exceptions are isolated (one bad sink cannot affect siblings or the cascade).

```clojure
;; Omit :rf.egress/profile for the off-box default. It is the PROJECTION that
;; changes with the profile, never the record kind — a sink always receives a
;; :rf.observe/error record.
(rf/configure!
  {:observability {:errors [{:sink :sentry/errors}]}})

(rf/register-observability-sink! :sentry/errors
  (fn [error-record]
    (sentry/capture-exception
      (:exception error-record)
      {:tags {:event-id (:event-id error-record)
              :frame    (:frame error-record)}})))

(rf/unregister-observability-sink! :sentry/errors)
```

The payload is an **error-keyed union** — the per-event error record below, the frame-teardown report, and the promoted **non-event SSR records**. **Always branch on `(:rf.error/id record)` — never assume `:event` / `:event-id` / `:exception` are present.** Only the per-event records carry those slots; a sink fn that destructures them off every record will NPE on a non-event record.

**Sink record shape (`:rf.observe/error`) — per-event arm:**

```clojure
{:kind       :rf.observe/error                     ;; always present; branch on it
 :rf.error/id :rf.error/handler-exception           ;; the error keyword — the discriminator
 :event      [:cart/checkout {...}]                ;; tree slot: walked under frame policy
 :event-id   :cart/checkout
 :frame      :rf/default
 :time       1715600000000                         ;; ms since epoch — summary slot, always present
 :exception  #error{...}                           ;; dropped ONLY under :rf.egress/public-error
 :elapsed-ms 8}                                    ;; queue → throw, integer
```

In place of `:outcome` it carries `:rf.error/id`, the category. `:failing-id`, `:flow-id`, `:where` and `:source-coord` ride the top level as summary slots when the producer supplied them; every other attribution slot (`:reason` among them) is lifted onto a `:tags` tree the projector walks — so read `(get-in record [:tags :reason])`, not `(:reason record)`.

The profile chooses **how much of the tree survives**, never which record you get: the default `:rf.egress/off-box-observability` redacts classified-sensitive paths and elides large ones, `:rf.egress/local-raw` keeps both, and `:rf.egress/public-error` additionally drops the top-level `:exception`. `:kind`, `:rf.error/id` and the summary ids are there on all three.

### Recovery is framework-owned — there is no app-steering policy

The runtime applies a **typed per-category default**: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app, no-such-handler / no-such-sub no-op. There is no per-frame `:on-error` recovery policy. Recovery for **expected** failures belongs at the source — managed-HTTP `:retry`, optional-read fallback. To re-run a failed event, dispatch a fresh one; the runtime never re-runs the failing handler.

### What rides the always-on axis: the promotion criterion

The error axis is small — an alert on it should *mean something*. A category is on the axis only when **all three legs** hold:

1. **Production-reachable** — it can fire in a production build, not just a dev-time misuse caught at the boundary.
2. **A contract breach or resource leak the caller can't already see** — a *leaked handle / skipped teardown / suppressed write / corrupted invariant* the next operation can't observe.
3. **Silence compounds** — the cost of nobody hearing grows with process lifetime / request volume / retries.

**The `:rf.error/*` namespace is framework-owned — do not mint app/domain categories under it.** The `:errors` sink route is a *consume* surface, not *emit*: app code does not raise its own `:rf.error/*` records, and the catalogue is fixed (Cardinal rule 7). App/domain telemetry goes under an **app-owned namespace** (`:myapp.error/payment-declined`), a framework public API where one exists, or your ordinary logging pipeline addressed directly from your handler. The axis is `:rf.error/*`-only (never `:rf.warning/*`) and carries **structured data only** (ids/keys/frame, never raw values).

### The first promoted category: `:rf.error/frame-teardown-failed`

When a frame is destroyed the runtime runs a best-effort recipe of teardown steps — each loaded artefact's lifecycle teardown (the machines `:exit` cascade, resource caches, in-flight HTTP, …) plus a few of its own. Rather than one record per failed step, it emits **one bounded report per destroy** carrying a `:hook-failures` vector. Each entry's `:hook` names the step that threw — the artefact, with the `:phase` it threw in, or one of the runtime's own steps — and `:where` records the boundary that caught it: `:safe-call-contribution!` for an artefact's teardown, `:safe-teardown-step!` for the runtime's own.

**This is a NON-EVENT record, and its category slots ride `:tags`.** The non-event projector keeps a **shorter** top-level summary set than the per-event one: only `:frame`, `:rf.error/id`, `:event-id`, `:elapsed-ms`, `:time` and `:correlation` (those the producer supplied), beside `:kind` and `:exception`. **Every other slot — `:hook-failures`, `:reason`, `:recovery` — is lifted onto `:tags`** and redacted under frame classification:

```clojure
{:kind  :rf.observe/error
 :rf.error/id :rf.error/frame-teardown-failed   ;; the discriminator; branch on it
 :frame :app/per-request-42
 :time  1715600000000
 :tags  {:recovery      :ignored          ;; teardown stays best-effort
         :reason        "2 frame-teardown step(s) threw during destroy; ..."
         :hook-failures [{:hook :http     :phase :post-dead :exception #object[...] :where :safe-call-contribution!}
                         {:hook :machines :phase :post-dead :exception #object[...] :where :safe-call-contribution!}]}}
```

So read `(get-in record [:tags :hook-failures])`; `(:hook-failures record)` on a sink record is `nil`. In **development** the per-step detail also surfaces as `:rf.warning/teardown-hook-exception` traces on the diagnostic channel.

### The promoted-SSR records: `:rf.error/ssr-*` (non-event)

The production-reachable **SSR error categories** ride this same axis (EP-0008). On a long-lived JVM SSR host an `:errors` sink receives them **even under `-Dre-frame.debug=false`**, where they are the only telemetry. The seven categories:

- **`:rf.error/ssr-render-failed`** — a render-time `Throwable` while building the response body (slots: `:frame`, `:exception`, `:exception-message`, `:ex-class`). Projection-eligible (the wire status is stamped), so promotion does not double-stamp.
- **`:rf.error/malformed-hydration-payload`** — a bad hydration payload (the hydrate-handler path AND the pre-frame **frameless** parse sub-path, the latter carrying `:frame nil`).
- **`:rf.error/ssr-head-resolution-failed`** — the active route's `:head` fn threw; the host degrades to an empty head fragment (slots: `:frame`, `:exception`). Non-projecting (still 200).
- **`:rf.error/sanitised-on-projection`** — the error projector threw / returned a non-`nil` non-`:rf/public-error` shape, or the configured `:public-error-id` names no registered projector; the runtime fell back to the locked generic-500 (slots: `:projector-id`, `:original-operation`, `:projection-failure-reason`). A projector returning `nil` defers to the default projector and records nothing. Non-projecting + re-entry-guarded.
- **`:rf.error/ssr-ring-error-view-failed`** — a caller-supplied `:error-view` threw; the host falls back to its locked default error template (slots: `:frame`, `:exception`, `:ex-class`). Non-projecting.
- **`:rf.error/hydration-frame-id-mismatch`** — a `:rf/hydrate` payload `:rf/frame-id` present-and-different from the frame being hydrated into fails **closed** (app-db + runtime-db unchanged) and emits this record (slots: `:where`, `:frame`, `:failing-id` `:rf/hydrate`, `:target-frame`, `:payload-frame-id`, `:reason`). Non-projecting.
- **`:rf.error/ssr-ring-response-status-invalid`** — the Ring materialiser met a non-integer response `:status` and rewrote it **closed** to 500 (slots: `:where`, `:status-type` — the offending value's class name, never the value — `:reason`, `:recovery`). **`:frame` is always `nil`**: the materialiser has no frame in scope. Non-projecting.

**None carries `:event` / `:event-id`, and some carry `:frame nil`.** Branch on `(:rf.error/id record)`, then read each category's slots **under `:tags`** — the same non-event lift as the teardown report: `:frame`, `:rf.error/id` and `:time` stay top-level beside `:kind` and `:exception`, and everything category-specific arrives as `(get-in record [:tags <slot>])`. Note `:where` and `:failing-id`: top-level summary slots on the per-event route, `:tags` slots here. The non-projecting members change what off-box shippers see, never the wire outcome. (Keep these distinct from the `:rf.ssr/*` *compatibility* diagnostics — version/digest/hydration mismatch — which stay trace-channel. See [`ssr-authoring.md`](ssr-authoring.md).)

## Triple-gate registration pattern

The sink routes are always-on; **registration sites** should belt-and-braces gate on explicit config + `goog.DEBUG=false` + a credential probe, so an accidental dev-bundle deploy with prod config doesn't quietly ship records to your back-end.

```clojure
(when (and (= "production" (:env config))
           (not ^boolean re-frame.interop/debug-enabled?)
           (:api-key config))
  (rf/register-observability-sink! :datadog/events
    (fn [event-record]
      (datadog/track-event! event-record)))
  (rf/register-observability-sink! :sentry/errors
    (fn [error-record]
      (sentry/capture-exception (:exception error-record)
                                {:tags {:event-id (:event-id error-record)
                                        :frame    (:frame error-record)}}))))
```

Three independent conditions: **config env tag** (the app knows it's production), **`goog.DEBUG=false`** (the bundle is the production bundle), **api-key present** (credentials wired). Skip any leg and you get the dev path.

## Why production has no trace bus

`re-frame.trace/emit!` and the `register-listener! :trace` plumbing are gated by `re-frame.interop/debug-enabled?`. Under `:advanced` + `goog.DEBUG=false`, the Closure compiler DCEs the entire trace surface — registrations, the ring buffer, the per-event allocation. The sink routes are the minimal surface that survives that elision. Re-enable the full trace bus in production with `:closure-defines {goog.DEBUG true}` only if the bundle cost is acceptable.

### The JVM production gate (`re-frame.debug` / `RE_FRAME_DEBUG`)

On the **JVM** — SSR hosts, headless tooling, test runners — there is no Closure DCE, so the diagnostic trace surface is gated at runtime by a switch set BEFORE `re-frame.interop` loads: **`-Dre-frame.debug=false`** (the Java system property) or **`RE_FRAME_DEBUG`** (the environment variable).

**The default depends on the JVM.** A JVM that hosts SSR (`day8/re-frame2-ssr-ring` on the classpath) defaults the gate **OFF**, so its REPL and test aliases opt in with `-Dre-frame.debug=true`. Any other JVM defaults it **ON** ("production-elided" means *elidable*, not *elided by default*): a production tooling or webhook process that does not set `-Dre-frame.debug=false` runs the **full dev diagnostic surface**, retaining user input in per-frame trace rings and epoch history, so it **MUST set `-Dre-frame.debug=false` explicitly**. The gate suppresses the **diagnostic trace** surface only — the always-on error axis survives it, so `:errors` sink shippers (the promoted SSR records included) keep delivering.

Full rationale: [`docs/core/observability.md`](https://github.com/day8/re-frame2/blob/main/docs/core/observability.md), [`spec/009-Instrumentation.md §What IS available in production`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#what-is-available-in-production), and [`spec/009-Instrumentation.md §JVM builds`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#jvm-builds).

## Generic shipper recipe (Datadog / Sentry / Honeycomb)

The record shapes are tight enough to ship verbatim. The pattern, on the off-box default:

```clojure
(rf/configure!
  {:observability {:handled-events [{:sink :observability/events}]}})

(rf/register-observability-sink! :observability/events
  (fn [{:keys [event-id time outcome elapsed-ms frame effects]}]
    (forward!
      {:name      (str event-id)
       :timestamp time                     ;; when the event settled, ms since epoch
       :tags      {:outcome outcome :frame frame}
       :duration  elapsed-ms
       :effects   effects})))
```

Want the event args as a payload too? Add `:rf.egress/profile :rf.egress/local-raw` to the entry and read the `:event` slot. **Understand what you are asking for:** classified-sensitive paths then arrive **in the clear** and large values **whole** — use it for an on-box pipeline, not a hosted back-end.

Worked vendor recipes (Datadog tags, Sentry breadcrumbs, Honeycomb spans): [`docs/core/how-to/report-errors-in-production.md`](https://github.com/day8/re-frame2/blob/main/docs/core/how-to/report-errors-in-production.md).

## Common gotchas

- **Sinks block the drain step.** Bodies run synchronously after each event settles. Ship work to a background channel (`requestIdleCallback`, queueing fetch, `setTimeout 0`) if it can't fit inside the per-event wall-clock budget.
- **Don't re-run elision in a sink — re-walking cannot widen anything.** Classified paths already *are* `:rf/redacted` and large ones `:rf.size/large-elided`; re-walking with `:rf.egress/include-sensitive?` / `:rf.egress/include-large?` flipped to `true` is a no-op too. Widening is chosen *before* projection, by putting `:rf.egress/profile :rf.egress/local-raw` on the entry.
- **Sensitivity redacts the payload, it does NOT drop the record.** Every event record fans out — you always get the event-id, frame, outcome and timing, a built-in audit trail of sensitive events without their secret values. The only whole-record drop is `:rf.trace/no-emit?` on handler-meta (and its frame-config sibling `:rf.trace/frame-no-emit?`): a **tool-author opt-out**, so a tool that dispatches its own events (a recorder, an inspector, the pair MCP) does not narrate its bookkeeping onto the wire it watches. It is **not** a privacy control — it drops the record for every consumer, auditors included (Spec 009 §Trace-emission opt-out).
- **Sink exceptions are swallowed.** The cascade catches; sibling sinks still run. You will NOT see a thrown sink error in the console; log inside the sink body if you want visibility.
- **Don't use the `:trace` stream for production observability.** `register-listener! :trace` dies under `:advanced` + `goog.DEBUG=false`, and `:trace` / `:epoch` are the only members its vocabulary has. The surviving production surfaces are enumerated in [`spec/009-Instrumentation.md` §What IS available in production](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#what-is-available-in-production).

## Cross-references

- Guide concept: [`docs/core/observability.md`](https://github.com/day8/re-frame2/blob/main/docs/core/observability.md). Worked vendor recipes: [`docs/core/how-to/report-errors-in-production.md`](https://github.com/day8/re-frame2/blob/main/docs/core/how-to/report-errors-in-production.md).
- Spec normative: [`spec/009-Instrumentation.md §What IS available in production`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md).
- Privacy composition: [`privacy-and-elision.md`](privacy-and-elision.md).

---

*Derived from `re-frame.observability` (`route-handled-event!` / `route-error!` / `route-error-record!`) and `re-frame.projection` (`project-egress`) @ main.*
