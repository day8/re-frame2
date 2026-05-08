(ns re-frame.core
  "Public API surface for re-frame2. Per docs/specification/API.md.

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
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.machines :as machines]
            [re-frame.routing :as routing]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]
            [re-frame.epoch :as epoch]
            [re-frame.http-managed :as http-managed]
            [re-frame.substrate.adapter :as adapter]
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
;; events/reg-event-db etc. — internal callers (re-frame.routing,
;; re-frame.ssr) reach the fn directly and don't pay the macro tax.
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

(defn get-view
  "Return the render fn for a registered view by id, or nil if not
  registered. The wrapped fn called with the view's invocation args
  yields the hiccup tree."
  [id]
  (when-let [meta (registrar/lookup :view id)]
    (:handler-fn meta)))

(defn view
  "Runtime-lookup handle for a registered view. Returns the registered
  render fn (whatever shape — Form-1, Form-2 — produced by `reg-view`
  or `reg-view*`) or nil if not registered. Per Spec 001 §`(re-frame.core/view id)`
  and Spec 004 §Calling a registered view: render trees use Vars;
  runtime lookups use ids; this is the id-keyed lookup."
  [id]
  (get-view id))

(defn render-to-string
  "Render a hiccup tree to an HTML string. Per Spec 011 §The render-tree
  → HTML emitter. Delegates to the installed substrate adapter's
  :render-to-string slot — for the plain-atom adapter (JVM/SSR) that
  routes through re-frame.ssr; for Reagent it can route through
  reagent.dom.server. opts may carry :doctype? to prepend '<!DOCTYPE html>'
  and :emit-hash? to inject data-rf-render-hash on the root element."
  ([render-tree] (render-to-string render-tree {}))
  ([render-tree opts]
   ;; On JVM the plain-atom adapter's :render-to-string requires that
   ;; re-frame.ssr has been loaded so it can bind *hiccup-emitter*.
   ;; Force the load lazily — otherwise the adapter throws a clear error.
   #?(:clj (try (require 're-frame.ssr) (catch Throwable _ nil)))
   (adapter/render-to-string render-tree opts)))

(defn render-tree-hash
  "Stable structural hash of a render tree (FNV-1a 32-bit, lowercase
  hex). Identical output on JVM and CLJS for the same canonical-EDN
  representation. Per Spec 011 §Hydration-mismatch detection."
  [render-tree]
  #?(:clj (try (require 're-frame.ssr) (catch Throwable _ nil)))
  (let [hash-fn #?(:clj  (requiring-resolve 're-frame.ssr/render-tree-hash)
                   :cljs (resolve 're-frame.ssr/render-tree-hash))]
    (when hash-fn
      #?(:clj  ((deref hash-fn) render-tree)
         :cljs (hash-fn render-tree)))))
(def make-frame      frame/make-frame)
(def reset-frame     frame/reset-frame!)
(def destroy-frame   frame/destroy-frame!)
(def clear-flow      flows/clear-flow)

#?(:clj
   (defmacro reg-flow
     "Register a flow. Per Spec 001 the metadata stamped onto the
     registry slot includes :ns / :line / :file captured at this call
     site."
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
          (flows/reg-flow ~@args)))))

#?(:clj
   (defmacro reg-route
     "Register a route. Per Spec 001 the metadata stamped onto the
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
          (routing/reg-route ~id ~metadata)))))

#?(:clj
   (defmacro reg-app-schema
     "Register a Malli schema at a path inside app-db. Per Spec 001 the
     metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site."
     [path schema]
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
          (schemas/reg-app-schema ~path ~schema)))))

;; Schema introspection — pure fn aliases (no source-coord capture
;; needed, these are read-only public queries).
(def app-schema-at   schemas/app-schema-at)
(def app-schemas     schemas/app-schemas)

#?(:clj
   (defmacro reg-machine
     "Register a machine as an event handler. Per Spec 001 the
     metadata stamped onto the registry slot includes :ns / :line /
     :file captured at this call site."
     [machine-id machine]
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
          (machines/reg-machine ~machine-id ~machine)))))

#?(:cljs
   (do
     (def reg-flow        flows/reg-flow)
     (def reg-route       routing/reg-route)
     (def reg-app-schema  schemas/reg-app-schema)
     (def reg-machine     machines/reg-machine)))

