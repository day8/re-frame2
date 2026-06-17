(ns re-frame.story.assertion-redaction-cljs-test
  "Assertion-with-redaction scenario (rf2-shy6n; substrate wired by
  rf2-ee38b.3).

  Per `tools/story/spec/015-Test-Coverage.md` §Assertion vocabulary
  scenarios, row 'Assertion-with-redaction (sensitive payload)':
  registering an assertion against a path-marked sensitive value MUST
  surface `:rf/redacted` in the recorded `:actual` slot, not the raw
  sensitive payload — the assertion's serialised form lands in tooling
  surfaces (test-mode pane, MCP read-assertions, JSON-log-egress
  pipelines) and the contract is 'never leak the raw value to
  observation surfaces' per spec/015-Data-Classification.

  ## Status: WIRED (rf2-ee38b.3)
  ##
  ## The assertion evaluators (`evaluate-path-equals` /
  ## `evaluate-path-matches` / `evaluate-sub-equals` in
  ## `tools/story/src/re_frame/story/assertions.cljc`) now project the
  ## captured value through `re-frame.elision/elide-wire-value` (keyed
  ## on the asserted path + the variant frame) BEFORE stamping `:actual`.
  ## A path the frame marked sensitive (via `re-frame.core/add-marks` /
  ## `set-marks`, or a schema entry with `{:sensitive? true}`) records
  ## `:rf/redacted` instead of the raw value; a non-sensitive path passes
  ## through unchanged. The tests below mark the path sensitive then
  ## assert the recorded `:actual` is `:rf/redacted`."
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
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001 (rf2-vzld77 / rf2-ixb0bq): a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>], NOT the retired app-db
  ;; `:rf/runtime` path — mirror `re-frame.machines`.
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
;; rf2-ee38b.3 — assertion-with-redaction (substrate WIRED)
;;
;;   Given a variant whose frame marks `[:auth :token]` sensitive (via
;;   `re-frame.core/add-marks`), an assertion against that path records
;;   `:rf/redacted` in `:actual`, NOT the raw token string. Same for
;;   `:rf.assert/path-matches` and `:rf.assert/sub-equals`.
;;
;;   The marks live on the variant FRAME, so we run-variant once to
;;   allocate the frame, mark the path sensitive, then re-run the play
;;   (via execute-play!) and read the freshly-recorded assertion.
;; ===========================================================================

(deftest assertion-path-equals-redacts-sensitive-actual
  (testing "rf2-ee38b.3: :rf.assert/path-equals records :rf/redacted in
            :actual for a sensitive path (not the raw bearer token)"
    (rf/reg-event :auth/login
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-secret-12345")}))
    (story/reg-variant :story.redaction.path-equals/probe
      {:events [[:auth/login]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals
                 [:auth :token]
                 "BEARER-secret-12345"]]]})
    (async done
      (-> (story/run-variant :story.redaction.path-equals/probe)
          (async-lib/then
            (fn [_first-run]
              ;; Mark the path sensitive on the variant frame, then re-run
              ;; the assertion against the now-marked frame.
              (story/add-marks :story.redaction.path-equals/probe
                            {[:auth :token] :sensitive})
              (-> (story/execute-play! :story.redaction.path-equals/probe)
                  (async-lib/then
                    (fn [_]
                      (let [recs (story/read-assertions
                                   :story.redaction.path-equals/probe)
                            pe   (last (filter #(= :rf.assert/path-equals
                                                   (:assertion %))
                                               recs))]
                        (is (= :rf/redacted (:actual pe))
                            "assertion :actual is :rf/redacted, NOT the raw token")
                        ;; The assertion still PASSES — equality is checked
                        ;; against the raw value before projection.
                        (is (true? (:passed? pe))
                            "redaction does not change the pass/fail outcome")
                        ;; rf2-006y9b — :expected / :payload / :reason MUST
                        ;; NOT carry the raw secret either (the whole record
                        ;; egresses to the test pane / MCP / log sinks).
                        (is (= :rf/redacted (:expected pe))
                            ":expected is projected — the raw token does not leak")
                        (is (= [[:auth :token] :rf/redacted] (:payload pe))
                            ":payload is rebuilt from the redacted expected")
                        (is (not (re-find #"BEARER-secret-12345" (str (:reason pe))))
                            ":reason does not print the raw token")
                        (story/destroy-variant!
                          :story.redaction.path-equals/probe)
                        (done)))))))))))

