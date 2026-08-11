# Interop

You want a date picker from npm, not a hand-written calendar. Declare the
component once, then use it anywhere a view is legal:

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
  [date-picker {:selected  (h/sub [:task/due-date])
                ;; value-first onChange(date): h/event sees the library's arguments
                :on-change (h/event [date & _] [:task/set-due date])}])
```

Children cross as ordinary hiccup. `h/defhost` is the declared door to
foreign React. The rest of this page is that door's contract.

Why declare at all? A raw JS component in the tree breaks three things:

- A JS require in a shared namespace stops JVM loading and headless tests.
- The node holds an opaque JS object, so `=` tests stop working there.
- Tools get a bare value where they wanted an identity.

`h/defhost` keeps the require in one `.cljs` host namespace. The rest of the
tree stays data.

## What crosses, and how

Declare at top level, never during render. With no options:

| At the crossing | Behaviour |
|---|---|
| Top-level prop names | Canonical React slot names — `:on-change` → `onChange`, `:class` → `className`; `data-*` and `aria-*` stay hyphenated |
| Prop values | Cross by identity — a keyword stays a keyword, a map stays a CLJS map. **No deep conversion**: nested option maps are not camelCased, collections are not translated. When the library wants a JS options object, a camelCase map, or a string, hand it that yourself — `#js {…}`, `clj->js`, `"compact"`, `(name :compact)` |
| HTML-attribute slots | `:class`, `:id`, `:role`, `data-*`, `aria-*` stringify. `:class` gets the same coercion as a native tag: `["btn" nil :on]` joins to `"btn on"` |
| Children | Hiccup, lowered where you wrote them |
| Declared callbacks | The `:callbacks` contract below |
| Declared slots | ReactNode positions — hiccup becomes elements there under the captured frame |
| Server | Client-only, unless the declaration says `:server :render` |

Options: `:callbacks`, `:slots`, `:server`, and `:fallback`. An unknown
option raises `:rf.error/hicasso-host-unknown-option` at declaration. These
also fail at declaration: a bad contract, a contract on `:key` or `:ref`,
duplicate prop spellings, and a component that resolved to `nil` (usually a
`:default` import from a library with no default export). Failures point at
your declaration line.

## Callbacks: `:event`, `:handler`, `:render`

A declared slot carries a contract. The contract is never inferred from an
`on*` name:

```clojure
(h/defhost picker Widget
  {:callbacks {:on-pick       :event
               :on-imperative :handler
               :on-render-row :render}})
```

**`:event`** — works like a native `:on-*`. Write an event vector, or
`h/event` when you need the library's arguments. Foreign invokers pass
*their* arguments — `onPick(value, event)`, `onChange(date)` — and every
argument reaches the `h/event` body in library order.

**`:handler`** — the function crosses by identity, with no wrapper. The
library gets exactly the function you wrote; your return value goes back to
that caller. Use this for imperative APIs such as `open()` and `scrollTo()`.

**`:render`** — the library calls you *during its own render* (`renderRow`,
`renderItem`). The body must be pure, and it returns hiccup only through
`h/as-element`:

```clojure
;; ids came from (h/sub [:feed/ids]) earlier in the body
[virtual-list
 {:item-count (count ids)
  :render-row (h/event [i]
                (h/as-element
                  [:li.row {:on-click [:feed/open (nth ids i)]}
                   (str (nth ids i))]))}]
```

`h/as-element` is the explicit hiccup-to-element conversion. `h/event`
captures the supplying view's frame when the body builds the callback; the
library's later call runs under that frame. Event vectors built in that body
fire later, on the user's click, into the frame of the view that *supplied*
the callback. A dispatch *while* the call runs raises
`:rf.error/hicasso-dispatch-in-render-position` and names the position.

**Write `h/event` whenever the row carries event vectors.** A plain function
is legal at every contract and crosses untouched — no wrapper, and therefore
no frame. That is right when the callback returns markup with no event
vectors in it: a `[some-view {…}]` head resolves the frame React already has
where the library renders it. It is wrong the moment an event vector appears
in the row itself, because turning an event vector into a callback with no
frame raises `:rf.error/hicasso-intent-outside-boundary`.

