(ns re-frame.machines.cofx-attach
  "Machine consumer-attachment for recordable coeffects. Per Spec 005
  §Consumer attachment — declaring requirements on named entries + EP-0017
  slice-B.9 (rf2-mjmxgb).

  ## What this namespace does

  A machine guard / action / entry / exit callback that consumes a host
  fact (the wall-clock time, a generated UUID, a random draw) declares its
  requirement with **`:rf.cofx/requires`** on the machine's **named** entry
  — the `{:rf.cofx/requires [...] :fn (fn [...] ...)}` map form in
  `:guards` / `:actions`. This namespace owns the three slice-B.9 pieces:

    1. **Inline-fn restriction (registration-time hard error).**
       `:rf.cofx/requires` may live ONLY on a named `:guards` / `:actions`
       ENTRY MAP (the form that also carries `:fn`). It may NOT ride an
       inline callback — a bare-fn guard/action, or `:rf.cofx/requires`
       placed directly on a transition slot (`:on` / `:always` / `:after`
       entry) or on `:entry` / `:exit`. The declaration must sit with the
       code that can be checked against it; an inline fn has no entry to
       attach a checkable diet to. Violations are
       `:rf.error/machine-cofx-requires-inline` (registration is rejected).

    2. **Derived ensure-sets (registration-time index + dispatch-time
       ensure).** At registration `index-ensure-sets` walks the machine and
       parses each named entry's `:rf.cofx/requires` (via
       `re-frame.cofx/parse-requires`), building a per-named-entry parsed
       diet plus the per-state `:always`-closure diet. At dispatch
       `ensure-set-for` computes the union of parsed requires across every
       guard/action any candidate transition for the inner event type can
       touch on the active state path (leaf→root), INCLUDING the `:always`
       cascade reachable from candidate targets and from the current state.
       The handler ensures that set onto the in-flight `:rf.cofx` record
       **before transition selection** runs (`re-frame.cofx/deliver-declared
       -cofx`), so a guard-consumed fact is never generated per-fired-
       transition — a mid-selection host read would put nondeterminism in
       the fold's most sensitive spot (replay selecting a DIFFERENT
       transition through the statechart). Generated values are written back
       into the record the epoch captures, so replay re-presents them.

    3. **Lint (dev-only diagnostic).** `:rf.warning/machine-cofx-consume-
       undeclared` — a named guard/action whose `:fn` destructures a cofx
       leaf key the entry did not declare in `:rf.cofx/requires` (consuming
       without declaring); `:rf.warning/machine-cofx-ambient-durable` — a
       named entry declaring an AMBIENT-grade id (`durable state folds facts,
       never reads`, mechanically checkable for an action that may write
       `:data`). Both are recommendations (Spec 005 §Consumer attachment /
       EP-0017 §9), emitted at registration and DCE'd in production.

  ## Why a separate namespace

  The derivation + ensure machinery + lint are a cohesive slice-B.9 unit
  with their own require on `re-frame.cofx` (the core delivery surface).
  Keeping them out of `lifecycle-fx.validation` / `lifecycle-fx.registration`
  keeps those files focused and lets the engine require this leaf without a
  cycle (it requires only `grammar`, `path-walk`, `cofx`, `trace`, `interop`).

  XState offers NO parity guidance here — it has no replay contract; this
  surface is re-frame2's own (EP-0017 §Rationale — Why consumer attachment
  for machines)."
  (:require [re-frame.cofx :as cofx]
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.machines.grammar :as grammar]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved spec keys (registration-derived index) ----------------------
;;
;; The registration-time index lives on the machine spec under a reserved
;; `:rf/*` key so it threads through the handler closure with the rest of the
;; machine def (`:rf/frame` / `:rf/platform` / `:rf/parent-id` / `:rf/cofx`)
;; and reverts atomically with re-registration. Per Conventions §The single-
;; root reserved set the key is owner-qualified under `:rf/*`.

(def ^:private ensure-index-key
  "Reserved machine-spec key carrying the registration-derived
  consumer-attachment index — `{:by-entry {[:guards id] [parsed...] ...}
  :always-by-state {<decl-path> [parsed...] ...}}`. Stamped by
  `index-ensure-sets`; read by `ensure-set-for`."
  :rf/cofx-ensure-index)

;; ---- entry-map shape helpers ----------------------------------------------
;;
;; A `:guards` / `:actions` registry VALUE is one of three shapes (mirroring
;; `transition/chase-ref`): a bare fn, a co-located ENTRY MAP carrying `:fn`
;; (the named form the `reg-machine` macro produces, plus optional `:source-*`
;; / `:rf.cofx/requires` slots), or a keyword indirection. `:rf.cofx/requires`
;; can ride ONLY the entry-map form.

(def ^:private requires-key :rf.cofx/requires)

(defn- entry-map?
  "True iff `v` is a co-located named-entry map (`{:fn <fn> ...}`)."
  [v]
  (and (map? v) (fn? (:fn v))))

(defn- entry-requires
  "The raw (unparsed) `:rf.cofx/requires` vector declared on a `:guards` /
  `:actions` entry `v`, or nil. Only an entry MAP can carry it; a bare fn or
  a keyword indirection never does."
  [v]
  (when (map? v) (requires-key v)))

;; ---- inline-fn restriction (registration-time hard error) -----------------

