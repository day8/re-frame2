# Production observability

Production observability rides **two always-on listener APIs** that survive `goog.DEBUG=false` and `:advanced` compilation. They are **parallel to** (not a fallback from) the dev-only trace bus. The trace surface DCEs in CLJS production builds; `register-event-listener!` and `register-error-listener!` do not. Use them to ship event + error records to Datadog / Sentry / Honeycomb / a custom pipeline.

Authoring rule: in production, you wire two listeners — one for events (success + error outcomes), one for errors (exception payloads). The framework already runs `elide-wire-value` against each record's `:event` vector before fan-out — listeners do **not** re-walk for privacy / size.

## When to load

Wiring a production observability shipper, writing a `register-event-listener!` / `register-error-listener!` body, or asking "what's the prod-survivable equivalent of `register-listener!`?".

## `register-event-listener!` — one record per dispatched event

Fires once per event the runtime processes — NOT per sub, NOT per fx, NOT per `:event/db-changed`. Registration is idempotent (re-registering the same id replaces); listener exceptions are caught (cascade continues).

```clojure
(rf/register-event-listener!
  :datadog/events
  (fn [event-record]
    (datadog/track-event! event-record)))

(rf/unregister-event-listener! :datadog/events)
```

**Record shape (tight — Spec 009 §Event-emit listener):**

```clojure
{:event      [:cart/checkout {:items [...]}]      ;; the dispatched event vector (elided)
 :event-id   :cart/checkout                        ;; (first event)
 :frame      :rf/default                           ;; resolved frame-id
 :time       1715600000000                         ;; emit timestamp (host clock, ms since epoch)
 :outcome    :ok                                   ;; :ok | :error
 :elapsed-ms 12}                                   ;; queue → settle, integer
```

No trace-bus keys (no `:dispatch-id`, `:parent-dispatch-id`, `:rf.trace/trigger-handler`, source coords) — those ride the dev-only trace surface. Verified: `re-frame.event-emit/dispatch-on-event!` (`event_emit.cljc:167-230`); record shape per the ns docstring §Record shape.

**Privacy is path-based, applied at egress — the record always fans out.** Every surviving record's `:event` vector is walked by `elide-wire-value` with off-box defaults (large → `:rf.size/large-elided`; schema-declared sensitive paths → `:rf/redacted`) *before* listeners run. There is **no** whole-record privacy drop at the handler boundary: `dispatch-on-event!` never suppresses a record for sensitivity — it redacts the payload per `[:rf.runtime/elision :sensitive-declarations]` (in runtime-db) and ships the rest. (Handler-meta `:sensitive?` is **not** consulted; it was removed from the runtime — see `event_emit.cljc` ns docstring.) The only whole-record drop gate is `:rf.trace/no-emit?` on handler-meta, which is **framework-internal** (Xray / Story bookkeeping handlers) and not a user privacy knob.

## `register-error-listener!` — one record per runtime error

Fires once per catalogued production-reachable `:rf.error/*` event the runtime emits through the error-emit substrate. This is the single error-observability surface; per-listener exceptions are isolated (one bad listener cannot affect siblings or the cascade).

```clojure
(rf/register-error-listener!
  :sentry/errors
  (fn [error-record]
    (sentry/capture-exception
      (:exception error-record)
      {:tags {:event-id (:event-id error-record)
              :frame    (:frame error-record)}})))

(rf/unregister-error-listener! :sentry/errors)
```

**Record shape (tight — Spec 009 §Error-emit listener):**

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

