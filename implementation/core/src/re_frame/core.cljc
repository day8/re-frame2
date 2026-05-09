(ns re-frame.core
  "Public API surface for re-frame2. Per spec/API.md.

  Users `(:require [re-frame.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event-fx`, etc. Internal namespaces are not part of the
  contract.

  This namespace re-exports the canonical surface; per-namespace docs
  carry the design rationale."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.cofx :as cofx]
            [re-frame.subs :as subs]
            [re-frame.interceptor :as interceptor]
            [re-frame.std-interceptors :as std-interceptors]
            ;; re-frame.schemas (Spec 010) ships as a separate Maven
            ;; artefact (day8/re-frame-2-schemas, rf2-p7va). The core
            ;; artefact MUST NOT `:require [re-frame.schemas]` — that
            ;; would pull schemas (and its Malli dep) onto every
            ;; consumer's classpath even when no schema is registered.
            ;; The re-export wrappers below look the schemas API up
            ;; through the late-bind hook table at call time, which the
            ;; schemas artefact populates from its own ns-load.
            [re-frame.late-bind :as late-bind]
            ;; re-frame.flows (Spec 013) ships as a separate Maven
            ;; artefact (day8/re-frame-2-flows, rf2-tfw3). The core
            ;; artefact MUST NOT `:require [re-frame.flows]` — that
            ;; would pull the namespace, the per-frame flow registry,
            ;; the topological-sort engine, and the dirty-check
            ;; `last-inputs` map onto every consumer's classpath even
            ;; when no flow is registered. The re-export wrappers below
            ;; look the flows API up through the late-bind hook table
            ;; at call time, which the flows artefact populates from
            ;; its own ns-load.
            ;;
            ;; re-frame.machines (Spec 005) ships as a separate Maven
            ;; artefact (day8/re-frame-2-machines, rf2-xbtj). The core
            ;; artefact MUST NOT `:require [re-frame.machines]` — that
            ;; would pull the machines namespace and its `:rf/machine`
            ;; sub registration onto every consumer's classpath even
            ;; when no machine is registered. The re-export wrappers
            ;; below look the machines API up through the late-bind
            ;; hook table at call time, which the machines artefact
            ;; populates from its own ns-load.
            ;;
            ;; re-frame.routing (Spec 012) ships as a separate Maven
            ;; artefact (day8/re-frame-2-routing, rf2-k682). The core
            ;; artefact MUST NOT `:require [re-frame.routing]` — that
            ;; would pull the namespace, the route-rank / pattern-compile
            ;; / nav-token machinery, the `:rf/route` reg-sub family,
            ;; and every `:rf.route/*` / `:rf.nav/*` keyword string onto
            ;; every consumer's classpath even when no route is
            ;; registered. The re-export wrappers below look the routing
            ;; API up through the late-bind hook table at call time,
            ;; which the routing artefact populates from its own ns-load.
            ;;
            ;; re-frame.http-managed (Spec 014) ships as a separate Maven
            ;; artefact (day8/re-frame-2-http, rf2-5kpd). The core
            ;; artefact MUST NOT `:require [re-frame.http-managed]` —
            ;; that would pull the namespace, the in-flight request
            ;; registry, the Fetch / `java.net.http.HttpClient`
            ;; transport adapters, the encode / decode pipeline, the
            ;; retry-with-backoff machinery, the eight-category
            ;; `:rf.http/*` failure taxonomy, and every `:rf.http/*`
            ;; keyword string onto every consumer's classpath even when
            ;; no managed-HTTP request is issued. The re-export wrappers
            ;; below look the http test-helper API up through the
            ;; late-bind hook table at call time, which the http
            ;; artefact populates from its own ns-load.
            ;;
            ;; re-frame.ssr (Spec 011) ships as a separate Maven artefact
            ;; (day8/re-frame-2-ssr, rf2-uo7v). The core artefact MUST
            ;; NOT `:require [re-frame.ssr]` — that would pull the pure
            ;; hiccup → HTML emitter, the FNV-1a render-tree-hash
            ;; machinery, the per-request `[:rf/response]` accumulator,
            ;; the six `:rf.server/*` server-only fxs, the
            ;; `reg-error-projector` registry kind plus its built-in
            ;; default, the SSR error-projection trace listener, the
            ;; `:rf/hydrate` event, and every `:rf.ssr/*` / `:rf.server/*`
            ;; keyword string onto every consumer's classpath even when
            ;; no server-side rendering is performed. The re-export
            ;; wrappers (`render-to-string`, `render-tree-hash`,
            ;; `reg-error-projector`, `project-error`) below look the
            ;; ssr API up through the late-bind hook table at call
            ;; time, which the ssr artefact populates from its own
            ;; ns-load.
            ;;
            ;; re-frame.epoch (Tool-Pair §Time-travel) ships as a
            ;; separate Maven artefact (day8/re-frame-2-epoch,
            ;; rf2-lt4e — the seventh and final per-feature split per
            ;; rf2-5vjj Strategy B). The core artefact MUST NOT
            ;; `:require [re-frame.epoch]` — that would pull the
            ;; per-frame `:rf/epoch-record` ring buffer, the per-cascade
            ;; trace-capture path, the `:sub-runs` / `:renders` /
            ;; `:effects` projection walker, the schema-validate /
            ;; machine-version / missing-reference predicates, and
            ;; every `:rf.epoch/*` keyword string onto every consumer's
            ;; classpath even when the pair-tool surface is unused.
            ;; The re-export wrappers (`epoch-history`, `restore-epoch`,
            ;; `register-epoch-cb`, `remove-epoch-cb`) and the
            ;; `(rf/configure :epoch-history ...)` knob below look the
            ;; epoch API up through the late-bind hook table at call
            ;; time, which the epoch artefact populates from its own
            ;; ns-load. Per Tool-Pair §Time-travel §Production elision
            ;; the entire epoch surface is gated on
            ;; `interop/debug-enabled?` whether or not the artefact is
            ;; on the classpath; the wrappers degrade silently (empty
            ;; vector / false / no-op) when it's absent so a release
            ;; build that omits the artefact does not raise.
            [re-frame.source-coords :as source-coords]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]
            [re-frame.substrate.adapter :as adapter]
            ;; re-frame.substrate.plain-atom — required for its ns-load
            ;; side-effect: on the JVM it auto-registers as the default
            ;; adapter (rf2-84po), so JVM tests / SSR / headless apps boot
            ;; with `(rf/init!)` alone. On CLJS the require pulls in the
            ;; symbol but plain-atom does NOT auto-register as default
            ;; (the substrate-specific ns the consumer requires —
            ;; re-frame.substrate.reagent or .uix — is the registry
            ;; populator). The alias is retained for the `:plain-atom`
            ;; lookup keyword and for explicit-spec tests.
            [re-frame.substrate.plain-atom :as plain-atom]
            ;; CLJS only: re-frame.views holds the Reagent-aware reg-view*
            ;; (with React-context wiring). On JVM the registrar registration
            ;; is sufficient.
            #?@(:cljs [[re-frame.views :as views]]))
  ;; The reg-view macro is defined in the #?(:clj ...) block below. Make
  ;; it visible to CLJS callers under the `re-frame.core` alias by
  ;; self-referring `:require-macros`. Per Spec 004 §reg-view, the macro
  ;; lives in re-frame.core; CLJS users can write `rf/reg-view` after
  ;; `(:require [re-frame.core :as rf])` without an explicit
  ;; `:require-macros` clause at the call site.
  #?(:cljs (:require-macros [re-frame.core :refer [reg-view]])))

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
;; future addition (a re-frame.core-macros companion ns following
;; the re-frame.views-macros pattern); the ALIAS path keeps current
;; CLJS callers functioning. Tooling that consumes :ns / :line /
;; :file via the JVM side (server-rendering, JVM tests, REPL
;; introspection) is unaffected.

