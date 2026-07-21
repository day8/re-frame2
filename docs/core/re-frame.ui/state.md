# State: the three inputs

A view has **three inputs**, each with exactly one spelling:

| Question | Answer |
|---|---|
| Shared application state? | `(sub [:query …])` |
| Given by my parent? | props |
| Ephemeral, mine, gone on unmount? | `(ui/local initial)` |

There is no fourth — and that is the feature. No ratoms, cursors, reactions, or
external stores. State that matters lives in [app-db](../app-db.md), where events,
time-travel, and [Xray](../observability.md) can all see it.

Start with `sub` and props: most views want nothing else. The sections below take
each input in turn, then add `effect` and `dispatch-fn` for the times a view must
reach the world outside the tree.

## Shared state: `sub`

```clojure
(ui/defview order-summary [{:keys [order-id]}]
  (let [order (sub [:orders/by-id order-id])
        total (sub [:orders/total order-id])]
    [:div
     [:h3 (:title order)]
     [:strong (str "$" total)]]))
```

- `(sub …)` returns the **current value** and re-renders this view when it changes —
  the contract the [reactivity page](reactivity-and-ownership.md) unpacks.
- Parametric queries just work: when `order-id` changes, the old target releases and
  the new one acquires atomically. You never see order A's data against order B's id,
  even for one frame.
- **Conditional reads are legal.** `sub` is not a hook — a collapsed row that reads
  nothing genuinely subscribes to nothing:

```clojure
(let [details (when expanded? (sub [:orders/details id]))] …)   ; fine
```

The *one* restriction is loops: a `(sub …)` inside `(for …)` is a compile error,
because a view's read sites must be finite. The fix is the natural factoring —
a keyed child view that subscribes for one row:

```clojure
(ui/defview order-row [{:keys [id]}]
  [:li (:title (sub [:orders/by-id id]))])

(ui/defview order-list []
  [:ul (for [id (sub [:orders/visible-ids])]
         [order-row {:key id :id id}])])
```

Keep real computation in `rf/reg-sub` — shared, cached, visible to tools, and
testable on the JVM ([Subscriptions](../subscriptions.md)). Views do presentation
math only. Outside a view — in a test or a tool — use core's `rf/subscribe`;
`(sub …)` is a view form and fails loudly anywhere else.

## Props

Props arrive as one map, destructured in the header, and are compared **by value,
per slot** to decide re-renders — the memoisation story from the
[mental model](mental-model.md).

- Prefer named slots (`:keys`) over `:as` — `:as` materialises the whole map and
  switches the view to generic comparison, a visible dev cost.
- Children arrive as `:children`; declaring that binding is what opts a view into
  accepting them. Passing children to a view that never declared them is a compile
  error.
- `:key` is reserved for React's list-identity slot — it is never one of your own
  props.

## Local ephemera: `local`

Some state has no business in app-db: uncommitted field text, an open/closed
disclosure, a hover flag. That is `local` — host component-local state that lives
**outside** re-frame2's epochs, is invisible to subs and tools, and re-renders only
this view.

```clojure
(ui/defview search-box []
  (let [[text set-text] (ui/local "")]
    [:div.search
     [:input {:value text
              :on-input (ui/event [e] (set-text (.. e -target -value)) nil)}]
     [:button {:on-click [:search/run text]} "Search"]]))
```

`(ui/local initial)` returns a **three-tuple** `[value set! update!]`; two-element
destructuring, as above, stays valid when you don't need the updater.

- `set!` stores its argument **exactly** — a stored function is a value, never a
  React-style updater.
- `update!` applies `(f current & args)` to the **latest** host state, so several
  same-turn writers (a key handler, a pointer handler, a timer) compose instead of
  losing writes.
- Both are **host-only**: call them from a committed handler or an `effect`
  callback, never during render.

Note the seam in the search box: the *keystroke* stays local; the *intent*
(`[:search/run text]`) is data — the moment the draft crosses into an event vector
is exactly the moment it becomes product state. When another view must react to
every keystroke, the keystroke already *is* product state — skip `local` and
dispatch placeholders instead:

```clojure
[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]
```

`local` is **forbidden** for anything needing cross-view observation, replay,
persistence, schema or tool inspection, or subscription-derived computation — a
loading flag is the classic offender. When in doubt, prefer app-db: a value wrongly
kept local is invisible to tools and unrecoverable on replay; a value in app-db that
turns out never to be read is merely slightly verbose. The
[Where should this value live?](../where-state-lives.md) page walks the fuller
decision.

## The world outside: `effect` and `dispatch-fn`

