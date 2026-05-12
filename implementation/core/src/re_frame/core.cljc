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
            [re-frame.spec :as spec]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]
            [re-frame.trace.projection :as trace-projection]
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
  #?(:cljs (:require-macros [re-frame.core :refer [reg-view with-frame bound-fn]])))

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
     "Register a (db, event) -> new-db handler. Per Spec 001 the
     metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(events/reg-event-db ~id ~@args))))

#?(:clj
   (defmacro reg-event-fx
     "Register a (cofx, event) -> effects-map handler. Per Spec 001
     the metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(events/reg-event-fx ~id ~@args))))

#?(:clj
   (defmacro reg-event-ctx
     "Register a context-handler. Per Spec 001 the metadata stamped
     onto the registry slot includes :ns / :line / :file captured at
     this call site."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(events/reg-event-ctx ~id ~@args))))

#?(:clj
   (defmacro reg-sub
     "Register a subscription. Per Spec 001 the metadata stamped onto
     the registry slot includes :ns / :line / :file captured at this
     call site."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(subs/reg-sub ~id ~@args))))

#?(:clj
   (defmacro reg-fx
     "Register an fx handler. Per Spec 001 the metadata stamped onto
     the registry slot includes :ns / :line / :file captured at this
     call site."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(fx/reg-fx ~id ~@args))))

#?(:clj
   (defmacro reg-cofx
     "Register a coeffect handler. Per Spec 001 the metadata stamped
     onto the registry slot includes :ns / :line / :file captured at
     this call site."
     [id & args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(cofx/reg-cofx ~id ~@args))))

#?(:clj
   (defmacro reg-frame
     "Register a frame. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site."
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
  "Plain-fn surface for view registration. Per Spec 004 §reg-view*.

  Takes an id keyword and a render fn of any shape. No auto-def, no
  auto-inject of `dispatch`/`subscribe`, no compile-time check on the
  shape of `render-fn`. Use this when the registration is computed at
  runtime: dynamic ids, library generation, registration without a
  Var, or when the body doesn't fit the literal-fn contract enforced
  by the `reg-view` macro (Reagent Form-3 / `create-class`).

  On CLJS this delegates to `re-frame.views/reg-view*` so the
  registered fn is wrapped with the React-context hook used to
  resolve the surrounding frame at render time.

  An optional metadata map may be supplied; merged with any pending
  source-coords captured by the `reg-view` macro at the call site
  (per Spec 001 §Source-coordinate capture)."
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
     "Register a view as a defn-shape macro. Per Spec 004 §reg-view.

     Shape:

       (reg-view sym [args] body+)
       (reg-view sym docstring [args] body+)
       (reg-view ^{:rf/id :explicit/id} sym [args] body+)

     Behavior:
     - Auto-derives the id from `(keyword (str *ns*) (str sym))`.
       Override via `^{:rf/id :explicit/id}` metadata on the symbol.
     - Auto-injects lexical bindings `dispatch` and `subscribe`,
       bound at render-time to `(rf/dispatcher)` / `(rf/subscriber)` of
       the surrounding frame.
     - Defs the symbol to the wrapped render fn AND registers under
       the id in the :view registry.

     Compile-time error if the second arg (after optional docstring)
     is not a vector — the args vector of a defn-shape. For runtime
     registration with computed ids or non-defn-shape bodies (e.g.
     Form-3 / `create-class`), use `reg-view*` instead.

     Per Spec 001 §Source-coordinate capture the metadata stamped onto
     the registry slot includes :ns / :line / :file captured here."
     {:arglists '([sym args body+] [sym docstring args body+])}
     [sym & more]
     ;; See rf2-xnym rationale at the top of the registration section for
     ;; the metadata-free ns-symbol.
     (expand-reg-view (meta &form) (symbol (str (ns-name *ns*))) *file* sym more)))

(defn view
  "Runtime-lookup handle for a registered view. Returns the registered
  render fn (whatever shape — Form-1, Form-2 — produced by `reg-view`
  or `reg-view*`) or nil if not registered. The wrapped fn called with
  the view's invocation args yields the hiccup tree.

  Per Spec 001 §`(re-frame.core/view id)` and Spec 004 §Calling a
  registered view: render trees use Vars; runtime lookups use ids; this
  is the id-keyed lookup."
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
     "Register a flow. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site.

     Per rf2-tfw3 the flows implementation lives in the
     `day8/re-frame2-flows` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `reg-flow` MUST add
     `day8/re-frame2-flows` to their deps and require
     `re-frame.flows` at app boot; without it, the lookup returns nil
     and the call throws a clear error."
     [& args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(rf-flows/reg-flow ~@args))))

#?(:clj
   (defmacro reg-route
     "Register a route. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site.

     Per rf2-k682 the routing implementation lives in the
     `day8/re-frame2-routing` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `reg-route` MUST add
     `day8/re-frame2-routing` to their deps and require
     `re-frame.routing` at app boot; without it, the lookup returns
     nil and the call throws a clear error."
     [id metadata]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(rf-routing/reg-route ~id ~metadata))))