Two laws close the contract. **The declaration wins at every carrier.**
`:event` accepts an event vector or a key-map. `:handler` and `:render`
refuse the same carrier with
`:rf.error/hicasso-intent-at-a-non-event-contract`. An ordinary unmarked
function crosses untouched at all three. **Event vectors stay event-first**
([Events as data](03-events-as-data.md)). A value-first invoker has no DOM
event at argument one, so a marker-carrying vector refuses with
`:rf.error/hicasso-intent-needs-the-event`. `h/event` is the spelling for
those slots. A bare vector with no marker never touches its arguments, so
`{:on-pick [:city/picked "paris"]}` is legal under any invoker.

## ReactNode slots

Some props are markup positions: a modal's title, a footer, a Suspense
fallback. Declare those props as slots. Hiccup written at a declared slot
then becomes elements under the captured frame:

```clojure
(h/defhost modal Modal
  {:callbacks {:on-close :event}
   :slots     #{:title :footer}})

[modal {:on-close [:dialog/cancel]
        :title    [:h2 "Delete article?"]
        :footer   [:button.danger {:on-click [:article/delete id]} "Delete"]}]
```

Event vectors inside a declared slot fire into the declaring view's frame,
the same as vectors in children. A string or ready React element at a
declared slot passes as-is. React's own wrappers host the same way: declare
`Suspense` once with `:slots #{:fallback}`, and write the fallback as hiccup.

At an **undeclared** prop, hiccup is data. `{:title [:h2 "Tasks"]}` hands the
library the vector `["h2" "Tasks"]`. That is silent, because a vector is a
legal value at a data position — which is why you declare slots, and why the
runtime never guesses them. For a one-off, `(h/as-element [:h2 "Tasks"])`
crosses a real element through any prop.

## Providers and compound components

A library provider is only a component; a compound family is only several
components. Declare each member you use. Crossings are real React elements,
so the library's context flows through unbroken:

```clojure
(h/defhost themed      (.-Provider theme-context) {:server :render})

(h/defhost tabs        Tabs           {:callbacks {:on-value-change :event}})
(h/defhost tab-list    (.-List Tabs))
(h/defhost tab-trigger (.-Trigger Tabs))
```

!!! warning "Write `{:server :render}` on every transparent wrapper"
    Under the default Client-only policy, a crossing renders nothing on the
    server — including children. A Client-only provider therefore silently
    deletes everything beneath it from the response, with no mismatch report.
    Declare `{:server :render}` on every transparent wrapper. For React's own
    context provider, Render is trivially true. Providers whose *value* is
    browser-derived are covered in
    [SSR and hydration](17-ssr-and-hydration.md).

## Server policy, per declaration

Every host states one of two policies.

**Render** — `{:server :render}`. You assert the component renders
deterministic server bytes: one tree across server render, hydration, and
fresh mount.

**Client-only** — the default. The server emits nothing at the crossing; the
component appears when the client adopts it. Until adoption,
`{:fallback [:div.chart-skeleton]}` renders *instead of* the crossing. The
fallback is inert markup: a `defview` or `defhost` head inside one raises
`:rf.error/hicasso-host-fallback-boundary-head`. `:fallback` beside
`{:server :render}`, or any other policy value, raises
`:rf.error/hicasso-host-bad-ssr-policy` at declaration. Full server story:
[SSR and hydration](17-ssr-and-hydration.md).

## Portals

Sometimes markup must land in a different DOM container — a toast rack, a
vendor-owned overlay div — while it stays part of your tree. The portal
helper turns hiccup into React's `createPortal` and keeps the frame:

```clojure
(h/defview save-toast [_]
  [h/portal {:target js/document.body}
   [:div.toast {:on-click [:toast/dismiss]}
    (str (h/sub [:toast/message]))]])
```

Three facts:

- **Events bubble through the React tree, not the DOM tree.** An `:on-click`
  on `save-toast`'s hiccup ancestor sees clicks inside the toast even though
  the toast's DOM lives on `body`. Event vectors fire into the owner's frame
  as usual.
