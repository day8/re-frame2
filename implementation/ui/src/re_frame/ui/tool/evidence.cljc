(ns re-frame.ui.tool.evidence
  "The first-party tool projection over the ViewCell DEBUG
  invalidation-evidence plane (rf2-vxgfnd.75) — the `re-frame.ui.tool`/Xray
  consumer the reactive scheduler's `set-evidence-sink!` seam was built for
  (rf2-vxgfnd.46).

  WHAT IT PROJECTS. Each flushed render batch delivers one BOUNDED causal
  summary per pending cell (`re-frame.ui.reactive/fold-evidence` — first/latest
  frame-epoch, occurrence `:count`, the cause set, a capped shown-target
  sample, and the honest `:dropped`/`:dropped-exact?` loss account from
  rf2-vxgfnd.74). This namespace ACCRETES those per-batch records into one
  bounded per-cell accumulator keyed by stable identity — a projection ordinal
  (`:cell-id`), the authoring view (`:view-id`), and the owning client root
  (`:root-id`, resolved through the live-root registry at read time) — so a
  tool (Xray) can attribute coalesced renders to their contributing movement
  across the cell's whole observed life WITHOUT retaining any event payload.

  LIFECYCLE — IDENTITY-OWNED, NEVER CLOBBERING. The reactive seam is a raw
  last-write-wins slot; this tier makes it safe for tool coexistence + HMR:
  `install!` claims the projection for an owner id, a SECOND owner's install
  is REJECTED (returns false, warns — the first registration is never
  silently cleared), a same-owner re-install is an idempotent re-arm (the
  `:after-load` / Fast Refresh path — accumulated evidence survives), and
  `uninstall!` (owner-checked) releases the sink callback and every retained
  entry. A tool absent or uninstalled retains ZERO ViewCells/evidence.

  LINEARIZED BY GENERATION (rf2-vxgfnd.147). Owner, generation, the armed
  sink closure, the entries, and the ordinal mint live in ONE state atom
  (`state*`), so install/uninstall/publication transitions are single
  atomic swaps. Every ownership transition through the closed state bumps
  a monotonically unique GENERATION, the armed sink closure captures the
  generation current at arm time, and a delivery publishes ONLY while its
  captured generation exactly matches the current open one — so a delayed
  callback that raced an uninstall cannot repopulate an ownerless
  projection, a stale owner-A callback cannot contaminate owner B's fresh
  projection, and an uninstall/reinstall of the SAME logical owner id is
  ABA-safe (the old incarnation's callbacks are fenced out by generation,
  not by owner equality). On the JVM the lifecycle transitions additionally
  serialize their raw-slot arm/disarm under a tiny transition lock — held
  only across the swap + slot write, NEVER through a tool/user callback —
  so a stale A teardown can never disarm B's freshly installed sink. The
  legal linearization: a publication linearizes at its state swap and takes
  effect iff the open generation it captured is still current; install and
  uninstall linearize in transition-lock order.

  OBSERVATIONAL ONLY. The scheduler stays authoritative: delivery runs inside
  the flush's containment (rf2-vxgfnd.73 — a throwing consumer can neither
  strand a cell nor abort a batch), the projection never acquires/releases or
  computes, and dead (torn-down) cells are PRUNED at delivery and at read so
  abandoned cells disappear from the projection. The whole operative surface
  is gated on `interop/debug-enabled?` and DCEs out of `:advanced` +
  goog.DEBUG=false production output (Spec 006 §The internal observation
  port; 03 §3)."
  (:require [re-frame.interop     :as interop]
            [re-frame.ui.reactive :as reactive]
            #?(:cljs [re-frame.ui.client :as client])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the bounded per-cell accumulator ---------------------------------------
;;
;; The SAME honesty discipline as the per-window fold (rf2-vxgfnd.74), applied
;; at the cumulative tier: a bounded SHOWN sample of distinct moving targets,
;; overflow recorded by IDENTITY in a bounded loss set, saturation flagged
;; (never silently lost), everything constant-size per cell.

(def ^:private ^:const target-cap
  ;; Distinct moving targets SHOWN per cell accumulator — mirrors the
  ;; per-window shown-sample cap so a one-batch cell projects verbatim.
  8)

(def ^:private ^:const dropped-cap
  ;; Distinct OMITTED target keys tracked before the cumulative loss account
  ;; itself saturates (`:dropped-exact?` flips false; `(count :dropped)`
  ;; becomes a LOWER bound) — mirrors the per-window loss-set cap.
  64)

(def ^:private empty-accrual
  ;; The zero accumulator — also the normalization for a (defensive) nil or
  ;; partial delivered batch, which folds as empty.
  {:first-epoch    nil
   :latest-epoch   nil
   :count          0
   :causes         #{}
   :targets        []
   :dropped        #{}
   :dropped-exact? true})

(defn- fold-target
  "Fold ONE distinct target key `tk` from a delivered batch into the
  cumulative shown-sample/loss-set pair. First classification is stable: a
  target already shown (or already recorded as an omission) folds as known —
  re-delivery across batches can never inflate the loss (the cross-batch
  restatement of the rf2-vxgfnd.74 honesty fix)."
  [acc tk]
  (let [known? (or (some #(= tk %) (:targets acc))
                   (contains? (:dropped acc) tk))]
    (cond
      known?                                 acc
      (< (count (:targets acc)) target-cap)  (update acc :targets conj tk)
      (< (count (:dropped acc)) dropped-cap) (update acc :dropped conj tk)
      :else                                  (assoc acc :dropped-exact? false))))

(defn- accrete-evidence
  "Fold one flushed batch's bounded evidence record `ev` into the cell's
  cumulative accumulator `acc` (nil = first delivery). Returns a BOUNDED,
  constant-size record — `empty-accrual`'s keys plus `:batches`:

    :first-epoch    — the anchor of the FIRST delivered window (set once,
                      never rewritten — nil stays nil when that window
                      carried no epoch evidence, mirroring the window fold)
    :latest-epoch   — the most recent window's latest movement
    :count          — total invalidation OCCURRENCES across every batch
    :batches        — how many flushed batches delivered evidence
    :causes         — the union cause set (:value/:hmr/:disposed — ≤3)
    :targets        — bounded shown sample of distinct moving targets
    :dropped        — bounded loss SET of distinct omitted targets; its
                      count is the honest cumulative fan-out loss
    :dropped-exact? — false once EITHER a delivered window or the
                      cumulative loss set saturated (a floor, never a lie)

  Never retains `ev` itself, a payload, or anything scaling with the
  invalidation count."
  [acc ev]
  (let [ev  (merge empty-accrual ev)
        acc (or acc (assoc empty-accrual
                           :first-epoch (:first-epoch ev)
                           :batches 0))]
    (-> acc
        (update :batches inc)
        (assoc :latest-epoch (:latest-epoch ev))
        (update :count + (:count ev))
        (update :causes into (:causes ev))
        (as-> a (reduce fold-target a (concat (:targets ev) (:dropped ev))))
        (cond-> (false? (:dropped-exact? ev)) (assoc :dropped-exact? false)))))

;; ---- projection state --------------------------------------------------------

(defonce ^:private state*
  ;; THE ONE AUTHORITATIVE projection state (rf2-vxgfnd.147) — owner,
  ;; generation, the armed sink closure, the retained entries, and the
  ;; ordinal mint transition TOGETHER in single atomic swaps, so the
  ;; lifecycle is linearizable (no second store to race):
  ;;
  ;;   :owner        — the identity currently owning the projection (any
  ;;                   comparable value; a namespaced keyword by
  ;;                   convention), or nil when uninstalled.
  ;;   :generation   — monotonically unique ownership-span counter. Bumped
  ;;                   on every transition through the CLOSED state (fresh
  ;;                   claim, uninstall, force-release) and NEVER reused,
  ;;                   so a callback fenced on the generation it captured
  ;;                   is ABA-safe across uninstall/reinstall of the same
  ;;                   logical owner id. A same-owner re-arm (the HMR
  ;;                   path) is the SAME continuous ownership span and
  ;;                   keeps its generation — accumulated evidence and
  ;;                   in-flight deliveries stay valid together.
  ;;   :sink         — the exact closure this tier last armed on the raw
  ;;                   reactive slot (nil when closed) — kept HERE per the
  ;;                   one-authoritative-state discipline, and the capture
  ;;                   door for race fixtures (`installed-sink`).
  ;;   :entries      — `ViewCell -> {:cell-id ord :view-id vid :evidence
  ;;                   accrual}` — the whole retained projection. Keyed by
  ;;                   cell identity (the scheduler's own dedup axis); dead
  ;;                   cells are pruned at every delivery and read, and the
  ;;                   map clears atomically WITH the ownership transition
  ;;                   on uninstall, so a tool absent or closed retains
  ;;                   ZERO cells.
  ;;   :next-ordinal — monotonic mint for `:cell-id` (a stable,
  ;;                   developer-facing instance ordinal: two mounts of one
  ;;                   view are two cells; the ordinal keeps their rows
  ;;                   apart and stable across batches). Never reset while
  ;;                   installed; reset with the entries when the
  ;;                   projection closes (a closed tool retains no ordinal
  ;;                   state).
  ;;
  ;; `defonce` (module-lived); note the var is NEW with this shape — a hot
  ;; reload from the pre-.147 multi-atom representation initialises it
  ;; fresh (the owning tool's `:after-load` re-install re-claims it), so
  ;; no old-shape value can survive into the new code (the rf2-vxgfnd.168
  ;; lesson applied at the source).
  (atom {:owner nil :generation 0 :sink nil :entries {} :next-ordinal 0}))

#?(:clj
   (defonce ^:private transition-lock
     ;; Serialises LIFECYCLE transitions (install/uninstall/force-release)
     ;; with their raw-slot arm/disarm so the pair is one linearization
     ;; point: a stale A teardown can never disarm B's freshly armed sink.
     ;; Held ONLY across the state swap + `set-evidence-sink!` write —
     ;; never through a tool/user callback (`project-batch!` publication
     ;; does not take it; the generation fence in its swap is its
     ;; linearization). CLJS is single-threaded: transitions cannot
     ;; interleave, so no lock exists there.
     (Object.)))

(defn- transition!
  "Run lifecycle transition `f` (state swap + raw-slot effect) atomically
  with respect to other transitions. See `transition-lock`."
  [f]
  #?(:clj  (locking transition-lock (f))
     :cljs (f)))

(defn- prune-dead
  "Drop every entry whose cell has been torn down (`:dead` — root/frame
  teardown, rf2-vxgfnd.85). A disconnected (Activity-hidden) cell is still
  retained and reconnectable, so its accumulated evidence stays."
  [entries]
  (reduce-kv (fn [m cell _]
               (if (= :dead (reactive/lifecycle cell)) (dissoc m cell) m))
             entries entries))

(defn- project-batch!
  "One generation-fenced delivery: accrete one flushed batch's bounded
  record into `cell`'s accumulator and sweep dead cells — IFF `gen`, the
  generation the armed sink closure captured, is still the current OPEN
  owner's generation. A callback whose generation has closed (uninstall,
  force-release, or a later reinstall — even of the same owner id) is a
  no-op: it can neither repopulate an ownerless projection nor contaminate
  a successor owner's fresh one (rf2-vxgfnd.147). The fence, the ordinal
  mint, and the entry write are ONE swap, so publication linearizes at the
  swap and a concurrent uninstall either precedes it (fence rejects) or
  follows it (the transition clears the entry it just admitted).

  Runs inside the flush's rf2-vxgfnd.73 containment; constant-work per
  delivery (the record is already bounded). Never called in production
  (the flush's sink call is debug-gated); belt-gated here regardless."
  [gen cell ev]
  (when interop/debug-enabled?
    (when-not (= :dead (reactive/lifecycle cell))
      (swap! state*
             (fn [{:keys [owner generation entries next-ordinal] :as s}]
               (if-not (and (some? owner) (= gen generation))
                 s
                 (let [entries  (prune-dead entries)
                       existing (get entries cell)
                       entry    (or existing
                                    {:cell-id  next-ordinal
                                     :view-id  (reactive/cell-view-id cell)
                                     :evidence nil})]
                   (assoc s
                          :entries (assoc entries cell
                                          (update entry :evidence
                                                  accrete-evidence ev))
                          :next-ordinal (if existing
                                          next-ordinal
                                          (inc next-ordinal))))))))
    nil))

(defn- sink-for
  "The armed evidence-sink closure for one ownership span: every delivery
  it performs is fenced on `generation` — the generation current when it
  was armed (`install!`). Retains nothing beyond that integer."
  [generation]
  (fn [cell ev] (project-batch! generation cell ev)))

;; ---- lifecycle (identity-owned install/uninstall) ---------------------------

(defn- warn-rejected-install!
  "A rejected install must not be SILENT (the caller also gets false):
  emit one host diagnostic naming both owners. Debug-gated at the caller,
  so the whole branch DCEs out of production."
  [current rejected]
  #?(:cljs
     (when (exists? js/console)
       (.warn js/console
              (str "[re-frame.ui] the invalidation-evidence projection is "
                   "already owned by " (pr-str current) " — the install for "
                   (pr-str rejected) " was REJECTED (one tool cannot "
                   "silently clear another owner's registration). Uninstall "
                   "the current owner first (uninstall!), or coordinate on "
                   "one owner id.")))
     :clj nil))

