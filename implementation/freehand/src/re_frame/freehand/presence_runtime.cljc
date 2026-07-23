(ns re-frame.freehand.presence-runtime
  "Presence — declarative enter/exit retention (Spec 004 §Presence, F4).

  The ONE keyed retention machine, shared by both execution modes. The
  interpreted front ends ([[re-frame.freehand.react]] in the browser,
  [[re-frame.freehand.tree]] on the JVM structural host) and the compiled
  emitters both lower a `(v/presence …)` to this runtime, so retention,
  the terminal timeout bound, re-entry, and the phase read are the SAME
  code in interpreted and compiled markup — parity by construction, not by
  agreement.

  DELIBERATELY BOUNDED: not an animation system. The three-phase keyed
  retention machine plus the `:timeout-ms` terminal safety bound, and
  nothing more (no easing, no curves, no presence-event bus). The boundary
  is DOM-AGNOSTIC: it inserts no wrapper node, stamps no attributes, and
  observes no DOM events. A presence-aware child owns its own exit styling
  and accessibility by reading `(presence-phase)` = `:unmounting`
  (rf2-0ufty).

  A `(v/presence {:timeout-ms n} keyed-children)` boundary tracks its
  children BY KEY across renders. A key passes `:mounting → :present`; when
  a key leaves the incoming set the child is RETAINED in `:unmounting` (its
  exit transition runs against that phase) until the `:timeout-ms` safety
  bound fires, then the removal is terminal and EXACTLY-ONCE (React unmounts
  the retained subtree, so every subscription / effect it owns is released).
  Removal-then-reinsertion of a key interrupts the exit and re-enters at
  `:present`.

  `(presence-phase)` is the single phase read — `react/useContext` on CLJS,
  `:present` on the JVM structural subset. Outside a boundary it returns
  `:present`, so presence-aware children stay reusable anywhere.

  The runtime split:

    reconcile          PURE next-entry derivation (both hosts, unit-testable)
    claim-identities   PURE one-pair-per-key claim over the flattened children
                       (a key IS a retained identity; two children cannot own one)
    the presence clock a fake-advanceable exit scheduler; [[flush-presence!]]
                       drives it deterministically without wall-clock sleeps,
                       production arms `js/setTimeout`
    presence-boundary  the React function component that wires the two together

  The JVM structural node lives in [[re-frame.freehand.tree/presence]] (a
  fragment carrying the `:rf.ui/presence {:phase :present :timeout-ms n}`
  diagnostic marker, §004B); this namespace's JVM arm is `presence-phase`
  only.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Presence."
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

  EVERY committed key — still-`:present` and retained (`:unmounting`) alike —
  holds its committed slot; the incoming ORDER orders only the appended new keys,
  so children render in first-appearance order and an incoming reorder is ignored
  (the blessed order contract, rf2-1kb0v — presence is enter/exit, not reordering)."
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

(defn claim-identities
  "Reduce the ordered flattened `[key element]` pairs to ONE pair per OWNERSHIP
  IDENTITY — the invariant `reconcile`, the element map and the exit timers all
  assume but none of them can enforce.

  A boundary tracks its children by key, so a key IS a retained identity. Two
  children under one key alias: `reconcile` derives two entries sharing a slot,
  the element and timer maps (plain maps keyed by key) collapse to one, render
  emits duplicate Provider keys, and the single exit timer removes every entry
  sharing that identity. The FIRST claimant in document order owns the key;
  later collisions are dropped and returned for diagnosis (rf2-vxgfnd.96.2).

  React has already string-coerced `.-key`, so plain equality here IS the
  collision domain the ownership maps use — key `1` and key `\"1\"` alias, and
  this catches them together. Deliberately boundary-LOCAL: it sees the children
  of one boundary, already flattened, and keeps no registry.

  -> `[distinct-pairs duplicate-keys]`, both in document order."
  [pairs]
  (let [[pairs* dups _]
        (reduce (fn [[acc dups seen] [k :as pair]]
                  (if (contains? seen k)
                    [acc (conj dups k) seen]
                    [(conj acc pair) dups (conj seen k)]))
                [[] [] #{}]
                pairs)]
    [pairs* dups]))

;; ---------------------------------------------------------------------------
;; The presence clock — a fake-advanceable exit scheduler
;;
;; ONE registry of pending exits owns every retention timer. Production arms a
;; real `js/setTimeout` per exit; tests advance the logical clock through
;; [[flush-presence!]] (→ `advance-clock!`), firing due timers with no
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
  purely through [[flush-presence!]] disable it for determinism; production
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
  order. The deterministic, wall-clock-free driver behind [[flush-presence!]].
  Returns the number of exits removed."
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

(defn flush-presence!
  "Deterministically fire retained exits WITHOUT a wall-clock sleep — the test
  driver Spec 004 §Presence names. It disables the real-`setTimeout` arm so the
  logical clock is the sole removal driver, then advances that clock: with no
  argument, to quiescence (every pending exit fires); with `ms`, by that many
  logical milliseconds (partial advance). Returns the number of exits removed.

  A test drives presence transitions through this and never `js/setTimeout`, so
  a retention window closes exactly when the test says and a run carries no
  wall-clock flake."
  ([]   (set-wall-clock! false) (advance-clock!))
  ([ms] (set-wall-clock! false) (advance-clock! ms)))

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
  string-coerced), the identity `reconcile` tracks. The front end guarantees
  every presence child is keyed, so an element without a key is defensive-only.
  Flattening can still surface the SAME key twice (explicit siblings, two `for`
  arrays, colliding dynamic keys) — `claim-identities` reduces the result to one
  pair per identity before the retention machine ever sees it."
  [acc x]
  (cond
    (nil? x)              acc
    (js/Array.isArray x)  (reduce collect-elements acc x)
    (react-element? x)    (if-some [k (.-key x)] (conj acc [k x]) acc)
    :else                 acc))

