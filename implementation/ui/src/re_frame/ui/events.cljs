(ns re-frame.ui.events
  "Commit-owned native event callbacks for compiled views.

  Render records an ownership-free candidate table.  A layout commit publishes
  that exact table to the component's EventOwner; abandoned renders therefore
  cannot retarget a callback.  The DOM receives one stable callback per lexical
  site, while invocation reads the latest COMMITTED event template and locked
  frame operations."
  (:refer-clojure :exclude [dispatch-fn])
  (:require [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
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

;; ---------------------------------------------------------------------------
;; Render-time event capture (S3a).
;;
;; A render accumulates its ownership-free candidate into a plain js-object whose
;; per-site `sites` table is itself a js-object, threaded through a module-private
;; single slot rather than a dynamic `binding`. Render is synchronous and
;; single-threaded, and compiled child views are rendered by React LATER (never
;; called inline within a parent thunk), so event captures never nest within one
;; thunk: a save/restore of the slot suffices and avoids both the dynamic-var
;; push/pop and the per-site persistent-map `assoc-in` churn on the G-1
;; direct-render hot path. The candidate→owner commit split is unchanged: a layout
;; commit republishes the exact table to the EventOwner, so an abandoned render
;; (which never reaches `commit!`) publishes no ownership.
;;
;; Each site's stable callback rides on its descriptor, so there is no separate
;; callback table to allocate per render or merge at commit: a re-render reuses
;; the callback the site's committed descriptor carries. A site-key is a string
;; sid in DEV and an integer site-index in production; both key the `sites` table
;; directly (the integer coerces to its decimal string, consistently for get and
;; set), and because the candidate's winning table becomes the committed table
;; verbatim, owner and candidate always agree on that key with no rebuild.
;; ---------------------------------------------------------------------------

;; Module-private single-slot holder for the in-flight candidate. `nil` outside
;; any render boundary; a compiled event site executed outside one throws.
(def ^:private capture-holder #js {:current nil})

(defn make-owner
  "Create the commit-owned state for one mounted compiled-view instance.

  A plain mutable js-object: render, layout commit, native invocation and
  disconnect all run on the single JS thread and never interleave, so an atom's
  CAS is unnecessary and a direct mutable slot is both leaner and faster. Every
  field is installed by a browser layout commit (never reached under the SSR G-1
  bench), so all stay nil until then and `make-owner` is a single allocation on
  the measured render path. The stable per-site callback lives on the committed
  descriptor, so there is no separate callback table to allocate or merge."
  [view-id]
  #js {:viewId view-id
       :lifecycle :new
       :committed nil
       :nativeRefs nil
       :onceFired nil
       :frameOps nil
       ;; The per-view stable committed-frame dispatcher (ui/dispatch-fn),
       ;; minted lazily and kept stable across renders and reconnects.
       :dispatchFn nil})

(defn- make-candidate
  "A fresh ownership-free candidate for one render.  `sites` is a js-object
  keyed by the raw site-key (integer site-index in production, string sid in
  DEV) whose descriptors each carry their stable callback; `nativeRefs` holds
  the cold passive-listener persistent map."
  [owner frame-id]
  #js {:owner owner
       :frameId frame-id
       :sites (js-obj)
       :nativeRefs {}
       ;; A ui/dispatch-fn site with no DOM handler sites still needs the
       ;; committed frame resolved at commit; this flag carries that intent
       ;; from render (a dispatch-fn call) to `commit!`.
       :needsFrameOps false})

(defn with-capture
  "Evaluate `thunk` under a fresh event candidate.  Returns the pair
  `[element capture]` as a lightweight js-array (destructures via `nth` exactly
  like a vector, but costs one object rather than a PersistentVector's two on
  every render). Resolving the ambient frame is lazy, so the stable dev wrapper
  may install this capability without requiring a frame from event-free views."
  [owner frame-id thunk]
  (let [candidate (make-candidate owner frame-id)
        prior     (.-current capture-holder)]
    (set! (.-current capture-holder) candidate)
    (try
      #js [(thunk) candidate]
      (finally
        (set! (.-current capture-holder) prior)))))

(defn- publish-commit!
  "Republish the winning render's exact table to the owner.  Cold path: runs
  only in a browser layout commit, never under the SSR G-1 bench. The candidate
  and owner key their site table by the same raw site-key, so the winning `sites`
  table (whose descriptors already carry their stable callbacks) becomes the
  committed table verbatim — no per-site rebuild and no callback merge. The
  uniform `frame-ops` bundle lives once on the owner; a provider retarget updates
  it without churning the callback the descriptor carries onto the DOM node."
  [^js owner ^js candidate frame-ops]
  (let [sites (.-sites candidate)]
    (set! (.-lifecycle owner) :connected)
    (set! (.-committed owner) sites)
    (set! (.-frameOps owner) frame-ops)
    (set! (.-nativeRefs owner) (.-nativeRefs candidate))
    ;; Retain :once-consumed state only for sites still present this render.
    (let [prev (.-onceFired owner)
          next (js-obj)]
      (when (some? prev)
        (doseq [k (js/Object.keys prev)]
          (when (some? (unchecked-get sites k))
            (unchecked-set next k true))))
      (set! (.-onceFired owner) next)))
  nil)

(defn commit!
  "Publish the winning render's exact event table.  Callback identity is kept
  independently from the table, so provider retargets update the destination
  without changing the function installed on the DOM node.

  The three-argument arity is the narrow host-agnostic test seam: it supplies a
  frame-op bundle directly, allowing callback semantics to be tested without a
  React host or the retired process-global dispatch hook."
  ([owner ^js capture]
   (let [frame-ops (when (or (pos? (alength (js/Object.keys (.-sites capture))))
                             (.-needsFrameOps capture))
                     (if-some [frame-id (.-frameId capture)]
                       (frames/frame-ops-for frame-id)
                       ;; Preserve the canonical no-frame-context failure for a
                       ;; client commit outside any provider. SSR never runs
                       ;; layout effects and therefore never reaches this read.
                       (frames/frame-ops)))]
     (publish-commit! owner capture frame-ops)))
  ([owner capture frame-ops]
   (publish-commit! owner capture frame-ops)))

(defn disconnect!
  [^js owner]
  ;; Native DOM ownership belongs to the composed React ref. React calls its
  ;; cleanup during node replacement/unmount (and StrictMode ref replay); the
  ;; lifecycle fence here only makes an in-flight callback inert immediately by
  ;; dropping the committed table (invocation reads it and no-ops on nil).
  (set! (.-lifecycle owner) :disconnected)
  (set! (.-committed owner) nil)
  (set! (.-nativeRefs owner) nil)
  (set! (.-onceFired owner) nil)
  ;; Drop the committed frame so a leaked ui/dispatch-fn cannot route into a
  ;; released frame; the :disconnected lifecycle gate is authoritative, and the
  ;; stable dispatcher itself survives so an Activity reveal reuses it.
  (set! (.-frameOps owner) nil)
  nil)

(defn- capture-or-throw! []
  (or (.-current capture-holder)
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

(defn- debug-site-tags
  [{:keys [view-id sid source-coord path]}]
  {:view-id view-id
   :site-id sid
   :source-coord source-coord
   :occurrence-path path})

(defn- warn-unregistered!
  ;; Takes the event VECTOR (not its head) so the `(first ...)` extraction is
  ;; itself inside the DEV-only guard and costs nothing on the production render
  ;; path (the whole body elides under `:advanced`).
  ;;
  ;; `frame-target` scopes the existence check to the frame whose SEALED IMAGE
  ;; GENERATION dispatch resolves the handler through — the RENDER-captured frame
  ;; at render time, the COMMITTED frame at invocation time. Resolving through the
  ;; same `live-frame/call-with-frame-resolution` seam dispatch uses (which binds
  ;; `registrar/*generation*`) is what keeps this an honest early-typo detector:
  ;; a bare process-registrar read would warn FALSELY for an id registered only
  ;; in the frame's image, and SUPPRESS a valid warning for an id present only
  ;; process-current but absent from the frame image. A nil / non-live target
  ;; binds nothing and reads the process registrar (byte-identical to before).
  [event frame-target debug-site]
  (when interop/debug-enabled?
    (let [event-id (first event)]
      (when (and (keyword? event-id)
                 (nil? (live-frame/call-with-frame-resolution
                        frame-target
                        (fn [] (registrar/lookup :event event-id)))))
        (trace/emit!
         :warning :rf.warning/unregistered-event-id
         (merge
          {:event-id event-id
           :reason (str "handler references an event id not registered in the "
                        "frame that resolves this dispatch. A lazily loaded "
                        "module may register it before the callback fires; the "
                        "site stays live and dispatches")
           :recovery :warned-and-continued}
          (debug-site-tags debug-site)))))))

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

(defn- dispatch-committed!
  "Dispatch a committed handler's event vector to the owner's frame. The one
  sync door drains inside the native event and advances the dirty ViewCells
  before React's discrete re-render; every other site batches."
  [^js owner flags event debug-site]
  (let [frame-ops (.-frameOps owner)
        opts      (debug-dispatch-opts debug-site)]
    (if (pos? (bit-and flags sync-flag))
      (do
        ((:dispatch-sync frame-ops) event opts)
        ;; The update/commit drain has reached quiescence. Advance each dirty
        ;; ViewCell once; React owns the one host batch.
        (reactive/flush-frame! (:frame frame-ops)))
      ((:dispatch frame-ops) event opts))))

(defn- invoke-site!
  [^js owner site-key native-event]
  (when-some [committed (.-committed owner)]
    (when-some [^js desc (unchecked-get committed site-key)]
      (let [flags (.-flags desc)]
        (when-not (and (pos? (bit-and flags once-flag))
                       (unchecked-get (.-onceFired owner) site-key))
          (when (pos? (bit-and flags once-flag))
            (unchecked-set (.-onceFired owner) site-key true))
          (when (pos? (bit-and flags prevent-default-flag))
            (.preventDefault native-event))
          (when (pos? (bit-and flags stop-propagation-flag))
            (.stopPropagation native-event))
          (case (.-kind desc)
            :data
            (dispatch-committed! owner flags
                                 (project-event (.-value desc) native-event)
                                 (.-debugSite desc))

            ;; A compiler-proven ui/event site: the body runs synchronously with
            ;; the live native event and NAMES its outcome. A vector dispatches
            ;; (through the sync door when this is a controlled-input site, else
            ;; batched); nil dispatches nothing; anything else is a loud didactic
            ;; diagnostic. Placeholder keywords in the runtime result are ordinary
            ;; data (dev warns), exactly as for a runtime-forwarded vector.
            :event-fn
            (let [result ((.-value desc) native-event)]
              (cond
                (nil? result) nil

                (vector? result)
                (do
                  (when interop/debug-enabled?
                    (warn-dynamic-placeholder! result (.-debugSite desc))
                    ;; Resolve existence through the COMMITTED frame's own image
                    ;; generation — the exact frame this dispatch routes into.
                    (warn-unregistered! result (:frame (.-frameOps owner))
                                        (.-debugSite desc)))
                  (dispatch-committed! owner flags result (.-debugSite desc)))

                :else
                (error/throw-error!
                 :rf.error/ui-tree-malformed 're-frame.ui/event
                 ;; rf2-q9q9y — `result` is whatever the AUTHOR's committed
                 ;; `ui/event` body returned, so a foreign value lands here
                 ;; verbatim. Message half `error/pr-form`, ex-data half
                 ;; `error/safe-form`.
                 (str "a (ui/event …) body produced " (error/pr-form result)
                      " — a committed ui/event handler must return an event "
                      "vector to dispatch, or nil to dispatch nothing")
                 {:extra (error/safe-form {:value result})})))

            :fn
            ((.-value desc) native-event)

            nil)))))
  nil)

(defn- stable-site-callback!
  "The stable callback for `site-key`: the one already committed for this site
  (kept stable across commits while the site stays mounted), or one already
  published earlier in THIS render, else a freshly minted callback. An abandoned
  render only ever writes its own `candidate.sites` and never the owner, so it
  can seed no callback — the candidate/owner split holds."
  [^js owner ^js sites site-key]
  (let [committed (.-committed owner)
        prior     (or (when (some? committed) (unchecked-get committed site-key))
                      (unchecked-get sites site-key))]
    (if (some? prior)
      (.-callback ^js prior)
      (fn [native-event]
        (invoke-site! owner site-key native-event)))))

(defn- publish-candidate-site!
  [site-key ^js descriptor]
  (let [^js candidate (capture-or-throw!)
        sites         (.-sites candidate)
        callback      (stable-site-callback! (.-owner candidate) sites site-key)]
    (set! (.-callback descriptor) callback)
    (unchecked-set sites site-key descriptor)
    callback))

(defn data-handler
  "Return the stable callback for a compiler-proven data handler site."
  [site-key template flags debug-site]
  ;; Resolve existence through the RENDER-captured frame's generation — the
  ;; frame React observed for this render, matching what the committed callback
  ;; dispatches into. The whole DEV read (frame-id included) elides in production.
  (when interop/debug-enabled?
    (warn-unregistered! template (.-frameId ^js (capture-or-throw!)) debug-site))
  (publish-candidate-site!
   site-key #js {:kind :data :value template :flags flags :debugSite debug-site
                 :callback nil}))

(defn event-handler
  "Return the stable callback for a compiler-proven `ui/event` site. `f` is the
  compiled `(fn [native-event] -> event-vector | nil)` body; `flags` carries the
  controlled-input sync bit exactly as a literal vector site does. Its outcome is
  classified at invocation (vector ⇒ dispatch, nil ⇒ no dispatch, anything else ⇒
  loud diagnostic), so the SITE proof is static while the prefix/payload stay
  runtime values.

  Reused VERBATIM at a foreign/internal-view component prop (flags 0 — the
  controlled-input door is DOM-only): the foreign invoker's first argument binds
  `e`, so the per-site-stable, committed-slot-reading machinery is identical
  whether the invoker is a native DOM event or a foreign callback."
  [site-key f flags debug-site]
  (publish-candidate-site!
   site-key #js {:kind :event-fn :value f :flags flags :debugSite debug-site
                 :callback nil}))

;; ---------------------------------------------------------------------------
;; ui/handler — the explicit IMPERATIVE committed callback (S3)
;;
;; The imperative sibling of `ui/event`: its body runs after commit and its
;; return is IGNORED (no dispatch of a returned vector). Per-site stable and
;; committed-slot reading like every committed callback, so an external/foreign
;; consumer attaches ONE stable identity and every invocation reads the winning
;; commit — the stale-closure boundary law. The stable callback is VARIADIC so
;; a foreign invoker's full argument list reaches the body verbatim (a DOM
;; `:on-*` site simply passes the single native event; the bare-fn shorthand's
;; explicit spelling).
;; ---------------------------------------------------------------------------

(defn- invoke-handler-site!
  "Apply the LATEST committed `ui/handler` body to the invoker's args; the
  return is imperative and dropped. No-ops after disconnect (committed = nil),
  so a foreign callback that outlived its view is inert — never routed into a
  torn-down owner."
  [^js owner site-key args]
  (when-some [committed (.-committed owner)]
    (when-some [^js desc (unchecked-get committed site-key)]
      (apply (.-value desc) args)))
  nil)

(defn- stable-handler-callback!
  "The stable VARIADIC callback for a `ui/handler` site: the one already
  committed (kept stable while the site stays mounted), one published earlier in
  THIS render, else a freshly minted variadic forwarder. Mirrors
  `stable-site-callback!`; an abandoned render seeds no callback (candidate/owner
  split)."
  [^js owner ^js sites site-key]
  (let [committed (.-committed owner)
        prior     (or (when (some? committed) (unchecked-get committed site-key))
                      (unchecked-get sites site-key))]
    (if (some? prior)
      (.-callback ^js prior)
      (fn [& args] (invoke-handler-site! owner site-key args)))))

(defn handler-callback
  "Return the stable callback for a compiler-proven `ui/handler` site — the
  explicit imperative committed callback (Spec 004 §Handlers). `f` is the
  compiled fixed-arity body; its return is IGNORED. At a DOM `:on-*` site the
  native event binds its first parameter (the explicit form of the bare-fn
  shorthand); at a foreign/internal-view prop the invoker's arguments bind
  through verbatim."
  [site-key f debug-site]
  (let [^js candidate (capture-or-throw!)
        sites         (.-sites candidate)
        callback      (stable-handler-callback! (.-owner candidate) sites site-key)]
    (unchecked-set sites site-key
                   #js {:kind :handler :value f :flags 0 :debugSite debug-site
                        :callback callback})
    callback))

