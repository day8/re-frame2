(ns re-frame.story.artifact-test
  "Tests for the `:rf.test/run-artifact` schema + `replay-run-artifact`
  (spec/017-Testing-Story.md §Run artifact and replay): pure construction
  and result projection, then headless replay against a live frame."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.epoch  :as rf.epoch]
            [re-frame.frame  :as rf.frame]
            [re-frame.http.managed]       ;; production managed-HTTP fx surface (:rf.http/managed)
            [re-frame.http.test-support]  ;; stub install seam + canned-stub handlers
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.determinism :as rf.story.determinism]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.play.settled-boundary :as rf.story.play.settled-boundary]))

;; ===========================================================================
;; PURE: schema + construction
;; ===========================================================================

(deftest make-run-artifact-coerces-program
  (doseq [[label parts expected]
          [["a bare event lifts to [:dispatch …]; a tagged step passes through"
            {:event-program [[:counter/inc] [:dispatch [:counter/dec]]]}
            {:event-program [[:dispatch [:counter/inc]] [:dispatch [:counter/dec]]]}]
           [":setup ⧺ :script fold into one program, setup first"
            {:setup [[:dispatch [:seed/a]]] :script [[:dispatch [:act/b]] [:wait 5]]}
            {:event-program [[:dispatch [:seed/a]] [:dispatch [:act/b]] [:wait 5]]}]
           ["an explicit :event-program wins over :setup / :script"
            {:event-program [[:dispatch [:only/this]]] :setup [[:dispatch [:ignored]]]}
            {:event-program [[:dispatch [:only/this]]]}]
           ["slots outside the artifact surface are dropped; known slots kept"
            {:event-program [[:dispatch [:e]]] :seed 42 :fx-decisions {:http/get :http/stub}
             :source {:tool :recorder} :bogus/extra :dropped}
            {:event-program [[:dispatch [:e]]] :seed 42 :fx-decisions {:http/get :http/stub}
             :source {:tool :recorder}}]]]
    (testing label
      (is (= (merge {:artifact/kind :rf.test/run-artifact :fx-decisions {}} expected)
             (rf.story.artifact/make-run-artifact parts))))))

(deftest run-artifact-predicate
  (is (rf.story.artifact/run-artifact?
        {:artifact/kind :rf.test/run-artifact :event-program []}))
  (is (not (rf.story.artifact/run-artifact? {:event-program []}))
      "missing :artifact/kind")
  (is (not (rf.story.artifact/run-artifact? {:artifact/kind :rf.test/run-artifact}))
      "missing :event-program"))

(deftest program-events-projection
  (let [a (rf.story.artifact/make-run-artifact
            {:event-program [[:dispatch [:a 1]]
                             [:wait 10]
                             [:dispatch-sync [:b 2]]
                             [:assert-db [:k] :v]]})]
    (is (= [[:a 1] [:b 2]] (rf.story.artifact/program-events a))
        ":wait / :assert-* contribute no event")))

;; ===========================================================================
;; PURE: replay-result construction from a hand-built tape
;; ===========================================================================

(defn- epoch
  "A minimal `:rf/epoch-record`."
  [epoch-id m]
  (merge {:epoch-id epoch-id :outcome :ok
          :db-before {} :db-after {}
          :trace-events [] :effects [] :sub-runs [] :renders []}
         m))