(defn- count-keyless
  "Count the flattened children whose runtime `.-key` is nil — the elements
  `collect-elements` DROPS (a keyless child cannot be retention-tracked, so it
  never renders). The compiled tier guarantees a `:key` FORM on every presence
  child, but the form's runtime VALUE can be `undefined` (e.g. a JS-interop read
  of an absent property), which React's jsx-runtime leaves as a nil `.-key` —
  compile cannot catch it. NOTE a CLJS-nil key value is a different case: jsx
  coerces it to the string \"null\" (a valid key), so `{:key (:id x)}` with a
  missing `:id` RENDERS rather than dropping. This parallel dev-only walk lets
  the render path warn about the otherwise-silent drop; the drop itself stays in
  `collect-elements`, unconditional in production."
  [n x]
  (cond
    (nil? x)              n
    (js/Array.isArray x)  (reduce count-keyless n x)
    (react-element? x)    (if (nil? (.-key x)) (inc n) n)
    :else                 n))

(defn- warn-duplicate-identities!
  "Dev diagnostic for a key claimed twice at ONE presence boundary. Reuses the
  catalogued `:rf.error/ui-duplicate-key` — same failure, same string-coercion
  collision domain as a keyed list site, one boundary further out. Stripped in
  production by `goog.DEBUG`; the drop itself is unconditional, so a production
  build still never diverges ownership."
  [dup-keys]
  (when ^boolean js/goog.DEBUG
    (doseq [k dup-keys]
      (js/console.warn
       "[:rf.error/ui-duplicate-key] duplicate key at a (v/presence …) boundary:"
       (pr-str k)
       (str "— presence tracks children BY KEY, so two children under one key "
            "would share one retained identity and one exit timer. The later "
            "child is DROPPED. Give each presence child a distinct key (keys "
            "compare after React's string coercion: 1 collides with \"1\")."))))
  nil)

(defn- warn-keyless-drops!
  "Dev diagnostic for a presence child whose runtime `:key` evaluated to nil.
  Mirrors `warn-duplicate-identities!`: advisory dev output stripped in
  production by `goog.DEBUG`, while the drop itself (in `collect-elements`) is
  unconditional, so a production build never diverges. No catalogued
  `:rf.error/*` id — a nil runtime key is not a new failure mode (compile still
  guarantees the `:key` form), and `:rf.error/ui-duplicate-key` does not honestly
  fit; this is a plain advisory. One warning per dropped element."
  [n]
  (when ^boolean js/goog.DEBUG
    (dotimes [_ n]
      (js/console.warn
       (str "presence: a keyless child under a (v/presence …) boundary was "
            "DROPPED — its :key evaluated to `undefined` (a nil React key), so "
            "it never renders. Presence tracks children BY KEY; give the child a "
            "defined key. (A CLJS-nil key value becomes the string \"null\" and "
            "renders — a nil React key comes from an `undefined` value.)"))))
  nil)

