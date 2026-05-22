(ns day8.re-frame2-causa.filters.hidden-indicator-cljs-test
  "Integration tests for the L2 'N hidden by filters' indicator
  (rf2-jvghz, defect #1). Covers:

    1. `:rf.causa/hidden-by-filters` sub composition — raw vs filtered
       visible counts under an IN pill, a frame pin, and a mute.
    2. The indicator view renders the count + cause chips + Clear
       button whenever filtered-count < raw-count (incl. filtered-to-
       empty).
    3. `:rf.causa/clear-all-filters` resets pills + frame pin + mutes so
       the filtered list snaps back to raw and the indicator vanishes.

  Mirrors the spine_filters integration test's registry / frame /
  trace-bus setup so the sub-graph resolves through the `:rf/causa`
  frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.frame-switcher :as frame-switcher]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.spine-filters :as spine-filters]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (spine-filters/clear-raw!)
  (frame-switcher/clear!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (spine-filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/causa
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync ev)))

(defn- dispatch-trace-ev
  ([id event-vec] (dispatch-trace-ev id event-vec :rf/default))
  ([id event-vec frame-id]
   {:id           id
    :op-type      :event
    :operation    :event/dispatched
    :tags         {:event       event-vec
                   :frame       frame-id
                   :dispatch-id id}}))

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

(defn- text-of
  "Concatenate the string leaves under a node — good enough to assert
  the count message reads as expected."
  [node]
  (apply str (filter string? (hiccup-seq node))))

;; -------------------------------------------------------------------------
;; (1) sub composition
;; -------------------------------------------------------------------------

(deftest hidden-by-filters-zero-when-no-filters
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a]))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b]))
  (let [s (frame-sub [:rf.causa/hidden-by-filters])]
    (is (= 0 (:hidden s)))
    (is (false? (:visible? s)))
    (is (false? (:any-active? s)))
    (is (= 2 (:raw-count s)))
    (is (= 2 (:filtered-count s)))))

(deftest hidden-by-filters-counts-out-pill-suppression
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:auth/login]))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:user/mouse-move]))
  (trace-bus/collect-trace! (dispatch-trace-ev 3 [:order/submit]))
  ;; OUT pill hides :user/mouse-move → 1 hidden.
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :user/mouse-move}])
  (let [s (frame-sub [:rf.causa/hidden-by-filters])]
    (is (= 1 (:hidden s)))
    (is (true? (:visible? s)))
    (is (true? (:any-active? s)))
    (is (= 3 (:raw-count s)))
    (is (= 2 (:filtered-count s)))
    (is (= 1 (count (:pills s))))
    (is (= :out (:mode (first (:pills s)))))))

(deftest hidden-by-filters-counts-frame-pin-suppression
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a] :rf/frame-x))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b] :rf/frame-y))
  (trace-bus/collect-trace! (dispatch-trace-ev 3 [:c] :rf/frame-y))
  ;; Pin to :rf/frame-y → only 2 of 3 survive, 1 hidden, frame named.
  (frame-dispatch [:rf.causa/select-frame :rf/frame-y])
  (let [s (frame-sub [:rf.causa/hidden-by-filters])]
    (is (= 1 (:hidden s)))
    (is (true? (:visible? s)))
    (is (= :rf/frame-y (:frame s)))))

(deftest hidden-by-filters-counts-mute-suppression
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a]))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:noise/tick]))
  (frame-dispatch [:rf.causa/mute-event-id :noise/tick])
  (let [s (frame-sub [:rf.causa/hidden-by-filters])]
    (is (= 1 (:hidden s)))
    (is (= 1 (:muted-count s)))
    (is (true? (:visible? s)))))

(deftest hidden-by-filters-frame-pin-to-empty-still-visible
  (testing "the looked-broken case — pin to a frame with no events leaves
            zero filtered rows but the summary stays visible"
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a] :rf/frame-x))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b] :rf/frame-x))
    (frame-dispatch [:rf.causa/select-frame :rf/empty-frame])
    (let [s (frame-sub [:rf.causa/hidden-by-filters])]
      (is (= 2 (:hidden s)))
      (is (= 0 (:filtered-count s)))
      (is (true? (:visible? s))))))

