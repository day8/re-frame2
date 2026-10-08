(ns re-frame.story.ui.sidebar-signals-cljs-test
  "The sidebar's five-axis signal-chip derivation (spec/018 §7.1 + §12.6).
  The axes — status / fidelity / world-inputs / runner-requirement /
  frame-binding — MUST stay DISTINCT: args / network / fx-overrides are world
  inputs, not fidelity; browser is a runner requirement; attached / MCP-bound
  are frame bindings. Every fn under test is `.cljc`-pure, so this runs on
  the JVM and on the CLJS node-test build; the rendered strip is
  `re-frame.story.ui.sidebar-chips-cljs-test`."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.sidebar-signals :as rf.story.ui.sidebar-signals]))

;; ---- status axis ---------------------------------------------------------

(deftest status-signal-shape
  ;; an unknown / nil status defaults to :pending rather than throwing
  (are [status value] (= value (:value (rf.story.ui.sidebar-signals/status-signal status)))
    :fail  :fail
    nil    :pending
    :bogus :pending))

;; ---- fidelity axis (reuses plan/compute-fidelity) ------------------------

(deftest fidelity-signals-from-world-inputs
  (are [body rungs] (= rungs (mapv :value (rf.story.ui.sidebar-signals/fidelity-signals body)))
    ;; a bare render-as-mounted variant has no fidelity chips
    {}                           []
    {:script [[:dispatch [:e]]]} [:real-setup]
    ;; ladder order: real-setup > db-seed > sub-overrides
    {:setup         [[:dispatch [:e]]]
     :db-seed       {:a 1}
     :sub-overrides {[:q] 1}}
    [:real-setup :db-seed :sub-overrides]))

;; ---- world-inputs axis (distinct from fidelity) --------------------------

(deftest world-input-signals-presence
  (are [body slots] (= slots (mapv :value (rf.story.ui.sidebar-signals/world-input-signals body)))
    ;; an empty map is not a world input
    {:args {} :network {} :fx-overrides {}}
    []
    ;; render order is args, route, network, fx-overrides
    {:args {:n 1} :route [:home]
     :network {[:get "/x"] {:reply {:ok 1}}}
     :fx-overrides {:fx :stub}}
    [:args :route :network :fx-overrides]))

;; ---- runner-requirement axis (capability tokens) -------------------------

(deftest runner-requirement-from-capabilities
  (are [body kind] (= kind (rf.story.ui.sidebar-signals/required-runner-kind body))
    {}                                           :headless
    {:script [[:click "#go"]]}                   :dom
    {:assertions [[:rf.assert/visual-snapshot]]} :browser))

;; ---- frame-binding axis (a binding, not a tier) --------------------------

(deftest frame-binding-signal-shape
  (are [body value] (= value (:value (rf.story.ui.sidebar-signals/frame-binding-signal body)))
    {}                                         :fresh
    {:frame-binding :attached}                 :attached
    ;; an invalid binding falls back to :fresh
    {:frame-binding :bogus}                    :fresh
    ;; MCP-bound is an affordance on an attached binding, not a tier
    {:frame-binding :attached :mcp-bound true} :mcp-bound))

;; ---- inherited + composed world: the chips agree with the plan -----------

(deftest chips-read-the-world-extends-and-compose-pass-down
  ;; The sidebar hands `variant-signals` the RAW registered body. An
  ;; `:extends` child of a pinned variant, or a variant composing a seeding
  ;; fragment, renders on inherited / composed world, so its chips must
  ;; match the compiled plan's `[:world :fidelity]`.
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
    (let [values (fn [vid axis]
                   (set (map :value (get (rf.story.ui.sidebar-signals/variant-signals
                                           (rf.story.registrar/handler-meta :variant vid)
                                           :pending)
                                         axis))))]
      (doseq [vid [:story.fid/pinned :story.fid/child
                   :story.fid/grandchild :story.fid/composed]]
        (is (= (get-in (rf.story.plan/variant-plan vid) [:world :fidelity] #{})
               (values vid :fidelity))
            (str vid " — the fidelity chips are the plan's rungs")))
      (is (= #{:sub-overrides :real-setup} (values :story.fid/grandchild :fidelity))
          "setup and pin both flow down two levels")
      (is (= #{:db-seed} (values :story.fid/composed :fidelity))
          "the composed fragment's seed is the variant's seed")
      (is (= #{:args :network :fx-overrides} (values :story.fid/child :world-inputs))
          "the inherited world inputs are the child's world inputs"))
    (finally (rf.story.registrar/clear-all!))))
