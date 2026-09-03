(ns re-frame.managed-http-counter-cljs-test
  "Contract regression for the managed-HTTP counter example's synthetic
  cancellation reply.

  The example's `Start long` / `Cancel` path exercises the production
  managed-abort registry contract with no network behind it: `Start long`
  seeds a request-id-keyed handle into the in-flight registry, and `Cancel`
  fires the live `:rf.http/managed-abort` fx, which resolves the handle and
  invokes its example-owned `:abort-fn`. That closure HAND-BUILDS the
  `:rf.http/aborted` reply and dispatches it back to `:http-counter/start-long`.

  Because the reply is hand-built (not lowered through
  `re-frame.http.reply/failure-reply`), it can drift from the uniform reply
  contract silently: the example's own handler branches only on `:status`, so
  a mis-spelled cancellation-reason key still returns the UI to idle and every
  structural gate (compile, asset-staging, status-only smoke) stays green. The
  July pre-alpha migration retired the top-level `:cancel/reason` key in favour
  of the namespaced `:rf.reply/cancel-reason`; this test pins the seeded abort
  closure to the CURRENT contract so a future drift fails loud. The `teeth`
  block proves the test cannot false-green on `:status` / `:error` alone —
  restoring the retired spelling is rejected as
  `:rf.reply/cancelled-missing-reason`.

  It belongs in the framework test tree, NOT under `examples/` (examples stay
  test-free per rf2-8cevm). It `:require`s `managed-http-counter.core` (a
  Reagent-coupled `.cljs`-only entry ns — its ns-load registers the events /
  subs / fx, and transitively `re-frame.http.managed`, which registers the
  `:rf.http/managed-abort` fx) so it runs under the consolidated `:node-test`
  CLJS build, whose source paths include `../examples/core`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.reply :as rf.reply]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; Requiring the entry ns fires the example's ns-load
            ;; reg-event / reg-fx / reg-sub / reg-view forms (and,
            ;; transitively, the managed-HTTP fx family) against the live
            ;; registrar captured into this ns's fixture baseline.
            [managed-http-counter.core :as mhc]))

;; `:ambient-frame nil` — these tests create + drive their own anon frames with
;; an explicit `{:frame f}`; no ambient `:rf/default` scope is wanted.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- start-long-frame!
  "Boot a fresh anon frame and drive the example's opening
  `:http-counter/start-long` move — the REAL handler's `:else` branch, which
  fires `:http-counter/seed-long-request` to record a request-id-keyed handle
  in the in-flight registry. Returns the frame."
  []
  (let [f (rf.frame/make-anon-frame-record! {:doc "managed-http-counter test frame"})]
    (rf/dispatch-sync [:http-counter/start-long] {:frame f})
    f))

(deftest cancellation-reply-satisfies-uniform-reply-contract
  (let [f         (start-long-frame!)
        delivered (atom [])]
    (is (some? (rf.http.registry/lookup-in-flight mhc/long-request-id))
        "Start long seeds the request-id-keyed handle into the in-flight registry")

    ;; Swap in a capturing handler so the reply the example's abort closure
    ;; DISPATCHES back to :http-counter/start-long is recorded verbatim. The
    ;; abort reply is delivered synchronously: the async dispatch the closure
    ;; issues settles to fixed point within the Cancel drain (Spec 002
    ;; run-to-completion), so no queue flush is needed. The fixture restores
    ;; the real handler after each test.
    (rf/reg-event :http-counter/start-long
      (fn [{:keys [db]} [_ reply]]
        (swap! delivered conj reply)
        {:db db}))

    ;; Cancel through the LIVE :rf.http/managed-abort fx — the production
    ;; abort-by-request-id path (handlers/managed-abort-handler ->
    ;; registry/abort-in-flight! -> the seeded :abort-fn).
    (rf/dispatch-sync [:http-counter/cancel] {:frame f})

    (is (= 1 (count @delivered))
        "exactly one cancellation reply is delivered")
    (is (nil? (rf.http.registry/lookup-in-flight mhc/long-request-id))
        "the in-flight registry slot is cleared by the abort closure")

    (let [reply (first @delivered)]
      (testing "the hand-built reply satisfies the uniform reply contract"
        (is (rf.reply/valid-reply? reply)
            (str "re-frame.reply/validate-reply must accept the reply; problems="
                 (pr-str (rf.reply/validate-reply reply)))))
      (testing "it carries the canonical cancellation facts"
        (is (= :cancelled (:status reply)))
        (is (true? (:cancelled? reply)))
        (is (= :user (:rf.reply/cancel-reason reply))
            "the cancellation reason rides the namespaced :rf.reply/cancel-reason key")
        (is (= :rf.http/aborted (get-in reply [:error :kind])))
        (is (= :user (get-in reply [:error :reason])))
        (is (= mhc/long-request-id (get-in reply [:error :request-id]))))
      (testing "the retired top-level :cancel/reason key is absent"
        (is (not (contains? reply :cancel/reason))))
      (testing "teeth — the reply cannot false-green on :status / :error alone"
        ;; Dropping the namespaced reason, or restoring the retired
        ;; :cancel/reason spelling (the exact pre-migration defect), is
        ;; rejected as :rf.reply/cancelled-missing-reason even though
        ;; :status :cancelled and :error are left untouched.
        (doseq [broken [(dissoc reply :rf.reply/cancel-reason)
                        (-> reply
                            (dissoc :rf.reply/cancel-reason)
                            (assoc :cancel/reason :user))]]
          (is (not (rf.reply/valid-reply? broken)))
          (is (= :rf.reply/cancelled-missing-reason
                 (:rf.reply/problem (first (rf.reply/validate-reply broken))))))))))

(deftest cancel-restores-idle-and-records-the-aborted-error
  (testing "the real reply path returns the UI to :idle and records the :rf.http/aborted error"
    (let [f (start-long-frame!)]
      (is (= :loading (:http-counter/status (rf/app-db-value f)))
          "the opening move parks the UI in :loading until the abort resolves")

      (rf/dispatch-sync [:http-counter/cancel] {:frame f})
      (let [db (rf/app-db-value f)]
        (is (= :idle (:http-counter/status db))
            "the abort reply eases the UI back to :idle")
        (is (= :rf.http/aborted (get-in db [:http-counter/error :kind]))
            "the classified :rf.http/aborted error is recorded for the view")
        (is (nil? (rf.http.registry/lookup-in-flight mhc/long-request-id))
            "the in-flight registry slot is cleared")))))
