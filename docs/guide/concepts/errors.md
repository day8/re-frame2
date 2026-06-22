# Errors: dossiers, not log lines

> **Who this is for.** You know [events and the cascade](events-and-the-cascade.md) and [effects and coeffects](effects-and-coeffects.md). Now you want to know what happens when one of them breaks. Two anchors will help: **Sentry's structured events** (an error is a rich record, not a string) and **React error boundaries** (failures land in one designated place). Two things work differently here, though. You instrument nothing — the framework emits the structured record itself, with the causal context already attached. And there's no boundary component to catch and steer. What the runtime does after each kind of error is fixed per category, so your code observes the failure rather than deciding what to do about it.

**The takeaway: that's not a log line — that's a dossier.**

## The dossier

You've caught a hundred errors with `console.error("something broke", e)`. It kept the message and the stack, but it threw away the important half: which event was in flight, which frame owned it, which handler was on the hook, what the cascade had already done. All of that existed in the runtime microseconds before the catch — and then you spent the next hour rebuilding it by hand.

re-frame2's rule is simple: if the runtime knows it, the error record carries it. An event (a dispatched `[:id ...]` vector describing something that happened), a handler (the function that processes it), a frame (one isolated app instance) — all of that context is already in scope when something breaks, so the framework keeps it. Every error it detects becomes a map with a known, stable shape, fat on purpose:

```clojure
{:id        42                              ;; unique trace id
 :op-type   :error                          ;; severity — the universal discriminator
 :operation :rf.error/handler-exception     ;; category — a namespaced keyword
 :recovery  :no-recovery                    ;; what the runtime did next
 :time      1781078400456                   ;; emit time
 :source    :ui                             ;; what triggered the cascade
 :rf.trace/trigger-handler                  ;; the in-scope handler, with its
 {:kind         :event                      ;; registration-site coordinates
  :id           :cart/add-item
  :source-coord {:ns myapp.cart :file "src/myapp/cart.cljs" :line 142}}
 :tags      {:category          :rf.error/handler-exception
             :failing-id        :cart/add-item
             :handler-id        :cart/add-item
             :event             [:cart/add-item {:id 7}]
             :frame             :rf/default
             :reason            "Event handler `:cart/add-item` threw: ..."
             :exception-message "Cannot read properties of undefined (reading 'price')"}}
```

That's not a log line. That's a dossier.

Three fields do the heavy lifting, and once you know them you've learned most of the surface:

- **`:op-type`** is severity. `:error` for genuine failures, `:warning` for misuse the runtime recovers from. So to ask "show me everything that failed", you filter on `:op-type :error` — and you don't need to know a single category name to do it.
- **`:operation`** is the category — a namespaced keyword like `:rf.error/handler-exception` or `:rf.error/no-such-fx`. To narrow down to "show me only handler exceptions", filter on it exactly.
- **`:recovery`** is what the framework did *after* the error. This is the field that tells you whether your app is still standing, which is usually the first thing you want to know.

Two more slots turn a debugging session into a click. `:rf.trace/trigger-handler` carries the file and line where the failing handler was *registered*, and `:rf.trace/call-site` carries the line of the `dispatch` that triggered the cascade. Tools render both as jump-to-source links. Everything else rides under `:tags`, with one fixed, schema-checked payload shape per category, so a consumer always knows what's in the envelope.

> **The dossier is a development surface.** Production builds *eliminate* the trace machinery — not disable it, eliminate it: the code is compiled out of the release bundle, dossiers and all. Errors that must still reach a monitor in production travel a separate always-on channel carrying a tight, privacy-projected record. The channel map is in [observability](observability.md); wiring Sentry to it is [a how-to](../how-to/report-errors-in-production.md).

## A catalogue you consult, not memorise

The framework emits errors from a fixed set of categories — dozens of them, covering handlers, subscriptions, effects, coeffects, schemas, frames, routing, machines, SSR. An effect (an `:fx` request for the runtime to do something in the world) and a coeffect (a fact the runtime reads in *for* a handler) both show up here, as do subscriptions, the reactive queries your views read from. The set grows but never breaks, so you don't memorise the list — you learn its grammar.

