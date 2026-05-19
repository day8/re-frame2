(ns day8.re-frame2-causa.static.machines.sim-helpers
  "Pure-data helpers for Causa's Static Machines Sim sub-mode (rf2-r4nao
  rehost; engine originally rf2-v869p Phase 2, parent rf2-2tkza).

  ## Rehost (rf2-r4nao)

  Rehosted from `panels/machine_inspector_sim_helpers.cljc` when the
  Runtime Machine Inspector collapsed (rf2-y9xmf). The pure-data algebra
  is unchanged — only the ns name + the consuming UI surface moved.
  Sim is now exclusively a Static-surface sub-mode (event-INDEPENDENT
  'what-if' simulator).

  ## Why a separate `.cljc` ns

  Same dual-target pattern every other Causa helper ns uses
  (subscriptions, routes, machine-inspector-helpers).
  The sim view in `static/machines/sim.cljs` builds the hiccup; the
  *logic* — projecting a definition into an initial snapshot, deriving
  available transitions, building audit-trail rows — lives here as
  pure data → data so the JVM unit-test target (`clojure -M:test`)
  drives it without a CLJS runtime.

  ## Design source-of-truth

  `ai/findings/causa-uc1-simulation-design-2026-05-17.md` carries the
  full UC1 Sim design. Quick recap:

    - Sim is a **sub-mode of Mode A** — a toggle in the panel header
      flips the chart from live-highlight to sim-highlight (amber).
    - The sim **clones** the registered machine definition into Causa
      state; production registry is untouched.
    - A user picks an event from an autocomplete-style picker (v1: a
      text input + dropdown of declared events for the current state)
      and clicks Step.
    - The runtime calls `rf/machine-transition` (the public late-bind
      surface) with the cloned definition + current sim snapshot +
      event vector. On `result/ok`, the snapshot advances and an
      audit-trail row is appended. On `result/fail`, the snapshot stays
      and an error surfaces.
    - Reset returns to the declared initial state.
    - Exit disposes the sim state (per-machine slot deleted).

  ## Sim-state shape

  The Sim sub-mode keeps per-machine state on Causa's frame app-db at
  `[:sim/by-machine <machine-id>]`. Each slot is:

      {:active?         <bool>   ;; sub-mode toggled on?
       :definition      <map>    ;; the cloned machine definition
       :snapshot        <map>    ;; current {:state :data ...}
       :audit-trail     <vec>    ;; [{:from :to :event :guard?} ...]
       :last-error      <map>    ;; nil or {:event :info :reason}
       :pending-event   <str>    ;; the event-id text the user is typing
       :pending-data    <str>}   ;; EDN payload (optional)

  ## What this ns exposes

    1. `initial-snapshot`          — definition → seed `{:state :data}`
    2. `available-transitions`     — definition + snapshot → seq of
                                     {:event :target :guard} maps
    3. `event-id-suggestions`      — definition → distinct sorted
                                     event-ids (autocomplete source)
    4. `parse-event-vector`        — string \"[:foo {:x 1}]\" → vector
                                     or nil on parse error
    5. `append-audit-row`          — push a step entry onto the trail
    6. `format-state-display`      — sim chart state-keyword resolver
    7. `result-ok?` / `result-snap` / `result-info` — thin shims so
       the CLJS panel can read Result values without importing the
       machines artefact ns directly (Causa has no compile-time dep
       on `re-frame.machines.result`)."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---- definition introspection -------------------------------------------

(defn- walk-states
  "Walk `state-map` recursively, calling `(f path node)` for every leaf
  and compound state node. Returns nothing — purely for side-effecting
  reduction. The companion `collect-event-ids` / `available-transitions`
  fns build their own accumulators around it."
  ([f state-map] (walk-states f [] state-map))
  ([f parent-path state-map]
   (doseq [[state-id node] state-map]
     (let [path (conj parent-path state-id)]
       (f path node)
       (when (:states node)
         (walk-states f path (:states node)))))))

