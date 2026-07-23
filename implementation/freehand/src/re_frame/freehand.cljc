(ns re-frame.freehand
  "The Freehand view substrate — the single public door of the re-frame2
  view layer (EP-0036; ruled by D001, conventionally aliased `v`).

  Freehand is one re-frame-native substrate with two execution modes over
  one semantic model: an interpreted paved path, and a compiled hot tier
  selected by `{:compiled true}` on the same declaration. A declared view
  var holds a descriptor value in both modes.

  ## This namespace is a DOOR, not a layer

  Everything published here is either declared here (the declaration and
  callback macros, and the framework's own `route-link` view) or
  re-exported from the namespace that owns it. The descriptor machinery —
  the type, its constructor, the head classifier, the call normalizer,
  the render and structural bodies — lives in the internal
  [[re-frame.freehand.descriptor]], for two reasons that turn out to be
  one:

  1. **The boundary is closed.** `v/defview` is the only way to create an
     internal mounted boundary (D002). A raw constructor on this surface
     would mint a value that passes `view?` and classifies as `:view`
     while carrying no view-id, no source and no lowering — the
     one-declaration rule bypassed by autocomplete. The constructor is
     not published, so the rule is enforced by construction.
  2. **Emitters can require what they need.** An emitter needs the
     classifier and the normalizer; if those lived here, every emitter
     would require this door and this door could never require an
     emitter back. Re-exports like `v/mount` would be unreachable behind
     that cycle.

  What crosses the door: the declaration form, the callback authoring
  forms, the two inspection reads (`view?` / `describe`) tools and
  registries use, the pure event materializer, and `route-link`.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md);
  the published roster is [`spec/API.md`](../../../../spec/API.md)."
  (:require [re-frame.error :as error]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.events :as events]
            ;; The structural node builders a COMPILED declaration's emitted
            ;; body calls. Required here, and not by the consumer, because
            ;; promotion is a one-line change to the declaration: adding
            ;; `{:compiled true}` must not oblige a namespace to acquire a
            ;; require it did not need a moment earlier. `node` sits BELOW this
            ;; namespace and takes nothing back from it.
            [re-frame.freehand.node]
            [re-frame.freehand.presence-runtime :as presence-runtime]
            [re-frame.freehand.route-link-seam :as route-link-seam]
            [re-frame.interop :as interop]
            #?@(:clj  [[re-frame.freehand.compiler :as compiler]
                       [re-frame.source-coords :as source-coords]]
                :cljs [[re-frame.freehand.root :as root]]))
  #?(:cljs (:require-macros [re-frame.freehand :refer [defview event handler render-fn]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The declaration form
;; ---------------------------------------------------------------------------

#?(:clj
   (defn ^:no-doc parse-defview-args
     "Parse `(defview name docstring? opts? [props] body+)` into
     `{:docstring :opts :params :body}`, or nil when the shape is not one
     of the four legal spellings. Plain CLJ so both the macro and its
     tests can reach it."
     [more]
     (let [[a b c] more]
       (cond
         (vector? a)                       {:docstring nil :opts nil :params a :body (next more)}
         (and (map? a) (vector? b))        {:docstring nil :opts a   :params b :body (nnext more)}
         (and (string? a) (vector? b))     {:docstring a   :opts nil :params b :body (nnext more)}
         (and (string? a) (map? b) (vector? c))
         {:docstring a :opts b :params c :body (nthnext more 3)}
         :else nil))))

