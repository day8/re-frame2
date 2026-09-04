# Interop

Use `h/defhost` to give a foreign React component a named, testable boundary,
with its slot and server policy declared once and its callbacks inferred
exactly as on a native tag.

```clojure
(ns app.hosts.date-picker
  (:require [re-frame.hicasso :as h]
            ["react-datepicker" :default DatePicker]))

(h/defhost date-picker DatePicker)
```

```clojure
(ns app.views
  (:require [re-frame.hicasso :as h]
            [app.hosts.date-picker :refer [date-picker]]))

(h/defview due-field [_]
  [date-picker
   {:selected  (h/sub [:task/due-date])
    :on-change (h/event [date & _]
                 [:task/set-due date])}])
```

The declaration keeps npm requires in a `.cljs` host namespace, gives tools a
stable component identity, and records how values cross. Children remain
Hiccup.

A raw component value in a tree would otherwise create three avoidable
problems: a JS require can make a shared `.cljc` namespace unloadable on the
JVM, structural tests encounter an opaque object, and tools cannot name the
crossing.

## Crossing rules

Declare a host at namespace top level, never during rendering.

| Value at the crossing | Behaviour |
| --- | --- |
| Top-level prop names | converted to canonical React slots: `:on-change` → `onChange`, `:class` → `className`; `data-*` and `aria-*` remain hyphenated |
| Prop values | pass by identity; nested maps and collections are not deeply converted |
| HTML-like attribute slots | class/id/role/data/ARIA values use native-attribute coercion; Hicasso class collections are joined |
| Children | Hiccup is converted where it was authored |
| Callbacks | contract inferred from the prop's spelling, as on a native tag — see below |
| Declared slots | Hiccup becomes React elements under the captured frame |
| Server | Client-only unless the declaration says `:server :render` |

When a library expects a JavaScript options object, camelCase nested keys, or a
string instead of a keyword, provide that value explicitly with `#js`,
`clj->js`, a string, or `(name value)`. Hicasso does not guess a library's
data model.

The declaration accepts `:callbacks`, `:slots`, `:server`, and `:fallback`. A
declaration outside that shape — an unknown option, a `:callbacks` value outside
`:event` and `:render`, a malformed `:slots` set — fails with
`:rf.error/hicasso-bad-host-declaration`, the reason naming which; a component
that resolved to `nil` — often a mistaken `:default` import — fails with
`:rf.error/hicasso-host-no-component`. These errors point to the declaration rather than a later
mount.

## Callback contracts

Hicasso infers a callback's contract from the prop's spelling, exactly as it
does on a native tag. An `on*` prop is an **event** position; any other prop
that receives `h/event` is a **render** position; a plain function crosses
untouched anywhere. No declaration is needed for the usual case:

```clojure
(h/defhost date-picker DatePicker)
(h/defhost virtual-list VirtualList)
```

### Event positions

An `on*` prop accepts an intent vector, a key map, or `h/event`. The foreign
component passes its own arguments in its documented order. Use `h/event` for a
value-first callback such as `onChange(date)`:

```clojure
[date-picker {:selected  due-date
              :on-change (h/event [date _event]
                           [:task/set-due date])}]
```

A bare intent with no marker is valid even when no DOM event exists because it
does not inspect callback arguments. A marker-bearing intent remains
event-first; if argument one is not a DOM event, Hicasso raises
`:rf.error/hicasso-intent-needs-the-event` and points to `h/event`.

### Render positions

A callback at any other prop runs during the foreign component's React render.
It must be pure and must return a React element, not raw Hiccup:

```clojure
[virtual-list
 {:item-count (count ids)
  :render-row (h/event [i]
                (h/as-element
                 [:li.row
                  {:on-click [:feed/open (nth ids i)]}
                  (str (nth ids i))]))}]
```

`h/as-element` converts the Hiccup result. `h/event` captures the supplying
view's frame, so event vectors inside that result later dispatch to the correct
frame. The callback itself is pure: its return is the render output, and
nothing is dispatched while it runs.

A plain function is legal at any position and passes through without a wrapper.
It is enough when the callback returns a Hicasso view head whose frame is
resolved where React renders it. If the callback's raw Hiccup contains event
vectors, use `h/event`; otherwise conversion has no captured frame and raises
`:rf.error/hicasso-intent-outside-boundary`. There is no separate "handler"
contract to declare: a plain function is that contract.

### Overriding the spelling

Some libraries name render props `on*`. Fluent UI's `onRenderItem`,
`onRenderCell` and `onRenderHeader` return UI; Ant Design's `onRow` and `onCell`
return props maps, and `onFilter` returns a boolean. The event wrapper returns
`nil`, so an `h/event` at one of those props would blank the list silently.
Declare the override once, on the host:

```clojure
(h/defhost details-list DetailsList
  {:callbacks {:on-render-item :render}})
```

`:callbacks` takes `:event` or `:render` and nothing else, and a declared
contract outranks the spelling. Any other value is refused at the declaration
with `:rf.error/hicasso-bad-host-declaration`. Write the override only
where the spelling is wrong; the usual case needs none.

