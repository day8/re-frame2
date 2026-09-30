(ns day8.re-frame2-xray.static.machines.browse-list-cljs-test
  "CLJS render tests for the Static Machines browse-list.

  ## What's under test

    1. Pip cluster — pip-cap dots inline, '>cap N live' textual count
       beyond. Silent for zero.
    2. Sort button label reflects the active axis.
    3. Listbox ARIA, the selected row's aria-selected, the visible /
       total count line and the no-results state.
    4. The search input's keystroke and Escape handlers dispatch the
       Machines search events.
    5. A row's `→ Dynamic` chip click dispatches the JUMP for that row.
    6. Row and pip React keys ride the attribute map."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.helpers :as h]
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

;; Tests call `rf.test-helpers/find-by-testid` directly; there is no Xray
;; walker facade. `hiccup-seq` (depth-first nodes over the expanded tree) is
;; not exposed by test-helpers, so it is a thin wrapper over
;; `rf.test-helpers/expand-tree` for the string-leaf extraction below.
(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree)))

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

(defn- seed-definitions! [defs]
  (frame-dispatch [:rf.xray/set-machine-definitions-override-for-test defs]))

;; -------------------------------------------------------------------------
;; Pip cluster
;; -------------------------------------------------------------------------

(deftest pip-cluster-zero-is-silent
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-snapshots! {}) ;; no live instances
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-row-pips"))
          "no pip cluster when live-count is zero"))))

;; -------------------------------------------------------------------------
;; Sort button label reflects the active axis
;; -------------------------------------------------------------------------

(deftest sort-button-label-tracks-the-active-axis
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          btn  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-sort")
          text (->> btn hiccup-seq (filter string?) (apply str))]
      (is (re-find #"Name" text) "default sort axis is Name")))
  (frame-dispatch [:rf.xray.static.machines/cycle-sort])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          btn  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-sort")
          text (->> btn hiccup-seq (filter string?) (apply str))]
      (is (re-find #"States" text)))))

;; -------------------------------------------------------------------------
;; Listbox ARIA
;; -------------------------------------------------------------------------

(deftest browse-list-uses-listbox-aria
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree   (machines-tree/panel-tree)
          rows-el (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-rows")
          attrs  (second rows-el)]
      (is (= "listbox" (:role attrs)))
      (is (string? (:aria-label attrs))))))

(deftest selected-row-carries-aria-selected-true
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          row-a (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-row-a")
          attrs (second row-a)]
      ;; Note: machine-id is :m/a so name = "a", testid suffix matches.
      (is (= "true" (:aria-selected attrs))))))

;; -------------------------------------------------------------------------
;; Count line shows total vs visible
;; -------------------------------------------------------------------------

(deftest toolbar-count-shows-visible-and-total
  (xray-setup!)
  (seed-machines! [:foo/a :foo/b :bar/c])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          count-el (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-count")
          text (->> count-el hiccup-seq (filter string?) (apply str))]
      (is (re-find #"3 machines" text))))
  (frame-dispatch [:rf.xray.static.machines/set-search "foo"])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          count-el (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-count")
          text (->> count-el hiccup-seq (filter string?) (apply str))]
      (is (re-find #"2 / 3" text)))))

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
      (is (fn? on-change) "PRECONDITION: the search input handles keystrokes")
      (when on-change (on-change #js {:target #js {:value "foo"}}))
      (is (= [[:rf.xray.static.machines/set-search "foo"]] @seen)
          "a keystroke dispatches set-search carrying the input's value")
      (reset! seen [])
      (is (fn? on-key-down) "the search input handles keydown")
      (when on-key-down (on-key-down #js {:key "Escape"}))
      (is (= [[:rf.xray.static.machines/clear-search]] @seen)
          "Escape dispatches clear-search")
      (reset! seen [])
      (when on-key-down (on-key-down #js {:key "a"}))
      (is (= [] @seen) "any other key dispatches nothing"))))

(deftest row-jump-chip-click-dispatches-the-jump-for-its-row
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (rf/with-frame :rf/xray
    (let [{:keys [seen tree]} (recording-tree)
          chip    (rf.test-helpers/find-by-testid
                    tree "rf-xray-static-machines-row-jump-b")
          stopped (atom false)]
      (is (some? chip)
          "PRECONDITION: the :m/b row — not the default-selected first row —
           carries its `→ Dynamic` chip")
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
;; Both of this pane's seqs key on the ATTRIBUTE MAP. Reader metadata —
;; `^{:key …}` on a vector literal — would not do: Reagent reads it, but
;; Fresco's codec reads `:key` from the ATTRIBUTE MAP and reads Clojure
;; metadata NOWHERE, so under a boundary the metadata spelling reaches
;; React as nothing at all.
;;
;; THIS ROW DOES NOT ASSERT WITH `meta`, and that is deliberate: `(meta …)`
;; reads nil at a CORRECT site too, because the key lives in the
;; attribute map — so a metadata assertion is hollow in BOTH directions,
;; green on broken code and red on correct code. It asserts the codec-readable
;; spelling instead. The other half of the evidence is the browser lane's
;; W5, which asserts the row key REACHES REACT by surviving a head removal;
;; the pip key cannot have a row like that, because its expression is
;; positional (`i`) and a positional key is indistinguishable from no key
;; under exactly that operation.

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
      ;; ---- the row seq ----
      (is (= 2 (count fragments))
          "NON-VACUITY: one keyed fragment per registered machine — a zero
           here would make every assertion below vacuous")
      (is (every? #(map? (second %)) fragments)
          "each row fragment carries an ATTRIBUTE MAP, which is the only
           place Fresco's codec looks for a key")
      (is (= #{":foo/alpha" ":foo/beta"}
             (set (map #(:key (second %)) fragments)))
          "and the keys are the domain-shaped expressions
           `(str machine-id)`, distinct within the seq")

      ;; ---- the pip seq ----
      (is (some? pips-node)
          "NON-VACUITY: the live machine's pip cluster is on screen, so the
           pip assertions below have something to read")
      (is (= 1 (count pip-spans))
          "one pip span for the one live instance")
      (is (every? #(contains? (second %) :key) pip-spans)
          "each pip carries `:key` in its attribute map rather than on
           reader metadata")
      (is (= [0] (mapv #(:key (second %)) pip-spans))
          "and the key expression is the positional index"))))