;; reg-error-projector lives in re-frame.ssr so the registry kind
;; ships with its default :rf.ssr/default-error-projector. Forward
;; through requiring-resolve to avoid a top-level :require cycle
;; (ssr.cljc requires events/fx/registrar which core also requires).
(defn -reg-error-projector
  "Internal helper — prefer `reg-error-projector` from public callers.
  This is the fn-form delegate the public macro / CLJS alias forward to.
  Forwards through requiring-resolve to keep the load order acyclic."
  ([id projector-fn]
   (-reg-error-projector id {} projector-fn))
  ([id metadata projector-fn]
   #?(:clj (try (require 're-frame.ssr) (catch Throwable _ nil)))
   (let [f #?(:clj  (requiring-resolve 're-frame.ssr/reg-error-projector)
              :cljs (resolve 're-frame.ssr/reg-error-projector))]
     (f id metadata projector-fn))))

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
  Returns a :rf/public-error map. Per Spec 011 §Server error projection."
  [frame-id trace-event]
  #?(:clj (try (require 're-frame.ssr) (catch Throwable _ nil)))
  (let [f #?(:clj  (requiring-resolve 're-frame.ssr/project-error)
             :cljs (resolve 're-frame.ssr/project-error))]
    (f frame-id trace-event)))

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

(def match-url routing/match-url)
(def route-url routing/route-url)

;; ---- machine helpers ------------------------------------------------------

(def create-machine-handler machines/create-machine-handler)
(def machine-transition     machines/machine-transition)
(def machines               machines/machines)
(def machine-meta           machines/machine-meta)

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
(def frames       (fn [] (frame/frame-ids)))
(def frame-meta   frame/frame-meta)
(def get-frame-db frame/frame-app-db-value)

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
;; All elided in production via `interop/debug-enabled?`.

(def epoch-history     epoch/epoch-history)
(def restore-epoch     epoch/restore-epoch)
(def register-epoch-cb epoch/register-epoch-cb!)
(def remove-epoch-cb   epoch/remove-epoch-cb!)

;; ---- Spec 014 — :rf.http/managed -----------------------------------------
;;
;; The `:rf.http/managed` family is registered at re-frame.http-managed
;; ns-load time (per Spec 014 §Implementation status). User code reaches
;; the test-time helpers through this ns.

(def install-managed-request-stubs!   http-managed/install-managed-request-stubs!)
(def uninstall-managed-request-stubs! http-managed/uninstall-managed-request-stubs!)
(def with-managed-request-stubs*      http-managed/with-managed-request-stubs*)
#?(:clj
   (defmacro with-managed-request-stubs
     "Spec 014 §Testing — install stubs, run body, uninstall. `stubs` is
     `{[method url] {:reply <:ok|:failure>}}`."
     [stubs & body]
     `(http-managed/with-managed-request-stubs* ~stubs (fn [] ~@body))))

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
    :epoch-history (epoch/configure! opts)
    :trace-buffer  (trace/configure-trace-buffer! opts)
    :sub-cache     (subs/configure! opts)
    nil))

;; ---- substrate adapter ----------------------------------------------------

(def install-adapter!  adapter/install-adapter!)
(def dispose-adapter!  adapter/dispose-adapter!)

;; ---- boot -----------------------------------------------------------------

(defn init!
  "Idempotent boot. Installs an adapter (defaulting to plain-atom for JVM /
  headless / SSR — CLJS browser apps should pass the Reagent adapter
  explicitly). Ensures :rf/default frame is present."
  ([] (init! plain-atom/adapter))
  ([adapter]
   (when-not (adapter/current-adapter)
     (adapter/install-adapter! adapter))
   (frame/ensure-default-frame!)
   nil))

;; ---- self-registration of framework subs ----------------------------------

(reg-sub :rf/route routing/route-sub-fn)
(reg-sub :rf.route/id     :<- [:rf/route] (fn [route _] (:id route)))
(reg-sub :rf.route/params :<- [:rf/route] (fn [route _] (:params route)))
(reg-sub :rf.route/query  :<- [:rf/route] (fn [route _] (:query route)))
(reg-sub :rf.route/transition :<- [:rf/route] (fn [route _] (:transition route)))
(reg-sub :rf.route/error  :<- [:rf/route] (fn [route _] (:error route)))

;; :rf/machine reg-sub lives in re-frame.machines and registers at that
;; namespace's load time, so the smoke-test fixture's require :reload
;; recovers it after registrar/clear-all!. Per Spec 005 §Subscribing to
;; machines via sub-machine.
