# Report errors in production

Your production build compiles out re-frame2's trace surface. No trace listeners, no rings, no Xray. But a handler exception at 3am still has to reach your monitor with real context attached. This guide wires the **always-on error substrate** to Sentry. You register one listener, gated so it cannot leak dev data, and branch correctly on every record shape the substrate delivers.

In a plain JS app you know `Sentry.init` plus the global `window.onerror` hook. That gives you the exception and its stack, but not what the app was doing. re-frame2 does more. On every production-reachable `:rf.error/*` failure, the runtime fans one structured record to every registered error listener. Each record carries the event that was in flight, the frame it ran in, and the raw host exception. **The error substrate is always on: production keeps the dossiers, not the firehose.**

> **Watch out: `register-listener!` is not a production wire.** The trace stream ([Observability](../concepts/observability.md)) is dev-only. It is dead-code-eliminated under `:advanced` + `goog.DEBUG=false`. A monitor bridge on it works beautifully in dev and ships *nothing* in production. The production surface is `register-error-listener!`, a separate always-on substrate that survives elision on purpose.

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
    (rf/register-error-listener! ::sentry-bridge
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

The gates are redundant on purpose. The substrate survives `goog.DEBUG=false`, so your own config flag is what keeps the bridge out of dev. The extra `(not ^boolean interop/debug-enabled?)` check catches one nasty deploy: a dev bundle shipped with production config baked in. In that case the listener refuses to register rather than leak dev-verbose data to Sentry, and you notice the silence on the dashboard. Re-registering the same id replaces the listener atomically, so it is hot-reload safe. A throwing listener is isolated. It cannot block the cascade or sibling listeners.

## 2. Branch on the category, never the prose

The listener payload is a union of shapes. `(:error record)`, the category keyword, is the discriminator:

- **Per-event error record** — `{:error :event :event-id :frame :time :exception :elapsed-ms}`. One per production-reachable failure inside a cascade: handler, interceptor, cofx, flow, and fx exceptions, plus invalid-operation categories like `:rf.error/no-such-handler` and `:rf.error/frame-destroyed`. For those nothing threw, so `:exception` is `nil`.
- **The frame-teardown report** — `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}`. One bounded record per frame destroy whose best-effort cleanup hooks threw, with the per-hook detail in the `:hook-failures` vector. It exists because a leaked handle on a long-lived host compounds silently. This is the case that breaks naive bridges: it carries **no `:event` and no `:exception`**, so an unconditional `captureException` mis-ships it.
- On the SSR tier, a few more non-event categories (`:rf.error/ssr-render-failed` and siblings) ride the same union. They carry no `:event` / `:event-id`, each with its own flat keys, some with an `:exception` and some without. See [spec 009](../../../spec/009-Instrumentation.md) for the full catalogue.

Two rules keep the branch correct for the long haul. First, branch structurally: on the `(:error record)` keyword and on whether `:exception` is present. Never match the `:reason` string. That string is human-facing prose and its wording is allowed to change; the structured slots are the contract. Second, ship the `:event` vector with confidence. The substrate redacts it before fan-out. Paths your app classified sensitive arrive as `:rf/redacted`, and large payloads arrive as size markers ([Keep secrets out of traces](keep-secrets-out-of-traces.md)).

## 3. Know what survives elision

- **Gone** from an `:advanced` + `goog.DEBUG=false` bundle: every trace emit, `register-listener!` delivery, the per-frame trace rings, epoch history and time-travel, dispatch-id correlation, source-coords, Xray and the pair tooling. Zero code, zero cost.
- **Still firing:** this error substrate; its event-emit sibling `register-event-listener!`, which delivers one tight `{:event :event-id :frame :time :outcome :elapsed-ms}` record per processed event (throughput and latency for an APM dashboard); and an opt-in Performance API channel behind its own compile-time flag.
- **The listener observes; it never steers.** Recovery is the framework's typed per-category default ([Errors: dossiers, not log lines](../concepts/errors.md)). There is no error hook that swallows, substitutes, or re-runs.
- **JVM caveat:** on the JVM the diagnostic gate defaults **on**. A production SSR host must set `-Dre-frame.debug=false` explicitly. The error substrate fires under both settings, so this bridge keeps working there too.

## Prefer the frame sink for metrics

The raw listener above is the **corpus-wide** hook: every frame, one fan-out. It carries the raw `:exception` object deliberately, because a post-mortem monitor needs the host throwable and its stack. For everything else off-box — handled-event metrics to Datadog or Honeycomb, error records projected under a specific frame's privacy policy — use the front-door surface instead: a frame `:observability` sink registered with `rf/reg-observability-sink!`. The runtime hands your sink an **already-projected** record, with sensitive fields redacted for you, and routing is fail-closed per frame. Start there unless you specifically need the corpus-wide raw-exception hook this page wires.

## Verify it in dev

The substrate is live in dev too. Only your gates keep the bridge off. So verify the branch before you ship. Register the listener body without the gates, putting a `println` in place of the Sentry calls. Make a handler throw, then click the thing that dispatches it in your running app. The record prints synchronously, with `:error` and `:event-id` filled in. The same failure also lands on Xray's trace surface as the full dev dossier: the firehose you have in dev, next to the tight record production will keep.

---

You can now:

- bridge production errors to Sentry through `register-error-listener!`, with belt-and-braces gating that fails safe on a mis-deployed dev bundle
- branch on `(:error record)` structurally, handling the frame-teardown report's no-exception shape correctly
- say exactly which observability surfaces survive elision, and set the JVM gate on an SSR host
- choose between the corpus-wide raw listener and a projected frame `:observability` sink

**Next:** classify what must never leave the box in [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md), or set up the build flags in [Configure dev and production builds](configure-dev-and-prod.md).
