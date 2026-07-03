(ns re-frame.http-body-prep-failure-test
  "rf2-065xo finding 1 (JVM) — body realization + body encoding are a
  MANAGED request-preparation phase.

  Defect (closed by this test): `run-attempt!` realized a `:body` thunk
  and ran `encoding/encode-body` in its outer binding `let` — BEFORE
  `record-in-flight!` registered the handle and BEFORE the platform
  transport try/catch. A throwing body thunk or a non-serialisable body
  ESCAPED `run-attempt!` and surfaced as a generic
  `:rf.error/fx-handler-exception` (the catch-all in `re-frame.fx`'s fx
  walk) instead of the caller's `:on-failure` with a `:rf.http/*` shape.
  The caller waited forever; retry/backoff/cancellation were skipped;
  instrumentation saw the wrong taxonomy.

  Fix: `prepare-body!` runs the realization + encoding AFTER the handle
  is registered, catches a throw, and routes a canonical
  `:rf.http/transport` failure (`:stage :request-prep`) through the
  normal `maybe-retry!` path — so `:on-failure`, retry policy, trace
  metadata, abort precedence, and sensitivity handling all stay
  consistent. `:rf.http/transport` is the spec's category for an error
  before the HTTP transaction completed (Spec 014 §Failure categories,
  closed set — no new category minted), and it is in the retryable
  subset, so a configured retry re-runs the attempt (re-invoking the
  thunk for a fresh handle).

  These cases throw in the prep phase BEFORE any socket is touched, so
  no test server is needed — the failure is delivered synchronously
  through the router on `dispatch-sync`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as registry]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http.managed :reload)
  ((requiring-resolve 're-frame.machines/reset-timers!))
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- a throwing body thunk -------------------------------------------------

(deftest throwing-body-thunk-delivers-managed-transport-failure
  (testing "rf2-065xo — a `:body` thunk that throws delivers ONE :on-failure reply with :rf.http/transport (NOT :rf.error/fx-handler-exception), and clears the registry"
    (let [replies     (atom [])
          traces      (atom [])
          listener-id ::body-thunk]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/throwing-thunk
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    "http://127.0.0.1:0/x"
                                 :method :post
                                 ;; The thunk is realized in the prep phase
                                 ;; and throws — there is no live socket.
                                 :body   (fn [] (throw (ex-info "boom-thunk" {})))}
                    :request-id :prep-thunk
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/throwing-thunk])
        ;; The failure is delivered synchronously on the dispatch-sync drain
        ;; (the throw happens before any async transport handoff).
        (is (= 1 (count @replies))
            "exactly one reply — the prep failure is delivered once")
        (let [reply (first @replies)]
          (is (= :error (:status reply)))
          (is (= :rf.http/transport (get-in reply [:error :kind]))
              "a throwing body thunk surfaces as the managed :rf.http/transport category")
          (is (= :request-prep (get-in reply [:error :stage]))
              "the :stage discriminator marks this as a request-preparation failure")
          (is (some? (get-in reply [:error :message]))
              "the thrown message rides the failure for diagnostics"))
        (is (not-any? #(= :rf.error/fx-handler-exception (:operation %)) @traces)
            "NO generic :rf.error/fx-handler-exception escaped — the throw is caught in the prep phase")
        (is (empty? (registry/in-flight-snapshot))
            "the in-flight registry is cleared — the failed-prep request is not pinned")
        (finally
          (trace/unregister-listener! listener-id))))))

;; ---- a body that fails to encode -------------------------------------------

(deftest unencodable-body-delivers-managed-transport-failure
  (testing "rf2-065xo — a body that `encode-body` (JSON) rejects delivers ONE :on-failure reply with :rf.http/transport (NOT :rf.error/fx-handler-exception)"
    (let [replies     (atom [])
          traces      (atom [])
          listener-id ::encode-fail]
      (try
        (trace/register-listener! listener-id (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/unencodable
          (fn [_ _]
            {:fx [[:rf.http/managed
                   ;; :request-content-type :json forces a JSON encode of a
                   ;; value Cheshire cannot serialise (a bare function),
                   ;; throwing inside `encode-body` in the prep phase.
                   {:request    {:url    "http://127.0.0.1:0/x"
                                 :method :post
                                 :request-content-type :json
                                 :body   {:f (fn [])}}
                    :request-id :prep-encode
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/unencodable])
        (is (= 1 (count @replies))
            "exactly one reply — the encode failure is delivered once")
        (let [reply (first @replies)]
          (is (= :error (:status reply)))
          (is (= :rf.http/transport (get-in reply [:error :kind]))
              "an encode failure surfaces as the managed :rf.http/transport category")
          (is (= :request-prep (get-in reply [:error :stage]))))
        (is (not-any? #(= :rf.error/fx-handler-exception (:operation %)) @traces)
            "NO generic :rf.error/fx-handler-exception escaped")
        (is (empty? (registry/in-flight-snapshot)))
        (finally
          (trace/unregister-listener! listener-id))))))

;; ---- retry when configured -------------------------------------------------

(deftest throwing-body-thunk-retries-when-configured
  (testing "rf2-065xo — when `:retry {:on #{:rf.http/transport} …}` is set, a throwing body thunk RETRIES (re-invoking the thunk per attempt) then finally fails :rf.http/transport"
    (let [replies     (atom [])
          invocations (atom 0)
          listener-id ::retry-thunk]
      (try
        (trace/register-listener! listener-id (fn [_] nil))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/retry-thunk
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    "http://127.0.0.1:0/x"
                                 :method :post
                                 ;; Each attempt re-invokes the thunk for a
                                 ;; fresh handle (Spec 014 §Body encoding);
                                 ;; here every invocation throws.
                                 :body   (fn []
                                           (swap! invocations inc)
                                           (throw (ex-info "boom-retry" {})))}
                    :retry      {:on           #{:rf.http/transport}
                                 :max-attempts 3
                                 ;; Zero backoff keeps the test fast; the
                                 ;; retry still rides the backoff scheduler.
                                 :backoff      {:base-ms 1 :factor 1 :max-ms 1}}
                    :request-id :prep-retry
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/retry-thunk])
        ;; Wait for the retry sequence to exhaust (3 attempts) and the final
        ;; failure reply to land — the backoff timer fires on a scheduler
        ;; thread, so this completes asynchronously.
        (let [deadline (+ (System/currentTimeMillis) 4000)]
          (while (and (empty? @replies)
                      (< (System/currentTimeMillis) deadline))
            (Thread/sleep 10)))
        (is (= 3 @invocations)
            "the throwing thunk was re-invoked once per attempt (max-attempts 3) — prep failures honour the retry policy")
        (is (= 1 (count @replies))
            "exactly one FINAL reply after the retries exhaust")
        (let [reply (first @replies)]
          (is (= :error (:status reply)))
          (is (= :rf.http/transport (get-in reply [:error :kind]))
              "the final reply carries the :rf.http/transport prep-failure category"))
        (is (empty? (registry/in-flight-snapshot))
            "the registry is clean after the retry sequence exhausts")
        (finally
          (trace/unregister-listener! listener-id))))))