(deftest assertion-path-equals-sentinel-expected-passes
  (testing "rf2-006y9b: an author who pins the documented :rf/redacted
            sentinel as :expected against a sensitive path gets a PASSING
            assertion (the sentinel contract), with no raw value anywhere"
    (rf/reg-event :auth/login2
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-secret-99999")}))
    (story/reg-variant :story.redaction.sentinel/probe
      {:events [[:auth/login2]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals
                 [:auth :token]
                 :rf/redacted]]]})
    (async done
      (-> (story/run-variant :story.redaction.sentinel/probe)
          (async-lib/then
            (fn [_first-run]
              (story/add-marks :story.redaction.sentinel/probe
                            {[:auth :token] :sensitive})
              (-> (story/execute-play! :story.redaction.sentinel/probe)
                  (async-lib/then
                    (fn [_]
                      (let [recs (story/read-assertions
                                   :story.redaction.sentinel/probe)
                            pe   (last (filter #(= :rf.assert/path-equals
                                                   (:assertion %))
                                               recs))]
                        (is (true? (:passed? pe))
                            "the sentinel-expected assertion PASSES against a sensitive path")
                        (is (= :rf/redacted (:expected pe)))
                        (is (= :rf/redacted (:actual pe)))
                        (story/destroy-variant!
                          :story.redaction.sentinel/probe)
                        (done)))))))))))

(deftest assertion-path-equals-non-sensitive-passes-value-through
  (testing "rf2-ee38b.3: a NON-sensitive path records the raw value
            unchanged (redaction only fires on marked paths)"
    (rf/reg-event :ui/set-label (fn [{:keys [db]} _] {:db (assoc db :label "hello")}))
    (story/reg-variant :story.redaction.plain/probe
      {:events [[:ui/set-label]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:label] "hello"]]]})
    (async done
      (-> (story/run-variant :story.redaction.plain/probe)
          (async-lib/then
            (fn [result]
              (let [pe (first (filter #(= :rf.assert/path-equals (:assertion %))
                                      (:assertions result)))]
                (is (= "hello" (:actual pe))
                    "non-sensitive value passes through unredacted"))
              (story/destroy-variant! :story.redaction.plain/probe)
              (done)))))))

(deftest assertion-sub-equals-redacts-on-path-bearing-sub-vec
  (testing "rf2-ee38b.3: :rf.assert/sub-equals redacts :actual when the
            sub-vec carries the app-db path as its args (the projection
            keys on (rest sub-vec)). A parameterised sub
            [:sub/id :user :ssn] → args path [:user :ssn]; marking that
            path sensitive redacts the recorded :actual.

            Note: a bare sub-id whose args carry NO app-db path (e.g.
            [:user/ssn]) cannot be auto-redacted at the assertion layer —
            full sub-marker propagation (spec/015 §reg-sub) is a sub-
            engine feature tracked separately. The assertion layer
            redacts what its path-key reaches."
    (rf/reg-event :session/save-pii
      (fn [{:keys [db]} _] {:db (assoc-in db [:user :ssn] "123-45-6789")}))
    ;; Parameterised sub: reads the path passed as args.
    (rf/reg-sub :pii/at (fn [db [_ & path]] (get-in db (vec path))))
    (story/reg-variant :story.redaction.sub-equals/probe
      {:events [[:session/save-pii]]
       :play-script [[:dispatch-sync [:rf.assert/sub-equals
                 [:pii/at :user :ssn]
                 "123-45-6789"]]]})
    (async done
      (-> (story/run-variant :story.redaction.sub-equals/probe)
          (async-lib/then
            (fn [_first-run]
              (story/add-marks :story.redaction.sub-equals/probe
                            {[:user :ssn] :sensitive})
              (-> (story/execute-play! :story.redaction.sub-equals/probe)
                  (async-lib/then
                    (fn [_]
                      (let [recs (story/read-assertions
                                   :story.redaction.sub-equals/probe)
                            se   (last (filter #(= :rf.assert/sub-equals
                                                   (:assertion %))
                                               recs))]
                        (is (= :rf/redacted (:actual se))
                            "sub-equals :actual redacts the sensitive value")
                        (is (true? (:passed? se))
                            "redaction does not change the pass/fail outcome")
                        (story/destroy-variant!
                          :story.redaction.sub-equals/probe)
                        (done)))))))))))
