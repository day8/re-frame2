# Report errors in production

Ship a re-frame2 app as an optimised production build and the compiler **[elides](../glossary.md#elide)** the whole dev-time *trace surface* — the rich "here's everything that just happened" feed that powers tools like [Xray](../glossary.md#xray). No [trace stream](../glossary.md#trace-stream), no per-frame [epoch](../glossary.md#epoch) rings, none of it. Good. You're not shipping a debugger to every user.

But it leaves a gap. An [event handler](../glossary.md#event-handler) can still throw at 3am, and when it does you want that failure to land in your monitor with real context attached — not a bare stack trace stripped of *what the app was doing*.

re-frame2 closes the gap with an **always-on error substrate**: a small slice of runtime that survives elision *on purpose*. For every failure the substrate covers, it fans one structured **[error record](../glossary.md#error-record)** out to every error [listener](../glossary.md#listener) you've registered. Each record carries three things worth having at 3am:

- **the [event](../glossary.md#event) that was in flight** — the data vector describing what the user asked for;
- **the [frame](../glossary.md#frame) it ran in** — the isolated app instance, with its own [app-db](../glossary.md#app-db); and
- **the raw host exception** — the actual throwable, stack and all.

This guide builds a production bridge to Sentry, one step at a time: register the listener, gate it so it can't leak dev data, then branch correctly on every record shape the substrate hands you. Smallest thing that works first. Safety as we go.

??? info "Coming from plain JavaScript?"

    You'd reach for `Sentry.init` plus the global `window.onerror` hook. That gives you the exception and its stack — genuinely useful — but it can't tell you what the app was actually *doing* when things went sideways. re-frame2's record carries the in-flight event alongside the throwable, so your issue tracker shows the *cause*, not just the crash site. The slogan: **production keeps the dossiers, not the firehose.**

## 1. Register a listener

Here is the smallest possible bridge. It catches the failures the substrate reports and ships the throwable to Sentry:

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

One verb, `register-listener!`. Its leading `:errors` keyword names the **stream** you're listening to — think of a stream as a named channel the runtime broadcasts on, and `:errors` is the one carrying production failures. The second argument, `::sentry-bridge`, is an id *you* choose, so you can replace or remove this listener later. That's the whole surface for now: every failure the substrate reports runs your `fn` once with a structured `record`, and you forward its `:exception` to Sentry.

That's a working bridge — but it's naive in three ways we'll fix in turn: it fires in dev too, it assumes every record carries an `:exception` (some don't), and it ships nothing useful about *what* the app was doing. The rest of this page is those three fixes.

One trap to disarm before you go further. There's a *different* stream, `register-listener! :trace`, and it's tempting because it carries everything. Don't reach for it here. It's dev-only and gets **elided** in a production build (`:advanced` + `goog.DEBUG=false`), so a monitor built on `:trace` works beautifully on your laptop and ships *nothing* in production — and you discover the gap mid-incident, which is the worst possible time. The `:errors` stream is the always-on substrate that survives elision. That's the one.

??? info "From re-frame v1"

    v1 had no always-on error wire — production monitoring meant wrapping `dispatch` yourself or hanging off `window.onerror`. The four-member `register-listener!` vocabulary (`:trace`, `:events`, `:errors`, `:epoch`) is new in v2, and two of those streams (`:events`, `:errors`) are *designed* to survive elision. There's no bare-trace default and no compatibility alias: an unknown stream [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/unknown-listener-stream`.

??? info "Coming from Redux?"

    The closest analogue is a logging / crash-reporting middleware you `applyMiddleware` once at store creation. The difference: this listener sits *outside* the data path entirely. It observes failures; it never sits in the reducer chain; it has no power to swallow, retry, or rewrite the action. It's a read-only seat by design — more on that in §5.

## 2. Gate it so it can't fire in dev

The substrate is always on, in dev *and* production, so the bare listener from §1 fires against your real Sentry project every time something throws while you're developing. Not what you want. The fix is to register it only in an actual production build — gate it behind your own build flag:

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

(That `^boolean` is a Clojure *type hint* on `interop/debug-enabled?` — it tells the compiler "this is a true boolean," which lets the production compiler constant-fold the check away. Read it as if it weren't there.)

Three gates, redundant on purpose, each earning its keep:

- `config/production?` is your own build flag — the thing that *actually* keeps the bridge out of dev, since the substrate survives `goog.DEBUG=false` and won't gate itself.
- `(not ^boolean interop/debug-enabled?)` is the belt-and-braces check. It catches one specific nasty deploy: a **dev** bundle that shipped with **production** config baked in. There, the listener refuses to register rather than fire dev-verbose data at Sentry — and the resulting silence on your dashboard is exactly the signal you want.
- `config/sentry-dsn` means no DSN, no bridge. A missing DSN is a misconfiguration, not a reason to half-wire a monitor.

Two more properties fall out of the substrate's design for free:

- **Re-registering the same id replaces it atomically.** Register `::sentry-bridge` twice and the swap happens *between* two emits, never mid-emit — no record re-delivered, none dropped. So the bridge is **hot-reload safe**: dev refreshes won't stack duplicate listeners.
- **A listener that throws is isolated.** The runtime wraps each invocation in its own try/catch, so a bug in *your* bridge can't block the [pipeline run](../glossary.md#run) or take down sibling listeners.

To take the bridge back down — a feature flag flipping off, say — call `(rf/unregister-listener! :errors ::sentry-bridge)`.

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
                        :extra {:event        (pr-str (:event record))
                                :reason       (:reason record)
                                :elapsed-ms   (:elapsed-ms record)
                                :source-coord (:source-coord record)}})] ;; {:ns :file :line}, when captured
      (if-let [ex (:exception record)]
        (Sentry/captureException ex ctx)
        (Sentry/captureMessage (str (:error record)) ctx)))))
