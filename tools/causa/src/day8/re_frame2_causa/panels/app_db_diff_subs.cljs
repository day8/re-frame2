(ns day8.re-frame2-causa.panels.app-db-diff-subs
  "Subscriptions and read-models for the App-DB Diff panel.

  ## rf2-gfxmk Phase 1 — sections-per-cluster

  Three additional subs land here for the structural-diff engine:

    `:rf.causa/selected-epoch-annotated-tree` — annotated-tree shape
      from `day8.re-frame2-causa.diff.annotated-tree/diff-tree` for the
      currently-focused epoch. Cached per `:epoch-id` for the same
      reason the legacy `:selected-epoch-diff` is: cascades replay the
      same `db-before` / `db-after` pair across the inspector's life.

    `:rf.causa/selected-epoch-sections` — section-grouping pass output
      consumed by the new renderer. Derived from the annotated tree
      above; lexicographic ordering per design §3.1.1.

  The legacy `:rf.causa/selected-epoch-diff` (flat triples) stays
  registered for back-compat consumers — the pin-store, MCP exporter,
  and `show me when this changed` walker continue to read it. Both
  shapes are kept in lockstep via the triples-adapter (the legacy sub
  now derives from the annotated tree via
  `diff.triples-adapter/annotated-tree->triples`, so a future audit
  can switch to the adapter projection if desired)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.section-grouping :as sg]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]))

(defn- find-epoch-in-history
  "Return the `:rf/epoch-record` in `history` whose `:epoch-id` matches
  `epoch-id`, or nil if absent. Pure data → record-or-nil. Inlined
  here when the Time Travel panel was deleted (rf2-qy0nu) — App-DB
  Diff was the only remaining consumer."
  [history epoch-id]
  (when (some? epoch-id)
    (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
          history)))

(defonce diff-cache
  ;; Per-`:epoch-id` cache for the diff triples computed by the
  ;; `:rf.causa/selected-epoch-diff` sub. Tests reset this atom
  ;; between cases; production callers should only write via the sub.
  (atom {}))

(defonce annotated-tree-cache
  ;; Per-`:epoch-id` cache for the annotated-tree shape computed by
  ;; `:rf.causa/selected-epoch-annotated-tree`. Same caching contract
  ;; as `diff-cache` above. Tests reset this atom between cases.
  (atom {}))

(defonce redacted-modified-cache
  ;; Per-`:epoch-id` cache for the redacted-paths-modified count
  ;; computed by `:rf.causa/selected-epoch-redacted-modified-count`
  ;; (rf2-bz1cl). Same caching contract as `diff-cache` above — the
  ;; walk is bounded by `count-redacted-modified-paths`' structural-
  ;; sharing short-circuit but cascades replay the same db-before /
  ;; db-after pair across the inspector's life, so caching the
  ;; final integer is still cheap insurance. Tests reset this atom
  ;; between cases.
  (atom {}))

