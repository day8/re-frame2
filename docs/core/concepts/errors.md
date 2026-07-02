# Errors: dossiers, not log lines

You know [events and the cascade](events-and-the-cascade.md) and [effects and coeffects](effects-and-coeffects.md). Now: what happens when one of them breaks?

You've caught a hundred errors with `console.error("something broke", e)`. It kept the message and the stack — and threw away the important half: *which [event](../glossary.md#event) was in flight, which [frame](../glossary.md#frame) owned it, which [handler](../glossary.md#event-handler) was on the hook, what the [cascade](../glossary.md#event-cascade) had already done*. All of that existed in the runtime microseconds before the catch, and then you spent the next hour rebuilding it by hand.

re-frame2's rule is simple: **if the runtime knows it, the [error record](../glossary.md#error-record) carries it.** Every error the framework detects becomes a structured map — fat on purpose — with the causal context already attached. This page teaches you to read that map, predict how your app degrades after each kind of failure, fix the error you'll hit first, and test the structure instead of the string.

## The dossier

When something breaks, the runtime already has an [event](../glossary.md#event) (a dispatched `[:id ...]` vector recording that something happened), an [event handler](../glossary.md#event-handler) (the pure function processing it), and a [frame](../glossary.md#frame) (one isolated app instance) in scope. It keeps all of it. Here's the shape:

```clojure
{:id        42                              ;; unique trace id
 :op-type   :error                          ;; severity — the universal discriminator
 :operation :rf.error/handler-exception     ;; category — a namespaced keyword
 :recovery  :no-recovery                    ;; what the runtime did next
 :time      1781078400456                   ;; emit time
 :source    :ui                             ;; what triggered the cascade
 :rf.trace/trigger-handler                  ;; the in-scope handler — its value is
 {:kind         :event                      ;; the map below, holding the handler's
  :id           :cart/add-item              ;; registration-site coordinates
  :source-coord {:ns myapp.cart :file "src/myapp/cart.cljs" :line 142}}
 :rf.trace/call-site                        ;; where the `dispatch` was written
 {:file "src/myapp/cart_view.cljs" :line 88}
 :tags      {:category          :rf.error/handler-exception
             :failing-id        :cart/add-item
             :handler-id        :cart/add-item
             :phase             :before                  ;; the chain phase that threw
             :rf.event/v        [:cart/add-item {:id 7}]
             :frame             :app/main
             :reason            "Event handler `:cart/add-item` threw: ..."
             :exception-message "Cannot read properties of undefined (reading 'price')"}}
```

That's not a log line. That's a dossier.

Three fields do the heavy lifting, and once you know them you've learned most of the surface:

- **`:op-type`** is severity. `:error` for genuine failures, `:warning` for misuse the runtime recovers from. To ask "show me everything that failed", filter on `:op-type :error` — and you don't need to know a single category name to do it.
- **`:operation`** is the category — a namespaced keyword like `:rf.error/handler-exception` or `:rf.error/no-such-fx`. To narrow to "only handler exceptions", filter on it exactly.
- **`:recovery`** is what the framework did *after* the error. This is the field that tells you whether your app is still standing — usually the first thing you want to know.

Two more slots turn a debugging session into a click. `:rf.trace/trigger-handler` carries the file and line where the failing handler was *registered*; `:rf.trace/call-site` carries the line of the [`dispatch`](../glossary.md#dispatch) that triggered the [cascade](../glossary.md#event-cascade). Tools render both as jump-to-source links. Everything else rides under `:tags`, with one fixed, schema-checked payload shape per category, so a consumer always knows what's in the envelope.

??? info "Coming from Sentry + React error boundaries?"

    Two anchors will help. **Sentry's structured events:** an error is a rich record, not a string. **React error boundaries:** failures land in one designated place. Two things work differently here, though. You instrument *nothing* — the framework emits the structured record itself, with the causal context already attached, where Sentry needs you to wrap and tag by hand. And there's no boundary *component* to catch and steer: what the runtime does after each kind of error is fixed per category, so your code *observes* the failure rather than deciding what to do about it.

??? note "Going deeper — it's a tagged union"

    The dossier is a single product type with a closed tag set per category — a *sum type* keyed on `:operation`, where each variant carries a statically-known `:tags` payload. That's why a consumer can pattern-match on `:operation` and trust the shape of what follows: the catalogue *is* the type definition. The `:op-type`/`:operation` pair is a coarse-then-fine discriminator — severity first (for the "did anything fail?" fold), category second (for per-variant handling) — so you consume at whichever granularity your tool needs.

!!! note "The dossier is a development surface"

    Production builds [*elide*](../glossary.md#elide) the [trace stream](../glossary.md#trace-stream) — not disable it, elide it: dead-code elimination compiles the code clean out of the release bundle, dossiers and all. Errors that must still reach a monitor in production travel a separate always-on channel carrying a tight, privacy-projected record (covered at the end of this page). The channel split lives in [observability](observability.md); wiring Sentry to it is [a how-to](../how-to/report-errors-in-production.md).

## A catalogue you consult, not memorise

The framework emits errors from a fixed set of categories — dozens of them, covering handlers, subscriptions, effects, coeffects, schemas, frames, routing, machines, SSR. The set grows but never breaks, so you don't memorise the list — you learn its grammar.

The prefix names the owning subsystem:

- `:rf.error/*` — a genuine failure.
- `:rf.warning/*` — recoverable misuse.
- `:rf.fx/*` / `:rf.cofx/*` / `:rf.ssr/*` / `:rf.epoch/*` — substrate events riding the same envelope (a platform-skipped [effect](../glossary.md#effect), a [hydration mismatch](../../ssr/glossary.md#hydration-mismatch)).

The vocabulary is stable and additive: existing categories are never renamed or repurposed. Pin a test to `:rf.error/no-such-fx` and it still means that next year.

The catalogue itself — every category, its trigger, its `:tags` payload, its recovery default — is a closed reference table, and every dossier names its own category, so you meet entries one at a time in the trace rather than studying the list cold. Treat it like HTTP status codes: look a category up when you meet it.

## Recovery is typed — and it isn't yours

When an error fires, the runtime applies a typed, per-category default. That's the whole story. There's no app-steering error policy here: no global error handler, no hook that swallows an exception or substitutes a result.

Swallowing an exception masks a bug, and fabricating a replacement result invents something the thrown handler could not have produced. Genuine recovery belongs at the source, for *expected* failures, where "recovery" actually means something: [managed HTTP's](../../async/http.md) `:retry` for a flaky network, or a defensive default at a read. And the framework never re-runs a failing handler behind your back — when you want to try again, you [dispatch](../glossary.md#dispatch) a fresh event.

The `:recovery` vocabulary is small and readable on sight:

- `:no-recovery` — the operation did not complete.
- `:replaced-with-default` — an unresolved input is substituted (a `nil` for a missing sub input, say) and the body runs on.
- `:logged-and-skipped` — the offending input is dropped; siblings still apply.
- `:warned-and-replaced` — last-write-wins on a conflicting set; advisory only.
- `:skipped` — a platform-gated effect documented out, not really an error.
- `:fix-registration` — the "you registered it wrong, here's how" verb covering typos and bad `reg-*` arguments (a `dispatch` to a misspelled fx-id, a [resource](../../resources/glossary.md#resource) subscribed before it's registered, a `reg-view` missing its args vector).

Plus a few category-specific verbs like `:supply-frame` on the missing-frame error below. Every category carries its assigned recovery verb in the dossier itself, so you read it off the record rather than memorising the assignment.

Four of those defaults shape how your app degrades, so they're worth knowing by heart:

- **A throwing event handler halts its cascade with no half-applied state.** `:rf.error/handler-exception` means no [`:db` commit](../glossary.md#commit) and no `:fx`. [app-db](../glossary.md#app-db) is exactly as it was before the dispatch.
- **A dispatch to an unregistered event id is a traced no-op.** `:rf.error/no-such-handler` (recovery `:replaced-with-default`) lets a feature module with a botched load order boot degraded instead of crashing. The trace names the exact id, so the bug announces itself instead of showing up as "huh, the button does nothing."
- **A missing fx drops only itself.** Read this one twice, because people get it backwards. `:rf.error/no-such-fx` does *not* halt the cascade — the handler's `:db` change still applies and the sibling `:fx` entries still fire.
- **A missing coeffect fails loud, before the handler runs.** `:rf.error/unregistered-cofx`. We'll see why this is the strict sibling of the missing-fx case below.

!!! note "No app-policy error hook"

    There is no `reg-event-error-handler`. Observation lives on a [listener](../glossary.md#listener) on the error channel (the `:errors` stream covered at the end of this page); there is no app-policy recovery hook — recovery is a framework-owned typed default, not an app concern. Coming from a v1 app that installed an error hook, the [migration guide](../25-from-re-frame-v1.md) maps the translation.

!!! note "One category, three modes"

    `:rf.error/no-such-handler` covers three [registrar](../glossary.md#registrar) misses, discriminated by a mandatory `:kind` tag: `:kind :event` (the dispatch case above), `:kind :route` (a URL that matched no registered [route](../../routing/glossary.md#route) pattern — see [routing](../../routing/concepts.md)), and `:kind :frame` (a tool surface addressing a frame-id that isn't registered). Filter on the operation keyword alone for a single "registrar miss" view; route on `:kind` when you want per-mode handling.

!!! warning "Gotcha"

    If you guard an [app-db](../glossary.md#app-db) path, an event, or an HTTP `:decode` step with a [schema](../glossary.md#schema), a value that fails it emits `:rf.error/schema-validation-failure` with recovery `:no-recovery` — it halts to surface the bug *early*, where the bad value was produced. The `:where` tag names the boundary that caught it (`:event` / `:app-db` / `:sub-return` / `:fx-args` / `:cofx` / …) and `:explain` carries the Malli explanation. (One boundary is softer: at `:where :flow-output` the record is observational — the flow's value still commits; [Flows](flows.md) explains why.) The surprise is the asymmetry: production builds [*elide*](../glossary.md#elide) the check entirely, so this category fires **only in dev**. Treat schema checks as a development assertion that hardens your data at its source, not as a runtime gate you can lean on in production. (A *malformed registered schema* — a structurally broken Malli form — is the separate `:rf.error/malformed-schema`, which fails closed and rolls the commit back.)

## The failures you'll actually meet

Read each category through the production incident it describes. Here are the three you'll meet first; the rest read the same way, so once you have the rhythm you can find the catalogue row, read the trigger, and check the recovery for any of them.

### A dispatch with no frame in scope

*You wrote a quick `(rf/dispatch [:cart/add-item {:id 7}])` at the REPL, or in a `setTimeout` callback, or in a promise `.then` — and instead of a cascade you got an error.* This is the most common first stumble, and it trips nearly everyone once.

[`dispatch`](../glossary.md#dispatch) and [`subscribe`](../glossary.md#subscribe--derive) resolve their target [frame](../glossary.md#frame) from the surrounding scope, but a deferred callback runs on a fresh stack, long after that scope has unwound. The runtime does not guess a default — [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found). It emits `:rf.error/no-frame-context` (recovery: `:supply-frame`) and dispatches nothing.

The fix is always the same: **carry the frame** across the async gap. In an [effect handler](../glossary.md#effect-handler), the first argument is the same context map the event handler received — capture `:frame` from it at entry and pass it explicitly on the deferred dispatch:

```clojure
;; Capture the frame at fx-handler entry, pass it on every dispatch that fires
;; later — the same idiom managed HTTP uses for its async callbacks. Here a
;; payment SDK confirms the checkout via host success/error callbacks.
(rf/reg-fx :checkout/confirm-payment
  {:platforms #{:client}}
  (fn [frame-ctx args]
    (let [frame (:frame frame-ctx)]
      (.confirm js/window.paymentSdk (:token args)
        (fn [receipt] (rf/dispatch [:checkout/paid {:receipt-id (.-id receipt)}]
                                   {:frame frame}))
        (fn [err]     (rf/dispatch [:checkout/payment-failed {:code (.-code err)}]
                                   {:frame frame}))))))
```

Delete either `{:frame frame}` and that callback's dispatch raises `:rf.error/no-frame-context`. In a [view](../glossary.md#view), you render under the [frame-provider](../glossary.md#frame-provider) — and registered views do this for you, so you rarely think about it. At the REPL or in a test, wrap the work in `with-frame` / `with-new-frame`, or grab a [capture-frame](../glossary.md#capture-frame) to carry across the boundary by value.

### A handler throws

*The user arrived on a deep link that bypassed your init event, so `:cart` was never seeded. The first click walks `update-in` straight into a `nil` and throws.*

```clojure
(rf/reg-event :cart/add-item
  (fn [{:keys [db]} [_ item]]
    {:db (update-in db [:cart :items] conj item)}))    ;; throws when :cart doesn't exist
```

The runtime catches it and emits `:rf.error/handler-exception` with `:recovery :no-recovery`: cascade halted, nothing committed, app-db untouched. The fix is a defensive default at the point of access:

```clojure
(rf/reg-event :cart/add-item
  (fn [{:keys [db]} [_ item]]
    {:db (update-in db [:cart :items] (fnil conj []) item)}))
```

!!! note "Do this once, then you'll trust it"

    Make a handler throw on purpose in dev with [Xray](../glossary.md#xray) open. The error lands *inside the cascade that produced it*. The dispatch that caused it sits right above. The category and recovery read straight off the error row. One click lands on the handler's registration site. Nothing about the failure is out-of-band — and that's the whole pitch.

??? note "Going deeper — the failure is attributed, not lumped"

    `:rf.error/handler-exception` is scoped tight: it means the **event handler body itself** threw. The runtime knows the difference between that and the things standing around it. A throw from a [*coeffect*](../glossary.md#coeffect) supplier during context assembly (a registered `reg-cofx` value-returning supplier whose body raised) emits `:rf.error/coeffect-exception`, attributed to the failing cofx id rather than mis-blamed on the handler. A throw from a *user [interceptor](../glossary.md#interceptor)* you wired into the chain emits `:rf.error/interceptor-exception`, attributed to that interceptor's `:id` and the `:phase` (`:before` or `:after`) it threw in. All three share the recovery — `:no-recovery`, atomic abort, no `:db` install, no `:fx` — and all three halt the same cascade. What differs is the name on the dossier, so when the trace says `:rf.error/coeffect-exception` you go straight to the supplier instead of staring at a handler that never ran. (An `:after`-phase interceptor throw still aborts atomically: under the [deferred-commit](../glossary.md#commit) contract, `:after` runs *before* the `:db` is installed, so a teardown throw discards the whole [effect map](../glossary.md#effect-map) the same as any pre-install failure.)

### A missing fx is not a missing cofx

*You moved an fx-id behind a feature module, the load order shifted, and an event now fires before the fx registers.* The bogus entry emits `:rf.error/no-such-fx`, naming the fx-id and the event that carried it. The rest of the handler's work proceeds: `:db` applies, sibling effects fire. The reason it's safe to drop is that an [effect](../glossary.md#effect) is *output* — dropping one corrupts nothing the handler already computed.

A missing [coeffect](../glossary.md#coeffect) is the stricter sibling, because a cofx is *input*. If a handler's `:rf.cofx/requires` names an id with no `reg-cofx` registration, the framework emits `:rf.error/unregistered-cofx` and [fails loud](../glossary.md#fail-loud-not-silent) — at registration where it can check statically, else at first processing, always *before* the handler runs. Running the handler anyway would mean computing new state from a silently-missing fact, and that's exactly the silent-`nil` coupling the [declared-coeffects model](effects-and-coeffects.md) exists to kill.

??? note "Going deeper — the asymmetry is about data flow"

    Output can be partial without lying: a dropped `:fx` is one less side-effect, and the state the handler computed is still true. Input cannot — a missing cofx means a *fact the handler asked for is absent*, so any state computed from it is fabricated. The runtime treats the two ends of the pipe differently on purpose: best-effort on the output side, fail-closed on the input side. It's the same discipline a total function applies to its domain versus its codomain.

### A subscription throws, or reads one that isn't there

*The same `nil` that crashed your handler can crash a [sub](../glossary.md#subscription) instead — a layer-2 sub maps over `(:items cart)` while `cart` is still `nil`.* The read side has its own two categories, and they're the exact mirror of the event side:

- **A throwing sub computation emits `:rf.error/sub-exception`** (recovery `:replaced-with-default`): the sub returns `nil`, the view sees no value, and the failure is named — not a blank panel with no clue. A `:where` tag (`:reactive` for the hot recompute path, `:compute-sub` for the on-demand path) tells you which resolution path threw. The fix is the same defensive default you'd apply in a handler.
- **A `subscribe` to an unregistered sub-id — or a `:<-` input naming one — emits `:rf.error/no-such-sub`** (recovery `:replaced-with-default`): the unresolved input is substituted with `nil` and the sub's body still runs. This is the read-side twin of `:rf.error/no-such-handler`: a botched load order degrades to a `nil` read with the exact id named in the trace, rather than crashing the render.

The recovery is `:replaced-with-default` for both — a missing or broken *derivation* yields `nil` and renders on, because a view that's missing one value is still a view. That's deliberately gentler than the event side: an `:rf.error/handler-exception` halts its whole cascade (`:no-recovery`), but a sub failure is contained to the one node and its dependents, leaving the rest of the [derivation graph](../glossary.md#the-derivation-graph) intact.

## Test the structure, not the string

Errors are data, so asserting them is as boring as asserting anything else. Register a [listener](../glossary.md#listener) on the [trace stream](../glossary.md#trace-stream) with `rf/register-listener!` ([observability](observability.md) is its full story), do the thing that should fail, filter for the category, then pin the structured fields. This is the same shape the framework's own suite uses:

```clojure
;; Shape adapted from the framework's own error tests.
;; Requires [clojure.test :refer [deftest is testing]] and [re-frame.core :as rf].
(deftest unknown-fx-is-dropped-and-siblings-fire
  (testing "an unknown fx-id traces :rf.error/no-such-fx and the walk continues"
    (let [traces (atom [])
          fired  (atom [])]
      ;; register-listener!'s first arg is the *stream*: :trace is the
      ;; dev tap, :errors the always-on production channel (more below).
      (rf/register-listener! :trace ::collect #(swap! traces conj %))
      (try
        (rf/reg-fx :checkout/analytics
          (fn [_frame-ctx args] (swap! fired conj args)))
        (rf/reg-event :checkout/submit
          (fn [_ _]
            {:fx [[:checkout/never-registered {:order-id 7}]   ;; the typo under test
                  [:checkout/analytics        {:order-id 7}]]}))
        (rf/with-new-frame [_f (rf/make-frame {})]
          (rf/dispatch-sync [:checkout/submit]))
        (finally
          (rf/unregister-listener! :trace ::collect)))

      ;; One bad fx does not poison the walk — the sibling still fired.
      (is (= [{:order-id 7}] @fired))

      ;; Structural assertions: pin the contract, never the prose.
      (let [errors (filter #(= :rf.error/no-such-fx (:operation %)) @traces)]
        (is (= 1 (count errors)))
        (let [t (first errors)]
          (is (= :error (:op-type t)))
          (is (= :checkout/never-registered (get-in t [:tags :rf.fx/id]))))))))
```

Three things in that test generalise to every category:

1. **The assertions are structural.** They pin `:operation`, `:op-type`, and the schema-checked `:tags` keys — the contract. They never touch `:reason`, the human-facing headline sentence, because its wording is allowed to change. String-shaped error tests rot; structural ones don't.
2. **The listener is scoped to the test** and detached in `finally` — and detached with the *same* stream you registered on (`:trace` here), so a failing assertion can't leak it into the next test. `rf/clear-listeners! :trace` is the blunter test-isolation hammer when you'd rather drop every listener on a stream at once; production code never calls it.
3. **It runs on the JVM.** No browser, no DOM — you register, [dispatch-sync](../glossary.md#dispatch-sync), emit, and assert, in milliseconds. [Testing a full cascade](../testing/cascades.md) covers the fixture machinery for suites of these.

The same move covers every category: `dispatch-sync` for event errors, a [sub](../glossary.md#subscription) computation for sub errors, frame setup and teardown for lifecycle errors.

!!! note "One verb, four streams"

    That first argument to `register-listener!` is a stream selector, and it's worth knowing the whole set because it's how the dossier model connects to everything around it. `:trace` is the dev tap you just used — [elided](../glossary.md#elide) out of production builds, the firehose [Xray](../glossary.md#xray) drinks from. `:errors` is the **always-on error channel**: the same structured records, but it survives production, fans across every frame, and is *not* projected under any frame's egress policy. That's the channel the dossier promised earlier — and it's why wiring Sentry in production is just `(rf/register-listener! :errors ::sentry report!)`. Mind one thing: the corpus-wide record arrives *unprojected* (only the event vector is wire-elided), so scrubbing before shipping is on you — the [how-to](../how-to/report-errors-in-production.md) walks the scrub and when to prefer a frame-declared sink instead. The remaining two — `:events` (an always-on per-event integration hook) and `:epoch` (drain-settle [epoch](../glossary.md#epoch) records for time-travel tooling) — are the [observability](observability.md) page's territory. One closed vocabulary; pass an unknown stream and you get a loud `:rf.error/unknown-listener-stream`, no silent default.

## Advanced

### The errors that throw, not trace

Everything so far has been the *traced* dossier — a failure inside a running [cascade](../glossary.md#event-cascade), where the runtime records the error and recovers. But a minority of failures don't ride the trace stream at all: they **throw**. A registration is rejected, an optional artefact is absent, a delegation surface is reached before [`init!`](../glossary.md#init). These surface as an `ex-info` (these are the catalogue's "thrown ex-info" rows — `:rf.error/unregistered-cofx` at registration, `:rf.error/resource-missing-scope-policy`, a `frame-provider` misuse, the removed `inject-cofx`, and the rest of the registration-time family). You meet them at the REPL, in a `try`/`catch`, or as a red boot stack trace — not in Xray's epoch view.

They carry the *same* category vocabulary as the dossier, in a parallel shape, so one consumer path reads both surfaces. The discriminator moves from `:operation` to **`:rf.error/id`**, and it lives in `ex-data`:

```clojure
(try
  (rf/reg-resource :article {} request-fn)        ;; oops — no :scope policy
  (catch #?(:clj Exception :cljs :default) e
    (:rf.error/id (ex-data e))))                   ;; => :rf.error/resource-missing-scope-policy
```

Four slots are guaranteed on every framework throw: **`:rf.error/id`** (the `:rf.error/*` category — the sole machine pivot), **`:where`** (the user-facing fn symbol that threw, e.g. `'rf/reg-resource`), **`:recovery`** (the same vocabulary as the traced dossier, commonly `:fix-registration` here), and **`:reason`** (a one-sentence human description). Surface-specific keys (`:received`, `:resource-id`, `:cycle`, …) merge on top.

Two rules make these safe to branch on:

- **Branch on `:rf.error/id`, never on the message.** `(:rf.error/id (ex-data e))` is the canonical discriminator — `case` / `condp` on it exactly as you'd match `:operation` on a traced record. It's the *same* `:rf.error/<category>` keyword in both surfaces, so a category that can both throw and trace reads one vocabulary either way.
- **The message is for humans, and its wording can change.** `(ex-message e)` leads with an actionable sentence and *trails* a bracketed `[:rf.error/<id>]` token — e.g. `"… require an adapter ns and install it before boot. [:rf.error/no-adapter-installed]"`. That token is greppable from a raw log line, but assert against it with a substring or `thrown-with-msg?` regex, never whole-string equality. The same discipline as `:reason` on the traced side: pin the structure, not the prose.

This is why the page can promise "one vocabulary across every surface": the traced dossier's `:operation` and the thrown error's `:rf.error/id` are the same catalogue keyword, so a tool — or your test — pivots on one closed set whether the failure recovered or aborted.

## Where the boundaries are

This page is the *model* — the record, the taxonomy, the recovery contract. Three adjacent concerns live elsewhere, on purpose:

- **The wire itself** — the [trace stream](../glossary.md#trace-stream) these records ride, the channel split, and how [Xray](../glossary.md#xray) reads the same stream your one-line test listener just did — is [observability](observability.md).
- **Production shipping** to a monitor is [a how-to](../how-to/report-errors-in-production.md).
- **The server boundary** — raw error records must never leak into an HTTP response — is handled by [SSR](../../ssr/concepts.md), which projects them to a sanitised public shape before anything reaches the browser.
