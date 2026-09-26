# Islands

Most of a Fresco application is interpreted Hiccup: `defview` bodies, `h/sub`
reads, event vectors. Some regions are better written in React itself: a
component that needs a hook, a vendor widget that keeps its own state, pointer
handling that updates on every move, or a screen that is React-shaped by
design.

An **island** is a React component, raw or UIx, mounted through `h/defhost`
under the same root, frame and app-db as the Hiccup around it. When it needs
Fresco state it uses two hooks from `re-frame.fresco.native`: `n/use-sub` reads
a subscription and `n/use-frame` returns the frame's operations. An island that
reads nothing needs neither; a vendor widget fed by props is an island with
nothing from that namespace in it.

`[...]` always means interpreted Hiccup. A React element is never interpreted;
it passes through unchanged. An application that never requires
`re-frame.fresco.native` includes none of its code.

## The same row three ways

A todo row reads one subscription and dispatches one click. As an ordinary
Fresco view:

```clojure
(ns app.todos.row
  (:require [re-frame.fresco :as h]))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li {:class    (if done? "todo done" "todo")
          :on-click [:todo/toggle id]}
     title]))
```

The parent renders `[todo-row {:key id :id id}]`. Every row starts here, and
most stay here.

The same row as a UIx island:

```clojure
(ns app.todos.row
  (:require [re-frame.fresco :as h]
            [re-frame.fresco.native :as n]
            [uix.core :refer [defui $]]))

(defui todo-row* [{:keys [id]}]
  (let [{:keys [dispatch]}    (n/use-frame)
        {:keys [title done?]} (n/use-sub [:todo/by-id id])]
    ($ :li {:class    (if done? "todo done" "todo")
            :on-click (fn [_] (dispatch [:todo/toggle id]))}
       title)))

(defn todo-row-react [^js props]
  ($ todo-row* {:id (.-id props)}))

(h/defhost todo-row todo-row-react)
```

A `defui` reads its props from UIx's own props object, which only UIx's `$`
builds. So the host mounts a plain function that hands the JavaScript props to
`$`. That wrapper is the only extra code the UIx version needs.

The same row in raw React:

```clojure
(ns app.todos.row
  (:require ["react" :as react]
            [re-frame.fresco :as h]
            [re-frame.fresco.native :as n]))

(defn todo-row* [^js props]
  (let [id                    (.-id props)
        {:keys [dispatch]}    (n/use-frame)
        {:keys [title done?]} (n/use-sub [:todo/by-id id])]
    (react/createElement "li"
      #js {:className (if done? "todo done" "todo")
           :onClick   (fn [_] (dispatch [:todo/toggle id]))}
      title)))

(h/defhost todo-row todo-row*)
```

Both islands mount as `[todo-row {:key id :id id}]`, exactly as the view did;
the parent cannot tell which of the three it renders. Use UIx when the region
is substantial React-first code, and raw React when you would rather not add a
dependency. Ordinary React hooks are legal in either, because you own the
component and its call order.

`h/defhost` names the crossing for tools and tests and carries its server
policy. `[:>]` mounts a one-off without a name ([Interop](09-interop.md)).

## Reading and dispatching from an island

`n/use-sub` is a real React hook, so React's rules apply: call it at the top
level of the component, unconditionally, once per read. It reads through the
same subscription cache a `defview` uses, so the value, the wake-up on commit,
and the entry in Xray are the same.

| Read API | Legal context | Rule |
| --- | --- | --- |
| `h/sub` | synchronous Fresco view body | ordinary function call; branches, loops and helpers are legal |
| `n/use-sub` | React component | React hook; top level and unconditional |

Two `n/use-sub` calls are two React subscriptions, where a `defview` body's
several `h/sub` reads share one. An island that reads a dozen keys should
probably be a `defview`.

`n/use-frame` returns `{:frame :dispatch :dispatch-sync :subscribe}` for the
frame the island is mounted in. The map is the same object on every render, so
it is safe to close over and to use in effect dependencies. If the frame is
destroyed and recreated under the same id, a callback still holding the old
operations raises `:rf.error/frame-destroyed` instead of writing to the new
frame. Take the operations from the live island, never from a global.

