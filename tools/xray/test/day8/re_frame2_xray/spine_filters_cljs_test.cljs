(ns day8.re-frame2-xray.spine-filters-cljs-test
  "Per-event-id mute filter tests (rf2-ikuwt). Covers:

    1. Pure helpers (filter-event-bundles, mute / unmute / clear reducers).
    2. EDN round-trip (<-edn / ->edn).
    3. save! / load! localStorage round-trip.
    4. Event handler wiring (mute-event-id / unmute-event-id /
       clear-muted-event-ids + persist fx).
    5. Hydration on install (localStorage value lifts into the slot).
    6. Filtered-cascades composition — muting an event-id strips
       matching cascades from `:rf.xray/filtered-event-bundles`.
    7. Row context menu state (open / close).
    8. Mute manager modal state (open / close).
    9. End-to-end: right-click → context menu → 'Mute' item dispatches
       mute-event-id → row disappears from the L2 list."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (spine-filters/clear-raw!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  ;; mirrors mount.cljs/ensure-xray-frame! — re-runs hydrate after the
  ;; frame is registered (preload-time install no-op'd).
  (spine-filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- dispatch-trace-ev [id event-vec]
  {:id           id
   :op-type      :rf.event
   :operation    :rf.event/dispatched
   :tags         {:rf.event/v       event-vec
                  :frame       :rf/default
                  :rf.trace/dispatch-id id}})

;; ---- hiccup helpers (copied from existing right-click integration test) --

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
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

;; -------------------------------------------------------------------------
;; (1) Pure helpers
;; -------------------------------------------------------------------------

(deftest event-bundle-event-id-pluck
  (is (= :foo/bar (spine-filters/event-bundle-event-id {:event [:foo/bar 1 2]})))
  (is (nil? (spine-filters/event-bundle-event-id {:event nil})))
  (is (nil? (spine-filters/event-bundle-event-id {})))
  (is (nil? (spine-filters/event-bundle-event-id {:event "not-a-vector"}))))

(deftest mute-event-id-reducer
  (is (= #{:a} (spine-filters/mute-event-id nil :a))
      "nil input promotes to empty set")
  (is (= #{:a :b} (spine-filters/mute-event-id #{:a} :b)))
  (is (= #{:a} (spine-filters/mute-event-id #{:a} :a))
      "muting an already-muted id is a no-op (set semantics)")
  (is (= #{:a} (spine-filters/mute-event-id #{:a} nil))
      "nil event-id is dropped — never corrupts the set"))

(deftest unmute-event-id-reducer
  (is (= #{} (spine-filters/unmute-event-id #{:a} :a)))
  (is (= #{:a} (spine-filters/unmute-event-id #{:a} :b))
      "unmuting an absent id is a no-op")
  (is (= #{} (spine-filters/unmute-event-id nil :a))
      "nil input promotes to empty set"))

(deftest clear-muted-reducer
  (is (= #{} (spine-filters/clear-muted #{:a :b :c})))
  (is (= #{} (spine-filters/clear-muted nil))))

(deftest filter-event-bundles-strips-muted-ids
  (let [cs [{:event [:a]} {:event [:b]} {:event [:c]}]]
    (is (= cs (spine-filters/filter-event-bundles cs #{}))
        "empty muted set returns the input vector")
    (is (= cs (spine-filters/filter-event-bundles cs nil))
        "nil muted set returns the input vector")
    (is (= [{:event [:b]} {:event [:c]}]
           (spine-filters/filter-event-bundles cs #{:a})))
    (is (= []
           (spine-filters/filter-event-bundles cs #{:a :b :c})))))

(deftest filter-event-bundles-preserves-event-less-cascades
  (testing "cascades with no event vector (e.g. :ungrouped bucket) survive
            the mute filter — they're handled by the separate
            :show-ungrouped? opt-in"
    (let [cs [{:event [:a]} {:event nil :dispatch-id :ungrouped} {:event [:b]}]]
      (is (= [{:event nil :dispatch-id :ungrouped} {:event [:b]}]
             (spine-filters/filter-event-bundles cs #{:a}))))))

;; -------------------------------------------------------------------------
;; (2) EDN round-trip
;; -------------------------------------------------------------------------

(deftest edn-round-trip-preserves-set
  (let [muted #{:a/x :b/y :c/z}]
    (is (= muted
           (spine-filters/<-edn (spine-filters/->edn muted))))))

(deftest edn-round-trip-handles-empty
  (is (= #{} (spine-filters/<-edn (spine-filters/->edn #{}))))
  (is (= #{} (spine-filters/<-edn (spine-filters/->edn nil)))))

(deftest edn-malformed-falls-back-to-empty
  (is (= #{} (spine-filters/<-edn "not edn at all")))
  (is (= #{} (spine-filters/<-edn "{not a set}"))))

(deftest edn-sequential-falls-through-to-set
  (testing "hand-edited [: vec :form] still loads as a set so a user
            tweak that yields a vector isn't a hard fail"
    (is (= #{:a :b} (spine-filters/<-edn "[:a :b]")))))

;; -------------------------------------------------------------------------
;; (3) save! / load round-trip
;; -------------------------------------------------------------------------

(deftest save-and-load-round-trip
  (when (and (exists? js/window) (.-localStorage js/window))
    (let [muted #{:auth/login :user/mouse-move}]
      (spine-filters/save! muted)
      (is (= muted (spine-filters/load))))))

(deftest load-when-slot-empty-returns-empty-set
  (spine-filters/clear-raw!)
  (is (= #{} (spine-filters/load))))

;; -------------------------------------------------------------------------
;; (4) Event handler wiring + persist fx
;; -------------------------------------------------------------------------

(deftest mute-event-id-event-writes-slot-and-persists
  (when (and (exists? js/window) (.-localStorage js/window))
    (xray-setup!)
    (frame-dispatch [:rf.xray/mute-event-id :user/mouse-move])
    (is (= #{:user/mouse-move}
           (frame-sub [:rf.xray/muted-event-ids])))
    (is (= 1 (frame-sub [:rf.xray/muted-event-ids-count])))
    (is (= #{:user/mouse-move}
           (spine-filters/load))
        "mute round-trips to localStorage")))

(deftest unmute-event-id-event-clears-slot
  (when (and (exists? js/window) (.-localStorage js/window))
    (xray-setup!)
    (frame-dispatch [:rf.xray/mute-event-id :user/mouse-move])
    (frame-dispatch [:rf.xray/mute-event-id :user/scroll])
    (frame-dispatch [:rf.xray/unmute-event-id :user/scroll])
    (is (= #{:user/mouse-move}
           (frame-sub [:rf.xray/muted-event-ids])))
    (is (= #{:user/mouse-move} (spine-filters/load))
        "unmute persists the new set")))

(deftest clear-muted-event-ids-drops-every-entry
  (when (and (exists? js/window) (.-localStorage js/window))
    (xray-setup!)
    (frame-dispatch [:rf.xray/mute-event-id :a])
    (frame-dispatch [:rf.xray/mute-event-id :b])
    (frame-dispatch [:rf.xray/clear-muted-event-ids])
    (is (= #{} (frame-sub [:rf.xray/muted-event-ids])))
    (is (= #{} (spine-filters/load)))))

;; -------------------------------------------------------------------------
;; (5) Hydration
;; -------------------------------------------------------------------------

(deftest hydrate-lifts-localstorage-into-slot
  (when (and (exists? js/window) (.-localStorage js/window))
    ;; Pre-seed localStorage BEFORE registry install so hydrate-on-mount
    ;; lifts the value.
    (spine-filters/save! #{:auth/login :user/mouse-move})
    (registry/reset-for-test!)
    (xray-setup!)
    (is (= #{:auth/login :user/mouse-move}
           (frame-sub [:rf.xray/muted-event-ids])))
    (spine-filters/clear-raw!)))

(deftest hydrate-empty-localstorage-leaves-slot-empty
  (spine-filters/clear-raw!)
  (registry/reset-for-test!)
  (xray-setup!)
  (is (= #{} (frame-sub [:rf.xray/muted-event-ids]))
      "no localStorage → empty slot (first-session honesty)"))

;; -------------------------------------------------------------------------
;; (6) Filtered-cascades composition
;; -------------------------------------------------------------------------

(deftest muting-strips-rows-from-filtered-event-bundles
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:user/mouse-move]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:order/submit]))
  ;; Sanity — all three rows present pre-mute.
  (let [before (frame-sub [:rf.xray/filtered-event-bundles])]
    (is (= 3 (count before))))
  ;; Mute :user/mouse-move.
  (frame-dispatch [:rf.xray/mute-event-id :user/mouse-move])
  (let [after (frame-sub [:rf.xray/filtered-event-bundles])
        ids   (mapv (fn [c] (first (:event c))) after)]
    (is (= 2 (count after)))
    (is (= [:auth/login :order/submit] ids)
        ":user/mouse-move stripped from filtered-event-bundles")))

(deftest muting-strips-rows-from-l2-event-list
  (xray-setup!)
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:user/mouse-move]))
  (trace-collector/seed-trace-for-test! (dispatch-trace-ev 3 [:order/submit]))
  (rf/with-frame :rf/xray
    ;; All three rows render pre-mute.
    (let [tree (shell/shell-view)]
      (is (some? (find-by-testid tree "rf-xray-event-row-1")))
      (is (some? (find-by-testid tree "rf-xray-event-row-2")))
      (is (some? (find-by-testid tree "rf-xray-event-row-3"))))
    (rf/dispatch-sync [:rf.xray/mute-event-id :user/mouse-move])
    ;; Row 2 (:user/mouse-move) disappears.
    (let [tree (shell/shell-view)]
      (is (some? (find-by-testid tree "rf-xray-event-row-1")))
      (is (nil? (find-by-testid tree "rf-xray-event-row-2"))
          "muted row stripped from L2 list")
      (is (some? (find-by-testid tree "rf-xray-event-row-3"))))))

;; -------------------------------------------------------------------------
;; (7) Row context menu open / close state
;; -------------------------------------------------------------------------

(deftest open-row-context-menu-event-writes-slot
  (xray-setup!)
  (is (nil? (frame-sub [:rf.xray/row-context-menu])))
  (frame-dispatch [:rf.xray/open-row-context-menu
                   {:event-id :user/mouse-move :x 100 :y 200}])
  (is (= {:event-id :user/mouse-move :x 100 :y 200}
         (frame-sub [:rf.xray/row-context-menu]))))

(deftest open-row-context-menu-with-nil-event-id-is-noop
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-row-context-menu
                   {:event-id nil :x 0 :y 0}])
  (is (nil? (frame-sub [:rf.xray/row-context-menu]))
      "nil event-id refuses to open the menu — defensive guard"))

(deftest close-row-context-menu-event-clears-slot
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-row-context-menu
                   {:event-id :a :x 10 :y 20}])
  (frame-dispatch [:rf.xray/close-row-context-menu])
  (is (nil? (frame-sub [:rf.xray/row-context-menu]))))

(deftest row-context-menu-renders-mute-and-hide-items
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-row-context-menu
                   {:event-id :user/mouse-move :x 100 :y 200}])
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)
          menu (find-by-testid tree "rf-xray-row-context-menu")
          mute (find-by-testid tree "rf-xray-row-context-menu-mute")
          hide (find-by-testid tree "rf-xray-row-context-menu-hide-event-type")]
      (is (some? menu) "menu mounts when slot is set")
      (is (some? mute) "menu carries 'Mute' item")
      (is (some? hide) "menu carries 'Always hide…' item")
      (is (= ":user/mouse-move"
             (:data-rf-xray-event-id (second menu)))
          "menu carries the event-id for assertion"))))

;; -------------------------------------------------------------------------
;; (8) Mute manager modal
;; -------------------------------------------------------------------------

(deftest mute-manager-open-close
  (xray-setup!)
  (is (false? (frame-sub [:rf.xray/mute-manager-open?])))
  (frame-dispatch [:rf.xray/open-mute-manager])
  (is (true? (frame-sub [:rf.xray/mute-manager-open?])))
  (frame-dispatch [:rf.xray/close-mute-manager])
  (is (false? (frame-sub [:rf.xray/mute-manager-open?]))))

(deftest mute-manager-renders-muted-ids
  (xray-setup!)
  (frame-dispatch [:rf.xray/mute-event-id :a/x])
  (frame-dispatch [:rf.xray/mute-event-id :b/y])
  (frame-dispatch [:rf.xray/open-mute-manager])
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)
          dialog (find-by-testid tree "rf-xray-mute-manager-dialog")
          list   (find-by-testid tree "rf-xray-mute-manager-list")
          row-a  (find-by-testid tree "rf-xray-mute-manager-row-:a/x")
          row-b  (find-by-testid tree "rf-xray-mute-manager-row-:b/y")]
      (is (some? dialog) "manager dialog mounts")
      (is (some? list) "manager renders the list section")
      (is (some? row-a) "row for :a/x")
      (is (some? row-b) "row for :b/y"))))

(deftest mute-manager-empty-state
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-mute-manager])
  (rf/with-frame :rf/xray
    (let [tree  (shell/shell-view)
          empty (find-by-testid tree "rf-xray-mute-manager-empty")
          list  (find-by-testid tree "rf-xray-mute-manager-list")]
      (is (some? empty) "empty-state copy mounts when no ids muted")
      (is (nil? list) "list section absent when empty"))))

;; -------------------------------------------------------------------------
;; (9) L1 ribbon indicator
;; -------------------------------------------------------------------------

(deftest ribbon-mute-indicator-hidden-when-no-mutes
  (xray-setup!)
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)
          ind  (find-by-testid tree "rf-xray-ribbon-mute-indicator")]
      (is (nil? ind) "indicator absent when no event-ids muted"))))

(deftest ribbon-mute-indicator-shows-when-mute-set-nonempty
  (xray-setup!)
  (frame-dispatch [:rf.xray/mute-event-id :a])
  (frame-dispatch [:rf.xray/mute-event-id :b])
  (rf/with-frame :rf/xray
    (let [tree (shell/shell-view)
          ind  (find-by-testid tree "rf-xray-ribbon-mute-indicator")
          cnt  (find-by-testid tree "rf-xray-ribbon-mute-indicator-count")]
      (is (some? ind) "indicator mounts when mute set is non-empty")
      (is (some? cnt))
      (is (= "2" (nth cnt 2))
          "count text matches set size"))))

;; -------------------------------------------------------------------------
;; (10) End-to-end: open menu → click 'Mute' → row disappears
;; -------------------------------------------------------------------------

(deftest end-to-end-mute-from-context-menu
  (testing "open menu → click 'Mute' fires both the mute dispatch +
            the close-menu dispatch. We replace `re-frame.core/dispatch-impl`
            (the `^:no-doc` seam the row on-click's `rf/dispatch` macro call
            expands to) with a capturing fn so the test sees the dispatch
            payloads without depending on async drain timing; the real flow
            replays both dispatches via dispatch-sync to assert the
            downstream effects (row dropped, menu closed, indicator
            visible)."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:auth/login]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:user/mouse-move]))
    (let [dispatches (atom [])]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/open-row-context-menu
                           {:event-id :user/mouse-move :x 50 :y 50}])
        ;; Capture dispatches from the on-click handler.
        (with-redefs [rf/dispatch-impl (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree     (shell/shell-view)
                mute-btn (find-by-testid tree "rf-xray-row-context-menu-mute")
                handler  (:on-click (second mute-btn))]
            (is (fn? handler) "'Mute' button has on-click handler")
            (when handler (handler #js {:stopPropagation (fn [] nil)}))))
        ;; Both dispatches captured.
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/mute-event-id (first ev))
                         (= :user/mouse-move (second ev))))
                  @dispatches)
            "click fires :rf.xray/mute-event-id")
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/close-row-context-menu (first ev))))
                  @dispatches)
            "click also closes the menu")
        ;; Replay both via dispatch-sync to assert downstream effects.
        (rf/dispatch-sync [:rf.xray/mute-event-id :user/mouse-move])
        (rf/dispatch-sync [:rf.xray/close-row-context-menu])
        (let [tree (shell/shell-view)]
          (is (some? (find-by-testid tree "rf-xray-event-row-1")))
          (is (nil? (find-by-testid tree "rf-xray-event-row-2"))
              "muted row dropped from L2")
          (is (nil? (find-by-testid tree "rf-xray-row-context-menu"))
              "menu closed after click"))
        (let [tree (shell/shell-view)
              ind  (find-by-testid tree "rf-xray-ribbon-mute-indicator")]
          (is (some? ind) "ribbon mute indicator visible"))))))
