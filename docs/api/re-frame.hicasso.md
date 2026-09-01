# re-frame.hicasso

`re-frame.hicasso` is the public door of Hicasso, the re-frame-native view layer.
Everything an author writes against lives here; everything below it is
`re-frame.hicasso.impl.*` and is not a consumer surface. The intended spelling is
one alias:

```clojure
(:require [re-frame.hicasso :as h])
```

Hicasso replaces the view notation and nothing else. Events, app-db,
subscriptions and effects are unchanged — read [`re-frame.core`](re-frame.core.md)
for the pipeline and this page for the authoring surface.

This page is the manifest-tracked index of the door's public vars: Kind,
Signature, and what the var is. The **full contract** — the four shapes an `on-*`
prop may take, the `defhost` options table, the `mount!` / `hydrate!` asymmetry,
the render discipline — lives in the [Hicasso API reference](../core/hicasso/api-reference.md),
alongside the guide that teaches it. This page deliberately does not duplicate it;
where an entry below is terse, that reference is where the depth is.

**A split-host namespace.** The door is a `.cljc` whose two arms are disjoint. The
three authoring macros are its `#?(:clj …)` arm and are what `ns-publics` returns
on the JVM; the eleven runtime vars are its `#?(:cljs …)` arm and exist only under
ClojureScript. Both arms are inventoried — the JVM manifest generator owns the
macros, the CLJS analyzer probe owns the runtime vars — so neither host can
silently gain or lose a public.

## Authoring macros

### `defview`

- **Kind**: macro
- **Signature**:
  ```clojure
  (h/defview name docstring? [props] body …)
  ```
- **Description**: Mints a boundary — a real React function component, and a legal
  hiccup head. `argv` is the ordinary one-props-map argument vector, so
  destructuring reads as it does in any Clojure fn.
  - The macro **reads no body**. It expands to a `def` of the minted head plus a
    source coordinate, so a refusal raised while the body runs can name where the
    boundary was written.
  - The `fn` it emits is **anonymous**, so nothing it binds can shadow a helper of
    the same name — `(h/defview todo-row [p] (todo-row-body p))` is safe at the
    ordinary spelling.
  - The name is also registered in re-frame's `:view` registrar under
    `(keyword "<ns>" "<sym>")`, for **forward resolution only** — a tool holding a
    keyword the author wrote reaches the view they meant. It carries no
    `:handler-fn`, and rides the `debug-enabled?` gate, so a production build
    registers nothing.
  - Hooks do not belong in a body: a body is dynamically composed, so a hook
    written there would make its own call order depend on a data path. Put
    hook-intensive behaviour in a React island reached through `defhost`.
- **Example**:
  ```clojure
  (h/defview todo-row [{:keys [id]}]
    (let [todo (h/sub [:todo/by-id id])]
      [:li {:on-click [:todo/toggle id]} (:text todo)]))
  ```

### `defhost`

- **Kind**: macro
- **Signature**:
  ```clojure
  (h/defhost name docstring? component)
  (h/defhost name docstring? component opts)
  ```
- **Description**: The interop door. Names the crossing to a foreign React
  component once; the resulting var is a hiccup head anywhere, indistinguishable
  from a view. Callback contracts are inferred from each prop's spelling, exactly
  as at a native tag. `opts` carries four optional keys — `:callbacks`, `:slots`,
  `:server`, `:fallback` — and any other key is refused. Anything written past
  `opts` is refused rather than silently dropped
  (`:rf.error/hicasso-bad-host-declaration`).
- **Example**:
  ```clojure
  (h/defhost date-picker DatePicker)
  (h/defhost modal Modal {:slots #{:title :footer}})
  ```

### `event`

- **Kind**: macro
- **Signature**:
  ```clojure
  (h/event [args …] body …)
  ```
- **Description**: The one callback form, for a position where the event itself is
  wanted. It expands to a marked `fn` and nothing else, so the value is an ordinary
  function. Which of two contracts it carries is selected by **position**, not by
  the name: at an `on*`-spelled prop a returned vector is dispatched and any other
  return ignored; at any other walked prop it is a render position, pure, whose
  return is the render output.
- **Example**:
  ```clojure
  [:input {:on-change (h/event [e] [:draft/set (.. e -target -value)])}]
  ```

## Reads