(defn- requires-inline-error
  "Throw `:rf.error/machine-cofx-requires-inline` — `:rf.cofx/requires` was
  found somewhere other than a named `:guards` / `:actions` entry MAP (an
  inline callback or a bare slot). Registration is rejected. Per Spec 005
  §Consumer attachment §Inline callbacks cannot declare requirements +
  Spec 009 §Error catalogue."
  [where detail]
  (error/throw-error!
    :rf.error/machine-cofx-requires-inline
    'rf/reg-machine
    (str ":rf.cofx/requires may be declared ONLY on a NAMED "
         ":guards / :actions entry map (the "
         "`{:rf.cofx/requires [...] :fn (fn [...] ...)}` form) "
         "— never on an inline callback. A fact-consuming "
         "guard / action must be a named entry so the declared "
         "diet sits with the code that can be checked against "
         "it (Spec 005 §Consumer attachment). Move the inline "
         "fn into the machine's :guards / :actions map and "
         "declare :rf.cofx/requires on its entry. Found at: "
         where ".")
    {:recovery :no-recovery
     :extra    detail}))

(defn- check-no-inline-requires!
  "Reject `:rf.cofx/requires` anywhere it cannot be honoured. `v` is a
  transition-slot value or a callback value. The ONLY legal home is a named
  `:guards` / `:actions` entry map (validated separately by
  `check-named-entry!`). Here we catch the inline cases:

    - a MAP value (an inline transition map / inline callback map) carrying
      `:rf.cofx/requires` directly — e.g. `{:on {:go {:rf.cofx/requires [...]
      :target :done}}}` or an inline `:guards` entry that is a map but is NOT
      the `{:fn ...}` named form;
    - a vector of candidate maps any of which carries it.

  A bare-fn / keyword value never carries the key (it is not a map), so it
  passes here — the restriction it represents (a bare fn cannot declare) is
  intrinsic. `where` names the declaring site for the diagnostic."
  [where v]
  (cond
    (and (map? v) (contains? v requires-key) (not (entry-map? v)))
    (requires-inline-error where {:offending v})

    (vector? v)
    (doseq [el v]
      (when (and (map? el) (contains? el requires-key))
        (requires-inline-error where {:offending el})))))

;; ---- named-entry shape + parse (registration-time) ------------------------

(defn- check-named-entry!
  "Validate a named `:guards` / `:actions` entry that carries
  `:rf.cofx/requires`, and return its PARSED diet (the `parse-requires`
  entry vector). A non-entry-map value carrying the key is the inline error
  (a `{:rf.cofx/requires ...}` with no `:fn` — there is no callback to
  deliver to). A well-formed entry's requires is parsed via the SAME
  `re-frame.cofx/parse-requires` the event-handler path uses, so a malformed
  declaration raises `:rf.error/cofx-request-invalid` / a duplicate id
  `:rf.error/cofx-name-collision` IDENTICALLY to a `reg-event` handler.
  `failing-id` is the `[machine-id-placeholder slot id]`-style label for the
  error payload. Returns `[]` for an entry with no requires."
  [slot entry-id v]
  (let [raw (entry-requires v)]
    (cond
      (nil? raw) []
      ;; `:rf.cofx/requires` present but the value is not the named entry-map
      ;; form (no `:fn`) — an inline declaration with nothing to deliver to.
      (not (entry-map? v))
      (requires-inline-error (str slot " " entry-id) {:offending v})
      :else
      (cofx/parse-requires [slot entry-id] raw))))

;; ---- machine walkers (mirror validation's coverage) -----------------------
;;
;; `:guards` / `:actions` are the ONLY named-entry homes; the inline-fn
;; restriction additionally sweeps every transition slot + `:entry` / `:exit`
;; so an inline `:rf.cofx/requires` cannot hide on a slot value.

(defn- always-entries
  "Normalise a state-node's `:always` slot to a vector of entry maps (a
  single map or a vector of maps; absent → `[]`). Mirrors
  `validation/always-entries`."
  [node]
  (let [a (:always node)]
    (cond (nil? a) [] (vector? a) a :else [a])))

(defn- walk-nodes-with-path
  "Yield `[absolute-path node]` pairs for every state node, recursing
  through `:states`; for a parallel machine, per region (region names are
  not part of the path). Mirrors `validation/walk-state-nodes-with-path`."
  [machine]
  (letfn [(walk [path nodes]
            (mapcat (fn [[k n]]
                      (let [p (conj path k)]
                        (cons [p n]
                              (when (:states n) (walk p (:states n))))))
                    nodes))]
    (if (and (map? (:regions machine)) (seq (:regions machine)))
      (mapcat (fn [[_r body]] (walk [] (:states body))) (:regions machine))
      (walk [] (:states machine)))))

(defn- check-inline-restriction!
  "Sweep every transition slot + callback slot of every state node (and the
  machine / region roots) for an inline `:rf.cofx/requires` declaration, and
  reject it. The legal home — a named `:guards` / `:actions` entry map — is
  parsed by `index-ensure-sets`; this pass catches the ILLEGAL homes."
  [machine]
  (let [roots (if (and (map? (:regions machine)) (seq (:regions machine)))
                (cons machine (vals (:regions machine)))
                [machine])
        check-node!
        (fn [path node]
          (let [where (str "state " (if (seq path) (peek path) :rf/root))]
            (doseq [[_ev v] (:on node)]    (check-no-inline-requires! (str where " :on") v))
            (doseq [[_d v]  (:after node)] (check-no-inline-requires! (str where " :after") v))
            (doseq [e (always-entries node)] (check-no-inline-requires! (str where " :always") e))
            (when (contains? node :on-done)
              (check-no-inline-requires! (str where " :on-done") (:on-done node)))
            (check-no-inline-requires! (str where " :entry") (:entry node))
            (check-no-inline-requires! (str where " :exit")  (:exit node))))]
    (doseq [[path node] (walk-nodes-with-path machine)]
      (check-node! path node))
    (doseq [root roots]
      (doseq [[_ev v] (:on root)]    (check-no-inline-requires! ":rf/root :on" v))
      (doseq [[_d v]  (:after root)] (check-no-inline-requires! ":rf/root :after" v))
      (when (contains? root :on-done)
        (check-no-inline-requires! ":rf/root :on-done" (:on-done root))))))

