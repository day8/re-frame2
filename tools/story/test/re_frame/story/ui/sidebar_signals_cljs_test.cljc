(ns re-frame.story.ui.sidebar-signals-cljs-test
  "JVM-portable regression net for the sidebar's five-axis signal chips
  (rf2-ba86n.4, spec/018 §7.1 + §12.6). Every fn under test is `.cljc`-pure
  so this corpus runs on both JVM (`clojure -M:test`) and CLJS
  (`npm run test:cljs`) — see the sibling `sidebar-search-cljs-test`.

  ## The contract under test

  The five axes — status / fidelity / world-inputs / runner-requirement /
  frame-binding — MUST stay DISTINCT (spec/018 §7.1): args / network /
  fx-overrides are world inputs, NOT fidelity; browser is a runner
  requirement, NOT fidelity; attached / MCP-bound are frame bindings, NOT
  runner tiers. This corpus asserts the derivation keeps them in separate
  buckets and reads only the variant body's real metadata.

  Named `-cljs-test` so the `:node-test` build's `cljs-test$` ns-regexp
  selects it; under its old `-test` name it ran on the JVM only (rf2-1ep8)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.sidebar-signals :as rf.story.ui.sidebar-signals]))

;; ---- status axis ---------------------------------------------------------

(deftest status-signal-shape
  (testing "each known status maps to its own labelled chip"
    (doseq [s [:pass :fail :cannot-run :error :running :pending
               :blocked :dirty :redacted]]
      (let [chip (rf.story.ui.sidebar-signals/status-signal s)]
        (is (= :status (:axis chip)))
        (is (= s (:value chip)))
        (is (string? (:label chip))))))
  (testing "an unknown / nil status defaults to :pending (never throws)"
    (is (= :pending (:value (rf.story.ui.sidebar-signals/status-signal nil))))
    (is (= :pending (:value (rf.story.ui.sidebar-signals/status-signal :bogus))))))

;; ---- fidelity axis (reuses plan/compute-fidelity) ------------------------

(deftest fidelity-signals-from-world-inputs
  (testing "a bare render-as-mounted variant has NO fidelity chips"
    (is (= [] (rf.story.ui.sidebar-signals/fidelity-signals {}))))
  (testing ":real-setup from setup/script/events"
    (is (= [:real-setup]
           (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals {:setup [[:dispatch [:e]]]}))))
    (is (= [:real-setup]
           (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals {:setup [[:e]]}))))
    (is (= [:real-setup]
           (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals {:script [[:dispatch [:e]]]})))))
  (testing ":sub-overrides is a fidelity rung"
    (is (= [:sub-overrides]
           (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals {:sub-overrides {[:q] 1}})))))
  (testing ":db-seed is a fidelity rung"
    (is (= [:db-seed]
           (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals {:db-seed {:a 1}})))))
  (testing "ladder order is real-setup > db-seed > sub-overrides"
    (is (= [:real-setup :db-seed :sub-overrides]
           (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals
                          {:setup [[:dispatch [:e]]]
                           :db-seed {:a 1}
                           :sub-overrides {[:q] 1}})))))
  (testing "args / network / fx-overrides are NOT fidelity rungs"
    (is (= [] (rf.story.ui.sidebar-signals/fidelity-signals {:args {:n 1}
                                         :network {[:get "/x"] {:reply {:ok 1}}}
                                         :fx-overrides {:fx :stub}})))))

;; ---- world-inputs axis (distinct from fidelity) --------------------------

