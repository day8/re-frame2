# Errors

When a handler throws and you log it with `console.error("something broke", e)`, you
keep the message and the stack. At the moment of the throw the runtime also knew
which [event](glossary.md#event) was in flight, which [frame](glossary.md#frame)
it ran in, which [handler](glossary.md#event-handler) was running, and what the
[pipeline run](glossary.md#run) had already done. re-frame2 keeps all of it: every
error the framework detects becomes a structured
[error record](glossary.md#error-record) with that context attached.

## The error record

Here is the record for an event handler that threw:

```clojure
{:id        42                              ;; unique trace id
 :op-type   :error                          ;; severity
 :operation :rf.error/handler-exception     ;; category, a namespaced keyword
 :recovery  :no-recovery                    ;; what the runtime did next
 :time      1781078400456                   ;; emit time
 :rf.trace/trigger-handler                  ;; the handler that was running,
 {:kind         :event                      ;; with its registration site
  :id           :todo/add
  :source-coord {:ns app.todos :file "src/app/todos.cljs" :line 42}}
 :rf.trace/call-site                        ;; where the `dispatch` was written
 {:file "src/app/todo_views.cljs" :line 18}
 :tags      {:category          :rf.error/handler-exception
             :failing-id        :todo/add
             :handler-id        :todo/add
             :event-id          :todo/add
             :event             [:todo/add "Buy milk"]
             :frame             :app
             :phase             :before
             :reason            "Event handler threw."
             :exception-message "Invalid arity: 0"}}
```

Three fields do most of the work:

- **`:op-type`** is severity: `:error` for genuine failures, `:warning` for misuse the
  runtime recovers from. Filter on `:op-type :error` to see everything that failed.
- **`:operation`** is the category, such as `:rf.error/handler-exception` or
  `:rf.error/no-such-fx`. Match it exactly to narrow.
- **`:recovery`** is what the framework did after the error.

`:rf.trace/trigger-handler` gives the file and line where the failing handler was
registered, and `:rf.trace/call-site` gives the line of the
[`dispatch`](glossary.md#dispatch) that started the run. Tools render both as
jump-to-source links. Everything else is under `:tags`, which has one fixed,
schema-checked shape per category. `:category` repeats `:operation` so the payload
stands alone, and `:failing-id` names the culprit (here the handler itself, so it
equals `:handler-id`).

??? info "Coming from Sentry + React error boundaries?"

    Like a Sentry event, an error is a rich record rather than a string. Unlike Sentry,
    you instrument nothing: the framework emits the record itself. And unlike an error
    boundary, no component catches and steers. What the runtime does after each kind of
    error is fixed per category, so your code observes the failure rather than deciding
    what to do about it.

This record comes from the [trace stream](glossary.md#trace-stream), which production
builds [elide](glossary.md#elide): the code that builds it is compiled out of the
release bundle. Errors that must reach a monitor in production go through a separate
always-on channel with a smaller record; see
[Errors in production](#errors-in-production).

Xray shows every record. A development build in the browser also prints each error
that no `:errors` sink handled, with `console.error`: `[re-frame2]`, the category and
its reason, then the record. Warnings are never printed; read them
in Xray or with a `:trace` listener ([Observability](observability.md)). On the JVM
and Node nothing is printed.

## Categories

There are dozens of categories, covering handlers, subscriptions, effects, coeffects,
schemas, frames, routing, machines and SSR. You do not need to memorise them. The
prefix tells you what kind of record you are holding:

- `:rf.error/*` is a genuine failure.
- `:rf.warning/*` is recoverable misuse.
- `:rf.fx/*`, `:rf.cofx/*`, `:rf.ssr/*` and `:rf.epoch/*` are lifecycle records from
  those subsystems that use the same shape (a platform-skipped
  [effect](glossary.md#effect), a
  [hydration mismatch](../ssr/glossary.md#hydration-mismatch)).

Existing categories are never renamed or repurposed, so a test pinned to
`:rf.error/no-such-fx` keeps meaning the same thing. Every record also gives a
one-sentence `:reason`. Treat categories like HTTP status codes: look one up in the
[error catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) when you
meet it.

## What the runtime does after an error

Each category has a fixed default. Four of them decide how your app degrades:

- **A throwing event handler halts its run with nothing applied.**
  `:rf.error/handler-exception` means no [`:db` commit](glossary.md#commit) and no
  `:fx`. [app-db](glossary.md#app-db) is exactly as it was before the dispatch.
- **A dispatch to an unregistered event id does nothing, and says so.**
  `:rf.error/no-such-handler` runs a do-nothing handler in its place, so a feature
  module with a broken load order boots degraded instead of crashing, and the record
  names the missing id.
- **A missing fx drops only itself.** `:rf.error/no-such-fx` does not halt the run:
  the handler's `:db` change still applies and the other `:fx` entries still run.
  This is the one people get backwards.
- **A missing coeffect fails before the handler runs.**
  `:rf.error/unregistered-cofx`. The reason for the difference from a missing fx is
  [below](#a-missing-fx-is-not-a-missing-cofx).

There is no app-level error policy: no global error handler, and no hook that
swallows an exception or substitutes a result. Swallowing an exception hides a bug,
and a substituted result is something the handler could not have produced. Recover
from expected failures at the source: [managed HTTP's](../async/http.md) `:retry` for
a flaky network, or a default at the point of a read. The framework never re-runs a
failing handler; to try again, dispatch a fresh event. To observe errors, use an
`:errors` sink ([Errors in production](#errors-in-production)); v1's
`reg-event-error-handler` maps onto one
([From re-frame v1](25-from-re-frame-v1.md#removed-surfaces-interceptors-and-the-test-rename)).

The `:recovery` field names the default that was applied:

| `:recovery` | Meaning |
|---|---|
| `:no-recovery` | The failing step did not complete. For a handler, coeffect or interceptor exception that is the whole run; for a missing or throwing fx, only that effect, and the rest of the run still applies. |
| `:replaced-with-default` | An unresolved input was substituted (for example `nil` for a missing sub input) and the body ran on. |
| `:logged-and-skipped` | The offending input was dropped; its siblings still applied. |
| `:warned-and-replaced` | Two writes conflicted over one target and the last one won. Advisory only. |
| `:skipped` | A platform-gated effect did not run. Not really an error. |
| `:fix-registration` | A `reg-*` call was malformed: a typo, a bad argument shape, a view missing its args vector. |

A few categories have their own value, such as `:supply-frame` on the missing-frame
error below.

??? note "One category, three modes"

    `:rf.error/no-such-handler` covers three [registrar](glossary.md#registrar) misses,
    distinguished by a `:kind` tag: `:kind :event` (the dispatch case above),
    `:kind :route` (a URL that matched no registered
    [route](../routing/glossary.md#route); see [routing](../routing/concepts.md)), and
    `:kind :frame` (a tool addressing a frame id that is not registered). Filter on the
    category for every registrar miss; branch on `:kind` for per-mode handling.

## Common failures

These are the four you will meet first. The rest read the same way.

### A dispatch with no frame in scope

*You wrote `(rf/dispatch [:todo/toggle 1])` at the REPL, in a `setTimeout` callback,
in a promise `.then`, or in a button's `:on-click`, and got an error instead of a
run.* Nearly everyone hits this once.

[`dispatch`](glossary.md#dispatch) and
[`subscribe`](glossary.md#subscribe--derive) find their frame in the surrounding
scope, and a deferred callback runs on a fresh stack after that scope has unwound.
The runtime does not guess a default
([frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found)).
It emits `:rf.error/no-frame-context` (recovery `:supply-frame`) and throws, so
nothing is dispatched.

The fix is to carry the frame across the async gap:

- In an effect handler, read `:frame` from the context argument and pass
  `{:frame frame}` in the deferred dispatch's opts, as [Effects](effects.md) shows.
- In app code, use `capture-frame`;
  [Frames](frames.md#the-async-boundary-capture-the-frame) covers it.
- At the REPL or in a test, `with-frame` and `with-new-frame` establish the scope.

Views are where this is met most often. A [view](glossary.md#view) renders inside the
[frame-provider](glossary.md#frame-provider), but its `:on-click` runs later, after
the render has finished, so `#(rf/dispatch [:todo/toggle id])` inside one throws. Use
the `dispatch` and `subscribe` that [`reg-view`](views.md) injects: they capture the
frame at render time.
[Views](views.md#the-trap-a-callback-that-fires-after-render-has-no-frame) has the
wrong and right versions side by side.

### A handler throws

*Adding the first todo computes the next id with `max` over no keys, and that
throws.*

```clojure
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max (keys (:todos db))))]     ;; throws when :todos is empty
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))
```

The runtime catches the exception and emits `:rf.error/handler-exception` with
`:recovery :no-recovery`: the run halts, nothing is committed, and app-db is
untouched. Fix it with a default at the point of access:

```clojure
(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))
```

Make a handler throw on purpose in dev with [Xray](glossary.md#xray) open. The error
appears inside the run that produced it, below the dispatch that caused it, with its
category and recovery on the row and a link to the handler's registration site.

??? note "Coeffect and interceptor exceptions"

    `:rf.error/handler-exception` means the event handler body threw. A throw from a
    [coeffect](glossary.md#coeffect) supplier while the handler's inputs are assembled
    emits `:rf.error/coeffect-exception`, attributed to the cofx id. A throw from an
    [interceptor](glossary.md#interceptor) emits `:rf.error/interceptor-exception`,
    attributed to the interceptor's `:id` and the `:phase` (`:before` or `:after`) it
    threw in. All three have recovery `:no-recovery` (no `:db`, no `:fx`) and halt the
    run; the different name sends you to the supplier or interceptor instead of a
    handler that never ran. An `:after` throw also discards the handler's result,
    because `:after` runs before the `:db` is [committed](glossary.md#commit).

### A missing fx is not a missing cofx

*You moved an fx into a feature module, the load order shifted, and an event now runs
before the fx is registered.* The unknown entry emits `:rf.error/no-such-fx`, naming
the fx id (the `:rf.fx/id` tag) and the event that returned it. The rest of the
handler's work proceeds: `:db` applies and the other effects run. Dropping it is safe
because an [effect](glossary.md#effect) is output; losing one does not invalidate
anything the handler computed.

A missing [coeffect](glossary.md#coeffect) is stricter, because a cofx is input. If a
handler's `:rf.cofx/requires` names an id with no `reg-cofx` registration, the
framework emits `:rf.error/unregistered-cofx` and halts the run: at registration
where it can check statically, otherwise at first use, and always before the handler
runs. Running the
handler anyway would compute new state from a missing fact, which is what
[declared coeffects](coeffects.md) exist to prevent.

### A subscription throws, or reads one that isn't there

*The same missing data can break a [subscription](glossary.md#subscription) instead:
`:todo/remaining-count` calls a function on a todo that is still `nil`.* The render
phase has two categories, mirroring the event side:

- **A throwing sub computation emits `:rf.error/sub-exception`** (recovery
  `:replaced-with-default`). The sub returns `nil` and the failure is named. Fix it
  with a default, as in a handler.
- **A `subscribe` to an unregistered sub id, or an `:inputs` entry naming one, emits
  `:rf.error/no-such-sub`** (recovery `:replaced-with-default`). The missing input is
  `nil` and the sub's body still runs. This is the render-side counterpart of
  `:rf.error/no-such-handler`.

In both cases the view still renders, with one value missing. That is gentler than
the event side: a handler exception halts the whole run, while a sub failure is
contained to that node and its dependents in the
[derivation graph](glossary.md#the-derivation-graph).

## Errors in production

The record above comes from the `:trace` stream, which production builds
[elide](glossary.md#elide). A separate always-on channel survives. Declare
`{:observability {:errors [{:sink :app/sentry}]}}` on the frame, or once for every
frame with `(rf/configure! {:observability {:errors [{:sink :app/sentry}]}})`, and
register the function with `rf/register-observability-sink!`. The record your sink
receives is smaller than the trace record. Its category is under `:error` rather than
`:operation`, and it arrives already projected under the frame's
[data classification](glossary.md#data-classification), so sensitive paths are
redacted before your code sees them. The process-wide entry also receives records
whose frame does not resolve at all.
[Report errors in production](how-to/report-errors-in-production.md) walks through
it. On the server, [SSR](../ssr/concepts.md) projects error records to a sanitised
public shape before anything reaches the browser.

## Test the structure, not the string

Errors are data, so you assert on them like any other data. Register a
[listener](glossary.md#listener) on the [trace stream](glossary.md#trace-stream) with
`rf/register-listener!` ([Observability](observability.md) covers listeners), do the
thing that should fail, filter for the category, and check the structured fields:

```clojure
;; Requires [clojure.test :refer [deftest is testing]] and [re-frame.core :as rf].
(deftest unknown-fx-is-dropped-and-siblings-fire
  (testing "an unknown fx id traces :rf.error/no-such-fx and the other fx still run"
    (let [traces (atom [])
          saved  (atom [])]
      ;; The first argument is the stream; :trace is the dev trace stream.
      (rf/register-listener! :trace ::collect #(swap! traces conj %))
      (try
        (rf/reg-fx :todo.storage/save
          (fn [_ctx todos] (swap! saved conj todos)))
        (rf/reg-event :todo/clear-done
          (fn [_ _]
            {:fx [[:todo.toast/show "Cleared"]     ;; never registered: the fault under test
                  [:todo.storage/save {}]]}))
        (rf/with-new-frame [_f (rf/make-frame {})]
          (rf/dispatch-sync [:todo/clear-done]))
        (finally
          (rf/unregister-listener! :trace ::collect)))

      ;; The other fx still ran.
      (is (= [{}] @saved))

      ;; Assert on the structure, never the prose.
      (let [errors (filter #(= :rf.error/no-such-fx (:operation %)) @traces)]
        (is (= 1 (count errors)))
        (let [t (first errors)]
          (is (= :error (:op-type t)))
          (is (= :todo.toast/show (get-in t [:tags :rf.fx/id]))))))))
```

Notes:

1. **The assertions are structural.** They check `:operation`, `:op-type` and `:tags`
   keys, never `:reason`, whose wording can change.
2. **The listener is scoped to the test** and removed in `finally`, on the stream it
   was registered on, so a failing assertion cannot leak it into the next test.
   Clearing every listener at once is the [reset fixture](testing/index.md#set-up-the-test-runner)'s
   job, and there is no public bulk-clear function.
3. **It runs on the JVM.** No browser and no DOM: register,
   [dispatch-sync](glossary.md#dispatch-sync), and assert in milliseconds.
   [Test a pipeline run](testing/pipeline-runs.md) covers fixtures for suites of these.

The same approach covers every category: `dispatch-sync` for event errors, a
subscription computation for sub errors, frame setup and teardown for lifecycle
errors.

## Advanced

### The errors that throw, not trace

Everything so far is a traced record: a failure inside a
[pipeline run](glossary.md#run), where the runtime records the error and recovers.
Some failures throw instead: a registration is rejected, an optional feature's
artefact is not on the classpath, or an API is called before
[`init!`](glossary.md#init). These surface as an `ex-info`, mostly at registration
time. You meet them at the REPL, in a `try`/`catch`, or as a stack trace at boot,
not in Xray's epoch view.

They use the same category vocabulary, under **`:rf.error/id`** in `ex-data`:

```clojure
(try
  (rf/reg-resource :todo/list {} fetch-todos)     ;; no :scope policy
  (catch #?(:clj Exception :cljs :default) e
    (:rf.error/id (ex-data e))))                   ;; => :rf.error/resource-missing-scope-policy
```

Every framework throw has four slots:

- **`:rf.error/id`**, the category.
- **`:where`**, the public function that threw, such as `'rf/reg-resource`. (On a
  traced record, `:where` names a code path instead.)
- **`:recovery`**, from the same vocabulary as traced records, usually
  `:fix-registration`.
- **`:reason`**, a one-sentence description.

Surface-specific keys (`:received`, `:resource-id`, `:cycle`, …) are merged on top.

Branch on `:rf.error/id` with `case` or `condp`, never on the message. The message is
for humans: `(ex-message e)` starts with an actionable sentence and ends with a
bracketed `[:rf.error/<id>]` token, e.g.
`"… require an adapter ns and install it before boot. [:rf.error/no-adapter-installed]"`.
The token is greppable in a raw log. In tests, match it with a substring or a
`thrown-with-msg?` regex, never whole-string equality.

Because a traced `:operation`, a sink record's `:error` and a thrown `:rf.error/id`
come from the same catalogue, a tool or test branches on one set of keywords whether
the failure recovered or aborted.

### Schema validation failures

If you guard an [app-db](glossary.md#app-db) path or an event with a
[schema](glossary.md#schema), a value that fails it emits
`:rf.error/schema-validation-failure`. The recovery depends on the boundary named in
the `:where` tag:

| `:where` | Recovery |
|---|---|
| `:event` | The handler is skipped. |
| `:app-db`, `:machine-data` | The run is rolled back; nothing commits. |
| `:fx-args` | Only the offending fx is skipped; the others run. |
| `:sub-return`, `:sub-override` | The sub yields `nil` and the view renders on. |
| `:flow-output`, `:machine-output` | Observational only; the value still commits. |

`:explain` holds the Malli explanation. A schema that is itself malformed (a
childless `[:vector]`, an unknown operator) is a separate category,
`:rf.error/malformed-schema`. Malli only finds it the first time the schema runs, and
from then on every commit is rolled back until you fix the registration.

**These checks run only in dev.** A production build elides the validators you
declared over your own code, so treat a `reg-app-schema` guard as a development
assertion. In a release build a value that violates it installs silently.

In dev, a rollback also reaches the `:errors` stream: one record per failing
registration (`:app-db` and `:machine-data` rejections, and
`:rf.error/malformed-schema`), with `:rollback? true`, the `:registered-path` (or the
`:machine-id` and `:phase`) and a `:reason`. A failure that only skips one fx or
replaces a sub with `nil` stays on the trace, because nothing was discarded.

While no sink handles these records they are printed to the console. An empty page
with red `[re-frame2] :rf.error/schema-validation-failure … got nil` lines usually
means a schema registered as non-nilable over a slot nothing has written yet: every
transaction is rejected, so nothing ever installs. Register the path as nilable, or
seed it before the first write. Wiring an `:errors` sink (on the frame or through
`rf/configure!`) handles the records and the console lines stop.

A sink reading these records must look under `:tags`: use
`(get-in record [:tags :rollback?])`, not `(:rollback? record)`. On this record only
`:error`, `:event-id`, `:frame`, `:time` and `:kind` are top-level; `:rollback?`,
`:where`, `:registered-path`, `:reason` and `:recovery` are under `:tags`.

A few schema checks run in every build. The one in this category is an event handler
registered with `{:schema … :boundary? true}`: a payload that fails is rejected in
production too, and reported as a `:rf.error/schema-validation-failure` record tagged
`:source :boundary` on the `:errors` sink, carrying identifiers only. A managed-HTTP
`:decode` schema reports `:rf.http/decode-failure` instead, and a recordable
coeffect's `:schema` throws `:rf.error/cofx-value-invalid`.
[Validate with schemas](how-to/validate-with-schemas.md#in-production-what-goes-what-stays)
lists them all, with the production record's keys.