(defn install!
  "Claim the invalidation-evidence projection for `owner-id` (any comparable
  non-nil value; a namespaced keyword by convention) and arm the reactive
  seam (`reactive/set-evidence-sink!`) with this namespace's accretor.

  Identity-owned, HMR-safe:
    - unowned            → installs fresh (empty projection); returns true.
    - owned by owner-id  → idempotent RE-ARM (the `:after-load`/Fast-Refresh
                           path, and the recovery after a test-fixture
                           `reset-scheduler!` cleared the raw slot) —
                           accumulated evidence is KEPT; returns true.
    - owned by another   → REJECTED: nothing changes, the current owner's
                           registration stands, one console diagnostic is
                           emitted (never silent); returns false.

  Production (`goog.DEBUG=false` / `-Dre-frame.debug=false`): a no-op
  returning false — the debug evidence plane does not exist there, and the
  whole projection DCEs out of `:advanced` output.

  While installed, the raw `set-evidence-sink!` slot belongs to this tier;
  bypassing it directly is the test seam only.

  Linearization (rf2-vxgfnd.147): the ownership swap + the raw-slot arm are
  one transition (`transition!`), a FRESH claim opens a NEW generation, and
  the armed closure captures exactly that generation — deliveries of any
  earlier generation are fenced out, even for the same owner id (ABA-safe)."
  [owner-id]
  (if-not interop/debug-enabled?
    false
    (do
      (assert (some? owner-id) "install! requires a non-nil owner id")
      (transition!
       (fn []
         (let [[old new]
               (swap-vals! state*
                           (fn [{:keys [owner generation] :as s}]
                             (cond
                               ;; fresh claim → NEW generation, fresh fenced
                               ;; closure, empty projection
                               (nil? owner)
                               (let [g (inc generation)]
                                 (assoc s :owner owner-id
                                          :generation g
                                          :sink (sink-for g)
                                          :entries {}))
                               ;; same-owner re-arm → the SAME ownership span
                               ;; (generation kept, evidence kept), fresh
                               ;; closure for the raw slot
                               (= owner owner-id)
                               (assoc s :sink (sink-for generation))
                               ;; foreign owner → untouched
                               :else s)))]
           (if (or (nil? (:owner old)) (= (:owner old) owner-id))
             ;; Arm the raw slot with the closure the transition minted —
             ;; both writes sit inside the transition lock, so no stale
             ;; teardown can interleave between them.
             (do (reactive/set-evidence-sink! (:sink new))
                 true)
             (do (warn-rejected-install! (:owner new) owner-id)
                 false))))))))

