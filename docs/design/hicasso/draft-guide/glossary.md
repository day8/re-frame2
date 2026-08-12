# Hicasso glossary

This glossary defines Hicasso-specific terms. Core re-frame2 terms such as
[app-db](../../../core/glossary.md#app-db),
[frame](../../../core/glossary.md#frame),
[event](../../../core/glossary.md#event), and
[subscription](../../../core/glossary.md#subscription) live in the
[core glossary](../../../core/glossary.md).

## Authoring

<a id="hicasso"></a>
### Hicasso

re-frame2's native React view adapter. Hicasso interprets
[Hiccup](../../../core/glossary.md#hiccup), reads subscriptions with
[`h/sub`](#hsub), and accepts event vectors as [intents](#intent). App-db,
events, effects, and the event pipeline remain ordinary re-frame2.

Require it as:

```clojure
[re-frame.hicasso :as h]
```

Forms, overlays, routing helpers, the native tier, and test tooling are separate
optional namespaces.

Related: [Getting started](01-getting-started.md),
[Installation](00-installation.md).

<a id="defview"></a>
### `defview`

`h/defview` defines a Hicasso [view](#view). The view receives one props map and
returns Hiccup, `nil`, a fragment, or a native React element at the direct-return
performance level.

Use a view as a Hiccup head. Do not call it as an ordinary function:

```clojure
(h/defview counter [_]
  [:main
   [:h1 "Clicked " (h/sub [:counter/count]) " times"]
   [:button {:on-click [:counter/increment]}
    "Click me"]])

[counter {}]   ;; view boundary
(counter {})   ;; raises
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

Native tags, fragments, and [`h/defhost`](#defhost) heads do not create Hicasso
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
`:rf.error/hicasso-bad-head`.

Related: [Views and reads](02-views-and-reads.md).

<a id="hsub"></a>
### `h/sub`

The only subscription-read form inside a Hicasso view. It is an ordinary
function call and may appear in a `let`, conditional, loop, or synchronous
helper.

```clojure
(let [todo (h/sub [:todo/by-id id])]
  [:span (:title todo)])
```

A bare `rf/subscribe` in a view body is not an alternative. Native components
use [`n/use-sub`](#nuse-sub).

Related: [Views and reads](02-views-and-reads.md).

<a id="read-extent-law"></a>
### Read-extent law

`h/sub` is legal only during direct synchronous execution of the active view
body, including helpers it calls immediately. A callback, promise, timer, lazy
sequence, unforced delay, or other deferred computation may not carry the read
outside that extent.

A read after the extent raises a structured error such as
`:rf.error/hicasso-sub-outside-render` or
`:rf.error/hicasso-deferred-read-at-boundary`. Read the value during render and
close over the value instead.

Related: [Views and reads](02-views-and-reads.md).

<a id="collector"></a>
### Collector

The runtime mechanism that records the subscriptions a [boundary](#boundary)
reads during one body execution. Commit reconciles that read set. An abandoned
or retried render acquires no durable subscription ownership.

Related: [Views and reads](02-views-and-reads.md).

<a id="component-abi"></a>
### Component ABI

The props and children contract for a Hiccup head: which values are converted,
which pass by identity, where `:key` and `:ref` live, and how children arrive.

- Hicasso views receive a ClojureScript props map.
- Declared hosts follow their callback, slot, and server contracts.
- Native [`n/$`](#n-dollar) uses React slots and does not lower Hicasso event
  intents or controlled fields.

Related: [Views and reads](02-views-and-reads.md),
[Interop](09-interop.md), [The native tier](10-native-tier.md).

<a id="lowering"></a>
### Lowering

The conversion from Hicasso data to React props and elements. It includes the
Hiccup walk, event-intent callback creation, controlled-field behaviour, and
attribute normalisation.

When diagnostics identify lowering itself as the cost owner, the local escape
is a direct [`n/$`](#n-dollar) return from the same view.

Related: [Events as data](03-events-as-data.md),
[The native tier](10-native-tier.md).

<a id="owned-wins"></a>
### Owned-wins merge

When a view forwards an attributes map into an element, literal keys written by
the element author take precedence. Control slots such as `:value`, handlers,
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

<a id="hfn"></a>
### `h/fn`

The one marked callback form (HD-024). Expands to an ordinary function. The
contract comes from the **position** where it is written: event positions
dispatch a returned vector; render positions must stay pure; unclaimed host
props refuse the mark.

```clojure
[:input {:type "file"
         :on-change (h/fn [e]
                      [:upload/picked
                       (js/Array.from (.. e -target -files))])}]
```

Captures the rendering frame when created. Use it when arguments determine the
event — value-first foreign callbacks, file lists, drag data — or when the
body must call browser methods such as `.preventDefault`.

Related: [Events as data](03-events-as-data.md),
[Interop](09-interop.md).

<a id="hframe"></a>
### `h/frame`

Returns the frame **id keyword** of the Hicasso boundary currently rendering.
Legal only during a boundary body or a render callback that boundary supplied.
Not a tracked subscription.

The taught carry spelling is composition with core:

```clojure
(let [{:keys [dispatch]} (rf/capture-frame (h/frame))]
  …)
```

Zero-arity ambient `(rf/capture-frame)` refuses under Hicasso's body
discipline. Prefer effects for application async work; use this at foreign
edges that retain a dispatching closure.

Related: [Events as data](03-events-as-data.md).

<a id="hvalue"></a>
<a id="hchecked"></a>
### `::h/value` and `::h/checked`

Reserved markers replaced at dispatch with the DOM event target's `.value` or
`.checked`. Substitution occurs only at the top level of the event vector.

```clojure
[:input
 {:value    (h/sub [:draft])
  :on-input [:draft/changed ::h/value]}]
```

Related: [Events as data](03-events-as-data.md),
[Controlled inputs](04-controlled-inputs.md).

<a id="hprevent"></a>
### `::h/prevent`

An intent wrapper that calls `preventDefault` and then dispatches one inner
event vector. Hicasso does not auto-prevent clicks or form submits.

```clojure
[:form
 {:on-submit [::h/prevent [:signup/submit]]}
 ...]
```

Related: [Events as data](03-events-as-data.md).

<a id="controlled-field"></a>
### Controlled field

An input whose displayed value comes from app-db and whose user edits return as
event intents. Hicasso's controlled path provides:

- synchronous same-turn convergence;
- committed-value echo;
- caret and selection preservation;
- IME composition safety;
- explicit reset through [`::h/revision`](#hrevision).

The native tier does not provide this repair. Keep controlled text fields on
the interpreted Hicasso path.

Related: [Controlled inputs](04-controlled-inputs.md).

<a id="hrevision"></a>
### `::h/revision`

A reserved prop for controlled text. Change it when the field should
re-baseline to the current model value after a reset, rejection, rewrite, or
server normalisation.

```clojure
[:input
 {:value       (h/sub [:field/value id])
  ::h/revision (h/sub [:field/revision id])
  :on-input    [:field/edit id ::h/value]}]
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
 {"Enter"  [:editor/commit]
  "Escape" [:editor/cancel]}}
```

Unlisted keys are ignored. There is no modifier DSL; use [`h/fn`](#hfn)
for cases such as Ctrl+Enter. Key maps suppress matches during IME composition.

Related: [Events as data](03-events-as-data.md).

## Interop

<a id="defhost"></a>
### `defhost`

`h/defhost` declares a foreign React component once. The declaration can define:

- callback contracts: `:event`, `:handler`, or `:render`;
- ReactNode [slots](#reactnode-slot);
- a [server policy](#server-policy);
- a Client-only fallback.

```clojure
(h/defhost date-picker DatePicker
  {:callbacks {:on-change :event}
   :slots     #{:calendar}
   :server    :client-only})
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

`h/as-component` turns a Hicasso view into a real React component that a native
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

The SSR contract for a host or native component:

- **Render**: execute on the server and produce deterministic React HTML;
- **Client-only**: do not execute on the server; produce a deterministic
  fallback or nothing until the browser adopts the root.

Foreign hosts and named native components default to Client-only. Native
Hiccup and intrinsic React elements render by default.

Related: [SSR and hydration](18-ssr-and-hydration.md),
[Interop](09-interop.md).

<a id="raw-escape"></a>
### Raw escape (`:>`)

`[:> Component props ...]` mounts a foreign React component without a lasting
host declaration. It is useful for migration or a true one-off. Repeated
crossings should use [`h/defhost`](#defhost) so callback contracts, slots, and
server policy remain explicit.

Related: [Interop](09-interop.md).

## Native tier

<a id="native-tier"></a>
### Native tier

The optional `re-frame.hicasso.native` namespace, usually aliased `n`. It
provides direct React element construction and named native components.

`[...]` always means interpreted Hiccup. `n/$` always means native React.
Nothing silently compiles or promotes one form into the other.

Related: [The native tier](10-native-tier.md).

<a id="n-dollar"></a>
### `n/$`

A macro that constructs one React element directly. It does not perform Hiccup
lowering, intent conversion, class collection merging, controlled-field repair,
or automatic Hiccup-child conversion.

```clojure
(n/$ :td {:class "px"} px)
(n/$ :td (n/props cell-props) px)
```

Use `h/as-element` when one native subtree needs an interpreted Hiccup child.

Related: [The native tier](10-native-tier.md).

<a id="nprops"></a>
### `n/props`

A syntactic marker telling `n/$` that a dynamic expression is its props
operand. It creates no runtime wrapper.

Without the marker, an arbitrary dynamic map in second position is treated as
a child.

Related: [The native tier](10-native-tier.md).

<a id="ndefcomponent"></a>
### `n/defcomponent`

Defines a named top-level React function component with Hicasso's native-tier
marker, source identity, display name, and server policy. Ordinary React hooks
are legal inside it.

Use [`n/use-sub`](#nuse-sub) and [`n/use-frame`](#nuseframe) to join the current
re-frame2 frame.

Related: [The native tier](10-native-tier.md).

<a id="nuse-sub"></a>
### `n/use-sub`

A React hook that subscribes to a re-frame2 query in a native component. It
obeys React's rules of hooks: call it unconditionally at the top level of the
component.

Related: [The native tier](10-native-tier.md).

<a id="nuseframe"></a>
### `n/use-frame`

A React hook returning frame-locked operations such as `:dispatch`,
`:dispatch-sync`, and `:subscribe` for the current native component.

Related: [The native tier](10-native-tier.md).

<a id="native-island"></a>
### Native island

A named native React component under the same React root and re-frame2 frame as
the surrounding Hicasso application. It is appropriate for hooks, vendor
widgets, and high-rate host-private mechanics.

Xray names and times the crossing, while the inner React tree remains
host-opaque.

Related: [The native tier](10-native-tier.md).

<a id="nmemo"></a>
### `n/memo`

Marker-preserving React memoisation for a named native component. Raw
`react/memo` can erase the marker used by Xray and embedding checks.

Related: [The native tier](10-native-tier.md).

<a id="nlazy"></a>
### `n/lazy`

Marker-preserving `React.lazy` loading for a named native component. It follows
React's promise-returning loader contract and retains the Hicasso native marker.
Declare it at namespace top level.

Related: [Code splitting and lazy loading](21-code-splitting.md),
[The native tier](10-native-tier.md).

<a id="performance-ladder"></a>
### Performance ladder

Five explicit implementation levels:

1. ordinary Hicasso;
2. tuned [read topology](#read-topology);
3. a direct native return from an existing view;
4. a named [native island](#native-island);
5. a native screen.

Related: [Performance](19-performance.md),
[The native tier](10-native-tier.md).

<a id="escape-benefit-rule"></a>
### Escape-benefit rule

Keep a native escape only when it:

- recovers at least 20% of the measured interaction;
- saves at least 2 ms at p95; or
- converts a failed user-visible budget into a pass.

Otherwise remove it.

Related: [Performance](19-performance.md).

## State homes

<a id="one-state-owner"></a>
### One state owner

Application-visible state lives in re-frame2 app-db. Hicasso does not add a
component-local reactive store. A host may retain private mechanics only when
they are not a hidden duplicate of an application fact.

Related: [Ephemeral state](11-ephemeral-state.md).

<a id="motion-presence"></a>
### `motion/presence`

Optional exit-retention head from `re-frame.hicasso.motion`. Keeps keyed
children for `:timeout-ms` after their data leaves app-db so CSS exit
transitions can run. Applies `::h/mounting` / `::h/unmounting` attribute
overrides on elements, or passes `:rf/phase` to view children. Not an
animation system.

Related: [Motion and presence](12-motion-and-presence.md),
[Ephemeral state](11-ephemeral-state.md).

<a id="pressure-valve"></a>
### Pressure valve

A legitimate home for UI state under the one-state-owner rule:

- an explicit app-db address;
- the forms module for drafts and form control;
- native host state for high-rate private mechanics;
- browser-owned state as an explicit interop choice;
- presence retention for pixels that outlive removed data.

Related: [Ephemeral state](11-ephemeral-state.md).

<a id="overlay"></a>
### Overlay

`re-frame.hicasso.overlay` popover and modal primitives. They use the browser's
native top layer. App-db owns `:open?`; `:on-dismiss` is an event; the browser
owns stacking, light-dismiss, modal focus trapping, and focus restoration.

A closed overlay has no DOM node, listener, or active body subscriptions.

Related: [Overlays and focus](13-overlays-and-focus.md).

<a id="route-link"></a>
### `route-link`

A routing helper that returns a real anchor and encodes navigation as a Hicasso
intent. It supports route ids and params, optional intent prefetch, native link
semantics, and link-local veto behaviour.

It is an inline function, not a separate view. Active-state styling comes from
a route subscription comparison.

Related: [Routing and navigation](07-routing-and-navigation.md).

<a id="demand-driven-committed-read"></a>
### Demand-driven committed read

A resource subscription with `:demand true`:

```clojure
(h/sub
 [:rf/resource
  {:resource :app/suggestions
   :params   {:q q}
   :demand   true}])
```

Commit acquires demand. Unmount, parameter change, or a committed render that
stops taking the read releases demand. Speculative and abandoned renders
acquire nothing. Without `:demand`, the resource subscription is passive.

Related: [Async resources](08-async-resources.md),
[Resources glossary](../../../resources/glossary.md).

## Testing

<a id="test-kit"></a>
### Test kit

Two namespaces:

- `re-frame.hicasso.test`, usually `ht`, for pure and semantic tests;
- `re-frame.hicasso.test.mounted`, usually `hm`, for mounted React and DOM
  tests.

Related: [Testing](15-testing.md).

<a id="testing-ladder"></a>
### Testing ladder

| Level | Proves | Mechanism |
| --- | --- | --- |
| L0 | Handlers, subscriptions, transitions | Pure function calls |
| L1 | Intents, codecs, revision laws, macro expansion | Data and property tests |
| L2 | One hook-free body as a semantic tree | [`ht/tree`](#semantic-harness) |
| L3 | React lifecycle, hooks, hosts, error boundaries | Mounted facade |
| L4 | IME, caret, focus, hydration, performance | Real browser engines |

A lower level does not prove the equality of a higher level.

Related: [Testing](15-testing.md).

<a id="semantic-harness"></a>
### Semantic harness

`ht/tree` runs one hook-free Hicasso view body with injected subscription
fixtures and returns a semantic tree. Nested views remain represented as calls.
Hooks, hosts, raw React elements, and `n/$` results are refused and belong at
L3.

Related: [Testing](15-testing.md).

<a id="mounted-facade"></a>
### Mounted facade

The `hm` namespace for L3 tests. It provides isolated-frame mount and hydrate,
rerender, dispatch-and-settle, settle, virtual-clock advancement, unmount, and
`assert-clean!` residue checking.

Related: [Testing](15-testing.md).

<a id="sabotage-control"></a>
### Sabotage control

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

## Diagnostics

<a id="causal-lens"></a>
### Causal lens

The diagnostic sequence used by Xray:

```text
event
  → subscriptions recomputed
  → values changed
  → views notified
  → bodies run
  → React commit
  → browser paint
```

Render, commit, and paint are separate claims.

Related: [Diagnostics](16-diagnostics.md).

<a id="explain-render"></a>
### Explain-render

Xray's answer to “why did this view run?” It reports the cause category, changed
reads or props, current read set, fan-out, completeness, and evidence loss.

Related: [Diagnostics](16-diagnostics.md).

<a id="hot-view-advisor"></a>
### Hot-view advisor

A diagnostic ranking that combines time, frequency, read churn, and fan-out,
then classifies the pressure as computation, topology, lowering, React, or
layout. It recommends the smallest credible remedy and never auto-promotes
code to native.

Related: [Diagnostics](16-diagnostics.md),
[Performance](19-performance.md).

<a id="loss-labels"></a>
### Loss labels

Explicit labels for incomplete evidence:

- `:unknown`;
- `:opaque` / `:no-static-analysis`;
- `:host-opaque`;
- `:cap`;
- `:uncorrelated`.

Missing evidence is not represented as an empty result.

Related: [Diagnostics](16-diagnostics.md).

<a id="complaint-catalogue"></a>
### Complaint catalogue

The stable `:rf.error/*` and `:rf.warning/*` identifier set, including cause,
recovery, and source links where available. Tests assert the id, not the human
message.

Related: [Diagnostics](16-diagnostics.md).

<a id="production-erasure"></a>
### Production erasure

Removal of development diagnostics, evidence machinery, source locations, and
complaint messages from default release bundles. Optional performance timing
has a separate compile-time flag and is disabled by default.

Related: [Diagnostics](16-diagnostics.md).

## Lifecycle and delivery

<a id="mount"></a>
### `mount!`, `render!`, and `unmount!`

The Hicasso root lifecycle.

`h/mount!` associates a DOM container, a frame, optional `:initial-events`, and
one root view. It returns a root handle. Initial events run in order before
first paint.

`h/render!` renders a new root element through the same handle.

`h/unmount!` tears the root down and is safe to call more than once.

```clojure
(defonce root
  (h/mount!
   (js/document.getElementById "app")
   {:frame :rf/default
    :initial-events [[:app/init]]}
   [app-shell {}]))
```

Related: [Installation](00-installation.md).

<a id="hydrate"></a>
### `hydrate!`

Two functions complete hydration:

- `re-frame.ssr/hydrate!` installs the server payload into the client frame;
- `h/hydrate!` adopts existing server DOM for one Hicasso root.

State hydration must run before DOM adoption.

Related: [SSR and hydration](18-ssr-and-hydration.md).

<a id="error-boundary"></a>
### Error boundary

`h/error-boundary` is a React error region with `:fallback`, `:reset-key`, and
`:on-error`. It is different from a re-render [boundary](#boundary); only the
error boundary catches descendant render and lifecycle exceptions.

Expected failures remain ordinary app-db state.

Related: [Errors](17-errors.md).

<a id="user-visible-budget"></a>
### User-visible budget

A performance requirement expressed as an observable user outcome, such as:

- discrete interaction paint within 50 ms p95;
- controlled echo within one frame;
- broad operation within 100 ms p95;
- zero teardown residue.

Synthetic benchmark scores do not replace these budgets.

Related: [Performance](19-performance.md).

<a id="shadow-comparison"></a>
### Shadow comparison

A migration witness that mounts a reference implementation and candidate under
isolated equivalent state, drives both with one script, and compares canonical
DOM plus event-intent streams at each checkpoint.

Related: [Migrating from Reagent](20-migration-from-reagent.md).
