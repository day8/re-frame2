# Report errors in production

Send runtime failures to an error monitor through an observability sink. The error
stream stays on in production even though detailed traces and epoch history are
[elided](../glossary.md#elide). Each record names the error category, the event and
frame when known, and the host exception when there is one.

This recipe registers a Sentry bridge, declares its delivery policy, then adds
handled-event metrics. The same sink shape works with another monitor.

The stream covers the event pipeline, subscriptions and flows. Client rendering
errors need the view layer's error boundary or your monitor's browser integration;
Fresco can dispatch from a boundary's `:on-error`
([Errors](../fresco/17-errors.md)).

## 1. Declare the policy, register the sink

A sink has two parts: a function registered under an id, and a policy naming that
id as a destination. Install both before creating the app's frame, so boot errors
are observed too:

```clojure
(ns app.monitoring
  (:require ["@sentry/browser" :as Sentry]
            [re-frame.core :as rf]
            [app.config :as config]))

(defn report-error! [record]
  (let [context (clj->js {:tags {:category (str (:error record))
                                :event-id (str (:event-id record))
                                :frame (str (:frame record))}
                         :extra {:elapsed-ms (:elapsed-ms record)}})]
    (if-let [exception (:exception record)]
      (Sentry/captureException exception context)
      (Sentry/captureMessage (str (:error record)) context))))

(defn init! []
  (rf/configure!
    {:observability
     {:errors [{:sink :app.sinks/sentry
                :rf.egress/profile :rf.egress/off-box-observability}]}})
  (when (and config/production? config/sentry-dsn)
    (Sentry/init #js {:dsn config/sentry-dsn})
    (rf/register-observability-sink! :app.sinks/sentry report-error!)))
```

`config/production?` and `config/sentry-dsn` are application settings. Call this
`init!` during boot before mounting `frame-root` or calling `make-frame`
([Boot and mount an app](boot-and-mount-an-app.md)). The sink receives a projected
record, so declared sensitive paths are already redacted. The exception itself is
passed through under this profile; [step 4](#4-choose-the-profile-and-know-what-survives-elision)
explains that boundary.

Both branches matter: a thrown handler supplies an exception; an unknown event id
produces a structured refusal without one. This bridge reports either.

### Declare it once for the whole process

The `configure!` policy above is inherited by every frame. A frame needs its own
`:observability` only when it differs:

```clojure
;; This frame opts out of error forwarding but still inherits handled-event sinks.
[rf/frame-root {:id :preview :observability {:errors []}}
 [preview-view]]
```

Inheritance is per stream. A frame's `:errors` replaces the process error list;
when absent, the process list applies. A sink listed at both levels receives the
record once. Each frame's classification still governs its records.

The process default also receives [records no frame owns](#records-no-frame-owns).
`(rf/configure! {:observability nil})` clears it. A malformed policy throws
`:rf.error/bad-frame-classification` when configured.

## 2. Gate it so it can't fire in dev

The error stream works in development too. The `when` in `init!` registers the
remote sink only when your application selects a production deployment and supplies
a DSN. Keep the policy declared in development; with no matching registered sink,
the framework reports errors to the console.

Registering the same sink id again replaces its function, so hot reload does not
stack callbacks. A throwing sink cannot block the event or another sink. Remove it
with `(rf/unregister-observability-sink! :app.sinks/sentry)`, for example when a
feature flag turns forwarding off.

Use `register-observability-sink!` for production monitoring. A
`register-listener! :trace` callback stops receiving records in production because
that stream is compiled out.

## 3. Branch on the category, never the prose

Group and filter on `(:error record)`, such as `:rf.error/handler-exception`.
Human-readable messages may change. The bridge keeps the category, event id and
frame as tags even when there is no exception.

Invalid operations such as `:rf.error/no-such-handler`, `:rf.error/frame-destroyed`
and `:rf.error/no-frame-context` are reported in release builds too. The last has
no owning frame, so only the process default receives it. See
[Errors](../errors.md) for recovery behaviour and the
[API reference](../../api/README.md#errors) for per-operation contracts.

!!! note "Records that name the failing component"

    For `:rf.error/interceptor-exception` and `:rf.error/coeffect-exception`,
    `:event-id` names the dispatched event. `:failing-id` names the interceptor or
    supplier that broke; use it to group one component failing across many events.
    Source coordinates are available in development.

### Don't scrub the `:event` yourself

The default off-box profile omits the event vector. Use the retained event id for
attribution; do not re-read raw state or event arguments to fill the gap. When
forwarding a record yourself outside the sink mechanism, apply
[`project-egress`](../../api/re-frame.core.md#project-egress) first.

## 4. Choose the profile, and know what survives elision

The `:rf.egress/profile` on each `:observability` entry decides whether your sink sees the host exception:

- **`:rf.egress/off-box-observability`** is the default when you omit the key. It redacts sensitive paths and elides large ones, and passes the `:exception` through, since the stack is the point of hosted monitoring.
- **`:rf.egress/public-error`** drops the record's top-level `:exception`. Use it when the destination is less trusted than your APM.

An unknown profile in a sink policy throws `:rf.error/bad-frame-classification` when the policy is configured. An unknown profile passed directly to `project-egress` throws `:rf.error/unknown-egress-profile`.

Under the default profile, a secret in an exception's message or `ex-data` is not redacted, because the projector can't see inside a throwable. `:rf.egress/public-error` removes the record's own top-level `:exception` only.

What still runs in a production build (`:advanced`, `goog.DEBUG=false`), in short ([Observability](../observability.md) has the full account):

- **Removed:** trace emission, `register-listener! :trace` delivery, the per-frame trace rings, [epoch](../glossary.md#epoch) history and [time travel](../glossary.md#time-travel), dispatch-id correlation, source coordinates, [Xray](../glossary.md#xray), and the pair tooling.
- **Kept:** the error stream (frame sinks and the process default), the `:handled-events` stream ([step 6](#6-pair-errors-with-its-handled-events-sibling)), and the opt-in Performance API channel, which has its own compile-time flag.

The sink only observes. What happens after a failure is fixed per category by the framework ([Errors](../errors.md)): a destroyed frame's operation is ignored and reported, a failed subscription returns `nil`, and a handler that throws [fails loud](../glossary.md#fail-loud-not-silent) without crashing the app. Recovery has already happened by the time your sink runs, and there is no hook to swallow, substitute, or retry.

!!! warning "Gotcha: turn off the JVM debug flag on an SSR host"

    On the JVM the debug flag defaults to on. A production SSR host should set `-Dre-frame.debug=false`, or it keeps user input in trace rings for every request ([Configure dev and production builds](configure-dev-and-prod.md#3-shipping-a-jvmssr-tier-one-system-property)). The error stream runs either way, so this bridge works regardless of the setting.

## 5. Verify it in dev

Because the error stream runs in dev, you can check the branching before you ship. Register a sink without the gates, printing where the Sentry calls would go:

```clojure
(rf/register-observability-sink! :app.sinks/sentry
  (fn [record]
    (println :rf-error (:error record)
             :event-id       (:event-id record)
             :frame          (:frame record)
             :event          (:event record)
             :has-exception? (some? (:exception record)))))
```

Then trigger a few failures; each record prints synchronously:

- **A handler that throws.** You get `:rf.error/handler-exception` with `:has-exception? true`.
- **An unregistered event.** Dispatch an event id nothing is registered under. You get `:rf.error/no-such-handler` with `:has-exception? false`.
- **A secret in the event.** Register the throwing handler with `{:sensitive [[:password]]}` and dispatch it with a `:password` in its arg-map. Under the off-box profile the entire `:event` is omitted, so the printed value is `nil`; the event id remains available. Check a projected trace in Xray to see the individual password marker.

The same failure also appears in Xray with the full dev trace, so you can compare what dev shows with what production keeps.

## 6. Pair `:errors` with its `:handled-events` sibling

The `:handled-events` stream answers the other production question: how many events you process, how fast, and how many fail. It delivers one record per processed event after its run settles, declared in the same frame policy:

```clojure
(rf/configure!
  {:observability {:errors         [{:sink :app.sinks/sentry
                                     :rf.egress/profile :rf.egress/off-box-observability}]
                   :handled-events [{:sink :app.sinks/metrics
                                     :rf.egress/profile :rf.egress/off-box-observability}]}})

(when config/production?
  (rf/register-observability-sink! :app.sinks/metrics
    (fn [{:keys [event-id frame status elapsed-ms]}]
      ;; ship one timing/throughput point per processed event
      (metrics/timing! "rf.event" elapsed-ms
        {:event-id (str event-id) :frame (str frame) :status (name status)}))))
```

!!! warning "Gotcha: the result is in `:status`, and there is no `:time`"

    The handled-event record carries the dispatch result under `:status`, on every profile; destructure that key, since `(name nil)` on a missing one throws. It has no `:time` key, so stamp your own clock; error records do have `:time`.

`:status` describes whether the event pipeline reached its commit and effect walk. It takes one of five values:

- `:ok` — clean settle: `:db` [committed](../glossary.md#commit), flows ran, `:fx` walked.
- `:error` — the interceptor chain (handler or interceptor) threw; the run halted before any `:db` commit.
- `:rejected` — a `:boundary? true` handler's `:schema` refused the event's payload, so the handler never ran.
- `:rolled-back` — `:db` schema validation rejected the candidate state before it installed, so the container kept its pre-handler value; computed flow outputs were discarded and `:fx` was skipped.
- `:flow-error` — a flow's derive function threw; the run halted before `:fx`.

An `:ok` record does not prove every effect succeeded. Effects run after the commit and are best-effort; a thrown or missing effect can emit an error while the event keeps `:status :ok`. Pair the handled-event record with the error stream.

!!! warning "In production, `:rolled-back` never appears; `:rejected` does"

    App-db schema validation is dev-only, so in a release build nothing produces `:rolled-back`: a dispatch whose `:db` violates a registered schema installs anyway and reports `:ok`. A flat `:rolled-back` line proves nothing. For a production check, put the invariant in the handler, or register the handler that receives untrusted input `:boundary? true`.

    A `:boundary? true` handler's check does run in release builds: a refused payload reports `:status :rejected` here and one `:rf.error/schema-validation-failure` record (`:source :boundary`) on the error sink. A spike in `:rejected` is real, and it is the value to alert on.

Together, `:handled-events` tells you that something is wrong (a spike in `:error` or `:rejected`), and `:errors` tells you what: the exception and its stack, or for a boundary rejection, the event and schema ids.

## Advanced

### The frame-teardown report

When a frame is destroyed it runs best-effort cleanup hooks: [flow](../glossary.md#flow) teardown, [resource](../../resources/glossary.md#resource) cleanup, [schema](../glossary.md#schema) deregistration, trace-ring release. Any of them may throw, and the runtime reports all the failures together in one record with no `:event` and no top-level `:exception`.

The runtime builds the record with flat, category-specific keys:

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :some-frame-id
 :hook-failures [{:hook :flows/teardown-on-frame-destroy! :exception <ex> :where :safe-call-hook!}
                 {:hook :resources/on-frame-destroyed!    :exception <ex> :where :safe-call-hook!}]
 :reason        "..."        ;; one human-readable sentence
 :time          1718900000000}
```

On the way to a sink, every key other than the summary keys (`:frame`, `:error`, `:event-id`, `:elapsed-ms`, `:time`, `:correlation`) is moved into a `:tags` map. The frame is already gone when this record is emitted, so no frame policy is consulted: only the process default ([Records no frame owns](#records-no-frame-owns)) receives it, projected with no governing frame. Under the off-box profiles `:tags` therefore arrives as `:rf/redacted`, so send the summary keys; `(get-in record [:tags :hook-failures])` is readable only on a `:rf.egress/local-raw` entry. The SSR categories below also move their keys under `:tags`.

`report-error!` already sends this record as a message tagged with its category and frame. For a clearer title, handle it before falling back to that function:

```clojure
(rf/register-observability-sink! :app.sinks/sentry
  (fn [record]
    (case (:error record)
      ;; The frame-teardown report: frame-keyed, NO :event, NO top-level :exception.
      ;; It reaches the process default only, and its :tags arrive redacted.
      :rf.error/frame-teardown-failed
      (Sentry/captureMessage
        (str "Frame teardown failed: " (:frame record))
        (clj->js {:level "error"
                  :tags  {:frame (str (:frame record))}}))

      ;; Every other category uses the complete bridge from step 1.
      (report-error! record))))
```

Where `:tags` is readable, each `:hook-failures` entry names the teardown step that threw (`:hook`, either a cleanup-hook key or a direct step such as `:frame/notify-machine-destruction!`), carries that step's exception, and records in `:where` the boundary that caught it (`:safe-call-hook!` or `:safe-teardown-step!`). Teardown is best-effort, so nothing in the record calls for action; it is there for diagnosis.

The runtime reports one record per destroy rather than one per failed step, so an SSR host that destroys a frame per request can't flood your monitor, and the steps that failed together stay together. If teardown aborts partway, the failures collected so far are still reported.

### SSR categories

On a [server-side rendering](../../ssr/glossary.md#ssr) host, these categories reach the same sink: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`, and `:rf.error/hydration-frame-id-mismatch`. They carry no `:event` or `:event-id`, their own keys are moved under `:tags` as above, and only some carry an `:exception`. A hydration payload that fails to parse before any frame exists produces a record with no frame, which only the process default from step 1 receives.

### Records no frame owns

Normally a frame's policy decides where its records go, and each record is projected under that frame's classification. Two kinds of record have no frame policy to consult, and only the process default from step 1 receives them:

- **Records with no frame**, such as `:rf.error/no-frame-context` or an SSR hydration payload that failed to parse before any frame existed. The process default receives them with ids intact and the payload `:rf/redacted`.
- **Records from a frame that has been destroyed.** No frame policy is consulted, so a destroyed frame's id can never reach the sink of a new frame that reused the id. The process default receives the record with the old id kept as a diagnostic.

Declaring the default covers both:

```clojure
(rf/configure! {:observability {:errors [{:sink :app.sinks/sentry}]}})
```

There is no unprojected, process-wide error listener; every record reaches you through a sink, projected. If you want sensitive paths in the clear and large values whole, ask for a wider profile: `{:sink :app.sinks/sentry :rf.egress/profile :rf.egress/local-raw}`. That profile is meant for a trusted local destination, and what arrives is still a projected `:rf.observe/error` with the same `:kind` and summary keys.
