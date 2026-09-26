# re-frame.fresco

Fresco is re-frame2's own view layer, and this namespace is what you write Fresco
views with: `defview` defines a view, `sub` reads a subscription inside it, and
`client-root` / `render!` mount it. Events, `app-db`, subscriptions and effects
are unchanged and stay on [`re-frame.core`](re-frame.core.md); Fresco changes only
how views are written.

Choose Fresco when you want views to stay data: markup is hiccup, a view calls
`h/sub` exactly where it needs a value, and most event handlers are event vectors
rather than closures, so tests and tools can read them off the rendered tree. For
a React-first screen, with hooks throughout and a React component library at its
centre, the [UIx adapter](re-frame.adapter.uix.md) is usually clearer, and an
existing Reagent app can stay on the [Reagent adapter](re-frame.adapter.reagent.md).
You can mix them: `defhost` mounts a React component inside a Fresco view, and
`as-component` mounts a Fresco view inside a React one. [When Fresco
fits](../core/fresco/index.md#when-fresco-fits) has the longer comparison.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.fresco :as h]
          [re-frame.fresco.substrate :as substrate])
```

```clojure
(h/defview counter [_]
  [:button {:on-click [:counter/inc]} (h/sub [:counter/value])])

(defonce app-root (h/client-root))

(defn ^:dev/after-load mount! []
  (h/render! app-root
             [h/frame-root {:id :rf/default :initial-events [[:counter/initialise]]}
              [counter]]
             (js/document.getElementById "app")))

(defn run []
  (rf/init! substrate/adapter)
  (mount!))