;; -------------------------------------------------------------------------
;; (2) view renders the banner
;; -------------------------------------------------------------------------

(deftest indicator-renders-count-and-clear-when-hidden
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a]))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b]))
  (trace-bus/collect-trace! (dispatch-trace-ev 3 [:noise/tick]))
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :noise/tick}])
  (rf/with-frame :rf/causa
    (let [tree (shell/shell-view)
          indicator (find-by-testid tree "rf-causa-filters-hidden-indicator")
          count-node (find-by-testid tree "rf-causa-filters-hidden-count")]
      (is (some? indicator) "banner renders when rows are hidden")
      (is (some? (find-by-testid tree "rf-causa-filters-hidden-clear"))
          "Clear filters button present")
      (is (some? (find-by-testid tree "rf-causa-filters-hidden-pill-0"))
          "the OUT pill is named as the cause")
      (is (re-find #"1 event hidden by filters" (text-of count-node))))))

(deftest indicator-absent-when-nothing-hidden
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a]))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b]))
  (rf/with-frame :rf/causa
    (let [tree (shell/shell-view)]
      (is (nil? (find-by-testid tree "rf-causa-filters-hidden-indicator"))
          "no banner when filtered == raw"))))

;; -------------------------------------------------------------------------
;; (3) clear-all-filters resets every surface
;; -------------------------------------------------------------------------

(deftest clear-all-filters-resets-pills-frame-and-mutes
  (when (and (exists? js/window) (.-localStorage js/window))
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a] :rf/frame-x))
    (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b] :rf/frame-x))
    (trace-bus/collect-trace! (dispatch-trace-ev 3 [:noise/tick] :rf/frame-x))
    (frame-dispatch [:rf.causa/add-filter :in {:pattern :a}])
    (frame-dispatch [:rf.causa/mute-event-id :noise/tick])
    (frame-dispatch [:rf.causa/select-frame :rf/frame-x])
    ;; Pre-clear — pills, frame pin, mute all active.
    (is (true? (:any-active? (frame-sub [:rf.causa/hidden-by-filters]))))
    (frame-dispatch [:rf.causa/clear-all-filters])
    (let [s (frame-sub [:rf.causa/hidden-by-filters])]
      (is (= {:in [] :out []} (frame-sub [:rf.causa/active-filters]))
          "pills reset")
      (is (nil? (frame-sub [:rf.causa/current-frame]))
          "frame pin cleared")
      (is (= #{} (frame-sub [:rf.causa/muted-event-ids]))
          "mutes cleared")
      (is (= 0 (:hidden s)) "nothing hidden after clear")
      (is (false? (:visible? s)) "indicator vanishes")
      (is (= 3 (:filtered-count s)) "filtered list snaps back to raw"))
    ;; Persistence — the unpin + unmute survive a reload read.
    (is (nil? (frame-switcher/load)) "frame-pin localStorage cleared")
    (is (= #{} (spine-filters/load)) "mute localStorage cleared")))

(deftest clear-all-filters-removes-banner-from-view
  (causa-setup!)
  (trace-bus/collect-trace! (dispatch-trace-ev 1 [:a]))
  (trace-bus/collect-trace! (dispatch-trace-ev 2 [:b]))
  (trace-bus/collect-trace! (dispatch-trace-ev 3 [:noise/tick]))
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :noise/tick}])
  (rf/with-frame :rf/causa
    (is (some? (find-by-testid (shell/shell-view)
                               "rf-causa-filters-hidden-indicator")))
    (rf/dispatch-sync [:rf.causa/clear-all-filters])
    (is (nil? (find-by-testid (shell/shell-view)
                              "rf-causa-filters-hidden-indicator"))
        "banner gone after Clear filters")))
