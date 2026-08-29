# Islands

Most of a Hicasso application is interpreted Hiccup: `defview` bodies, `h/sub`
reads, event vectors. Some regions want React itself — a hook, a vendor widget
that keeps its own state, pointer mechanics that update on every move, a screen
that is React-shaped by design. Write those regions in React. An **island** is a
React component, raw or UIx, mounted through `h/defhost` under the same root,
frame and app-db as the Hiccup around it. When it needs Hicasso state, two hooks
join it: `n/use-sub` reads a subscription and `n/use-frame` returns the frame's
operations. That is the whole of `re-frame.hicasso.native`; nothing else lives
there.

`[...]` always means interpreted Hiccup. A React element is never interpreted;
it passes through unchanged. An application that never requires the hooks
namespace includes none of its code.

## Two paths

1. **Write the component in React.** Raw React through `react/createElement`
   (or a `.jsx` file), or UIx. Ordinary React hooks are legal because you own
   the source and its call order.
2. **Mount it through `h/defhost`.** The declaration names the crossing for
   tools and tests and carries its server policy. `[:>]` does the same for a
   one-off without a name ([Interop](09-interop.md)). Both stay under the
   existing root and frame.

Inside the component, `n/use-sub` and `n/use-frame` are how it reaches Hicasso
state. An island that reads nothing needs neither: a vendor widget fed by props
is an island with nothing from this namespace in it.

## The same row three ways

A watchlist row reads one subscription and dispatches one click. As ordinary
Hicasso:

```clojure
(ns app.watchlist.row
  (:require [re-frame.hicasso :as h]))

(h/defview quote-row [{:keys [sym]}]
  (let [{:keys [px up?]} (h/sub [:quotes/row sym])]
    [:tr {:class    (if up? "quote up" "quote down")
          :on-click [:watchlist/select sym]}
     [:td.sym sym]
     [:td.px px]]))
```

The parent renders `[quote-row {:key sym :sym sym}]`. This is where every row
starts, and where most of them stay.

The same row as a UIx island:

```clojure
(ns app.watchlist.row
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.native :as n]
            [uix.core :refer [defui $]]))

(defui quote-row* [{:keys [sym]}]
  (let [{:keys [dispatch]} (n/use-frame)
        {:keys [px up?]}   (n/use-sub [:quotes/row sym])]
    ($ :tr {:class    (if up? "quote up" "quote down")
            :on-click (fn [_] (dispatch [:watchlist/select sym]))}
       ($ :td.sym sym)
       ($ :td.px px))))

(defn quote-row-react [^js props]
  ($ quote-row* {:sym (.-sym props)}))

(h/defhost quote-row quote-row-react)
```

A `defui` reads its props from UIx's own carrier, which only UIx's `$` builds,
so the crossing from a host is one plain function handing the JavaScript props
across. That is the whole cost of the interop, and it is UIx's rather than
Hicasso's.

The same row in raw React:

```clojure
(ns app.watchlist.row
  (:require ["react" :as react]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.native :as n]))

(defn quote-row* [^js props]
  (let [sym                (.-sym props)
        {:keys [dispatch]} (n/use-frame)
        {:keys [px up?]}   (n/use-sub [:quotes/row sym])]
    (react/createElement "tr"
      #js {:className (if up? "quote up" "quote down")
           :onClick   (fn [_] (dispatch [:watchlist/select sym]))}
      (react/createElement "td" #js {:className "sym"} sym)
      (react/createElement "td" #js {:className "px"} px))))

(h/defhost quote-row quote-row*)
```

Both islands mount as `[quote-row {:key sym :sym sym}]`, exactly as the view
did; the parent cannot tell which of the three it is rendering. The second and
third are the same program. Reach for UIx when the region is substantial
React-first code, and for raw React when you would rather not add a dependency.

## Reading and dispatching from an island

`n/use-sub` is a real React hook, so React's rules are the rules: top level of
the component, unconditional, one call per read. It hands
`useSyncExternalStore` the same subscribe and snapshot a boundary reading that
key gets, so the island's read builds the same cell, joins the same reader
membership, is woken by the same commit and appears in the same Xray rosters.
Two calls are two subscriptions, where a body's several `h/sub` reads are one;
an island reading a dozen keys wanted a `defview`.

| Read API | Legal context | Rule |
| --- | --- | --- |
| `h/sub` | synchronous Hicasso view body | ordinary function call; branches, loops and helpers are legal |
| `n/use-sub` | React component | React hook; top level and unconditional |

`n/use-frame` returns `{:frame :dispatch :dispatch-sync :subscribe}` for the
frame the island is mounted in. The map is reference-stable for one frame
incarnation, and it is pinned to the incarnation rather than to the keyword. A
frame keyword is only an address: destroy a frame and recreate it under the
same id and a callback still holding the old operations is refused with
`:rf.error/frame-destroyed` rather than writing to whoever owns the address
now. Take the operations from the live island, never from a global stash.

