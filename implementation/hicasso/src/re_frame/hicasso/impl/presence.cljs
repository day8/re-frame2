(ns re-frame.hicasso.impl.presence
  "PRESENCE AS DATA — the retention machine and the phase transform
  (HD-025). The **pure half**: a value in, a value out, no React, no
  clock, no ambient read. The React component that drives it is
  `re-frame.hicasso.impl.presence-react`.

  ## Why the phase is never an ambient read

  Exposing a keyed child's phase as a dynamic var read is a trap, and the
  trap is silent. Props written inline in the parent are evaluated during
  the PARENT'S render, so an ambient read there resolves to the parent's
  phase rather than the per-child one the author meant. Avoiding it would
  force every fading child into a separate declared view purely so the
  var resolves against the right child, and the a11y obligation would
  then cost three separate `(when exiting? …)` attributes on that child.

  ## The one shape

  **`::motion/mounting` and `::motion/unmounting` are OVERRIDE MAPS on
  the child**, merged into it while the child is in that phase — into an
  element's attributes, and equally into a view's props (HD-030):

      (motion/presence {:timeout-ms 300}
        (for [t (sub [:toasts/visible])]
          [:div.toast {:key (:id t)
                       ::motion/unmounting {:class \"toast toast--exit\"
                                            :inert true :aria-hidden true}}
           (:message t)]))

      [toast-card {:key id :toast t ::motion/unmounting {:exiting? true}}]

  A view is handed ordinary props under names its author chose and
  branches on those; it never sees the phase as a value. That closes the
  trap above by construction: a merged prop cannot be read from the
  wrong render scope, it appears in a structural test's props map, and a
  headless test supplies it with no clock. There is no `presence-phase`
  surface and no `:rf/phase` prop — one grammar for both child kinds.

  ## Why the boundary may apply an override at all

  A boundary that stamped attributes *by itself* would have to guess at a
  node it never sees, and that is not what happens here: the AUTHOR
  writes the override on the node. The boundary already owns the
  retained-children list — that is what retention IS — so applying an
  override the author wrote is a hiccup→hiccup transform performed before
  the codec runs. React then sees an ordinary element whose props
  changed: no wrapper node, no stamped `data-*`, **no ref, no effect, no
  hook and no ambient read** in the merge itself.

  ## Honest limits

  - **The override applies to the child the tray can SEE.** An override
    written INSIDE an opaque child view is invisible to it, and the
    codec's prop walks skip it rather than emit it; the override goes on
    the child's own head, where the tray merges it.
  - **ENTER is the weak half**: driving enter purely as a `:mounting` →
    `:present` class flip can race paint. `::motion/mounting` ships, and
    the guide teaches the CSS answer — an animation on insertion, or
    `@starting-style`.

  ## The standing rules

  `:timeout-ms` is MANDATORY, and is both the retention length and the
  hard terminal bound; re-entry cancels exit; keys are required on every
  dynamic child; presence never dispatches domain mount/unmount events.

  ## The machine

  A state value is `{:order [key …] :entries {key {:child … :phase …
  :deadline …}}}`. `step` is the whole transition and is **idempotent**
  — `(step (step s cs t1 ms) cs t2 ms)` equals `(step s cs t1 ms)` — which
  is what lets the React half adjust its state during render rather than
  spend an effect on it. Deadlines are absolute instants stored when a
  key starts exiting, so a timer re-armed for an unrelated reason cannot
  extend a child's retention past its terminal bound."
  (:require [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.error :refer [fail!]]))

;; ---------------------------------------------------------------------------
;; The reserved keys
;; ---------------------------------------------------------------------------

;; The two override keys are DEFINED in `re-frame.hicasso.impl.codec` and
;; read from there: they are this module's vocabulary, but the codec's
;; prop walks have to recognise them — an override no tray reached is
;; skipped there rather than emitted as an attribute — and this namespace
;; requires the codec rather than the other way round.

(def mounting-key
  "`::motion/mounting` — the attribute overrides applied while a child is
  entering."
  rf.hicasso.impl.codec/mounting-key)

