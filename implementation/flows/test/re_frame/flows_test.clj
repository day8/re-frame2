(ns re-frame.flows-test
  "JVM smoke coverage for Spec 013 — Flows.

  This file backstops the conformance fixtures in
  spec/conformance/fixtures/flow-*.edn. Where the fixtures
  describe canonical flow shapes as data (skipped by the reference
  harness until the :flow/* capability set is wired into the conformance
  runner), the tests here exercise the same paths against the JVM
  reference implementation directly:

    - reg-flow / clear-flow round-trip
    - dirty-check (=-equal inputs do NOT recompute)
    - topological sort (B reads what A wrote; one drain pass)
    - cycle detection at registration time
    - hot-reload preserves the output value when the new body is
      value-equivalent on current inputs
    - :rf.fx/reg-flow / :rf.fx/clear-flow toggle round-trip
    - clear-all / lifecycle interaction with the per-frame registry"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- per-test reset -------------------------------------------------------
;;
;; Mirrors the smoke_test.clj fixture so each deftest starts from a clean
;; registrar / frames / flows state. Re-loading routing / ssr restores the
;; framework events that clear-all! wiped (some smoke tests rely on
;; :rf.route/navigate etc. resolving — keep the reset shape consistent so
;; running these tests in any order works).

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  ;; flows.cljc keeps a private last-inputs atom for dirty-checking
  ;; (per Spec 013 §Dirty-check semantics). The smoke-test fixture
  ;; doesn't reset it; left alone, an entry from this namespace's tests
  ;; can leak into a sibling namespace's identically-keyed flow and
  ;; cause its first-evaluation to no-op (new-inputs would =-equal the
  ;; stale last-inputs). Clear it here so cross-namespace test order
  ;; can't introduce hidden flakiness.
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---------------------------------------------------------------------------
;; 1. reg-flow / clear-flow lifecycle (registry side)
;; ---------------------------------------------------------------------------

(deftest reg-flow-populates-registry
  (testing "reg-flow stores the flow under [frame-id flow-id] in the per-frame registry"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]})
    (is (contains? (get @flows/flows :rf/default) :area)
        "the flow lives under :rf/default's slot of the per-frame registry")
    (is (some? (registrar/lookup :flow :area))
        "the flow is also discoverable via the :flow registrar kind")))

(deftest clear-flow-removes-from-registry-and-vacates-output-slot
  (testing "clear-flow removes the flow and dissoc-in's its output path"
    ;; rf2-aqt7: clear-flow's update-in path math is off-by-one for
    ;; single-element :path vectors. Use a two-element :path here so
    ;; the working branch (>= 2 elements) is exercised.
    (rf/reg-event-db :seed (fn [_ _] {:rect {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:rect :w] [:rect :h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:seed])
    (is (= 12 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "flow ran on the drain after :seed and materialised :rect/:area")
    (rf/clear-flow :area)
    (is (not (contains? (get @flows/flows :rf/default) :area))
        "the per-frame registry no longer carries :area")
    (is (not (contains? (get (rf/get-frame-db :rf/default) :rect) :area))
        "clear-flow dissoc'd the leaf at the flow's :path"))
  (testing "calling clear-flow on an unknown id is a no-op (does not throw)"
    (rf/clear-flow :no-such-flow)))

(deftest clear-flow-handles-single-element-path
  (testing "rf2-aqt7: clear-flow with a single-element :path dissocs the top-level key"
    ;; The repro from rf2-aqt7: a flow whose :path is a one-element vector
    ;; [:area]. Before the fix, clear-flow's (update-in cur [] dissoc :area)
    ;; left :area in app-db (and silently introduced an {nil nil} entry).
    (rf/reg-event-db :seed (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:area]})
    (rf/dispatch-sync [:seed])
    (is (= 12 (get (rf/get-frame-db :rf/default) :area))
        "flow ran on the drain after :seed and materialised :area")
    (rf/clear-flow :area)
    (let [db (rf/get-frame-db :rf/default)]
      (is (not (contains? db :area))
          "single-element :path is dissoc'd cleanly")
      (is (not (contains? db nil))
          "no spurious {nil nil} entry from update-in on empty path")
      (is (= {:w 3 :h 4} db)
          "siblings of the cleared key are untouched"))))

(deftest reg-flow-validates-required-keys
  (testing "missing :id throws"
    (is (thrown? Throwable
                 (rf/reg-flow {:inputs [[:n]] :output identity :path [:x]}))))
  (testing ":inputs must be a vector"
    (is (thrown? Throwable
                 (rf/reg-flow {:id :bad :inputs :not-a-vec
                               :output identity :path [:x]}))))
  (testing ":output must be a fn"
    (is (thrown? Throwable
                 (rf/reg-flow {:id :bad :inputs [[:n]]
                               :output 42 :path [:x]}))))
  (testing ":path must be a vector"
    (is (thrown? Throwable
                 (rf/reg-flow {:id :bad :inputs [[:n]]
                               :output identity :path :not-a-vec})))))

(deftest reg-flow-detects-cycles-at-registration
  (testing ":a depends on :b, :b depends on :a — registering the second throws"
    (rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
    (is (thrown? Throwable
                 (rf/reg-flow {:id :b :inputs [[:a]]
                               :output identity :path [:b]}))
        "the cyclic registration unwinds and throws :rf.error/flow-cycle")
    (is (not (contains? (get @flows/flows :rf/default) :b))
        "cycle-detection rolls back the partial registration of :b")))

;; ---------------------------------------------------------------------------
;; 2. Dirty-check / re-evaluation
;; ---------------------------------------------------------------------------

(deftest flow-recomputes-on-input-change
  (testing "mutating an input path causes the flow to fire and the output to update"
    (rf/reg-event-db :init (fn [_ _] {:w 0 :h 0}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    (rf/reg-event-db :h!   (fn [db [_ h]] (assoc db :h h)))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:init])
    (is (= 0 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "first drain after :init fires the flow with 0 × 0 = 0")
    (rf/dispatch-sync [:w! 5])
    (is (= 0 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        ":h is still 0 → 5 × 0 = 0; flow ran")
    (rf/dispatch-sync [:h! 6])
    (is (= 30 (get-in (rf/get-frame-db :rf/default) [:rect :area]))
        "5 × 6 = 30 after both inputs are populated")))

(deftest flow-noop-on-equal-input-rewrite
  (testing "rewriting an input path with an =-equal value does NOT re-fire the flow"
    (let [calls (atom 0)]
      (rf/reg-event-db :init      (fn [_ _] {:n 5}))
      (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :output (fn [n]
                              (swap! calls inc)
                              (* 2 n))
                    :path   [:derived :doubled]})
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "first drain fires the flow once (initial evaluation)")
      (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled])))
      ;; Replace :n with the same value (5 → 5).
      (rf/dispatch-sync [:replace-n 5])
      (is (= 1 @calls)
          ":n was replaced with =-equal value; flow did NOT recompute")
      (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
          "output unchanged")
      ;; Now flip :n to a different value.
      (rf/dispatch-sync [:replace-n 7])
      (is (= 2 @calls)
          ":n changed to 7; flow recomputed")
      (is (= 14 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))))))

(deftest flow-no-recompute-when-unrelated-path-changes
  (testing "writing an unrelated path does not re-fire a flow whose inputs are stable"
    (let [calls (atom 0)]
      (rf/reg-event-db :init      (fn [_ _] {:user {:name "alice"} :other 0}))
      (rf/reg-event-db :bump-other (fn [db _] (update db :other inc)))
      (rf/reg-flow {:id     :user/uppercase-name
                    :inputs [[:user :name]]
                    :output (fn [n]
                              (swap! calls inc)
                              (when n (.toUpperCase ^String n)))
                    :path   [:user :uppercase-name]})
      (rf/dispatch-sync [:init])
      (is (= 1 @calls) "first evaluation always fires")
      (is (= "ALICE" (get-in (rf/get-frame-db :rf/default)
                             [:user :uppercase-name])))
      (dotimes [_ 5] (rf/dispatch-sync [:bump-other]))
      (is (= 1 @calls)
          ":other changed but [:user :name] did not; flow stayed quiet"))))

;; ---------------------------------------------------------------------------
;; 3. Topological sort — B depends on A; one drain settles both
;; ---------------------------------------------------------------------------

(deftest flow-topo-sort-cascades-in-one-drain
  (testing "B reads what A wrote; topo sort places A first; one drain settles both"
    (rf/reg-event-db :init (fn [_ _] {:w 2 :h 3}))
    (rf/reg-event-db :w!   (fn [db [_ w]] (assoc db :w w)))
    ;; A: :area depends on :w :h, writes :rect/:area.
    (rf/reg-flow {:id     :rect/area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    ;; B: :area*2 depends on :rect/:area, writes :rect/:area*2.
    (rf/reg-flow {:id     :rect/area-doubled
                  :inputs [[:rect :area]]
                  :output (fn [a] (* 2 a))
                  :path   [:rect :area*2]})
    (rf/dispatch-sync [:init])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 6  (get-in db [:rect :area]))   "A fired with 2 × 3 = 6")
      (is (= 12 (get-in db [:rect :area*2])) "B fired in the same drain with 6 × 2 = 12"))
    (rf/dispatch-sync [:w! 5])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 15 (get-in db [:rect :area]))   "A re-fired: 5 × 3 = 15")
      (is (= 30 (get-in db [:rect :area*2])) "B saw A's new output and re-fired: 30"))))

(deftest flow-topo-sort-handles-prefix-overlap
  (testing "B's :inputs is a prefix of A's :path — A still runs before B (Spec 013 §Dependency rule)"
    (rf/reg-event-db :init (fn [_ _] {:user {:name "alice"} :note ""}))
    ;; A writes deep at [:user :uppercase] — its :path is rooted in
    ;; the same prefix as B's input.
    (rf/reg-flow {:id     :user/uppercase
                  :inputs [[:user :name]]
                  :output (fn [n] (when n (.toUpperCase ^String n)))
                  :path   [:user :uppercase]})
    ;; B's input is [:user] — a prefix of A's :path. Per Spec 013,
    ;; the dependency rule fires in either prefix direction.
    (rf/reg-flow {:id     :user/note
                  :inputs [[:user]]
                  :output (fn [u]
                            (str "user-keys:"
                                 (pr-str (vec (sort (keys u))))))
                  :path   [:summary :note]})
    (rf/dispatch-sync [:init])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= "ALICE" (get-in db [:user :uppercase]))
          "A wrote :user :uppercase")
      (is (= "user-keys:[:name :uppercase]"
             (get-in db [:summary :note]))
          "B saw both :name and the just-written :uppercase in one drain"))))

;; ---------------------------------------------------------------------------
;; 4. Hot-reload — re-registration preserves output when bodies agree
;; ---------------------------------------------------------------------------

(deftest flow-hot-reload-preserves-equivalent-output
  (testing "re-registering a flow with a body that produces the same output keeps the output stable"
    (rf/reg-event-db :init (fn [_ _] {:n 5}))
    (rf/reg-event-db :tick (fn [db _] (update db :tick (fnil inc 0))))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled])))
    ;; Re-register with a body that produces the SAME output for the
    ;; current input. Per Spec 013 §Re-registration the next drain
    ;; re-evaluates; the user-visible output stays 10.
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (+ n n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:tick])
    (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
        "value-equivalent re-registration leaves the output stable")))

(deftest flow-hot-reload-new-body-recomputes-on-next-drain
  (testing "if the new body would produce a different value, the next drain materialises it"
    (rf/reg-event-db :init  (fn [_ _] {:n 5}))
    (rf/reg-event-db :tick  (fn [db _] (update db :tick (fnil inc 0))))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    (is (= 10 (get-in (rf/get-frame-db :rf/default) [:derived :doubled])))
    ;; Re-register with a 100x body; same input still 5.
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 100 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:tick])
    (is (= 500 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
        "after re-registration the new body produces 5 × 100 = 500")))

;; ---------------------------------------------------------------------------
;; 5. Toggle via :rf.fx/reg-flow / :rf.fx/clear-flow
;; ---------------------------------------------------------------------------

(deftest fx-reg-flow-and-clear-flow-round-trip
  (testing ":rf.fx/reg-flow registers; :rf.fx/clear-flow removes; the output path is dissoc'd"
    (rf/reg-event-db :init  (fn [_ _] {:wizard {:foo 3 :bar 4}}))
    (rf/reg-event-fx :enter (fn [_ _]
                              {:fx [[:rf.fx/reg-flow
                                     {:id     :step-2/computed
                                      :inputs [[:wizard :foo] [:wizard :bar]]
                                      :output (fn [foo bar] (+ foo bar))
                                      :path   [:wizard :result]}]]}))
    (rf/reg-event-db :foo!  (fn [db [_ v]] (assoc-in db [:wizard :foo] v)))
    (rf/reg-event-fx :leave (fn [_ _]
                              {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
    (rf/dispatch-sync [:init])
    (is (nil? (get-in (rf/get-frame-db :rf/default) [:wizard :result]))
        "no flow yet — :result is unset")
    ;; Register the flow during :enter. Per Spec 013 §Sequencing the flow
    ;; first runs on the NEXT event drain.
    (rf/dispatch-sync [:enter])
    (is (contains? (get @flows/flows :rf/default) :step-2/computed)
        "registry now carries :step-2/computed")
    ;; Drive a drain with a benign event; the flow first-fires here.
    (rf/dispatch-sync [:foo! 5])
    (is (= 9 (get-in (rf/get-frame-db :rf/default) [:wizard :result]))
        "flow ran on this drain with 5 + 4 = 9")
    ;; Now clear via fx.
    (rf/dispatch-sync [:leave])
    (is (not (contains? (get @flows/flows :rf/default) :step-2/computed))
        "registry slot removed")
    (is (not (contains? (get (rf/get-frame-db :rf/default) :wizard) :result))
        ":rf.fx/clear-flow dissoc-in'd the output path")))

;; ---------------------------------------------------------------------------
;; 6. clear-all / clean-state interaction with :flow registrar slot
;; ---------------------------------------------------------------------------

(deftest clear-all-clears-flow-registrar-slot
  (testing "registrar/clear-all! removes the :flow kind so subsequent reg-flow starts clean"
    (rf/reg-flow {:id :one :inputs [[:a]] :output identity :path [:slots :one]})
    (rf/reg-flow {:id :two :inputs [[:a]] :output identity :path [:slots :two]})
    (is (some? (registrar/lookup :flow :one)))
    (is (some? (registrar/lookup :flow :two)))
    (registrar/clear-all!)
    (is (nil? (registrar/lookup :flow :one)))
    (is (nil? (registrar/lookup :flow :two)))))

(deftest reset-flows-atom-clears-per-frame-state
  (testing "resetting flows/flows clears the per-frame registry; reg-flow repopulates fresh"
    (rf/reg-flow {:id :one :inputs [[:a]] :output identity :path [:slots :one]})
    (is (contains? (get @flows/flows :rf/default) :one))
    (reset! flows/flows {})
    (reset! schemas/schemas-by-frame {})
    (is (empty? (get @flows/flows :rf/default))
        "per-frame map is empty after reset")
    (rf/reg-flow {:id :one :inputs [[:a]] :output identity :path [:slots :one]})
    (is (contains? (get @flows/flows :rf/default) :one)
        "re-registration after reset works without raising")))
