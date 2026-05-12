(ns re-frame.story-ui-test
  "JVM tests for Stage 4 (rf2-ekai) pure logic.

  The shell's reactive layer (Reagent ratom, component lifecycles,
  Reagent renders) is CLJS-only — those tests live in
  `re-frame.story-ui-cljs-test`. JVM-side we cover every pure-data
  helper in the `re-frame.story.ui.*` namespaces:

  - shell state transitions (selection, filters, fingerprints, overrides)
  - sidebar tag collection + variant grouping
  - workspace layout resolution (:grid, :variants-grid, :prose, :tabs)

  These tests run alongside the Stage 2 + 3 JVM corpus via
  `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as frame]
            [re-frame.machines        :as machines]
            [re-frame.registrar       :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story           :as story]
            [re-frame.story.config    :as config]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.state  :as state]
            [re-frame.story.ui.workspace :as workspace]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-fixture [test-fn]
  ;; Mirror the Stage 3 runtime test fixture — story/clear-all! drops
  ;; the side-table; framework registrar is wiped + the machines ns is
  ;; reloaded to re-register its framework-shipped sub; canonical
  ;; vocabulary is reinstalled so :tag membership validates correctly.
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-counters!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-fixture)

;; ---- pure shell-state helpers -------------------------------------------

(deftest default-shell-state-shape
  (testing "default-shell-state carries the v1 slots"
    (let [s state/default-shell-state]
      (is (nil? (:selected-variant s)))
      (is (nil? (:selected-workspace s)))
      (is (= #{} (:tag-filter s)))
      (is (= [] (:active-modes s)))
      (is (= {} (:cell-overrides s)))
      (is (= :reagent (:substrate s)))
      (is (= 0 (:hot-reload-tick s))))))

(deftest select-variant-transitions
  (testing "select-variant + select-workspace mutate the state"
    (let [s  state/default-shell-state
          s1 (state/select-variant s :story.a/x)
          s2 (state/select-workspace s1 :Workspace.demo/y)]
      (is (= :story.a/x (:selected-variant s1)))
      (is (= :Workspace.demo/y (:selected-workspace s2)))
      (is (= :story.a/x (:selected-variant s2))))))

(deftest toggle-tag-filter-pure
  (testing "toggle-tag-filter adds and removes"
    (let [s  state/default-shell-state
          s1 (state/toggle-tag-filter s :dev)
          s2 (state/toggle-tag-filter s1 :dev)]
      (is (= #{:dev} (:tag-filter s1)))
      (is (= #{} (:tag-filter s2))))))

(deftest set-active-modes-pure
  (testing "set-active-modes replaces"
    (let [s  state/default-shell-state
          s1 (state/set-active-modes s [:Mode.t/dark])]
      (is (= [:Mode.t/dark] (:active-modes s1))))))

(deftest cell-overrides-pure
  (testing "set + clear overrides work on the pure map"
    (let [s  state/default-shell-state
          s1 (state/set-cell-override s :story.a/x :n 42)
          s2 (state/clear-cell-overrides s1 :story.a/x)]
      (is (= 42 (get-in s1 [:cell-overrides :story.a/x :n])))
      (is (nil? (get-in s2 [:cell-overrides :story.a/x]))))))

(deftest hot-reload-tick-monotonic
  (testing "bump-hot-reload-tick increments each call"
    (let [s state/default-shell-state]
      (is (= 0 (:hot-reload-tick s)))
      (is (= 1 (-> s state/bump-hot-reload-tick :hot-reload-tick)))
      (is (= 2 (-> s state/bump-hot-reload-tick
                   state/bump-hot-reload-tick :hot-reload-tick))))))

(deftest record-fingerprints-roundtrip
  (testing "record-fingerprints stores the per-variant map"
    (let [s  state/default-shell-state
          s1 (state/record-fingerprints s :story.a/x {:dec/foo 0xdeadbeef})]
      (is (= {:dec/foo 0xdeadbeef}
             (get-in s1 [:fingerprints :story.a/x]))))))

(deftest pin-snapshot-appends
  (testing "pin-snapshot appends a labelled marker"
    (let [s  state/default-shell-state
          s1 (state/pin-snapshot s :story.a/x "checkpoint" 5)
          s2 (state/pin-snapshot s1 :story.a/x "later" 11)]
      (is (= 2 (count (get-in s2 [:pinned-snapshots :story.a/x]))))
      (is (= 5 (-> s2 :pinned-snapshots :story.a/x first :epoch-id))))))

(deftest panel-visibility-toggle
  (testing "toggle-panel flips a panel's visibility"
    (let [s  state/default-shell-state
          s1 (state/toggle-panel s :trace)]
      (is (= false (get-in s1 [:panel-visibility :trace]))))))

;; ---- mode-tabs (rf2-9hc8) ------------------------------------------------

(deftest mode-tabs-canonical-set
  (testing "the three canonical mode tabs ship in stable order"
    (is (= [:dev :docs :test] state/mode-tabs)))
  (testing "every mode-tab has a human label"
    (doseq [t state/mode-tabs]
      (is (string? (get state/mode-tab-labels t))
          (str "missing label for " t)))))

(deftest mode-tab-default-is-dev
  (testing "the default mode-tab is :dev (Story v1 canvas)"
    (is (= :dev state/default-mode-tab)))
  (testing "active-mode-tab falls back to :dev for unknown variants"
    (is (= :dev (state/active-mode-tab state/default-shell-state :no/such-variant)))))

(deftest valid-mode-tab?-rejects-noise
  (is (state/valid-mode-tab? :dev))
  (is (state/valid-mode-tab? :docs))
  (is (state/valid-mode-tab? :test))
  (is (not (state/valid-mode-tab? :canvas)))
  (is (not (state/valid-mode-tab? :nonsense)))
  (is (not (state/valid-mode-tab? nil)))
  (is (not (state/valid-mode-tab? "test"))))

(deftest set-active-mode-tab-roundtrip
  (testing "set-active-mode-tab records the per-variant selection"
    (let [s  state/default-shell-state
          s1 (state/set-active-mode-tab s :story.x/a :docs)]
      (is (= :docs (state/active-mode-tab s1 :story.x/a)))
      (is (= :dev  (state/active-mode-tab s1 :story.x/b))
          "other variants keep the default")))
  (testing "selections are independent per variant"
    (let [s  state/default-shell-state
          s1 (-> s
                 (state/set-active-mode-tab :story.x/a :docs)
                 (state/set-active-mode-tab :story.x/b :test))]
      (is (= :docs (state/active-mode-tab s1 :story.x/a)))
      (is (= :test (state/active-mode-tab s1 :story.x/b)))))
  (testing "an invalid tab leaves state untouched"
    (let [s  state/default-shell-state
          s1 (state/set-active-mode-tab s :story.x/a :nonsense)]
      (is (= s s1)))))

;; ---- pure filter + grouping ---------------------------------------------

(deftest filter-variants-empty-filter
  (testing "an empty filter passes everything through"
    (story/reg-variant :story.f/a {:tags #{:dev} :events []})
    (story/reg-variant :story.f/b {:tags #{:test} :events []})
    (let [vs (story-registrar/handlers :variant)]
      (is (= 2 (count (state/filter-variants vs #{})))))))

(deftest filter-variants-with-tag
  (testing "filter restricts to variants whose tags intersect"
    (story/reg-variant :story.f2/a {:tags #{:dev} :events []})
    (story/reg-variant :story.f2/b {:tags #{:test} :events []})
    (let [vs (story-registrar/handlers :variant)
          devs (state/filter-variants vs #{:dev})]
      (is (= 1 (count devs)))
      (is (contains? devs :story.f2/a)))))

(deftest group-variants-by-story-keeps-untagged
  (testing "variants with no story namespace still appear under their derived parent"
    (story/reg-variant :story.g/a {:events []})
    (story/reg-variant :story.g/b {:events []})
    (let [vs       (story-registrar/handlers :variant)
          grouped  (state/group-variants-by-story vs)
          entries  (into {} (map (juxt :story-id :variants) grouped))]
      (is (= 1 (count grouped)))
      (is (= 2 (count (get entries :story.g)))))))

(deftest parent-story-id-derivation
  (testing "parent-story-id mirrors args/parent-story-id"
    (is (= :story.foo (state/parent-story-id :story.foo/bar)))
    (is (nil? (state/parent-story-id :unqualified)))))

;; ---- workspace resolver --------------------------------------------------

(deftest grid-layout
  (testing ":grid produces variant cells in declared order"
    (let [cells (workspace/resolve-layout
                  :Workspace.x/y
                  {:layout :grid :variants [:story.a/x :story.b/y]})]
      (is (= 2 (count cells)))
      (is (every? #(= :variant (:type %)) cells))
      (is (= [:story.a/x :story.b/y]
             (mapv :variant-id cells))))))

(deftest tabs-layout
  (testing ":tabs resolves to the same cell shape as :grid"
    (let [cells (workspace/resolve-layout
                  :Workspace.t/x
                  {:layout :tabs :variants [:story.a/x]})]
      (is (= 1 (count cells)))
      (is (= :variant (-> cells first :type))))))

(deftest variants-grid-from-anchor
  (testing ":variants-grid enumerates variants of the anchor story"
    (story/reg-variant :story.vg/a {:events []})
    (story/reg-variant :story.vg/b {:events []})
    (story/reg-variant :story.other/x {:events []})
    (let [cells (workspace/resolve-layout
                  :Workspace.vg/all
                  {:layout :variants-grid})]
      (is (= 2 (count cells)))
      (is (every? #(= :variant (:type %)) cells)))))

(deftest variants-grid-explicit-story
  (testing ":variants-grid honours an explicit :story override"
    (story/reg-variant :story.vge/a {:events []})
    (story/reg-variant :story.vge/b {:events []})
    (let [cells (workspace/resolve-layout
                  :Workspace.other/all
                  {:layout :variants-grid :story :story.vge})]
      (is (= 2 (count cells))))))

(deftest prose-layout-interleaves
  (testing ":prose preserves :content order"
    (let [cells (workspace/resolve-layout
                  :Workspace.guide/intro
                  {:layout :prose
                   :content [{:type :prose   :body "first"}
                             {:type :variant :id   :story.a/x}
                             {:type :prose   :body "last"}]})]
      (is (= 3 (count cells)))
      (is (= :prose   (-> cells (nth 0) :type)))
      (is (= "first"  (-> cells (nth 0) :body)))
      (is (= :variant (-> cells (nth 1) :type)))
      (is (= :story.a/x (-> cells (nth 1) :variant-id)))
      (is (= :prose   (-> cells (nth 2) :type)))
      (is (= "last"   (-> cells (nth 2) :body))))))

(deftest custom-layout-resolves
  (testing ":custom layout emits a single :custom cell"
    (let [cells (workspace/resolve-layout
                  :Workspace.c/x
                  {:layout :custom :render :app/custom-view})]
      (is (= 1 (count cells)))
      (is (= :custom (-> cells first :type)))
      (is (= :app/custom-view (-> cells first :render))))))

(deftest unknown-layout-empty
  (testing "unknown layouts degrade gracefully"
    (is (= [] (workspace/resolve-layout :Workspace.x/y {:layout :weird})))))
