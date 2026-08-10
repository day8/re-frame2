(ns re-frame.hicasso.impl.presence
  "PRESENCE AS DATA — the retention machine and the phase transform
  (rf2-2rtt6.37, HD-025). The **pure half**: a value in, a value out, no
  React, no clock, no ambient read. The React component that drives it is
  `re-frame.hicasso.impl.presence-react`.

  ## The trap this deletes

  The shipped predecessor exposes a keyed child's phase as an AMBIENT
  READ, and its own guide records what that costs, verbatim:

  > Read the phase inside a DECLARED, KEYED CHILD VIEW, as above. Reading
  > it in markup written inline in the parent is a trap: those props are
  > evaluated during the PARENT'S render, so the phase you get is the
  > parent's, not the per-child one you meant.

  So a fading toast cannot be written inline. It must be extracted into a
  child view purely so a dynamic var resolves against the right child,
  and getting it wrong yields the wrong phase SILENTLY. The a11y
  obligation then costs three separate `(when exiting? …)` attributes on
  that child.

  ## The two changes

  **(1) `::h/mounting` and `::h/unmounting` are attribute OVERRIDE MAPS
  on a native node.** The boundary merges them into that node's attrs
  while the child is in that phase:

      (h/presence {:timeout-ms 300}
        (for [t (sub [:toasts/visible])]
          [:div.toast {:key (:id t)
                       ::h/unmounting {:class \"toast toast--exit\"
                                       :inert true :aria-hidden true}}
           (:message t)]))

  The child view has disappeared and the three `(when exiting? …)`
  attributes are one map.

  **(2) When the child IS a boundary, the phase arrives as an ORDINARY
  PROP** — `[toast-card {:key id :toast t :rf/phase :unmounting}]`. That
  deletes the trap by construction: a prop cannot be read from the wrong
  render scope, it appears in a structural test's props map, and a
  headless test can supply it with no clock.

  The consequence worth recording: `presence-phase` has no Hicasso
  equivalent. One fewer public concept against K5. (K5 — the ergonomics
  kill criterion — was removed by operator ruling on 2026-08-04; this
  records the reason the shape was chosen, not a live gate.)

  ## Why the predecessor's rejection does not apply

  It rejected exactly this, and gave a reason: *\"A boundary that stamped
  attributes would have to guess at a node it never sees.\"* That is
  sound for a boundary stamping **by itself**. It does not survive the
  AUTHOR writing the override on the node. The boundary already owns the
  retained-children list — that is what retention IS — so applying an
  override the author wrote is a hiccup→hiccup transform performed before
  the codec runs. React then sees an ordinary element whose props
  changed: no wrapper node, no stamped `data-*`, **no ref, no effect, no
  hook and no ambient read** in the merge itself.

  ## Honest limits

  - **The override applies to a node the boundary can SEE.** An override
    written inside an opaque child view is invisible to it; change (2) is
    what that case is for, and an override written on a boundary child is
    a loud error naming `:rf/phase` rather than a silently dropped map.
  - **ENTER is the weak half**, and the predecessor already says why:
    driving enter purely as a `:mounting` → `:present` class flip can race
    paint. `::h/mounting` ships, and the guide teaches the CSS answer —
    an animation on insertion, or `@starting-style`.

  ## Inherited unchanged

  `:timeout-ms` is MANDATORY, and is both the retention length and the
  hard terminal bound; re-entry cancels exit; keys are required on every
  dynamic child; presence never dispatches domain mount/unmount events.

  ## The machine

  A state value is `{:order [key …] :entries {key {:child … :phase …
  :deadline …}}}`. [[step]] is the whole transition and is **idempotent**
  — `(step (step s cs t1 ms) cs t2 ms)` equals `(step s cs t1 ms)` — which
  is what lets the React half adjust its state during render rather than
  spend an effect on it. Deadlines are absolute instants stored when a
  key starts exiting, so a timer re-armed for an unrelated reason cannot
  extend a child's retention past its terminal bound."
  (:require [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.error :refer [fail!]]))

;; ---------------------------------------------------------------------------
;; The reserved keys
;; ---------------------------------------------------------------------------