(def ^:private defview-option-keys
  "The CLOSED roster of `defview` option keys — every option whose
  semantics have LANDED, and nothing else.

  A key outside it is rejected at macro expansion, and that includes a
  RESERVED option a later slice owns: the props-schema options are not
  accepted until the schema surface exists. A reserved option
  accepted-and-ignored is the worst of the outcomes available — the
  declaration reads as one thing and reports itself as another, and a
  one-character typo produces valid code with different semantics. The
  slice that implements an option adds it here, in the same change, as
  `:compiled` did when the compiled tier landed."
  #{:children-policy :compiled})

#?(:clj
   (defn ^:no-doc expand-defview
     "Build the expansion form for a `defview` call. `form-meta` is
     `(meta &form)`, `file` is `*file*`, `ns-sym` is the consuming
     namespace's symbol — all captured at the call site so the emitted
     form holds literals rather than reading `*ns*` / `*file*` at runtime
     (CLJS binds neither).

     The seven-argument arity additionally carries the macro's own `&form`
     and `&env`, which a `{:compiled true}` declaration needs: the
     compiled front end anchors diagnostics at the declaration's source
     coordinates and resolves heads through the consuming compiler's
     environment. An interpreted declaration needs neither, so the short
     arity stays exactly what it was."
     ([form-meta file ns-sym sym more]
      (expand-defview nil nil form-meta file ns-sym sym more))
     ([&form &env form-meta file ns-sym sym more]
     (let [{:keys [docstring opts params body]} (or (parse-defview-args more)
                                                    (error/throw-error!
                                                      :rf.error/defview-bad-args
                                                      'v/defview
                                                      (str "v/defview is spelled (v/defview name docstring? opts? "
                                                           "[props] body …) — a name, an optional docstring, an "
                                                           "optional options map, then exactly one parameter vector "
                                                           "and a body. " sym "'s declaration does not match.")
                                                      {:recovery :fix-the-declaration
                                                       :extra    {:view sym}}))
           policy    (get opts :children-policy :optional)
           compiled? (get opts :compiled false)
           unknown   (vec (sort (remove defview-option-keys (keys opts))))]
       (when (seq unknown)
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str "v/defview options are the closed roster " (pr-str (vec (sort defview-option-keys)))
                "; " sym " declares " (pr-str unknown) ". An option key is never discarded — "
                "a reserved option whose owning slice has not landed (the props-schema "
                "options) is rejected until it does, because an accepted-and-ignored option "
                "is a declaration that quietly means something other than it says.")
           {:recovery :fix-the-declaration
            :extra    {:view sym :unknown-options unknown}}))
       (when-not (contains? #{true false} compiled?)
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str ":compiled selects the compiled tier and is true or false; " sym
                " declares " (pr-str compiled?) ".")
           {:recovery :fix-the-declaration
            :extra    {:view sym :compiled compiled?}}))
       (when-not (= 1 (count params))
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str "A Freehand view takes exactly one argument — its props map. " sym
                " declares " (count params) ". There are no positional view arguments: "
                "destructure the props map instead.")
           {:recovery :fix-the-declaration
            :extra    {:view sym :params (count params)}}))
       (when-not (seq body)
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str "A Freehand view declaration needs a body: " sym " carries a parameter "
                "vector and nothing after it, so it would render nothing at all. Write "
                "the semantic tree the view renders, or an explicit nil body for a view "
                "that deliberately renders nothing.")
           {:recovery :fix-the-declaration
            :extra    {:view sym}}))
       (when-not (contains? descriptor/children-policies policy)
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str ":children-policy is one of " (pr-str (sort descriptor/children-policies)) "; " sym
                " declares " (pr-str policy) ".")
           {:recovery :fix-the-declaration
            :extra    {:view sym :children-policy policy}}))
       ;; The two lowerings differ in ONE entry. Everything a caller can see —
       ;; the descriptor, the view id, the source coordinates, the children
       ;; policy, the props contract, the boundary node a structural render
       ;; produces — is built here, once, from the same declaration. That is
       ;; what makes `{:compiled true}` a one-line change rather than a port:
       ;; there is no compiled call spelling to migrate to, because the compiled
       ;; tier reuses the interpreted tier's whole surface and replaces only the
       ;; body that builds the markup.
       (let [view-id (keyword (str ns-sym) (str sym))
             entry   {:view-id         view-id
                      :source          `(if interop/debug-enabled?
                                          ~(source-coords/coords-form form-meta file ns-sym)
                                          ~(source-coords/prod-coords-form form-meta file ns-sym))
                      :lowering        (if compiled? :compiled :interpreted)
                      :children-policy policy}
             entry   (if compiled?
                       (let [{:keys [body manifest]}
                             (compiler/compile-structural-view
                               {:form            &form
                                :menv            &env
                                :ns-sym          ns-sym
                                :vname           sym
                                :view-id         view-id
                                :params          params
                                :body            body
                                :children-policy policy})]
                         (assoc entry :structural body :manifest manifest))
                       (assoc entry :render
                              `(fn ~(symbol (str sym "-render")) ~params ~@body)))]
         ;; The var metadata is what a COMPILE-TIME head classifier reads: a
         ;; compiled body resolves `[panel {…}]` before any descriptor exists,
         ;; so view-ness, the children policy and the LOWERING have to be
         ;; legible from the Var alone. It is the same declaration answering
         ;; the same questions the runtime descriptor answers — just early
         ;; enough for a build to fail instead of a render. The lowering is
         ;; there so a compiled parent's manifest can mark a child boundary
         ;; that crosses back into the interpreted mode (D010).
         `(def ~(cond-> (vary-meta sym assoc
                                   :re-frame.freehand/view true
                                   :re-frame.freehand/lowering (if compiled? :compiled :interpreted)
                                   :re-frame.freehand/children-policy policy)
                  docstring (vary-meta assoc :doc docstring))
            (descriptor/declare-view ~entry)))))))

#?(:clj
   (defmacro defview
     "Declare a Freehand view — the ONE declaration form, and the only way
     to create an internal mounted boundary.

         (v/defview panel
           \"Optional docstring.\"
           {:children-policy :optional}       ; optional options map
           [{:keys [title children]}]         ; exactly one param — the props map
           [:section.panel [:h2 title] children])

     The var holds a small **descriptor value**, not an ordinary
     function: `panel` is mounted as `[panel {…}]` and is never invoked.
     `(panel {})` raises `:rf.error/view-called-directly` naming the three
     legal recoveries — mount it, inline it as a plain `defn`, or extract
     a shared `defn` helper — so the mistake can neither quietly return
     `nil` the way a map-shaped descriptor would, nor answer with a raw
     host cast failure. The descriptor implements the host call protocol
     for exactly that reason, which is why `(ifn? panel)` is **true**;
     ask [[view?]] when the question is whether a value is a view.

     A plain `defn` is the other half of the convention: helpers are
     direct-called with parentheses and run inside the boundary that
     called them, owning no subscriptions, no occurrence, no memoization
     and no error containment of their own. Changing brackets to
     parentheses changes runtime ownership, not spelling.

     Options — the roster is CLOSED, and every key is optional:

       `:children-policy`  `:optional` (default), `:none`, or `:required`
       `:compiled`         `false` (default), or `true` to select the
                           compiled tier's finite grammar

     ## `{:compiled true}` — the one-line promotion

         (v/defview todo-row
           {:compiled true}                   ; the whole change
           [{:keys [text done?]}]
           [:li.row {:class {:done done?}} text])

     The marker selects the versioned grammar `:re-frame.freehand/v1`
     ([Spec 004D](../../../../spec/004D-Freehand-Compiled-Grammar.md)) for
     THIS declaration and nothing else. Callers do not change, structural
     output does not change, and the view's own tests do not change —
     mounting is `[todo-row {…}]` either way, because the descriptor, the
     props contract and the boundary node are the interpreted tier's and
     the compiled tier reuses them.

     What does change is that the body must be inside a finite language.
     Compilation is EXPLICIT: a form the grammar does not admit is a
     build failure naming a recovery — never a silent demotion, and never
     an interpreted walk hidden inside compiled markup, which would make
     the analysis a compiled view's manifests and diagnostics rest on
     untrue. Promotion is per-declaration and not transitive: a compiled
     view may mount interpreted children, and the honest recovery for a
     body that will not compile is to extract the awkward part into its
     own declared child — or to drop the marker again, which is the same
     one-line change in reverse.

     ## What the declaration refuses

     The option roster above is CLOSED. An unknown key raises
     `:rf.error/defview-bad-args` at macro expansion, NAMING it — an
     option is never discarded, because a one-character typo would
     otherwise produce valid code with different semantics. That holds
     for a RESERVED option whose owning slice has not landed: the
     props-schema options are refused until the schema surface exists,
     the way `:compiled` was refused until the compiled tier landed.

     A declaration also needs a BODY. A parameter vector with nothing
     after it would expand into a view that renders nothing and says
     nothing; a view that deliberately renders nothing writes an
     explicit `nil` body.

     Per [Spec 004 §The descriptor and `v/defview`](../../../../spec/004-Views.md)."
     [sym & more]
     (expand-defview &form &env (meta &form) *file* (ns-name *ns*) sym more)))

;; ---------------------------------------------------------------------------
;; Descriptor inspection — the public reads
;; ---------------------------------------------------------------------------
;;
;; A declared view is a value, so a tool needs to answer about one: is this
;; a view, what does it say about itself, and — for a compiled one — what
;; does its analysis make statically knowable. Those are the whole
;; supported inspection surface. The descriptor TYPE, its constructor, the
;; head classifier, the call normalizer and the two private bodies stay in
;; `re-frame.freehand.descriptor` — an emitter's vocabulary, not an
;; application's.

(def ^{:doc "True when `x` is a view declared with [[defview]] — the ONE
  value a Freehand runtime classifies as an internal view boundary.

  Total and host-neutral: the same value answers the same way on the JVM
  and in ClojureScript, in both execution modes. This is the predicate
  head classification and tooling ask; `ifn?` is not a proxy for it (a
  declared view IS `IFn`, purely so a direct call can explain itself —
  see [[defview]]).

  Per [Spec 004 §A declared view cannot be called](../../../../spec/004-Views.md)."
       :arglists '([x])}
  view? descriptor/view?)

(def ^{:doc "The descriptor's public **inspection / registry
  projection** — a plain map, distinct from the runtime value:

      {:re-frame.freehand/view true
       :view-id                :app.todo/todo-row
       :source                 {:ns … :file … :line …}
       :lowering               :interpreted   ; or :compiled
       :children-policy        :optional      ; or :none, :required
       :props-schema           <schema>}      ; absent when none declared

  `:props-schema` is **absent** when no schema was declared — absence is
  reported as absence, never as `:any`. The descriptor's render body, its
  compiled structural body, and its host `mount` and structural `tree`
  entries are private and are deliberately NOT projected: an emitter
  resolves through them, and none of their shapes is a contract an
  application may depend on.

  This is what tools, registries and catalogues read. It is inspection
  data, never a dispatch surface — every view is mounted the same way.

  Per [Spec 004 §The inspection projection](../../../../spec/004-Views.md)."
       :arglists '([view])}
  describe descriptor/describe)

(def ^{:doc "A COMPILED declaration's **manifest** — what its analysis
  makes statically knowable about it, as plain data — or `nil` for an
  interpreted declaration, which has no analysis to report.

      (v/manifest people-list)
      ;; => {:view-id       :app.people/people-list
      ;;     :grammar       :re-frame.freehand/v1
      ;;     :subscriptions [{:sid … :query [:person 7]
      ;;                      :source-coord {:file … :line 42 :column 12}
      ;;                      :path [0]}]
      ;;     :events [] :slots [] :html-sites [] :frame-ops []
      ;;     :capabilities  #{:sub}
      ;;     :reactive?     true
      ;;     :view-cell     :present
      ;;     :crossings     [{:view-id :re-frame.freehand/markup
      ;;                      :lowering :interpreted
      ;;                      :source-coord {:file … :line 44 :column 3}
      ;;                      :path [1]}]}

  The `:subscriptions` / `:events` / `:slots` / `:html-sites` /
  `:frame-ops` rosters are the view's finite lexical sites and
  `:capabilities` their union with the structural capability bits. EVERY
  roster entry carries a `:source-coord` — the coordinates of the form
  that produced it, or of the enclosing declaration where the reader
  anchored no narrower position — so a manifest fact is traceable to the
  source that made it, and never a position the source would not agree
  with.
  `:reactive?` / `:view-cell` carry the **capability-elision** verdict: a
  view with no reactive site (no `sub`, no committed handler, no
  `dispatch-fn`, no `(frame)`) omits the reactive ViewCell shell, which is
  what compilation buys — and the omitted-cell count over a fixture is an
  assertion on an exact integer, never a threshold. `:crossings` is the
  roster of internal-view boundaries the body mounts, one entry per lexical
  site, each MARKED with the mode it crosses into — a compiled view that
  mounts an interpreted child is the ordinary case, so the manifest says
  where the compiled tier stops rather than leaving a reader to assume it
  does not.

  Nil for an interpreted declaration is the honest answer, not an
  omission: the interpreted mode has no finite grammar and no analysis
  step, so there is nothing it could claim.

  Per [Spec 004D §Static manifests and capability elision](../../../../spec/004D-Freehand-Compiled-Grammar.md)."
       :arglists '([view])}
  manifest descriptor/manifest)

;; ---------------------------------------------------------------------------
;; The subscription law — the render-only reactive read
;; ---------------------------------------------------------------------------
;;
;; Spec 006 §The subscription law (D005). The read primitive of the paved
;; path is one function over the atomic shell's `observe!`: a render-time
;; resolve-and-probe that records the read on the render's own candidate,
;; ownership-free, so an abandoned render publishes nothing and the
;; SELECTED commit is what turns the record into an owned dependency. The
;; capture, the same-thread rule, the outside-render diagnostic and the
;; `rf=` stabilization all live in `re-frame.freehand.cell`; this door
;; only names the authoring verb over them.

(def ^{:doc "Read a subscription's current VALUE during render — the paved
  path's reactive read.

      (v/defview basket-total [_]
        [:output (v/sub [:basket/total])])

  `v/sub` resolves `query` against the view's frame, returns the current
  value, and RECORDS the read on this render's candidate. The read owns
  nothing on its own — no ref-count, no watch, no cache node — so a render
  the host abandons leaks nothing; the SELECTED commit is what turns the
  record into an owned dependency published with the rest of the boundary's
  bundle. A later change to the query's value then invalidates exactly this
  occurrence and recomputes it, and the new dependencies, event targets and
  evidence republish atomically.

  It is legal ONLY during an active declared render, and the capture is
  SAME-THREAD — including through an ordinary `defn` helper called from the
  body, because the render owns the read wherever the call lexically sits. A
  `v/sub` with no active render — a REPL, a timer, a `v/event` or `v/handler`
  callback, a foreign listener — fails loud with
  `:rf.error/view-read-outside-render` rather than probing a value nobody
  owns; a read conveyed to a child thread fails with
  `:rf.error/view-forked-capture`. Non-reactive callers use the
  frame-explicit one-shot [`rf/subscribe-once`](../../../../spec/006-ReactiveSubstrate.md#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value),
  which resolves, probes, returns and releases without installing a
  dependency — deliberately a `re-frame.core` verb, not a Freehand one.

  The value is STABILIZED: an `rf=`-equal recompute returns the exact prior
  value object, so an equal value is not movement. The rule is one sentence
  and holds in both execution modes — the compiled tier proves a finite set
  of read sites, the interpreted tier records the reads a committed render
  actually made.

  Per [Spec 006 §The subscription law](../../../../spec/006-ReactiveSubstrate.md#the-subscription-law)."
       :arglists '([query])}
  sub cell/observe!)

;; ---------------------------------------------------------------------------
;; Roots and mounting — `v/mount`
;; ---------------------------------------------------------------------------
;;
;; Spec 004C §The mount grammar. Mounting is the browser half of getting a
;; Freehand tree onto a page; the JVM renders the SAME root form
;; structurally through the tree emitter, so the minimal one-root spelling
;; is one spelling on both hosts. `mount` re-exports the client surface in
;; `re-frame.freehand.root` — a re-export the descriptor split (this door
;; requires its emitters, never the reverse) is what makes reachable.
;; Browser-only, because a DOM node is: there is no JVM mount, only the JVM
;; structural render the door does not need to publish.

#?(:cljs
   (def ^{:doc "Mount the declared view at `root-form`'s head into
  `dom-node`, and return the live root handle.

      (v/mount [app {}] (js/document.getElementById \"app\"))

  The minimal one-root spelling: a declared view at the head, whose
  registered id becomes the root's derived identity (its `:root-id`, a
  qualified keyword). The SAME `[app {}]` form renders structurally on the
  JVM — mounting and structural rendering are one spelling.

  IDEMPOTENT PER ROOT (Spec 004C §3): re-mounting the same root-id into the
  same container RE-RENDERS the existing host root rather than allocating a
  second one. That is the hot-reload path — a reload mints a fresh
  descriptor object for the redefined view, but the qualified id it keys on
  does not move, so the reload finds the root live and re-renders the new
  body without reseeding the host root. Body/generation churn is an
  internal fact of the descriptor, never part of the identity.

  Frame preflight, authored identity, multi-root duplicate detection,
  failed-root isolation, total teardown and hydration are later slices; a
  bare declared view at the head is the whole grammar here, and `opts` is
  accepted and ignored.

  Per [Spec 004C §The mount grammar](../../../../spec/004C-Roots-and-Mount.md)."
          :arglists '([root-form dom-node] [root-form dom-node opts])}
     mount root/mount))

;; ---------------------------------------------------------------------------
;; Event intent and the callback roster
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Event intent and the payload materializer, §Callback roles
;; and identity (D006, D008). The mechanics — the site table, the
;; committed proxy, the host payload seams — are
;; `re-frame.freehand.events`, an INTERNAL namespace. What crosses to the
;; public door is the authoring surface plus the one pure materializer
;; every mode, host and test path shares.

(def ^{:doc "The CLOSED scalar projection roster — `::v/value`,
  `::v/checked` and `::v/key`.

  These are the only reserved markers a declarative event vector may
  carry, and the exact keys of the payload map a firing site supplies.
  A marker in a top-level argument position is replaced at firing time
  from the live callback payload; a marker nested inside another value
  is ordinary application data. Adding a fourth projection is a grammar
  decision — anything richer than a shallow scalar read is [[event]]'s
  job.

  Per [Spec 004 §Event intent and the payload materializer](../../../../spec/004-Views.md)."}
  projections events/projections)

(def ^{:doc "The ONE pure event materializer: replace the reserved
  projection markers in an event vector with the live scalars in a
  payload map, and return a plain vector ready for ordinary re-frame
  dispatch.

      (v/materialize-event [:account/email-edited ::v/value]
                           {::v/value \"mike@example.com\"})
      ;; => [:account/email-edited \"mike@example.com\"]

  Every path runs through exactly this function — a literal vector, a
  forwarded `(conj on-change ::v/value)`, an options map's `:event`, an
  [[event]] body's result, interpreted, compiled, production and test.
  That is why general `rf/dispatch` needs no payload arity: projection
  is a Freehand event-site concern, and a projection keyword inside an
  ordinary domain event is never secretly interpreted.

  It is exposed so a structural test can supply a literal payload and
  assert the exact dispatched vector without a browser, running the
  production semantics rather than a test-only convention.

  Per [Spec 004 §Event intent and the payload materializer](../../../../spec/004-Views.md)."
       :arglists '([event payload])}
  materialize-event events/materialize-event)

(def ^{:doc "The expert callback seam: hand a foreign API a function with
  EXACTLY its supplied identity, for the case where that identity is
  itself protocol data — a listener the library removes by identity, a
  memo key it compares.

  Every other roster form gets a site-owned stable proxy; this one
  deliberately does not, so re-render churn is the author's to manage.
  Reach for it only when a wrapper or [[event]] genuinely cannot express
  the protocol.

  Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
       :arglists '([f])}
  raw-fn events/raw-fn)

#?(:clj
   (defmacro event
     "Declare a callback that converts an invoker's arguments into ONE
     event vector or `nil` — the explicit conversion seam at a foreign
     boundary.

         [date-picker {:on-change (v/event [date]
                                    [:booking/departure-changed (iso-date date)])}]

     The body runs synchronously with the live callback arguments and
     NAMES its outcome: an event vector dispatches (through the same
     materializer every other event form uses, so `::v/value` and
     friends still fill), `nil` dispatches nothing, and anything else is
     a loud diagnostic. It may not `v/sub`, use hooks, refs or effects —
     decisions that depend on changing application state belong in the
     receiving re-frame event handler, which sees the committed frame.

     Its identity is stable per site: an unchanged site keeps the exact
     callback across re-renders while body changes publish atomically at
     commit, so a foreign consumer sees no churn and never a stale body.

     Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
     [params & body]
     (events/expand-callback :event 'v/event params body)))

#?(:clj
   (defmacro handler
     "Declare an explicit IMPERATIVE foreign callback — work that is not
     application intent and produces no event.

         [canvas-host {:measure (v/handler [node] (measure! node))}]

     The body's return is ignored; it is neither a render nor a place to
     store state. Like [[event]] it is stable per site and reads the
     exact committed body when invoked, and it is retired with its site,
     so a foreign listener that outlives its view is inert rather than
     firing into a successor.

     Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
     [params & body]
     (events/expand-callback :handler 'v/handler params body)))

#?(:clj
   (defmacro render-fn
     "Declare a PURE callback a foreign owner invokes during ITS render.

         [virtual-list {:render-item (v/render-fn [{:keys [id]}]
                                       [result-row {:id id}])}]

     It may return Freehand content, and it may NOT `v/sub`, dispatch,
     use hooks or touch refs — it can run during an uncommitted
     candidate render, which is exactly why it is excluded from the
     committed-proxy scheme. Freehand makes no identity promise here: a
     lowering may reuse it when the descriptor and its captures compare
     equal, and an API that treats callback identity as protocol data
     uses [[raw-fn]] or a wrapper instead.

     Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
     [params & body]
     (events/expand-callback :render-fn 'v/render-fn params body)))

;; ---------------------------------------------------------------------------
;; Presence — keyed enter/exit retention
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Presence. ONE keyed retention contract, honoured identically by
;; both execution modes. In COMPILED markup `(v/presence …)` is a seq form the
;; analyzer recognises and lowers; in INTERPRETED markup it is this ordinary
;; function call, which returns a reserved-head hiccup vector the interpreted
;; walks intercept and lower to the SAME retention runtime. The runtime itself
;; ([[re-frame.freehand.presence-runtime]]) is shared, so retention, the
;; terminal timeout bound, re-entry and the phase read are the same behaviour
;; whichever mode wrote the boundary.

(defn presence
  "(v/presence {:timeout-ms n} keyed-children) — declarative enter/exit
  retention, deliberately bounded (NOT an animation system). Keyed children
  pass :mounting → :present → :unmounting; an exiting child is RETAINED for
  exactly the MANDATORY :timeout-ms — the exit retention duration AND terminal
  bound — then removal is terminal and exactly-once (all ownership released).
  Removal-then-reinsertion of a key interrupts the exit and re-enters at
  :present. Children hold first-appearance order; an incoming reorder is
  ignored (presence is enter/exit, not reordering).

  DOM-agnostic: the boundary inserts no wrapper node, stamps no attributes, and
  observes no DOM events. A presence-aware child owns its own exit styling and
  accessibility — stamp `inert` / `aria-hidden` and the exit class against
  (v/presence-phase) = :unmounting; the child's stylesheet owns
  prefers-reduced-motion.

  Called directly in an INTERPRETED body and recognised as a seq form by the
  COMPILED analyzer — one spelling, one contract, both modes.

  Per [Spec 004 §Presence](../../../../spec/004-Views.md#presence)."
  [opts & children]
  (when-not (map? opts)
    (error/throw-error!
      :rf.error/ui-tree-malformed 'v/presence
      (str "(v/presence {:timeout-ms n} children) needs a literal options map with a "
           "mandatory :timeout-ms (the terminal safety bound); the options map is missing.")
      {:recovery :no-recovery :extra {:opts (error/diag-value-summary opts)}}))
  (let [t     (:timeout-ms opts)
        extra (seq (disj (set (keys opts)) :timeout-ms))]
    (when-not (and (number? t) (pos? t))
      (error/throw-error!
        :rf.error/ui-tree-malformed 'v/presence
        (str ":timeout-ms is MANDATORY on (v/presence …) and is a positive number of "
             "milliseconds — the terminal safety bound AND the exit retention duration; "
             "got " (pr-str t) ".")
        {:recovery :no-recovery :extra {:timeout-ms (error/diag-value-summary t)}}))
    (when extra
      (error/throw-error!
        :rf.error/ui-tree-malformed 'v/presence
        (str "the only (v/presence …) option is :timeout-ms; got also "
             (pr-str (vec extra)) ".")
        {:recovery :no-recovery :extra {:unknown-options (vec extra)}})))
  (when (empty? children)
    (error/throw-error!
      :rf.error/ui-tree-malformed 'v/presence
      "(v/presence {:timeout-ms n} children) needs at least one keyed child."
      {:recovery :no-recovery :extra {}}))
  (into [descriptor/presence-tag opts] children))

(def ^{:doc "(v/presence-phase) — the single presence-phase read: :mounting /
  :present / :unmounting inside a (v/presence …) boundary, :present outside one
  (so presence-aware children stay reusable anywhere). A render-time read (a
  React context read on ClojureScript); the JVM structural render always yields
  :present.

  Per [Spec 004 §Presence](../../../../spec/004-Views.md#presence)."
       :arglists '([])}
  presence-phase presence-runtime/presence-phase)

;; ---------------------------------------------------------------------------
;; `route-link` — a framework view, declared the ordinary way
;; ---------------------------------------------------------------------------
;;
;; A framework-supplied view is not a privileged one. `route-link` is
;; declared with the same `defview` an application uses, holds the same
;; descriptor, takes the same one props map, and will be lowered
;; by the same emitters — there is no route-link intrinsic to teach, to
;; special-case in the analyzer, or to keep in step with the paved path.
;;
;; The body is one call into `route-link-seam`, which owns the anchor and
;; consumes the two late-bound `:routing/*` hooks. Spec 012 keeps href and
;; click semantics; Freehand contributes the descriptor and nothing else.

(defview route-link
  "(v/route-link {:to :route-id …html-attrs} & children) — a navigation
  anchor.

  Renders a REAL `<a href=…>` carrying the route's strategy-encoded href,
  so copy-link, open-in-new-tab, keyboard activation and no-JavaScript
  navigation all work; on a plain in-app left click it dispatches the
  routing cascade to the frame that rendered it instead of letting the
  browser reload the page.

      [v/route-link {:to :article :params {:slug slug} :class \"title\"}
       title]

  The control keys are `:to` / `:params` / `:query` / `:fragment` and
  `:on-click`. `:to` is required (a registered route id); `:params` /
  `:query` / `:fragment` feed both the href and the dispatch payload.
  Every OTHER key — `:class`, `:title`, `:id`, `:aria-label`, `:target`,
  `:download`, and any further HTML attribute — passes through to the
  `<a>` untouched.

  `:on-click` is the imperative PRE-NAVIGATION seam, not a second intent
  site: it takes a plain function or a [[handler]], runs FIRST, exactly
  once, and may veto. If it prevents the default — or the anchor is
  native (`:target` other than `_self`, or `:download`), or the click is a
  modifier / middle / auxiliary-button click — the framework does NOT
  intercept and the browser's own `href` behaviour stands. That deferral
  is the whole reason to use this view rather than hand-rolling one.

  An event vector, an event options map or a [[event]] at `:on-click` is
  REJECTED at render, naming the recovery. The click already produces the
  one routing intent, so an application reaction belongs behind that
  routing event or its transition; `.preventDefault`, a confirm dialog or
  an analytics ping is what [[handler]] is for.

  Rendering it without `day8/re-frame2-routing` on the classpath fails
  loud with `:rf.error/routing-artefact-missing` rather than emitting a
  dead link; a plain `[:a]` stays available for intentional
  browser-native navigation. JVM and SSR render the handler-free
  path-form shell, and the hydrated client re-encodes through the frame's
  URL strategy.

  The behavioural contract and its conformance rows live in
  [Spec 012 §The Freehand route-link descriptor](../../../../spec/012-Routing.md)
  — routing owns the law, this view supplies the descriptor."
  [props]
  (route-link-seam/anchor props))

;; ---------------------------------------------------------------------------
;; `markup` — the declared boundary a markup VALUE crosses at
;; ---------------------------------------------------------------------------
;;
;; D010's standard recovery, and — this is the whole design — it is not a
;; mechanism. It is an ordinary interpreted `defview`, declared with the same
;; macro an application uses, mounted with the same call spelling, producing
;; the same boundary node. Nothing in the compiled tier knows its name.
;;
;; That is what keeps `:re-frame.freehand/v1` closed. A grammar valve
;; (`v/interp`, or a fallback arm that walked an unrecognised child value)
;; would put an interpreter INSIDE compiled markup, and every claim a compiled
;; manifest makes would become conditional on values the analyzer never saw.
;; A declared child puts the interpreter on the other side of a boundary that
;; is visible in the source, counted in the manifest, and addressable in the
;; tree.

(defview markup
  "(v/markup {:value hiccup}) — mount markup you are holding as a VALUE.

  Interpreted Clojure treats markup as data: a helper returns Hiccup, a
  prop carries it, a late transform rewrites it. The compiled tier cannot
  lower that — a runtime value is not a template — so a compiled body that
  hands one to a child position is refused, naming this recovery
  ([Spec 004D](../../../../spec/004D-Freehand-Compiled-Grammar.md)):

      (v/defview editor
        {:compiled true}
        [{:keys [error hint]}]
        [:section
         [v/markup {:value (field-help error hint)}]])

  There is no `v/interp` and no automatic dynamic-markup walk. This is an
  ordinary declared interpreted child, and the difference matters: the
  compiled parent sees ONE statically named descriptor boundary, the child
  owns the walk and its own occurrence, and the parent's manifest marks the
  crossing `:interpreted` instead of quietly claiming the subtree. The cost
  of the escape is visible in the source and countable in the evidence,
  which is the only version of the escape worth having.

  `:value` is anything a view body may return — a Hiccup vector, a seq of
  them, text, a number, or nothing. It accepts no children: the value IS
  the content, and a silently dropped child would be worse than the loud
  `:rf.error/view-children-policy` this raises instead.

  In an interpreted parent it is legal but pointless — write the markup
  where it goes. Reach for it when a compiled parent needs a value it
  cannot see through.

  Per [Spec 004 §Cross-mode children](../../../../spec/004-Views.md)."
  {:children-policy :none}
  [{:keys [value]}]
  value)
