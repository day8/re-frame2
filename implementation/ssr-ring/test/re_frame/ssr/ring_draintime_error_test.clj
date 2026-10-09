(ns re-frame.ssr.ring-draintime-error-test
  "Drain-time error attribution at the ssr-ring wire boundary. Many live
  per-request server frames is the canonical SSR shape; a drain-time 404 must
  land on the response of the request whose frame emitted it, which holds only
  if each drain-time trace carries its emitting `:frame` (with none, the
  projector no-ops and the response keeps its default 200)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(deftest two-concurrent-frames-attribute-drain-time-404-per-frame
  (rf/reg-route :route/home {} "/")
  (rf/reg-event :init/route-from-uri
    {:platforms        #{:server}
     :rf.cofx/requires [:rf.server/request]}
    (fn [{request :rf.server/request} _]
      {:fx [[:dispatch [:rf.route/handle-url-change (:uri request)]]]}))
  (let [handler (rf.ssr.ring/ssr-handler
                  {:initial-events [[:init/route-from-uri]]
                   :root-view      [:main "concurrent root"]
                   :payload        :rf.ssr.payload/whole-app-db})]
    (rf.ssr.ring.test-support/with-jetty [port handler]
      (let [client  (rf.ssr.ring.test-support/new-http-client)
            ;; 8 threads x 10 requests, alternating unmatched (404) and "/" (200).
            results (->> (for [t (range 8)]
                           (future
                             (doall
                               (for [i (range 10)
                                     :let [miss? (even? (+ t i))]]
                                 [(if miss? :miss :hit)
                                  (:status (rf.ssr.ring.test-support/http-get
                                             client port (if miss? (str "/missing/t" t "-i" i) "/") 30))]))))
                         doall
                         (mapcat deref))]
        (is (= {[:miss 404] 40 [:hit 200] 40} (frequencies results)))))))
