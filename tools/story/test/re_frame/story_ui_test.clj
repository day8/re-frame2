(ns re-frame.story-ui-test
  "JVM tests for the Story shell's pure logic in the `re-frame.story.ui.*`
  namespaces; the reactive layer is covered by `re-frame.story-ui-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            ;; Loaded for its epoch tape: without it a run records no
            ;; effects, so `run-all-records-the-run-level-status`'s thrown fx
            ;; is invisible to the run's floor when this ns runs alone.
            [re-frame.epoch]
            [re-frame.frame           :as rf.frame]
            [re-frame.machines        :as rf.machines]
            [re-frame.registrar       :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story           :as rf.story]
            [re-frame.story.async     :as rf.story.async]
            [re-frame.story.config    :as rf.story.config]
            [re-frame.story.loaders   :as rf.story.loaders]
            [re-frame.story.predicates :as rf.story.predicates]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.runtime   :as rf.story.runtime]
            [re-frame.story.ui.command-palette :as rf.story.ui.command-palette]
            [re-frame.story.ui.docs   :as rf.story.ui.docs]
            [re-frame.story.ui.state  :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.pure :as rf.story.ui.test-mode.pure]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-fixture [test-fn]
  ;; Mirror the runtime test fixture — rf.story/clear-all! drops
  ;; the side-table; framework registrar is wiped + the machines ns is
  ;; reloaded to re-register its framework-shipped sub; canonical
  ;; vocabulary is reinstalled so :tag membership validates correctly.
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-fixture)

;; ---- pure shell-state helpers -------------------------------------------

(deftest default-shell-state-shape
  (is (= [nil nil #{} [] {} :reagent 0]
         ((juxt :selected-variant :selected-workspace :tag-filter :active-modes
                :cell-overrides :substrate :hot-reload-tick)
          rf.story.ui.state/default-shell-state))))

;; The sidebar's row clicks compose select-variant with select-workspace nil
;; (and the mirror), so neither mode is a one-way door.
(deftest variant-row-click-symmetric-clear-rf2-hscut
  (let [s0  rf.story.ui.state/default-shell-state
        sel (juxt :selected-variant :selected-workspace)]
    (is (= [:story.nav/v1 nil]
           (sel (-> s0
                    (rf.story.ui.state/select-workspace :Workspace.nav/all)
                    (rf.story.ui.state/select-variant :story.nav/v1)
                    (rf.story.ui.state/select-workspace nil)))))
    (is (= [nil :Workspace.nav/all]
           (sel (-> s0
                    (rf.story.ui.state/select-variant :story.nav/v1)
                    (rf.story.ui.state/select-workspace :Workspace.nav/all)
                    (rf.story.ui.state/select-variant nil)))))))

(deftest shell-state-setters
  (let [s0 rf.story.ui.state/default-shell-state]
    (is (= [#{:dev} #{}]
           [(:tag-filter (rf.story.ui.state/toggle-tag-filter s0 :dev))
            (-> s0
                (rf.story.ui.state/toggle-tag-filter :dev)
                (rf.story.ui.state/toggle-tag-filter :dev)
                :tag-filter)])
        "toggle-tag-filter adds then removes")
    (is (= [:Mode.t/dark] (:active-modes (rf.story.ui.state/set-active-modes s0 [:Mode.t/dark]))))
    (is (= 2 (-> s0
                 rf.story.ui.state/bump-hot-reload-tick
                 rf.story.ui.state/bump-hot-reload-tick
                 :hot-reload-tick)))
    (is (false? (get-in (rf.story.ui.state/toggle-panel s0 :controls)
                        [:panel-visibility :controls])))))

;; ---- repeater stable row-ids --------------------------------------------
;;
;; The controls-panel repeater keys each row on a stable per-entry id kept at
;; `[:rf.story/repeater-row-ids [variant-id path]]` in lockstep with the
;; entries vector; the CLJS suite covers the rendered hiccup keys.

(defn- row-ids [s] (rf.story.ui.state/repeater-row-ids s :story.x/v [:items]))
(defn- ensure-ids [s n] (rf.story.ui.state/ensure-repeater-row-ids s :story.x/v [:items] n))

(deftest repeater-row-ids-ensure-allocates-fresh-ids-rf2-c8kfy
  (let [s3 (ensure-ids rf.story.ui.state/default-shell-state 3)
        s4 (ensure-ids rf.story.ui.state/default-shell-state 4)]
    (testing "growing allocates fresh distinct ids from the counter"
      (is (= [3 true 3] [(count (row-ids s3)) (apply distinct? (row-ids s3))
                         (:rf.story/repeater-id-counter s3)])))
    (testing "shrinking truncates from the right, so visible rows keep their ids"
      (is (= (subvec (row-ids s4) 0 2) (row-ids (ensure-ids s4 2)))))
    (testing "an equal count is a no-op: no counter churn, no reallocation"
      (is (= s3 (ensure-ids s3 3))))))

(deftest repeater-row-ids-remove-mid-list-rf2-c8kfy
  (testing "remove drops the id at i and the survivors keep their identity — a
            renderer keying on index would shift React keys and leak focus and
            cursor onto neighbouring rows"
    (let [s4        (ensure-ids rf.story.ui.state/default-shell-state 4)
          [a _ c d] (row-ids s4)
          remove-at #(row-ids (rf.story.ui.state/remove-repeater-row-id s4 :story.x/v [:items] %))]
      (is (= [a c d] (remove-at 1)))
      (is (= (row-ids s4) (remove-at 5) (remove-at -1)) "out of range is a no-op")))
  (testing "append allocates a fresh id at the end"
    (let [s2 (ensure-ids rf.story.ui.state/default-shell-state 2)
          s3 (rf.story.ui.state/append-repeater-row-id s2 :story.x/v [:items])]
      (is (= [(row-ids s2) 3 3 true]
             [(subvec (row-ids s3) 0 2) (count (row-ids s3))
              (:rf.story/repeater-id-counter s3) (apply distinct? (row-ids s3))])))))

(deftest repeater-row-ids-isolated-by-variant-and-path-rf2-c8kfy
  (testing "ids are keyed on [variant-id path]: independent, disjoint vectors"
    (let [s  (-> rf.story.ui.state/default-shell-state
                 (rf.story.ui.state/ensure-repeater-row-ids :story.a/v [:items] 2)
                 (rf.story.ui.state/ensure-repeater-row-ids :story.b/v [:items] 2)
                 (rf.story.ui.state/ensure-repeater-row-ids :story.a/v [:other] 2))
          vs (map (fn [[v p]] (rf.story.ui.state/repeater-row-ids s v p))
                  [[:story.a/v [:items]] [:story.b/v [:items]] [:story.a/v [:other]]])]
      (is (= [[2 2 2] 6 6]
             [(map count vs) (:rf.story/repeater-id-counter s) (count (distinct (apply concat vs)))]))))
  (testing "clear-cell-overrides drops the variant's row ids with its overrides"
    (let [s (-> rf.story.ui.state/default-shell-state
                (rf.story.ui.state/set-cell-override :story.x/v [:items] [1 2 3])
                (ensure-ids 3)
                (rf.story.ui.state/clear-cell-overrides :story.x/v))]
      (is (= [nil true] [(get-in s [:cell-overrides :story.x/v]) (empty? (row-ids s))])))))

;; ---- command palette -----------------------------------------------------

(deftest command-palette-builds-search-corpus
  (testing "entries enumerate every registry kind, and a story entry carries its variant ids"
    (let [entries (rf.story.ui.command-palette/entries
                    {:stories    {:story.cp {:doc "Counter parent"}}
                     :variants   {:story.cp/empty {} :story.cp/full {}}
                     :workspaces {:Workspace.cp/all {}}
                     :modes      {:Mode.cp/dark {}}
                     :decorators {:cp/outline {}}})]
      (is (= {:story 1 :variant 2 :workspace 1 :mode 1 :decorator 1}
             (select-keys (frequencies (map :kind entries))
                          [:story :variant :workspace :mode :decorator])))
      (is (= [:story.cp/empty :story.cp/full]
             (:variant-ids (first (filter #(= :story (:kind %)) entries))))))))

(deftest command-palette-search-matches-id-doc-and-kind
  (let [entries (rf.story.ui.command-palette/entries
                  {:stories    {:story.checkout {:doc "Payment flow"}}
                   :variants   {:story.checkout/error {:doc "Declined card state"}}
                   :workspaces {:Workspace.checkout/grid {:doc "All payment states"}}
                   :modes      {:Mode.theme/dark {:doc "Night palette"}}
                   :decorators {:checkout/auth {:doc "Authenticated shell"}}})
        top     #(:id (first (rf.story.ui.command-palette/search entries %)))]
    (is (= :story.checkout/error (top "checkout error")) "an id substring ranks first")
    (is (= :story.checkout/error (top "declined")) "doc text is searchable")
    (is (= :Workspace.checkout/grid (top "workspace payment")) "kind participates")
    (is (= :Mode.theme/dark (top "mthdrk")) "a fuzzy subsequence catches compact input")
    (is (empty? (rf.story.ui.command-palette/search entries "no such thing")))))

(deftest command-palette-active-index-wraps
  (is (= [1 0 2 0]
         (map #(apply rf.story.ui.command-palette/move-active-index %)
              [[0 1 3] [2 1 3] [0 -1 3] [0 1 0]]))))

(deftest command-palette-carries-save-current-command
  (testing "save-current-state is a searchable :command entry (spec/019 §3)"
    (is (= {:kind :command :action :save-current-as-variant}
           (select-keys (first (filter #(= :save-current-as-variant (:id %))
                                       (rf.story.ui.command-palette/command-entries)))
                        [:kind :action])))
    (is (= :save-current-as-variant
           (:id (first (rf.story.ui.command-palette/search
                         (rf.story.ui.command-palette/entries {}) "save variant")))))))

;; ---- mode-tabs -----------------------------------------------------------

(deftest valid-mode-tab?-rejects-noise
  (is (= [true true true false false false false]
         (map (comp boolean rf.story.ui.state/valid-mode-tab?)
              [:dev :docs :test :canvas :nonsense nil "test"]))))

(deftest set-active-mode-tab-roundtrip
  (let [s0 rf.story.ui.state/default-shell-state
        s  (-> s0
               (rf.story.ui.state/set-active-mode-tab :story.x/a :docs)
               (rf.story.ui.state/set-active-mode-tab :story.x/b :test))]
    (is (= [:docs :test :dev]
           (map #(rf.story.ui.state/active-mode-tab s %) [:story.x/a :story.x/b :story.x/c]))
        "selections are per variant; other variants keep the default")
    (is (= s0 (rf.story.ui.state/set-active-mode-tab s0 :story.x/a :nonsense))
        "an invalid tab leaves state untouched")))

;; ---- pure filter + grouping ---------------------------------------------

(deftest drop-default-excluded-hides-until-toggled-on
  (testing "a variant carrying a `:default-filter :exclude` tag is dropped
            unless the active tag filter names that tag"
    (let [vs {:story.dx/a {:tags #{:shipped}}
              :story.dx/b {:tags #{:internal}}
              :story.dx/c {:tags #{:internal :shipped}}}]
      (is (= #{:story.dx/a}
             (set (keys (rf.story.ui.state/drop-default-excluded vs #{:internal} #{}))))
          "hidden while the filter is empty")
      (is (= #{:story.dx/a :story.dx/b :story.dx/c}
             (set (keys (rf.story.ui.state/drop-default-excluded vs #{:internal} #{:internal}))))
          "shown once :internal is toggled on")
      (is (= vs (rf.story.ui.state/drop-default-excluded vs #{} #{}))
          "no default-excluded tags leaves the map unchanged"))))

(deftest group-variants-by-story-derives-unregistered-parent
  (testing "variants of a never-registered story group under the derived parent id"
    (rf.story/reg-variant :story.g/a {:setup []})
    (rf.story/reg-variant :story.g/b {:setup []})
    (is (= [[:story.g 2]]
           (map (juxt :story-id (comp count :variants))
                (rf.story.ui.state/group-variants-by-story
                  (rf.story.registrar/registrations :variant)))))))

(deftest parent-story-id-derivation
  (testing "parent-story-id (canonical leaf in re-frame.story.predicates)"
    (is (= :story.foo (rf.story.predicates/parent-story-id :story.foo/bar)))
    (is (nil? (rf.story.predicates/parent-story-id :unqualified)))))

;; ---- faceted filter (SB9 facet taxonomy) -------------------------------

(deftest filter-variants-faceted
  (testing "filter-variants applies AND across axes, OR within an axis"
    (doseq [[tag axis] [[:status/alpha :status] [:status/stable :status]
                        [:role/dev :role] [:role/design :role]]]
      (rf.story/reg-tag tag {:axis axis}))
    (rf.story/reg-variant :story.facet/a {:tags #{:status/alpha :role/dev} :setup []})
    (rf.story/reg-variant :story.facet/b {:tags #{:status/stable :role/dev} :setup []})
    (rf.story/reg-variant :story.facet/c {:tags #{:status/stable :role/design} :setup []})
    (let [vs  (rf.story.registrar/registrations :variant)
          ids #(set (keys (rf.story.ui.state/filter-variants
                            vs % (rf.story.registrar/tag->axis-index))))]
      (is (= #{:story.facet/a :story.facet/b :story.facet/c} (ids #{:status/alpha :status/stable}))
          "OR within an axis")
      (is (= #{:story.facet/c} (ids #{:status/stable :role/design})) "AND across axes")
      (is (= #{:story.facet/b :story.facet/c} (ids #{:status/stable})))
      (is (= 3 (count (rf.story.ui.state/filter-variants vs #{})))
          "the 2-arity with an empty filter passes everything"))))

(deftest group-tags-by-axis-buckets-and-sorts
  (testing "group-tags-by-axis buckets per axis into sorted vectors; the no-axis
            bucket catches explicit no-axis tags and unregistered ones"
    (is (= {:status                           [:status/alpha :status/stable]
            :role                             [:role/dev]
            :re-frame.story.registrar/no-axis [:loose/freeform :unregistered]}
           (rf.story.ui.state/group-tags-by-axis
             [:status/alpha :role/dev :status/stable :loose/freeform :unregistered]
             {:status/alpha   :status
              :status/stable  :status
              :role/dev       :role
              :loose/freeform :re-frame.story.registrar/no-axis})))))

(deftest ordered-axes-canonical-then-extras-then-no-axis
  (testing "canonical axes go first, project-defined alphabetical, no-axis last"
    (let [by-axis {:status                              [:s/a]
                   :role                                [:r/x]
                   :zeta                                [:z/x]
                   :alpha                               [:a/x]
                   :re-frame.story.registrar/no-axis    [:loose]}]
      (is (= [:status :role :alpha :zeta
              :re-frame.story.registrar/no-axis]
             (rf.story.ui.state/ordered-axes by-axis)))))
  (testing "missing canonical axes are skipped, no-axis trails"
    (is (= [:status :re-frame.story.registrar/no-axis]
           (rf.story.ui.state/ordered-axes
             {:status                              [:s/a]
              :re-frame.story.registrar/no-axis    [:loose]})))))

;; ---- workspace resolver --------------------------------------------------

(deftest grid-layout
  (testing ":grid and :tabs produce variant cells in declared order"
    (doseq [layout [:grid :tabs]]
      (is (= [[:variant :story.a/x] [:variant :story.b/y]]
             (map (juxt :type :variant-id)
                  (rf.story.ui.workspace/resolve-layout
                    :Workspace.x/y {:layout layout :variants [:story.a/x :story.b/y]})))
          (str layout)))))

(deftest variants-grid-from-anchor
  (testing ":variants-grid enumerates the variants of the workspace id's story"
    (rf.story/reg-variant :story.vg/a {:setup []})
    (rf.story/reg-variant :story.vg/b {:setup []})
    (rf.story/reg-variant :story.other/x {:setup []})
    (is (= [[:variant :story.vg/a] [:variant :story.vg/b]]
           (sort (map (juxt :type :variant-id)
                      (rf.story.ui.workspace/resolve-layout
                        :Workspace.vg/all {:layout :variants-grid})))))))

(deftest variants-grid-explicit-variants
  (testing ":variants-grid renders an explicit :variants list, in declared
            order, instead of enumerating the workspace id's story"
    (rf.story/reg-variant :story.vgx/a {:setup []})
    (rf.story/reg-variant :story.vgx/b {:setup []})
    (rf.story/reg-variant :story.vgy/c {:setup []})
    (let [cells (rf.story.ui.workspace/resolve-layout
                  :Workspace.vgx/curated
                  {:layout :variants-grid :variants [:story.vgy/c :story.vgx/a]})]
      (is (= [:story.vgy/c :story.vgx/a] (mapv :variant-id cells))))))

(deftest prose-layout-interleaves
  (testing ":prose preserves :content order"
    (is (= [[:prose "first" nil] [:variant nil :story.a/x] [:prose "last" nil]]
           (map (juxt :type :body :variant-id)
                (rf.story.ui.workspace/resolve-layout
                  :Workspace.guide/intro
                  {:layout  :prose
                   :content [{:type :prose :body "first"}
                             {:type :variant :id :story.a/x}
                             {:type :prose :body "last"}]}))))))

(deftest unknown-layout-empty
  (testing "unknown layouts degrade gracefully"
    (is (= [] (rf.story.ui.workspace/resolve-layout :Workspace.x/y {:layout :weird})))))

;; ---- :isolation slot ----------------------------------------------------

(deftest variants-grid-isolation-does-not-change-cell-resolution
  (testing "absent, :isolated and :shared :isolation resolve the same cell
            vector — the slot tunes mount strategy, not enumeration"
    (rf.story/reg-variant :story.iso/a {:setup []})
    (rf.story/reg-variant :story.iso/b {:setup []})
    (let [cells-for (fn [isolation]
                      (rf.story.ui.workspace/resolve-layout
                        :Workspace.iso/all
                        (cond-> {:layout :variants-grid}
                          isolation (assoc :isolation isolation))))
          absent    (cells-for nil)]
      (is (= absent (cells-for :isolated) (cells-for :shared)))
      (is (= 2 (count absent))))))

;; ---- :for anchor + :columns template ------------------------------------

(deftest variants-grid-for-anchor-is-read
  (testing ":variants-grid reads the :for anchor — the workspace id here derives
            :story.unrelated, which has no variants, so only :for can supply it"
    (rf.story/reg-variant :story.for-anchor/a {:setup []})
    (rf.story/reg-variant :story.for-anchor/b {:setup []})
    (rf.story/reg-variant :story.other-anchor/x {:setup []})
    (is (= [[:variant :story.for-anchor/a] [:variant :story.for-anchor/b]]
           (sort (map (juxt :type :variant-id)
                      (rf.story.ui.workspace/resolve-layout
                        :Workspace.unrelated/grid
                        {:layout :variants-grid :for :story.for-anchor})))))))

(deftest grid-template-columns-honours-columns
  (testing "a positive :columns gives N equal columns with no 280px floor, so the
            pinned grid never outgrows the pane; anything else keeps auto-fit"
    (is (= ["repeat(3, minmax(0, 1fr))" "repeat(1, minmax(0, 1fr))"]
           (map rf.story.ui.workspace/grid-template-columns [3 1])))
    (is (= #{"repeat(auto-fit, minmax(280px, 1fr))"}
           (set (map rf.story.ui.workspace/grid-template-columns [nil 0 -2]))))))

;; ---- docs mode ----------------------------------------------------------

(deftest docs-variant-tags-falls-back-to-story
  (rf.story/reg-story :story.t1 {:doc "parent" :tags #{:dev :docs}})
  (rf.story/reg-variant :story.t1/a {:tags #{:dev :test} :setup []})
  (rf.story/reg-variant :story.t1/b {:setup []})
  (is (= [:dev :test] (rf.story.ui.docs/variant-tags :story.t1/a)) "the variant's own :tags first")
  (is (= [:dev :docs] (rf.story.ui.docs/variant-tags :story.t1/b)) "else the parent story's"))

(deftest docs-variant-tags-resolves-removal-marker
  (testing "A child that :extends a :dev-tagged parent and
            declares :!dev shows NO :dev and NO :!dev chip (effective set)"
    (rf.story/reg-variant :story.tm/base  {:tags #{:dev :test} :setup []})
    (rf.story/reg-variant :story.tm/child {:extends :story.tm/base :tags #{:!dev} :setup []})
    (is (= [:test] (rf.story.ui.docs/variant-tags :story.tm/child)))))

(deftest docs-args-rows-pulls-doc-from-argtypes
  (testing "args-rows takes each row's :doc from :argtypes — a map's :doc, the
            Storybook-compat :description, or a bare string — and leaves an
            undocumented arg nil"
    (rf.story/reg-variant :story.a/x
      {:argtypes {:label {:doc "The cell label"} :desc {:description "compat"} :bare "Short doc"}
       :setup    []})
    (is (= [[:bare 2 "Short doc"] [:count 0 nil] [:desc 1 "compat"] [:label "Total" "The cell label"]]
           (sort-by first
                    (map (juxt :key :value :doc)
                         (rf.story.ui.docs/args-rows :story.a/x {:label "Total" :desc 1 :bare 2 :count 0}))))))
  (testing "args-rows merges the parent story's :argtypes under the variant's"
    (rf.story/reg-story :story.p {:argtypes {:label {:doc "from story"} :count {:doc "from story"}}})
    (rf.story/reg-variant :story.p/x {:argtypes {:label {:doc "from variant"}} :setup []})
    (is (= {:label "from variant" :count "from story"}
           (into {} (map (juxt :key :doc))
                 (rf.story.ui.docs/args-rows :story.p/x {:label "L" :count 0}))))))

(deftest docs-decorator-rows-classifies-by-section
  (testing "decorator-rows splits the pack into hiccup / frame-setup / fx-override / error rows"
    (is (= [[:hiccup "outer"] [:hiccup nil] [:frame-setup "init"] [:fx-override nil]
            [:error "unknown :kind"]]
           (map (juxt :section :doc)
                (rf.story.ui.docs/decorator-rows
                  {:hiccup       [{:id :dec/h1 :body {:kind :hiccup :doc "outer"}}
                                  {:id :dec/h2 :body {:kind :hiccup}}]
                   :frame-setup  [{:id :dec/fs :body {:kind :frame-setup :doc "init"}}]
                   :fx-override  [{:id :dec/fx :body {:kind :fx-override}}]
                   :errors       [{:id :dec/bad :reason "unknown :kind"}]
                   :fingerprints {}}))))))

(deftest docs-parameter-rows-pulls-three-slots
  (testing "parameter-rows emits only the non-empty slots, falling back to the parent story's"
    (rf.story/reg-variant :story.p1/x {:substrates #{:reagent} :platforms #{:client} :setup []})
    (rf.story/reg-story :story.p2 {:substrates #{:reagent :uix} :platforms #{:client}})
    (rf.story/reg-variant :story.p2/x {:setup []})
    (let [rows #(into {} (map (juxt :key :value)) (rf.story.ui.docs/parameter-rows %))]
      (is (= {:substrates #{:reagent} :platforms #{:client}} (rows :story.p1/x)))
      (is (= {:substrates #{:reagent :uix} :platforms #{:client}} (rows :story.p2/x))))))

(deftest docs-prose-for-variant
  (testing "prose-for-variant collects the prose blocks of the :prose workspaces
            that reference the variant, in workspace-id then content order"
    (rf.story/reg-variant :story.d/x {:setup []})
    (rf.story/reg-variant :story.d/y {:setup []})
    (rf.story/reg-workspace :Workspace.d/a
      {:layout :prose :content [{:type :prose :body "alpha"} {:type :variant :id :story.d/x}]})
    (rf.story/reg-workspace :Workspace.d/b
      {:layout :prose :content [{:type :variant :id :story.d/x} {:type :prose :body "beta"}]})
    (rf.story/reg-workspace :Workspace.d/grid {:layout :grid :variants [:story.d/x :story.d/y]})
    (is (= [["alpha" :Workspace.d/a] ["beta" :Workspace.d/b]]
           (map (juxt :body :workspace-id) (rf.story.ui.docs/prose-for-variant :story.d/x))))
    (is (= [] (rf.story.ui.docs/prose-for-variant :story.d/y))
        "a variant only a non-prose workspace references picks up nothing")))

;; ---- test mode ----------------------------------------------------------

(defn- run-all
  "Run each variant to completion, as Run all does."
  [ids]
  (into {}
        (map (fn [vid] [vid (rf.story.async/deref-blocking
                              (rf.story.runtime/run-variant vid nil) 30000)]))
        ids))

(defn- dot
  "A run's sidebar dot: Run all's `run-one-test!` (CLJS-only) records the
  run's aggregate summary plus its `:status`, and the dot reads that back."
  [result]
  (-> rf.story.ui.state/default-shell-state
      (rf.story.ui.state/record-test-run
        :v (assoc (rf.story.ui.state/aggregate-summary (:assertions result))
                  :status (:status result)))
      (rf.story.ui.state/variant-test-status :v)))

(deftest test-mode-variant-has-tests?-checks-play-slot
  (testing "variant-has-tests? is true only for a :script or :plays carrying a step"
    (rf.story/reg-variant :story.tm/empty {:setup []})
    (rf.story/reg-variant :story.tm/empty-play {:setup [] :script []})
    (rf.story/reg-variant :story.tm/has
      {:setup [] :script [[:dispatch-sync [:rf.assert/path-equals [:count] 0]]]})
    (rf.story/reg-variant :story.tm/plays
      {:setup [] :plays [{:name "happy"
                          :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]]}]})
    (is (= [false false true true false]
           (map (comp boolean rf.story.ui.test-mode.pure/variant-has-tests?)
                [:story.tm/empty :story.tm/empty-play :story.tm/has :story.tm/plays
                 :story.tm/unknown])))))

(deftest test-mode-variant-has-tests?-declarative-expectations
  (testing "a variant whose only tests are declarative :assertions or :checks has
            tests, so the Tests pane runs it; empty vectors are not tests"
    (rf.story/reg-check :story.tm/c-is-zero {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.tm/assertions-only
      {:setup [] :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.tm/checks-only {:setup [] :checks [:story.tm/c-is-zero]})
    (rf.story/reg-variant :story.tm/empty-expectations {:setup [] :assertions [] :checks []})
    (is (= [true true false]
           (map (comp boolean rf.story.ui.test-mode.pure/variant-has-tests?)
                [:story.tm/assertions-only :story.tm/checks-only :story.tm/empty-expectations])))))

(deftest run-all-runs-declarative-expectation-variants
  (testing "Run all selects and executes an :assertions-only and a :checks-only
            variant beside a :script control, and their records take the script
            assert's shape"
    (rf.story/reg-check :story.rall/c-is-zero
      {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.rall/assertions-only
      {:tags #{:test} :db-seed {:c 0}
       :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.rall/checks-only
      {:tags #{:test} :db-seed {:c 0} :checks [:story.rall/c-is-zero]})
    (rf.story/reg-variant :story.rall/script
      {:tags #{:test} :db-seed {:c 0}
       :script [[:assert [:rf.assert/path-equals [:c] 0]]]})
    (rf.story/reg-variant :story.rall/failing
      {:tags #{:test} :db-seed {:c 0}
       :assertions [[:rf.assert/path-equals [:c] 1]]})
    (let [ids     (rf.story.ui.state/testable-variant-ids
                    (rf.story.registrar/registrations :variant))
          results (run-all ids)
          shape   (fn [vid] (set (mapcat keys (:assertions (get results vid)))))]
      (is (= [:story.rall/assertions-only :story.rall/checks-only
              :story.rall/failing :story.rall/script]
             ids)
          "Run all selects the declarative variants, not only the :script control")
      (is (= {:story.rall/assertions-only :pass :story.rall/checks-only :pass
              :story.rall/failing :fail :story.rall/script :pass}
             (update-vals results dot))
          "a failing declarative assertion fails the run, never a silent skip")
      (is (seq (shape :story.rall/script)))
      (is (= (shape :story.rall/script)
             (shape :story.rall/assertions-only)
             (shape :story.rall/checks-only))
          "declarative records carry the same keys as a script assert record"))))

(deftest run-all-records-the-run-level-status
  (testing "Run all and watch mode record the run's `:status`, as the Tests pane
            does. A thrown fx after the `:db` commits is agreement-floor
            evidence, so the run fails while its one assertion passes; folded
            from the assertion counts alone, the dot would read a green `:pass`.
            `run-one-test!`'s own witness is
            `re-frame.story.ui.run-all-status-cljs-test`."
    (rf/reg-fx :story.rstat/boom {:platforms #{:client :server}}
      (fn [_ _] (throw (ex-info "probe fx" {}))))
    (rf/reg-fx :story.rstat/quiet {:platforms #{:client :server}} (fn [_ _] nil))
    (rf/reg-event :story.rstat/set-n
      (fn [{:keys [db]} [_ fx-id]] {:db (assoc db :n 1) :fx [[fx-id true]]}))
    (doseq [[vid fx] [[:story.rstat/floor :story.rstat/boom] [:story.rstat/clean :story.rstat/quiet]]]
      (rf.story/reg-variant vid
        {:tags   #{:test}
         :script [[:dispatch-sync [:story.rstat/set-n fx]]
                  [:assert [:rf.assert/path-equals [:n] 1]]]}))
    (let [{floor :story.rstat/floor clean :story.rstat/clean}
          (run-all [:story.rstat/floor :story.rstat/clean])]
      (is (= [:fail true] [(:status floor)
                           (:all-passed? (rf.story.ui.state/aggregate-summary (:assertions floor)))])
          "precondition: the run fails on the thrown fx while every assertion passed")
      (is (= :fail (dot floor)) "the dot takes the run's verdict, not the assertion counts")
      (is (= [:pass :pass] [(:status clean) (dot clean)])
          "control: the same shape with a quiet fx passes, and its dot is green"))
    (rf.story/destroy-variant! :story.rstat/floor)
    (rf.story/destroy-variant! :story.rstat/clean)))

(deftest inherited-and-composed-checks-select-and-run
  (testing ":checks a variant receives only through :extends or a :compose of a
            check id RUN (the compiler merges them into [:expect :checks]), so
            variant-has-tests? and Run all's selection — fed the sidebar's own
            registry-snapshot — select the variant, and its verdict is honest in
            both directions"
    (rf.story/reg-check :story.inh/c-is-zero
      {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-check :story.inh/c-is-one
      {:assertions [[:rf.assert/path-equals [:c] 1]]})
    (rf.story/reg-variant :story.inh/parent-pass
      {:tags #{:dev} :db-seed {:c 0} :checks [:story.inh/c-is-zero]})
    (rf.story/reg-variant :story.inh/parent-fail
      {:tags #{:dev} :db-seed {:c 0} :checks [:story.inh/c-is-one]})
    (rf.story/reg-variant :story.inh/extends-pass
      {:tags #{:test} :extends :story.inh/parent-pass})
    (rf.story/reg-variant :story.inh/extends-fail
      {:tags #{:test} :extends :story.inh/parent-fail})
    (rf.story/reg-variant :story.inh/compose-pass
      {:tags #{:test} :db-seed {:c 0} :compose [:story.inh/c-is-zero]})
    (rf.story/reg-variant :story.inh/compose-fail
      {:tags #{:test} :db-seed {:c 0} :compose [:story.inh/c-is-one]})
    (doseq [vid [:story.inh/extends-pass :story.inh/extends-fail
                 :story.inh/compose-pass :story.inh/compose-fail]]
      (is (rf.story.ui.test-mode.pure/variant-has-tests? vid)
          (str vid " — the Tests pane runs it instead of the empty state")))
    (let [ids (rf.story.ui.state/testable-variant-ids
                (:variants (rf.story.ui.state/registry-snapshot)))]
      (is (= [:story.inh/compose-fail :story.inh/compose-pass
              :story.inh/extends-fail :story.inh/extends-pass]
             ids)
          "Run all selects the inherited and composed check variants, not their :dev parents")
      (is (= {:story.inh/extends-pass :pass :story.inh/extends-fail :fail
              :story.inh/compose-pass :pass :story.inh/compose-fail :fail}
             (update-vals (run-all ids) dot))
          "an inherited or composed failing check fails the run, never a silent skip"))))

(deftest composed-fragment-script-selects-and-runs
  (testing "A :script a variant receives only through a :compose of a fragment
            RUNS (the compiler prepends it onto the primary play, or synthesizes
            one — spec/017 §Total merge order), so variant-has-tests? and Run
            all's selection select the variant and its verdict is honest in both
            directions; composing a fragment with no :script still prunes"
    (rf.story/reg-fragment :fragment.cmp/c-is-zero
      {:script [[:assert [:rf.assert/path-equals [:c] 0]]]})
    (rf.story/reg-fragment :fragment.cmp/c-is-one
      {:script {:script [[:assert [:rf.assert/path-equals [:c] 1]]]}})
    (rf.story/reg-fragment :fragment.cmp/no-script {:setup []})
    (rf.story/reg-variant :story.cmp/compose-pass
      {:tags #{:test} :db-seed {:c 0} :compose [:fragment.cmp/c-is-zero]})
    (rf.story/reg-variant :story.cmp/compose-fail
      {:tags #{:test} :db-seed {:c 0} :compose [:fragment.cmp/c-is-one]})
    (rf.story/reg-variant :story.cmp/compose-no-script
      {:tags #{:test} :db-seed {:c 0} :compose [:fragment.cmp/no-script]})
    (is (= [true true false]
           (map (comp boolean rf.story.ui.test-mode.pure/variant-has-tests?)
                [:story.cmp/compose-pass :story.cmp/compose-fail :story.cmp/compose-no-script]))
        "a composed fragment without a :script gives the run nothing to judge")
    (let [ids (rf.story.ui.state/testable-variant-ids
                (:variants (rf.story.ui.state/registry-snapshot)))]
      (is (= [:story.cmp/compose-fail :story.cmp/compose-pass] ids)
          "Run all selects the composed-script variants, not the script-less one")
      (is (= {:story.cmp/compose-pass :pass :story.cmp/compose-fail :fail}
             (update-vals (run-all ids) dot))
          "a failing composed script step fails the run, never a silent skip"))))

(deftest shell-state-aggregate-summary-counts-pass-fail-skip
  (testing "aggregate-summary tallies passed / failed / skipped; :all-passed? needs
            at least one record, every one passed and none skipped"
    (let [rec  (fn [a p] {:assertion a :passed? p})
          summ #(select-keys (rf.story.ui.state/aggregate-summary %)
                             [:total :passed :failed :skipped :all-passed?])]
      (is (= {:total 4 :passed 2 :failed 1 :skipped 1 :all-passed? false}
             (summ [(rec :rf.assert/path-equals true) (rec :rf.assert/path-equals false)
                    (rec :rf.assert/sub-equals true) (rec :rf.assert/skipped false)])))
      (is (= {:total 2 :passed 2 :failed 0 :skipped 0 :all-passed? true}
             (summ [(rec :rf.assert/path-equals true) (rec :rf.assert/sub-equals true)])))
      (is (false? (:all-passed? (summ [(rec :rf.assert/path-equals true)
                                       (rec :rf.assert/skipped false)])))
          "a skipped record disqualifies :all-passed?")
      (is (= {:total 0 :passed 0 :failed 0 :skipped 0 :all-passed? false} (summ []) (summ nil))
          "zero records is not 'all passed': the variant ran nothing"))))

(deftest record-test-run-preserves-skipped-and-failure-counts
  (testing "the test-widget projection keeps skipped and failed counts actionable"
    (let [summary (rf.story.ui.state/aggregate-summary
                    [{:assertion :rf.assert/path-equals :passed? true}
                     {:assertion :rf.assert/path-equals :passed? false}
                     {:assertion :rf.assert/skipped     :passed? false}])
          s       (rf.story.ui.state/record-test-run
                    rf.story.ui.state/default-shell-state :story.agg/failing
                    (assoc summary :ran-at-ms 123 :elapsed-ms 7))]
      (is (= {:status :fail :total 3 :passed 1 :failed 1 :skipped 1 :elapsed-ms 7 :ran-at-ms 123}
             (select-keys (get-in s [:tests :runs :story.agg/failing])
                          [:status :total :passed :failed :skipped :elapsed-ms :ran-at-ms]))))))

(deftest test-mode-assertion-row-projection
  (let [row rf.story.ui.test-mode.pure/assertion-row]
    (testing "a passing record's status, assertion and label; an empty payload drops from the label"
      (is (= [:pass :rf.assert/path-equals ":rf.assert/path-equals [[:count] 7]"]
             ((juxt :status :assertion :label)
              (row {:assertion :rf.assert/path-equals :payload [[:count] 7]
                    :passed? true :expected 7 :actual 7}))))
      (is (= ":rf.assert/no-warnings"
             (:label (row {:assertion :rf.assert/no-warnings :payload [] :passed? true})))))
    (testing "a failing record surfaces its detail; :source-coord stands in for :source"
      (is (= [:fail {:expected 7 :actual 0 :reason "mismatch" :source {:file "stories.cljs" :line 42}}]
             ((juxt :status #(select-keys (:detail %) [:expected :actual :reason :source]))
              (row {:assertion :rf.assert/path-equals :payload [[:count] 7] :passed? false
                    :expected 7 :actual 0 :reason "mismatch"
                    :source {:file "stories.cljs" :line 42}}))))
      (is (= {:file "f.cljs" :line 9}
             (-> (row {:assertion :rf.assert/sub-equals :passed? false
                       :source-coord {:file "f.cljs" :line 9}})
                 :detail :source))))
    (testing ":rf.assert/skipped reads :skip; a nil record reads :fail and still has a label"
      (is (= :skip (:status (row {:assertion :rf.assert/skipped :passed? false}))))
      (is (= [:fail true] ((juxt :status (comp some? :label)) (row nil)))))
    (testing ":row-key is the label, so the view keys :expanded on identity and a
              re-run that inserts a sibling does not open the wrong row"
      (let [a (row {:assertion :rf.assert/path-equals :payload [[:count] 1]
                    :passed? false :expected 1 :actual 0})
            b (row {:assertion :rf.assert/path-equals :payload [[:count] 2]
                    :passed? false :expected 2 :actual 0})]
        (is (= [(:label a) (:label b)] [(:row-key a) (:row-key b)]))
        (is (not= (:row-key a) (:row-key b)))))))

(deftest test-mode-assertion-row-unified-status
  (testing "assertion-row prefers a stamped :status over :passed? (spec/021 §1) —
            :cannot-run and :error are not folded into :fail — and derives them
            from flags on an unstamped record"
    (is (= [:cannot-run :error :pass :cannot-run :error]
           (map (comp :status rf.story.ui.test-mode.pure/assertion-row)
                [{:assertion :rf.assert/caused :status :cannot-run :passed? false}
                 {:assertion :rf.assert/path-equals :status :error :passed? false}
                 {:assertion :rf.assert/path-equals :status :pass :passed? false}
                 {:assertion :rf.assert/caused :cannot-run? true}
                 {:assertion :rf.assert/path-equals :error "boom"}])))))

(deftest test-mode-run-status
  (let [rs rf.story.ui.test-mode.pure/run-status]
    (testing "the unified run-level :status wins"
      (is (= [:pass :fail :error :cannot-run]
             (map #(rs {:status %} {}) [:pass :fail :error :cannot-run]))))
    (testing "no result is :pending; without :status the counts decide"
      (is (= [:pending :pending :pass :fail :cannot-run]
             [(rs nil nil) (rs {} {:total 0}) (rs {} {:total 2 :failed 0 :all-passed? true})
              (rs {} {:total 2 :failed 1}) (rs {} {:total 2 :failed 0 :cannot-run 1})])))))

(deftest test-mode-check-rows
  (testing "check-rows groups :checks by id with pass/fail counts and the
            projected assertion rows (spec/021 §1); no checks is an empty state"
    (let [[r & more] (rf.story.ui.test-mode.pure/check-rows
                       {:checks [{:check :auth/logged-in :status :fail
                                  :assertions [{:assertion :rf.assert/path-equals
                                                :status :pass :payload [[:user] 1]}
                                               {:assertion :rf.assert/path-equals
                                                :status :fail :payload [[:role] :admin]}]}]})]
      (is (= [nil :auth/logged-in :fail 1 1 2 2]
             [more (:check r) (:status r) (:passed r) (:failed r) (:total r) (count (:rows r))])))
    (is (= [] (rf.story.ui.test-mode.pure/check-rows {})
           (rf.story.ui.test-mode.pure/check-rows {:checks []})))))

(deftest test-mode-schema-rows
  (let [rows     rf.story.ui.test-mode.pure/schema-rows
        consumed #(mapv :consumed? (rows %))
        pass-rec (fn [sel] {:assertion :rf.assert/schema-error :status :pass :actual sel})
        login    {:selector [:event :auth/login] :where :event :failing-id :auth/login :epoch-id 3}
        role     {:selector [:app-db [:user] [:role]] :where :app-db :failing-id nil :epoch-id 4
                  :reason "invalid role"}
        r1       {:schema-violations [login role] :assertions [(pass-rec [:event :auth/login])]}]
    (testing "a :pass schema-error record consumes its violation; the rest stay
              unconsumed agreement-floor failures (spec/021 §1)"
      (is (= [true false] (consumed r1)))
      (is (= "invalid role" (:reason (second (rows r1))))))
    (testing "consumption is an exact multiset: two same-selector violations and
              one matching expectation mark exactly one, agreeing with the floor"
      (is (= [true false]
             (consumed {:schema-violations  [{:selector [:event :x] :where :event :failing-id :x :epoch-id 1}
                                             {:selector [:event :x] :where :event :failing-id :x :epoch-id 2}]
                        :consumed-selectors #{[:event :x]}
                        :assertions         [(pass-rec [:event :x])]}))))
    (testing "a consumed selector with no :pass record is a caller escape hatch and
              excuses every same-selector violation"
      (is (= [true false] (consumed {:schema-violations  [login role]
                                     :consumed-selectors #{[:event :auth/login]}
                                     :assertions         []}))))
    (testing "with empty :consumed-selectors a :pass record still carries the consumption"
      (is (= [true] (consumed {:schema-violations  [{:selector [:event :auth/login] :where :event}]
                               :consumed-selectors #{}
                               :assertions         [(pass-rec [:event :auth/login])]}))))
    (is (= [] (rows {})) "no violations is empty")))

(deftest test-mode-cannot-run-rows
  (testing "cannot-run-rows surfaces required vs available evidence for each
            refusal (spec/021 §1; spec/018 §12.6); none is empty"
    (is (= [{:required #{:dom} :available #{:headless} :missing #{:dom}
             :reason :runner-lacks-capability :runner :headless}]
           (map #(select-keys % [:required :available :missing :reason :runner])
                (rf.story.ui.test-mode.pure/cannot-run-rows
                  {:cannot-run [{:status :cannot-run :required-runner #{:dom}
                                 :available-runner #{:headless} :missing #{:dom}
                                 :reason :runner-lacks-capability :runner :headless
                                 :unit [:click "#go"]}]}))))
    (is (= [] (rf.story.ui.test-mode.pure/cannot-run-rows {})))))

(deftest test-mode-filter-rows
  (testing "the failed-only filter keeps the actionable rows — :fail, :error,
            :cannot-run — and off keeps every row (spec/021 §1)"
    (let [rows (map #(hash-map :status %) [:pass :fail :error :cannot-run :skip :pass])]
      (is (= rows (rf.story.ui.test-mode.pure/filter-rows rows false)))
      (is (= [:fail :error :cannot-run]
             (map :status (rf.story.ui.test-mode.pure/filter-rows rows true)))))))

(deftest test-mode-evidence-available?
  (testing "evidence-available? gates the pending affordance on a retained tape
            or narrative (spec/021 §2)"
    (is (= [true true false false]
           (map rf.story.ui.test-mode.pure/evidence-available?
                [{:epoch-tape [{:epoch-id 1}]} {:narrative [{:span 1}]}
                 {:epoch-tape [] :narrative []} {}])))))

(deftest test-mode-format-elapsed-ms
  (testing "format-elapsed-ms switches to seconds at 1s and blanks nil,
            non-numbers and negatives"
    (is (= ["0 ms" "12 ms" "999 ms" "1.0 s" "1.2 s" "" "" ""]
           (map rf.story.ui.test-mode.pure/format-elapsed-ms [0 12 999 1000 1234 nil "no" -5])))))

(deftest test-mode-format-timestamp-ms
  (testing "format-timestamp-ms emits an HH:mm:ss-shaped string"
    (let [s (rf.story.ui.test-mode.pure/format-timestamp-ms (System/currentTimeMillis))]
      (is (re-matches #"\d{2}:\d{2}:\d{2}" s))))
  (testing "format-timestamp-ms returns empty string for non-numbers"
    (is (= "" (rf.story.ui.test-mode.pure/format-timestamp-ms nil)))
    (is (= "" (rf.story.ui.test-mode.pure/format-timestamp-ms "no")))))

;; ---- step-through scrubber ----------------------------------------------

(deftest test-mode-play-step-label-renders-event-id
  (testing "play-step-label stringifies the event id only, blank for malformed input"
    (is (= [":auth/email-changed" ":rf.assert/path-equals" "" "" ""]
           (map rf.story.ui.test-mode.pure/play-step-label
                [[:auth/email-changed "alice@example.com"] [:rf.assert/path-equals [[:count] 7]]
                 nil [] "not-a-vec"])))))

(deftest test-mode-play-step-statuses-maps-events-to-status
  (let [steps    rf.story.ui.test-mode.pure/play-step-statuses
        statuses #(mapv :status (steps %1 %2))
        rec      (fn [a p] {:assertion a :passed? p})]
    (testing "one row per play event: events read :event, assertions take :pass /
              :fail from their records"
      (let [play [[:auth/email-changed "alice"] [:auth/submit]
                  [:rf.assert/path-equals [[:user :email] "alice"]]
                  [:rf.assert/path-equals [[:user :submitted?] true]]]]
        (is (= [[:event 0 ":auth/email-changed" (nth play 0)]
                [:event 1 ":auth/submit" (nth play 1)]
                [:pass 2 ":rf.assert/path-equals" (nth play 2)]
                [:fail 3 ":rf.assert/path-equals" (nth play 3)]]
               (map (juxt :status :index :label :event)
                    (steps play [(rec :rf.assert/path-equals true)
                                 (rec :rf.assert/path-equals false)]))))))
    (testing ":rf.assert/skipped reads :skip; an assertion with no record (the
              fail-fast gap) reads :fail so the gap shows"
      (is (= [:skip] (statuses [[:rf.assert/skipped]] [(rec :rf.assert/skipped false)])))
      (is (= [:pass :fail] (statuses [[:rf.assert/path-equals [[:k] 1]]
                                      [:rf.assert/path-equals [[:k] 2]]]
                                     [(rec :rf.assert/path-equals true)]))))
    (is (= [] (steps [] []) (steps nil nil)) "empty inputs")))

(deftest test-mode-epoch-id-slice-trigger-event-alignment
  (let [slice rf.story.ui.test-mode.pure/epoch-id-slice
        tape  (fn [& pairs] (mapv (fn [[id ev]] {:epoch-id id :trigger-event ev}) pairs))
        play  [[:a] [:b] [:c]]]
    (testing "each play event matches the tape by :trigger-event, in order"
      (is (= [10 11 12] (slice (tape [10 [:a]] [11 [:b]] [12 [:c]]) play))))
    (testing "an interleaved epoch from a non-dispatch step (:click / :type) is
              skipped, not attributed to the next play event as a positional
              trailing-N slice would"
      (is (= [100 102 103]
             (slice (tape [100 [:a]] [101 [:click/side-effect]] [102 [:b]] [103 [:c]]) play))))
    (testing "a tape that runs out before every play event matches yields [] rather
              than a mis-mapping, as do nil and empty inputs"
      (is (= [] (slice (tape [10 [:a]]) play) (slice nil [[:a]])
             (slice (tape [1 [:a]]) nil) (slice [] []))))))
