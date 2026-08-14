# Interop

Use `h/defhost` to give a foreign React component a named, testable boundary
with explicit callback, slot, and server contracts.

```clojure
(ns app.hosts.date-picker
  (:require [re-frame.hicasso :as h]
            ["react-datepicker" :default DatePicker]))

(h/defhost date-picker DatePicker
  {:callbacks {:on-change :event}})
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
| Declared callbacks | use the callback contract below |
| Declared slots | Hiccup becomes React elements under the captured frame |
| Server | Client-only unless the declaration says `:server :render` |

When a library expects a JavaScript options object, camelCase nested keys, or a
string instead of a keyword, provide that value explicitly with `#js`,
`clj->js`, a string, or `(name value)`. Hicasso does not guess a library's
data model.

The declaration accepts `:callbacks`, `:slots`, `:server`, and `:fallback`.
`:rf.error/hicasso-host-unknown-option` reports an unknown option. Declaration
also fails for malformed callback contracts, contracts on `:key` or `:ref`,
duplicate prop spellings that normalize to one slot, or a component that
resolved to `nil` — often a mistaken `:default` import. These errors point to
the declaration rather than a later mount.

## Callback contracts

Declare each callback as `:event`, `:handler`, or `:render`. Hicasso never
infers a contract from an `on*` name.

```clojure
(h/defhost picker Widget
  {:callbacks {:on-pick       :event
               :on-imperative :handler
               :on-render-row :render}})
```

### `:event`

An event callback accepts an intent vector or `h/event`. The foreign component
passes its own arguments in its documented order. Use `h/event` for a
value-first callback such as `onChange(date)`:

```clojure
(h/event [date _event]
  [:task/set-due date])
```

A bare intent with no marker is valid even when no DOM event exists because it
does not inspect callback arguments. A marker-bearing intent remains
event-first; if argument one is not a DOM event, Hicasso raises
`:rf.error/hicasso-intent-needs-the-event` and points to `h/event`.

### `:handler`

A handler function passes through by identity. The foreign component receives
the exact function and receives its return value. Use this contract for
imperative callback APIs such as `open`, `scrollTo`, or a predicate.

### `:render`

A render callback runs during the foreign component's React render. It must be
pure and must return a React element, not raw Hiccup:

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
frame. Dispatching while the render callback itself runs raises
`:rf.error/hicasso-dispatch-in-render-position`.

A plain function is legal under any callback contract and passes through
without a wrapper. It is enough when the callback returns a Hicasso view head
whose frame is resolved where React renders it. If the callback's raw Hiccup
contains event vectors, use `h/event`; otherwise conversion has no captured
frame and raises `:rf.error/hicasso-intent-outside-boundary`.

The declared contract always wins. Supplying an intent or key map to a
`:handler` or `:render` position raises
`:rf.error/hicasso-intent-at-a-non-event-contract`.

## ReactNode slots

Declare props whose values are markup positions:

```clojure
(h/defhost modal Modal
  {:callbacks {:on-close :event}
   :slots     #{:title :footer}})

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

(h/defhost tabs Tabs
  {:callbacks {:on-value-change :event}})
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
| callback contracts | every prop is unclaimed |
| ReactNode slot declarations | Hiccup in props remains data |
| selectable server policy and fallback | fixed Client-only with no direct fallback |
| one declaration-time validation site | failures occur at each crossing |
| quarantine of JS require in a host namespace | require remains in the view namespace |

An event vector at an `on*` prop raises
`:rf.error/hicasso-host-undeclared-callback`; an `h/event` at any raw escape
prop raises `:rf.error/hicasso-host-unclaimed-callback`. Both direct the author
to `h/defhost` rather than allowing an inert array or unbound callback.

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

The raw component position must evaluate to a valid React element type. `nil`
raises `:rf.error/hicasso-raw-no-component`. A string, keyword, Hicasso
`defview` head, `defhost` head, or already-built React element raises
`:rf.error/hicasso-raw-not-a-component`. These errors occur at the authored
crossing, including during server render.

## Render a Hicasso view from native React

`h/as-component` converts a Hicasso view head into a real React component for
UIx, `n/defcomponent`, JavaScript, or TypeScript parents:

```clojure
(def article-card*
  (h/as-component article-card))
```

React props return to the Hicasso view as a normal props map with canonical
names (`articleId` becomes `:article-id`) and identity-preserved values. The
view retains its memoization, subscription reads, key identity, teardown, and
frame from React context. Rendering it outside a Hicasso root raises
`:rf.error/no-frame-context`.

Use `h/as-element` for one subtree returned through a callback. Use
`h/as-component` when a native parent will mount, key, and re-render the view
as a component.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A library ignores a keyword, CLJS map, or nested kebab key | Values pass by identity and nested values are not deeply converted | Supply the exact documented JS/string shape with `#js`, `clj->js`, or explicit strings |
| Hiccup in a prop appears as array data | The prop was not declared as a ReactNode slot | Add it to `:slots` or convert that value with `h/as-element` |
| React rejects an object returned by a render callback | Raw Hiccup crossed a `:render` contract | Return `h/as-element` |
| `:rf.error/hicasso-intent-at-a-non-event-contract` | An intent or key map was supplied to a handler/render contract | Declare `:event` or supply the function/value the contract requires |
| `:rf.error/hicasso-host-undeclared-callback` at `[:>]` | An event vector was placed in an unclaimed callback prop | Declare the host and callback contract |
| A raw callback runs and then raises `:rf.error/no-frame-context` | A plain function retained no rendering frame | Capture the frame in the Hicasso body or use a declared event callback |
| A shared namespace fails to load on the JVM | It contains a JavaScript require | Move the require and host declarations to a `.cljs` namespace |
| `:rf.error/hicasso-host-bad-ssr-policy` at declaration | Invalid policy or fallback attached to Render | Use Render or Client-only; fallback belongs only to Client-only |
| A hosted component does not receive changed application state | The surrounding view bailed out and the host's own props did not change | Put every value that drives the host on the host's props |
| A provider's children vanish from server HTML | Transparent wrapper remained Client-only | Declare deterministic wrappers `{:server :render}` |

## When not to host

Use `defhost` for foreign components. Do not wrap application-owned hot code in
a host as a performance technique; the native tier is the measured path for
that case.

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
