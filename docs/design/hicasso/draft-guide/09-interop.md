# Interop

You want a date picker from npm, not a hand-written calendar. Declare the
component once. Then use it anywhere a view is legal:

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

> **Declare the host once. Then use it as an ordinary view head.**

Children cross as ordinary hiccup, and they lower where they render.
[`h/defhost`](glossary.md#defhost) is the one declared door to foreign React. Everything else on this
page is that door's contract, seen from a different side.

Why declare at all? A raw JS component dropped into the tree breaks three
things at once:

- A JS require in a shared namespace stops JVM loading, and it stops your
  headless tests with it.
- The node holds an opaque JS object, so `=` tests stop working at that point.
- Tools get a bare value where they wanted an identity.

[`h/defhost`](glossary.md#defhost) quarantines the require in one `.cljs` host namespace. The rest of
the tree stays data. You pay the friction once, and every use after that is
free.

## What crosses, and how

Mint a declaration at top level, never during render. With no options:

| At the crossing | Behaviour |
|---|---|
| Top-level prop names | canonical React slot names — `:on-change` → `onChange`, `:class` → `className`; `data-*` and `aria-*` stay hyphenated |
| Prop values | cross by identity — a keyword stays a keyword, and a map stays a ClojureScript map. There is **no deep conversion**: nested option maps are not camelCased, and collections are not translated (a guess about which nested maps are options and which are data would be a support trap). When the library documents a JavaScript options object, a camelCase map, or a string, hand it exactly that yourself — `#js {…}`, `clj->js`, `"compact"`, `(name :compact)` |
| HTML-attribute slots | `:class`, `:id`, `:role`, `data-*`, `aria-*` stringify — an HTML attribute is the destination, and a string is the only representation there. `:class` gets the same coercion as a native tag: `["btn" nil :on]` joins to `"btn on"` |
| Children | hiccup, lowered where you wrote them |
| Declared callbacks | the `:callbacks` contract below |
| Declared slots | ReactNode positions — hiccup lowers there under the captured frame |
| Server | Client-only, unless the declaration says `:server :render` |

The declaration takes four options: `:callbacks`, `:slots`, `:server`, and
`:fallback`. The declaration refuses an unknown option at mint
(`:rf.error/hicasso-host-unknown-option`); it does not ignore the option.
These mistakes also fail at the declaration:

- a bad contract
- a contract on `:key` or `:ref`
- duplicate prop spellings
- a component that resolved to `nil` — the usual cause is a `:default` import
  from a library with no default export

At the declaration, your stack trace points at your line.

## Callbacks: `:event`, `:handler`, `:render`

A declared slot carries a contract. The contract is never inferred from an
`on*` name:

```clojure
(h/defhost picker Widget
  {:callbacks {:on-pick       :event
               :on-imperative :handler
               :on-render-row :render}})
```

**`:event`** — this slot works as a native `:on-*` does. Write an [intent](glossary.md#intent)
vector, or write [`h/event`](glossary.md#hevent) when you need the library's arguments. Foreign
invokers pass *their* arguments — `onPick(value, event)`, `onChange(date)` —
and every argument reaches the [`h/event`](glossary.md#hevent) body in library order.

**`:handler`** — the function crosses by identity, with no wrapper. The
library gets exactly the function that you wrote, and your return value goes
back to that caller. Use this contract for imperative APIs such as `open()`
and `scrollTo()`. [Hicasso](glossary.md#hicasso) does not invent meaning for those calls.

**`:render`** — the library calls you *during its own render* (`renderRow`,
`renderItem`). The body must be pure, and it returns hiccup only through
[`h/as-element`](glossary.md#as-element):

```clojure
;; ids came from (h/sub [:feed/ids]) earlier in the body
[virtual-list
 {:item-count (count ids)
  :render-row (fn [i]
                (h/as-element
                  [:li.row {:on-click [:feed/open (nth ids i)]}
                   (str (nth ids i))]))}]
```

[`h/as-element`](glossary.md#as-element) is the one explicit hiccup-to-element conversion, and the
declared position supplies the frame. The row built in that body carries its
[intents](glossary.md#intent). The intents fire later, on the user's click, into the frame of the
[boundary](glossary.md#boundary) that *supplied* the callback. A dispatch *while* the call runs raises
`:rf.error/hicasso-dispatch-in-render-position`, and the error names the
position.

Two laws close the contract. First, **the declaration wins at every carrier**.
The `:event` contract accepts an intent vector or a key-map. The `:handler`
and `:render` contracts refuse the same carrier with
`:rf.error/hicasso-intent-at-a-non-event-contract`. An ordinary unmarked
function crosses untouched at all three contracts. Second, **intent vectors
stay event-first** ([Events as data](03-events-as-data.md)). A value-first
invoker has no DOM event at argument one, so a marker-carrying intent refuses
with `:rf.error/hicasso-intent-needs-the-event`. [`h/event`](glossary.md#hevent) is the spelling for
those slots. A bare intent with no marker never touches its arguments, so
`{:on-pick [:city/picked "paris"]}` is legal under any invoker.

## ReactNode slots

Some props are not data. They are markup positions: a [modal](glossary.md#overlay)'s title, a footer,
a Suspense fallback. Declare those props as slots. Hiccup written at a
declared slot then lowers under the captured frame:

```clojure
(h/defhost modal Modal
  {:callbacks {:on-close :event}
   :slots     #{:title :footer}})

[modal {:on-close [:dialog/cancel]
        :title    [:h2 "Delete article?"]
        :footer   [:button.danger {:on-click [:article/delete id]} "Delete"]}]
```

Intents inside a declared slot fire into the declaring [boundary](glossary.md#boundary)'s frame,
exactly as [intents](glossary.md#intent) in children do. A string or a ready React element at a
declared slot passes as-is. React's own wrappers host the same way: declare
`Suspense` once with `:slots #{:fallback}`, and write the fallback as hiccup.

At an **undeclared** prop, hiccup is data. `{:title [:h2 "Tasks"]}` hands the
library the vector `["h2" "Tasks"]`. This happens silently, because a vector
is a legal value at a data position. That is why you declare slots, and why
the runtime never guesses them. For a one-off, `(h/as-element [:h2 "Tasks"])`
crosses a real element through any prop.

## Providers and compound components

A library provider is only a component, and a compound family is only several
components. Declare each member that you use. The crossings are real React
elements, so the library's context flows through them unbroken:

```clojure
(h/defhost themed      (.-Provider theme-context) {:server :render})

(h/defhost tabs        Tabs           {:callbacks {:on-value-change :event}})
(h/defhost tab-list    (.-List Tabs))
(h/defhost tab-trigger (.-Trigger Tabs))
```

!!! warning "Write `{:server :render}` on every transparent wrapper"
    Under the default Client-only policy, a crossing renders nothing on the
    server — and "nothing" includes the children. A Client-only provider
    therefore silently deletes everything beneath it from the response, and
    the runtime reports no mismatch. So declare `{:server :render}` on every
    transparent wrapper. For React's own context provider, Render is trivially
    true. The full story — including the provider whose *value* is
    browser-derived and has no server answer — is
    [SSR and hydration](17-ssr-and-hydration.md)'s.

## Server policy, per declaration

Every host states one of two policies.

**Render** — `{:server :render}`. You assert that the component renders
deterministic server bytes. The component is one tree across server render,
hydration, and fresh mount.

**Client-only** — the default. The server emits nothing at the crossing, and
the component appears when the client adopts it. Until adoption,
`{:fallback [:div.chart-skeleton]}` renders *instead of* the crossing. The
fallback is inert markup: the declaration refuses a [`defview`](glossary.md#defview) or [`defhost`](glossary.md#defhost)
head inside one (`:rf.error/hicasso-host-fallback-boundary-head`). `:fallback`
beside `{:server :render}`, or any other policy value, refuses at mint with
`:rf.error/hicasso-host-bad-ssr-policy`. The full server story is
[SSR and hydration](17-ssr-and-hydration.md).

## Portals

Sometimes markup must land in a different DOM container — a toast rack, a
vendor-owned [overlay](glossary.md#overlay) div — while it stays part of your tree. The [portal](glossary.md#portal) helper
lowers hiccup into React's `createPortal` and preserves the frame:

```clojure
(h/defview save-toast [_]
  [h/portal {:target js/document.body}
   [:div.toast {:on-click [:toast/dismiss]}
    (str (h/sub [:toast/message]))]])
```

Keep three facts straight:

- **Events bubble through the React tree, not the DOM tree.** A `:on-click`
  on `save-toast`'s hiccup ancestor sees clicks inside the toast, even though
  the toast's DOM lives on `body`. Intents fire into the owner's frame as
  usual.
- **A changed `:target` is a remount.** The subtree unmounts and remounts in
  the new container. Keep the target stable.
- **The [portal](glossary.md#portal) is Client-only.** No DOM target exists on a server, so the
  portal contributes nothing to the response. An explicit `:fallback` may emit
  placeholder markup at the portal's tree position.

For popovers and modals as a product — anchoring, dismissal, focus — use
[Overlays and focus](12-overlays-and-focus.md). The portal is the raw
mechanism for containers that you do not control.

## The escape: `[:>]`

`[:> Component props & children]` is the [raw escape](glossary.md#raw-escape):
the same foreign path as [`h/defhost`](glossary.md#defhost), with the
declaration erased. It exists for two
cases. Migration is the first: Reagent codebases arrive full of `[:>]` sites,
and those sites are legal here. The second is the one-off crossing that a
declaration cannot express, such as a component selected at runtime from data:

```clojure
(def widgets {:chart Chart :table Table :map MapView})

(h/defview panel [{:keys [kind]}]
  [:> (get widgets kind) {:series (h/sub [:panel/series kind])}])
```

The escape is **not** the performance tier. A measured hot region takes the
[native tier](10-native-tier.md)'s visibly different language instead.
**Declare what you use twice.** When you erase the declaration, you lose
exactly what the declaration carried:

| A declaration carries | [`h/defhost`](glossary.md#defhost) | `[:>]` |
|---|---|---|
| A crossing name for tools | authored | the constant `"[:>]"` |
| Callback contracts | exact, per slot | none — every prop is unclaimed |
| [ReactNode slots](glossary.md#reactnode-slot) | declared | none — hiccup in a prop is data |
| A [server policy](glossary.md#server-policy) | Render or Client-only with fallback | fixed Client-only, unspellable |
| An early site for refusals | checked once, at the declaration | every refusal fires at the crossing |
| A `.cljc` quarantine | one host namespace | the require lands in your view namespace |

Every prop at an escape is unclaimed. Two refusals guard the silent failures
that would follow:

- An [intent](glossary.md#intent) vector at an `on*`-spelled prop refuses with
  `:rf.error/hicasso-host-undeclared-callback`. The runtime never infers a
  contract from a name. Without the refusal, your intent would cross as an
  inert array.
- An [`h/event`](glossary.md#hevent) at any escape prop refuses with
  `:rf.error/hicasso-host-unclaimed-callback`. The marked form asks its
  position for a contract, and no escape position can answer.

Both refusals name [`h/defhost`](glossary.md#defhost) as the recovery.

A plain function crosses by identity and runs. But it carries no frame, and
the render has unwound by the time the library calls it. A bare `rf/dispatch`
inside therefore raises `:rf.error/no-frame-context`. The recovery is
[Events as data](03-events-as-data.md)'s: call `(rf/capture-frame)` in the
body, and close over its `:dispatch` — until a declared `:event` slot builds
that for you.

The escape renders nothing on the server: it has no declaration, so it has no
`:fallback`. But a transparent pass-through host around it puts placeholder
markup at the site:

```clojure
(h/defhost skeleton-slot (fn [^js p] (.-children p))
  {:fallback [:div.skeleton]})

[skeleton-slot {} [:> (get widgets kind) {:series data}]]
```

On the server, the fallback renders instead of the crossing, children and all.
In the browser, the escape mounts as usual. This gives a placeholder, never
server-rendered content: only `{:server :render}` on the real component buys
that.

The component position takes what React accepts as an element type. `nil` —
the usual result of a `:default` import from a library with no default
export — is refused as `:rf.error/hicasso-raw-no-component`. A string, a
keyword, a [`defview`](glossary.md#defview) head, a [`defhost`](glossary.md#defhost) head, or an already-built React
*element* is refused as `:rf.error/hicasso-raw-not-a-component`. Each refusal
carries its own recovery. Each fires in your render, on the server too, with
your stack pointing at the line that you wrote.

## The outward bridge

Interop runs in the other direction too. A native React parent —
[`n/defcomponent`](glossary.md#ndefcomponent), UIx, or plain JavaScript — renders a minted [Hicasso](glossary.md#hicasso) view
under the existing frame. There is no second root and no second state owner.

```clojure
;; article-card is an ordinary h/defview; hand article-card* to the React
;; side and render it like any component: <ArticleCard articleId={7} />
(def article-card* (h/as-component article-card))
```

[`h/as-component`](glossary.md#outward-bridge) returns a real React component. The parent's props arrive as
the view's ordinary props map: names come back through the canonical rule
(`articleId` → `:article-id`), and values cross by identity. The view keeps
its memo wrapper, its reads, its key identity, and its teardown law. Its frame
comes from React context, and that context flows through any foreign
components in between. When the view renders outside a [Hicasso](glossary.md#hicasso) root, the
mount refuses with `:rf.error/no-frame-context`. [`h/as-element`](glossary.md#as-element) and
[`h/as-component`](glossary.md#outward-bridge) are the two halves of one seam. Use the element for a
one-shot subtree handed through a callback. Use the component for a view that
a native parent will mount, key, and re-render itself.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Library ignores or rejects a prop — a keyword, a ClojureScript map, a kebab key inside a nested map | Values cross by identity; nested keys are not camelCased; deep conversion is not offered | Hand the library what it documents — the string, `#js`, or `clj->js`, at the call site |
| Hiccup passed in a prop renders as array data | Only children and declared slots lower; an undeclared prop is data | Declare the prop in `:slots`, or wrap in [`h/as-element`](glossary.md#as-element) |
| React refuses an object as a child, from a render callback | The body returned a raw vector; a `:render` return crosses unconverted | Return through [`h/as-element`](glossary.md#as-element) |
| `:rf.error/hicasso-intent-at-a-non-event-contract` | Intent carrier at `:handler` or `:render`; neither dispatches | Declare `:event`, or write the real function |
| `:rf.error/hicasso-host-undeclared-callback` at a `[:>]` | Intent at an `on*`-spelled escape prop; contracts are never inferred | Declare the host and name the prop in `:callbacks` |
| A `[:>]` callback runs, then `:rf.error/no-frame-context` | A plain function carries no frame; the render has unwound | `(rf/capture-frame)` in the body; close over its `:dispatch` |
| Namespace does not load on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host namespace |
| `:rf.error/hicasso-host-bad-ssr-policy` at mint | A policy value outside Render / Client-only, or `:fallback` beside `:server :render` | Two policies; the fallback belongs to Client-only |
| Hosted component does not update when the app state did | The [boundary](glossary.md#boundary) above bailed out on equal props; the host was never re-entered | Put the changing value on the host's *own* props |

## When not to host

[`defhost`](glossary.md#defhost) is for components that you do not own. A hot, **hand-written**
component is the [native tier](10-native-tier.md)'s job, not a host
declaration around your own code. If the library is a thin wrapper over DOM,
and twenty lines of hiccup reproduce it, write the twenty lines. A hosted
component is a node that your tools see less of and that your tests assert
less about. Most applications end with only two or three hard hosted widgets.

## Advanced

### The crossing has no memo wrapper

A [`defview`](glossary.md#defview) [boundary](glossary.md#boundary) memoizes on its props. A host crossing does not. The
foreign component re-renders whenever the boundary that wrote it re-renders.
The bail-out you want is the enclosing boundary's: put the crossing behind a
small `defview` of its own. The corollary applies in reverse. A host that must
react to a subscription needs the value on its *own* props. If you read the
value one boundary up and stop there, the host sees a value that never
changes.

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

Four properties matter here, and each one blocks a real defect:

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

Under StrictMode (dev), React runs attach → cleanup → attach, and this shape
survives: the first handle dies with the cleanup that created it. A ref fires
on attach and detach only, never on a config change. Therefore keep the config
fixed for the connection's life, and route steady-state change through an
ordinary event and effect. Sometimes the attach must close over per-instance
props — an API key, a per-panel config. Then it needs `react/useCallback` to
stay stable, and hooks do not belong in a [`defview`](glossary.md#defview) body. That is the signal
that the edge has outgrown this recipe. It wants a named native component,
where hooks are legal and the same ref shape carries over
([The native tier](10-native-tier.md)).
