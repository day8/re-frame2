# Hicasso glossary

Hicasso nouns, verbs, and laws. One term, definition first; short code when
the spelling matters; Related points at the teaching page. Core re-frame2
terms (`app-db`, [frame](../../../core/glossary.md#frame), [event](../../../core/glossary.md#event),
[subscription](../../../core/glossary.md#subscription)) live in the
[core glossary](../../../core/glossary.md).

Grouped by role: [authoring](#authoring), [events and control](#events-and-control),
[interop](#interop), [native tier](#native-tier), [state homes](#state-homes),
[testing](#testing), [diagnostics](#diagnostics), and [lifecycle and delivery](#lifecycle-and-delivery).

## Authoring

<a id="hicasso"></a>
### **Hicasso**

re-frame2's native view adapter: interpreted [Hiccup](../../../core/glossary.md#hiccup)
on a modern React function-component host. You write vectors and maps, read
with [`h/sub`](#hsub) at the point of use, and put event vectors in attributes
as [intents](#intent). The app-db, events, and pipeline stay ordinary
re-frame2.

Require `[re-frame.hicasso :as h]`. Optional modules (forms, overlays, native
tier, test kit, routing helpers) are separate requires and cost nothing when
absent.

Related: [Getting started](01-getting-started.md), [Installation](installation.md).

<a id="defview"></a>
### **defview**

`h/defview` — mints a Hicasso [view](#view): a React/re-frame [boundary](#boundary)
used as a Hiccup head. Props map in, Hiccup (or a native React element at
[rung 3 of the performance ladder](#performance-ladder)) out. Direct Clojure
invocation refuses; mount it as a vector:

```clojure
(h/defview counter [_]
  [:main
   [:h1 "Clicked " (h/sub [:counter/count]) " times"]
   [:button {:on-click [:counter/increment]} "Click me"]])

[counter]          ;; legal
(counter {})       ;; refuses — write [counter]
```

Related: [Views and reads](02-views-and-reads.md).

<a id="view"></a>
### **view**

A function from a props map to markup, registered with [`defview`](#defview).
In head position of a Hiccup vector it is a [boundary](#boundary). Plain
`defn` helpers are not views: call them as functions so they [inline](#inline-helper).

Related: [Views and reads](02-views-and-reads.md).

<a id="boundary"></a>
### **boundary**

Hicasso's unit of independent re-render, minted by [`defview`](#defview). Owns
four things: React identity, the body's [`h/sub`](#hsub) reads, value-equality
memoization, and the [frame](../../../core/glossary.md#frame) to which
[intents](#intent) dispatch. Native tags, fragments, and [`defhost`](#defhost)
heads also sit in vector position; none of those is a boundary.

Related: [Views and reads](02-views-and-reads.md).

<a id="inline-helper"></a>
### **inline helper**

An ordinary `defn` called from a view body. Its Hiccup splices into the caller;
any `h/sub` it performs donates membership **upward** to the enclosing
[boundary](#boundary). Helpers cost no re-render granularity. A plain `defn`
in head position refuses (`:rf.error/hicasso-bad-head`).

```clojure
[todo-row {:key id :id id}]   ;; boundary child
(row-icon {:kind :urgent})    ;; inlined helper
```

Related: [Views and reads](02-views-and-reads.md).

<a id="hsub"></a>
### **h/sub**

The only view-side subscription read. Ordinary function call at the point of
use — legal in `let`, `when`, loops, and synchronous helpers. Tracks reads for
the active [boundary](#boundary) under the [read-extent law](#read-extent-law).

```clojure
(let [todo (h/sub [:todo/by-id id])]
  [:span (:title todo)])
```

Bare `rf/subscribe` in a body is not a fallback: it refuses. Past the fence
into [native](#native-tier) code, use [`n/use-sub`](#nuse-sub) instead.

Related: [Views and reads](02-views-and-reads.md).

<a id="read-extent-law"></a>
### **read-extent law**

`h/sub` is legal only during the **direct synchronous execution** of the
active [boundary](#boundary) body (including ordinary helpers it calls). A
read deferred through a callback, promise, timer, lazy sequence, or other
escaped extent refuses with source and recovery
(`:rf.error/hicasso-sub-outside-render`). Capture values during the body;
async work re-enters through events and coeffects.

Related: [Views and reads](02-views-and-reads.md).

<a id="collector"></a>
### **collector**

Runtime mechanism that records which subscriptions a [boundary](#boundary)
took during one body run. Commit reconciles that read set; abandoned and
retried renders acquire no durable ownership. Escaping the collector is what
the [read-extent law](#read-extent-law) forbids.

Related: [Views and reads](02-views-and-reads.md#the-collector).

<a id="component-abi"></a>
### **component ABI**

The props/children contract for a head: what converts, what is opaque, where
keys live. [Views](#view) take a Clojure props map (keys in the map, not
metadata); [hosts](#defhost) convert per declaration; native
[`n/$`](#n) uses React slots and no intent lowering.

Related: [Views and reads](02-views-and-reads.md#the-component-abi).

<a id="lowering"></a>
### **lowering**

The step that turns Hicasso data into React props and elements: Hiccup walk,
[intent](#intent) → callback, controlled-field repair, attribute rules. Cost
owner when Xray names "lowering"; the narrow escape is a direct
[`n/$`](#n-dollar) return from the same [boundary](#boundary).

Related: [Events as data](03-events-as-data.md), [Native tier](10-native-tier.md).

<a id="owned-wins"></a>
### **owned wins**

Attribute-merge rule: literal keys written on the element always beat
forwarded maps, by presence. Do not forward `:key` or [`::h/revision`](#hrevision)
through a generic merge.

Related: [Views and reads](02-views-and-reads.md#forwarding-attributes-owned-wins).

<a id="read-topology"></a>
### **read topology**

Where [reads](#hsub) sit relative to list structure — the main performance
knob while staying on ordinary Hicasso:

| Shape | Idea |
|---|---|
| **Fine** | each row reads itself — sparse independent updates |
| **Coarse** | one view-model for the whole list — cheap mount / bulk replace |
| **Chunked** | page-sized batches bound the invalidation sweep |
| **Windowed** | only visible rows exist in the DOM (usually a foreign virtualizer) |

Related: [Lists and collections](06-lists-and-collections.md).

---

## Events and control

<a id="intent"></a>
### **intent**

An [event](../../../core/glossary.md#event) vector written in an attribute at
an event position (`on-*` / `onClick`). The runtime builds the callback and
dispatches the vector to the rendering [boundary](#boundary)'s frame. The tree
stays data: tests assert with `=`; tools read meaning without running the app.

```clojure
[:button {:on-click [:todo/toggle id]} "✓"]
```

Related: [Events as data](03-events-as-data.md).

<a id="hevent"></a>
### **h/event**

Explicit handler form when a vector intent is not enough: value-first foreign
callbacks, calculated events, or reading the invoker's arguments. Captures
the current frame at creation; one meaning everywhere (including host slots).

```clojure
{:on-change (h/event [date] [:calendar/picked date])}
```

Related: [Events as data](03-events-as-data.md).

<a id="hvalue"></a>
<a id="hchecked"></a>
### **::h/value** / **::h/checked**

Reserved markers substituted at dispatch from the DOM event target's
`.value` / `.checked`. Top level of the intent vector only.

```clojure
[:input {:value (h/sub [:draft]) :on-input [:edit ::h/value]}]
```

Related: [Events as data](03-events-as-data.md), [Controlled inputs](04-controlled-inputs.md).

<a id="hprevent"></a>
### **::h/prevent**

Intent head that prevents the browser default, then dispatches the inner
intent. Nothing auto-prevents — not click, not submit.

```clojure
[:form {:on-submit [::h/prevent [:signup/submit]]} …]
```

Related: [Events as data](03-events-as-data.md).

<a id="controlled-field"></a>
### **controlled field**

An input whose displayed value is owned by app-db (via a subscription) and
whose edits return as [intents](#intent). Hicasso's controlled law covers
same-turn convergence, committed echo, caret/selection, IME composition, and
identity-preserving [revision](#hrevision) reset. Form fields stay on the
interpreted side; the [native tier](#native-tier) has no controlled repair.

Related: [Controlled inputs](04-controlled-inputs.md).

<a id="hrevision"></a>
### **::h/revision**

Reserved controlled-text prop: change this value to re-baseline the field's
draft (accept, reject, or rewrite). Reset is never by value equality — a
model that reasserts the same string would be invisible to `not=`. Exact
namespaced keyword only; a bare `:revision` is an ordinary attribute.

```clojure
[:input {:value       (h/sub [:field/value id])
         ::h/revision (h/sub [:field/revision id])
         :on-input    [:field/edit id ::h/value]}]
```

Related: [Controlled inputs](04-controlled-inputs.md).

<a id="buffered-field"></a>
### **buffered-field**

`forms/buffered-field` — optional forms helper: one controlled input with a
draft in front of the model. Accepts, rejects, and rewrites through
[`::h/revision`](#hrevision); pairs with per-instance submit status and
validation display.

Related: [Forms](05-forms.md).

<a id="keyboard-map"></a>
### **keyboard map**

Data map on a keyboard prop (e.g. `:on-key-down`) from DOM `.key` string →
intent. Unlisted keys are ignored. There is no modifier DSL — richer cases use
[`h/event`](#hevent). IME composition is central: Enter during composition
commits nothing.

Related: [Events as data](03-events-as-data.md#keyboard-as-data).

---

## Interop

<a id="defhost"></a>
### **defhost**

`h/defhost` — declared door to a foreign React component. Names the React
value once, documents ReactNode [slots](#reactnode-slot), callback contracts
(`:event` / `:handler` / `:render`), and [server policy](#server-policy).
Quarantines the npm require in one host namespace.

```clojure
(h/defhost date-picker DatePicker
  {:slots #{:calendar}
   :server :client-only})
```

Related: [Interop](09-interop.md).

<a id="reactnode-slot"></a>
### **ReactNode slot**

A prop position a [host](#defhost) declares as carrying React children or
named content (Suspense `:fallback`, compound slots). Hiccup in that position
lowers under the captured frame without deep-converting arbitrary data maps.

Related: [Interop](09-interop.md#reactnode-slots).

<a id="as-element"></a>
### **as-element**

`h/as-element` — explicit Hiccup → React element conversion for render props
and foreign callbacks. The one honest conversion when a library expects a
ReactNode, not data.

```clojure
{:renderItem (fn [row]
               (h/as-element [row-view {:id (:id row)}]))}
```

Related: [Interop](09-interop.md), [Lists and collections](06-lists-and-collections.md).

<a id="outward-bridge"></a>
### **as-component** / **outward bridge**

`h/as-component` returns a real React component so a native parent
(`n/defcomponent`, UIx, or JS) can render a minted Hicasso view under the
existing frame provider — no second root, no exposed internal codec ABI.
Symmetric to hosts embedding foreign React inward.

Related: [Interop](09-interop.md#the-outward-bridge).

<a id="portal"></a>
### **portal**

Optional helper that lowers Hiccup into `createPortal`, preserves frame and
context, and documents event bubbling / target-change identity plus
client/server policy. Overlays that need the **native top layer** use the
overlay module instead of a hand-rolled portal.

Related: [Interop](09-interop.md#portals), [Overlays and focus](12-overlays-and-focus.md).

<a id="server-policy"></a>
### **server policy**

Per-surface answer for SSR: **Render** (deterministic React server bytes) or
**Client-only** (source-located refusal + deterministic fallback). Silent
`nil` is not a policy. Declared on hosts and native components; intrinsic
Hiccup renders by default.

Related: [SSR and hydration](17-ssr-and-hydration.md), [Interop](09-interop.md#server-policy-per-declaration).

<a id="raw-escape"></a>
### **raw escape** (`:>`)

Hiccup head that passes a React component through without a lasting
[host](#defhost) declaration. Useful once; repeated use graduates to
`defhost` so slots, server policy, and callbacks stay declared.

Related: [Interop](09-interop.md#the-escape-).

---

## Native tier

<a id="native-tier"></a>
### **native tier**

Optional namespace `re-frame.hicasso.native` (alias `n`): explicit exit to
direct React construction and hooks. **`[...]` is always interpreted Hiccup;
`n/$` is always native React.** Neither form rewrites the other; nothing
compiles Hiccup. Absent from bundles that never require the namespace.

Related: [Native tier](10-native-tier.md).

<a id="n-dollar"></a>
### **n/$**

Macro: one explicit element form → direct React construction. No intent
lowering, no controlled repair, no Hiccup children (convert with
[`as-element`](#as-element)). Dynamic props maps must be marked with
[`n/props`](#nprops).

```clojure
(n/$ :td {:class "px"} px)
(n/$ :td (n/props cell-props) px)
```

Related: [Native tier](10-native-tier.md#the-n-grammar).

<a id="nprops"></a>
### **n/props**

Marker that classifies a dynamic expression as the props operand of
[`n/$`](#n-dollar). Emits no runtime wrapper — classification only. An unmarked
dynamic map in second position is a **child**, not props.

Related: [Native tier](10-native-tier.md#the-n-grammar).

<a id="ndefcomponent"></a>
### **n/defcomponent**

Defines a stable top-level native function component: display name, source,
HMR, and one props/children ABI. Default self-contained route for a
[named native island](#native-island). Ordinary React hooks are legal inside;
frame access via [`n/use-sub`](#nuse-sub) / [`n/use-frame`](#nuseframe).

Related: [Native tier](10-native-tier.md).

<a id="nuse-sub"></a>
<a id="nuseframe"></a>
### **n/use-sub** / **n/use-frame**

Native React hooks that join the installed Hicasso frame. Substrate-neutral
(shared spine; no UIx dependency). Use inside [`n/defcomponent`](#ndefcomponent)
or UIx `defui` — not inside a dynamically branched [`defview`](#defview) body.

Related: [Native tier](10-native-tier.md).

<a id="native-island"></a>
### **native island**

A named native component under the same React root and re-frame2 frame as
the rest of the app — hooks, vendor widgets, high-rate mechanics. Authored
with [`n/defcomponent`](#ndefcomponent), UIx, or a JS host. Xray names the
crossing; the inner React tree is [host-opaque](#loss-labels).

Related: [Native tier](10-native-tier.md#rung-4--a-named-native-island).

<a id="nmemo"></a>
<a id="nlazy"></a>
### **n/memo** / **n/lazy**

Marker-preserving memoization and `React.lazy` loading for native components.
Raw `react/memo` / `React.lazy` erase identity metadata that Xray and HMR
need.

Related: [Native tier](10-native-tier.md#keeping-the-marker-the-abi-helpers),
[Code splitting](20-code-splitting.md).

<a id="performance-ladder"></a>
### **performance ladder**

Five explicit rungs from ordinary Hicasso to a full native screen. No
`:fast` flag and no second meaning for Hiccup:

1. Ordinary Hicasso
2. Tuned [read topology](#read-topology)
3. Direct native return (`n/$` from a `defview`)
4. [Named native island](#native-island)
5. Native screen

Related: [Performance](18-performance.md), [Native tier](10-native-tier.md).

<a id="escape-benefit-rule"></a>
### **escape-benefit rule**

An escape stays only if it recovers **≥20%** of the measured interaction,
saves **≥2 ms** at p95, or converts a failed user-visible budget into a pass;
otherwise remove it. Thresholds never widen to keep a red row green.

Related: [Performance](18-performance.md#the-escape-benefit-rule).

---

## State homes

<a id="one-state-owner"></a>
### **one state owner**

Application-visible state lives in re-frame2 [app-db](../../../core/glossary.md#app-db)
only. There is no component-local reactive cell and no second store. Hosts may
hold host-private mechanics (motion, focus, vendor handles) that are never an
invisible duplicate of application facts.

Related: [Ephemeral state](11-ephemeral-state.md).

<a id="pressure-valve"></a>
### **pressure valve**

Named legitimate home for a kind of UI state under [one state owner](#one-state-owner):
explicit app-db address (default); optional forms/draft modules; host-private
React/DOM state; uncontrolled DOM as an explicit interop choice. If a fact
fits no valve, it goes to app-db.

Related: [Ephemeral state](11-ephemeral-state.md).

<a id="overlay"></a>
### **overlay (popover / modal)**

`re-frame.hicasso.overlay` primitives on the browser's native top layer.
`:open?` is app-db data; `:on-dismiss` is an event; the platform owns stacking,
light-dismiss, and focus trap/return. Closed means zero DOM and zero listeners.

Related: [Overlays and focus](12-overlays-and-focus.md).

<a id="route-link"></a>
### **route-link**

Routing helper: navigates by event, optional intent [prefetch](../../../routing/glossary.md#intent-prefetch),
scroll/focus conduct. Plain function (inlines); active state is a subscription
comparison, not a built-in class.

Related: [Routing and navigation](07-routing-and-navigation.md).

<a id="demand-driven-committed-read"></a>
### **demand-driven committed read**

Optional resource read that declares `:demand true`: **commit acquires**,
unmount / param change / stopped read **releases**. Abandoned renders acquire
nothing. Uses existing committed-read membership — no second per-read ledger.
Without `:demand`, a resource sub stays a passive projection.

```clojure
(h/sub [:rf/resource {:resource :app/suggestions
                      :params   {:q q}
                      :demand   true}])
```

Related: [Async resources](08-async-resources.md#demand-driven-committed-reads),
[Resources glossary](../../../resources/glossary.md).

---

## Testing

<a id="test-kit"></a>
### **test kit**

Supported namespace `re-frame.hicasso.test` (alias `ht`): pure data tiers,
[semantic harness](#semantic-harness), mounted facade, and browser helpers.
Product surface, not a loose utility bag.

Related: [Testing](14-testing.md).

<a id="testing-ladder"></a>
### **testing ladder**

Five rungs; use the lowest that can prove the claim:

| Rung | Proves | Mechanism |
|---|---|---|
| L0 | handlers, subs, transitions | pure functions |
| L1 | intents, codecs, revision laws, `n/$` expansion | pure data / property tests |
| L2 | registered hook-free bodies | [semantic harness](#semantic-harness) |
| L3 | lifecycle, hooks, hosts, errors | mounted React DOM |
| L4 | IME, caret, focus, hydration, perf | real browsers |

A green lower rung never claims a higher equality (L2 ≠ hydration bytes).

Related: [Testing](14-testing.md).

<a id="semantic-harness"></a>
### **semantic harness**

L2: `ht/tree` runs a registered hook-free body under injected read fixtures
and returns a semantic tree. Hosts, hooks, raw React, and [`n/$`](#n-dollar) results
are **opaque** and refuse — mount those at L3.

Related: [Testing](14-testing.md#l2--the-semantic-harness).

<a id="mounted-facade"></a>
### **mounted facade**

L3 helpers: isolated-frame mount, hydrate, rerender, dispatch-and-settle,
settle, unmount, `assert-clean!` (residue vs pre-mount baseline after
quiescence).

Related: [Testing](14-testing.md#l3--the-mounted-facade).

<a id="sabotage-control"></a>
### **sabotage control**

Negative twin of an important gate: a deliberate mutation that must make the
gate fail, so an empty population cannot pass green.

Related: [Testing](14-testing.md#the-sabotage-twin).

<a id="canonical-dom"></a>
### **canonical DOM**

Normalized DOM / structure equality used by differential and migration
witnesses. Distinct from semantic-tree equality (L2) and from React server
byte / hydration equality.

Related: [Testing](14-testing.md#canonical-dom),
[Migration from Reagent](19-migration-from-reagent.md).

---

## Diagnostics

<a id="causal-lens"></a>
### **causal lens**

Xray's diagnostic chain:

```
event → subscriptions recomputed → values changed → boundaries notified
      → bodies run → React commit → paint
```

Each link has its own evidence seam. **Render is not commit; commit is not
paint.** Timing proximity alone never proves a link.

Related: [Diagnostics](15-diagnostics.md).

<a id="explain-render"></a>
### **explain-render**

Xray answer to *why did this [boundary](#boundary) run?* — cause kind (reads,
props, context, host, retry/abandonment), current read set, fan-out,
completeness and loss.

Related: [Diagnostics](15-diagnostics.md#explain-render).

<a id="hot-view-advisor"></a>
### **hot-view advisor**

Ranks hot boundaries (time, frequency, read churn, fan-out), **classifies
pressure** (computation, topology, lowering, React, layout), then recommends
the smallest credible remedy. Recommends a native escape only when native
addresses the measured owner; never auto-promotes.

Related: [Diagnostics](15-diagnostics.md#the-hot-view-advisor),
[Performance](18-performance.md).

<a id="loss-labels"></a>
### **loss labels**

Honest gaps instead of empty panels: `:unknown`, `:opaque` /
`:no-static-analysis`, `:host-opaque`, `:cap`, `:uncorrelated`. Unknown is
never encoded as an empty collection.

Related: [Diagnostics](15-diagnostics.md#honest-loss-labels).

<a id="complaint-catalogue"></a>
### **complaint catalogue**

Stable `:rf.error/*` / `:rf.warning/*` ids with cause, recovery, and
jump-to-source in Xray. Every refusal in the guide is an entry; branch on the
id, not prose.

Related: [Diagnostics](15-diagnostics.md#the-complaint-catalogue).

<a id="production-erasure"></a>
### **production erasure**

Dev diagnostics, source maps for complaints, and evidence sentinels are absent
from default production bundles. Budgets and teardown are verified on
production builds.

Related: [Diagnostics](15-diagnostics.md#production-erasure).

---

## Lifecycle and delivery

<a id="mount"></a>
### **mount!** / **render!** / **unmount!**

Root lifecycle. `h/mount!` associates a DOM node, a [frame](../../../core/glossary.md#frame)
id, and optional `:initial-events` (seed app-db before first paint), and
returns an idempotent **root handle**. `render!` re-renders into that handle;
`unmount!` is a no-op if already unmounted (safe for fixtures and reload
hooks).

```clojure
(defonce root
  (h/mount! (js/document.getElementById "app")
            {:frame :rf/default :initial-events [[:app/init]]}
            [app-shell]))
```

Related: [Installation](installation.md).

<a id="hydrate"></a>
### **hydrate!**

Two verbs on one job. **`re-frame.ssr/hydrate!`** installs the server payload
into the client frame (state half). **`h/hydrate!`** adopts the server DOM for
a Hicasso root (DOM half). Server and client share one React renderer;
mismatches surface as hydration errors.

Related: [SSR and hydration](17-ssr-and-hydration.md).

<a id="error-boundary"></a>
### **error-boundary**

`h/error-boundary` — React error region in the tree (`:fallback`,
`:reset-key`, `:on-error`). Distinct from a re-render [boundary](#boundary);
only this component catches throws. Expected failures stay data, not
exceptions.

Related: [Errors](16-errors.md).

<a id="user-visible-budget"></a>
### **user-visible budget**

Performance contract in terms a user can notice (e.g. discrete interaction
paint ≤50 ms p95; controlled echo within one frame; broad ops ≤100 ms p95;
zero teardown residue). Comparative bands and island parity sit beside these;
synthetic scores never redefine "fast enough."

Related: [Performance](18-performance.md#the-budgets).

<a id="shadow-comparison"></a>
### **shadow comparison**

Also called **shadow mode**. Migration witness: dual-render
[canonical DOM](#canonical-dom) / [intent](#intent) diff against a Reagent (or
other) twin so conversion preserves behaviour before codemod. Refusals are
named classes, not silent drift.

Related: [Migration from Reagent](19-migration-from-reagent.md).
