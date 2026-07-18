(ns re-frame.ui.presence-runtime
  "Presence — declarative enter/exit retention (Spec 004 §Presence, S4).
  (Named `-runtime` to avoid the CLJS var↔namespace munging clash with the
  public `re-frame.ui/presence` var — the same split as `lease` ↔
  `re-frame.ui.lease-descriptor`.)
  DELIBERATELY BOUNDED: not an animation system. The three-phase keyed
  retention machine plus the `:timeout-ms` terminal safety bound, and nothing
  more (no easing, no curves, no presence-event bus).

  A `(ui/presence {:timeout-ms n} keyed-children)` boundary tracks its children
  BY KEY across renders. A key passes `:mounting → :present`; when a key leaves
  the incoming set the child is RETAINED in `:unmounting` (its exit transition
  runs against that phase) until the `:timeout-ms` safety bound fires, then the
  removal is terminal and EXACTLY-ONCE (React unmounts the retained subtree, so
  every lease / effect it owns is released). Removal-then-reinsertion of a key
  interrupts the exit and re-enters at `:present`.

  `(presence-phase)` is the single phase read — `react/useContext` on CLJS,
  `:present` on the JVM structural subset. Outside a boundary it returns
  `:present`, so presence-aware children stay reusable anywhere.

  The runtime split:

    reconcile          PURE next-entry derivation (both hosts, unit-testable)
    the presence clock a fake-advanceable exit scheduler; `flush-presence!`
                       (re-frame.ui.test) drives it deterministically without
                       wall-clock sleeps, production arms `js/setTimeout`
    presence-boundary  the React function component that wires the two together

  The JVM structural node lives in `re-frame.ui.tree/presence` (a fragment
  carrying the `:rf.ui/presence {:phase :present :timeout-ms n}` diagnostic
  marker, §004B); this namespace's JVM arm is `presence-phase` only."
  #?(:cljs (:require ["react" :as react])))

;; ---------------------------------------------------------------------------
;; reconcile — the PURE three-phase entry derivation (both hosts)
;; ---------------------------------------------------------------------------

(defn reconcile
  "Derive the next ordered entry vector from the COMMITTED entries and the
  incoming key vector. Pure and idempotent — the same inputs always give the
  same output, so a StrictMode double-render is harmless.

    committed    ordered [{:key k :phase :mounting|:present|:unmounting} …]
    incoming     ordered [k …] (the keys present in this render)

  Rules, in committed order then appended new keys:
    - a committed key still incoming keeps its phase, EXCEPT `:unmounting`
      flips back to `:present` (removal-then-reinsertion re-entry);
    - a committed key no longer incoming becomes `:unmounting` (retained —
      it is dropped from the set only by its removal callback, never here);
    - a brand-new incoming key is appended as `:mounting`.

  Retained (`:unmounting`) keys hold their slot; new keys append at the end."
  [committed incoming]
  (let [incoming-set (set incoming)
        committed-keys (into #{} (map :key) committed)
        kept (mapv (fn [{:keys [key phase] :as e}]
                     (cond
                       (and (incoming-set key) (= :unmounting phase))
                       (assoc e :phase :present)          ; re-entry
                       (incoming-set key) e               ; still here
                       :else (assoc e :phase :unmounting))) ; retained for exit
                   committed)
        added (into [] (comp (remove committed-keys)
                             (map (fn [k] {:key k :phase :mounting})))
                    incoming)]
    (into kept added)))

;; ---------------------------------------------------------------------------
;; The presence clock — a fake-advanceable exit scheduler
;;
;; ONE registry of pending exits owns every retention timer. Production arms a
;; real `js/setTimeout` per exit; tests advance the logical clock through
;; `ui.test/flush-presence!` (→ `advance-clock!`), firing due timers with no
;; wall-clock sleep. `fire-timer!` is id-keyed and idempotent, so a real
;; setTimeout that lands after a test already flushed its timer is a no-op —
;; the terminal removal runs EXACTLY ONCE regardless of which path wins.
;; ---------------------------------------------------------------------------

#?(:cljs
   (do

(defonce ^:private clock
  ;; :now      logical milliseconds
  ;; :next-id  monotonic timer id
  ;; :pending  {id {:fire-at ms :remove! fn :handle js-timeout-handle}}
  (atom {:now 0 :next-id 0 :pending {}}))

(defonce ^:private wall-clock?
  ;; Production arms a real js/setTimeout; a deterministic test disables it so
  ;; the logical clock (advance-clock!) is the SOLE removal driver.
  (atom true))

(defn set-wall-clock!
  "Enable/disable the real-`setTimeout` removal arm. Tests that drive removal
  purely through `flush-presence!` disable it for determinism; production
  leaves it on. Returns the new value."
  [on?]
  (reset! wall-clock? (boolean on?)))

(defn pending-count
  "How many exits are currently retained (awaiting removal)?"
  []
  (count (:pending @clock)))

(defn fire-timer!
  "Fire the pending timer `id` EXACTLY ONCE: atomically claim + remove it, then
  run its `:remove!`. A second call (real setTimeout after a test flush, a
  double advance) finds nothing and is a no-op — the exactly-once guard."
  [id]
  (let [entry (get-in @clock [:pending id])]
    (when entry
      (let [claimed (volatile! nil)]
        (swap! clock update :pending
               (fn [p] (when-let [e (get p id)]
                         (vreset! claimed e))
                       (dissoc p id)))
        (when-let [{:keys [remove! handle]} @claimed]
          (when handle (js/clearTimeout handle))
          (remove!))))))

(defn cancel-timer!
  "Cancel pending timer `id` WITHOUT firing its removal (re-entry / unmount)."
  [id]
  (let [entry (get-in @clock [:pending id])]
    (when-let [handle (:handle entry)] (js/clearTimeout handle))
    (swap! clock update :pending dissoc id)
    nil))

(defn schedule-exit!
  "Register an exit removal to fire after `timeout-ms` of clock time. Returns
  the timer id (pass to `cancel-timer!` on re-entry / unmount). Production also
  arms a real `js/setTimeout`; both routes funnel through the id-keyed
  `fire-timer!`, so the removal is exactly-once."
  [timeout-ms remove!]
  (let [id (:next-id @clock)
        fire-at (+ (:now @clock) timeout-ms)
        handle (when @wall-clock?
                 (js/setTimeout #(fire-timer! id) timeout-ms))]
    (swap! clock (fn [c]
                   (-> c
                       (assoc :next-id (inc (:next-id c)))
                       (assoc-in [:pending id]
                                 {:fire-at fire-at :remove! remove! :handle handle}))))
    id))

(defn- due-ids
  "Pending ids whose fire-at ≤ `t`, in fire-at then id order (deterministic)."
  [t]
  (->> (:pending @clock)
       (filter (fn [[_ {:keys [fire-at]}]] (<= fire-at t)))
       (sort-by (fn [[id {:keys [fire-at]}]] [fire-at id]))
       (mapv first)))

(defn advance-clock!
  "Advance the logical clock by `ms` (or, with no arg, to quiescence — past the
  last pending fire-at) and fire every timer that comes due, in deterministic
  order. The deterministic, wall-clock-free driver behind
  `ui.test/flush-presence!`. Returns the number of exits removed."
  ([]
   (let [pend (:pending @clock)]
     (if (empty? pend)
       0
       (let [maxf (apply max (map :fire-at (vals pend)))]
         (advance-clock! (- (inc maxf) (:now @clock)))))))
  ([ms]
   (let [target (+ (:now @clock) ms)]
     (swap! clock assoc :now target)
     (let [ids (due-ids target)]
       (doseq [id ids] (fire-timer! id))
       (count ids)))))

(defn reset-clock!
  "Drop every pending exit and reset logical time — a test-isolation hook."
  []
  (doseq [[_ {:keys [handle]}] (:pending @clock)]
    (when handle (js/clearTimeout handle)))
  (reset! clock {:now 0 :next-id 0 :pending {}})
  nil)

;; ---------------------------------------------------------------------------
;; presence-context + the boundary component
;; ---------------------------------------------------------------------------

(defonce ^js presence-context
  ;; Default `:present` — a presence-aware child rendered OUTSIDE any boundary
  ;; reads `:present`, so it stays reusable anywhere.
  (react/createContext :present))

(defn- react-element? [x]
  (and (some? x) (react/isValidElement x)))

(defn- collect-elements
  "Flatten the incoming children (a compiled `for` emits nested JS arrays) into
  an ordered [key element] vector. Keys come from React's own `.-key` (already
  string-coerced), the identity `reconcile` tracks. The analyzer guarantees
  every presence child is keyed, so an element without a key is defensive-only."
  [acc x]
  (cond
    (nil? x)              acc
    (js/Array.isArray x)  (reduce collect-elements acc x)
    (react-element? x)    (if-some [k (.-key x)] (conj acc [k x]) acc)
    :else                 acc))

(defn- render-entries
  "Render each entry as its element wrapped in a phase-carrying context Provider
  (so `(presence-phase)` in the child reads its phase). The Provider is keyed by
  the entry key; the wrapped element keeps its own key too."
  [entries elements]
  (into-array
   (keep (fn [{:keys [key phase]}]
           (when-some [el (get elements key)]
             (react/createElement (.-Provider presence-context)
                                  #js {:value phase :key key}
                                  el)))
         entries)))

(defn- entries-signature [entries]
  ;; phase-sensitive identity so the commit effect only re-renders on a real
  ;; change (avoids an infinite setState loop).
  (mapv (juxt :key :phase) entries))

(defn ^:private PresenceComponent [^js props]
  (let [timeout-ms (.-timeoutMs props)
        incoming   (.-incoming props)
        ;; forceUpdate cell — the setter identity is stable across renders.
        tick       (react/useState 0)
        force!     (fn [] ((aget tick 1) inc))
        ;; committed state (source of truth) — mutated ONLY in the effect /
        ;; removal callbacks, never during render, so `reconcile` reads a stable
        ;; snapshot and a StrictMode double-render is idempotent.
        st         (react/useRef nil)]
    (when (nil? (.-current st))
      (set! (.-current st) #js {:entries [] :elements {} :timers {}}))
    (let [state    (.-current st)
          incoming-pairs (collect-elements [] incoming)
          incoming-keys  (mapv first incoming-pairs)
          ;; keep the latest element for every incoming key; retained keys keep
          ;; their last-seen element.
          elements (merge (.-elements state) (into {} incoming-pairs))
          desired  (reconcile (.-entries state) incoming-keys)]
      (set! (.-elements state) elements)
      ;; Commit + phase transitions run AFTER paint (a side-effect, never during
      ;; render). Runs every commit; idempotent via signature guards.
      (react/useEffect
       (fn []
         (let [state   (.-current st)
               desired (reconcile (.-entries state) (mapv first (collect-elements [] incoming)))
               timers  (.-timers state)
               ;; 1. schedule a removal timer for each newly-:unmounting key
               ;; 2. cancel the timer for each re-entered key
               timers* (reduce
                        (fn [tm {:keys [key phase]}]
                          (cond
                            (and (= :unmounting phase) (not (contains? tm key)))
                            (assoc tm key
                                   (schedule-exit!
                                    timeout-ms
                                    (fn remove-key! []
                                      (let [s (.-current st)]
                                        (set! (.-entries s)
                                              (filterv #(not= key (:key %)) (.-entries s)))
                                        (set! (.-elements s) (dissoc (.-elements s) key))
                                        (set! (.-timers s) (dissoc (.-timers s) key)))
                                      (force!))))
                            (and (not= :unmounting phase) (contains? tm key))
                            (do (cancel-timer! (get tm key)) (dissoc tm key))
                            :else tm))
                        timers
                        desired)
               ;; 3. flip every :mounting entry to :present (enter transition)
               next    (mapv (fn [e] (if (= :mounting (:phase e))
                                       (assoc e :phase :present) e))
                             desired)]
           (set! (.-timers state) timers*)
           (when (not= (entries-signature next) (entries-signature (.-entries state)))
             (set! (.-entries state) next)
             (force!)))
         js/undefined))
      ;; teardown: cancel every pending timer (React unmounts the retained
      ;; subtrees itself — the terminal ownership release).
      (react/useEffect
       (fn [] (fn []
                (let [state (.-current st)]
                  (doseq [[_ id] (.-timers state)] (cancel-timer! id))
                  (set! (.-timers state) {}))))
       #js [])
      (render-entries desired elements))))

(defn presence-boundary
  "Build a `(ui/presence {:timeout-ms n} children)` element (the CLJS emitter's
  lowering target). `timeout-ms` is the compile-time literal safety bound;
  `children` is the compiled keyed children (a JS array, possibly nesting the
  arrays a `for` emits)."
  [timeout-ms children]
  (react/createElement PresenceComponent
                       #js {:timeoutMs timeout-ms :incoming children}))

   ))

;; ---------------------------------------------------------------------------
;; presence-phase — the single phase read (both hosts)
;; ---------------------------------------------------------------------------

(defn presence-phase
  "Read the current presence phase — `:mounting` / `:present` / `:unmounting`
  inside a `(ui/presence …)` boundary, `:present` outside one. A render-time
  read (CLJS `react/useContext`); on the JVM structural subset it is always
  `:present`."
  []
  #?(:cljs (react/useContext presence-context)
     :clj  :present))