(deftest replay-result-shared-shape
  (let [a    (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
        tape [(epoch :e1 {:db-after {:n 1}})]
        res  (rf.story.artifact/replay-result
               {:epoch-tape tape :artifact a
                :outcomes [{:status :settled :boundary :headless}]
                :frame-id :rf.test.replay/f :app-db {:n 1}})]
    (is (= {:status :pass :runner :headless :app-db {:n 1} :epoch-tape tape
            :run-artifact a :schema-violations []}
           (select-keys res [:status :runner :app-db :epoch-tape :run-artifact
                             :schema-violations])))))

(deftest replay-result-status-follows-the-tape-and-step-outcomes
  (let [a      (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
        replay (fn [outcome]
                 (rf.story.artifact/replay-result
                   {:epoch-tape [] :artifact a :outcomes [outcome] :frame-id :f :app-db {}}))]
    (testing ":cannot-run when a step refused"
      (let [refusal {:status :cannot-run :required-boundary :dom :provided-boundary :headless}]
        (is (= [:cannot-run refusal] ((juxt :status :cannot-run) (replay refusal))))))
    (testing ":error when a step errored"
      (is (= [:error "boom"] ((juxt :status :error) (replay {:status :error :error "boom"})))))))

;; ===========================================================================
;; HEADLESS replay: against a live frame
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Requiring `re-frame.epoch` installs the epoch late-bind hooks, so
  ;; `epoch-history` records a real tape; clear it so a replay reads only its
  ;; own epochs.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest replay-wraps-not-replaces-richer-dispatch-when-fx-decisions-present
  ;; A richer adapter's enqueue + flush path must run: the fx reapplication
  ;; wraps the supplied :dispatch! instead of calling dispatch-sync! directly.
  (let [dispatch-calls (atom [])
        dispatch-opts  (atom [])
        fx-hits        (atom [])]
    (rf/reg-fx :rep.fx/real {:platforms #{:client :server}}
               (fn [_ _] (swap! fx-hits conj :real)))
    (rf/reg-fx :rep.fx/stub {:platforms #{:client :server}}
               (fn [_ _] (swap! fx-hits conj :stub)))
    (rf/reg-event :rep/fire (fn [_ _] {:fx [[:rep.fx/real {}]]}))
    (let [probe-hooks {:provides  :headless
                       :dispatch! (fn probe-dispatch!
                                    ([frame-id event-vector]
                                     (probe-dispatch! frame-id event-vector nil))
                                    ([frame-id event-vector opts]
                                     (swap! dispatch-calls conj event-vector)
                                     (swap! dispatch-opts conj opts)
                                     (rf.story.play.settled-boundary/drain-sync! frame-id event-vector opts)))
                       :flush!    {:headless (fn [_frame-id] nil)}}
          a   (rf.story.artifact/make-run-artifact
                {:event-program [[:dispatch [:rep/fire]]]
                 :fx-decisions  {:rep.fx/real :rep.fx/stub}})
          res (rf.story.artifact/replay-run-artifact a {:hooks probe-hooks})]
      (is (= :pass (:status res)))
      (is (= [[:rep/fire]] @dispatch-calls) "the supplied :dispatch! was invoked")
      (is (= [:stub] @fx-hits) "the fx override rode the wrapped dispatch")
      (is (= [{:rf.cofx/mint-policy :strict}] @dispatch-opts)
          "the strict mint policy rode the adapter's :dispatch! opts"))))

(deftest replay-into-caller-supplied-frame
  (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/make-frame {:id :rep/caller-frame :doc "caller-owned replay frame"})
  (let [a   (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:rep/inc]]]})
        res (rf.story.artifact/replay-run-artifact a {:frame :rep/caller-frame})]
    (is (= :pass (:status res)))
    (is (= :rep/caller-frame (:frame res)))
    (is (contains? @rf.frame/frames :rep/caller-frame)
        "the caller owns the frame's lifecycle, so it is not destroyed")))

;; Teardown destroys the frame VALUE the replay created, carrying its exact
;; incarnation token, so a same-id successor seated during the replay
;; survives (the two-argument destroy no-ops against it).
(deftest replay-teardown-is-incarnation-exact
  (rf/reg-event :rep/noop (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
  (let [real-destroy    rf/destroy-frame!
        teardown-target (atom ::none)]
    (with-redefs [rf/destroy-frame!
                  (fn [target & more]
                    (when (and (= ::none @teardown-target)
                               (rf.frame/frame-value? target))
                      (reset! teardown-target target))
                    (apply real-destroy target more))]
      (let [a   (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:rep/noop]]]})
            res (rf.story.artifact/replay-run-artifact a)]
        (is (not (contains? @rf.frame/frames (:frame res)))
            "the replay-allocated frame is released")))
    (is (some? (rf.frame/frame-value-incarnation-token @teardown-target))
        "teardown targeted the make-frame value with its incarnation token")))

;; ===========================================================================
;; Recordable-coeffect envelopes survive replay, under STRICT mint policy
;; ===========================================================================

