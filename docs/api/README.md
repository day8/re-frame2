# The re-frame2 API

These pages record the exact public API of re-frame2's ClojureScript
implementation: every public function, macro and var an application uses, one
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
| Render on the server and hydrate on the client | [re-frame.ssr](re-frame.ssr.md), [re-frame.ssr.head](re-frame.ssr.head.md) for the `<head>`, [re-frame.ssr.ring](re-frame.ssr.ring.md) for the Ring handler |
| Inspect or rewind a frame's recent history in development | [re-frame.epoch](re-frame.epoch.md) |
| Group the dev trace stream into one record per event | [re-frame.trace.projection](re-frame.trace.projection.md) |
| Emit User-Timing measures in production builds | [re-frame.performance](re-frame.performance.md) |
| Isolate and reset framework state between tests | [re-frame.test-support](re-frame.test-support.md) |
| Walk rendered hiccup in tests | [re-frame.test-helpers](re-frame.test-helpers.md) |

The Fresco pages list each namespace's public vars. The full Fresco authoring
contract lives in the [Fresco API reference](../core/fresco/api-reference.md).
A Fresco app uses both corpora, because Fresco replaces only the view notation:
events, subscriptions, effects and frames are the same as everywhere else.

## Reading an entry

Each var has an entry headed by its name:

- **Kind**: function, macro, var, component or React hook; for keyword-addressed
  surfaces, effect, event, subscription or interceptor reference.
- **Signature**: every public arity, with its return value.
- **Description**: what the var does, then its rules and edge cases. Longer
  option lists and error lists appear as their own sub-lists.
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

## Coverage

Every public var an application can use has an entry here. CI checks the entries
against the project's API manifest: a var the manifest tiers as
application-facing (`:front-porch`, `:advanced`, `:adapter` or `:testing`)
turns the build red when it has no entry. Vars meant only for tooling or the implementation are documented
where a caller needs them, but are not guaranteed an entry.

## Tests

```clojure
(ns my-app.core-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [re-frame.test-helpers :as th]))
```

[re-frame.test-support](re-frame.test-support.md) resets framework state
around each test; [re-frame.test-helpers](re-frame.test-helpers.md) finds
elements in rendered hiccup.
