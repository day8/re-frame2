(ns re-frame.ssr.ring.status-rewrite-always-on-test
  "The materialiser's fail-closed `:status` rewrite reports itself on the
  always-on error axis, which survives `-Dre-frame.debug=false`, as well as
  on the dev trace. A host that hand-builds the response accumulator or
  calls the public materialiser can reach the rewrite; the reserved
  `:rf.server/*` fx guard their own args."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.ring.pipeline :as rf.ssr.ring.pipeline]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(defn- status-rewrite-records
  "Run `thunk`; return the always-on status-rewrite records it fanned."
  [thunk]
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::status-rewrite-watch
      (fn [record]
        (when (= :rf.error/ssr-ring-response-status-invalid (:error record))
          (swap! records conj record))))
    (try
      (thunk)
      (finally
        (rf.error-emit/unregister-error-listener! ::status-rewrite-watch)))
    @records))

(deftest record-key-set-is-closed
  ;; The record ships off-box unredacted, so it is pinned whole against an
  ;; independently written expectation: a slot added later fails here.
  (let [ring    (atom nil)
        records (status-rewrite-records
                  #(reset! ring (rf.ssr.ring.pipeline/ssr-response->ring-response
                                  {:status "404" :headers [["content-type" "text/html"]]}
                                  "<p>x</p>")))]
    (is (= {:status 500 :headers {"content-type" "text/html"} :body "<p>x</p>"}
           @ring))
    (is (= [{:error       :rf.error/ssr-ring-response-status-invalid
             :frame       nil
             :where       :ssr-ring/ssr-response->ring-response
             :status-type "java.lang.String"
             :reason      :non-integer-status
             :recovery    :failed-closed-to-500}]
           (mapv #(dissoc % :time) records)))
    (is (integer? (:time (first records))))))

(deftest the-record-never-carries-the-offending-value
  ;; The raw status is caller-supplied and unbounded; only its type crosses.
  (let [secret   "sekrit-session-token"
        [record] (status-rewrite-records
                   #(rf.ssr.ring.pipeline/ssr-response->ring-response
                      {:status secret :headers []} "x"))]
    (is (= "java.lang.String" (:status-type record)))
    (is (not (contains? record :status)))
    (is (not-any? #{secret} (vals record)))))

(deftest a-well-formed-status-passes-through-and-reports-nothing
  (let [statuses (atom nil)
        records  (status-rewrite-records
                   #(reset! statuses
                            (mapv (fn [resp]
                                    (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                                               resp nil)))
                                  [{:status 201}
                                   {}
                                   {:redirect {:status 307 :location "/x"}}
                                   {:redirect {:location "/x"}}])))]
    (is (= [201 200 307 302] @statuses))
    (is (= [] records))))

(deftest one-rewrite-fans-exactly-one-record
  ;; A target-less redirect needs its status for the no-target warning and the
  ;; response, so the rewrite must be resolved once.
  (let [ring    (atom nil)
        records (status-rewrite-records
                  #(reset! ring (rf.ssr.ring.pipeline/ssr-response->ring-response
                                  {:redirect {:status "302"}} nil)))]
    (is (= 500 (:status @ring)))
    (is (= 1 (count records)))))

(deftest the-two-axes-split-by-posture
  ;; The record fires in every build; the dev warning, which alone keeps the
  ;; offending value, fires only where debug is enabled.
  (let [warnings (atom [])]
    (rf/register-listener! :trace ::status-rewrite-trace-watch
      (fn [ev] (when (= :rf.ssr/ssr-non-integer-status (:operation ev))
                 (swap! warnings conj (-> (:tags ev)
                                          (select-keys [:where :status :status-type])
                                          (assoc :op-type (:op-type ev) :recovery (:recovery ev)))))))
    (try
      (is (= 1 (count (status-rewrite-records
                        #(rf.ssr.ring.pipeline/ssr-response->ring-response
                           {:status "404" :headers []} "x")))))
      (finally
        (rf/unregister-listener! :trace ::status-rewrite-trace-watch)))
    ;; The dev warning's slots are the ones the Spec 009 catalogue row lists.
    (is (= (if rf.interop/debug-enabled?
             [{:op-type     :warning
               :where       :ssr-ring/ssr-response->ring-response
               :status      "404"
               :status-type "java.lang.String"
               :recovery    :failed-closed-to-500}]
             [])
           @warnings))))
