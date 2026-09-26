# re-frame.fresco

Fresco is re-frame2's own view layer, and this namespace is what you write Fresco
views with: `defview` defines a view, `sub` reads a subscription inside it, and
`client-root` / `render!` mount it. Events, `app-db`, subscriptions and effects
are unchanged and stay on [`re-frame.core`](re-frame.core.md); Fresco changes only
how views are written.

```clojure
(:require [re-frame.fresco :as h])
```

```clojure
;; Boot installs an adapter before mounting: see re-frame.fresco.substrate.

(h/defview counter [_]
  [:button {:on-click [:counter/inc]} (h/sub [:counter/value])])

(defonce app-root (h/client-root))

(defn ^:dev/after-load mount! []
  (h/render! app-root
             [h/frame-root {:id :rf/default :initial-events [[:counter/initialise]]}
              [counter]]
             (js/document.getElementById "app")))
```

The frame functions are core's: inside a view body, `(rf/current-frame-id)` and
zero-arity `(rf/capture-frame)` return the rendering view's frame, and this
namespace does not duplicate them. The [Fresco API
reference](../core/fresco/api-reference.md) is the long-form contract: the shapes
an `on-*` prop may take, the `defhost` options table, hydration through `render!`,
and the render rules.

## Authoring macros

The namespace is a `.cljc`: these three macros are its Clojure side, and are what
`ns-publics` returns on the JVM. The other twelve vars exist only in ClojureScript.

### `defview`

- **Kind**: macro
- **Signature**:
  ```clojure
  (h/defview name docstring? [props] body …)
  ```
- **Description**: Defines a view: a React function component that is also a legal
  hiccup head, so you render it as `[todo-row {:id 7}]`. Fresco calls this
  independently re-rendering unit a *boundary*. The argument vector takes one props
  map, destructured as in any Clojure fn.
    - The macro does not inspect the body. It expands to a `def` of the view plus a
      source coordinate, so an error raised while the body runs can name where the
      view was written.
    - The `fn` it emits is anonymous, so it binds no name that could shadow a helper:
      `(h/defview todo-row [p] (todo-row-body p))` is safe.
    - The view is also registered in re-frame's `:view` registry under
      `(keyword "<ns>" "<sym>")`, so a tool holding a keyword the author wrote can
      find the view: the entry holds it at `:handler-fn`, and `(rf/view id)` returns
      it. Registration happens only in debug builds; a production build registers
      nothing.
    - Do not call React hooks in a body. A body is composed dynamically, so a hook's
      call order would depend on the data. Put hook-heavy behaviour in a React island
      reached through `defhost`.
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
- **Description**: Declares a React component from outside Fresco once, so you can
  use it anywhere as a hiccup head, exactly like a view. Callback props are inferred
  from each prop's name, as on a native tag.
    - `opts` takes four optional keys: `:callbacks`, `:slots`, `:server` and
      `:fallback`. Any other key, or any form after `opts`, raises
      `:rf.error/fresco-bad-host-declaration` instead of being dropped.
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
- **Description**: Creates a callback, for a prop where you need the event object or
  other arguments. It expands to a marked `fn` and nothing else, so the value is an
  ordinary function.
    - The prop's position, not the name, decides what the return value means. At an
      `on*` prop a returned vector is dispatched and any other return is ignored. At
      any other prop Fresco walks, it is a pure render callback and its return is the
      render output.
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
- **Description**: Returns the current value of the subscription `query-v`. Call it
  anywhere inside a view body, including inside a `when`, a `for` or a plain helper
  function the body calls; the view records the dependency where the read happens,
  so a branch not taken adds none.

## Frame boundaries

The frame is set in the tree, not by `render!`. `frame-root` or `frame-provider`
puts a frame in context for everything below it, whether at the root of a page or
around a subtree inside one. `frame-root` is the same component `rf/frame-root` and
`re-frame.adapter.uix/frame-root` mount, and both heads write the one React context
every adapter reads, so a Fresco tree is written like a Reagent or UIx one and a
Fresco subtree under a UIx provider resolves the same frame.

