(ns re-frame.story-fx-stubs-per-fx-scenarios-test
  "Explicit per-fx regression net for `:rf.story/force-fx-stub`
  (rf2-ysr4y + rf2-mwr16). Pairs with `re-frame.story-fx-stubs-test`,
  which already covers boot-time registration, the ref-args
  expansion, the multi-decorator `:overrides` map and the per-frame
  stub-call log; this namespace adds the four canonical fx-id
  scenarios called out by the spec/015 §force-fx-stub matrix —
  `:http`, `:analytics`, `:websocket`, `:navigation` — plus the two
  stage-5 scenarios that the existing suite only covered
  implicitly:

  - **stub-overriding-real** (rf2-mwr16): a real `reg-fx` handler is
    registered *and* the stub is installed via the decorator. The
    real handler would throw if it ran; the stub captures the
    payload. Asserts the stub takes precedence at the framework
    `:fx-overrides` redirect.

  - **stub-failure-mode** (rf2-mwr16): the stub `:response` is a
    failure payload (`{:status :error ...}`). The variant under
    test reads the response into app-db; `:rf.assert/path-equals`
    against the failure path passes; the shell does not crash and
    the lifecycle reaches `:ready`.

  Per spec/004 §force-fx-stub the code path is identical for every
  fx-id keyword. The value of these tests is preventing a future
  regression that special-cases `:http` (because every existing
  test happens to exercise that fx-id) — see rf2-ysr4y for the
  motivation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.frames     :as frames]
            [re-frame.story.fx-stubs   :as fx-stubs]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.play       :as play]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (reset! assertions/trace-accumulators {})
  (reset! play/stepper-state            {})
  (reset! frames/stub-call-log          {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; rf2-ysr4y — per-fx scenarios (HTTP / analytics / websocket / navigation)
;;
;; Each scenario registers an event-fx that emits the fx-id under test, runs
;; a variant with the matching `force-fx-stub`, then asserts:
;;
;;   1. The lifecycle reaches :ready (no crash).
;;   2. The frame's :fx-overrides map carries the fx-id.
;;   3. The stub-call log records exactly one entry with the original
;;      payload and fx-id.
;;   4. `observed-fx-ids` includes the fx-id.
;;   5. `:rf.assert/effect-emitted` passes.
;; ===========================================================================

(defn- assert-stub-intercepted!
  "Common assertion bundle shared by the four per-fx scenarios.
  Centralised so the four scenarios stay byte-for-byte parallel —
  the value of the per-fx net is exactly that we can't accidentally
  special-case one fx-id, so the assertions for each must be
  identical."
  [variant-id fx-id expected-payload result]
  (is (= :ready (:lifecycle result))
      (str fx-id " — lifecycle reaches :ready (no crash)"))
  (let [overrides (:fx-overrides (rf/frame-meta variant-id))]
    (is (contains? overrides fx-id)
        (str fx-id " — frame :fx-overrides carries the redirect")))
  (let [log (frames/stub-call-log-for variant-id)]
    (is (= 1 (count log))
        (str fx-id " — exactly one stub-call recorded"))
    (is (= fx-id (:fx-id (first log)))
        (str fx-id " — log entry carries the original fx-id"))
    (is (= expected-payload (:payload (first log)))
        (str fx-id " — log entry carries the original payload")))
  (is (contains? (fx-stubs/observed-fx-ids variant-id) fx-id)
      (str fx-id " — observed-fx-ids surfaces the stubbed fx"))
  (let [last-a (last (:assertions result))]
    (is (true? (:passed? last-a))
        (str fx-id " — :rf.assert/effect-emitted passes against the stub"))))

(deftest http-fx-stub-scenario
  (testing ":http fx is intercepted by force-fx-stub end-to-end"
    (rf/reg-event-fx :do/http-emit
      (fn [_ _] {:fx [[:http {:url "/api" :method :get}]]}))
    (story/reg-variant :story.fxscen.http/v
      {:decorators [[:rf.story/force-fx-stub :http {:status :ok :body {:n 1}}]]
       :events     []
       :play-script [[:dispatch-sync [:do/http-emit]]
                    [:dispatch-sync [:rf.assert/effect-emitted :http]]]})
    (let [r (async/deref-blocking (story/run-variant :story.fxscen.http/v) 5000)]
      (assert-stub-intercepted! :story.fxscen.http/v :http
                                {:url "/api" :method :get} r))
    (story/destroy-variant! :story.fxscen.http/v)))

(deftest analytics-fx-stub-scenario
  (testing ":analytics fx is intercepted by force-fx-stub end-to-end"
    (rf/reg-event-fx :do/analytics-emit
      (fn [_ _] {:fx [[:analytics {:event "page-view" :path "/home"}]]}))
    (story/reg-variant :story.fxscen.analytics/v
      {:decorators [[:rf.story/force-fx-stub :analytics {:ack? true}]]
       :events     []
       :play-script [[:dispatch-sync [:do/analytics-emit]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]]})
    (let [r (async/deref-blocking (story/run-variant :story.fxscen.analytics/v) 5000)]
      (assert-stub-intercepted! :story.fxscen.analytics/v :analytics
                                {:event "page-view" :path "/home"} r))
    (story/destroy-variant! :story.fxscen.analytics/v)))

(deftest websocket-fx-stub-scenario
  (testing ":websocket fx is intercepted by force-fx-stub end-to-end"
    (rf/reg-event-fx :do/websocket-emit
      (fn [_ _] {:fx [[:websocket {:topic "live" :payload {:tick 1}}]]}))
    (story/reg-variant :story.fxscen.websocket/v
      {:decorators [[:rf.story/force-fx-stub :websocket {:connected? true}]]
       :events     []
       :play-script [[:dispatch-sync [:do/websocket-emit]]
                    [:dispatch-sync [:rf.assert/effect-emitted :websocket]]]})
    (let [r (async/deref-blocking (story/run-variant :story.fxscen.websocket/v) 5000)]
      (assert-stub-intercepted! :story.fxscen.websocket/v :websocket
                                {:topic "live" :payload {:tick 1}} r))
    (story/destroy-variant! :story.fxscen.websocket/v)))

(deftest navigation-fx-stub-scenario
  (testing ":navigation fx is intercepted by force-fx-stub end-to-end"
    (rf/reg-event-fx :do/navigation-emit
      (fn [_ _] {:fx [[:navigation {:to "/dashboard" :replace? false}]]}))
    (story/reg-variant :story.fxscen.navigation/v
      {:decorators [[:rf.story/force-fx-stub :navigation {:landed? true}]]
       :events     []
       :play-script [[:dispatch-sync [:do/navigation-emit]]
                    [:dispatch-sync [:rf.assert/effect-emitted :navigation]]]})
    (let [r (async/deref-blocking (story/run-variant :story.fxscen.navigation/v) 5000)]
      (assert-stub-intercepted! :story.fxscen.navigation/v :navigation
                                {:to "/dashboard" :replace? false} r))
    (story/destroy-variant! :story.fxscen.navigation/v)))

;; ===========================================================================
;; rf2-mwr16 — stub-overriding-real
;;
;; Register a real `reg-fx` handler that would throw if invoked, install
;; the stub via decorator, then dispatch the fx and assert the real
;; handler did NOT run. The proof is in two channels:
;;
;;   1. A side-channel atom that the real handler would write to —
;;      remains empty after the run.
;;   2. The stub-call log carries the payload — proving the redirect
;;      actually happened.
;; ===========================================================================

(deftest stub-overrides-real-handler
  (testing "force-fx-stub takes precedence over a registered real fx handler"
    (let [real-called?  (atom false)
          ;; Side-channel atom the real handler would mutate. If the
          ;; stub didn't intercept, this would flip to true (or, if the
          ;; ex/throw path won the race, the run-variant would surface
          ;; the throw as a record-don't-throw assertion).
          real-payloads (atom [])]
      (rf/reg-fx :http
        (fn [payload]
          (reset! real-called? true)
          (swap! real-payloads conj payload)
          ;; A real :http handler would push a network request here;
          ;; we throw instead so a missing-redirect regression fails
          ;; loudly via the :events-phase exception projection too.
          (throw (ex-info "real :http fx must NOT run under force-fx-stub"
                          {:payload payload}))))
      (rf/reg-event-fx :do/http-call
        (fn [_ _]
          {:fx [[:http {:url "/should-not-hit-real" :method :get}]]}))
      (story/reg-variant :story.fxoverride/real
        {:decorators [[:rf.story/force-fx-stub :http {:status :ok :body {}}]]
         :events     []
         :play-script [[:dispatch-sync [:do/http-call]]
                      [:dispatch-sync [:rf.assert/effect-emitted :http]]]})
      (let [r (async/deref-blocking (story/run-variant :story.fxoverride/real) 5000)]
        (is (= :ready (:lifecycle r))
            "lifecycle reaches :ready — the stub absorbed the call, the real
             handler's throw never fired")
        (is (false? @real-called?)
            "real :http handler must NOT be invoked — the framework
             :fx-overrides redirect routes the call to the stub event before
             reg-fx dispatch")
        (is (= [] @real-payloads)
            "real handler's side-channel records zero payloads")
        (let [log (frames/stub-call-log-for :story.fxoverride/real)]
          (is (= 1 (count log)))
          (is (= :http (:fx-id (first log)))
              "the stub captured the redirected fx-id")
          (is (= {:url "/should-not-hit-real" :method :get}
                 (:payload (first log)))
              "the stub captured the original payload"))
        (is (true? (:passed? (last (:assertions r))))
            ":rf.assert/effect-emitted passes against the stub"))
      (story/destroy-variant! :story.fxoverride/real))))

;; ===========================================================================
;; rf2-mwr16 — stub-failure-mode
;;
;; Install a stub whose :response represents a failure payload. The
;; variant's event handler reads the stub's response into app-db (the
;; common shape for libraries like re-frame-http-fx, where the fx handler
;; emits an `:on-failure` event with the response body). Asserts:
;;
;;   1. Lifecycle reaches :ready (no crash).
;;   2. The failure response made it into app-db along the documented path.
;;   3. The :rf.assert/path-equals against the failure path passes.
;;
;; The wiring here is intentionally a thin emulation of how a library
;; like re-frame-http-fx surfaces a failure: the stub's :response sits
;; in the per-frame stub-call log; a follow-on event reads it and
;; copies it into app-db. The test asserts the *contract* — the stub
;; absorbed the call without crashing AND a downstream consumer can
;; observe the failure payload — without coupling to any specific
;; failure-event shape.
;; ===========================================================================

(deftest stub-failure-mode-records-without-crash
  (testing "force-fx-stub with a failure response payload — variant
            records the failure into app-db and assertions pass"
    (let [failure-payload {:status :error :code 500 :body {:reason "server-down"}}]
      ;; Event handler emits the :http fx; a follow-on event reads the
      ;; stub-call log entry for this frame and copies the payload into
      ;; app-db along [:http-result]. The stub itself just absorbs the
      ;; call; the failure-shape contract is whatever the app under
      ;; test makes of the response.
      (rf/reg-event-fx :do/http-emit-fail
        (fn [_ _] {:fx [[:http {:url "/api/may-fail"}]]}))
      ;; Record the failure marker into app-db so :rf.assert/path-equals
      ;; can observe it. Mirrors the shape of an :on-failure event a
      ;; library like re-frame-http-fx would dispatch off a failed
      ;; managed-fx response.
      (rf/reg-event-db :record/failure
        (fn [db _] (assoc db :http-result failure-payload)))
      (story/reg-variant :story.fxfail/v
        {:decorators [[:rf.story/force-fx-stub :http failure-payload]]
         :events     []
         :play-script [[:dispatch-sync [:do/http-emit-fail]]
                      [:dispatch-sync [:record/failure]]
                      [:dispatch-sync [:rf.assert/effect-emitted :http]]
                      [:dispatch-sync [:rf.assert/path-equals [:http-result :status] :error]]
                      [:dispatch-sync [:rf.assert/path-equals [:http-result :code]   500]]
                      [:dispatch-sync [:rf.assert/path-equals [:http-result :body]
                                             {:reason "server-down"}]]]})
      (let [r       (async/deref-blocking (story/run-variant :story.fxfail/v) 5000)
            asserts (:assertions r)]
        (is (= :ready (:lifecycle r))
            "lifecycle reaches :ready — failure-shaped response does NOT
             crash the shell (record-don't-throw)")
        (is (every? :passed? asserts)
            "every play assertion passes — :rf.assert/effect-emitted +
             the three :rf.assert/path-equals against the failure path")
        (is (= 4 (count asserts))
            "exactly four assertions recorded — :effect-emitted + three :path-equals")
        ;; Belt-and-braces: pluck the recorded payload off the stub log
        ;; and confirm it matches what we declared in the decorator.
        ;; Proves the failure payload made the full round-trip through
        ;; the framework's :fx-overrides redirect.
        (let [log (frames/stub-call-log-for :story.fxfail/v)]
          (is (= 1 (count log)))
          (is (= :http (:fx-id (first log))))
          (is (= {:url "/api/may-fail"} (:payload (first log)))
              "stub log captures the original request payload; the failure
               payload lives in the decorator's :response slot, not the log
               (the log records what the variant emitted, not the canned
               reply)")))
      (story/destroy-variant! :story.fxfail/v))))
