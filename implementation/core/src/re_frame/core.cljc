(ns re-frame.core
  "Public API surface for re-frame2. Per spec/API.md.

  Users `(:require [re-frame.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event-fx`, etc. Internal namespaces are not part of the
  contract.

  This namespace re-exports the canonical surface; per-namespace docs
  carry the design rationale.

  ;; ---- ns-load topology -----
  ;;
  ;; Per rf2-hoiu, each optional-artefact wrapper (flows, routing,
  ;; schemas, machines, ssr, epoch, http) lives in its own
  ;; `re-frame.core-X` sibling namespace; the re-exports below preserve
  ;; the single-import contract — apps still write `rf/reg-flow` etc.
  ;; after `(:require [re-frame.core :as rf])`. The sibling namespaces
  ;; are accessible (they're not under `.impl.`) but `rf/` is the
  ;; documented entry point.
  ;;
  ;; Per rf2-2vbm the file naming uses `core_X` rather than `core/X`
  ;; because CLJS goog.provide for `re-frame.core` overwrites its parent
  ;; object, which would wipe a previously-loaded `re-frame.core.X` —
  ;; the dash-form is the flat alternative that compiles under both
  ;; targets.
  ;;
  ;; Each optional-artefact wrapper looks up its target fn through the
  ;; late-bind hook table at call time (per rf2-tfw3, rf2-k682,
  ;; rf2-p7va, rf2-xbtj, rf2-uo7v, rf2-lt4e, rf2-5kpd) so this
  ;; namespace does NOT statically `:require` the producing
  ;; namespaces. The producing artefacts ship as separate Maven
  ;; artefacts and populate the hook table from their own ns-load.
  ;; Apps that omit a feature do not pay its bundle weight."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.cofx :as cofx]
            [re-frame.subs :as subs]
            [re-frame.interceptor :as interceptor]
            [re-frame.std-interceptors :as std-interceptors]
            [re-frame.privacy :as privacy]
            [re-frame.spec :as spec]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]
            [re-frame.trace.projection :as trace-projection]
            [re-frame.elision :as elision]
            [re-frame.substrate.adapter :as adapter]
            ;; Optional-artefact wrappers (rf2-hoiu). Each ns wraps a
            ;; per-feature Maven artefact and looks the producing fns
            ;; up via the late-bind hook table; pulling the wrapper
            ;; namespaces does NOT pull the feature artefacts.
            [re-frame.core-flows    :as rf-flows]
            [re-frame.core-routing  :as rf-routing]
            [re-frame.core-schemas  :as rf-schemas]
            [re-frame.core-machines :as rf-machines]
            [re-frame.core-ssr      :as rf-ssr]
            [re-frame.core-epoch    :as rf-epoch]
            [re-frame.core-http     :as rf-http]
            ;; re-frame.substrate.plain-atom — kept as a require so the
            ;; symbol resolves for the `plain-atom/adapter` re-export
            ;; below. Per rf2-agql there is no default-adapter registry
            ;; and no ns-load side-effect: every consumer (including
            ;; tests and SSR hosts) calls `(rf/init! adapter-map)` with
            ;; an explicit adapter spec.
            [re-frame.substrate.plain-atom :as plain-atom]
            ;; CLJS only: re-frame.views holds the Reagent-aware reg-view*
            ;; (with React-context wiring). On JVM the registrar registration
            ;; is sufficient.
            #?@(:cljs [[re-frame.views :as views]]))
  ;; `bound-fn` shadows clojure.core/bound-fn — the v2 surface deliberately
  ;; reuses the name (per Spec 002 §bound-fn) for the frame-capturing form.
  (:refer-clojure :exclude [bound-fn])
  ;; The reg-view / with-frame / bound-fn macros are defined in the
  ;; #?(:clj ...) blocks below. Make them visible to CLJS callers under
  ;; the `re-frame.core` alias by self-referring `:require-macros`. Per
  ;; Spec 004 §reg-view, the macros live in re-frame.core; CLJS users
  ;; can write `rf/reg-view` after `(:require [re-frame.core :as rf])`
  ;; without an explicit `:require-macros` clause at the call site.
  #?(:cljs (:require-macros [re-frame.core :refer [reg-view with-frame bound-fn
                                                   dispatch dispatch-sync
                                                   subscribe inject-cofx]])))

;; ---- registration ---------------------------------------------------------
;;
;; Per Spec 001 §Source-coordinate capture (CLJS reference) and
;; Tool-Pair §Source-mapping: every reg-* registration's metadata
;; carries :ns / :line / :file auto-supplied at compile time. We
;; wrap each reg-* fn in a macro that captures (meta &form)'s
;; :line / :column plus *ns* / *file*, binds re-frame.source-coords/
;; *pending-coords* around the underlying fn, and the fn merges the
;; coords into the registered metadata.
;;
;; JVM side: defmacro form. Direct fn-form access is preserved via
;; events/reg-event-db etc. — internal callers (re-frame.ssr) reach the
;; fn directly and don't pay the macro tax. Per rf2-k682 the routing
;; namespace lives in a separate artefact and uses events/reg-event-fx
;; directly rather than the macro layer.
;;
;; CLJS side: keep the existing fn-alias form. The macro path is a
;; future addition (a re-frame.core-macros companion ns); the ALIAS
;; path keeps current CLJS callers functioning. Tooling that consumes
;; :ns / :line / :file via the JVM side (server-rendering, JVM tests,
;; REPL introspection) is unaffected.
;;
;; rf2-xnym: the rationale for `(symbol (str (ns-name *ns*)))` rather
;; than `(ns-name *ns*)` — in CLJS macro context the ns-symbol may
;; carry the consumer namespace's :doc metadata, which would then get
;; serialised into the bundle and defeat production elision. Every
;; reg-* macro below routes its (meta &form) / *file* / *ns* capture
;; through `with-coords-form` so the rationale lives in one place.

