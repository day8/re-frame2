# Report errors in production

Your production build compiles out re-frame2's trace surface entirely — no trace listeners, no per-frame rings, no Xray. That keeps the bundle lean, but it leaves you with a gap to close: a handler (the function that processes an event and returns the next state) can still throw at 3am, and when it does, you want that failure to land in your monitor with real context attached. This guide wires the **always-on error substrate** to Sentry. You register one listener, gate it so it can't leak dev data into your monitor, and branch correctly on every record shape the substrate hands you. By the end you'll have a production bridge that fails safe, ships the right context, and stays correct as the framework evolves.

Coming from a plain JS app, you already know the shape of this: `Sentry.init` plus the global `window.onerror` hook. That combination gives you the exception and its stack, which is genuinely useful — but it can't tell you what the app was actually *doing* when things went sideways. re-frame2 gives you more. On every production-reachable `:rf.error/*` failure, the runtime fans one structured record out to every registered error listener. Each record carries the event that was in flight (the data describing what the user asked for), the frame it ran in (a frame is one isolated instance of your app, with its own state), and the raw host exception. **The error substrate is always on: production keeps the dossiers, not the firehose.**

> **The `:trace` stream is not a production wire.** This trips people up, so it's worth saying first. The trace stream ([Observability](../concepts/observability.md)) — `register-listener! :trace` — is dev-only: it's dead-code-eliminated under `:advanced` + `goog.DEBUG=false`. A monitor bridge built on it works beautifully in dev and ships *nothing* in production, which means you discover the gap the hard way — usually during an incident. The production surface is a different stream of the same verb, `register-listener! :errors`: a separate, always-on substrate that survives elision on purpose. Reach for that one.

> **One verb, four streams.** `register-listener!` takes a leading `stream` keyword from a **closed four-member vocabulary** — `:trace`, `:events`, `:errors`, `:epoch` — so the differentiator is data, not a per-channel function. There is no bare-trace default and no compatibility alias: an unknown stream throws `:rf.error/unknown-listener-stream`. Two of those streams survive elision (`:events`, `:errors`); two are dev-only (`:trace`, `:epoch`). This page lives almost entirely on `:errors`, with a nod to its `:events` sibling at the end.

## 1. Wire the bridge, belt-and-braces gated

Here's the whole thing. We'll walk through why each gate is there right after.

```clojure
;; Pattern per spec/009-Instrumentation.md §What IS available in production.
(ns app.monitoring
  (:require ["@sentry/browser" :as Sentry]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [app.config :as config]))

(defn init! []
  (when (and config/production?                        ;; your own config flag
             (not ^boolean interop/debug-enabled?)     ;; belt-and-braces
             config/sentry-dsn)                        ;; no DSN, no bridge
    (Sentry/init #js {:dsn config/sentry-dsn})
    (rf/register-listener! :errors ::sentry-bridge
      (fn [record]
        (case (:error record)
          ;; The frame-teardown report: frame-keyed, NO :event, NO :exception.
          :rf.error/frame-teardown-failed
          (Sentry/captureMessage
            (str "Frame teardown failed: " (:frame record))
            (clj->js {:level "error"
                      :tags  {:frame (str (:frame record))}
                      :extra {:reason       (:reason record)
                              :failed-hooks (mapv (fn [{:keys [hook exception]}]
                                                    {:hook  (str hook)
                                                     :error (some-> exception ex-message)})
                                                  (:hook-failures record))}}))

          ;; Every other category. Handler/interceptor/cofx/flow/fx
          ;; failures carry the raw host throwable; invalid operations
          ;; (:rf.error/no-such-handler, ...) carry :exception nil —
          ;; nothing threw — so branch on presence, never assume it.
          (let [ctx (clj->js {:tags  {:category   (str (:error record))
                                      :event-id   (str (:event-id record))
                                      :failing-id (str (:failing-id record)) ;; nil unless distinct
                                      :frame      (str (:frame record))}
                              :extra {:event      (pr-str (:event record))
                                      :reason     (:reason record)
                                      :elapsed-ms (:elapsed-ms record)}})]
            (if-let [ex (:exception record)]
              (Sentry/captureException ex ctx)
              (Sentry/captureMessage (str (:error record)) ctx))))))))
```

The three gates in that `when` are redundant on purpose, and the redundancy earns its keep:

- `config/production?` is your own build flag — the thing that *actually* keeps the bridge out of dev, since the substrate itself survives `goog.DEBUG=false` and won't gate itself.
- `(not ^boolean interop/debug-enabled?)` is the belt-and-braces check. It catches one specific nasty deploy: a **dev** bundle that shipped with **production** config baked into it. In that case the listener refuses to register rather than fire dev-verbose data at Sentry — and you'll notice the resulting silence on your dashboard, which is exactly the signal you want.
- `config/sentry-dsn` means no DSN, no bridge. A missing DSN is a misconfiguration, not a reason to half-wire a monitor.

Two more properties fall out of the substrate's design and are worth knowing. Re-registering the same `id` (`::sentry-bridge`) replaces the listener atomically — the swap from old callback to new happens *between* two emits, never mid-emit, with no event re-delivered and none dropped — so this is **hot-reload safe**: your dev refreshes won't stack duplicate listeners. And a listener that throws is **isolated**: the runtime try/catches each invocation, so a bug in your bridge can't block the cascade or take down sibling listeners alongside it. (Want to take the bridge back down — a feature flag flipping off, say? `(rf/unregister-listener! :errors ::sentry-bridge)` removes exactly that one listener.)

> **Coming from Redux?** The closest analogue is a logging/crash-reporting middleware that you `applyMiddleware` once at store-creation. The difference is that this listener sits *outside* the data path entirely — it observes failures, it never sits in the reducer chain, and it has no power to swallow, retry, or rewrite the action. It's a read-only seat, by design (more on that below).

## 2. Branch on the category, never the prose

The payload your listener receives is a *union* of shapes, and your first instinct might be to tell them apart by reading the message text. Resist it. The real discriminator is `(:error record)` — the category keyword — and it's stable in a way human-facing prose simply isn't.

Here are the shapes you'll actually receive:

- **Per-event error record** — `{:error :event :event-id :frame :time :exception :elapsed-ms}`. One per production-reachable failure inside a cascade: handler, interceptor, cofx, flow, and fx exceptions, plus invalid-operation categories. The runtime attributes each one to its *true* failing component — a handler throw is `:rf.error/handler-exception`, but a coeffect-supplier throw during context assembly is `:rf.error/coeffect-exception` and a user-interceptor throw is `:rf.error/interceptor-exception`, even though all three ride the same `:before`/`:after` chain. For the invalid-operation categories below nothing actually *threw*, so `:exception` is `nil` — which is exactly why the code above branches on `(:exception record)` being present rather than assuming it.
- **The frame-teardown report** — `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :time}`. One bounded record per frame destroy whose best-effort cleanup hooks threw, with the per-hook detail in the `:hook-failures` vector. It exists because a leaked handle on a long-lived host (an SSR server, say) compounds silently over the process's lifetime. This is the case that breaks naive bridges: it carries **no `:event` and no `:exception`** at the top level, so an unconditional `captureException` either mis-ships it or crashes on a `nil`. That's why it gets its own `case` arm.
- **SSR (server-side rendering) categories** — on the SSR tier, a handful of non-event categories ride the same union: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`, and `:rf.error/hydration-frame-id-mismatch`. They carry no `:event` / `:event-id`, each with its own flat keys, some with an `:exception` and some without. See [spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue) for the full per-category payload.

### The invalid-operation and frameless categories

Most error records carry a throwable, but a meaningful set don't — the *invalid-operation* categories. These fire when the runtime refuses an operation outright rather than letting something throw: addressing a handler that was never registered, dispatching into a frame that's already been destroyed, and so on. They are production-reachable (a stale closure, a race against teardown), so they survive elision, and they arrive with `:exception nil`:

- `:rf.error/no-such-handler` / `:rf.error/no-such-sub` / `:rf.error/no-such-fx` / `:rf.error/no-such-cofx` — you dispatched / subscribed / requested an id nothing is registered under.
- `:rf.error/frame-destroyed` — an operation targeted a frame whose lifecycle already ended (a callback fired after the frame tore down).
- `:rf.error/write-after-destroy` — a commit-plane write was suppressed because the target container was already gone (the write-path partner of `frame-destroyed`).
- `:rf.error/override-fallthrough` — an [image](../../../spec/002-Frames.md) composition resolved to no provider for an overridden id.
- `:rf.error/no-frame-context` — a frame-scoped op (subscribe / dispatch via the ambient 1-arity `rf/` forms) ran with **no frame stamp under no established scope** — the classic "plain Reagent fn can't see its frame" footgun, or a native async callback whose continuation fired after the cascade scope unwound. This record is itself **frameless** (`:frame nil`) but carries capture-site ancestry through the `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` correlation keys, so an off-box shipper can still attribute it.
- `:rf.error/bad-frame-provider-arg` — a public `frame-provider` got a non-nil `:frame` that wasn't a keyword (a string, a number). Frame ids are keywords; this one fails fast before the bad value reaches React context.
- `:rf.error/machine-spawn-unregistered-type` — a runtime spawn of an unregistered `:machine-id` (no inline `:definition`) was refused fail-closed. A structural-only record: `:machine-id`, `:frame`, `:reason`. (The machine *registration*-time rejections are dev-only and never reach this surface.)

Because these have no throwable, your bridge falls through to `captureMessage` rather than `captureException` — the `if-let` in §1 handles that automatically. The lesson is the one rule that keeps the whole branch honest: **check for `:exception`'s presence; never assume it.**

### When the failing component isn't the dispatched event

There's a subtlety worth its own paragraph, because it's the difference between a useful Sentry issue and a useless one. For most categories the failing id *is* the event id — `:rf.error/handler-exception` fails the handler, and the handler *is* the event; the `:rf.error/sub-*` categories carry the sub-id under `:event-id`. But two categories fail a component that is **distinct** from the dispatched event:

- `:rf.error/interceptor-exception` — a *user interceptor* in the chain threw. The `:event-id` slot still names the dispatched event; the *interceptor's* id is the actually-broken thing.
- `:rf.error/coeffect-exception` — a *coeffect supplier* threw during context assembly. Again `:event-id` is the event; the failing *cofx* id is the culprit.

For exactly these two, the record **also** carries `:failing-id` (the failing interceptor / cofx id) and `:reason` (a short human sentence). Without `:failing-id` an off-box shipper would see the category and the event but *not which interceptor or cofx broke* — because the classified component id otherwise rides only the dev-trace tags, which DCE under `goog.DEBUG=false`. So shipping `:failing-id` to Sentry as a tag (as the §1 code does — it's `nil` and harmless on the categories that don't set it) is what lets you group "this one interceptor is failing across many events" instead of a smear of unrelated event-ids.

Two rules keep this branch correct for the long haul:

1. **Branch structurally.** Switch on the `(:error record)` keyword, check whether `:exception` is *present*, and read `:failing-id` when it's there — never match against the `:reason` string. That string is human-facing prose, and its wording is allowed to change between releases. The structured slots are the contract; lean on those.
2. **Ship `:event` with confidence.** You don't have to scrub the event vector yourself — the substrate runs it through `re-frame.elision/elide-wire-value` once before fan-out, with the off-box defaults. Paths your app classified as sensitive arrive as `:rf/redacted`, and oversized payloads arrive as the `:rf.size/large-elided` marker rather than the full thing ([Keep secrets out of traces](keep-secrets-out-of-traces.md)).

> **One thing the substrate does *not* scrub: the raw `:exception`.** The `:event` vector is wire-elided, but the host throwable rides **raw** — deliberately, because a post-mortem shipper needs the stack to be useful. This is the documented exception to the always-on axis's "structured data only" rule. The consequence: a value that landed in an exception message or `ex-data` is *not* redacted on this surface. If that worries you for a given frame, the projected frame sink (below) is the surface that drops the `:exception` for you under its `:rf.egress/public-error` profile.

### The frame-teardown report, in detail

The teardown report is the one shape that trips up bridges written for "an error is an event with an exception," so it's worth dwelling on its exact slots. The record is:

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :some-frame-id
 :hook-failures [{:hook :flows/teardown-on-frame-destroy! :exception <ex> :where :safe-call-hook!}
                 {:hook :resources/on-frame-destroyed!    :exception <ex> :where :safe-call-hook!}]
 :reason        "..."        ;; one human-readable sentence
 :time          1718900000000}
```

Each `:hook-failures` entry names the late-bound cleanup hook that threw (`:hook`), carries *that hook's* throwable (`:exception` — note the per-hook exceptions live **inside** the vector, not at the record's top level), and stamps `:where :safe-call-hook!` for provenance. The record's own recovery disposition is the framework default `:ignored` — teardown stays best-effort by design — and is *not* a slot you read off the record; it's a property of the category, fixed by the runtime.