;; ---- registration-time index (parsed diets) -------------------------------

(defn- index-named-entries
  "Parse every named `:guards` / `:actions` entry's `:rf.cofx/requires`,
  returning `{[slot entry-id] [parsed...] ...}` (only entries that declared
  a non-empty diet appear). Each entry is parsed + shape-checked via
  `check-named-entry!`."
  [machine]
  (into {}
        (for [slot  [:guards :actions]
              [eid v] (get machine slot)
              :let  [parsed (check-named-entry! slot eid v)]
              :when (seq parsed)]
          [[slot eid] parsed])))

(defn- entry-diet
  "The parsed diet for a `:guards` / `:actions` REF as it appears on a
  transition (`:guard` / `:action` / `:entry` / `:exit`). A keyword ref maps
  to its named entry's parsed diet; an inline fn has the empty diet (it
  cannot declare — the restriction guarantees it). `by-entry` is the
  `index-named-entries` map."
  [by-entry slot ref]
  (if (keyword? ref)
    (get by-entry [slot ref] [])
    []))

(defn- candidate-maps
  "Normalise a transition-slot value to its candidate maps (each carrying
  `:guard` / `:action` / `:target`). Mirrors `transition/normalise-
  candidates` shape-wise via the shared `grammar/transition-value-form`, but
  does NOT throw — a malformed shape (caught by `validation`) contributes no
  candidates here."
  [v]
  (case (grammar/transition-value-form v)
    :nil              [{}]
    :keyword          [{:target v}]
    :candidate-vector v
    :vec-target       [{:target v}]
    :map              [v]
    :other            []))

(defn- node-at
  "Root→leaf descent into `machine`'s `:states` (or a region body's).
  Thin wrapper over `grammar/node-at` so the closure walk resolves targets
  against EXACTLY the tree the runtime drives."
  [states path]
  (grammar/node-at states (vec path)))