(defn- incoming-identities
  "The flattened children of this boundary as ordered, ownership-unique
  `[key element]` pairs — the only shape the retention machine may see."
  [incoming]
  (let [[pairs dups] (claim-identities (collect-elements [] incoming))]
    (warn-duplicate-identities! dups)
    ;; `count-keyless` is a SECOND full walk of the child tree, run purely to
    ;; feed the dev advisory. Gate the traversal AND its warning together behind
    ;; the compile-time `goog.DEBUG` branch so `:advanced` + `goog.DEBUG=false`
    ;; DCEs the whole walk — an eager argument would otherwise still execute in
    ;; production and discard its result (rf2-vz6a1). `dups` above needs no such
    ;; gate: it is already computed by `claim-identities`, so its warning is free.
    (when ^boolean js/goog.DEBUG
      (warn-keyless-drops! (count-keyless 0 incoming)))
    pairs))

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
        ;; COMMITTED state (source of truth) — one ref the current and the
        ;; work-in-progress fiber BOTH see. Mutated only in the commit effect
        ;; and the removal callbacks, never during render, so `reconcile` reads
        ;; a stable snapshot, a StrictMode double-render is idempotent, and a
        ;; render React abandons leaves it exactly as it found it. The lazy seed
        ;; below is the one render-phase write, and it can only ever install the
        ;; empty state.
        st         (react/useRef nil)
        incoming-pairs (incoming-identities incoming)
        incoming-keys  (mapv first incoming-pairs)]
    (when (nil? (.-current st))
      ;; FIRST render seed — the ordinary client mount: no entries, so
      ;; `reconcile` classifies the incoming keys `:mounting` and the enter
      ;; transition runs. Server-adopted hydration (seeding `:present` to match
      ;; the server's `:present` markup instead of fabricating an enter
      ;; transition) is the SSR slice's to wire, once Freehand carries the
      ;; root-scoped phase fact `client-only` will install; F4 presence has no
      ;; hydration signal to consult, so it always seeds the client-mount path.
      (set! (.-current st) #js {:entries [] :elements {} :timers {}}))
    (let [state    (.-current st)
          ;; THIS RENDER'S CANDIDATE element map — a value this render holds and
          ;; returns its output from, never a publication. The committed
          ;; snapshot with the incoming elements laid over it; a retained key
          ;; keeps its last COMMITTED element.
          elements (merge (.-elements state) (into {} incoming-pairs))
          desired  (reconcile (.-entries state) incoming-keys)]
      ;; Commit + phase transitions run AFTER paint (a side-effect, never during
      ;; render). Runs every commit; idempotent via signature guards.
      (react/useEffect
       (fn []
         (let [state   (.-current st)
               ;; re-derived from the same props the render saw; the uniqueness
               ;; claim is pure, so both paths agree on the ordered key set
               ;; (the dev warning already fired during render).
               pairs   (first (claim-identities (collect-elements [] incoming)))
               ;; PUBLISH the elements — here, because React runs an effect only
               ;; for a render it COMMITTED. A candidate React abandons (a
               ;; transition that suspends, a superseded render) runs no effect
               ;; and so publishes nothing, exactly as a dropped
               ;; `re-frame.freehand.cell` candidate does. Publishing during
               ;; render instead would hand a never-committed element to the
               ;; shared ref that the CURRENT tree renders from, and the next
               ;; timer-driven commit would paint it.
               ;;
               ;; `.-elements` is re-read here rather than reusing the render's
               ;; merge, so a removal callback that fired in the render→commit
               ;; gap is not undone by a stale snapshot.
               _       (set! (.-elements state)
                             (merge (.-elements state) (into {} pairs)))
               desired (reconcile (.-entries state) (mapv first pairs))
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
  "Build a `(v/presence {:timeout-ms n} children)` element (the lowering target
  both modes emit). `timeout-ms` is the terminal safety bound; `children` is the
  keyed children as a JS array (possibly nesting the arrays a `for` emits) — the
  compiled emitter hands `(cljs.core/array …)` and the interpreted React walk
  hands the walked child elements as an array."
  [timeout-ms children]
  (react/createElement PresenceComponent
                       #js {:timeoutMs timeout-ms :incoming children}))

   ))

;; ---------------------------------------------------------------------------
;; presence-phase — the single phase read (both hosts)
;; ---------------------------------------------------------------------------

(defn presence-phase
  "Read the current presence phase — `:mounting` / `:present` / `:unmounting`
  inside a `(v/presence …)` boundary, `:present` outside one. A render-time
  read (CLJS `react/useContext`); on the JVM structural subset it is always
  `:present`."
  []
  #?(:cljs (react/useContext presence-context)
     :clj  :present))
