(ns re-frame.http-swallowed-failure-test
  "rf2-rl5tt — `:on-failure nil` silences the failure reply (fire-and-forget),
  but a REAL (non-aborted) failure routed into that silence is an error the
  app never sees. Per the committed no-silent-swallow principle, the transport
  emits a ONE-SHOT `:rf.warning/failure-swallowed` dev trace the first time a
  non-aborted failure is dropped by `:on-failure nil`. Aborts are legitimately
  silent and MUST NOT warn.

  These exercise the transport's reply-dispatch surface directly via `#'`
  (the swallow detection is private), so the three contract cases run
  synchronously and deterministically — no socket, no abort-race timing.
  `dispatch-reply!` no-ops cleanly when no late-bind router is registered
  (`encoding/dispatch-reply-via-late-bind!` short-circuits on an absent
  `:router/dispatch!`), so calling the failure-dispatch helper with a
  synthetic ctx fires the swallow-detection path without needing a runtime."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http.transport :as transport]
            [re-frame.trace :as trace]))

;; Private surface reached via #' (same discipline as http_decode_test.clj).
(def ^:private dispatch-failure!         @#'transport/dispatch-failure!)
(def ^:private on-failure-silenced?      @#'transport/on-failure-silenced?)
(def ^:private failure-swallowed-warned? @#'transport/failure-swallowed-warned?)

(defn- reset-latch [t]
  ;; The one-shot latch is a `defonce` that persists across deftests in the
  ;; same JVM; reset it before each so every case starts un-warned.
  (reset! failure-swallowed-warned? false)
  (t))

(use-fixtures :each reset-latch)

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    ::http-swallowed-failure-cap]
    (try
      (trace/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (trace/unregister-listener! cb-id)))))

(defn- swallowed-warnings [captured]
  (filter #(= :rf.warning/failure-swallowed (:operation %)) @captured))

;; A synthetic ctx carrying just the slots the dispatch-failure! / swallow
;; path reads: the explicit-on-failure decision map, :url, :sensitive?. No
;; :handle (the once-only reply guard no-ops without one) and no router (the
;; late-bind dispatch no-ops), so the call is side-effect-free apart from the
;; (intended) swallow-warning trace.
(defn- ctx-on-failure-nil []
  {:explicit-on-failure {:supplied? true :value nil}
   :url                 "https://example.test/data"
   :sensitive?          false})

(defn- ctx-on-failure-present []
  ;; EP-0002 (rf2-nn0jqa): the present-:on-failure path actually dispatches
  ;; the reply event, so the synthetic ctx must carry the frame stamp the
  ;; reply dispatch reads (the `:on-failure nil` siblings are silenced and
  ;; never dispatch, so they need none). `:rf/default` need not be a live
  ;; frame here — the reply lands as a frame-destroyed no-op, which this
  ;; test (asserting only the absence of a swallow warning) is indifferent to.
  {:explicit-on-failure {:supplied? true :value [:api/load-error]}
   :url                 "https://example.test/data"
   :frame               :rf/default
   :sensitive?          false})

(deftest on-failure-silenced?-detects-explicit-nil-only
  (testing "rf2-rl5tt — the silence predicate mirrors build-reply-event's
            silence branch: supplied? true AND value nil"
    (is (true?  (on-failure-silenced? (ctx-on-failure-nil)))
        "explicit :on-failure nil is silenced")
    (is (false? (on-failure-silenced? (ctx-on-failure-present)))
        "an explicit event-vector target is not silenced")
    (is (false? (on-failure-silenced? {:explicit-on-failure {:supplied? false :value nil}}))
        "the omitted default (reply-to-origin) is not silenced")))

(deftest swallowed-non-aborted-failure-emits-exactly-one-trace
  (testing "rf2-rl5tt — a NON-aborted failure dropped by :on-failure nil
            emits exactly one :rf.warning/failure-swallowed trace carrying
            the failure + a human :reason"
    (with-trace-capture
      (fn [captured]
        (dispatch-failure! (ctx-on-failure-nil)
                           {:kind :rf.http/http-5xx :status 500})
        (let [warns (swallowed-warnings captured)]
          (is (= 1 (count warns))
              (str "expected exactly one swallowed-failure warning; saw "
                   (count warns)))
          (let [w (first warns)]
            (is (= :warning (:op-type w)))
            (is (= "https://example.test/data" (get-in w [:tags :url]))
                "the (privacy-prepared) url rides the tags")
            (is (= :rf.http/http-5xx (get-in w [:tags :failure :kind]))
                "the dropped failure rides the tags for diagnosis")
            (is (string? (get-in w [:tags :reason]))
                "a human-readable :reason explains the swallow")))))))

(deftest aborted-failure-emits-no-swallow-trace
  (testing "rf2-rl5tt — an aborted failure dropped by :on-failure nil emits
            NO swallow warning: a cancelled request that no longer wants its
            reply is correct-by-design silence, not a swallowed error. Holds
            for every abort reason."
    (with-trace-capture
      (fn [captured]
        (doseq [reason [:user :actor-destroyed :timeout]]
          (dispatch-failure! (ctx-on-failure-nil)
                             {:kind :rf.http/aborted :reason reason}))
        (is (empty? (swallowed-warnings captured))
            "no swallowed-failure warning fires for aborts")))))

(deftest present-on-failure-emits-no-swallow-trace
  (testing "rf2-rl5tt — a failure routed to a PRESENT :on-failure target is
            not swallowed, so no warning fires (the reply has a home)"
    (with-trace-capture
      (fn [captured]
        (dispatch-failure! (ctx-on-failure-present)
                           {:kind :rf.http/http-5xx :status 500})
        (is (empty? (swallowed-warnings captured))
            "a present :on-failure target suppresses the swallow warning")))))

(deftest swallow-warning-is-one-shot-per-runtime
  (testing "rf2-rl5tt — repeated swallowed non-aborted failures collapse to a
            single warning (the per-runtime latch); fire-and-forget telemetry
            beacons that knowingly opt out must not flood the trace surface"
    (with-trace-capture
      (fn [captured]
        (dotimes [_ 5]
          (dispatch-failure! (ctx-on-failure-nil)
                             {:kind :rf.http/transport :message "boom"}))
        (is (= 1 (count (swallowed-warnings captured)))
            "the one-shot latch collapses repeated swallows to a single warning")))))
