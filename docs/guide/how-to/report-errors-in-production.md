# Report errors in production

Your production build compiles out re-frame2's trace surface entirely — no trace listeners, no rings, no Xray. That keeps the bundle lean, but it leaves a gap: a handler (the function that processes an event and returns the next state) can still throw at 3am, and when it does, you want that failure to reach your monitor with real context attached. This guide wires the **always-on error substrate** to Sentry. You register one listener, gate it so it can't leak dev data into your monitor, and branch correctly on every record shape the substrate hands you.

Coming from a plain JS app, you already know the shape of this: `Sentry.init` plus the global `window.onerror` hook. That combination gives you the exception and its stack, which is useful, but it doesn't tell you what the app was actually doing when things went sideways. re-frame2 gives you more. On every production-reachable `:rf.error/*` failure, the runtime fans one structured record out to every registered error listener. Each record carries the event that was in flight (the data describing what the user asked for), the frame it ran in (a frame is one isolated instance of your app, with its own state), and the raw host exception. **The error substrate is always on: production keeps the dossiers, not the firehose.**

!!! warning "the :trace stream is not a production wire"

    This trips people up. The trace stream ([Observability](../concepts/observability.md)) — `register-listener! :trace` — is dev-only: it's dead-code-eliminated under `:advanced` + `goog.DEBUG=false`. A monitor bridge built on it works beautifully in dev and ships *nothing* in production, which means you find out the hard way. The production surface is a different stream of the same verb, `register-listener! :errors`, a separate always-on substrate that survives elision on purpose. Reach for that one.

## 1. Wire the bridge, belt-and-braces gated

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
                              :failed-hooks (mapv (comp str :hook)
                                                  (:hook-failures record))}}))

          ;; Every other category. Handler/interceptor/cofx/flow/fx
          ;; failures carry the raw host throwable; invalid operations
          ;; (:rf.error/no-such-handler, ...) carry :exception nil —
          ;; nothing threw — so branch on presence, never assume it.
          (let [ctx (clj->js {:tags  {:category (str (:error record))
                                      :event-id (str (:event-id record))
                                      :frame    (str (:frame record))}
                              :extra {:event      (pr-str (:event record))
                                      :elapsed-ms (:elapsed-ms record)}})]
            (if-let [ex (:exception record)]
              (Sentry/captureException ex ctx)
              (Sentry/captureMessage (str (:error record)) ctx))))))))
```

The three gates are redundant on purpose, and the redundancy earns its keep. The substrate survives `goog.DEBUG=false`, so it's your own config flag that actually keeps the bridge out of dev. The extra `(not ^boolean interop/debug-enabled?)` check is there to catch one nasty deploy: a dev bundle that shipped with production config baked into it. In that case the listener refuses to register rather than leak dev-verbose data to Sentry — and you'll notice the silence on the dashboard, which is exactly the signal you want. Re-registering the same id replaces the listener atomically, so this is hot-reload safe. And a listener that throws is isolated: it can't block the cascade or take down sibling listeners alongside it.

## 2. Branch on the category, never the prose

The payload your listener receives is a union of shapes, and you might expect to tell them apart by reading the message text. Here's why that's the wrong move: `(:error record)`, the category keyword, is the real discriminator, and it's stable in a way the prose isn't.

- **Per-event error record** — `{:error :event :event-id :frame :time :exception :elapsed-ms}`. One per production-reachable failure inside a cascade: handler, interceptor, cofx, flow, and fx exceptions, plus invalid-operation categories like `:rf.error/no-such-handler` and `:rf.error/frame-destroyed`. For those last ones nothing actually threw, so `:exception` is `nil`.
- **The frame-teardown report** — `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}`. One bounded record per frame destroy whose best-effort cleanup hooks threw, with the per-hook detail in the `:hook-failures` vector. It exists because a leaked handle on a long-lived host compounds silently over time. This is the case that breaks naive bridges: it carries **no `:event` and no `:exception`**, so an unconditional `captureException` mis-ships it.
- On the SSR (server-side rendering) tier, a few more non-event categories (`:rf.error/ssr-render-failed` and siblings) ride the same union. They carry no `:event` / `:event-id`, each with its own flat keys, some with an `:exception` and some without. See [spec 009](../../../spec/009-Instrumentation.md) for the full catalogue.

Two rules keep this branch correct for the long haul. First, branch structurally — on the `(:error record)` keyword and on whether `:exception` is present — and never match against the `:reason` string. That string is human-facing prose, and its wording is allowed to change between releases; the structured slots are the contract, so lean on those. Second, ship the `:event` vector with confidence, because the substrate redacts it for you before fan-out. Paths your app classified as sensitive arrive as `:rf/redacted`, and large payloads arrive as size markers rather than the full thing ([Keep secrets out of traces](keep-secrets-out-of-traces.md)).

## 3. Know what survives elision

It's worth being precise about what's still running in production, because the line isn't obvious.

- **Gone** from an `:advanced` + `goog.DEBUG=false` bundle: every trace emit, `register-listener! :trace` delivery, the per-frame trace rings, epoch history and time-travel, dispatch-id correlation, source-coords, Xray, and the pair tooling. Zero code, zero cost.
- **Still firing:** this error substrate; its event-emit sibling `register-listener! :events`, which delivers one tight `{:event :event-id :frame :time :outcome :elapsed-ms}` record per processed event (throughput and latency, ready for an APM dashboard); and an opt-in Performance API channel behind its own compile-time flag.
- **The listener observes; it never steers.** Recovery is the framework's typed per-category default ([Errors: dossiers, not log lines](../concepts/errors.md)). There is no error hook that swallows, substitutes, or re-runs — the listener is a read-only seat.

!!! note "JVM caveat: flip the gate on an SSR host"

    On the JVM the diagnostic gate defaults **on**, which is the opposite of what you want in production. A production SSR host must set `-Dre-frame.debug=false` explicitly. The good news is the error substrate fires under both settings, so this bridge keeps working there regardless — you're only flipping the gate to shed the dev-verbose overhead.

## Prefer the frame sink for metrics

The raw listener above is the **corpus-wide** hook: every frame, one fan-out. It carries the raw `:exception` object deliberately, because a post-mortem monitor needs the host throwable and its stack to be useful.

!!! note "When to reach for the sink instead"

    For everything that isn't post-mortem — handled-event metrics to Datadog or Honeycomb, or error records projected under a specific frame's privacy policy — use the front-door surface instead: a frame `:observability` sink registered with `rf/register-observability-sink!`. The runtime hands your sink an **already-projected** record, with sensitive fields redacted for you, and routing is fail-closed per frame. Start there unless you specifically need the corpus-wide raw-exception hook this page wires.

## Verify it in dev

The substrate is live in dev too — only your gates keep the bridge off — which is convenient, because it means you can verify the branch before you ship. Register the listener body without the gates, dropping a `println` in place of the Sentry calls. Make a handler throw, then click the thing that dispatches it (dispatch is how you send an event into the system) in your running app. The record prints synchronously, with `:error` and `:event-id` filled in, so you can see the shape with your own eyes. That same failure also lands on Xray's trace surface as the full dev dossier — the firehose you have in dev, sitting right next to the tight record production will actually keep.

---

You can now:

- bridge production errors to Sentry through `register-listener! :errors`, with belt-and-braces gating that fails safe on a mis-deployed dev bundle
- branch on `(:error record)` structurally, handling the frame-teardown report's no-exception shape correctly
- say exactly which observability surfaces survive elision, and set the JVM gate on an SSR host
- choose between the corpus-wide raw listener and a projected frame `:observability` sink
