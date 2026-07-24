(ns re-frame.freehand.compiler
  "defview / custom-element expansion pipeline: arity + options parsing,
  header (Q2) analysis, template analysis, manifest + fingerprint
  assembly, and per-host emission. Each host build runs the shared
  analyzer over its own source and hands that build's own AST to exactly
  one emitter (the portability law: no emitter consumes raw source or
  another emitter's output).

  Runs on the JVM only (macro expansion for both hosts); .cljc so the
  namespace stays loadable everywhere.

  There is ONE browser emitter for a compiled declaration:
  [[re-frame.freehand.compiler.emit-react]], reached through
  [[compile-structural-view]]. The transplanted second CLJS emitter that
  once sat beside it — `re-frame.freehand.compiler.emit-cljs` — lowered to
  a `re-frame.freehand.runtime` namespace this artefact does not have, and
  no authored declaration ever reached it; it was removed rather than given
  an invented runtime contract. A future emitter change should confirm that
  every symbol it emits RESOLVES: nothing fails until the emission path is
  taken, so a green suite is not evidence that emitted code is callable."
  (:require [clojure.string :as str]
            #?@(:clj [[re-frame.freehand.compiler.analyze :as ana]
                      [re-frame.freehand.compiler.build :as build]
                      [re-frame.freehand.compiler.emit-jvm :as emit-jvm]
                      [re-frame.freehand.compiler.emit-react :as emit-react]
                      [re-frame.freehand.compiler.env :as env]
                      [re-frame.freehand.compiler.grammar :as grammar]
                      [re-frame.freehand.compiler.header :as header]
                      [re-frame.freehand.fingerprint :as fingerprint]
                      [re-frame.freehand.props-schema :as props-schema]])))

