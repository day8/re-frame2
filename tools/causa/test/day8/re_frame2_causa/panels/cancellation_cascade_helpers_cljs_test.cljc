(ns day8.re-frame2-causa.panels.cancellation-cascade-helpers-cljs-test
  "Pure-data tests for Causa's Cancellation-cascade visualiser
  helpers (rf2-59e7k).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as the other helper tests — Cognitect's
  test-runner (CLJ) and Shadow's `:node-test` build both pick up
  `(cljs-)?test$` ns names.

  ## What's under test

    1. Predicates (destroy-event? / abort-event? / cancellation-anchor?
       / classify-fx / cancel-cause).
    2. extract-cascade: six core fixtures (single abort, many aborts,
       no aborts, nested destroys, mixed cancel-causes, correlation-id
       pairing) plus the empty-state branches.
    3. cascade-summary / group-by-cancel-cause / should-collapse?
    4. Formatters."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.cancellation-cascade-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- dispatched-ev
  ([event] (dispatched-ev event {:dispatch-id 1 :time 1000}))
  ([event {:keys [dispatch-id time id frame]
           :or {dispatch-id 1 time 1000 id 100 frame :rf/default}}]
   {:id        id
    :operation :event/dispatched
    :op-type   :event
    :time      time
    :tags      {:event       event
                :dispatch-id dispatch-id
                :frame       frame}}))

(defn- destroy-ev
  [{:keys [machine-id reason dispatch-id time id op spawned-id parent-id]
    :or {reason       :explicit
         dispatch-id  1
         time         1010
         id           200
         op           :rf.machine/destroyed}}]
  {:id        id
   :operation op
   :op-type   :rf.machine
   :time      time
   :tags      (cond-> {:machine-id  machine-id
                       :reason      reason
                       :dispatch-id dispatch-id}
                spawned-id (assoc :spawned-id spawned-id)
                parent-id  (assoc :parent-id parent-id))})

(defn- http-abort-ev
  [{:keys [request-id url actor-id dispatch-id time id sensitive?]
    :or {request-id  :req-1
         url         "/api/foo"
         actor-id    :user-session
         dispatch-id 1
         time        1020
         id          300}}]
  {:id         id
   :operation  :rf.http/aborted-on-actor-destroy
   :op-type    :rf.http
   :severity   :info
   :time       time
   :sensitive? (boolean sensitive?)
   :tags       (cond-> {:request-id  request-id
                        :url         url
                        :actor-id    actor-id}
                 dispatch-id (assoc :dispatch-id dispatch-id))})

(defn- ws-abort-ev
  [{:keys [event actor-id dispatch-id time id]
    :or {event       [:heartbeat]
         actor-id    :user-session
         dispatch-id 1
         time        1023
         id          400}}]
  {:id         id
   :operation  :rf.ws/aborted-on-actor-destroy
   :op-type    :rf.ws
   :time       time
   :tags       {:event       event
                :actor-id    actor-id
                :dispatch-id dispatch-id}})

(defn- timer-cancel-ev
  [{:keys [timer-id dispatch-id time id]
    :or {timer-id    :debounce
         dispatch-id 1
         time        1024
         id          500}}]
  {:id         id
   :operation  :rf.machine.timer/cancelled-on-resolution
   :op-type    :rf.machine.timer
   :time       time
   :tags       {:timer-id    timer-id
                :dispatch-id dispatch-id}})

(defn- invoke-cancel-ev
  [{:keys [child-id dispatch-id time id]
    :or {child-id    :fetch
         dispatch-id 1
         time        1025
         id          600}}]
  {:id         id
   :operation  :rf.machine.invoke/cancelled-on-join-resolution
   :op-type    :rf.machine.invoke
   :time       time
   :tags       {:child-id    child-id
                :dispatch-id dispatch-id}})

;; ---- (1) predicates -----------------------------------------------------

(deftest destroy-event?-positive
  (is (true? (h/destroy-event? (destroy-ev {:machine-id :user-session}))))
  (is (true? (h/destroy-event? {:operation :rf.machine.lifecycle/destroyed
                                :tags {:reason :parent-frame-destroyed}}))))

(deftest destroy-event?-negative
  (is (false? (h/destroy-event? (dispatched-ev [:auth/logout]))))
  (is (false? (h/destroy-event? (http-abort-ev {})))))

