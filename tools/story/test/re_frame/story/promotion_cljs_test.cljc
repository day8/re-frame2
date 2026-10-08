(ns re-frame.story.promotion-cljs-test
  "Tests for the run-artifact → variant promotion bridge
  (spec/017-Testing-Story.md §Promotion — Promotion bridge).

  The compiler is pure and the registrar a side-table, so the bridge tests
  run on the JVM and on node (the `-cljs-test` suffix opts it into
  `:node-test`). The tests that run a variant block on its result with
  `deref-blocking`, so they are `#?(:clj …)`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed]       ;; production managed-HTTP fx surface (:rf.http/managed)
            [re-frame.http.test-support]  ;; stub install seam + canned-stub handlers
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.args :as rf.story.args]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.determinism :as rf.story.determinism]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.promotion :as rf.story.promotion]
            [re-frame.story.registrar :as rf.story.registrar]
            ;; the Test-mode dialog's pure capture + draft helpers — the
            ;; fail/pass/fail regression tests promote exactly the way the
            ;; dialog does
            [re-frame.story.ui.promotion :as rf.story.ui.promotion]
            ;; `deref-blocking` is JVM-only; the run-based tests are `:clj`-gated
            #?@(:clj [[re-frame.story.async :as rf.story.async]])))

;; ---- fixtures -----------------------------------------------------------

;; The replay and run tests dispatch into a live frame, so besides emptying
;; the side-table the fixture installs an adapter and a default frame.
(defn reset-side-table! [t]
  (rf.story.registrar/clear-all!)
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-side-table!)

;; ---- helpers ------------------------------------------------------------

(defn- sample-artifact
  "A two-step dispatch program with provenance slots and the bulky captured
  evidence the provenance link drops."
  []
  (rf.story.artifact/make-run-artifact
    {:event-program [[:dispatch [:counter/init 5]]
                     [:dispatch [:counter/inc]]]
     :seed          42
     :fx-decisions  {:http/get :http/stub}
     :created-at    "2026-05-30T00:00:00Z"
     :source        {:tool :recorder}
     ;; bulky captured evidence the promotion link MUST drop:
     :epoch-tape    [{:big :tape}]
     :trace         [{:big :trace}]
     :result        {:status :fail}}))

;; ===========================================================================
;; A run artifact becomes a readable normalized plan (spec/017 §Promotion)
;; ===========================================================================

(deftest materialize-produces-a-plan-and-registers-nothing
  (testing "the default policy projects the whole program into :script,
            demoting nothing to :setup, and :variant/id names the plan"
    (let [plan (rf.story.promotion/materialize-variant-plan
                 (sample-artifact) {:variant/id :story.counter/regression-042})]
      (is (= {:variant/id :story.counter/regression-042
              :setup      []
              :script     [[:dispatch [:counter/init 5]]
                           [:dispatch [:counter/inc]]]}
             {:variant/id (:variant/id plan)
              :setup      (get-in plan [:world :setup])
              :script     (:script plan)}))
      (is (empty? (rf.story.registrar/registrations :variant))
          "materialize is pure: a named plan is still not registered"))))

;; ===========================================================================
;; A generated event program becomes script/setup per policy
;; ===========================================================================

(deftest program-projects-to-setup-and-script-per-policy
  (let [art (sample-artifact)
        cut (fn [opts]
              (let [plan (rf.story.promotion/materialize-variant-plan art opts)]
                {:setup (get-in plan [:world :setup]) :script (:script plan)}))]
    (is (= {:setup  [[:dispatch [:counter/init 5]]]
            :script [[:dispatch [:counter/inc]]]}
           (cut {:setup-count 1}))
        ":setup-count cuts preconditions off the front")
    (is (= {:setup [[:dispatch [:seed/a]]] :script [[:dispatch [:act/b]]]}
           (cut {:setup [[:dispatch [:seed/a]]] :script [[:dispatch [:act/b]]]}))
        "an explicit :setup + :script partition is used verbatim")
    (is (= {:setup  [[:dispatch [:counter/init 5]] [:dispatch [:counter/inc]]]
            :script []}
           (rf.story.promotion/partition-program art {:setup-count 99}))
        "an oversized :setup-count clamps to the program")))

(deftest materialize-preserves-source-artifact-link
  (is (= {:artifact/kind :rf.test/run-artifact
          :seed          42
          :event-program [[:dispatch [:counter/init 5]] [:dispatch [:counter/inc]]]
          :fx-decisions  {:http/get :http/stub}
          :created-at    "2026-05-30T00:00:00Z"
          :source        {:tool :recorder}}
         (:run-artifact (rf.story.promotion/materialize-variant-plan (sample-artifact))))
      "the link keeps the replayable core and drops the tape, trace and result"))

(deftest promotion-refuses-a-missing-id-or-artifact
  (testing "promotion registers only under an explicit :variant/id, and both
            entry points refuse a nil artifact, which would register a hollow
            body that passes with zero assertions"
    (is (= [:rf.error/story-promote-no-id
            :rf.error/story-promote-no-artifact
            :rf.error/story-promote-no-artifact]
           (mapv (fn [f]
                   (try (f) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                          (:rf.error/id (ex-data e)))))
                 [#(rf.story.promotion/promote-run-artifact! (sample-artifact) {})
                  #(rf.story.promotion/promote-run-artifact! nil {:variant/id :story.counter/hollow})
                  #(rf.story.promotion/materialize-variant-plan nil)])))
    (is (empty? (rf.story.registrar/registrations :variant))
        "a refused promotion registers nothing")))

(deftest promote-registers-the-named-variant
  (is (= :story.counter/regression-042
         (rf.story.promotion/promote-run-artifact!
           (sample-artifact) {:variant/id :story.counter/regression-042})))
  (let [body (rf.story.registrar/handler-meta :variant :story.counter/regression-042)]
    (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind])))
    (is (= [[:dispatch [:counter/init 5]] [:dispatch [:counter/inc]]] (:script body)))))

