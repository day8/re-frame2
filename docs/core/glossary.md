# Glossary

re-frame2 terms, one entry each: the definition first, a short example where the
spelling matters, and a link to the page that teaches it.

## The Nouns

### **adapter**

The value that connects re-frame2 to a view layer (the [substrate](#substrate)). It
is a map of functions, not the library itself. Fresco's is
`re-frame.fresco.substrate/adapter`; Reagent, UIx and reagent-slim each ship one.
Install it once at boot with [`init!`](#init):

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/init! reagent-adapter/adapter)
```

To switch view layer, require a different adapter. Events, subscriptions and app-db
stay the same.

Related: [Views](views.md), [Fresco installation](fresco/00-installation.md),
[Reagent adapter](../api/re-frame.adapter.reagent.md).

### **app-db**

The single immutable map of application state, one per [frame](#frame). You choose
its shape. An [event handler](#event-handler) returns a new app-db, the runtime
commits it, and [subscriptions](#subscription) derive what [views](#view) show from
it.

```clojure
{:todos   {1 {:id 1 :title "Buy milk" :done? false}}
 :showing :all}
```

Framework state lives beside it in [runtime-db](#runtime-db); see
[the two partitions](#the-two-partitions).

Related: [app-db](app-db.md), [Validate with schemas](how-to/validate-with-schemas.md).

### **path**

A vector of keys into [app-db](#app-db), read and written like `get-in` and
`assoc-in`: `[:todos 1 :done?]`. Schemas, [data classification](#data-classification),
a flow's `:output-path` and the `path` interceptor all address app-db this way.

Related: [app-db](app-db.md).

### **coeffect**

A fact about the world (the time, a fresh id, a stored value) that the runtime hands
to an [event handler](#event-handler) as data, so the handler never reaches out
itself. The handler's first argument is the [world](#world) map: `:db` is always
there, and any other fact is listed under `:rf.cofx/requires`. The clock,
`:rf/time-ms`, is built in:

```clojure
(rf/reg-event :todo/add
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id]
                     {:id id :title title :done? false :created-at time-ms})})))
```

Register other suppliers with [`reg-cofx`](../api/re-frame.core.md#reg-cofx). A fact
that feeds a durable write, as here, must be [recordable](#recordable-vs-ambient-coeffects).

Related: [Coeffects](coeffects.md).

### **effect**

One side effect, described as data: `[effect-id args]`, listed in the `:fx` vector of
an [effect map](#effect-map). The event handler only describes it; the
[effect handler](#effect-handler) registered for that id performs it.

```clojure
[:todo.storage/save todos]
```

Related: [Effects](effects.md).

### **effect handler**

The function registered with `reg-fx` for an effect id. The runtime calls it once per
matching `:fx` entry, with a small context map and that entry's args. Impure work
lives here, so event handlers stay pure.

```clojure
(rf/reg-fx :todo.storage/save
  (fn [_ctx todos]
    (.setItem js/localStorage "todos" (pr-str todos))))
```

Built-ins include `:dispatch`, `:dispatch-later` and `:rf.http/managed`.

Related: [Effects](effects.md).

### **effect map**

What an [event handler](#event-handler) (or a
[machine](../machines/glossary.md#machine) action) returns: a description of the
change. `:db` is the new [app-db](#app-db); `:fx` is a vector of
[effects](#effect):

```clojure
{:db new-db
 :fx [[:rf.http/managed {:request    {:method :post :url "/api/todos"}
                         :on-success [:todo/saved]
                         :on-failure [:todo/save-failed]}]
      [:todo.storage/save todos]]}
