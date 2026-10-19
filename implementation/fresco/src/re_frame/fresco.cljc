(ns re-frame.fresco
  "FRESCO — the public door.

  Everything an author writes against lives here, and everything below is
  `re-frame.fresco.impl.*`. The intended spelling is one alias:

      (ns my.app
        (:require [re-frame.fresco :as h]))

      (h/defview todo-row [{:keys [id]}]
        (let [todo (h/sub [:todo/by-id id])]
          [:li {:on-click [:todo/toggle id]} (:text todo)]))

  ## What this namespace is

  **The four authoring macros live HERE** — `defview`, `defhost` and the
  two carriers, `event` and `render-fn` — because this is the authoring
  surface. The contract every var here keeps is
  `implementation/fresco/spec/authoring-contract.md`. The frame doors are core's: `rf/current-frame-id` and
  zero-arity `rf/capture-frame` answer the rendering boundary's frame
  inside a body, and this door duplicates neither.

  **Every other var below is an ALIAS.** This namespace adds no
  behaviour: each `def` names a value `re-frame.fresco.impl.*` owns.

  ## The marker keywords need no export

  `::h/value`, `::h/prevent`, `::h/revision`, `::h/checked` and
  `::h/clear` read `:re-frame.fresco/…`. Alias this
  namespace as `h` and the auto-resolved spelling the guide teaches
  resolves, with no keyword changing value.

  ## What this door does NOT carry — the optional modules

  `presence` is `re-frame.fresco.motion/presence`, required
  separately:

      (:require [re-frame.fresco :as h]
                [re-frame.fresco.motion :as motion])

  There is no alias here, and that is the point rather than an
  omission. An optional module has to be **absent when unused** — no
  reachable code in an application that never asked for it — and one
  `:require` here would put the retention machine into every bundle that
  ever touched the door. So the door names no optional module, and
  `fresco/scripts/check_optional_module_reachability.py` fails if one
  appears. The presence override keys are the motion module's own
  vocabulary and spell its namespace: `::motion/mounting` /
  `::motion/unmounting`, i.e. `:re-frame.fresco.motion/…` (naming-ledger
  row 31). They are not `::h/…`
  keywords and are not in the marker set above; the codec recognises
  them, and drops one written out of a presence tray's reach, so it never
  reaches the DOM or a component as a prop."
  ;; The macro side reaches core's `re-frame.source-coords` for the one
  ;; thing a defining macro cannot do portably by hand: pick the right
  ;; `:file` for the coordinate it captures. Under CLJS the analyzer never
  ;; binds Clojure's `*file*` during expansion, so a naive read bakes the
  ;; `"NO_SOURCE_PATH"` sentinel into every declaration; core solves that
  ;; once, absolutises the classpath-relative path while it is there, and
  ;; is already a dependency of this package.
  #?(:clj (:require [re-frame.source-coords :as rf.source-coords]))
  #?(:cljs
     (:require [re-frame.fresco.impl.boundary :as rf.fresco.impl.boundary]
               [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
               [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
               [re-frame.fresco.impl.frame-boundary :as rf.fresco.impl.frame-boundary]
               [re-frame.fresco.impl.intent]
               [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
               [re-frame.fresco.impl.portal :as rf.fresco.impl.portal]
               [re-frame.fresco.impl.route-link :as rf.fresco.impl.route-link]
               [re-frame.fresco.impl.state :as rf.fresco.impl.state]))
  #?(:cljs (:require-macros [re-frame.fresco :refer [defview defhost event render-fn]])))

;; ---------------------------------------------------------------------------
;; The four macros
;; ---------------------------------------------------------------------------
;;
;; This file is a `.cljc` only because a CLJS namespace cannot otherwise
;; hand its macros to a consumer, and its `:clj` side is these four macros
;; and nothing else — no runtime namespace is required on the JVM, so there
;; is no JVM render path for a reader to mistake for one. A `.cljc` that
;; carried more would invite a JVM-side implementation this package does
;; not have.

#?(:clj
   (defmacro defview
     "**Define a view** — a real React function component. `argv` is the
  ordinary one-props-map argument vector, and in Hiccup the var is a head:

      (h/defview todo-row [{:keys [id]}]
        (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
          [:li {:class (when done? :done)
                :on-click [:todo/toggle id]}
           title]))

      [todo-row {:key id :id id}]

  The body reads with `h/sub` anywhere in its synchronous extent and
  writes its handlers as data. Hooks do not belong in a body; a plain
  function in head position is refused. In debug builds the view is also
  registered under `(keyword \"<ns>\" \"<sym>\")`, so `(rf/view id)`
  answers it. Contract: implementation/fresco/spec/authoring-contract.md
  §The boundary ABI and §Reads."
     ;; THE EMITTED FN IS ANONYMOUS, and that is the contract. A named
     ;; `fn` binds its own name for its own body, so a name derived from
     ;; `sym` would shadow an author's same-named helper —
     ;; `(defn todo-row-body [p] …)` + `(h/defview todo-row [p]
     ;; (todo-row-body p))` would recurse until the stack overflowed.
     ;; The identifiers this macro decides — the `"<ns>/<sym>"` view
     ;; name and the source coordinate — are passed as VALUES.
     ;;
     ;; HOOKS ARE A LAW, NOT A REFUSAL. A body is dynamically composed,
     ;; so a hook in it would make React's call sequence follow a Fresco
     ;; data path. The macro reads no body, so nothing here could refuse
     ;; one; React's own rules-of-hooks error is the enforcement. The
     ;; shell's own two hooks are the whole budget (I9).
     ;;
     ;; THE SOURCE COORDINATE rides a declaration extent around the
     ;; mint, so every refusal raised while this body runs names where
     ;; the view was written. Both halves are `debug-enabled?`-gated, and
     ;; the extent closes in a `finally` so a refusing mint does not
     ;; leave its name ambient.
     ;;
     ;; THE REGISTRATION is an authoring-time alias for FORWARD
     ;; resolution only: one `:view` registrar entry carrying the
     ;; coordinate and the minted head at `:handler-fn`, `identical?` to
     ;; what the `def` binds. It mints no runtime identity, and it rides
     ;; the same `debug-enabled?` gate, so a release build registers
     ;; nothing. `re-frame.fresco.impl.collector/publish-view-alias!`
     ;; carries the slot's shape.
     [sym & more]
     (let [doc       (when (string? (first more)) (first more))
           [argv & body] (if doc (rest more) more)
           view-name (str (ns-name *ns*) "/" sym)
           ;; The registrar id, derived by `reg-view`'s own
           ;; rule (`core-reg-view-macro/expand-reg-view`) so the two
           ;; substrates spell one convention. No `^{:rf/id …}` override
           ;; here: a `defview` has exactly one name and this is it.
           view-id   (keyword (str (ns-name *ns*)) (str sym))
           coord     (rf.source-coords/coords-form (meta &form) *file* (ns-name *ns*))
           ;; The registration slot IS the coordinate map — `reg-view`
           ;; merges its coords into the slot's top level and tools read
           ;; them back from there — plus the author's `:doc`, without
           ;; which the registrar would dev-warn `:rf.warning/missing-doc`
           ;; against a view that IS documented.
           slot      (cond-> coord doc (assoc :doc doc))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.fresco.impl.error/declaring! ~view-name ~coord))
            (try
              (let [head# (re-frame.fresco.impl.collector/mint-view!
                            ~view-name
                            ;; ANONYMOUS — see the docstring's section on
                            ;; naming. A named `fn` binds its own name
                            ;; for its own body, so any name derived from
                            ;; `sym` shadows the author's helper of that
                            ;; name.
                            (fn ~argv ~@body))]
                ;; Inside the extent, so a refusal the
                ;; registration raised would name this declaration; after
                ;; the mint, because the head is what the entry carries.
                ;; The head is still what the `def` binds: registration is
                ;; a side effect and returns nothing to it.
                (when re-frame.interop/debug-enabled?
                  (re-frame.fresco.impl.collector/publish-view-alias!
                    ~view-id ~slot head#))
                head#)
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.fresco.impl.error/declared!)))))))))

