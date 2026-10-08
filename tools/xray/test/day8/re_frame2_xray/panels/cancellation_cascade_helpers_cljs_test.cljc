(ns day8.re-frame2-xray.panels.cancellation-cascade-helpers-cljs-test
  "Pure-data tests for Xray's Cancellation-cascade visualiser helpers.
  Dual-target: the JVM test-runner and the `:node-test` build both pick
  up a `-cljs-test` ns."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test    :refer-macros [are deftest is]])
            [day8.re-frame2-xray.panels.cancellation-cascade-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- dispatched-ev
  [event {:keys [dispatch-id time id frame]}]
  {:id        id
   :operation :rf.event/dispatched
   :time      time
   :tags      (cond-> {:rf.event/v event :rf.trace/dispatch-id dispatch-id}
                frame (assoc :frame frame))})

(defn- destroy-ev
  "`op` defaults to the FX-substrate channel and `reason` to `:explicit`,
  the emittable pair for a normal teardown."
  [{:keys [machine-id reason dispatch-id time id op frame]
    :or {reason       :explicit
         dispatch-id  1
         time         1010
         id           200
         op           :rf.machine/destroyed}}]
  {:id        id
   :operation op
   :time      time
   :tags      (cond-> {:machine-id           machine-id
                       :reason               reason
                       :rf.trace/dispatch-id dispatch-id}
                frame (assoc :frame frame))})

(defn- http-abort-ev
  [{:keys [request-id url actor-id dispatch-id time id frame]
    :or {request-id  :req-1
         url         "/api/foo"
         actor-id    :user-session
         dispatch-id 1
         time        1020
         id          300}}]
  {:id        id
   :operation :rf.http/aborted-on-actor-destroy
   :time      time
   :tags      (cond-> {:request-id request-id
                       :url        url
                       :actor-id   actor-id}
                dispatch-id (assoc :rf.trace/dispatch-id dispatch-id)
                frame       (assoc :frame frame))})

(defn- http-aborted-ev
  "The transport's `:rf.http/aborted` row; on an actor destroy it echoes the
  registry's `:rf.http/aborted-on-actor-destroy` for the same request."
  [{:keys [request-id actor-id reason dispatch-id time id]
    :or {reason :actor-destroyed
         time   1021}}]
  {:id        id
   :operation :rf.http/aborted
   :time      time
   :tags      {:reason               reason
               :actor-id             actor-id
               :request-id           request-id
               :rf.trace/dispatch-id dispatch-id}})

(defn- ws-abort-ev
  [{:keys [actor-id dispatch-id time id]}]
  {:id        id
   :operation :rf.ws/aborted-on-actor-destroy
   :time      time
   :tags      {:actor-id actor-id :rf.trace/dispatch-id dispatch-id}})

(defn- timer-cancel-ev
  [{:keys [dispatch-id time id] :or {dispatch-id 1 time 1024 id 500}}]
  {:id        id
   :operation :rf.machine.timer/cancelled
   :time      time
   :tags      {:timer-id             :debounce
               :reason               :on-resolution
               :rf.trace/dispatch-id dispatch-id}})

(defn- invoke-cancel-ev
  [{:keys [dispatch-id time id] :or {dispatch-id 1 time 1025 id 600}}]
  {:id        id
   :operation :rf.machine.spawn/cancelled-on-join-resolution
   :time      time
   :tags      {:child-id :fetch :rf.trace/dispatch-id dispatch-id}})

;; ---- predicates ---------------------------------------------------------

(deftest cancellation-anchor?-rejects-impossible-channel-reason-tuples
  ;; Checking channel and reason membership independently would admit
  ;; both; the disjoint channel/reason matrix forbids them.
  (are [op reason] (false? (h/cancellation-anchor?
                             (destroy-ev {:machine-id :x :op op :reason reason})))
    :rf.machine.lifecycle/destroyed :explicit
    :rf.machine/destroyed           :parent-frame-destroyed))

(deftest cancel-cause-defaults-by-operation
  (are [ev cause] (= cause (h/cancel-cause ev))
    (assoc-in (http-abort-ev {}) [:tags :cancel-cause] :timeout) :timeout        ; an explicit tag wins
    (timer-cancel-ev {})                                         :on-resolution  ; then the :reason tag
    (invoke-cancel-ev {})                                        :join-resolved)) ; then the per-op default

;; ---- extract-cascade ----------------------------------------------------

(deftest extract-single-abort
  ;; Every row carries the canonical :rf.trace/dispatch-id, and the abort
  ;; pairs on its request-id.
  (is (= {:parent-decision  {:event-vec [:auth/logout] :t 1000 :machine-id nil
                             :dispatch-id 42 :trace-id 1}
          :child-teardowns  [{:child-id :user-session :spawned-id nil :parent-id nil
                              :invoke-id nil :t 1010 :reason :explicit
                              :last-state nil :inflight-count 1
                              :trace-id 2 :dispatch-id 42}]
          :effect-aborts    [{:fx :http :req {:request-id :req-1 :url "/api/profile"}
                              :t 1020 :cancel-cause :actor-destroyed
                              :request-id :req-1 :url "/api/profile"
                              :actor-id :user-session :correlation-id :req-1
                              :trace-id 3 :dispatch-id 42}]
          :total-elapsed-ms 20
          :empty-kind       nil}
         (h/extract-cascade
           [(dispatched-ev [:auth/logout] {:dispatch-id 42 :time 1000 :id 1})
            (destroy-ev {:machine-id :user-session :dispatch-id 42 :time 1010 :id 2})
            (http-abort-ev {:url "/api/profile" :dispatch-id 42 :time 1020 :id 3})]))))

(deftest extract-counts-an-actor-destroy-abort-once
  ;; Destroying an actor with one in-flight request emits the registry's
  ;; :rf.http/aborted-on-actor-destroy AND the transport's :rf.http/aborted
  ;; :reason :actor-destroyed echo for that request: one abort, not two.
  (let [actor   :rf.http/managed#1
        destroy (destroy-ev {:machine-id actor :dispatch-id 3 :time 1005 :id 67})
        abort   (http-abort-ev {:actor-id actor :dispatch-id 3 :time 1003 :id 64})
        c       (h/extract-cascade
                  [(dispatched-ev [:app/cancel] {:dispatch-id 3 :time 1000 :id 60})
                   abort
                   (http-aborted-ev {:request-id :req-1 :actor-id actor
                                     :dispatch-id 3 :time 1004 :id 65})
                   destroy])]
    (is (= [64] (mapv :trace-id (:effect-aborts c))))
    (is (= [1] (mapv :inflight-count (:child-teardowns c))))
    (is (= "1 child destroyed · 1 effect aborted · 3ms elapsed" (h/cascade-summary c)))
    (is (= 2 (count (:effect-aborts
                      (h/extract-cascade
                        [destroy
                         abort
                         (http-aborted-ev {:request-id :req-2 :actor-id actor
                                           :reason :user :dispatch-id 3 :id 66})]))))
        "an :rf.http/aborted for any other reason is its own abort")))

(deftest extract-many-aborts-sorted
  (is (= [[5020 :http] [5030 :ws] [5040 :http] [5050 :after] [5060 :machine-invoke]]
         (mapv (juxt :t :fx)
               (:effect-aborts
                 (h/extract-cascade
                   [(destroy-ev {:machine-id :checkout :dispatch-id 9 :time 5010 :id 2})
                    (http-abort-ev {:request-id :r3 :actor-id :checkout
                                    :dispatch-id 9 :time 5040 :id 5})
                    (http-abort-ev {:request-id :r1 :actor-id :checkout
                                    :dispatch-id 9 :time 5020 :id 3})
                    (ws-abort-ev {:actor-id :checkout :dispatch-id 9 :time 5030 :id 4})
                    (timer-cancel-ev {:dispatch-id 9 :time 5050 :id 6})
                    (invoke-cancel-ev {:dispatch-id 9 :time 5060 :id 7})]))))))

(deftest extract-nested-destroys
  ;; A parent teardown cascading to two children: every destroy in the
  ;; cascade is a teardown row, counting the aborts its actor held.
  (is (= [[:parent 0] [:child-a 1] [:child-b 1]]
         (mapv (juxt :child-id :inflight-count)
               (:child-teardowns
                 (h/extract-cascade
                   [(destroy-ev {:machine-id :parent  :time 1010 :id 2})
                    (destroy-ev {:machine-id :child-a :time 1011 :id 3})
                    (destroy-ev {:machine-id :child-b :time 1012 :id 4})
                    (http-abort-ev {:request-id :r1 :actor-id :child-a :time 1020 :id 5})
                    (http-abort-ev {:request-id :r2 :actor-id :child-b :time 1021 :id 6})]))))))

(deftest extract-without-an-anchor-has-no-trigger
  (is (= {:parent-decision nil :child-teardowns [] :effect-aborts []
          :total-elapsed-ms nil :empty-kind :no-trigger}
         (h/extract-cascade [])))
  (is (= :no-trigger
         (:empty-kind (h/extract-cascade
                        [(destroy-ev {:machine-id :x :reason :rf.machine/finished})])))
      "natural termination is not a cancellation"))

(deftest extract-focus-by-machine-id
  (is (= [:a]
         (mapv :child-id
               (:child-teardowns
                 (h/extract-cascade
                   [(destroy-ev {:machine-id :a :dispatch-id 1 :time 1000 :id 1})
                    (destroy-ev {:machine-id :b :dispatch-id 2 :time 2000 :id 2})]
                   {:kind :machine-id :id :a}))))))

;; ---- summarisers --------------------------------------------------------

(deftest cascade-summary-with-no-aborts-pluralises-children
  (is (= "2 children destroyed · 0 effects aborted"
         (h/cascade-summary
           (h/extract-cascade
             [(destroy-ev {:machine-id :a :time 1010 :id 1})
              (destroy-ev {:machine-id :b :time 1011 :id 2})])))))

(deftest should-collapse?-respects-threshold
  (are [n collapse?] (= collapse? (h/should-collapse? {:effect-aborts (vec (repeat n {}))}))
    10 false
    11 true))

;; ---- frame scoping ------------------------------------------------------
;;
;; Xray's trace buffer is every host frame's ring merged, and a dispatch-id
;; is unique only within a frame, so both correlation paths — dispatch-id
;; and the wall-clock window — can reach into a foreign frame.

(deftest extract-cascade-does-not-fold-a-peer-frames-abort
  ;; Frame B's abort lands 15 ms after frame A's teardown, inside the
  ;; wall-clock window.
  (let [buf   [(destroy-ev {:machine-id :user-session :time 1000 :id 1 :frame :frame/a})
               (http-abort-ev {:request-id :b-1 :actor-id :cart :dispatch-id 2
                               :time 1015 :id 2 :frame :frame/b})]
        focus {:kind :machine-id :id :user-session}]
    (is (= 1 (count (:effect-aborts (h/extract-cascade buf focus))))
        "a caller naming no frame scopes nothing")
    (is (= :no-aborts
           (:empty-kind (h/extract-cascade buf (assoc focus :frame :frame/a)))))))

(deftest extract-cascade-keeps-its-own-frames-rows
  ;; Both frames reuse dispatch-id 1; the scope keeps frame B's abort,
  ;; teardown and decision row its own.
  (let [c (h/extract-cascade
            [(dispatched-ev [:auth/logout] {:dispatch-id 1 :time 1000 :id 1 :frame :frame/a})
             (destroy-ev {:machine-id :user-session :time 1010 :id 2 :frame :frame/a})
             (http-abort-ev {:request-id :a-1 :time 1020 :id 3 :frame :frame/a})
             (dispatched-ev [:cart/clear] {:dispatch-id 1 :time 1005 :id 4 :frame :frame/b})
             (destroy-ev {:machine-id :cart :time 1015 :id 5 :frame :frame/b})
             (http-abort-ev {:request-id :b-1 :actor-id :cart :time 1025 :id 6 :frame :frame/b})]
            {:kind :dispatch-id :id 1 :frame :frame/b})]
    (is (= [[:b-1] [:cart] [:cart/clear]]
           [(mapv :request-id (:effect-aborts c))
            (mapv :child-id (:child-teardowns c))
            (-> c :parent-decision :event-vec)]))))

(deftest extract-cascade-keeps-frameless-rows-under-a-frame-scope
  ;; An actor-destroy abort fired outside the originating drain reaches Xray
  ;; with no frame tag and no dispatch-id: unattributable, not foreign.
  (is (= [:r1]
         (mapv :request-id
               (:effect-aborts
                 (h/extract-cascade
                   [(destroy-ev {:machine-id :user-session :time 1000 :id 1 :frame :frame/a})
                    (http-abort-ev {:request-id :r1 :dispatch-id nil :time 1015 :id 2})]
                   {:kind :machine-id :id :user-session :frame :frame/a}))))))

;; ---- formatters ---------------------------------------------------------

(deftest format-fx-label-shapes
  (is (= "HTTP POST /api/foo"
         (h/format-fx-label {:fx :http :req {:method :post} :url "/api/foo"}))))
