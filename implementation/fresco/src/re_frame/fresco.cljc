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
  `as-component` alone is a `defn` that forwards to its owner, because it
  is called at namespace top level and an alias call would keep every
  unreferenced bridge in a release bundle.

  ## The marker keywords need no export

  `::h/value`, `::h/prevent`, `::h/revision` and `::h/checked`
  read `:re-frame.fresco/…`. Alias this
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
               [re-frame.fresco.impl.route-link :as rf.fresco.impl.route-link]))
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
;; The vars — aliases naming a value `impl.*` owns, and one forwarding fn
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^{:doc "`h/sub` — read a subscription's current value inside a view body,
  helpers, branches and loops included. The read is an edge the view
  re-renders on, recorded where it happens:

      (let [todo (h/sub [:todo/by-id id])] …)

  Contract: implementation/fresco/spec/authoring-contract.md §Reads.
  `re-frame.fresco.impl.collector/sub`."}
       sub rf.fresco.impl.collector/sub)

     (def ^{:doc "`h/error-boundary` — the runtime's own error boundary
  (HD-020(c)); takes `:fallback`, `:reset-key` and `:on-error`.

  Named for React's own term of art, which is also what the naming ledger
  rules (row 12). A bare `boundary` would be the wrong word twice over: every minted `defview` is already *a boundary* here,
  and React has a second kind — the Suspense boundary — that this one is
  not. `re-frame.fresco.impl.boundary/boundary`."}
       error-boundary rf.fresco.impl.boundary/boundary)

     (def ^{:doc "`h/portal` — render children into another DOM container, in the
  same React tree and frame:

      [h/portal {:target js/document.body}
       [:div.toast {:on-click [:toast/dismiss]} \"saved\"]]

  Events bubble through the React tree, a changed `:target` remounts the
  subtree, and it is Client-only, with optional `:fallback` markup at its
  own tree position on the server. Anchoring, dismissal and focus are the
  overlay module's. Contract: implementation/fresco/spec/authoring-contract.md §The interop door.
  `re-frame.fresco.impl.portal/portal`."}
       portal rf.fresco.impl.portal/portal)

     (def ^{:doc "One real anchor, as data — href and click decision taken
  whole from routing's late-bound seams. A plain function, not a boundary:
  it mints no boundary and adds no hook. Contract: implementation/fresco/spec/authoring-contract.md §Intents.
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

     (defn as-component
       "`h/as-component` — **the outward bridge**: a real React
  component a native parent — UIx, Reagent or JavaScript — mounts, wrapping
  a Fresco view:

      (def article-card* (h/as-component article-card))

  Declare it once at top level. React props arrive as the view's props map
  under canonical names (`articleId` → `:article-id`), and the frame comes
  from React context, written by any adapter. Contract: implementation/fresco/spec/authoring-contract.md §The interop
  door. `re-frame.fresco.impl.codec/as-component`."
       ;; A FORWARDING FN, NOT AN ALIAS, because it is called at namespace
       ;; top level. A call through an alias var compiles to dynamic IFn
       ;; dispatch, which Closure cannot drop, so every unreferenced bridge
       ;; would keep the view it wraps in a release bundle; a call to a
       ;; `defn` is direct, and the bridge is a pure allocation.
       [head]
       (rf.fresco.impl.codec/as-component head))

     ;; ---- the two frame boundaries: ENSURE and SCOPE ---------------------
     ;;
     ;; The frame is spelled IN THE TREE, as it is on every other
     ;; React-shaped substrate (spec/002). The root
     ;; door below carries React-root options and nothing about frames.

     (def ^{:doc "`h/frame-root` — **ENSURE a named frame for a subtree**.
  It takes the `rf/make-frame` option map whole, creates the frame when it
  is absent and runs `:initial-events` once, reuses it when it is live, and
  destroys nothing on unmount:

      [h/frame-root {:id :app/main :initial-events [[:app/init]]}
       [root-view]]

  To reach a frame another boundary declared, use `h/frame-provider`.
  Contract: implementation/fresco/spec/authoring-contract.md §Frames, roots and hydration. One implementation across
  every substrate, core's `re-frame.views.frame-boundary/frame-root-fc`.
  `re-frame.fresco.impl.frame-boundary/frame-root`."}
       frame-root rf.fresco.impl.frame-boundary/frame-root)

     (def ^{:doc "`h/frame-provider` — **SCOPE an existing frame to a
  subtree**. It creates, refreshes and destroys nothing:

      [h/frame-provider {:frame :app/main} [views/page {}]]

  `:frame` takes a frame-id keyword or a live frame value, and a frame that
  is not live fails loud. It is the verb for a second root sharing a frame,
  for an adopting root after SSR, and for a subtree on another frame. Not
  `re-frame.fresco.substrate/frame-provider`, the substrate contract's seat
  an application never writes. Contract: implementation/fresco/spec/authoring-contract.md §Frames, roots and hydration.
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
  in**. Inert and opaque: no DOM work and nothing to undo, so it belongs
  under a `defonce`:

      (defonce app-root (h/client-root))

  Contract: implementation/fresco/spec/authoring-contract.md §Frames, roots and hydration.
  `re-frame.fresco.impl.mount/client-root`."}
       client-root rf.fresco.impl.mount/client-root)

     (def ^{:doc "`h/render!` — **the root door and the hot-reload door,
  in one verb**: render `tree` through `handle` at DOM node `node`. The
  first call creates the Root — or, under `{:hydrate? true}`, adopts the
  server's DOM — and every later call updates it:

      (defn ^:dev/after-load mount! []
        (h/render! app-root
                   [h/frame-root {:id :app/main :initial-events [[:app/init]]}
                    [app {}]]
                   (js/document.getElementById \"app\")))

  `tree` is rooted at a frame head and re-rendered whole. `opts` takes
  `:identifier-prefix`, `:on-recoverable-error` and `:hydrate?`, and takes
  effect on the first call. Contract: implementation/fresco/spec/authoring-contract.md §Frames, roots and hydration.
  `re-frame.fresco.impl.mount/render-client-root!`."}
       render! rf.fresco.impl.mount/render-client-root!)

     (def ^{:doc "`h/unmount!` — **take THIS root down** and return its
  handle to inert; a later `h/render!` through it mounts afresh. Idempotent,
  and it destroys no frame:

      (h/unmount! app-root)

  Contract: implementation/fresco/spec/authoring-contract.md §Frames, roots and hydration.
  `re-frame.fresco.impl.mount/unmount-client-root!`."}
       unmount! rf.fresco.impl.mount/unmount-client-root!)))
