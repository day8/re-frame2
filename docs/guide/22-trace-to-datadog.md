# 22 — Production observability (Datadog, Sentry, Honeycomb)

> **If you're skipping this chapter, the upshot:** production observability uses two **always-on listener APIs** that survive `goog.DEBUG=false` — `register-event-emit-listener!` (one record per dispatched event) and `register-error-emit-listener!` (one record per runtime error). Each record runs through `rf/elide-wire-value` for privacy + size elision, then ships to Datadog via [`:rf.http/managed`](10-doing-http-requests.md) for free retry + abort-on-destroy. Datadog is **production-only**; in dev you have Causa, pair2, and story. The recipe is Datadog-shaped; the shape is generic — Honeycomb, Sentry, Mezmo, Mixpanel, your in-house pipeline all attach the same way.

Real apps need observability. You want to know, in production, which events are firing on which page, how often, against what cohort. You want a dashboard that lights up when a release stops dispatching `:checkout/submit` at the rate it used to. You want SLO alerts you can trust. You want to see errors with their event-context, so when something fails at 3am you know what the user was doing.

re-frame2 ships two **always-on listener substrates** for exactly that, parallel to the dev-only trace bus:

| Substrate | What fires | Public API |
|---|---|---|
| `event_emit` | once per **dispatched event** (success or error outcome) | `register-event-emit-listener!` |
| `error_emit` | once per **runtime error** (handler exception, machine fault, etc.) | `register-error-emit-listener!` |

Both substrates are **production-survivable** — they survive `:advanced` compilation with `goog.DEBUG=false`. Both apply `rf/elide-wire-value` to every record before fan-out. Both have zero cost when no listener is registered.

> **What about the trace bus?** The trace bus you met in [ch.15](15-devtools-and-pair-tools.md) is dev-only. It dead-code-eliminates in production. It exists so Causa, pair2-mcp, and story can show you everything that happens — every sub recompute, every fx, every interceptor — at the cost of bundle size and hot-path overhead you don't want in shipped code. Production has no trace bus. Production has these two listener APIs instead — narrow shapes, always-on, observability-only.

You'll know:

- The two record shapes you'll receive — tight, purpose-shaped, no trace plumbing.
- How `rf/elide-wire-value` honours `:sensitive?` and `:large?` flags before your listener gets the record.
- How to translate each record into Datadog's Event API shape.
- How to send the payload via `:rf.http/managed` so you get retry, abort-on-actor-destroy, and middleware redaction for free.
- The operational caveats — batching, registration gating, the failure-feedback loop.

## Why production has no trace bus

The trace bus is the right surface for dev tooling. It emits *everything*: every sub computation, every fx call, every machine transition, every interceptor step, every registration. Causa scrubs through it. pair2-mcp queries it. story plays it back. The fidelity is the point.

That fidelity costs bundle bytes (the trace machinery itself) and hot-path cycles (every sub recompute emits a structured event). Production cannot afford either. So the trace bus dead-code-eliminates: when `goog.DEBUG=false`, the runtime calls that emit trace events evaporate at compile time, leaving zero overhead.

But you still need observability in production. Not "everything that happened" — just **which events fired** and **which errors fired**, with their elapsed time, outcome, and surrounding event context. That's two tight surfaces, each with one job, each surviving `goog.DEBUG=false` because the records are small and the listeners are opt-in.

`event_emit` and `error_emit` are those surfaces. They're not a fallback from trace — they're a deliberate, separate production primitive.

## The event-emit record

Every time an event finishes (successfully or with an error), the runtime invokes every registered event-emit listener with a single record:

```clojure
{:event       <vector>     ;; e.g. [:checkout/submit {:cart-id 42}]
 :event-id    <kw>         ;; the first element of :event
 :frame       <kw>         ;; e.g. :rf/default
 :time        <inst>       ;; (js/Date.) at dispatch start
 :outcome     :ok | :error ;; whether the handler threw
 :elapsed-ms  <int>}        ;; wall-clock duration in handler
```

No `:op-type`, no `:operation`, no `:tags`, no `:source`. Those are trace-bus shape. Production observability doesn't need them.

The record passes through `rf/elide-wire-value` **before** your listener sees it — so any `:sensitive?` handler drops the entire record, and any `:large?` payload (e.g., a 5MB base64 PDF in `:event`) is replaced with a `:rf.size/large-elided` marker. See [ch.23 — Privacy + Size Elision](23-privacy-and-elision.md) for the full elision contract.

