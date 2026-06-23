# Report errors in production

When you ship a re-frame2 app with an optimised production build, the compiler strips out the whole *trace surface* — the rich, dev-time stream of "here's everything that just happened" that powers tools like Xray. No trace listeners, no per-frame history rings, none of it. (The jargon for "the compiler deleted this code because production can't reach it" is **elision** — *dead-code elimination*, DCE for short. You'll see the word a lot on this page; that's all it means.) Eliding the trace surface keeps the bundle lean, but it leaves you with a gap to close: a handler — the function that processes an event and returns the next state — can still throw at 3am, and when it does, you want that failure to land in your monitor with real context attached.

re-frame2 closes that gap with an **always-on error substrate**: a small slice of runtime that survives elision *on purpose*. On every production-reachable failure, it fans one structured record out to every error listener you've registered. Each record carries three things worth having at 3am:

- **the event that was in flight** — the data vector describing what the user asked for;
- **the frame it ran in** — a frame is one isolated instance of your app, with its own state ([Frames](../concepts/frames.md)); and
- **the raw host exception** — the actual throwable, stack and all.

This guide builds a production bridge to Sentry one step at a time: register the listener, gate it so it can't leak dev data, then branch correctly on every record shape the substrate hands you. We start with the smallest thing that works and add safety as we go.

> **For JavaScript developers.** In a plain JS app you'd reach for `Sentry.init` plus the global `window.onerror` hook. That gives you the exception and its stack — genuinely useful, but it can't tell you what the app was actually *doing* when things went sideways. re-frame2's record carries the in-flight event alongside the throwable, so your issue tracker shows the *cause*, not just the crash site. The slogan: **production keeps the dossiers, not the firehose.**

## 1. Register a listener

Here is the smallest possible bridge. It catches every production failure and ships the throwable to Sentry:

```clojure
(ns app.monitoring
  (:require ["@sentry/browser" :as Sentry]
            [re-frame.core :as rf]))

(defn init! []
  (Sentry/init #js {:dsn "https://...@sentry.io/..."})
  (rf/register-listener! :errors ::sentry-bridge
    (fn [record]
      (Sentry/captureException (:exception record)))))
```

There's only one verb here, `register-listener!`. Its leading `:errors` keyword names the *stream* you want to listen to — think of a stream as a named channel the runtime broadcasts on, and `:errors` is the one that carries production failures. The second argument, `::sentry-bridge`, is just an id you choose so you can later replace or remove this listener. That's the whole API surface for now. Every time a production-reachable failure occurs, your `fn` runs once with a structured `record`, and you forward its `:exception` to Sentry.

That's a working bridge — but it's naive in three ways we'll fix in turn: it fires in dev too, it assumes every record carries an `:exception` (some don't), and it ships nothing useful about *what* the app was doing. The rest of this page is those three fixes.

> **The `:trace` stream is not a production wire.** This trips people up, so it's worth saying early. There's a *different* stream, `register-listener! :trace` ([Observability](../concepts/observability.md)), that's dev-only: it gets elided in a production build (`:advanced` + `goog.DEBUG=false`). A monitor built on `:trace` works beautifully in dev and ships *nothing* in production — you discover the gap during an incident. The `:errors` stream is the always-on substrate that survives elision. Reach for that one.

> **From re-frame v1.** v1 had no always-on error wire — production monitoring meant wrapping `dispatch` yourself or hanging off `window.onerror`. The four-member `register-listener!` vocabulary (`:trace`, `:events`, `:errors`, `:epoch`) is new in v2, and two of those streams (`:events`, `:errors`) are *designed* to survive elision. There is no bare-trace default and no compatibility alias: an unknown stream throws `:rf.error/unknown-listener-stream`.

> **Coming from Redux?** The closest analogue is a logging/crash-reporting middleware you `applyMiddleware` once at store-creation. The difference: this listener sits *outside* the data path entirely. It observes failures, it never sits in the reducer chain, and it has no power to swallow, retry, or rewrite the action. It's a read-only seat by design — more on that in §5.

## 2. Gate it so it can't fire in dev

The substrate is always on, in dev *and* production, so the bare listener from §1 will fire against your real Sentry project every time something throws while you're developing — not what you want. The fix is to register it only when you're actually in a production build. Gate it behind your own build flag:

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
    (rf/register-listener! :errors ::sentry-bridge
      (fn [record]
        (Sentry/captureException (:exception record))))))
