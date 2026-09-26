(ns re-frame.machine-sub-classification-test
  "The framework `[:rf/machine <id>]` subscription carries its machine's
  declared `:data` classification to every egress of its value.

  A subscription does not inherit its inputs' classification, so an app
  classifies its own subs. It cannot classify this one: the framework
  registers it. Its value IS the actor's snapshot, so it is redacted by the
  same declaration the machine's own traces use — the `:sensitive` /
  `:large` paths `reg-machine` declares, lowered per actor into the frame's
  elision registry — at the two surfaces that carry it:

   1. the `:rf.sub/run` trace's `:rf.sub/value` / `:rf.sub/prev-value`
      (`re-frame.classification/project-sub-tags`);
   2. an off-box direct read that names the sub through `:query-v`
      (`re-frame.elision/elide-wire-value`, the door Pair MCP `read-sub` /
      `list-subscriptions :include-values` / `snapshot :sub-cache` use).

  The in-process read stays raw: classification applies at egress only."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact registers `:rf/machine` and the
            ;; `:machines/reg-machine` hook `rf/reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.elision :as rf.elision]
            [re-frame.interop :as rf.interop]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private mid :rf.machine-sub-classification/auth)
(def ^:private token-1 "secret-sub-token-one")
(def ^:private token-2 "secret-sub-token-two")

(defn- reg-auth-machine!
  "An auth machine declaring `:data :token` sensitive and `:data :blob` large.
  `:login` and `:refresh` both store the event's token, so a second write
  gives the held subscription a recompute whose prior value carries a secret."
  []
  (rf/reg-machine mid
    {:sensitive [[:data :token]]
     :large     [[:data :blob]]
     :initial   :anon
     :data      {:token nil :blob nil :retries 0}
     :actions   {:store (fn [{:keys [data event]}]
                          {:data (assoc data
                                        :token   (second event)
                                        :blob    (apply str (repeat 64 "b"))
                                        :retries (inc (:retries data)))})}
     :states    {:anon   {:on {:login {:target :authed :action :store}}}
                 :authed {:on {:refresh {:action :store}}}}}))

(defn- capture-traces
  "Run `f` and return every trace event emitted meanwhile, always unregistering
  the listener."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try (f)
         @captured
         (finally (rf/unregister-listener! :trace ::capture)))))

(defn- machine-sub-runs
  "The captured `:rf.sub/run` events for `[:rf/machine mid]`."
  [events]
  (filterv #(and (= :rf.sub/run (:operation %))
                 (= [:rf/machine mid] (get-in % [:tags :rf.sub/query-v])))
           events))

(deftest machine-declaration-is-lowered
  (testing "every redaction below reads the lowered declaration, so pin that it
            is there: a missing claim would make them read raw for a reason that
            has nothing to do with the sub"
    (reg-auth-machine!)
    (rf/dispatch-sync [mid [:login token-1]])
    (let [reg (:rf.runtime/elision (rf.machines.test-support/runtime-db))]
      (is (contains? (:sensitive-declarations reg)
                     [:rf.runtime/machines :snapshots mid :data :token]))
      (is (contains? (:declarations reg)
                     [:rf.runtime/machines :snapshots mid :data :blob])))))

(deftest sub-run-trace-redacts-the-machine-declaration
  (reg-auth-machine!)
  (rf/dispatch-sync [mid [:login token-1]])
  (let [r      (rf/subscribe [:rf/machine mid])
        before (atom nil)
        after  (atom nil)
        events (capture-traces
                 (fn []
                   (reset! before @r)
                   (rf/dispatch-sync [mid [:refresh token-2]])
                   (reset! after @r)))
        runs   (machine-sub-runs events)]
    (testing "the in-process read stays raw"
      (is (= token-1 (get-in @before [:data :token])))
      (is (= token-2 (get-in @after [:data :token]))))
    (when rf.interop/debug-enabled?
      (is (= 2 (count runs)) "a first run and a recompute, both traced")
      (testing "the first run: :rf.sub/value is redacted by the machine's paths"
        (let [v (get-in (first runs) [:tags :rf.sub/value])]
          (is (= rf.privacy/redacted-sentinel (get-in v [:data :token]))
              "the :sensitive [:data :token] slot is redacted")
          (is (rf.elision/marker? (get-in v [:data :blob]))
              "the :large [:data :blob] slot is the size marker")
          (is (= 1 (get-in v [:data :retries]))
              "an undeclared slot rides verbatim — the redaction is path-precise")
          (is (= :authed (:state v)) "the snapshot's structure rides verbatim")))
      (testing "the recompute: :rf.sub/prev-value and :rf.sub/value both redact"
        (let [t (:tags (second runs))]
          (is (= rf.privacy/redacted-sentinel (get-in t [:rf.sub/prev-value :data :token])))
          (is (= rf.privacy/redacted-sentinel (get-in t [:rf.sub/value :data :token])))
          (is (rf.elision/marker? (get-in t [:rf.sub/prev-value :data :blob])))
          (is (= 2 (get-in t [:rf.sub/value :data :retries])))))
      (testing "no secret appears anywhere on the machine sub's trace"
        (is (not (.contains (pr-str runs) token-1)))
        (is (not (.contains (pr-str runs) token-2)))))))

(deftest off-box-direct-read-redacts-the-machine-declaration
  (testing "a direct read naming the sub through `:query-v` walks the snapshot at
            its runtime-db position, so the lowered declaration matches"
    (reg-auth-machine!)
    (rf/dispatch-sync [mid [:login token-1]])
    (let [snap (rf.machines.test-support/snapshot mid)
          wire (rf.elision/elide-wire-value snap {:query-v [:rf/machine mid]
                                                  :frame   :rf/default})]
      (is (= token-1 (get-in snap [:data :token])) "precondition: the value is raw")
      (is (= rf.privacy/redacted-sentinel (get-in wire [:data :token])))
      (is (rf.elision/marker? (get-in wire [:data :blob])))
      (is (= 1 (get-in wire [:data :retries])))
      (is (not (.contains (pr-str wire) token-1))))))

(deftest another-machines-sub-is-not-redacted-by-this-declaration
  (testing "the declaration is keyed by machine id: a sibling machine that
            declares nothing rides raw through the same sub"
    (reg-auth-machine!)
    (rf/reg-machine :rf.machine-sub-classification/plain
      {:initial :idle
       :data    {:token "not-a-secret"}
       :states  {:idle {}}})
    (rf/dispatch-sync [mid [:login token-1]])
    (rf/dispatch-sync [:rf.machine-sub-classification/plain [:rf.machine/noop]])
    (let [snap (rf.machines.test-support/snapshot :rf.machine-sub-classification/plain)
          wire (rf.elision/elide-wire-value
                 snap {:query-v [:rf/machine :rf.machine-sub-classification/plain]
                       :frame   :rf/default})]
      (is (= "not-a-secret" (get-in wire [:data :token]))))))
