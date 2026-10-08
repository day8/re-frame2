(ns re-frame.story-assertions-cljs-test
  "CLJS tests for Story's `:rf.assert/*` vocabulary: `run-variant` resolves
  under CLJS with its `:assertions` recorded, `assertions-passing?` works
  from CLJS callers, and the evaluator branches no end-to-end case reaches.
  The bulk lives in the JVM `re-frame.story-assertions-test`."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.subs             :as rf.subs]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  ;; CLJS has no `require :reload` to re-fire the machines artefact's
  ;; registration of its runtime-db `:rf/machine` sub, which the registrar
  ;; clear dropped, so mirror it here.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(deftest cljs-assertions-passing?-roundtrip
  (testing "run-variant resolves with a passing path-equals record, and
            assertions-passing? is true on all-pass and false on any-fail"
    (rf/reg-event :test/n2 (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (rf.story/reg-variant :story.cljs.passing/ok
      {:setup [[:test/n2]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:n] 42]]]})
    (rf.story/reg-variant :story.cljs.passing/bad
      {:setup [[:test/n2]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:n] 999]]]})
    (async done
      (-> (rf.story/run-variant :story.cljs.passing/ok)
          (rf.story.async/then
            (fn [ok-r]
              (is (= [[true] true]
                     [(mapv :passed? (:assertions ok-r)) (rf.story/assertions-passing? ok-r)]))
              (-> (rf.story/run-variant :story.cljs.passing/bad)
                  (rf.story.async/then
                    (fn [bad-r]
                      (is (false? (rf.story/assertions-passing? bad-r)))
                      (rf.story/destroy-variant! :story.cljs.passing/ok)
                      (rf.story/destroy-variant! :story.cljs.passing/bad)
                      (done))))))))))

(deftest cljs-record-not-throw
  (testing "failing assertions never throw on CLJS; the sequence runs to completion"
    (rf.story/reg-variant :story.cljs.contract/v
      {:setup []
       :script [[:dispatch-sync [:rf.assert/path-equals [:nope] :unexpected]]
                [:dispatch-sync [:rf.assert/path-equals [:also-nope] :other]]]})
    (async done
      (-> (rf.story/run-variant :story.cljs.contract/v)
          (rf.story.async/then
            (fn [r]
              (is (= [[false false] false]
                     [(mapv :passed? (:assertions r)) (rf.story/assertions-passing? r)]))
              (rf.story/destroy-variant! :story.cljs.contract/v)
              (done)))))))

;; The evaluator branches below are reached directly through their vars.
;; A `:rf.assert/dispatched?` needle (spec/007 `[event-or-pred]`) is a
;; predicate fn, a bare keyword or a literal event vector.

(def ^:private event-matches? @#'rf.story.assertions/event-matches?)

(deftest cljs-event-matches?-per-needle-kind
  (testing "a fn needle sees the whole observed vector (a nil return is
            false), a bare keyword matches the head id whatever the payload,
            a vector matches exactly, and any other needle is false"
    (are [observed needle matched?] (= matched? (event-matches? observed needle))
      [:user/click 7]      (fn [ev] (= 7 (second ev)))  true
      [:user/click 7]      (fn [ev] (= 99 (second ev))) false
      [:user/click]        (constantly nil)             false
      [:user/click {:x 1}] :user/click                  true
      [:user/submit]       :user/click                  false
      [:user/click 1]      [:user/click 1]              true
      [:user/click 1]      [:user/click 2]              false
      [:user/click]        "not-a-needle"               false
      [:user/click]        42                           false)))

;; `compute-sub` recovers a throwing sub body to nil itself, so the
;; evaluator's own catch is reached only when `compute-sub` throws (a failure
;; before its own try); `with-redefs` forces that.
(def ^:private evaluate-sub-equals @#'rf.story.assertions/evaluate-sub-equals)

(deftest cljs-evaluate-sub-equals-sub-throws
  (testing "when compute-sub throws, the evaluator records a fail with the
            :rf.assert/sub-threw sentinel as :actual — never propagates"
    (with-redefs [rf.subs/compute-sub (fn [_query-v _db]
                                        (throw (ex-info "kaboom" {})))]
      (let [out (evaluate-sub-equals :rf/default {} [[:boom] :anything])]
        (is (= [false :rf.assert/sub-threw :anything] ((juxt :passed? :actual :expected) out)))
        (is (re-find #"threw" (:reason out)))))))

(deftest cljs-evaluate-sub-equals-runtime-db-projection
  (testing "a runtime-db projection sub resolves against the full frame-state
            value the play-runner hands over; bare app-db would read nil"
    (rf.subs/reg-runtime-sub :pecaxy/light
      (fn [rt _] (get-in rt [:rf.runtime/machines :snapshots :traffic-light :state])))
    (let [runtime {:rf.runtime/machines {:snapshots {:traffic-light {:state :red}}}}
          lookup  #((juxt :passed? :actual)
                    (evaluate-sub-equals :rf/default % [[:pecaxy/light] :red]))]
      (is (= [true :red] (lookup {:rf.db/app {} :rf.db/runtime runtime})))
      (is (= [false nil] (lookup {:rf.db/app {}}))))))

;; The evaluator is pure over the tape-projected emitted-fx SET: when the fx
;; was emitted but the optional predicate rejects it — or throws — the record
;; fails with the reason, and nothing propagates.
(def ^:private evaluate-effect-emitted @#'rf.story.assertions/evaluate-effect-emitted)

(deftest cljs-evaluate-effect-emitted-pred-rejects-present-fx
  (let [out (evaluate-effect-emitted #{:http} [:http (constantly false)])]
    (is (= [false true] [(:passed? out) (contains? (:actual out) :http)])
        ":actual still reports the emitted set")
    (is (re-find #"predicate rejected" (:reason out)))))

(deftest cljs-evaluate-effect-emitted-pred-accepts-present-fx
  (testing "fx present and predicate accepts → pass (the positive arm of
            the same branch, for contrast)"
    (let [out (evaluate-effect-emitted #{:http} [:http (constantly true)])]
      (is (true? (:passed? out)))
      (is (re-find #"emitted during play" (:reason out))))))

(deftest cljs-evaluate-effect-emitted-pred-throws-is-rejection
  (is (false? (:passed? (evaluate-effect-emitted #{:http}
                                                 [:http (fn [_] (throw (ex-info "x" {})))])))))