#?(:clj
   (defn ^:private with-coords-form
     "Wrap `body-form` in a binding of `source-coords/*pending-coords*`
     to the compile-time coord map for `form-meta` / `file` / `ns-sym`.
     Caller passes `(meta &form)`, `*file*`, and the metadata-free
     ns-symbol (per the rationale above). Returns a syntax-quote-safe
     form suitable for a reg-* defmacro to emit.

     The rf2-52gw helper: centralises the (binding [...] (target ...))
     skeleton that every reg-* macro emits, so each defmacro becomes a
     one-line delegation rather than a 12-line repetition."
     [form-meta file ns-sym body-form]
     `(binding [source-coords/*pending-coords*
                ~(source-coords/coords-form form-meta file ns-sym)]
        ~body-form)))

#?(:clj
   (defmacro reg-event-db
     "Register a `(fn [db event-vec] new-db)` event handler under `id`.
     Captures source-coords (Spec 001) at this call site. See
     `re-frame.events/reg-event-db` for the full signature."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(events/reg-event-db ~id ~@args))))

#?(:clj
   (defmacro reg-event-fx
     "Register a `(fn [cofx event-vec] effect-map)` event handler under `id`.
     Effect-map is a closed shape: only `:db` and `:fx` at the top level.
     Captures source-coords (Spec 001) at this call site. See
     `re-frame.events/reg-event-fx` for the full signature."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(events/reg-event-fx ~id ~@args))))

#?(:clj
   (defmacro reg-event-ctx
     "Register a `(fn [context] context)` full-context event handler under `id`.
     Advanced — most handlers want `reg-event-db` or `reg-event-fx` instead.
     Captures source-coords (Spec 001) at this call site. See
     `re-frame.events/reg-event-ctx` for the full signature."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(events/reg-event-ctx ~id ~@args))))

#?(:clj
   (defmacro reg-sub
     "Register a subscription under `id`. Layer-1 subs read `app-db`
     directly; layer-2 subs chain off other subs via `:<-`. Captures
     source-coords (Spec 001) at this call site. See
     `re-frame.subs/reg-sub` for the full signature."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(subs/reg-sub ~id ~@args))))

#?(:clj
   (defmacro reg-fx
     "Register an effect handler under `id`. Handler signature is
     `(fn [ctx args] ...)`; runs when a `reg-event-fx` returns an effect-map
     carrying `[id args]` inside its `:fx` vector. Captures source-coords
     (Spec 001) at this call site. See `re-frame.fx/reg-fx` for the full
     signature."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(fx/reg-fx ~id ~@args))))

#?(:clj
   (defmacro reg-cofx
     "Register a coeffect handler under `id` — a source of input data
     injected into an event handler's `:coeffects` map via `inject-cofx`.
     Captures source-coords (Spec 001) at this call site. See
     `re-frame.cofx/reg-cofx` for the full signature."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(cofx/reg-cofx ~id ~@args))))

#?(:clj
   (defmacro reg-frame
     "Register a frame. Captures source-coords (Spec 001) at this call site.
     See `re-frame.frame/reg-frame` for the full signature."
     [id metadata]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(frame/reg-frame ~id ~metadata))))

;; CLJS side keeps the fn-aliases. Source-coord capture on CLJS will
;; ride a future re-frame.core-macros companion ns. The fns themselves
;; (events/reg-*, subs/reg-sub, etc.) honour `*pending-coords*` either
;; way — the macros above are the *capture* path; the merge path is
;; in the fns.
#?(:cljs
   (do
     (def reg-event-db    events/reg-event-db)
     (def reg-event-fx    events/reg-event-fx)
     (def reg-event-ctx   events/reg-event-ctx)
     (def reg-sub         subs/reg-sub)
     (def reg-fx          fx/reg-fx)
     (def reg-cofx        cofx/reg-cofx)
     (def reg-frame       frame/reg-frame)))

(defn reg-view*
  "Plain-fn surface for view registration. Use for runtime registration
  (computed ids, library generation, Form-3 / `create-class` bodies) where
  the `reg-view` macro's defn-shape doesn't fit. Optional metadata map is
  merged with any pending source-coords from a wrapping `reg-view`.
  Per Spec 004 §reg-view*."
  ([id render-fn]
   (reg-view* id {} render-fn))
  ([id metadata render-fn]
   #?(:cljs
      ;; Hand off to the Reagent-aware impl which wraps with
      ;; :context-type metadata for frame resolution. The merge of
      ;; pending source-coords happens here so re-frame.views/reg-view*
      ;; receives a single, complete metadata map.
      (views/reg-view* id (source-coords/merge-coords metadata) render-fn)
      :clj
      (registrar/register! :view id (assoc (source-coords/merge-coords metadata)
                                           :handler-fn render-fn)))
   id))

;; ---- reg-view expansion helpers (JVM-only) -------------------------------
;;
;; Per Spec 004 §reg-view: defn-shape macro. The expander lives here as
;; plain CLJ helpers so the defmacro stays a one-line delegation and
;; CLJS test files can also exercise the helpers JVM-side. Per rf2-4lc9o
;; the helpers were consolidated here when the legacy
;; `re-frame.views-macros` import path was cut.

#?(:clj
   (defn parse-reg-view-args
     "Per Spec 004 §reg-view defn-shape. Parses (sym docstring? args body+)
     into {:sym :docstring :args :body}. Returns nil when shape is invalid."
     [more]
     (let [[a & rest1] more]
       (cond
         (vector? a)
         {:docstring nil :args a :body rest1}
         (and (string? a) (vector? (first rest1)))
         {:docstring a :args (first rest1) :body (next rest1)}
         :else nil))))

#?(:clj
   (defn describe-reg-view-bad-second-arg
     "Human-readable description of an invalid second-arg to reg-view, for
     the compile-error message. Per Spec 004 §reg-view compile-error
     contract: the rejected cases are Var-ref symbol, create-class call,
     and computed-fn call."
     [x]
     (cond
       (symbol? x)
       (str "a Var reference (" x ")")
       (and (seq? x) (symbol? (first x)) (= "create-class" (name (first x))))
       (str "a (" (first x) " …) call")
       (seq? x)
       (str "a (" (first x) " …) call")
       (nil? x)
       "nothing"
       :else
       (str "a " (some-> x type .getSimpleName) " — " (pr-str x)))))

#?(:clj
   (defn- reagent-slim-form-tag
     "Classify the user's body shape (Form-1 / Form-2) at compile time, when
     `day8/reagent-slim` is on the classpath. Returns a keyword form-tag or
     nil when the helper isn't available (other adapter, JVM-only build,
     etc.). Per rf2-yfbx the compile-time fold sits in the canonical
     `reg-view` macro — there is no separate `defview` macro. The runtime
     detection in `reagent2.impl.component/wrap-render` is the load-bearing
     correctness path; this tag is an additive optimisation that lets
     `wrap-render` short-circuit the classification cond on the hot path.

     Lookup goes through `requiring-resolve` so core does not statically
     depend on reagent-slim. UIx- or Helix-only builds resolve to nil
     here and emit the body untagged."
     [body]
     (when-let [classifier (try (requiring-resolve
                                  'reagent2.impl.component/classify-form-body)
                                (catch Exception _ nil))]
       (classifier body))))

#?(:clj
   (defn expand-reg-view
     "Build the expansion form for a reg-view macro call. `form-meta` is
     `(meta &form)` from the calling macro; `current-ns-sym` is
     `(ns-name *ns*)` at expansion time; `current-file` is `*file*` at
     expansion time. Both are captured in the expansion as literals so
     the emitted form does not reference `*ns*` / `*file*` at runtime —
     necessary for the CLJS path, where `cljs.core/*ns*` is nil at
     runtime.

     Per rf2-yfbx (folded into reg-view, not a separate `defview`): when
     reagent-slim is on the classpath, the body is structurally classified
     (Form-1 / Form-2) at expansion time and the wrapper fn is stamped
     with `^{:reagent2/form ...}` meta. The reagent-slim runtime path
     (`reagent2.impl.component/wrap-render`) reads this tag to skip the
     Form-1/2 cond on the hot path. The runtime detection remains
     load-bearing for correctness; this is an additive perf hint."
     [form-meta current-ns-sym current-file sym more]
     (let [parsed   (parse-reg-view-args more)
           sym-meta (or (meta sym) {})
           id-meta  (:rf/id sym-meta)
           id       (or id-meta (keyword (str current-ns-sym) (str sym)))
           ;; Anything on the symbol other than :rf/id is treated as slot
           ;; metadata (matches Clojure's defn idiom: ^{:doc "..."} sym).
           slot-meta (dissoc sym-meta :rf/id)]
       (when (nil? parsed)
         (throw (ex-info
                  (str "reg-view second argument must be an args vector "
                       "(defn-shape: (reg-view sym [args] body)). Got "
                       (describe-reg-view-bad-second-arg (first more))
                       ". For runtime registration, use "
                       "(re-frame.core/reg-view* :id render-fn).")
                  {:sym sym :got (first more) :args-after-sym (vec more)})))
       (let [{:keys [docstring args body]} parsed
             form-tag (reagent-slim-form-tag body)
             def-form (if docstring
                        `(def ~sym ~docstring (re-frame.core/view ~id))
                        `(def ~sym (re-frame.core/view ~id)))
             full-slot-meta (cond-> slot-meta
                              docstring (assoc :doc docstring)
                              form-tag  (assoc :reagent2/form form-tag))
             ;; The wrapper fn carries the form-tag as its own meta too, so
             ;; renderers that take the fn alone (e.g. directly via
             ;; (rf/view :id)) can still read it without round-tripping
             ;; through the registry slot.
             fn-form  (if form-tag
                        (with-meta
                          `(fn ~sym ~args
                             (let [~'dispatch  (re-frame.core/dispatcher)
                                   ~'subscribe (re-frame.core/subscriber)]
                               ~@body))
                          {:reagent2/form form-tag})
                        `(fn ~sym ~args
                           (let [~'dispatch  (re-frame.core/dispatcher)
                                 ~'subscribe (re-frame.core/subscriber)]
                             ~@body)))]
         ;; Per Conventions §`reg-*` return-value convention: every `reg-*`
         ;; macro returns its primary id. The auto-def is a side effect; the
         ;; macro's terminal value is `id`. (Without the trailing `id` the
         ;; `def` would be the last form and the macro would return the Var,
         ;; not the id — breaking the uniform return-value contract.)
         ;; Per rf2-hzos.
         `(do
            (binding [re-frame.source-coords/*pending-coords*
                      ~(source-coords/coords-form form-meta current-file current-ns-sym)]
              (re-frame.core/reg-view* ~id
                ~full-slot-meta
                ~fn-form))
            ~def-form
            ~id)))))

#?(:clj
   (defmacro reg-view
     "Register a view as a defn-shape macro. Auto-derives id from
     `(keyword (str *ns*) (str sym))` (override via `^{:rf/id :id}` meta on
     sym), auto-injects lexical `dispatch` / `subscribe` bound to the
     surrounding frame, defs the symbol and registers under the id.
     For runtime registration with computed ids or non-defn bodies, use
     `reg-view*`. Per Spec 004 §reg-view."
     {:arglists '([sym args body+] [sym docstring args body+])}
     [sym & more]
     ;; See rf2-xnym rationale at the top of the registration section for
     ;; the metadata-free ns-symbol.
     (expand-reg-view (meta &form) (symbol (str (ns-name *ns*))) *file* sym more)))

(defn view
  "Runtime-lookup handle for a registered view. Returns the registered
  render fn (or nil if not registered) — call with the view's invocation
  args to yield the hiccup tree. Per Spec 004 §Calling a registered view."
  [id]
  (when-let [meta (registrar/lookup :view id)]
    (:handler-fn meta)))

;; ---- SSR re-exports (Spec 011, rf2-uo7v) ---------------------------------
;;
;; The fn-form wrappers live in re-frame.core-ssr; this namespace
;; re-exports them so users still write `rf/render-to-string` etc. The
;; `reg-error-projector` defmacro is below in the frame-management
;; section because it shares the source-coord-capture pattern.

(def render-to-string rf-ssr/render-to-string)
(def render-tree-hash rf-ssr/render-tree-hash)
(def project-error    rf-ssr/project-error)

;; ---- frame management ----------------------------------------------------

(def make-frame      frame/make-frame)
(def reset-frame     frame/reset-frame!)
(def destroy-frame   frame/destroy-frame!)

;; ---- flows / routing / schemas / machines: reg-* macros ------------------
;;
;; Each macro below uses `with-coords-form` to bind source-coords and
;; then delegates to the fn-form wrapper in the matching
;; `re-frame.core.X` sub-namespace (which performs the late-bind
;; lookup). This pattern keeps the source-coord capture site in
;; `re-frame.core` (so `(meta &form)` is the user's call site) and
;; the late-bind glue in the sub-namespace.

#?(:clj
   (defmacro reg-flow
     "Register a flow. Captures source-coords (Spec 001) at this call site.
     Implementation ships in `day8/re-frame2-flows` (rf2-tfw3); apps must
     add the artefact and require `re-frame.flows` at boot. See
     `re-frame.core-flows/reg-flow` for the full signature."
     [& args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(rf-flows/reg-flow ~@args))))

#?(:clj
   (defmacro reg-route
     "Register a route under `id`. `metadata` is a map keyed at minimum on
     `:path` (URL pattern, Spec 012 §Pattern syntax). Captures
     source-coords (Spec 001) at this call site. Implementation ships in
     `day8/re-frame2-routing` (rf2-k682); apps must add the artefact and
     require `re-frame.routing` at boot. See
     `re-frame.core-routing/reg-route` for the full signature."
     [id metadata]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(rf-routing/reg-route ~id ~metadata))))

#?(:clj
   (defmacro reg-app-schema
     "Register a Malli schema at a path inside app-db (frame-scoped per
     Spec 010). Captures source-coords (Spec 001) at this call site.
     Implementation ships in `day8/re-frame2-schemas` (rf2-p7va). See
     `re-frame.core-schemas/reg-app-schema` for the full signature."
     {:arglists '([path schema] [path schema opts])}
     [path schema & [opts]]
     (let [opts' (or opts {})]
       (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                         `(rf-schemas/reg-app-schema ~path ~schema ~opts')))))

#?(:clj
   (defmacro reg-app-schemas
     "Bulk-register a `{path -> schema}` map against the active frame (or
     the `:frame` opt). Plural form of `reg-app-schema`. Captures
     source-coords (Spec 001) at this call site. Implementation ships in
     `day8/re-frame2-schemas` (rf2-p7va). See
     `re-frame.core-schemas/reg-app-schemas` for the full signature."
     {:arglists '([path->schema] [path->schema opts])}
     [path->schema & [opts]]
     (let [opts' (or opts {})]
       (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                         `(rf-schemas/reg-app-schemas ~path->schema ~opts')))))

#?(:clj
   (defmacro reg-machine
     "Register a machine as an event handler. Captures source-coords
     (Spec 001) at this call site plus a per-element coord index under
     `:rf.machine/source-coords` (Spec 005 §Source-coord stamping;
     dev-only — DCE'd under `goog.DEBUG=false`). Implementation ships in
     `day8/re-frame2-machines` (rf2-xbtj). For runtime registration use
     `reg-machine*`."
     [machine-id machine]
     (let [ns-sym         (symbol (str (ns-name *ns*)))
           file           *file*
           ;; Walk the literal spec form at compile time. When `machine`
           ;; is a non-map (a symbol bound to a value at runtime) the
           ;; walker returns {} — tools fall back to the call-site coords
           ;; on the top-level handler-meta.
           per-el-coords  (source-coords/walk-machine-spec machine ns-sym file)
           ;; Build a syntax-quote-safe literal form for the coord index.
           ;; Symbols inside `:ns` need to be quoted (otherwise the syntax
           ;; quote splice would try to namespace-resolve them at compile
           ;; time and the compiler would throw ClassNotFoundException for
           ;; the consumer's ns).
           per-el-form    (into {}
                                (map (fn [[path coords]]
                                       [path
                                        (cond-> {:ns (list 'quote (:ns coords))}
                                          (:file coords)   (assoc :file (:file coords))
                                          (:line coords)   (assoc :line (:line coords))
                                          (:column coords) (assoc :column (:column coords)))])
                                     per-el-coords))
           machine-sym    (gensym "machine__")]
       ;; If the walker returned no entries — i.e. the spec form was a
       ;; symbol / non-literal map / had no positional metadata — skip the
       ;; per-element stamping branch entirely. Avoids polluting the
       ;; registered spec with an empty `:rf.machine/source-coords` key
       ;; that user code might compare against.
       (if (empty? per-el-coords)
         (with-coords-form (meta &form) file ns-sym
                           `(rf-machines/reg-machine ~machine-id ~machine))
         (with-coords-form (meta &form) file ns-sym
                           `(let [~machine-sym ~machine
                                  ;; Per-element source-coord stamping (rf2-8bp3). The literal
                                  ;; index is reachable only inside this `interop/debug-enabled?`
                                  ;; gate; under :advanced + goog.DEBUG=false the closure compiler
                                  ;; folds the gate to false and the entire literal DCE's.
                                  stamped# (if interop/debug-enabled?
                                             (assoc ~machine-sym
                                                    :rf.machine/source-coords
                                                    ~per-el-form)
                                             ~machine-sym)]
                              (rf-machines/reg-machine ~machine-id stamped#)))))))

#?(:cljs
   (do
     (def reg-flow        rf-flows/reg-flow)
     (def reg-route       rf-routing/reg-route)
     (def reg-app-schema  rf-schemas/reg-app-schema)
     (def reg-app-schemas rf-schemas/reg-app-schemas)
     (def reg-machine*    rf-machines/reg-machine*)))

;; Plain-fn surface for the JVM. Used by code-gen pipelines and the
;; conformance corpus when registering machines without a literal spec
;; form. Per Spec 005 §reg-machine vs reg-machine* (rf2-8bp3).
#?(:clj
   (def reg-machine* rf-machines/reg-machine*))

;; reg-error-projector lives in re-frame.ssr so the registry kind ships
;; with its default :rf.ssr/default-error-projector. Per rf2-uo7v ssr
;; ships in day8/re-frame2-ssr; the producing fn is looked up through
;; the late-bind hook table so core never statically requires
;; re-frame.ssr. The fn-form delegate is `rf-ssr/-reg-error-projector`.
;;
;; The local `-reg-error-projector` def below is `^:private` (per the
;; leading-dash convention — rf2-ubeyt). The JVM macro emits
;; `rf-ssr/-reg-error-projector` directly so it never depends on the
;; local alias being visible at the user's compile site. The CLJS branch
;; (line below) reuses the private alias within this namespace.

(def ^:private -reg-error-projector rf-ssr/-reg-error-projector)

#?(:clj
   (defmacro reg-error-projector
     "Register an error projector — `(trace-event) -> public-error-map`.
     Frames opt in via the `:ssr` config's `:public-error-id` key.
     Captures source-coords (Spec 001) at this call site. Per Spec 011
     §Server error projection."
     [& args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(rf-ssr/-reg-error-projector ~@args))))

#?(:cljs
   (def reg-error-projector -reg-error-projector))

;; ---- flows / schemas — plain-fn re-exports -------------------------------

(def clear-flow             rf-flows/clear-flow)

(def app-schema-at          rf-schemas/app-schema-at)
(def app-schemas            rf-schemas/app-schemas)
(def app-schemas-digest     rf-schemas/app-schemas-digest)
(def set-schema-validator!  rf-schemas/set-schema-validator!)
(def set-schema-explainer!  rf-schemas/set-schema-explainer!)

;; ---- clearing -------------------------------------------------------------

(def clear-event events/clear-event)
(def clear-sub   subs/clear-sub)
(def clear-fx    fx/clear-fx)
(def clear-subscription-cache! subs/clear-subscription-cache!)

;; ---- dispatch and subscribe -----------------------------------------------
;;
;; Per rf2-ts1a — call-site source-coords. Each user-facing surface ships
;; as a macro + `*`-fn pair (Q1=C: existing-name macro, fn-form gets the
;; `*` suffix; same convention as `reg-view` / `reg-view*` and `reg-
;; machine` / `reg-machine*` per Conventions §`*`-suffix naming).
;;
;;   `dispatch`       — macro; captures `(meta &form)` and routes through
;;                       `dispatch*` with the call-site stamped on `opts`.
;;   `dispatch*`      — runtime-callable fn (delegates to `router/dispatch!`).
;;                       Use this in HoF contexts (`(map dispatch* xs)`) or
;;                       when the surface is reached programmatically.
;;
;; Same shape for `dispatch-sync` / `dispatch-sync*`, `subscribe` /
;; `subscribe*`, `inject-cofx` / `inject-cofx*`.
;;
;; The compile-time `:rf.trace/call-site` map elides under `goog.DEBUG=
;; false` (Q3=B dev-only elision) — the macro expansion is
;; `(if interop/debug-enabled? <stamping-call> <plain-call>)` so the
;; entire stamping branch (including the literal map) DCE's. emit-error!
;; reads `trace/*current-call-site*` and attaches the value as
;; `:rf.trace/call-site` (Q2=A flat sibling of `:rf.trace/trigger-handler`)
;; on the emitted event.

(def dispatch*       router/dispatch!)
(def dispatch-sync*  router/dispatch-sync!)
(def subscribe-value subs/subscribe-value)
(def unsubscribe     subs/unsubscribe)
(def compute-sub     subs/compute-sub)

(defn subscribe*
  "Runtime-callable fn form of `subscribe` (HoF / programmatic callers).
  The macro form captures `(meta &form)` and binds it around this fn.
  Arities mirror `re-frame.subs/subscribe`."
  ([query-v]            (subs/subscribe query-v))
  ([frame-id query-v]   (subs/subscribe frame-id query-v)))

(defn inject-cofx*
  "Runtime-callable fn form of `inject-cofx` (HoF / programmatic callers).
  Arities: `(inject-cofx* :id)`, `(inject-cofx* :id value)`,
  `(inject-cofx* :id value call-site)`. The macro form routes through the
  3-arity with a `cofx/no-value` sentinel."
  ([cofx-id]                      (cofx/inject-cofx cofx-id))
  ([cofx-id value]                (cofx/inject-cofx cofx-id value nil))
  ([cofx-id value call-site]      (cofx/inject-cofx cofx-id value call-site)))

;; ---- call-site capturing macros (rf2-ts1a) -------------------------------
;;
;; Each macro captures `(meta &form)` and `*ns*` / `*file*` at expansion
;; time and emits an `(if interop/debug-enabled? <stamping> <plain>)`
;; branch around the matching `*`-fn call. Under :advanced +
;; `goog.DEBUG=false` the closure compiler constant-folds the gate to
;; false and the entire stamping branch — including the literal
;; `:rf.trace/call-site` map — DCE's. Per Q3=B dev-only elision.
;;
;; The `coords-form` helper is reused from `re-frame.source-coords` so
;; the literal map carries the same `{:ns :file :line :column}` shape as
;; registration-site coords (rf2-mdjp `:file` resolution rules apply
;; identically — form-meta `:file` wins, `"NO_SOURCE_PATH"` sentinel is
;; dropped).

#?(:clj
   (defn ^:private call-site-form
     "Build the literal call-site cond-> map for a callable's macro form.
     Returns the unguarded form; callers wrap in their own
     `(if interop/debug-enabled? ... ...)` so the entire branch (binding
     scope or opts-key assoc) DCEs under `goog.DEBUG=false`."
     [form-meta ns-sym file]
     (source-coords/coords-form form-meta file ns-sym)))

#?(:clj
   (defmacro dispatch
     "Enqueue `event-vec` on the target frame's router; returns nil
     immediately, BEFORE the handler runs. Captures call-site coords
     (rf2-ts1a) for error-trace attribution. For HoF / programmatic use
     call `dispatch*`. Per Spec 002 §Routing."
     ([event-vec]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (re-frame.core/dispatch* ~event-vec {:rf.trace/call-site ~cs-form})
           (re-frame.core/dispatch* ~event-vec))))
     ([event-vec opts]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (re-frame.core/dispatch* ~event-vec
                                    (assoc ~opts :rf.trace/call-site ~cs-form))
           (re-frame.core/dispatch* ~event-vec ~opts))))))

#?(:clj
   (defmacro dispatch-sync
     "Run `event-vec` end-to-end synchronously; the router drains to fixed
     point. For tests / REPL / bootstrap only — never call from inside a
     running event handler (raises `:rf.error/dispatch-sync-in-handler`).
     Captures call-site coords (rf2-ts1a). For HoF / programmatic use call
     `dispatch-sync*`. Per Spec 002 §dispatch-sync."
     ([event-vec]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (re-frame.core/dispatch-sync* ~event-vec {:rf.trace/call-site ~cs-form})
           (re-frame.core/dispatch-sync* ~event-vec))))
     ([event-vec opts]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (re-frame.core/dispatch-sync* ~event-vec
                                         (assoc ~opts :rf.trace/call-site ~cs-form))
           (re-frame.core/dispatch-sync* ~event-vec ~opts))))))

#?(:clj
   (defmacro subscribe
     "Return a reaction whose value is the registered sub's current output
     for `query-v` (`[sub-id & args]`); deref to read. 2-arity targets an
     explicit frame, otherwise resolves via `current-frame`. Use
     `subscribe-value` for a one-shot read; use `subscribe*` for HoF /
     programmatic callers. Captures call-site coords (rf2-ts1a). Per
     Spec 006 §Lookup algorithm."
     ([query-v]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (binding [re-frame.trace/*current-call-site* ~cs-form]
             (re-frame.core/subscribe* ~query-v))
           (re-frame.core/subscribe* ~query-v))))
     ([frame-id query-v]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (binding [re-frame.trace/*current-call-site* ~cs-form]
             (re-frame.core/subscribe* ~frame-id ~query-v))
           (re-frame.core/subscribe* ~frame-id ~query-v))))))

#?(:clj
   (defmacro inject-cofx
     "Used as an interceptor in the interceptor-vector of `reg-event-*`.
     Builds a `:before`-only interceptor that runs the cofx registered
     under `cofx-id` and merges its result into the handler's `:coeffects`.
     2-arity `(inject-cofx :id value)` passes a per-call value. Captures
     call-site coords (rf2-ts1a). For HoF / programmatic use call
     `inject-cofx*`. See `re-frame.cofx/inject-cofx` for the full signature."
     ([cofx-id]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        ;; Route through the 3-arity with the `cofx/no-value` sentinel
        ;; so the call-site can ride. The 3-arity branch in
        ;; cofx/inject-cofx detects the sentinel via identical? and
        ;; takes the no-value path through the cofx fn body.
        `(if re-frame.interop/debug-enabled?
           (re-frame.core/inject-cofx* ~cofx-id re-frame.cofx/no-value ~cs-form)
           (re-frame.core/inject-cofx* ~cofx-id))))
     ([cofx-id value]
      (let [cs-form (call-site-form (meta &form) (symbol (str (ns-name *ns*))) *file*)]
        `(if re-frame.interop/debug-enabled?
           (re-frame.core/inject-cofx* ~cofx-id ~value ~cs-form)
           (re-frame.core/inject-cofx* ~cofx-id ~value))))))

;; ---- frame-aware closures (runtime side) ---------------------------------
;;
;; Per Spec 002 §View ergonomics and Spec 004 §Affordance for plain fns.
;; *current-frame* is the JVM-and-CLJS-shared dynamic var that with-frame
;; binds. CLJS adds a React-context bridge in re-frame.views; this lets
;; plain fns (tests, JVM SSR, REPL) capture-and-bind a frame without
;; Reagent.

;; *current-frame* and current-frame live in re-frame.frame so the
;; sub / dispatch defaults can read them without a circular require.
;; Re-export here for the public API surface.
;;
;; Per rf2-d4sf the public `current-frame` consults the
;; `:adapter/current-frame` late-bind hook on CLJS so the React-context
;; tier of the resolution chain is live at this call site (and at every
;; user-facing surface that flows through `(dispatcher)` / `(subscriber)` /
;; `bound-fn` capture). The JVM build falls through to
;; `frame/current-frame` (dynamic-var → :rf/default).
(defn current-frame
  "Return the active frame at the call site. Resolution chain: dynamic
  var -> React context (CLJS only, via `:adapter/current-frame` late-bind
  hook) -> `:rf/default`. Per Spec 002 §Reading the frame from React
  context."
  []
  #?(:cljs (if-let [f (late-bind/get-fn :adapter/current-frame)]
             (f)
             (frame/current-frame))
     :clj  (frame/current-frame)))

(defn dispatcher
  "Return a fn that dispatches under the current frame, captured at call
  time so closures need not thread it. Per Spec 004 §Affordance for plain
  fns:
    (let [d (rf/dispatcher)] [:button {:on-click #(d [:inc])} ...])"
  []
  (let [frame (current-frame)]
    (fn dispatch-fn
      ([event]      (dispatch* event {:frame frame}))
      ([event opts] (dispatch* event (assoc opts :frame frame))))))

(defn subscriber
  "Return a fn that subscribes under the current frame, captured at call
  time. Sibling of `dispatcher`. Per Spec 004 §Affordance for plain fns."
  []
  (let [frame (current-frame)]
    (fn subscribe-fn
      [query-v]
      (subs/subscribe frame query-v))))

;; with-frame is a macro (per Spec 002 §with-frame and spec/API.md row 74).
;; Two shapes:
;;
;;   Shape 1 — bare keyword (operate on an existing frame):
;;     (with-frame :scratch body...)
;;     => (binding [frame/*current-frame* :scratch] body...)
;;
;;   Shape 2 — let-binding (create, use, destroy):
;;     (with-frame [f (make-frame opts)] body...)
;;     => (let [f (make-frame opts)]
;;          (try
;;            (binding [frame/*current-frame* f] body...)
;;            (finally (destroy-frame f))))
;;
;; The discriminator is the first argument: a vector triggers Shape 2;
;; anything else is Shape 1. The fn-form `(with-frame frame-id thunk)`
;; is no longer supported — callers wanting a thunk-style wrapper should
;; write `(with-frame :foo (thunk))` (the thunk call sits inside the
;; macro body, identical semantics) or call `binding` directly.

#?(:clj
   (defmacro with-frame
     "Run `body` with `*current-frame*` bound to the given frame-id.
     Two shapes — first arg is the discriminator:
       (with-frame :keyword body+)        pin to an existing frame-id;
       (with-frame [sym expr] body+)      eval expr, bind, run, destroy.
     For async closures after body returns, capture via `bound-fn` /
     `dispatcher` / `subscriber`. Per Spec 002 §with-frame."
     {:arglists '([frame-id body+] [[sym expr] body+])}
     [bindings & body]
     (cond
       (and (vector? bindings) (= 2 (count bindings)))
       (let [[sym expr] bindings]
         `(let [~sym ~expr]
            (try
              (binding [frame/*current-frame* ~sym]
                ~@body)
              (finally
                (destroy-frame ~sym)))))

       :else
       `(binding [frame/*current-frame* ~bindings]
          ~@body))))

;; Note: the `bound-dispatcher` / `bound-subscriber` aliases were cut
;; under rf2-knz3l — they were pure aliases for `dispatcher` /
;; `subscriber` whose `bound-` prefix added redundant noise (the verb-
;; form names already imply capture-at-call-time semantics). Callers
;; that need an async-safe dispatch / subscribe closure call
;; `(rf/dispatcher)` / `(rf/subscriber)` directly.

;; `bound-fn` (below) is the macro-form sibling of `dispatcher` /
;; `subscriber`: captures `*current-frame*` at definition time and
;; restores it at call time. Useful for closures called from async
;; boundaries (timers, http-handlers, promises) where the dynamic-var
;; binding has been lost. Per Spec 002 §bound-fn.

#?(:clj
   (defmacro bound-fn
     "Return a fn that captures the current frame and re-binds
     `*current-frame*` inside its body. Per Spec 002 §bound-fn."
     [argv & body]
     (let [frame-sym (gensym "frame__")]
       `(let [~frame-sym (re-frame.core/current-frame)]
          (fn ~argv
            (binding [re-frame.frame/*current-frame* ~frame-sym]
              ~@body))))))

#?(:clj
   (defmacro with-fx-overrides
     "Bind a per-call `:fx-overrides` map for `body`'s lexical scope —
     test-support sugar over `(rf/dispatch ev {:fx-overrides {...}})`.
     Precedence: per-call opt > lexical `with-fx-overrides` > per-frame
     `:fx-overrides`. Composes with `with-frame`. Per Spec 002
     §`:fx-overrides`."
     [overrides-map & body]
     `(binding [re-frame.router/*fx-overrides* ~overrides-map]
        ~@body)))

;; ---- view ergonomics (CLJS only) ------------------------------------------
;; reg-view (the macro) lives above as a JVM/CLJS-shared `#?(:clj defmacro)`.
;; frame-provider is a Reagent component re-exported here so `rf/frame-provider`
;; is the canonical user-facing surface (per Spec 002 §What `frame-provider` is
;; and the API.md table); it lives in re-frame.views to keep React/Reagent off
;; the JVM load path.

;; User-facing component scoping a frame keyword to its subtree. Wraps
;; children in the shared frame Context Provider so descendants resolve
;; to the named frame via `(rf/dispatcher)` / `(rf/subscriber)` /
;; `reg-view`-registered components. Per Spec 002 §What `frame-provider`
;; is; per rf2-zde3z the canonical phrasing lives on the Reagent /
;; UIx / Helix implementations themselves — see
;; `re-frame.views.provider/frame-provider` for the Reagent variant
;; documented in full.
#?(:cljs (def frame-provider views/frame-provider))

;; ---- routing helpers ------------------------------------------------------

(def match-url rf-routing/match-url)
(def route-url rf-routing/route-url)
(def route-link rf-routing/route-link)

;; ---- machine helpers ------------------------------------------------------

(def create-machine-handler rf-machines/create-machine-handler)
(def machine-transition     rf-machines/machine-transition)
(def machines               rf-machines/machines)
(def machine-meta           rf-machines/machine-meta)
(def machine-by-system-id   rf-machines/machine-by-system-id)
(def dispatch-to-system     rf-machines/dispatch-to-system)
(def sub-machine            rf-machines/sub-machine)
(def has-tag?               rf-machines/has-tag?)

;; ---- introspection (per Spec 002 §The public registrar query API) -------

(def handlers     registrar/handlers)
(def handler-meta registrar/handler-meta)
(def handler-ids  registrar/ids)
(def registry-summary registrar/all-kinds-with-counts)
(def frame-ids    frame/frame-ids)
(def frame-meta   frame/frame-meta)
(defn get-frame-db
  "Return the current `app-db` value (plain map) for the named frame, or
  `nil` if not registered. Value-form accessor (no deref). For path
  reads use `snapshot-of`. Per Spec 002 §The public registrar query API."
  [frame-id]
  (frame/frame-app-db-value frame-id))

(defn snapshot-of
  "Return the value at `path` in a frame's app-db — convenience wrapper
  over `(get-in (rf/get-frame-db frame-id) path)`. Frame resolution:
  `(:frame opts)` if supplied, else `(current-frame)`. Returns `nil` if
  the frame is missing or the path resolves to nothing. Per Spec 002
  §The public registrar query API."
  ([path] (snapshot-of path nil))
  ([path opts]
   (let [frame-id (or (:frame opts) (current-frame))]
     (get-in (frame/frame-app-db-value frame-id) path))))

;; sub-cache is CLJS-only — the reactive cache is materialised by the
;; substrate adapter and only carries a useful :value on the JS side.
;; The JVM definition returns nil so the symbol resolves under both
;; targets. Per Spec 002 §The public registrar query API.
(defn sub-cache
  "Inspect a frame's runtime sub-cache — CLJS-only, returns
  `{query-v {:value v :ref-count n}}`. JVM returns `nil` (cache has no
  reaction values). No-arg form uses the active frame. Per Spec 002
  §The public registrar query API."
  ([] (sub-cache (current-frame)))
  ([frame-id]
   (subs/sub-cache-snapshot frame-id)))

;; sub-topology is the **static** counterpart to sub-cache — pure data
;; derived from the registrar at registration time, JVM-runnable. Per
;; Spec 002 §The public registrar query API and Spec 006 §Subscription
;; topology vs subscription tracking.
(def sub-topology subs/sub-topology)

;; ---- interceptors ---------------------------------------------------------

(def ->interceptor   interceptor/->interceptor)
(def get-coeffect    interceptor/get-coeffect)
(def assoc-coeffect  interceptor/assoc-coeffect)
(def get-effect      interceptor/get-effect)
(def assoc-effect    interceptor/assoc-effect)
(def inject-cofx     cofx/inject-cofx)
(def path            std-interceptors/path)
(def unwrap          std-interceptors/unwrap)

;; ---- privacy (Spec 009 §Privacy; rf2-isdwf) ------------------------------
;;
;; The `with-redacted` positional interceptor and the framework's
;; published `sensitive?` predicate. Per Spec 009 lines 1149-1268:
;; `:sensitive?` is the filter-out signal (top-level on the trace
;; event); `with-redacted` is the in-place payload scrub. The two
;; compose — a handler carrying both `:sensitive? true` in its
;; metadata-map AND `with-redacted` in its positional chain emits
;; trace events that are BOTH stamped `:sensitive? true` AND carry
;; redacted payloads. See API.md and Spec 009.

(def with-redacted   privacy/with-redacted)
(def sensitive?      trace/sensitive?)

;; ---- :spec/validate-at-boundary (rf2-r2uh) -------------------------------
;;
;; Per Spec 010 §Production builds — the production-side validation
;; interceptor. Re-uses the handler's existing `:spec` metadata; no-op
;; in dev (step-1 already validates), no-op when no validator is
;; registered (per rf2-froe's nil-validator opt-out), no-op when the
;; handler carries no `:spec` (and emits `:rf.warning/boundary-without-spec`
;; once to flag the misconfiguration). On failure, skips the handler
;; and emits `:rf.error/schema-validation-failure :where :event` —
;; same recovery path as dev-mode step-1 failures.
;;
;; Users can write either `[rf/validate-at-boundary]` (this re-export)
;; or import `re-frame.spec` directly and write `[spec/validate-at-boundary]`
;; — both refer to the same value.

(def validate-at-boundary spec/validate-at-boundary)

;; ---- trace ----------------------------------------------------------------

(def register-trace-cb!  trace/register-trace-cb!)
(def remove-trace-cb!    trace/remove-trace-cb!)
(def emit-trace!         trace/emit!)
(def trace-buffer        trace/trace-buffer)
(def clear-trace-buffer! trace/clear-trace-buffer!)

;; ---- size-elision wire-boundary walker (rf2-v9tw2; Spec 009) -------------
;;
;; Per [API.md §Size-elision wire-boundary walker]. The walker is the
;; single normative emission site for the `:rf/redacted` privacy
;; sentinel and the `:rf.size/large-elided` marker; per-tool
;; reimplementation is prohibited. The registry of declared paths
;; lives at `[:rf/elision]` in every frame's app-db (per
;; [Conventions §Reserved app-db keys]) so declarations survive
;; restore-epoch and persist across hot reload. Surface:
;;
;;   rf/elide-wire-value            — the walker
;;   rf/declare-large-path!         — REPL/boot wrapper around :rf.size/declare-large
;;   rf/clear-large-path!           — REPL/boot wrapper around :rf.size/clear
;;   rf/populate-elision-from-schemas!
;;                                  — schema-driven boot population

(def elide-wire-value             elision/elide-wire-value)
(def declare-large-path!          elision/declare-large-path!)
(def clear-large-path!            elision/clear-large-path!)
(def populate-elision-from-schemas! elision/populate-elision-from-schemas!)
(def elision-declarations         elision/declarations)
(def elision-runtime-flagged      elision/runtime-flagged)

;; Per rf2-wvzgd: cascade projection. Pure-data fn lifted from Story's
;; trace panel; consumed by Story (`tools/story/src/re_frame/story/ui/
;; trace.cljs`), and (when impl lands) Causa (rf2-5aw5v) and pair2's
;; `cascade-of`. CLJC for JVM-side testability.
(def group-cascades      trace-projection/group-cascades)
(def domino-bucket       trace-projection/domino-bucket)

;; ---- epoch history (Tool-Pair §Time-travel) ------------------------------

(def epoch-history     rf-epoch/epoch-history)
(def restore-epoch     rf-epoch/restore-epoch)
(def register-epoch-cb! rf-epoch/register-epoch-cb!)
(def remove-epoch-cb!   rf-epoch/remove-epoch-cb!)
(def reset-frame-db!   rf-epoch/reset-frame-db!)

;; ---- Spec 014 — :rf.http/managed -----------------------------------------

(def install-managed-request-stubs!   rf-http/install-managed-request-stubs!)
(def uninstall-managed-request-stubs! rf-http/uninstall-managed-request-stubs!)
(def with-managed-request-stubs*      rf-http/with-managed-request-stubs*)
(def reg-http-interceptor             rf-http/reg-http-interceptor)
(def clear-http-interceptor           rf-http/clear-http-interceptor)

#?(:clj
   (defmacro with-managed-request-stubs
     "Install stubs, run body, uninstall. `stubs` is
     `{[method url] {:reply <:ok|:failure>}}`. Implementation ships in
     `day8/re-frame2-http` (rf2-5kpd). Per Spec 014 §Testing."
     [stubs & body]
     `(with-managed-request-stubs* ~stubs (fn [] ~@body))))

(defn configure
  "Configure a process-level runtime knob. v1 keys:
    :epoch-history {:depth N}                       ring depth (default 50; 0 disables)
    :trace-buffer  {:depth N}                       ring depth (default 200; 0 disables)
    :sub-cache     {:grace-period-ms N}             dispose grace (default 50ms)
    :elision       {:rf.size/threshold-bytes N}     runtime auto-detect threshold (default 16384; 0 disables)
  Unknown keys silently no-op. Per-frame settings live on frame metadata.
  Per Tool-Pair §How AI tools attach."
  [knob opts]
  (case knob
    ;; :epoch-history is published by the `day8/re-frame2-epoch`
    ;; artefact (rf2-lt4e). When the artefact is not on the classpath
    ;; the configure call is a silent no-op — the epoch surface is
    ;; dev-tier, so an absent artefact means recording is already off
    ;; and tuning the depth has no effect.
    :epoch-history (when-let [f (late-bind/get-fn :epoch/configure!)]
                     (f opts))
    :trace-buffer  (trace/configure-trace-buffer! opts)
    :sub-cache     (subs/configure! opts)
    :elision       (elision/configure-elision! opts)
    nil))

;; ---- substrate adapter ----------------------------------------------------

(def install-adapter!    adapter/install-adapter!)
(def dispose-adapter!    adapter/dispose-adapter!)
(def current-adapter     adapter/current-adapter)
(def current-adapter-spec adapter/current-adapter-spec)

;; ---- boot -----------------------------------------------------------------

(defn- bad-init-arg!
  "Raise `:rf.error/no-adapter-specified` with a consistent reason
  string. Factored out of `init!`'s nil-check and not-map-check to
  avoid prose drift between the two branches."
  [received]
  (throw (ex-info ":rf.error/no-adapter-specified"
                  (cond-> {:where    'rf/init!
                           :expected "adapter spec map"
                           :recovery :no-recovery
                           :reason   "rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry. Require the adapter ns and pass its `adapter` Var: (rf/init! reagent/adapter). Per rf2-agql the default-adapter registry was removed."}
                    (some? received) (assoc :received received)))))

(defn init!
  "Idempotent boot — installs a substrate adapter and ensures the
  `:rf/default` frame exists. Pass the adapter spec map directly (no
  default-adapter registry; rf2-agql):
    (require '[re-frame.adapter.reagent :as reagent])
    (rf/init! reagent/adapter)
  Non-map / nil raises `:rf.error/no-adapter-specified`. Per Spec 006
  §Adapter selection at boot."
  [adapter-map]
  (cond
    (nil? adapter-map)        (bad-init-arg! nil)
    (not (map? adapter-map))  (bad-init-arg! adapter-map)
    :else
    (do
      (when-not (adapter/current-adapter)
        (adapter/install-adapter! adapter-map))
      (frame/ensure-default-frame!)
      nil)))

;; ---- self-registration of framework subs ----------------------------------
;;
;; The `:rf/route` / `:rf.route/{id,params,query,transition,error}`
;; reg-subs live in `re-frame.routing` and register at that namespace's
;; load time, so the smoke-test fixture's `require :reload` recovers them
;; after `registrar/clear-all!`. Per rf2-k682 — routing ships in
;; `day8/re-frame2-routing`.
;;
;; The `:rf/machine` reg-sub lives in `re-frame.machines` and registers
;; at that namespace's load time, similarly. Per Spec 005 §Subscribing
;; to machines via sub-machine and rf2-xbtj.