(deftest abort-event?-positive
  (is (true? (h/abort-event? (http-abort-ev {}))))
  (is (true? (h/abort-event? (ws-abort-ev {}))))
  (is (true? (h/abort-event? (timer-cancel-ev {}))))
  (is (true? (h/abort-event? (invoke-cancel-ev {})))))

(deftest abort-event?-negative
  (is (false? (h/abort-event? (dispatched-ev [:auth/logout]))))
  (is (false? (h/abort-event? (destroy-ev {:machine-id :x})))))

(deftest cancellation-anchor?-true-for-cancel-reasons
  (doseq [r [:explicit :parent-unmount-cascade :parent-frame-destroyed]]
    (is (true? (h/cancellation-anchor?
                 (destroy-ev {:machine-id :x :reason r}))))))

(deftest cancellation-anchor?-false-for-natural-finish
  (is (false? (h/cancellation-anchor?
                (destroy-ev {:machine-id :x :reason :rf.machine/finished})))))

(deftest classify-fx-maps-each-operation
  (is (= :http   (h/classify-fx (http-abort-ev {}))))
  (is (= :ws     (h/classify-fx (ws-abort-ev {}))))
  (is (= :after  (h/classify-fx (timer-cancel-ev {}))))
  (is (= :machine-invoke (h/classify-fx (invoke-cancel-ev {})))))

(deftest cancel-cause-prefers-explicit-tag
  (is (= :user-clicked-cancel
         (h/cancel-cause
           (assoc-in (http-abort-ev {}) [:tags :cancel-cause]
                     :user-clicked-cancel)))))

(deftest cancel-cause-defaults-by-operation
  (is (= :actor-destroyed (h/cancel-cause (http-abort-ev {}))))
  (is (= :actor-destroyed (h/cancel-cause (ws-abort-ev {}))))
  (is (= :join-resolved   (h/cancel-cause (timer-cancel-ev {}))))
  (is (= :join-resolved   (h/cancel-cause (invoke-cancel-ev {})))))

;; ---- (2) extract-cascade: single abort ---------------------------------

(deftest extract-single-abort
  (testing "one parent decision → one teardown → one HTTP abort"
    (let [buf [(dispatched-ev [:auth/logout]
                              {:dispatch-id 1 :time 1000 :id 1})
               (destroy-ev {:machine-id :user-session
                            :dispatch-id 1 :time 1010 :id 2})
               (http-abort-ev {:request-id  :req-1
                               :url         "/api/profile"
                               :actor-id    :user-session
                               :dispatch-id 1
                               :time        1020
                               :id          3})]
          c   (h/extract-cascade buf)]
      (is (nil? (:empty-kind c)))
      (is (= [:auth/logout] (-> c :parent-decision :event-vec)))
      (is (= 1 (count (:child-teardowns c))))
      (is (= :user-session (-> c :child-teardowns first :child-id)))
      (is (= 1 (-> c :child-teardowns first :inflight-count)))
      (is (= 1 (count (:effect-aborts c))))
      (is (= :http (-> c :effect-aborts first :fx)))
      (is (= :actor-destroyed (-> c :effect-aborts first :cancel-cause)))
      (is (= 20 (:total-elapsed-ms c))))))

;; ---- (2) extract-cascade: many aborts ----------------------------------

(deftest extract-many-aborts-sorted
  (testing "five aborts under one teardown, sorted by :time"
    (let [buf [(dispatched-ev [:checkout/cancel]
                              {:dispatch-id 9 :time 5000 :id 1})
               (destroy-ev {:machine-id :checkout :dispatch-id 9
                            :time 5010 :id 2})
               (http-abort-ev {:request-id :r3 :url "/api/log"
                               :dispatch-id 9 :time 5040 :id 5
                               :actor-id :checkout})
               (http-abort-ev {:request-id :r1 :url "/api/finalize"
                               :dispatch-id 9 :time 5020 :id 3
                               :actor-id :checkout})
               (ws-abort-ev {:event [:heartbeat]
                             :dispatch-id 9 :time 5030 :id 4
                             :actor-id :checkout})
               (timer-cancel-ev {:timer-id :debounce
                                 :dispatch-id 9 :time 5050 :id 6})
               (invoke-cancel-ev {:child-id :fetch
                                  :dispatch-id 9 :time 5060 :id 7})]
          c   (h/extract-cascade buf)]
      (is (= 5 (count (:effect-aborts c))))
      (is (= [5020 5030 5040 5050 5060]
             (mapv :t (:effect-aborts c))))
      (is (= [:http :ws :http :after :machine-invoke]
             (mapv :fx (:effect-aborts c))))
      (is (= 60 (:total-elapsed-ms c))))))

