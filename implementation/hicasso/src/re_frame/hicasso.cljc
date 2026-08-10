(ns re-frame.hicasso
  "HICASSO — the public door (rf2-hic-001).

  Everything an author writes against lives here, and everything below is
  `re-frame.hicasso.impl.*`. The intended spelling is one alias:

      (ns my.app
        (:require [re-frame.hicasso :as h]))

      (h/defview todo-row [{:keys [id]}]
        (let [todo (h/sub [:todo/by-id id])]
          [:li {:on-click [:todo/toggle id]} (:text todo)]))

  ## Provenance — this package is the prototype, MOVED

  The runtime was measured as `re-frame.bench.hicasso.{front,arm1}.*` in
  `implementation/freehand/test/re_frame/bench/hicasso/`. rf2-hic-001
  copied it here **mechanically**: namespaces renamed, nothing else. The
  bench tree still stands, still runs, and is pinned file-by-file by
  `frozen-sources.edn` beside this source root, so \"the package is the
  prototype\" is a checked claim rather than a remembered one.

  Two consequences of the move are worth naming, because neither is a
  decision this bead took:

  - **`arm1/lang.clj`'s three macros live in THIS namespace.** They are
    the authoring surface, so this is where the bead puts them; their
    bodies are the prototype's, with only the emitted target namespaces
    renamed. The macro names are the prototype's too — `hfn` rather than
    `fn`, `hframe` rather than `frame` — because rf2-hic-001 renames
    nothing. The authoring surface those spellings were chosen FOR is
    `h/fn` and `h/frame`; closing that gap is a naming decision and
    belongs to the bead that owns naming, not to a mechanical move.
  - **Every var below keeps its prototype name.** This namespace adds no
    behaviour: each `def` is an alias, and the value on the right is the
    one the bench suites measured.

  ## The marker keywords need no export

  `::h/value`, `::h/prevent`, `::h/navigate`, `::h/mounting`,
  `::h/unmounting`, `::h/revision`, `::h/checked` and `::h/clear` already
  read `:re-frame.hicasso/…` in the prototype — the package's name was
  anticipated when they were minted. Alias this namespace as `h` and the
  auto-resolved spelling the guide teaches resolves for the first time,
  with no keyword changing value.

  ## What this door does NOT carry — the optional modules (rf2-hic-053)

  `presence` used to be a var here and is now
  [[re-frame.hicasso.motion/presence]], required separately:

      (:require [re-frame.hicasso :as h]
                [re-frame.hicasso.motion :as motion])

  There is no alias left behind, and that is the point rather than an
  omission. An optional module has to be **absent when unused** — no
  reachable code in an application that never asked for it — and one
  `:require` here would put the retention machine into every bundle that
  ever touched the door. So the door names no optional module, and
  `hicasso/scripts/check_optional_module_reachability.py` fails if one
  reappears. `::h/mounting` and `::h/unmounting` stay in the marker set
  above: they are the vocabulary the motion module reads, and moving the
  namespace is not renumbering the keywords (naming-ledger row 31)."
  ;; The macro side reaches core's `re-frame.source-coords` for the one
  ;; thing a defining macro cannot do portably by hand: pick the right
  ;; `:file` for the coordinate it captures. Under CLJS the analyzer never
  ;; binds Clojure's `*file*` during expansion, so a naive read bakes the
  ;; `"NO_SOURCE_PATH"` sentinel into every declaration; core solved that
  ;; once, absolutises the classpath-relative path while it is there, and
  ;; is already this package's only dependency (rf2-hic-007).
  #?(:clj (:require [re-frame.source-coords :as source-coords]))
  #?(:cljs
     (:require [re-frame.hicasso.impl.boundary :as impl-boundary]
               [re-frame.hicasso.impl.collector :as impl-collector]
               [re-frame.hicasso.impl.intent :as impl-intent]
               [re-frame.hicasso.impl.mount :as impl-mount]
               [re-frame.hicasso.impl.route-link :as impl-route-link]
               [re-frame.hicasso.impl.state :as impl-state]))
  #?(:cljs (:require-macros [re-frame.hicasso :refer [defview defhost hfn]])))

