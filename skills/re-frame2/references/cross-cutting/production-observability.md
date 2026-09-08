# Production observability

The **normal** way an app ships event + error records to Datadog / Sentry / Honeycomb / a custom pipeline is **declarative, frame-owned**: declare a sink under a frame's `:observability` policy, register its fn with `rf/register-observability-sink!`, and let the runtime route one **already-projected** record per event/error to it. This EP-0015 egress path composes the owning frame's classification (`:sensitive` / `:large`) with the entry's `:rf.egress/profile`, so **on the off-box default** your sink never sees raw sensitive values and never has to re-walk for privacy / size. (The guarantee is the default's, not the route's: a sink whose entry opts into `:rf.egress/local-raw` is *asking* for classified values in the clear, which is that profile's whole purpose.) It is the **only** production door — the corpus-wide `register-listener!` `:events` / `:errors` streams were retired, per [`spec/API.md` §Observation listeners](https://github.com/day8/re-frame2/blob/main/spec/API.md#observation-listeners). Declare the same entry grammar with `(rf/configure! {:observability …})` for a cross-frame seat, and add `:rf.egress/profile :rf.egress/local-raw` when you want a *wider* projection — sensitive and large values kept rather than redacted and elided.

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

Both routes ride the **same two always-on substrates** that survive `goog.DEBUG=false` and `:advanced` — **parallel to** (not a fallback from) the dev-only trace bus, which DCEs in CLJS production builds. The frame `:observability` path is the projection-and-routing layer **beside** those substrates, and the substrates themselves are implementation tier — they carry no public registration verb. **The two are fed separately from one source event, not chained**: the substrate walks its own copy with `elide-wire-value` under off-box defaults, while the sink route builds its record from the *original* dispatched vector and projects it once, under the owning frame's classification and the entry's profile. A sink therefore never re-walks for privacy / size — the projector already did it, for that sink's profile. Egress composition: [`privacy-and-elision.md`](privacy-and-elision.md) §Choosing where observations go.

## The mental model: three channels, three production guarantees

Frame everything below against the **three observability channels** the runtime has — they answer different questions and survive production differently:

1. **The causal channel** — effects-as-data (`:dispatch`, `:fx`). It *is* the program, not a log line about it. **Never elided** — deleting it deletes the app.
2. **The diagnostic channel** — `register-listener!` on the `:trace` stream and the whole trace bus (every trace event, the per-frame rings, source-coord enrichment, correlation ids). Ambient, for dev eyes and tools. **Production-elided**: Closure-DCE'd under `:advanced` + `goog.DEBUG=false`; runtime-gated on the JVM (see below).
3. **The always-on error axis** — the `:observability :errors` sink route. It is **production-survivable**: NOT gated by `debug-enabled?`, so it survives elision, delivering one `:rf.error/*` record per production-reachable failure to your shipper (Sentry / Rollbar / Honeybadger). The `:handled-events` sink is the throughput/latency sibling on the same survives-production footing.

The JS-ecosystem anchor: this is the equivalent of "Sentry/Rollbar SDK for the error axis, an APM SDK for the event axis, and a Redux-DevTools-style time-travel bus for the diagnostic channel" — except the diagnostic bus is compiled *out* of production rather than tree-shaken at the edges, and the error axis is a first-class framework substrate rather than a global `window.onerror` hook. **Divergence to flag:** unlike a JS error SDK, you do not steer recovery from the sink (see below); recovery is framework-owned.

## When to load

Wiring a production observability shipper, declaring a frame `:observability` sink or the `(rf/configure! {:observability …})` process default, writing a sink fn body, or asking "what's the prod-survivable equivalent of the `:trace` stream?".

## `:handled-events` — one record per dispatched event

> **One production door.** Production observation is `register-observability-sink!` against a frame's `:observability` policy, or the same entry grammar declared once with `(rf/configure! {:observability …})`. The corpus-wide always-on listener streams were RETIRED from the public facade: independent corpus observation regardless of a frame's policy is withdrawn as a public primitive, per [`spec/009-Instrumentation.md` §The listener API](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#the-listener-api). The sections below document the always-on substrate the sink route lowers onto and the record shapes it carries.

Fires once per event the runtime processes — NOT per sub, NOT per fx, NOT per `:event/db-changed`. Registration is idempotent (re-registering the same sink id replaces); sink exceptions are caught (cascade continues).

```clojure
(rf/configure! {:observability {:handled-events [{:sink :datadog/events}]}})

(rf/register-observability-sink! :datadog/events
  (fn [event-record]
    (datadog/track-event! event-record)))

(rf/unregister-observability-sink! :datadog/events)
```

A sink receives the record **already projected** under the governing classification and the entry's egress profile. This is the shape it gets, on **every** profile:

**Sink record shape (`:rf.observe/handled-event`):**

```clojure
{:kind        :rf.observe/handled-event            ;; always present; branch on it
 :frame       :rf/default                          ;; resolved frame-id
 :event-id    :cart/checkout
 :status      :ok                                  ;; :ok | :error | :rolled-back | :flow-error | :rejected
 :elapsed-ms  12                                   ;; queue → settle, integer
 :effects     [:fx :dispatch]                      ;; the effect keys the cascade walked (when any)
 :correlation {:work-id … :dispatch-id …}          ;; when the cascade carried one
 :event       [:cart/checkout {:items [...]}]}     ;; ONLY under a sensitive-opted profile; see below
```

> **A profile chooses projection OPTIONS, not a different record.** `:rf.egress/profile` never selects which record constructor runs — the runtime always builds the `:rf.observe/handled-event` above and then projects it. So `:rf.egress/local-raw` does **not** hand you the substrate envelope: it is the trusted-local option set (`:rf.egress/include-sensitive? true`, `:rf.egress/include-large? true`), and its one visible effect on this record is that the `:event` args slot is **kept** — still projected, never raw — where the off-box default omits it entirely. The dispatch result is spelled **`:status`** on every profile, and there is **no `:time` slot on a handled-event record at all**. Destructuring `outcome` or `time` in a sink fn binds `nil`.

`:status` covers **every** cascade-failure path, not just the interceptor-chain exception, so a dispatch that aborted is never mis-reported as a clean `:ok`:

- `:ok` — clean settle (db committed, flows ran, `:fx` walked).
- `:error` — the interceptor chain (handler or interceptor) threw.
- `:rolled-back` — `:db` schema validation rejected the candidate state before it installed, so the container kept its pre-handler value (Spec 010 §Per-step recovery row 4); flows and `:fx` were skipped.
- `:flow-error` — a flow's `:output` threw (Spec 013 §Failure semantics rule 3); the cascade halted before `:fx`.
- `:rejected` — a `:boundary? true` handler's `:schema` refused the event's payload. The handler never ran; entered interceptors still unwound in full. It is the lowest-priority discriminator: a chain throw, a flow throw or a candidate rollback during the unwind still wins.

**`:rejected` and `:rolled-back` are exact complements on the production question, and a shipper should treat them that way.** `:rolled-back` reports the `reg-app-schema` candidate check, which a release build elides — so its *absence* in production is not evidence that no schema was violated. `:rejected` reports the `:boundary? true` check, which Spec 010 keeps ungated — so it does have a producer in a release build, and it is the outcome to alert on for malformed or hostile input at an untrusted ingress. Each rejection is paired with one always-on `:rf.error/schema-validation-failure` record (`:source :boundary`) on the `:errors` stream carrying the identifiers; the offending value rides the dev-only trace and never egresses.

What decides which side a check falls on is **what the check is for, not who declared the schema it reads** (Spec 000 C-000.35): an ordinary registration diagnostic elides, while a check the framework relies on to keep a promise of its own holds in every build. So the boundary check is not the only schema check that survives — a declared route's `:params` / `:query` shape, a recordable coeffect's `:schema`, the reserved `:rf.server/*` effects' checks on their own arguments and Managed HTTP's `:decode` all run in a release build too ([`../fundamentals/schemas.md`](../fundamentals/schemas.md#what-survives-is-settled-by-what-the-check-is-for-not-by-who-declared-it) is the full matrix). The recordable coeffect is the one to read carefully: that `:schema` is declared on the programmer's own `reg-cofx` and survives regardless, because the framework applies it where the value folds into the durable record. What is exclusive is narrower, and is the thing a shipper actually wires an alert to: those others throw or reject on their own paths, and a throw reports `:error` like any other, so `:rejected` is the only value in this vocabulary that names a surviving schema check as such.

No trace-bus keys (no `:dispatch-id`, `:parent-dispatch-id`, `:rf.trace/trigger-handler`, source coords) — those ride the dev-only trace surface.

**The substrate record underneath — IMPLEMENTATION TIER, not what your sink receives.**
The sink route lowers onto the always-on `re-frame.event-emit` substrate, whose own record is a different shape. You will meet it reading framework source or a framework-internal capture bracket; it has **no public registration verb**, and **no egress profile reaches it**.

```clojure
;; re-frame.event-emit/dispatch-on-event! — IMPLEMENTATION TIER
{:event      [:cart/checkout {:items [...]}]
 :event-id   :cart/checkout
 :frame      :rf/default
 :time       1715600000000                         ;; emit timestamp, ms since epoch
 :outcome    :ok                                   ;; the substrate's spelling of :status
 :elapsed-ms 12}
```

`:outcome` and `:time` live **only** here. The projected `:rf.observe/handled-event` above is the record every sink sees, on every profile.

**Privacy is path-based, applied at egress — the record always fans out.** **There are TWO independent projections of one source event, never one feeding the other.** The substrate walks *its* `:event` copy with `elide-wire-value` under off-box defaults (large → `:rf.size/large-elided`; classified sensitive paths → `:rf/redacted`) before its own fan-out. The sink route is fed separately: it builds a fresh `:rf.observe/*` record from the **original dispatched vector** and projects that once, under the governing frame's classification and the entry's profile. So the profile on your entry decides what *your* sink sees, and the substrate's redaction neither constrains nor contributes to it — an entry on `:rf.egress/local-raw` receives classified values in the clear on a dispatch whose substrate record reads `:rf/redacted`. Sensitivity is owner-classified by *path* (the registration's `:sensitive` paths for transient event args; the durable app-db `:sensitive` classification effect a handler returns alongside `:db`), **fail-open** — an unclassified path ships raw. **No** whole-record privacy drop at the handler boundary: `dispatch-on-event!` never suppresses a record for sensitivity — it redacts the payload per `[:rf.runtime/elision :sensitive-declarations]` (runtime-db) and ships the rest. (Handler-meta `:sensitive?` is **not** consulted — removed from the runtime, see `event_emit.cljc` docstring.) The only whole-record drop gate is `:rf.trace/no-emit?` on handler-meta — a **documented opt-out for tool authors**, not a privacy knob. It exists so a tool that dispatches its own events (a recorder, an inspector, the pair MCP) does not narrate its own bookkeeping onto the wire it is watching; Xray, Story and the pair use it, and so may you. Its frame-scoped sibling is `:rf.trace/frame-no-emit?` in the frame config. Never reach for either to hide sensitive data: sensitivity is path-classified and redacted at egress, and a `no-emit?` handler drops the record for *everyone*, auditors included. (Spec 009 §Trace-emission opt-out is the contract; the observability guide's Advanced section is the worked recipe.)

## `:errors` — one record per runtime error

Fires once per catalogued production-reachable `:rf.error/*` event the runtime emits through the error-emit substrate. This is the single error-observability surface; per-sink exceptions are isolated (one bad sink cannot affect siblings or the cascade).

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

The payload is an **error-keyed union** of several record shapes — the per-event error record below, the frame-teardown report (§The first promoted category), and the EP-0008-promoted **non-event SSR records** (§The promoted-SSR records). **Always branch on `(:error record)` — never assume `:event` / `:event-id` / `:exception` are present.** Only the per-event records carry those slots; the teardown report and the SSR records do not (a teardown report carries `:hook-failures`; the SSR records carry `:frame` + category-specific slots, some with no `:event` at all). A sink fn that destructures `:event-id` / `:exception` off every record will NPE on a non-event record.

**Sink record shape (`:rf.observe/error`) — per-event arm:**

```clojure
{:kind       :rf.observe/error                     ;; always present; branch on it
 :error      :rf.error/handler-exception           ;; the error keyword — the discriminator
 :event      [:cart/checkout {...}]                ;; tree slot: walked under frame policy
 :event-id   :cart/checkout
 :frame      :rf/default
 :time       1715600000000                         ;; ms since epoch — summary slot, always present
 :exception  #error{...}                           ;; dropped ONLY under :rf.egress/public-error
 :elapsed-ms 8}                                    ;; queue → throw, integer
```

Unlike the handled-event record this one **does** carry `:time`, and it carries `:error` rather than `:status`. `:failing-id`, `:flow-id`, `:where` and `:source-coord` ride the top level as summary slots when the producer supplied them; every other attribution slot (`:reason` among them) is lifted onto a `:tags` tree the projector walks — so read `(get-in record [:tags :reason])`, not `(:reason record)`.

The `:rf.egress/profile` on the entry chooses **how much of the tree survives**, never which record you get: the default `:rf.egress/off-box-observability` redacts classified-sensitive paths and elides large ones, `:rf.egress/local-raw` keeps both, and `:rf.egress/public-error` additionally drops the top-level `:exception`. `:kind`, `:error` and the summary ids are there on all three.

### Recovery is framework-owned — there is no app-steering policy

Error **recovery** is not an app-config concern. The runtime applies a **typed per-category default**: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app, no-such-handler / no-such-sub no-op. There is no per-frame `:on-error` recovery policy — recovery is framework-owned. Genuine recovery for **expected** failures is handled at the source — managed-HTTP `:retry`, optional-read fallback — where "recovery" actually has meaning. Off-box **observability** is the `:errors` sink (above). To re-run a failed event, dispatch a fresh one; the runtime never re-runs the failing handler.

### What rides the always-on axis: the promotion criterion

The error axis is small — an alert on it should *mean something*. Most failures stay diagnostic (dev-visible, production-elided); only a specific shape earns a production-survivable record. A category is on the axis only when **all three legs** hold:

1. **Production-reachable** — it can fire in a production build, not just a dev-time misuse caught at the boundary (a malformed-registration shape, a dev-only schema check: those stay diagnostic).
2. **A contract breach or resource leak the caller can't already see** — the load-bearing leg. A bad event vector throws at the call site; the caller sees it. A *leaked handle / skipped teardown / suppressed write / corrupted invariant* leaves the process in a state the next operation can't observe — that needs an off-box record.
3. **Silence compounds** — the cost of nobody hearing grows with process lifetime / request volume / retries. A leaked timer per SSR request is cheap once and fatal at ten million.

**The `:rf.error/*` namespace is framework-owned — do not mint app/domain categories under it.** The `:errors` sink route is a *consume* surface, not *emit*: app code does not raise its own `:rf.error/*` records through it, the `:rf/*` namespace is reserved (Cardinal rule 7), and the catalogue of categories on this axis is fixed and framework-defined. App/domain telemetry has its own homes: an **app-owned namespace** for custom trace/log keys (`:myapp.error/payment-declined`), a framework **public API** where one exists, or your ordinary logging / observability pipeline addressed directly from your handler. Keeping domain failures off `:rf.error/*` keeps the stream pure signal — every record a framework-recognised production-reachable failure. The axis is `:rf.error/*`-only (never `:rf.warning/*`) and carries **structured data only** (ids/keys/frame, never raw values — egress redaction applies).

### The first promoted category: `:rf.error/frame-teardown-failed`

One always-on category beyond the per-failure errors is worth knowing because its record shape differs. When a frame is destroyed the runtime runs a best-effort recipe of teardown steps — optional late-bound cleanup hooks plus a few guarded direct steps (notably the `:frame/notify-machine-destruction!` machine cascade). A throwing step is a resource leak the next operation can't see and compounds per SSR request — all three legs hold. Rather than flood the shipper with one record per failed step, the runtime emits **one bounded report per destroy** carrying a `:hook-failures` vector. Each entry's `:hook` names the step that threw (either kind — the `:hook-failures` / `:hook` wire names are deliberately stable) and `:where` records the boundary that caught it: `:safe-call-hook!` for a late-bound hook, `:safe-teardown-step!` for a guarded direct step.

**This is a NON-EVENT record, and on the sink route its category slots ride `:tags`.** The non-event projector keeps a **different, shorter** summary set than the per-event one above: only `:frame`, `:error`, `:event-id`, `:elapsed-ms`, `:time` and `:correlation` stay at the top level (those the producer actually supplied), beside the `:kind` it stamps and the `:exception` it handles separately. **Every other slot the producer wrote flat — `:hook-failures`, `:reason`, `:recovery` — is lifted onto `:tags`**, so the projector walks and redacts it under frame classification (a `:hook-failures` entry's nested ex-data included). This is the shape your `:errors` sink body must handle, with `:error` — not `:operation` — as the discriminator:

```clojure
{:kind  :rf.observe/error
 :error :rf.error/frame-teardown-failed   ;; the discriminator; branch on it
 :frame :app/per-request-42
 :time  1715600000000
 :tags  {:recovery      :ignored          ;; teardown stays best-effort
         :reason        "2 frame-teardown step(s) threw during destroy; ..."
         :hook-failures [{:hook :http/abort-inflight :exception #object[...] :where :safe-call-hook!}
                         {:hook :timers/clear        :exception #object[...] :where :safe-call-hook!}]}}
```

So read `(get-in record [:tags :hook-failures])` and `(get-in record [:tags :reason])`. **`(:hook-failures record)` on a sink record is `nil`** — top level is where the *implementation-tier* `re-frame.error-emit` envelope carries them (`{:error :frame :hook-failures :reason :recovery :time}`, flat), and that envelope reaches framework source and framework-internal capture brackets, never a sink.

In **development** the per-step detail still surfaces as `:rf.warning/teardown-hook-exception` traces on the diagnostic channel (at their causal positions, DCE'd in prod); the single always-on report is what a production shipper sees. A generic shipper body that maps `(:error record)` to the alert name and forwards the rest already handles this category without special-casing — the only gotcha is not assuming an `:event`/`:event-id` slot (a teardown report has neither; branch on `(:error record)` if you need the per-category shape).

### The promoted-SSR records: `:rf.error/ssr-*` (non-event)

The production-reachable **SSR error categories** ride this same always-on axis (EP-0008). On a long-lived JVM SSR host, an `:errors` sink receives them **even under `-Dre-frame.debug=false`** — where the dev trace surface is elided, the off-box record is the only telemetry. The eight categories:

- **`:rf.error/ssr-render-failed`** — a render-time `Throwable` while building the response body (slots: `:frame`, `:exception`, `:exception-message`, `:ex-class`). Projection-eligible (the wire status is stamped), so promotion does not double-stamp.
- **`:rf.error/ssr-streaming-writer-failed`** — a streaming-SSR writer thread threw on a post-commit chunk (slots: `:frame`, `:exception`, `:ex-class`, `:phase`, `:boundary-id` on continuation phases, `:committed? true`). Non-projecting (the 200 already committed).
- **`:rf.error/malformed-hydration-payload`** — a bad hydration payload (the hydrate-handler path AND the pre-frame **frameless** parse sub-path, the latter carrying `:frame nil`).
- **`:rf.error/ssr-head-resolution-failed`** — the active route's `:head` fn threw; the host degrades to an empty head fragment (slots: `:frame`, `:exception`). Recoverable-degradation, non-projecting (still 200).
- **`:rf.error/sanitised-on-projection`** — the error projector itself threw / returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 (slots: `:projector-id`, `:original-operation`, `:projection-failure-reason`). Non-projecting + re-entry-guarded (one-shot, never re-projects).
- **`:rf.error/ssr-ring-error-view-failed`** — a caller-supplied `:error-view` threw; the host falls back to its locked default error template (slots: `:frame`, `:exception`, `:ex-class`). Non-projecting.
- **`:rf.error/hydration-frame-id-mismatch`** — the `:rf/hydrate` handler's direct-`dispatch-sync` guard: a payload `:rf/frame-id` present-and-different from the frame being hydrated into fails **closed** (app-db + runtime-db left unchanged, no compatibility-check fxs) and emits this record (slots: `:where`, `:frame`, `:failing-id` `:rf/hydrate`, `:target-frame`, `:payload-frame-id`, `:reason`). Non-projecting.
- **`:rf.error/ssr-ring-response-status-invalid`** — the Ring materialiser met a non-integer response `:status` and rewrote it **closed** to 500 (slots: `:where`, `:status-type` — the offending value's *class name*, never the value itself — `:reason`, `:recovery`). **Frameless: `:frame` is always `nil`**, because the materialiser is a pure map→map fn with no frame in scope — honestly always nil rather than sometimes-populated. Non-projecting: it fires at materialisation time, after the status is already resolved, so a projection could only fight the 500 it is reporting.

**These are NON-EVENT records — none carries `:event` / `:event-id`, and some carry `:frame nil` (the frameless hydration-parse path; `:rf.error/ssr-ring-response-status-invalid` always).** A sink that assumes the per-event shape NPEs; branch on `(:error record)`, then read each category's own slots **under `:tags`**. The slot names listed above are the *producer's* flat names, and they take the same non-event lift as the teardown report: `:frame`, `:error` and `:time` stay at the top level beside `:kind`, `:exception` rides its own top-level slot, and everything category-specific — `:phase`, `:ex-class`, `:exception-message`, `:boundary-id`, `:committed?`, `:projector-id`, `:original-operation`, `:projection-failure-reason`, `:where`, `:failing-id`, `:target-frame`, `:payload-frame-id`, `:status-type`, `:reason`, `:recovery` — arrives as `(get-in record [:tags <slot>])`. Note `:where` and `:failing-id` in that list: they are top-level **summary** slots on the per-event route above, and `:tags` slots here. The two routes do not share one whitelist. The recoverable-degradation members (`:rf.error/ssr-head-resolution-failed`, `:rf.error/ssr-ring-error-view-failed`) and the post-commit members (`:rf.error/ssr-streaming-writer-failed`, `:rf.error/sanitised-on-projection`) are **non-projecting**, as is the materialisation-time `:rf.error/ssr-ring-response-status-invalid` — their riding the always-on axis changes what off-box shippers see, never the wire outcome. (Keep these distinct from the `:rf.ssr/*` *compatibility* diagnostics — version/digest/hydration mismatch — which stay trace-channel and do NOT ride this axis. See [`ssr-authoring.md`](ssr-authoring.md).)

## Triple-gate registration pattern

The substrate is always-on; **registration sites** should belt-and-braces gate on explicit config + `goog.DEBUG=false` + a credential probe, so an accidental dev-bundle deploy with prod config doesn't quietly ship records to your back-end.

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

Three independent conditions: **config env tag** (the app knows it's production), **`goog.DEBUG=false`** (the bundle is the production bundle), **api-key present** (credentials wired). Skip any leg and you get the dev path. Pattern documented in `re-frame.event-emit` ns docstring §goog.DEBUG framing.

## Why production has no trace bus

`re-frame.trace/emit!` and the `register-listener! :trace` plumbing are gated by `re-frame.interop/debug-enabled?`. Under `:advanced` + `goog.DEBUG=false`, the Closure compiler DCEs the entire trace surface — registrations, the ring buffer, the per-event allocation, every `tag/value` map. The bundle savings and per-event allocation savings are part of re-frame2's "production debugging is opt-out, not opt-in" stance.

The two always-on substrates behind the `:handled-events` / `:errors` sink routes carve a minimal surface that **survives** that elision: a tiny record shape, a `defonce` registry that hot reload won't blow away, fan-out gated on registry size (empty-map check short-circuits). Re-enable the full trace bus in production by flipping `:closure-defines {goog.DEBUG true}` if and only if the bundle cost is acceptable.

### The JVM production gate (`re-frame.debug` / `RE_FRAME_DEBUG`)

`goog.DEBUG=false` is the **CLJS** posture gate (Closure DCE). On the **JVM** — SSR hosts, headless tooling, test runners — there is no Closure DCE, so the diagnostic trace surface is gated at runtime by a separate switch, set BEFORE `re-frame.interop` loads:

- **`-Dre-frame.debug=false`** — the Java system property on the JVM command line, or
- **`RE_FRAME_DEBUG`** — the process environment variable.

**The JVM default is ON** ("production-elided" means *elidable*, not *elided by default*). A production JVM SSR / tooling process that does not set `-Dre-frame.debug=false` runs the **full dev diagnostic surface** — retaining user input in per-frame trace rings and epoch history. EP-0008 calls this out: a JVM artefact shipped for production **MUST set `-Dre-frame.debug=false` explicitly** in its deployment (the audit-finding posture — an SSR/headless process should not retain user input by default). These are build-time / process-start gates that select the posture; apps do not toggle them per-request. Critically, the gate suppresses the **diagnostic trace** surface only — the **always-on error-emit axis (surface #4) survives it**, so `:errors` sink shippers (including the promoted SSR records above) keep delivering under `-Dre-frame.debug=false`. That is the whole point of the always-on split: event/error observability survives the production posture; the dev trace surface does not.

Full rationale: [`docs/core/observability.md`](https://github.com/day8/re-frame2/blob/main/docs/core/observability.md), [`spec/009-Instrumentation.md §What IS available in production`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#what-is-available-in-production), and [`spec/009-Instrumentation.md §JVM builds`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#jvm-builds).

## Generic shipper recipe (Datadog / Sentry / Honeycomb)

The record shapes are tight enough to ship verbatim — every observability vendor's wire format is a strict subset of "event-id + timestamp + tags + duration". The pattern, on the off-box default:

```clojure
(rf/configure!
  {:observability {:handled-events [{:sink :observability/events}]}})

(rf/register-observability-sink! :observability/events
  (fn [{:keys [event-id status elapsed-ms frame effects]}]
    (forward!
      {:name      (str event-id)
       :timestamp (js/Date.now)            ;; a handled-event record carries NO :time slot
       :tags      {:status status :frame frame}
       :duration  elapsed-ms
       :effects   effects})))
```

**Destructure `:status`, not `:outcome`, and stamp your own timestamp.** Those two slots are where a hand-written sink goes wrong: `:outcome` and `:time` belong to the implementation-tier substrate record, not to the `:rf.observe/handled-event` your sink receives — bind them here and you forward `nil` under both keys, and `(name nil)` throws.

Want the event args as a payload too? Add `:rf.egress/profile :rf.egress/local-raw` to the entry and read the `:event` slot, which the off-box default omits entirely. **Understand what you are asking for:** `local-raw` is the trusted-local option set, so classified-sensitive paths arrive **in the clear** and large values arrive **whole** — it is the profile that stops eliding, not one that hands you a different record. Use it for an on-box pipeline, not for a hosted back-end. On the off-box default the surviving slots are structural metadata only, and there is nothing left to re-walk. See [`privacy-and-elision.md`](privacy-and-elision.md) for the elision composition rules.

Worked vendor recipes (Datadog tags, Sentry breadcrumbs, Honeycomb spans): [`docs/core/how-to/report-errors-in-production.md`](https://github.com/day8/re-frame2/blob/main/docs/core/how-to/report-errors-in-production.md).

## Common gotchas

- **Sinks block the drain step.** Bodies run synchronously after each event settles. Ship work to a background channel (`requestIdleCallback`, queueing fetch, `setTimeout 0`) if it can't fit inside the per-event wall-clock budget.
- **Don't re-run elision in a sink — re-walking cannot widen anything.** On the off-box default the record you receive was already projected: classified paths *are* `:rf/redacted` and large ones `:rf.size/large-elided`, because the projector substituted them. Re-walking with defaults is a no-op, and re-walking with `:rf.egress/include-sensitive?` / `:rf.egress/include-large?` flipped to `true` is **equally** a no-op — there is nothing left in the record to widen. Widening is chosen *before* projection, by putting `:rf.egress/profile :rf.egress/local-raw` on the entry.
- **Sensitivity redacts the payload, it does NOT drop the record.** There is no handler-level "drop the whole record" privacy gate — every event record fans out, and classified sensitive paths (the registration's `:sensitive` event-arg paths; the durable app-db `:sensitive` classification effect's slices) in its `:event` payload arrive as `:rf/redacted` (per `elide-wire-value` egress). You always get the event-id, frame, `:status`, and timing — a built-in audit trail of sensitive events without their secret values. The only whole-record drop (`:rf.trace/no-emit?`, and its frame-scoped sibling `:rf.trace/frame-no-emit?`) is a documented tool-author opt-out — it exists so a tool that dispatches its own events stays quiet on the wire it watches — and it is emphatically **not** a privacy control: it drops the record for every consumer, not just the untrusted one.
- **Sink exceptions are swallowed.** The cascade catches; sibling sinks still run. You will NOT see a thrown sink error in the console; log inside the sink body if you want visibility.
- **Don't use the `:trace` stream for production observability.** `register-listener! :trace` dies under `:advanced` + `goog.DEBUG=false`, and `:trace` / `:epoch` are the only members its vocabulary has. The `:observability` sink is the prod-survivable channel.
- **There is no corpus-wide listener any more.** The always-on listener streams were retired from the public facade; the surviving production surfaces are enumerated in [`spec/009-Instrumentation.md` §What IS available in production](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#what-is-available-in-production). A cross-frame seat is `(rf/configure! {:observability …})`; a wider projection — sensitive and large kept — is `:rf.egress/local-raw` on the entry, which is still a projected `:rf.observe/*` record and not the substrate envelope.

## Cross-references

- Guide concept: [`docs/core/observability.md`](https://github.com/day8/re-frame2/blob/main/docs/core/observability.md) — narrative walkthrough of the one-wire substrate and what survives elision. Worked vendor recipes: [`docs/core/how-to/report-errors-in-production.md`](https://github.com/day8/re-frame2/blob/main/docs/core/how-to/report-errors-in-production.md).
- Spec normative: [`spec/009-Instrumentation.md §What IS available in production`](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md) — substrate contracts.
- Privacy composition: [`privacy-and-elision.md`](privacy-and-elision.md) — owner-classified sensitive paths (the durable app-db `:sensitive` classification effect / registration `:sensitive`) are redacted to `:rf/redacted` by `elide-wire-value`; payload already walked before your sink sees it. No whole-record drop.

---

*Derived from `re-frame.observability`, `re-frame.projection`, `re-frame.event-emit` and `re-frame.error-emit` @ main. Verified surfaces: the projected sink records (`route-handled-event!` / `route-error!` / `route-error-record!` in `observability.cljc`, projected by `project-egress` in `projection.cljc`); registration is `register-observability-sink!` against a frame's `:observability` policy or the `(rf/configure! {:observability …})` process default. The implementation-tier substrates underneath are `dispatch-on-event!` (`event_emit.cljc`) and `dispatch-on-error!` / `dispatch-frame-teardown-report!` (`error_emit.cljc`); their `:outcome` enum is per each ns docstring §Record shape.*
