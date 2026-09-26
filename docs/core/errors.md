# Errors

When a handler throws and you log it with `console.error("something broke", e)`, you
keep the message and the stack. At the moment of the throw the runtime also knew
which [event](glossary.md#event) was in flight, which [frame](glossary.md#frame)
owned it, which [handler](glossary.md#event-handler) was running, and what the
[pipeline run](glossary.md#run) had already done. re-frame2 keeps all of it: every
error the framework detects becomes a structured
[error record](glossary.md#error-record) with that context attached.

This page shows how to read the record, how your app degrades after each kind of
failure, how to fix the error you will hit first, and how to test errors by their
structure. Shipping errors from production is covered in
[Report errors in production](how-to/report-errors-in-production.md).

## The error record

Here is the record for a handler that threw:

```clojure
{:id        42                              ;; unique trace id
 :op-type   :error                          ;; severity
 :operation :rf.error/handler-exception     ;; category — a namespaced keyword
 :recovery  :no-recovery                    ;; what the runtime did next
 :time      1781078400456                   ;; emit time
 :rf.trace/trigger-handler                  ;; the handler that was running,
 {:kind         :event                      ;; with its registration site
  :id           :cart/add-item
  :source-coord {:ns myapp.cart :file "src/myapp/cart.cljs" :line 142}}
 :rf.trace/call-site                        ;; where the `dispatch` was written
 {:file "src/myapp/cart_view.cljs" :line 88}
 :tags      {:category          :rf.error/handler-exception
             :failing-id        :cart/add-item
             :handler-id        :cart/add-item
             :event-id          :cart/add-item
             :event             [:cart/add-item {:id 7}]
             :frame             :app/main
             :phase             :before                  ;; the chain phase that threw
             :reason            "Event handler threw."
             :exception-message "Cannot read properties of undefined (reading 'price')"}}
```

Three fields do most of the work:

- **`:op-type`** is severity: `:error` for genuine failures, `:warning` for misuse the
  runtime recovers from. To see everything that failed, filter on `:op-type :error`.
- **`:operation`** is the category, a namespaced keyword such as
  `:rf.error/handler-exception` or `:rf.error/no-such-fx`. Match it exactly to narrow.
- **`:recovery`** is what the framework did *after* the error, which tells you whether
  the app is still standing.

`:rf.trace/trigger-handler` carries the file and line where the failing handler was
*registered*; `:rf.trace/call-site` carries the line of the
[`dispatch`](glossary.md#dispatch) that started the run. Tools render both as
jump-to-source links. Everything else is under `:tags`, which has one fixed,
schema-checked shape per category. `:category` repeats `:operation` so the payload
stands alone, and `:failing-id` names the culprit — here the handler itself, which is
why it equals `:handler-id`.

??? info "Coming from Sentry + React error boundaries?"

    Like a Sentry event, an error is a rich record rather than a string. Unlike Sentry,
    you instrument nothing: the framework emits the record itself, with the causal
    context attached. And unlike an error boundary, there is no component that catches
    and steers. What the runtime does after each kind of error is fixed per category,
    so your code observes the failure rather than deciding what to do about it.

??? note "Going deeper — it's a tagged union"

    The record is a sum type keyed on `:operation`, where each variant carries a known
    `:tags` payload. A consumer can pattern-match on `:operation` and trust the shape
    that follows; the catalogue is the type definition. `:op-type` then `:operation` is
    a coarse-then-fine discriminator — severity for "did anything fail?", category for
    per-variant handling.

The record above comes from the [trace stream](glossary.md#trace-stream), which
production builds [elide](glossary.md#elide): the code that builds it is compiled out
of the release bundle. Errors that must reach a monitor in production travel a
separate always-on channel with a tighter record, covered at the end of
[Test the structure, not the string](#test-the-structure-not-the-string).

## Categories

There are dozens of categories, covering handlers, subscriptions, effects, coeffects,
schemas, frames, routing, machines and SSR. You do not need to memorise them. The
prefix tells you what kind of record you are holding:

- `:rf.error/*` — a genuine failure.
- `:rf.warning/*` — recoverable misuse.
- `:rf.fx/*` / `:rf.cofx/*` / `:rf.ssr/*` / `:rf.epoch/*` — lifecycle events from those
  subsystems that use the same record shape (a platform-skipped
  [effect](glossary.md#effect), a
  [hydration mismatch](../ssr/glossary.md#hydration-mismatch)).

Existing categories are never renamed or repurposed: a test pinned to
`:rf.error/no-such-fx` keeps meaning the same thing.

Every record describes itself. It carries `:operation`, repeats it under
`:tags :category`, states what the runtime did in `:recovery`, and gives a
one-sentence `:reason`. Treat categories like HTTP status codes: look one up when you
meet it.

## What the runtime does after an error

When an error fires, the runtime applies a fixed default for that category. There is
no app-level error policy: no global error handler, no hook that swallows an exception
or substitutes a result. Swallowing an exception hides a bug, and a substituted result
is something the handler could not have produced. Recovery from *expected* failures
belongs at the source: [managed HTTP's](../async/http.md) `:retry` for a flaky
network, or a default at the point of a read. The framework never re-runs a failing
handler; to try again, [dispatch](glossary.md#dispatch) a fresh event.

The `:recovery` values:

- `:no-recovery` — the operation did not complete.
- `:replaced-with-default` — an unresolved input is substituted (for example `nil` for
  a missing sub input) and the body runs on.
- `:logged-and-skipped` — the offending input is dropped; siblings still apply.
- `:warned-and-replaced` — two writes conflict over the same target and the last one
  wins; advisory only.
- `:skipped` — a platform-gated effect did not run; not really an error.
- `:fix-registration` — a `reg-*` call was malformed: a typo, a `reg-sub` handed a
  bad argument shape, a [resource](../resources/glossary.md#resource) subscribed before
  it is registered, a `reg-view` missing its args vector.

A few categories carry their own value, such as `:supply-frame` on the missing-frame
error below. The record always states its recovery, so read it from there.

Four defaults decide how your app degrades, and they are worth knowing:

- **A throwing event handler halts its run with nothing applied.**
  `:rf.error/handler-exception` means no [`:db` commit](glossary.md#commit) and no
  `:fx`. [app-db](glossary.md#app-db) is exactly as it was before the dispatch.
- **A dispatch to an unregistered event id does nothing, and says so.**
  `:rf.error/no-such-handler` (recovery `:replaced-with-default`, a do-nothing
  handler) lets a feature module with a broken load order boot degraded instead of
  crashing, and the record names the exact id.
- **A missing fx drops only itself.** `:rf.error/no-such-fx` does *not* halt the run:
  the handler's `:db` change still applies and the sibling `:fx` entries still run.
  This is the one people get backwards.
- **A missing coeffect fails before the handler runs.**
  `:rf.error/unregistered-cofx`. The reason for the difference from missing fx is
  [below](#a-missing-fx-is-not-a-missing-cofx).

!!! note "No error hook"

    There is no `reg-event-error-handler`. To observe errors, give the frame an
    `:observability` `:errors` sink, covered at the end of this page. If a v1 app
    installed an error hook, the [migration guide](25-from-re-frame-v1.md) maps the
    translation.

??? note "One category, three modes"

    `:rf.error/no-such-handler` covers three [registrar](glossary.md#registrar) misses,
    distinguished by a `:kind` tag: `:kind :event` (the dispatch case above),
    `:kind :route` (a URL that matched no registered
    [route](../routing/glossary.md#route) — see [routing](../routing/concepts.md)), and
    `:kind :frame` (a tool addressing a frame id that is not registered). Filter on the
    category alone for every registrar miss; branch on `:kind` for per-mode handling.

## Common failures

These are the four you will meet first. The rest read the same way.

### A dispatch with no frame in scope

*You wrote `(rf/dispatch [:cart/add-item {:id 7}])` at the REPL, in a `setTimeout`
callback, in a promise `.then`, or in a button's `:on-click`, and got an error instead
of a run.* Nearly everyone hits this once.

[`dispatch`](glossary.md#dispatch) and
[`subscribe`](glossary.md#subscribe--derive) resolve their
[frame](glossary.md#frame) from the surrounding scope, and a deferred callback runs on
a fresh stack after that scope has unwound. The runtime does not guess a default
([frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found)).
It emits `:rf.error/no-frame-context` (recovery `:supply-frame`) and throws, so
nothing is dispatched.

The fix is to carry the frame across the async gap. In an effect handler, read
`:frame` on entry and pass `{:frame frame}` in the deferred dispatch's opts, as
[Effects](effects.md) shows. In app code, `capture-frame` does the same;
[Frames](frames.md#the-async-boundary-capture-the-frame) covers it. At the REPL or in
a test, `with-frame` / `with-new-frame` establish the scope.

A [view](glossary.md#view) is not an exception, and this is where the error is met
most often. A view renders inside the [frame-provider](glossary.md#frame-provider),
but its `:on-click` runs later, after the render has finished, so a fully qualified
`#(rf/dispatch [:cart/add-item])` inside one throws. Use the `dispatch` and
`subscribe` that [`reg-view`](views.md) injects: they capture the frame at render
time. [Views](views.md#the-trap-a-callback-that-fires-after-render-has-no-frame) has
the wrong and right versions side by side.

### A handler throws

*The user arrived on a deep link that skipped your init event, so `:cart` was never
seeded. The first click calls `update-in` on a `nil` and throws.*

```clojure
(rf/reg-event :cart/add-item
  (fn [{:keys [db]} [_ item]]
    {:db (update-in db [:cart :items] conj item)}))    ;; throws when :cart doesn't exist
```

The runtime catches it and emits `:rf.error/handler-exception` with
`:recovery :no-recovery`: run halted, nothing committed, app-db untouched. Fix it with
a default at the point of access:

```clojure
(rf/reg-event :cart/add-item
  (fn [{:keys [db]} [_ item]]
    {:db (update-in db [:cart :items] (fnil conj []) item)}))
```

Try making a handler throw on purpose in dev with [Xray](glossary.md#xray) open. The
error appears inside the run that produced it, below the dispatch that caused it,
with its category and recovery on the row and a link to the handler's registration
site.

??? note "Going deeper — which component threw"

    `:rf.error/handler-exception` means the **event handler body** threw. A throw from
    a [coeffect](glossary.md#coeffect) supplier while the handler's inputs are
    assembled emits `:rf.error/coeffect-exception`, attributed to the cofx id. A throw
    from a user [interceptor](glossary.md#interceptor) emits
    `:rf.error/interceptor-exception`, attributed to the interceptor's `:id` and the
    `:phase` (`:before` or `:after`) it threw in. All three have the same recovery —
    `:no-recovery`, no `:db` install, no `:fx` — and halt the same run. The different
    name sends you to the supplier or interceptor instead of a handler that never ran.
    An `:after`-phase throw also aborts the whole run: `:after` runs *before* the `:db`
    is [committed](glossary.md#commit), so the throw discards the whole
    [effect map](glossary.md#effect-map).

### A missing fx is not a missing cofx

*You moved an fx-id into a feature module, the load order shifted, and an event now
runs before the fx is registered.* The unknown entry emits `:rf.error/no-such-fx`,
naming the fx-id (the `:rf.fx/id` tag) and the event that returned it. The rest of the
handler's work proceeds: `:db` applies and sibling effects run. Dropping it is safe
because an [effect](glossary.md#effect) is output; losing one does not invalidate
anything the handler computed.

A missing [coeffect](glossary.md#coeffect) is stricter, because a cofx is input. If
a handler's `:rf.cofx/requires` names an id with no `reg-cofx` registration, the
framework emits `:rf.error/unregistered-cofx` and
[fails loud](glossary.md#fail-loud-not-silent) — at registration where it can check
statically, otherwise at first processing, and always *before* the handler runs.
Running the handler anyway would compute new state from a missing fact, which is the
silent-`nil` coupling [declared coeffects](coeffects.md) exist to prevent.

### A subscription throws, or reads one that isn't there

*The same `nil` can break a [subscription](glossary.md#subscription) instead: a
layer-2 sub maps over `(:items cart)` while `cart` is still `nil`.* The render phase
has two categories, mirroring the event side:

- **A throwing sub computation emits `:rf.error/sub-exception`** (recovery
  `:replaced-with-default`). The sub returns `nil` and the failure is named. A
  `:where` tag says which path threw: `:reactive` for the normal recompute path,
  `:compute-sub` for an on-demand computation. Fix it with the same default you would
  use in a handler.
- **A `subscribe` to an unregistered sub id, or an `:inputs` entry naming one, emits
  `:rf.error/no-such-sub`** (recovery `:replaced-with-default`). The missing input is
  `nil` and the sub's body still runs. This is the render-side counterpart of
  `:rf.error/no-such-handler`.

In both cases the view still renders with one value missing. That is gentler than the
event side: `:rf.error/handler-exception` halts the whole run, while a sub failure is
contained to that node and its dependents in the
[derivation graph](glossary.md#the-derivation-graph).

## Test the structure, not the string

Errors are data, so you assert on them like any other data. Register a
[listener](glossary.md#listener) on the [trace stream](glossary.md#trace-stream) with
`rf/register-listener!` ([Observability](observability.md) covers listeners), do the
thing that should fail, filter for the category, and check the structured fields:

```clojure
;; Requires [clojure.test :refer [deftest is testing]] and [re-frame.core :as rf].
(deftest unknown-fx-is-dropped-and-siblings-fire
  (testing "an unknown fx-id traces :rf.error/no-such-fx and the other fx still run"
    (let [traces (atom [])
          fired  (atom [])]
      ;; The first argument is the stream; :trace is the dev trace stream.
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

      ;; The sibling fx still ran.
      (is (= [{:order-id 7}] @fired))

      ;; Pin the structure, never the prose.
      (let [errors (filter #(= :rf.error/no-such-fx (:operation %)) @traces)]
        (is (= 1 (count errors)))
        (let [t (first errors)]
          (is (= :error (:op-type t)))
          (is (= :checkout/never-registered (get-in t [:tags :rf.fx/id]))))))))
```

Notes:

1. **The assertions are structural.** They check `:operation`, `:op-type` and
   `:tags` keys. They never check `:reason`, whose wording is allowed to change.
2. **The listener is scoped to the test** and removed in `finally`, on the same stream
   it was registered on, so a failing assertion cannot leak it into the next test.
   Clearing every listener at once is a fixture concern: `re-frame.test-support`'s
   reset clears the registries directly, and there is no public bulk-clear function.
3. **It runs on the JVM.** No browser, no DOM: register,
   [dispatch-sync](glossary.md#dispatch-sync), and assert in milliseconds.
   [Test a pipeline run](testing/pipeline-runs.md) covers fixtures for suites of these.

The same approach covers every category: `dispatch-sync` for event errors, a
subscription computation for sub errors, frame setup and teardown for lifecycle
errors.

!!! note "The test tap is not the production route"

    The `:trace` stream is [elided](glossary.md#elide) from production builds. It is
    what [Xray](glossary.md#xray) reads and the right tool inside a test, but it is not
    how you ship errors off-box.

    The always-on error channel survives production, and you reach it through the
    frame's `:observability` policy. Declare `{:observability {:errors [{:sink ::sentry}]}}`
    on the frame and register the sink with `rf/register-observability-sink!`. The
    record arrives already projected under that frame's
    [data classification](glossary.md#data-classification), so sensitive paths are
    redacted before your code sees them. To cover every frame, and records whose frame
    does not resolve at all, declare the same entry once with
    `(rf/configure! {:observability {:errors [{:sink ::sentry}]}})`.
    [Report errors in production](how-to/report-errors-in-production.md) walks through
    it. On the server, [SSR](../ssr/concepts.md) projects error records to a sanitised
    public shape before anything reaches the browser.

    The only other listener stream is `:epoch` ([epoch](glossary.md#epoch) records for
    time-travel tooling), covered in [Observability](observability.md). Passing any
    other stream throws `:rf.error/unknown-listener-stream`.

## Advanced

### The errors that throw, not trace

Everything so far is a traced record: a failure inside a
[pipeline run](glossary.md#run), where the runtime records the error and recovers.
Some failures throw instead. A registration is rejected, an optional feature's
artefact is not on the classpath, an API is called before [`init!`](glossary.md#init).
These surface as an `ex-info`, mostly at registration time. You meet them at the REPL,
in a `try`/`catch`, or as a stack trace at boot, not in Xray's epoch view.

They use the same category vocabulary. The discriminator is **`:rf.error/id`** in
`ex-data`:

```clojure
(try
  (rf/reg-resource :article {} request-fn)        ;; no :scope policy
  (catch #?(:clj Exception :cljs :default) e
    (:rf.error/id (ex-data e))))                   ;; => :rf.error/resource-missing-scope-policy
```

Every framework throw carries four slots: **`:rf.error/id`** (the category),
**`:where`** (the public function that threw, e.g. `'rf/reg-resource` — unlike the
traced `:where`, which names a boundary or code path), **`:recovery`** (the same
vocabulary as traced records, usually `:fix-registration` here), and **`:reason`** (a
one-sentence description). Surface-specific keys (`:received`, `:resource-id`,
`:cycle`, …) are merged on top.

- **Branch on `:rf.error/id`, never on the message.** Use `case` / `condp` on it, as
  you would on `:operation` for a traced record.
- **The message is for humans.** `(ex-message e)` starts with an actionable sentence
  and ends with a bracketed `[:rf.error/<id>]` token, e.g.
  `"… require an adapter ns and install it before boot. [:rf.error/no-adapter-installed]"`.
  The token is greppable in a raw log. In tests, match it with a substring or a
  `thrown-with-msg?` regex, never whole-string equality.

A traced record's `:operation` and a thrown error's `:rf.error/id` come from the same
catalogue, so a tool or a test branches on one set of keywords whether the failure
recovered or aborted.

### Schema validation failures

If you guard an [app-db](glossary.md#app-db) path or an event with a
[schema](glossary.md#schema), a value that fails it emits
`:rf.error/schema-validation-failure`. The recovery depends on the boundary named in
the `:where` tag:

| `:where` | Recovery |
|---|---|
| `:event` | The handler is skipped. |
| `:app-db`, `:machine-data` | The run is rolled back; nothing commits. |
| `:fx-args` | Only the offending fx is skipped; siblings run. |
| `:sub-return`, `:sub-override` | The sub yields `nil` and the view renders on. |
| `:flow-output`, `:machine-output` | Observational only; the value still commits. |

`:explain` carries the Malli explanation. A schema that is itself malformed (a
childless `[:vector]`, an unknown operator) is a separate category,
`:rf.error/malformed-schema`. Malli only discovers it the first time it runs, and from
then on every commit is rolled back until you fix the registration.

**These checks run only in dev.** A production build elides the validators you
declared over your own code, so treat a `reg-app-schema` guard as a development
assertion. In a release build a value that violates it installs silently.

In dev, a rollback also reaches the `:errors` stream, not just the trace: one record
per failing registration (`:app-db` and `:machine-data` rejections, and
`:rf.error/malformed-schema`), with `:rollback? true`, the `:registered-path` (or the
`:machine-id` and `:phase`) and a `:reason`. A failure that only skips one fx or
replaces a sub with `nil` stays on the trace, because nothing was discarded. While no
sink owns these records they are printed to the console, so an empty page with red
`[re-frame2] :rf.error/schema-validation-failure … got nil` lines usually means a
schema registered as non-nilable over a slot nothing has written yet: every
transaction is rejected, so nothing ever installs. Register the path as nilable, or
seed it before the first write. Wiring an `:errors` sink (on the frame or through
`rf/configure!`) takes ownership and the console lines stop.

A sink reading these records must look under `:tags`: use
`(get-in record [:tags :rollback?])`, not `(:rollback? record)`. On this record only
`:error`, `:event-id`, `:frame`, `:time` and `:kind` are top-level; `:rollback?`,
`:where`, `:registered-path`, `:reason` and `:recovery` are under `:tags`.

Three schema checks run in **every** build:

- **An event handler registered with `{:schema … :boundary? true}`**, for untrusted
  input such as an HTTP body, a websocket frame or a query string. A payload that
  fails is rejected in production too: the handler is skipped and the payload never
  reaches app-db. The failure is reported on both always-on streams, as a
  `:rf.error/schema-validation-failure` record tagged `:source :boundary` on the
  `:errors` sink and as `:status :rejected` on the dispatch's `:handled-events`
  record, so it is the one member of this category you can alert on from a release
  build. The production record carries identifiers only (`:error`, `:where`,
  `:source`, `:event-id`, `:failing-id`, `:schema-id`, `:frame`, `:recovery`,
  `:time`) and omits the payload, the offending value, `:explain` and `:reason`,
  because a boundary value can carry secrets under keys the schema never named.
  [Validate with schemas](how-to/validate-with-schemas.md#in-production-what-goes-what-stays)
  has the details.
- **A managed-HTTP `:decode` schema.** This is not this category: a 2xx body that
  fails its schema is classified as `:rf.http/decode-failure` (with
  `:schema-validation-failure? true`) and the request fails.
- **A recordable coeffect's `reg-cofx` `:schema`.** A supplied, replayed or generated
  value that fails it raises `:rf.error/cofx-value-invalid`, which throws
  (`:recovery :no-recovery`) in production as well as dev. Recordable coeffects are
  part of the event's durable record, and a bad one would corrupt replay, SSR and
  [Xray](glossary.md#xray). [Coeffects](coeffects.md) explains why.
