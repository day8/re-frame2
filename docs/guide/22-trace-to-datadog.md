# 22 — Trace forwarding to Datadog

> **If you're skipping this chapter, the upshot:** the trace bus you met in [ch.15](15-devtools-and-pair-tools.md) is your observability surface. Register a listener, filter to `:op-type :event`, run every captured event through `rf/elide-wire-value` to drop sensitive payloads and elide large ones, then POST to Datadog via [`:rf.http/managed`](10-doing-http-requests.md) (free retry, free abort-on-destroy, redaction-friendly). The recipe is Datadog-shaped; the shape is generic — Honeycomb, Sentry, Mezmo, Mixpanel, your in-house pipeline all attach the same way.

Real apps need observability. You want to know, in production, which events are firing on which page, how often, against what cohort. You want a dashboard that lights up when a release stops dispatching `:checkout/submit` at the rate it used to. You want SLO alerts you can trust.

[ch.15](15-devtools-and-pair-tools.md) made the architectural pitch: re-frame2 commits to **one observation surface**, and every tool — devtools, pair programmers, story playgrounds, an in-app debug panel — consumes it through the same listener API. This chapter is the same idea, pointed at a third-party observability platform: hook a listener up to the trace bus, transform each event into a Datadog Event Submission payload, ship it.

The chapter spends most of its length on the two things the bead listing of "register and POST" hides: **privacy** (your event vectors carry passwords, payment details, PII) and **size** (a single `app-db` slice can be 5MB of base64 PDF). Both are framework primitives. You consume them through one function — `rf/elide-wire-value` — at the wire boundary.

You'll know:

- How to register a trace listener that fires on every event, and filter it to the slice you want to ship.
- How to run each event through `rf/elide-wire-value` to honour the privacy and size elision flags.
- How to translate a trace event into Datadog's Event API shape.
- How to send the payload via `:rf.http/managed` so you get retry, abort-on-actor-destroy, and middleware redaction for free.
- The operational caveats — batching, env gating, the failure-feedback loop.

The Datadog listener you'll register below fires **once per managed-external-effect emit** — once per `:rf.http/managed` request, once per `:rf.ws/*` connection transition, once per state-machine `:invoke` lifecycle event, once per SSR per-request fx. That uniform observation surface is property four of the eight-property managed-effect contract in [`spec/Managed-Effects.md`](../../spec/Managed-Effects.md); the consequence at this end of the wire is that one listener, one filter, one elision pass, one POST recipe covers every framework-owned async surface the app issues.

## The substrate: one trace bus, every tool attaches

A re-frame2 dev build emits a structured trace event for every meaningful runtime moment — every dispatch, every handler invocation, every sub recomputation, every fx, every error. Each event is a map with stable, named keys: `:id`, `:op-type`, `:operation`, `:tags`, `:source`, `:time`, sometimes `:sensitive?`, sometimes `:rf.trace/trigger-handler`. The shape is the contract.

```clojure
;; The canonical attach:
(rf/register-trace-cb!
  :my-app/datadog-shipper
  (fn [trace-event]
    ;; one call per emitted trace event, synchronously, on the runtime's emit stack
    (do-something-with! trace-event)))
```

Two rules from the listener contract worth pinning before we go further:

- **The callback runs synchronously.** It sits inside the framework's emit loop — return fast or you'll slow every event in the cascade. Anything I/O-bound (HTTP, file write, channel publish) belongs on the other side of an async hop. Dispatching a re-frame2 event from the callback is the canonical async hop: the dispatch enters the queue, the callback returns, the runtime continues, and the dispatched event runs the I/O on a fresh cascade.
- **Exceptions are caught.** A throw inside your listener is logged and discarded — it does not propagate to the framework or break other listeners. That's a kindness (one broken integration can't break the app) but it also means **silent failures are easy**. Wire your Datadog send to *trace its own failure*, so you can see the integration's health on the same dashboard.