(defn install!
  "Install the App-DB Diff subscriptions."
  []
  ;; rf2-fvplw — panel-observed frame follows the spine `:rf.causa/focus`.
  ;; The frame-picker writes `[:focus :frame]` via `:rf.causa/set-frame`,
  ;; and `compose-focus` also derives `:frame` from the focused cascade.
  ;; Without this seam the App-db panel previously read only the legacy
  ;; `:target-frame` slot (which `:rf.causa/set-frame` does NOT touch),
  ;; so it stayed hardcoded to `:rf/default` no matter what the user
  ;; picked. The legacy slot survives as the fallback when no focus has
  ;; resolved a frame yet (cold start, no focusable cascades) — keeps
  ;; the boot-time empty-state useful.
  (rf/reg-sub :rf.causa/observed-frame
    :<- [:rf.causa/focus]
    :<- [:rf.causa/target-frame]
    (fn [[focus target] _query]
      (or (:frame focus) target)))

  (rf/reg-sub :rf.causa/target-frame-db
    :<- [:rf.causa/observed-frame]
    :<- [:rf.causa/epoch-history]
    (fn [[target _epoch-history] _query]
      (rf/get-frame-db target)))

  (rf/reg-sub :rf.causa/selected-epoch-record
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (when selected-id
        (find-epoch-in-history history selected-id))))

  ;; Per rf2-drf32 — the diff sub falls back to `(peek history)` when
  ;; the selected epoch is absent from history. The selection slot is
  ;; shared with the Time Travel panel (so a scrub from there is
  ;; visible here too), but a stale selection (e.g. an epoch that has
  ;; aged out of the ring buffer, or persisted from a prior session)
  ;; previously stranded this panel showing "no slice changes" for
  ;; every subsequent dispatch. Always picking the latest record when
  ;; the selection can't be located restores the user's expectation
  ;; that "the diff panel shows whatever just happened".
  (rf/reg-sub :rf.causa/selected-epoch-diff
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (or (when selected-id
                         (find-epoch-in-history history selected-id))
                       (peek history))]
        (when record
          (let [epoch-id (:epoch-id record)
                cached   (get @diff-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              (let [diff (h/diff-paths (:db-before record)
                                       (:db-after  record))
                    live (into #{} (map :epoch-id) history)]
                (swap! diff-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id diff))))
                diff)))))))

  ;; ---- rf2-gfxmk Phase 1 — annotated-tree + sections -------------------
  ;;
  ;; The structural-diff engine produces an annotated mirror tree (every
  ;; node tagged `:added`/`:removed`/`:modified`/`:same`/`:children`).
  ;; The grouping pass decomposes that tree into N path-headed sections
  ;; for the renderer. Both are cached per `:epoch-id` for the same
  ;; reason `:selected-epoch-diff` is — cascades replay the same
  ;; `db-before`/`db-after` pair as the inspector navigates.
  (rf/reg-sub :rf.causa/selected-epoch-annotated-tree
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (or (when selected-id
                         (find-epoch-in-history history selected-id))
                       (peek history))]
        (when record
          (let [epoch-id (:epoch-id record)
                cached   (get @annotated-tree-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              (let [tree (at/diff-tree (:db-before record)
                                       (:db-after  record))
                    live (into #{} (map :epoch-id) history)]
                (swap! annotated-tree-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id tree))))
                tree)))))))

  (rf/reg-sub :rf.causa/selected-epoch-sections
    :<- [:rf.causa/selected-epoch-annotated-tree]
    (fn [annotated _query]
      (if annotated
        (sg/group-into-sections annotated)
        [])))

  ;; ---- rf2-bz1cl / rf2-dl3gx — redacted-paths-modified count ----------
  ;;
  ;; The structural diff (above) correctly emits NO rows when both
  ;; sides of a path carry `:rf/redacted` — `:rf/redacted` = `:rf/redacted`
  ;; structurally. When an app-supplied `:redact-fn` substitutes the
  ;; sentinel into `:db-before` / `:db-after` AND the underlying values
  ;; differ, the diff body is empty and the developer's "something
  ;; changed" expectation is not met.
  ;;
  ;; This sub computes a separate-from-diff signal: the count of
  ;; redacted leaves whose enclosing subtree mutated across the
  ;; cascade. The renderer surfaces the count as a muted chip above
  ;; the diff body when count > 0.
  ;;
  ;; ## Sources (preferred → fallback)
  ;;
  ;; 1. **Egress slot (rf2-dl3gx, preferred).** The epoch record may
  ;;    carry `:rf.epoch/redacted-modified-paths-count` — an integer
  ;;    computed BY THE FRAMEWORK from raw db-before / db-after values
  ;;    BEFORE the `:redact-fn` runs (per
  ;;    `re-frame.epoch.assembly/redacted-modified-paths-count` +
  ;;    `spec/Spec-Schemas.md §:rf/epoch-record`). When the slot is
  ;;    present the sub returns it verbatim — exact count, no walk.
  ;;
  ;; 2. **Heuristic fallback (rf2-bz1cl).** Records that lack the slot
  ;;    (legacy snapshots, mock records with no schema layer, tests
  ;;    seeding raw record maps) fall back to the Causa-side heuristic
  ;;    `app-db-diff-helpers/count-redacted-modified-paths` — paths where
  ;;    both sides carry the sentinel AND the parent subtree's pointer
  ;;    differs. Tight upper bound; can over-state when a sibling
  ;;    changed and the redacted slot was incidentally untouched.
  ;;
  ;; The cache is shared between both paths — `:epoch-id` keys the
  ;; integer regardless of which source produced it.
  ;;
  ;; ## Selection fallback
  ;;
  ;; Mirrors `:rf.causa/selected-epoch-diff` — the same selection-stale
  ;; path that strands the diff panel strands the chip; same
  ;; `(peek history)` fallback applies.
  (rf/reg-sub :rf.causa/selected-epoch-redacted-modified-count
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (or (when selected-id
                         (find-epoch-in-history history selected-id))
                       (peek history))]
        (if record
          (let [epoch-id (:epoch-id record)
                cached   (get @redacted-modified-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              ;; Egress slot wins when present — exact count from the
              ;; framework (rf2-dl3gx). Falls back to the Causa-side
              ;; heuristic only when the slot is absent.
              (let [egress (:rf.epoch/redacted-modified-paths-count record)
                    n      (if (some? egress)
                             egress
                             (h/count-redacted-modified-paths
                               (:db-before record)
                               (:db-after  record)))
                    live   (into #{} (map :epoch-id) history)]
                (swap! redacted-modified-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id n))))
                n)))
          0))))

  (rf/reg-sub :rf.causa/pinned-slices-store
    (fn [db _query]
      (get db :pinned-slices-store {})))

  (rf/reg-sub :rf.causa/pinned-slices
    :<- [:rf.causa/pinned-slices-store]
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/target-frame-db]
    (fn [[store target db] _query]
      (h/live-pinned-slices store target db)))

  (rf/reg-sub :rf.causa/focused-slice-path
    (fn [db _query]
      (get db :focused-slice-path)))

  (rf/reg-sub :rf.causa/show-me-when-this-changed-result
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/epoch-history]
    (fn [[focused-path history] _query]
      (if focused-path
        (h/epochs-touching-path history focused-path)
        [])))

  ;; rf2-fvplw — `:target-frame` in the composite output is the
  ;; *observed* frame (picker-selected / focused-cascade frame), not
  ;; the legacy `:target-frame` slot. The empty-state body uses this
  ;; value, so the user always sees the frame their focus is on rather
  ;; than the hardcoded `:rf/default`.
  (rf/reg-sub :rf.causa/app-db-diff
    :<- [:rf.causa/observed-frame]
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/selected-epoch-diff]
    :<- [:rf.causa/selected-epoch-sections]
    :<- [:rf.causa/pinned-slices]
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/show-me-when-this-changed-result]
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-redacted-modified-count]
    (fn [[target db diff-triples sections pinned focused-path focused-hits
          history redacted-modified-count]
         _query]
      (let [{:keys [non-reserved]} (h/partition-reserved
                                     (or diff-triples []))]
        {:target-frame              target
         :history-empty?            (empty? history)
         :changed-non-reserved      non-reserved
         :changed-sections          sections
         :changed-reserved          (h/reserved-summary db)
         :pinned-slices             pinned
         :focused-path              focused-path
         :focused-hits              focused-hits
         ;; rf2-bz1cl — redacted-paths-modified hint chip surface.
         ;; Count > 0 when an app `:redact-fn` substituted the
         ;; `:rf/redacted` sentinel into both `:db-before` /
         ;; `:db-after` at the same path inside a mutated subtree.
         :redacted-modified-count   redacted-modified-count}))))
