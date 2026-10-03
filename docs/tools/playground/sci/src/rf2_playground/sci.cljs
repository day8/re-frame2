(ns rf2-playground.sci
  "The eval engine for the docs' `cljs-rf2` cells.

  A cell runs in an SCI context that exposes re-frame2's public API, then
  renders its last form. The bundle is a shadow-cljs `:browser` build of SCI,
  re-frame2 core, reagent-slim, Fresco and every optional artefact (machines,
  flows, schemas, HTTP, resources, routing, epoch and SSR), with React 19
  bundled in. It installs `window.rf2sci` for the bootstrap
  (`src/playground.mjs`) to call. It is not a Scittle plugin, because
  Scittle's plugins ship stock reagent and re-frame.

  The last form renders through Fresco when it contains a Fresco head (an
  `h/defview`, an `h/defhost`, or `h/frame-root` / `h/frame-provider`) or a
  handler written as data (`:on-click [:inc]`), and through reagent2
  otherwise. Plain hiccup renders the same either way. A Fresco view cannot
  be a child in a reagent2 tree, so one tree uses one renderer; different
  cells may use different ones.

  How the API reaches a cell:

    - `sci/copy-ns` over `re-frame.core` exposes its public vars. In CLJS the
      `reg-*` registrations, `reg-resource` and `reg-route` included, are
      plain fns, so `(rf/reg-event :id (fn ...))` needs no macro support.
    - `reg-view`, `reg-machine` and `reg-flow` are JVM-only macros on the
      façade, so the SCI `re-frame.core` binds them to an SCI macro
      (`sci-reg-view`) and to the runtime fns `re-frame.machines/reg-machine*`
      and `re-frame.flows/reg-flow`. Cells write the same calls as real code.
    - Fresco's `defview` and `event` are JVM-only macros too, so the SCI
      `re-frame.fresco` binds them to SCI macros that expand to the same
      runtime calls.
    - `dispatch`, `dispatch-sync` and `subscribe` default to the playground's
      frame, so a frame-less cell still works. A cell that creates its own
      frame with `frame-root` routes through it as a real app would.

  Requiring each optional artefact installs its late-bind hooks and
  framework registrations (the `:rf/machine` sub, `:rf.http/managed`, the
  `:rf.resource/*` events and subs, and so on) at bundle load.
  `re-frame.http.test-support` is in the bundle too, so a cell can stub
  `:rf.http/managed` and run HTTP and resource demos with no network."
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
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.routing]
            [re-frame.epoch]
            [re-frame.ssr]
            [re-frame.fresco :as h]
            [re-frame.fresco.forms]
            [re-frame.fresco.overlay]
            [re-frame.fresco.motion]
            ;; The runtime fns Fresco's `defview` and `event` expand to, and
            ;; the head markers `renderLast` reads.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]
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

;; SCI versions of Fresco's JVM `defview` and `event` macros
;; (re-frame.fresco). `defview` mints the boundary and publishes its `:view`
;; registrar alias, deriving the name from the cell's ns at eval time. It skips
;; the declaration extent that only attributes source coordinates.
(defn- sci-defview
  [_&form _&env sym & more]
  (let [[doc more]    (if (string? (first more))
                        [(first more) (rest more)]
                        [nil more])
        [argv & body] more]
    (when-not (and (symbol? sym) (vector? argv))
      (throw (ex-info (str "defview takes a name and an args vector: "
                           "(h/defview sym [props] body). Got: "
                           (pr-str (first more)))
                      {:sym sym})))
    (let [head (gensym "head")]
      (list 'def sym
            (list 'let [head (list 're-frame.fresco.impl.collector/mint-view!
                                   (list 'str (list 'ns-name '*ns*) "/" (str sym))
                                   (list* 'fn argv body))]
                  (list 're-frame.fresco.impl.collector/publish-view-alias!
                        (list 'keyword (list 'str (list 'ns-name '*ns*)) (str sym))
                        (if doc {:doc doc} {})
                        head)
                  head)))))

(defn- sci-event
  [_&form _&env argv & body]
  (list 're-frame.fresco.impl.intent/callback (list* 'fn argv body)))

(def fresco-ns (sci/create-ns 're-frame.fresco nil))
(def re-frame-fresco-namespace
  (merge
   (sci/copy-ns re-frame.fresco fresco-ns)
   {'defview (sci/new-var 'defview sci-defview {:ns fresco-ns :macro true :sci/macro true})
    'event   (sci/new-var 'event sci-event {:ns fresco-ns :macro true :sci/macro true})}))