Error **recovery** is not an app-config concern. The runtime applies a **typed per-category default**: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app, no-such-handler / no-such-sub no-op. There is no per-frame `:on-error` recovery policy — it was removed per rf2-hiqtk8 (errors are not generically recoverable by an app policy; the policy's return value was never read or applied). Genuine recovery for **expected** failures is handled at the source — managed-HTTP `:retry`, optional-read fallback — where "recovery" actually has meaning. Off-box **observability** is `register-error-listener!` (above). To re-run a failed event, dispatch a fresh one; the runtime never re-runs the failing handler.

## Triple-gate registration pattern

The substrate is always-on; **registration sites** should belt-and-braces gate on explicit config + `goog.DEBUG=false` + a credential probe, so an accidental dev-bundle deploy with prod config doesn't quietly ship records to your back-end.

```clojure
(when (and (= "production" (:env config))
           (not ^boolean re-frame.interop/debug-enabled?)
           (:api-key config))
  (rf/register-event-listener!
    :datadog/events
    (fn [event-record]
      (datadog/track-event! event-record)))
  (rf/register-error-listener!
    :sentry/errors
    (fn [error-record]
      (sentry/capture-exception (:exception error-record)
                                {:tags {:event-id (:event-id error-record)
                                        :frame    (:frame error-record)}}))))
```

Three independent conditions: **config env tag** (the app knows it's production), **`goog.DEBUG=false`** (the bundle is the production bundle), **api-key present** (credentials wired). Skip any leg and you get the dev path. Pattern documented in `re-frame.event-emit` ns docstring §goog.DEBUG framing.

## Why production has no trace bus

`re-frame.trace/emit!` and the `register-listener!` plumbing are gated by `re-frame.interop/debug-enabled?`. Under `:advanced` + `goog.DEBUG=false`, the Closure compiler DCEs the entire trace surface — registrations, the ring buffer, the per-event allocation, every `tag/value` map. The bundle savings (~12-15 KB gzipped) and per-event allocation savings are part of re-frame2's "production debugging is opt-out, not opt-in" stance.

The two always-on listener APIs carve a minimal substrate that **survives** that elision: a tiny record shape, a `defonce` registry that hot reload won't blow away, fan-out gated on registry size (empty-map check short-circuits). Re-enable the full trace bus in production by flipping `:closure-defines {goog.DEBUG true}` if and only if the bundle cost is acceptable.

Full rationale: [`docs/guide/16-observability.md`](../../../../docs/guide/16-observability.md) and [`spec/009-Instrumentation.md §What IS available in production`](../../../../spec/009-Instrumentation.md) (line 489).

## Generic shipper recipe (Datadog / Sentry / Honeycomb)

The record shapes are tight enough to ship verbatim — every observability vendor's wire format is a strict subset of "event-id + timestamp + tags + payload". The pattern:

```clojure
(rf/register-event-listener!
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

Worked vendor recipes (Datadog tags, Sentry breadcrumbs, Honeycomb spans): [`docs/guide/16-observability.md`](../../../../docs/guide/16-observability.md).

## Common gotchas

- **Listeners block the drain step.** Bodies run synchronously after each event settles. Ship work to a background channel (`requestIdleCallback`, queueing fetch, `setTimeout 0`) if it can't fit inside the per-event wall-clock budget.
- **Don't re-run elision unless widening.** The record already has off-box defaults applied. A listener that re-walks with defaults is a no-op; one that flips `:include-large?` / `:include-sensitive?` to `true` exposes data you'd otherwise hide.
- **Sensitivity redacts the payload, it does NOT drop the record.** There is no handler-level "drop the whole record" privacy gate — every event record fans out, and schema-declared sensitive paths in its `:event` payload arrive as `:rf/redacted` (per `elide-wire-value` egress). You always get the event-id, frame, outcome, and timing — a built-in audit trail of sensitive events without their secret values. The only whole-record drop (`:rf.trace/no-emit?`) is framework-internal and not yours to set.
- **Listener exceptions are swallowed.** The cascade catches; sibling listeners still run. You will NOT see a thrown listener error in the console; log inside the listener body if you want visibility.
- **Don't use `register-listener!` for production observability.** It dies under `:advanced` + `goog.DEBUG=false`. The two `*-emit-listener!` surfaces are the prod-survivable channel.

## Cross-references

- Guide chapter: [`docs/guide/16-observability.md`](../../../../docs/guide/16-observability.md) — narrative walkthrough with vendor-specific recipes.
- Spec normative: [`spec/009-Instrumentation.md §What IS available in production`](../../../../spec/009-Instrumentation.md) (line 489) — substrate contracts.
- Privacy composition: [`privacy-and-elision.md`](privacy-and-elision.md) — schema-declared sensitive paths are redacted to `:rf/redacted` by `elide-wire-value`; payload already walked at listener entry. No whole-record drop.

---

*Derived from `re-frame.event-emit` and `re-frame.error-emit` @ main. Verified surfaces: `register-event-listener!` (event_emit.cljc:139), `register-error-listener!` (error_emit.cljc:139); record shapes per each ns docstring §Record shape.*