(deftest world-input-signals-presence
  (testing "no world inputs → no chips"
    (is (= [] (rf.story.ui.sidebar-signals/world-input-signals {})))
    (is (= [] (rf.story.ui.sidebar-signals/world-input-signals {:args {} :network {} :fx-overrides {}}))))
  (testing "args / route / network / fx-overrides each surface a chip"
    (is (= [:args]         (mapv :value (rf.story.ui.sidebar-signals/world-input-signals {:args {:n 1}}))))
    (is (= [:route]        (mapv :value (rf.story.ui.sidebar-signals/world-input-signals {:route [:home]}))))
    (is (= [:network]      (mapv :value (rf.story.ui.sidebar-signals/world-input-signals
                                          {:network {[:get "/x"] {:reply {:ok 1}}}}))))
    (is (= [:fx-overrides] (mapv :value (rf.story.ui.sidebar-signals/world-input-signals
                                          {:fx-overrides {:fx :stub}})))))
  (testing "render order is args, route, network, fx-overrides"
    (is (= [:args :route :network :fx-overrides]
           (mapv :value (rf.story.ui.sidebar-signals/world-input-signals
                          {:args {:n 1} :route [:home]
                           :network {[:get "/x"] {:reply {:ok 1}}}
                           :fx-overrides {:fx :stub}})))))
  (testing "every world-input chip is on the :world-inputs axis, never :fidelity"
    (is (every? #(= :world-inputs (:axis %))
                (rf.story.ui.sidebar-signals/world-input-signals {:args {:n 1} :network {[:get "/x"] {}}})))))

;; ---- runner-requirement axis (capability tokens) -------------------------

(deftest runner-requirement-from-capabilities
  (testing "a pure app-db variant needs only :headless"
    (is (= :headless (rf.story.ui.sidebar-signals/required-runner-kind {})))
    (is (= :headless (rf.story.ui.sidebar-signals/required-runner-kind
                       {:script [[:dispatch [:inc]]]
                        :assertions [[:rf.assert/path-equals [:n] 1]]}))))
  (testing "a DOM step escalates the requirement to :dom"
    (is (= :dom (rf.story.ui.sidebar-signals/required-runner-kind
                  {:script [[:click "#go"]]}))))
  (testing "a visual-snapshot assertion escalates to :browser"
    (is (= :browser (rf.story.ui.sidebar-signals/required-runner-kind
                      {:assertions [[:rf.assert/visual-snapshot]]}))))
  (testing "the chip rides the :runner-requirement axis, not fidelity"
    (let [chip (rf.story.ui.sidebar-signals/runner-requirement-signal {:script [[:click "#x"]]})]
      (is (= :runner-requirement (:axis chip)))
      (is (= :dom (:value chip)))
      (is (string? (:label chip))))))

;; ---- frame-binding axis (a binding, not a tier) --------------------------

(deftest frame-binding-signal-shape
  (testing "default is :fresh"
    (is (= :fresh (:value (rf.story.ui.sidebar-signals/frame-binding-signal {})))))
  (testing ":attached is honoured"
    (is (= :attached (:value (rf.story.ui.sidebar-signals/frame-binding-signal {:frame-binding :attached})))))
  (testing "an invalid frame-binding falls back to :fresh (fail-safe)"
    (is (= :fresh (:value (rf.story.ui.sidebar-signals/frame-binding-signal {:frame-binding :bogus})))))
  (testing ":mcp-bound is a UI affordence for an attached binding, not a tier"
    (let [chip (rf.story.ui.sidebar-signals/frame-binding-signal {:frame-binding :attached :mcp-bound true})]
      (is (= :frame-binding (:axis chip)))
      (is (= :mcp-bound (:value chip))))))

;; ---- composite: all five axes kept distinct ------------------------------