```

(`clj->js` converts the Clojure map into the plain JS object Sentry's API wants; `pr-str` renders the event vector as a readable string. Nothing re-frame-specific — just the two adapters you reach for when handing Clojure data to a JS library.)

So why can the `:exception` be absent? Most failures carry the raw host throwable, but a meaningful set don't — the **invalid-operation** categories. These fire when the runtime *refuses* an operation outright rather than letting something throw: addressing a handler that was never registered, [dispatching](../glossary.md#dispatch) into a frame that's already been destroyed. They're production-reachable (a stale closure or a race against teardown can both trigger them), so they survive elision, and they arrive with `:exception nil`:

- `:rf.error/no-such-handler` / `:rf.error/no-such-sub` / `:rf.error/no-such-fx` / `:rf.error/unregistered-cofx` — you dispatched / [subscribed](../glossary.md#subscription) / requested an id nothing is registered under (the last fires when a handler's `:rf.cofx/requires` names a [coeffect](../glossary.md#coeffect) with no `reg-cofx`).
- `:rf.error/frame-destroyed` — an operation targeted a frame whose lifecycle already ended (a callback fired after teardown).
- `:rf.error/write-after-destroy` — a write to app-db was suppressed because the target frame was already gone (the write-path partner of `frame-destroyed`).
- `:rf.error/override-fallthrough` — an [image](../glossary.md#image) (the sealed set of [registrations](../glossary.md#registration) a frame resolves against) resolved to no provider for an overridden id.
- `:rf.error/no-frame-context` — a frame-scoped op (a `subscribe` / `dispatch` using the ambient 1-arity `rf/` forms) ran with **no frame in scope** — the classic "a plain Reagent function can't see its frame" footgun, or a native async callback whose continuation fired after the run had already unwound. This record is itself **frameless** (`:frame nil`); in a *dev* build it also carries the capture site's `:rf.trace/dispatch-id` — one correlation key, naming the run that captured the callback. There is no `:rf.trace/parent-dispatch-id` beside it: ancestry is never copied onto the record, it's **walked** from that id across the trace stream's `:rf.event/dispatched` events ([Observability](../observability.md#two-properties-that-shape-everything-downstream)). In production you get neither — the id is dev-minted, and the stream you'd walk it across is elided (§5's elision list covers why). ([Frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) — and a callback that lost its frame is the price of breaking that rule; capture a [capture-frame](../glossary.md#capture-frame) before the async boundary to avoid it.)
- `:rf.error/bad-frame-provider-arg` — a public [`frame-provider`](../glossary.md#frame-provider) got a non-nil `:frame` that was neither a frame id keyword nor a live frame value (a string, a number). Those two are the whole target grammar; this fails fast before the bad value reaches React context.
- `:rf.error/machine-spawn-unregistered-type` — a runtime spawn of an unregistered [machine](../../machines/glossary.md#machine) (`:machine-id` with no inline `:definition`) was refused fail-closed. A structural-only record: `:machine-id`, `:frame`, `:reason`. (Machine *registration*-time rejections are dev-only and never reach this surface.)

Because these have no throwable, your bridge falls through to `captureMessage` — the `if-let` handles it automatically. And there's a second reason to branch on the category keyword rather than the message: the `:reason` string is human-facing, and its exact wording is allowed to change between releases. The structured slots are the contract; lean on those, not the prose.

!!! note "One slot worth grabbing — `:source-coord`"

    When the failing handler or subscription was registered through a `reg-*` macro, the record carries a `:source-coord` of `{:ns … :file … :line …}` — the definition site of the *broken* component. It's the production analogue of the jump-to-source chip [Xray](../glossary.md#xray) shows in dev, and it survives elision on purpose: the coord rides a separate always-on registry, *not* the public registration metadata (which is stripped of coord keys under `goog.DEBUG=false`). Ship it as `:extra` (above) and your Sentry issue links straight back to the line; it also makes a stabler fingerprint than a stack trace whose frames shift between builds. The slot is simply **absent** when the component was registered programmatically (no macro to capture the coord) — so read it defensively, never assume it's there.

### Name the *failing* component, not just the event

Here's the subtlety that's the difference between a useful Sentry issue and a useless one. For most categories the failing id *is* the event id — `:rf.error/handler-exception` fails the handler, and the handler *is* the event; the `:rf.error/sub-*` categories carry the sub-id under `:event-id`. But two categories fail a component **distinct** from the dispatched event:

- `:rf.error/interceptor-exception` — a *user [interceptor](../glossary.md#interceptor)* in the chain threw. `:event-id` still names the dispatched event; the *interceptor's* id is the actually-broken thing.
- `:rf.error/coeffect-exception` — a *[coeffect](../glossary.md#coeffect) supplier* threw while assembling the handler's inputs. Again `:event-id` is the event; the failing *cofx* id is the culprit.

For exactly these two, the record **also** carries `:failing-id` (the broken interceptor / cofx id) and `:reason` (a short human sentence). The §3 code already ships `:failing-id` as a tag — it's `nil` and harmless on every other category. That one tag is what lets you group "*this* interceptor is failing across many events" instead of staring at a smear of unrelated event-ids. Without it, an off-box shipper would see the category and the event but *not which interceptor or cofx broke* — because the classified component id otherwise rides only the dev-trace tags, which are elided under `goog.DEBUG=false`.

??? note "Going deeper"

    The error payload is a *tagged union* (a sum type) keyed by `(:error record)`, with `:exception` an *optional* field rather than a guaranteed one — so a correct consumer is a fold over the discriminant with a presence-check on the optional. The substrate guarantees the tag is a stable closed vocabulary while leaving the `:reason` prose free to vary: the classic move of pinning the machine-readable variant tag and treating the human string as a non-contractual rendering. Branch on the tag, project the optional, ignore the prose.

### Don't scrub the `:event` yourself

You don't have to redact the event vector by hand to keep secrets out of Sentry. The substrate runs it through `re-frame.elision/elide-wire-value` once before fan-out, with the off-box defaults. Paths your app classified as sensitive arrive as `:rf/redacted`, and oversized payloads arrive as the `:rf.size/large-elided` marker rather than the full thing. That's [data classification](../glossary.md#data-classification) doing its job at the egress boundary ([Keep secrets out of traces](keep-secrets-out-of-traces.md)).

Now the flip side, out loud: the raw `:exception` is **not** scrubbed. The `:event` vector is wire-elided, but the host throwable rides raw — deliberately, because a post-mortem shipper needs the stack to be useful. The consequence: a value that landed in an exception message or `ex-data` is *not* redacted on this surface. This is the documented exception to the always-on substrate's "structured data only" rule. If that worries you for a given frame, the projected frame sink (§8) drops the `:exception` for you under its `:rf.egress/public-error` profile.

## 4. Handle the frame-teardown report

One record shape breaks bridges written for "an error is an event with an exception," and it deserves its own branch. When a frame is destroyed it runs a batch of best-effort cleanup hooks — [flow](../glossary.md#flow) teardown, [resource](../../resources/glossary.md#resource) cleanup, [schema](../glossary.md#schema) deregistration, trace-ring release. Any of those may throw, and the runtime reports *all the ones that threw together*, in one bounded record with **no `:event` and no top-level `:exception`**:

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :some-frame-id
 :hook-failures [{:hook :flows/teardown-on-frame-destroy! :exception <ex> :where :safe-call-hook!}
                 {:hook :resources/on-frame-destroyed!    :exception <ex> :where :safe-call-hook!}]
 :reason        "..."        ;; one human-readable sentence
 :time          1718900000000}
```

