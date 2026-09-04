(ns day8.re-frame2-xray.filters.hidden-indicator-cljs-test
  "Integration tests for the L2 'N events filtered out' indicator
  (rf2-jvghz, defect #1). Covers:

    1. `:rf.xray/hidden-by-filters` sub composition — raw vs filtered
       visible counts under an IN pill, a frame pin, and a mute.
    2. The indicator view renders the `N events filtered out` warning
       whenever filtered-count < raw-count (incl. filtered-to-empty);
       the Clear Filters button + cause chips are retired (rf2-pjjwh),
       and the `:rf.xray/clear-all-filters` bulk-reset event was
       removed with them (rf2-rdhbk — no caller survived).

  Mirrors the spine_filters integration test's registry / frame /
  trace-bus setup so the sub-graph resolves through the `:rf/xray`
  frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset into one owner
  ;; (plain-atom + the `:all` tier, which already covers the trace-collector
  ;; rings the old init reset a SECOND time); `:post-reset` carries this
  ;; suite's filter-state tail.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (spine-filters/clear-raw!)
                   (frame-switcher/clear!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (spine-filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- dispatch-trace-ev
  ([id event-vec] (dispatch-trace-ev id event-vec :rf/default))
  ([id event-vec frame-id]
   {:id           id
    :op-type      :rf.event
    :operation    :rf.event/dispatched
    :tags         {:rf.event/v       event-vec
                   :frame       frame-id
                   :rf.trace/dispatch-id id}}))

;; ---- hiccup helpers -----------------------------------------------------
;; The private expand-tree / hiccup-seq / find-by-testid / text-of copies were
;; semantically identical to `re-frame.test-helpers`; tests call
;; `rf.test-helpers/find-by-testid` / `rf.test-helpers/text-content` directly (rf2-vj80u8 — no Xray
;; walker facade). `count-by-testid` is a thin count over `rf.test-helpers/find-all-by-testid`.

(defn- count-by-testid
  "How many nodes in the expanded tree carry `testid` — used to assert a
  committed pill renders EXACTLY once (rf2-ad7zx.18: the hidden-message
  no longer re-renders the pills as cause chips)."
  [tree testid]
  (count (rf.test-helpers/find-all-by-testid tree testid)))

;; -------------------------------------------------------------------------
;; (1) sub composition
;; -------------------------------------------------------------------------

(deftest hidden-by-filters-zero-when-no-filters
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b]))
  (let [s (frame-sub [:rf.xray/hidden-by-filters])]
    (is (= 0 (:hidden s)))
    (is (false? (:visible? s)))
    (is (false? (:any-active? s)))
    (is (= 2 (:raw-count s)))
    (is (= 2 (:filtered-count s)))))

(deftest hidden-by-filters-counts-out-pill-suppression
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:user/mouse-move]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:order/submit]))
  ;; OUT pill hides :user/mouse-move → 1 hidden.
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :user/mouse-move}])
  (let [s (frame-sub [:rf.xray/hidden-by-filters])]
    (is (= 1 (:hidden s)))
    (is (true? (:visible? s)))
    (is (true? (:any-active? s)))
    (is (= 3 (:raw-count s)))
    (is (= 2 (:filtered-count s)))
    (is (= 1 (count (:pills s))))
    (is (= :out (:mode (first (:pills s)))))))

(deftest frame-is-a-view-scope-not-a-filter
  (testing "rf2-4vp5j Workstream C — selecting a frame is a view SCOPE,
            not a filter: it is NEVER counted as hidden, NEVER an active
            filter, and the summary carries no `:frame` cause. The count
            baseline is computed WITHIN the selected frame so switching
            frames does not inflate 'hidden'."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a] :rf/frame-x))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b] :rf/frame-y))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:c] :rf/frame-y))
    ;; Scope to :rf/frame-y → the list shows frame-y's events; the
    ;; other frame's events are out-of-SCOPE, not hidden-by-filter.
    (frame-dispatch [:rf.xray/select-frame :rf/frame-y])
    (let [s (frame-sub [:rf.xray/hidden-by-filters])]
      (is (= 0 (:hidden s)) "frame scope is NOT counted as hidden")
      (is (false? (:visible? s)) "no hidden-count message for a scope change")
      (is (false? (:any-active? s)) "a frame scope alone is not an active filter")
      (is (not (contains? s :frame)) "no :frame cause in the model")
      ;; the list is scoped to frame-y (2 events), decoupled from filters
      (is (= :rf/frame-y (frame-sub [:rf.xray/view-scope-frame])))
      (is (= 2 (count (frame-sub [:rf.xray/filtered-event-bundles])))))))

