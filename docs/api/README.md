# The re-frame2 API

Use this reference to look up signatures, options, return values and errors
for the public API. Entries are grouped by namespace or feature. To learn how
to build an application, use the [Core guide](../core/introduction.md).

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/init! reagent-adapter/adapter)
```

`re-frame.core`, required as `rf`, supplies events, subscriptions, effects and
frames. Install an adapter at boot; the example uses Reagent. A Fresco app also
requires [`re-frame.fresco`](re-frame.fresco.md) for its view functions and can
install [Fresco's own adapter](re-frame.fresco.substrate.md).

## Which page

Look on [`re-frame.core`](re-frame.core.md) for a registration macro or frame
operation. A feature page holds the full contract for its registrations,
keyword-addressed events and effects, and feature-specific functions. Requiring
the feature namespace loads its runtime; calling a facade macro alone does not.

Choose the API by how the value is used. A value derived only for views usually
needs a [subscription](re-frame.core.md#reg-sub). A derived value that handlers
must read from `app-db` needs a [flow](re-frame.flows.md). Server data that needs
caching, deduplication or invalidation belongs in a
[resource](re-frame.resources.md); a one-off request whose reply an event handles
can use [managed HTTP](re-frame.http.md) directly.

A [machine](re-frame.machines.md) is useful when the events a workflow accepts
depend on its named state. Ordinary event handlers are enough while those
checks are small. View APIs depend on the installed view layer: Reagent views
use `rf/reg-view`, UIx components read with
[`use-sub`](re-frame.adapter.uix.md#use-sub), and Fresco views read with
[`h/sub`](re-frame.fresco.md#sub).

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
  An entry calls such an id always-on where it matters.

"(development builds)" after an id means only a development build checks for
it. Each API entry names the errors relevant to that call and explains its
recovery. The [Errors guide](../core/errors.md) shows how to catch a thrown
error, inspect a reported failure and send production errors to a sink.

## Coverage

Every public var an application can use has an entry here. CI checks the entries
against the project's API manifest: a var the manifest tiers as
application-facing (`:front-porch`, `:advanced`, `:adapter` or `:testing`)
turns the build red when it has no entry. Vars meant only for tooling or the implementation are documented
where a caller needs them, but are not guaranteed an entry.
