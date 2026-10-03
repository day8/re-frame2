(ns rf2-playground.sci
  "The eval engine for the docs' `cljs-rf2` cells.

  A cell runs in an SCI context that exposes re-frame2's public API and
  renders its last form through reagent2. The bundle is a shadow-cljs
  `:browser` build of SCI, re-frame2 core, reagent-slim, machines, flows and
  schemas, with React 19 bundled in, and it installs `window.rf2sci` for the
  bootstrap (`src/playground.mjs`) to call. It is not a Scittle plugin,
  because Scittle's plugins ship stock reagent and re-frame.

  How the API reaches a cell:

    - `sci/copy-ns` over `re-frame.core` exposes its public vars. In CLJS the
      `reg-*` registrations are plain fns, so `(rf/reg-event :id (fn ...))`
      needs no macro support.
    - `reg-view`, `reg-machine` and `reg-flow` are JVM-only macros on the
      façade, so the SCI `re-frame.core` binds them to an SCI macro
      (`sci-reg-view`) and to the runtime fns `re-frame.machines/reg-machine*`
      and `re-frame.flows/reg-flow`. Cells write the same calls as real code.
    - `dispatch`, `dispatch-sync` and `subscribe` default to the playground's
      frame, so a frame-less cell still works. A cell that creates its own
      frame with `frame-root` routes through it as a real app would.

  Requiring `re-frame.machines`, `re-frame.flows` and `re-frame.schemas`
  installs their late-bind hooks and framework registrations (the
  `:rf/machine` sub, the `:rf.machine/*` fxs, the Malli validator) at bundle
  load."
  (:require [sci.core :as sci]
            [re-frame.core :as rf]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            ;; The constructor the `reg-view` expansion names. Bound under
            ;; its own SCI namespace, not merged into `re-frame.core`.
            [re-frame.capture-frame :as rf.capture-frame]
            ;; Used by `disposePage` to clear page-owned registrations; not
            ;; exposed to cells.
            [re-frame.registrar :as rf.registrar]
            [re-frame.views]
            [re-frame.machines]
            [re-frame.flows]
            [re-frame.schemas]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [reagent2.core :as r]
            [reagent2.ratom :as ratom]
            [reagent2.dom.client :as rdc]))

;; ---------------------------------------------------------------------------
;; SCI namespace configs
;; ---------------------------------------------------------------------------

;; The frame cells use unless they name their own. `renderLast` creates it on
;; demand (see `ensure-init!`).
(def ^:private app-frame :rf/default)

;; A cell's `:on-click` handler calls `dispatch` after render, outside any
;; `with-frame` scope, and the runtime never infers a frame (a bare call raises
;; `:rf.error/no-frame-context`). These wrappers make `app-frame` the default;
;; an explicit `:frame` in the cell's opts still wins. They call the owning
;; namespaces directly: the `rf/dispatch` macro would record this file as the
;; call site.
(defn- playground-dispatch
  ([event]      (rf.router/dispatch! event {:frame app-frame}))
  ([event opts] (rf.router/dispatch! event (merge {:frame app-frame} opts))))

(defn- playground-dispatch-sync
  ([event]      (rf.router/dispatch-sync! event {:frame app-frame}))
  ([event opts] (rf.router/dispatch-sync! event (merge {:frame app-frame} opts))))

(defn- playground-subscribe
  ([query-v]      (rf.subs/subscribe query-v {:frame app-frame}))
  ([query-v opts] (rf.subs/subscribe query-v (merge {:frame app-frame} opts))))