;; ===========================================================================
;; A :network-stubbed run: the link and the promoted body both re-derive it
;; ===========================================================================
;;
;; Replay re-installs the per-route stubs from `:network`; without it every
;; managed request fail-closes on "no stub matched" — a different run.

(defn- register-network-event!
  "Register an event that issues a managed-HTTP request to `[method url]` and
  records the reply (routed back via `:reply-to`) in app-db under `:got`."
  [event-id [method url]]
  (rf/reg-event event-id
    (fn [{:keys [db]} [_ msg reply]]
      (if reply
        {:db (assoc db :got reply)}
        {:fx [[:rf.http/managed {:request {:method method :url url}
                                 :decode  :json
                                 :reply-to [event-id msg]}]]}))))

(defn- network-artifact
  "The artifact a recorded HTTP run produces: its `:network` route map plus
  the `:fx-decisions` managed-stub redirect."
  [routes script]
  (let [variant-id :story.promo-net/v
        plan       (rf.story.plan/variant-plan
                     variant-id
                     {:lookup {variant-id {:network routes
                                           :script  script}}})]
    (rf.story.determinism/->artifact plan)))

(deftest promotion-link-of-network-run-re-derives-it
  (register-network-event! :promo-net/get-cart [:get "/api/cart"])
  (let [art (network-artifact {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
                              [[:dispatch [:promo-net/get-cart]]])
        run (rf.story.artifact/replay-run-artifact (rf.story.promotion/provenance-link art))
        got (:got (:app-db run))]
    (is (= [:pass :ok {:items [{:sku "A"}]}]
           [(:status run) (:status got) (:value got)])
        "replaying the link alone matches the route and returns the recorded reply")))

(deftest promoted-network-variant-runs-to-the-same-result
  (testing "the promoted BODY carries :network, so body → plan → ->artifact →
            replay reproduces the source run's reply"
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [art  (network-artifact {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
                                 [[:dispatch [:promo-net/get-cart]]])
          src  (rf.story.artifact/replay-run-artifact art)
          ran  (rf.story.artifact/replay-run-artifact
                 (rf.story.determinism/->artifact
                   (rf.story.plan/variant-plan (rf.story.promotion/artifact->variant-body art))))]
      (is (= (:status src) (:status ran) :pass))
      ;; `:rf.frame/id` is a fresh per-replay frame id.
      (is (= (dissoc (:got (:app-db src)) :rf.frame/id)
             (dissoc (:got (:app-db ran)) :rf.frame/id)))
      (is (= :ok (:status (:got (:app-db ran))))
          "the route stub matched — not a fail-closed 'no stub matched'"))))

;; ===========================================================================
;; A promoted regression fails for the reason its source failed
;; ===========================================================================
;;
;; An artifact records a program, not a judgement, and Test mode's capture
;; keeps only the dispatches. So promotion must carry the source's
;; declarative `:assertions`, its resolved checks (`:compose` is child-only,
;; so `:extends` cannot recover a composed check), its full step program, and
;; the run inputs its checkpoints and inherited `:setup` read. Each shape is
;; judged fail / pass / fail against the app: it fails under the fault with
;; its source's counts, passes once the handler is fixed, and fails again
;; when the fault returns. The DIALOG route is the Test-mode capture plus its
;; default draft (`:extends` the source, `:setup-count 0`); the API route is
;; a plan-derived artifact promoted with only a `:variant/id`.

#?(:clj
   (defn- reg-inc!
     "The app under test. `fixed?` false is the FAULT — `:promo/inc` never
     moves the counter; true is the repair."
     [fixed?]
     (rf/reg-event :promo/inc
       (fn [{:keys [db]} _]
         {:db (if fixed? (update db :n (fnil inc 0)) (assoc db :n 0))}))))

#?(:clj
   (defn- dialog-promote!
     "Promote `source-id`'s run `result` the way the Test-mode dialog does:
     its capture over the dispatch-only play-events, then its default draft."
     [source-id result promoted-id]
     (rf.story.promotion/promote-run-artifact!
       (rf.story.ui.promotion/result->artifact
         result (rf.story.play/variant-play-events source-id))
       (rf.story.ui.promotion/draft->promote-opts
         {:variant-id promoted-id :tags #{:test} :setup-count 0 :extends source-id}))))

#?(:clj
   (defn- api-promote!
     "Promote a plan-derived artifact of `source-id` with only a `:variant/id`."
     [source-id promoted-id]
     (rf.story.promotion/promote-run-artifact!
       (rf.story.determinism/->artifact (rf.story.plan/variant-plan source-id))
       {:variant/id promoted-id})))

#?(:clj
   (defn- verdict
     "What a regression is judged by: its status and how many assertion and
     check records it produced."
     [result]
     {:status     (:status result)
      :assertions (count (:assertions result))
      :checks     (count (:checks result))}))

#?(:clj
   (defn- run-verdict
     "Run variant `id` headless and keep what a regression is judged by."
     [id]
     (let [result (rf.story.async/deref-blocking (rf.story/run id) 10000)]
       (verdict result))))

#?(:clj
   (defn- fault-fix-fault
     "Run `id` under the fault, then against the fixed app, then under the
     restored fault. `reg-app!` installs the app, faulty when given false; it
     defaults to `reg-inc!`."
     ([id] (fault-fix-fault id reg-inc!))
     ([id reg-app!]
      (reg-app! false)
      (let [faulty (run-verdict id)]
        (reg-app! true)
        (let [fixed (run-verdict id)]
          (reg-app! false)
          [faulty fixed (run-verdict id)])))))