Both hooks resolve the frame from the island's position in the tree. No
argument reaches a sibling frame. Rendering outside every frame raises
`:rf.error/no-frame-context`.

Keep high-rate work inside the island. A drag keeps the pointer position in
`react/useState` and dispatches one event when the pointer is released
([Ephemeral state](11-ephemeral-state.md)).

Hot reload creates a new component, so React remounts the subtree and local
hook state resets on save. State that must survive a save belongs in app-db and
comes back through `n/use-sub`.

An island is client-only unless its host declares `{:server :render}`. During
server rendering `n/use-sub` performs the same one-off snapshot read as `h/sub`
and installs no live subscription ([SSR and hydration](18-ssr-and-hydration.md)).

## When not to write an island

Do not cross for speed without a reproducible, measured interaction problem
traced to this region. Read placement, unstable props, excessive event volume and a large
DOM are fixed at the Fresco level first.

Keep form controls interpreted. An `<input>` inside an island loses Fresco's
same-turn value repair, selection preservation, IME protection and
`::h/revision` handling, and moving it there does not make typing faster.

Do not create islands for stylistic consistency. Islands throughout the
application amount to a change of view layer; use the UIx adapter for that.

## Native screens

A canvas editor, diagramming surface or vendor-grid screen may be React-shaped
from its first design. Implement that screen in React under the same adapter,
root and frames. Only the view implementation changes: it does not need a
second state owner or an independent React root. Create an independent root
only when you need isolation; it does not make anything faster.

## Verify every crossing

After crossing, rerun the checks Fresco can no longer perform inside the React
subtree:

- DOM and interaction parity
- focus and selection
- frame routing
- SSR and hydration
- cleanup and StrictMode behaviour
- the original performance script

Xray shows the island's `n/use-sub` reads. It does not time the host, and it
labels the inner React tree as opaque; React DevTools shows the host under its
`defhost` name.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `n/use-sub` or `n/use-frame` raises `:rf.error/no-frame-context` | The component mounted outside a Fresco frame provider | Mount it under the application root; in tests, mount it with `hm/mount!` ([Testing](15-testing.md)) |
| A click inside an island or a directly returned element does nothing | An event vector or `h/event` at a raw React prop, where nothing converts it | Dispatch from `n/use-frame` in an island, or from `(rf/capture-frame)` in a view body |
| A `defui` mounted through `h/defhost` sees empty props | UIx reads props from its own object, which only `$` builds | Mount a plain function that calls `$` with the JavaScript props |
| Local island state resets after each code save | Hot reload creates a new component and React remounts it | Expected; move persistent state to app-db |
| The React rewrite does not improve the measurement | Hiccup construction was not the cost | Remove the escape and look at view structure and event volume |
| A controlled field loses caret or composition behaviour | It was moved into an island | Keep the field interpreted, or implement the full React contract yourself |

## Advanced

### A hot view may return a React element

There is one step short of an island. A `defview` whose Hiccup construction is
the measured cost may return a React element directly. The view keeps its
identity, props memoization, frame, `h/sub` reads, lifecycle and Xray name;
only the returned subtree skips interpretation. Nothing inside that element is
converted (no event vectors, class collections or controlled-field repair), so
a callback is a plain function that carries the frame by capturing it:

```clojure
(ns app.todos.row
  (:require ["react" :as react]
            [re-frame.core :as rf]
            [re-frame.fresco :as h]))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])
        {:keys [dispatch]}    (rf/capture-frame)]
    (react/createElement "li"
      #js {:className (if done? "todo done" "todo")
           :onClick   (fn [_] (dispatch [:todo/toggle id]))}
      title)))
```

In Fresco's own benchmark, on a 200-view mount, the direct return recovered
19.2% of mount time, with an observed range of 7.0–31.4% over fifteen rounds.
Treat that as a possible saving on a page made of many views, not a promise
for yours.

Keep the escape only when the measured interaction improves materially: at
least 20%, at least 2 ms at p95, or enough to move a user-visible budget from
fail to pass. Otherwise remove it; a second authoring style with no measured
benefit is a maintenance cost. [Performance](19-performance.md) describes the
method, and [The escape ladder](escape-ladder.md) lists the options in order.

Hooks remain illegal in a `defview` body, which may branch and loop. A view
that needs a hook is an island.