(deftest variant-signals-keeps-axes-distinct
  (testing "a rich variant surfaces all five axes in separate buckets"
    (let [sig (rf.story.ui.sidebar-signals/variant-signals
                {:setup        [[:dispatch [:seed]]]
                 :sub-overrides {[:q] 1}
                 :args          {:label "x"}
                 :network       {[:get "/x"] {:reply {:ok 1}}}
                 :fx-overrides  {:fx :stub}
                 :script        [[:click "#go"]]
                 :frame-binding :attached}
                :pass)]
      ;; status — a single chip
      (is (= :pass (get-in sig [:status :value])))
      ;; fidelity — real-setup (from setup/script) + sub-overrides; NOT
      ;; contaminated by the world inputs.
      (is (= #{:real-setup :sub-overrides}
             (set (mapv :value (:fidelity sig)))))
      ;; world inputs — args + network + fx-overrides, kept OUT of fidelity.
      (is (= #{:args :network :fx-overrides}
             (set (mapv :value (:world-inputs sig)))))
      ;; runner requirement — the :click step needs :dom.
      (is (= :dom (get-in sig [:runner-requirement :value])))
      ;; frame binding — attached.
      (is (= :attached (get-in sig [:frame-binding :value])))
      ;; the five axes are five distinct keys.
      (is (= rf.story.ui.sidebar-signals/chip-axes
             [:status :fidelity :world-inputs :runner-requirement :frame-binding]))))
  (testing "a calm default variant still carries the always-present axes"
    (let [sig (rf.story.ui.sidebar-signals/variant-signals {} :pending)]
      (is (= :pending (get-in sig [:status :value])))
      (is (= [] (:fidelity sig)))
      (is (= [] (:world-inputs sig)))
      (is (= :headless (get-in sig [:runner-requirement :value])))
      (is (= :fresh (get-in sig [:frame-binding :value]))))))

;; ---- inherited + composed world: the chips agree with the plan -----------

(deftest chips-read-the-world-extends-and-compose-pass-down
  (testing "rf2-3x7nj.28.5: the sidebar hands `variant-signals` the RAW
            registered body, as `variant-row` does. An `:extends` child of a
            pinned variant, or a variant composing a seeding fragment,
            renders on inherited / composed world, and its chips must say
            so. The fidelity chips are compared against the compiled plan's
            `[:world :fidelity]`, the producer of the rung."
    (rf.story.registrar/clear-all!)
    (try
      (rf.story.registrar/reg-fragment* :fragment.fid/seeded {:db-seed {:n 1}})
      (rf.story.registrar/reg-variant* :story.fid/pinned
        {:sub-overrides {[:probe/n] 5}
         :args          {:label "x"}
         :network       {[:get "/api/cart"] {:reply {:ok {:items []}}}}
         :fx-overrides  {:rf.http/fetch :stub}})
      (rf.story.registrar/reg-variant* :story.fid/child
        {:extends :story.fid/pinned
         :setup   [[:probe/noop]]})
      (rf.story.registrar/reg-variant* :story.fid/grandchild
        {:extends :story.fid/child})
      (rf.story.registrar/reg-variant* :story.fid/composed
        {:compose [:fragment.fid/seeded]})
      (let [signals (fn [vid]
                      (rf.story.ui.sidebar-signals/variant-signals
                        (rf.story.registrar/handler-meta :variant vid) :pending))
            values  (fn [vid axis] (set (map :value (get (signals vid) axis))))]
        (doseq [vid [:story.fid/pinned :story.fid/child
                     :story.fid/grandchild :story.fid/composed]]
          (is (= (get-in (rf.story.plan/variant-plan vid) [:world :fidelity] #{})
                 (values vid :fidelity))
              (str vid " — the fidelity chips are the plan's rungs")))
        (is (= #{:sub-overrides :real-setup} (values :story.fid/child :fidelity))
            "the child's picture still rests on the inherited pin")
        (is (= #{:sub-overrides :real-setup} (values :story.fid/grandchild :fidelity))
            "setup and pin both flow down two levels")
        (is (= #{:db-seed} (values :story.fid/composed :fidelity))
            "the composed fragment's seed is the variant's seed")
        (is (= #{:args :network :fx-overrides} (values :story.fid/child :world-inputs))
            "the inherited world inputs are the child's world inputs"))
      (finally (rf.story.registrar/clear-all!)))))