;; ---------------------------------------------------------------------------
;; The three macros — `re-frame.bench.hicasso.arm1.lang`, moved
;; ---------------------------------------------------------------------------
;;
;; Bodies verbatim from the prototype; only the three emitted target
;; namespaces are renamed. The prototype kept them in a `.clj` on the
;; grounds that a `.cljc` "would invite a JVM-side implementation this arm
;; does not have". That reason survives the move: this file is a `.cljc`
;; only because a CLJS namespace cannot otherwise hand its macros to a
;; consumer, and its `:clj` side is these three macros and nothing else —
;; no runtime namespace is required on the JVM, so there is still no JVM
;; render path for a reader to mistake for one.

#?(:clj
   (defmacro defview
     "Mint a boundary — a real React function component (HD-016).

  `argv` is the ordinary one-props-map argument vector, so destructuring
  reads as it does in any Clojure fn. In hiccup the resulting var is a
  legal head: `[todo-row {:key id :id id}]`. A plain function in head
  position is a loud error rather than a silent embedding, which is what
  makes the head's identity stable by construction and leaves the codec's
  stable-component-head cache with nothing to do.

  ## The fn this expands to is ANONYMOUS, and that is the contract
  (rf2-jan2)

  The expansion binds NO name inside your body, so every symbol a body
  resolves is one you wrote. The ordinary extract-a-helper spelling is
  therefore safe at the ordinary spelling:

      (defn todo-row-body [props] …)
      (h/defview todo-row [props] (todo-row-body props))

  It was not always. The macro used to name the fn it emits
  `<sym>-body`, and a named `fn` binds its own name for its own body — so
  the pair above expanded to `(fn todo-row-body [p] (todo-row-body p))`
  and recursed until the stack overflowed, under React exactly as under
  the test kit, reporting `Maximum call stack size exceeded` and naming
  neither the view nor the macro that shadowed the helper.

  Anonymity rather than a gensym or a reserved prefix, because those make
  the collision improbable where this makes it unrepresentable: a fn with
  no name has nothing to shadow with. Nothing was spent for it. The
  identifiers this macro decides are the `\"<ns>/<sym>\"` view name and
  the coordinate below, both computed here and both passed as VALUES —
  `mint-view!` stamps the view name as `displayName` on the body and on
  the component, and Spec 009 keys its render measure `rf:render:<view
  name>` off the same string. The emitted fn's own symbol fed none of
  them; it reached nothing but a stack frame.

  ## The source coordinate (rf2-hic-007)

  The expansion opens a declaration extent around the mint, carrying the
  `:ns` / `:file` / `:line` / `:column` this macro read off its own form.
  [[re-frame.hicasso.impl.error/fail!]] resolves it back by view name, so
  every refusal raised while this boundary's body runs says WHERE the
  boundary was written without a single call site passing anything.

  Both halves sit inside `(when re-frame.interop/debug-enabled? …)`, so
  under `:advanced` + `goog.DEBUG=false` the Closure compiler removes the
  calls and the coordinate map — file string included — and the `def` is
  the bare mint inside an empty `try`.

  The extent CLOSES IN A `finally`, so a mint that refuses does not leave
  its own name ambient for whatever runs next. A declaration refusal is
  routinely caught — by a module loader, or by an HMR runtime whose
  mounted page carries on rendering — and the slot then named a `def`
  that never completed."
     [sym & more]
     (let [doc       (when (string? (first more)) (first more))
           [argv & body] (if doc (rest more) more)
           view-name (str (ns-name *ns*) "/" sym)
           coord     (source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~view-name ~coord))
            (try
              (re-frame.hicasso.impl.collector/mint-view!
                ~view-name
                ;; ANONYMOUS — see the docstring's rf2-jan2 section. A
                ;; named `fn` binds its own name for its own body, so any
                ;; name derived from `sym` shadows the author's helper of
                ;; that name.
                (fn ~argv ~@body))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))

#?(:clj
   (defmacro hfn
     "**The one callback form** (HD-024). `h/fn` in the authoring surface;
  spelled `hfn` here because `h/fn` is qualified in the product and a
  bare `fn` would shadow `cljs.core/fn` on a `:refer`.

  Expands to nothing but a marked `fn`:

      (hfn [e] (js/Array.from (.. e -target -files)) …)
      ;; =>
      (re-frame.hicasso.impl.intent/callback (fn [e] …))

  The value is an ORDINARY FUNCTION — that is the point of the ruling.
  The contract comes from the position it is written at, so there is
  nothing to choose between and nothing that can fail to be callable
  where Hicasso does not walk. See
  [[re-frame.hicasso.impl.intent]] for the position table."
     [argv & body]
     `(re-frame.hicasso.impl.intent/callback (fn ~argv ~@body))))

#?(:clj
   (defmacro defhost
     "**The interop door — the one-line declaration (HD-011).** Name the
  crossing to a foreign React component once; use the resulting var as a
  hiccup head anywhere, indistinguishable from a view:

      (defhost date-picker DatePicker
        {:callbacks {:on-change :event}})

      [date-picker {:selected  due-date
                    :on-change (hfn [date & _] [:task/set-due date])}]

  `opts` is optional and carries `:callbacks` — a FINITE map from exact
  prop names to `:event`, `:handler` or `:render`, never inferred from an
  `on*` spelling — and `:ssr`, the server policy:

      (defhost chart Chart
        {:ssr {:fallback [:div.chart-skeleton]}})

      (defhost themed (.-Provider theme-context)
        {:ssr :render})

  `:ssr :client-only` is the DEFAULT and needs no writing: the host
  region renders nothing on the server and nothing on hydration's first
  client pass, and the foreign component mounts once the markup is
  adopted. `{:fallback <hiccup>}` renders that markup there instead.
  A fresh (non-hydrated) mount renders the foreign component on its
  first pass under either policy — the placeholder never flashes.

  `:ssr :render` is the third value and it is an ASSERTION: *this
  component is safe to render on the server*. The declaration then mints
  no gate at all — the component is the element's own type — so the same
  component, the same props, the same context and the same CHILDREN
  render on the server, on hydration's first pass and on a fresh mount.
  One tree everywhere: no mismatch, no adoption swap, no remount. It is
  the only policy under which a crossing's children reach the server
  response, which is what a transparent wrapper such as a context
  provider needs. If the assertion is false the server render throws —
  `window is not defined` — loudly and at the crossing.

  There is no fourth value, and an option `defhost` does not know is
  refused rather than ignored.

  **A declared fallback is INERT MARKUP, and that is enforced.** It is
  walked into one element at the declaration and reused at every use
  site, so a `defview` or `defhost` head written there — an element
  whose body runs later — is refused with
  `:rf.error/hicasso-host-fallback-boundary-head`. Write plain hiccup,
  or declare `:ssr :render` and render the real subtree.

  Policy lives on the declaration, so every use site inherits it; the
  defaults, the refusals and the crossing itself are
  [[re-frame.hicasso.impl.codec/mint-host!]]'s.

  The callback is an [[hfn]] and not an intent vector because
  react-datepicker calls `onChange(date, event)` — VALUE-first, with no
  event at argument one — while the vector spelling is EVENT-first
  (HD-024's argument law, in
  [[re-frame.hicasso.impl.intent]]). `[:task/set-due ::h/value]`
  there raises `:rf.error/hicasso-intent-needs-the-event` naming the
  position, and the one callback form is the spelling that sees the
  library's own arguments, in order. At an EVENT-first foreign callback —
  `onDraft(event)` — the vector and its markers are legal and shorter.
  Both halves are witnessed in `arm1/host_hatch_dom_cljs_test`.

  Like [[defview]] it is not a compiler: it expands to a `def` of the
  minted head, reads no body, and captures the name and the source
  coordinate — the name for `displayName`, and both for every diagnostic
  the crossing raises.

  ## The declaration extent earns its keep here (rf2-hic-007)

  `defview` opens one so that a refusal from a body can name the boundary
  it came from. `defhost` opens one for a nearer reason: the refusals
  above — an unknown option, a fourth `:ssr` value, a boundary head in a
  declared fallback — are raised by `mint-host!` DURING the expansion's
  own `def`, at namespace load, with no render anywhere on the stack. The
  extent is what puts the offending declaration's file and line on them.
  It is `debug-enabled?`-gated and elides whole under `:advanced` +
  `goog.DEBUG=false`.

  Those same refusals are why the extent closes in a `finally` here
  rather than being abandoned with the aborted `def`: a bad `defhost` is
  the ordinary way a declaration throws, and an HMR runtime catches it
  and keeps the mounted page rendering. Without the `finally` every
  refusal raised afterwards — from an event handler, a timer, anywhere —
  inherited this declaration's `:view` and `:source`. The refusal on its
  way out is unaffected: [[re-frame.hicasso.impl.error/fail!]] builds the
  whole ex-data before it throws."
     [sym & more]
     (let [doc         (when (string? (first more)) (first more))
           [component opts] (if doc (rest more) more)
           host-name   (str (ns-name *ns*) "/" sym)
           coord       (source-coords/coords-form (meta &form) *file* (ns-name *ns*))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (do
            (when re-frame.interop/debug-enabled?
              (re-frame.hicasso.impl.error/declaring! ~host-name ~coord))
            (try
              (re-frame.hicasso.impl.codec/mint-host!
                ~host-name ~component ~(or opts {}))
              (finally
                (when re-frame.interop/debug-enabled?
                  (re-frame.hicasso.impl.error/declared!)))))))))

;; ---------------------------------------------------------------------------
;; The vars — aliases, every one under its prototype name
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^{:doc "**The ambient collector** — read a subscription's value
  from anywhere inside a body, including inside a `when`, a `for` or an
  inlined helper. The edge is recorded where the read happens, so a branch
  not taken contributes no edge.
  [[re-frame.hicasso.impl.collector/sub]]."}
       sub impl-collector/sub)

     (def ^{:doc "**Grouped — the control.** One fixed site takes the whole
  query collection and returns the snapshot the body destructures, so a
  boundary's edge set is a function of its declaration rather than of its
  control flow. [[re-frame.hicasso.impl.collector/use-subs]]."}
       use-subs impl-collector/use-subs)

     (def ^{:doc "`h/frame` in the authoring surface — the frame id KEYWORD
  of the boundary currently rendering, for the handful of core doors that
  take one (`rf/capture-frame`, `rf/with-frame`). Spelled `hframe` because
  a bare `frame` would shadow on a `:refer`.
  [[re-frame.hicasso.impl.intent/hframe]]."}
       hframe impl-intent/hframe)

     (def ^{:doc "`h/boundary` — the runtime's own error boundary
  (HD-020(c)); takes `:fallback`, `:reset-key` and `:on-error`.
  [[re-frame.hicasso.impl.boundary/boundary]]."}
       boundary impl-boundary/boundary)

     (def ^{:doc "`h/reg-state` — the instance-key sugar (HD-009). Mints one
  parametric subscription and one setter event under `[:ui ::concern ikey]`,
  and nothing else. [[re-frame.hicasso.impl.state/reg-state]]."}
       reg-state impl-state/reg-state)

     (def ^{:doc "One real anchor, as data — href and click decision taken
  whole from routing's late-bound seams. A plain function, not a boundary:
  it mints no boundary and adds no hook.
  [[re-frame.hicasso.impl.route-link/route-link]]."}
       route-link impl-route-link/route-link)

     (def ^{:doc "Associate a DOM container, a frame keyword and a hiccup
  tree; returns the handle [[release!]] takes. HD-021(b)'s whole execution
  contract. [[re-frame.hicasso.impl.mount/root!]]."}
       root! impl-mount/root!)

     (def ^{:doc "Unmount the root and drop every edge, cell and cached
  closure the runtime held. Idempotent.
  [[re-frame.hicasso.impl.mount/release!]]."}
       release! impl-mount/release!)))