- **A changed `:target` is a remount.** The subtree unmounts and remounts in
  the new container. Keep the target stable.
- **The portal is Client-only.** No DOM target exists on a server, so the
  portal contributes nothing to the response. An explicit `:fallback` may emit
  placeholder markup at the portal's tree position.

For popovers and modals as product UI — anchoring, dismissal, focus — use
[Overlays and focus](12-overlays-and-focus.md). The portal is the raw
mechanism for containers you do not control.

## The escape: `[:>]`

`[:> Component props & children]` is the raw escape: the same foreign path
as `h/defhost`, without a declaration. It exists for two cases. Migration is
the first: Reagent codebases arrive full of `[:>]` sites, and those sites are
legal here. The second is a one-off crossing a declaration cannot express,
such as a component selected at runtime from data:

```clojure
(def widgets {:chart Chart :table Table :map MapView})

(h/defview panel [{:keys [kind]}]
  [:> (get widgets kind) {:series (h/sub [:panel/series kind])}])
```

This is not the performance tier. A measured hot region takes the
[native tier](10-native-tier.md). **Declare what you use twice.** When you
erase the declaration, you lose what it carried:

| A declaration carries | `h/defhost` | `[:>]` |
|---|---|---|
| A crossing name for tools | authored | the constant `"[:>]"` |
| Callback contracts | exact, per slot | none — every prop is unclaimed |
| ReactNode slots | declared | none — hiccup in a prop is data |
| A server policy | Render or Client-only with fallback | fixed Client-only, unspellable |
| Early site for refusals | checked once, at the declaration | every refusal fires at the crossing |
| A `.cljc` quarantine | one host namespace | the require lands in your view namespace |

Every prop at an escape is unclaimed. Two refusals guard silent failures:

- An event vector at an `on*`-spelled prop raises
  `:rf.error/hicasso-host-undeclared-callback`. The runtime never infers a
  contract from a name. Without the refusal, your vector would cross as an
  inert array.
- An `h/event` at any escape prop raises
  `:rf.error/hicasso-host-unclaimed-callback`. The marked form asks its
  position for a contract, and no escape position can answer.

Both name `h/defhost` as the recovery.

A plain function crosses by identity and runs, but carries no frame. By the
time the library calls it the render has unwound, so a bare `rf/dispatch`
raises `:rf.error/no-frame-context`. Recovery is the same as in
[Events as data](03-events-as-data.md): call `(rf/capture-frame)` in the body
and close over its `:dispatch` — until a declared `:event` slot builds that
for you.

The escape renders nothing on the server: no declaration means no
`:fallback`. A transparent pass-through host around it can put placeholder
markup at the site:

```clojure
(h/defhost skeleton-slot (fn [^js p] (.-children p))
  {:fallback [:div.skeleton]})

[skeleton-slot {} [:> (get widgets kind) {:series data}]]
```

On the server, the fallback renders instead of the crossing, children and
all. In the browser, the escape mounts as usual. That is a placeholder, not
server-rendered content — only `{:server :render}` on the real component buys
that.

The component position takes what React accepts as an element type. `nil` —
usual result of a `:default` import from a library with no default export —
raises `:rf.error/hicasso-raw-no-component`. A string, keyword, `defview`
head, `defhost` head, or already-built React *element* raises
`:rf.error/hicasso-raw-not-a-component`. Each refusal carries its own
recovery and fires in your render (on the server too), with your stack at the
line you wrote.

## The outward bridge

Interop runs the other way too. A native React parent — `n/defcomponent`,
UIx, or plain JavaScript — can render a Hicasso view under the existing
frame. There is no second root and no second state owner.

```clojure
;; article-card is an h/defview; hand article-card* to the React side and
;; render it like any component: <ArticleCard articleId={7} />
(def article-card* (h/as-component article-card))
```