Notice where the throwables live: **inside** the `:hook-failures` vector, one per failed teardown step, not at the top level. An unconditional `captureException` would either mis-ship this record or crash on the `nil` top-level `:exception`. So give it its own arm in front of the catch-all. (`case` dispatches on the value of `(:error record)`; the first arm matches the teardown category, and the final arm — with no key in front of it — is the default, reusing the §3 logic verbatim.)

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

(`mapv` walks `:hook-failures` and builds a new vector — one summary map per failed step. `some->` guards against a missing `:exception`: it calls `ex-message` only when the exception is non-`nil`, returning `nil` instead of crashing otherwise.) Each `:hook-failures` entry names the teardown step that threw (`:hook` — a late-bound cleanup-hook key, or a guarded direct step such as `:frame/notify-machine-destruction!`), carries *that step's* throwable (`:exception`), and stamps `:where` with the catch boundary that recorded it (`:safe-call-hook!` for a late-bound hook, `:safe-teardown-step!` for a guarded direct step) for provenance. Teardown stays best-effort by design — there's no slot here you act on; the disposition is a fixed property of the category.

!!! note "Why a single bounded report?"

    A frame destroy may run *many* teardown steps — optional cleanup hooks plus a few guarded direct steps — several of which could throw. The runtime *could* fan out one error per failed step, but on a long-lived SSR host that destroys a frame per request, that's a flood pointed straight at your error shipper. Instead it accumulates failures into one bounded record with a `:hook-failures` vector. You keep the which-steps-failed-together correlation (external shippers won't reliably re-group it), and you keep your error budget. The destroy *is* the fact; the failed steps are detail rows. It's also **emit-safe on a partial teardown**: entries are flushed through a finally-shaped boundary, so even if teardown aborts after step 3 of 7, the entries collected so far still ship.

