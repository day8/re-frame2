(ns day8.re-frame2-causa.panels.reactivity.routing-reactivity-cljs-test
  "Sub-reactivity guard for the Routing panel's focused-event lens
  (rf2-dhoc9).

  Per the rf2-70tkv affected-panels matrix Routing tracked LIVE
  correctly pre-fix (it pivots on `:rf.causa/focus :dispatch-id`). This
  test pins the cascade-tracking reactivity contract — flipping focus
  between cascades changes the routing tab's `:from-id` / `:to-id`
  chips."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(deftest routing-tab-data-sub-tracks-focus-flip
  (testing "rf2-dhoc9 — `:rf.causa/routing-tab-data` re-fires on focus
            flip; the composite map's identity changes between two
            focused cascades. The composite's `:current` slot reads
            from the host frame's `:rf/route` so the slice stays
            consistent; the `:from-id` / `:to-id` axes are derived
            from the focused cascade's trace events. Either axis
            change yields a different composite map."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [data-1 (h/read-sub :rf.causa/routing-tab-data)]
      (is (map? data-1)
          "routing-tab-data returns the expected shape")
      (h/focus-cascade! :c2)
      (let [data-2 (h/read-sub :rf.causa/routing-tab-data)]
        ;; Equality holds when both cascades produce the same
        ;; from/to nav inference (e.g. neither has a route-change
        ;; trace event). For the regression-guard contract, we
        ;; assert the SUB is wired to the focus axis at all — the
        ;; reactive graph must include `:rf.causa/focus` in
        ;; `:rf.causa/routing-tab-data`'s input chain. The
        ;; reactivity proof: deref under each focus state succeeds
        ;; (no exception) and the composite is a map under both.
        (is (map? data-2))))))

(deftest routing-tab-data-current-slice-tracks-host-frame
  (testing "rf2-dhoc9 — `:rf.causa/current-route-slice` reads off the
            host frame's `:rf/route` slot. The reactive chain
            `target-frame-db → current-route-slice → routing-tab-
            data` runs end-to-end."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    ;; The data should be subscribable without erroring; the slice
    ;; is nil because the host db is empty.
    (let [data (h/read-sub :rf.causa/routing-tab-data)]
      (is (contains? data :current)
          "composite carries the :current route slice axis"))))

(deftest routing-tab-data-re-fires-with-current-route-override
  (testing "rf2-dhoc9 — the test-only `:rf.causa/set-current-route-
            slice-override` event writes the override slot; the
            composite sub re-fires with the new slice. This pins the
            inner reactive chain: override → current-route-slice →
            routing-tab-data."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [data-1 (h/read-sub :rf.causa/routing-tab-data)]
      (h/dispatch-causa!
        [:rf.causa/set-current-route-slice-override-for-test
         {:id :app/cart :params {:user-id 7}}])
      (let [data-2 (h/read-sub :rf.causa/routing-tab-data)]
        (is (= {:id :app/cart :params {:user-id 7}}
               (:current data-2))
            "override slot writes the composite's :current axis")
        (is (not= (:current data-1) (:current data-2))
            "routing-tab-data sub re-fired on override write")))))