## The error-emit record

Every time the runtime catches an error, the runtime invokes every registered error-emit listener with:

```clojure
{:error      <kw>          ;; e.g. :rf.error/handler-exception
 :event      <vector>      ;; the event that was being processed
 :event-id   <kw>
 :frame      <kw>
 :time       <inst>
 :exception  <ex-data>     ;; structured exception data — message, stack, ex-data slot
 :elapsed-ms <int>}
```

Same elision pass; same `:sensitive?` drop semantics; same `:large?` substitution. The `:exception` slot is `ex-data` from the thrown exception — no raw stack traces with PII embedded.

`:on-error` per-frame policy ([ch.14](14-errors.md)) is the **in-app recovery** path — your frame says "if this fails, do X". `register-error-emit-listener!` is the **observability** path — every error gets logged to Datadog regardless of per-frame recovery. The two are orthogonal; both fire on the same exception.

## Registration

Datadog is production-only. Dev gets Causa + pair2 + story. Your registration site should reflect that:

```clojure
(when (and (= "production" (:env config))
           (not ^boolean re-frame.interop/debug-enabled?)
           (:api-key config))
  (rf/register-event-emit-listener!
    :my-app/datadog-events
    (fn [record] ...))
  (rf/register-error-emit-listener!
    :my-app/datadog-errors
    (fn [record] ...)))
```

Three gates, AND'd together:

1. **Your config** says this is a production deploy.
2. **`goog.DEBUG=false`** — belt-and-braces against a dev bundle being accidentally deployed with prod config baked in. If `goog.DEBUG=true` somehow snuck through, the listener silently refuses to register, and you notice on the Datadog dashboard (no events appearing) rather than at 3am when prod is leaking dev-only verbose data.
3. **API key present** — no point registering if the shipper can't actually send.

All three predicates are cheap. The conservative default protects against accidental dev-laptop traffic to your production Datadog org.

## Mapping each record to Datadog's wire shape

Datadog's [Event Submission API](https://docs.datadoghq.com/api/latest/events/) takes a POST with a JSON body and a `DD-API-KEY` header:

```
POST https://api.datadoghq.com/api/v1/events
Headers:
  DD-API-KEY: <your-key>
  Content-Type: application/json
Body:
  {"title":            "<short string, indexed>",
   "text":             "<longer body, supports markdown>",
   "alert_type":       "info" | "warning" | "error" | "success",
   "tags":             ["env:prod", "service:checkout", ...],
   "date_happened":    <unix-seconds>,
   "source_type_name": "re-frame2"}
```

Each of the two record shapes maps cleanly:

```clojure
(defn event-record->datadog [{:keys [event-id event frame time outcome elapsed-ms]}]
  {:title            (str event-id)
   :text             (pr-str {:event event :elapsed-ms elapsed-ms})
   :alert_type       (case outcome :ok "info" :error "error")
   :tags             [(str "event_id:" event-id)
                      (str "frame:" frame)
                      (str "outcome:" (name outcome))
                      (str "env:" (:env config))]
   :date_happened    (quot (.getTime time) 1000)
   :source_type_name "re-frame2"})

(defn error-record->datadog [{:keys [error event-id event frame time exception elapsed-ms]}]
  {:title            (str error)
   :text             (pr-str {:event event :exception exception :elapsed-ms elapsed-ms})
   :alert_type       "error"
   :tags             [(str "error:" error)
                      (str "event_id:" event-id)
                      (str "frame:" frame)
                      (str "env:" (:env config))]
   :date_happened    (quot (.getTime time) 1000)
   :source_type_name "re-frame2"})
```

Notice: every Datadog tag is a string. Tag dimensions are flat — colons are the conventional separator, not nested addressing. Keep cardinality low on tag values you index on (don't ship `event_id:` if you have a million distinct event ids; do ship `frame:` and `outcome:` because they're small enumerations).

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

