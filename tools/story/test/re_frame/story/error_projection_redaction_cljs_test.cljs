(ns re-frame.story.error-projection-redaction-cljs-test
  "Error-projection-with-redaction scenario (rf2-q0ec2; implemented by
  rf2-294yq5.5).

  Per `tools/story/spec/015-Test-Coverage.md` §Assertion vocabulary
  scenarios, row 'Handler exception with sensitive ex-data: redaction
  propagates', and `tools/story/spec/002-Runtime.md` §Error projection
  §Privacy + `tools/story/spec/API.md` §Error-projection records:

  - the `:rf.error/exception` record's `:error :data` slot passes the
    captured `ex-data` through `re-frame.elision/elide-wire-value` keyed
    on the variant frame, so author-keyed slots sourced from path-marked
    app-db paths record `:rf/redacted` rather than the raw value;
  - the `:error :message` string is NOT auto-walked (author
    responsibility per spec/Security.md §Author guidance).

  ## Status: WIRED (rf2-294yq5.5)
  ##
  ## `re-frame.story.error/throwable->error-map` now threads the variant
  ## frame into the `:data` projection (the frame-scoped wire-elision
  ## walker). The tests below mark a path sensitive on the variant frame,
  ## then throw an `ex-info` whose `ex-data` carries a value at that path
  ## from a phase-4 play event, and assert the recorded `:error :data`
  ## redacts it while the message survives verbatim."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.async      :as async-lib]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.ui.state   :as state]
            [re-frame.subs             :as subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ===========================================================================
;; rf2-294yq5.5 — handler-exception ex-data redaction propagates
;;
;;   The marks live on the variant FRAME, so we run-variant once to
;;   allocate the frame, mark the path sensitive, then run a phase-4 play
;;   whose dispatched event throws an ex-info carrying the sensitive value
;;   in ex-data — and read the freshly-recorded exception record.
;; ===========================================================================

(deftest exception-ex-data-redacts-sensitive-slot
  (testing "rf2-294yq5.5: a handler that throws ex-info with a value at a
            path-marked-sensitive key records :rf/redacted in :error :data,
            NOT the raw secret; the :error :message survives verbatim"
    (rf/reg-event-db :auth/boom
      (fn [_ _]
        (throw (ex-info "Invalid credentials"
                        {:token  "BEARER-secret-12345"
                         :reason :bad-password}))))
    (story/reg-variant :story.err-redaction/probe
      {:events      []
       :play-script [[:dispatch-sync [:auth/boom]]]})
    (async done
      (-> (story/run-variant :story.err-redaction/probe)
          (async-lib/then
            (fn [_first-run]
              ;; Mark the ex-data key sensitive on the variant frame, then
              ;; re-run the play so the throw is captured under the marks.
              (rf/add-marks :story.err-redaction/probe
                            {[:token] :sensitive})
              (-> (story/execute-play! :story.err-redaction/probe)
                  (async-lib/then
                    (fn [_]
                      (let [recs (story/read-assertions
                                   :story.err-redaction/probe)
                            ex   (last (filter #(= :rf.error/exception
                                                   (:assertion %))
                                               recs))
                            data (get-in ex [:error :data])]
                        (is (some? ex)
                            "the throwing handler was captured as an
                             :rf.error/exception record")
                        (is (= :rf/redacted (:token data))
                            "the sensitive ex-data slot is redacted, NOT the
                             raw bearer token")
                        (is (= :bad-password (:reason data))
                            "a non-sensitive ex-data slot passes through")
                        (is (= "Invalid credentials" (get-in ex [:error :message]))
                            "the message string survives verbatim (NOT
                             auto-walked)")
                        (story/destroy-variant! :story.err-redaction/probe)
                        (done)))))))))))

(deftest exception-ex-data-non-sensitive-passes-through
  (testing "rf2-294yq5.5: with NO marks, the captured ex-data passes through
            unredacted (frame-scoped elision only redacts marked paths)"
    (rf/reg-event-db :plain/boom
      (fn [_ _]
        (throw (ex-info "boom" {:detail "not-secret"}))))
    (story/reg-variant :story.err-plain/probe
      {:events      []
       :play-script [[:dispatch-sync [:plain/boom]]]})
    (async done
      (-> (story/run-variant :story.err-plain/probe)
          (async-lib/then
            (fn [result]
              (let [ex   (last (filter #(= :rf.error/exception (:assertion %))
                                       (:assertions result)))
                    data (get-in ex [:error :data])]
                (is (= "not-secret" (:detail data))
                    "an unmarked ex-data slot is not redacted"))
              (story/destroy-variant! :story.err-plain/probe)
              (done)))))))