### `frame-root`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [h/frame-root {:id :app/main :initial-events [[:app/boot]]} child …]
  ```
- **Description**: Creates the named frame if it is absent, or reuses it without
  re-seeding if it is live, and provides it to the subtree.
    - The opts map is the whole `rf/make-frame` option map: `:id` (required), plus
      `:doc`, `:fx-overrides`, `:url-bound?` and the rest. Frame options go here
      rather than into a separate `make-frame` call before the mount.
    - The frame is created at commit, which is why `frame-root` is the wrong head for
      hydrating server-rendered markup; use `frame-provider` there (see `render!`).
    - A `:frame` key raises `:rf.error/frame-root-given-frame`, naming
      `frame-provider`. Changing a mounted boundary's `:id` or opts raises
      `:rf.error/frame-root-reconfigured`.

### `frame-provider`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [h/frame-provider {:frame :app/main} child …]
  ```
- **Description**: Scopes a subtree to a frame that already exists; it creates and
  configures nothing.
    - Use it after `re-frame.ssr/hydrate!`, and for a second root on a frame another
      root already created.
    - An absent frame raises `:rf.error/frame-provider-frame-absent`. An `:id` key
      raises `:rf.error/frame-provider-given-id`, naming `frame-root`.

## Roots

A browser app needs a React root that is created once, updated on every hot
reload and released on teardown. `client-root`, `render!` and `unmount!` manage
it, with the same names and the same create-then-update behaviour as on the
[Reagent](re-frame.adapter.reagent.md#the-client-root) and
[UIx](re-frame.adapter.uix.md#the-client-root) adapters. Each function acts only on
the root whose handle you pass, so a page can hold as many roots as it needs.

### `client-root`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/client-root)
  ```
- **Description**: Returns a new, inert client-root handle. It does no DOM work and
  makes no React call, so it belongs under a `defonce` at namespace load; the first
  `render!` through the handle creates (or adopts) the React root.
    - The handle is opaque: pass it to `render!` and `unmount!` and nothing else. The
      raw React root is not reachable through it.
    - A handle holds at most one React root at a time, so two roots on a page need
      two handles.
- **Example**:
  ```clojure
  (defonce app-root (h/client-root))
  ```

### `render!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/render! handle view container)
  (h/render! handle view container opts)
  ```
- **Description**: Renders `view` into the DOM node `container` through `handle`:
  the first call creates the React root, and every later call updates it inside
  `flushSync`. React reconciles against the tree on the page, so the DOM, the
  subscriptions and component state survive a hot reload. Returns nil.
    - `container` and `opts` are read on the first call only.
    - `view` is the whole root tree, frame head included:
      `[h/frame-root {:id …} …]` or `[h/frame-provider {:frame …} …]`. Render the
      same head each time. A later render that drops it leaves the subtree with no
      frame in context, and switching to the other head is a React type change that
      remounts everything.
    - `opts` takes root options only: `:hydrate?`, and `:identifier-prefix`, which is
      passed to React as `identifierPrefix`. `:frame` or `:initial-events` raise
      `:rf.error/fresco-frame-config-misplaced`, naming the head that takes them; any
      other key raises `:rf.error/fresco-unknown-root-option`.
    - `{:hydrate? true}` makes the first call adopt the server-rendered DOM already in
      `container` (`hydrateRoot`) instead of replacing it, with its own
      recoverable-error reporter in debug builds. It returns before adoption
      finishes, and must be given the same `:identifier-prefix` the server render
      used. A later call through a live handle ignores `:hydrate?`.
    - A hydrating tree uses `frame-provider`. `frame-root` creates its frame at
      commit, so its first render has no children, while an adopting root must
      render the server's markup on its first pass; `frame-provider` renders its
      children immediately, so the shapes agree.
    - Neither hydration step creates the frame: `re-frame.ssr/hydrate!` dispatches
      `:rf/hydrate` at a frame that must already exist. Make the frame first, install
      the payload second, and adopt the DOM third.
- **Example**:
  ```clojure
  (h/render! app-root
             [h/frame-root {:id :rf/default :initial-events [[:counter/initialise]]}
              [counter]]
             (js/document.getElementById "app"))
  ```

### `unmount!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/unmount! handle)
  ```
- **Description**: Unmounts the React root `handle` holds and returns the handle to
  inert, so a later `render!` through it mounts afresh.
    - Idempotent. Sibling roots' subscriptions and frames are untouched, and the
      container stays in the document; React empties it but does not remove it.
    - `rf/destroy-adapter!` also releases a still-live handle's root, exactly once,
      after which `unmount!` does nothing.
- **Example**:
  ```clojure
  (h/unmount! app-root)
  ```

## Markup

### `error-boundary`

- **Kind**: var (React class component, usable as a hiccup head)
- **Signature**:
  ```clojure
  [h/error-boundary {:fallback f :reset-key k :on-error e} child …]
  ```
- **Description**: Catches an error thrown while rendering anything below it and
  renders `:fallback` in place of its children. It is React's error boundary, a
  class component, and not a Fresco view: it reads no subscriptions and uses no
  hooks.
    - `:fallback` is hiccup, or `(fn [error] hiccup)`.
    - A change in `:reset-key` (compared with `=`) clears the caught error and
      re-mounts the children.
    - `:on-error` fires once per caught error: an event vector is dispatched with the
      error appended, in the frame the boundary is mounted under, and a function is
      called with the error.

### `portal`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [h/portal {:target node :fallback markup} child …]
  ```