> **Why a single bounded teardown report?** A frame destroy may run *many* optional cleanup hooks — flow teardown, resource cleanup, schema deregistration, trace-ring release — and several could throw. The runtime could fan out one error per failed hook — but on a long-lived SSR host destroying a frame per request, that's a flood pointed straight at your error shipper. Instead the runtime accumulates the failures into one bounded `:rf.error/frame-teardown-failed` record with a `:hook-failures` vector. You keep the which-hooks-failed-together correlation (which external shippers won't reliably re-group), and you keep your error budget. The destroy *is* the fact; the hooks are detail rows. And it's **emit-safe on a partial teardown**: the entries are flushed through a finally-shaped boundary, so even if teardown itself aborts after hook 3 of 7, the entries collected so far still ship.

## 3. Know what survives elision

It's worth being precise about what's still running in production, because the line isn't obvious from the outside.

- **Gone** from an `:advanced` + `goog.DEBUG=false` bundle: every trace emit, `register-listener! :trace` delivery, the per-frame trace rings, epoch history and time-travel, dispatch-id correlation, source-coords, Xray, and the pair tooling. Zero code, zero cost.
- **Still firing:** this error substrate (the `:errors` stream); its event-emit sibling `register-listener! :events`, which delivers one tight `{:event :event-id :frame :time :outcome :elapsed-ms}` record per processed event (throughput and latency, ready for an APM dashboard); and an opt-in Performance API channel behind its own compile-time flag.
- **The listener observes; it never steers.** Recovery is the framework's typed per-category default ([Errors: dossiers, not log lines](../concepts/errors.md)) — frame-destroyed recovers and emits, a failed subscription returns `nil`, a thrown handler fails loud without crashing the app. There is no error *hook* that swallows, substitutes, or re-runs (the per-frame `:on-error` recovery policy was removed). Your listener is a read-only seat, full stop.

> **JVM caveat — flip the gate on an SSR host.** On the JVM the diagnostic gate defaults **on**, which is the opposite of what you want in production. A production SSR host must set `-Dre-frame.debug=false` explicitly to shed the dev-verbose overhead. The good news: the error substrate fires under *both* settings, so this bridge keeps working there regardless — flipping the gate is purely about cost, not coverage.

### The `:events` sibling — when you want throughput, not crashes

Crash reporting is the `:errors` stream's job. But its sibling, the `:events` stream, answers the *other* production-observability question: how many events am I processing, how fast, and how many of them aborted? It fires one tight record per processed event after the cascade settles:

```clojure
(when (and config/production? (not ^boolean interop/debug-enabled?))
  (rf/register-listener! :events ::apm-bridge
    (fn [{:keys [event-id frame outcome elapsed-ms]}]
      ;; ship one timing/throughput point per processed event
      (metrics/timing! "rf.event" elapsed-ms
        {:event-id (str event-id) :frame (str frame) :outcome (name outcome)}))))
```

The `:outcome` slot is the part that earns its keep, because it reports the dispatch result across **every** cascade-failure path — a dispatch that aborted is never mis-reported to your APM as a clean `:ok`. It takes one of four values:

- `:ok` — clean settle: `:db` committed, flows ran, `:fx` walked.
- `:error` — the interceptor chain (handler or interceptor) threw; the cascade halted before any `:db` commit.
- `:rolled-back` — post-commit `:db` schema validation rejected the new state and the container was restored to its pre-handler value; flows and `:fx` were skipped.
- `:flow-error` — a flow's `:output` threw; the cascade halted before `:fx`.

The two streams pair naturally: `:events` tells you *something is wrong* (a spike of `:error` / `:rolled-back` outcomes on your dashboard); `:errors` tells you *what* (the throwable and its stack, ready for the issue tracker).

## Prefer the frame sink for metrics

The raw `:errors` listener above is the **corpus-wide** hook: every frame, one fan-out, and it carries the raw `:exception` object deliberately, because a post-mortem monitor needs the host throwable and its stack to be useful. That's the right tool for crash reporting. It is *not* the right tool for everything.