#?(:clj
   (defmacro reg-event-db
     "Register a (db, event) -> new-db handler. Per Spec 001 the
     metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site."
     [id & args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (events/reg-event-db ~id ~@args)))))

#?(:clj
   (defmacro reg-event-fx
     "Register a (cofx, event) -> effects-map handler. Per Spec 001
     the metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site."
     [id & args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (events/reg-event-fx ~id ~@args)))))

#?(:clj
   (defmacro reg-event-ctx
     "Register a context-handler. Per Spec 001 the metadata stamped
     onto the registry slot includes :ns / :line / :file captured at
     this call site."
     [id & args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (events/reg-event-ctx ~id ~@args)))))

#?(:clj
   (defmacro reg-sub
     "Register a subscription. Per Spec 001 the metadata stamped onto
     the registry slot includes :ns / :line / :file captured at this
     call site."
     [id & args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (subs/reg-sub ~id ~@args)))))

#?(:clj
   (defmacro reg-fx
     "Register an fx handler. Per Spec 001 the metadata stamped onto
     the registry slot includes :ns / :line / :file captured at this
     call site."
     [id & args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (fx/reg-fx ~id ~@args)))))

#?(:clj
   (defmacro reg-cofx
     "Register a coeffect handler. Per Spec 001 the metadata stamped
     onto the registry slot includes :ns / :line / :file captured at
     this call site."
     [id & args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (cofx/reg-cofx ~id ~@args)))))

#?(:clj
   (defmacro reg-frame
     "Register a frame. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site."
     [id metadata]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (frame/reg-frame ~id ~metadata)))))

;; CLJS side keeps the fn-aliases. Source-coord capture on CLJS will
;; ride a future re-frame.core-macros companion ns (per the existing
;; re-frame.views-macros pattern). The fns themselves (events/reg-*,
;; subs/reg-sub, etc.) honour `*pending-coords*` either way — the
;; macros above are the *capture* path; the merge path is in the fns.
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
     ;; Delegates to the shared expander in re-frame.views-macros so
     ;; both this surface and the legacy
     ;; `:require-macros [re-frame.views-macros :refer [reg-view]]`
     ;; emit identical expansions.
     ;; Construct a fresh metadata-free symbol; (ns-name *ns*) carries the
     ;; consumer namespace's :doc metadata in CLJS macro context, which
     ;; would otherwise serialise into the bundle and defeat elision.
     ((requiring-resolve 're-frame.views-macros/expand-reg-view)
      (meta &form) (symbol (str (ns-name *ns*))) *file* sym more)))

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