;; ---- (2) extract-cascade: no aborts ------------------------------------

(deftest extract-no-aborts
  (testing "destroy fired but nothing aborted — empty-kind :no-aborts"
    (let [buf [(dispatched-ev [:auth/logout]
                              {:dispatch-id 1 :time 1000 :id 1})
               (destroy-ev {:machine-id :user-session :dispatch-id 1
                            :time 1010 :id 2})]
          c   (h/extract-cascade buf)]
      (is (= :no-aborts (:empty-kind c)))
      (is (= 0 (count (:effect-aborts c))))
      (is (= 1 (count (:child-teardowns c))))
      (is (= 0 (-> c :child-teardowns first :inflight-count))))))

;; ---- (2) extract-cascade: nested destroys ------------------------------

(deftest extract-nested-destroys
  (testing "parent destroy → two child destroys → aborts on each"
    (let [buf [(dispatched-ev [:auth/logout]
                              {:dispatch-id 1 :time 1000 :id 1})
               (destroy-ev {:machine-id :parent :dispatch-id 1
                            :time 1010 :id 2
                            :spawned-id :parent
                            :parent-id nil
                            :reason :explicit})
               (destroy-ev {:machine-id :child-a :dispatch-id 1
                            :time 1011 :id 3
                            :reason :parent-unmount-cascade})
               (destroy-ev {:machine-id :child-b :dispatch-id 1
                            :time 1012 :id 4
                            :reason :parent-unmount-cascade})
               (http-abort-ev {:request-id :r1 :actor-id :child-a
                               :dispatch-id 1 :time 1020 :id 5})
               (http-abort-ev {:request-id :r2 :actor-id :child-b
                               :dispatch-id 1 :time 1021 :id 6})]
          c   (h/extract-cascade buf)]
      (is (= 3 (count (:child-teardowns c))))
      (is (every? #(contains? #{0 1} %)
                  (map :inflight-count (:child-teardowns c))))
      (is (= 2 (count (:effect-aborts c)))))))

;; ---- (2) extract-cascade: mixed cancel-causes --------------------------

(deftest extract-mixed-cancel-causes
  (testing "explicit :cancel-cause tags ride through"
    (let [buf [(destroy-ev {:machine-id :s :dispatch-id 1
                            :time 1000 :id 1})
               (-> (http-abort-ev {:dispatch-id 1 :time 1010 :id 2})
                   (assoc-in [:tags :cancel-cause] :user-clicked-cancel))
               (-> (http-abort-ev {:dispatch-id 1 :time 1011 :id 3})
                   (assoc-in [:tags :cancel-cause] :timeout))]
          c   (h/extract-cascade buf)
          grouped (h/group-by-cancel-cause c)]
      (is (= 2 (count grouped)))
      (is (contains? grouped :user-clicked-cancel))
      (is (contains? grouped :timeout)))))

;; ---- (2) extract-cascade: correlation-id pairing -----------------------

(deftest extract-correlation-id-pairing
  (testing "request-id rides through to :correlation-id on each row"
    (let [buf [(destroy-ev {:machine-id :s :dispatch-id 1
                            :time 1000 :id 1})
               (http-abort-ev {:request-id :req-XYZ
                               :url "/api/x"
                               :dispatch-id 1 :time 1010 :id 2})]
          c   (h/extract-cascade buf)
          row (first (:effect-aborts c))]
      (is (= :req-XYZ (:correlation-id row)))
      (is (= :req-XYZ (:request-id row)))
      (is (= "/api/x"  (:url row))))))

;; ---- (2) extract-cascade: empty buffer ---------------------------------

(deftest extract-empty-buffer
  (let [c (h/extract-cascade [])]
    (is (= :no-trigger (:empty-kind c)))
    (is (nil? (:parent-decision c)))
    (is (= [] (:child-teardowns c)))
    (is (= [] (:effect-aborts c)))))

