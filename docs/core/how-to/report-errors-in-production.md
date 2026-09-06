# Report errors in production

Ship a re-frame2 app as an optimised production build and the compiler **[elides](../glossary.md#elide)** the whole dev-time *trace surface* — the rich "here's everything that just happened" feed that powers tools like [Xray](../glossary.md#xray). No [trace stream](../glossary.md#trace-stream), no per-frame [epoch](../glossary.md#epoch) rings, none of it. Good. You're not shipping a debugger to every user.

But it leaves a gap. An [event handler](../glossary.md#event-handler) can still throw at 3am, and when it does you want that failure to land in your monitor with real context attached — not a bare stack trace stripped of *what the app was doing*.

re-frame2 closes the gap with an **always-on error substrate**: a small slice of runtime that survives elision *on purpose*. For every failure the substrate covers it builds one structured **[error record](../glossary.md#error-record)** carrying three things worth having at 3am:

- **the [event](../glossary.md#event) that was in flight** — the data vector describing what the user asked for;
- **the [frame](../glossary.md#frame) it ran in** — the isolated app instance, with its own [app-db](../glossary.md#app-db); and
- **the host exception** — the actual throwable, stack and all.

The normal way that record reaches your monitor is a **frame `:observability` sink**. The frame declares which sink handles its errors and under what egress profile; the runtime then hands that sink an **already-projected** record — paths your app classified as sensitive arrive redacted, oversized ones elided, before your code sees them. Your integration never scrubs anything. That is the route this guide builds.

One step at a time: declare the policy and register the sink, gate it so it can't leak dev data, then branch correctly on every record shape the substrate hands you. Smallest thing that works first. Safety as we go.

??? info "Coming from plain JavaScript?"

    You'd reach for `Sentry.init` plus the global `window.onerror` hook. That gives you the exception and its stack — genuinely useful — but it can't tell you what the app was actually *doing* when things went sideways. re-frame2's record carries the in-flight event alongside the throwable, so your issue tracker shows the *cause*, not just the crash site. The slogan: **production keeps the dossiers, not the firehose.**

## 1. Declare the policy, register the sink

Two moves. The **frame** says where its error records go; you register the concrete function against the id it named:

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

The `:observability` map on the frame is a **policy**, not a callback: `:errors` lists the sink ids that receive this frame's error records, and each entry's `:rf.egress/profile` names the boundary the data is allowed to cross. That profile is what decides how much survives projection — §5 is where you choose it deliberately. `register-observability-sink!` binds the function to the id; it returns the `sink-id`, re-registering the same id replaces it, and a throwing sink is isolated from its siblings.

Routing is **fail-closed**: a frame with no `:observability` policy routes *nothing*, and there is no `:rf/default` synthesised on your behalf, so you can't leak from a frame you never classified. Declaring the policy is not the same as routing, either — an entry naming a sink id you never registered routes nowhere, visibly and on purpose.

That's a working bridge — but it's naive in three ways we'll fix in turn: it registers in dev too, it assumes every record carries an `:exception` (some don't), and it ships nothing useful about *what* the app was doing. The rest of this page is those three fixes.

One trap to disarm before you go further. There's a *different* surface, `register-listener! :trace`, and it's tempting because it carries everything. Don't reach for it here. It's dev-only and gets **elided** in a production build (`:advanced` + `goog.DEBUG=false`), so a monitor built on `:trace` works beautifully on your laptop and ships *nothing* in production — and you discover the gap mid-incident, which is the worst possible time. The error substrate behind the sink is the always-on half that survives elision. That's the one.

??? info "From re-frame v1"

    v1 had no always-on error wire — production monitoring meant wrapping `dispatch` yourself or hanging off `window.onerror`. Both halves of v2's substrate are new: the frame-owned `:observability` sink taught here, and the corpus-wide `:errors` listener stream in §8. Two of the listener streams (`:events`, `:errors`) are *designed* to survive elision; there's no bare-trace default and no compatibility alias, so an unknown stream [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/unknown-listener-stream`.

??? info "Coming from Redux?"

    The closest analogue is a logging / crash-reporting middleware you `applyMiddleware` once at store creation. The difference: this sink sits *outside* the data path entirely. It observes failures; it never sits in the reducer chain; it has no power to swallow, retry, or rewrite the action. It's a read-only seat by design — more on that in §5.

## 2. Gate it so it can't fire in dev

The substrate is always on, in dev *and* production, so the bare sink from §1 ships to your real Sentry project every time something throws while you're developing. Not what you want. The fix is to register the sink function only in an actual production build — gate it behind your own build flag:

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

(That `^boolean` is a Clojure *type hint* on `interop/debug-enabled?` — it tells the compiler "this is a true boolean," which lets the production compiler constant-fold the check away. Read it as if it weren't there.)

Three gates, redundant on purpose, each earning its keep:

- `config/production?` is your own build flag — the thing that *actually* keeps the bridge out of dev, since the substrate survives `goog.DEBUG=false` and won't gate itself.
- `(not ^boolean interop/debug-enabled?)` is the belt-and-braces check. It catches one specific nasty deploy: a **dev** bundle that shipped with **production** config baked in. There, the sink refuses to register rather than fire dev-verbose data at Sentry — and the resulting silence on your dashboard is exactly the signal you want.
- `config/sentry-dsn` means no DSN, no bridge. A missing DSN is a misconfiguration, not a reason to half-wire a monitor.

Notice what the frame keeps: the `:observability` policy from §1 stays declared unconditionally. It's data, and it costs nothing. What you gate is the *sink function*. In a dev build the policy therefore resolves to no registered sink, the record is routed nowhere, and the framework's own console fallback prints it for you — which is precisely what you want on your laptop, and why the gate belongs on the registration rather than on the declaration.

Two more properties fall out of the substrate's design for free:

- **Re-registering the same id replaces it.** Register `:app.sinks/sentry` twice and the second binding wins, so the bridge is **hot-reload safe**: dev refreshes won't stack duplicate sinks.
- **A sink that throws is isolated.** The runtime wraps each invocation in its own try/catch, so a bug in *your* bridge can't block the [pipeline run](../glossary.md#run) or take down sibling sinks.

To take the bridge back down — a feature flag flipping off, say — call `(rf/unregister-observability-sink! :app.sinks/sentry)`.

## 3. Branch on the category, never the prose

Now the second naive assumption. The `record` your sink receives isn't one fixed shape — it's one of several related shapes (a *union*), and not all of them carry an `:exception`. So your bridge has to look at a record and decide *which* kind it's holding before it reads fields off it.

Your first instinct might be to tell them apart by reading the message text. Resist it. The real discriminator is `(:error record)` — a category keyword like `:rf.error/handler-exception` — and it's stable in a way human-facing prose simply isn't. Projection leaves it alone: `:error` is a *summary* slot, passed through untouched, as are `:frame`, `:event-id`, `:elapsed-ms`, `:time` and `:correlation`.

The single rule that keeps the branch honest: **check whether `:exception` is present; never assume it.** The `if-let` below does exactly that. (`if-let` is Clojure's "bind-and-test in one move": it pulls `(:exception record)` out, and if that value is non-`nil` it runs the first branch with `ex` bound to it, otherwise it runs the second.) Exception present, ship it as an exception; absent, ship a message:

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

(`clj->js` converts the Clojure map into the plain JS object Sentry's API wants; `pr-str` renders the event vector as a readable string. Nothing re-frame-specific — just the two adapters you reach for when handing Clojure data to a JS library.)

So why can the `:exception` be absent? Most failures carry the host throwable, but a meaningful set don't — the **invalid-operation** categories. These fire when the runtime *refuses* an operation outright rather than letting something throw: addressing a handler that was never registered, [dispatching](../glossary.md#dispatch) into a frame that's already been destroyed. They're production-reachable (a stale closure or a race against teardown can both trigger them), so they survive elision, and they arrive with `:exception nil`:

- `:rf.error/no-such-handler` / `:rf.error/no-such-sub` / `:rf.error/no-such-fx` / `:rf.error/unregistered-cofx` — you dispatched / [subscribed](../glossary.md#subscription) / requested an id nothing is registered under (the last fires when a handler's `:rf.cofx/requires` names a [coeffect](../glossary.md#coeffect) with no `reg-cofx`).
- `:rf.error/frame-destroyed` — an operation targeted a frame whose lifecycle already ended (a callback fired after teardown).
- `:rf.error/write-after-destroy` — a write to app-db was suppressed because the target frame was already gone (the write-path partner of `frame-destroyed`).
- `:rf.error/override-fallthrough` — an [image](../glossary.md#image) (the sealed set of [registrations](../glossary.md#registration) a frame resolves against) resolved to no provider for an overridden id.
- `:rf.error/no-frame-context` — a frame-scoped op (a `subscribe` / `dispatch` using the ambient 1-arity `rf/` forms) ran with **no frame in scope** — the classic "a plain Reagent function can't see its frame" footgun, or a native async callback whose continuation fired after the run had already unwound. This record is itself **frameless** (`:frame nil`), so no frame policy can route it and it never reaches a sink at all — §8 is the seat that sees it. ([Frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) — and a callback that lost its frame is the price of breaking that rule; capture a [capture-frame](../glossary.md#capture-frame) before the async boundary to avoid it.)
- `:rf.error/bad-frame-provider-arg` — a public [`frame-provider`](../glossary.md#frame-provider) got a non-nil `:frame` that was neither a frame id keyword nor a live frame value (a string, a number). Those two are the whole target grammar; this fails fast before the bad value reaches React context.
- `:rf.error/machine-spawn-unregistered-type` — a runtime spawn of an unregistered [machine](../../machines/glossary.md#machine) (`:machine-id` with no inline `:definition`) was refused fail-closed. A structural-only record: `:machine-id`, `:frame`, `:reason`. (Machine *registration*-time rejections are dev-only and never reach this surface.)

Because these have no throwable, your bridge falls through to `captureMessage` — the `if-let` handles it automatically. And there's a second reason to branch on the category keyword rather than the message: human-facing prose is allowed to change between releases. The structured slots are the contract; lean on those, not the prose.

!!! note "Producer attribution rides the corpus-wide record, not the sink"

    Two categories fail a component **distinct** from the dispatched event: `:rf.error/interceptor-exception` (a user [interceptor](../glossary.md#interceptor) in the chain threw) and `:rf.error/coeffect-exception` (a [coeffect](../glossary.md#coeffect) supplier threw while assembling the handler's inputs). For both, `:event-id` still names the dispatched event while the *actually broken* component is something else — and the slots that name it (`:failing-id`, the human `:reason`, and the `:source-coord` definition site) ride the corpus-wide `:errors` record only; the sink route does not carry them. So if grouping by "*this* interceptor is failing across many events" is what you need, §8's listener is the seat that has it.

??? note "Going deeper"

    The error payload is a *tagged union* (a sum type) keyed by `(:error record)`, with `:exception` an *optional* field rather than a guaranteed one — so a correct consumer is a fold over the discriminant with a presence-check on the optional. The substrate guarantees the tag is a stable closed vocabulary while leaving human prose free to vary: the classic move of pinning the machine-readable variant tag and treating the human string as a non-contractual rendering. Branch on the tag, project the optional, ignore the prose.

### Don't scrub the `:event` yourself

You don't have to redact the event vector by hand to keep secrets out of Sentry. The record arrives already projected under the frame's classification and the entry's egress profile. Paths your app classified as sensitive arrive as `:rf/redacted`, and oversized payloads arrive as the `:rf.size/large-elided` marker rather than the full thing. That's [data classification](../glossary.md#data-classification) doing its job at the egress boundary ([Keep secrets out of traces](keep-secrets-out-of-traces.md)).

The `:exception` is the one slot that does **not** get that treatment under the default profile — §5 is where that choice is made, and made deliberately.

## 4. Handle the frame-teardown report

One record shape breaks bridges written for "an error is an event with an exception," and it deserves its own branch. When a frame is destroyed it runs a batch of best-effort cleanup hooks — [flow](../glossary.md#flow) teardown, [resource](../../resources/glossary.md#resource) cleanup, [schema](../glossary.md#schema) deregistration, trace-ring release. Any of those may throw, and the runtime reports *all the ones that threw together*, in one bounded record with **no `:event` and no top-level `:exception`**.

This is the family where the sink's shape differs from the raw record, so it is worth seeing both. The substrate builds the union record with flat, category-specific slots:

```clojure
{:error         :rf.error/frame-teardown-failed
 :frame         :some-frame-id
 :hook-failures [{:hook :flows/teardown-on-frame-destroy! :exception <ex> :where :safe-call-hook!}
                 {:hook :resources/on-frame-destroyed!    :exception <ex> :where :safe-call-hook!}]
 :reason        "..."        ;; one human-readable sentence
 :time          1718900000000}
```

On the way to a sink, every slot that is not a canonical summary key (`:frame`, `:error`, `:event-id`, `:elapsed-ms`, `:time`, `:correlation`) is lifted onto a `:tags` map, so the projector walks and redacts it under the frame's classification — an app value folded into `:reason`, an exception's `ex-data`, all of it. **So your sink reads `(get-in record [:tags :hook-failures])`, not `(:hook-failures record)`.** The same lift applies to the SSR categories below.

Give it its own arm in front of the catch-all. (`case` dispatches on the value of `(:error record)`; the first arm matches the teardown category, and the final arm — with no key in front of it — is the default, reusing the §3 logic verbatim.)

```clojure
(rf/register-observability-sink! :app.sinks/sentry
  (fn [record]
    (case (:error record)
      ;; The frame-teardown report: frame-keyed, NO :event, NO top-level :exception.
      ;; Its category-specific slots ride :tags after projection.
      :rf.error/frame-teardown-failed
      (Sentry/captureMessage
        (str "Frame teardown failed: " (:frame record))
        (clj->js {:level "error"
                  :tags  {:frame (str (:frame record))}
                  :extra {:reason       (get-in record [:tags :reason])
                          :failed-hooks (mapv (fn [{:keys [hook exception]}]
                                                {:hook  (str hook)
                                                 :error (some-> exception ex-message)})
                                              (get-in record [:tags :hook-failures]))}}))

      ;; Every other category — the §3 branch.
      (let [ctx (clj->js {:tags  {:category (str (:error record))
                                  :event-id (str (:event-id record))
                                  :frame    (str (:frame record))}
                          :extra {:event      (pr-str (:event record))
                                  :elapsed-ms (:elapsed-ms record)}})]
        (if-let [ex (:exception record)]
          (Sentry/captureException ex ctx)
          (Sentry/captureMessage (str (:error record)) ctx))))))
```

(`mapv` walks the lifted `:hook-failures` and builds a new vector — one summary map per failed step. `some->` guards against a missing `:exception`: it calls `ex-message` only when the exception is non-`nil`, returning `nil` instead of crashing otherwise.) Each entry names the teardown step that threw (`:hook` — a late-bound cleanup-hook key, or a guarded direct step such as `:frame/notify-machine-destruction!`), carries *that step's* throwable (`:exception`), and stamps `:where` with the catch boundary that recorded it (`:safe-call-hook!` for a late-bound hook, `:safe-teardown-step!` for a guarded direct step) for provenance. Teardown stays best-effort by design — there is no slot here you act on; the disposition is a fixed property of the category.

!!! note "Why a single bounded report?"

    A frame destroy may run *many* teardown steps — optional cleanup hooks plus a few guarded direct steps — several of which could throw. The runtime *could* fan out one error per failed step, but on a long-lived SSR host that destroys a frame per request, that's a flood pointed straight at your error shipper. Instead it accumulates failures into one bounded record with a `:hook-failures` vector. You keep the which-steps-failed-together correlation (external shippers won't reliably re-group it), and you keep your error budget. The destroy *is* the fact; the failed steps are detail rows. It's also **emit-safe on a partial teardown**: entries are flushed through a finally-shaped boundary, so even if teardown aborts after step 3 of 7, the entries collected so far still ship.

!!! note "SSR categories ride the same union"

    On the [server-side-rendering](../../ssr/glossary.md#ssr) tier, a handful of non-event categories arrive here too: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`, and `:rf.error/hydration-frame-id-mismatch`. They carry no `:event` / `:event-id`, each has its own flat keys (lifted onto `:tags` on the sink route, exactly as above), and some carry an `:exception` while others don't — one more reason the structural branch checks `:exception` presence rather than assuming it. The pre-frame hydration-parse path is *frameless*, so like `:rf.error/no-frame-context` it reaches §8's listener and never a sink.

## 5. Choose the profile — and know what survives elision

The `:rf.egress/profile` on each `:observability` entry is the one slot that is easy to get wrong, and it decides exactly one interesting thing: whether your sink sees the host throwable.

- **`:rf.egress/off-box-observability`** — the default, and what you get if you omit the key. It redacts sensitive paths and elides large ones, but **walks the `:exception` through**. That's hosted monitoring, where the stack is the point.
- **`:rf.egress/public-error`** — *drops* the throwable entirely. Reach for it when the record's destination is less trusted than your APM.

So if the whole reason you reached for a frame sink was to strip the host exception, you must say `:rf.egress/profile :rf.egress/public-error` explicitly — leaving the profile off keeps the exception. (An unknown profile is rejected fail-closed with `:rf.error/unknown-egress-profile`, so a typo can't silently downgrade the boundary.)

One honest caveat about the default. The projector walks the exception as it walks any other slot, and an opaque host throwable is a value whose provenance it cannot prove — so a secret that landed in an exception *message* or in `ex-data` is not redacted by classification. If that worries you for a given frame, `:rf.egress/public-error` is the answer, and it is total.

It is also worth being precise about what is still running in production, because the line isn't obvious from outside. ([Observability](../observability.md) is the full account of what [elision](../glossary.md#elide) removes and spares; here's the short version a monitor needs.)

- **Gone** from an `:advanced` + `goog.DEBUG=false` bundle: every trace emit, `register-listener! :trace` delivery, the per-frame trace rings, [epoch](../glossary.md#epoch) history and [time-travel](../glossary.md#time-travel), dispatch-id correlation, source-coords, [Xray](../glossary.md#xray), and the pair tooling. Zero code, zero cost.
- **Still firing:** this error substrate — both its frame-sink route and the corpus-wide `:errors` stream of §8; the `:handled-events` metrics sibling (§7); and an opt-in Performance API channel behind its own compile-time flag.
- **The sink observes; it never steers.** What to *do* about a failure — recover, retry, fall back — is decided by the framework's typed per-category default ([Errors: dossiers, not log lines](../errors.md)), not by your sink: frame-destroyed recovers and emits, a failed subscription returns `nil`, a thrown handler [fails loud](../glossary.md#fail-loud-not-silent) without crashing the app. There is no error *hook* that swallows, substitutes, or re-runs. Your sink is a read-only seat, full stop.

!!! note "Recovery and observation are separate"

    There is no lever for crash handling that catches inside an interceptor's `:after` and rewrites the context. re-frame2 separates *recovery* (a fixed per-category framework default) from *observation* (your read-only sink). You can't swallow or retry from the sink; recovery already happened before your `fn` ran.

!!! warning "Gotcha — flip the JVM gate on an SSR host"

    On the JVM the diagnostic gate defaults **on**, the opposite of what you want in production. A production SSR host must set `-Dre-frame.debug=false` explicitly to shed the dev-verbose overhead (otherwise it retains user input in trace rings, per request). The good news: the error substrate fires under *both* settings, so this bridge keeps working there regardless — flipping the gate is purely about cost, not coverage.

## 6. Verify it in dev

The substrate is live in dev too — only your gates keep the bridge *off* — which is convenient, because you can eyeball the branch before you ship. Register a sink body **without the gates** against the same id, dropping a `println` where the Sentry calls go:

```clojure
(rf/register-observability-sink! :app.sinks/sentry
  (fn [record]
    (println :rf-error (:error record)
             :event-id       (:event-id record)
             :frame          (:frame record)
             :event          (:event record)
             :has-exception? (some? (:exception record)))))
```

Now exercise a few failing paths and watch the records print **synchronously**:

- **A handler that throws.** Click the thing that dispatches it. You get `:rf.error/handler-exception` with `:has-exception? true`.
- **A bad dispatch.** Dispatch an event id nothing is registered under. You get `:rf.error/no-such-handler` with `:has-exception? false` — proof, with your own eyes, that the invalid-operation categories arrive with `:exception nil`.
- **A sensitive path in the event.** Dispatch an event whose handler classified one of its `:db` paths as sensitive, then look at the printed `:event`: it arrives as `:rf/redacted`. That is projection, and it is the thing the sink route does for you that no listener does.

That same handler failure also lands on Xray's trace surface as the full dev dossier — the firehose you have in dev, sitting right next to the tight record production will actually keep. Seeing both side by side is the fastest way to build the right intuition for what gets dropped at the elision boundary.

## 7. Pair `:errors` with its `:handled-events` sibling

Crash reporting is the error route's job. Its sibling, the **`:handled-events`** stream, answers the *other* production-observability question: how many events am I processing, how fast, and how many aborted? It delivers one tight record per processed event after the run settles, and it is declared on the same frame policy:

```clojure
(rf/make-frame
  {:id :app
   :observability {:errors         [{:sink :app.sinks/sentry
                                     :rf.egress/profile :rf.egress/off-box-observability}]
                   :handled-events [{:sink :app.sinks/metrics
                                     :rf.egress/profile :rf.egress/off-box-observability}]}})

(when (and config/production? (not ^boolean interop/debug-enabled?))
  (rf/register-observability-sink! :app.sinks/metrics
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

    The handled-event stream survives the production gate, but app-db schema validation does not. `reg-app-schema` is a development-time assertion: a production build registers your schemas and never checks them, so nothing is left to reject a candidate and `:rolled-back` has no producer. A dispatch whose `:db` violates a registered schema installs anyway and reports `:ok`. Do not read a quiet `:rolled-back` metric as evidence that no schema was violated — in production it is quiet by construction. If you need a real production check, put the invariant in the handler, or validate untrusted input with the `:rf.schema/at-boundary` interceptor.

    That interceptor is the complement of everything in the paragraph above, and the two are worth holding side by side because they are easy to conflate. Its check is ungated, and so is its **report**: a refused payload settles `:outcome :rejected` on the handled-event stream and fans one `:rf.error/schema-validation-failure` record (`:source :boundary`) onto the error route. Both surfaces on this page see it, in a release build, with no wiring of your own. So a spike of `:rejected` on your dashboard is a real one, and it is the outcome to alert on — a flat `:rolled-back` line, in the same build, means nothing at all.

    The boundary record is deliberately **structural only**. Its key set is closed: `:error`, `:where`, `:source`, `:event-id`, `:failing-id`, `:schema-id`, `:frame`, `:recovery`, `:time` — and nothing derived from the payload. No event vector, no offending value, no Malli explanation, not even the interpolated `:reason` the development trace carries. That is *stricter* than the redaction applied elsewhere on this page rather than weaker, and the reason is worth internalising: a validation failure's natural detail is the value that failed, and at a boundary that value is attacker-controlled or user-private by definition, so it can carry secrets under keys your declared schema never anticipated — precisely the keys no schema-aware redactor can be trusted to have seen. Omitting the slot is the only policy that holds. You can therefore count refusals, attribute each to a frame and an event id, and alert on the rate; to *diagnose* one you read the dev trace, or branch in your own handler code. See [Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays).

The two pair naturally: `:handled-events` tells you *something is wrong* (a spike of `:error` or `:rejected` outcomes on your dashboard); `:errors` tells you *what* (the throwable and its stack, ready for the issue tracker — or, for a boundary rejection, the event and schema ids that name the refused ingress).

??? info "Coming from TanStack Query?"

    Think of `:handled-events` as the per-query lifecycle telemetry you'd feed a metrics dashboard, and `:errors` as the crash channel you'd feed an issue tracker — except here they're two entries in one frame's policy, differentiated by data, not two separate libraries you bolt on.

## 8. Advanced — the corpus-wide listener

Everything above is **policy-selected, projected** delivery: a frame declares where its records go, and the runtime projects each one under that frame's classification before your sink sees it.

There is a second door, and it is a different *kind* of thing rather than a different flavour of the same thing. **`(rf/register-listener! :errors id f)` is independent corpus observation**: one fan-out per process, across every frame, delivered *regardless of any frame's policy*, and always **unprojected**.

```clojure
;; Advanced: one seat that sees the whole corpus, unprojected.
(rf/register-listener! :errors ::corpus-tap
  (fn [record]
    (audit/record! record)))
```

Three things reach that seat and nothing else:

- **Frameless records.** A `:frame nil` record — `:rf.error/no-frame-context`, the pre-frame SSR hydration-parse path — has no owning frame to supply a policy, so no sink can ever route it.
- **Records belonging to a frame that is already gone.** A known-dead-incarnation emission deliberately suppresses the sink route, so a dead frame's bare id can never resolve to a same-id successor's sink. The corpus fan-out still fires.
- **Producer attribution.** `:failing-id`, the human `:reason` and the `:source-coord` definition site ride the corpus-wide record and are not carried on the sink route (§3's note).

What is **not** on that list is the host `:exception`. It is tempting to read the corpus-wide door as "the one that keeps the throwable" — it isn't. The sink keeps the throwable too under the default profile; only `:rf.egress/public-error` drops it (§5). The discriminator is *independent corpus observation versus policy-selected projected delivery*, not the presence of a stack trace.

The price of the seat is that you own the trust boundary yourself: nothing arrives redacted except the `:event` vector, which the substrate wire-elides once before fan-out. Take it back down with `(rf/unregister-listener! :errors ::corpus-tap)`.

!!! note "One more consequence of ownership"

    In an untooled dev build the framework prints a console line for any error record *nothing routed*. Registering a corpus-wide listener — for any reason, even one that ignores the category — takes ownership of every record on the page and silences that fallback for all of them, a host app's frames included. A frame sink takes ownership of just its own frame's records. That asymmetry is a reason to prefer the sink even when either would do.
