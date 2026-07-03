(ns day8.re-frame2-xray.filters.hidden-indicator-cljs-test
  "Integration tests for the L2 'N hidden by filters' indicator
  (rf2-jvghz, defect #1). Covers:

    1. `:rf.xray/hidden-by-filters` sub composition — raw vs filtered
       visible counts under an IN pill, a frame pin, and a mute.
    2. The indicator view renders the count + cause chips + Clear
       button whenever filtered-count < raw-count (incl. filtered-to-
       empty).
    3. `:rf.xray/clear-all-filters` resets pills + frame pin + mutes so
       the filtered list snaps back to raw and the indicator vanishes.

  Mirrors the spine_filters integration test's registry / frame /
  trace-bus setup so the sub-graph resolves through the `:rf/xray`
  frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (spine-filters/clear-raw!)
  (frame-switcher/clear!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
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

;; ---- hiccup helpers (mirrors spine_filters_cljs_test) -------------------

(declare expand-tree)

(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else tree))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-tree tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- count-by-testid
  "How many nodes in the expanded tree carry `testid` — used to assert a
  committed pill renders EXACTLY once (rf2-ad7zx.18: the hidden-message
  no longer re-renders the pills as cause chips)."
  [tree testid]
  (count (filter (fn [node]
                   (and (vector? node)
                        (map? (second node))
                        (= testid (:data-testid (second node)))))
                 (hiccup-seq tree))))

(defn- text-of
  "Concatenate the string leaves under a node — good enough to assert
  the count message reads as expected."
  [node]
  (apply str (filter string? (hiccup-seq node))))

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
          indicator (find-by-testid tree "rf-xray-filters-hidden-indicator")
          count-node (find-by-testid tree "rf-xray-filters-hidden-count")]
      (is (some? indicator) "banner renders when rows are hidden")
      ;; rf2-pjjwh — the Clear Filters button is retired; the warning
      ;; carries the count only.
      (is (nil? (find-by-testid tree "rf-xray-filters-hidden-clear"))
          "Clear filters button is retired (rf2-pjjwh)")
      ;; rf2-3f2di A5 — the bar-2 warning reads `N events filtered out`
      ;; (authority reference events-ribbon), superseding the prior
      ;; `N events hidden by filters` copy.
      (is (re-find #"1 event filtered out" (text-of count-node))))))

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
        (is (nil? (find-by-testid tree "rf-xray-filters-hidden-causes"))
            "no duplicate cause-chip cluster in the hidden-message")
        (is (nil? (find-by-testid tree "rf-xray-filters-hidden-pill-0"))
            "no duplicate pill chip in the hidden-message")
        ;; the count survives; Clear Filters is retired (rf2-pjjwh).
        (is (some? (find-by-testid tree "rf-xray-filters-hidden-count"))
            "the hidden count is kept")
        (is (nil? (find-by-testid tree "rf-xray-filters-hidden-clear"))
            "Clear Filters is retired (rf2-pjjwh)")))))

(deftest indicator-absent-when-nothing-hidden
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b]))
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)]
      (is (nil? (find-by-testid tree "rf-xray-filters-hidden-indicator"))
          "no banner when filtered == raw"))))

;; -------------------------------------------------------------------------
;; (3) clear-all-filters resets every surface
;; -------------------------------------------------------------------------

(deftest clear-all-filters-resets-pills-and-mutes-not-frame
  (testing "rf2-4vp5j Workstream C — Clear Filters resets pills + mutes
            but LEAVES the frame view scope untouched (frame is a scope,
            not a filter)."
    (when (and (exists? js/window) (.-localStorage js/window))
      (xray-setup!)
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a] :rf/frame-x))
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b] :rf/frame-x))
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:noise/tick] :rf/frame-x))
      (frame-dispatch [:rf.xray/add-filter :in {:pattern :a}])
      (frame-dispatch [:rf.xray/mute-event-id :noise/tick])
      (frame-dispatch [:rf.xray/select-frame :rf/frame-x])
      ;; Pre-clear — pills + mute active (frame is a scope, not counted).
      (is (true? (:any-active? (frame-sub [:rf.xray/hidden-by-filters]))))
      (frame-dispatch [:rf.xray/clear-all-filters])
      (let [s (frame-sub [:rf.xray/hidden-by-filters])]
        (is (= {:in [] :out []} (frame-sub [:rf.xray/active-filters]))
            "pills reset")
        (is (= :rf/frame-x (frame-sub [:rf.xray/current-frame]))
            "frame view scope is PRESERVED — Clear Filters never touches it")
        (is (= #{} (frame-sub [:rf.xray/muted-event-ids]))
            "mutes cleared")
        (is (= 0 (:hidden s)) "nothing hidden after clear")
        (is (false? (:visible? s)) "message vanishes")
        ;; list snaps back to frame-x's full set (3 events)
        (is (= 3 (:filtered-count s)) "filtered list snaps back to frame scope"))
      ;; Persistence — the unmute survives a reload read.
      (is (= #{} (spine-filters/load)) "mute localStorage cleared"))))

(deftest clear-all-filters-removes-banner-from-view
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:a]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:b]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:noise/tick]))
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :noise/tick}])
  (rf/with-frame :rf/xray
    (is (some? (find-by-testid (shell/shell-view)
                               "rf-xray-filters-hidden-indicator")))
    (rf/dispatch-sync [:rf.xray/clear-all-filters])
    (is (nil? (find-by-testid (shell/shell-view)
                              "rf-xray-filters-hidden-indicator"))
        "banner gone after Clear filters")))