- **Description**: Renders its children into the DOM node `:target`, through
  React's `createPortal`.
    - Events bubble through the React tree, so an ancestor's `:on-click` sees clicks
      inside the portalled subtree.
    - A changed `:target` is a remount, so keep it stable.
    - It is client-only: the subtree is absent from a server response, and
      `:fallback` takes its tree position there.
    - Anchoring, dismissal and focus belong to
      [`re-frame.fresco.overlay`](re-frame.fresco.overlay.md).

### `route-link`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/route-link {:to route :params p :query q :fragment s} child …)
  ```
- **Description**: Returns a real `<a>` for a route, as hiccup; the `href` and the
  click handling come from re-frame2's routing.
    - It is a plain function, not a view: it adds no boundary and no hook.
    - If `re-frame.routing` is not loaded, it raises
      `:rf.error/routing-artefact-missing` at render.

### `as-element`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/as-element hiccup)
  ```
- **Description**: Converts hiccup to a React element, under the frame of the view
  currently rendering. Use it where Fresco does not convert hiccup for you.
    - A declared `:render` callback's return value reaches the foreign component
      unconverted, so a hiccup vector returned there makes React throw.
    - A `[:>]` crossing has no `:slots`, so a prop that takes an element needs one.
    - A child handed to a native React subtree needs one, because a hiccup vector
      is refused there.
    - Where the crossing is declared, prefer `defhost`'s `:slots`, which converts
      those props at every use site.

### `as-component`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/as-component view)
  ```
- **Description**: Returns a real React component for a Fresco view, so a UIx or
  plain-JavaScript parent can mount it under the frame it is already in. Define it
  once at top level, beside the view.
    - The parent's props arrive as the view's ordinary props map, and its children
      at `:children`. The frame comes from React context, so no second root or state
      owner is involved.
- **Example**:
  ```clojure
  (def article-card* (h/as-component article-card))
  ```

## Local state

### `reg-state`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/reg-state concern opts?)
  ```
- **Description**: Registers per-instance UI state for `concern`: one parametric
  subscription and one setter event, stored under `[:ui ::concern ikey]`, plus the
  shared `::h/clear` event that returns an instance to its default. Returns
  `concern`.
    - `concern` must be a namespace-qualified keyword, and `opts` takes only
      `:default`; either fault raises `:rf.error/fresco-state-bad-argument`.

## Optional modules

The optional modules are separate namespaces, and this one requires none of them,
so an app that never requires one carries none of its code. `presence`, for
example, is `re-frame.fresco.motion/presence`, not `h/presence`.
[`.forms`](re-frame.fresco.forms.md), [`.motion`](re-frame.fresco.motion.md),
[`.native`](re-frame.fresco.native.md), [`.overlay`](re-frame.fresco.overlay.md)
and [`.substrate`](re-frame.fresco.substrate.md) each have a page here; the
`.server` SSR module, the test kit and the tool namespaces are documented in the
[Fresco API reference](../core/fresco/api-reference.md).

## Marker keywords

`::h/value`, `::h/prevent`, `::h/revision`, `::h/checked` and `::h/clear` are
keywords in the `:re-frame.fresco` namespace, so they need no export: with this
namespace aliased as `h`, the auto-resolved spelling the guide uses resolves to
them.
