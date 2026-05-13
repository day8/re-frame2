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
            [re-frame.story.ui.docs   :as docs]
            [re-frame.story.ui.state  :as state]
            [re-frame.story.ui.test-mode :as test-mode]
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

;; ---- docs mode (rf2-rodx) -----------------------------------------------

(deftest docs-parent-story-id
  (testing "parent-story-id mirrors args/parent-story-id"
    (is (= :story.foo (docs/parent-story-id :story.foo/bar)))
    (is (nil? (docs/parent-story-id :no-namespace)))
    (is (nil? (docs/parent-story-id nil)))))

(deftest docs-variant-tags-falls-back-to-story
  (testing "variant-tags reads variant :tags first"
    (story/reg-story :story.t1
      {:doc "parent" :tags #{:dev :docs}})
    (story/reg-variant :story.t1/a
      {:tags #{:dev :test} :events []})
    (is (= [:dev :test] (docs/variant-tags :story.t1/a))))
  (testing "variant-tags falls back to the parent story when the variant has none"
    (story/reg-story :story.t2
      {:doc "parent" :tags #{:dev :docs}})
    (story/reg-variant :story.t2/a {:events []})
    (is (= [:dev :docs] (docs/variant-tags :story.t2/a)))))

(deftest docs-args-rows-pulls-doc-from-argtypes
  (testing "args-rows surfaces :doc from the variant's :argtypes entry"
    (story/reg-variant :story.a/x
      {:args     {:label "Total" :count 0}
       :argtypes {:label {:doc "The cell label"}}
       :events   []})
    (let [rows  (docs/args-rows :story.a/x {:label "Total" :count 0})
          by-k  (into {} (map (juxt :key identity)) rows)]
      (is (= 2 (count rows)))
      (is (= "The cell label" (:doc (get by-k :label))))
      (is (nil? (:doc (get by-k :count))))
      (is (= "Total" (:value (get by-k :label))))))
  (testing "args-rows accepts the Storybook-compat :description key"
    (story/reg-variant :story.a/desc
      {:argtypes {:label {:description "Story-compat description slot"}}
       :events   []})
    (let [[row] (docs/args-rows :story.a/desc {:label "foo"})]
      (is (= "Story-compat description slot" (:doc row)))))
  (testing "args-rows accepts a bare-string :argtypes value"
    (story/reg-variant :story.a/bare
      {:argtypes {:label "Short doc"}
       :events   []})
    (let [[row] (docs/args-rows :story.a/bare {:label "foo"})]
      (is (= "Short doc" (:doc row)))))
  (testing "args-rows merges parent-story :argtypes under variant :argtypes"
    (story/reg-story :story.p
      {:argtypes {:label {:doc "from story"}
                  :count {:doc "from story"}}})
    (story/reg-variant :story.p/x
      {:argtypes {:label {:doc "from variant"}}
       :events   []})
    (let [rows  (docs/args-rows :story.p/x {:label "L" :count 0})
          by-k  (into {} (map (juxt :key identity)) rows)]
      (is (= "from variant" (:doc (get by-k :label))))
      (is (= "from story"   (:doc (get by-k :count)))))))

(deftest docs-decorator-rows-classifies-by-section
  (testing "decorator-rows splits the pack into hiccup / frame-setup / fx-override / error rows"
    (let [pack {:hiccup       [{:id :dec/h1 :body {:kind :hiccup :doc "outer"}}
                               {:id :dec/h2 :body {:kind :hiccup}}]
                :frame-setup  [{:id :dec/fs :body {:kind :frame-setup :doc "init"}}]
                :fx-override  [{:id :dec/fx :body {:kind :fx-override}}]
                :errors       [{:id :dec/bad :reason "unknown :kind"}]
                :fingerprints {}}
          rows (docs/decorator-rows pack)]
      (is (= 5 (count rows)))
      (is (= [:hiccup :hiccup :frame-setup :fx-override :error]
             (mapv :section rows)))
      (is (= "outer" (-> rows (nth 0) :doc)))
      (is (= "unknown :kind" (-> rows (nth 4) :doc))))))

(deftest docs-parameter-rows-pulls-three-slots
  (testing "parameter-rows emits :modes / :substrates / :platforms only when non-empty"
    (story/reg-variant :story.p1/x
      {:substrates #{:reagent}
       :platforms  #{:client}
       :events     []})
    (let [rows  (docs/parameter-rows :story.p1/x)
          by-k  (into {} (map (juxt :key identity)) rows)]
      (is (= 2 (count rows)))
      (is (contains? by-k :substrates))
      (is (contains? by-k :platforms))
      (is (not (contains? by-k :modes)))))
  (testing "parameter-rows falls back to the parent story's slots"
    (story/reg-story :story.p2
      {:substrates #{:reagent :uix}
       :platforms  #{:client}})
    (story/reg-variant :story.p2/x {:events []})
    (let [rows (docs/parameter-rows :story.p2/x)
          by-k (into {} (map (juxt :key identity)) rows)]
      (is (= #{:reagent :uix} (:value (get by-k :substrates))))
      (is (= #{:client}       (:value (get by-k :platforms)))))))

(deftest docs-prose-for-variant
  (testing "prose-for-variant returns workspace prose blocks that reference the variant"
    (story/reg-variant :story.d/x {:events []})
    (story/reg-variant :story.d/y {:events []})
    (story/reg-workspace :Workspace.d/intro
      {:layout  :prose
       :content [{:type :prose   :body "## How it works"}
                 {:type :variant :id   :story.d/x}
                 {:type :prose   :body "Footer note."}]})
    (let [blocks (docs/prose-for-variant :story.d/x)]
      (is (= 2 (count blocks)))
      (is (= "## How it works" (-> blocks first :body)))
      (is (= :Workspace.d/intro (-> blocks first :workspace-id))))
    (testing "a variant the workspace doesn't reference picks up nothing"
      (is (= [] (docs/prose-for-variant :story.d/y)))))
  (testing "non-prose layouts are ignored entirely"
    (story/reg-variant :story.dg/x {:events []})
    (story/reg-workspace :Workspace.dg/grid
      {:layout :grid :variants [:story.dg/x]})
    (is (= [] (docs/prose-for-variant :story.dg/x))))
  (testing "prose blocks ride through in source order across multiple workspaces"
    (story/reg-variant :story.dm/x {:events []})
    (story/reg-workspace :Workspace.dm/a
      {:layout  :prose
       :content [{:type :prose   :body "alpha"}
                 {:type :variant :id   :story.dm/x}]})
    (story/reg-workspace :Workspace.dm/b
      {:layout  :prose
       :content [{:type :variant :id   :story.dm/x}
                 {:type :prose   :body "beta"}]})
    (let [bodies (mapv :body (docs/prose-for-variant :story.dm/x))]
      ;; Sorted by workspace id (alphabetic) then content order — :a/a
      ;; before :b/b.
      (is (= ["alpha" "beta"] bodies)))))

;; ---- test mode (rf2-qmjo) -----------------------------------------------

(deftest test-mode-parent-story-id
  (testing "parent-story-id mirrors the docs helper"
    (is (= :story.foo (test-mode/parent-story-id :story.foo/bar)))
    (is (nil? (test-mode/parent-story-id :no-namespace)))
    (is (nil? (test-mode/parent-story-id nil)))))

(deftest test-mode-variant-has-tests?-checks-play-slot
  (testing "variant-has-tests? is false when :play is absent or empty"
    (story/reg-variant :story.tm/empty {:events []})
    (story/reg-variant :story.tm/empty-play {:events [] :play []})
    (is (not (test-mode/variant-has-tests? :story.tm/empty)))
    (is (not (test-mode/variant-has-tests? :story.tm/empty-play))))
  (testing "variant-has-tests? is true when :play carries any event"
    (story/reg-variant :story.tm/has
      {:events [] :play [[:rf.assert/path-equals [:count] 0]]})
    (is (test-mode/variant-has-tests? :story.tm/has)))
  (testing "variant-has-tests? returns false for an unknown variant-id"
    (is (not (test-mode/variant-has-tests? :story.tm/unknown)))))

(deftest test-mode-aggregate-summary-counts-pass-fail-skip
  (testing "aggregate-summary tallies passed / failed / skipped"
    (let [s (test-mode/aggregate-summary
              [{:assertion :rf.assert/path-equals :passed? true}
               {:assertion :rf.assert/path-equals :passed? false}
               {:assertion :rf.assert/sub-equals  :passed? true}
               {:assertion :rf.assert/skipped     :passed? false}])]
      (is (= 4 (:total s)))
      (is (= 2 (:passed s)))
      (is (= 1 (:failed s)))
      (is (= 1 (:skipped s)))
      (is (false? (:all-passed? s)))))
  (testing "aggregate-summary's :all-passed? is true only when every
            non-skipped record passed AND no records were skipped"
    (let [all-pass (test-mode/aggregate-summary
                     [{:assertion :rf.assert/path-equals :passed? true}
                      {:assertion :rf.assert/sub-equals  :passed? true}])]
      (is (true?  (:all-passed? all-pass)))
      (is (= 0   (:failed all-pass)))
      (is (= 0   (:skipped all-pass))))
    (let [with-skip (test-mode/aggregate-summary
                      [{:assertion :rf.assert/path-equals :passed? true}
                       {:assertion :rf.assert/skipped     :passed? false}])]
      (is (false? (:all-passed? with-skip))
          "a skipped record disqualifies :all-passed?")))
  (testing "aggregate-summary on an empty vector returns zeros + :all-passed? false"
    (let [s (test-mode/aggregate-summary [])]
      (is (= 0 (:total s)))
      (is (= 0 (:passed s)))
      (is (= 0 (:failed s)))
      (is (= 0 (:skipped s)))
      (is (false? (:all-passed? s))
          "zero records = not 'all passed' (the variant ran nothing)")))
  (testing "aggregate-summary tolerates nil"
    (let [s (test-mode/aggregate-summary nil)]
      (is (= 0 (:total s)))
      (is (false? (:all-passed? s))))))

(deftest test-mode-assertion-row-projection
  (testing "assertion-row maps a passing record to :status :pass"
    (let [row (test-mode/assertion-row
                {:assertion :rf.assert/path-equals
                 :payload   [[:count] 7]
                 :passed?   true
                 :expected  7
                 :actual    7})]
      (is (= :pass (:status row)))
      (is (= :rf.assert/path-equals (:assertion row)))
      (is (re-find #":rf.assert/path-equals" (:label row)))
      (is (re-find #"\[\[:count\] 7\]" (:label row)))))
  (testing "assertion-row maps a failing record to :status :fail + surfaces detail"
    (let [row (test-mode/assertion-row
                {:assertion :rf.assert/path-equals
                 :payload   [[:count] 7]
                 :passed?   false
                 :expected  7
                 :actual    0
                 :reason    "mismatch"
                 :source    {:file "stories.cljs" :line 42}})]
      (is (= :fail (:status row)))
      (let [d (:detail row)]
        (is (= 7   (:expected d)))
        (is (= 0   (:actual   d)))
        (is (= "mismatch" (:reason d)))
        (is (= {:file "stories.cljs" :line 42} (:source d))))))
  (testing "assertion-row maps :rf.assert/skipped to :status :skip"
    (let [row (test-mode/assertion-row
                {:assertion :rf.assert/skipped :passed? false})]
      (is (= :skip (:status row)))))
  (testing "assertion-row reads :source-coord as a fallback for :source"
    (let [row (test-mode/assertion-row
                {:assertion :rf.assert/sub-equals
                 :passed?   false
                 :source-coord {:file "f.cljs" :line 9}})]
      (is (= {:file "f.cljs" :line 9} (-> row :detail :source)))))
  (testing "assertion-row's :label omits an empty payload"
    (let [row (test-mode/assertion-row
                {:assertion :rf.assert/no-warnings
                 :payload   []
                 :passed?   true})]
      (is (= ":rf.assert/no-warnings" (:label row)))))
  (testing "assertion-row tolerates a fully-empty record without throwing"
    (let [row (test-mode/assertion-row nil)]
      (is (= :fail (:status row))
          "nil record defaults to fail — a missing passed? slot can't be 'pass'")
      (is (some? (:label row))))))

(deftest test-mode-format-elapsed-ms
  (testing "format-elapsed-ms switches at the 1s boundary"
    (is (= "0 ms"   (test-mode/format-elapsed-ms 0)))
    (is (= "12 ms"  (test-mode/format-elapsed-ms 12)))
    (is (= "999 ms" (test-mode/format-elapsed-ms 999)))
    (is (= "1.0 s"  (test-mode/format-elapsed-ms 1000)))
    (is (= "1.2 s"  (test-mode/format-elapsed-ms 1234))))
  (testing "format-elapsed-ms tolerates nil / non-numbers / negatives"
    (is (= "" (test-mode/format-elapsed-ms nil)))
    (is (= "" (test-mode/format-elapsed-ms "no")))
    (is (= "" (test-mode/format-elapsed-ms -5)))))

(deftest test-mode-format-timestamp-ms
  (testing "format-timestamp-ms emits an HH:mm:ss-shaped string"
    (let [s (test-mode/format-timestamp-ms (System/currentTimeMillis))]
      (is (re-matches #"\d{2}:\d{2}:\d{2}" s))))
  (testing "format-timestamp-ms returns empty string for non-numbers"
    (is (= "" (test-mode/format-timestamp-ms nil)))
    (is (= "" (test-mode/format-timestamp-ms "no")))))

;; ---- step-through scrubber (rf2-lc36w) ----------------------------------

(deftest test-mode-play-step-label-renders-event-id
  (testing "play-step-label stringifies the event-id only"
    (is (= ":auth/email-changed"
           (test-mode/play-step-label [:auth/email-changed "alice@example.com"])))
    (is (= ":rf.assert/path-equals"
           (test-mode/play-step-label [:rf.assert/path-equals [[:count] 7]]))))
  (testing "play-step-label tolerates nil / malformed input"
    (is (= "" (test-mode/play-step-label nil)))
    (is (= "" (test-mode/play-step-label [])))
    (is (= "" (test-mode/play-step-label "not-a-vec")))))

(deftest test-mode-play-step-statuses-maps-events-to-status
  (testing "non-assertion events get :event status; assertion events get :pass/:fail from records"
    (let [play       [[:auth/email-changed "alice"]
                      [:auth/submit]
                      [:rf.assert/path-equals [[:user :email] "alice"]]
                      [:rf.assert/path-equals [[:user :submitted?] true]]]
          assertions [{:assertion :rf.assert/path-equals :passed? true}
                      {:assertion :rf.assert/path-equals :passed? false}]
          out        (test-mode/play-step-statuses play assertions)]
      (is (= 4 (count out)) "one row per :play event")
      (is (= [:event :event :pass :fail] (mapv :status out)))
      (is (= [0 1 2 3] (mapv :index out)))
      (is (= ":auth/email-changed" (-> out (nth 0) :label)))
      (is (= ":rf.assert/path-equals" (-> out (nth 2) :label)))
      (is (= [:auth/email-changed "alice"] (-> out (nth 0) :event)))))
  (testing "play-step-statuses handles :rf.assert/skipped as :skip"
    (let [play       [[:rf.assert/skipped]]
          assertions [{:assertion :rf.assert/skipped :passed? false}]
          out        (test-mode/play-step-statuses play assertions)]
      (is (= [:skip] (mapv :status out)))))
  (testing "play-step-statuses tolerates fewer records than assertion events (fail-fast gap)"
    ;; assertion event with no matching record renders :fail so the user sees the gap.
    (let [play       [[:rf.assert/path-equals [[:k] 1]]
                      [:rf.assert/path-equals [[:k] 2]]]
          assertions [{:assertion :rf.assert/path-equals :passed? true}]
          out        (test-mode/play-step-statuses play assertions)]
      (is (= [:pass :fail] (mapv :status out)))))
  (testing "play-step-statuses handles empty inputs"
    (is (= [] (test-mode/play-step-statuses [] [])))
    (is (= [] (test-mode/play-step-statuses nil nil)))))

(deftest test-mode-epoch-id-slice-trailing-window
  (testing "epoch-id-slice returns the trailing n epoch-ids"
    (let [history [{:epoch-id 10} {:epoch-id 11} {:epoch-id 12}
                   {:epoch-id 13} {:epoch-id 14}]]
      (is (= [12 13 14] (test-mode/epoch-id-slice history 3)))
      (is (= [10 11 12 13 14] (test-mode/epoch-id-slice history 5)))
      (is (= [14] (test-mode/epoch-id-slice history 1)))))
  (testing "epoch-id-slice short-circuits to [] when history is too small"
    ;; production / ring-buffer-trimmed contexts: degrade gracefully
    ;; rather than mis-mapping play-steps onto wrong epochs.
    (is (= [] (test-mode/epoch-id-slice [{:epoch-id 1}] 3))))
  (testing "epoch-id-slice tolerates nil history and non-positive n"
    (is (= [] (test-mode/epoch-id-slice nil 3)))
    (is (= [] (test-mode/epoch-id-slice [{:epoch-id 1}] 0)))
    (is (= [] (test-mode/epoch-id-slice [{:epoch-id 1}] -2)))))
