(ns re-frame.resources.revalidate-listeners
  "Host window-focus and network-reconnect listeners that feed the resource
  revalidation events at a frame:

    window  focus                         -> [:rf.resource/window-focused]
    document visibilitychange-to-visible  -> [:rf.resource/window-focused]
    window  online                        -> [:rf.resource/network-reconnected]

  The listener carries no policy; the event handlers in
  `re-frame.resources.events` do the active-stale scan and refetch. The
  frame's `:revalidate-on` key declares which listeners exist, and the frame
  lifecycle hooks reconcile and tear them down, so there is no install/remove
  fn. The JVM arm installs nothing, so `.cljc` boot code requires this
  namespace without a DOM.
  Spec 016 §Revalidation is a frame property (`:revalidate-on`)."
  (:require [re-frame.late-bind :as rf.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def window-focused-event
  "The event a window-focus (tab-return) signal dispatches; its handler
  refetches the frame's active stale entries with cause `:focus`. Dispatched
  by the host listener, never by user code. Spec 016 §Stale and GC scheduling."
  :rf.resource/window-focused)

(def network-reconnected-event
  "The event a network-reconnect signal dispatches; its handler refetches the
  frame's active stale entries with cause `:reconnect`. Dispatched by the
  host listener, never by user code. Spec 016 §Stale and GC scheduling."
  :rf.resource/network-reconnected)

;; ---- host-side listener side table ----------------------------------------

(defonce
  ^{:doc "Host-side side table: `frame-id` → `{:focus … :visibility … :online …}`
   listener handles (CLJS), absent on the JVM. Transient host state rather
   than runtime-db, so an epoch restore cannot rewind it and it never rides
   the wire. Cleared per frame by `release-frame!`.
   Runtime-Subsystems §Host-transient subsystem state."}
  listener-table
  (atom {}))

(defn dispatch-revalidation!
  "Dispatches `event-id` at `frame-id` with `:source :revalidate`, through
  the `:router/dispatch!` late-bind hook because the resources artefact never
  requires the router. No-op when no dispatcher is bound."
  [frame-id event-id]
  (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (dispatch! [event-id] {:frame frame-id :source :revalidate})))

#?(:cljs
   (defn- browser-window
     "The `js/window` object, or nil when no DOM is present (node / SSR)."
     []
     (when (exists? js/window) js/window)))

#?(:cljs
   (defn- browser-document
     "The `js/document` object, or nil when no DOM is present.
     `visibilitychange` fires only on `document`, so its listener lives here."
     []
     (when (exists? js/document) js/document)))

#?(:cljs
   (defn- remove-frame-listeners!
     "Detaches and drops `frame-id`'s installed listeners. Idempotent."
     [frame-id]
     (when-let [{:keys [focus visibility online]} (get @listener-table frame-id)]
       (try (when-let [window-target (browser-window)]
               (when focus  (.removeEventListener window-target "focus" focus))
               (when online (.removeEventListener window-target "online" online)))
             (when-let [document-target (browser-document)]
               (when visibility
                 (.removeEventListener document-target "visibilitychange" visibility)))
             (catch :default _ nil)))
     (swap! listener-table dissoc frame-id)
     nil))

(def revalidation-triggers
  "The closed enum `:revalidate-on` draws from. `:focus` covers both
  tab-return signals (window `focus` and document visibilitychange-to-visible);
  `:reconnect` is window `online`. Which resources then refetch is the
  resource-level stale/owner policy.
  Spec 016 §Revalidation is a frame property (`:revalidate-on`)."
  #{:focus :reconnect})

(defn reconcile-listeners!
  "Installs exactly the `triggers` subset of `revalidation-triggers` for
  `frame-id`, replacing whatever it had, so a repeated registration never
  stacks a listener. Nil or `#{}` removes them all; unknown members select
  nothing. It replaces rather than diffs: identical triggers re-attach fresh
  handlers, which is observationally inert. The JVM arm only drops the
  side-table slot. Called by the `:resources/on-frame-registered!` hook.
  Returns nil. Spec 016 §Revalidation is a frame property (`:revalidate-on`)."
  [frame-id triggers]
  #?(:cljs
     (let [triggers   (set triggers)
           focus?     (contains? triggers :focus)
           reconnect? (contains? triggers :reconnect)]
       ;; Replace, don't stack; also the relinquish path for an empty set.
       (remove-frame-listeners! frame-id)
       (when-let [window-target (and (or focus? reconnect?) (browser-window))]
         (let [document-target    (browser-document)
               focus-handler      (when focus?
                                    (fn [_e] (dispatch-revalidation! frame-id window-focused-event)))
               visibility-handler (when focus?
                                    (fn [_e]
                                      ;; visibilitychange fires on hide too;
                                      ;; only the return to visible revalidates.
                                      (when (and document-target
                                                 (= "visible" (.-visibilityState document-target)))
                                        (dispatch-revalidation! frame-id window-focused-event))))
               online-handler     (when reconnect?
                                    (fn [_e] (dispatch-revalidation! frame-id network-reconnected-event)))]
           (when focus-handler
             (.addEventListener window-target "focus" focus-handler))
           (when (and visibility-handler document-target)
             (.addEventListener document-target "visibilitychange" visibility-handler))
           (when online-handler
             (.addEventListener window-target "online" online-handler))
           (swap! listener-table assoc frame-id
                  {:focus focus-handler :visibility visibility-handler :online online-handler})))
       nil)
     :clj (do (swap! listener-table dissoc frame-id) nil)))

;; ---- frame teardown -------------------------------------------------------

(defn release-frame!
  "Removes `frame-id`'s listeners: the empty reconcile, not a second code
  path. Idempotent; returns nil. Runtime-Subsystems §5. Teardown"
  [frame-id]
  (reconcile-listeners! frame-id nil))

(defn on-frame-destroyed!
  "The listener half of the `:resources/on-frame-destroyed!` teardown hook,
  which the façade composes with the attempt-handle and timer releases.
  Returns nil. Runtime-Subsystems §5. Teardown"
  [frame-id]
  (release-frame! frame-id))

(defn reset-cache!
  "Removes every frame's listeners, for test isolation; the runtime and
  frames reset does not clear this host-side state. Returns nil."
  []
  (doseq [frame-id (keys @listener-table)]
    (reconcile-listeners! frame-id nil))
  (reset! listener-table {})
  nil)
