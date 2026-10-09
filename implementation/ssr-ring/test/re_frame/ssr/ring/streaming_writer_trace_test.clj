(ns re-frame.ssr.ring.streaming-writer-trace-test
  "A write that throws after the streamed head commits is reported as
  `:rf.error/ssr-streaming-writer-failed`, naming the chunk in flight, on the
  trace bus and on the always-on error-listener axis (Spec 011 §Failure
  semantics)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.ring.lifecycle :as rf.ssr.ring.lifecycle]
            [re-frame.ssr.ring.pipeline :as rf.ssr.ring.pipeline]
            [re-frame.ssr.ring.streaming :as rf.ssr.ring.streaming]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            [re-frame.test-support :refer [with-trace-recorder!]])
  (:import [java.io OutputStream PipedInputStream PipedOutputStream]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(defn- throw-on-nth-write-stream
  "An OutputStream whose `n`th write throws an IOException; the others are
  dropped. The writer writes once per chunk, so `n` picks the phase."
  ^OutputStream [n]
  (let [calls  (atom 0)
        write! #(when (= n (swap! calls inc))
                  (throw (java.io.IOException. (str "forced-write-failure-at-" n))))]
    (proxy [OutputStream] []
      (write
        ([_] (write!))
        ([_ _ _] (write!)))
      (flush [])
      (close []))))

(deftest writer-failed-trace-carries-distinct-phase-per-chunk
  (testing "a throw on each chunk's write is one writer-failed trace naming that
            phase, and the boundary id inside a continuation drain"
    (rf/reg-event :rf.test.phase/init {:platforms #{:server}} (fn [_ _] {}))
    (rf/reg-view ^{:rf/id :rf.test.phase/root} phase-root []
      [:main
       [:rf/suspense-boundary {:id :rf.test.phase/b :fallback [:p "loading"]}
        [:p "done"]]])
    (let [opts {:initial-events [[:rf.test.phase/init]]
                :root-view      [(rf/view :rf.test.phase/root)]
                :payload        :rf.ssr.payload/whole-app-db}]
      ;; The boundary leaves app-db unchanged, so it writes no delta chunk:
      ;; 1 prefix, 2 shell html, 3 boundary template, 4 final payload, 5 suffix.
      (doseq [[n phase boundary-id] [[1 :shell-prefix]
                                     [3 :continuation-template :rf.test.phase/b]
                                     [4 :final-payload]
                                     [5 :suffix]]]
        (let [{:keys [frame-id]} (rf.ssr.ring.pipeline/setup-request-frame!
                                   opts {:uri "/" :request-method :get})
              rendered (rf.ssr.ring.streaming/render-streaming-shell! frame-id opts)
              events   (with-trace-recorder! [captured]
                         (@#'rf.ssr.ring.streaming/run-streaming-writer!
                           (throw-on-nth-write-stream n) frame-id rendered opts)
                         (filterv #(= :rf.error/ssr-streaming-writer-failed (:operation %))
                                  @captured))]
          (rf.ssr.ring.lifecycle/destroy-frame-quietly! frame-id)
          (is (= [{:op-type  :error
                   :recovery :truncate-and-close
                   :tags     (cond-> {:category   :rf.error/ssr-streaming-writer-failed
                                      :frame      frame-id
                                      :exception  (str "forced-write-failure-at-" n)
                                      :ex-class   "java.io.IOException"
                                      :phase      phase
                                      :committed? true}
                               boundary-id (assoc :boundary-id boundary-id))}]
                 (mapv #(select-keys % [:op-type :recovery :tags]) events))
              (str "throw on write " n)))))))

(deftest writer-failed-reaches-the-always-on-listener-under-debug-off
  (testing "with the dev trace elided (debug off) the failure still reaches the
            always-on error listener — telemetry only, the 200 is already sent"
    (let [pipe-in  (PipedInputStream. 1024)
          pipe-out (PipedOutputStream. pipe-in)
          seen     (atom [])]
      (.close pipe-in)
      (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
      (try
        (with-redefs [rf.interop/debug-enabled? false]
          (@#'rf.ssr.ring.streaming/run-streaming-writer!
            pipe-out :no-such-frame {:shell-prefix "<html>" :shell-html "" :continuations []} {}))
        (finally
          (rf.error-emit/unregister-error-listener! ::recorder)))
      (is (= [{:frame :no-such-frame :phase :shell-prefix
               :recovery :truncate-and-close :committed? true}]
             (->> @seen
                  (filter #(= :rf.error/ssr-streaming-writer-failed (:error %)))
                  (mapv #(select-keys % [:frame :phase :recovery :committed?]))))))))
