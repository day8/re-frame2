(ns re-frame.capture-frame-reincarnation-sink-route-cljs-test
  "A stale `rf/capture-frame` op's dead-incarnation failure stays OUT of the
  same-id successor frame's own `:observability :errors` sink.

  An op on a bundle captured for incarnation A, invoked after A is destroyed
  and a same-id successor B reseated, recovers-but-emits
  `:rf.error/frame-destroyed`. The frame-owned sink route resolves a record's
  bare frame id to the CURRENT frame, so both rejection sites pass
  `route-frame?` false: the synchronous supersession pre-check, and the late
  expected-incarnation fence in `dispatch!` / `dispatch-sync!` /
  `subscribe-in-frame`, reached when B replaces A between the pre-check and
  the resolve. The corpus record and the dev trace still fire exactly once,
  with A's attribution. That the default route delivers a live frame's
  ordinary error to its sink is
  `re-frame.observability-routing-cljs-test/error-routes-projected-to-declared-error-sink`.

  The sink route and the corpus record are always-on, so everything except
  the dev-trace count runs under the production gate. `probe-vacuity!` lands a
  real record in B's sink afterwards, which is what makes its zero count a
  real suppression rather than an unwired sink."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core          :as rf]
            [re-frame.error-emit    :as rf.error-emit]
            [re-frame.frame         :as rf.frame]
            [re-frame.interop       :as rf.interop]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support  :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.error-emit/clear-error-listeners!)
                      (rf.observability/clear-observability-sinks!))}))

;; A distinctive body leaf, so a leak into a sink would be unmistakable.
(def ^:private secret-event    [:audit/secret {:password :TOP-SECRET}])
(def ^:private subscribe-query [:reinc/n {:token :SUBSCRIBE-IDENTITY}])

;; `:expected` is the attribution the surviving corpus record carries. Only a
;; subscribe pins `:event` (it keeps RAW query identity); a dispatch's `:event`
;; is elision-dependent and the route suppression does not touch it.
(def ^:private op-cases
  [{:op :subscribe     :invoke (fn [h] ((:subscribe h)     subscribe-query))
    :expected {:event-id :reinc/n :event subscribe-query}}
   {:op :dispatch      :invoke (fn [h] ((:dispatch h)      secret-event))
    :expected {:event-id :audit/secret}}
   {:op :dispatch-sync :invoke (fn [h] ((:dispatch-sync h) secret-event))
    :expected {:event-id :audit/secret}}])

;; Registered so a leak into B would resolve a real handler / reaction.
(defn- register-event+sub! []
  (rf/reg-event :audit/secret (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
  (rf/reg-sub   :reinc/n      (fn [db _] (:n db))))

(defn- make-frame-with-error-sink!
  [id sink-id seen]
  (rf.observability/register-observability-sink! sink-id (fn [r] (swap! seen conj r)))
  (rf/make-frame {:id id
                  :observability {:errors [{:sink sink-id
                                            :rf.egress/profile :rf.egress/off-box-observability}]}}))

(defn- capture-emits
  "Run `thunk`, collecting the corpus `:rf.error/frame-destroyed` records and
  the dev-trace events it fans. Returns `{:records :traces :result}`."
  [thunk]
  (let [recs   (atom [])
        traces (atom [])]
    (rf.error-emit/register-error-listener!
      ::records (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! recs conj r))))
    (rf.trace.tooling/register-listener!
      ::traces (fn [ev] (when (= :rf.error/frame-destroyed (:operation ev)) (swap! traces conj ev))))
    (try
      (let [result (thunk)]
        {:records @recs :traces @traces :result result})
      (finally
        (rf.error-emit/unregister-error-listener! ::records)
        (rf.trace.tooling/unregister-listener! ::traces)))))

(defn- probe-vacuity!
  [fid b-sink]
  (let [before (count @b-sink)]
    (rf.observability/route-error!
      :rf.error/handler-exception [:qjfrw/probe] :qjfrw/probe fid nil 0 0 nil)
    (is (= (inc before) (count @b-sink))
        "B's sink is live — a directly-routed ordinary error reaches it")))

;; The late seam: a one-shot interposition on the pre-check's own liveness read
;; runs `make-b!` (destroy A, reseat same-id B) at the moment the pre-check
;; validates A, so the op passes the pre-check and meets B at the late fence.
(defn- run-superseded-after-precheck
  [frame-id a-token make-b! op]
  (let [real  rf.frame/frame-incarnation-live?
        fired (atom false)]
    (with-redefs [rf.frame/frame-incarnation-live?
                  (fn [id token]
                    (let [live? (real id token)]
                      (when (and (not @fired)
                                 (= id frame-id)
                                 (identical? token a-token)
                                 live?)
                        ;; set first, so make-b!'s own liveness reads take the real path
                        (reset! fired true)
                        (make-b!))
                      live?))]
      (op))))

(deftest stale-capture-never-reaches-successor-error-sink
  (doseq [seam [:pre-check :late-mismatch]
          {:keys [op invoke expected]} op-cases]
    (testing (str "stale " (name op) " at the " (name seam) " seam")
      (register-event+sub!)
      (let [fid     (keyword (str "qjfrw." (name seam)) (name op))
            sink-id (keyword "qjfrw.sinks" (str (name seam) "-" (name op)))
            b-sink  (atom [])
            make-b! (fn []
                      (rf/destroy-frame! fid)
                      (make-frame-with-error-sink! fid sink-id b-sink))
            _       (rf/make-frame {:id fid})
            a-token (rf.frame/frame-incarnation-token fid)
            stale   (rf/capture-frame fid)
            emits   (case seam
                      :pre-check     (do (make-b!)
                                         (capture-emits #(invoke stale)))
                      :late-mismatch (capture-emits
                                       #(run-superseded-after-precheck
                                          fid a-token make-b! (fn [] (invoke stale)))))]
        (is (zero? (count @b-sink))
            "A's dead-incarnation failure never reaches successor B's :errors sink")
        (is (= [(merge {:frame fid :op op} expected)]
               (mapv #(select-keys % (into [:frame :op] (keys expected))) (:records emits)))
            "exactly one corpus record, keeping A's bare frame id, the op realm and the head")
        (when rf.interop/debug-enabled?
          (is (= 1 (count (:traces emits))) "exactly one dev trace on axis 2"))
        (is (nil? (:result emits)) "the stale op recovers to nil")
        (is (nil? (:marked-by (rf/app-db-value fid))) "the stale op mutated nothing in B")
        (probe-vacuity! fid b-sink)))))