(def unmounting-key
  "`::motion/unmounting` — the attribute overrides applied while a child
  is being retained on its way out."
  rf.hicasso.impl.codec/unmounting-key)

(def override-keys #{mounting-key unmounting-key})

(def initial {:order [] :entries {}})

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

;; `fail!` is `re-frame.hicasso.impl.error`'s — one constructor for the whole
;; package, and the ambient view and source coordinate come with it.

;; ---------------------------------------------------------------------------
;; Reading a child
;; ---------------------------------------------------------------------------

(defn renderable?
  "`nil` and `false` children render nothing, so they are not entries.
  Everything else must be a keyed hiccup vector."
  [child]
  (and (some? child) (not (false? child))))

(defn- props-of [child] (let [p (nth child 1 nil)] (when (map? p) p)))

(defn- with-props
  "Put `props` on a hiccup vector, inserting the map when the vector had
  none."
  [child props]
  (if (map? (nth child 1 nil))
    (assoc child 1 props)
    (into [(nth child 0) props] (subvec child 1))))

(defn child-key
  "A child's `:key`, from the props map where HD-016 puts it. Required on
  every dynamic child, because the key IS the identity the state machine
  runs on — a child with no key, a non-vector included, has no way to be
  the same child next render, so it is refused rather than retained under
  an identity nothing wrote."
  [child]
  (let [k (when (vector? child) (:key (props-of child)))]
    (when (nil? k)
      (fail! :rf.error/hicasso-presence-child-unkeyed
             're-frame.hicasso.impl.presence/child-key
             (str "A presence child must be a hiccup vector with a :key; it was "
                  (pr-str child) ". Presence retains children by key, so a "
                  "child without one cannot be recognised across a render "
                  "and cannot be animated out.")
             {:child child}))
    k))

;; ---------------------------------------------------------------------------
;; The phase transform — hiccup in, hiccup out
;; ---------------------------------------------------------------------------

(defn- override-for [props phase]
  (case phase
    :mounting   (get props mounting-key)
    :unmounting (get props unmounting-key)
    nil))

(defn with-phase
  "Apply `phase` to one child, as data: the phase's override map merged
  over the child's own props — an element's attributes or a view's props
  alike — with the override WINNING, because that is what an override
  is, and the two override keys removed. A child carrying no override
  comes back untouched, by identity.

  The structural slots are never taken from the override, and the
  exclusion is on the canonical SLOT through
  `re-frame.hicasso.impl.codec/without-structural`: an override carrying
  `\"key\"` or `:x/key` would otherwise canonicalise onto React's key and
  remount the very child presence exists to retain, mid-exit. Design
  record: docs/design/hicasso/decisions.md, HD-025 and HD-030."
  [child phase]
  (let [props    (props-of child)
        override (override-for props phase)
        base     (when props (apply dissoc props override-keys))]
    (cond
      (map? override)
      (with-props child (merge base (rf.hicasso.impl.codec/without-structural override)))

      ;; Nothing to strip and nothing to merge: the child is already
      ;; exactly what it should render as, so it comes back untouched
      ;; and this phase costs no allocation at all.
      (= base props) child

      (some? base) (with-props child base)
      :else        child)))

;; ---------------------------------------------------------------------------
;; The machine
;; ---------------------------------------------------------------------------

(defn step
  "The whole transition: fold `children` (as they are now) into `state`,
  starting an exit for every key that has gone and cancelling one for
  every key that has come back.

  **Idempotent**, which is the property the React half rides: a second
  application with the same children changes nothing, whatever `now` is,
  because a `:mounting` entry stays `:mounting` and an exiting entry
  keeps the deadline it was given. `now + timeout-ms` is stored as an
  absolute instant precisely so that re-deriving cannot extend it."
  [state children now timeout-ms]
  (let [live      (filterv renderable? children)
        live-keys (mapv child-key live)
        live-set  (set live-keys)
        by-key    (zipmap live-keys live)
        prev      (:entries state)
        entries   (reduce (fn [acc k]
                            (let [p     (get prev k)
                                  child (get by-key k)]
                              (assoc acc k
                                     (cond
                                       (nil? p)
                                       {:child child :phase :mounting}

                                       ;; Re-entry cancels exit — the child
                                       ;; returns to :present rather than
                                       ;; finishing its exit and remounting.
                                       (= :unmounting (:phase p))
                                       {:child child :phase :present}

                                       :else
                                       (assoc p :child child)))))
                          {}
                          live-keys)
        entries   (reduce-kv (fn [acc k p]
                               (if (contains? live-set k)
                                 acc
                                 (assoc acc k
                                        (if (= :unmounting (:phase p))
                                          p
                                          (assoc p :phase    :unmounting
                                                   :deadline (+ now timeout-ms))))))
                             entries
                             prev)
        ;; First-appearance slots are frozen, so an exiting child does not
        ;; jump mid-animation: surviving keys keep their order and genuinely
        ;; new keys are appended in the order the source produced them.
        kept      (filterv #(contains? entries %) (:order state))]
    {:order   (into kept (remove (set kept)) live-keys)
     :entries entries}))

(defn settle
  "Flip every `:mounting` entry to `:present`. The React half calls this
  after paint; a headless test calls it directly."
  [state]
  (let [entries (reduce-kv (fn [m k e]
                             (assoc m k (if (= :mounting (:phase e))
                                          (assoc e :phase :present)
                                          e)))
                           {}
                           (:entries state))]
    (if (= entries (:entries state)) state (assoc state :entries entries))))

(defn expire
  "Drop every retained child whose deadline has passed. Removal is
  terminal: `:timeout-ms` is a clock, not a transition listener, so a
  child leaves on time whether or not any CSS ran."
  [state now]
  (let [dead (into #{}
                   (keep (fn [[k e]]
                           (when (and (= :unmounting (:phase e))
                                      (<= (:deadline e) now))
                             k)))
                   (:entries state))]
    (if (empty? dead)
      state
      {:order   (filterv (complement dead) (:order state))
       :entries (apply dissoc (:entries state) dead)})))

(defn next-deadline
  "The earliest instant at which something must leave, or `nil`. One
  timer serves every exiting child."
  [state]
  (when-some [ds (seq (keep (comp :deadline val) (:entries state)))]
    (reduce min ds)))

(defn mounting?
  "Is anything waiting for its enter flip?"
  [state]
  (boolean (some (fn [[_ e]] (= :mounting (:phase e))) (:entries state))))

(defn pending-signature
  "What the React half's effect depends on: the work outstanding, as a
  value it can compare. Deliberately a printed value rather than a hash —
  a collision here would drop a timer, and dropping a timer means a node
  that never leaves."
  [state]
  (pr-str [(next-deadline state) (mounting? state)
           (filterv (fn [k] (= :unmounting (:phase (get (:entries state) k))))
                    (:order state))]))

(defn phases
  "`key -> phase`, in order. The headless assertion surface."
  [state]
  (into {} (map (fn [k] [k (:phase (get (:entries state) k))])) (:order state)))

(defn render
  "The children to hand the codec: every retained and live child, in
  frozen order, each with its phase already applied as data."
  [state]
  (mapv (fn [k]
          (let [e (get (:entries state) k)]
            (with-phase (:child e) (:phase e))))
        (:order state)))

(defn check-timeout!
  "`:timeout-ms` is MANDATORY and positive, inherited unchanged. It is the
  retention length AND the hard terminal bound, so a boundary without one
  is a boundary whose children can be stuck on screen forever if CSS
  fails or is disabled."
  [timeout-ms]
  (when-not (and (number? timeout-ms) (pos? timeout-ms))
    (fail! :rf.error/hicasso-presence-timeout-required
           're-frame.hicasso.impl.presence/check-timeout!
           (str "presence needs a positive :timeout-ms; it was "
                (pr-str timeout-ms) ". It is the retention length and the hard "
                "terminal bound — presence does not wait on transitionend, so "
                "without it a child could be retained forever.")
           {:timeout-ms timeout-ms}))
  timeout-ms)