```

(That `^boolean` is a Clojure *type hint* on `interop/debug-enabled?` — it just tells the compiler "this value is a true boolean," which lets the production compiler constant-fold the check away. You can read it as if it weren't there.)

Three gates, redundant on purpose, each earning its keep:

- `config/production?` is your own build flag — the thing that *actually* keeps the bridge out of dev, since the substrate survives `goog.DEBUG=false` and won't gate itself.
- `(not ^boolean interop/debug-enabled?)` is the belt-and-braces check. It catches one specific nasty deploy: a **dev** bundle that shipped with **production** config baked in. There, the listener refuses to register rather than fire dev-verbose data at Sentry — and you'll notice the resulting silence on your dashboard, which is exactly the signal you want.
- `config/sentry-dsn` means no DSN, no bridge. A missing DSN is a misconfiguration, not a reason to half-wire a monitor.

Two more properties fall out of the substrate's design for free. First, re-registering the same `id` (`::sentry-bridge`) **replaces** the listener atomically — the swap happens *between* two emits, never mid-emit, with no event re-delivered and none dropped — so this is **hot-reload safe**: dev refreshes won't stack duplicate listeners. Second, a listener that throws is **isolated**: the runtime wraps each invocation in its own try/catch, so a bug in your bridge can't block the cascade or take down sibling listeners. To take the bridge back down — a feature flag flipping off, say — call `(rf/unregister-listener! :errors ::sentry-bridge)`.

## 3. Branch on the category, never the prose

Now the second naive assumption. The `record` your listener receives isn't one fixed shape — it's one of several related shapes (a *union*), and not all of them carry an `:exception`. So your bridge has to look at a record and decide *which* kind it's holding before it reads fields off it.

Your first instinct might be to tell them apart by reading the message text. Resist it. The real discriminator is `(:error record)` — a category keyword like `:rf.error/handler-exception` — and it's stable in a way human-facing prose simply isn't.

The single rule that keeps the branch honest: **check whether `:exception` is present; never assume it.** The `if-let` below does exactly that. (`if-let` is Clojure's "bind-and-test in one move": it pulls `(:exception record)` out, and if that value is non-`nil` it runs the first branch with `ex` bound to it, otherwise it runs the second.) Exception present, ship it as an exception; absent, ship a message:

```clojure
(rf/register-listener! :errors ::sentry-bridge
  (fn [record]
    (let [ctx (clj->js {:tags  {:category   (str (:error record))
                                :event-id   (str (:event-id record))
                                :failing-id (str (:failing-id record)) ;; nil unless distinct
                                :frame      (str (:frame record))}
                        :extra {:event      (pr-str (:event record))
                                :reason     (:reason record)
                                :elapsed-ms (:elapsed-ms record)}})]
      (if-let [ex (:exception record)]
        (Sentry/captureException ex ctx)
        (Sentry/captureMessage (str (:error record)) ctx)))))