(defn uninstall!
  "Release the projection iff `owner-id` owns it: one atomic transition
  frees the ownership, CLOSES the generation (so every in-flight delivery
  of this span is fenced out — a delayed callback cannot repopulate the
  released projection), drops EVERY retained entry (cells + accumulated
  evidence) and the ordinal state, and disarms the reactive sink slot —
  teardown releases all callbacks/references, so a closed tool retains
  nothing. Owner-checked AND transition-serialised: a stale uninstall
  racing a successor's install either precedes it (releases, then the
  successor claims fresh) or follows it (sees the successor's ownership
  and refuses — the successor's sink/state are untouched). Returns true
  when released; false (nothing changes) when `owner-id` is not the
  current owner. Production: no-op, false."
  [owner-id]
  (if-not interop/debug-enabled?
    false
    (transition!
     (fn []
       (let [[old _] (swap-vals! state*
                                 (fn [{:keys [owner generation] :as s}]
                                   (if (= owner owner-id)
                                     {:owner        nil
                                      :generation   (inc generation)
                                      :sink         nil
                                      :entries      {}
                                      :next-ordinal 0}
                                     s)))]
         (if (= (:owner old) owner-id)
           (do (reactive/set-evidence-sink! nil)
               true)
           false))))))

(defn force-release!
  "Test support: release the projection REGARDLESS of owner — sink slot
  cleared, every entry dropped, ownership freed, and the generation CLOSED
  (an in-flight delivery captured before the reset is fenced out exactly
  as under an owner-checked uninstall). The fixture-reset door (pair it
  with `reactive/reset-scheduler!` between fixtures); production tools use
  the owner-checked `uninstall!`. Returns nil."
  []
  (transition!
   (fn []
     (swap! state* (fn [{:keys [generation]}]
                     {:owner        nil
                      :generation   (inc generation)
                      :sink         nil
                      :entries      {}
                      :next-ordinal 0}))
     (when interop/debug-enabled?
       (reactive/set-evidence-sink! nil))
     nil)))

