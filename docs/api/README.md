# The re-frame2 API

These pages record the exact public API of re-frame2's implementation
(ClojureScript, with `re-frame.ssr.ring` on the JVM): every public function,
macro and var an application uses, one
page per namespace, with its signatures, options, return values and errors. They answer "what
exactly can I call?". To learn how to build with re-frame2, start with the
[Core guide](../core/introduction.md) instead.

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/init! reagent-adapter/adapter)
```

Most application code needs only `re-frame.core`, required as `rf`, plus the
adapter for its view substrate. Everything else is optional and required when
you use it.

## Which page

| When you need to… | Page |
|---|---|
| Register events, subscriptions, effects and views; dispatch; create frames; boot the app | [re-frame.core](re-frame.core.md) |
| Render with Reagent (stock or slim) | [re-frame.adapter.reagent](re-frame.adapter.reagent.md) |
| Render with UIx | [re-frame.adapter.uix](re-frame.adapter.uix.md) |
| Write views with Fresco, re-frame2's own view layer | [re-frame.fresco](re-frame.fresco.md), and [re-frame.fresco.substrate](re-frame.fresco.substrate.md) for its adapter |
| Add a buffered form field, a popover or modal, exit animations, or React-island hooks to a Fresco app | [re-frame.fresco.forms](re-frame.fresco.forms.md), [re-frame.fresco.overlay](re-frame.fresco.overlay.md), [re-frame.fresco.motion](re-frame.fresco.motion.md), [re-frame.fresco.native](re-frame.fresco.native.md) |
| Validate `app-db`, events and effects with Malli schemas | [re-frame.schemas](re-frame.schemas.md) |
| Keep a derived value materialised in `app-db` | [re-frame.flows](re-frame.flows.md) |
| Make HTTP requests with retries, cancellation and decoding handled for you | [Managed HTTP reference](re-frame.http.md) |
| Model a workflow as a state machine | [re-frame.machines](re-frame.machines.md) |
| Map URLs to routes and render links | [re-frame.routing](re-frame.routing.md) |
| Cache server data that views subscribe to, and write it back with mutations | [re-frame.resources](re-frame.resources.md) |
| Render on the server and hydrate on the client | [re-frame.ssr](re-frame.ssr.md), [re-frame.ssr.head](re-frame.ssr.head.md) for the `<head>`, [re-frame.ssr.ring](re-frame.ssr.ring.md) for the Ring handler, [re-frame.ssr.ring.node](re-frame.ssr.ring.node.md) to render the body on a Node sidecar |
| Inspect or rewind a frame's recent history in development | [re-frame.epoch](re-frame.epoch.md) |
| Group the dev trace stream into one record per event | [re-frame.trace.projection](re-frame.trace.projection.md) |
| Emit User-Timing measures in production builds | [re-frame.performance](re-frame.performance.md) |
| Isolate and reset framework state between tests | [re-frame.test-support](re-frame.test-support.md) |
| Walk rendered hiccup in tests | [re-frame.test-helpers](re-frame.test-helpers.md) |

The Fresco pages list each namespace's public vars. The guide's
[Fresco API reference](../core/fresco/api-reference.md) lists every Fresco name
with the chapter that teaches it, and covers the Fresco modules that have no page
here. A Fresco app uses both corpora, because Fresco replaces only the view notation:
events, subscriptions, effects and frames are the same as everywhere else.

## Reading an entry

Each var has an entry headed by its name:

- **Kind**: function, macro, var, component or React hook; for keyword-addressed
  surfaces, effect, event, subscription, machine or interceptor reference.
- **Signature**: every public arity, with its return value.
- **Description**: what the var does, then its rules and edge cases. Longer
  option lists and error lists appear as their own **Options** and **Errors**
  sub-lists; [Errors](#errors) says how to read an error id.
- **Example**: a short, real call, where one is worth showing. Some entries,
  such as compile-time flags, have none.

Events, effects and subscriptions are addressed by keyword, not by var
(`:rf.http/managed`, `[:rf/machine id]`). They are documented as entries or
tables on the page of the namespace that registers them, with a **Payload**
line in place of a signature.

**The facade.** Optional features re-export their registration macros through
`re-frame.core`, so you write `rf/reg-machine`, `rf/reg-flow`, `rf/reg-resource`
and so on. The core page has a short entry for each and links to the feature's
own page, which holds the full contract. The feature's other functions are
called on its own namespace, for example `rf.machines/machine-transition`.

**Framework integration.** Some pages end with vars that exist for adapters,
tools and the test harness rather than for application code. They are grouped
under their own headings, after the application-facing API.

## Errors

An entry's **Errors** name the error ids a caller can meet. The verb says how
each one reaches you.

- **Throws** (or **raises**): the call throws an `ex-info`. Its ex-data carries
  the id under `:rf.error/id`, beside `:where` (the function that threw),
  `:reason` (one sentence) and `:recovery`, and the message ends with the id in
  brackets. Branch on `(:rf.error/id (ex-data e))`, never on the message.
- **Emits** (or **reports**): nothing is thrown. The runtime recovers as the
  entry says and records a trace event whose `:operation` is the id, with
  `:op-type :error`, or `:warning` for a `:rf.warning/*` id. Trace events reach
  [trace listeners](re-frame.core.md#register-listener) and the
  [trace buffer](re-frame.core.md#trace-buffer) in development builds;
  production builds remove them.
- **Always-on**: some reported ids are also delivered in every build,
  production included, as records on the `:errors` stream, which a frame's
  [observability sink](re-frame.core.md#register-observability-sink) ships.
  An entry calls such an id always-on where it matters, and the catalogue
  below marks every one.

"(development builds)" after an id means only a development build checks for
it. [Spec 009's error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue)
is the full list of ids, with each one's payload, default recovery and channel.

## Coverage

Every public var an application can use has an entry here. CI checks the entries
against the project's API manifest: a var the manifest tiers as
application-facing (`:front-porch`, `:advanced`, `:adapter` or `:testing`)
turns the build red when it has no entry. Vars meant only for tooling or the implementation are documented
where a caller needs them, but are not guaranteed an entry.
