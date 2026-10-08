(ns day8.re-frame2-xray.static.machines.browse-list-cljs-test
  "CLJS render tests for the Static Machines browse-list.

  ## What's under test

    1. Sort button label reflects the active axis.
    2. The selected row's aria-selected, the visible / total count line
       and the no-results state.
    3. The search input's keystroke and Escape handlers dispatch the
       Machines search events.
    4. A row's `→ Dynamic` chip click dispatches the JUMP for that row.
    5. Row and pip React keys ride the attribute map."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-helpers.static-machines-tree
             :as machines-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset (plain-atom + `:all` tier,
  ;; which resets the trace-collector rings too); `:post-reset` carries the
  ;; suppressed-count + static-persistence + machines-localStorage slate.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!)
                   (ls/clear!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray (rf/dispatch-sync ev)))

(defn- seed-machines! [ids]
  (frame-dispatch [:rf.xray/set-registered-machines-override-for-test
                   (vec ids)]))

(defn- seed-snapshots! [snaps]
  (frame-dispatch [:rf.xray/set-machine-snapshots-override-for-test snaps]))

(defn- testid-text
  "The text under `testid` in the rendered panel. Call inside a frame scope."
  [testid]
  (rf.test-helpers/text-content
    (rf.test-helpers/find-by-testid (machines-tree/panel-tree) testid)))

;; -------------------------------------------------------------------------
;; Sort button label reflects the active axis
;; -------------------------------------------------------------------------

(deftest sort-button-label-tracks-the-active-axis
  (xray-setup!)
  (seed-machines! [:m/a])
  (frame-dispatch [:rf.xray.static.machines/cycle-sort])
  (rf/with-frame :rf/xray
    (is (= "Sort: States" (testid-text "rf-xray-static-machines-sort")))))

;; -------------------------------------------------------------------------
;; Selection
;; -------------------------------------------------------------------------

(deftest selected-row-carries-aria-selected-true
  ;; :m/b, not the default-selected first row, so the select event is what
  ;; moves the mark.
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (frame-dispatch [:rf.xray.static.machines/select :m/b])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (= ["false" "true"]
             (mapv #(:aria-selected (second (rf.test-helpers/find-by-testid tree %)))
                   ["rf-xray-static-machines-row-a"
                    "rf-xray-static-machines-row-b"]))))))

;; -------------------------------------------------------------------------
;; Count line shows total vs visible
;; -------------------------------------------------------------------------

(deftest toolbar-count-shows-visible-and-total
  (xray-setup!)
  (seed-machines! [:foo/a :foo/b :bar/c])
  (rf/with-frame :rf/xray
    (is (= "3 machines" (testid-text "rf-xray-static-machines-count"))))
  (frame-dispatch [:rf.xray.static.machines/set-search "foo"])
  (rf/with-frame :rf/xray
    (is (= "2 / 3" (testid-text "rf-xray-static-machines-count")))))

;; -------------------------------------------------------------------------
;; No-results state
;; -------------------------------------------------------------------------

(deftest no-results-state-when-search-misses
  (xray-setup!)
  (seed-machines! [:foo/a :foo/b])
  (frame-dispatch [:rf.xray.static.machines/set-search "nonexistent"])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-no-results"))))))

;; -------------------------------------------------------------------------
;; Handlers pulled off the tree and fired with a RECORDING dispatcher
;; -------------------------------------------------------------------------
;;
;; `browse-list-tree` takes the dispatcher the boundary would bind, so a
;; recording fn in its place shows exactly which events a handler sends.

(defn- recording-tree
  "The browse list rendered with a dispatcher that records every event
  into the returned `seen` atom. Call inside a frame scope."
  []
  (let [seen (atom [])]
    {:seen seen
     :tree (machines-tree/browse-list-tree #(swap! seen conj %))}))

(deftest search-input-keystroke-and-escape-dispatch-the-search-events
  (xray-setup!)
  (seed-machines! [:foo/a :bar/b])
  (rf/with-frame :rf/xray
    (let [{:keys [seen tree]} (recording-tree)
          input (rf.test-helpers/find-by-testid
                  tree "rf-xray-static-machines-search-input")
          {:keys [on-change on-key-down]} (second input)]
      (on-change #js {:target #js {:value "foo"}})
      (is (= [[:rf.xray.static.machines/set-search "foo"]] @seen)
          "a keystroke dispatches set-search carrying the input's value")
      (reset! seen [])
      (on-key-down #js {:key "Escape"})
      (is (= [[:rf.xray.static.machines/clear-search]] @seen)
          "Escape dispatches clear-search")
      (reset! seen [])
      (on-key-down #js {:key "a"})
      (is (= [] @seen) "any other key dispatches nothing"))))

(deftest row-jump-chip-click-dispatches-the-jump-for-its-row
  ;; The :m/b row, not the default-selected first row.
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (rf/with-frame :rf/xray
    (let [{:keys [seen tree]} (recording-tree)
          chip    (rf.test-helpers/find-by-testid
                    tree "rf-xray-static-machines-row-jump-b")
          stopped (atom false)]
      ((:on-click (second chip)) #js {:stopPropagation #(reset! stopped true)})
      (is (= [[:rf.xray/set-mode :dynamic]
              [:rf.xray/select-tab :machines]
              [:rf.xray/select-machine-id :m/b]]
             @seen)
          "the click flips to Dynamic, opens the Machines tab and selects
           THIS row's machine")
      (is (true? @stopped)
          "and stops propagation, so the enclosing row button's select does
           not also fire"))))

;; -------------------------------------------------------------------------
;; React keys ride the ATTRIBUTE MAP, never Clojure metadata
;; -------------------------------------------------------------------------
;;
;; Fresco's codec reads `:key` from the attribute map and reads Clojure
;; metadata nowhere, so a `^{:key …}` would reach React as nothing. This
;; row asserts the codec-readable spelling; `(meta …)` would read nil at a
;; correct site too. The browser lane's W5 shows the row key reaching
;; React; the positional pip key cannot be witnessed that way.

(defn- vectors-under [tree]
  (filter vector? (tree-seq (some-fn vector? seq?) seq tree)))

(deftest row-and-pip-keys-ride-the-attribute-map-not-metadata
  (xray-setup!)
  (seed-machines! [:foo/alpha :foo/beta])
  (seed-snapshots! {:foo/alpha {:state :idle}})
  (rf/with-frame :rf/xray
    (let [tree      (machines-tree/browse-list-tree)
          rows-node (rf.test-helpers/find-by-testid
                      tree "rf-xray-static-machines-rows")
          fragments (filterv #(= :<> (first %)) (vectors-under rows-node))
          pips-node (rf.test-helpers/find-by-testid
                      tree "rf-xray-static-machines-row-pips")
          pip-spans (filterv #(and (= :span (first %))
                                   (map? (second %)))
                             (rest pips-node))]
      (is (= [":foo/alpha" ":foo/beta"] (mapv #(:key (second %)) fragments))
          "one row fragment per machine, keyed `(str machine-id)` in its
           attribute map")
      (is (= [0] (mapv #(:key (second %)) pip-spans))
          "the one live instance's pip is keyed by its index in its
           attribute map"))))
