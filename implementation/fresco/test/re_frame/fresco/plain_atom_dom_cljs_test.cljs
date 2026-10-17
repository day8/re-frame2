(ns re-frame.fresco.plain-atom-dom-cljs-test
  "A Fresco boundary under the PLAIN-ATOM adapter — mounted, dispatched to,
  re-rendered.

  Fresco renders itself, through `react-dom/client` and its own
  `useSyncExternalStore` shell, so what it takes from a substrate adapter is
  the observation half alone: a container `app-db` lives in, and a derived
  value that takes a watch and says when it moved. The plain-atom derived
  value does both (Spec 006 §`make-derived-value`): it watches its sources
  while an observer is attached and notifies only when the value moves by
  `=`. So the collector's `add-watch` on a subscription is live under
  plain-atom, and this file pins that it is — through a layer-1 read and a
  layer-2 read whose source is itself a derived value.

  What plain-atom does NOT have is its own render path: its `:render` slot
  refuses and `render-to-string` is its only one. That is no obstacle to
  a Fresco tree, whose mount never goes through the adapter's `:render`.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` the DOM claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.checkpoint-support :as rf.fresco.checkpoint-support]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::plain-atom)

;; Registered above `use-fixtures`: the reset fixture restores the
;; registrar to the baseline it captured when that form was evaluated.
(rf/reg-sub ::count (fn [db _] (:count db 0)))
(rf/reg-sub ::doubled {:inputs [[::count]]} (fn [[n] _] (* 2 n)))
(rf/reg-event ::inc (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(def ^:private !runs (atom 0))

(rf.fresco/defview counter
  "One boundary reading a layer-1 sub and the layer-2 sub derived from it."
  [_]
  (swap! !runs inc)
  [:p.counter (str (rf.fresco/sub [::count]) "/" (rf.fresco/sub [::doubled]))])

(defn- text [handle]
  (.-textContent (.querySelector (:container handle) "p.counter")))

(deftest a-boundary-under-plain-atom-re-renders-when-a-dispatch-moves-its-read
  (if-not (rf.fresco.impl.mount/browser?)
    (is true "a mounted Fresco boundary needs a real React DOM — :node-test has none")
    (do
      (rf.fresco.checkpoint-support/leave-act-environment!)
      (is (= :rf.adapter/plain-atom (:kind (rf.substrate.adapter/current-adapter)))
          "the installed adapter is plain-atom")
      (let [handle (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!)
                                               frame-id [counter {}])]
        (try
          (is (= "0/0" (text handle)) "the boundary mounted and painted its first read")
          (reset! !runs 0)
          (rf.fresco.impl.mount/dispatch! handle [::inc])
          (is (= "1/2" (text handle))
              "the dispatch moved app-db, both derived values notified, and the
               boundary re-rendered with the new reads")
          (is (= 1 @!runs) "one re-render for one dispatch")
          (testing "and it stays live: a second dispatch lands as well"
            (rf.fresco.impl.mount/dispatch! handle [::inc])
            (is (= "2/4" (text handle))))
          (finally (rf.fresco.impl.mount/release! handle)))))))
