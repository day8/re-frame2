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

(defn install!
  "Install the App-DB Diff subscriptions."
  []
  (rf/reg-sub :rf.causa/target-frame-db
    :<- [:rf.causa/target-frame]
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

  (rf/reg-sub :rf.causa/app-db-diff
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/target-frame-db]
    :<- [:rf.causa/selected-epoch-diff]
    :<- [:rf.causa/selected-epoch-sections]
    :<- [:rf.causa/pinned-slices]
    :<- [:rf.causa/focused-slice-path]
    :<- [:rf.causa/show-me-when-this-changed-result]
    :<- [:rf.causa/epoch-history]
    (fn [[target db diff-triples sections pinned focused-path focused-hits history]
         _query]
      (let [{:keys [non-reserved]} (h/partition-reserved
                                     (or diff-triples []))]
        {:target-frame          target
         :history-empty?        (empty? history)
         :changed-non-reserved  non-reserved
         :changed-sections      sections
         :changed-reserved      (h/reserved-summary db)
         :pinned-slices         pinned
         :focused-path          focused-path
         :focused-hits          focused-hits}))))