## ReactNode slots

Declare props whose values are markup positions:

```clojure
(h/defhost modal Modal
  {:slots #{:title :footer}})

[modal
 {:on-close [:dialog/cancel]
  :title    [:h2 "Delete article?"]
  :footer   [:button.danger
             {:on-click [:article/delete id]}
             "Delete"]}]
```

Hiccup in a declared slot becomes a React element under the frame captured by
the declaring view. Event vectors inside it use that frame. Strings and
already-built React elements pass through unchanged.

At an undeclared prop, a Hiccup vector remains data and may silently reach the
library as an array. Declare the slot, or use `h/as-element` for a one-off
conversion. React wrappers such as Suspense use the same model; declare
`:fallback` as a slot.

## Providers and compound components

Declare each member of a provider or compound component family that the
application uses:

```clojure
(h/defhost themed
  (.-Provider theme-context)
  {:server :render})

(h/defhost tabs Tabs)
(h/defhost tab-list (.-List Tabs))
(h/defhost tab-trigger (.-Trigger Tabs))
```

React context flows normally through hosted elements.

!!! warning "Transparent wrappers need an explicit server policy"
    A Client-only host renders neither itself nor its children on the server.
    A provider left at the default can therefore remove an entire subtree from
    the response without creating a hydration mismatch report. Mark a
    deterministic transparent wrapper `{:server :render}`. Browser-derived
    provider values require the SSR pattern described in the hydration
    chapter.

## Server policy

A host has one of two policies:

- **Render** — `{:server :render}` asserts deterministic output across server
  render, hydration, and fresh client mount.
- **Client-only** — the default. The server omits the crossing. Optional
  `:fallback` Hiccup appears at the crossing until the client adopts it.

A fallback must be inert markup. A `defview` or `defhost` head inside it raises
`:rf.error/hicasso-host-fallback-boundary-head`. Combining `:fallback` with
`:server :render`, or supplying another policy value, raises
`:rf.error/hicasso-host-bad-ssr-policy` at declaration.

## Portals

Use `h/portal` when Hiccup must render into another DOM container while
remaining part of the same React tree and frame:

```clojure
(h/defview save-toast [_]
  [h/portal {:target js/document.body}
   [:div.toast
    {:on-click [:toast/dismiss]}
    (str (h/sub [:toast/message]))]])
```

Portal behaviour:

- Events bubble through the React tree rather than the DOM ancestry, and event
  vectors keep the owner's frame.
- Changing `:target` remounts the portal subtree. Keep the target stable when
  identity or local browser state matters.
- Portals are Client-only because the server has no DOM target. An explicit
  fallback may emit placeholder markup at the portal's source-tree position.

Use the overlays module for product modals and popovers; it adds anchoring,
dismissal, and focus policy. A portal is the lower-level container mechanism.

## Raw `[:>]` escape

`[:> Component props & children]` crosses to a foreign component without a
declaration. It exists for migration and genuinely one-off dynamic component
selection:

```clojure
(def widgets
  {:chart Chart
   :table Table
   :map   MapView})

(h/defview panel [{:keys [kind]}]
  [:> (get widgets kind)
   {:series (h/sub [:panel/series kind])}])
```

Declare a component once it appears more than once. The raw escape loses:

| Contract carried by `h/defhost` | Raw `[:>]` |
| --- | --- |
| authored name for tools | constant `"[:>]"` |
| a `:callbacks` override for an on*-named render prop | none; the spelling decides |
| ReactNode slot declarations | Hiccup in props remains data |
| selectable server policy and fallback | fixed Client-only with no direct fallback |
| one declaration-time validation site | failures occur at each crossing |
| quarantine of JS require in a host namespace | require remains in the view namespace |

Callbacks are inferred from the spelling on a raw escape exactly as on a
declared host: an intent vector or `h/event` at an `on*` prop dispatches into
the writing view's frame, and `h/event` at any other prop is a frame-carrying
render callback. What the escape cannot express is the override for an
on*-named render prop and a ReactNode slot, where `h/event` is refused with
`:rf.error/hicasso-host-unclaimed-callback`; both need `h/defhost`.

A plain function still crosses by identity but carries no frame. Ambient
`rf/dispatch` from that function later raises `:rf.error/no-frame-context`.
Capture the current frame during the body and close over its `:dispatch`, or
replace the escape with a declared `:event` callback.

A transparent Client-only wrapper can provide server placeholder markup around
an escape:

```clojure
(h/defhost skeleton-slot
  (fn [^js props]
    (.-children props))
  {:fallback [:div.skeleton]})

[skeleton-slot {}
 [:> (get widgets kind) {:series data}]]
```

The fallback is a placeholder, not server rendering of the foreign component.
Only a declaration on the real component with `{:server :render}` makes that
claim.

The raw component position must evaluate to a valid React element type. `nil` —
usually a mistaken `:default` import — and a Hicasso `defview` or `defhost` head
raise `:rf.error/hicasso-raw-not-a-component` at the authored crossing,
including during server render. Any other invalid type is React's own error at
render.

