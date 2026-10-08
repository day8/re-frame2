(ns re-frame.ssr-request-cofx-test
  "The `:rf.server/request` cofx surfaces the frame's request slot, which a
  host adapter fills with `set-request!` before the drain and empties with
  `clear-request!`. The cofx is server-only and per-frame."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- observe!
  "Dispatch an event requiring the cofx on `frame`; return
  `[:present|:absent request]` as its handler saw it."
  [frame]
  (let [observed (atom :unset)]
    (rf/reg-event :req-test/observe
      {:rf.cofx/requires [:rf.server/request]}
      (fn [{:keys [rf.server/request] :as ctx} _]
        (reset! observed [(if (contains? ctx :rf.server/request) :present :absent) request])
        {}))
    (rf/dispatch-sync [:req-test/observe] {:frame frame})
    @observed))

(deftest cofx-injects-nil-when-slot-unpopulated
  (is (= [:present nil] (observe! (rf.frame/make-anon-frame-record! {:platform :server})))))

(deftest cofx-is-skipped-on-client-frame
  (with-trace-recorder! [traces]
    (is (= [:absent nil] (observe! (rf.frame/make-anon-frame-record! {:platform :client})))
        "the cofx did not run on a client frame")
    (when rf.interop/debug-enabled?
      (is (= [[:rf.server/request :client #{:server} :skipped]]
             (for [t @traces
                   :when (= :rf.cofx/skipped-on-platform (:operation t))]
               [(-> t :tags :rf.cofx/id)
                (-> t :tags :rf.cofx/platform)
                (-> t :tags :rf.cofx/registered-platforms)
                (:recovery t)]))
        "one skip trace naming the cofx, the platform and the declared set"))))

(deftest two-frames-carry-independent-request-data
  (let [frame-a   (rf.frame/make-anon-frame-record! {:platform :server})
        frame-b   (rf.frame/make-anon-frame-record! {:platform :server})
        request-a {:uri "/articles/aaa" :headers {"cookie" "session=user-a"}}
        request-b {:uri "/articles/bbb" :headers {"cookie" "session=user-b"}}
        observed  (atom {})]
    (rf.ssr/set-request! frame-a request-a)
    (rf.ssr/set-request! frame-b request-b)
    (rf/reg-event :req-test/read-isolated
      {:rf.cofx/requires [:rf.server/request]}
      (fn [{:keys [rf.server/request] frame :rf.frame/id} _]
        (swap! observed assoc frame request)
        {}))
    (rf/dispatch-sync [:req-test/read-isolated] {:frame frame-a})
    (rf/dispatch-sync [:req-test/read-isolated] {:frame frame-b})
    (is (= {frame-a request-a frame-b request-b} @observed))))

(deftest clear-request-removes-the-slot
  (let [server-frame (rf.frame/make-anon-frame-record! {:platform :server})]
    (rf.ssr/set-request! server-frame {:uri "/x"})
    (rf.ssr/clear-request! server-frame)
    (is (nil? (rf.ssr/get-request server-frame)))))
