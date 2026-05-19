# Production observability

Production observability rides **two always-on listener APIs** that survive `goog.DEBUG=false` and `:advanced` compilation. They are **parallel to** (not a fallback from) the dev-only trace bus. The trace surface DCEs in CLJS production builds; `register-event-emit-listener!` and `register-error-emit-listener!` do not. Use them to ship event + error records to Datadog / Sentry / Honeycomb / a custom pipeline.

Authoring rule: in production, you wire two listeners — one for events (success + error outcomes), one for errors (exception payloads). The framework already runs `elide-wire-value` against each record's `:event` vector before fan-out — listeners do **not** re-walk for privacy / size.

## When to load

Wiring a production observability shipper, writing a `register-event-emit-listener!` / `register-error-emit-listener!` body, or asking "what's the prod-survivable equivalent of `register-trace-cb!`?".

## `register-event-emit-listener!` — one record per dispatched event

Per rf2-rirbq. Fires once per event the runtime processes — NOT per sub, NOT per fx, NOT per `:event/db-changed`. Registration is idempotent (re-registering the same id replaces); listener exceptions are caught (cascade continues).

```clojure
(rf/register-event-emit-listener!
  :datadog/events
  (fn [event-record]
    (datadog/track-event! event-record)))

(rf/unregister-event-emit-listener! :datadog/events)
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

**Handler-meta `:sensitive?` honoured BEFORE elision** (per rf2-6hklf): if the event's registered handler-meta carries `:sensitive? true`, `dispatch-on-event!` drops the record entirely — listeners are NOT invoked, regardless of which paths in the payload happen to match `[:rf/elision]` declarations. Sensitive at the handler boundary is the headline privacy filter.

## `register-error-emit-listener!` — one record per runtime error

Per rf2-bacs4. Fires once per `:rf.error/*` event the runtime emits through the error-emit substrate (handler exceptions today; the substrate is the normative seam for future `:rf.error/*` records). Independent of the per-frame `:on-error` policy fn (per rf2-hqbeh) — both fan out from the same emission site; one bad listener cannot affect the policy fn, and vice versa.

```clojure
(rf/register-error-emit-listener!
  :sentry/errors
  (fn [error-record]
    (sentry/capture-exception
      (:exception error-record)
      {:tags {:event-id (:event-id error-record)
              :frame    (:frame error-record)}})))

(rf/unregister-error-emit-listener! :sentry/errors)
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

Verified: `re-frame.error-emit/dispatch-on-error!` (`error_emit.cljc:171-230`); record shape per the ns docstring §Record shape.

**Composition with per-frame `:on-error` policy** (rf2-hqbeh): the error-emit substrate runs the per-frame `:on-error` policy AND the corpus-wide listener registry from one emission site. Use `:on-error` for **in-app recovery** (retry, mark, navigate); use `register-error-emit-listener!` for **off-box observability**. They are independent — register both when you need both.

### `:on-error` shape and wiring (the in-app recovery slot)

`:on-error` is a `reg-frame` metadata slot. The framework calls it with the structured error event AFTER emitting the trace and BEFORE applying the category's default recovery. It returns a closed-shape map telling the runtime how to proceed; `nil` defers to the category default.

```clojure
(rf/reg-frame :rf/default
  {:doc "App-shell frame with monitoring + recovery."
   :on-error
   (fn handle-error [error-event]
     ;; error-event is an :rf/trace-event with :op-type :error.
     (case (:operation error-event)
       :rf.error/handler-exception
       {:recovery    :replaced-with-default
        :replacement {:db (get-in error-event [:tags :db-before])}
        :notes       "handler threw — rolled back to db-before"}

       :rf.error/schema-validation-failure
       {:recovery    :replaced-with-default
        :replacement (get-in error-event [:tags :default-value])}

       ;; default: defer to the category's documented recovery
       nil))})
```

**Return-map contract** (closed shape, per [Spec 009 §Error-handler policy](../../../../spec/009-Instrumentation.md#error-handler-policy-on-error-per-frame)):

```clojure
{:recovery    <keyword>   ;; REQUIRED — one of :no-recovery, :replaced-with-default,
                          ;; :skipped, :warned-and-replaced, :logged-and-skipped, :ignored
 :replacement <value>     ;; OPTIONAL — only honoured when :recovery is :replaced-with-default;
                          ;; shape is category-specific (effect-map for :handler-exception, etc.)
 :notes       <string>}   ;; OPTIONAL — free-form; surfaced under :tags :notes on the augmented trace
```

**Production survival (rf2-hqbeh).** Unlike the rest of the trace surface, `:on-error` is NOT gated by `re-frame.interop/debug-enabled?` — it rides the same always-on error-emit substrate as `register-error-emit-listener!`. Registered policy fns fire on production handler exceptions; the substrate covers `:rf.error/handler-exception` today (the primary production-monitoring case). Each frame has at most one `:on-error` handler; re-registering the frame replaces the policy.

**Composition rubric** — pick one, both, or neither:

| Need | Surface | Why |
|---|---|---|
| In-app recovery (rollback, substitute, halt) | `:on-error` | Runs synchronously in the dispatch cascade; its return drives the runtime's next step. |
| Off-box shipping (Sentry / Datadog) | `register-error-emit-listener!` | Cascade-independent; one bad listener cannot affect the policy fn (or vice versa). |
| Both | Register both | The substrate fans out from one emission site; independent failure domains. The `:on-error` fn MAY return `nil` to forward-and-defer (per the [Spec 009 §Composition with libraries](../../../../spec/009-Instrumentation.md#error-handler-policy-on-error-per-frame) idiom) — letting the listener ship to the monitor while the runtime applies its default recovery. |

A policy fn that throws does NOT recursively invoke itself — the runtime emits `:rf.error/on-error-policy-exception` and falls back to the category default. Listener exceptions are caught the same way (sibling listeners still run).

## Triple-gate registration pattern

The substrate is always-on; **registration sites** should belt-and-braces gate on explicit config + `goog.DEBUG=false` + a credential probe, so an accidental dev-bundle deploy with prod config doesn't quietly ship records to your back-end.

```clojure
(when (and (= "production" (:env config))
           (not ^boolean re-frame.interop/debug-enabled?)
           (:api-key config))
  (rf/register-event-emit-listener!
    :datadog/events
    (fn [event-record]
      (datadog/track-event! event-record)))
  (rf/register-error-emit-listener!
    :sentry/errors
    (fn [error-record]
      (sentry/capture-exception (:exception error-record)
                                {:tags {:event-id (:event-id error-record)
                                        :frame    (:frame error-record)}}))))
```

Three independent conditions: **config env tag** (the app knows it's production), **`goog.DEBUG=false`** (the bundle is the production bundle), **api-key present** (credentials wired). Skip any leg and you get the dev path. Pattern documented in `re-frame.event-emit` ns docstring §goog.DEBUG framing.

## Why production has no trace bus

`re-frame.trace/emit!` and the `register-trace-cb!` plumbing are gated by `re-frame.interop/debug-enabled?`. Under `:advanced` + `goog.DEBUG=false`, the Closure compiler DCEs the entire trace surface — registrations, the ring buffer, the per-event allocation, every `tag/value` map. The bundle savings (~12-15 KB gzipped) and per-event allocation savings are part of re-frame2's "production debugging is opt-out, not opt-in" stance.

The two always-on listener APIs carve a minimal substrate that **survives** that elision: a tiny record shape, a `defonce` registry that hot reload won't blow away, fan-out gated on registry size (empty-map check short-circuits). Re-enable the full trace bus in production by flipping `:closure-defines {goog.DEBUG true}` if and only if the bundle cost is acceptable.

Full rationale: [`docs/guide/22-trace-to-datadog.md`](../../../../docs/guide/22-trace-to-datadog.md) and [`spec/009-Instrumentation.md §What IS available in production`](../../../../spec/009-Instrumentation.md) (line 489).

## Generic shipper recipe (Datadog / Sentry / Honeycomb)

The record shapes are tight enough to ship verbatim — every observability vendor's wire format is a strict subset of "event-id + timestamp + tags + payload". The pattern:

```clojure
(rf/register-event-emit-listener!
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

Worked vendor recipes (Datadog tags, Sentry breadcrumbs, Honeycomb spans): [`docs/guide/22-trace-to-datadog.md`](../../../../docs/guide/22-trace-to-datadog.md).

## Common gotchas

- **Listeners block the drain step.** Bodies run synchronously after each event settles. Ship work to a background channel (`requestIdleCallback`, queueing fetch, `setTimeout 0`) if it can't fit inside the per-event wall-clock budget.
- **Don't re-run elision unless widening.** The record already has off-box defaults applied. A listener that re-walks with defaults is a no-op; one that flips `:include-large?` / `:include-sensitive?` to `true` exposes data you'd otherwise hide.
- **`:sensitive?` on the handler drops the listener record entirely — there's nothing to ship.** If you need an audit trail of sensitive events (without their payloads), that's a separate per-event `:sensitive?`-aware path; don't try to ship redacted records through the same channel.
- **Listener exceptions are swallowed.** The cascade catches; sibling listeners still run. You will NOT see a thrown listener error in the console; log inside the listener body if you want visibility.
- **Don't use `register-trace-cb!` for production observability.** It dies under `:advanced` + `goog.DEBUG=false`. The two `*-emit-listener!` surfaces are the prod-survivable channel.

## Cross-references

- Guide chapter: [`docs/guide/22-trace-to-datadog.md`](../../../../docs/guide/22-trace-to-datadog.md) — narrative walkthrough with vendor-specific recipes.
- Spec normative: [`spec/009-Instrumentation.md §What IS available in production`](../../../../spec/009-Instrumentation.md) (line 489) — substrate contracts.
- Privacy composition: [`privacy-and-elision.md`](privacy-and-elision.md) — `:sensitive?` short-circuits BEFORE elision; payload already walked at listener entry.
- Per-frame `:on-error` policy: [`references/fundamentals/frames.md`](../fundamentals/frames.md) §`:on-error` — in-app recovery, sibling to the corpus-wide error listener.

---

*Derived from `re-frame.event-emit` (rf2-rirbq) and `re-frame.error-emit` (rf2-bacs4) @ main. Verified surfaces: `register-event-emit-listener!` (event_emit.cljc:139), `register-error-emit-listener!` (error_emit.cljc:139); record shapes per each ns docstring §Record shape.*
