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
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.subs             :as rf.subs]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ===========================================================================
;; rf2-294yq5.5 / rf2-bsk1d9 — handler-exception ex-data redaction propagates
;;
;;   The variant declares its sensitive ex-data path at registration via the
;;   EP-0015 frame-owned `:sensitive` slot — installed as part of frame
;;   creation, so a single `run-variant` captures the throw under the
;;   classification. No public `add-marks` mutation.
;; ===========================================================================

(deftest exception-ex-data-redacts-sensitive-slot
  (testing "rf2-bsk1d9: a handler that throws ex-info with a value at a
            frame-owned sensitive key records :rf/redacted in :error :data,
            NOT the raw secret; the :error :message survives verbatim"
    (rf/reg-event :auth/boom
      (fn [_ _]
        (throw (ex-info "Invalid credentials"
                        {:token  "BEARER-secret-12345"
                         :reason :bad-password}))))
    (rf.story/reg-variant :story.err-redaction/probe
      {:setup      []
       :sensitive   {:app-db [[:token]]}
       :script [[:dispatch-sync [:auth/boom]]]})
    (async done
      (-> (rf.story/run-variant :story.err-redaction/probe)
          (rf.story.async/then
            (fn [result]
              (let [ex   (last (filter #(= :rf.error/exception (:assertion %))
                                       (:assertions result)))
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
                    "the message string survives verbatim (NOT auto-walked)"))
              (rf.story/destroy-variant! :story.err-redaction/probe)
              (done)))))))

(deftest exception-ex-data-non-sensitive-passes-through
  (testing "rf2-294yq5.5: with NO marks, the captured ex-data passes through
            unredacted (frame-scoped elision only redacts marked paths)"
    (rf/reg-event :plain/boom
      (fn [_ _]
        (throw (ex-info "boom" {:detail "not-secret"}))))
    (rf.story/reg-variant :story.err-plain/probe
      {:setup      []
       :script [[:dispatch-sync [:plain/boom]]]})
    (async done
      (-> (rf.story/run-variant :story.err-plain/probe)
          (rf.story.async/then
            (fn [result]
              (let [ex   (last (filter #(= :rf.error/exception (:assertion %))
                                       (:assertions result)))
                    data (get-in ex [:error :data])]
                (is (= "not-secret" (:detail data))
                    "an unmarked ex-data slot is not redacted"))
              (rf.story/destroy-variant! :story.err-plain/probe)
              (done)))))))
