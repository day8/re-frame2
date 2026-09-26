(ns day8.re-frame2-xray.panels.reactivity.routing-reactivity-cljs-test
  "Sub-reactivity guard for the Routing panel's focused-event lens.

  Routing tracks LIVE by pivoting on `:rf.xray/focus :dispatch-id`. This
  test pins the cascade-tracking reactivity contract — flipping focus
  between cascades changes the routing tab's `:from-id` / `:to-id`
  chips."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(deftest routing-tab-data-re-fires-with-current-route-override
  (testing "the test-only `:rf.xray/set-current-route-
            slice-override` event writes the override slot; the
            composite sub re-fires with the new slice. This pins the
            inner reactive chain: override → current-route-slice →
            routing-tab-data."
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [data-1 (h/read-sub :rf.xray/routing-tab-data)]
      (h/dispatch-xray!
        [:rf.xray/set-current-route-slice-override-for-test
         {:id :app/cart :params {:user-id 7}}])
      (let [data-2 (h/read-sub :rf.xray/routing-tab-data)]
        (is (= {:id :app/cart :params {:user-id 7}}
               (:current data-2))
            "override slot writes the composite's :current axis")
        (is (not= (:current data-1) (:current data-2))
            "routing-tab-data sub re-fired on override write")))))