> **When to reach for the sink instead.** For everything that isn't post-mortem — handled-event metrics to Datadog or Honeycomb, or error records projected under a *specific* frame's privacy policy — use the front-door surface: a frame `:observability` sink, declared on the frame and wired with `rf/register-observability-sink!`. The runtime hands your sink an **already-projected** record — sensitive fields redacted for you under the frame's classification, the `:exception` dropped under the `:rf.egress/public-error` profile — and routing is fail-closed per frame. Start there unless you specifically need the corpus-wide raw-exception hook this page wires.

Concretely, a frame declares which sink ids it routes to under its `:observability` config — `:handled-events` for the per-event metrics stream, `:errors` for projected error records — and you register the concrete sink fn against each id:

```clojure
;; On the frame registration (per spec/015-Data-Classification.md):
;;   :observability {:handled-events [{:sink :app.sinks/datadog}]
;;                   :errors         [{:sink :app.sinks/sentry-projected}]}

(rf/register-observability-sink! :app.sinks/sentry-projected
  (fn [record]
    ;; record is ALREADY projected under the frame's classification +
    ;; the entry's egress profile: sensitive paths redacted, :exception
    ;; dropped. No sink-local scrubbing — and none needed.
    (Sentry/captureMessage (str (:error record)) (clj->js {:extra record}))))
```

`register-observability-sink!` returns the `sink-id`; re-registering the same id replaces; a throwing sink is isolated from its siblings, exactly like the raw listener. The framework ships no Datadog / Sentry client — the sink fn is your integration's concern. And routing is **fail-closed**: a frame with no `:observability` policy routes *nothing* (there's no `:rf/default` synthesis), so you can't accidentally leak from an unclassified frame.

The mental split: the `:errors` listener is the global black box recorder (raw exceptions, one per process, *unprojected*); the frame `:observability` sink is the per-frame, policy-aware egress for metrics and projected diagnostics. Crash reporting that needs the host stack wants the former; dashboards and privacy-policy-bound error egress want the latter. One record type the sink *can't* carry, by the way, is a **frameless** (`:frame nil`) record — the pre-frame SSR hydration-parse path and `:rf.error/no-frame-context` — because there's no owning frame to supply a policy. Those reach the corpus-wide listener only; it's the other reason a black-box `:errors` listener stays useful even when you've gone all-in on frame sinks.

## Verify it in dev

The substrate is live in dev too — only your gates keep the bridge *off* — which is convenient, because it means you can eyeball the branch before you ship. Register the listener body **without the gates**, dropping a `println` in place of the Sentry calls:

```clojure
(rf/register-listener! :errors ::probe
  (fn [record]
    (println :rf-error (:error record)
             :event-id      (:event-id record)
             :failing-id    (:failing-id record)
             :has-exception? (some? (:exception record)))))
```

Now exercise a few failing paths and watch the records print **synchronously**, with `:error`, `:event-id`, and (where distinct) `:failing-id` filled in:

- **A handler that throws.** Click the thing that dispatches it (dispatch is how you send an event into the system). You'll get `:rf.error/handler-exception` with `:has-exception? true` and no `:failing-id` (the handler *is* the event).
- **A bad dispatch.** Dispatch an event id nothing is registered under. You'll get `:rf.error/no-such-handler` with `:has-exception? false` — proof, with your own eyes, that the invalid-operation categories arrive with `:exception nil`.
- **A throwing interceptor or cofx supplier.** This one prints with `:failing-id` set to the broken component, while `:event-id` still names the event — exactly the distinction §2 cares about.

That same handler failure also lands on Xray's trace surface as the full dev dossier — the firehose you have in dev, sitting right next to the tight record production will actually keep. Seeing both side by side is the fastest way to build the right intuition for what gets dropped at the elision boundary.

---

You can now:

- bridge production errors to Sentry through `register-listener! :errors`, with belt-and-braces gating that fails safe on a mis-deployed dev bundle
- branch on `(:error record)` structurally — handling the invalid-operation categories' `:exception nil` shape, the frame-teardown report's no-event / no-exception shape, and the `:failing-id` slot that names a broken interceptor or cofx
- pair the `:errors` crash stream with its `:events` throughput sibling and read the `:outcome` enum on your APM dashboard
- say exactly which observability surfaces survive elision, and set the JVM gate on an SSR host
- choose between the corpus-wide raw `:errors` listener and a projected frame `:observability` sink wired with `register-observability-sink!`