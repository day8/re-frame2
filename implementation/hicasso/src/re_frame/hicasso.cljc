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
  with no keyword changing value."
  #?(:cljs
     (:require [re-frame.hicasso.impl.boundary :as impl-boundary]
               [re-frame.hicasso.impl.intent :as impl-intent]
               [re-frame.hicasso.impl.mount :as impl-mount]
               [re-frame.hicasso.impl.presence-react :as impl-presence-react]
               [re-frame.hicasso.impl.route-link :as impl-route-link]
               [re-frame.hicasso.impl.runtime :as impl-runtime]
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
  stable-component-head cache with nothing to do."
     [sym & more]
     (let [doc       (when (string? (first more)) (first more))
           [argv & body] (if doc (rest more) more)
           view-name (str (ns-name *ns*) "/" sym)
           body-name (symbol (str sym "-body"))]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (re-frame.hicasso.impl.runtime/mint-view!
            ~view-name
            (fn ~body-name ~argv ~@body))))))

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
  minted head, reads no body, and captures only the name — for
  `displayName` and for every diagnostic the crossing raises."
     [sym & more]
     (let [doc         (when (string? (first more)) (first more))
           [component opts] (if doc (rest more) more)
           host-name   (str (ns-name *ns*) "/" sym)]
       `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
          (re-frame.hicasso.impl.codec/mint-host!
            ~host-name ~component ~(or opts {}))))))

;; ---------------------------------------------------------------------------
;; The vars — aliases, every one under its prototype name
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
     (def ^{:doc "**The ambient collector** — read a subscription's value
  from anywhere inside a body, including inside a `when`, a `for` or an
  inlined helper. The edge is recorded where the read happens, so a branch
  not taken contributes no edge.
  [[re-frame.hicasso.impl.runtime/sub]]."}
       sub impl-runtime/sub)

     (def ^{:doc "**Grouped — the control.** One fixed site takes the whole
  query collection and returns the snapshot the body destructures, so a
  boundary's edge set is a function of its declaration rather than of its
  control flow. [[re-frame.hicasso.impl.runtime/use-subs]]."}
       use-subs impl-runtime/use-subs)

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

     (def ^{:doc "`h/presence` — enter/exit lifecycle for a keyed child list,
  with `::h/mounting` / `::h/unmounting` attribute overrides applied while
  a child is in that phase (HD-025).
  [[re-frame.hicasso.impl.presence-react/presence]]."}
       presence impl-presence-react/presence)

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
