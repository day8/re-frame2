(ns re-frame.story-assertions-test
  "JVM tests for Story's `:rf.assert/*` vocabulary (spec/007 §Assertion
  vocabulary): the seven dispatched assertions, the tape-evaluated
  `:rf.assert/schema-error`, the record-don't-throw contract and
  `assertions-passing?`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ;; The tape-projected assertions read the epoch tape.
            [re-frame.epoch            :as rf.epoch]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.subs             :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play.evidence     :as rf.story.play.evidence]
            [re-frame.story.ui.evidence-spine :as rf.story.ui.evidence-spine]
            [re-frame.story.ui.test-mode.pure :as rf.story.ui.test-mode.pure]
            [re-frame.trace            :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  ;; A fresh tape per test, so the tape-projected assertions read only their own.
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

(defn- run-v! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000))

(deftest canonical-seven-registered
  (let [events (rf.registrar/registrations :event)
        seven  [:rf.assert/path-equals :rf.assert/path-matches :rf.assert/sub-equals
                :rf.assert/dispatched? :rf.assert/state-is :rf.assert/no-warnings
                :rf.assert/effect-emitted]]
    (is (empty? (remove #(contains? events %) seven))
        "the seven dispatched handlers register at install-canonical-vocabulary!")
    (is (not (contains? events :rf.assert/schema-error))
        ":rf.assert/schema-error is tape-evaluated, not a reg-event handler")
    (is (= (conj (set seven) :rf.assert/schema-error)
           (set (rf.story/canonical-assertion-ids))
           (set rf.story.assertions/canonical-assertion-ids))
        "canonical-assertion-ids is the seven plus :rf.assert/schema-error")))

(deftest path-equals-pass
  (testing ":rf.assert/path-equals passes when the value at path matches"
    (rf/reg-event :test/set-status
      (fn [{:keys [db]} _] {:db (assoc-in db [:auth :status] :authenticated)}))
    (rf.story/reg-variant :story.auth/happy
      {:setup [[:test/set-status]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:auth :status] :authenticated]]]})
    (let [[a & more] (:assertions (run-v! :story.auth/happy))]
      (is (= [nil true :rf.assert/path-equals :authenticated :authenticated
              [[:auth :status] :authenticated]]
             [more (:passed? a) (:assertion a) (:actual a) (:expected a) (:payload a)]))
      (is (number? (:elapsed-ms a)))
      (is (string? (:reason a))))
    (rf.story/destroy-variant! :story.auth/happy)))