```

(`clj->js` converts the Clojure map into the plain JS object Sentry's API wants; `pr-str` renders the event vector as a readable string. Nothing re-frame-specific — just the two adapters you reach for when handing Clojure data to a JS library.)

So why can the `:exception` be absent? Most failures carry the raw host throwable, but a meaningful set don't — the **invalid-operation** categories. These fire when the runtime *refuses* an operation outright rather than letting something throw: addressing a handler that was never registered, dispatching into a frame that's already been destroyed. They're production-reachable (a stale closure, a race against teardown can both trigger them), so they survive elision, and they arrive with `:exception nil`:

- `:rf.error/no-such-handler` / `:rf.error/no-such-sub` / `:rf.error/no-such-fx` / `:rf.error/no-such-cofx` — you dispatched / subscribed / requested an id nothing is registered under.
- `:rf.error/frame-destroyed` — an operation targeted a frame whose lifecycle already ended (a callback fired after teardown).
- `:rf.error/write-after-destroy` — a write to app-db was suppressed because the target frame was already gone (the write-path partner of `frame-destroyed`).
- `:rf.error/override-fallthrough` — an [image](../../../spec/002-Frames.md) (a composed stack of registrations a frame draws from) resolved to no provider for an overridden id.
- `:rf.error/no-frame-context` — a frame-scoped op (a `subscribe` / `dispatch` using the ambient 1-arity `rf/` forms) ran with **no frame in scope** — the classic "a plain Reagent function can't see its frame" footgun, or a native async callback whose continuation fired after the cascade had already unwound. This record is itself **frameless** (`:frame nil`) but carries capture-site ancestry through the `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` correlation keys, so an off-box shipper can still attribute it.
- `:rf.error/bad-frame-provider-arg` — a public `frame-provider` got a non-nil `:frame` that wasn't a keyword (a string, a number). Frame ids are keywords; this fails fast before the bad value reaches React context.
- `:rf.error/machine-spawn-unregistered-type` — a runtime spawn of an unregistered `:machine-id` (with no inline `:definition`) was refused fail-closed. A structural-only record: `:machine-id`, `:frame`, `:reason`. (Machine *registration*-time rejections are dev-only and never reach this surface.)

Because these have no throwable, your bridge falls through to `captureMessage` — the `if-let` handles it automatically. There's a second reason to branch on the category keyword rather than the message text: the `:reason` string is human-facing, and its exact wording is allowed to change between releases. The structured slots are the contract; lean on those, not the prose.

### Name the *failing* component, not just the event

There's a subtlety here that's the difference between a useful Sentry issue and a useless one. For most categories the failing id *is* the event id — `:rf.error/handler-exception` fails the handler, and the handler *is* the event; the `:rf.error/sub-*` categories carry the sub-id under `:event-id`. But two categories fail a component **distinct** from the dispatched event:

- `:rf.error/interceptor-exception` — a *user interceptor* in the chain threw. `:event-id` still names the dispatched event; the *interceptor's* id is the actually-broken thing.
- `:rf.error/coeffect-exception` — a *coeffect supplier* threw while assembling the handler's inputs. Again `:event-id` is the event; the failing *cofx* id is the culprit.

For exactly these two, the record **also** carries `:failing-id` (the broken interceptor / cofx id) and `:reason` (a short human sentence). The §3 code already ships `:failing-id` as a tag — it's `nil` and harmless on every other category. That tag is what lets you group "this one interceptor is failing across many events" instead of staring at a smear of unrelated event-ids. Without it, an off-box shipper would see the category and the event but *not which interceptor or cofx broke* — because the classified component id otherwise rides only the dev-trace tags, which are elided under `goog.DEBUG=false`.

> **Going deeper.** The error payload is a *tagged union* (a sum type) keyed by `(:error record)`, and `:exception` is an optional field rather than a guaranteed one — so a correct consumer is a fold over the discriminant with a presence-check on the optional. The substrate guarantees the tag is a stable closed vocabulary while leaving the `:reason` prose free to vary; that's the classic move of pinning the machine-readable variant tag and treating the human string as a non-contractual rendering. Branch on the tag, project the optional, ignore the prose.

### Don't scrub the `:event` yourself

You don't have to redact the event vector by hand to keep secrets out of Sentry. The substrate runs it through `re-frame.elision/elide-wire-value` once before fan-out, with the off-box defaults. Paths your app classified as sensitive arrive as `:rf/redacted`, and oversized payloads arrive as the `:rf.size/large-elided` marker rather than the full thing ([Keep secrets out of traces](keep-secrets-out-of-traces.md)).

> **Gotcha — the raw `:exception` is *not* scrubbed.** The `:event` vector is wire-elided, but the host throwable rides **raw** — deliberately, because a post-mortem shipper needs the stack to be useful. The consequence: a value that landed in an exception message or `ex-data` is *not* redacted on this surface. This is the documented exception to the always-on substrate's "structured data only" rule. If that worries you for a given frame, the projected frame sink (§8) drops the `:exception` for you under its `:rf.egress/public-error` profile.

## 4. Handle the frame-teardown report

There's one record shape that breaks bridges written for "an error is an event with an exception," and it deserves its own branch. When a frame is destroyed, it runs a batch of best-effort cleanup hooks — flow teardown, resource cleanup, schema deregistration, trace-ring release. Any of those hooks may throw, and the runtime reports *all the ones that threw together*, in one bounded record that has **no `:event` and no top-level `:exception`**:

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :some-frame-id
 :hook-failures [{:hook :flows/teardown-on-frame-destroy! :exception <ex> :where :safe-call-hook!}
                 {:hook :resources/on-frame-destroyed!    :exception <ex> :where :safe-call-hook!}]
 :reason        "..."        ;; one human-readable sentence
 :time          1718900000000}
```

Notice where the throwables live: **inside** the `:hook-failures` vector, one per failed hook, not at the top level. So an unconditional `captureException` would either mis-ship this record or crash on the `nil` top-level `:exception`. Give it its own arm in front of the catch-all branch. (`case` dispatches on the value of `(:error record)`; the first arm matches the teardown category, and the final arm — with no key in front of it — is the default that handles everything else, reusing the §3 logic verbatim.)

