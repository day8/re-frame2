(ns ^:requires-debug re-frame.machine-minted-cofx-replay-token-test
  "Coeffects minted inside a machine run reach the replay token: epoch
  assembly folds `:rf.cofx/generated` traces into it, so facts a guard or
  action mints mid-run sit beside the run-start ones. Capture and strict
  re-presentation are pinned end to end by `re-frame.epoch-replay-cljs-test`'s
  `restore-then-replay-by-id-reproduces-a-mid-run-machine-mint`; the first
  deftest here is the control that makes the captured fact load-bearing.

  A sub-valued source (`{:rf/sub query-v :as fact-id}`) is evaluated inside the
  machine run too, so its value reaches the token the same way; the second
  deftest replays it from the real epoch record (Spec 005 §Consumer attachment).
  The third pins that the value leaving the token takes the sub's own
  classification (Spec 015 §Registration-owned transient classification), and
  that a record whose sub fact was redacted is refused as incomplete input
  rather than replayed with the substitute (Tool-Pair §Replay).

  ## Production gate

  Dev-only, so the `(ns ...)` form carries `^:requires-debug`: the
  `:prod-gate` lane loads this namespace and runs none of it. Class: SUBJECT
  ELIDED. What it asserts about — a recorded epoch, the ring, the
  capture buffer, or a record read back out of them — is assembled only
  while `interop/debug-enabled?` is on, so under `-Dre-frame.debug=false`
  there is no subject. Epoch's production posture is that the
  artefact is off, so that is the design, not a defect."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Publishes the machine hooks; the fixture keeps those ns-load
            ;; registrations across tests.
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.epoch/clear-history!)
                (rf.epoch/clear-epoch-listeners!))}))

(defn- machine-state [machine-id]
  (-> (:rf.db/runtime (rf/frame-state-value :test/main))
      (get-in [:rf.runtime/machines :snapshots machine-id])
      :state))

;; `:inner`'s guard requires `:replay/gen`, which is not in `:go`'s ensure-set,
;; so under `:live` it is minted mid-drain.
(def ^:private mint-machine
  {:initial :a
   :guards  {:check {:rf.cofx/requires [:replay/gen]
                     :fn (fn [{cofx :rf.cofx}] (some? (:replay/gen cofx)))}}
   :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
   :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
             :b    {:on {:inner {:target :done :guard :check}}}
             :done {}}})

