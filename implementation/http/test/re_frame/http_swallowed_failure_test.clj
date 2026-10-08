(ns re-frame.http-swallowed-failure-test
  "A failure reply with no target (`:on-failure nil`) is silenced, but a REAL
  (non-aborted) failure dropped that way is an error the app never sees, so the
  transport emits a ONE-SHOT `:rf.warning/failure-swallowed` dev trace. Aborts
  are legitimately silent and never warn.

  The swallow detection is private, so these drive the transport's
  `dispatch-failure!` directly with a synthetic ctx: with no `:handle` the
  once-only reply guard no-ops, and with no router the late-bind dispatch
  no-ops, so the only side effect is the warning trace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http.transport :as rf.http.transport]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:private dispatch-failure!         @#'rf.http.transport/dispatch-failure!)
(def ^:private failure-swallowed-warned? @#'rf.http.transport/failure-swallowed-warned?)

;; The one-shot latch is a `defonce` that outlives a deftest; reset it so every
;; case starts un-warned.
(use-fixtures :each (fn [t] (reset! failure-swallowed-warned? false) (t)))

(defn- swallowed-warnings
  "Run `body-fn` and return the `:rf.warning/failure-swallowed` rows it emitted."
  [body-fn]
  (let [captured (atom [])
        cb-id    ::http-swallowed-failure-cap]
    (try
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn)
      (filterv #(= :rf.warning/failure-swallowed (:operation %)) @captured)
      (finally
        (rf.trace.tooling/unregister-listener! cb-id)))))

(def ^:private ctx-on-failure-nil
  {:explicit-on-failure {:supplied? true :value nil}
   :url                 "https://example.test/data"
   :sensitive?          false})

(deftest swallowed-non-aborted-failure-emits-exactly-one-trace
  (testing "repeated swallowed failures collapse to ONE warning (fire-and-forget
            beacons must not flood the trace), which carries the url and the
            dropped failure"
    (let [warns (swallowed-warnings
                  #(dotimes [_ 3]
                     (dispatch-failure! ctx-on-failure-nil {:kind :rf.http/http-5xx :status 500})))]
      (is (= 1 (count warns)))
      (is (= {:url "https://example.test/data" :failure {:kind :rf.http/http-5xx :status 500}}
             (select-keys (:tags (first warns)) [:url :failure]))))))

(deftest aborted-failure-emits-no-swallow-trace
  (testing "a cancelled request that no longer wants its reply is silent by design"
    (is (empty? (swallowed-warnings
                  #(dispatch-failure! ctx-on-failure-nil {:kind :rf.http/aborted :reason :user}))))))

(deftest present-on-failure-emits-no-swallow-trace
  (testing "a failure routed to a present :on-failure target has a home"
    ;; The reply dispatch reads the frame stamp; `:rf/default` need not be live —
    ;; the reply lands as a frame-destroyed no-op.
    (is (empty? (swallowed-warnings
                  #(dispatch-failure! {:explicit-on-failure {:supplied? true :value [:api/load-error]}
                                       :url                 "https://example.test/data"
                                       :frame               :rf/default
                                       :sensitive?          false}
                                      {:kind :rf.http/http-5xx :status 500}))))))