(def mounting-key
  "`::h/mounting` — the attribute overrides applied while a child is
  entering."
  :re-frame.hicasso/mounting)

(def unmounting-key
  "`::h/unmounting` — the attribute overrides applied while a child is
  being retained on its way out."
  :re-frame.hicasso/unmounting)

(def override-keys #{mounting-key unmounting-key})

(def phase-prop
  "`:rf/phase` — how a BOUNDARY child receives its phase. An ordinary
  prop, so it cannot be read from the wrong render scope, it appears in a
  structural test's props map, and a headless test supplies it with no
  clock."
  :rf/phase)

(def initial {:order [] :entries {}})

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

;; `fail!` is `re-frame.hicasso.impl.error`'s — one constructor for the whole
;; package, and the ambient view and source coordinate come with it
;; (rf2-hic-007). The eight lines that stood here were one of six identical
;; copies.

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
  runs on — an unkeyed child has no way to be the same child next render."
  [child]
  (when-not (vector? child)
    (fail! :rf.error/hicasso-presence-child-not-hiccup
           'front.presence/child-key
           (str "A presence child must be a keyed hiccup vector; it was "
                (pr-str child) ".")
           :give-every-presence-child-a-keyed-hiccup-vector
           {:child child}))
  (let [k (:key (props-of child))]
    (when (nil? k)
      (fail! :rf.error/hicasso-presence-child-unkeyed
             'front.presence/child-key
             (str "A presence child has no :key. Presence retains children by "
                  "key, so an unkeyed child cannot be recognised across a "
                  "render and cannot be animated out.")
             :put-a-key-in-the-child-props-map
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
  "Apply `phase` to one child, as data.

  A **native node** takes the phase's attribute-override map merged over
  its own attributes — the override WINS, because that is what an
  override is — with the two structural slots never taken from it, the
  same law `:&` carries (HD-023). The two override keys are always
  removed, so an override never reaches the DOM as an attribute.

  **The exclusion is on the canonical SLOT, through the filter
  [[re-frame.hicasso.impl.codec/without-structural]] that `:&`
  uses.** It has to be: an override carrying `\"key\"` or `:x/key`
  survives a raw `#{:key :ref}` dissoc and canonicalises straight onto
  React's key, which would remount the very node presence exists to
  retain — the child would restart its exit, or vanish and come back,
  precisely while it is being animated out. A `\"ref\"` or `:x/ref`
  likewise reaches the retained node. Retained key identity is therefore
  pinned by construction: the only `:key` in the merged map is the one
  the child was retained under, because nothing else can reach that slot
  in any spelling.

  A **boundary child** takes `:rf/phase` as an ordinary prop instead, and
  an override written there is a loud error: the boundary cannot see
  inside an opaque view, and silently dropping the map is the class of
  failure this whole ruling exists to delete."
  [child phase]
  (let [props (props-of child)]
    (if (codec/boundary-head? (nth child 0))
      (do
        (when (some #(contains? props %) override-keys)
          (fail! :rf.error/hicasso-presence-override-on-a-view
                 'front.presence/with-phase
                 (str "A presence attribute override was written on a VIEW head. "
                      "Presence merges overrides into nodes it can see; a view is "
                      "opaque to it. The view receives " (pr-str phase-prop)
                      " as an ordinary prop — branch or style on that instead.")
                 :read-the-phase-prop-inside-the-view
                 {:child child :phase phase}))
        (with-props child (assoc props phase-prop phase)))
      (let [override (override-for props phase)
            base     (when props (apply dissoc props override-keys))]
        (cond
          (map? override)
          (with-props child (merge base (codec/without-structural override)))

          ;; Nothing to strip and nothing to merge: the child is already
          ;; exactly what it should render as, so it comes back untouched
          ;; and this phase costs no allocation at all.
          (= base props) child

          (some? base) (with-props child base)
          :else        child)))))

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
           'front.presence/check-timeout!
           (str "presence needs a positive :timeout-ms; it was "
                (pr-str timeout-ms) ". It is the retention length and the hard "
                "terminal bound — presence does not wait on transitionend, so "
                "without it a child could be retained forever.")
           :give-presence-a-positive-timeout-ms
           {:timeout-ms timeout-ms}))
  timeout-ms)