### `sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/sub query-v)
  ```
- **Description**: The ambient collector — read a subscription's value from
  anywhere inside a body, including inside a `when`, a `for` or an inlined helper.
  The edge is recorded where the read happens, so a branch not taken contributes no
  edge. The frame doors are core's rather than duplicated here:
  `(rf/current-frame-id)` and zero-arity `(rf/capture-frame)` are legal inside a
  body.

## Roots

Four doors and one handle. Every one is root-scoped: a page may hold as many roots
as it likes, and no call here reaches a root the caller did not name.

### `mount!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/mount! container config view)
  ```
- **Description**: The root door. Ensures a frame, associates it with a DOM
  container and one root view, and answers the handle the other three take.
  `config` carries `:frame` (required — the frame keyword this root scopes, created
  if absent or joined as it stands), `:initial-events` (dispatched synchronously
  **only when this mount creates the frame**, draining before the call returns) and
  `:identifier-prefix` (React's `identifierPrefix`, a pass-through).
- **Example**:
  ```clojure
  (h/mount! (js/document.getElementById "app")
            {:frame :rf/default :initial-events [[:counter/initialise]]}
            [counter])
  ```

### `hydrate!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/hydrate! container config view)
  ```
- **Description**: Adopts a container's existing server-rendered DOM rather than
  replacing it. `config` carries `:frame` and optionally `:identifier-prefix` — and
  no `:initial-events`. **It is not `mount!`'s symmetric twin**: it does not ensure
  its frame, it returns *before* adoption finishes, and it must be handed the same
  `:identifier-prefix` the server render used. State comes first and through a
  different door: `re-frame.ssr/hydrate!` installs the server's app-db and must run
  before this.

### `render!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/render! handle view)
  ```
- **Description**: Re-renders a mounted root in place, synchronously, and answers
  its handle — the hot-reload door. React reconciles the new tree against the one
  on the page. Calling `mount!` again instead would create a second root and
  discard every node, subscription and scrap of component state.

### `unmount!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/unmount! handle)
  ```
- **Description**: Takes this root down — `mount!`'s inverse, and idempotent. Leaves
  sibling roots' subscriptions and frames exactly where they were, and leaves the
  container in the document, which React empties but does not remove.

## Markup

### `error-boundary`

- **Kind**: Var (React class component; a legal hiccup head)
- **Signature**:
  ```clojure
  [h/error-boundary {:fallback f :reset-key k :on-error e} child …]
  ```
- **Description**: The runtime's own error boundary, named for React's term of art.
  A React class, so React hands it a render-phase throw from anything below. It is
  not a Hicasso *reactive* boundary: it reads no subscription, holds no cell and
  spends no hook.

### `portal`

- **Kind**: Var (minted host head)
- **Signature**:
  ```clojure
  [h/portal {:target node :fallback markup} child …]
  ```
- **Description**: Hiccup into `createPortal`. Three facts and nothing else: events
  bubble through the **React** tree, so an ancestor's `:on-click` sees clicks inside
  the portalled subtree; a changed `:target` is a remount, so keep it stable; and it
  is client-only, so the subtree is absent from a server response and `:fallback` is
  what takes its tree position. Anchoring, dismissal and focus conduct belong to the
  overlay module, not here.

### `route-link`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/route-link {:to route :params p :query q :fragment s} child …)
  ```
- **Description**: One real anchor, as data — href and click decision taken whole
  from routing's late-bound seams. A plain function, not a boundary: it mints no
  boundary and adds no hook.

### `as-element`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/as-element hiccup)
  ```
- **Description**: The one explicit hiccup→ReactNode conversion, under the frame of
  the boundary currently rendering. It exists because a declared `:render` return
  crosses **unconverted**: a returned hiccup vector would reach React, which refuses
  it. Also the answer at the two places a declaration cannot reach — a `[:>]` escape,
  and past the native fence. Where the crossing *is* declared, prefer `defhost`'s
  `:slots`, which lowers those positions for every use site at once.

### `as-component`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/as-component view)
  ```
- **Description**: The outward bridge — answers a real React component for a hiccup
  head, so a UIx or plain-JavaScript parent mounts a minted Hicasso view under the
  frame it is already in. Declared once at top level, beside the view. The parent's
  props arrive as the view's ordinary props map, children at `:children`, and the
  frame comes from React context: no second root, state owner or props ABI appears
  anywhere.

## Local state

### `reg-state`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/reg-state concern opts?)
  ```
- **Description**: The instance-key sugar. Mints one parametric subscription and one
  setter event under `[:ui ::concern ikey]`, and nothing else.

## What this door does not carry

The optional modules are reached separately, so an application that never asks for
one carries none of it — `presence` is `re-frame.hicasso.motion/presence`, and
`.forms`, `.overlay`, `.motion`, `.substrate` and the `.server` SSR module each cost
a classpath entry and no bundle bytes until required. The door names none of them,
and that is the point rather than an omission: one `:require` here would put the
retention machine into every bundle that ever touched the door. Those modules, the
test kit and the tool tier are documented in the
[Hicasso API reference](../core/hicasso/api-reference.md); they carry no
api-manifest rows of their own.

The marker keywords need no export. `::h/value`, `::h/prevent`, `::h/revision`,
`::h/checked` and `::h/clear` read `:re-frame.hicasso/…`, so aliasing this namespace
as `h` resolves the auto-resolved spelling the guide teaches with no keyword changing
value.