(defn- resolve-target-path
  "Resolve a candidate `:target` (declared at `decl-path`) to an absolute
  path within `states`, or nil. A keyword is a sibling (direct child of the
  declaring state's PARENT compound); a vector is absolute; `:same-state` is
  the declaring state; nil / internal contributes the declaring state itself
  (the `:always` on the unchanged state still runs). Mirrors the runtime
  target resolution closely enough for the static closure."
  [states decl-path target]
  (cond
    (or (nil? target) (= :same-state target)) (vec decl-path)
    (keyword? target) (conj (vec (drop-last decl-path)) target)
    (vector? target)  (vec target)
    :else             nil))

(defn- initial-descent-path
  "Descend `path` (an absolute path within `states`) through the `:initial`
  chain to its leaf — the analog of `transition/initial-cascade` against a
  `states` scope (NOT a machine). When `path` lands on a compound node with an
  `:initial`, the cascade enters that child, and so on, exactly as the runtime
  enters a compound target. `visited` guards a malformed `:initial` cycle.
  Returns the full descent path (the deepest entered leaf)."
  [states path]
  (loop [p       (vec path)
         visited #{}]
    (let [n (node-at states p)]
      (if (and (map? n) (:initial n) (:states n)
               (not (contains? visited p)))
        (recur (conj p (:initial n)) (conj visited p))
        p))))

(defn- lifecycle-diet-along-path
  "Accumulate (into the transient `diet`) the named-`:actions` diets of the
  `slot` (`:entry` or `:exit`) callback on every node along absolute `path`
  within `states` (each prefix leaf→root order is irrelevant — the ensure-set
  is a deduped union). A node's `:entry` / `:exit` value is a callback ref:
  a keyword names an `:actions` entry (whose `:rf.cofx/requires` is the only
  legal way for a lifecycle action to consume a recordable fact — inline
  requires on `:entry` / `:exit` are rejected at registration), so its parsed
  diet rides via `entry-diet by-entry :actions`. A bare-fn / nil ref has the
  empty diet. `by-entry` is the `index-named-entries` map."
  [by-entry states path slot diet]
  (doseq [i (range (count path))]
    (let [prefix (subvec (vec path) 0 (inc i))
          node   (node-at states prefix)]
      (doseq [d (entry-diet by-entry :actions (get node slot))]
        (conj! diet d)))))

(defn- always-step-for-state
  "ONE `:always` settle step for the active state at `path` within `states`:
  across every `:always` entry's `:guard` / `:action` on every node along the
  path (leaf→root), accumulate (1) the parsed diets into the transient `diet`,
  and (2) every `:always` target that hop COULD settle onto, returned as a
  vector of absolute paths. A target is resolved from the node the `:always`
  was DECLARED on (`prefix`), since a bare-keyword `:target` is a sibling of
  the declaring state. `by-entry` is the `index-named-entries` map. The
  per-hop helper `always-diet-for-state` drives to a fixed point."
  [by-entry states path diet]
  (let [targets (transient [])]
    (doseq [i (range (count path))]
      (let [prefix (subvec (vec path) 0 (inc i))
            node   (node-at states prefix)]
        (doseq [e (always-entries node)]
          (doseq [d (entry-diet by-entry :guards  (:guard e))]  (conj! diet d))
          (doseq [d (entry-diet by-entry :actions (:action e))] (conj! diet d))
          (when-let [tgt (resolve-target-path states prefix (:target e))]
            (conj! targets tgt)))))
    (persistent! targets)))

(defn- always-diet-for-state
  "The `:always` closure diet for the active state at `path` within `states`:
  the concatenation of parsed diets across every `:always` entry's `:guard` /
  `:action` reachable as the `:always` cascade settles to a FIXED POINT from
  this configuration. An `:always` declared at any active ancestor can fire
  after the macrostep settles, and — critically — an `:always` whose guard
  passes settles to its `:target`, whose OWN `:always` may then fire, and so
  on (`transition/drain-to-fixed-point` runs every `:always` hop within ONE
  macrostep). The static ensure-set must therefore chase every `:always`
  `:target` to the same fixed point the runtime settles, so a guard/action
  reached at chain depth >=2 (A --go--> B, B :always--> C, C :always {:guard
  g-requiring-cofx}) still has its declared `:rf.cofx/requires` ensured BEFORE
  the macrostep runs (the ensure step runs ONCE, `registration/ensure-ctx-
  cofx`). `by-entry` is the `index-named-entries` map; `visited` tracks
  resolved state-paths so an `:always` cycle terminates. Returns a vector of
  `parse-requires` entries (deduped by the caller's `add!`)."
  [by-entry states path]
  (let [diet (transient [])]
    (loop [worklist [(vec path)]
           visited  #{}]
      (if-let [p (peek worklist)]
        (let [rest* (pop worklist)]
          (if (contains? visited p)
            (recur rest* visited)
            (let [targets (always-step-for-state by-entry states p diet)
                  next*   (reduce (fn [wl t]
                                    (if (contains? visited t) wl (conj wl t)))
                                  rest* targets)]
              (recur next* (conj visited p)))))
        (persistent! diet)))))

(defn- scope-for
  "Return the `:states` scope a snapshot state resolves within. For a flat /
  compound machine it is `(:states machine)`. For a parallel machine the
  state is a region-name → region-state map; the closure is unioned across
  every region's body (each region's path resolves within its own `:states`)
  — see `ensure-set-for`."
  [machine]
  (:states machine))

;; ---- registration-time entry point ----------------------------------------

(defn index-ensure-sets
  "Validate the inline-fn restriction and DERIVE the consumer-attachment
  index, returning `machine` with the index stamped under
  `:rf/cofx-ensure-index`. Per Spec 005 §Consumer attachment + EP-0017
  slice-B.9.

  Two registration-time effects:
    1. `check-inline-restriction!` — reject any `:rf.cofx/requires` on an
       inline callback / bare slot (`:rf.error/machine-cofx-requires-inline`);
    2. parse every named `:guards` / `:actions` entry's `:rf.cofx/requires`
       (`:rf.error/cofx-request-invalid` / `-name-collision` on a malformed
       declaration, IDENTICAL to the event-handler path) into a per-entry
       parsed-diet index the dispatch-time `ensure-set-for` reads.

  Idempotent and pure (machine → machine'); called from
  `make-machine-handler` after `validate-machine!`. A machine with no
  `:rf.cofx/requires` anywhere stamps an empty index (cheap)."
  [machine]
  (check-inline-restriction! machine)
  (let [by-entry (index-named-entries machine)]
    (assoc machine ensure-index-key {:by-entry by-entry})))

;; ---- dispatch-time ensure-set ---------------------------------------------

;; The synthetic compound/parallel done-completion event id
;; (`transition/done-event-id`). Inlined as a literal for the same cycle-free
;; reason as `after-elapsed-event-id` below. The done signal is
;; `[:rf.machine/done <completed-node-path>]`; the completed node's `:on-done`
;; transition (a SEPARATE slot from `:on`) is the candidate the runtime selects
;; (`transition/pick-done-transition`), so the ensure-set for a raised done
;; event MUST add that `:on-done` guard/action diet (rf2-xsdn5h — the enclosing
;; `:on {:rf.machine/done …}` escape hatch IS already covered by the `:on` walk
;; since the event-id matches an `:on` key, but the `:on-done` slot is not).
(def ^:private done-event-id :rf.machine/done)

(defn- on-done-diet-for-event
  "Add (into `add!`) the `:on-done` guard/action diets of the completed node a
  raised done event targets (rf2-xsdn5h). `event` is `[:rf.machine/done
  <node-path>]`; `<node-path>` (the 2nd element) is the absolute path of the
  compound (or parallel-region) node whose `:final?` child completed. The node's
  `:on-done` value is one-or-more candidate maps (mirroring
  `transition/pick-done-transition` step 1); each candidate's `:guard` /
  `:action` named-entry diet — and the `:always` closure reachable from its
  resolved `:target` (via `add-always!`) — joins the ensure-set, so an
  `:on-done` guard/action declaring `:rf.cofx/requires` has its facts ensured
  before the done transition is selected. No-op when the event is not a done
  signal, the node-path does not resolve, or the node declares no `:on-done`."
  [by-entry states event add! add-always!]
  (when (and (vector? event) (= done-event-id (first event)))
    (let [done-path (second event)]
      (when (vector? done-path)
        (let [node (node-at states done-path)]
          (when (and (map? node) (contains? node :on-done))
            (doseq [cand (candidate-maps (:on-done node))]
              (add! (entry-diet by-entry :guards  (:guard cand)))
              (add! (entry-diet by-entry :actions (:action cand)))
              (when-let [tgt (resolve-target-path states done-path (:target cand))]
                (add-always! tgt)))))))))

(defn- ensure-set-in-scope
  "Compute the ensure-set (a set of `parse-requires` entries, deduped by
  `:id`) for a single `states` scope, active `path`, and inner event type
  `event-id`. The union of parsed diets across:

    (a) every candidate transition for `event-id` on the active path
        (leaf→root), the candidate's `:guard` + `:action` diets; PLUS
    (b) the `:always` closure reachable from each candidate's target AND
        from the current active state (the `:always` cascade that runs after
        the transition settles — or after no transition fires).

  When `event` is a raised compound/parallel done signal
  (`[:rf.machine/done <node-path>]`), the completed node's `:on-done` guard/
  action diets join the set too (rf2-xsdn5h) — the `:on` walk covers the
  enclosing `:on {:rf.machine/done …}` escape hatch, but the `:on-done` slot is
  a separate candidate the runtime selects.

  `by-entry` is the registration index's `:by-entry` map. Returns a vector of
  parsed-requires entries (deduped by id, declaration-order-insensitive — the
  ensure step is order-independent)."
  [by-entry states path event]
  (let [event-id (when (vector? event) (first event))
        acc      (volatile! [])
        seen-ids (volatile! #{})
        add!     (fn [diet]
                   (doseq [{:keys [id] :as e} diet]
                     (when-not (contains? @seen-ids id)
                       (vswap! seen-ids conj id)
                       (vswap! acc conj e))))
        add-always! (fn [tgt] (add! (always-diet-for-state by-entry states tgt)))
        ;; rf2-knxbok — accumulate a lifecycle (`:entry`/`:exit`) slot's named-
        ;; action diet along a path into a transient, then add! it deduped.
        add-lifecycle! (fn [scope p slot]
                         (let [diet (transient [])]
                           (lifecycle-diet-along-path by-entry scope p slot diet)
                           (add! (persistent! diet))))]
    ;; (a) candidate transitions for this event type, leaf→root. We do NOT
    ;; stop at the first match — the ensure-set is STATIC (every candidate
    ;; the selection COULD touch must have its guard-consumed facts ensured
    ;; before selection runs, since a guard reads them to decide).
    (doseq [i (range (count path))]
      (let [prefix (subvec (vec path) 0 (inc i))
            node   (node-at states prefix)
            on     (:on node)
            ;; exact + :ns/* + :* descriptor tiers can all match this event.
            keys*  (cond-> [event-id]
                     (and (keyword? event-id) (namespace event-id))
                     (conj (keyword (namespace event-id) "*"))
                     :always (conj :*))]
        (doseq [k     keys*
                :when (contains? on k)
                cand  (candidate-maps (get on k))]
          (add! (entry-diet by-entry :guards  (:guard cand)))
          (add! (entry-diet by-entry :actions (:action cand)))
          ;; rf2-knxbok — (c) the exit→action→entry cascade a fired candidate
          ;; runs also executes the active-state `:exit` actions and the
          ;; target-state `:entry` actions (a named `:actions` ref carrying
          ;; `:rf.cofx/requires` is the only legal lifecycle consumer). Add the
          ;; TARGET state's `:entry` diet — and the `:entry` diet of every node
          ;; entered descending the target's `:initial` chain, since entering a
          ;; compound target cascades into its initial leaf. The active-state
          ;; `:exit` diet is added once below (it is candidate-independent).
          (when-let [tgt (resolve-target-path states prefix (:target cand))]
            (add-lifecycle! states (initial-descent-path states tgt) :entry)
            ;; (b) :always closure reachable from this candidate's target.
            (add! (always-diet-for-state by-entry states tgt))))))
    ;; rf2-knxbok — (c, exit) any fired candidate exits some suffix of the
    ;; active state path; over-approximate soundly by ensuring the `:exit` diet
    ;; of every active node leaf→root (an un-fired exit is a harmless no-op —
    ;; generated facts are written back idempotently). Candidate-independent, so
    ;; added once.
    (add-lifecycle! states path :exit)
    ;; (b-current) the :always closure from the current active state — an
    ;; :always can fire on the unchanged configuration (an internal action
    ;; microstep, or after a guard-blocked / unhandled event) reachable
    ;; without any :on transition firing.
    (add! (always-diet-for-state by-entry states path))
    ;; rf2-xsdn5h — (d) a raised compound/parallel done signal
    ;; (`[:rf.machine/done <node-path>]`) selects the completed node's `:on-done`
    ;; transition (a separate slot from `:on`); add its guard/action diet + the
    ;; `:always` closure reachable from its target so an `:on-done` callback's
    ;; `:rf.cofx/requires` is ensured before the done transition is selected.
    (on-done-diet-for-event by-entry states event add! add-always!)
    @acc))

;; The synthetic timer event id (`transition/after-elapsed-event-id`). Inlined
;; as a literal so this leaf does not require `transition` — the docstring's
;; require contract (`grammar` / `path-walk` / `cofx` / `trace` / `interop`)
;; stays cycle-free. `root-after-match` in `transition` is the runtime peer.
(def ^:private after-elapsed-event-id :rf.machine.timer/after-elapsed)

(defn- root-target-always-diet
  "The `:always`-closure diet reachable from a PARALLEL-ROOT transition's
  region-qualified `:target`, added into `add!`. A root `:on` / `:after`
  `:target` is region-qualified (`[<region> & <in-region-path>]`, or a vector
  OF such targets `[[<region> …] [<region> …]]`), or targetless (action-only).
  Each region-qualified head resolves into THAT region's own `:states` body,
  so the moved region's settled `:always` cascade (Spec 005 §Root parallel
  `:on` — \"a moved region then settles its own `:always`\") contributes its
  guard/action requires to the ensure-set exactly as `ensure-set-in-scope`'s
  (b) clause does for a flat target. A non-region-qualified / malformed target
  (rejected at registration) contributes nothing here."
  [by-entry machine target add!]
  (let [add-region-target!
        (fn [t]
          (when (and (vector? t) (seq t) (keyword? (first t)))
            (let [region (first t)
                  rpath  (vec (rest t))
                  scope  (get-in machine [:regions region :states])]
              (when (and scope (seq rpath))
                (add! (always-diet-for-state by-entry scope rpath))))))]
    (cond
      ;; multiple region-qualified targets — a vector of target-vectors.
      (and (vector? target) (seq target) (vector? (first target)))
      (doseq [t target] (add-region-target! t))
      ;; a single region-qualified target.
      (vector? target)
      (add-region-target! target)
      :else nil)))

(defn- parallel-root-diet
  "Compute the PARALLEL-ROOT's own `:on` / `:after` ensure-set contribution
  for `event`, added into `add!`. The parallel branch of `ensure-set-for`
  unions each region's scope; this adds the root's OWN transition surfaces,
  which the runtime evaluates separately (`transition/root-on-match` /
  `transition/root-after-match`) as the ancestor fallback (Spec 005 §Root
  parallel `:on` / §Root-level `:after`). Without this a coeffect declared by
  a root `:on` / root `:after` guard/action — live transition surfaces — is
  never ensured before selection (rf2-bu106a).

  Two surfaces, mirroring the two runtime root resolvers:

    (a) root `:on` — for a user event, the root's own `:on` is the ancestor
        fallback (consulted when no region handles the event). Like
        `ensure-set-in-scope`'s (a) clause, the ensure-set is STATIC: every
        candidate the selection COULD touch (across the exact / `:ns/*` / `:*`
        descriptor tiers) has its guard + action diets ensured, plus the
        `:always` closure reachable from each candidate's region-qualified
        target.

    (b) root `:after` — for the synthetic root timer event
        (`[:rf.machine.timer/after-elapsed delay-key epoch []]`, carried
        decl-path `[]`), the root's `:after` entry at `delay-key` contributes
        its guard + action diets plus the `:always` closure from its target.

  Mirrors the runtime root resolvers' candidate grammar via `candidate-maps`
  (the shared shape-wise normalisation). `by-entry` is the registration
  index's `:by-entry` map."
  [by-entry machine event event-id add!]
  (if (= after-elapsed-event-id event-id)
    ;; (b) root `:after` — only a `[]`-carried root timer is the root's own
    ;; (a region-prefixed decl-path routes to its region, not the root).
    (let [[_ delay-key _epoch raw-decl-path] event]
      (when (= [] (vec raw-decl-path))
        (doseq [cand (candidate-maps (get-in machine [:after delay-key]))]
          (add! (entry-diet by-entry :guards  (:guard cand)))
          (add! (entry-diet by-entry :actions (:action cand)))
          (root-target-always-diet by-entry machine (:target cand) add!))))
    ;; (a) root `:on` — three descriptor tiers (exact + :ns/* + :*), every
    ;; candidate, mirroring `match-on-clause`'s tier walk on the root node.
    (let [on    (:on machine)
          keys* (cond-> [event-id]
                  (and (keyword? event-id) (namespace event-id))
                  (conj (keyword (namespace event-id) "*"))
                  :always (conj :*))]
      (doseq [k     keys*
              :when (contains? on k)
              cand  (candidate-maps (get on k))]
        (add! (entry-diet by-entry :guards  (:guard cand)))
        (add! (entry-diet by-entry :actions (:action cand)))
        (root-target-always-diet by-entry machine (:target cand) add!)))))

(defn ensure-set-for
  "Compute the consumer-attachment ENSURE-SET for `machine` (carrying the
  registration index) given the active `snapshot` and the inner `event`.
  Returns a `parse-requires`-shaped entry vector — the static union of every
  `:rf.cofx/requires` any candidate guard/action for this event type could
  touch on the active state path, INCLUDING the `:always` cascade reachable
  from candidate targets (Spec 005 §Consumer attachment §the derived
  per-(state × event-type) ensure-set). Empty when the machine declares no
  requires (the no-op fast path) or the index is absent (pure-fn callers).

  For a PARALLEL machine the snapshot's `:state` is a region-name → region-
  state map; the ensure-set unions each region's own scope (each region's
  path resolves within its own `:states` body), since the broadcast routes
  the event to every region, PLUS the parallel ROOT's own `:on` / `:after`
  candidates (the ancestor fallback the runtime evaluates separately via
  `transition/root-on-match` / `root-after-match` — rf2-bu106a). The set is
  deduped by id across regions and the root."
  [machine snapshot event]
  (let [{:keys [by-entry]} (get machine ensure-index-key)
        event-id (when (vector? event) (first event))]
    (if (or (empty? by-entry) (nil? event-id))
      []
      (let [state (:state snapshot)]
        (if (and (map? state) (map? (:regions machine)) (seq (:regions machine)))
          ;; parallel — union each region's scope PLUS the parallel ROOT's own
          ;; `:on` / `:after` (the ancestor fallback the runtime evaluates
          ;; separately — `root-on-match` / `root-after-match`), deduped by id
          ;; (rf2-bu106a).
          (let [acc      (volatile! [])
                seen-ids (volatile! #{})
                add!     (fn [diet]
                           (doseq [{:keys [id] :as e} diet]
                             (when-not (contains? @seen-ids id)
                               (vswap! seen-ids conj id)
                               (vswap! acc conj e))))]
            (doseq [[region region-state] state
                    :let [body  (get-in machine [:regions region])
                          scope (:states body)
                          rpath (cond (vector? region-state) region-state
                                      (keyword? region-state) [region-state]
                                      :else nil)]
                    :when (and scope rpath)]
              (add! (ensure-set-in-scope by-entry scope rpath event)))
            ;; the parallel root's own live transition surfaces.
            (parallel-root-diet by-entry machine event event-id add!)
            @acc)
          ;; flat / compound — single scope.
          (let [path (cond (vector? state) state
                           (keyword? state) [state]
                           :else nil)]
            (if path
              (ensure-set-in-scope by-entry (scope-for machine) path event)
              [])))))))

(defn- initial-path-for-scope
  "The initial-descent leaf path for a `states` scope given an `:initial`
  declaration (a keyword or a vector path). Mirrors `build-initial-snapshot`'s
  flat/region initial cascade against the static scope. Returns nil for a
  missing / malformed `:initial`."
  [states initial-decl]
  (let [decl-path (cond
                    (keyword? initial-decl) [initial-decl]
                    (vector? initial-decl)  (vec initial-decl)
                    :else                   nil)]
    (when (seq decl-path)
      (initial-descent-path states decl-path))))

(defn bootstrap-ensure-set-for
  "Compute the consumer-attachment ENSURE-SET for `machine`'s BIRTH (initial-
  entry) macrostep (rf2-knxbok). The bootstrap cascade
  (`parallel/apply-initial-entry-cascade`, run inside `maybe-boot` BEFORE the
  dispatch-time `ensure-ctx-cofx`) fires the initial-state descent's `:entry`
  actions, then the birth-time `:always` settle. A named `:actions` ref on an
  `:entry` slot (or reached by a birth `:always`) carrying `:rf.cofx/requires`
  must therefore have its facts ensured BEFORE `maybe-boot` runs — otherwise it
  reads an unensured token (the bootstrap hole this closes).

  Returns the deduped union of: the `:entry` diet of every node entered on the
  initial-descent path, PLUS the `:always` closure reachable from the initial
  leaf — for a flat / compound machine the single root scope, for a parallel
  machine each region's own scope. Empty when the machine declares no requires
  (the no-op fast path) or the index is absent (pure-fn callers)."
  [machine]
  (let [{:keys [by-entry]} (get machine ensure-index-key)]
    (if (empty? by-entry)
      []
      (let [acc      (volatile! [])
            seen-ids (volatile! #{})
            add!     (fn [diet]
                       (doseq [{:keys [id] :as e} diet]
                         (when-not (contains? @seen-ids id)
                           (vswap! seen-ids conj id)
                           (vswap! acc conj e))))
            add-scope! (fn [scope initial-decl]
                         (when-let [ipath (initial-path-for-scope scope initial-decl)]
                           (let [diet (transient [])]
                             (lifecycle-diet-along-path by-entry scope ipath :entry diet)
                             (add! (persistent! diet)))
                           ;; the birth `:always` settle from the initial leaf.
                           (add! (always-diet-for-state by-entry scope
                                                        (initial-path-for-scope scope initial-decl)))))]
        (if (and (map? (:regions machine)) (seq (:regions machine)))
          (doseq [[_region body] (:regions machine)]
            (add-scope! (:states body) (:initial body)))
          (add-scope! (scope-for machine) (:initial machine)))
        @acc))))

;; ---- the dispatch-time ensure step ----------------------------------------

(defn- ensure-diet-onto
  "Ensure a derived `ensure-set` (a `parse-requires`-shaped entry vector) onto
  the in-flight `:rf.cofx` record `recorded`. Returns the (possibly generation-
  augmented) record — equal to `recorded` when the set is empty or nothing was
  generated. `deliver-declared-cofx` reads recordable values FROM the record
  and writes generated ones back into it; we thread an empty coeffects map (the
  delivered flat spread is unused by machines — callbacks read the WHOLE record
  off `:rf.cofx`) and keep only the augmented record. The shared core of both
  the dispatch-time (`ensure-cofx`) and birth-time (`bootstrap-ensure-cofx`)
  ensure steps.

  `mint-policy` (EP-0017 §6, rf2-n0myjq) gates the declared-absent generator-
  backed branch — `:strict` (replay / the `:test` preset) refuses to mint and
  surfaces `:rf.error/missing-required-cofx`, `:live` / `:explicit-live`
  generate. The 6-arity of `deliver-declared-cofx` is the policy-aware surface
  the EVENT path uses; threading the resolved policy here gives the machine
  ensure path the SAME mint semantics rather than the 5-arity `:live` default."
  [ensure-set recorded frame-id failing-id mint-policy]
  (if (empty? ensure-set)
    recorded
    (:rf.cofx
     (cofx/deliver-declared-cofx {} ensure-set (or recorded {}) failing-id
                                 frame-id mint-policy))))

(defn ensure-cofx
  "Ensure the consumer-attachment ensure-set for `machine` / `snapshot` /
  `event` onto the in-flight `:rf.cofx` record `recorded` BEFORE transition
  selection runs (Spec 005 §Consumer attachment). Returns the (possibly
  generation-augmented) `:rf.cofx` record — equal to `recorded` when nothing
  was generated. Generated values are written back so the epoch captures the
  post-generation token and replay re-presents them; a provided fact absent
  from the record is `:rf.error/missing-required-cofx`; a strict mint policy
  generates nothing (an absent generator-backed fact is missing-required).

  `frame-id` tags the trace / error emits; `failing-id` is the machine-id.
  Delegates to `re-frame.cofx/deliver-declared-cofx` — the SAME satisfaction
  surface the event-handler path uses — but discards the delivered coeffects
  spread (machine callbacks read the WHOLE record off `:rf.cofx`, never a flat
  delivery): we keep only the augmented record.

  `mint-policy` (EP-0017 §6, rf2-n0myjq) is the EFFECTIVE policy the caller
  resolved (per-call dispatch opt ▸ frame config ▸ `:live`), threaded into
  `deliver-declared-cofx`'s 6-arity so the machine ensure path mints under the
  SAME semantics as the event path: replay / `:test` (`:strict`) refuse to mint
  a declared-absent generator-backed fact (surfacing missing-required), `:live`
  / `:explicit-live` generate. Omitting `mint-policy` falls back to the
  router's `:live` default for the (rare) caller that has no policy to thread
  (the pure-fn conformance/JVM fixtures, which carry no token, never reach the
  delivery anyway since the ensure-set fast-paths empty there).

  A no-op (returns `recorded`) when the ensure-set is empty."
  ([machine snapshot event recorded frame-id failing-id]
   (ensure-cofx machine snapshot event recorded frame-id failing-id
                cofx/default-mint-policy))
  ([machine snapshot event recorded frame-id failing-id mint-policy]
   (ensure-diet-onto (ensure-set-for machine snapshot event)
                     recorded frame-id failing-id mint-policy)))

(defn bootstrap-ensure-cofx
  "Ensure the BIRTH (initial-entry) ensure-set onto the in-flight `:rf.cofx`
  record `recorded` BEFORE `maybe-boot` runs the bootstrap cascade (rf2-knxbok).
  Same satisfaction surface / write-back / error semantics as `ensure-cofx`,
  but the ensure-set is `bootstrap-ensure-set-for` (the initial-descent `:entry`
  diet + the birth `:always` closure). `mint-policy` (rf2-n0myjq) is the
  effective resolved policy, threaded identically to `ensure-cofx`. A no-op
  (returns `recorded`) when the bootstrap ensure-set is empty."
  ([machine recorded frame-id failing-id]
   (bootstrap-ensure-cofx machine recorded frame-id failing-id
                          cofx/default-mint-policy))
  ([machine recorded frame-id failing-id mint-policy]
   (ensure-diet-onto (bootstrap-ensure-set-for machine)
                     recorded frame-id failing-id mint-policy)))

;; ---- lint (dev-only diagnostic) -------------------------------------------
;;
;; Per Spec 005 §Consumer attachment + EP-0017 §9: a recommended lint. Two
;; checks, both registration-time + dev-gated (DCE'd in production):
;;
;;   1. consume-without-declaring — a named guard/action whose `:fn` reads a
;;      cofx leaf off the `:rf.cofx` record (`{:keys [rf/time-ms]}` /
;;      `(:rf/time-ms cofx)`-style) that the entry did NOT declare in its
;;      `:rf.cofx/requires`. The framework cannot ENSURE an undeclared fact,
;;      so the read silently binds nil (live) or whatever a fixture happened
;;      to supply (test) — the silent-nil hole consumer attachment closes.
;;   2. ambient-durable — a named ACTION declaring an AMBIENT-grade id. An
;;      action may write durable `:data`; folding an ambient (re-run-on-
;;      replay) read into durable state is the "durable state folds facts,
;;      never reads" violation, mechanically checkable.
;;
;; The consume-without-declaring lint is necessarily SHALLOW (a source-form
;; heuristic over the `:source-code` the macro captured) — it is a
;; recommendation, not a hard gate; a false negative is acceptable (the
;; ensure-set still only ensures DECLARED facts, so an undeclared read is
;; simply un-ensured, never wrong). We only lint when the `:source-code` slot
;; is present (the macro path in dev); the plain-fn / production path skips.

(defn- emit-consume-undeclared!
  "Emit `:rf.warning/machine-cofx-consume-undeclared` (dev-only diagnostic)."
  [machine-id slot entry-id leaf]
  (trace/emit! :warning :rf.warning/machine-cofx-consume-undeclared
               {:machine-id machine-id
                :slot       slot
                :entry-id   entry-id
                :rf.cofx/id leaf
                :recovery   :warned-and-proceeded}))

(defn- emit-ambient-durable!
  "Emit `:rf.warning/machine-cofx-ambient-durable` (dev-only diagnostic)."
  [machine-id slot entry-id cofx-id]
  (trace/emit! :warning :rf.warning/machine-cofx-ambient-durable
               {:machine-id machine-id
                :slot       slot
                :entry-id   entry-id
                :rf.cofx/id cofx-id
                :recovery   :warned-and-proceeded}))

(defn lint-machine!
  "Run the dev-only consumer-attachment lints over `machine` (carrying the
  registration index), tagging diagnostics with `machine-id`. Per Spec 005
  §Consumer attachment + EP-0017 §9. A no-op under `interop/debug-enabled?
  false` (production) and when the machine declares no requires.

  Recommendation-grade: emits `:rf.warning/*` diagnostics, never throws.
  Called from the registration home AFTER the index is built (so it can read
  the parsed diets and the entry source-forms)."
  [machine-id machine]
  (when interop/debug-enabled?
    (let [{:keys [by-entry]} (get machine ensure-index-key)]
      (when (seq by-entry)
        (doseq [[[slot entry-id] parsed] by-entry]
          (let [declared    (set (map :id parsed))
                entry       (get-in machine [slot entry-id])
                source-code (:source-code entry)]
            ;; (2) ambient-durable — an ACTION declaring an ambient-grade id.
            (when (= slot :actions)
              (doseq [{:keys [id]} parsed]
                (let [meta (registrar/lookup :cofx id)]
                  (when (and meta (not (:recordable? meta)))
                    (emit-ambient-durable! machine-id slot entry-id id)))))
            ;; (1) consume-without-declaring — heuristic source scan. The
            ;; macro-captured `:source-code` is a quoted form; scan it for
            ;; `:rf.cofx`-record leaf reads not covered by `declared`. We
            ;; look for symbol/keyword tokens of the shape `<ns>/<name>`
            ;; destructured beside a `:rf.cofx`/`cofx` binding. Shallow by
            ;; design (a recommendation, not a gate).
            (when source-code
              (let [tokens (->> (tree-seq coll? seq source-code)
                                (filter keyword?)
                                set)]
                ;; A declared id appearing as a destructure key is fine; an
                ;; `:rf.cofx` leaf keyword present in the source but NOT
                ;; declared is the flagged case. Restrict to owner-qualified
                ;; cofx-shaped keywords that are registered cofx ids to avoid
                ;; flagging ordinary :data keys.
                (doseq [t tokens
                        :when (and (qualified-keyword? t)
                                   (not (contains? declared t))
                                   (some? (registrar/lookup :cofx t)))]
                  (emit-consume-undeclared! machine-id slot entry-id t))))))))))
