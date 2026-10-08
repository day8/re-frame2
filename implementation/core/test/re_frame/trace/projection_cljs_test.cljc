(ns re-frame.trace.projection-cljs-test
  "`re-frame.trace.projection/group-by-event` + `domino-bucket` over the real
  Spec 009 §`:op-type` vocabulary. Pure data — no fixture, no frame, no
  router; JVM and CLJS run the same suite."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.trace.projection :as rf.trace.projection]))

;; ---- domino-bucket --------------------------------------------------------

(deftest domino-bucket-classifies-each-trace-event
  (testing "one row per classification arm; the classification is total, so
            anything outside the vocabulary — or no shape at all — is :other"
    (are [ev bucket] (= bucket (rf.trace.projection/domino-bucket ev))
      {:op-type :rf.event :operation :rf.event/dispatched}   :event
      {:op-type :rf.event :operation :rf.event/run-start}    :handler
      {:op-type :rf.event :operation :rf.event/run-end}      :handler
      {:op-type :rf.event :operation :rf.event/db-changed}   :other
      {:op-type :rf.fx :operation :rf.fx/do-fx}              :fx
      {:op-type :rf.fx :operation :rf.fx/handled}            :effect
      {:op-type :rf.sub :operation :rf.sub/run}              :sub
      {:op-type :rf.view :operation :rf.view/render}         :render
      {:op-type :error :operation :rf.error/no-such-handler} :other
      {}                                                     :other)))

;; ---- group-by-event -------------------------------------------------------

(defn- cascade-evs
  "A representative one-cascade event stream."
  ([dispatch-id event-vec]
   (cascade-evs dispatch-id event-vec :rf/default))
  ([dispatch-id event-vec frame-id]
   [{:id 1 :op-type :rf.event    :operation :rf.event/dispatched
     :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v event-vec :frame frame-id}}
    {:id 2 :op-type :rf.event    :operation :rf.event/run-start
     :tags {:rf.trace/dispatch-id dispatch-id :rf.trace/phase :run-start :frame frame-id}}
    {:id 3 :op-type :rf.event    :operation :rf.event/run-end
     :tags {:rf.trace/dispatch-id dispatch-id :rf.trace/phase :run-end :frame frame-id}}
    {:id 4 :op-type :rf.fx       :operation :rf.fx/do-fx
     :tags {:rf.trace/dispatch-id dispatch-id :frame frame-id}}
    {:id 5 :op-type :rf.fx       :operation :rf.fx/handled
     :tags {:rf.trace/dispatch-id dispatch-id :rf.fx/id :db :frame frame-id}}
    {:id 6 :op-type :rf.fx       :operation :rf.fx/handled
     :tags {:rf.trace/dispatch-id dispatch-id :rf.fx/id :dispatch :frame frame-id}}
    {:id 7 :op-type :rf.sub      :operation :rf.sub/run
     :tags {:rf.trace/dispatch-id dispatch-id :rf.sub/id :sub/foo :frame frame-id}}
    {:id 8 :op-type :rf.view     :operation :rf.view/render
     :tags {:rf.trace/dispatch-id dispatch-id :rf.view/render-key [:app/root nil] :frame frame-id}}]))

(deftest group-by-event-one-cascade-six-buckets
  (testing "a cascade reduces to one record: :handler is the LAST run marker,
            and :dispatched is the full trace event, hoisted slots included"
    (let [evs (-> (cascade-evs 100 [:user/login {:id 42}])
                  (update 0 assoc :rf.trace/call-site {:file "src/views.cljs" :line 127} :source :ui))]
      (is (= [{:dispatch-id        100
               :parent-dispatch-id nil
               :frame              :rf/default
               :event              [:user/login {:id 42}]
               :dispatched         (evs 0)
               :handler            (evs 2)
               :fx                 (evs 3)
               :effects            [(evs 4) (evs 5)]
               :subs               [(evs 6)]
               :renders            [(evs 7)]
               :other              []}]
             (rf.trace.projection/group-by-event evs))))))

(deftest group-by-event-keeps-same-dispatch-id-separate-by-frame
  (testing "dispatch-id is frame-scoped, so the same id in two frames yields two records"
    (let [a (cascade-evs 10 [:counter/a-inc] :counter/a)
          b (mapv #(update % :id + 100) (cascade-evs 10 [:counter/b-inc] :counter/b))]
      (is (= [[:counter/a 10 [:counter/a-inc]] [:counter/b 10 [:counter/b-inc]]]
             (mapv (juxt :frame :dispatch-id :event)
                   (rf.trace.projection/group-by-event (concat a b))))))))

(deftest group-by-event-events-without-dispatch-id-land-in-ungrouped
  (testing "registry-time and frame-lifecycle events carry no dispatch-id and share :ungrouped"
    (let [evs [{:id 1 :op-type :rf.frame :operation :rf.frame/created :tags {:frame :app}}
               {:id 2 :op-type :rf.registry :operation :rf.registry/handler-registered
                :tags {:kind :event :id :user/login}}]]
      (is (= [[:ungrouped evs]]
             (mapv (juxt :dispatch-id :other) (rf.trace.projection/group-by-event evs)))))))

(deftest group-by-event-error-and-warning-events-ride-along-in-other
  (testing "an error inside a cascade lands in that cascade's :other, not its own record"
    (let [err {:id 100 :op-type :error :operation :rf.error/handler-exception
               :tags {:rf.trace/dispatch-id 400 :event-id :foo}}]
      (is (= [[400 [err]]]
             (mapv (juxt :dispatch-id :other)
                   (rf.trace.projection/group-by-event (conj (cascade-evs 400 [:foo]) err))))))))

(deftest group-by-event-empty-input-yields-empty-output
  (is (= [] (rf.trace.projection/group-by-event nil))))

(deftest group-by-event-surfaces-parent-dispatch-id
  (testing ":parent-dispatch-id is read off the dispatched event's tag; a root has none
            (Spec 009 §Dispatch correlation)"
    (are [tags parent] (= parent (:parent-dispatch-id
                                   (first (rf.trace.projection/group-by-event
                                            [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                              :tags tags}]))))
      {:rf.trace/dispatch-id 20 :rf.trace/parent-dispatch-id 10 :rf.event/v [:form/validate]} 10
      {:rf.trace/dispatch-id 10 :rf.event/v [:user/click]}                                   nil)))

(deftest group-by-event-orders-dispatched-root-only-bundle-by-its-own-id
  (testing "a root-only bundle sorts by its :dispatched event's :id — the bare
            :event vector carries no :id, so reading it would sort the bundle last"
    (let [root-only {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id :root-only :rf.event/v [:root-only]}}
          full      (mapv #(update % :id + 99) (cascade-evs :full-cascade [:full]))]
      (is (= [:root-only :full-cascade]
             (map :dispatch-id (rf.trace.projection/group-by-event (concat full [root-only]))))))))