(deftest path-matches-pass
  (testing ":rf.assert/path-matches validates against malli"
    (rf/reg-event :test/set-count
      (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (rf.story/reg-variant :story.malli/ok
      {:setup [[:test/set-count]]
       :script [[:dispatch-sync [:rf.assert/path-matches [:n] :int]]]})
    (let [r (rf.story.async/deref-blocking (rf.story/run-variant :story.malli/ok) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (rf.story/destroy-variant! :story.malli/ok)))

(deftest path-matches-fail
  (testing ":rf.assert/path-matches records failure with explanation on schema mismatch"
    (rf/reg-event :test/set-bad
      (fn [{:keys [db]} _] {:db (assoc db :n "not a number")}))
    (rf.story/reg-variant :story.malli/bad
      {:setup [[:test/set-bad]]
       :script [[:dispatch-sync [:rf.assert/path-matches [:n] :int]]]})
    (let [r (rf.story.async/deref-blocking (rf.story/run-variant :story.malli/bad) 5000)]
      (is (false? (-> r :assertions first :passed?)))
      (is (some? (-> r :assertions first :explanation))
          "the failure record carries the Malli explanation"))
    (rf.story/destroy-variant! :story.malli/bad)))

(deftest path-matches-explanation-redacts-a-sensitive-value
  ;; Malli's explain carries the value it validated; on a classified path
  ;; the record's :explanation must be as leak-free as its :actual, whether
  ;; the asserted path is the sensitive leaf or a map holding it.
  (rf/reg-event :test/login
    (fn [{:keys [db]} _] {:db (assoc db :auth {:token "BEARER-secret-12345" :user "ada"})}))
  (rf.story/reg-variant :story.malli/classified
    {:setup     [[:test/login]]
     :sensitive {:app-db [[:auth :token]]}
     :script    [[:dispatch-sync [:rf.assert/path-matches [:auth :token] :int]]
                 [:dispatch-sync [:rf.assert/path-matches [:auth] [:map [:token :int] [:user :int]]]]]})
  (let [r       (run-v! :story.malli/classified)
        records (filter #(= :rf.assert/path-matches (:assertion %)) (:assertions r))]
    (is (= [false false] (mapv :passed? records)))
    (is (every? #(re-find #":rf/redacted" (:explanation %)) records)
        "the explanation keeps its shape, the sentinel standing in for the secret")
    (is (re-find #"ada" (:explanation (second records)))
        "a value on an unclassified path still explains its own failure")
    (is (not (re-find #"BEARER-secret" (pr-str records)))
        "no slot of either record carries the raw token"))
  (rf.story/destroy-variant! :story.malli/classified))

(deftest sub-equals-pass
  (testing ":rf.assert/sub-equals compares the subscription's value, recording a mismatch"
    (rf/reg-event :test/init
      (fn [{:keys [db]} _] {:db (assoc db :counter 7)}))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    (rf.story/reg-variant :story.sub/v
      {:setup [[:test/init]]
       :script [[:dispatch-sync [:rf.assert/sub-equals [:counter] 7]]
                [:dispatch-sync [:rf.assert/sub-equals [:counter] 3]]]})
    (is (= [[true 7] [false 7]]
           (map (juxt :passed? :actual) (:assertions (run-v! :story.sub/v)))))
    (rf.story/destroy-variant! :story.sub/v)))

(deftest sub-equals-runtime-db-projection
  (testing ":rf.assert/sub-equals over a runtime-db projection sub (the idiomatic
            machine-snapshot shape) resolves the live value, not nil — the
            play-runner hands compute-sub the whole frame state, not the bare
            app-db"
    (rf/reg-event :test/seed-machine-sub
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :traffic-light]
                                  {:state :red})}))
    (rf.subs/reg-runtime-sub :traffic-light/state
      (fn [rt _] (get-in rt [:rf.runtime/machines :snapshots :traffic-light :state])))
    (rf.story/reg-variant :story.sub/runtime
      {:setup [[:test/seed-machine-sub]]
       :script [[:dispatch-sync [:rf.assert/sub-equals [:traffic-light/state] :red]]]})
    (is (= [true :red]
           ((juxt :passed? :actual) (first (:assertions (run-v! :story.sub/runtime))))))
    (rf.story/destroy-variant! :story.sub/runtime)))

;; spec 004 §Privacy rule 1: a sub declaring its whole output sensitive
;; (spec/015's own `:partner/api-token` example) records the sentinel in
;; every value-bearing slot, though its query vector names no app-db path.
(deftest sub-equals-record-projects-a-sensitive-sub-output
  (rf/reg-event :test/store-key
    (fn [{:keys [db]} _] {:db (assoc-in db [:tenant :partner-api-key] "PARTNER-KEY-SECRET")}))
  (rf/reg-sub :partner/api-token {:sensitive [[]]}
    (fn [db _] (get-in db [:tenant :partner-api-key])))
  (rf/reg-sub :partner/api-token-public
    (fn [db _] (get-in db [:tenant :partner-api-key])))
  (rf.story/reg-check :check.privacy/key-stored
    {:assertions [[:rf.assert/sub-equals [:partner/api-token] "PARTNER-KEY-SECRET"]]})
  (rf.story/reg-variant :story.privacy/sub-classified
    {:setup  [[:test/store-key]]
     :script [[:assert [:rf.assert/sub-equals [:partner/api-token] "PARTNER-KEY-SECRET"]]
              [:assert [:rf.assert/sub-equals [:partner/api-token] "WRONG"]]]})
  (rf.story/reg-variant :story.privacy/sub-check
    {:setup [[:test/store-key]] :checks [:check.privacy/key-stored]})
  (rf.story/reg-variant :story.privacy/sub-public
    {:setup  [[:test/store-key]]
     :script [[:assert [:rf.assert/sub-equals [:partner/api-token-public] "PARTNER-KEY-SECRET"]]]})
  (let [r       (run-v! :story.privacy/sub-classified)
        records (filterv #(= :rf.assert/sub-equals (:assertion %)) (:assertions r))]
    (is (= [[true :rf/redacted :rf/redacted [[:partner/api-token] :rf/redacted]]
            [false :rf/redacted :rf/redacted [[:partner/api-token] :rf/redacted]]]
           (mapv (juxt :passed? :expected :actual :payload) records))
        "the raw comparison still decides pass and fail; the records carry the sentinel")
    (is (not (str/includes? (pr-str (:assertions r)) "PARTNER-KEY-SECRET"))
        "no slot of either record, reason included, carries the secret"))
  (let [r (run-v! :story.privacy/sub-check)]
    (is (= [[:check.privacy/key-stored :pass [:rf/redacted]]]
           (mapv (juxt :check :status #(mapv :actual (:assertions %))) (:checks r)))
        "a check still groups its redacted record")
    (is (not (str/includes? (pr-str (select-keys r [:assertions :checks])) "PARTNER-KEY-SECRET"))))
  (let [r (run-v! :story.privacy/sub-public)]
    (is (= [[true "PARTNER-KEY-SECRET"]]
           (mapv (juxt :passed? :actual) (:assertions r)))
        "control: an unclassified sub's record keeps the value it returned"))
  (rf.story/destroy-variant! :story.privacy/sub-classified)
  (rf.story/destroy-variant! :story.privacy/sub-check)
  (rf.story/destroy-variant! :story.privacy/sub-public))

(deftest state-is-pass
  (testing ":rf.assert/state-is reads the machine snapshot in the runtime-db
            partition, recording expected and actual on a mismatch"
    (rf/reg-event :test/seed-machine
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :traffic-light]
                                  {:state :red})}))
    (rf.story/reg-variant :story.machine/red
      {:setup [[:test/seed-machine]]
       :script [[:dispatch-sync [:rf.assert/state-is :traffic-light :red]]
                [:dispatch-sync [:rf.assert/state-is :traffic-light :green]]]})
    (is (= [true [false :green :red]]
           (let [[a b] (:assertions (run-v! :story.machine/red))]
             [(:passed? a) ((juxt :passed? :expected :actual) b)])))
    (rf.story/destroy-variant! :story.machine/red)))

;; Record-don't-throw: `004-Assertions.md` §Record-don't-throw semantics.
(deftest record-not-throw-on-failure
  (testing "a failing assertion never throws; the play sequence continues"
    (rf/reg-event :test/touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (rf.story/reg-variant :story.contract/v
      {:setup []
       :script [[:dispatch-sync [:rf.assert/path-equals [:nope] :unexpected]]
                [:dispatch-sync [:test/touch]]
                [:dispatch-sync [:rf.assert/path-equals [:touched] true]]]})
    (let [r (run-v! :story.contract/v)]
      (is (= [[false true] true]
             [(mapv :passed? (:assertions r)) (-> r :app-db :touched)])
          "both assertions recorded and :test/touch fired between them"))
    (rf.story/destroy-variant! :story.contract/v)))

(deftest assertions-passing-vacuously-true-on-empty
  (testing "assertions-passing? is vacuously true on an empty list
            (spec/007 §Story-as-test duality) and false when any assertion failed"
    (rf/reg-event :test/n2 (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf.story/reg-variant :story.empty/v {:setup [] :script []})
    (rf.story/reg-variant :story.any-fail/v
      {:setup [[:test/n2]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:n] 1]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 999]]]})
    (let [empty-run (run-v! :story.empty/v)]
      (is (= [true []] [(rf.story/assertions-passing? empty-run) (:assertions empty-run)])))
    (is (false? (rf.story/assertions-passing? (run-v! :story.any-fail/v))))
    (rf.story/destroy-variant! :story.empty/v)
    (rf.story/destroy-variant! :story.any-fail/v)))

;; :rf.assert/schema-error declares an EXPECTED schema violation (spec/017
;; §Schema rule); its selector must mirror the projected violation's so the
;; multiset matcher pairs them.
(deftest schema-error-selector-mirrors-violation-selector
  (testing "the declared selector matches evidence/violation-selector key for key
            per surface; a where-less spec is the [:any] wildcard; an unknown
            surface keys by [:where failing-id]"
    (doseq [[spec selector]
            [[{:where :event :event :checkout/submit} [:event :checkout/submit]]
             [{:where :event :event :checkout/submit :path [:cart]} [:event :checkout/submit [:cart]]]
             [{:where :cofx :cofx :load/session} [:cofx :load/session]]
             [{:where :fx-args :fx-args :http/get} [:fx-args :http/get]]
             [{:where :sub-return :sub-return :auth/state :query-v [:auth/state]}
              [:sub-return :auth/state [:auth/state]]]
             [{:where :app-db :registered-path [:auth] :path [:auth :token]}
              [:app-db [:auth] [:auth :token]]]
             [{:where :machine-data :machine-id :checkout/fsm :phase :entry}
              [:machine-data :checkout/fsm :entry]]
             [{} [:any]]
             [{:event :x} [:any]]
             [{:where :custom/surface :failing-id :some-id} [:custom/surface :some-id]]]]
      (is (= selector (rf.story.assertions/schema-error-selector spec)) (pr-str spec)))))

(deftest schema-error-expectation-projection
  (let [a [:rf.assert/schema-error {:where :event :event :x}]]
    (is (= {:atom a :spec {:where :event :event :x} :selector [:event :x]}
           (select-keys (rf.story.assertions/schema-error-expectation a)
                        [:atom :spec :selector])))))

;; The evaluator is pure over the tape-projected warning records, so its
;; :count / :actual projection is reached through the var.
(def ^:private evaluate-no-warnings @#'rf.story.assertions/evaluate-no-warnings)

(deftest evaluate-no-warnings-fail-branch
  (testing "warnings present fail, with :count and the operations as :actual;
            none passes"
    (let [out (evaluate-no-warnings [{:operation :rf.warning/slow-sub
                                      :epoch-id 1 :trace-id 10}
                                     {:operation :rf.warning/deprecated
                                      :epoch-id 2 :trace-id 20}]
                                    [])]
      (is (= [false 2 [:rf.warning/slow-sub :rf.warning/deprecated]]
             ((juxt :passed? :count :actual) out))))
    (is (= [true 0 []] ((juxt :passed? :count :actual) (evaluate-no-warnings [] []))))))

(deftest causal-bounds-exactly-shorthand
  (testing "per-id default bounds, and the :exactly / :min / :max overrides"
    (doseq [[id spec bounds] [[:rf.assert/caused {} {:min 1}]
                              [:rf.assert/caused {:exactly 2} {:min 2 :max 2}]
                              [:rf.assert/caused {:min 3} {:min 3}]
                              [:rf.assert/caused {:min 2 :max 5} {:min 2 :max 5}]
                              [:rf.assert/no-cascade-rerender {:exactly 0} {:min 0 :max 0}]]]
      (is (= bounds (rf.story.assertions/causal-bounds id spec)) (pr-str id spec)))))

(deftest causal-effect-surface-sub-over-view-precedence
  (testing ":sub wins over :view; neither measures the cause's total count"
    (is (= [[:sub :a] [:sub :a] [:view :b] [:any]]
           (map rf.story.assertions/causal-effect-surface
                [{:sub :a :view :b} {:sub :a} {:view :b} {}])))))

;; An in-script [:rf.assert/no-warnings] and the run result's :warnings slot
;; read the one tape projection, keyed on `:op-type :warning`: an
;; `:op-type :error` (an unregistered event) is in neither.
(deftest no-warnings-agrees-with-warnings-slot-on-error-only-run
  (rf.story/reg-variant :story.ssot/error-only
    {:setup []
     :script [[:dispatch-sync [:no/such-handler]]
              [:dispatch-sync [:rf.assert/no-warnings]]]})
  (let [r       (run-v! :story.ssot/error-only)
        no-warn (last (filter #(= :rf.assert/no-warnings (:assertion %)) (:assertions r)))]
    (is (= [0 true 0] [(count (:warnings r)) (:passed? no-warn) (:count no-warn)])
        "an error-only run: the slot is empty and the assertion passes with a matching :count"))
  (rf.story/destroy-variant! :story.ssot/error-only))

(deftest dispatched?-projects-from-tape-trigger-events
  (testing ":rf.assert/dispatched? reads the tape's :trigger-event projection,
            matching a literal vector or a bare keyword head — including a
            re-dispatched (nested) event"
    (rf/reg-event :ssot/outer (fn [_ _] {:fx [[:dispatch [:ssot/inner 42]]]}))
    (rf/reg-event :ssot/inner (fn [{:keys [db]} [_ n]] {:db (assoc db :inner n)}))
    (rf.story/reg-variant :story.ssot/dispatched
      {:setup []
       :script [[:dispatch-sync [:ssot/outer]]
                [:dispatch-sync [:rf.assert/dispatched? [:ssot/inner 42]]]
                [:dispatch-sync [:rf.assert/dispatched? :ssot/inner]]
                [:dispatch-sync [:rf.assert/dispatched? [:never/fired]]]]})
    (is (= {[:ssot/inner 42] true :ssot/inner true [:never/fired] false}
           (->> (:assertions (run-v! :story.ssot/dispatched))
                (filter #(= :rf.assert/dispatched? (:assertion %)))
                (into {} (map (juxt :expected :passed?))))))
    (rf.story/destroy-variant! :story.ssot/dispatched)))

;; `dispatched-events` feeds `:rf.assert/dispatched?` and the loaders'
;; vector-form `:loaders-complete-when`. Spec 009 §Privacy: needles match the
;; raw trigger events, and the record shows a sensitive epoch's trigger as
;; `[event-id :rf/redacted]` while the local-render egress profile redacts;
;; an `:rf.assert/*` trigger is a verdict the runner dispatched, never
;; behaviour under test.

(defn- with-tape
  "Run `thunk` with the private `frame-tape` projection redefed to return
  `tape` for any frame (`with-redefs-fn` takes the `#'`-var directly, so a
  private fn in another ns is redefable without importing it)."
  [tape thunk]
  (with-redefs-fn {#'rf.story.assertions/frame-tape (constantly tape)} thunk))

(deftest dispatched?-matches-raw-and-records-projected
  (let [tape     [{:trigger-event [:auth/login]}
                  {:trigger-event [:auth/submit {:password "hunter2"}] :rf.epoch/sensitive? true}
                  {:trigger-event [:rf.assert/path-equals [:cart] {}]}
                  {:trigger-event [:cart/checkout] :rf.epoch/sensitive? true}]
        epochs   (with-tape tape #(@#'rf.story.assertions/dispatched-epochs :any-frame))
        evaluate @#'rf.story.assertions/evaluate-dispatched?
        redacted (evaluate epochs false [[:auth/submit {:password "hunter2"}]])]
    (is (= [[:auth/login] [:auth/submit {:password "hunter2"}] [:cart/checkout]]
           (with-tape tape #(rf.story.assertions/dispatched-events :any-frame)))
        "matching reads every raw trigger, and the assertion trigger always drops")
    (is (= [true [:auth/submit :rf/redacted] [[:auth/login] [:auth/submit :rf/redacted] [:cart/checkout]]]
           [(:passed? redacted) (:expected redacted) (:actual redacted)])
        "a sensitive trigger still matches, and reaches the record redacted")
    (is (= [[:auth/submit :rf/redacted]] (:payload redacted))
        "the record's payload carries the projected event, not the author's raw needle")
    (is (= [[:auth/login] [:auth/submit {:password "hunter2"}] [:cart/checkout]]
           (:actual (evaluate epochs true [:cart/checkout])))
        "a frame revealed to :rf.egress/local-raw records the raw triggers")))

(deftest a-classified-variant-asserts-and-waits-on-what-it-dispatched
  ;; Once setup fills a classified path every later epoch is sensitive, so
  ;; matching has to read the raw triggers for either form to work at all.
  (rf/reg-event :auth/login (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "BEARER-x")}))
  (rf/reg-event :cart/checkout (fn [{:keys [db]} _] {:db (assoc db :checked-out? true)}))
  (rf.story/reg-variant :story.classified/dispatched
    {:setup     [[:auth/login] [:cart/checkout]]
     :sensitive {:app-db [[:auth :token]]}
     :script    [[:dispatch-sync [:rf.assert/dispatched? :cart/checkout]]]})
  (rf.story/reg-variant :story.classified/loaders
    {:loaders               [[:auth/login]]
     :loaders-complete-when [[:auth/login]]
     :setup                 [[:cart/checkout]]
     :sensitive             {:app-db [[:auth :token]]}})
  (is (= [:pass [true]]
         (let [r (run-v! :story.classified/dispatched)]
           [(:status r) (mapv :passed? (:assertions r))])))
  (is (= [:pass true]
         (let [r (run-v! :story.classified/loaders)]
           [(:status r) (get-in r [:app-db :checked-out?])]))
      "the vector :loaders-complete-when completes, so setup runs")
  (rf.story/destroy-variant! :story.classified/dispatched)
  (rf.story/destroy-variant! :story.classified/loaders))

;; The needle is compared raw, so the author's own vector carries the secret:
;; the whole record — `:payload` as well as `:expected` / `:actual` — carries
;; the projected event, and the check the atom came from still groups it.
(deftest dispatched?-record-never-carries-a-sensitive-event-raw
  (rf/reg-event :auth/login
    (fn [{:keys [db]} [_ input]] {:db (assoc-in db [:auth :token] (:password input))}))
  (rf.story/reg-check :check.privacy/logged-in
    {:assertions [[:rf.assert/dispatched? [:auth/login {:password "RAW-SECRET-PW"}]]]})
  (let [login   [[:auth/login {:password "RAW-SECRET-PW"}]]
        run     (fn [vid body]
                  (rf.story/reg-variant vid (merge {:setup login :checks [:check.privacy/logged-in]} body))
                  (let [r (run-v! vid)]
                    (rf.story/destroy-variant! vid)
                    r))
        record  (fn [r] (first (filter #(= :rf.assert/dispatched? (:assertion %)) (:assertions r))))
        grouped (fn [r] (mapv :passed? (:assertions (first (:checks r)))))]
    (let [r (run :story.privacy/dispatched-classified {:sensitive {:app-db [[:auth :token]]}})]
      (is (= [:pass true [true]] [(:status r) (:passed? (record r)) (grouped r)])
          "the raw comparison still matches, and the check groups its record")
      (is (= [[:auth/login :rf/redacted]] (:payload (record r))))
      (is (not (str/includes? (pr-str (select-keys r [:assertions :checks])) "RAW-SECRET-PW"))
          "no slot of the record, nor of its check group, carries the secret"))
    (let [r (run :story.privacy/dispatched-public {})]
      (is (= [:pass [true] login] [(:status r) (grouped r) (:payload (record r))])
          "control: an unclassified variant's record keeps the event as dispatched"))
    (rf.story.config/set-frame-egress-profile! :story.privacy/dispatched-revealed :rf.egress/local-raw)
    (try
      (let [r (run :story.privacy/dispatched-revealed {:sensitive {:app-db [[:auth :token]]}})]
        (is (= [:pass [true] login] [(:status r) (grouped r) (:payload (record r))])
            "control: a frame revealed to :rf.egress/local-raw records the raw event"))
      (finally
        (rf.story.config/set-frame-egress-profile! :story.privacy/dispatched-revealed nil)))))

(deftest effect-emitted-projects-from-tape-effects
  (testing ":rf.assert/effect-emitted reads the same tape :effects projection as
            the run result's :effects slot"
    (rf/reg-fx :ssot.fx/real {:platforms #{:client :server}} (fn [_ _] nil))
    (rf/reg-event :ssot/emit-real (fn [_ _] {:fx [[:ssot.fx/real {:url "x"}]]}))
    (rf.story/reg-variant :story.ssot/effect
      {:setup []
       :script [[:dispatch-sync [:ssot/emit-real]]
                [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/real]]
                [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/never]]]})
    (let [r (run-v! :story.ssot/effect)]
      (is (some #(= :ssot.fx/real (:fx-id %)) (:effects r)))
      (is (= {:ssot.fx/real true :ssot.fx/never false}
             (->> (:assertions r)
                  (filter #(= :rf.assert/effect-emitted (:assertion %)))
                  (into {} (map (juxt :expected :passed?)))))))
    (rf.story/destroy-variant! :story.ssot/effect)))

;; A stubbed fx lands on the tape under its rewritten stub id, so
;; `emitted-fx` unions the tape effects with the stub-call log to answer for
;; the original fx id.
(deftest effect-emitted-projects-stubbed-fx-from-stub-log
  (rf/reg-fx :ssot.fx/http {:platforms #{:client :server}} (fn [_ _] nil))
  (rf/reg-event :ssot/login (fn [_ _] {:fx [[:ssot.fx/http {:url "/login"}]]}))
  (rf.story/reg-variant :story.ssot/stubbed
    {:decorators [[:rf.story/force-fx-stub :ssot.fx/http {:status :ok}]]
     :setup      []
     :script     [[:dispatch-sync [:ssot/login]]
                  [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/http]]
                  [:dispatch-sync [:rf.assert/effect-emitted :ssot.fx/never]]]})
  (let [r (run-v! :story.ssot/stubbed)]
    (is (contains? (rf.story.assertions/emitted-fx :story.ssot/stubbed) :ssot.fx/http))
    (is (= {:ssot.fx/http true :ssot.fx/never false}
           (->> (:assertions r)
                (filter #(= :rf.assert/effect-emitted (:assertion %)))
                (into {} (map (juxt :expected :passed?)))))))
  (rf.story/destroy-variant! :story.ssot/stubbed))

;; A same-id re-run resets the frame in place, so the frame's epoch ring still
;; carries the previous run's epochs; the tape-projected assertions must read
;; only the current run.

(defn- okc-run-verdict
  "Run `variant-id`; return `[status passed?]` for its last `assertion-id` record."
  [variant-id assertion-id]
  (let [r (run-v! variant-id)]
    [(:status r)
     (:passed? (last (filter #(= assertion-id (:assertion %)) (:assertions r))))]))

(deftest dispatched-assertion-reads-only-the-current-run
  (testing "C1/C2/C3 — a same-id re-run does not inherit the previous run's dispatch"
    (rf/reg-event :okc/ok (fn [{:keys [db]} _] {:db (assoc db :ok true)}))
    (rf.story/reg-variant :story.okc/dispatched
      {:script [[:dispatch [:okc/ok]] [:assert [:rf.assert/dispatched? [:okc/ok]]]]})
    (is (= [:pass true] (okc-run-verdict :story.okc/dispatched :rf.assert/dispatched?))
        "C1 — the first run dispatches, and dispatched? passes on real evidence")
    (rf.story/reg-variant :story.okc/dispatched
      {:script [[:assert [:rf.assert/dispatched? [:okc/ok]]]]})
    (is (= [:fail false] (okc-run-verdict :story.okc/dispatched :rf.assert/dispatched?))
        "C2 — the SAME id re-run WITHOUT the dispatch fails: the previous run's
         epoch is not this run's evidence")
    (rf.story/reg-variant :story.okc/dispatched-fresh
      {:script [[:assert [:rf.assert/dispatched? [:okc/ok]]]]})
    (is (= [:fail false] (okc-run-verdict :story.okc/dispatched-fresh :rf.assert/dispatched?))
        "C3 control — a fresh id with the same assert-only script fails, and C2 agrees with it")
    (rf.story/destroy-variant! :story.okc/dispatched)
    (rf.story/destroy-variant! :story.okc/dispatched-fresh)))

(deftest effect-emitted-and-no-warnings-read-only-the-current-run
  (testing "effect-emitted and no-warnings are run-scoped too, in both directions"
    (rf/reg-fx :okc/fx {:platforms #{:client :server}} (fn [_ _] nil))
    (rf/reg-event :okc/emit (fn [_ _] {:fx [[:okc/fx 1]]}))
    (rf/reg-event :okc/ok (fn [{:keys [db]} _] {:db (assoc db :ok true)}))
    (rf/reg-event :okc/warn (fn [{:keys [db]} _]
                              (rf.trace/emit! :warning :okc/warned {})
                              {:db db}))
    (rf.story/reg-variant :story.okc/fx
      {:script [[:dispatch-sync [:okc/emit]] [:assert [:rf.assert/effect-emitted :okc/fx]]]})
    (is (= [:pass true] (okc-run-verdict :story.okc/fx :rf.assert/effect-emitted))
        "the first run emits, and effect-emitted passes")
    (rf.story/reg-variant :story.okc/fx
      {:script [[:assert [:rf.assert/effect-emitted :okc/fx]]]})
    (let [[status passed?] (okc-run-verdict :story.okc/fx :rf.assert/effect-emitted)]
      (is (false? passed?)
          "a same-id re-run that emits nothing records effect-emitted FALSE — the
           record does not inherit the previous run's fx")
      (is (not= :pass status)))
    (rf.story/reg-variant :story.okc/warn
      {:script [[:dispatch-sync [:okc/warn]] [:assert [:rf.assert/no-warnings]]]})
    (is (= [:fail false] (okc-run-verdict :story.okc/warn :rf.assert/no-warnings))
        "control — a run that warns fails no-warnings, so the warning reaches the tape")
    (rf.story/reg-variant :story.okc/warn
      {:script [[:dispatch-sync [:okc/ok]] [:assert [:rf.assert/no-warnings]]]})
    (is (= [:pass true] (okc-run-verdict :story.okc/warn :rf.assert/no-warnings))
        "a clean same-id re-run PASSES no-warnings — the previous run's warning
         does not fail it")
    (rf.story/destroy-variant! :story.okc/fx)
    (rf.story/destroy-variant! :story.okc/warn)))

;; The Test pane resolves a failed row to its Evidence beat through the
;; record's `:dispatch-id`. These records come from a real run, and the
;; expected beat is found by its trigger event, so the expectation does not
;; lean on the coordinate under test.

(defn- beat-for-trigger
  "The `:beat-idx` of the retained beat whose trigger event is `event`, or nil."
  [narrative event]
  (some #(when (= event (:trigger-event %)) (:beat-idx %))
        (rf.story.play.evidence/narrative-beats narrative)))

(deftest real-failed-assertion-resolves-to-its-own-non-first-beat-rf2-v5p6l
  (testing "a failed :assert-db after a dispatch step resolves to the
            assertion's own retained beat, which is not the first beat"
    (rf/reg-event :evidence-link/set
      (fn [{:keys [db]} _] {:db (assoc db :count 2)}))
    (rf.story/reg-variant :story.evidence-link/retained
      {:script {:script [[:dispatch-sync [:evidence-link/set]]
                         [:assert-db [:count] 99]]}})
    (let [result    (rf.story.async/deref-blocking
                      (rf.story/run :story.evidence-link/retained) 5000)
          narrative (:narrative result)
          record    (first (:assertions result))
          row       (rf.story.ui.test-mode.pure/assertion-row record)
          own-beat  (beat-for-trigger narrative [:rf.assert/path-equals [:count] 99])]
      (is (= :fail (:status result)))
      (is (= :fail (:status row)))
      (is (pos-int? own-beat)
          "the assertion's own epoch is retained, and it is not the first beat")
      (is (some? (:dispatch-id record))
          "the canonical record carries the dispatch coordinate the router bound")
      (is (= (:dispatch-id record) (:dispatch-id row))
          "the row keeps the record's coordinate")
      (is (= own-beat (rf.story.ui.evidence-spine/row->beat-index narrative row))
          "the row resolves to the assertion's own beat"))
    (rf.story/destroy-variant! :story.evidence-link/retained)))

(deftest real-unretained-assertion-resolves-to-no-beat-rf2-v5p6l
  (testing "a failed assertion whose epoch the ring evicted resolves to no
            beat, so no link can land on a neighbouring beat"
    (try
      (rf/configure! {:epoch-history {:depth 1}})
      (rf/reg-event :evidence-link/set
        (fn [{:keys [db]} _] {:db (assoc db :count 2)}))
      (rf.story/reg-variant :story.evidence-link/unretained
        {:script {:script [[:assert-db [:count] 99]
                           [:dispatch-sync [:evidence-link/set]]]}})
      (let [result    (rf.story.async/deref-blocking
                        (rf.story/run :story.evidence-link/unretained) 5000)
            narrative (:narrative result)
            row       (rf.story.ui.test-mode.pure/assertion-row (first (:assertions result)))]
        (is (= :fail (:status row)))
        (is (nil? (beat-for-trigger narrative [:rf.assert/path-equals [:count] 99]))
            "control: the depth-1 ring evicted the assertion's own epoch")
        (is (some? (beat-for-trigger narrative [:evidence-link/set]))
            "control: the later dispatch step's epoch is the one retained")
        (is (nil? (rf.story.ui.evidence-spine/row->beat-index narrative row))
            "the row resolves to no beat"))
      (finally
        ;; `:depth` is process-global — restore the framework default.
        (rf/configure! {:epoch-history {:depth 50}})
        (rf.story/destroy-variant! :story.evidence-link/unretained)))))
