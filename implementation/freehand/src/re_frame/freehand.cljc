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
  ;; `cljs.core/spread` exists (an `apply` internal; its Clojure twin is
  ;; private, so only ClojureScript sees the collision). Excluded rather than
  ;; renamed: `v/spread` is the name the grammar has, on the door and in the
  ;; compiled analyzer's resolution set, and a door that shadowed a core
  ;; internal silently would be worse than one that says so here.
  (:refer-clojure :exclude [spread])
  (:require [re-frame.error :as error]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as errors]
            [re-frame.freehand.events :as events]
            ;; The structural node builders a COMPILED declaration's emitted
            ;; body calls. Required here, and not by the consumer, because
            ;; promotion is a one-line change to the declaration: adding
            ;; `{:compiled true}` must not oblige a namespace to acquire a
            ;; require it did not need a moment earlier. `node` sits BELOW this
            ;; namespace and takes nothing back from it. Aliased because the
            ;; door reaches it DIRECTLY too: `v/spread` / `v/spread-safe` are
            ;; the interpreted front end of a fold the canonicaliser owns.
            [re-frame.freehand.node :as node]
            [re-frame.freehand.presence-runtime :as presence-runtime]
            ;; The whole meaning of a `:props` schema, in one host-neutral
            ;; namespace both execution modes already consult. The declaration
            ;; consults it too, so what a schema may SAY and what it then
            ;; DECIDES are one statement rather than two that could disagree.
            [re-frame.freehand.props-schema :as props-schema]
            ;; The compiled tier's render-time reactive runtime — what an
            ;; authored `(v/sub …)` inside a `{:compiled true}` body lowers to.
            ;; Required here for the SAME reason `node` is: promotion is a
            ;; one-line change to the declaration, so adding `{:compiled true}`
            ;; must not oblige a namespace to acquire a require it did not need
            ;; a moment earlier. Without it a compiled declaration carrying a
            ;; subscription cannot even LOAD — the emitted lowering names a
            ;; namespace nothing on the consumer's path has loaded. `reactive`
            ;; sits BELOW this namespace (it reaches only the shell) and takes
            ;; nothing back from it.
            [re-frame.freehand.reactive]
            [re-frame.freehand.route-link-seam :as route-link-seam]
            [re-frame.interop :as interop]
            #?@(:clj  [[re-frame.freehand.compiler :as compiler]
                       [re-frame.freehand.compiler.root :as compiler-root]
                       ;; The JVM tree -> HTML seam `v/render-static` renders
                       ;; through. Loaded on the JVM (not by the consumer) so a
                       ;; server namespace that requires only `re-frame.freehand`
                       ;; can render-static: the emitted call names
                       ;; `re-frame.freehand.tree/emit-static-html`, which
                       ;; late-resolves `re-frame.ssr` at render time — the door
                       ;; still takes NO static require on `re-frame.ssr`
                       ;; (Spec 011 §wall). `tree` sits BELOW this door and takes
                       ;; nothing back from it.
                       [re-frame.freehand.tree]
                       [re-frame.source-coords :as source-coords]]
                ;; `compiled-react` is required here for the SAME reason `node`
                ;; and `reactive` are: it is what a COMPILED declaration's
                ;; emitted browser body calls, and promotion is a one-line
                ;; change to the declaration — adding `{:compiled true}` must
                ;; not oblige a namespace to acquire a require it did not need
                ;; a moment earlier. It sits BELOW this namespace and takes
                ;; nothing back from it.
                :cljs [[re-frame.freehand.compiled-react]
                       [re-frame.freehand.root :as root]]))
  #?(:cljs (:require-macros [re-frame.freehand
                             :refer [defbehavior defview event handler render-fn
                                     render-static]])))

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
  RESERVED option a later slice owns. A reserved option
  accepted-and-ignored is the worst of the outcomes available — the
  declaration reads as one thing and reports itself as another, and a
  one-character typo produces valid code with different semantics. The
  slice that implements an option adds it here, in the same change, as
  `:compiled` did when the compiled tier landed and `:props` did when the
  schema surface did."
  #{:children-policy :compiled :props})

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
                "a reserved option whose owning slice has not landed is rejected until it "
                "does, because an accepted-and-ignored option is a declaration that quietly "
                "means something other than it says.")
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
       ;; A schema may not govern a RESERVED slot. `:key` is stripped by the
       ;; call ABI before props are delivered and `:children` arrives as
       ;; trailing forms under `:children-policy`, so a schema naming either
       ;; would be a second contract for something that already has one — and
       ;; an impossible one, since no call can deliver the prop it advertises
       ;; to a catalogue. It is refused at the declaration, where the breach
       ;; is, rather than at a call site that could not repair it.
       ;;
       ;; Only a LITERAL `[:map …]` is read: an opaque schema's entries are
       ;; nobody's to see, and inventing a rejection from a guess would be the
       ;; same error as inventing a closing roster from one.
       (let [reserved (props-schema/reserved-declared (get opts :props))]
         (when (seq reserved)
           (error/throw-error!
             :rf.error/defview-bad-args
             'v/defview
             (props-schema/reserved-declaration-message sym reserved)
             {:recovery :fix-the-declaration
              :extra    {:view sym :reserved reserved}})))
       ;; The two lowerings differ in ONE entry. Everything a caller can see —
       ;; the descriptor, the view id, the source coordinates, the children
       ;; policy, the props contract, the boundary node a structural render
       ;; produces — is built here, once, from the same declaration. That is
       ;; what makes `{:compiled true}` a one-line change rather than a port:
       ;; there is no compiled call spelling to migrate to, because the compiled
       ;; tier reuses the interpreted tier's whole surface and replaces only the
       ;; body that builds the markup.
       (let [view-id (keyword (str ns-sym) (str sym))
             ;; The schema is held as INERT DATA on the entry, present iff the
             ;; declaration named it. Absence stays absence all the way to
             ;; `describe`, so a tool can tell an undeclared contract from a
             ;; deliberately permissive one — a distinction an `:any` default
             ;; would erase.
             ;;
             ;; Inert means the AUTHORED schema reaches the entry and the Var,
             ;; not the value an expression would evaluate to. The compiled
             ;; front end below can only ever see the authored form, so an
             ;; opaque `:props` leaves the map open there; had the same
             ;; declaration evaluated its way to a literal `[:map …]` at the
             ;; runtime descriptor, the boundary would close the map the
             ;; compiler had just left open, and the schema would mean two
             ;; different things in one declaration.
             schema?     (contains? opts :props)
             schema      (get opts :props)
             schema-form (props-schema/inert-form schema)
             entry   (cond-> {:view-id         view-id
                              :source          `(if interop/debug-enabled?
                                                  ~(source-coords/coords-form form-meta file ns-sym)
                                                  ~(source-coords/prod-coords-form form-meta file ns-sym))
                              :lowering        (if compiled? :compiled :interpreted)
                              :children-policy policy}
                       schema? (assoc :props-schema schema-form))
             entry   (if compiled?
                       (let [{:keys [body react manifest]}
                             (compiler/compile-structural-view
                               {:form            &form
                                :menv            &env
                                :ns-sym          ns-sym
                                :vname           sym
                                :view-id         view-id
                                :params          params
                                :body            body
                                :props-schema    schema
                                :children-policy policy})]
                         ;; The manifest is INERT DATA about the declaration,
                         ;; and it is QUOTED because it contains authored FORMS:
                         ;; a subscription site records the query the author
                         ;; wrote, and a query that reads a prop —
                         ;; `(v/sub [:person/by-id id])` — carries the body's
                         ;; own local symbol. Spliced unquoted into the entry
                         ;; map, that symbol would be EVALUATED where no such
                         ;; local exists and the declaration would not compile.
                         ;; The structural body beside it is the opposite kind
                         ;; of value — a form to evaluate — so only the manifest
                         ;; is quoted. (The JVM emitter's own `register-view!`
                         ;; call has always quoted it, for exactly this reason.)
                         ;;
                         ;; `:react` is the BROWSER realisation of the same
                         ;; analysis, present only on a ClojureScript expansion.
                         ;; One declaration, one analysis, two emitted bodies —
                         ;; each host runs the one it can, and neither is derived
                         ;; from the other.
                         (cond-> (assoc entry
                                        :structural body
                                        :manifest (list 'quote manifest))
                           react (assoc :react react)))
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
                  ;; The SCHEMA, not a precomputed key roster: a compiled
                  ;; parent resolves this var at build time and derives the
                  ;; closing keys through the same function the boundary
                  ;; uses, so the two modes cannot drift on what a schema
                  ;; admits. It is the same inert form the entry carries —
                  ;; one representation, published once, read by both.
                  schema?   (vary-meta assoc :re-frame.freehand/props-schema schema-form)
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
       `:props`            a props schema — Malli vector data, held inert

     ## `:props` — the optional contract

         (v/defview todo-row
           {:compiled true
            :props    [:map [:id :int] [:text :string]]}
           [{:keys [id text]}]
           [:li.row {:data-id id} text])

     OPTIONAL, in both modes. The compiler rejects what it cannot lower,
     never what lacks documentation, so an ordinary application view
     declares no schema and pays no ceremony for it. Where a schema is
     MANDATORY is build and catalogue policy over published, reusable
     views and views claiming generated parity — not the grammar.

     A declared schema decides one thing, and the same thing in both
     modes: it CLOSES the props map to the keys it names. A view that
     really does forward arbitrary props says so in the schema itself,
     `[:map {:closed false} …]`, rather than by silent tolerance. `:key`
     and `:children` are reserved slots and never schema entries, and a
     literal schema naming either is REFUSED here, at the declaration —
     it would advertise a prop the call ABI can never deliver.

     The modes differ only in WHEN a breach is reported: a compiled call
     site's keys are literal, so the analyzer names them at build time; a
     delivered props map is knowable at render, so the boundary names it
     then. Promotion therefore changes the moment a mistake surfaces, not
     which props are legal. The runtime check is development-only — a
     schema is a compile-time and tooling fact, and production renders
     the same tree either way.

     A schema behind a reference or an expression is OPAQUE, and an
     opaque schema closes nothing — at every surface. The declaration
     publishes it as AUTHORED, inert and unevaluated, so the compiled
     analyzer and the boundary read one schema rather than an expression
     and the literal it happened to evaluate to.

     A declaration WITHOUT `:props` reports its schema as absent from
     [[describe]], never as `:any`: an undeclared contract and a
     deliberately permissive one are different facts.

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
     for a RESERVED option whose owning slice has not landed, the way
     `:compiled` was refused until the compiled tier landed and `:props`
     until the schema surface did.

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
  reported as absence, never as `:any` — and a declared one is projected
  as AUTHORED, inert and unevaluated, so a schema behind a reference or
  an expression reaches a catalogue as that reference or expression and
  closes nothing anywhere. The descriptor's render body, its
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

  `opts` is a CLOSED map. Identity: `:root-id` (authored, verbatim) or
  `:disambiguator` (a scalar appended to the derived id, so ONE view can
  mount twice on one page with neither site authoring an id), plus
  `:identifier-prefix`. Preflight: `:frame` — a frame-id keyword SCOPES a
  frame something else owns, a `make-frame` opts map carrying `:id`
  ENSUREs one the root owns for its lifetime, and either way the frame is
  live before React sees anything. Host: React's `:on-uncaught-error` /
  `:on-caught-error` / `:on-recoverable-error`.

  A root claims its id, its container and its `identifierPrefix` in the
  per-document registry BEFORE it renders, so a collision on any of them
  fails loud with the existing roots untouched.

  Per [Spec 004C §The mount grammar](../../../../spec/004C-Roots-and-Mount.md)."
          :arglists '([root-form dom-node] [root-form dom-node opts])}
     mount root/mount))

#?(:cljs
   (def ^{:doc "Adopt the server-rendered markup already in `dom-node` for
  the declared view at `root-form`'s head, and return the live root handle.

      (v/hydrate-root (js/document.getElementById \"app\") [app {}])

  Hydration ADOPTS the server's DOM rather than replacing it, so the page
  the user is already looking at becomes the live page. Verification is
  React's own adoption: a divergence React recovers from — a text
  mismatch, a missing, extra or wrong-type element — is reported as
  `:rf.ssr/hydration-mismatch` and React replaces the offending DOM. An
  ATTRIBUTE-only divergence is outside that signal by React's own
  contract, which makes no guarantee to patch attribute mismatches.

  Identity comes from the server — READ, not derived. The server emits a
  **Root Manifest** as the container's immediately following element
  sibling, and the hydrating root takes its `:root-id` and its
  `identifierPrefix` from that manifest's content. So identity opts
  (`:root-id`, `:disambiguator`, `:identifier-prefix`) are REFUSED here
  (`:rf.error/root-manifest-invalid`) — a client that renders under its
  own prefix breaks `use-id` hydration. `:frame` and the host error
  callbacks are accepted, exactly as at [[mount]].

  A container carrying nothing to adopt takes the FALLBACK, and takes it
  BEFORE any manifest is asked for: that is the client-only first load of
  a page whose server never rendered this root, and such a page carries no
  manifest either. A container that DOES carry server markup must carry
  the manifest that says what it was rendered as — nothing adjacent is
  `:rf.error/root-manifest-invalid`, and no SSR artefact on the classpath
  at all is `:rf.error/ssr-artefact-missing` naming `day8/re-frame2-ssr`.

  Per [Spec 011 §Hydration](../../../../spec/011-SSR.md)."
          :arglists '([dom-node root-form] [dom-node root-form opts])}
     hydrate-root root/hydrate-root))

#?(:cljs
   (def ^{:doc "Tear a mounted root down completely, and answer nil.

      (v/unmount! root)

  TOTAL: the registry entry goes — and with it the root-id, container and
  `identifierPrefix` claims — the React root unmounts, every ViewCell
  below it disconnects (releasing every dependency, retiring every
  published callback), and the root's reference to its frame is released.
  A frame the root ENSUREd is DESTROYED once no live root still
  references it; a frame the root merely scoped is left alone.

  GUARDED, and a no-op rather than a throw when the guard fails: a root
  already unmounted, or superseded by a newer root claiming its id, has
  nothing left to release — and tearing down on its behalf would tear
  down the successor.

  Per [Spec 004C §The mount grammar](../../../../spec/004C-Roots-and-Mount.md)."
          :arglists '([root])}
     unmount! root/unmount!))

;; ---------------------------------------------------------------------------
;; Server render — `v/render-static`
;; ---------------------------------------------------------------------------
;;
;; Spec 011 §Freehand server render. The pure `:server`-phase static-HTML
;; render — the JVM/server counterpart of the client mount verbs. Unlike
;; `v/mount` / `v/hydrate-root` (runtime functions re-exported from the browser
;; client), render-static is a MACRO over
;; `re-frame.freehand.compiler.root/render-static-form`: it needs the LITERAL
;; root form at the call site (a runtime-assembled vector is not v1 grammar), it
;; runs the transitive static-capability proof at BUILD time, and it emits into
;; the `re-frame.freehand.tree` seam that folds the versioned JVM tree to an
;; inert HTML string through a LATE-resolved `re-frame.ssr` — so the door takes
;; no static require on the SSR artefact. JVM/server only: a CLJS expansion is
;; the ruled `:rf.ui.compile/ui-render-static-jvm-only` compile error (there are
;; no structural trees in the browser).

#?(:clj
   (defmacro render-static
     "(v/render-static root-form) => an inert HTML string.

         (v/render-static [app {:route route}])

     The pure `:server`-phase static-HTML render — Freehand's counterpart of
     React's `renderToStaticMarkup`. It compiles the LITERAL root form to the
     versioned JVM structural tree and folds it to a static HTML string:
     NON-hydrating, with NO Root Manifest, NO hydration payload, and NO phase
     flip. It is the static-page path, not the SSR-then-hydrate path —
     `v/hydrate-root` + `re-frame.ssr/hydrate!` own that (Spec 011).

     JVM/SERVER ONLY. Author it in a `.clj` (or JVM-loaded `.cljc`) server-render
     namespace; a CLJS expansion is the compile error
     `:rf.ui.compile/ui-render-static-jvm-only` (the client emitter targets React
     directly — there are no structural trees in the browser). Like the mount
     verbs it is a MACRO, so the root form is LITERAL at the call site: a
     runtime-assembled vector is the same `:rf.ui.compile/runtime-root-form`
     compile error every root-form entry raises, and the root-id derives from the
     ONE mounted view exactly as `v/mount` derives it.

     NO SILENT ELISION (Spec 004C §3, EP-0034 §2): a runtime-requiring capability
     — a subscription, a committed handler, an effect, a foreign head — anywhere
     in the root's server-reachable view closure is a LOUD build error
     (`:rf.ui.compile/static-root-requires-runtime`) with source coordinates,
     never a capability quietly dropped from the static output. `v/client-only`
     stays legal (only its capability-free fallback is server-reachable); a
     deterministic `use-id` is exempt. To server-render a live subtree, mount it
     in the browser with `v/mount` / `v/hydrate-root`, or move it behind a
     `v/client-only` with a capability-free fallback.

     `re-frame.freehand` takes NO compile-time require on `re-frame.ssr`: the
     emitted render reaches the SSR serialiser through the late-resolution seam
     `re-frame.freehand.tree/emit-static-html`, so a render-static call in a
     namespace that requires only `re-frame.freehand` compiles and renders. A
     missing `day8/re-frame2-ssr` artefact at render time is the ruled, typed
     `:rf.error/ssr-artefact-missing` naming the artefact — never a raw host
     exception.

     Per [Spec 011 §Freehand server render](../../../../spec/011-SSR.md)."
     [root-form]
     (compiler-root/render-static-form &form &env root-form)))

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
;; Render slots — parameterized content supplied by the caller
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Render slots. `v/render-fn` declares the content and `v/slot`
;; invokes it, and both are COMMON grammar: in a COMPILED body they are seq
;; forms the analyzer recognises and lowers; in an INTERPRETED body they are
;; the ordinary macro and the ordinary function call below. One spelling, one
;; contract, two front ends — the `v/presence` shape.
;;
;; The asymmetry the two modes DO keep is deliberate and is D010's. An
;; interpreted slot also accepts an ordinary pure function, because it has
;; nothing to prove about the content it invokes; a compiled one accepts only
;; a render-fn, because the compiled tier's whole claim is that it can SEE
;; what it lowers.

(defn slot
  "(v/slot render-fn-value arg…) — render the parameterized content a
  CALLER supplied, at the site the component chooses, with the arguments
  the component supplies.

      (v/defview data-table [{:keys [rows row]}]
        [:tbody (for [r rows] ^{:key (:id r)} (v/slot row r))])

      [data-table {:rows rows
                   :row  (v/render-fn [r] [:tr [:td (:name r)]])}]

  `render-fn-value` is a [[render-fn]] value, or `nil` — an absent slot
  renders nothing, so a component may offer content it does not require.
  An INTERPRETED body additionally accepts an ordinary pure function of
  the same arguments; a compiled one does not (see above). Anything else
  is a loud diagnostic naming the render-fn recovery.

  The arity is a CONTRACT, not a convention: a render-fn declares a fixed
  parameter vector and a slot passes exactly that many arguments, checked
  before the call so the answer is the same on both hosts rather than
  JavaScript's silent `undefined` or the JVM's raw `ArityException`.

  The rendered output participates in the surrounding children exactly
  like any other child — there is no slot node and no wrapper in the
  structural tree.

  Per [Spec 004 §Render slots](../../../../spec/004-Views.md#render-slots)."
  [render-fn-value & args]
  (events/invoke-slot render-fn-value args))

;; ---------------------------------------------------------------------------
;; Props forwarding — `v/spread` and `v/spread-safe`
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Props forwarding. Two forms, because forwarding a consumer's
;; attribute map onto an element you own is two different bargains and the
;; grammar makes the author pick one at the site. `v/spread` is the visible
;; cost — whatever the map carries lands, later-arg-wins. `v/spread-safe` is
;; the bounded one, and the bound is what a component library needs: the
;; structural/controlled keys and its OWN handler families are denied to the
;; caller in every build, so a forwarded map can add to the element but never
;; clobber what the component promised about it.
;;
;; Both are functions HERE and seq forms to the compiled analyzer, which is
;; the same one-spelling/two-front-ends arrangement `v/presence` has. The fold
;; itself belongs to neither: it is `re-frame.freehand.node`'s, so a promoted
;; declaration forwards through the identical rule.

(defn spread
  "(v/spread base) / (v/spread base overrides) — forward a runtime
  attribute map onto an element, in its props position.

      [:div.card (v/spread attrs {:class \"is-open\"})]

  Both maps are author-space attribute maps; `overrides` wins every
  collision. Every key is judged by the rule a LITERAL attribute key is
  judged by — the same refusals, read off the same emitted slot — so a
  map assembled at run time cannot smuggle in a spelling the grammar
  refuses at a visible site. `:key` is refused outright: it is not an
  attribute, and it is literal at the element that carries it.

  This is the VISIBLE-COST forward. Whatever the map carries lands on the
  element, and the author said so at the site. A component library
  forwarding a consumer's attrs wants [[spread-safe]] instead.

  Per [Spec 004 §Props forwarding](../../../../spec/004-Views.md#props-forwarding)."
  ([base] (node/spread-attrs base nil))
  ([base overrides] (node/spread-attrs base overrides)))

(defn spread-safe
  "(v/spread-safe owned caller) — forward a CONSUMER's attribute map onto
  an element the component owns, bounded.

      (v/defview text-field [{:keys [value attrs]}]
        [:input (v/spread-safe {:value value :on-change [:field/changed]}
                               attrs)])

  `owned` is the component's own props map; `caller` the forwarded
  runtime one. The deny law runs in EVERY build, not just dev: `:key`,
  `:ref`, `:value`, `:checked` and the component's own `on-*` handler
  families — both the bubble and capture phases — may not appear in
  `caller`, and an offender is a loud diagnostic rather than a silent
  drop. Alternate spellings do not route around it: a key is judged by
  the slot it is about to be written into, so a namespaced, string,
  symbol or already-camel spelling of a denied name is denied with it.

  Everything that survives folds UNDER the owned props — owned wins — with
  `:class` the one exception: the two class values COMPOSE, owned first,
  because a caller passing a utility class is adding to the element and
  not replacing what the component put there.

  That bound is what lets a component keep a promise about the element it
  renders. A controlled input stays controlled, an owned handler stays the
  one that fires, and the consumer still gets to pass `aria-*`, `data-*`
  and a class.

  Per [Spec 004 §Props forwarding](../../../../spec/004-Views.md#props-forwarding)."
  [owned caller]
  (node/spread-safe-attrs owned caller))

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
  ;; A shipped reusable view, so it carries a schema — and an HONEST one.
  ;; Every unrecognised key really does pass through to the `<a>`, so the
  ;; map is declared OPEN rather than listing a closed roster it would
  ;; then have to break. `{:closed false}` states the forwarding once,
  ;; where the rest of the props contract is.
  {:props [:map {:closed false}
           [:to :keyword]
           [:params {:optional true} [:maybe :map]]
           [:query {:optional true} [:maybe :map]]
           [:fragment {:optional true} [:maybe :string]]
           [:on-click {:optional true} :any]]}
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
  {:children-policy :none
   ;; The whole props contract is one key, so the closed default is exactly
   ;; right: a caller who reaches for a second one has misread the boundary,
   ;; and meets that at the call rather than in a value silently ignored.
   :props           [:map [:value :any]]}
  [{:keys [value]}]
  value)

;; ---------------------------------------------------------------------------
;; `error-boundary` — the framework's resettable render-failure boundary
;; ---------------------------------------------------------------------------
;;
;; Like `route-link`, a framework-supplied boundary is not a privileged one:
;; it is a declared internal boundary classified `:view`, so it is mounted
;; `[v/error-boundary {…} child]` and never called. What it does NOT share
;; with an ordinary `defview` is a render body — a boundary CONTAINS its
;; child rather than producing markup, so its descriptor carries the reserved
;; `:error-boundary` marker instead, and both emitters route it to their
;; containment realisation (a React class boundary in the browser, a
;; structural `contain` on the JVM). The law it drives — capture, the
;; once-per-generation safe intent, reset, and the private frame egress —
;; lives in `re-frame.freehand.errors`; Spec 004 §Error boundaries
;; and error egress owns the contract.

(def ^{:doc "(v/error-boundary {:fallback … :reset-key … :on-error …} child)
  — a RESETTABLE render-failure boundary with a fallback.

      [v/error-boundary
       {:reset-key route-revision
        :fallback  [broken-page {}]
        :on-error  [:telemetry/ui-render-failed]}
       [workspace-page {:workspace-id workspace-id}]]

  It catches render-class failures below it — a Freehand child body throwing,
  Hiccup normalization or common prop/event validation throwing, and (in the
  browser) a descendant foreign component throwing where React boundaries
  apply. It does NOT catch event-handler, asynchronous, or re-frame
  handler/sub failures: those keep their existing typed owners.

  A caught failure shows `:fallback` and publishes NOTHING from the failed
  render — the atomic shell already guarantees an abandoned candidate owns
  nothing. `:on-error`, when present, is one event prefix; the framework
  appends a bounded SAFE SUMMARY (a stable diagnostic id, the failing view
  id, phase, fingerprint and evidence — never the exception, props, app-db,
  or event payloads) and dispatches it exactly once per failure generation
  after the fallback commits. Changing `:reset-key` by `rf=` clears the
  captured failure and re-mounts the child — there is no boundary ref and no
  imperative reset handle.

  Production reporting rides a SECOND, private channel: at most one record
  per failure generation is promoted onto re-frame's always-on error axis
  and the frame-owned observability sink, carrying the opaque exception and a
  capped host stack an off-box shipper needs. That record carries NO
  automatic app-db or event-history capture — an application that wants a
  redacted snapshot obtains it through its own `:on-error` handler and an
  allow-list it owns.

  Options — the roster is CLOSED: `:fallback` (required), `:reset-key`, and
  `:on-error`. An unknown key, a missing `:fallback`, or an `:on-error` that
  is not an event-prefix vector raises `:rf.error/error-boundary-bad-args`.

  Per [Spec 004 §Error boundaries and error egress](../../../../spec/004-Views.md#error-boundaries-and-error-egress)."}
  error-boundary
  (descriptor/declare-view
    {:view-id         errors/boundary-view-id
     :lowering        :interpreted
     :children-policy :required
     :error-boundary  true
     ;; The option roster this boundary documents as CLOSED, said once as
     ;; data so a catalogue and a tool read the same contract the prose
     ;; states. `:fallback` is required; the guarded child arrives as
     ;; children under `:children-policy :required`, never as a prop.
     :props-schema    [:map
                       [:fallback :any]
                       [:reset-key {:optional true} :any]
                       [:on-error {:optional true} [:maybe :vector]]]}))

;; ---------------------------------------------------------------------------
;; Registered behaviors — the one sanctioned imperative boundary
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Registered behaviors and commands (D013). Two names, and the
;; split between them is the design: `defbehavior` REGISTERS code under a
;; qualified id, and `behavior` attaches that id — as DATA — to one node.
;; Nothing else crosses. There is no neutral hook, ref, effect, portal or
;; mount callback, and there is no host handle an application can hold.
;;
;; `behavior` is a declared boundary like `v/error-boundary`: a descriptor
;; mounted in a vector head, never called. Its private entry carries the
;; reserved `:behavior` marker, and the browser emitter routes it to the
;; lifecycle realisation instead of walking an ordinary body — the same
;; shape the error boundary uses for its React class. The JVM has no such
;; realisation and needs none: the render body below hands the decorated
;; node straight back, so the structural tree records an INERT MARKER —
;; the boundary, its behavior id, its target and its public config — and
;; nothing runs.

#?(:clj
   (defmacro defbehavior
     "Register an imperative host **behavior** — the ONE sanctioned way to
     own DOM or opaque host state, bounded to a single node.

         (v/defbehavior autosize
           \"Grow the textarea to fit its content.\"
           {:timing     :layout
            :connect    (fn [{:keys [node config]}] (fit! node config))
            :update     (fn [{:keys [node config]}] (fit! node config))
            :disconnect (fn [{:keys [node memory]}] (.disconnect memory))
            :commands   {:refit (fn [{:keys [node config]}] (fit! node config))}})

     The var it binds holds the **registered id** — the qualified keyword
     `:my.ns/autosize` — not the implementation. That is what keeps a use
     site data: `[v/behavior {:use autosize …} …]` records the id, the
     target and the config in the structural tree, while the code stays in
     the registry where a tool can neither serialize nor invoke it.

     The definition roster is CLOSED, and every key is optional except that
     a behavior must declare at least one of them:

       `:timing`      `:passive` (default) or `:layout` — the CLOSED set of
                      moments the lifecycle may run at. `:layout` runs
                      before the browser paints, which is the only honest
                      home for measure-then-place work; `:passive` runs
                      after paint. There is no third value, because the set
                      of moments host state can move at is part of the
                      contract rather than an implementation detail.
       `:connect`     runs once, at the COMMIT that mounts the node — never
                      from a render the host abandons. Its return value
                      becomes the connection's PRIVATE memory.
       `:update`      runs when the committed `:config` MOVES by `rf=`, with
                      `:prev-config` alongside. Equal config is not
                      movement, so a re-render that changes nothing touches
                      no host state. Its return replaces the memory.
       `:disconnect`  runs exactly once per committed connection, on
                      unmount. The connection is already released when it
                      runs, so its context is inert — a teardown reports
                      nothing and simply lets go.
       `:commands`    a finite map of operation keyword to function, reached
                      through the `:re-frame.freehand.host/command` effect.
       `:opaque`      `true` when the behavior owns the node's descendants,
                      which makes Freehand children on that node an error
                      rather than content the host will silently overwrite.

     Every lifecycle entry takes ONE context map: `:node` (the live host
     node), `:config`, `:memory`, `:behavior`, `:target`, `:generation`, and
     `:dispatch` — a generation-fenced outward dispatch into the frame the
     connection committed under. There is no frame query function: state a
     behavior needs arrives as `:config`, so a host cannot read application
     state at a moment nobody chose.

     Per [Spec 004 §Registered behaviors and commands](../../../../spec/004-Views.md#registered-behaviors-and-commands)."
     [sym & more]
     (let [[docstring definition]
           (if (string? (first more))
             [(first more) (second more)]
             [nil (first more)])]
       (when (or (nil? definition)
                 (seq (if docstring (nnext more) (next more))))
         (error/throw-error!
           :rf.error/behavior-bad-args
           'v/defbehavior
           (str "v/defbehavior is spelled (v/defbehavior name docstring? "
                "{:timing … :connect … :update … :disconnect … :commands …}) — a "
                "name, an optional docstring, and exactly one definition map. "
                sym "'s declaration does not match.")
           {:recovery :fix-the-declaration
            :extra    {:behavior sym}}))
       `(def ~(cond-> (vary-meta sym assoc :re-frame.freehand/behavior true)
                docstring (vary-meta assoc :doc docstring))
          (behaviors/register!
            ~(keyword (str (ns-name *ns*)) (str sym))
            ~definition)))))

(def ^{:doc "(v/behavior {:use behavior-id :target … :config …} node) — attach
  a registered behavior to ONE node.

      [v/behavior {:use    autosize
                   :target :composer/body
                   :config {:max-rows 8}}
       [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]]

  A declared boundary, mounted in a vector head and never called. The option
  roster is CLOSED — `:use` (required, the qualified id a `v/defbehavior`
  declaration binds), `:target` (the caller-authored semantic id a command
  addresses), `:config` (the public configuration) — and the child is exactly
  ONE element: a behavior owns one node, so a declared view, a fragment or a
  presence boundary there is refused rather than guessed at.

  `:config` is DATA through and through. A callback, a node, a ref or a
  preconstructed host instance is refused at the door on BOTH hosts — a
  configuration the structural tree cannot record is a use site a test and a
  tool cannot read. Something the host should report leaves as an event intent
  the behavior dispatches; something the host needs built is `:connect`'s to
  build.

  `:target` is what a command addresses, and it is caller-authored on purpose:
  Freehand derives no address from render position, so a sort, a rename, a
  parent extraction or a virtualized remount does not move it. It must be
  unique among live connections — a command reaching an ambiguous target is
  REFUSED rather than delivered to whichever mounted last.

  CONNECTION IS COMMIT-ONLY. The lifecycle rides a ref and an effect, both of
  which React runs only for a render it SELECTED, so a candidate the host
  abandons performs no host work at all. `:disconnect` runs exactly once per
  committed connection, and the connection is released before it runs — after
  teardown there is nothing left to leak, which a test asserts as an ABSENCE.

  On the JVM this is an INERT MARKER: the boundary node records the behavior
  id, the target and the config, the decorated element renders as itself, and
  nothing connects. Commands are refused there with the same reason.

  Per [Spec 004 §Registered behaviors and commands](../../../../spec/004-Views.md#registered-behaviors-and-commands)."}
  behavior
  (descriptor/declare-view
    {:view-id         behaviors/behavior-view-id
     :lowering        :interpreted
     :children-policy :required
     :behavior        true
     ;; The option roster this boundary documents as CLOSED, said once as
     ;; data so a catalogue and a tool read the same contract the prose
     ;; states. `:use` is required; the decorated node arrives as children
     ;; under `:children-policy :required`, never as a prop. The schema
     ;; states the SHAPE — that `:config` is data all the way down, and that
     ;; the child is one element, are laws a schema cannot express and
     ;; `re-frame.freehand.behaviors/read-opts` enforces.
     :props-schema    [:map
                       [:use :qualified-keyword]
                       [:target {:optional true} :any]
                       [:config {:optional true} [:maybe :map]]]
     ;; The STRUCTURAL realisation, and the common validation gate. The
     ;; browser emitter routes this descriptor to its own component (which
     ;; runs exactly the same `read-opts`), so this body is the JVM's — it
     ;; validates the call and hands the decorated node straight back, which
     ;; is what makes the structural projection an inert marker wrapping the
     ;; author's own element.
     :render          (fn behavior-render [props] (:child (behaviors/read-opts props)))}))