#?(:clj
   (defmacro reg-app-schema
     "Register a Malli schema at a path inside app-db. Per Spec 001 the
     metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site.

     Per Spec 010 §Per-frame schemas this registration is frame-scoped.
     The frame to register against comes from the optional `opts`
     map's `:frame` key; default is `(re-frame.frame/current-frame)` —
     usually `:rf/default` unless called inside `(with-frame ...)`.

     Per rf2-p7va the schemas implementation lives in the
     `day8/re-frame2-schemas` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `reg-app-schema` MUST add
     `day8/re-frame2-schemas` to their deps; without it, the lookup
     returns `nil` and the call throws a clear error."
     {:arglists '([path schema] [path schema opts])}
     [path schema & [opts]]
     (let [opts' (or opts {})]
       (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                         `(rf-schemas/reg-app-schema ~path ~schema ~opts')))))

#?(:clj
   (defmacro reg-machine
     "Register a machine as an event handler. Per Spec 001 the metadata
     stamped onto the registry slot includes :ns / :line / :file captured
     at this call site, AND per Spec 005 §Source-coord stamping (rf2-8bp3)
     a per-element coord index keyed by spec-path is attached under the
     spec's `:rf.machine/source-coords` key. Tools (re-frame-pair,
     re-frame-10x, IDE jump-to-source) read both back via
     `(rf/handler-meta :event machine-id)` (top-level coords) and
     `(:rf.machine/source-coords (rf/machine-meta machine-id))` (per-element
     index).

     Production-elision: the per-element index is wrapped in
     `(when interop/debug-enabled? ...)`; under `:advanced` +
     `goog.DEBUG=false` the closure compiler constant-folds the gate to
     false and DCEs the entire literal — no spec-element string fragments
     survive.

     Per rf2-xbtj the machines implementation lives in the
     `day8/re-frame2-machines` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never statically
     requires it. Apps that use `reg-machine` MUST add
     `day8/re-frame2-machines` to their deps and require
     `re-frame.machines` at app boot; without it, the lookup returns nil
     and the call throws a clear error.

     For runtime registration (computed ids, code-gen pipelines, REPL),
     use `reg-machine*` — the plain-fn surface beneath this macro."
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
     (def reg-flow       rf-flows/reg-flow)
     (def reg-route      rf-routing/reg-route)
     (def reg-app-schema rf-schemas/reg-app-schema)
     (def reg-machine*   rf-machines/reg-machine*)))

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

(def -reg-error-projector rf-ssr/-reg-error-projector)

#?(:clj
   (defmacro reg-error-projector
     "Register an error projector — a fn `(trace-event) → public-error-map`.
     Per Spec 011 §Server error projection. Frames opt into a custom
     projector via the :ssr config's :public-error-id key:

       (rf/reg-error-projector :myapp/public-error
         (fn [trace-event] ...public-error-shape...))

     Per Spec 001 §Source-coordinate capture the metadata stamped onto
     the registry slot includes :ns / :line / :file captured at this
     call site."
     [& args]
     (with-coords-form (meta &form) *file* (symbol (str (ns-name *ns*)))
                       `(-reg-error-projector ~@args))))

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
(def clear-fx    fx/unregister-fx)
(def clear-subscription-cache! subs/clear-subscription-cache!)

;; ---- dispatch and subscribe -----------------------------------------------

(def dispatch       router/dispatch!)
(def dispatch-sync  router/dispatch-sync!)
(def subscribe      subs/subscribe)
(def subscribe-value subs/subscribe-value)
(def unsubscribe    subs/unsubscribe)
(def compute-sub    subs/compute-sub)

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
;; user-facing surface that flows through `(dispatcher)`/`(subscriber)`/
;; `bound-fn` capture). The JVM build falls through to
;; `frame/current-frame` (dynamic-var → :rf/default).
(defn current-frame
  "Return the active frame at the call site. Per Spec 002 §Reading the
  frame from React context the chain is: dynamic var → React context
  (CLJS only) → :rf/default. The React-context tier is published by
  the active adapter through the `:adapter/current-frame` late-bind
  hook (rf2-d4sf)."
  []
  #?(:cljs (if-let [f (late-bind/get-fn :adapter/current-frame)]
             (f)
             (frame/current-frame))
     :clj  (frame/current-frame)))

