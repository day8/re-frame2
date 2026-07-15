(ns re-frame.ui.events
  "Commit-owned native event callbacks for compiled views.

  Render records an ownership-free candidate table.  A layout commit publishes
  that exact table to the component's EventOwner; abandoned renders therefore
  cannot retarget a callback.  The DOM receives one stable callback per lexical
  site, while invocation reads the latest COMMITTED event template and locked
  frame operations."
  (:require [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace :include-macros true]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.rules :as rules]))

;; Private object sentinels distinguish compiled placeholders from an authored
;; runtime vector that merely happens to contain the same keyword.  Identity is
;; intentional: keywords cannot carry this provenance through a dynamic value.
(def ^js value-placeholder   #js {})
(def ^js checked-placeholder #js {})
(def ^js key-placeholder     #js {})

(def ^:private sync-flag 1)
(def ^:private prevent-default-flag 2)
(def ^:private stop-propagation-flag 4)
(def ^:private once-flag 8)

(def ^:dynamic *capture* nil)

(defn make-owner
  "Create the commit-owned state for one mounted compiled-view instance."
  [view-id]
  (atom {:view-id view-id
         :lifecycle :new
         :committed {}
         :callbacks {}
         :native-refs {}
         :once-fired #{}}))

(defn with-capture
  "Evaluate `thunk` under a fresh event candidate.  Returns [element capture].
  Resolving the ambient frame is lazy, so the stable dev wrapper may install
  this capability without requiring a frame from event-free views."
  [owner frame-id thunk]
  (let [candidate (atom {:sites {}
                         :callbacks {}
                         :native-refs {}
                         :frame-id frame-id})
        element   (binding [*capture* {:owner owner :candidate candidate}]
                    (thunk))]
    [element @candidate]))

(defn- publish-commit!
  [owner capture frame-ops]
  (let [sites (:sites capture)
        committed (reduce-kv (fn [m k site]
                               (assoc m k (assoc site :frame-ops frame-ops)))
                             {} sites)]
    (swap! owner
           (fn [state]
             (-> state
                 (assoc :lifecycle :connected
                        :committed committed
                        :native-refs (:native-refs capture))
                 (update :callbacks merge (:callbacks capture))
                 (update :once-fired
                         #(into #{} (filter (set (keys sites))) %))))))
  nil)

(defn commit!
  "Publish the winning render's exact event table.  Callback identity is kept
  independently from the table, so provider retargets update the destination
  without changing the function installed on the DOM node.

  The three-argument arity is the narrow host-agnostic test seam: it supplies a
  frame-op bundle directly, allowing callback semantics to be tested without a
  React host or the retired process-global dispatch hook."
  ([owner capture]
   (let [sites (:sites capture)
         frame-ops (when (seq sites)
                     (if-some [frame-id (:frame-id capture)]
                       (frames/frame-ops-for frame-id)
                       ;; Preserve the canonical no-frame-context failure for a
                       ;; client commit outside any provider. SSR never runs
                       ;; layout effects and therefore never reaches this read.
                       (frames/frame-ops)))]
     (publish-commit! owner capture frame-ops)))
  ([owner capture frame-ops]
   (publish-commit! owner capture frame-ops)))

(defn disconnect!
  [owner]
  ;; Native DOM ownership belongs to the composed React ref. React calls its
  ;; cleanup during node replacement/unmount (and StrictMode ref replay); the
  ;; lifecycle fence here only makes an in-flight callback inert immediately.
  (swap! owner assoc
         :lifecycle :disconnected
         :committed {}
         :native-refs {}
         :once-fired #{})
  nil)

(defn- capture-or-throw! []
  (or *capture*
      (error/throw-error!
       :rf.error/ui-tree-malformed 're-frame.ui/render
       "compiled event handler executed outside its owning render boundary"
       {:extra {:reason :event-site-outside-capture}})))

(defn- project-event
  [template native-event]
  (let [target (unchecked-get native-event "target")]
    (mapv (fn [x]
            (cond
              (identical? x value-placeholder)
              (unchecked-get target "value")

              (identical? x checked-placeholder)
              (unchecked-get target "checked")

              (identical? x key-placeholder)
              (unchecked-get native-event "key")

              :else x))
          template)))

(defn- debug-dispatch-opts
  [{:keys [view-id sid source-coord path classification]}]
  (if interop/debug-enabled?
    {:source :ui
     :source-detail {:view-id view-id
                     :site-id sid
                     :occurrence-path path
                     :classification classification}
     :rf.trace/call-site source-coord}
    {:source :ui}))

(defn- invoke-site!
  [owner site-key native-event]
  (when-let [{:keys [kind value flags frame-ops debug-site]}
             (get-in @owner [:committed site-key])]
    (when-not (and (pos? (bit-and flags once-flag))
                   (contains? (:once-fired @owner) site-key))
      (when (pos? (bit-and flags once-flag))
        (swap! owner update :once-fired conj site-key))
      (when (pos? (bit-and flags prevent-default-flag))
        (.preventDefault native-event))
      (when (pos? (bit-and flags stop-propagation-flag))
        (.stopPropagation native-event))
      (case kind
        :data
        (let [event (project-event value native-event)
              opts  (debug-dispatch-opts debug-site)]
          (if (pos? (bit-and flags sync-flag))
            (do
              ((:dispatch-sync frame-ops) event opts)
              ;; The write-side drain has reached quiescence. Advance each
              ;; dirty ViewCell once; React owns the one host batch.
              (reactive/flush-frame! (:frame frame-ops)))
            ((:dispatch frame-ops) event opts)))

        :fn
        (value native-event)

        nil)))
  nil)

(defn- stable-site-callback!
  [owner candidate site-key]
  (or (get-in @owner [:callbacks site-key])
      (get-in @candidate [:callbacks site-key])
      (let [callback (fn [native-event]
                       (invoke-site! owner site-key native-event))]
        (swap! candidate assoc-in [:callbacks site-key] callback)
        callback)))

(defn- debug-site-tags
  [{:keys [view-id sid source-coord path]}]
  {:view-id view-id
   :site-id sid
   :source-coord source-coord
   :occurrence-path path})

(defn- warn-unregistered!
  [event-id debug-site]
  (when (and interop/debug-enabled?
             (keyword? event-id)
             (nil? (registrar/lookup :event event-id)))
    (trace/emit!
     :warning :rf.warning/unregistered-event-id
     (merge
      {:event-id event-id
       :reason (str "compiled data handler references an event id that is not "
                    "currently registered. A lazily loaded module may register "
                    "it before invocation; rendering continues")
       :recovery :warned-and-continued}
      (debug-site-tags debug-site)))))

(defn- warn-dynamic-placeholder!
  [event debug-site]
  (when interop/debug-enabled?
    (when-some [placeholder (some rules/placeholders event)]
      (trace/emit!
       :warning :rf.warning/placeholder-in-dynamic-vector
       (merge {:event event
               :placeholder placeholder
               :reason (str "placeholder keywords splice only in literal "
                            "compiled event vectors; this runtime vector "
                            "dispatches the keyword as ordinary data")
               :recovery :warned-and-continued}
              (debug-site-tags debug-site))))))

(defn- publish-candidate-site!
  [site-key descriptor]
  (let [{:keys [owner candidate]} (capture-or-throw!)
        callback  (stable-site-callback! owner candidate site-key)]
    (swap! candidate assoc-in [:sites site-key] descriptor)
    callback))

(defn data-handler
  "Return the stable callback for a compiler-proven data handler site."
  [site-key template flags debug-site]
  (warn-unregistered! (first template) debug-site)
  (publish-candidate-site!
   site-key {:kind :data :value template :flags flags :debug-site debug-site}))

;; ---------------------------------------------------------------------------
;; Literal passive handler maps — narrow native-listener seam
;; ---------------------------------------------------------------------------

(defn- assign-authored-ref!
  "Apply React's callback/object ref shapes and return a callback-ref cleanup,
  if the authored callback supplied one (React 19)."
  [authored-ref node]
  (cond
    (nil? authored-ref) nil
    (fn? authored-ref)  (authored-ref node)
    :else                (do (set! (.-current authored-ref) node) nil)))

(defn- detach-native!
  [state listeners authored-ref expected-token]
  (let [{:keys [node token authored-cleanup]} @state]
    (when (and node
               (or (nil? expected-token) (identical? expected-token token)))
      ;; Clear first: authored cleanup may synchronously cause another ref
      ;; transition, which must not observe this attachment as still live.
      (reset! state {:node nil :token nil :authored-cleanup nil})
      (doseq [{:keys [event-name callback capture?]} listeners]
        (.removeEventListener node event-name callback capture?))
      (if (fn? authored-cleanup)
        (authored-cleanup)
        (assign-authored-ref! authored-ref nil))))
  nil)

(defn- make-native-ref-entry
  [listeners authored-ref]
  (let [state (atom {:node nil :token nil :authored-cleanup nil})
        ref-fn
        (fn native-passive-ref [node]
          (if (some? node)
            (do
              ;; Defensive and idempotent when React replays a callback ref.
              (detach-native! state listeners authored-ref nil)
              (let [token #js {}]
                (doseq [{:keys [event-name callback capture?]} listeners]
                  ;; `:once` stays in the stable committed callback fence. That
                  ;; preserves its consumed state across HMR/ref reattachment;
                  ;; the browser listener owns only the passive/capture bits.
                  (.addEventListener
                   node event-name callback
                   #js {:passive true :capture capture?}))
                (reset! state {:node node
                               :token token
                               :authored-cleanup nil})
                (try
                  (let [cleanup (assign-authored-ref! authored-ref node)]
                    (when (and (fn? cleanup)
                               (identical? token (:token @state)))
                      (swap! state assoc :authored-cleanup cleanup)))
                  (catch :default e
                    (detach-native! state listeners authored-ref token)
                    (throw e)))
                ;; React 19 invokes this cleanup instead of calling the ref
                ;; with nil. The token prevents a stale replay cleanup from
                ;; detaching a newer node attachment.
                (fn cleanup-native-passive-ref []
                  (detach-native! state listeners authored-ref token))))
            ;; React 18 / defensive callback-ref cleanup path.
            (detach-native! state listeners authored-ref nil)))
        entry {:listeners listeners
               :authored-ref authored-ref
               :state state
               :ref ref-fn}]
    entry))

(defn- same-listener-config?
  [a b]
  (and (= (:event-name a) (:event-name b))
       (= (:capture? a) (:capture? b))
       (identical? (:callback a) (:callback b))))

(defn- matching-native-entry?
  [entry listeners authored-ref]
  (and entry
       (identical? authored-ref (:authored-ref entry))
       (= (count listeners) (count (:listeners entry)))
       (every? true?
               (map same-listener-config? listeners (:listeners entry)))))

(defn passive-ref
  "INTERNAL compiler target for one element's literal `:passive true` maps.

  `specs` is the closed vector `[site-key event-name callback capture?]` for
  each passive handler on this native element. The returned React ref composes
  any authored callback/object ref and owns exactly one native listener per
  spec. An unchanged committed element reuses the exact ref; option/ref changes
  make React clean the old attachment before installing the new one."
  [specs authored-ref]
  (let [{:keys [owner candidate]} (capture-or-throw!)
        listeners (mapv (fn [[_site-key event-name callback capture?]]
                          {:event-name event-name
                           :callback callback
                           :capture? (boolean capture?)})
                        specs)
        group-key (mapv first specs)
        prior     (or (get-in @candidate [:native-refs group-key])
                      (get-in @owner [:native-refs group-key]))
        entry     (if (matching-native-entry? prior listeners authored-ref)
                    prior
                    (make-native-ref-entry listeners authored-ref))]
    (swap! candidate assoc-in [:native-refs group-key] entry)
    (:ref entry)))

(defn dynamic-handler
  "Classify a dynamic handler-position value at render time, then publish its
  committed meaning behind the site's stable callback.  Placeholder-looking
  keywords in runtime vectors remain ordinary data by construction."
  [site-key v debug-site]
  (cond
    (nil? v)
    js/undefined

    (vector? v)
    (do
      (warn-dynamic-placeholder! v debug-site)
      (warn-unregistered! (first v) debug-site)
      (publish-candidate-site!
       site-key {:kind :data :value v :flags 0 :debug-site debug-site}))

    (map? v)
    (let [unknown (remove rules/handler-option-keys (keys v))
          {:keys [event prevent-default stop-propagation capture passive once]} v]
      (when (or (seq unknown) (not (vector? event)))
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/render
         (str "a dynamic handler options map needs a vector :event and only "
              "the closed handler-option keys; got " (pr-str v))
         {:extra {:value v :unknown-keys (vec unknown)}}))
      (when (or capture passive)
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/render
         (str "dynamic :capture/:passive listener options cannot change the "
              "native listener phase after compilation; write this options "
              "map literally at the element site")
         {:extra {:value v :unsupported-dynamic-options
                  (cond-> [] capture (conj :capture) passive (conj :passive))}}))
      (warn-dynamic-placeholder! event debug-site)
      (warn-unregistered! (first event) debug-site)
      (publish-candidate-site!
       site-key {:kind :data
                 :value event
                 :flags (+ (if prevent-default prevent-default-flag 0)
                           (if stop-propagation stop-propagation-flag 0)
                           (if once once-flag 0))
                 :debug-site debug-site}))

    (fn? v)
    (publish-candidate-site!
     site-key {:kind :fn :value v :flags 0 :debug-site debug-site})

    :else
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/render
     (str "a dynamic handler expression produced " (pr-str v) " — handlers "
          "classify by type: event vector, options map, handler fn, or nil")
     {:extra {:value v}})))
