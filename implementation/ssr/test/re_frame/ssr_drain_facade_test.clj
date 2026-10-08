(ns re-frame.ssr-drain-facade-test
  "`re-frame.ssr/drain-blocking-resources!`, the façade the Ring and
  streaming render paths call. The drain loop lives in the resources
  artefact behind the `:resources/drain-blocking-ssr!` hook, which the ssr
  slice never loads; only here can the hook-absent fast path and the façade's
  opts normalisation be pinned."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr :as rf.ssr]))

(def ^:private drain-key :resources/drain-blocking-ssr!)

(defn- restore-hook! [prev]
  (if prev
    (rf.late-bind/set-fn! drain-key prev)
    (do (swap! rf.late-bind/hooks dissoc drain-key)
        (rf.late-bind/invalidate-cache! drain-key))))

(deftest hook-absent-returns-the-settled-fast-path-shape
  (let [prev    (rf.late-bind/get-fn drain-key)
        settled {:settled? true :timed-out [] :route-blocking-failure nil}]
    (try
      (swap! rf.late-bind/hooks dissoc drain-key)
      (rf.late-bind/invalidate-cache! drain-key)
      (is (= [settled settled]
             [(rf.ssr/drain-blocking-resources! :ssr/some-frame)
              (rf.ssr/drain-blocking-resources!
                :ssr/some-frame {:ssr-blocking-timeout-ms 123 :pump! nil :tick-ms 9})])
          "the shape the Ring host consults, with or without opts")
      (finally
        (restore-hook! prev)))))

(deftest hook-present-forwards-resolved-opts-and-return
  (let [prev     (rf.late-bind/get-fn drain-key)
        calls    (atom [])
        sentinel {:settled? false :timed-out #{[:x]} :route-blocking-failure {:route :r}}
        my-pump  (fn [_tick] :pumped)]
    (try
      (rf.late-bind/set-fn! drain-key
                            (fn [frame-id opts] (swap! calls conj [frame-id opts]) sentinel))
      (is (= sentinel (rf.ssr/drain-blocking-resources! :ssr/f))
          "the hook's result rides back verbatim")
      (rf.ssr/drain-blocking-resources! :ssr/f {:pump! nil})
      (rf.ssr/drain-blocking-resources! :ssr/f {:ssr-blocking-timeout-ms 250 :pump! my-pump :tick-ms 7})
      ;; The default pump is read at assertion time: sibling suites reload
      ;; `re-frame.ssr`, minting a fresh fn object.
      (is (= [[:ssr/f {:deadline-ms 5000 :pump! @#'rf.ssr/default-blocking-pump! :tick-ms 5}]
              [:ssr/f {:deadline-ms 5000 :pump! nil :tick-ms 5}]
              [:ssr/f {:deadline-ms 250 :pump! my-pump :tick-ms 7}]]
             @calls)
          "defaults when absent; an EXPLICIT nil :pump! wins over the default pump")
      (finally
        (restore-hook! prev)))))