#?(:clj
   (defmacro event
     "**The event carrier.** A function whose returned VECTOR is
  dispatched into the frame of the view that wrote it; any other return is
  ignored. It means this at every prop — a native tag, a `defhost` or a
  `[:>]` crossing, whatever the prop is called — and every argument the
  invoker passes reaches the body, so a value-first foreign callback is
  written here:

      [date-picker {:on-change (h/event [date _e] [:todo/set-due id date])}]

  The value is an ordinary function, so where Fresco does not walk it
  simply runs. Contract: implementation/fresco/spec/authoring-contract.md
  §Intents."
     [argv & body]
     `(re-frame.fresco.impl.intent/callback (fn ~argv ~@body))))

#?(:clj
   (defmacro render-fn
     "**The render carrier.** A function a foreign component calls during
  its own render: the Hiccup it returns becomes a React element, and an
  intent inside that Hiccup dispatches into the frame of the view that
  wrote the carrier. It means this at every prop, whatever the prop is
  called:

      [virtual-list {:total-count  (count ids)
                     :item-content (h/render-fn [i]
                                     [:li {:on-click [:row/pick (nth ids i)]}
                                      (nth ids i)])}]

  It is render output and dispatches nothing itself. Contract:
  implementation/fresco/spec/authoring-contract.md §The interop door."
     [argv & body]
     `(re-frame.fresco.impl.intent/render-carrier
        (fn ~argv (re-frame.fresco.impl.codec/as-element (do ~@body))))))