```

`rf/init!` installs an adapter before anything mounts. Fresco's own is
[`re-frame.fresco.substrate`](re-frame.fresco.substrate.md); a Reagent or UIx
adapter works too.

The frame functions are core's: inside a view body, `(rf/current-frame-id)` and
zero-arity `(rf/capture-frame)` return the rendering view's frame, and this
namespace does not duplicate them.

This page has one entry per public var, like every page in this section. The
guide's [Fresco API reference](../core/fresco/api-reference.md) lists the same names
from the guide's side, pointing each at the chapter that teaches it, and covers the
modules that have no page here (see [Optional modules](#optional-modules)).

## Authoring macros

`defview` and `defhost` each expand to a `def`, so write them at the top level of a
namespace, never inside a view body. `event` is written inline, at a prop. On the
JVM these three macros are the whole namespace; the other twelve vars exist only in
ClojureScript.

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
    - A `:key` in the props map goes to React and is removed from the props the body
      receives, so give each list item its domain id there. Children written after
      the props map arrive in the props at `:children`.
    - The macro does not inspect the body. It expands to a `def` of the view plus a
      source coordinate, so an error raised while the body runs can name where the
      view was written.
    - The `fn` it emits is anonymous, so it binds no name that could shadow a helper:
      `(h/defview todo-row [p] (todo-row-body p))` is safe.
    - The view is also registered in re-frame's `:view` registry under
      `(keyword "<ns>" "<sym>")`, so a tool holding a keyword the author wrote can
      find the view: the entry holds it at `:handler-fn`, and `(rf/view id)` returns
      it. Registration happens only in development builds; a production build
      registers nothing.
    - Do not call React hooks in a body. A body is composed dynamically, so a hook's
      call order would depend on the data. Put hook-heavy behaviour in a React
      component mounted through `defhost`, which the guide calls an *island*; see
      [`re-frame.fresco.native`](re-frame.fresco.native.md).
- **Errors**, raised while the view renders:
    - [`:rf.error/no-frame-context`](../core/fresco/troubleshooting.md#no-frame-context)
      when no `frame-root` or `frame-provider` is above the view.
    - `:rf.error/ambient-frame-refused` when the body calls `rf/subscribe`, or
      `rf/dispatch` with no `:frame`: a read there would not re-render the view.
      Read with `h/sub`, and dispatch from an event prop, an `h/event` or a handle
      from `(rf/capture-frame)`.
    - [`:rf.error/fresco-deferred-read-at-boundary`](../core/fresco/troubleshooting.md#fresco-deferred-read-at-boundary)
      when a child view's props carry an unforced `delay`. Pass a function instead,
      or deref the delay in the body that wrote it.
    - [`:rf.error/fresco-generation-fence-exhausted`](../core/fresco/troubleshooting.md#fresco-generation-fence-exhausted)
      when the body sees a new commit on four runs in a row, which a body that
      writes on every render causes. Move the write out of the render.
- **Example**:
  ```clojure
  (h/defview todo-row [{:keys [id]}]
    (let [todo (h/sub [:todo/by-id id])]
      [:li {:on-click [:todo/toggle id]} (:text todo)]))

  (h/defview todo-list [_]
    [:ul (for [id (h/sub [:todo/visible-ids])]
           [todo-row {:key id :id id}])])
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
  from each prop's name, as on a native tag: an `on*` prop takes the shapes under
  [Event props](#event-props), and any other prop given an `h/event` is a render
  callback.
    - The docstring goes before `component`. Written after it, it is read as `opts`
      and the declaration is refused.
    - Declaration errors are raised when the namespace loads.
    - Other props cross shallowly. Keys are camelCased (`:on-row-click` becomes
      `onRowClick`, `:class` becomes `className`); a function crosses as itself; a
      map or vector goes through `clj->js`; and a keyword or symbol crosses as
      itself, except at `:class`, `:id`, `:role`, `data-*` and `aria-*`, where it
      becomes its name. A component that expects the string `"primary"` must be
      passed the string, not `:primary`.
- **Options**:
    - `:callbacks`: `{prop :event|:render}`, overriding the contract a prop's name
      implies. A vendor's `on*`-named render prop needs
      `{:callbacks {:on-render-item :render}}`.
    - `:slots`: the set of props that take a React element. Hiccup written at one is
      converted under the writing view's frame; at an undeclared prop a hiccup vector
      is passed through as data.
    - `:server`: `:client-only` (the default: the component renders nothing on the
      server or on hydration's first pass, and mounts once the page is adopted) or
      `:render` (you assert the component is safe to render on the server).
    - `:fallback`: hiccup rendered in place of a `:client-only` component until it
      mounts.
- **Errors**:
    - `:rf.error/fresco-host-no-component`: `component` is nil, usually a JS import
      that resolved nothing.
    - `:rf.error/fresco-bad-host-declaration`: `opts` is not a map, has a key outside
      the four, or names a contract other than `:event` or `:render`; `:slots` is not
      a set of prop names, names `:key` or `:ref`, spells one prop twice, or names a
      prop that `:callbacks` also names; or a form follows `opts`. Nothing is
      silently dropped.
    - `:rf.error/fresco-host-bad-ssr-policy`: a `:server` value outside the two, a
      `:fallback` beside `:server :render`, or `:fallback nil`.
    - `:rf.error/fresco-host-fallback-boundary-head`: the `:fallback` contains a
      `defview` or `defhost` head. A fallback is plain markup.
    - [`:rf.error/fresco-host-unclaimed-callback`](../core/fresco/troubleshooting.md#fresco-host-unclaimed-callback),
      at render: an `h/event` at a prop named in `:slots`. A slot takes markup; write
      hiccup there, or take the prop out of `:slots`.
- **Example**:
  ```clojure
  ;; DatePicker and Modal are React components required from npm.
  (h/defhost date-picker DatePicker)
  (h/defhost modal Modal {:slots #{:title :footer}})

  ;; react-datepicker calls onChange(date, event), value first, so the prop
  ;; takes an h/event; a bare vector would raise
  ;; :rf.error/fresco-intent-needs-the-event.
  [date-picker {:selected  due-date
                :on-change (h/event [date & _] [:task/set-due date])}]
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
    - Reach for it only when an event vector cannot say what you need. To pass the
      input's value or checked flag, write `[:draft/set ::h/value]`, which needs no
      callback (see [Event props](#event-props)).
    - Where it is written decides what its return value means. At an `on*` prop a
      returned vector is dispatched and any other return is ignored. At any other
      prop of a native tag, a `defhost` component or a `[:>]` crossing, it is a pure
      render callback and its return is the render output. At `:ref`, which Fresco
      leaves to React, it is a plain function.
- **Example**:
  ```clojure
  [:input {:type      "file"
           :on-change (h/event [e] [:app/upload (js/Array.from (.. e -target -files))])}]
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
    - It reads in the frame of the view that is rendering.
    - Outside a view render (in an event handler, a callback or a utility) it raises
      `:rf.error/fresco-sub-outside-render`, naming the query. For a one-off read
      there, use `rf/subscribe-once`.
    - An unregistered query reads `nil` and emits `:rf.error/no-such-sub`, and a
      subscription whose body throws reads `nil` and emits `:rf.error/sub-exception`.
      Neither throws into the view.
- **Example**:
  ```clojure
  (h/defview todo-footer [_]
    [:footer
     [:span (h/sub [:todo/remaining-count]) " left"]
     (when (h/sub [:todo/any-done?])
       [:button {:on-click [:todo/clear-done]} "Clear completed"])])
  ```

## Frame boundaries

The frame is set in the tree, not by `render!`. `frame-root` or `frame-provider`
puts a frame in context for everything below it, whether at the root of a page or
around a subtree inside one. `frame-root` is the same component `rf/frame-root` and
`re-frame.adapter.uix/frame-root` mount, and both heads write the one React context
every adapter reads, so a Fresco tree is written like a Reagent or UIx one and a
Fresco subtree under a UIx provider resolves the same frame.

Use `frame-root` where a frame is declared, usually once at the root of the app: it
creates the frame and holds its options. Use `frame-provider` everywhere else a
subtree needs a frame that already exists. Do not declare a live frame a second
time with `frame-root` to reach it: a second `frame-root` on the same `:id`
replaces that frame's options wholesale, so a partial options map drops what the
first one set.

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
    - `:initial-events` run once, when the frame is first created. A hot reload or
      remount under the same `:id` keeps the frame's state and does not run them
      again.
    - The frame is created at commit, which is why `frame-root` is the wrong head for
      hydrating server-rendered markup; use `frame-provider` there (see `render!`).
    - Unmounting destroys nothing: the frame outlives the head, and
      `rf/destroy-frame!` ends it.
- **Errors**:
    - `:rf.error/frame-root-missing-id` when `:id` is missing or not a keyword.
    - `:rf.error/frame-root-given-frame` on a `:frame` key, naming `frame-provider`.
    - `:rf.error/frame-root-reconfigured` when a mounted head's `:id` or opts
      change. To point the subtree at a different frame, give the head a React
      `:key` that changes, which remounts it.
- **Example**:
  ```clojure
  ;; Created on first mount and seeded once; reused, not re-seeded, on hot reload.
  [h/frame-root {:id :app/main :initial-events [[:app/init]]}
   [root-view]]
  ```

### `frame-provider`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [h/frame-provider {:frame :app/main} child …]
  ```
- **Description**: Scopes a subtree to a frame that already exists; it creates,
  refreshes and destroys nothing.
    - Use it after `re-frame.ssr/hydrate!`, for a second root on a frame another
      root already created, and around a subtree that works on another frame, such
      as a preview pane.
    - `:frame` is a frame id keyword or the live frame value `rf/make-frame`
      returns.
    - Everything written below the head uses its frame: intents, `h/event`
      callbacks and child views. Calls the enclosing body makes itself (`h/sub`,
      `h/route-link`, `rf/capture-frame`) run before the head takes effect and stay
      in the body's frame, so read the scoped frame from a child view under the
      provider.
- **Errors**:
    - `:rf.error/frame-provider-frame-absent` when the frame does not exist.
    - `:rf.error/no-frame-context` on a nil `:frame`.
    - `:rf.error/bad-frame-provider-arg` on a `:frame` that is neither a keyword nor
      a live frame value.
    - `:rf.error/frame-provider-given-id` on an `:id` key, naming `frame-root`.
- **Example**:
  ```clojure
  (h/defview preview-pane [_]
    [:aside.preview
     [h/frame-provider {:frame :app/preview}
      [invoice-view]]])   ;; :app/preview already exists; invoice-view reads and
                          ;; dispatches in it
  ```

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
  `render!` through the handle creates (or hydrates) the React root.
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
      same head with the same options each time. A later render that drops the head
      leaves the subtree with no frame in context; one that drops a `frame-root`
      option, such as `:initial-events` because they have already run, raises
      `:rf.error/frame-root-reconfigured`; and switching to the other head is a
      React type change that remounts everything. Re-passing `:initial-events` is
      harmless, since they run once per frame, so build the tree in one function and
      call it from every `render!`.
    - `opts` takes root options only: `:hydrate?`, and `:identifier-prefix`, which is
      passed to React as `identifierPrefix`. `:frame` or `:initial-events` raise
      `:rf.error/fresco-frame-config-misplaced`, naming the head that takes them; any
      other key raises `:rf.error/fresco-unknown-root-option`.
    - `{:hydrate? true}` makes the first call adopt the server-rendered DOM already in
      `container` (`hydrateRoot`) instead of replacing it, with its own
      recoverable-error reporter in development builds. It returns before adoption
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

  ;; Hydrating a server-rendered page (ssr is re-frame.ssr):
  (rf/make-frame {:id :app/main})                              ;; 1. frame
  (ssr/hydrate! {:frame :app/main})                            ;; 2. state
  (h/render! app-root                                          ;; 3. DOM
             [h/frame-provider {:frame :app/main} [page]]
             (js/document.getElementById "app")
             {:hydrate? true :identifier-prefix "main"})
  ```

### `unmount!`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/unmount! handle)
  ```
- **Description**: Unmounts the React root `handle` holds and returns the handle to
  inert, so a later `render!` through it mounts afresh. Returns nil.
    - Idempotent. Sibling roots' subscriptions and frames are untouched, and the
      container stays in the document; React empties it but does not remove it.
    - It destroys no frame, including this root's own: a frame outlives the root
      that created it, and `rf/destroy-frame!` ends it.
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
    - Without `:fallback`, a caught error renders nothing where the children were.
    - It catches throws while rendering, and from lifecycles and effects, below it.
      A throw from an event handler, a timer or another callback goes to the
      browser's error channel, and a throw while rendering `:fallback` goes to the
      next boundary up.
- **Errors**:
    - `:rf.error/fresco-boundary-unknown-prop` on any other key, so a misspelt option
      cannot leave a boundary that silently reports nothing.
    - `:rf.error/fresco-boundary-bad-on-error` when `:on-error` is neither an event
      vector nor a function (a bare keyword included).
    - `:rf.error/fresco-intent-outside-boundary` when `:on-error` is a vector and no
      frame is in scope. Mount the boundary under a frame, or pass a function.
- **Example**:
  ```clojure
  [h/error-boundary {:fallback  (fn [e] [:p.error "The chart failed: " (ex-message e)])
                     :reset-key (h/sub [:chart/query])
                     :on-error  [:chart/render-failed]}
   [chart-panel]]
  ```

### `portal`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [h/portal {:target node :fallback markup} child …]
  ```
- **Description**: Renders its children into the DOM node `:target`, through
  React's `createPortal`. Use it for a container the application does not own; for
  a popover or dialog, use [`re-frame.fresco.overlay`](re-frame.fresco.overlay.md),
  which handles anchoring, dismissal and focus without a portal.
    - Events bubble through the React tree, so an ancestor's `:on-click` sees clicks
      inside the portalled subtree, and intents inside it dispatch in the writing
      view's frame.
    - A changed `:target` is a remount, so keep it stable rather than looking it up
      on every render. A `:target` that is not a DOM node is React's own error at
      render.
    - It is client-only: the subtree is absent from a server response, and
      `:fallback` takes its tree position there.
- **Example**:
  ```clojure
  [h/portal {:target js/document.body}
   [:div.toast {:on-click [:toast/dismiss]} "Saved"]]
  ```

### `route-link`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/route-link {:to route :params p :query q :fragment s
                 :prefetch :intent :on-click veto …attrs}
                child …)
  ```
- **Description**: Returns a real `<a>` for a route, as hiccup; the `href` and the
  click handling come from re-frame2's routing. A plain left-click navigates in the
  frame that rendered the link; modifier clicks, and links with `:target` or
  `:download`, are left to the browser (see
  [`route-link`](re-frame.routing.md#route-link)).
    - It is a plain function, not a view: call it, rather than writing it as a head.
      It adds no boundary and no hook, and it must be called inside a view render.
    - `:to` names a registered route; `:params`, `:query` and `:fragment` build the
      `href`. Every other key except `:prefetch` and `:on-click` passes through to
      the `<a>`, so `:class` and `:aria-current` work as usual.
    - `:on-click` is a veto that runs before the navigation: `nil`,
      `[::h/prevent [:app/event]]` (cancel the navigation and dispatch this instead),
      an `h/event` or a plain function. A bare event vector is refused, because the
      click already produces the routing event.
    - `:prefetch :intent` dispatches `[:rf.route/prefetch …]` for the link's route
      from `:on-mouse-enter`, `:on-focus` and `:on-touch-start`. Leaving the key out
      is the only way to opt out.
- **Errors**:
    - `:rf.error/routing-artefact-missing` at render when `re-frame.routing` is not
      loaded, naming the `:to`.
    - `:rf.error/fresco-route-link-outside-boundary` when called outside a view
      render.
    - `:rf.error/fresco-route-link-bad-on-click` on an `:on-click` outside the four
      shapes above.
    - `:rf.error/route-link-bad-prefetch` on any `:prefetch` value other than
      `:intent`.
    - `:rf.error/fresco-route-link-claimed-intent-position` when `:prefetch :intent`
      is combined with your own `:on-mouse-enter`, `:on-focus` or `:on-touch-start`.
      Drop `:prefetch` and dispatch the prefetch by hand from the positions you are
      not using.
    - The address errors of [`route-url`](re-frame.routing.md#route-url), at render:
      `:rf.error/no-such-route` for an unregistered `:to`,
      `:rf.error/missing-route-param` for a missing path param, and the validation
      errors listed there.
- **Example**:
  ```clojure
  (h/defview byline [{:keys [author]}]
    [:span.byline "by "
     (h/route-link {:to       :app/profile
                    :params   {:username author}
                    :class    "author"
                    :prefetch :intent}
       author)])
  ```

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
    - Intents in the converted markup keep dispatching in the frame of the view that
      supplied them. Outside any view render it still converts, but an intent in the
      markup raises `:rf.error/fresco-intent-outside-boundary`.
- **Example**:
  ```clojure
  (h/defhost virtual-list VirtualList)

  (h/defview feed [{:keys [ids]}]
    [virtual-list
     {:item-count (count ids)
      :render-row (h/event [i]
                    (h/as-element
                      [:li.row {:on-click [:feed/open (nth ids i)]}
                       (str (nth ids i))]))}])
  ```

### `as-component`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/as-component view)
  ```
- **Description**: Returns a real React component for a Fresco view, so a UIx or
  plain-JavaScript parent can mount it under the frame it is already in. Define it
  once at top level, beside the view: each call makes a new component type, so a
  call during render would remount the subtree every time.
    - The parent's props arrive as the view's ordinary props map, with camelCase
      names turned back into keywords (`articleId` becomes `:article-id`) and
      values passed as they are; its children arrive at `:children`.
    - The frame comes from React context, written by any adapter's frame head, so no
      second root or state owner is involved. Rendered outside every frame, it raises
      `:rf.error/no-frame-context`.
- **Example**:
  ```clojure
  (def article-card* (h/as-component article-card))
  ;; Exported to JSX as ArticleCard: <ArticleCard articleId={7} />
  ```

## Local state

### `reg-state`

- **Kind**: function
- **Signature**:
  ```clojure
  (h/reg-state concern opts?)
  ```
- **Description**: Registers per-instance UI state for `concern`: one parametric
  subscription and one setter event, stored in `app-db` at
  `[:ui concern instance-key]`, plus the shared `::h/clear` event that returns an
  instance to its default. Returns `concern`. Use it for a value that means nothing
  beyond itself, such as a disclosure's open flag or a tab strip's selection; when a
  change must trigger something else or be recorded, write a named event instead.
    - Read with `[concern instance-key]`, write with `[concern instance-key value]`,
      and clear with `[::h/clear concern instance-key]`. An absent entry reads as
      `:default`.
    - `concern` must be a namespace-qualified keyword, since it is a subscription
      id, an event id and an `app-db` key at once. `opts` takes only `:default`.
    - The instance key is a keyword, string, number, or a vector of those; derive it
      from data, the way you would a React `:key`. `nil` is refused, because it is
      what a missing prop evaluates to and would make every instance share one
      value.
    - Registering the same concern again replaces all three registrations, so the
      last `:default` wins.
- **Errors**:
    - `:rf.error/fresco-state-bad-argument`, thrown by `reg-state`, for an
      unqualified concern, or options that are not a map or carry a key other than
      `:default`. The message names the fault.
    - A bad instance key raises the same id inside the subscription or event, where
      the runtime catches it: a read emits `:rf.error/sub-exception` and reads `nil`,
      and a write or clear emits `:rf.error/handler-exception` and changes nothing.
- **Example**:
  ```clojure
  (h/reg-state ::open? {:default false})

  (h/defview panel [{:keys [id title]}]
    (let [open? (h/sub [::open? id])]
      [:section
       [:button {:on-click [::open? id (not open?)]} title]
       (when open? [panel-body {:id id}])]))
  ```

## Optional modules

The optional modules are separate namespaces, and this one requires none of them,
so an app that never requires one carries none of its code. `presence`, for
example, is `re-frame.fresco.motion/presence`, not `h/presence`. Each of these has
a page here:

- [`.forms`](re-frame.fresco.forms.md): a text field that edits a draft and commits
  it on Enter or blur.
- [`.motion`](re-frame.fresco.motion.md): keeping children rendered while they
  animate out.
- [`.native`](re-frame.fresco.native.md): hooks for a React component mounted inside
  a Fresco tree.
- [`.overlay`](re-frame.fresco.overlay.md): popovers and modal dialogs on the
  browser's top layer.
- [`.substrate`](re-frame.fresco.substrate.md): Fresco's own adapter, for
  `rf/init!`.

The `.server` SSR module, the test kits and the tool namespaces are documented in
the [Fresco API reference](../core/fresco/api-reference.md).

## Hiccup

A view body returns hiccup. A vector's head is one of:

| Head | What it renders |
| --- | --- |
| a tag keyword, symbol or string, such as `:span.label#total` | a DOM element. `.class` shorthand joins `:class`, and `#id` applies when there is no `:id` |
| `:<>` | a React fragment, reading only `:key` and `:ref` from its props map |
| a `defview` or `defhost` var, or a head from this namespace or an optional module | that view or component |
| `:>`, written `[:> Component props? child …]` | an undeclared React component: `defhost` with no options, always client-only and with no fallback |

A child is hiccup, a string or number, a seq (its members splice in place), a
React element, or a keyword or symbol (rendered as its name). `nil` and `false`
render nothing. At a native tag, prop keys are camelCased except `aria-*`,
`data-*` and `--custom` properties; `:class` takes a string, keyword or collection
of those; a map value such as `:style` gets camelCased keys; and `:ref` reaches
React untouched.

- **Errors**:
    - [`:rf.error/fresco-empty-vector`](../core/fresco/troubleshooting.md#fresco-empty-vector):
      `[]` where hiccup is expected.
    - [`:rf.error/fresco-bad-head`](../core/fresco/troubleshooting.md#fresco-bad-head):
      any other head, most often a plain function.
    - [`:rf.error/fresco-true-child`](../core/fresco/troubleshooting.md#fresco-true-child):
      `true` as a child, usually a predicate result.
    - [`:rf.error/fresco-raw-not-a-component`](../core/fresco/troubleshooting.md#fresco-raw-not-a-component):
      `[:>]` given `nil` or a `defview` or `defhost` var.
- **Warnings**, on the console in development builds, once per site:
    - [`:rf.warning/fresco-missing-key`](../core/fresco/troubleshooting.md#fresco-missing-key):
      a seq of views with no `:key`, passed as a view's children.
    - [`:rf.warning/fresco-entity-key`](../core/fresco/troubleshooting.md#fresco-entity-key):
      a view child keyed by a map, vector or other object.

## Event props

An event prop, named `:on-<event>` or React's `:on<Event>` (`:on-click` and
`:onClick` are the same prop), takes one of four shapes, on a native tag and on a
`defhost` component alike:

| Value | What happens |
| --- | --- |
| an event vector, `[:todo/toggle id]` | dispatched in the view's frame when the event fires. At `:on-submit` the browser default is also prevented. |
| a key map, `{"Enter" [:todo/commit id] "Escape" [:todo/cancel id]}` | the entry for the pressed key (spelled as the browser's `KeyboardEvent.key`) runs: a vector is dispatched, and a function or `h/event` is called. Presses during IME composition are ignored. |
| an `h/event` callback | called with the event; a returned vector is dispatched. |
| a plain function | passed to React unchanged. |

- **Errors**:
    - [`:rf.error/fresco-intent-outside-boundary`](../core/fresco/troubleshooting.md#fresco-intent-outside-boundary):
      an event vector or key map rendered with no frame in scope, or an `h/event`
      at an `on*` prop that returns a vector with none. Render the markup under a
      frame head.
    - [`:rf.error/fresco-intent-needs-the-event`](../core/fresco/troubleshooting.md#fresco-intent-needs-the-event):
      a vector carrying `::h/value`, `::h/checked` or `::h/prevent`, a vector at
      `:on-submit`, or a key map, at a prop whose caller passes something other than
      the DOM event first, such as `onChange(date)`. Write an `h/event`, which
      receives every argument. A plain vector with no marker works at any prop.

## Marker keywords

`::h/value`, `::h/prevent`, `::h/revision`, `::h/checked` and `::h/clear` are
keywords in the `:re-frame.fresco` namespace, so they need no export: with this
namespace aliased as `h`, the auto-resolved spelling the guide uses resolves to
them.

| Keyword | Where it goes | What it does |
| --- | --- | --- |
| `::h/value` | inside an event vector | replaced at dispatch time by the event target's current value, or a vector of the selected values on a `<select multiple>`. On a file input it raises `:rf.error/fresco-file-input-value-marker`; read `.files` in an `h/event` instead |
| `::h/checked` | inside an event vector | replaced by the target's checked flag |
| `::h/prevent` | as an event vector's head, wrapping another vector | calls `preventDefault`, then dispatches the wrapped vector: `[::h/prevent [:filter/show-done]]`. It wraps exactly one non-empty event vector and does not nest; anything else raises [`:rf.error/fresco-malformed-prevent`](../core/fresco/troubleshooting.md#fresco-malformed-prevent) |
| `::h/revision` | a prop on a controlled `<input>` or `<textarea>` | a change resets the field to its `:value`, even when `:value` itself did not change. Anywhere else it raises `:rf.error/fresco-revision-not-controlled` |
| `::h/clear` | as an event id | `[::h/clear concern instance-key]` removes a `reg-state` instance, back to its default |

Substitution looks only at the top level of the vector: `[:todo/edit id ::h/value]`
works, and a marker nested in a map or an inner vector is left as it is.