;; render-to-string / render-tree-hash are late-bound via the hook table
;; so core does not statically require re-frame.ssr (rf2-uo7v — ssr
;; ships in day8/re-frame-2-ssr). When the ssr artefact is not on the
;; classpath the lookups return nil and the wrappers raise
;; :rf.error/ssr-artefact-missing.
(defn render-to-string
  "Render a hiccup tree to an HTML string. Per Spec 011 §The render-tree
  → HTML emitter. Delegates to the installed substrate adapter's
  :render-to-string slot — for the plain-atom adapter (JVM/SSR) that
  routes through re-frame.ssr; for Reagent it can route through
  reagent.dom.server. opts may carry :doctype? to prepend '<!DOCTYPE html>'
  and :emit-hash? to inject data-rf-render-hash on the root element.
  Late-bound via :ssr/render-to-string."
  ([render-tree] (render-to-string render-tree {}))
  ([render-tree opts]
   (if-let [f (late-bind/get-fn :ssr/render-to-string)]
     (f render-tree opts)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'render-to-string
                      :recovery :no-recovery
                      :reason   "rf/render-to-string requires day8/re-frame-2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))

(defn render-tree-hash
  "Stable structural hash of a render tree (FNV-1a 32-bit, lowercase
  hex). Identical output on JVM and CLJS for the same canonical-EDN
  representation. Per Spec 011 §Hydration-mismatch detection. Late-bound
  via :ssr/render-tree-hash."
  [render-tree]
  (if-let [f (late-bind/get-fn :ssr/render-tree-hash)]
    (f render-tree)
    (throw (ex-info ":rf.error/ssr-artefact-missing"
                    {:where    'render-tree-hash
                     :recovery :no-recovery
                     :reason   "rf/render-tree-hash requires day8/re-frame-2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))
(def make-frame      frame/make-frame)
(def reset-frame     frame/reset-frame!)
(def destroy-frame   frame/destroy-frame!)

;; reg-flow / clear-flow are late-bound via the hook table so core does
;; not statically require re-frame.flows (rf2-tfw3 — flows ships in
;; day8/re-frame-2-flows). When the flows artefact is not on the
;; classpath the lookups return nil and the wrappers raise
;; :rf.error/flows-artefact-missing.
(defn clear-flow
  "Per Spec 013 §Lifecycle: clear a flow from a frame's registry and
  vacate its output path. Late-bound via :flows/clear-flow."
  ([id] (clear-flow id {}))
  ([id opts]
   (if-let [f (late-bind/get-fn :flows/clear-flow)]
     (f id opts)
     (throw (ex-info ":rf.error/flows-artefact-missing"
                     {:where    'clear-flow
                      :flow-id  id
                      :recovery :no-recovery
                      :reason   "rf/clear-flow requires day8/re-frame-2-flows on the classpath; add it to deps and require re-frame.flows at app boot."})))))

#?(:clj
   (defmacro reg-flow
     "Register a flow. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site.

     Per rf2-tfw3 the flows implementation lives in the
     `day8/re-frame-2-flows` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `reg-flow` MUST add
     `day8/re-frame-2-flows` to their deps and require
     `re-frame.flows` at app boot; without it, the lookup returns nil
     and the call throws a clear error."
     [& args]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (if-let [f# (late-bind/get-fn :flows/reg-flow)]
            (apply f# (list ~@args))
            (throw (ex-info ":rf.error/flows-artefact-missing"
                            {:where    'reg-flow
                             :recovery :no-recovery
                             :reason   "rf/reg-flow requires day8/re-frame-2-flows on the classpath; add it to deps and require re-frame.flows at app boot."})))))))

#?(:clj
   (defmacro reg-route
     "Register a route. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site.

     Per rf2-k682 the routing implementation lives in the
     `day8/re-frame-2-routing` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `reg-route` MUST add
     `day8/re-frame-2-routing` to their deps and require
     `re-frame.routing` at app boot; without it, the lookup returns
     nil and the call throws a clear error."
     [id metadata]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (if-let [f# (late-bind/get-fn :routing/reg-route)]
            (f# ~id ~metadata)
            (throw (ex-info ":rf.error/routing-artefact-missing"
                            {:where    'reg-route
                             :route-id ~id
                             :recovery :no-recovery
                             :reason   "rf/reg-route requires day8/re-frame-2-routing on the classpath; add it to deps and require re-frame.routing at app boot."})))))))

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
     `day8/re-frame-2-schemas` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `reg-app-schema` MUST add
     `day8/re-frame-2-schemas` to their deps; without it, the lookup
     returns `nil` and the call throws a clear error."
     {:arglists '([path schema] [path schema opts])}
     [path schema & [opts]]
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*)
           ;; returns the ns-symbol but in CLJS macro context that
           ;; symbol may carry the consumer namespace's :doc metadata,
           ;; which would then get serialised into the bundle and
           ;; defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*
           opts'   (or opts {})]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (if-let [f# (late-bind/get-fn :schemas/reg-app-schema)]
            (f# ~path ~schema ~opts')
            (throw (ex-info ":rf.error/schemas-artefact-missing"
                            {:where    'reg-app-schema
                             :path     ~path
                             :recovery :no-recovery
                             :reason   "rf/reg-app-schema requires day8/re-frame-2-schemas on the classpath; add it to deps and require re-frame.schemas at app boot."})))))))

;; Schema introspection — pure fn re-exports (no source-coord capture
;; needed, these are read-only public queries). Late-bound through the
;; hook table so core does not statically require re-frame.schemas
;; (rf2-p7va).
(defn app-schema-at
  "Return the registered schema for a path in a frame, or nil. Per Spec
  010 §Schemas as a tooling and agent surface. Returns nil when the
  schemas artefact is not on the classpath."
  ([path] (app-schema-at path {}))
  ([path opts]
   (when-let [f (late-bind/get-fn :schemas/app-schema-at)]
     (f path opts))))

(defn app-schemas
  "Return every registered `app-schema-at` declaration for a frame as a
  `{path → schema}` map. Per Spec 010 §Per-frame schemas. Returns `{}`
  when the schemas artefact is not on the classpath."
  ([] (app-schemas {}))
  ([opts-or-frame-id]
   (if-let [f (late-bind/get-fn :schemas/app-schemas)]
     (f opts-or-frame-id)
     {})))

(defn app-schemas-digest
  "Return a stable digest of the registered schemas for a frame. Per
  Spec 010 §Digest algorithm. Returns `nil` when the schemas artefact
  is not on the classpath."
  ([] (app-schemas-digest {}))
  ([opts-or-frame-id]
   (when-let [f (late-bind/get-fn :schemas/app-schemas-digest)]
     (f opts-or-frame-id))))

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
     `day8/re-frame-2-machines` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never statically
     requires it. Apps that use `reg-machine` MUST add
     `day8/re-frame-2-machines` to their deps and require
     `re-frame.machines` at app boot; without it, the lookup returns nil
     and the call throws a clear error.

     For runtime registration (computed ids, code-gen pipelines, REPL),
     use `reg-machine*` — the plain-fn surface beneath this macro."
     [machine-id machine]
     (let [m              (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym         (symbol (str (ns-name *ns*)))
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
         `(binding [source-coords/*pending-coords*
                    (cond-> {:ns '~ns-sym}
                      ~file        (assoc :file ~file)
                      ~(:line m)   (assoc :line ~(:line m))
                      ~(:column m) (assoc :column ~(:column m)))]
            (if-let [f# (late-bind/get-fn :machines/reg-machine)]
              (f# ~machine-id ~machine)
              (throw (ex-info ":rf.error/machines-artefact-missing"
                              {:where      'reg-machine
                               :machine-id ~machine-id
                               :recovery   :no-recovery
                               :reason     "rf/reg-machine requires day8/re-frame-2-machines on the classpath; add it to deps and require re-frame.machines at app boot."}))))
         `(binding [source-coords/*pending-coords*
                    (cond-> {:ns '~ns-sym}
                      ~file        (assoc :file ~file)
                      ~(:line m)   (assoc :line ~(:line m))
                      ~(:column m) (assoc :column ~(:column m)))]
            (let [~machine-sym ~machine
                  ;; Per-element source-coord stamping (rf2-8bp3). The literal
                  ;; index is reachable only inside this `interop/debug-enabled?`
                  ;; gate; under :advanced + goog.DEBUG=false the closure compiler
                  ;; folds the gate to false and the entire literal DCE's.
                  stamped# (if interop/debug-enabled?
                             (assoc ~machine-sym
                                    :rf.machine/source-coords
                                    ~per-el-form)
                             ~machine-sym)]
              (if-let [f# (late-bind/get-fn :machines/reg-machine)]
                (f# ~machine-id stamped#)
                (throw (ex-info ":rf.error/machines-artefact-missing"
                                {:where      'reg-machine
                                 :machine-id ~machine-id
                                 :recovery   :no-recovery
                                 :reason     "rf/reg-machine requires day8/re-frame-2-machines on the classpath; add it to deps and require re-frame.machines at app boot."})))))))))

#?(:cljs
   (do
     ;; reg-flow is late-bound via the hook table so core does not
     ;; statically require re-frame.flows (rf2-tfw3 — flows ships in
     ;; day8/re-frame-2-flows).
     (defn reg-flow
       ([flow] (reg-flow flow {}))
       ([flow opts]
        (if-let [f (late-bind/get-fn :flows/reg-flow)]
          (f flow opts)
          (throw (ex-info ":rf.error/flows-artefact-missing"
                          {:where    'reg-flow
                           :recovery :no-recovery
                           :reason   "rf/reg-flow requires day8/re-frame-2-flows on the classpath; add it to deps and require re-frame.flows at app boot."})))))
     ;; reg-route is late-bound via the hook table so core does not
     ;; statically require re-frame.routing (rf2-k682 — routing ships in
     ;; day8/re-frame-2-routing).
     (defn reg-route
       [id metadata]
       (if-let [f (late-bind/get-fn :routing/reg-route)]
         (f id metadata)
         (throw (ex-info ":rf.error/routing-artefact-missing"
                         {:where    'reg-route
                          :route-id id
                          :recovery :no-recovery
                          :reason   "rf/reg-route requires day8/re-frame-2-routing on the classpath; add it to deps and require re-frame.routing at app boot."}))))
     ;; reg-app-schema is late-bound via the hook table so core does not
     ;; statically require re-frame.schemas (rf2-p7va — schemas ships
     ;; in day8/re-frame-2-schemas).
     (defn reg-app-schema
       ([path schema] (reg-app-schema path schema {}))
       ([path schema opts]
        (if-let [f (late-bind/get-fn :schemas/reg-app-schema)]
          (f path schema opts)
          (throw (ex-info ":rf.error/schemas-artefact-missing"
                          {:where    'reg-app-schema
                           :path     path
                           :recovery :no-recovery
                           :reason   "rf/reg-app-schema requires day8/re-frame-2-schemas on the classpath; add it to deps and require re-frame.schemas at app boot."})))))
     ;; reg-machine* is late-bound via the hook table so core does not
     ;; statically require re-frame.machines (rf2-xbtj — machines ships
     ;; in day8/re-frame-2-machines). Per Spec 005 §reg-machine vs
     ;; reg-machine* (rf2-8bp3) this is the plain-fn surface — used by
     ;; the `reg-machine` macro's emitted form, by code-gen pipelines
     ;; that already carry a stamped spec, and by REPL workflows that
     ;; bypass the macro path. Programmatic callers see no per-element
     ;; source-coord index (only the macro can walk the literal spec at
     ;; expansion time).
     (defn reg-machine*
       [machine-id machine]
       (if-let [f (late-bind/get-fn :machines/reg-machine)]
         (f machine-id machine)
         (throw (ex-info ":rf.error/machines-artefact-missing"
                         {:where      'reg-machine*
                          :machine-id machine-id
                          :recovery   :no-recovery
                          :reason     "rf/reg-machine* requires day8/re-frame-2-machines on the classpath; add it to deps and require re-frame.machines at app boot."}))))))

;; Plain-fn surface for the JVM. Used by code-gen pipelines and the
;; conformance corpus when registering machines without a literal spec
;; form. Per Spec 005 §reg-machine vs reg-machine* (rf2-8bp3).
#?(:clj
   (defn reg-machine*
     [machine-id machine]
     (if-let [f (late-bind/get-fn :machines/reg-machine)]
       (f machine-id machine)
       (throw (ex-info ":rf.error/machines-artefact-missing"
                       {:where      'reg-machine*
                        :machine-id machine-id
                        :recovery   :no-recovery
                        :reason     "rf/reg-machine* requires day8/re-frame-2-machines on the classpath; add it to deps and require re-frame.machines at app boot."})))))

;; reg-error-projector lives in re-frame.ssr so the registry kind ships
;; with its default :rf.ssr/default-error-projector. Per rf2-uo7v ssr
;; ships in day8/re-frame-2-ssr; the producing fn is looked up through
;; the late-bind hook table so core never statically requires
;; re-frame.ssr. When the ssr artefact is not on the classpath the
;; lookup returns nil and the call raises :rf.error/ssr-artefact-missing.
(defn -reg-error-projector
  "Internal helper — prefer `reg-error-projector` from public callers.
  This is the fn-form delegate the public macro / CLJS alias forward to.
  Late-bound via :ssr/reg-error-projector."
  ([id projector-fn]
   (-reg-error-projector id {} projector-fn))
  ([id metadata projector-fn]
   (if-let [f (late-bind/get-fn :ssr/reg-error-projector)]
     (f id metadata projector-fn)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'reg-error-projector
                      :id       id
                      :recovery :no-recovery
                      :reason   "rf/reg-error-projector requires day8/re-frame-2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))

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
     (let [m       (meta &form)
           ;; Construct a fresh, metadata-free symbol. (ns-name *ns*) returns
           ;; the ns-symbol but in CLJS macro context that symbol may carry
           ;; the consumer namespace's :doc metadata, which would then get
           ;; serialised into the bundle and defeat production elision.
           ns-sym  (symbol (str (ns-name *ns*)))
           file    *file*]
       `(binding [source-coords/*pending-coords*
                  (cond-> {:ns '~ns-sym}
                    ~file        (assoc :file ~file)
                    ~(:line m)   (assoc :line ~(:line m))
                    ~(:column m) (assoc :column ~(:column m)))]
          (-reg-error-projector ~@args)))))

#?(:cljs
   (def reg-error-projector -reg-error-projector))

(defn project-error
  "Apply the active error projector for frame-id to the trace event.
  Returns a :rf/public-error map. Per Spec 011 §Server error projection.
  Late-bound via :ssr/project-error."
  [frame-id trace-event]
  (if-let [f (late-bind/get-fn :ssr/project-error)]
    (f frame-id trace-event)
    (throw (ex-info ":rf.error/ssr-artefact-missing"
                    {:where    'project-error
                     :frame    frame-id
                     :recovery :no-recovery
                     :reason   "rf/project-error requires day8/re-frame-2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))

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
(def current-frame frame/current-frame)

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

(defn with-frame
  "Run `thunk` with *current-frame* bound to `frame-id`. The macro form
  in re-frame.views wraps an expression in a thunk; this fn variant
  is JVM-friendly for tests / SSR / REPL."
  [frame-id thunk]
  (binding [frame/*current-frame* frame-id]
    (thunk)))

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

;; ---- view ergonomics (CLJS only) ------------------------------------------
;; reg-view (the macro), with-frame live in re-frame.views-macros (CLJS-
;; only macros — users `(:require-macros [re-frame.views-macros :refer ...])`).
;; frame-provider is a Reagent component re-exported here so `rf/frame-provider`
;; is the canonical user-facing surface (per Spec 002 §What `frame-provider` is
;; and the API.md table); it lives in re-frame.views to keep React/Reagent off
;; the JVM load path.

#?(:cljs (def frame-provider views/frame-provider))

;; ---- routing helpers ------------------------------------------------------
;;
;; Per rf2-k682 the routing surface lives in the
;; `day8/re-frame-2-routing` artefact. The re-exports below late-bind
;; through the hook table so core does not statically require
;; re-frame.routing. When the routing artefact is not on the classpath
;; the lookups return nil and the wrappers raise
;; :rf.error/routing-artefact-missing.

(defn match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Match a URL against
  registered routes; return `{:route-id :params :query
  :validation-failed?}` for the first match, or `nil` if no route
  matches. Late-bound via :routing/match-url."
  [url]
  (if-let [f (late-bind/get-fn :routing/match-url)]
    (f url)
    (throw (ex-info ":rf.error/routing-artefact-missing"
                    {:where    'match-url
                     :recovery :no-recovery
                     :reason   "rf/match-url requires day8/re-frame-2-routing on the classpath; add it to deps and require re-frame.routing at app boot."}))))

(defn route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Inverse of `match-url` —
  build a URL string from a route-id + path-params (and optional
  query-params). Late-bound via :routing/route-url."
  ([route-id path-params] (route-url route-id path-params {}))
  ([route-id path-params query-params]
   (if-let [f (late-bind/get-fn :routing/route-url)]
     (f route-id path-params query-params)
     (throw (ex-info ":rf.error/routing-artefact-missing"
                     {:where    'route-url
                      :route-id route-id
                      :recovery :no-recovery
                      :reason   "rf/route-url requires day8/re-frame-2-routing on the classpath; add it to deps and require re-frame.routing at app boot."})))))

;; ---- machine helpers ------------------------------------------------------
;;
;; Per rf2-xbtj the machines surface lives in the
;; `day8/re-frame-2-machines` artefact. The re-exports below late-bind
;; through the hook table so core does not statically require
;; re-frame.machines. When the machines artefact is not on the
;; classpath the lookups return nil and the wrappers raise
;; :rf.error/machines-artefact-missing (for active surfaces) or return
;; safe defaults (for read-only queries).

(defn create-machine-handler
  "Build an event-fx handler from a machine spec. Per Spec 005
  §Registration. Late-bound via :machines/create-machine-handler."
  [machine]
  (if-let [f (late-bind/get-fn :machines/create-machine-handler)]
    (f machine)
    (throw (ex-info ":rf.error/machines-artefact-missing"
                    {:where    'create-machine-handler
                     :recovery :no-recovery
                     :reason   "rf/create-machine-handler requires day8/re-frame-2-machines on the classpath; add it to deps and require re-frame.machines at app boot."}))))

(defn machine-transition
  "Pure (machine, snapshot, event) -> [snapshot fx]. Per Spec 005
  §Drain semantics §Level 3. Late-bound via :machines/machine-transition."
  [machine snapshot event]
  (if-let [f (late-bind/get-fn :machines/machine-transition)]
    (f machine snapshot event)
    (throw (ex-info ":rf.error/machines-artefact-missing"
                    {:where    'machine-transition
                     :recovery :no-recovery
                     :reason   "rf/machine-transition requires day8/re-frame-2-machines on the classpath; add it to deps and require re-frame.machines at app boot."}))))

(defn machines
  "Return a sequence of registered machine ids. Per Spec 005
  §Querying machines. Returns `[]` when the machines artefact is not
  on the classpath."
  []
  (if-let [f (late-bind/get-fn :machines/machines)]
    (f)
    []))

(defn machine-meta
  "Return the registered machine spec map for machine-id, or nil. Per
  Spec 005 §Querying machines. Returns nil when the machines artefact
  is not on the classpath."
  [machine-id]
  (when-let [f (late-bind/get-fn :machines/machine-meta)]
    (f machine-id)))

(defn machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf/system-ids]` reverse index, or nil. The optional
  `frame-id` arg targets an explicit frame; without it, resolution uses
  the current frame (per `with-frame` / frame-provider, defaulting to
  `:rf/default`).

  Per Spec 005 §Named addressing via :system-id. Returns nil when the
  machines artefact is not on the classpath."
  ([system-id]
   (when-let [f (late-bind/get-fn :machines/machine-by-system-id)]
     (f system-id)))
  ([system-id frame-id]
   (when-let [f (late-bind/get-fn :machines/machine-by-system-id)]
     (f system-id frame-id))))

(defn dispatch-to-system
  "Sugar: dispatch `event` to the spawned-machine bound to `system-id`
  in the active frame. Equivalent to
  `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`,
  with a no-op fall-through when the system-id is unbound. Per Spec 005
  §Cross-machine messaging by name."
  ([system-id event]
   (when-let [machine-id (machine-by-system-id system-id)]
     (dispatch [machine-id event])))
  ([system-id event frame-id]
   (when-let [machine-id (machine-by-system-id system-id frame-id)]
     (dispatch [machine-id event] {:frame frame-id}))))

(defn sub-machine
  "Subscribe to a machine's snapshot. Sugar over (subscribe [:rf/machine
  machine-id]). Returns a reaction whose value is the snapshot
  {:state <kw> :data <map>} or nil if the machine is not yet
  initialised. Per Spec 005 §Subscribing to machines via sub-machine."
  [machine-id]
  (subscribe [:rf/machine machine-id]))

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

;; ---- trace ----------------------------------------------------------------

(def register-trace-cb!  trace/register-trace-cb!)
(def remove-trace-cb!    trace/remove-trace-cb!)
(def emit-trace!         trace/emit!)
(def trace-buffer        trace/trace-buffer)
(def clear-trace-buffer! trace/clear-trace-buffer!)

;; ---- epoch history (Tool-Pair §Time-travel) ------------------------------
;;
;; Per Tool-Pair §Time-travel and Spec 009 §`register-epoch-cb`. Every
;; drain-settle records an `:rf/epoch-record` per frame. The history is
;; queryable; the listener API mirrors register-trace-cb!; restore-epoch
;; rewinds the frame's app-db to a recorded epoch's `:db-after`.
;;
;; Per rf2-lt4e (the seventh and final per-feature split per rf2-5vjj
;; Strategy B), the epoch surface ships in `day8/re-frame-2-epoch`; the
;; four re-exports below late-bind through the hook table so core never
;; statically requires it. When the epoch artefact is not on the
;; classpath, the lookups return nil and the wrappers degrade quietly:
;; `epoch-history` returns the empty vector, `restore-epoch` returns
;; false, the listener register / remove return nil. The whole surface
;; is still gated on `interop/debug-enabled?` (per Tool-Pair §Time-travel
;; §Production elision), so production builds elide regardless of
;; classpath presence.

(defn epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs, when the
  ring buffer's depth is 0 (recording disabled), or when the
  `day8/re-frame-2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/epoch-history`."
  [frame-id]
  (if-let [f (late-bind/get-fn :epoch/epoch-history)]
    (f frame-id)
    []))

(defn restore-epoch
  "Rewind the named frame's `app-db` to the named epoch's `:db-after`.
  Per Tool-Pair §Time-travel: returns `true` on success, `false` on any
  of the six documented failure modes (each emits a structured
  `:rf.epoch/*` error trace and leaves `app-db` unchanged) and `false`
  when the `day8/re-frame-2-epoch` artefact is not on the classpath.
  Late-bound via `:epoch/restore-epoch`."
  [frame-id epoch-id]
  (if-let [f (late-bind/get-fn :epoch/restore-epoch)]
    (f frame-id epoch-id)
    false))

(defn register-epoch-cb
  "Register a callback fired once per drain-settle with the assembled
  `:rf/epoch-record`. Per Spec 009 §`register-epoch-cb`. Same-id
  registrations replace; listener exceptions are isolated. Returns the
  id. No-op (returns nil) when the `day8/re-frame-2-epoch` artefact is
  not on the classpath. Late-bound via `:epoch/register-epoch-cb`."
  [id f]
  (when-let [g (late-bind/get-fn :epoch/register-epoch-cb)]
    (g id f)))

(defn remove-epoch-cb
  "Remove the listener registered under id. No-op when the
  `day8/re-frame-2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/remove-epoch-cb`."
  [id]
  (when-let [f (late-bind/get-fn :epoch/remove-epoch-cb)]
    (f id)))

(defn reset-frame-db!
  "Replace `frame-id`'s `app-db` with `new-db`, bypassing the dispatch
  loop. Per Tool-Pair §Pair-tool writes (rf2-zq55).

  The canonical Tool-Pair write surface for state injection — pair
  tools use it for evolved-state-shape probes after a handler hot-swap,
  story-tool fixture setup, conformance-harness state seeding, and
  time-travel from JSON-loaded bug repros. Records a synthetic
  `:rf/epoch-record` so `restore-epoch` can rewind the previous state;
  emits `:rf.epoch/db-replaced` on success.

  Failure modes (each is a no-op on `app-db` and emits a structured
  error trace):

    :rf.error/no-such-handler                 — frame not registered
    :rf.epoch/reset-frame-db-during-drain     — drain in flight
    :rf.epoch/reset-frame-db-schema-mismatch  — `new-db` fails the
                                                 frame's app-schema set

  Dev-only — gated on `interop/debug-enabled?`. Production builds
  (`:advanced` + `goog.DEBUG=false`) elide via Closure DCE. Late-bound
  via `:epoch/reset-frame-db!`; raises `:rf.error/epoch-artefact-missing`
  when the `day8/re-frame-2-epoch` artefact is not on the classpath
  (the surface records an epoch and so cannot degrade silently — the
  caller's invariant is 'undo works after this call').

  Returns `true` on success, `false` on any failure."
  [frame-id new-db]
  (if-let [f (late-bind/get-fn :epoch/reset-frame-db!)]
    (f frame-id new-db)
    (throw (ex-info ":rf.error/epoch-artefact-missing"
                    {:where    'reset-frame-db!
                     :recovery :no-recovery
                     :reason   "rf/reset-frame-db! requires day8/re-frame-2-epoch on the classpath; add it to deps and require re-frame.epoch at app boot."}))))

;; ---- Spec 014 — :rf.http/managed -----------------------------------------
;;
;; Per rf2-5kpd the `:rf.http/managed` family is registered at
;; re-frame.http-managed ns-load time (per Spec 014 §Implementation
;; status), but the namespace ships in the day8/re-frame-2-http
;; artefact. The core artefact does NOT `:require [re-frame.http-managed]`
;; — apps that don't issue managed-HTTP requests don't carry the
;; transport adapters or the `:rf.http/*` keyword strings on the
;; classpath. The test-helper wrappers below look the producing fns up
;; via the late-bind hook table; when the http artefact is not on the
;; classpath the lookups return nil and the wrappers raise
;; :rf.error/http-artefact-missing.

(defn install-managed-request-stubs!
  "Spec 014 §Testing — install per-call fx-overrides for `:rf.http/managed`
  that synthesise the configured replies. Late-bound via
  `:http/install-managed-request-stubs!`."
  [stubs]
  (if-let [f (late-bind/get-fn :http/install-managed-request-stubs!)]
    (f stubs)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'install-managed-request-stubs!
                     :recovery :no-recovery
                     :reason   "rf/install-managed-request-stubs! requires day8/re-frame-2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

(defn uninstall-managed-request-stubs!
  "Spec 014 §Testing — remove the per-call fx-override installed by
  `install-managed-request-stubs!`. Late-bound via
  `:http/uninstall-managed-request-stubs!`."
  []
  (if-let [f (late-bind/get-fn :http/uninstall-managed-request-stubs!)]
    (f)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'uninstall-managed-request-stubs!
                     :recovery :no-recovery
                     :reason   "rf/uninstall-managed-request-stubs! requires day8/re-frame-2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

(defn with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Late-bound via
  `:http/with-managed-request-stubs*`."
  [stubs thunk]
  (if-let [f (late-bind/get-fn :http/with-managed-request-stubs*)]
    (f stubs thunk)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'with-managed-request-stubs*
                     :recovery :no-recovery
                     :reason   "rf/with-managed-request-stubs* requires day8/re-frame-2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

#?(:clj
   (defmacro with-managed-request-stubs
     "Spec 014 §Testing — install stubs, run body, uninstall. `stubs` is
     `{[method url] {:reply <:ok|:failure>}}`.

     Per rf2-5kpd the http implementation lives in the
     `day8/re-frame-2-http` artefact; the emitted form looks the
     producing fn up via the late-bind hook table so core never
     statically requires it. Apps that use `with-managed-request-stubs`
     MUST add `day8/re-frame-2-http` to their deps and require
     `re-frame.http-managed` at app boot; without it, the lookup
     returns nil and the call throws a clear error."
     [stubs & body]
     `(if-let [f# (late-bind/get-fn :http/with-managed-request-stubs*)]
        (f# ~stubs (fn [] ~@body))
        (throw (ex-info ":rf.error/http-artefact-missing"
                        {:where    'with-managed-request-stubs
                         :recovery :no-recovery
                         :reason   "rf/with-managed-request-stubs requires day8/re-frame-2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."})))))

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
    ;; :epoch-history is published by the `day8/re-frame-2-epoch`
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

(def install-adapter!         adapter/install-adapter!)
(def dispose-adapter!         adapter/dispose-adapter!)
(def current-adapter          adapter/current-adapter)
(def register-default-adapter! adapter/register-default-adapter!)

;; ---- boot -----------------------------------------------------------------

(defn- resolve-init-adapter
  "Internal — turn an `init!` argument into an adapter spec map.

  Per rf2-84po (resolves rf2-4cb6) `init!` accepts:

    nil       — no-arg form; resolve via the default-adapter registry
                  populated by substrate-adapter ns-loads. If exactly one
                  adapter has registered as default, use it. If zero,
                  raise :rf.error/no-adapter-registered. If more than one,
                  raise :rf.error/multiple-default-adapters and surface
                  the registered keys so the consumer can disambiguate
                  via `(rf/init! :reagent)` / `(rf/init! :uix)`.

    keyword   — explicit pick from the default-adapter registry
                  (`:reagent`, `:uix`, `:plain-atom`). The keyword is a
                  contract surface — same name the substrate ns
                  registers itself under. Unknown keywords raise
                  :rf.error/unknown-adapter-key.

    map       — adapter spec map (a literal adapter); installed as-is.
                  Used by tests / examples that want to pass a custom
                  adapter (e.g. wrapped plain-atom for instrumentation)."
  [arg]
  (cond
    (nil? arg)
    (let [resolved (adapter/resolve-default-adapter)]
      (case (:error resolved)
        :rf.error/no-adapter-registered
        (throw (ex-info ":rf.error/no-adapter-registered"
                        {:where    'init!
                         :recovery :no-recovery
                         :reason   "rf/init! was called with no args but no substrate adapter has registered as a default. Add a substrate dep (day8/re-frame-2-reagent or day8/re-frame-2-uix) and (:require [re-frame.substrate.reagent]) (or .uix) at app boot — the require's ns-load registers the adapter as the default. Tests / atypical setups can pass an adapter spec or keyword explicitly: (rf/init! :reagent) / (rf/init! :plain-atom) / (rf/init! my-adapter-map)."}))

        :rf.error/multiple-default-adapters
        (throw (ex-info ":rf.error/multiple-default-adapters"
                        {:where    'init!
                         :keys     (:keys resolved)
                         :recovery :no-recovery
                         :reason   "rf/init! was called with no args but more than one substrate adapter has registered as a default. This is most often a mixed-substrate app post rf2-3yij where both re-frame.substrate.reagent and re-frame.substrate.uix were required; pick one explicitly via (rf/init! :reagent) or (rf/init! :uix)."}))
        ;; success
        (:adapter resolved)))

    (keyword? arg)
    (or (adapter/lookup-default-adapter arg)
        (throw (ex-info ":rf.error/unknown-adapter-key"
                        {:where    'init!
                         :key      arg
                         :known    (vec (sort (keys (adapter/registered-default-adapters))))
                         :recovery :no-recovery
                         :reason   "rf/init! was called with a keyword that is not registered in the default-adapter registry. Either require the substrate ns (which registers itself at ns-load time) or pass the adapter spec map directly."})))

    (map? arg)
    arg

    :else
    (throw (ex-info ":rf.error/bad-init-arg"
                    {:where    'init!
                     :received arg
                     :expected "nil | keyword | adapter-map"
                     :recovery :no-recovery
                     :reason   "rf/init! accepts no args (resolve via default-adapter registry), a keyword (`:reagent` / `:uix` / `:plain-atom`), or an adapter spec map."}))))

(defn init!
  "Idempotent boot. Installs a substrate adapter and ensures the
  `:rf/default` frame is present.

  Argument forms:

    (rf/init!)              ;; resolve from default-adapter registry
    (rf/init! :reagent)     ;; pick from registry by key
    (rf/init! adapter-map)  ;; install a literal adapter spec

  Per rf2-84po (resolves rf2-4cb6). The no-arg form is the canonical
  surface: substrate-adapter namespaces register themselves as default
  candidates at ns-load time, so an app that
  `(:require [re-frame.substrate.reagent])` boots with `(rf/init!)`
  alone. Tests and mixed-substrate apps disambiguate via the keyword
  form. Per Spec 006 §Adapter selection at boot."
  ([]        (init! nil))
  ([adapter-or-key]
   (when-not (adapter/current-adapter)
     (adapter/install-adapter! (resolve-init-adapter adapter-or-key)))
   (frame/ensure-default-frame!)
   nil))

;; ---- self-registration of framework subs ----------------------------------
;;
;; The `:rf/route` / `:rf.route/{id,params,query,transition,error}`
;; reg-subs live in `re-frame.routing` and register at that namespace's
;; load time, so the smoke-test fixture's `require :reload` recovers them
;; after `registrar/clear-all!`. Per rf2-k682 — routing ships in
;; `day8/re-frame-2-routing`.
;;
;; The `:rf/machine` reg-sub lives in `re-frame.machines` and registers
;; at that namespace's load time, similarly. Per Spec 005 §Subscribing
;; to machines via sub-machine and rf2-xbtj.