#?(:clj
   (defmacro defhost
     "**The interop door.** Name the crossing to a foreign React component
  once, at namespace top level; the var is a hiccup head anywhere, like a
  view:

      (defhost date-picker DatePicker)

      [date-picker {:selected  due-date
                    :on-change (h/event [date _e] [:task/set-due date])}]

  `(defhost name doc? component opts?)`. Every key of `opts` is optional:

  - `:slots` — the set of props that take markup; Hiccup there lowers
    under the writing view's frame. At any other prop a vector is data.
  - `:server` — `:client-only`, the default, or `:render`, the assertion
    that the component renders deterministically on the server.
  - `:fallback` — Client-only's inert placeholder Hiccup.

  Props cross as at a native tag: a carrier (`h/event`, `h/render-fn`)
  keeps its meaning at every prop, a vector or key-map is an intent only
  at an `on*` prop, and a plain function crosses untouched. Refused at
  load: a nil component (`:rf.error/fresco-host-no-component`), and as
  `:rf.error/fresco-bad-host-declaration` non-map options, an unknown
  option, a malformed `:slots` set and any form after `opts`. Contract:
  implementation/fresco/spec/authoring-contract.md §The interop door."
     ;; Positional flexibility is deliberately NOT accepted. A docstring
     ;; after the component binds to `opts` and pushes the real options
     ;; map into the discarded tail, so it is refused at whichever guard
     ;; it reaches first.
     ;;
     ;; The declaration extent (`declaring!` … `declared!`) puts the
     ;; declaration's file and line on a refusal `mint-host!` raises at
     ;; namespace load, where no render is on the stack. It closes in a
     ;; `finally` because a refusing `defhost` is the ordinary way a
     ;; declaration throws under HMR, and without it every later refusal
     ;; would inherit this declaration's `:view` and `:source`.
     [sym & more]
     (let [doc         (when (string? (first more)) (first more))
           forms       (if doc (rest more) more)
           [component opts] forms
           ;; THE TAIL, refused rather than dropped: the destructure
           ;; above is fixed-width, so everything past `opts` would
           ;; vanish silently. Detected here so the extra forms are quoted
           ;; rather than evaluated; raised at load, inside the extent, so
           ;; the refusal carries the declaration's coordinate.
           extra       (seq (drop 2 forms))
           host-name   (str (ns-name *ns*) "/" sym)
           coord       (rf.source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.fresco.impl.error/declaring! ~host-name ~coord))
            (try
              ~(if extra
                 `(re-frame.fresco.impl.codec/refuse-host-extra-forms!
                    ~host-name '~(vec extra))
                 `(re-frame.fresco.impl.codec/mint-host!
                    ~host-name ~component ~(or opts {})))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.fresco.impl.error/declared!)))))))))

