# Fresco glossary

This glossary defines Fresco-specific terms. Core re-frame2 terms such as
[app-db](../glossary.md#app-db),
[frame](../glossary.md#frame),
[event](../glossary.md#event), and
[subscription](../glossary.md#subscription) live in the
[core glossary](../glossary.md).

## Authoring

<a id="fresco"></a>
### Fresco

re-frame2's own React view layer. Fresco interprets
[Hiccup](../glossary.md#hiccup) itself, reads subscriptions with
[`h/sub`](#hsub), and accepts event vectors as [intents](#intent). App-db,
events, effects, and the event pipeline remain ordinary re-frame2.

Require it as:

```clojure
[re-frame.fresco :as h]
```

Forms, overlays, motion, the island hooks, the server renderer, Fresco's own
[adapter](../glossary.md#adapter) (`re-frame.fresco.substrate`) and the test kit
are separate optional namespaces.

Related: [Getting started](01-getting-started.md),
[Installation](00-installation.md).

<a id="defview"></a>
### `defview`

`h/defview` defines a Fresco [view](#view). The view receives one props map and
returns Hiccup, `nil`, a fragment, or a native React element at the direct-return
performance level.

Use a view as a Hiccup head. Do not call it as an ordinary function:

```clojure
(h/defview counter [_]
  [:main
   [:h1 "Count: " (h/sub [:value])]
   [:button {:on-click [:inc]} "+1"]])

[counter {}]   ;; right: a Hiccup head
(counter {})   ;; wrong: a view is not a function to call
```

Related: [Views and reads](02-views-and-reads.md).

<a id="view"></a>
### View

A function from a props map to markup, defined with [`h/defview`](#defview).
In Hiccup head position it creates an independently re-rendering
[boundary](#boundary). A plain `defn` is an [inline helper](#inline-helper), not
a view.

Related: [Views and reads](02-views-and-reads.md).

<a id="boundary"></a>
### Boundary

An independently re-rendering unit created by [`h/defview`](#defview). It:

- tracks the body's [`h/sub`](#hsub) reads;
- compares props with ClojureScript `=`;
- supplies the re-frame2 frame used by event [intents](#intent).

Native tags, fragments, and [`h/defhost`](#defhost) heads do not create Fresco
view boundaries.

Related: [Views and reads](02-views-and-reads.md).

<a id="inline-helper"></a>
### Inline helper

An ordinary function called from a view body. Its returned Hiccup is included
in the caller's tree, and any `h/sub` calls belong to the enclosing
[boundary](#boundary). It does not create independent re-render behaviour.

```clojure
[todo-row {:key id :id id}]   ;; child boundary
(row-icon {:kind :urgent})    ;; inline helper
```

A plain function in Hiccup head position raises
`:rf.error/fresco-bad-head`.

Related: [Views and reads](02-views-and-reads.md).

<a id="hsub"></a>
### `h/sub`

The only subscription-read form inside a Fresco view. It is an ordinary
function call and may appear in a `let`, conditional, loop, or synchronous
helper.

```clojure
(let [todo (h/sub [:todo/by-id id])]
  [:span (:title todo)])
```

Outside the body's synchronous run (in a callback, a promise, a timer or a lazy
sequence realised later) it raises `:rf.error/fresco-sub-outside-render`.

A bare `rf/subscribe` in a view body is not an alternative. React islands use
[`n/use-sub`](#nuse-sub).

Related: [Views and reads](02-views-and-reads.md).

<a id="collector"></a>
### Collector

The runtime mechanism that records the subscriptions a [boundary](#boundary)
reads during one body execution. Commit reconciles that read set. An abandoned
or retried render acquires no durable subscription ownership.

Related: [Views and reads](02-views-and-reads.md).

<a id="lowering"></a>
### Hiccup conversion

The conversion from Fresco data to React props and elements. It includes the
Hiccup walk, event-intent callback creation, controlled-field behaviour, and
attribute normalisation.

When diagnostics name Hiccup conversion as the cost, the local escape is
returning a React element directly from the same view.

Related: [Events as data](03-events-as-data.md),
[Islands](10-native-tier.md).

<a id="owned-wins"></a>
### Owned-wins merge

When a view forwards an attributes map into an element, merge the caller's map
first and the keys the element owns last, so the owned keys win. Fresco does not
do this for you. Control slots such as `:value`, handlers,
`:key`, and [`::h/revision`](#hrevision) should not be replaceable through a
generic forwarded map.

Related: [Views and reads](02-views-and-reads.md),
[Controlled inputs](04-controlled-inputs.md).

<a id="read-topology"></a>
### Read topology

The placement and grouping of subscription reads relative to a collection.

| Shape | Behaviour |
| --- | --- |
| Fine | Each row reads its own entity; good for sparse updates |
| Coarse | One view-model represents the collection; good for cheap mount or bulk replacement |
| Chunked | One read covers a bounded block of rows |
| Windowed | Only visible rows exist in the DOM, usually through a virtualiser |

Related: [Lists and collections](06-lists-and-collections.md).

## Events and control

<a id="intent"></a>
### Intent

An event vector written directly at an event prop. The runtime creates a
callback and dispatches the vector into the rendering view's frame.

```clojure
[:button {:on-click [:todo/toggle id]}
 "Toggle"]
```

The Hiccup tree retains the event as ordinary data, so tests and tools can
inspect it with `=`.

Related: [Events as data](03-events-as-data.md).

<a id="event"></a>
### `h/event`

The one marked callback form. Expands to an ordinary function. The contract
comes from the **position** where it is written: `on*` positions dispatch a
returned vector; render positions must stay pure; a declared ReactNode slot
refuses it with `:rf.error/fresco-host-unclaimed-callback`.

```clojure
[:input {:type "file"
         :on-change (h/event [e]
                      [:upload/picked
                       (js/Array.from (.. e -target -files))])}]
```

A returned vector is dispatched into the frame of the view that rendered the
callback. Use it when arguments determine the event — value-first foreign callbacks, file lists, drag data — or when the
body must call browser methods such as `.preventDefault`.

Related: [Events as data](03-events-as-data.md),
[Interop](09-interop.md).

<a id="hvalue"></a>
<a id="hchecked"></a>
### `::h/value` and `::h/checked`

Reserved markers replaced at dispatch with the event target's current value or
checked state. Substitution occurs only at the top level of the event vector.

`::h/value` is the target's `.value`, except on a `<select multiple>`, where it
is a vector of the selected option values (`[]` when nothing is selected).
`.value` there would give only the first selected option.

```clojure
[:input
 {:value    (h/sub [:todo.ui/draft])
  :on-input [:todo.ui/set-draft ::h/value]}]
```

Related: [Events as data](03-events-as-data.md),
[Controlled inputs](04-controlled-inputs.md).

<a id="hprevent"></a>
### `::h/prevent`

An intent wrapper that calls `preventDefault` and then dispatches one inner
event vector. Fresco does not prevent clicks by default. `:on-submit` is the one
position where an event vector prevents by default, so a submit needs no
wrapper. A function handler is never prevented for you.

```clojure
[:a.nav-link
 {:href      "#"
  :on-click  [::h/prevent [:todo/set-showing :active]]}
 "Active"]
```

Related: [Events as data](03-events-as-data.md).

<a id="controlled-field"></a>
### Controlled field

An input whose displayed value comes from app-db and whose user edits return as
event intents. Fresco's controlled path provides:

- the committed value returns in the same event turn, so fast typing loses
  nothing;
- the field shows what the handler committed;
- caret and selection survive a rejected or rewritten edit;
- IME composition is not interrupted;
- [`::h/revision`](#hrevision) resets the field explicitly.

A React island does not provide this repair. Keep controlled text fields on
the interpreted Fresco path.

Related: [Controlled inputs](04-controlled-inputs.md).

<a id="hrevision"></a>
### `::h/revision`

A reserved prop for controlled text. Change it when the field should
re-baseline to the current model value after a reset, rejection, rewrite, or
server normalisation.

```clojure
[:input
 {:value       (h/sub [:todo.ui/draft id])
  ::h/revision (h/sub [:todo.ui/draft-revision id])
  :on-input    [:todo.ui/edit id ::h/value]}]
```

Reset is not inferred from value equality. The exact namespaced keyword is
required; bare `:revision` is an ordinary attribute.

Related: [Controlled inputs](04-controlled-inputs.md).

<a id="buffered-field"></a>
### Buffered field

`forms/buffered-field` is an optional forms component that places an app-db
draft in front of a controlled model value. It supports commit, cancel,
rejection, rewrite, and revision-based reset.

Related: [Forms](05-forms.md).

<a id="keyboard-map"></a>
### Keyboard map

A map from DOM `.key` strings to event intents, used at `:on-key-down` or
`:on-key-up`.

```clojure
{:on-key-down
 {"Enter"  [:todo.ui/commit id]
  "Escape" [:todo.ui/cancel id]}}
```

Unlisted keys are ignored. There is no modifier DSL; use [`h/event`](#event)
for cases such as Ctrl+Enter. Key maps suppress matches during IME composition.

Related: [Events as data](03-events-as-data.md).

## Interop

<a id="defhost"></a>
### `defhost`

`h/defhost` declares a foreign React component once. Callback contracts are
inferred from each prop's spelling, as on a native tag; the declaration can
also define:

- a `:callbacks` override, `:event` or `:render`, for an on*-named render prop;
- ReactNode [slots](#reactnode-slot);
- a [server policy](#server-policy);
- a fallback for when the component is client-only.

```clojure
(h/defhost date-picker DatePicker
  {:slots  #{:calendar}
   :server :client-only})
```

Keep the JavaScript require in a `.cljs` host namespace.

Related: [Interop](09-interop.md).

<a id="reactnode-slot"></a>
### ReactNode slot

A host prop declared to contain React content, such as a modal title, footer,
or Suspense fallback. Hiccup supplied to the slot is converted to React
elements under the captured frame. Undeclared props receive Hiccup vectors as
ordinary data.

Related: [Interop](09-interop.md).

<a id="as-element"></a>
### `as-element`

`h/as-element` explicitly converts Hiccup to a React element for a render prop,
foreign callback, or other ReactNode position.

```clojure
{:render-item
 (fn [row]
   (h/as-element
    [row-view {:id (:id row)}]))}
```

Related: [Interop](09-interop.md),
[Lists and collections](06-lists-and-collections.md).

<a id="outward-bridge"></a>
### `as-component` / outward bridge

`h/as-component` turns a Fresco view into a real React component that a native
React, UIx, or JavaScript parent can mount under the existing frame provider.
It does not create another root or state owner.

Related: [Interop](09-interop.md).

<a id="portal"></a>
### Portal

`h/portal` renders Hiccup into another DOM container through React
`createPortal` while preserving frame and context. React events bubble through
the React tree rather than the DOM placement.

Use the overlay module instead when the UI should live on the browser's native
top layer.

Related: [Interop](09-interop.md),
[Overlays and focus](13-overlays-and-focus.md).

<a id="server-policy"></a>
### Server policy

The SSR contract a `defhost` declares with `:server`. `:render` runs the
component on the server. `:client-only`, the default, renders nothing there (or
the declared `:fallback`) until the browser adopts the root. `[:> …]` is always
client-only. Native Hiccup renders on the server.

Related: [SSR and hydration](18-ssr-and-hydration.md),
[Interop](09-interop.md).

<a id="raw-escape"></a>
### Raw escape (`:>`)

`[:> Component props ...]` mounts a foreign React component without a lasting
host declaration. It is useful for migration or a true one-off. Repeated
crossings should use [`h/defhost`](#defhost) so callback contracts, slots, and
server policy remain explicit.

Related: [Interop](09-interop.md).

<a id="crossing"></a>
### Crossing

The point where a Fresco tree hands a subtree to a foreign React component,
through [`h/defhost`](#defhost) or the [raw escape](#raw-escape), `[:> …]`.
Fresco converts the props there and sees nothing below it.

Related: [Interop](09-interop.md#crossing-rules).

## Islands and performance

<a id="native-tier"></a>
### `re-frame.fresco.native`

The optional hooks namespace, usually aliased `n`. It holds exactly two public
names, [`n/use-sub`](#nuse-sub) and [`n/use-frame`](#nuseframe), which are how a
React island reaches Fresco state. It carries no element grammar and no
component macro: an island is written in raw React or UIx.

`[...]` always means interpreted Hiccup. A React element is never interpreted;
it passes through unchanged.

Related: [Islands](10-native-tier.md).

<a id="nuse-sub"></a>
### `n/use-sub`

A React hook that subscribes to a re-frame2 query from inside a React island.
It reads the same subscription cache as `h/sub`, so it wakes on the same commit
and Xray shows the read, and it obeys React's rules of hooks: call it
unconditionally at the top level of the component.

Related: [Islands](10-native-tier.md).

<a id="nuseframe"></a>
### `n/use-frame`

A React hook returning the same map as
[`rf/capture-frame`](../glossary.md#capture-frame) — `:frame`, `:dispatch`,
`:dispatch-sync`, and `:subscribe` — for the frame the island is mounted in,
bound to that frame, so a frame recreated later under the same id is not
reached through it.

Related: [Islands](10-native-tier.md).

<a id="native-island"></a>
### Island

A React component, raw React or UIx, mounted through [`h/defhost`](#defhost)
under the same React root and re-frame2 frame as the surrounding Fresco
application. Use one for React hooks, vendor widgets, and fast-changing state
that only the component itself needs, such as drag positions.

Xray shows the island's `n/use-sub` reads. It does not time the island or see
inside its React tree; React DevTools shows the host under its `defhost` name.

Related: [Islands](10-native-tier.md).

<a id="performance-ladder"></a>
### Performance ladder

The five rungs from ordinary Fresco to a native screen, each taken only after
the one above fails a measurement.

Related: [The escape ladder](escape-ladder.md).

<a id="escape-benefit-rule"></a>
### Escape-benefit rule

What a performance escape must achieve to stay: recover at least 20% of the
measured interaction, save at least 2 ms at p95, or turn a failed budget into a
pass.

Related: [The escape ladder](escape-ladder.md#taking-a-performance-escape).

<a id="user-visible-budget"></a>
### User-visible budget

A performance target stated as something the user observes, such as a click
painting within 50 ms at p95.

Related: [Performance](19-performance.md#measure-against-a-budget).

## State homes

<a id="one-state-owner"></a>
### One state owner

Application-visible state lives in re-frame2 app-db. Fresco does not add a
component-local reactive store. A host may retain private mechanics only when
they are not a hidden duplicate of an application fact.

Related: [Ephemeral state](11-ephemeral-state.md).

<a id="motion-presence"></a>
### `motion/presence`

Optional exit-retention head from `re-frame.fresco.motion`. Keeps keyed
children for `:timeout-ms` after their data leaves app-db so CSS exit
transitions can run. Merges each child's `::motion/mounting` /
`::motion/unmounting` override map into it while it is in that phase — an
element's attributes or a view's props. Not an animation system.

Related: [Motion and presence](12-motion-and-presence.md),
[Ephemeral state](11-ephemeral-state.md).

<a id="overlay"></a>
### Overlay

`re-frame.fresco.overlay` popover and modal primitives. They use the browser's
native top layer. App-db owns `:open?`; `:on-dismiss` is an event; the browser
owns stacking, light-dismiss, modal focus trapping, and focus restoration.

A closed overlay has no DOM node, listener, or active body subscriptions.

Related: [Overlays and focus](13-overlays-and-focus.md).

## Routing and resources

<a id="route-link"></a>
### `route-link`

`h/route-link` returns a real anchor for a registered route and its params.
Clicking it navigates; modified clicks (new tab, and so on) behave as for any
link, and an `:on-click` can veto the navigation. `:prefetch :intent` loads the
destination's data on hover, focus and touch.

Call it as a function; it is not a view. To style the active link, compare the
route id from `[:rf.route/id]`.

Related: [Routing and navigation](07-routing-and-navigation.md).

<a id="view-scoped-read"></a>
### View-scoped read

A resource whose lifetime is a local view rather than the current route. It has
no dedicated mechanism: the event that decides the data is wanted ensures it
under an owner, and the event that dismisses the view releases that owner.

Resource subscriptions never fetch; they read the cache. An owner keeps its
entry from being garbage-collected until the owner is released.

Related: [Async resources](08-async-resources.md),
[Resources glossary](../../resources/glossary.md).

## Testing

<a id="test-kit"></a>
### Test kit

Two main namespaces:

- `re-frame.fresco.test`, usually `ht`, for pure and semantic tests;
- `re-frame.fresco.test.mounted`, usually `hm`, for mounted React and DOM
  tests.

`re-frame.fresco.test.forms` names the forms module's event ids, and
`re-frame.fresco.test.server` holds the server-render determinism check.

Related: [Testing](15-testing.md).

<a id="testing-ladder"></a>
### Testing ladder

The five test levels, L0 (pure functions) to L4 (real browsers). A lower level
does not prove what a higher one does.

Related: [Testing](15-testing.md#the-testing-ladder).

<a id="semantic-harness"></a>
### Semantic harness

`ht/tree` runs one hook-free Fresco view body with injected subscription
fixtures and returns a semantic tree. Nested views remain represented as calls.
Hosts and raw React elements are refused, and a hook call fails with React's
own error; all three belong at L3.

Related: [Testing](15-testing.md).

<a id="mounted-facade"></a>
### Mounted facade

The `hm` namespace for L3 tests. It provides isolated-frame mount and hydrate,
rerender, dispatch-and-settle, settle, virtual-clock advancement, unmount, and
`assert-clean!` residue checking.

Related: [Testing](15-testing.md).

<a id="sabotage-control"></a>
### Sabotage twin

A deliberately broken twin of an important test or measurement. It proves that
the instrument moves when the input is wrong and prevents an empty population
from passing vacuously.

Related: [Testing](15-testing.md).

<a id="canonical-dom"></a>
### Canonical DOM

A normalised DOM serialisation used for differential comparison. Attribute
names are ordered so equivalent DOM does not differ only because properties
were inserted in a different sequence.

Canonical DOM is distinct from semantic-tree equality, exact server bytes, and
hydrated browser behaviour.

Related: [Testing](15-testing.md),
[Migrating from Reagent](20-migration-from-reagent.md).

<a id="shadow-comparison"></a>
### Shadow comparison

A migration witness that mounts a reference implementation and candidate under
isolated equivalent state, drives both with one script, and compares canonical
DOM plus event-intent streams at each checkpoint.

Related: [Migrating from Reagent](20-migration-from-reagent.md).

## Diagnostics

<a id="causal-lens"></a>
### Causal chain

The stages Xray's Causal view follows one dispatch through, from the event to
the browser paint. Render, commit, and paint are separate claims.

Related: [Diagnostics](16-diagnostics.md#the-causal-chain).

<a id="explain-render"></a>
### Explain-render

Xray's answer to “why did this view run?” It reports the view's most recently
changed reads, the recent dispatches that recomputed them as leads rather than
causes, and what evidence was lost. Props and context are not causes Fresco
records.

Related: [Diagnostics](16-diagnostics.md).

<a id="hot-view-advisor"></a>
### Advisor

A diagnostic ranking that combines time, frequency, read churn, and fan-out,
then classifies the pressure it can measure: computation and read topology.
Hiccup conversion, React and layout it reports as unattributed, naming the tool
that can measure each. It recommends the smallest credible remedy and never
auto-promotes code to native.

Related: [Diagnostics](16-diagnostics.md),
[Performance](19-performance.md).

<a id="loss-labels"></a>
### Loss labels

Explicit labels for incomplete evidence:

- `:unknown`;
- `:opaque`;
- `:host-opaque`;
- `:cap`;
- `:uncorrelated`.

Missing evidence is not represented as an empty result.

Related: [Diagnostics](16-diagnostics.md).

<a id="complaint-catalogue"></a>
### Complaint

Fresco's name for one of its errors or warnings, each identified by a stable
`:rf.error/*` or `:rf.warning/*` id. Tests assert the id, not the human
message.

Related: [Troubleshooting](troubleshooting.md#the-complaint-index).

<a id="production-erasure"></a>
### Production erasure

Removal of development diagnostics, evidence machinery, source locations, and
development console warnings from default release bundles; a thrown complaint
keeps its id and message. Optional performance timing
has a separate compile-time flag and is disabled by default.

Related: [Diagnostics](16-diagnostics.md).

## Roots, hydration and errors

<a id="mount"></a>
### `client-root`, `render!`, and `unmount!`

The root lifecycle every re-frame2 React view adapter shares. `h/client-root`
returns an inert handle, so it belongs in a `defonce`. The first `h/render!`
through the handle creates the React root and every later call updates it, so
one function serves as the boot and the hot-reload hook. `h/unmount!` removes
the root and destroys no frame.

Related: [Installation](00-installation.md#what-the-boot-creates).

<a id="hydrate"></a>
### `{:hydrate? true}`

The `h/render!` option that makes its first call adopt server-rendered DOM
(`hydrateRoot`) instead of replacing it. The frame must already exist and hold
the server payload, and the tree uses `h/frame-provider`.

Related: [SSR and hydration](18-ssr-and-hydration.md).

<a id="error-boundary"></a>
### Error boundary

`h/error-boundary` is a React error region with `:fallback`, `:reset-key`, and
`:on-error`. It is different from a re-render [boundary](#boundary); only the
error boundary catches descendant render and lifecycle exceptions.

Expected failures remain ordinary app-db state.

Related: [Errors](17-errors.md).