#?(:clj
   (defn- assert-promotions-fail-pass-fail
     "Register `source-body` under `source-id`, check it fails on its one
     assertion and its `checks` check records (0 unless its verdict comes
     from a check), promote it by both routes, and check each promotion runs
     fail / pass / fail with the same counts throughout."
     ([source-id source-body] (assert-promotions-fail-pass-fail source-id source-body 0))
     ([source-id source-body checks]
      (rf.story/install-canonical-vocabulary!)
      (reg-inc! false)
      (rf.story.registrar/reg-variant* source-id source-body)
      (let [result   (rf.story.async/deref-blocking (rf.story/run source-id) 10000)
            dialog   (keyword (namespace source-id) (str (name source-id) "-dialog"))
            api      (keyword (namespace source-id) (str (name source-id) "-api"))
            failing  {:status :fail :assertions 1 :checks checks}]
        (is (= failing (verdict result))
            "the source fails on its one assertion under the fault")
        (dialog-promote! source-id result dialog)
        (api-promote! source-id api)
        (doseq [id [dialog api]]
          (is (= [failing {:status :pass :assertions 1 :checks checks} failing]
                 (fault-fix-fault id))
              (str id " runs fail / pass / fail with its source's one assertion")))))))

#?(:clj
   (deftest promoted-regression-keeps-its-declarative-expectation
     (testing "a source failing on a DECLARATIVE :assertions entry promotes by
               both routes into a variant that runs fail / pass / fail"
       (assert-promotions-fail-pass-fail
         :story.promo/declared
         {:tags       #{:test}
          :script     [[:dispatch [:promo/inc]]]
          :assertions [[:rf.assert/path-equals [:n] 1]]}))))

#?(:clj
   (deftest promoted-regression-keeps-its-in-script-checkpoint
     (testing "an [:assert …] checkpoint INSIDE the program survives both routes:
               the API artifact retains the whole program (the positive
               control), and the dialog's dispatch-only capture is replaced by
               the source's full program"
       (assert-promotions-fail-pass-fail
         :story.promo/checkpoint
         {:tags   #{:test}
          :script [[:dispatch [:promo/inc]]
                   [:assert [:rf.assert/path-equals [:n] 1]]]}))))