;; ---------------------------------------------------------------------------
;; The vars — aliases, every one naming a value `impl.*` owns
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^{:doc "**The ambient collector** — read a subscription's value
  from anywhere inside a body, including inside a `when`, a `for` or an
  inlined helper. The edge is recorded where the read happens, so a branch
  not taken contributes no edge.
  `re-frame.fresco.impl.collector/sub`."}
       sub rf.fresco.impl.collector/sub)

     (def ^{:doc "`h/error-boundary` — the runtime's own error boundary
  (HD-020(c)); takes `:fallback`, `:reset-key` and `:on-error`.

  Named for React's own term of art, which is also what the naming ledger
  rules (row 12). A bare `boundary` would be the wrong word twice over: every minted `defview` is already *a boundary* here,
  and React has a second kind — the Suspense boundary — that this one is
  not. `re-frame.fresco.impl.boundary/boundary`."}
       error-boundary rf.fresco.impl.boundary/boundary)

     (def ^{:doc "`h/reg-state` — the instance-key sugar (HD-009). Mints one
  parametric subscription and one setter event under `[:ui ::concern ikey]`,
  and ensures the shared clear event `[::h/clear ::concern ikey]` is
  registered. `re-frame.fresco.impl.state/reg-state`."}
       reg-state rf.fresco.impl.state/reg-state)

     (def ^{:doc "`h/portal` — **hiccup into `createPortal`**.
  A legal hiccup head taking `:target`, the DOM container the subtree
  renders into, and optionally `:fallback`, markup for the portal's own
  tree position while the page is server-rendered:

      [h/portal {:target js/document.body}
       [:div.toast {:on-click [:toast/dismiss]} \"saved\"]]

  Three facts, and nothing else to learn. **Events bubble through the
  REACT tree**, so an `:on-click` on a hiccup ancestor sees clicks inside
  the toast although its DOM node sits on `body`, and intents fire into
  the writing boundary's frame exactly as they do in an ordinary child.
  **A changed `:target` is a remount**, because React reconciles a portal
  position by its container — so keep the target stable rather than
  computing one per render. **It is client-only**: `createPortal` needs a
  container that already exists, a server render has none, so the
  portalled subtree is absent from the response and `:fallback` is what a
  caller puts at the tree position instead.

  A `:target` that is not a DOM container — overwhelmingly a lookup that
  answered nothing — is React's own *Target container is not a DOM
  element* at the client render.

  The raw mechanism, for containers the application does not own.
  Anchoring, dismissal and focus conduct are the overlay module's.
  `re-frame.fresco.impl.portal/portal`."}
       portal rf.fresco.impl.portal/portal)

     (def ^{:doc "One real anchor, as data — href and click decision taken
  whole from routing's late-bound seams. A plain function, not a boundary:
  it mints no boundary and adds no hook.
  `re-frame.fresco.impl.route-link/route-link`."}
       route-link rf.fresco.impl.route-link/route-link)

     (def ^{:doc "`h/as-element` — **the one explicit Hiccup→ReactNode
  conversion**, under the frame of the view currently rendering. It is
  the answer where no declaration reaches: one element at an undeclared
  `[:>]` prop, or one Fresco child inside a native subtree:

      [:> Panel {:title (h/as-element [:h2 \"Tasks\"])}]

  A render carrier (`h/render-fn`) and a declared `:slots` prop convert
  for you. Nothing infers markup: at an undeclared foreign prop a vector
  is data. An intent in markup converted outside every render extent is
  `:rf.error/fresco-intent-outside-boundary`. Contract:
  implementation/fresco/spec/authoring-contract.md §The interop door."}
       as-element rf.fresco.impl.codec/as-element)

     (def ^{:doc "`h/as-component` — **the outward bridge**.
  Answers a real React component for a hiccup head, so a React parent —
  UIx, Reagent or plain JavaScript — mounts a minted Fresco
  view under the frame it is already in:

      (def article-card* (h/as-component article-card))

  Declared once at top level, beside the view. The parent's props arrive
  as the view's ordinary props map (`articleId` → `:article-id`),
  children at `:children`, values as this shallow decode finds them — a
  Reagent parent's `[:>]` converts first, so names round-trip across the
  crossing and values do not. The frame comes from React context: ANY
  frame, written by any React-shaped adapter, rather than a Fresco
  root — and no second root, state owner or props ABI appears anywhere.
  Sits HERE rather than on the native tier because a UIx or JavaScript
  parent must not have to require the native namespace — and therefore
  ship it — to cross inward.
  `re-frame.fresco.impl.codec/as-component`."}
       as-component rf.fresco.impl.codec/as-component)

     ;; ---- the two frame boundaries: ENSURE and SCOPE ---------------------
     ;;
     ;; The frame is spelled IN THE TREE, as it is on every other
     ;; React-shaped substrate (spec/002). The root
     ;; door below carries React-root options and nothing about frames.

     (def ^{:doc "`h/frame-root` — **ENSURE a named frame for a subtree**,
  and the head a Fresco app's root view sits under:

      (h/render! app-root
        [h/frame-root {:id             :app/main
                       :initial-events [[:app/init]]
                       :fx-overrides   {:http stub-http}}
         [root-view]]
        node)

  **It takes the `rf/make-frame` option map WHOLE** — `:id`,
  `:initial-events`, `:images`, `:url-bound?`, `:fx-overrides`, `:preset`,
  every record-config key `make-frame` honours — because it IS the
  `make-frame` call, written where the frame is used. There is no curated
  subset to be caught out by.

  **ENSURE, not create.** Absent, the frame is created and
  `:initial-events` run once; live, it is REUSED — so a second root under
  the same `:id` attaches to it rather than resetting it. What survives
  is the DURABLE state: app-db, runtime-db, sub-cache and queue, and
  `:initial-events` are re-recorded but NEVER replayed (spec/002
  §`frame-root` reuse-without-reseed; EP-0027). What the re-acquire DOES
  refresh is the record config, because the ENSURE is a second
  `rf/make-frame` under the same id and that is `make-frame`'s
  idempotent-replacement path — the Clojure re-def model, where
  re-declaring an id refreshes its config while its state survives —
  which is what makes a hot reload Just Work.

  **So `frame-root` DECLARES a frame; it is not the verb for JOINING
  one.** That refresh REPLACES the record config wholesale rather than
  merging it, so a second boundary naming a live `:id` with a partial
  opts map silently drops what the creator configured. To reach a frame
  some other boundary already declared, use the sibling verb:
  `[h/frame-provider {:frame :app/main} …]` takes no opts at all, and
  creates, refreshes and destroys nothing.

  Unmounting destroys NOTHING: a frame outlives the boundary that ensured
  it, and `rf/destroy-frame!` is the verb for ending one.

  **The ENSURE is commit-owned.** The first render emits no subtree at
  all, and the frame is made in a `useLayoutEffect` — so a render React
  discards (Suspense, a concurrent abort) creates nothing and seeds
  nothing, and React's own layout phase re-renders synchronously before
  the browser paints. Under `h/render!`, which renders inside `flushSync`,
  that means the door still returns with the seeded markup on the page.

  Fail-loud where it matters: `:id` is required and must be a keyword; a
  `:frame` key is `:rf.error/frame-root-given-frame`, naming
  `h/frame-provider`; and changing a MOUNTED boundary's `:id` or opts is
  `:rf.error/frame-root-reconfigured` rather than a silent no-op — pass a
  React `:key` and remount to point at a different frame.

  ONE implementation across every substrate: the two-pass is core's
  `re-frame.views.frame-boundary/frame-root-fc`, the same component
  `rf/frame-root` and `re-frame.adapter.uix/frame-root` mount.
  `re-frame.fresco.impl.frame-boundary/frame-root`."}
       frame-root rf.fresco.impl.frame-boundary/frame-root)

     (def ^{:doc "`h/frame-provider` — **SCOPE an EXISTING frame to a
  subtree**. `frame-root`'s sibling and its opposite verb: it creates,
  refreshes and destroys nothing.

      (h/render! app-root
        [h/frame-provider {:frame :app/main} [views/page {}]]
        node
        {:hydrate? true :identifier-prefix \"main\"})

  Three places want it. **A second root on the page**, sharing a frame
  another root already ensured — the PURE JOIN, and the reason
  `frame-root` is not the verb for it: re-declaring a live `:id` would
  replace that frame's record config. **After SSR**, where the frame was
  made before the payload was installed and an adopting root must render
  the server's element shape on its FIRST pass — which is the reason
  SCOPE is the verb there, and it is a shape argument rather than a state
  one: see `h/render!`'s `{:hydrate? true}`. **A subtree on another
  frame** — a tenant switcher, a preview pane — nested under the root's
  own boundary. Everything written below the head acts on that frame —
  an inline intent, an `h/event`, a wrapper's children, a child view —
  while the calls the enclosing body itself makes (`h/sub`,
  `h/route-link`, `rf/capture-frame`) stay in the body's frame, because
  they run before the head lowers anything: read or link the scoped
  frame from a child view under the provider.

  `:frame` is required and takes the one frame-target grammar `dispatch`
  and `subscribe` teach: a frame-id keyword, or the live frame value
  `rf/make-frame` returns. **A frame that is not live FAILS LOUD**
  (`:rf.error/frame-provider-frame-absent`) rather than scoping a subtree
  to nothing — that guardrail is the reason SCOPE is its own component.
  An `:id` key is `:rf.error/frame-provider-given-id`, naming
  `h/frame-root`.

  Distinct from `re-frame.fresco.substrate/frame-provider`, which is the
  reactive-substrate contract's `:register-context-provider` slot — a
  lower-level seat the contract requires and an application never writes.
  This is the authoring verb, and it writes the same one React context
  every adapter reads, so a Fresco subtree under a UIx provider (or the
  reverse) resolves the same frame.
  `re-frame.fresco.impl.frame-boundary/frame-provider`."}
       frame-provider rf.fresco.impl.frame-boundary/frame-provider)

     ;; ---- the root lifecycle: one handle, one verb, one teardown ---------
     ;;
     ;; Spec 006 §The client root's grammar, which every React view
     ;; adapter publishes: an inert handle, a `render!` whose FIRST call
     ;; creates or adopts and whose later calls update, and an idempotent
     ;; `unmount!`. Every door here is ROOT-SCOPED, which is why
     ;; `release!` is not among them. A hydrating first render adopts the
     ;; tree `impl.mount/tree` shapes, and `re-frame.fresco.server` emits
     ;; that same tree by CALLING it — React derives a `useId` from tree
     ;; position as well as from the prefix, so a second function deciding
     ;; the root's shape for either half hydrates into a text mismatch
     ;; (docs/design/fresco/product/dispositions.md HS-11).

     (def ^{:doc "`h/client-root` — **allocate the handle a root lives
  in**. Inert: no DOM work, no React call, nothing to undo — so it belongs
  under a `defonce` at namespace load, above the boot that renders through
  it.

      (defonce app-root (h/client-root))

  The handle is OPAQUE. It is the one thing `render!` and `unmount!`
  take, the raw React Root is reachable through none of them, and
  liveness is never read off it — a Root's membership in the package's
  active set is the single liveness fact, which is what lets
  `rf/destroy-adapter!` and `unmount!` both reach React's own
  `root.unmount()` exactly once per root, whichever gets there first.

  One handle owns AT MOST ONE Root at a time. Two roots on a page are two
  handles; a handle whose Root has been released is inert again and the
  next `render!` through it mounts afresh.
  `re-frame.fresco.impl.mount/client-root`."}
       client-root rf.fresco.impl.mount/client-root)

     (def ^{:doc "`h/render!` — **the root door and the hot-reload door,
  in one verb**: render `tree` through `handle` at DOM node `node`.

      (defonce app-root (h/client-root))

      (defn ^:dev/after-load mount! []
        (h/render! app-root
                   [h/frame-root {:id :rf/default
                                  :initial-events [[:counter/initialise]]}
                    [counter]]
                   (js/document.getElementById \"app\")))

  **The FIRST call through a handle CREATES the Root; every later call
  UPDATES it.** That is the whole of why one verb is enough: the boot path
  and the `^:dev/after-load` hook are the same line, and no application
  keeps a root in an atom of its own or branches on whether one exists.
  On a hot reload the Root, its frame and that frame's app-db carry on,
  so the application state is untouched. The views do not: each
  `defview` evaluation mints a new component, as does each
  `h/as-component` call, and React remounts a component whose type
  changed, with everything beneath it. The DOM under a reloaded view is
  rebuilt, and what that DOM owns — focus, the caret, scroll position —
  starts over. Every update commits inside `flushSync`, so a witness may
  read the DOM on the next line.

  **`{:hydrate? true}` makes that first call ADOPT** the server-rendered
  DOM already in `node` instead of replacing it — `hydrateRoot` rather
  than `createRoot`, with this root's own adoption window and, in debug
  builds, its own recoverable-error reporter feeding Spec 011's
  `:rf.ssr/hydration-mismatch`. It is a FIRST-CALL mode and nothing else:
  a live handle updates the Root it already owns, so `{:hydrate? true}` on
  a later call is ignored rather than hydrating twice.

  **`node` and `opts` take effect on the first call only.** Later calls
  take them and use neither, though `opts` is still checked on every call
  — pass them or don't, whichever reads better where you are calling
  from; the three-arity form is the whole of the hot reload.

  **`tree` is Fresco hiccup whose root is a frame boundary head** —
  `[h/frame-root {:id …} …]` to ENSURE, `[h/frame-provider {:frame …} …]`
  to SCOPE. Re-render the WHOLE tree, boundary head included and carrying
  the SAME options. The frame is spelled in the tree, so a render that
  drops the head renders a root with no frame under it and every bare
  `dispatch` / `subscribe` beneath it loses the frame it resolved against.
  Nor may a later render merely TRIM the head's options: a committed
  `frame-root` scopes one frame for its lifetime and refuses
  reconfiguration, so re-rendering it with `:initial-events` dropped — on
  the reasoning that they have already run — raises
  `:rf.error/frame-root-reconfigured`. Re-rendering the head unchanged is
  free: `frame-root` ENSUREs, so it finds the frame live and reuses it,
  and `:initial-events` fire once per frame lifetime, so re-passing them
  re-records without replaying. Writing the tree ONCE, as a function the
  one `render!` call takes, is what keeps that true:

      (defn- tree []
        [h/frame-root {:id :app/main :initial-events [[:app/init]]}
         [app {}]])

      (defn ^:dev/after-load mount! []
        (h/render! app-root (tree) el))

  **`opts` carries ROOT options only, and a key it does not own FAILS
  LOUD** — there is no closed list quietly ignoring the rest.

  **The FRAME is the tree's, not the door's.** `[h/frame-root {:id …}]`
  ENSUREs — creates the frame if absent, reuses it as it stands if live —
  and takes the `rf/make-frame` option map WHOLE, `:initial-events`,
  `:fx-overrides`, `:url-bound?` and the rest; `[h/frame-provider {:frame
  …}]` SCOPEs a frame that already exists. Handing `:frame` or
  `:initial-events` to this door is `:rf.error/fresco-frame-config-
  misplaced`, naming the head that spells it; any other key is
  `:rf.error/fresco-unknown-root-option`. That is the same one boundary
  vocabulary every substrate writes (spec/002 §`frame-root`,
  §`frame-provider`), so a Fresco boot line reads like a Reagent or UIx
  one and every frame option has exactly one place to go.

  **`:identifier-prefix`** is React's own `identifierPrefix`, handed to
  `createRoot` or `hydrateRoot` untouched. It exists because `useId`
  numbers every root from the same start, so a page mounting two roots
  gives them distinct prefixes or watches their generated ids collide. A
  page with one root names none. No default, no coercion, no validation:
  React owns the option and this is a pass-through to it.

  **`:on-recoverable-error`** is React's own `onRecoverableError`, called
  with the error and React's error info for each error React recovers
  from. On a hydrating root the framework reports first — inside the
  adoption window a recovery is Spec 011's `:rf.ssr/hydration-mismatch`:
  the always-on record and, in development, the trace — and then calls
  this, in place of React's default report. It is how CI fails a build on
  a hydration recovery: collect what it is handed and assert nothing
  arrived.

  ## Hydrating: three calls, three jobs

  **State comes first, and it is a different door.** This adopts DOM;
  `re-frame.ssr/hydrate!` installs the server's app-db through
  `:rf/hydrate` and must run BEFORE it, so the first client render sees
  the state the server rendered from. Neither hydration step creates the
  frame:

      (rf/make-frame {:id :app/main})                     ;; 1. frame
      (ssr/hydrate! {:frame :app/main})                   ;; 2. state
      (h/render! app-root                                 ;; 3. DOM
                 [h/frame-provider {:frame :app/main} [views/page {}]]
                 node
                 {:hydrate? true :identifier-prefix \"main\"})

  **The hydrating tree SCOPEs rather than ENSUREs, and the reason is
  SHAPE, not state.** `frame-root`'s ENSURE is commit-owned, so its FIRST
  render emits no descendant subtree at all and the children arrive on a
  second pass. An adopting root has to render the server's element shape
  on its first pass — that is what `hydrateRoot` is matching against,
  `useId` positions included — so an `[h/frame-root {:id …} …]` under
  `{:hydrate? true}` hands React an empty tree where the server's markup
  is. `[h/frame-provider {:frame …} …]` renders its children immediately,
  so the shapes agree. `frame-provider` refuses an ABSENT frame
  (`:rf.error/frame-provider-frame-absent`), which catches a boot that
  never made one; it does NOT detect a frame that is live but never
  hydrated.

  **Hand `:identifier-prefix` the same string the server render used.**
  React numbers `useId` per root and prefixes it with this option, so a
  hydrating root given a different prefix — or none, where the server had
  one — resolves every id in the tree differently from the bytes it is
  adopting. `re-frame.fresco.server/render` takes the same key.

  **A hydrating first call returns BEFORE adoption finishes.** React
  adopts concurrently and nothing here forces it synchronously, so the
  DOM on the next line is still the server's; a witness waits for the
  adoption window to close rather than for a flush. Every LATER call
  through the same handle is an ordinary `flushSync` update, and it is
  handed the same wrapper tree the adoption produced — React reconciles a
  root by its top element, so a bare tree where that wrapper stood would
  discard every node the adoption established.

  Root-scoped, like every door in this section: each root owns its own
  container, prefix, adoption window and recoverable-error stream, so one
  root's mismatch is reported against that root and cannot silence a
  sibling's. `re-frame.fresco.impl.mount/render-client-root!`,
  `re-frame.fresco.impl.mount/require-root-options!`."}
       render! rf.fresco.impl.mount/render-client-root!)

     (def ^{:doc "`h/unmount!` — **take THIS root down** and return its
  handle to inert; a later `h/render!` through the same handle mounts
  afresh.

      (h/unmount! app-root)

  Idempotent, and it leaves everything else exactly where it was: the
  sibling roots' subscriptions and frames, and the node you rendered into,
  which React empties but does not remove. It destroys no frame — a frame
  outlives the root that ensured it, and `rf/destroy-frame!` is the verb
  for ending one.

  Because the Root sits in the package's active set, `rf/destroy-adapter!`
  releases a still-live handle's Root exactly once and this door then
  finds nothing left to do — liveness is the set's to say, never the
  handle's. `re-frame.fresco.impl.mount/unmount-client-root!`."}
       unmount! rf.fresco.impl.mount/unmount-client-root!)))
