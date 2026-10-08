(ns re-frame.ssr-teardown-corner-test
  "Per-request frame teardown, per invariant. `ssr_teardown_load_test` proves
  the aggregate under load; this names the failing side-channel at first
  sight."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.response :as rf.ssr.response]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- held-slots
  "Whether `fid` holds a request slot, a response slot, a pending-error-trace
  buffer and a hydration-payload claim."
  [fid]
  [(some? (rf.ssr.request/get-request fid))
   (contains? @rf.ssr.response/response-slots fid)
   (contains? @rf.ssr.error-listener/pending-error-traces fid)
   (some? (rf.ssr.install/installed-payload fid))])

(deftest on-frame-destroyed-isolates-across-frames
  (testing "one destroy releases all four of frame A's side-channels, leaves
            frame B's intact (Spec 011 §Request/Response storage substrate),
            and a second destroy of A is a clean no-op"
    (let [fid-a :rf.test/iso-frame-a
          fid-b :rf.test/iso-frame-b]
      (doseq [fid [fid-a fid-b]]
        (rf.ssr.request/set-request! fid {:uri (str "/" (name fid)) :request-method :get})
        (rf.ssr.response/swap-response! fid (fn [r] (assoc r :status 200)))
        (swap! rf.ssr.error-listener/pending-error-traces
               update fid (fnil conj []) {:op-type :error :operation :rf.error/iso-probe})
        (swap! rf.ssr.install/installed-payloads
               assoc fid (rf.ssr.install/claim-record "digest-iso" :rf.test/root)))
      (rf.ssr.request/on-frame-destroyed! fid-a)
      (is (= [false false false false] (held-slots fid-a)))
      (is (= [true true true true] (held-slots fid-b)))
      (is (nil? (rf.ssr.request/on-frame-destroyed! fid-a))))))