(rf/reg-event-fx :my-app.observability/ship
  {:doc "POST one Datadog event. Retry on transport / 5xx."}
  (fn [_ [_ {:keys [datadog-event] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      (case (:kind reply)
        :success {}
        :failure {:fx [[:rf.fx/emit-trace!
                       [:warning :my-app.observability/datadog-send-failed
                        {:failure (:failure reply)}]]]})
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

(defn install-datadog-shippers!
  "Attach both Datadog forwarders. Idempotent. Production-only by triple-gate."
  []
  (when (and (= "production" (:env dd-config))
             (not ^boolean re-frame.interop/debug-enabled?)
             (:api-key dd-config))

    (rf/register-event-emit-listener!
      :my-app/datadog-events
      (fn [record]
        (rf/dispatch [:my-app.observability/ship
                      {:datadog-event (event-record->datadog record)}])))

    (rf/register-error-emit-listener!
      :my-app/datadog-errors
      (fn [record]
        (rf/dispatch [:my-app.observability/ship
                      {:datadog-event (error-record->datadog record)}])))))
```

A few things worth pointing at:

1. **No filter inside the listener.** `event_emit` only fires on dispatched events; `error_emit` only fires on runtime errors. There's no sub recompute or fx-execute event to filter out — that's the whole point of these substrates being narrower than the trace bus.
2. **No `rf/elide-wire-value` call in user code.** The framework runs every record through elision *before* invoking your listener. By the time you see the record, sensitive values are dropped and large values are marker-substituted. (Compare to ch.15's trace-bus consumers, where elision is the consumer's job because trace events are emitted before any elision pass.)
3. **`:request-id` is unique per send.** A UUID per request lets the managed-HTTP in-flight registry track the POSTs independently. If you reuse an id, the second send aborts the first ([ch.10](10-doing-http-requests.md) covers the rules).
4. **The triple-gate is conservative on purpose.** If you forget any one of the three predicates, the integration silently no-ops. Configure thoughtfully; deploy carefully; the Datadog dashboard will tell you if events stop arriving.

## Batching

Datadog accepts one event per POST, and that gets expensive fast on a busy app. The right shape is to **batch in your listener** — collect records into a small buffer, flush periodically or when the buffer fills.

A sketch:

```clojure
(defonce ^:private buffer (atom []))

(defn- enqueue! [dd-event]
  (let [batch (swap! buffer conj dd-event)]
    (when (>= (count batch) 20)
      (let [to-send @buffer]
        (reset! buffer [])
        (rf/dispatch [:my-app.observability/ship-batch {:events to-send}])))))

;; Plus a periodic flush via :rf.fx/dispatch-later from an :app/init handler:
;; (every 30s) → :my-app.observability/flush-buffer.
```

`ship-batch` POSTs the array as a single Datadog v2 batch submission — same retry policy, same elision (already applied per-record before they reached the buffer), lower request volume. Tune the buffer size and flush interval against your dispatch frequency and Datadog's [API rate limits](https://docs.datadoghq.com/api/latest/rate-limits/).

## The shape is generic

The recipe is Datadog-shaped, but the structure isn't Datadog-specific. **The four moves — register both listeners, map each record to the wire shape, POST via `:rf.http/managed`, repeat** — work against any observability platform. Swap the URL, the auth header, and the event mapper and you have:

- **Honeycomb** — POST to `https://api.honeycomb.io/1/events/<dataset>`, swap `DD-API-KEY` for `X-Honeycomb-Team`, drop `:date_happened` (Honeycomb takes ISO-8601 in `time`).
- **Sentry** — POST to the Sentry [Store API](https://develop.sentry.dev/sdk/store/), wrap events in the Sentry envelope shape, switch the `:alert_type` mapping to Sentry's `level` enum.
- **Mezmo / Logz / Splunk** — same listeners, different endpoint, log-shaped body.
- **Your in-house pipeline** — POST to your own ingestion service. The listeners don't care.

The point: the framework owns the production-observability shape and tools own the rendering. Datadog is one renderer. So is your in-house pipeline. So is Sentry. Same two substrates, different consumers.

## Next

- [10 — Doing HTTP requests](10-doing-http-requests.md) — the managed-fx that does the actual POST, with retry and abort-on-destroy.
- [14 — Errors and how to handle them](14-errors.md) — the `:on-error` per-frame recovery policy that pairs with `register-error-emit-listener!`.
- [15 — Tooling](15-devtools-and-pair-tools.md) — the dev-only trace bus and the tools (Causa, pair2, story) that consume it.
- [23 — Privacy + Size Elision](23-privacy-and-elision.md) — the `:sensitive?` + `:large?` machinery the framework applies to every record before your listener sees it.