#?(:clj
   (deftest promoted-regression-keeps-a-dispatched-assertion-event
     (testing "REGRESSION GUARD — the one shape the artifact alone carries: an
               :rf.assert/* event DISPATCHED by the program rides :event-program
               on both routes. The source program is all dispatches, so nothing
               is replaced and nothing is carried; it must still run
               fail / pass / fail"
       (assert-promotions-fail-pass-fail
         :story.promo/dispatched
         {:tags   #{:test}
          :script [[:dispatch [:promo/inc]]
                   [:dispatch [:rf.assert/path-equals [:n] 1]]]}))))

#?(:clj
   (deftest promoted-regression-keeps-a-setup-only-source-expectation
     (testing "a source with a :setup precondition, a declarative :assertions
               entry and NO :script dispatches nothing, so the dialog's
               dispatch-only capture is empty. Both routes must still run
               fail / pass / fail with the source's one assertion"
       (assert-promotions-fail-pass-fail
         :story.promo/setup-declared
         {:tags       #{:test}
          :setup      [[:promo/inc]]
          :assertions [[:rf.assert/path-equals [:n] 1]]}))))

#?(:clj
   (deftest promoted-regression-keeps-a-checkpoint-only-script
     (testing "the login_form testbed's shape: a :setup precondition and a
               :script of [:assert …] checkpoints only. The script dispatches
               nothing, and the checkpoint must still survive both routes"
       (assert-promotions-fail-pass-fail
         :story.promo/setup-checkpoint
         {:tags   #{:test}
          :setup  [[:promo/inc]]
          :script [[:assert [:rf.assert/path-equals [:n] 1]]]}))))

#?(:clj
   (deftest promoted-regression-keeps-its-composed-check
     (testing "a source whose verdict comes from a check named in :compose
               promotes by both routes into a variant that runs fail / pass /
               fail with its check count. :compose is child-only, so even the
               dialog draft's :extends cannot recover the check"
       (rf.story.registrar/reg-check* :check.promo/n-is-one
         {:assertions [[:rf.assert/path-equals [:n] 1]]})
       (assert-promotions-fail-pass-fail
         :story.promo/composed
         {:tags    #{:test}
          :script  [[:dispatch [:promo/inc]]]
          :compose [:check.promo/n-is-one]}
         1))))

#?(:clj
   (deftest promoted-regression-keeps-a-dispatch-free-composed-check
     (testing "the dispatch-free shape Test mode captures from the source's
               stepped program: a :setup precondition, a composed
               check and no :script. The capture is empty, so the check reaches
               the promoted variant only by being carried"
       (rf.story.registrar/reg-check* :check.promo/n-is-one
         {:assertions [[:rf.assert/path-equals [:n] 1]]})
       (assert-promotions-fail-pass-fail
         :story.promo/setup-composed
         {:tags    #{:test}
          :setup   [[:promo/inc]]
          :compose [:check.promo/n-is-one]}
         1))))

#?(:clj
   (defn- reg-boot!
     "The app under test for a run-input checkpoint. `fixed?` false is the
     FAULT — `:promo/boot` seeds `:n` 1 where the run expects 2."
     [fixed?]
     (rf/reg-event :promo/boot
       (fn [{:keys [db]} _]
         {:db (assoc db :n (if fixed? 2 1))}))))

#?(:clj
   (defn- assert-run-input-promotion-fail-pass-fail
     "Run `source-body` under `source-id` with the Test-mode `run-opts`, capture
     the failing run and promote it the way the dialog does, and check the
     capture holds the checkpoint value the run asserted (2) and the promotion
     runs fail / pass / fail with its source's one assertion."
     [source-id source-body run-opts]
     (rf.story/install-canonical-vocabulary!)
     (reg-boot! false)
     (rf.story.registrar/reg-variant* source-id source-body)
     (let [result      (rf.story.async/deref-blocking (rf.story/run source-id run-opts) 10000)
           play-events (rf.story.play/variant-play-events source-id run-opts)
           capture     (rf.story.ui.promotion/result->artifact result play-events run-opts)
           promoted    (keyword (namespace source-id) (str (name source-id) "-dialog"))
           failing     {:status :fail :assertions 1 :checks 0}]
       (is (= failing (verdict result))
           "the source fails on its one checkpoint under the fault")
       (is (= [] play-events)
           "precondition: the script dispatches nothing")
       (when (is (some? capture) "the run is capturable")
         (is (= [[:assert [:rf.assert/path-equals [:n] 2]]] (:event-program capture))
             "the capture asserts the value the run asserted")
         (rf.story.promotion/promote-run-artifact!
           capture
           (rf.story.ui.promotion/draft->promote-opts
             {:variant-id promoted :tags #{:test} :setup-count 0 :extends source-id}))
         (is (= [failing {:status :pass :assertions 1 :checks 0} failing]
                (fault-fix-fault promoted reg-boot!))
             (str promoted " runs fail / pass / fail with its source's one assertion"))))))

#?(:clj
   (deftest promoted-regression-keeps-a-required-run-input
     (testing "a checkpoint-only source whose [:arg] has NO default, run with
               the :cell-overrides that supply it: the capture compiles with the
               run's inputs, and the promotion runs fail / pass / fail"
       (assert-run-input-promotion-fail-pass-fail
         :story.promo/required-input
         {:tags   #{:test}
          :setup  [[:promo/boot]]
          :script [[:assert [:rf.assert/path-equals [:n] [:arg :expected]]]]}
         {:cell-overrides {:expected 2}}))))

#?(:clj
   (deftest promoted-regression-keeps-an-overridden-run-input
     (testing "a checkpoint-only source whose [:arg] defaults to 1, run with
               :cell-overrides {:expected 2}: the capture asserts the executed
               2, so the dialog's default promotion of the failing run still
               fails against the unchanged app, then passes and fails as the
               boot handler is fixed and refaulted"
       (assert-run-input-promotion-fail-pass-fail
         :story.promo/overridden-input
         {:tags   #{:test}
          :args   {:expected 1}
          :setup  [[:promo/boot]]
          :script [[:assert [:rf.assert/path-equals [:n] [:arg :expected]]]]}
         {:cell-overrides {:expected 2}}))))

#?(:clj
   (def ^:private seeds
     "How many times `:promo/seed` has run — the setup-once pin."
     (atom 0)))

#?(:clj
   (defn- reg-seed!
     "The app under test for a run input its SETUP reads. `:promo/seed` takes a
     quantity and records whether the app accepts it. `fixed?` false is the
     FAULT — only the source's default quantity, 1, is accepted; true is the
     repair."
     [fixed?]
     (rf/reg-event :promo/seed
       (fn [{:keys [db]} [_ qty]]
         (swap! seeds inc)
         {:db (assoc db :accepted? (if fixed? (pos? qty) (= 1 qty)))}))))

#?(:clj
   (defn- assert-setup-input-promotion-fail-pass-fail
     "Run `source-body` under `source-id` with the Test-mode `run-opts`, whose
     input only the source's `:setup` reads, capture the failing run and promote
     it with the dialog's default draft, which `:extends` the source and so
     inherits that setup. Run with NO opts, the promoted variant must fail as
     its source did, pass once the app is fixed and fail when the fault returns,
     with its source's one assertion. Run with the source's opts it fails too
     (the positive control), and its inherited setup runs once per run."
     [source-id source-body run-opts]
     (rf.story/install-canonical-vocabulary!)
     (reg-seed! false)
     (rf.story.registrar/reg-variant* source-id source-body)
     (let [result   (rf.story.async/deref-blocking (rf.story/run source-id run-opts) 10000)
           capture  (rf.story.ui.promotion/result->artifact
                      result (rf.story.play/variant-play-events source-id run-opts) run-opts)
           promoted (keyword (namespace source-id) (str (name source-id) "-dialog"))
           failing  {:status :fail :assertions 1 :checks 0}]
       (is (= failing (verdict result))
           "the source fails on its one checkpoint under the run's input")
       (when (is (some? capture) "the run is capturable")
         (rf.story.promotion/promote-run-artifact!
           capture
           (rf.story.ui.promotion/draft->promote-opts
             {:variant-id promoted :tags #{:test} :setup-count 0 :extends source-id}))
         (is (= [failing {:status :pass :assertions 1 :checks 0} failing]
                (fault-fix-fault promoted reg-seed!))
             (str promoted ", run with no opts, runs fail / pass / fail with its source's one assertion"))
         (is (= failing
                (verdict (rf.story.async/deref-blocking (rf.story/run promoted run-opts) 10000)))
             "positive control: run with the source's opts, the promoted variant fails")
         (reset! seeds 0)
         (is (= failing (run-verdict promoted)))
         (is (= 1 @seeds) "the inherited setup runs once per run")))))

#?(:clj
   (deftest promoted-regression-keeps-an-overridden-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty], which
               defaults to 1, and the run overrides it to 2 through
               :cell-overrides. The checkpoint reads no input, so the capture is
               the same under any input. The promoted variant :extends the
               source and inherits that setup, so it must run it with the 2
               that failed, not the default that passes against the unchanged
               app"
       (assert-setup-input-promotion-fail-pass-fail
         :story.promo/setup-overridden-input
         {:tags   #{:test}
          :args   {:qty 1}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:cell-overrides {:qty 2}}))))

#?(:clj
   (deftest promoted-regression-keeps-a-required-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty] with NO default, supplied
               only by the run's :cell-overrides: the promoted variant must
               still compile and run it with the input that ran"
       (assert-setup-input-promotion-fail-pass-fail
         :story.promo/setup-required-input
         {:tags   #{:test}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:cell-overrides {:qty 2}}))))

#?(:clj
   (deftest promoted-regression-keeps-a-mode-supplied-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty], supplied only by an
               active mode: the promoted variant runs with no modes, so it must
               carry the input that ran"
       (rf.story.registrar/reg-mode* :Mode.promo/qty-two {:args {:qty 2}})
       (assert-setup-input-promotion-fail-pass-fail
         :story.promo/setup-mode-input
         {:tags   #{:test}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:active-modes [:Mode.promo/qty-two]}))))

(deftest promotion-carries-the-run-inputs-at-the-values-that-ran
  (testing "the promoted body's :args carry exactly the keys the run's modes and
            cell overrides supplied, at the values the source resolved under the
            ordinary precedence, so the setup the promotion inherits through
            :extends is the setup the run executed"
    (rf.story.registrar/reg-mode* :Mode.promo/qty-three {:args {:qty 3 :note "mode"}})
    (rf.story.registrar/reg-variant* :story.promo/seeded
      {:args   {:qty 1 :note "variant" :label "default"}
       :setup  [[:promo/seed [:arg :qty]]]
       :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]})
    (let [run-opts {:active-modes [:Mode.promo/qty-three] :cell-overrides {:qty 2}}
          capture  (rf.story.ui.promotion/result->artifact
                     {:status :fail :variant/id :story.promo/seeded} [] run-opts)
          draft    {:extends :story.promo/seeded}
          ran      (rf.story.plan/variant-plan
                     :story.promo/seeded
                     {:run-args (rf.story.args/run-arg-layers :story.promo/seeded run-opts)})]
      (is (= {:qty 2 :note "variant"}
             (:args (rf.story.promotion/artifact->variant-body capture draft)))
          "the cell override beats the mode for :qty, the source's own :note
           beats the mode, and :label, which no run input named, is not carried")
      (is (= (get-in ran [:world :setup])
             (get-in (rf.story.promotion/materialize-variant-plan capture draft) [:world :setup]))
          "the promoted plan's inherited setup is the setup the run executed")
      (is (= {:qty 5 :note "variant"}
             (:args (rf.story.promotion/artifact->variant-body
                      capture (assoc draft :args {:qty 5}))))
          "an explicit :args opt still wins over a carried input")
      (is (not (contains? (rf.story.promotion/artifact->variant-body
                            (rf.story.ui.promotion/result->artifact
                              {:status :fail :variant/id :story.promo/seeded} [])
                            draft)
                          :args))
          "a capture that recorded no run inputs carries no :args"))))

(deftest promotion-compiles-the-source-with-the-captured-run-inputs
  (testing "promotion compiles the source with the run inputs a Test-mode
            capture recorded, so a dispatch-only capture whose dispatch carries
            a run-only [:arg] is still replaced by the source's full program,
            and a composed check is still carried although the source does not
            compile without that input"
    (rf.story.registrar/reg-check* :check.promo/n-is-two
      {:assertions [[:rf.assert/path-equals [:n] 2]]})
    (rf.story.registrar/reg-variant* :story.promo/input-driven
      {:script  [[:dispatch [:promo/set [:arg :n]]]
                 [:assert [:rf.assert/path-equals [:n] [:arg :n]]]]
       :compose [:check.promo/n-is-two]})
    (let [run-opts {:cell-overrides {:n 2}}
          capture  (rf.story.ui.promotion/result->artifact
                     {:status :fail :variant/id :story.promo/input-driven}
                     (rf.story.play/variant-play-events :story.promo/input-driven run-opts)
                     run-opts)
          body     (rf.story.promotion/artifact->variant-body
                     capture {:extends :story.promo/input-driven})]
      (is (= [[:dispatch [:promo/set 2]]] (:event-program capture))
          "precondition: Test mode captures the dispatch that ran")
      (is (= [[:dispatch [:promo/set 2]]
              [:assert [:rf.assert/path-equals [:n] 2]]]
             (:script body))
          "the source's full program, compiled with the run's inputs")
      (is (= [:check.promo/n-is-two] (:checks body))
          "the composed check, resolved from the source compiled with the run's inputs"))))

(deftest ordinary-extends-inheritance-is-unchanged
  (testing "a plain :extends child still gets NO terminal assertions from its
            parent — promotion carries them, inheritance deliberately does not"
    (rf.story.registrar/reg-variant* :story.promo/parent
      {:script     [[:dispatch [:promo/inc]]]
       :assertions [[:rf.assert/path-equals [:n] 1]]})
    (rf.story.registrar/reg-variant* :story.promo/plain-child
      {:extends :story.promo/parent})
    (is (= [[:rf.assert/path-equals [:n] 1]]
           (get-in (rf.story.plan/variant-plan :story.promo/parent) [:expect :assertions])))
    (is (= [] (get-in (rf.story.plan/variant-plan :story.promo/plain-child)
                      [:expect :assertions])))))

(deftest only-the-recorded-source-variant-is-carried
  (rf.story.registrar/reg-variant* :story.promo/recorded
    {:script     [[:dispatch [:promo/inc]]]
     :assertions [[:rf.assert/path-equals [:n] 1]]})
  (let [program [[:dispatch [:promo/inc]]]
        body-of (fn [parts opts]
                  (select-keys (rf.story.promotion/artifact->variant-body
                                 (rf.story.artifact/make-run-artifact
                                   (assoc parts :event-program program))
                                 opts)
                               [:assertions :extends :script]))]
    (testing "the source is read off the artifact: [:result :variant/id] (a
              Test-mode capture) or [:source :variant/id] (a plan-derived
              one), and with no :extends given it is extended"
      (doseq [parts [{:result {:status :fail :variant/id :story.promo/recorded}}
                     {:source {:tool :determinism-gate :variant/id :story.promo/recorded}}]]
        (is (= {:assertions [[:rf.assert/path-equals [:n] 1]]
                :extends    :story.promo/recorded
                :script     program}
               (body-of parts nil)))))
    (testing "an :extends parent is NOT a source — an artifact that records no
              source is promoted exactly as captured"
      (is (= {:extends :story.promo/recorded :script program}
             (body-of {} {:extends :story.promo/recorded}))))
    (testing "a recorded source that is not registered carries nothing and
              has nothing to extend"
      (is (= {:script program}
             (body-of {:result {:variant/id :story.promo/never-registered}} nil))))))

(deftest promotion-carries-the-resolved-checks-once
  (testing "the carried :checks come from the compiler's resolution of the
            source (inherited, own and composed, each id once), so the promoted
            plan resolves the same checks as its source whether or not it
            :extends that source"
    (doseq [cid [:check.promo/inherited :check.promo/own :check.promo/composed]]
      (rf.story.registrar/reg-check* cid {:assertions [[:rf.assert/path-equals [:n] 1]]}))
    (rf.story.registrar/reg-variant* :story.promo/checked-parent
      {:checks [:check.promo/inherited]})
    (rf.story.registrar/reg-variant* :story.promo/checked
      {:extends :story.promo/checked-parent
       :script  [[:dispatch [:promo/inc]]]
       :checks  [:check.promo/own]
       :compose [:check.promo/composed :check.promo/own]})
    (let [resolved  [:check.promo/inherited :check.promo/own :check.promo/composed]
          art       (rf.story.determinism/->artifact
                      (rf.story.plan/variant-plan :story.promo/checked))
          checks-of (fn [body]
                      (get-in (rf.story.plan/variant-plan
                                (assoc body :variant/id :story.promo/checked-promoted))
                              [:expect :checks]))]
      (is (= resolved (get-in (rf.story.plan/variant-plan :story.promo/checked)
                              [:expect :checks]))
          "control: this is how the compiler resolves the source's checks")
      (testing "extending a variant other than the source, the inherited check
                is retained and the id named in both :checks and :compose is
                carried once"
        (let [body (rf.story.promotion/artifact->variant-body
                     art {:extends :story.promo/checked-parent})]
          (is (= :story.promo/checked-parent (:extends body))
              "an explicit :extends wins over the default")
          (is (= resolved (:checks body)))
          (is (= resolved (checks-of body)))))
      (testing "with :extends of the source, given or defaulted,
                inheritance supplies the chain's checks, so the body adds beside
                the source's own only what :extends cannot recover"
        (doseq [opts [{:extends :story.promo/checked} nil]]
          (let [body (rf.story.promotion/artifact->variant-body art opts)]
            (is (= :story.promo/checked (:extends body)))
            (is (= [:check.promo/own :check.promo/composed] (:checks body)))
            (is (= resolved (checks-of body)))))))))

(def ^:private dom-script
  "A DOM-driven play: typing, a click, a wait and two checkpoints, behind one
  dispatch (Test mode offers promotion only for a run with a dispatch)."
  [[:dispatch [:promo/open]]
   [:type "[data-test=promo-name]" "Ada"]
   [:click "[data-test=promo-save]"]
   [:wait 20]
   [:assert-dom "[data-test=promo-name]" :visible]
   [:assert [:rf.assert/path-equals [:saved] "Ada"]]])

(deftest promoted-dom-driven-variant-retains-its-script
  (testing "the dialog's dispatch-only capture of a DOM-driven variant promotes
            into a body carrying the source's FULL step program, so the promoted
            plan types, clicks, waits and asserts exactly as its source does and
            declares the same expectations and the same DOM runner requirement.
            The browser suite `re-frame.story.promotion-dom-cljs-test` runs it
            fail / pass / fail"
    (rf.story.registrar/install-canonical-tags!)
    (rf.story.registrar/reg-variant* :story.promo/dom
      {:tags       #{:test}
       :script     dom-script
       :assertions [[:rf.assert/path-equals [:saved] "Ada"]]})
    (let [capture  (rf.story.ui.promotion/result->artifact
                     {:status :fail :variant/id :story.promo/dom}
                     (rf.story.play/variant-play-events :story.promo/dom))
          body     (rf.story.promotion/artifact->variant-body
                     capture
                     (rf.story.ui.promotion/draft->promote-opts
                       {:variant-id  :story.promo/dom-promoted
                        :tags        #{:test}
                        :setup-count 0
                        :extends     :story.promo/dom}))
          source   (rf.story.plan/variant-plan :story.promo/dom)
          promoted (rf.story.plan/variant-plan
                     (assoc body :variant/id :story.promo/dom-promoted))]
      (is (= [[:dispatch [:promo/open]]] (:event-program capture))
          "Test mode captures only the dispatch — typing, click, wait and both
           checkpoints are absent from the artifact itself")
      (is (= (rf.story.play/variant-play-steps :story.promo/dom) (:script body))
          "the promoted body carries the source's full step program, in order")
      (is (= (:script source) (:script promoted)))
      (is (= (:expect source) (:expect promoted))
          "the same checks and the same terminal assertions")
      (is (= (:required-runner source) (:required-runner promoted)))
      (is (contains? (:required-runner promoted) :dom)
          "the promoted variant is still DOM-driven, not a flattened event replay"))))

;; ===========================================================================
;; The API route reproduces a source whose run depends on world
;; ===========================================================================
;;
;; The API route promotes `determinism/->artifact` of the source's compiled
;; plan. An artifact carries a program, the fx decisions and `:network`, but
;; not the source's decorator stubs, `:db-seed`, frame-setup or loaders.
;; Folding `[:world :setup]` into that program too would reproduce a source
;; with both `:setup` and world slots under neither spelling: without
;; `:extends` the promoted variant would run unseeded and fire the effect its
;; source stubbed, and with `:extends` of the source it would run the source's
;; setup twice. So the API route mirrors the Test-mode dialog: the program
;; leaves setup out, and promotion defaults `:extends` to the registered
;; source, which supplies setup and world exactly once.

#?(:clj
   (def ^:private world-real-calls
     "How many times the stubbed effect's REAL handler ran."
     (atom 0)))

#?(:clj
   (defn- reg-world-app!
     "The app under test for a world-dependent source. `:promo.world/load`
     issues `:promo.world/http`, whose real handler counts its calls and
     returns normally, so only the call count shows the stub was lost."
     []
     (rf/reg-fx :promo.world/http {:platforms #{:client :server}}
                (fn [_ctx _args] (swap! world-real-calls inc) nil))
     (rf/reg-event :promo.world/load (fn [_ _] {:fx [[:promo.world/http {}]]}))
     (rf/reg-event :promo.world/inc
       (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))))

#?(:clj
   (defn- api-recipe-promote!
     "Promote `source-id` by the documented API recipe (spec/017 §Promotion),
     compiling its plan with the run inputs in `run-opts`."
     [source-id promoted-id run-opts]
     (rf.story/promote-run-artifact!
       (rf.story.determinism/->artifact
         (rf.story/variant-plan source-id
                                {:run-args (rf.story.args/run-arg-layers source-id run-opts)}))
       {:variant/id promoted-id})))

#?(:clj
   (defn- world-verdict
     "Run variant `id` headless: its status, the final `:count`, and how many
     times the stubbed effect's real handler ran."
     [id]
     (reset! world-real-calls 0)
     (let [result (rf.story.async/deref-blocking (rf.story/run id) 10000)]
       {:status     (:status result)
        :count      (get-in result [:app-db :count])
        :real-calls @world-real-calls})))

#?(:clj
   (deftest api-route-reproduces-a-world-dependent-source
     (testing "a source whose run depends on a force-fx-stub decorator and a
               :db-seed, with and without a :setup, promotes by the documented
               API recipe into a variant that reproduces its count with 0 real
               calls"
       (rf.story/install-canonical-vocabulary!)
       (reg-world-app!)
       (let [world {:decorators [[:rf.story/force-fx-stub :promo.world/http {:status 200}]]
                    :db-seed    {:count 10}
                    :script     [[:dispatch [:promo.world/load]]
                                 [:dispatch [:promo.world/inc]]]}]
         (doseq [[source-id body expected]
                 [[:story.promo-world/stubbed world 11]
                  [:story.promo-world/stubbed-setup
                   (assoc world :setup [[:dispatch [:promo.world/inc]]]) 12]]]
           (rf.story.registrar/reg-variant* source-id body)
           (let [source   (world-verdict source-id)
                 promoted (keyword (namespace source-id) (str (name source-id) "-api"))]
             (is (= {:status :pass :count expected :real-calls 0} source)
                 (str "control: " source-id " stubs its effect and runs seeded"))
             (api-recipe-promote! source-id promoted nil)
             (is (= source (world-verdict promoted))
                 (str promoted " reproduces " source-id "'s count with 0 real calls"))))))))

(deftest api-route-from-an-inline-plan-keeps-its-setup
  (testing "an inline plan names no registered variant, so nothing is
            extended: its setup stays folded into the promoted program, already
            holding the run input's value, and the body carries no :args"
    (let [body (rf.story.promotion/artifact->variant-body
                 (rf.story.determinism/->artifact
                   (rf.story.plan/variant-plan
                     {:args   {:qty 1}
                      :setup  [[:dispatch [:promo/seed [:arg :qty]]]]
                      :script [[:dispatch [:promo/inc]]]}
                     {:run-args (rf.story.args/run-arg-layers nil {:cell-overrides {:qty 9}})})))]
      (is (= {:script [[:dispatch [:promo/seed 9]] [:dispatch [:promo/inc]]]}
             (select-keys body [:script :extends :args]))))))

;; ===========================================================================
;; The API route keeps the run inputs its source's setup reads
;; ===========================================================================
;;
;; The API route leaves a registered source's `[:world :setup]` out of the
;; promoted program, and `:extends` supplies it. That setup re-substitutes its
;; `[:arg]` placeholders when the promoted variant compiles, so without the
;; run's inputs a plan compiled with a cell override or an active mode would
;; promote into a variant whose inherited setup reads the source's default
;; instead. The artifact records the args its plan resolved, and promotion
;; carries the ones the run inputs changed as the body's own `:args`, as it
;; does for a Test-mode capture.

(deftest api-route-carries-the-run-inputs-at-the-values-that-ran
  (testing "promoted by the API route, the body's :args carry exactly the keys
            the plan's run inputs changed, at the values the plan resolved, so
            the setup the body inherits through :extends is the setup the plan
            compiled"
    (rf.story.registrar/reg-story* :story.promo-input {:args {:tone "story"}})
    (rf.story.registrar/reg-mode* :Mode.promo-input/loud {:args {:qty 3 :tone "loud"}})
    (rf.story.registrar/reg-variant* :story.promo-input/seeded
      {:args   {:qty 1 :label "default"}
       :setup  [[:promo/seed [:arg :qty]]]
       :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]})
    (let [src     :story.promo-input/seeded
          plan-of (fn [run-opts]
                    (rf.story.plan/variant-plan
                      src {:run-args (rf.story.args/run-arg-layers src run-opts)}))
          body-of (fn [plan opts]
                    (rf.story.promotion/artifact->variant-body
                      (rf.story.determinism/->artifact plan) opts))
          ran     (plan-of {:active-modes [:Mode.promo-input/loud] :cell-overrides {:qty 9}})]
      (is (= [[:dispatch [:promo/seed 9]]] (get-in ran [:world :setup]))
          "precondition: the source plan compiled its setup with the override")
      (is (= {:qty 9 :tone "loud"} (:args (body-of ran nil)))
          "the cell override beats the variant's :qty, the mode beats the
           story's :tone, and :label, which no input changed, is not carried")
      (doseq [opts [nil {:extends src}]]
        (is (= (get-in ran [:world :setup])
               (get-in (rf.story.promotion/materialize-variant-plan
                         (rf.story.determinism/->artifact ran) opts)
                       [:world :setup]))
            "the promoted plan's inherited setup is the setup the source plan compiled"))
      (is (= {:qty 5 :tone "loud"} (:args (body-of ran {:args {:qty 5}})))
          "an explicit :args opt still wins over a carried input")
      (testing "default-input control: a plan compiled with no run inputs, with
                or without the ambient layers, carries no :args"
        (is (not (contains? (body-of (plan-of nil) nil) :args)))
        (is (not (contains? (body-of (rf.story.plan/variant-plan src) nil) :args)))))))

#?(:clj
   (deftest api-route-keeps-a-mode-supplied-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty], supplied only by an active
               mode, so the source does not compile without it. Promoted by the
               API recipe, the variant runs with no modes, so it must carry the
               9 that ran, run fail / pass / fail with its source's one
               assertion, and run its inherited setup once per run"
       (rf.story/install-canonical-vocabulary!)
       (reg-seed! false)
       (rf.story.registrar/reg-mode* :Mode.promo-input/qty-nine {:args {:qty 9}})
       (rf.story.registrar/reg-variant* :story.promo-input/mode
         {:tags   #{:test}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]})
       (let [run-opts {:active-modes [:Mode.promo-input/qty-nine]}
             failing  {:status :fail :assertions 1 :checks 0}
             promoted :story.promo-input/mode-api]
         (is (= failing (verdict (rf.story.async/deref-blocking
                                   (rf.story/run :story.promo-input/mode run-opts) 10000)))
             "the source fails under the run's input")
         (api-recipe-promote! :story.promo-input/mode promoted run-opts)
         (is (= {:qty 9} (:args (rf.story.registrar/handler-meta :variant promoted))))
         (is (= [failing {:status :pass :assertions 1 :checks 0} failing]
                (fault-fix-fault promoted reg-seed!)))
         (reset! seeds 0)
         (is (= failing (run-verdict promoted)))
         (is (= 1 @seeds) "the inherited setup runs once per run")))))