`effect` synchronises with the host world — DOM measurement, a chart or animation
library attached through a ref. Never app logic; that is events and
[effects](../effects.md).

```clojure
(ui/defview chart [{:keys [series]}]
  (let [node (ui/ref)]                     ; the DOM-node ref (object ref)
    (ui/effect [node series]              ; value deps, compared by rf=
      (when-let [el (.-current node)]     ; read the node from .current
        (draw! el series)
        #(destroy! el)))                  ; optional cleanup fn
    [:canvas {:ref node}]))
```

`(ui/ref)` is the everyday **"I need the DOM node"** primitive — the object ref,
bound in the top-region `let`, handed to `:ref`, and read via `(.-current node)`
from the effect (it attaches at commit, *before* the effect fires). Assignment
never re-renders (contrast `local`). For the expert case where the node's
*identity* change must itself trigger work, a `(ui/raw-fn f)` callback ref is the
honest seam.

Deps are **values** — a rebuilt-but-equal vector is the same dep. No identity
traps, no `useCallback`, no stale closures. The returned cleanup runs on dep
change, disconnect, and unmount; StrictMode's dev replay is expected, and cleanup
is what makes it harmless.

- `(ui/effect :connect body)` runs at each connect (mount, or reveal after a hide)
  with cleanup at each disconnect. There is deliberately no "once" or "mount" name
  in a lifecycle React can replay.
- To dispatch from an effect or a foreign callback, capture `(ui/dispatch-fn)` in
  the view body: one **stable** function per view instance, bound to the committed
  frame, and loud if called after the view disconnects — a leaked listener becomes
  an error you see, not a dispatch into the void.

```clojure
(ui/defview media-bridge [{:keys [stream-id]}]
  (let [dispatch! (ui/dispatch-fn)]
    (ui/effect [stream-id]
      (let [stop (listen! stream-id #(dispatch! [:stream/sample %]))]
        stop))
    [:div.bridge]))
```

For the rare case that needs more than dispatch, `(ui/frame)` returns the
committed-frame ops bundle (`:frame` / `:dispatch` / `:dispatch-sync` /
`:subscribe`) — the same shape core's `capture-frame` gives you outside views. Exact
contract in the [API reference](../../api/re-frame.ui.md).

## Reading a resource

A view never keeps a resource alive itself — resource liveness is owned
**causally**, by whatever caused the load: a route entry, a machine, or an app
event, each with a named release ([Resources corpus](../../resources/concepts.md)).
A view only ever **reads** a resource, passively, with `sub`:

```clojure
(ui/defview latency-tile []
  (let [{:keys [status data]} (sub [:rf/resource {:resource :metrics/latency-feed}])]
    (case status
      (:idle :loading) [:div.tile.skeleton "…"]
      :error   [:div.tile.error "Feed unavailable"]
      ;; :loaded — and :fetching, which keeps prior data visible mid-refresh
      [:div.tile [:h3 "p95 latency"] [:strong (str (:p95 data) "ms")]])))
```

The read is the passive `(sub [:rf/resource …])`; nothing on this page fetches.
The resource system itself — registration, caching, refetch, the transport, and
the causal owners that keep an entry alive — is the
[Resources corpus](../../resources/concepts.md).

## When you get it wrong

The three inputs have few rules, and the ones they have fail where you can see them:

| If you write | What you see | The fix |
|---|---|---|
| `(sub …)` inside a `for` | Compile error `:rf.ui.compile/sub-in-loop` | Extract a keyed child view that subscribes for one row |
| `local` / `effect` below a branch or inside a loop | Compile error `:rf.ui.compile/hook-misplaced` | Keep them in the view's unconditional top region |
| `set!` / `update!` during render | Loud error `:rf.error/ui-tree-malformed` naming the render-phase mutation | Call setters from a committed handler or an `effect` callback |
| `(ui/dispatch-fn)` called after the view disconnected | Loud error `:rf.error/dispatch-disconnected` | Return a cleanup fn from the `effect` so the listener is removed |
| A `sub` with no frame above the view | `:rf.error/no-frame-context` | Mount under `frame-root`, or scope with `frame-provider` |

## When not

- State with product meaning goes to **app-db behind events** — the default. Reach
  for `local` only for keystroke-latency ephemera.
- `effect` is for the host world, not app logic — fetching in an `effect` bypasses
  events, epochs, and every tool that watches them.
- And the standing note for this whole section: `re-frame.ui` is experimental. The
  retained adapters (Reagent, reagent-slim, UIx) are the default choice, and this
  same state discipline — app-db, events, narrow subscriptions — applies there
  unchanged.