(defn dispatcher
  "Return a fn that dispatches an event under the current frame.
  Captures the frame at call time, so closures do not need to thread it.

  Per Spec 004 §Affordance for plain fns:
    (let [d (rf/dispatcher)] [:button {:on-click #(d [:inc])} ...])"
  []
  (let [frame (current-frame)]
    (fn dispatch-fn
      ([event] (dispatch event {:frame frame}))
      ([event opts] (dispatch event (assoc opts :frame frame))))))

(defn subscriber
  "Return a fn that subscribes under the current frame. Captures the
  frame at call time. The returned fn delegates to subscribe."
  []
  (let [frame (current-frame)]
    (fn subscribe-fn
      [query-v]
      (subscribe frame query-v))))

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
     Per Spec 002 §with-frame the macro accepts two shapes:

       (with-frame :keyword body+)
         Shape 1 — pin `*current-frame*` to the supplied frame-id for the
         body's duration. The frame is NOT created or destroyed by the
         macro; the keyword is used as-is.

       (with-frame [sym expr] body+)
         Shape 2 — evaluate `expr` (typically `(make-frame opts)` or
         `(reg-frame :id opts)`), bind the result to `sym`, run `body`
         with `*current-frame*` bound to that frame, and destroy the
         frame on exit (success or exception).

     The discriminator is the first argument: a vector triggers Shape 2,
     anything else triggers Shape 1.

     For async closures that fire after the body returns, capture the
     frame keyword via `bound-fn` / `bound-dispatcher` / `bound-subscriber`
     — the dynamic binding has unwound by then, and (under Shape 2) the
     frame has already been destroyed."
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

(defn bound-dispatcher
  "Capture the current frame and return a frame-bound dispatch fn that
  is safe to call from async callbacks where dynamic-var binding is no
  longer in scope. Per Spec 002 §bound-fn / bound-dispatcher."
  []
  (dispatcher))

(defn bound-subscriber
  "As bound-dispatcher, for subscribe."
  []
  (subscriber))

;; `bound-fn` is the macro-form of `bound-dispatcher`/`bound-subscriber`:
;; captures `*current-frame*` at definition time, restores it at call
;; time. Useful for closures called from async boundaries (timers,
;; http-handlers, promises) where dynamic-var binding has been lost.
;; Per Spec 002 §bound-fn.

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

;; ---- view ergonomics (CLJS only) ------------------------------------------
;; reg-view (the macro) lives above as a JVM/CLJS-shared `#?(:clj defmacro)`.
;; frame-provider is a Reagent component re-exported here so `rf/frame-provider`
;; is the canonical user-facing surface (per Spec 002 §What `frame-provider` is
;; and the API.md table); it lives in re-frame.views to keep React/Reagent off
;; the JVM load path.

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
  "Return the current `app-db` value (a plain map) for the named frame.
  Returns `nil` if the frame is not registered.

  This is a value-form accessor: there is no deref. To assert against
  a path, use `(get-in (rf/get-frame-db frame-id) path)` or the
  convenience wrapper `(rf/snapshot-of path opts)`.

  Per Spec 002 §The public registrar query API."
  [frame-id]
  (frame/frame-app-db-value frame-id))

(defn snapshot-of
  "Return the value at `path` in a frame's app-db. Convenience wrapper
  over `(get-in (rf/get-frame-db frame-id) path)` — Tool-Pair pins this
  surface as the public, opt-aware app-db query.

  Resolution chain for the frame:
    1. `:frame` key in `opts`, when supplied;
    2. `(current-frame)` — the active frame under `with-frame` /
       a Reagent frame-provider, defaulting to `:rf/default`.

  Returns `nil` if the frame is missing or the path resolves to nothing.
  Per Spec 002 §The public registrar query API."
  ([path] (snapshot-of path nil))
  ([path opts]
   (let [frame-id (or (:frame opts) (current-frame))]
     (get-in (frame/frame-app-db-value frame-id) path))))

;; sub-cache is CLJS-only — the reactive cache is materialised by the
;; substrate adapter and only carries a useful :value on the JS side.
;; The JVM definition returns nil so the symbol resolves under both
;; targets. Per Spec 002 §The public registrar query API.
(defn sub-cache
  "Inspect a frame's runtime sub-cache. CLJS-only — returns
  `{query-v {:value v :ref-count n}}` for every materialised
  subscription in the named frame. On JVM the cache exists for
  ref-counting purposes but the entries do not carry a deref-able
  reaction value, so this fn returns `nil`.

  No-arg form uses the active frame.

  Pair tools call this to display what the running app is currently
  subscribed to. Per Spec 002 §The public registrar query API."
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
     "Spec 014 §Testing — install stubs, run body, uninstall. `stubs` is
     `{[method url] {:reply <:ok|:failure>}}`.

     Per rf2-5kpd the http implementation lives in the
     `day8/re-frame2-http` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `with-managed-request-stubs`
     MUST add `day8/re-frame2-http` to their deps and require
     `re-frame.http-managed` at app boot; without it, the lookup
     returns nil and the call throws a clear error."
     [stubs & body]
     `(with-managed-request-stubs* ~stubs (fn [] ~@body))))

(defn configure
  "Configure a runtime knob. Closed v1 keys (additive across versions
  per Spec-ulation):

    :epoch-history {:depth N}         — set the per-frame epoch ring depth
                                         (default 50). 0 disables recording.
    :trace-buffer  {:depth N}         — set the retain-N trace ring buffer
                                         depth (default 200). 0 disables.
    :sub-cache     {:grace-period-ms N} — set the deferred-dispose grace
                                         period for the per-frame sub-cache
                                         (default 50ms; 0 = synchronous
                                         disposal). Per Spec 006 §Reference
                                         counting and disposal.

  Per Tool-Pair §How AI tools attach. Future keys (e.g. :performance-api
  per Spec 009) will land additively."
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
    nil))

;; ---- substrate adapter ----------------------------------------------------

(def install-adapter! adapter/install-adapter!)
(def dispose-adapter! adapter/dispose-adapter!)
(def current-adapter  adapter/current-adapter)

;; ---- boot -----------------------------------------------------------------

(defn init!
  "Idempotent boot. Installs a substrate adapter and ensures the
  `:rf/default` frame is present.

  Required arg — there is no default-adapter registry. Pass the adapter
  spec map you want installed:

    (require '[re-frame.adapter.reagent :as reagent])
    (rf/init! reagent/adapter)

  Per rf2-agql (replaces rf2-84po): rf2 ships no default-adapter
  registry. Each adapter ns exports an `adapter` var (the spec map);
  consumers require the ns and pass the var explicitly. This keeps
  adapter wiring explicit at the call site and removes the bundle
  weight a registry would impose on apps that do not need it.

  Argument shapes:

    (rf/init! adapter-map) ;; install the adapter — only valid form

  Calling `(rf/init!)` with no args raises
  `:rf.error/no-adapter-specified`. Calling `(rf/init! :reagent)` (a
  keyword) also raises — there is no registry to look up. The error
  message points the consumer at the adapter ns + var pattern.

  Per Spec 006 §Adapter selection at boot."
  ([]
   (throw (ex-info ":rf.error/no-adapter-specified"
                   {:where    'init!
                    :recovery :no-recovery
                    :reason   "rf/init! requires an explicit adapter spec map. Require the adapter ns and pass its `adapter` var: (require '[re-frame.adapter.reagent :as reagent]) (rf/init! reagent/adapter). Per rf2-agql the default-adapter registry was removed; there is no implicit default."})))
  ([adapter-map]
   (cond
     (nil? adapter-map)
     (throw (ex-info ":rf.error/no-adapter-specified"
                     {:where    'init!
                      :recovery :no-recovery
                      :reason   "rf/init! was called with nil. Require the adapter ns and pass its `adapter` var: (rf/init! reagent/adapter). Per rf2-agql the default-adapter registry was removed."}))

     (not (map? adapter-map))
     (throw (ex-info ":rf.error/no-adapter-specified"
                     {:where    'init!
                      :received adapter-map
                      :expected "adapter spec map"
                      :recovery :no-recovery
                      :reason   "rf/init! takes the adapter spec map directly — there is no keyword form and no registry. Require the adapter ns and pass its `adapter` var: (rf/init! reagent/adapter). Per rf2-agql the default-adapter registry was removed."}))

     :else
     (do
       (when-not (adapter/current-adapter)
         (adapter/install-adapter! adapter-map))
       (frame/ensure-default-frame!)
       nil))))

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