(deftest strict-without-the-minted-fact-diverges-control
  (rf/make-frame {:id :test/main})
  (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
  (rf/reg-machine :replay/mint mint-machine)
  (rf/dispatch-sync [:replay/mint [:go]]
                    {:frame :test/main
                     :rf.cofx {:rf/time-ms 111}
                     :rf.cofx/mint-policy :strict})
  (is (not= :done (machine-state :replay/mint))
      "strict refuses to mint the absent fact, so the machine does not reach :done"))

(defn- epoch-record-for
  "The newest record in `:test/main`'s ring whose `:trigger-event` is `trigger`."
  [trigger]
  (some #(when (= trigger (:trigger-event %)) %)
        (reverse (rf.epoch/epoch-history :test/main))))

(deftest sub-fact-reaches-the-epoch-record-and-strict-replay-reproduces-the-run
  (let [evals (atom 0)]
    (rf/make-frame {:id :test/main})
    (rf/reg-sub :subfact/cfg (fn [db _] (swap! evals inc) (get db :cfg 250)))
    (rf/reg-machine :subfact/m
      {:initial :idle
       :data    {}
       :actions {:sample {:rf.cofx/requires [{:rf/sub [:subfact/cfg] :as :subfact/cfg-v}]
                          :fn (fn [{data :data cofx :rf.cofx}]
                                {:data (assoc data :c (:subfact/cfg-v cofx))})}}
       :states  {:idle {:on {:go {:target :done :action :sample}}}
                 :done {}}})
    (rf/dispatch-sync [:subfact/m [:rf.machine/start]] {:frame :test/main})
    (let [pre-epoch-id (:epoch-id (last (rf.epoch/epoch-history :test/main)))]
      ;; An external :rf.cofx makes run-start pin a token the sub fact folds into.
      (rf/dispatch-sync [:subfact/m [:go]] {:frame :test/main :rf.cofx {:rf/time-ms 1}})
      (let [rec       (epoch-record-for [:subfact/m [:go]])
            [ev cofx] (edn/read-string (pr-str [(:trigger-event rec) (:rf.cofx rec)]))]
        (is (= [1 :done] [@evals (machine-state :subfact/m)])
            "live, the query is evaluated once and the run reaches :done")
        (is (= {:rf/time-ms 1 :subfact/cfg-v 250} (:rf.cofx rec))
            "the record's token carries the sub fact beside the external fact")
        (is (:ok? (rf.epoch/restore-epoch! :test/main pre-epoch-id)))
        (is (= :idle (machine-state :subfact/m)) "restore rewound the machine")
        (reset! evals 0)
        (rf/dispatch-sync ev {:frame :test/main :rf.cofx cofx :rf.cofx/mint-policy :strict})
        (is (= [0 :done 250]
               [@evals (machine-state :subfact/m)
                (-> (:rf.db/runtime (rf/frame-state-value :test/main))
                    (get-in [:rf.runtime/machines :snapshots :subfact/m :data :c]))])
            "the strict replay re-presents the recorded value without re-evaluating the query")))))

(deftest sub-fact-leaves-the-token-projected-by-its-subs-classification
  (let [seen   (atom nil)
        traces (atom [])]
    (rf/make-frame {:id :test/main})
    (rf/reg-sub :subfact/session {:sensitive [[:token]]}
      (fn [db _] (get db :session {:user "ada" :token "s3cret"})))
    (rf/reg-sub :subfact/cfg (fn [db _] (get db :cfg 250)))
    (rf/reg-machine :subfact/auth
      {:initial :idle
       :data    {}
       :actions {:sample {:rf.cofx/requires [{:rf/sub [:subfact/session] :as :subfact/session-v}
                                             {:rf/sub [:subfact/cfg] :as :subfact/cfg-v}]
                          :fn (fn [{data :data cofx :rf.cofx}]
                                (reset! seen (select-keys cofx [:subfact/session-v :subfact/cfg-v]))
                                {:data (assoc data :user (:user (:subfact/session-v cofx)))})}}
       :states  {:idle {:on {:go {:target :done :action :sample}}}
                 :done {}}})
    (rf/dispatch-sync [:subfact/auth [:rf.machine/start]] {:frame :test/main})
    (rf/register-listener! :trace ::traces (fn [ev] (swap! traces conj ev)))
    (try
      (rf/dispatch-sync [:subfact/auth [:go]] {:frame :test/main :rf.cofx {:rf/time-ms 1}})
      (finally (rf/unregister-listener! :trace ::traces)))
    (let [projected {:user "ada" :token :rf/redacted}
          generated (into {}
                          (comp (filter #(= :rf.cofx/generated (:operation %)))
                                (map (comp (juxt :rf.cofx/id :rf.cofx/value) :tags)))
                          @traces)
          rec       (epoch-record-for [:subfact/auth [:go]])]
      (is (= {:subfact/session-v {:user "ada" :token "s3cret"} :subfact/cfg-v 250} @seen)
          "the handler reads the real values off the in-flight token")
      (is (= :done (machine-state :subfact/auth)))
      (is (= {:subfact/session-v projected :subfact/cfg-v 250} generated)
          ":rf.cofx/generated carries each fact projected by its own sub's classification")
      (is (= {:rf/time-ms 1 :subfact/session-v projected :subfact/cfg-v 250} (:rf.cofx rec))
          "the record's token carries the projected sensitive fact beside the unchanged one")
      (is (= {:ok?    false
              :reason :rf.epoch/replay-non-replayable-record
              :cause  :incomplete-inputs
              :lost   [{:slot :rf.cofx :path [:subfact/session-v :token] :loss :redacted}]}
             (select-keys (rf.epoch/replay-epoch! :test/main (:epoch-id rec))
                          [:ok? :reason :cause :lost]))
          "strict replay refuses the redacted fact rather than re-presenting the substitute"))))