(deftest extract-natural-finish-is-not-an-anchor
  (testing "a :rf.machine/finished destroy is NOT a cancellation anchor"
    (let [buf [(destroy-ev {:machine-id :x :dispatch-id 1 :time 1
                            :reason :rf.machine/finished})]
          c   (h/extract-cascade buf)]
      (is (= :no-trigger (:empty-kind c))))))

;; ---- (2) extract-cascade: focus by machine-id --------------------------

(deftest extract-focus-by-machine-id
  (testing "focus picks the latest destroy for that machine-id"
    (let [buf [(destroy-ev {:machine-id :a :dispatch-id 1 :time 1000 :id 1})
               (destroy-ev {:machine-id :b :dispatch-id 2 :time 2000 :id 2})
               (http-abort-ev {:actor-id :a :dispatch-id 1
                               :time 1005 :id 3})
               (http-abort-ev {:actor-id :b :dispatch-id 2
                               :time 2005 :id 4})]
          c   (h/extract-cascade buf {:kind :machine-id :id :a})]
      (is (= 1 (count (:effect-aborts c))))
      (is (= :a (-> c :child-teardowns first :child-id))))))

;; ---- (2) extract-cascade: best-effort wall-clock window ----------------

(deftest extract-wall-clock-fallback
  (testing "actor-destroy abort outside the originating drain (no
            dispatch-id) is still folded in by wall-clock proximity"
    (let [buf [(destroy-ev {:machine-id :user-session :dispatch-id 1
                            :time 1000 :id 1})
               ;; abort fires AFTER the anchor's drain — dispatch-id
               ;; missing from tags; proximity gathers it.
               (-> (http-abort-ev {:request-id :r1 :actor-id :user-session
                                   :time 1015 :id 2})
                   (update :tags dissoc :dispatch-id))]
          c   (h/extract-cascade buf)]
      (is (= 1 (count (:effect-aborts c)))))))

;; ---- (3) summarisers ---------------------------------------------------

(deftest cascade-summary-empty
  (is (= "No cancellation cascade in the trace window."
         (h/cascade-summary (h/extract-cascade [])))))

(deftest cascade-summary-with-aborts
  (let [buf [(destroy-ev {:machine-id :s :dispatch-id 1 :time 1000 :id 1})
             (http-abort-ev {:dispatch-id 1 :time 1010 :id 2})
             (http-abort-ev {:dispatch-id 1 :time 1011 :id 3
                             :request-id :other})]
        s   (h/cascade-summary (h/extract-cascade buf))]
    (is (re-find #"2 effects aborted" s))
    (is (re-find #"1 child destroyed" s))))

(deftest should-collapse?-respects-threshold
  (let [tiny  {:effect-aborts (vec (repeat 3 {}))}
        big   {:effect-aborts (vec (repeat 50 {}))}]
    (is (false? (h/should-collapse? tiny)))
    (is (true?  (h/should-collapse? big)))
    (is (true?  (h/should-collapse? tiny 2)))))

(deftest group-by-cancel-cause-empty-cascade
  (let [c (h/extract-cascade [])]
    (is (= {} (h/group-by-cancel-cause c)))))

;; ---- (4) formatters ----------------------------------------------------

(deftest format-time-ms-handles-nil
  (is (= "—" (h/format-time-ms nil)))
  (is (= "1234ms" (h/format-time-ms 1234))))

(deftest format-event-vec-handles-nil-and-vec
  (is (= "—" (h/format-event-vec nil)))
  (is (= "[:auth/logout]" (h/format-event-vec [:auth/logout]))))

(deftest format-fx-label-shapes
  (is (re-find #"HTTP"
               (h/format-fx-label {:fx :http
                                   :req {:method :post}
                                   :url "/api/foo"})))
  (is (re-find #"WS send"
               (h/format-fx-label {:fx :ws :req {:event [:hb]}})))
  (is (re-find #":after timer fire"
               (h/format-fx-label {:fx :after :req {:timer-id :t1}})))
  (is (re-find #"machine-invoke"
               (h/format-fx-label {:fx :machine-invoke
                                   :req {:child-id :c1}}))))