(defn event-id-suggestions
  "Return the distinct sorted event-ids declared anywhere in `definition`.
  Walks every state-node's `:on` map and aggregates the keys. The
  autocomplete in the sim event-picker is seeded from this set so the
  user sees only events the machine actually responds to.

  Returns `[]` when the definition is nil or has no `:states`."
  [definition]
  (if (or (nil? definition) (nil? (:states definition)))
    []
    (let [acc (atom #{})]
      (walk-states
        (fn [_path node]
          (doseq [event-id (keys (:on node))]
            (swap! acc conj event-id)))
        (:states definition))
      (vec (sort-by str @acc)))))

(defn- node-at
  "Walk `definition`'s `:states` down the given `path` (vector of
  state-id keywords) and return the leaf node. Tolerates a path with a
  single keyword or a hierarchical vector."
  [definition path]
  (loop [m  (:states definition)
         p  (vec path)]
    (cond
      (empty? p)      nil
      (nil? m)        nil
      :else
      (let [n (get m (first p))]
        (cond
          (nil? n)        nil
          (= 1 (count p)) n
          :else           (recur (:states n) (rest p)))))))

(defn- normalise-path
  "Coerce a snapshot `:state` (keyword OR vector) into a vector path
  for `node-at` lookup."
  [state]
  (cond
    (nil? state)     []
    (keyword? state) [state]
    (vector? state)  state
    :else            []))

(defn- candidates
  "Mirror of `transition.cljc`'s normaliser — flatten a transition spec
  into a seq of candidate maps each carrying `:target` / optional `:guard`
  / optional `:action`. Pure-local copy so the helpers don't reach into
  the machines artefact."
  [spec]
  (cond
    (keyword? spec)            [{:target spec}]
    (and (vector? spec)
         (every? keyword? spec)) [{:target spec}]
    (map? spec)                [spec]
    (sequential? spec)         (mapcat candidates spec)
    :else                      []))

(defn available-transitions
  "Return a seq of `{:event :target :guard?}` maps — one per outgoing
  transition declared on the snapshot's current state node. The picker
  surfaces these as the user's step options.

  v1 scope: only direct `:on` transitions on the leaf state. `:always` /
  `:after` / parent-state inheritance are deferred to a follow-on bead
  (the engine handles them at step time; the picker only surfaces what
  the user can fire interactively from the leaf).

  Returns `[]` when the definition / snapshot is nil, or the current
  path doesn't resolve to a registered state."
  [definition snapshot]
  (let [path (normalise-path (:state snapshot))
        node (node-at definition path)]
    (if (nil? node)
      []
      (vec
        (for [[event-id spec] (:on node)
              candidate       (candidates spec)
              :let [t (:target candidate)]
              :when (some? t)]
          {:event   event-id
           :target  t
           :guard?  (some? (:guard candidate))
           :guard   (:guard candidate)})))))

;; ---- initial snapshot ---------------------------------------------------

(defn initial-snapshot
  "Build the seed sim snapshot for `definition`. Shape:

      {:state <keyword-or-vector>  ;; the declared :initial leaf
       :data  <map>}               ;; the declared :data initial map

  Phase 2 keeps the initial-state cascade simple (the `:initial` slot
  shallow-read) — the runtime's own `apply-initial-entry-cascade` would
  fire `:entry` actions, which sim deliberately skips at v1 (we want a
  pure, hermetic step machine the user drives). The first user-fired
  event runs `rf/machine-transition` which DOES execute entry / exit /
  action cascades — so action evaluation kicks in from step 1 onwards,
  not from initial bootstrap.

  Returns nil when `definition` is nil or has no `:initial`."
  [definition]
  (when (and (map? definition) (some? (:initial definition)))
    {:state (:initial definition)
     :data  (or (:data definition) {})}))

;; ---- sim-state lifecycle ------------------------------------------------

(defn make-sim-state
  "Build a fresh sim-state map for `machine-id` + `definition`. Called
  when the user toggles Sim on for a machine. The `:active?` flag stays
  true until `dispose-sim-state` zeros the slot."
  [machine-id definition]
  {:machine-id     machine-id
   :active?        true
   :definition     definition
   :snapshot       (initial-snapshot definition)
   :audit-trail    []
   :last-error     nil
   :pending-event  ""
   :pending-data   ""})

(defn reset-sim-state
  "Return `sim-state` reset to its initial snapshot, audit trail
  cleared, error cleared. Preserves `:definition` + `:active?` so the
  user stays in sim mode after a reset. Pending input is preserved (the
  user likely wants to re-fire what they were sketching)."
  [sim-state]
  (assoc sim-state
    :snapshot    (initial-snapshot (:definition sim-state))
    :audit-trail []
    :last-error  nil))

(defn append-audit-row
  "Push one step entry onto the trail. `row` is `{:from :to :event
  :guard? :data}`. The trail grows newest-last (the view renders it in
  insertion order)."
  [sim-state row]
  (update sim-state :audit-trail (fnil conj []) row))

(defn record-error
  "Stamp an error onto sim-state without advancing the snapshot. The
  view surfaces this in a red toast / inline. `info` is whatever the
  fail-Result's `::info` slot carried; `reason` is a human-readable
  string."
  [sim-state event info reason]
  (assoc sim-state :last-error {:event event :info info :reason reason}))

(defn clear-error
  "Drop any stamped error. Called on the next successful step."
  [sim-state]
  (assoc sim-state :last-error nil))

;; ---- event-vector parsing -----------------------------------------------

(defn parse-event-vector
  "Parse the user's typed event input into a re-frame event vector.

  Accepts:

    - a keyword string like `:foo/bar`        → `[:foo/bar]`
    - a vector form like `[:foo/bar {:x 1}]` → `[:foo/bar {:x 1}]`

  Returns the event vector on success, or a `{:error <str>}` map on
  parse failure. Whitespace-only input returns `{:error \"empty\"}`.

  Pure fn — JVM-runnable so the parsing rules are unit-testable."
  [text]
  (cond
    (nil? text)
    {:error "empty"}

    (str/blank? text)
    {:error "empty"}

    :else
    (let [trimmed (str/trim text)]
      (try
        (let [parsed (edn/read-string trimmed)]
          (cond
            (keyword? parsed)
            [parsed]

            (and (vector? parsed) (keyword? (first parsed)))
            parsed

            :else
            {:error (str "expected a keyword or vector starting with a keyword, got "
                         (pr-str parsed))}))
        (catch #?(:clj Throwable :cljs :default) e
          {:error (str "EDN parse error: " (ex-message e))})))))

;; ---- Result shims --------------------------------------------------------
;;
;; `rf/machine-transition` returns a `re-frame.machines.result/Result` —
;; a map shaped `{:re-frame.machines.result/tag :ok|:fail ...}`. Causa
;; has no compile-time dep on the machines artefact (the artefact may
;; not be on the classpath at all if the host hasn't installed it). We
;; shim the slot reads here via the literal qualified keywords so the
;; sim code path stays artefact-agnostic — the keyword shape is part of
;; the Result's public contract (per `result.cljc` docstring).

(def ^:private result-tag-kw :re-frame.machines.result/tag)
(def ^:private result-snap-kw :re-frame.machines.result/snap)
(def ^:private result-fx-kw :re-frame.machines.result/fx)
(def ^:private result-info-kw :re-frame.machines.result/info)

(defn result-ok?
  "True iff `r` is an `:ok` Result. Mirrors `result/ok?` without
  requiring the artefact ns at compile time."
  [r]
  (and (map? r) (= :ok (get r result-tag-kw))))

(defn result-fail?
  "True iff `r` is a `:fail` Result."
  [r]
  (and (map? r) (= :fail (get r result-tag-kw))))

(defn result-snap
  "Read the post-transition snapshot off an `:ok` Result."
  [r]
  (get r result-snap-kw))

(defn result-fx
  "Read the emitted fx vector off an `:ok` Result. Sim renders these in
  the audit trail entry but does NOT execute them — sim is hermetic."
  [r]
  (get r result-fx-kw))

(defn result-info
  "Read the diagnostic info map off a `:fail` Result."
  [r]
  (get r result-info-kw))

;; ---- step orchestrator (pure shape, runtime-callable) -------------------

(defn step-sim
  "Fold one engine call into a sim-state update. `runtime-fn` is a
  unary fn `(fn [event] result)` that closes over `(rf/machine-transition
  definition snapshot ...)`; the helper is shaped this way so the JVM
  test target can substitute a stub Result without booting the machines
  artefact (production CLJS passes the real fn).

  Returns the next `sim-state` — either with an advanced snapshot +
  trail entry, or with `:last-error` populated.

  Per the UC1 design (§4 Guards) failed-guard transitions surface
  inline; sim does NOT mutate the snapshot on fail."
  [sim-state event runtime-fn]
  (let [{:keys [snapshot]} sim-state
        prior-state        (:state snapshot)
        result             (runtime-fn event)]
    (cond
      (result-fail? result)
      (record-error sim-state event (result-info result) "transition failed")

      (result-ok? result)
      (let [new-snap (result-snap result)]
        (-> sim-state
            clear-error
            (assoc :snapshot new-snap)
            (append-audit-row
              {:from   prior-state
               :to     (:state new-snap)
               :event  event
               :data   (:data new-snap)
               :fx     (result-fx result)})))

      :else
      (record-error sim-state event nil "engine returned a non-Result value"))))

;; ---- display helpers ----------------------------------------------------

(defn format-state-display
  "Pretty-print a sim-snapshot `:state` for inline display. Keywords
  keep their `:`; vectors join with `.` for hierarchical clarity."
  [state]
  (cond
    (nil? state)     "(none)"
    (keyword? state) (str state)
    (vector? state)  (str "[" (str/join " " (map str state)) "]")
    :else            (str state)))

(defn format-event-display
  "Compact event-vector formatter for the audit trail."
  [event]
  (if (nil? event)
    ""
    (try
      (pr-str event)
      (catch #?(:clj Throwable :cljs :default) _
        (str event)))))
