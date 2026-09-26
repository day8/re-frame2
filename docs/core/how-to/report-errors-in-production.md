# Report errors in production

This recipe sends production failures to an error monitor such as Sentry, with the context needed to act on them. A production build [elides](../glossary.md#elide) the dev-time [trace stream](../glossary.md#trace-stream) and [epoch](../glossary.md#epoch) history, but the error stream stays on in every build. For each failure it covers, it builds one [error record](../glossary.md#error-record) carrying:

- the [event](../glossary.md#event) that was being handled;
- the [frame](../glossary.md#frame) it ran in;
- the host exception, with its stack, when there is one.

The record reaches your monitor through a frame `:observability` sink. The frame declares which sink handles its errors and under which egress profile, and the runtime passes the sink an already-projected record: paths your app classified as sensitive arrive redacted and large values elided, so your integration scrubs nothing. The steps below register the sink, keep it out of dev builds, handle every record shape, and add event metrics.

The error stream covers the event pipeline, subscriptions and flows, not client-side rendering. A view that throws while rendering is React's uncaught error, which the global handler `Sentry.init` installs picks up with no event or frame attached. In Fresco, an error boundary's `:on-error` can turn the failure into an event of your own ([Errors](../fresco/17-errors.md)).

??? info "Coming from plain JavaScript?"

    `Sentry.init` plus `window.onerror` gives you the exception and its stack, but not what the app was doing. The error record carries the event being handled alongside the throwable, so the issue shows the cause as well as the crash site.

## 1. Declare the policy, register the sink

The frame names the sink that takes its error records, and you register the function behind that name:

```clojure
(ns app.monitoring
  (:require ["@sentry/browser" :as Sentry]
            [re-frame.core :as rf]))

(defn init! []
  (Sentry/init #js {:dsn "https://...@sentry.io/..."})

  ;; (a) the frame's policy: which sink id takes its errors, under which profile
  (rf/make-frame
    {:id :app
     :observability {:errors [{:sink :app.sinks/sentry
                               :rf.egress/profile :rf.egress/off-box-observability}]}})

  ;; (b) the concrete sink fn, bound to that id
  (rf/register-observability-sink! :app.sinks/sentry
    (fn [record]                                ;; already projected — nothing to scrub
      (Sentry/captureException (:exception record)))))
```

Call `init!` once at boot, after `rf/init!`. If your app creates its frame with `frame-root` ([Boot and mount an app](boot-and-mount-an-app.md)), put the `:observability` key in the `frame-root` options instead of calling `make-frame`, or declare it once for every frame as shown below.

`:observability` is a policy, not a callback: `:errors` lists the sink ids that receive this frame's error records, and each entry's `:rf.egress/profile` decides how much of the record survives projection ([step 5](#5-choose-the-profile-and-know-what-survives-elision)). `register-observability-sink!` binds a function to the id and returns the id. Registering the same id again replaces it, and a sink that throws doesn't affect other sinks.

Routing fails closed: with no policy in reach, nothing is sent anywhere, and no default frame is invented for you. An entry naming a sink id you never registered sends nothing either.

This bridge works but has three problems, fixed in the steps below: it also runs in dev, it assumes every record has an `:exception`, and it sends nothing about what the app was doing.

Don't build a monitor on `register-listener! :trace` instead. The trace stream is elided from production builds, so such a monitor works on your laptop and sends nothing in production.

### Declare it once for the whole process

Sentry belongs to the deployment rather than to one frame, and most apps have more than one frame, so declare the policy once at boot and let every frame inherit it:

```clojure
  ;; instead of repeating it on every make-frame
  (rf/configure! {:observability {:errors [{:sink :app.sinks/sentry
                                            :rf.egress/profile :rf.egress/off-box-observability}]}})
```

A frame then needs its own `:observability` only when it differs. Inheritance is per stream: a frame that declares `:errors` uses its own error entries and still inherits the default's `:handled-events`, and `{:errors []}` opts one frame out. Only one source is consulted per record, so a sink listed in both fires once. A frame inherits the sink list only; its records are still projected under its own classification, so an admin frame that classifies more paths redacts more while sharing the same Sentry entry.

The process default also receives the records no frame owns, such as an error raised with no frame in scope ([step 8](#8-the-two-records-no-frame-owns)).

`(rf/configure! {:observability nil})` clears the default. The policy is checked when you call `configure!`: a malformed one throws `:rf.error/bad-frame-classification` immediately.

??? info "From re-frame v1"

    v1 had no production error stream; monitoring meant wrapping `dispatch` or relying on `window.onerror`. In re-frame2 the frame `:observability` sink and the process default both stay in production builds. `register-listener!` is a separate, dev-only function that accepts only `:trace` and `:epoch`; any other stream [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/unknown-listener-stream`.

??? info "Coming from Redux?"

    The nearest equivalent is crash-reporting middleware added once with `applyMiddleware`, except that this sink sits outside the data path. It observes failures and cannot swallow, retry, or rewrite anything ([step 5](#5-choose-the-profile-and-know-what-survives-elision)).

## 2. Gate it so it can't fire in dev

The error stream is on in dev too, so the sink from step 1 would send to your real Sentry project every time something throws while you develop. Register the sink function only in a production build, behind your own build flag:

```clojure
(ns app.monitoring
  (:require ["@sentry/browser" :as Sentry]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [app.config :as config]))

(defn init! []
  (when (and config/production?                        ;; your own build flag
             (not ^boolean interop/debug-enabled?)     ;; belt-and-braces
             config/sentry-dsn)                        ;; no DSN, no bridge
    (Sentry/init #js {:dsn config/sentry-dsn})
    (rf/register-observability-sink! :app.sinks/sentry
      (fn [record]
        (Sentry/captureException (:exception record))))))
```

The three conditions:

- `config/production?` is your own build flag. It is what keeps the bridge out of dev, because the error stream doesn't turn itself off.
- `(not ^boolean interop/debug-enabled?)` catches a dev bundle deployed with production config: the sink doesn't register, and the silence on your dashboard tells you something is wrong. (`^boolean` is a type hint that lets the compiler fold the check away.)
- `config/sentry-dsn`: without a DSN there is nothing to send to.

Keep the `:observability` policy from step 1 declared unconditionally; gate only the sink function. In a dev build the policy then names no registered sink, and the framework prints the record to the console instead, which is what you want while developing.

Registering the same id again replaces the sink, so hot reload doesn't stack duplicates. Each sink call is wrapped in its own try/catch, so a bug in your bridge can't block the [pipeline run](../glossary.md#run) or other sinks. To remove the bridge, for example when a feature flag turns off, call `(rf/unregister-observability-sink! :app.sinks/sentry)`.

## 3. Branch on the category, never the prose

Records don't all have the same shape, and not all of them carry an `:exception`. Tell them apart by `(:error record)`, a category keyword such as `:rf.error/handler-exception`, never by the message text, which may change between releases. Projection passes `:error` through untouched, along with the other summary slots: `:frame`, `:event-id`, `:elapsed-ms`, `:time`, and `:correlation`.

Check whether `:exception` is present rather than assuming it. With an exception, send it as one; without, send a message:

```clojure
(rf/register-observability-sink! :app.sinks/sentry
  (fn [record]
    (let [ctx (clj->js {:tags  {:category (str (:error record))
                                :event-id (str (:event-id record))
                                :frame    (str (:frame record))}
                        :extra {:event      (pr-str (:event record))
                                :elapsed-ms (:elapsed-ms record)}})]
      (if-let [ex (:exception record)]
        (Sentry/captureException ex ctx)
        (Sentry/captureMessage (str (:error record)) ctx)))))
```

The records without a throwable are the invalid-operation categories, raised when the runtime refuses an operation rather than something throwing: a handler that was never registered, a [dispatch](../glossary.md#dispatch) into a destroyed frame. A stale closure or a race with teardown can trigger them in production, so they are kept in release builds, and they arrive with `:exception nil`:

- `:rf.error/no-such-handler` / `:rf.error/no-such-sub` / `:rf.error/no-such-fx` / `:rf.error/unregistered-cofx`: a dispatch, [subscription](../glossary.md#subscription), or effect named an id nothing is registered under. The last is raised when a handler's `:rf.cofx/requires` names a [coeffect](../glossary.md#coeffect) with no `reg-cofx`.
- `:rf.error/frame-destroyed`: an operation targeted a frame that has been destroyed, typically a callback firing after teardown.
- `:rf.error/write-after-destroy`: a write to app-db was dropped because the frame was already gone.
- `:rf.error/override-fallthrough`: a frame's [image](../glossary.md#image), the set of [registrations](../glossary.md#registration) it resolves against, had no provider for an overridden id.
- `:rf.error/no-frame-context`: a `subscribe` or `dispatch` using the ambient `rf/` form ran with no frame in scope, typically from a plain function outside a view or from an async callback. The record has `:frame nil`, so no frame policy can route it; only the process default from step 1 receives it (see [step 8](#8-the-two-records-no-frame-owns)). To avoid it, [capture the frame](../glossary.md#capture-frame) before the async boundary ([frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found)).
- `:rf.error/bad-frame-provider-arg`: a [`frame-provider`](../glossary.md#frame-provider) got a non-nil `:frame` that was neither a frame-id keyword nor a frame value, such as a string or a number.
- `:rf.error/machine-spawn-unregistered-type`: a runtime spawn of an unregistered [machine](../../machines/glossary.md#machine) (`:machine-id` with no inline `:definition`) was refused. The sink sees `:frame` at the top level, with `:machine-id` and `:reason` under `:tags`.

The `if-let` sends these through `captureMessage`.

!!! note "Records that name the failing component"

    For `:rf.error/interceptor-exception` (an [interceptor](../glossary.md#interceptor) threw) and `:rf.error/coeffect-exception` (a [coeffect](../glossary.md#coeffect) supplier threw), `:event-id` names the dispatched event, but the broken component is something else. The record names it in `:failing-id`, with its `:source-coord` definition site, at the top level; the human-readable `:reason` is under `:tags`. Group on `:failing-id` to see one interceptor failing across many events.

### Don't scrub the `:event` yourself

The record arrives already projected under the frame's classification and the entry's egress profile: sensitive paths arrive as `:rf/redacted`, and large payloads as a `:rf.size/large-elided` marker ([Keep secrets out of traces](keep-secrets-out-of-traces.md)). The `:exception` doesn't get that treatment under the default profile; [step 5](#5-choose-the-profile-and-know-what-survives-elision) covers when to change that.

## 4. Handle the frame-teardown report

When a frame is destroyed it runs best-effort cleanup hooks: [flow](../glossary.md#flow) teardown, [resource](../../resources/glossary.md#resource) cleanup, [schema](../glossary.md#schema) deregistration, trace-ring release. Any of them may throw, and the runtime reports all the failures together in one record with no `:event` and no top-level `:exception`, so it needs its own branch.

The runtime builds the record with flat, category-specific keys:

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :some-frame-id
 :hook-failures [{:hook :flows/teardown-on-frame-destroy! :exception <ex> :where :safe-call-hook!}
                 {:hook :resources/on-frame-destroyed!    :exception <ex> :where :safe-call-hook!}]
 :reason        "..."        ;; one human-readable sentence
 :time          1718900000000}
```

On the way to a sink, every key other than the summary keys (`:frame`, `:error`, `:event-id`, `:elapsed-ms`, `:time`, `:correlation`) is moved into a `:tags` map. The frame is already gone when this record is emitted, so no frame policy is consulted: only the process default ([step 8](#8-the-two-records-no-frame-owns)) receives it, projected with no governing frame. Under the off-box profiles `:tags` therefore arrives as `:rf/redacted`, so send the summary keys; `(get-in record [:tags :hook-failures])` is readable only on a `:rf.egress/local-raw` entry. The SSR categories below also move their keys under `:tags`.

Add a `case` arm for the teardown category in front of the step 3 logic, which becomes the default arm:

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

      ;; Every other category: the step 3 branch.
      (let [ctx (clj->js {:tags  {:category (str (:error record))
                                  :event-id (str (:event-id record))
                                  :frame    (str (:frame record))}
                          :extra {:event      (pr-str (:event record))
                                  :elapsed-ms (:elapsed-ms record)}})]
        (if-let [ex (:exception record)]
          (Sentry/captureException ex ctx)
          (Sentry/captureMessage (str (:error record)) ctx))))))
```

Where `:tags` is readable, each `:hook-failures` entry names the teardown step that threw (`:hook`, either a cleanup-hook key or a direct step such as `:frame/notify-machine-destruction!`), carries that step's exception, and records in `:where` the boundary that caught it (`:safe-call-hook!` or `:safe-teardown-step!`). Teardown is best-effort, so nothing in the record calls for action; it is there for diagnosis.

The runtime reports one record per destroy rather than one per failed step, so an SSR host that destroys a frame per request can't flood your monitor, and the steps that failed together stay together. If teardown aborts partway, the failures collected so far are still reported.

!!! note "SSR categories arrive here too"

    On a [server-side rendering](../../ssr/glossary.md#ssr) host, these categories reach the same sink: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`, and `:rf.error/hydration-frame-id-mismatch`. They carry no `:event` or `:event-id`, their own keys are moved under `:tags` as above, and only some carry an `:exception`. A hydration payload that fails to parse before any frame exists produces a record with no frame, which only the process default from step 1 receives.

## 5. Choose the profile, and know what survives elision

The `:rf.egress/profile` on each `:observability` entry decides whether your sink sees the host exception:

- **`:rf.egress/off-box-observability`** is the default when you omit the key. It redacts sensitive paths and elides large ones, and passes the `:exception` through, since the stack is the point of hosted monitoring.
- **`:rf.egress/public-error`** drops the record's top-level `:exception`. Use it when the destination is less trusted than your APM.

An unknown profile throws `:rf.error/unknown-egress-profile`.

Under the default profile, a secret in an exception's message or `ex-data` is not redacted, because the projector can't see inside a throwable. `:rf.egress/public-error` removes the record's own top-level `:exception` only.

What still runs in a production build (`:advanced`, `goog.DEBUG=false`), in short ([Observability](../observability.md) has the full account):

- **Removed:** trace emission, `register-listener! :trace` delivery, the per-frame trace rings, [epoch](../glossary.md#epoch) history and [time travel](../glossary.md#time-travel), dispatch-id correlation, source coordinates, [Xray](../glossary.md#xray), and the pair tooling.
- **Kept:** the error stream (frame sinks and the process default), the `:handled-events` stream ([step 7](#7-pair-errors-with-its-handled-events-sibling)), and the opt-in Performance API channel, which has its own compile-time flag.

The sink only observes. What happens after a failure is fixed per category by the framework ([Errors](../errors.md)): a destroyed frame's operation is ignored and reported, a failed subscription returns `nil`, and a handler that throws [fails loud](../glossary.md#fail-loud-not-silent) without crashing the app. Recovery has already happened by the time your sink runs, and there is no hook to swallow, substitute, or retry.

!!! warning "Gotcha: turn off the JVM debug flag on an SSR host"

    On the JVM the debug flag defaults to on. A production SSR host should set `-Dre-frame.debug=false`, or it keeps user input in trace rings for every request ([Configure dev and production builds](configure-dev-and-prod.md#3-shipping-a-jvmssr-tier-one-system-property)). The error stream runs either way, so this bridge works regardless of the setting.

## 6. Verify it in dev

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
- **A secret in the event.** Register the throwing handler with `{:sensitive [[:password]]}` and dispatch it with a `:password` in its arg-map. In the printed `:event`, the password reads `:rf/redacted`, because the record was projected before your sink saw it.

The same failure also appears in Xray with the full dev trace, so you can compare what dev shows with what production keeps.

## 7. Pair `:errors` with its `:handled-events` sibling

The `:handled-events` stream answers the other production question: how many events you process, how fast, and how many fail. It delivers one record per processed event after its run settles, declared in the same frame policy:

```clojure
(rf/make-frame
  {:id :app
   :observability {:errors         [{:sink :app.sinks/sentry
                                     :rf.egress/profile :rf.egress/off-box-observability}]
                   :handled-events [{:sink :app.sinks/metrics
                                     :rf.egress/profile :rf.egress/off-box-observability}]}})

(when (and config/production? (not ^boolean interop/debug-enabled?))
  (rf/register-observability-sink! :app.sinks/metrics
    (fn [{:keys [event-id frame status elapsed-ms]}]
      ;; ship one timing/throughput point per processed event
      (metrics/timing! "rf.event" elapsed-ms
        {:event-id (str event-id) :frame (str frame) :status (name status)}))))
```

!!! warning "Gotcha: the result is in `:status`, and there is no `:time`"

    The handled-event record your sink receives carries the dispatch result under `:status`, on every profile. (The runtime's internal record uses `:outcome`, but no profile hands you that record; `:rf.egress/local-raw` only keeps the `:event` args that the off-box default omits.) Destructuring `outcome` in a sink binds `nil`, and `(name nil)` throws. A handled-event record also has no `:time` key, so stamp your own clock; error records do have `:time`.

`:status` takes one of five values, so a run that failed is never reported as `:ok`:

- `:ok` — clean settle: `:db` [committed](../glossary.md#commit), flows ran, `:fx` walked.
- `:error` — the interceptor chain (handler or interceptor) threw; the run halted before any `:db` commit.
- `:rejected` — a `:boundary? true` handler's `:schema` refused the event's payload, so the handler never ran.
- `:rolled-back` — `:db` schema validation rejected the candidate state before it installed, so the container kept its pre-handler value; flows and `:fx` were skipped.
- `:flow-error` — a flow's derive function threw; the run halted before `:fx`.

!!! warning "In production, `:rolled-back` never appears; `:rejected` does"

    App-db schema validation is dev-only, so in a release build nothing produces `:rolled-back`: a dispatch whose `:db` violates a registered schema installs anyway and reports `:ok`. A flat `:rolled-back` line proves nothing. For a production check, put the invariant in the handler, or register the handler that receives untrusted input `:boundary? true`.

    A boundary check does run in release builds, and so does its report: a refused payload reports `:status :rejected` on the handled-event sink and sends one `:rf.error/schema-validation-failure` record (`:source :boundary`) to the error sink, with no wiring of your own. A spike in `:rejected` is real, and it is the value to alert on.

    The boundary error record carries identifiers only (event id, schema id, frame), never the untrusted payload. You can count refusals and attribute each to a frame and event id; to diagnose one, read the dev trace or branch in the handler ([Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays) lists the record's keys).

Together, `:handled-events` tells you that something is wrong (a spike in `:error` or `:rejected`), and `:errors` tells you what: the exception and its stack, or for a boundary rejection, the event and schema ids.

??? info "Coming from TanStack Query?"

    `:handled-events` is the per-event telemetry you'd feed a metrics dashboard, and `:errors` the crash channel you'd feed an issue tracker, declared as two entries in one frame policy rather than two libraries.

## 8. The two records no frame owns

Normally a frame's policy decides where its records go, and each record is projected under that frame's classification. Two kinds of record have no frame policy to consult, and only the process default from step 1 receives them:

- **Records with no frame**, such as `:rf.error/no-frame-context` or an SSR hydration payload that failed to parse before any frame existed. The process default receives them with ids intact and the payload `:rf/redacted`.
- **Records from a frame that has been destroyed.** No frame policy is consulted, so a destroyed frame's id can never reach the sink of a new frame that reused the id. The process default receives the record with the old id kept as a diagnostic.

Declaring the default covers both:

```clojure
(rf/configure! {:observability {:errors [{:sink :app.sinks/sentry}]}})
```

There is no unprojected, process-wide error listener; every record reaches you through a sink, projected. If you want sensitive paths in the clear and large values whole, ask for a wider profile: `{:sink :app.sinks/sentry :rf.egress/profile :rf.egress/local-raw}`. That profile is meant for a trusted local destination, and what arrives is still a projected `:rf.observe/error` with the same `:kind` and summary keys.