The trace surface is **dev-only by default**. In production builds (`:advanced` + `goog.DEBUG=false`), the trace bus is dead-code-eliminated entirely. If you want production telemetry through this channel, see [§Per-environment gating](#per-environment-gating) at the bottom of the chapter; the short version is "guard your registration on `re-frame.interop/debug-enabled?` and accept that the channel only fires when that flag is true."

## Filtering — events only

The trace bus emits *everything*. Subs running, fxs firing, machine transitions, error events, registration metadata changes, frame creates. For a Datadog event-volume dashboard you almost certainly want the slice that maps cleanly to "the user did a thing" — the dispatched events.

The universal discriminator is `:op-type`, and the value you want is `:event`:

```clojure
(when (= :event (:op-type trace-event))
  (forward-to-datadog! trace-event))
```

Two notes on filter design:

- **Filter inside the callback, not at registration.** The framework's listener registration is shape-agnostic — every listener sees every event; the filter is yours to apply. Cheap predicate, fast bail, no allocation in the bail path. The same shape every tool in [ch.15](15-devtools-and-pair-tools.md) uses.
- **`:op-type :event` includes the inner `:event/dispatched`, `:event/db-changed`, and `:event` lifecycle phase events.** You usually want `:event/dispatched` only (one per user action) — adding `(= :event/dispatched (:operation trace-event))` narrows further. The `:operation` field is the *category*; the `:op-type` is the *severity*.

If you want errors too — and most observability dashboards do — broaden:

```clojure
(when (#{:event :error :warning} (:op-type trace-event))
  ...)
```

That's the shape: events for behaviour, errors for alerting. Run them through the same Datadog pipeline; let Datadog's `alert_type` field discriminate at the dashboard end.

## The critical step: `rf/elide-wire-value`

Trace events carry dispatched event vectors and (under `:event/db-changed`) `app-db` snapshots. Both can contain user input that has no business leaving the developer's machine: passwords, auth tokens, payment details, the PII captured in a form. They can also contain values that are *enormous* — a base64-encoded PDF preview under `[:user :uploaded-pdf]` is a 5MB string that will blow every wire cap downstream.

The framework's answer is a **unified wire-boundary walker** — one function that consults two orthogonal predicates (`:sensitive?` for privacy; `:large?` for size) and substitutes the appropriate placeholder. Same function, two flags, one helper:

```clojure
(rf/elide-wire-value trace-event {})
;; → the same trace event, but
;;     - sensitive values dropped (replaced with :rf/redacted, or the whole event filtered)
;;     - large values replaced with a :rf.size/large-elided marker
```

The opts map carries the elision policy:

```clojure
{:rf.size/include-large?     false   ;; default false — large values elide to markers
 :rf.size/include-sensitive? false   ;; default false — sensitive events drop entirely
 :rf.size/include-digests?   false   ;; default false — no sha256 in the marker
 :rf.size/threshold-bytes    16384   ;; auto-flag any value over this pr-str byte count
 :frame                      :rf/default}
```

**Off-box shippers (you, today) MUST default both `include-*` flags to `false`.** The Sentry / Honeybadger / pair2 / Causa-MCP forwarders that ship with re-frame2 all default to maximum elision. Off-box means "the data is leaving your trust boundary" — and Datadog's trust boundary is not yours.

What the walker does on a real trace event:

```clojure
;; Input — the runtime emitted this when :auth/sign-in fired:
{:operation :event/dispatched
 :op-type   :event
 :tags      {:event-id :auth/sign-in
             :event    [:auth/sign-in {:username "ada" :password "shhh"}]
             :frame    :rf/default}
 :sensitive? true}                   ;; the handler was registered with :sensitive? true

;; Output of (rf/elide-wire-value ev {}):
;; nil      — the whole event is dropped; sensitive drops before anything else.

;; The same handler with :sensitive? UNSET but with [:auth :otp-image] declared :large?:
{:operation :event/db-changed
 :tags      {:app-db-after
             {:auth {:otp-image
                     {:rf.size/large-elided
                      {:path   [:auth :otp-image]
                       :bytes  524288
                       :type   :string
                       :reason :declared
                       :hint   "OTP QR code"
                       :handle [:rf.elision/at [:auth :otp-image]]}}}}}}
;; The marker rides where the value was; the rest of the event survives.
```

The composition rule is **sensitive drop wins**. If both predicates match, the value is dropped, not marker-substituted — because the marker itself would leak `:path` and `:bytes` (structural information about the redacted slot). One walker, two flags, deterministic precedence.

A practical note on the source of the flags: `:sensitive?` is declared as a metadata key on the `reg-event-*` registration (chapter 14 introduces it; the [ch.14 privacy section](14-errors.md) is the deep dive). `:large?` is declared three ways — on a Malli schema slot, via the framework fx `:rf.size/declare-large` from any event handler, or auto-detected by a runtime byte-count heuristic. You consume the result. You don't have to teach Datadog what's sensitive or large; the framework already knows.

## Mapping a trace event to Datadog's wire shape

Datadog's [Event Submission API](https://docs.datadoghq.com/api/latest/events/) takes a POST with a JSON body and a `DD-API-KEY` header:

```
POST https://api.datadoghq.com/api/v1/events
Headers:
  DD-API-KEY: <your-key>
  Content-Type: application/json
Body:
  {"title":       "<short string, indexed>",
   "text":        "<longer body, supports markdown>",
   "alert_type":  "info" | "warning" | "error" | "success",
   "tags":        ["env:prod", "service:checkout", ...],
   "date_happened": <unix-seconds>,
   "source_type_name": "re-frame2"}
```

A trace event maps cleanly onto this shape:

```clojure
(defn trace-event->datadog [{:keys [operation op-type tags time] :as ev}]
  {:title            (str operation)                 ;; e.g. ":event/dispatched"
   :text             (pr-str (select-keys tags [:event-id :event :frame]))
   :alert_type       (case op-type
                       :error   "error"
                       :warning "warning"
                       :event   "info"
                       "info")
   :tags             [(str "operation:" operation)
                      (str "op_type:" op-type)
                      (str "frame:" (:frame tags))
                      (str "event_id:" (:event-id tags))
                      (str "env:" js/process.env.NODE_ENV)]
   :date_happened    (quot time 1000)                ;; ms → s
   :source_type_name "re-frame2"})
```

Notice: every tag is a string. Datadog's tag dimensions are flat — colons are conventional separator, not nested addressing. Keep cardinality low on tag values you index on (don't ship `event_id:` if you have a million distinct event ids; do ship `frame:` and `op_type:` because they're small enumerations).

## Sending via `:rf.http/managed`

The managed HTTP fx ([ch.10](10-doing-http-requests.md)) is the right transport. You get:

- **Retry with backoff** on `:rf.http/transport` and `:rf.http/http-5xx` failures (Datadog's API is generally reliable, but a momentary 502 from a Cloudflare edge is exactly the failure mode `:retry` exists for).
- **Abort on actor destroy** — if the frame that registered the listener gets destroyed, the in-flight requests it dispatched cancel cleanly. No leaked POSTs at SPA route changes.
- **Middleware redaction** — if you've installed a request-side interceptor that strips secrets from outgoing payloads (e.g. an internal-only auth-token filter), Datadog requests run through it too.

The full recipe:

```clojure
(ns my-app.observability.datadog
  (:require [re-frame.core :as rf]))

(def ^:private dd-config
  ;; In a real app this comes from a build-time env injection, not source.
  {:api-key (some-> js/process.env (.-DATADOG_API_KEY))
   :env     "production"
   :service "my-app"})

(rf/reg-event-fx :my-app.observability/ship-event
  {:doc "POST one trace event to Datadog. Retry on transport / 5xx."}
  (fn [{:keys [db]} [_ {:keys [datadog-event] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply path — log failures back through the trace stream so the
      ;; integration's health is visible on the same dashboard.
      (case (:kind reply)
        :success {}                              ;; happy path; nothing to update
        :failure {:fx [[:rf.fx/emit-trace!
                       [:warning :my-app.observability/datadog-send-failed
                        {:failure (:failure reply)}]]]})

      ;; Initial dispatch — issue the POST.
      {:fx [[:rf.http/managed
             {:request {:method  :post
                        :url     "https://api.datadoghq.com/api/v1/events"
                        :headers {"DD-API-KEY" (:api-key dd-config)}
                        :body    datadog-event
                        :request-content-type :json}
              :retry   {:on           #{:rf.http/transport :rf.http/http-5xx}
                        :max-attempts 3
                        :backoff      {:base-ms 500 :factor 2 :max-ms 8000 :jitter true}}
              :timeout-ms 10000
              :request-id [:my-app.observability/dd (random-uuid)]}]]})))

(defn install-datadog-shipper!
  "Attach the Datadog forwarder. Idempotent. Dev-only — the trace surface
   elides in production builds with goog.DEBUG=false."
  []
  (when ^boolean re-frame.interop/debug-enabled?
    (rf/register-trace-cb!
      :my-app/datadog-shipper
      (fn [trace-event]
        (when (#{:event :error :warning} (:op-type trace-event))
          (when-let [elided (rf/elide-wire-value trace-event {})]
            ;; Self-trace-loop guard — never ship our own failure events.
            (when-not (= :my-app.observability/datadog-send-failed
                         (:operation elided))
              (rf/dispatch
                [:my-app.observability/ship-event
                 {:datadog-event (trace-event->datadog elided)}]))))))))
```

Six things worth pointing at in that snippet:

1. **`when-let` on the elision return.** Sensitive events return `nil` — the binding fails and the ship is skipped. The framework already made the privacy call; we just honour it.
2. **The self-trace-loop guard.** Logging the Datadog send failure as `:my-app.observability/datadog-send-failed` is great for visibility — until the *very next* trace emit picks it up and tries to ship it, fails, emits another failure, and you've built a heartbeat. The exclusion is one line; skip it and you'll see the heartbeat in production at 3am.
3. **`:request-id` is unique per send.** A UUID per request lets the managed-HTTP in-flight registry track the POSTs independently. If you reuse an id, the second send aborts the first ([ch.10](10-doing-http-requests.md) covers the rules).
4. **`re-frame.interop/debug-enabled?` gates the registration.** In a production `:advanced` build with `goog.DEBUG=false`, the entire `(when ^boolean ...)` body is dead-code-eliminated; no registration, no listener function, no `rf/elide-wire-value` call survives. If you want Datadog telemetry from a production bundle, see [§Per-environment gating](#per-environment-gating).
5. **The reply path traces its own failure.** `rf.fx/emit-trace!` lets you fire a structured trace event from `:fx` — the same channel everything else rides. Now your Datadog dashboard sees `:my-app.observability/datadog-send-failed` events at the same place it sees the events that didn't ship, with the same shape. The shipper's health is monitored by the shipper's monitor — observability is self-applying.
6. **No `console.log`.** If you're tempted to `js/console.log` a Datadog failure: trace it instead. The trace stream is your one channel — sending one piece of failure data through a different channel splits your monitoring story.

## Batching

Datadog accepts one event per POST, and that gets expensive fast on a busy app. The right shape is to **batch in your listener** — collect events into a small buffer, flush periodically or when the buffer fills.

A sketch:

```clojure
(defonce ^:private buffer (atom []))

(defn- enqueue! [dd-event]
  (let [batch (swap! buffer (fn [b] (conj b dd-event)))]
    (when (>= (count batch) 20)                    ;; flush at 20 events
      (let [to-send @buffer]
        (reset! buffer [])
        (rf/dispatch [:my-app.observability/ship-batch {:events to-send}])))))

;; Plus a periodic flush via :rf.fx/dispatch-later from an :app/init handler:
;; (every 30s) → :my-app.observability/flush-buffer.
```

`ship-batch` POSTs the array as a single Datadog v2 batch submission — same retry policy, same elision, lower request volume. Tune the buffer size and flush interval against your dispatch frequency and Datadog's [API rate limits](https://docs.datadoghq.com/api/latest/rate-limits/).

## Per-environment gating

The trace surface is dev-only. That's a deliberate framework choice — it costs zero bytes in shipped binaries and avoids the entire "production-only side-channel that someone forgot to test" failure mode. But it also means the recipe above is, by default, a *dev-environment* observability story.

Two workable patterns for production telemetry:

- **Ship with `goog.DEBUG=true` in production.** Flip the closure-define in your release build's `shadow-cljs.edn` and the trace surface stays live. The bundle grows (trace machinery is back in); listener registrations run; the dashboard works. The tradeoff is the bundle size and the hot-path cost of trace emission — measurable on heavy apps; usually fine. This is the approach Datadog-shaped consumers reach for first.
- **Use the `rf:` User Timing channel instead.** [ch.16](16-performance.md) covers the parallel observability channel that *is* prod-safe by design — User Timing entries land in browser performance APIs and any APM consumer with a `PerformanceObserver` reads them with no framework call needed. The shapes are coarser (timings, not full trace events) but the budget is permanent.

In a config-driven build, gate the registration on a config knob, not just on `debug-enabled?`:

```clojure
(when (and ^boolean re-frame.interop/debug-enabled?
           (= "production" (:env dd-config))
           (:api-key dd-config))
  (rf/register-trace-cb! :my-app/datadog-shipper ...))
```

Three predicates. Three ways the integration silently no-ops if something's missing. The conservative default protects against accidental dev-laptop traffic to your production Datadog org.

## The shape is generic

The recipe is Datadog-shaped, but the structure isn't Datadog-specific. **The four moves — register a listener, filter the trace stream, run through `rf/elide-wire-value`, POST via `:rf.http/managed`** — work against any observability platform. Swap the URL, the auth header, and the event mapper and you have:

- **Honeycomb** — POST to `https://api.honeycomb.io/1/events/<dataset>`, swap `DD-API-KEY` for `X-Honeycomb-Team`, drop the `:date_happened` (Honeycomb takes ISO-8601 in `time`).
- **Sentry** — POST to the Sentry [Store API](https://develop.sentry.dev/sdk/store/), wrap events in the Sentry envelope shape, switch the `:alert_type` mapping to Sentry's `level` enum. Chapter 14 has a ready-made sketch.
- **Mezmo / Logz / Splunk** — same listener, different endpoint, log-shaped body.
- **Your in-house pipeline** — POST to your own ingestion service. The listener doesn't care.

The point of the trace bus is that the framework owns the shape and tools own the rendering. Datadog is one renderer. So is a dev panel. So is `re-frame-pair2`. So is the off-box error-monitor forwarder. Same surface; different consumers. The pitch from [ch.15](15-devtools-and-pair-tools.md) plays through here in full.

## Next

- [10 — Doing HTTP requests](10-doing-http-requests.md) — the managed-fx that does the actual POST, with retry and abort-on-destroy.
- [14 — Errors and how to handle them](14-errors.md) — the `:sensitive?` registration flag and the `with-redacted` interceptor in full; the Sentry-bridge pattern this chapter is a sibling of.
- [15 — Tooling](15-devtools-and-pair-tools.md) — the architectural pitch this chapter rides on: one trace bus, every tool consumes it.
- [16 — Performance](16-performance.md) — the parallel `rf:` User Timing channel, prod-safe by design.
