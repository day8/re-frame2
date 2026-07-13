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

(defonce ^:private owner*
  ;; The identity that currently owns the projection (any comparable value —
  ;; a namespaced keyword by convention), or nil when uninstalled. `defonce`
  ;; so the ownership survives namespace reload (HMR) — a same-owner
  ;; re-install is the idempotent re-arm path.
  (atom nil))

(defonce ^:private entries*
  ;; `ViewCell -> {:cell-id ord :view-id vid :evidence accrual}` — the whole
  ;; retained projection. Keyed by cell identity (the scheduler's own dedup
  ;; axis); dead cells are pruned at every delivery and read, and the map is
  ;; cleared on uninstall, so a tool absent or closed retains ZERO cells.
  (atom {}))

(defonce ^:private cell-ordinal*
  ;; Monotonic mint for `:cell-id` — a stable, developer-facing instance
  ;; ordinal (two mounts of one view are two cells; the ordinal keeps their
  ;; rows apart and stable across batches). Never reset while installed.
  (atom 0))

(defn- prune-dead
  "Drop every entry whose cell has been torn down (`:dead` — root/frame
  teardown, rf2-vxgfnd.85). A disconnected (Activity-hidden) cell is still
  retained and reconnectable, so its accumulated evidence stays."
  [entries]
  (reduce-kv (fn [m cell _]
               (if (= :dead (reactive/lifecycle cell)) (dissoc m cell) m))
             entries entries))

(defn- project-batch!
  "The installed evidence-sink `(fn [cell ev] …)`: accrete one flushed
  batch's bounded record into `cell`'s accumulator and sweep dead cells.
  Runs inside the flush's rf2-vxgfnd.73 containment; constant-work per
  delivery (the record is already bounded). Never called in production
  (the flush's sink call is debug-gated); belt-gated here regardless."
  [cell ev]
  (when interop/debug-enabled?
    (when-not (= :dead (reactive/lifecycle cell))
      ;; Mint the ordinal outside the swap (pure retry body). The CLJS host
      ;; is single-threaded; a concurrent JVM fixture could at worst waste
      ;; an ordinal, never corrupt an entry.
      (let [ord (when-not (contains? @entries* cell)
                  (swap! cell-ordinal* inc))]
        (swap! entries*
               (fn [entries]
                 (let [entries (prune-dead entries)
                       entry   (or (get entries cell)
                                   {:cell-id  ord
                                    :view-id  (reactive/cell-view-id cell)
                                    :evidence nil})]
                   (assoc entries cell
                          (update entry :evidence accrete-evidence ev)))))))
    nil))

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
  bypassing it directly is the test seam only."
  [owner-id]
  (if-not interop/debug-enabled?
    false
    (do
      (assert (some? owner-id) "install! requires a non-nil owner id")
      (let [[old new] (swap-vals! owner*
                                  (fn [cur]
                                    (if (or (nil? cur) (= cur owner-id))
                                      owner-id
                                      cur)))]
        (cond
          (nil? old)
          (do (reset! entries* {})
              (reactive/set-evidence-sink! project-batch!)
              true)

          (= old owner-id)
          (do (reactive/set-evidence-sink! project-batch!)
              true)

          :else
          (do (warn-rejected-install! new owner-id)
              false))))))

(defn uninstall!
  "Release the projection iff `owner-id` owns it: clear the reactive sink
  slot, drop EVERY retained entry (cells + accumulated evidence), and free
  the ownership — teardown releases all callbacks/references, so a closed
  tool retains nothing. Returns true when released; false (nothing changes)
  when `owner-id` is not the current owner. Production: no-op, false."
  [owner-id]
  (if-not interop/debug-enabled?
    false
    (let [[old _] (swap-vals! owner* (fn [cur] (if (= cur owner-id) nil cur)))]
      (if (= old owner-id)
        (do (reactive/set-evidence-sink! nil)
            (reset! entries* {})
            true)
        false))))

(defn force-release!
  "Test support: release the projection REGARDLESS of owner — sink slot
  cleared, every entry dropped, ownership freed. The fixture-reset door
  (pair it with `reactive/reset-scheduler!` between fixtures); production
  tools use the owner-checked `uninstall!`. Returns nil."
  []
  (reset! owner* nil)
  (reset! entries* {})
  (when interop/debug-enabled?
    (reactive/set-evidence-sink! nil))
  nil)

(defn installed-owner
  "The identity currently owning the projection, or nil (tool/test read)."
  []
  @owner*)

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
    (let [entries (swap! entries* prune-dead)
          roots   (root-id-index)]
      (->> entries
           (map (fn [[cell {:keys [cell-id view-id evidence]}]]
                  {:cell-id  cell-id
                   :view-id  view-id
                   :root-id  (get roots (reactive/cell-root cell))
                   :evidence evidence}))
           (sort-by :cell-id)
           (into [])))))