(deftest replay-delivers-recorded-cofx-verbatim
  (rf/reg-cofx :rep.cofx/delta {:recordable? true}
               (fn [] (throw (ex-info "generator must not run on replay" {}))))
  (rf/reg-event :rep/use-delta
    {:rf.cofx/requires [:rep.cofx/delta]}
    (fn [{:keys [db rep.cofx/delta]} _] {:db (assoc db :delta delta)}))
  (let [a   (rf.story.artifact/make-run-artifact
              {:event-program
               [[:dispatch [:rep/use-delta]
                 {:rf.cofx {:rf/time-ms 1781078400123 :rep.cofx/delta 42}}]]})
        res (rf.story.artifact/replay-run-artifact a)]
    (is (= :pass (:status res)))
    (is (= 42 (:delta (:app-db res))) "the recorded cofx value replayed verbatim")))

(deftest replay-is-strict-incomplete-record-fails-loud
  (let [gen-calls (atom 0)]
    (rf/reg-cofx :rep.cofx/missing {:recordable? true}
                 (fn [] (swap! gen-calls inc) 5))
    (rf/reg-event :rep/needs-missing
      {:rf.cofx/requires [:rep.cofx/missing]}
      (fn [_ _] {}))
    ;; The recorded envelope carries :rf/time-ms but not :rep.cofx/missing.
    (let [a   (rf.story.artifact/make-run-artifact
                {:event-program
                 [[:dispatch [:rep/needs-missing]
                   {:rf.cofx {:rf/time-ms 1781078400123}}]]})
          res (rf.story.artifact/replay-run-artifact a)]
      (is (zero? @gen-calls) "an incomplete record never re-mints")
      (is (contains? #{:error :cannot-run} (:status res))
          "replay fails loudly rather than passing a fresh-minted value"))))

;; ===========================================================================
;; Replay runs EVERY step, not only the dispatches
;; ===========================================================================

(defn- replay-program [program]
  (rf.story.artifact/replay-run-artifact
    (rf.story.artifact/make-run-artifact {:event-program program})))

(deftest replay-evaluates-assert-checkpoints
  (rf.story.assertions/install-canonical-assertions!)
  (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (testing "a FALSE [:assert …] checkpoint fails the replay and is recorded"
    (let [res (replay-program [[:dispatch [:rep/inc]]
                               [:assert [:rf.assert/path-equals [:n] 99]]])]
      (is (= :fail (:status res)))
      (is (= [[:rf.assert/path-equals false :fail]]
             (mapv (juxt :assertion :passed? :status) (:assertions res))))))
  (testing "a TRUE checkpoint passes and is recorded"
    (let [res (replay-program [[:dispatch [:rep/inc]]
                               [:assert [:rf.assert/path-equals [:n] 1]]])]
      (is (= :pass (:status res)))
      (is (= [[:rf.assert/path-equals true :pass]]
             (mapv (juxt :assertion :passed? :status) (:assertions res)))))))

(deftest replay-refuses-a-step-the-headless-runner-cannot-prove
  (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (let [res (replay-program [[:dispatch [:rep/inc]] [:click ".nope"]])]
    (is (= :cannot-run (:status res)))
    (is (= [:click ".nope"] (get-in res [:cannot-run :unit])))))

(deftest replay-fails-a-wait-until-that-never-holds
  (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (let [res (replay-program [[:dispatch [:rep/inc]] [:wait-until [:db [:n] 99]]])]
    (is (= :fail (:status res)))
    (is (= [:rf.error/story-play-step-failed] (mapv :assertion (:assertions res))))))

;; ===========================================================================
;; Tape-evaluated checkpoints get their verdict on replay
;; ===========================================================================
;;
;; A schema-error or causal checkpoint has no handler: the step executor
;; skips it and `replay-result` judges it with the result boundary's own
;; matchers.

(def ^:private schema-checkpoint
  [:assert [:rf.assert/schema-error {:where :event :event :rep/typed}]])

(deftest replay-fails-a-missing-expected-schema-violation
  (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (let [res (replay-program [[:dispatch [:rep/inc]] schema-checkpoint])]
    (is (= :fail (:status res)))
    (is (= [[:rf.assert/schema-error false :fail]]
           (mapv (juxt :assertion :passed? :status) (:assertions res))))))

(deftest replay-consumes-a-matching-expected-schema-violation
  ;; `replay-run-artifact` hands its captured tape to `replay-result`, so the
  ;; consumption is pinned there, against a tape carrying the violation.
  (let [tape     [(epoch 1 {:trace-events
                            [{:operation :rf.error/schema-validation-failure
                              :tags      {:where :event :failing-id :rep/typed}}]})]
        replay   (fn [program]
                   (rf.story.artifact/replay-result
                     {:epoch-tape tape
                      :artifact   (rf.story.artifact/make-run-artifact
                                    {:event-program program})
                      :outcomes   [{:status :settled :boundary :headless}]
                      :frame-id   :f
                      :app-db     {}}))]
    (testing "control: the same tape with no expectation is red on the floor"
      (let [res (replay [[:dispatch [:rep/typed "x"]]])]
        (is (= :fail (:status res)))
        (is (= [] (:assertions res)))))
    (testing "the expected violation passes, is consumed, and does not trip the floor"
      (let [res (replay [[:dispatch [:rep/typed "x"]] schema-checkpoint])]
        (is (= :pass (:status res)))
        (is (= [[:rf.assert/schema-error true :pass]]
               (mapv (juxt :assertion :passed? :status) (:assertions res))))
        (is (= #{[:event :rep/typed]} (:consumed-selectors res)))))))

(deftest replay-refuses-a-causal-checkpoint-it-cannot-prove
  (rf/reg-event :rep/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (let [res (replay-program [[:dispatch [:rep/inc]]
                             [:assert [:rf.assert/caused {:event :rep/inc}]]])]
    (is (= :cannot-run (:status res)))
    (is (= [[:rf.assert/caused false :cannot-run]]
           (mapv (juxt :assertion :passed? :status) (:assertions res))))
    (is (= :rf.assert/caused (get-in res [:cannot-run :assertion])))))

;; ===========================================================================
;; :network route stubs survive replay
;; ===========================================================================
;;
;; The `:network` world slot lowers to an `:fx-decisions` redirect onto the
;; managed test stub, which only matches routes once they are installed. The
;; artifact carries the routes in `:network`, and replay re-installs them;
;; without that, every replayed request fails closed on "no stub matched".

(defn- register-network-event!
  "Register `event-id`, which issues a managed-HTTP request to `[method url]`
  and records the reply under `:got`."
  [event-id [method url]]
  (rf/reg-event event-id
    (fn [{:keys [db]} [_ msg reply]]
      (if reply
        {:db (assoc db :got reply)}
        {:fx [[:rf.http/managed {:request {:method method :url url}
                                 :decode  :json
                                 :reply-to [event-id msg]}]]}))))

(defn- network-artifact
  "The run artifact `determinism/->artifact` builds from a `:network` variant
  plan for `routes` and `script`."
  [routes script]
  (let [variant-id :story.net/v
        plan       (rf.story.plan/variant-plan
                     variant-id
                     {:lookup {variant-id {:network routes
                                           :script  script}}})]
    (rf.story.determinism/->artifact plan)))

(deftest replay-reinstalls-network-success-route
  (register-network-event! :net/get-cart [:get "/api/cart"])
  (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
        art    (network-artifact routes [[:dispatch [:net/get-cart]]])
        res    (rf.story.artifact/replay-run-artifact art)]
    (is (= {:status :ok :value {:items [{:sku "A"}]}}
           (select-keys (:got (:app-db res)) [:status :value]))
        "the re-installed route stub matched and replied with the recorded payload")))

;; ===========================================================================
;; EXACT narrative attribution from runner-recorded settle boundaries
;; ===========================================================================
;;
;; Step 1 re-dispatches, so the tape is [a c d] over two dispatch steps. The
;; EVEN forward partition would group {a c} under step 0; the replay records
;; each step's settle boundary, so attribution is EXACT: {a} and {c d}.

(defn- beats-by-step
  "`{step [trigger-event …]}` over the flattened narrative beats of `result`."
  [result]
  (->> (rf.story.play.evidence/narrative-beats (:narrative result))
       (reduce (fn [m {:keys [step trigger-event]}]
                 (update m step (fnil conj []) trigger-event))
               {})))

(deftest replay-narrative-exact-attribution-of-redispatch-fanout
  (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
  (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
  (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
  (let [a (rf.story.artifact/make-run-artifact
            {:event-program [[:dispatch [:rkd/a]]
                             [:dispatch [:rkd/c]]]})]
    (is (= {[:dispatch [:rkd/a]] [[:rkd/a]]
            [:dispatch [:rkd/c]] [[:rkd/c] [:rkd/d]]}
           (beats-by-step (rf.story.artifact/replay-run-artifact a))))))