(def rf-ns (sci/create-ns 're-frame.core nil))

;; SCI version of the JVM `reg-view` macro (re-frame.core-reg-view-macro/
;; expand-reg-view). It registers the render fn with the real `reg-view*`,
;; binds `dispatch` and `subscribe` as locals from a render-time capture
;; frame, `def`s the var to `(rf/view id)`, and returns the id, as every
;; `reg-*` does. It derives the id from the cell's current ns (`:user/<sym>`
;; by default). It skips source-coord capture, which is optional (Spec 001)
;; and has no file to stamp in a browser cell.
(defn- sci-reg-view
  [_&form _&env sym & more]
  (let [[docstring more] (if (string? (first more))
                           [(first more) (rest more)]
                           [nil more])
        [args & body]    more]
    (when-not (and (symbol? sym) (vector? args) (seq body))
      (throw (ex-info (str "reg-view's second argument must be an args vector "
                           "(defn-shape: (reg-view sym [args] body)). Got: "
                           (pr-str (first more)))
                      {:rf.error/id :rf.error/reg-view-bad-args})))
    (let [;; Resolve the ns at eval time so a cell's own (ns ...) form shows through.
          id     (list 'keyword (list 'str (list 'ns-name '*ns*)) (str sym))
          handle (gensym "handle")]
      (list 'do
            (list 're-frame.core/reg-view* id
                  (if docstring {:doc docstring} {})
                  (list 'fn sym args
                        (list 'let
                              [handle     (list 're-frame.capture-frame/make-capture-frame
                                                (list 're-frame.core/current-frame-id)
                                                {:dispatch-opts {:source :ui}})
                               'dispatch  (list :dispatch handle)
                               'subscribe (list :subscribe handle)]
                              (cons 'do body))))
            (list 'def sym (list 're-frame.core/view id))
            id))))

;; Every public var of `re-frame.core`, with the frame-defaulting wrappers and
;; the macro stand-ins merged over the copied entries.
(def re-frame-core-namespace
  (merge
   (sci/copy-ns re-frame.core rf-ns)
   {'dispatch      (sci/copy-var playground-dispatch rf-ns)
    'dispatch-sync (sci/copy-var playground-dispatch-sync rf-ns)
    'subscribe     (sci/copy-var playground-subscribe rf-ns)
    'reg-machine   (sci/copy-var re-frame.machines/reg-machine* rf-ns)
    'reg-flow      (sci/copy-var re-frame.flows/reg-flow rf-ns)
    'reg-view      (sci/new-var 'reg-view sci-reg-view
                                {:ns rf-ns :macro true :sci/macro true})}))

;; Only the one var the `reg-view` expansion names, so the façade a cell sees
;; stays the real one.
(def capture-frame-ns (sci/create-ns 're-frame.capture-frame nil))
(def re-frame-capture-frame-namespace
  {'make-capture-frame (sci/copy-var rf.capture-frame/make-capture-frame capture-frame-ns)})

;; Lets a cell `(require '[re-frame.schemas])` and read registrations back.
(def schemas-ns (sci/create-ns 're-frame.schemas nil))
(def re-frame-schemas-namespace (sci/copy-ns re-frame.schemas schemas-ns))

(def r-ns (sci/create-ns 'reagent2.core nil))
(def reagent2-core-namespace (sci/copy-ns reagent2.core r-ns))

(def ratom-ns (sci/create-ns 'reagent2.ratom nil))
(def reagent2-ratom-namespace (sci/copy-ns reagent2.ratom ratom-ns))

(def rdc-ns (sci/create-ns 'reagent2.dom.client nil))
(def reagent2-dom-client-namespace (sci/copy-ns reagent2.dom.client rdc-ns))

;; `reagent.core` is an alias for `reagent2.core`, so a cell pasted from stock
;; reagent code still resolves.
(def sci-ctx
  (sci/init
   {:namespaces {'re-frame.core          re-frame-core-namespace
                 're-frame.capture-frame re-frame-capture-frame-namespace
                 're-frame.schemas       re-frame-schemas-namespace
                 'reagent2.core        reagent2-core-namespace
                 'reagent2.ratom       reagent2-ratom-namespace
                 'reagent2.dom.client  reagent2-dom-client-namespace
                 'reagent.core         reagent2-core-namespace}}))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

;; The adapter install is process-global: once per bundle load, never undone.
;; `disposePage` tears down frames but not the adapter, so it has its own flag.
(defonce ^:private adapter-inited? (atom false))

(defn- ensure-adapter! []
  (when-not @adapter-inited?
    (rf/init! rf.adapter.reagent-slim/adapter)
    (reset! adapter-inited? true)))

;; The app frame is page-owned: `disposePage` destroys it on each navigation
;; so the next page starts from an empty app-db. Create it whenever it is not
;; live.
(defn- ensure-app-frame! []
  (when-not (contains? (rf/frame-ids) app-frame)
    (rf/make-frame {:id app-frame})))

;; Cells register into the one process-global registrar. To tell a cell's
;; registrations from the framework's (the machines and flows artefacts'
;; subs and fxs, the adapter's installs), snapshot the registrar once, after
;; the adapter installs and before any cell runs. `disposePage` clears
;; everything outside this baseline. {kind #{id …}}, nil until captured.
(defonce ^:private baseline-registrations (atom nil))

(defn- capture-registration-baseline! []
  (when (nil? @baseline-registrations)
    (reset! baseline-registrations
            (into {} (map (fn [k] [k (rf.registrar/ids k)])) rf.registrar/kinds))))

(defn- ensure-init! []
  (ensure-adapter!)
  (capture-registration-baseline!)
  (ensure-app-frame!))

;; ---------------------------------------------------------------------------
;; JS entry points
;; ---------------------------------------------------------------------------

;; React roots keyed by result element, so a re-run renders into the same root.
(defonce ^:private roots (atom {}))

(defn- frame-bind-component
  "Wraps the head fn of a component vector so it renders inside
  `(rf/with-frame app-frame ...)`.

  A cell's component is usually a plain `defn`, not a `reg-view`. The
  `frame-provider` context reaches only `reg-view` components, so a plain
  fn's render-time `subscribe` would find no frame and throw. Re-binding the
  frame on every call fixes that. A non-fn head, such as `[:div ...]`, is
  returned unchanged."
  [component]
  (if (and (vector? component)
           (fn? (first component)))
    (let [f (first component)]
      (assoc component 0 (fn [& args] (rf/with-frame app-frame (apply f args)))))
    component))

(defn ^:export renderLast
  "Evaluates `src` and mounts its last form into `target-el`.

  The source is evaluated at the SCI top level, not wrapped in `(do ...)`, so
  a leading `(require ...)`'s aliases reach the forms after it. A vector is
  rendered as hiccup or a component; any other value is shown with `pr-str`,
  so a cell that ends in a `reg-*` call shows the registered id. A second call
  for the same element re-renders in place."
  [src target-el]
  (ensure-init!)
  ;; Evaluate under the app frame so a top-level `dispatch-sync` resolves.
  (let [component (rf/with-frame app-frame
                    (sci/eval-string* sci-ctx src))
        root (or (get @roots target-el)
                 (let [rt (rdc/create-root target-el)]
                   (swap! roots assoc target-el rt)
                   rt))]
    ;; `{:frame …}` scopes `reg-view` descendants to the existing app frame;
    ;; `frame-bind-component` covers a plain-fn head.
    (rdc/render root [rf/frame-provider {:frame app-frame}
                      (if (vector? component)
                        (frame-bind-component component)
                        [:code (pr-str component)])])
    nil))

;; ---------------------------------------------------------------------------
;; Instant-navigation teardown
;; ---------------------------------------------------------------------------

(defn ^:export disposePage
  "Releases everything the outgoing page's cells created. The bootstrap calls
  it on each Material instant navigation, before the next page's cells mount.

    1. React roots. Navigation discards the cells' DOM without telling React,
       so each cached root is unmounted, releasing its components and
       subscriptions.
    2. Frames. Cells reuse ids such as `:app` and `:rf/default`, and creating
       a frame whose id already exists keeps its app-db and skips its
       `:initial-events` (Spec 002 §Duplicate id policy). Destroying every
       frame makes the next page start from its documented initial state, and
       drops any app-db schemas registered against it.
    3. Registrations. Without this, a later page could dispatch to a handler
       it never registered and reach the outgoing page's. Everything outside
       the baseline captured in `ensure-init!` is unregistered, so that
       dispatch raises `:rf.error/no-such-handler` (or `-sub`) instead.

  The adapter and the framework's own registrations survive. Each step is
  guarded so one failure cannot strand the rest. Safe to call when nothing
  was created. Returns nil."
  []
  (doseq [[_el root] @roots]
    (try (rdc/unmount root) (catch :default _ nil)))
  (reset! roots {})
  ;; `frame-ids` returns a snapshot, so destroying frames cannot disturb the loop.
  (doseq [id (rf/frame-ids)]
    (try (rf/destroy-frame! id) (catch :default _ nil)))
  ;; Nothing to clear before any cell has run (baseline is nil).
  (when-let [baseline @baseline-registrations]
    (doseq [kind rf.registrar/kinds]
      (let [baseline-ids (get baseline kind #{})]
        (doseq [id (rf.registrar/ids kind)]
          (when-not (contains? baseline-ids id)
            (try (rf.registrar/unregister! kind id) (catch :default _ nil)))))))
  nil)

;; ---------------------------------------------------------------------------
;; window.rf2sci
;; ---------------------------------------------------------------------------

(defn ^:export init []
  (set! (.-rf2sci js/window)
        #js {:renderLast   renderLast
             :disposePage  disposePage
             ;; For the smoke: proves `disposePage` released the roots.
             :liveRootCount (fn [] (count @roots))}))

(init)