(deftest hidden-by-filters-counts-mute-suppression
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:noise/tick]))
  (frame-dispatch [:rf.xray/mute-event-id :noise/tick])
  (let [s (frame-sub [:rf.xray/hidden-by-filters])]
    (is (= 1 (:hidden s)))
    (is (= 1 (:muted-count s)))
    (is (true? (:visible? s)))))

(deftest frame-scope-to-empty-frame-is-not-hidden-by-filters
  (testing "rf2-4vp5j — scoping to a frame with no events leaves zero
            rows, but that is an empty SCOPE, not 'hidden by filters'.
            The count baseline is within the selected frame (0 raw, 0
            filtered) so nothing is counted as hidden and no message
            renders."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a] :rf/frame-x))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b] :rf/frame-x))
    (frame-dispatch [:rf.xray/select-frame :rf/empty-frame])
    (let [s (frame-sub [:rf.xray/hidden-by-filters])]
      (is (= 0 (:hidden s)) "frame scope is never counted as hidden")
      (is (= 0 (:filtered-count s)))
      (is (false? (:visible? s)) "no hidden-count message for an empty scope")
      (is (false? (:any-active? s))))))

;; -------------------------------------------------------------------------
;; (2) view renders the banner
;; -------------------------------------------------------------------------

(deftest indicator-renders-count-when-hidden
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:noise/tick]))
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :noise/tick}])
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)
          indicator (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-indicator")
          count-node (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-count")]
      (is (some? indicator) "banner renders when rows are hidden")
      ;; rf2-pjjwh — the Clear Filters button is retired; the warning
      ;; carries the count only.
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-clear"))
          "Clear filters button is retired (rf2-pjjwh)")
      ;; rf2-3f2di A5 — the bar-2 warning reads `N events filtered out`
      ;; (authority reference events-ribbon), superseding the prior
      ;; `N events hidden by filters` copy.
      (is (re-find #"1 event filtered out" (rf.test-helpers/text-content count-node))))))

(deftest hidden-message-does-not-duplicate-the-committed-pills
  (testing "rf2-ad7zx.18 — per the Figma EventsRibbon mock the hidden-state
            is a plain count. The committed pill must render EXACTLY ONCE
            (in the LEFT cluster via pills-view); the hidden-message must
            NOT re-render it as a cause chip. rf2-pjjwh — Clear Filters is
            retired."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:noise/tick]))
    (frame-dispatch [:rf.xray/add-filter :out {:pattern :noise/tick}])
    (rf/with-frame :rf/xray
      (let [tree (shell/shell-view)]
        ;; the committed OUT pill renders ONCE — in the left cluster.
        (is (= 1 (count-by-testid tree "rf-xray-filter-pill-out-0"))
            "the committed pill renders exactly once (left cluster)")
        ;; the duplicate cause-chip cluster is gone.
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-causes"))
            "no duplicate cause-chip cluster in the hidden-message")
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-pill-0"))
            "no duplicate pill chip in the hidden-message")
        ;; the count survives; Clear Filters is retired (rf2-pjjwh).
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-count"))
            "the hidden count is kept")
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-clear"))
            "Clear Filters is retired (rf2-pjjwh)")))))

(deftest indicator-absent-when-nothing-hidden
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b]))
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)]
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filters-hidden-indicator"))
          "no banner when filtered == raw"))))

;; (The former section (3) — `:rf.xray/clear-all-filters` resets every
;; surface — was deleted with the event itself (rf2-rdhbk). The bulk
;; reset had no surviving caller after rf2-pjjwh retired the Clear
;; Filters button; recovery is per surface — each pill's `✕`, and
;; `:rf.xray/clear-muted-event-ids` behind the mute chip/manager.)
