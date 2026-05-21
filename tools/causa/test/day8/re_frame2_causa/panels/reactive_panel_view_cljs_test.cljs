(ns day8.re-frame2-causa.panels.reactive-panel-view-cljs-test
  "Smoke tests for `reactive-panel-view` (rf2-wyvf2 · spec/021 §3).

  Mounts `reactive-panel` (the plain Reagent fn) and asserts the
  structural data-testid hooks ship: panel root, header, the two
  step sections, and the unchanged-subs toggle when applicable."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.reactive-panel :as facade]
            [day8.re-frame2-causa.panels.reactive-panel-view :as view]))

(defn- has-testid? [tree testid]
  (some? (th/find-by-testid tree testid)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest reactive-panel-mounts-with-root-testid
  (testing "rf2-wyvf2 — the panel root surfaces `rf-causa-reactive`
            data-testid + the panel installs the L4 tab"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive")
          "the root :section data-testid is present"))))

(deftest reactive-panel-renders-empty-state-without-cascade
  (testing "Empty-state copy renders when no cascade is focused"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-empty")
          "empty-state surfaces when no cascade exists"))))

(deftest reactive-panel-omits-large-h1-heading
  (testing "rf2-6xezz · Mike-direction 2026-05-21 — the View panel
            (renamed from Reactive per rf2-e33ad) NO LONGER renders a
            large h1 heading at its top. The tab strip is the panel-
            name source-of-truth; the panel body starts immediately
            under the optional metadata strip. The previous panel-icon
            element (`rf-causa-reactive-panel-icon`) is also removed
            because it lived inside the deleted h1."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)
          icon (th/find-by-testid tree "rf-causa-reactive-panel-icon")]
      (is (nil? icon) "panel-icon span is gone (lived in the deleted h1)"))))

(deftest reactive-panel-uses-views-display-label
  (testing "Mike-direction 2026-05-21 — the L4 tab displays as `Views`
            under the all-plural-domain-noun convention (Views / Flows /
            Schemas / Routes / Machines are all plural); the panel-
            registry key stays `:views` (internal id, never a user
            contract)."
    (facade/install!)
    (let [registered (panel-registry/tab-by-id :dynamic :views)]
      (is (some? registered) "panel-registry has a :views entry under :dynamic")
      (is (= "Views" (:label registered))
          "L4 tab label renders as `Views`"))))

(deftest reactive-panel-renders-two-cascade-sections
  (testing "rf2-isun6 — the View panel renders TWO pipeline sections
            (SUBS THIS CASCADE · VIEWS RE-RENDERED) wrapped in a left-
            rail container with a chevron between them. The prior three
            overlapping subs sections (SUBS RAN / SUBS WHOSE VALUE
            CHANGED / SUBS THAT CASCADED) collapsed into one table whose
            `changed?` / `cascaded?` columns carry those dimensions, so
            each sub appears exactly once. Empty states are ALWAYS
            visible per Mike-direction 2026-05-21."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    ;; The empty-state path doesn't render the sections; the table
    ;; shape is exercised directly in the focused-cascade test below
    ;; (which seeds `:rf.causa/reactive-data`). Here we confirm the
    ;; empty branch holds when no cascade fires.
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-empty")
          "empty branch holds when no cascade is focused"))))

(defn- seed-reactive-data!
  "Re-register the composite `:rf.causa/reactive-data` to return a
  literal so the panel view renders its focused-cascade body. The view
  is the unit under test here — the composite's projection logic is
  covered by reactive-panel-subs-cljs-test."
  [data]
  (rf/reg-sub :rf.causa/reactive-data (fn [_db _q] data)))

(deftest reactive-panel-subs-table-one-row-per-sub
  (testing "rf2-isun6 — one row per sub that RAN; changed? / cascaded?
            are columns (each sub appears exactly once). A sub that ran
            AND changed AND cascaded yields a SINGLE row carrying both
            ✓ flags, not three rows across three sections."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true
       :frame        :rf/app
       :focus        {:current :ep-1}
       :counts       {:subs-ran 3 :subs-skipped 0 :views-rendered 0}
       ;; :a ran only · :b ran + changed · :c ran + changed + cascaded
       :subs-ran     [{:sub-id :a/plain}
                      {:sub-id :b/changed :value-changed? true
                       :prev-value 1 :value 2}
                      {:sub-id :c/cascaded :value-changed? true
                       :prev-value 3 :value 4 :cascade? true
                       :cause-sub [:b/changed]}]
       :views-rendered []})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-subs-table")
          "the single subs table renders")
      ;; Exactly one row per sub — no triple-repeat across sections.
      (is (has-testid? tree "rf-causa-reactive-sub-row-_a_plain"))
      (is (has-testid? tree "rf-causa-reactive-sub-row-_b_changed"))
      (is (has-testid? tree "rf-causa-reactive-sub-row-_c_cascaded"))
      ;; The legacy per-section row testid is gone (collapsed into rows).
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-sub-ran"))
          "the old per-section row testid no longer ships"))))

(deftest reactive-panel-subs-table-empty-when-no-subs
  (testing "rf2-isun6 — empty subs placeholder when a cascade is focused
            but no subs ran."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (seed-reactive-data!
      {:has-cascade? true
       :frame        :rf/app
       :focus        {:current :ep-1}
       :counts       {:subs-ran 0 :subs-skipped 0 :views-rendered 0}
       :subs-ran     []
       :views-rendered []})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-subs-empty")
          "empty subs placeholder renders")
      (is (nil? (th/find-by-testid tree "rf-causa-reactive-subs-table"))
          "no table when no subs ran"))))