The prefix names the owning subsystem. `:rf.error/*` is a genuine failure. `:rf.warning/*` is recoverable misuse. `:rf.fx/*` / `:rf.cofx/*` / `:rf.ssr/*` / `:rf.epoch/*` are substrate events riding the same envelope — a platform-skipped effect, a hydration mismatch. The vocabulary is stable and additive, which means existing categories are never renamed or repurposed. Pin a test to `:rf.error/no-such-fx` and it still means that next year.

The full catalogue — every category, its trigger, its `:tags` payload, its recovery default — is one table in [spec 009](../../../spec/009-Instrumentation.md#error-event-catalogue). Treat it like an HTTP status-code list: look a category up when you meet it, rather than studying it cold.

## Recovery is typed — and it isn't yours

When an error fires, the runtime applies a typed, per-category default. That's the whole story. There's no app-steering error policy here: no global error handler, no hook that swallows an exception or substitutes a result. This is deliberate, and the reasoning is worth sitting with — swallowing an exception masks a bug, and fabricating a replacement result invents something the thrown handler could not have produced.

Genuine recovery belongs at the source, for *expected* failures, where "recovery" actually means something — [managed HTTP's](http.md) `:retry` for a flaky network, or a defensive default at a read. And the framework never re-runs a failing handler behind your back, so when you want to try again, you dispatch a fresh event.

> **Coming from re-frame v1?** `reg-event-error-handler` is gone. Its observability half becomes a listener on the error channel. Its steering half has no successor, by design. The [migration page](../25-from-re-frame-v1.md) maps the translation.

The `:recovery` vocabulary is small and readable on sight: `:no-recovery` (the operation did not complete), `:replaced-with-default`, `:logged-and-skipped` (the offending input dropped, siblings still apply), `:warned-and-replaced`, `:skipped`, `:retried` — plus a few category-specific verbs like `:supply-frame` on the missing-frame error below. The per-category assignments live in [the catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue).

Four of those defaults shape how your app degrades, so they're worth knowing by heart:

- **A throwing event handler halts its cascade with no half-applied state.** `:rf.error/handler-exception` means no `:db` commit and no `:fx`. app-db (your app's single state map) is exactly as it was before the dispatch.
- **A dispatch to an unregistered event id is a traced no-op.** `:rf.error/no-such-handler` lets a feature module with a botched load order boot degraded instead of crashing. The trace names the exact id, so the bug announces itself instead of showing up as "huh, the button does nothing."
- **A missing fx drops only itself.** Read this one twice, because people get it backwards. `:rf.error/no-such-fx` does *not* halt the cascade — the handler's `:db` change still applies and the sibling `:fx` entries still fire.
- **A missing coeffect fails loud, before the handler runs.** `:rf.error/unregistered-cofx`. The asymmetry between this and the missing-fx case is explained below.

## The failures you'll actually meet

Read each category through the production incident it describes. Here are the three you'll meet first; the rest read the same way, so once you have the rhythm you can find the catalogue row, read the trigger, and check the recovery for any of them.

### A dispatch with no frame in scope

*You wrote a quick `(rf/dispatch [:counter/inc])` at the REPL, or in a `setTimeout` callback, or in a promise `.then` — and instead of a cascade you got an error.* This is the most common first stumble, and it trips nearly everyone once. `dispatch` and `subscribe` resolve their target [frame](frames.md) from the surrounding scope, but a deferred callback runs on a fresh stack, long after that scope has unwound. The runtime does not guess a default. It emits `:rf.error/no-frame-context` (recovery: `:supply-frame`) and dispatches nothing.

The fix is always the same: **carry the frame** across the async gap. In an effect handler, capture it from the context at entry and pass it explicitly on the deferred dispatch:

```clojure
;; Same idiom as the deferred abort reply in
;; examples/reagent/managed_http_counter (core.cljs): capture the frame
;; at fx-handler entry, pass it on every dispatch that fires later.
(rf/reg-fx :geo/locate
  {:platforms #{:client}}
  (fn [frame-ctx _]
    (let [frame (:frame frame-ctx)]
      (.getCurrentPosition (.-geolocation js/navigator)
        (fn [pos] (rf/dispatch [:geo/located {:lat (.. pos -coords -latitude)
                                              :lng (.. pos -coords -longitude)}]
                               {:frame frame}))
        (fn [err] (rf/dispatch [:geo/failed {:code (.-code err)}]
                               {:frame frame}))))))
```

Delete either `{:frame frame}` and that callback's dispatch raises `:rf.error/no-frame-context`. In a view, you render under the frame provider — and registered views do this for you, so you rarely think about it. At the REPL or in a test, wrap the work in `with-frame` / `with-new-frame`.

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

> **Do this once, then you'll trust it.** Make a handler throw on purpose in dev with Xray open. The error lands *inside the cascade that produced it*. The dispatch that caused it sits right above. The category and recovery read straight off the error row. One click lands on the handler's registration site. Nothing about the failure is out-of-band — and that's the whole pitch.

### A missing fx is not a missing cofx

*You moved an fx-id behind a feature module, the load order shifted, and an event now fires before the fx registers.* The bogus entry emits `:rf.error/no-such-fx`, naming the fx-id and the event that carried it. The rest of the handler's work proceeds: `:db` applies, sibling effects fire. The reason it's safe to drop is that an fx is *output* — dropping one corrupts nothing the handler already computed.

A missing coeffect is the stricter sibling, because a cofx is *input*. If a handler's `:rf.cofx/requires` names an id with no `reg-cofx` registration, the framework emits `:rf.error/unregistered-cofx` and fails loud — at registration where it can check statically, else at first processing, always *before* the handler runs. Running the handler anyway would mean computing new state from a silently-missing fact, and that's exactly the silent-`nil` coupling the [declared-coeffects model](effects-and-coeffects.md) exists to kill.

## Test the structure, not the string

Errors are data, so asserting them is as boring as asserting anything else. Collect trace events with `rf/register-listener!` (the dev trace tap — [observability](observability.md) is its full story), do the thing that should fail, filter for the category, then pin the structured fields. This is the same shape the framework's own suite uses:

```clojure
;; Shape adapted from the framework's own error tests
;; (implementation/core/test/re_frame/fx_test.clj).
;; Requires [clojure.test :refer [deftest is testing]] and [re-frame.core :as rf].
(deftest unknown-fx-is-dropped-and-siblings-fire
  (testing "an unknown fx-id traces :rf.error/no-such-fx and the walk continues"
    (let [traces (atom [])
          fired  (atom [])]
      (rf/register-listener! ::collect #(swap! traces conj %))
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
          (rf/unregister-listener! ::collect)))

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
2. **The listener is scoped to the test** and detached in `finally`, so a failing assertion can't leak it into the next test.
3. **It runs on the JVM.** No browser, no DOM — you register, dispatch, emit, and assert, in milliseconds. [Testing a full cascade](../how-to/test-a-cascade.md) covers the fixture machinery for suites of these.

The same move covers every category: `dispatch-sync` for event errors, a sub computation for sub errors, frame setup and teardown for lifecycle errors.

## Where the boundaries are

This page is the *model* — the record, the taxonomy, the recovery contract. Three adjacent concerns live elsewhere, on purpose. **The wire itself** — the trace stream these records ride, the channel split, and how Xray reads the same stream your one-line test listener just did — is [observability](observability.md). **Production shipping** to a monitor is [a how-to](../how-to/report-errors-in-production.md). **The server boundary** — raw error records must never leak into an HTTP response — is handled by [SSR](ssr.md), which projects them to a sanitised public shape before anything reaches the browser.

---

**You can now:**

- Read any re-frame2 error record: severity from `:op-type`, category from `:operation`, what happened next from `:recovery` — and jump to the handler that caused it.
- Predict how your app degrades: a throwing handler halts its cascade with no half-applied state, a missing fx drops only itself, a missing cofx fails loud before the handler runs.
- Fix the most common first error, `:rf.error/no-frame-context`, by carrying the frame into deferred callbacks.
- Write an error test that pins the structured contract and survives reworded messages.