```clojure
(rf/register-listener! :errors ::sentry-bridge
  (fn [record]
    (case (:error record)
      ;; The frame-teardown report: frame-keyed, NO :event, NO top-level :exception.
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

      ;; Every other category — the §3 branch.
      (let [ctx (clj->js {:tags  {:category   (str (:error record))
                                  :event-id   (str (:event-id record))
                                  :failing-id (str (:failing-id record))
                                  :frame      (str (:frame record))}
                          :extra {:event      (pr-str (:event record))
                                  :reason     (:reason record)
                                  :elapsed-ms (:elapsed-ms record)}})]
        (if-let [ex (:exception record)]
          (Sentry/captureException ex ctx)
          (Sentry/captureMessage (str (:error record)) ctx))))))
```

(`mapv` walks the `:hook-failures` vector and builds a new vector — one summary map per failed hook. `some->` guards against a missing `:exception`: it calls `ex-message` only if the exception is non-`nil`, returning `nil` instead of crashing otherwise.) Each `:hook-failures` entry names the cleanup hook that threw (`:hook`), carries *that hook's* throwable (`:exception`), and stamps `:where :safe-call-hook!` for provenance. The record's recovery disposition is the framework default `:ignored` — teardown stays best-effort by design — and isn't a slot you read; it's a fixed property of the category.

> **Why a single bounded report?** A frame destroy may run *many* optional cleanup hooks, several of which could throw. The runtime *could* fan out one error per failed hook — but on a long-lived SSR host that destroys a frame per request, that's a flood pointed straight at your error shipper. Instead it accumulates failures into one bounded record with a `:hook-failures` vector. You keep the which-hooks-failed-together correlation (external shippers won't reliably re-group it), and you keep your error budget. The destroy *is* the fact; the hooks are detail rows. It's also **emit-safe on a partial teardown**: entries are flushed through a finally-shaped boundary, so even if teardown aborts after hook 3 of 7, the entries collected so far still ship.

> **SSR categories ride the same union.** On the server-side-rendering tier, a handful of non-event categories arrive on this stream: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`, and `:rf.error/hydration-frame-id-mismatch`. They carry no `:event` / `:event-id`, each has its own flat keys, and some carry an `:exception` while others don't — one more reason the structural branch checks `:exception` presence rather than assuming it. The full per-category payload is in [spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue).

## 5. Know what survives elision

It's worth being precise about what's still running in production, because the line isn't obvious from the outside.

- **Gone** from an `:advanced` + `goog.DEBUG=false` bundle: every trace emit, `register-listener! :trace` delivery, the per-frame trace rings, epoch history and time-travel, dispatch-id correlation, source-coords, Xray, and the pair tooling. Zero code, zero cost.
- **Still firing:** this error substrate (the `:errors` stream); its event-emit sibling `register-listener! :events` (§7); and an opt-in Performance API channel behind its own compile-time flag.
- **The listener observes; it never steers.** What to *do* about a failure — recover, retry, fall back — is decided by the framework's typed per-category default ([Errors: dossiers, not log lines](../concepts/errors.md)), not by your listener: frame-destroyed recovers and emits, a failed subscription returns `nil`, a thrown handler fails loud without crashing the app. There is no error *hook* that swallows, substitutes, or re-runs (the per-frame `:on-error` recovery policy was removed). Your listener is a read-only seat, full stop.

> **From re-frame v1.** If you wired crash handling in v1 by catching inside an interceptor's `:after` and rewriting the context, that lever is gone — and deliberately. v2 separates *recovery* (a fixed per-category framework default) from *observation* (your read-only listener). You can't swallow or retry from the listener; recovery already happened before your `fn` ran.

> **Gotcha — flip the JVM gate on an SSR host.** On the JVM the diagnostic gate defaults **on**, the opposite of what you want in production. A production SSR host must set `-Dre-frame.debug=false` explicitly to shed the dev-verbose overhead. The good news: the error substrate fires under *both* settings, so this bridge keeps working there regardless — flipping the gate is purely about cost, not coverage.

## 6. Verify it in dev

The substrate is live in dev too — only your gates keep the bridge *off* — which is convenient, because you can eyeball the branch before you ship. Register the listener body **without the gates**, dropping a `println` in place of the Sentry calls:

```clojure
(rf/register-listener! :errors ::probe
  (fn [record]
    (println :rf-error (:error record)
             :event-id      (:event-id record)
             :failing-id    (:failing-id record)
             :has-exception? (some? (:exception record)))))