`h/as-component` returns a real React component. The parent's props arrive as
the view's ordinary props map: names come back through the canonical rule
(`articleId` → `:article-id`), values cross by identity. The view keeps its
memo wrapper, its reads, its key identity, and its teardown. Its frame comes
from React context, and that context flows through foreign components in
between. When the view renders outside a Hicasso root, mount raises
`:rf.error/no-frame-context`. `h/as-element` and `h/as-component` are the two
halves of one bridge: use the element for a one-shot subtree handed through a
callback; use the component for a view a native parent will mount, key, and
re-render itself.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Library ignores or rejects a prop — keyword, CLJS map, kebab key inside a nested map | Values cross by identity; nested keys are not camelCased; no deep conversion | Hand the library what it documents — string, `#js`, or `clj->js` at the call site |
| Hiccup in a prop renders as array data | Only children and declared slots become elements; undeclared prop is data | Declare the prop in `:slots`, or wrap in `h/as-element` |
| React refuses an object as a child, from a render callback | Body returned a raw vector; `:render` return crosses unconverted | Return through `h/as-element` |
| `:rf.error/hicasso-intent-at-a-non-event-contract` | Event carrier at `:handler` or `:render` | Declare `:event`, or write the real function |
| `:rf.error/hicasso-host-undeclared-callback` at a `[:>]` | Event vector at an `on*`-spelled escape prop | Declare the host and name the prop in `:callbacks` |
| A `[:>]` callback runs, then `:rf.error/no-frame-context` | Plain function carries no frame; render has unwound | `(rf/capture-frame)` in the body; close over its `:dispatch` |
| Namespace does not load on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host namespace |
| `:rf.error/hicasso-host-bad-ssr-policy` at declaration | Policy value outside Render / Client-only, or `:fallback` beside `:server :render` | Two policies; fallback belongs to Client-only |
| Hosted component does not update when app state did | View above bailed out on equal props; host was never re-entered | Put the changing value on the host's *own* props |

## When not to host

`defhost` is for components you do not own. A hot, **hand-written**
component is the [native tier](10-native-tier.md), not a host around your own
code. If the library is a thin wrapper over DOM and twenty lines of hiccup
reproduce it, write the twenty lines. A hosted component is a node your tools
see less of and your tests assert less about. Most applications end with only
a few hard hosted widgets.

## Advanced

### The crossing has no memo wrapper

A `defview` memoizes on its props. A host crossing does not. The foreign
component re-renders whenever the view that wrote it re-renders. Put the
crossing behind a small `defview` of its own if you want that bail-out. The
corollary: a host that must react to a subscription needs the value on its
*own* props. If you read the value one view up and stop there, the host sees
a value that never changes.

### Imperative SDKs

A map SDK, a chart that wants a DOM node, anything that hands you a handle —
that is ordinary host-edge React, not a second interop API. Write a callback
ref. React 19 makes attach and teardown one mechanism: whatever the ref
function returns is its cleanup.

```clojure
;; in app.hosts.map-panel — requires ["some-map-sdk" :as sdk]
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

Four properties matter:

- **The handle is per-attach.** The cleanup that attach returned captures the
  handle. Never hold it in a module-level `defonce`: a hoisted handle lets the
  second attach overwrite and leak the first.
- **Teardown is idempotent.** The `done?` latch guards SDKs whose `destroy`
  throws on a dead handle.
- **A cleanup-returning ref never sees `nil`.** React calls the returned
  cleanup on detach *instead of* a re-invoke with `nil`, so a hand-written
  `nil` branch is dead code.
- **A top-level `defn` keeps the ref identity stable.** A fresh function on
  every render re-attaches every time, so the SDK rebuilds on every keystroke
  elsewhere in the tree.

Under StrictMode (dev), React runs attach → cleanup → attach; this shape
survives: the first handle dies with the cleanup that created it. A ref fires
on attach and detach only, never on a config change — keep config fixed for
the connection's life, and route steady-state change through an ordinary
event and effect. When attach must close over per-instance props (API key,
per-panel config), it needs a stable callback, and hooks do not belong in a
`defview` body. That is the signal the edge has outgrown this recipe: use a
named native component, where hooks are legal
([The native tier](10-native-tier.md)).