```

The top level is closed. Besides `:db` and `:fx` it accepts `:rf.db/runtime` and the
classification keys `:sensitive`, `:large`, `:clear-sensitive` and `:clear-large`.
Any other key fails loud and nothing commits.

Related: [Effects](effects.md).

### **error record**

The structured map the runtime produces when something fails. Its category, an
`:rf.error/*` keyword, is under `:operation` on a traced record, under `:error` on the
record an `:errors` [sink](#sink) receives, and under `:rf.error/id` in `ex-data` when
the framework throws. Branch on the category, never on the human-readable `:reason`.

```clojure
{:op-type   :error
 :operation :rf.error/no-such-fx
 :recovery  :no-recovery
 :tags      {:rf.fx/id :todo.storage/sav :failing-id :todo.storage/sav ,,,}}   ;; a typo'd fx id
```

The full record is on the dev-only [trace stream](#trace-stream). A smaller,
redacted record reaches the frame's `:errors` [sink](#sink) and survives production.

Related: [Errors](errors.md).

### **event**

A data vector saying that something happened. You [dispatch](#dispatch) it, and the
registered [event handler](#event-handler) decides what changes. The first element is
the id; further elements are facts, a single value or a payload map when there are
several:

```clojure
[:inc]
[:todo/add "Buy milk"]
[:todo/toggle 1]
```

Because an event is data, it can be logged, recorded and replayed.

Related: [Events](events.md).

<a id="event-cascade"></a>
### **event pipeline**

The fixed stages one dispatched [event](#event) goes through, in three phases:

- [update phase](#update-phase): run the handler to describe the change
- [commit phase](#commit-phase): apply it, `:db` first, then the other effects
- [render phase](#render-phase): recompute subscriptions and re-render views

Update and commit run once per event ([assemble](#assemble) →
[transform](#transform) → [commit](#commit) → [perform](#perform)). Render runs once
per [render batch](#render-batch) after the queue settles ([derive](#derive) →
[render](#render)).

One pass through the pipeline is a [run](#run); the record it leaves is an
[epoch](#epoch).

Related: [Introduction](introduction.md).

### **run**

One pass through the [event pipeline](#event-pipeline) for one dispatched
[event](#event). One dispatch is one run is one [epoch](#epoch). A
[drain](#drain--run-to-completion) is many runs sharing one render.

Related: [Introduction](introduction.md).

<a id="write-side"></a>
### **update phase**

The first phase of the [event pipeline](#event-pipeline): [assemble](#assemble) the
handler's inputs, then [transform](#transform) them by running the handler. It is
pure and executes nothing, so a throwing handler installs nothing.

Related: [Introduction](introduction.md).

### **commit phase**

The second phase: [commit](#commit) the new app-db, then [perform](#perform) the other
effects in order. The `:db` write always comes first and is atomic; the effects after
it are best-effort.

Related: [Effects](effects.md), [Introduction](introduction.md).

<a id="read-side"></a>
### **render phase**

The last phase: [derive](#derive) changed subscriptions, then [render](#render) the
views that read them. It runs once per [render batch](#render-batch), not once per
event, and only ever sees committed state.

Related: [Subscriptions](subscriptions.md), [Introduction](introduction.md).

### **render batch**

The set of pending recomputes and renders the [render phase](#render-phase) handles
in one go. It closes at the host's next microtask checkpoint, or at an explicit flush
in headless tests. A [drain](#drain--run-to-completion) is never split across
batches, and two drains that finish before the same checkpoint (two back-to-back
`dispatch-sync` calls, say) may share one. In ordinary app code, "one render per
drain" is a good working model.

Related: [Effects: run to completion](effects.md#run-to-completion).

### **world**

The map of facts an [event handler](#event-handler) receives as its first argument:
[app-db](#app-db) under `:db`, plus every [coeffect](#coeffect) it declared. It is
built in the [assemble](#assemble) stage.

```clojure
{:db {…}  :rf/time-ms 1781078400123}
```

Related: [Coeffects](coeffects.md).

### **event envelope**

The runtime's internal wrapper around a dispatched [event](#event): the event vector
plus its target [frame](#frame), origin, tracing ids, per-dispatch options, and the
recorded values of replayable coeffects such as `:rf/time-ms`. Handlers never see it;
they get the event vector and the [world](#world) map. You meet it only in tools and
low-level code.

Related: [Frames](frames.md).

### **event handler**

The pure function registered with `reg-event`. It takes the [world](#world) map
(including `:db`) and the event vector, and returns an
[effect map](#effect-map). It describes changes; it does not perform them.

```clojure
(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))
```

Related: [Events](events.md).

### **flow**

A derived value that re-frame2 keeps written at a path in [app-db](#app-db), so
[event handlers](#event-handler) can read it as plain state. (A
[subscription](#subscription)'s value is for views only.) You declare the input paths,
the output path, and a pure function:

```clojure
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]
   :frame       :app}
  (fn [todos] (count (remove :done? (vals todos)))))
```

A flow belongs to one frame. Flows can also be added and removed at run time through
effects.

Related: [Flows](flows.md).

### **frame**

One running instance of an app: its [app-db](#app-db), an event queue, and caches.
Every frame uses the same registered handlers (unless an [image](#image) narrows
them), each against its own state. Most apps have one frame, `:app`; tests, stories,
SSR requests and tools like [Xray](#xray) use more.

```clojure
[rf/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-app]]
```

Related: [Frames](frames.md).

### **capture-frame**

`(rf/capture-frame)` returns a **frame api**: a map with the current frame's
`:dispatch`, `:dispatch-sync` and `:subscribe`, plus its `:frame` id. Call it while the frame is in
scope and use the result in a later `setTimeout`, promise or WebSocket callback, which
would otherwise raise `:rf.error/no-frame-context`.

Related: [Frames](frames.md#the-async-boundary-capture-the-frame).

### **frame-provider**

A component that makes an existing [frame](#frame) current for a view subtree, so
`dispatch` and `subscribe` inside it reach that frame. It creates and destroys
nothing, and fails loud if the frame does not exist. Use
[frame-root](#frame-root) to create one.

Related: [Frames](frames.md).

### **frame-root**

A component that ensures a named [frame](#frame) exists and makes it current for its
subtree. On first mount it creates the frame and runs `:initial-events`; on later
mounts it reuses the live frame without re-seeding. It does not destroy the frame on
unmount; call `destroy-frame!` for that.

Related: [Frames](frames.md).

### **hiccup**

UI written as Clojure data: `[:div.card "Hi"]` is a `<div class="card">`. A
[view](#view) returns it, and the view layer turns it into React elements (or, on the
server, an HTML string).

```clojure
[:ul (for [todo todos] ^{:key (:id todo)} [:li (:title todo)])]
```

Related: [Hiccup](hiccup.md).

### **image**

The set of registrations a [frame](#frame) uses. Most frames use the **default
image**, every registration that is loaded. Name an image with `rf/image` when frames
need different behaviour: fake effects in a test, two examples that share event ids,
or a tool running beside the app. The image supplies behaviour; the frame supplies
state.

```clojure
(def todos-image
  (rf/image {:select-ns {:include ["app.todos"]}}))

(rf/make-frame {:id :app :images [todos-image]})
```

Related: [Images](images.md).

### **generation**

The frozen table of registrations a frame's [image](#image) resolves into: for each
kind and id, which handler answers. Every frame has one. Calling `make-frame` again
with the same `:id` and new `:images` swaps the frame onto a new generation.

Related: [Images](images.md).

### **interceptor**

A registered wrapper around [event handlers](#event-handler) for cross-cutting work
such as logging or undo. `:before` runs before the handler and can adjust its inputs;
`:after` runs after and can adjust the [effect map](#effect-map). Events opt in by
id:

```clojure
(rf/reg-interceptor :app/logger
  {:before (fn [ctx] ctx)
   :after  (fn [ctx] ctx)})

(rf/reg-event :todo/add
  {:interceptors [:app/logger]}
  (fn [{:keys [db]} [_ title]] {:db db}))
```

Related: [Interceptors](interceptors.md).

### **query vector**

The vector passed to `subscribe`: a sub id plus optional arguments. The whole vector
is the cache key, so equal vectors share one cached value.

```clojure
@(rf/subscribe [:todo/all])
@(rf/subscribe [:todo/by-id 1])
```

Related: [Subscriptions](subscriptions.md).

### **registrar**

The process-wide table that `reg-event`, `reg-sub`, `reg-fx` and the other shared
`reg-*` calls write to, keyed by kind and id. A
frame looks its handlers up in this table, through its [image](#image).

Related: [Images](images.md).

### **registration**

One entry in the [registrar](#registrar): an id mapped to a function or config that
the runtime looks up later. An app's behaviour is the set of registrations it makes.

| Call | Registers |
|---|---|
| `reg-event` | an [event handler](#event-handler) |
| `reg-sub` | a [subscription](#subscription) |
| `reg-fx` | an [effect handler](#effect-handler) |
| `reg-cofx` | a [coeffect](#coeffect) supplier |
| `reg-interceptor` | an [interceptor](#interceptor) |
| `reg-view` / `reg-view*` | a [view](#view) |

`reg-flow`, `reg-app-schema` and `reg-http-interceptor` are different: each
registers into one frame (the one in scope, or `:frame` in its metadata), not the
registrar. Other artefacts add their own registrations: `reg-machine`
([machines](../machines/concepts.md)), `reg-route` (routing), `reg-resource` and
`reg-mutation` ([resources](../api/re-frame.resources.md)), and `reg-head` and
`reg-error-projector` (SSR). A frame is not a registration; you
create one with `make-frame` or `frame-root`.

### **runtime-db**

The framework's half of a [frame](#frame)'s state, beside the [app-db](#app-db) you
own. It holds machine [snapshots](../machines/glossary.md#snapshot), the current
route, [resource](../resources/glossary.md#resource) caches and similar. Read it
through subscriptions and accessors; don't edit its paths directly.

```clojure
[:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow]
```

Related: [the two partitions](#the-two-partitions).

### **schema**

A data description of a value's shape, in Malli by default:
`[:map [:id :int] [:title :string] [:done? :boolean]]`. You attach one to an app-db
path (`reg-app-schema`), an event, or an HTTP `:decode` step. App-db and ordinary
event checks run only in dev; a `:boundary? true` event schema, a recordable
coeffect's schema, a declared route's shape, a managed-HTTP `:decode` schema and the
reserved `:rf.server/*` effects' arguments are checked in every build.

Related: [Validate with schemas](how-to/validate-with-schemas.md),
[Errors](errors.md#schema-validation-failures).

### **subscription**

A registered, cached query that a [view](#view) reads. It either reads
[app-db](#app-db) directly or derives from other subscriptions, and recomputes only
when its inputs change by `=`.

```clojure
(rf/reg-sub :todo/remaining-count {:inputs [[:todo/all]]}
  (fn [[todos] _] (count (remove :done? todos))))
```

Casual "sub" is fine. To use a derived value inside an event handler, use a
[flow](#flow).

Related: [Subscriptions](subscriptions.md).

### **substrate**

The view layer that renders to React: Fresco (re-frame2's own), Reagent, UIx or
reagent-slim. An [adapter](#adapter) connects re-frame2 to it. Events, subscriptions
and app-db are the same on every substrate; only how views are written differs.

Related: [Fresco](fresco/index.md), [Use UIx or slim](how-to/use-uix-or-slim.md).

### **view**

A function from [subscription](#subscription) values to [hiccup](#hiccup). It reads
state and [dispatches](#dispatch) events; it holds no business logic. It re-renders
when a subscription it read changes. With the Reagent-family adapters a view is a
`reg-view`, which provides a frame-bound `subscribe` and `dispatch`:

```clojure
(rf/reg-view todo-footer []
  [:p @(subscribe [:todo/remaining-count]) " left to do"])
```

In Fresco a view is an `h/defview` that reads with `h/sub`:

```clojure
;; (:require [re-frame.fresco :as h])
(h/defview todo-footer [_]
  [:p (h/sub [:todo/remaining-count]) " left to do"])
```

Related: [Views](views.md), [Fresco](fresco/01-getting-started.md).

## The Verbs

The six pipeline stages, in order: [assemble](#assemble) and
[transform](#transform) (update phase), [commit](#commit) and [perform](#perform)
(commit phase), [derive](#derive) and [render](#render) (render phase).

### **assemble**

Build the [world](#world) the [event handler](#event-handler) will receive:
[app-db](#app-db) under `:db` plus every fact listed in `:rf.cofx/requires`.

Related: [Coeffects](coeffects.md).

### **transform**

Run the pure [event handler](#event-handler): world and [event](#event) in,
[effect map](#effect-map) out. Nothing is executed yet.

Related: [Events](events.md).

### **commit**

Write the new [app-db](#app-db), once and atomically. It is the first step of the
[commit phase](#commit-phase). Everything before it can be abandoned: a throwing
handler or flow installs nothing. Everything after it is best-effort.

Related: [Introduction](introduction.md).

### **perform**

Run the `:fx` entries, in order, after the [commit](#commit), each through its
[effect handler](#effect-handler). This is the only stage that touches the outside
world. A throwing effect does not undo the commit.

Related: [Effects](effects.md).

### **derive**

Recompute the [subscriptions](#subscription) whose inputs changed in the committed
app-db. A result equal by `=` to the previous one stops recomputation downstream. The
public call is [`subscribe`](#subscribe--derive); "derive" names the stage.

Related: [Subscriptions](subscriptions.md).

### **render**

Re-run the [views](#view) that read a changed subscription, producing new
[hiccup](#hiccup); React then patches the DOM. It happens once per
[render batch](#render-batch), so the screen never shows intermediate values.

Related: [Views](views.md).

### **dispatch**

Put an [event](#event) on a [frame](#frame)'s queue. `dispatch` returns immediately;
the handler runs shortly after.

```clojure
(rf/dispatch [:todo/toggle 1])
```

Inside a `reg-view`, use the injected `dispatch`, which already knows its frame.

Related: [Events](events.md).

### **dispatch-sync**

Like [`dispatch`](#dispatch), but processes the event and drains the whole queue
before returning. Use it at boot, in tests and at the REPL, never from inside a
running handler (`:rf.error/dispatch-sync-in-handler`).

```clojure
(rf/dispatch-sync [:todo/initialise])   ;; app-db is committed before the next line
```

Related: [Run to completion](run-to-completion.md).

### **drain / run-to-completion**

Processing every queued [event](#event) (update and commit for each) before the
[render phase](#render-phase) runs once for all of them, so the UI updates once from
settled state. A drain stops early if it hits the re-entrancy depth limit or its frame
is destroyed.

Related: [Effects: run to completion](effects.md#run-to-completion),
[Run to completion (detail)](run-to-completion.md).

### **elide**

Compile dev-only code out of a production build, controlled by one flag (`goog.DEBUG`
in ClojureScript, `-Dre-frame.debug` on the JVM). It removes the
[trace stream](#trace-stream), the [epoch](#epoch) history and the schema checks you
declared. The always-on error and handled-event records survive, and so do the
framework's own boundary checks (a `:boundary? true` event schema, a recordable
coeffect's schema, a declared route's shape, a managed-HTTP `:decode` schema and the
reserved `:rf.server/*` effects' arguments).

Related: [Observability](observability.md#in-production-builds),
[Configure dev and production builds](how-to/configure-dev-and-prod.md).

### **init!**

The boot call that installs an [adapter](#adapter): `(rf/init! reagent-adapter/adapter)`.
Calling it again with the same adapter does nothing; a different adapter throws
`:rf.error/adapter-already-installed`. It does not create a frame.

Related: [Boot and mount an app](how-to/boot-and-mount-an-app.md).

### **project (egress)**

Redact a value under a frame's [data classification](#data-classification) before it
leaves the app, with `project-egress`. Reads inside the app are never projected.

```clojure
(rf/project-egress value {:frame :app :path [:auth]})
```

Related: [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### **register**

Add a [registration](#registration) with a `reg-*` call.

```clojure
(rf/reg-event :todo/clear-done
  (fn [{:keys [db]} _]
    {:db (update db :todos #(into {} (remove (comp :done? val)) %))}))
```

There is one `reg-event`; v1's `reg-event-db`, `reg-event-fx` and `reg-event-ctx`
do not exist.

Related: [Events](events.md).

### **subscribe / derive**

Read a [subscription](#subscription) by its [query vector](#query-vector).
Dereferencing the result in a view gives the current value and re-renders the view
when it changes.

```clojure
@(rf/subscribe [:todo/visible])
```

Related: [Subscriptions](subscriptions.md).

## The Concepts

### **Effects are data**

An [event handler](#event-handler) returns a description of its side effects, and the
runtime performs them. Pure handlers with data effects can be replayed, tested and
traced.

```clojure
{:fx [[:todo.storage/save todos]
      [:dispatch [:todo/set-showing :all]]]}
```

Related: [Effects](effects.md).

### **Fail loud, not silent**

When the runtime cannot do what was asked (an unregistered id, a missing coeffect, an
unknown effect), it emits a structured [error record](#error-record) naming the
problem, even when it carries on (a missing fx is dropped, a missing sub reads `nil`).
Nothing fails silently. Fail-loud (report instead of swallow) is different from
fail-closed (deny by default at a boundary).

Related: [Errors](errors.md).

### **Frame identity is carried, not found**

Every operation gets its [frame](#frame) from the surrounding scope: a frame-root or
provider, the running handler, or a captured frame. The runtime never falls back to a
default, so a call with no frame in scope raises `:rf.error/no-frame-context`.

Related: [Frames](frames.md).

### **The four homes (where state lives)**

The places derived and async state can live, cheapest first:
[subscription](#subscription), [flow](#flow),
[resource](../resources/glossary.md#resource),
[machine](../machines/glossary.md#machine). Pick the cheapest that fits.

Related: [Where should this value live?](where-state-lives.md)

### **The two partitions**

A [frame](#frame)'s state has two parts: [app-db](#app-db), which you own, and
[runtime-db](#runtime-db), which the framework owns. Their paths are `:rf.db/app`
and `:rf.db/runtime`, with subsystems under `:rf.runtime/*`.

Related: [app-db](app-db.md).

### **The uniform reply**

Every managed async operation (HTTP, resources, mutations, route loaders, machine
async) finishes by [dispatching](#dispatch) your event with one reply map, keyed by
`:status`: `:ok` (value at `:value`), `:partial` (both `:value` and `:error`),
`:error` (failure at `:error`) or `:cancelled`. A fifth status, `:stale`, marks a
reply whose request went obsolete; it is traced and never dispatched. This is different from a
resource read's `:status` (`:idle`, `:loading`, `:fetching`, `:loaded`, `:error`).

```clojure
[:todo/fetched {:status :ok :value [{:id 1 :title "Buy milk" :done? false}]}]
```

Related: [Managed HTTP](../async/http.md).

### **The derivation graph**

The graph of pure derivations that starts at [app-db](#app-db) and ends at
[views](#view). [Subscriptions](#subscription), [flows](#flow), resource reads, route
facts and machine selectors are its nodes. The runtime recomputes only along edges
whose value changed by `=`.

Related: [Subscriptions](subscriptions.md),
[One graph](derivations-and-algebra-views.md).

### **Data classification**

Marking an [app-db](#app-db) path `:sensitive` or `:large`, so the runtime replaces
its value with a redaction or size marker wherever it leaves the app (traces,
[Xray](#xray), SSR payloads, off-box logs). Rendering in the app still sees the real
value. It is hygiene at the boundary, not a security control.

Related: [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### **Recordable vs ambient coeffects**

A *recordable* [coeffect](#coeffect) (the clock, a fresh id) is captured when the
event is dispatched, so a replay sees the same value. An *ambient* one is read live
and not recorded: fine for a display hint, never for a durable write.

```clojure
{:rf.cofx/requires [:rf/time-ms]}   ;; recordable
```

Related: [Coeffects](coeffects.md).

## Observability

The trace stream and [epoch](#epoch) history are dev-only (see [elide](#elide)). In
production, error and handled-event records reach `:observability` [sinks](#sink).

### **trace stream**

The in-process feed of [trace events](#trace-event) the runtime emits as each event
runs: dispatch, handler, subscription recompute, effect, render. [Xray](#xray), Story
and the pair MCP read it, and so can your own [listener](#listener). Dev-only.

Related: [Observability](observability.md).

### **trace event**

One map on the [trace stream](#trace-stream), with `:op-type` (the family),
`:operation` (what happened), `:time` and `:tags`. Every trace event from one run has
the same `:rf.trace/dispatch-id`.

Related: [Observability](observability.md#the-trace-stream).

### **listener**

A callback registered with `register-listener!` on the `:trace` or `:epoch` stream.
Both streams are dev-only; use a [sink](#sink) in production. Listeners see classified
paths redacted and nothing more, so [project](#project-egress) anything you send
off-box.

Related: [Observability](observability.md#write-a-listener).

### **sink**

A function registered with `register-observability-sink!` and named in a frame's
`:observability` config (or once for the process with `configure!`). The `:errors`
stream delivers [error records](#error-record); `:handled-events` delivers one
record per handled event. Sinks work in production, and every record arrives already
redacted under the frame's [data classification](#data-classification).

```clojure
(rf/configure! {:observability {:errors [{:sink :app/sentry}]}})
(rf/register-observability-sink! :app/sentry (fn [record] (send-to-sentry! record)))
```

Related: [Report errors in production](how-to/report-errors-in-production.md).

### **epoch**

The record one [run](#run) leaves: the event, [app-db](#app-db) before and after, and
the run's [trace events](#trace-event). [Xray](#xray) steps through and rewinds
epochs. Dev-only.

Related: [Observability](observability.md#the-epoch-history-what-the-app-was).

### **time-travel**

Restoring a [frame](#frame) to the state it held after an earlier [epoch](#epoch),
with `restore-epoch!`. Both partitions are restored in one write, and no handlers
re-run.

Related: [Observability](observability.md#the-epoch-history-what-the-app-was).

### **Xray**

The dev inspector: an in-app panel over the [trace stream](#trace-stream) and each
frame's [epoch](#epoch) history. It shows what each event did, app-db diffs, and
supports time travel.

Related: [the Xray docs](../xray/index.md).

### **Story**

A view workbench: it renders a [view](#view)'s loading, empty, error and happy states
as named variants, each in its own [frame](#frame), and turns good examples into
tests.

Related: [the Story docs](../story/index.md).