!!! note "SSR categories ride the same union"

    On the [server-side-rendering](../../ssr/glossary.md#ssr) tier, a handful of non-event categories arrive on this stream: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`, and `:rf.error/hydration-frame-id-mismatch`. They carry no `:event` / `:event-id`, each has its own flat keys, and some carry an `:exception` while others don't — one more reason the structural branch checks `:exception` presence rather than assuming it.

## 5. Know what survives elision

It's worth being precise about what's still running in production, because the line isn't obvious from outside. ([Observability](../observability.md) is the full account of what [elision](../glossary.md#elide) removes and spares; here's the short version a monitor needs.)

- **Gone** from an `:advanced` + `goog.DEBUG=false` bundle: every trace emit, `register-listener! :trace` delivery, the per-frame trace rings, [epoch](../glossary.md#epoch) history and [time-travel](../glossary.md#time-travel), dispatch-id correlation, source-coords, [Xray](../glossary.md#xray), and the pair tooling. Zero code, zero cost.
- **Still firing:** this error substrate (the `:errors` stream); its event-emit sibling `register-listener! :events` (§7); and an opt-in Performance API channel behind its own compile-time flag.
- **The listener observes; it never steers.** What to *do* about a failure — recover, retry, fall back — is decided by the framework's typed per-category default ([Errors: dossiers, not log lines](../errors.md)), not by your listener: frame-destroyed recovers and emits, a failed subscription returns `nil`, a thrown handler [fails loud](../glossary.md#fail-loud-not-silent) without crashing the app. There is no error *hook* that swallows, substitutes, or re-runs. Your listener is a read-only seat, full stop.

!!! note "Recovery and observation are separate"

    There is no lever for crash handling that catches inside an interceptor's `:after` and rewrites the context. re-frame2 separates *recovery* (a fixed per-category framework default) from *observation* (your read-only listener). You can't swallow or retry from the listener; recovery already happened before your `fn` ran.

!!! warning "Gotcha — flip the JVM gate on an SSR host"

    On the JVM the diagnostic gate defaults **on**, the opposite of what you want in production. A production SSR host must set `-Dre-frame.debug=false` explicitly to shed the dev-verbose overhead (otherwise it retains user input in trace rings, per request). The good news: the error substrate fires under *both* settings, so this bridge keeps working there regardless — flipping the gate is purely about cost, not coverage.

## 6. Verify it in dev

The substrate is live in dev too — only your gates keep the bridge *off* — which is convenient, because you can eyeball the branch before you ship. Register the listener body **without the gates**, dropping a `println` where the Sentry calls go:

```clojure
(rf/register-listener! :errors ::probe
  (fn [record]
    (println :rf-error (:error record)
             :event-id       (:event-id record)
             :failing-id     (:failing-id record)
             :source-coord   (:source-coord record)
             :has-exception? (some? (:exception record)))))
```

Now exercise a few failing paths and watch the records print **synchronously**:

- **A handler that throws.** Click the thing that dispatches it. You get `:rf.error/handler-exception` with `:has-exception? true` and no `:failing-id` (the handler *is* the event). If you registered that handler with `reg-event` (the macro), `:source-coord` points at its definition line; register one programmatically and the slot is absent — a quick way to see the macro-capture rule from §3 in action.
- **A bad dispatch.** Dispatch an event id nothing is registered under. You get `:rf.error/no-such-handler` with `:has-exception? false` — proof, with your own eyes, that the invalid-operation categories arrive with `:exception nil`.
- **A throwing interceptor or cofx supplier.** This prints with `:failing-id` set to the broken component while `:event-id` still names the event — exactly the distinction §3 cares about.

That same handler failure also lands on Xray's trace surface as the full dev dossier — the firehose you have in dev, sitting right next to the tight record production will actually keep. Seeing both side by side is the fastest way to build the right intuition for what gets dropped at the elision boundary.

## 7. Pair `:errors` with its `:events` sibling

Crash reporting is the `:errors` stream's job. Its sibling, the `:events` stream, answers the *other* production-observability question: how many events am I processing, how fast, and how many aborted? It fires one tight record per processed event after the run settles:

```clojure
(when (and config/production? (not ^boolean interop/debug-enabled?))
  (rf/register-listener! :events ::apm-bridge
    (fn [{:keys [event-id frame outcome elapsed-ms]}]
      ;; ship one timing/throughput point per processed event
      (metrics/timing! "rf.event" elapsed-ms
        {:event-id (str event-id) :frame (str frame) :outcome (name outcome)}))))
```

The `:outcome` slot earns its keep, because it reports the dispatch result across **every** way a run can fail — so a dispatch that aborted is never mis-reported to your APM as a clean `:ok`. It takes one of five values:

- `:ok` — clean settle: `:db` [committed](../glossary.md#commit), flows ran, `:fx` walked.
- `:error` — the interceptor chain (handler or interceptor) threw; the run halted before any `:db` commit.
- `:rejected` — the `:rf.schema/at-boundary` interceptor refused the event's payload, so the handler never ran.
- `:rolled-back` — `:db` schema validation rejected the candidate state before it installed, so the container kept its pre-handler value; flows and `:fx` were skipped.
- `:flow-error` — a flow's `:output` threw; the run halted before `:fx`.

!!! warning "`:rolled-back` is the quiet one in production; `:rejected` is the loud one"

    The `:events` stream survives the production gate, but app-db schema validation does not. `reg-app-schema` is a development-time assertion: a production build registers your schemas and never checks them, so nothing is left to reject a candidate and `:rolled-back` has no producer. A dispatch whose `:db` violates a registered schema installs anyway and reports `:ok`. Do not read a quiet `:rolled-back` metric as evidence that no schema was violated — in production it is quiet by construction. If you need a real production check, put the invariant in the handler, or validate untrusted input with the `:rf.schema/at-boundary` interceptor.

    That interceptor is the complement of everything in the paragraph above, and the two are worth holding side by side because they are easy to conflate. Its check is ungated, and so is its **report**: a refused payload settles `:outcome :rejected` on the `:events` stream and fans one `:rf.error/schema-validation-failure` record (`:source :boundary`) onto the `:errors` stream. Both streams on this page see it, in a release build, with no wiring of your own. So a spike of `:rejected` on your dashboard is a real one, and it is the outcome to alert on — a flat `:rolled-back` line, in the same build, means nothing at all.

    The boundary record is deliberately **structural only**. Its key set is closed: `:error`, `:where`, `:source`, `:event-id`, `:failing-id`, `:schema-id`, `:frame`, `:recovery`, `:time` — and nothing derived from the payload. No event vector, no offending value, no Malli explanation, not even the interpolated `:reason` the development trace carries. That is *stricter* than the redaction applied elsewhere on this page rather than weaker, and the reason is worth internalising: a validation failure's natural detail is the value that failed, and at a boundary that value is attacker-controlled or user-private by definition, so it can carry secrets under keys your declared schema never anticipated — precisely the keys no schema-aware redactor can be trusted to have seen. Omitting the slot is the only policy that holds. You can therefore count refusals, attribute each to a frame and an event id, and alert on the rate; to *diagnose* one you read the dev trace, or branch in your own handler code. See [Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays).

The two streams pair naturally: `:events` tells you *something is wrong* (a spike of `:error` or `:rejected` outcomes on your dashboard); `:errors` tells you *what* (the throwable and its stack, ready for the issue tracker — or, for a boundary rejection, the event and schema ids that name the refused ingress).

??? info "Coming from TanStack Query?"

    Think of `:events` as the per-query lifecycle telemetry you'd feed a metrics dashboard, and `:errors` as the crash channel you'd feed an issue tracker — except here they're two streams of one verb, differentiated by data, not two separate libraries you bolt on.

## 8. Prefer the frame sink for metrics and privacy-bound egress

So far every listener has been **corpus-wide** — one fan-out covering every frame in the app, carrying the raw `:exception` deliberately, because a post-mortem monitor needs the host throwable and its stack to be useful. That's the right tool for crash reporting. It is *not* the right tool for everything.

There's a second surface for the cases that aren't post-mortem: a **frame `:observability` sink**. The difference is who owns the privacy policy. A corpus-wide listener sees every frame, so it can't apply any one frame's data-classification rules. A frame sink is declared *on a specific frame*, so the runtime can hand it an **already-projected** record — sensitive fields redacted under that frame's classification, the `:exception` dropped under the `:rf.egress/public-error` profile, no scrubbing left for you to do. ("Egress" here just means data leaving the app for an off-box destination; the [projection](../glossary.md#project-egress) is the policy deciding what's allowed out.)

Reach for a frame sink when you want either of these:

- **handled-event metrics** to Datadog or Honeycomb — per-event throughput / latency, routed through one frame's policy; or
- **error records projected under a specific frame's privacy policy** — the same crash data, scrubbed to that frame's rules before it leaves.

A frame declares which sink ids it routes to under its `:observability` config — `:handled-events` for the per-event metrics stream, `:errors` for projected error records — and you register the concrete sink function against each id:

```clojure
;; On the frame registration, under the frame's data-classification policy. Each
;; entry carries an :rf.egress/profile naming the boundary — that profile is what
;; decides how much survives projection:
;;   :observability
;;   {:handled-events [{:sink :app.sinks/datadog
;;                      :rf.egress/profile :rf.egress/off-box-observability}]
;;    :errors         [{:sink :app.sinks/sentry-projected
;;                      :rf.egress/profile :rf.egress/public-error}]}

(rf/register-observability-sink! :app.sinks/sentry-projected
  (fn [record]
    ;; record is ALREADY projected under the frame's classification + the
    ;; entry's egress profile: sensitive paths redacted, and — because this
    ;; entry chose :rf.egress/public-error — the raw :exception dropped.
    ;; No sink-local scrubbing, and none needed.
    (Sentry/captureMessage (str (:error record)) (clj->js {:extra record}))))
```

The `:rf.egress/profile` on each entry is the one slot that's easy to get wrong. The default — what you get if you omit it — is `:rf.egress/off-box-observability`, which redacts sensitive paths and elides large ones but **walks the `:exception` through** (it's hosted-monitoring, where the stack is the point). Only `:rf.egress/public-error` *drops* the throwable. So if the whole reason you reached for a frame sink was to strip the host exception, you must say `:rf.egress/profile :rf.egress/public-error` explicitly — leaving the profile off keeps the exception. (An unknown profile is rejected fail-closed with `:rf.error/unknown-egress-profile`, so a typo can't silently downgrade the boundary.)

`register-observability-sink!` returns the `sink-id`; re-registering the same id replaces; a throwing sink is isolated from its siblings, exactly like the raw listener. The framework ships no Datadog / Sentry client — the sink function is your integration's concern. And routing is **fail-closed**: a frame with no `:observability` policy routes *nothing* (there's no `:rf/default` synthesised on your behalf), so you can't accidentally leak from an unclassified frame.

The mental split: the corpus-wide `:errors` listener is the global black-box recorder (raw exceptions, one fan-out per process, *unprojected*); the frame `:observability` sink is the per-frame, policy-aware egress for metrics and projected diagnostics. Crash reporting that needs the host stack wants the former; dashboards and privacy-policy-bound error egress want the latter.

One boundary to carry away. A **frameless** (`:frame nil`) record — the pre-frame SSR hydration-parse path and `:rf.error/no-frame-context` — can't reach a frame sink at all: there's no owning frame to supply a policy. Those records reach the corpus-wide `:errors` listener only. Which is the other reason the black-box `:errors` listener stays useful even after you've gone all-in on frame sinks — it's the seat that sees everything, including the failures that belong to nobody.