#?(:clj
   (do

;; Each Shadow build carries accepted registries plus disposable compiler-env
;; scratch. A recompiled / edited / renamed source replaces its prior rows and
;; independent builds never share state. Distinct declarations with equal ids
;; fail atomically; the same var remains the HMR replacement path. The finalized
;; candidate registries become accepted only when Shadow retains the returned
;; build-state after the complete pipeline.
;;
;; The compile-time custom-element declarations are a build-scoped registry
;; like the other four: WRITTEN here (macro expansion → `build/contribute!
;; build/elements`) and READ on the compile path by the template analyzer
;; through `build/element-properties`, which resolves the AMBIENT build's
;; slice (rf2-vxgfnd.91). No process-global mirror atom: the former
;; `build/register! build/elements rules/custom-elements` bridge was a
;; last-writer-wins hazard where a sibling build's contribution could flip
;; another build's classification. The CLJS/JVM RUNTIME arm keeps its own
;; `re-frame.freehand.rules/custom-elements` ledger (populated by the emitted
;; `register-custom-element!`, read at render for dynamic `v/spread` props);
;; it is separate from this compile registry.

(def ^:private defview-option-keys #{:props :id :display-name})

(defn- fail
  ([id msg] (fail id msg nil))
  ([id msg data] (throw (env/compile-error id msg data))))

(defn- capabilities [ast]
  (let [caps (volatile! #{})]
    (letfn [(scan [n]
              (when (map? n)
                (case (:op n)
                  :raw     (vswap! caps conj :raw)
                  :html    (vswap! caps conj :html)
                  :foreign (vswap! caps conj :foreign)
                  :slot    (vswap! caps conj :render-slot)
                  ;; v/behavior — the ONE sanctioned imperative host boundary
                  ;; (D013). A live host lifecycle (connect / update / command /
                  ;; disconnect over a real node) is as much a runtime capability
                  ;; as an effect or a ref, so the boundary is a named capability
                  ;; rather than invisible — a behavior-bearing view must not
                  ;; report an empty roster (rf2-drpa3.116).
                  :behavior (vswap! caps conj :behavior)
                  :element (do (when (:custom? n) (vswap! caps conj :custom-element))
                               (when (get-in n [:props :spread])
                                 (vswap! caps conj :spread))
                               (when (get-in n [:props :safe-spread])
                                 (vswap! caps conj :spread-safe))
                               (when (:html n) (vswap! caps conj :html)))
                  nil)
                ;; A compiled render slot callback (a component prop value or an
                ;; inline v/slot argument) carries a :render-fn compiled body.
                (when (:render-fn n) (vswap! caps conj :render-fn))
                (doseq [[_ v] n]
                  (cond
                    (map? v) (scan v)
                    (vector? v) (doseq [x v] (scan x))))))]
      (scan ast))
    @caps))

(defn- compiled-capabilities
  "The full capability roster a compiled declaration carries: the
  STRUCTURAL capabilities its AST names (`:raw` / `:html` / `:foreign` /
  `:render-slot` / `:render-fn` / `:custom-element` / `:spread` /
  `:spread-safe` / `:behavior`) unioned with the REACTIVE and host-hook
  capabilities its lexical `sites` own. The reactive four — `:sub`, `:event`, `:frame`,
  `:dispatch-fn` — are exactly the set whose emptiness proves the ViewCell
  shell elidable ([[view-cell-elided?]]); `:local` / `:effect` and the
  folded interop-hook kinds are ordinary React machinery that ride a plain
  component, not the reactive shell."
  [ast sites]
  (-> (capabilities ast)
      (into (cond-> #{}
              (seq (:subs sites))         (conj :sub)
              (seq (:events sites))       (conj :event)
              (seq (:frame-ops sites))    (conj :frame)
              (seq (:dispatch-fns sites)) (conj :dispatch-fn)
              (seq (:locals sites))       (conj :local)
              (seq (:effects sites))      (conj :effect)))
      ;; the re-frame.freehand.react interop hooks fold onto the static-root
      ;; capability vocabulary — v/ref/use-*-effect -> :effect, use-context
      ;; -> :context (use-id is exempt, an inert deterministic id).
      (into (keep {:ref :ref :effect :effect :layout-effect :effect
                   :effect-event :effect :context :context})
            (map :kind (:react sites)))))

(defn view-cell-elided?
  "True when a compiled view's analysis PROVES it carries no reactive
  capability — no `sub`, no committed event handler, no `dispatch-fn`, and
  no `(frame)` read — so the reactive ViewCell shell is OMITTED and the
  view lowers to a plain memoized component.

  This is the exact predicate the React emitter's host-render selection
  turns on: a view with none of the reactive four never observes context,
  so it needs neither a `useSyncExternalStore` bridge nor an event owner.
  The verdict is DETERMINISTIC — a static function of the analyzed sites,
  never a timing measurement (EP-0036 D021) — which is why the omitted-cell
  COUNT over a fixture is an assertion on an exact integer.

  The asymmetry with the interpreted shell is deliberate and load-bearing:
  the interpreted shell ALWAYS observes frame context, because it has no
  finite grammar and cannot prove a body sub-free; compiled elision is
  earned only by that proof."
  [sites]
  (not (boolean (or (seq (:subs sites))
                    (seq (:events sites))
                    (seq (:dispatch-fns sites))
                    (seq (:frame-ops sites))))))

;; ---------------------------------------------------------------------------
;; Per-view static-render facts (Spec 004C §3 / EP-0034 §2 — the render-static
;; no-silent-elision proof)
;; ---------------------------------------------------------------------------

(def ^:private runtime-requiring-site-caps
  "Site-kind -> the runtime-requiring capability token render-static rejects.
  Every one names a live-runtime need a pure `:server` static render cannot
  honour (a sub read, a committed handler, a frame read, a host hook)."
  {:events :handler :subs :sub :frame-ops :frame
   :locals :local :effects :effect :dispatch-fns :dispatch-fn})

(defn view-static-facts
  "The SERVER-REACHABLE static-render facts for one analyzed body — the small
  internal projection the render-static transitive proof closes over (Spec 004C
  §3, EP-0034 §2 no-silent-elision):

    {:caps <set of runtime-requiring capability tokens in THIS body>
     :deps <set of directly-referenced view-ids>}

  `:caps` unions the runtime-requiring SITE kinds (subs / committed handlers /
  frame reads / host locals / effects / dispatch-fns), the substrate `v/ref`
  (-> `:ref`) and the re-frame.freehand.react host hooks (use-context -> `:context`,
  use-effect family -> `:effect`; `use-id` is EXEMPT — an inert deterministic id
  is static-safe), authored element/component `:ref` slots (a commit-phase host
  hook a static render cannot honour -> `:ref`), a `v/behavior` boundary (a live
  host lifecycle a :server render owns no node for -> `:behavior`), and foreign
  heads (`:foreign`, which a `react/lazy` head folds into). `:deps` are the view
  references to close over transitively.

  SERVER REACHABILITY governs BOTH the dependency walk AND the capability
  collection: a `v/client-only` subtree is browser-only — the JVM emitter
  renders only its capability-free FALLBACK — so its views, its authored refs,
  and its authored SITES (an inline `:on-click`, `sub`, effect …) never reach the
  static output and must not be counted (a static root never flips). Since the
  view-wide `sites` atom also carries the browser-only child's sites (the child
  is analyzed against the shared atom), each client-only node's `:path` is
  recorded and a site whose `:path` descends from one is excluded — the capability
  proof only counts the SERVER-REACHABLE sites, mirroring the dependency walk."
  [ast sites]
  (let [deps      (volatile! #{})
        foreign?  (volatile! false)
        ref?      (volatile! false)
        behavior? (volatile! false)
        co-paths  (volatile! [])]
    (letfn [(walk [n]
              (when (map? n)
                (if (= :client-only (:op n))
                  ;; browser-only child never reaches the JVM tree; record its
                  ;; path (so its sites drop out of the capability proof) and
                  ;; walk ONLY the capability-free fallback.
                  (do (vswap! co-paths conj (:path n))
                      (walk (:fallback n)))
                  (do
                    (when (= :view (:op n))    (vswap! deps conj (:view-id n)))
                    (when (= :foreign (:op n)) (vreset! foreign? true))
                    ;; a v/behavior boundary is a live host lifecycle (connect /
                    ;; update / command / disconnect over a real node) — a pure
                    ;; :server render owns no node and cannot honour it, so a
                    ;; server-reachable behavior is a runtime-requiring
                    ;; capability the JVM emitter would otherwise fold to an inert
                    ;; marker and silently drop. Reached HERE by the same walk
                    ;; that excludes a client-only subtree, so a behavior only in
                    ;; a client arm is never counted (rf2-drpa3.116).
                    (when (= :behavior (:op n)) (vreset! behavior? true))
                    ;; an authored `:ref` (element OR component props) is a
                    ;; commit-phase host hook — a static render cannot honour it,
                    ;; so a server-reachable ref is a runtime-requiring capability
                    ;; (the JVM emitter would otherwise drop it silently).
                    (when (some? (get-in n [:props :ref])) (vreset! ref? true))
                    (doseq [[_ v] n]
                      (cond
                        (map? v)    (walk v)
                        (vector? v) (run! walk v)))))))]
      (walk ast))
    (let [cos       @co-paths
          reachable (if (empty? cos)
                      (fn [kind] (get sites kind))
                      (let [under-co? (fn [p]
                                        (boolean
                                         (some (fn [co]
                                                 (and (<= (count co) (count p))
                                                      (= co (subvec p 0 (count co)))))
                                               cos)))]
                        (fn [kind] (remove #(under-co? (:path %)) (get sites kind)))))]
      {:caps (cond-> (into (into #{}
                                 (keep (fn [[kind cap]]
                                         (when (seq (reachable kind)) cap)))
                                 runtime-requiring-site-caps)
                           (keep {:ref :ref :context :context :effect :effect
                                  :layout-effect :effect :effect-event :effect})
                           (map :kind (reachable :react)))
               @foreign?  (conj :foreign)
               @ref?      (conj :ref)
               @behavior? (conj :behavior))
       :deps @deps})))

(defn build-view-static
  "The ambient build's per-view static-render facts index (view-id ->
  `{:caps :deps}`) — the read `re-frame.freehand/render-static`'s transitive proof
  closes over (Spec 004C §3). Per-build like every other registry read.

  Populated by the `defview` macro's slice contribution ONLY off the Shadow
  build path (plain-JVM / SSR / REPL): render-static is JVM-only, so a real
  Shadow build never reads this index and the macro does not contribute to it
  there (rf2-u53yy.1 S3). A dependency compiled in another build/context resolves
  from its registered manifest's `:static-facts` instead
  (`root/registered-view-static-facts`)."
  []
  (build/aggregate build/view-static))

(defn- source-coords [form cljs?]
  (let [m (meta form)]
    (cond-> {:file (if cljs?
                     (try @(requiring-resolve 'cljs.analyzer/*cljs-file*)
                          (catch Exception _ *file*))
                     *file*)}
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(defn- anchored
  "Run `thunk`; a compile error escaping it gains the declaration form's
  source coordinates (:file/:line/:column) in its ex-data — the S1e
  file:line anchoring, so every {:rf.ui.compile/error <id>} carries WHERE
  alongside id + didactic message. Existing ex-data wins on collisions;
  id, message and cause are preserved."
  [coords thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (throw
         (if (and (:rf.ui.compile/error data) (nil? (:file data)))
           (ex-info (ex-message ex) (merge coords data) ex)
           ex))))))

;; ---------------------------------------------------------------------------
;; `{:compiled true}` — selecting the `:re-frame.freehand/v1` grammar
;; ---------------------------------------------------------------------------
;;
;; The compiled tier is entered from the SAME `v/defview` a declaration already
;; uses. There is no second declaration form, no second registry, and no second
;; call spelling: `{:compiled true}` chooses which front end lowers the body,
;; and the descriptor, the props contract, the boundary and the structural
;; output are the ones the interpreted mode already owns.
;;
;; What the option actually buys is the FINITE grammar. Compilation is explicit
;; (EP-0036 governing law 6) because a compiler that silently declined would be
;; worse than one that refuses: an author would have no way to know which of
;; their views is paying for the analysis and which is not. So a body outside
;; `:re-frame.freehand/v1` is a build failure naming a recovery — never a quiet
;; demotion, and never an interpreted walk hidden inside compiled markup.

(defn- with-grammar
  "Anchor a compile error escaping the compiled pipeline with the grammar
  that refused the form and the recovery ladder for its id.

  Existing ex-data wins on collision, so a diagnostic that already named
  its own ladder (the grammar's own op check) keeps it; everything the
  analyzer raises gains one here, in ONE place, rather than by editing a
  hundred call sites into carrying a field they cannot choose better
  than a table can."
  [view-id thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (throw
         (if-let [id (:rf.ui.compile/error data)]
           (ex-info (ex-message ex)
                    (merge {:re-frame.freehand/grammar grammar/version
                            :recovery                  (grammar/recovery id)
                            :view                      view-id}
                           data)
                    ex)
           ex))))))

(defn structural-manifest
  "The manifest a `{:compiled true}` declaration carries — what its
  analysis makes statically knowable about it, as plain data. Every
  roster is FINITE, in source order, and derived from the analyzer's
  lexical site index, so a reader learns what a view does without running
  it and a tool inventories a codebase without mounting it.

      {:view-id       :app.people/people-list
       :grammar       :re-frame.freehand/v1
       :subscriptions [{:sid \"…\" :query [:person/by-id 7]
                        :source-coord {…} :path [0]}]
       :events        [{:sid \"…\" :source-coord {…} :path [1 0]}]
       :slots         [{:sid \"…\" :inline? true :source-coord {…} :path [2]}]
       :html-sites    []
       :frame-ops     []
       :capabilities  #{:sub :event}
       :reactive?     true
       :view-cell     :present
       :crossings     [{:view-id :app.people/person-row  :lowering :compiled
                        :source-coord {…} :path [0 :for]}
                       {:view-id :re-frame.freehand/markup :lowering :interpreted
                        :source-coord {…} :path [1]}]}

  - `:subscriptions` / `:events` / `:slots` / `:html-sites` / `:frame-ops`
    are the view's finite reactive, intent, render-slot, trusted-markup
    and committed-frame site rosters — the same lexical sites the
    analyzer indexed, projected to their statically knowable facts.
  - EVERY roster entry carries a `:source-coord` — `{:file :line :column}`
    of the form that produced it, or of the enclosing declaration when the
    reader anchored no narrower position (see
    [[re-frame.freehand.compiler.analyze]]). Total and never invented, so a
    manifest fact and a build-log line name the same lexical position, and
    a diagnostic can say which SITE a capability came from rather than only
    which view. The `:path` beside it is the deterministic occurrence
    coordinate and answers a different question.
  - `:capabilities` is the union of the structural and reactive
    capability bits ([[compiled-capabilities]]).
  - `:reactive?` and `:view-cell` carry the **capability-elision** verdict
    ([[view-cell-elided?]]): a view with no reactive site omits the
    reactive ViewCell shell (`:view-cell :elided`, `:reactive? false`),
    which is what the compiled tier buys and the omitted-cell count
    asserts exactly. The verdict is a deterministic function of the
    sites, never a measurement.
  - `:crossings` is the roster of internal-view boundaries the body
    mounts, one entry per LEXICAL site — a site inside a keyed list is one
    crossing that mounts many times — each MARKED with the mode it crosses
    into (the manifest half of D010). The crossings marked `:interpreted`
    are the view's **interp slots**; the manifest names sites, a render
    counts mounts, and neither is derivable from the other.
  - `:static-facts` is the render-static server-reachable `{:caps :deps}`
    projection ([[view-static-facts]]) — the same small closure
    `v/render-static`'s no-silent-elision proof walks. It is computed HERE,
    once, and the ambient `view-static` build index is populated FROM this
    manifest key rather than from a second call, so the two publications
    are one value and cannot drift. Carrying it on the manifest is what
    makes a view compiled in ANOTHER build (AOT, a precompiled jar) still
    fact-bearing when this build's index knows nothing about it.

  Sites and mounts are different quantities and MUST NOT be conflated:
  every roster here is a per-declaration STATIC fact, and occurrence
  evidence counts per-occurrence MOUNTS separately."
  [view-id ast sites]
  (let [elided? (view-cell-elided? sites)]
    {:view-id       view-id
     :grammar       grammar/version
     :subscriptions (mapv #(select-keys % [:sid :query :source-coord :path]) (:subs sites))
     :events        (mapv #(select-keys % [:sid :source-coord :path]) (:events sites))
     :slots         (mapv #(select-keys % [:sid :inline? :source-coord :path]) (:slots sites))
     :html-sites    (mapv #(select-keys % [:source-coord :path]) (:htmls sites))
     :frame-ops     (mapv #(select-keys % [:sid :source-coord :path]) (:frame-ops sites))
     :capabilities  (compiled-capabilities ast sites)
     :reactive?     (not elided?)
     :view-cell     (if elided? :elided :present)
     :crossings     (mapv #(select-keys % [:view-id :lowering :source-coord :path])
                          (:views sites))
     :static-facts  (view-static-facts ast sites)}))

(defn compile-structural-view
  "Lower a `{:compiled true}` declaration's body to its STRUCTURAL
  realisation — the `(fn [props] node)` the descriptor carries — or throw
  the diagnostic that says why the body is outside
  `:re-frame.freehand/v1`.

  Answers `{:body form :grammar kw :manifest {..} :sites {..}}`. `:sites`
  is the analyzer's lexical site index: the compiled tier's whole claim to
  see what a body does rests on those sites being complete, so they travel
  with the lowering rather than being recomputed by anyone who wants
  them.

  `:manifest` is the declaration-facing projection of that index — see
  [[structural-manifest]].

  `form` is the declaration's `&form`, `menv` its `&env`, `params` its
  one-element parameter vector and `body` the body forms."
  [{:keys [form menv ns-sym vname view-id params body children-policy props-schema]}]
  (let [cljs?  (some? (:ns menv))
        coords (source-coords form cljs?)
        ;; The static half of the props-schema decision. A declared schema
        ;; closes the map, and the analyzer already refuses an undeclared
        ;; literal key at a call site it can see — so the compiled tier gains
        ;; BUILD-TIME reporting of exactly the breach the boundary reports at
        ;; render, from exactly the same roster. Nil when no schema was
        ;; declared, or the schema is opaque or explicitly open: the map stays
        ;; open and nothing is checked.
        closing (props-schema/closing-keys props-schema)]
    (anchored
     coords
     #(with-grammar
        view-id
        (fn []
          (let [e0  (-> (env/make-env
                         {:host            (if cljs? :cljs :clj)
                          :cljs-env        menv
                          :ns-sym          ns-sym
                          :self            vname
                          :self-id         view-id
                          :source          coords
                          :template-anchor (fingerprint/digest "sta1-" body)})
                        (assoc :self-children? (not= :none children-policy)
                               :self-closed-keys closing))
                _   (ana/reject-reactive-binding! e0 params)
                ;; The parameter vector's own bindings are template locals: a
                ;; head or expression spelling one of them is a LOCAL, never a
                ;; var, which is what makes `[title …]` a dynamic head rather
                ;; than a mysteriously-resolving component.
                e   (-> e0
                        (env/with-locals (set (env/binding-syms (first params))))
                        (assoc :hooks-region? true))
                ast (ana/analyze-view-body e body)]
            (grammar/check! e view-id ast)
            (doseq [w @(:warnings e)]
              (binding [*out* *err*]
                (println (str "WARNING re-frame.freehand [" view-id "] "
                              (:id w) ": " (:msg w)))))
            (let [sites    @(:sites e)
                  manifest (structural-manifest view-id ast sites)]
              ;; The render-static no-silent-elision facts (Spec 004C §3,
              ;; EP-0034 §2): contribute this view's server-reachable
              ;; `{:caps :deps}` to the ambient `view-static` build index so
              ;; `v/render-static` can prove the view's capability closure
              ;; MID-EXPANSION (its JVM-only read; `re-frame.freehand.compiler.root/
              ;; static-capability-offender` closes over `build-view-static`).
              ;; Gated off the Shadow build path — render-static never runs there,
              ;; so the slice has no consumer — exactly as `defview**` gates its
              ;; own contribution. Without this the door's compiled `defview`
              ;; (which lowers through here, not through `defview**`) left the
              ;; index empty and every render-static proof reported the view
              ;; UNPROVEN.
              ;;
              ;; The contributed value is READ OFF THE MANIFEST rather than
              ;; recomputed. The ambient index is a per-build convenience and the
              ;; manifest is the durable cross-build carrier, and a view whose two
              ;; publications disagreed would prove one thing in the build that
              ;; compiled it and another in the build that consumes it. One
              ;; projection, published twice, is the only shape in which that
              ;; cannot happen (rf2-drpa3.159).
              (when-not (build/shadow-build-pass?)
                (build/contribute! build/view-static ns-sym view-id
                                   (:static-facts manifest)))
              (cond-> {:body     (emit-jvm/emit-structural-body e params ast)
                       :grammar  grammar/version
                       :manifest manifest
                       :sites    sites}
                ;; The BROWSER realisation, emitted only where there is a
                ;; browser to emit for. Both lowerings come out of ONE
                ;; analysis of ONE body — the emitters consume the same
                ;; normalized AST and neither is written in terms of the
                ;; other's output — so a compiled declaration cannot say one
                ;; thing in a structural render and another in the DOM.
                cljs? (assoc :react (emit-react/emit-react-body e params ast))))))))))

(defn- custom-element**
  [coords ns-sym tag opts]
  (when-not (and (keyword? tag) (nil? (namespace tag))
                 (str/includes? (name tag) "-"))
    (fail :rf.ui.compile/bad-custom-element
          (str "(v/custom-element tag opts): tag must be an unqualified "
               "keyword containing '-' (the custom-element grammar); got "
               (pr-str tag))
          {:tag tag}))
  (when-not (map? opts)
    (fail :rf.ui.compile/bad-custom-element
          "(v/custom-element tag {:properties #{...}}) needs a literal options map"
          {:tag tag :opts opts}))
  (let [unknown (remove #{:properties} (keys opts))]
    (when (seq unknown)
      (fail :rf.ui.compile/bad-custom-element
            (str "unknown custom-element option"
                 (when (next unknown) "s") " "
                 (str/join ", " (map pr-str unknown))
                 " — the v1 grammar is exactly {:properties #{...}} "
                 "(closed; :events / per-prop types / attribute reflection "
                 "are future rulings, not silent growth)")
            {:tag tag :unknown (vec unknown)})))
  (let [props (:properties opts #{})]
    (when-not (and (set? props)
                   (every? #(and (keyword? %) (nil? (namespace %))) props))
      (fail :rf.ui.compile/bad-custom-element
            (str ":properties must be a literal set of unqualified keyword "
                 "property names (kebab; :help-text -> the helpText JS "
                 "property); got " (pr-str props))
            {:tag tag :properties props}))
    ;; compile-time registration (this JVM performs macroexpansion for
    ;; both hosts) routed through the build-scoped model so a removed /
    ;; renamed declaration drops its stale property classification with
    ;; its file (rf2-vxgfnd.16). The emitted runtime registration (v/spread
    ;; classifies at render via the same registry, on the client) carries the
    ;; declaring ns so a hot-reload that DROPS this declaration evicts its
    ;; stale runtime row — the runtime arm of the same eviction model
    ;; (rf2-vxgfnd.48).
    ;;
    ;; The ONE cross-source declaration law (rf2-vxgfnd.143, delegated ruling
    ;; 2026-07-15 Option A): rf=-equal duplicates co-exist; a CONTRADICTORY
    ;; declaration of the same tag from another source fails ATOMICALLY with
    ;; both [build-id ns-sym] anchors and leaves the last-known-good aggregate
    ;; unchanged. Which of two contradicting sources expands second decides
    ;; only WHERE the failure is anchored — never who wins, and never whether
    ;; the build fails. `:declarations` evidence is sorted, so it is identical
    ;; under every source / evaluation / build-order permutation.
    ;; During a real Shadow build PASS the compile-time custom-element registry
    ;; is populated by the build hook's prepare-time ALL-MEMBERS source harvest,
    ;; which also enforces the cross-source conflict law there (rf2-u53yy.1 S1);
    ;; the macro is then validation/reporting-only — it validates the declaration
    ;; grammar above and emits the runtime registration below, but does NOT
    ;; contribute to (or evict from) the compile-time registry. Off that path
    ;; (plain-JVM / SSR, REPL — there is no `:compile-prepare` harvest), the
    ;; macro's own contribution populates the per-source `elements` slice and
    ;; reports contradictions here with the declaration's source coordinates.
    (let [decl {:properties props}]
      (when-not (build/shadow-build-pass?)
        (when-let [{:keys [conflict]} (build/contribute-element-checked!
                                       ns-sym tag decl)]
          (let [build-id (build/current-build-id)
                rows (vec (sort-by (juxt (comp str :build) (comp str :ns))
                                   [{:build build-id
                                     :ns (:owner conflict)
                                     :properties (:properties (:declaration conflict) #{})}
                                    {:build build-id :ns ns-sym :properties props}]))]
            (fail :rf.ui.compile/custom-element-conflict
                  (str "conflicting (v/custom-element " tag " ...) declarations — "
                       (str/join " vs "
                                 (map (fn [{:keys [ns properties]}]
                                        (str ns " declares :properties "
                                             (pr-str (vec (sort properties)))))
                                      rows))
                       ". One tag has ONE property manifest: delete the duplicate "
                       "declaration, or make both sources declare an IDENTICAL "
                       ":properties set (identical declarations may co-exist). "
                       "re-frame.freehand will not pick a winner by compile order")
                  {:tag tag :declarations rows})))))
    `(re-frame.freehand.rules/register-custom-element!
      ~tag {:properties ~props} '~ns-sym)))

(defn custom-element*
  "The RULED custom-element declaration grammar (Mike, 2026-07-12):
  (v/custom-element tag {:properties #{...}}) — top-level,
  compile-resolvable, registers like defview. The :properties set is the
  ENTIRE v1 grammar (options map closed); future keys are new rulings,
  not silent growth. `form` = &form, `menv` = &env — compile errors are
  anchored with the declaration form's source coordinates (S1e)."
  [form menv tag opts]
  (let [coords (source-coords form (some? (:ns menv)))
        ns-sym (if (:ns menv) (-> menv :ns :name) (ns-name *ns*))]
    (anchored coords
              #(custom-element** coords ns-sym tag opts))))

))