;; Only the vars the two macros expand to.
(def fresco-collector-ns (sci/create-ns 're-frame.fresco.impl.collector nil))
(def fresco-intent-ns (sci/create-ns 're-frame.fresco.impl.intent nil))
(def re-frame-fresco-collector-namespace
  {'mint-view!          (sci/copy-var rf.fresco.impl.collector/mint-view! fresco-collector-ns)
   'publish-view-alias! (sci/copy-var rf.fresco.impl.collector/publish-view-alias! fresco-collector-ns)})
(def re-frame-fresco-intent-namespace
  {'callback (sci/copy-var rf.fresco.impl.intent/callback fresco-intent-ns)})

(def fresco-forms-ns (sci/create-ns 're-frame.fresco.forms nil))
(def fresco-overlay-ns (sci/create-ns 're-frame.fresco.overlay nil))
(def fresco-motion-ns (sci/create-ns 're-frame.fresco.motion nil))

(def http-managed-ns (sci/create-ns 're-frame.http.managed nil))
(def http-test-support-ns (sci/create-ns 're-frame.http.test-support nil))
(def resources-ns (sci/create-ns 're-frame.resources nil))
(def routing-ns (sci/create-ns 're-frame.routing nil))
(def epoch-ns (sci/create-ns 're-frame.epoch nil))
(def ssr-ns (sci/create-ns 're-frame.ssr nil))

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
                 're-frame.http.managed      (sci/copy-ns re-frame.http.managed http-managed-ns)
                 're-frame.http.test-support (sci/copy-ns re-frame.http.test-support http-test-support-ns)
                 're-frame.resources         (sci/copy-ns re-frame.resources resources-ns)
                 're-frame.routing           (sci/copy-ns re-frame.routing routing-ns)
                 're-frame.epoch             (sci/copy-ns re-frame.epoch epoch-ns)
                 're-frame.ssr               (sci/copy-ns re-frame.ssr ssr-ns)
                 're-frame.fresco        re-frame-fresco-namespace
                 're-frame.fresco.impl.collector re-frame-fresco-collector-namespace
                 're-frame.fresco.impl.intent    re-frame-fresco-intent-namespace
                 're-frame.fresco.forms   (sci/copy-ns re-frame.fresco.forms fresco-forms-ns)
                 're-frame.fresco.overlay (sci/copy-ns re-frame.fresco.overlay fresco-overlay-ns)
                 're-frame.fresco.motion  (sci/copy-ns re-frame.fresco.motion fresco-motion-ns)
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

;; The React root behind each result element: `{:reagent root}` or
;; `{:fresco handle}`.
(defonce ^:private roots (atom {}))

(defn- release!
  "Unmounts and forgets the root behind `el`, if any."
  [el]
  (when-let [{:keys [reagent fresco]} (get @roots el)]
    (swap! roots dissoc el)
    (try (if reagent (rdc/unmount reagent) (h/unmount! fresco))
         (catch :default _ nil))))

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

(defn- fresco-head? [x]
  (or (rf.fresco.impl.codec/boundary-head? x)
      (rf.fresco.impl.codec/host-head? x)
      (rf.fresco.impl.codec/frame-boundary-head? x)))

;; A handler written as data — an intent vector, a key map or an `h/event`
;; callback — at an `on-*` prop. Fresco runs these; reagent2 needs a plain fn.
(defn- fresco-props? [x]
  (and (map? x)
       (boolean
        (some (fn [[k v]]
                (and (keyword? k)
                     (re-find #"^on[-A-Z]" (name k))
                     (or (vector? v) (map? v) (rf.fresco.impl.intent/callback? v))))
              x))))

(defn- fresco-tree?
  "Does the hiccup `x` need Fresco: a Fresco head, or a handler written as
  data, anywhere in the tree? Plain hiccup renders the same either way."
  [x]
  (cond
    (vector? x) (or (fresco-head? (first x))
                    (fresco-props? (second x))
                    (boolean (some fresco-tree? (rest x))))
    (seq? x)    (boolean (some fresco-tree? x))
    :else       false))

(defn- render-fresco! [value el]
  ;; A fresh root per run: a committed `h/frame-root` refuses new options, so
  ;; re-rendering an edited cell into the old root would fail.
  (release! el)
  (let [handle (h/client-root)]
    (swap! roots assoc el {:fresco handle})
    (h/render! handle [h/frame-provider {:frame app-frame} value] el)))

(defn- render-reagent! [value el]
  (let [root (or (:reagent (get @roots el))
                 (do (release! el)
                     (let [rt (rdc/create-root el)]
                       (swap! roots assoc el {:reagent rt})
                       rt)))]
    ;; `{:frame …}` scopes `reg-view` descendants to the existing app frame;
    ;; `frame-bind-component` covers a plain-fn head.
    (rdc/render root [rf/frame-provider {:frame app-frame}
                      (if (vector? value)
                        (frame-bind-component value)
                        [:code (pr-str value)])])))

(defn ^:export renderLast
  "Evaluates `src` and mounts its last form into `target-el`.

  The source is evaluated at the SCI top level, not wrapped in `(do ...)`, so
  a leading `(require ...)`'s aliases reach the forms after it. A tree with a
  Fresco head renders through Fresco; any other vector renders through
  reagent2, re-rendering in place on a second call for the same element. Any
  other value is shown with `pr-str`, so a cell that ends in a `reg-*` call
  shows the registered id."
  [src target-el]
  (ensure-init!)
  ;; Evaluate under the app frame so a top-level `dispatch-sync` resolves.
  (let [value (rf/with-frame app-frame
                (sci/eval-string* sci-ctx src))]
    (if (fresco-tree? value)
      (render-fresco! value target-el)
      (render-reagent! value target-el))
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
    4. HTTP interceptors. They are keyed by frame id outside the registrar,
       so a later page's frame of the same id would otherwise inherit them.

  Destroying a frame already aborts its in-flight requests and cancels its
  retry, resource and `:dispatch-later` timers.

  The adapter and the framework's own registrations survive. Each step is
  guarded so one failure cannot strand the rest. Safe to call when nothing
  was created. Returns nil."
  []
  (run! release! (keys @roots))
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
  (try (rf.http.managed/clear-all-http-interceptors!) (catch :default _ nil))
  nil)

;; ---------------------------------------------------------------------------
;; window.rf2sci
;; ---------------------------------------------------------------------------

(defn ^:export init []
  (set! (.-rf2sci js/window)
        #js {:renderLast    renderLast
             :disposePage   disposePage
             ;; The bootstrap calls this before writing an error into a
             ;; result element, so React never owns a node it did not render.
             :release       release!
             ;; For the smoke: proves `disposePage` released the roots.
             :liveRootCount (fn [] (count @roots))}))

(init)