;; ---------------------------------------------------------------------------
;; ui/dispatch-fn — the stable committed-frame dispatcher (S3)
;; ---------------------------------------------------------------------------

(defn- fail-dispatch-disconnected!
  [^js owner event]
  (error/throw-error!
   :rf.error/dispatch-disconnected 're-frame.ui/dispatch-fn
   (str "a (ui/dispatch-fn) dispatcher fired while its view was "
        (name (.-lifecycle owner)) " — a committed-frame dispatcher rejects "
        "dispatch in every non-connected state (the leaked-listener detector). "
        "Unregister the external listener in the (effect …) cleanup that "
        "created it, so it never outlives the view")
   {:extra {:view-id (.-viewId owner)
            :state (.-lifecycle owner)
            :event event}}))

(defn dispatch-fn
  "Render-time site: return this view's per-view STABLE committed-frame
  dispatcher. Its identity is stable across renders and reconnects (attach it
  as a listener once); it reads the COMMITTED frame at call time and retargets
  only on commit; and it rejects in every non-connected state with
  `:rf.error/dispatch-disconnected` (the leaked-listener detector). Marks the
  candidate so a dispatch-fn-only view (no DOM handler sites) still resolves the
  committed frame at commit."
  []
  (let [^js candidate (capture-or-throw!)
        ^js owner     (.-owner candidate)]
    (set! (.-needsFrameOps candidate) true)
    (or (.-dispatchFn owner)
        (let [f (fn stable-dispatch
                  ([event] (stable-dispatch event nil))
                  ([event opts]
                   (if (and (keyword-identical? :connected (.-lifecycle owner))
                            (some? (.-frameOps owner)))
                     ((:dispatch (.-frameOps owner))
                      event
                      (if (some? opts) (assoc opts :source :ui) {:source :ui}))
                     (fail-dispatch-disconnected! owner event))))]
          (set! (.-dispatchFn owner) f)
          f))))

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
  ([specs authored-ref]
   (passive-ref specs authored-ref nil))
  ([specs authored-ref occurrence-path]
   (let [^js candidate (capture-or-throw!)
         owner     (.-owner candidate)
         listeners (mapv (fn [[_site-key event-name callback capture?]]
                           {:event-name event-name
                            :callback callback
                            :capture? (boolean capture?)})
                         specs)
         ;; Lexical event sites identify the element shape; the enclosing row
         ;; keys identify the concrete native attachment. Capture-free data
         ;; callbacks remain shared across rows as the public loop law promises.
         group-key [occurrence-path (mapv first specs)]
         prior     (or (get (.-nativeRefs candidate) group-key)
                       (get (.-nativeRefs owner) group-key))
         entry     (if (matching-native-entry? prior listeners authored-ref)
                     prior
                     (make-native-ref-entry listeners authored-ref))]
     (set! (.-nativeRefs candidate)
           (assoc (.-nativeRefs candidate) group-key entry))
     (:ref entry))))

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
      (when interop/debug-enabled?
        (warn-dynamic-placeholder! v debug-site)
        (warn-unregistered! v (.-frameId ^js (capture-or-throw!)) debug-site))
      (publish-candidate-site!
       site-key #js {:kind :data :value v :flags 0 :debugSite debug-site
                     :callback nil}))

    (map? v)
    (let [unknown (remove rules/handler-option-keys (keys v))
          {:keys [event prevent-default stop-propagation capture passive once]} v]
      (when (or (seq unknown) (not (vector? event)))
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/render
         ;; rf2-q9q9y — `v` is the author's options map and a cycle is
         ;; reachable through any of its values. `unknown` holds its KEYS,
         ;; which are equally author-supplied, so the whole `:extra` crosses
         ;; rather than the `:value` slot alone.
         (str "a dynamic handler options map needs a vector :event and only "
              "the closed handler-option keys; got " (error/pr-form v))
         {:extra (error/safe-form {:value v :unknown-keys (vec unknown)})}))
      (when (or capture passive)
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/render
         (str "dynamic :capture/:passive listener options cannot change the "
              "native listener phase after compilation; write this options "
              "map literally at the element site")
         ;; rf2-q9q9y — the EX-DATA-ONLY half: this message never prints `v`,
         ;; so nothing overflowed at the thrower and a cyclic `:event` inside
         ;; the options map rode out to explode at a downstream sink instead.
         {:extra (error/safe-form
                  {:value v :unsupported-dynamic-options
                   (cond-> [] capture (conj :capture) passive (conj :passive))})}))
      (when interop/debug-enabled?
        (warn-dynamic-placeholder! event debug-site)
        (warn-unregistered! event (.-frameId ^js (capture-or-throw!)) debug-site))
      (publish-candidate-site!
       site-key #js {:kind :data
                     :value event
                     :flags (+ (if prevent-default prevent-default-flag 0)
                               (if stop-propagation stop-propagation-flag 0)
                               (if once once-flag 0))
                     :debugSite debug-site
                     :callback nil}))

    (fn? v)
    (publish-candidate-site!
     site-key #js {:kind :fn :value v :flags 0 :debugSite debug-site
                   :callback nil})

    :else
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/render
     ;; rf2-q9q9y — reached exactly when `v` is not nil/vector/map/fn, i.e.
     ;; when it IS a foreign object: `:on-click ctx.Provider` is the shape.
     ;; The CLJS twin of `tree/classify-event`, same message text, crossed in
     ;; the same change so the two host arms cannot drift.
     (str "a dynamic handler expression produced " (error/pr-form v) " — handlers "
          "classify by type: event vector, options map, handler fn, or nil")
     {:extra (error/safe-form {:value v})})))