## Render a Hicasso view from native React

`h/as-component` converts a Hicasso view head into a real React component for
Reagent, UIx, raw React, JavaScript, or TypeScript parents:

```clojure
(def article-card*
  (h/as-component article-card))
```

React props return to the Hicasso view as a normal props map with canonical
names (`articleId` becomes `:article-id`) and identity-preserved values. The
view retains its memoization, subscription reads, key identity, teardown, and
frame from React context. Rendering it outside every frame raises
`:rf.error/no-frame-context` — every frame, not every Hicasso root. `h/mount!`,
`rf/frame-provider` and `rf/frame-root` all write the same frame context, so a
bridged view inside a Reagent or UIx tree resolves that tree's frame and needs
no root of its own.

**A Reagent parent converts the props before that decode.** Identity is
preserved across the decode, which is the second half of the crossing; the
first half belongs to the parent. Props on this route travel through React, so
a Reagent parent converts them exactly as it does for any other `[:>]`
crossing: a keyword becomes its name, a map becomes a camel-cased JavaScript
object, any other collection is deeply `clj->js`'d, and strings, numbers,
booleans, `nil` and functions cross unchanged. Prop *names* survive the round
trip — `:article-id` is camel-cased on the way out and read back as
`:article-id` — but values do not. Cross an id and read the rest with `h/sub`
and the conversion never arises: [Views shared across the
boundary](20-migration-from-reagent.md#views-shared-across-the-boundary).

Use `h/as-element` for one subtree returned through a callback. Use
`h/as-component` when a native parent will mount, key, and re-render the view
as a component.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A library ignores a keyword, CLJS map, or nested kebab key | Values pass by identity and nested values are not deeply converted | Supply the exact documented JS/string shape with `#js`, `clj->js`, or explicit strings |
| Hiccup in a prop appears as array data | The prop was not declared as a ReactNode slot | Add it to `:slots` or convert that value with `h/as-element` |
| React rejects an object returned by a render callback | Raw Hiccup crossed a render position | Return `h/as-element` |
| A list renders nothing at an on*-named render prop | The spelling inferred the event contract, whose wrapper returns `nil` | Declare `{:callbacks {:on-render-item :render}}` on the host |
| `:rf.error/hicasso-bad-host-declaration` at declaration | A `:callbacks` value outside `:event` and `:render` — `:handler` included — or another declaration-shape fault the reason names | A plain function is the handler contract; declare `:event` or `:render` only where the spelling is wrong |
| A raw callback runs and then raises `:rf.error/no-frame-context` | A plain function retained no rendering frame | Capture the frame in the Hicasso body or use a declared event callback |
| A shared namespace fails to load on the JVM | It contains a JavaScript require | Move the require and host declarations to a `.cljs` namespace |
| `:rf.error/hicasso-host-bad-ssr-policy` at declaration | Invalid policy or fallback attached to Render | Use Render or Client-only; fallback belongs only to Client-only |
| A hosted component does not receive changed application state | The surrounding view bailed out and the host's own props did not change | Put every value that drives the host on the host's props |
| A provider's children vanish from server HTML | Transparent wrapper remained Client-only | Declare deterministic wrappers `{:server :render}` |

## When not to host

Use `defhost` for foreign components. Do not wrap application-owned hot code in
a host as a performance technique. Speed is decided by measurement
([Performance](19-performance.md)); when a measured region does move to React,
[Islands](10-native-tier.md) shows the crossing.

If a library is a thin wrapper over a small amount of ordinary Hiccup, writing
that Hiccup may provide better testability and diagnostics. Hosted widgets are
more opaque to structural tests and tools, so keep crossings limited to places
where the foreign implementation is worth that cost.

## Advanced

### Host crossings do not add a Hicasso memo wrapper

A hosted component is re-entered whenever the Hicasso view that authored it
re-renders. Put it behind a small `defview` when an equal-props bail-out is
useful. Conversely, state that must update the host must appear on its own
props; reading a value elsewhere without passing it cannot update the host.

### Imperative SDKs with callback refs

A DOM-attached SDK can use a callback ref whose return value performs cleanup:

```clojure
(defn- attach-map [node]
  (let [handle (sdk/mount node)
        done?  (volatile! false)]
    (fn cleanup []
      (when-not @done?
        (vreset! done? true)
        (sdk/destroy handle)))))

(h/defview map-panel [_]
  [:div.map {:ref attach-map}])
```

Keep the handle in the closure returned by that attachment rather than a
module-level `defonce`. Make cleanup idempotent for SDKs that reject repeated
destroy calls. React invokes the returned cleanup instead of calling the ref
again with `nil`. A top-level function keeps ref identity stable and avoids
reattaching on every render.

StrictMode performs attach, cleanup, then attach in development; each handle is
cleaned by the closure that created it. A ref does not rerun merely because a
configuration value changed. Keep attachment configuration stable and send
steady-state updates through events/effects. If attachment must close over
per-instance props with hook-managed identity, move the edge into a named
native component; hooks do not belong in a `defview` body.