(defn installed-owner
  "The identity currently owning the projection, or nil (tool/test read)."
  []
  (:owner @state*))

(defn installed-sink
  "The exact sink closure this tier last armed on the reactive slot, or
  nil when the projection is closed (tool/test read). Race fixtures use it
  the way `deliver-flush!` does — deref at delivery start, invoke later —
  to replay a DELAYED delivery against a projection whose generation has
  since closed and prove the fence holds."
  []
  (:sink @state*))

;; ---- the projection read -----------------------------------------------------

(defn- root-id-index
  "incarnation -> root-id over the CLJS client live-root registry — the
  developer-facing name for the opaque per-mount incarnation a cell enrols
  under (rf2-vxgfnd.85). Empty on the JVM host (no client registry; Tier-1
  cells attach to raw incarnations, which have no authored name)."
  []
  #?(:cljs (into {}
                 (keep (fn [rid]
                         (when-some [i (:root-incarnation
                                        (client/live-root-entry rid))]
                           [i rid])))
                 (client/live-root-ids))
     :clj  {}))

(defn projection
  "The current Xray-usable invalidation-evidence projection: a vector of
  per-cell records in stable `:cell-id` order —

    {:cell-id  ord      ; stable projection ordinal for this cell instance
     :view-id  vid      ; the authoring view (defview identity)
     :root-id  rid|nil  ; the owning LIVE client root (nil when the cell is
                        ;   not under a live client root — e.g. Tier-1/JVM)
     :evidence accrual} ; the bounded cumulative record (`accrete-evidence`)

  Dead cells are pruned before the read, so abandoned/unmounted cells never
  appear; an uninstalled (or never-installed) projection is empty. nil in a
  production build, where the debug evidence plane is elided (tool read)."
  []
  (when interop/debug-enabled?
    (let [entries (:entries (swap! state* update :entries prune-dead))
          roots   (root-id-index)]
      (->> entries
           (map (fn [[cell {:keys [cell-id view-id evidence]}]]
                  {:cell-id  cell-id
                   :view-id  view-id
                   :root-id  (get roots (reactive/cell-root cell))
                   :evidence evidence}))
           (sort-by :cell-id)
           (into [])))))