```

Now exercise a few failing paths and watch the records print **synchronously**:

- **A handler that throws.** Click the thing that dispatches it (dispatch is how you send an event into the system). You'll get `:rf.error/handler-exception` with `:has-exception? true` and no `:failing-id` (the handler *is* the event).
- **A bad dispatch.** Dispatch an event id nothing is registered under. You'll get `:rf.error/no-such-handler` with `:has-exception? false` — proof, with your own eyes, that the invalid-operation categories arrive with `:exception nil`.
- **A throwing interceptor or cofx supplier.** This prints with `:failing-id` set to the broken component while `:event-id` still names the event — exactly the distinction §3 cares about.

That same handler failure also lands on Xray's trace surface as the full dev dossier — the firehose you have in dev, sitting right next to the tight record production will actually keep. Seeing both side by side is the fastest way to build the right intuition for what gets dropped at the elision boundary.

## 7. Pair `:errors` with its `:events` sibling

Crash reporting is the `:errors` stream's job. Its sibling, the `:events` stream, answers the *other* production-observability question: how many events am I processing, how fast, and how many aborted? It fires one tight record per processed event after the cascade settles:

```clojure
(when (and config/production? (not ^boolean interop/debug-enabled?))
  (rf/register-listener! :events ::apm-bridge
    (fn [{:keys [event-id frame outcome elapsed-ms]}]
      ;; ship one timing/throughput point per processed event
      (metrics/timing! "rf.event" elapsed-ms
        {:event-id (str event-id) :frame (str frame) :outcome (name outcome)}))))
```

The `:outcome` slot earns its keep, because it reports the dispatch result across **every** way a cascade can fail — so a dispatch that aborted is never mis-reported to your APM as a clean `:ok`. It takes one of four values:

- `:ok` — clean settle: `:db` committed, flows ran, `:fx` walked.
- `:error` — the interceptor chain (handler or interceptor) threw; the cascade halted before any `:db` commit.
- `:rolled-back` — post-commit `:db` schema validation rejected the new state, so the container was restored to its pre-handler value; flows and `:fx` were skipped.
- `:flow-error` — a flow's `:output` threw; the cascade halted before `:fx`.

The two streams pair naturally: `:events` tells you *something is wrong* (a spike of `:error` / `:rolled-back` outcomes on your dashboard); `:errors` tells you *what* (the throwable and its stack, ready for the issue tracker).

> **Coming from TanStack Query?** Think of `:events` as the per-query lifecycle telemetry you'd feed a metrics dashboard, and `:errors` as the crash channel you'd feed an issue tracker — except here they're two streams of one verb, differentiated by data, not two separate libraries you bolt on.

## 8. Prefer the frame sink for metrics and privacy-bound egress

So far every listener has been **corpus-wide** — one fan-out covering every frame in the app, carrying the raw `:exception` object deliberately, because a post-mortem monitor needs the host throwable and its stack to be useful. That's the right tool for crash reporting. It is *not* the right tool for everything.

There's a second surface for the cases that aren't post-mortem: a **frame `:observability` sink**. The difference is who owns the privacy policy. A corpus-wide listener sees every frame, so it can't apply any one frame's data-classification rules. A frame sink is declared *on a specific frame*, so the runtime can hand it an **already-projected** record — sensitive fields redacted under that frame's classification, the `:exception` dropped under the `:rf.egress/public-error` profile, no scrubbing left for you to do. ("Egress" here just means data leaving the app for an off-box destination; the projection is the policy that decides what's allowed out.)

Reach for a frame sink when you want either of these:

- **handled-event metrics** to Datadog or Honeycomb — per-event throughput/latency, but routed through one frame's policy; or
- **error records projected under a specific frame's privacy policy** — the same crash data, but scrubbed to that frame's rules before it leaves.

A frame declares which sink ids it routes to under its `:observability` config — `:handled-events` for the per-event metrics stream, `:errors` for projected error records — and you register the concrete sink function against each id:

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

`register-observability-sink!` returns the `sink-id`; re-registering the same id replaces; a throwing sink is isolated from its siblings, exactly like the raw listener. The framework ships no Datadog / Sentry client — the sink function is your integration's concern. And routing is **fail-closed**: a frame with no `:observability` policy routes *nothing* (there's no `:rf/default` synthesised on your behalf), so you can't accidentally leak from an unclassified frame.

The mental split: the corpus-wide `:errors` listener is the global black-box recorder (raw exceptions, one fan-out per process, *unprojected*); the frame `:observability` sink is the per-frame, policy-aware egress for metrics and projected diagnostics. Crash reporting that needs the host stack wants the former; dashboards and privacy-policy-bound error egress want the latter.

> **Gotcha — frameless records reach only the corpus-wide listener.** One record type the sink *can't* carry is a **frameless** (`:frame nil`) record — the pre-frame SSR hydration-parse path and `:rf.error/no-frame-context` — because there's no owning frame to supply a policy. Those reach the corpus-wide `:errors` listener only. It's the other reason a black-box `:errors` listener stays useful even when you've gone all-in on frame sinks.