Both hooks resolve the frame from the island's own place in the tree. No
argument and no option reaches a sibling frame, and rendering outside every
frame refuses with `:rf.error/no-frame-context`.

High-rate work stays in the island. A resize handle keeps the live width in
`react/useState` and dispatches one fact when the pointer is released
([Ephemeral state](11-ephemeral-state.md)).

Hot reload reallocates the component. React sees a new element type and
remounts the subtree, so local hook state resets on save by design. State that
must survive a save belongs in app-db and returns through `n/use-sub`.

An island is Client-only unless its host declares `{:server :render}`. During
server rendering `n/use-sub` performs the same cold snapshot read as `h/sub`
and installs no live subscription ([SSR and hydration](18-ssr-and-hydration.md)).

## A hot view may return a React element

There is one escape short of an island. A `defview` whose Hiccup construction
is the measured cost may return a React element directly. The view keeps its
identity, equality memo, frame, `h/sub` reads, lifecycle and Xray name; only the
returned subtree skips interpretation. Nothing lowers inside that element — no
event vectors, no class collections, no controlled-field repair — so a callback
is a plain function, and it carries the frame by capturing it:

```clojure
(ns app.watchlist.row
  (:require ["react" :as react]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(h/defview quote-row [{:keys [sym]}]
  (let [{:keys [px up?]}   (h/sub [:quotes/row sym])
        {:keys [dispatch]} (rf/capture-frame (h/hframe))]
    (react/createElement "tr"
      #js {:className (if up? "quote up" "quote down")
           :onClick   (fn [_] (dispatch [:watchlist/select sym]))}
      (react/createElement "td" #js {:className "sym"} sym)
      (react/createElement "td" #js {:className "px"} px))))
```

This is the measured direct-return escape. The one record is the package's
budget ledger, row S8: on a 200-boundary mount the direct return recovered
19.2% of mount time, with an observed range of 7.0–31.4% over fifteen rounds —
unresolved against the keep rule below, because the range crosses its line. It
is a real saving on a page made of boundaries and not a promise for yours.

Keep an escape only when the measured interaction improves materially: at
least 20%, at least 2 ms at p95, or enough to move a user-visible budget from
fail to pass. Otherwise remove it. A small explicit diff is easier to maintain
than a permanent second authoring style with no demonstrated benefit.
[Performance](19-performance.md) owns the method, and
[The escape ladder](escape-ladder.md) sets out which descent you are on.

Hooks remain illegal in a `defview` body, which may branch and loop
dynamically. A view that needs one is an island.

## When not to write an island

Do not cross without a reproducible interaction and an attributed owner. Read
placement, unstable props, excessive event volume and uncontrolled DOM size are
fixed at the Hicasso level first.

Keep form controls interpreted. An `<input>` inside an island does not receive
Hicasso's same-turn convergence, selection preservation, IME protection or
`::h/revision` handling, and moving it there does not make typing faster.

Do not create islands for stylistic consistency. A few named crossings are a
boundary; islands throughout the application are a change of view-layer
strategy, and the UIx adapter is the better fit for that.

## Native screens

A canvas editor, diagramming surface or vendor-grid screen may be React-shaped
from its first useful design. Implement that screen in React under the same
adapter, root and frames. This changes only the view implementation for that
screen; it does not justify a second state owner or an independent React root.

An independent root is an isolation decision, not a performance optimisation.

## Verify every crossing

After crossing, rerun the contracts Hicasso can no longer inspect inside the
React subtree:

- DOM and interaction parity
- focus and selection
- frame routing
- SSR and hydration
- cleanup and StrictMode behaviour
- the original performance script

Xray names and times the host and shows the island's `n/use-sub` reads, while
correctly labelling the inner React tree opaque. It does not pretend to inspect
React descendants.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `n/use-sub` or `n/use-frame` raises `:rf.error/no-frame-context` | The component mounted outside a Hicasso frame provider | Mount it under the application root, or use the test kit's provider |
| A click inside an island or a directly returned element does nothing | An event vector or `h/event` at a raw React prop; nothing lowers it there | Dispatch from `n/use-frame` in an island, or from `(rf/capture-frame (h/hframe))` in a view body |
| A `defui` mounted through `h/defhost` sees empty props | UIx reads props from its own carrier, which only `$` builds | Cross through a plain function that calls `$` with the JavaScript props |
| Local island state resets after each code save | Hot reload allocates a new component and React remounts it | Expected; move persistent state to app-db |
| The React rewrite does not improve the measurement | Construction was not the actual cost owner | Remove the escape and return to topology and event attribution |
| A controlled field loses caret or composition behaviour | It was moved into an island | Keep the field interpreted, or implement the full React contract yourself |
