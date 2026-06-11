(ns re-frame.world-inputs-test
  "EP-0010 §Causal World Inputs core slice (rf2-s9ss0t).

  Pins the `:rf.world/inputs` envelope + coeffect contract that makes the
  frame fold deterministic with respect to prior frame-state plus the
  causal token (Spec 002 §The World-Input Rule):

    - the router STAMPS `:rf.world/inputs {:time-ms ...}` when the caller
      omits it (Spec 002 §Dispatch Envelope Stamping);
    - a caller-supplied map is PRESERVED verbatim — including extra slots
      (`:uuid`, `:random`, browser/storage facts) — and the router never
      overwrites a supplied `:time-ms`;
    - a CHILD dispatch (`:fx [[:dispatch ...]]`) gets its OWN map: `:time-ms`
      is NOT inherited from the parent (each is a distinct causal token);
    - the value is visible to handler bodies as the `:rf.world/inputs`
      coeffect alongside `:db` / `:event` / `:rf.db/runtime` / `:rf.frame/id`
      (Spec 002 §Event Context And Coeffects);
    - it is FILTERED out of the user-cofx trace projection exactly like the
      other framework defaults (`fx/framework-coeffect-keys`);
    - `:dispatched-at` is RETIRED in the same change (rider b) — its
      diagnostic dispatch-time need is the trace event `:time` stamp.

  JVM-only — the stamping path is platform-agnostic (`interop/now-ms`
  realises on both hosts); no CLJS host dependency under test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(def ^:private build-envelope
  "The private envelope builder — the dispatch envelope is not exposed to
  user handlers, so stamping/preservation is asserted directly against it."
  #'router/build-envelope)

(defn- capture-coeffects
  "Dispatch `[:capture]` on `frame-id` (threading `opts`) and return the
  coeffects map the handler saw."
  ([frame-id] (capture-coeffects frame-id nil))
  ([frame-id opts]
   (let [captured (atom nil)]
     (rf/reg-event-ctx :capture
       (fn [ctx] (reset! captured (:coeffects ctx)) ctx))
     (if opts
       (rf/dispatch-sync [:capture] (merge {:frame frame-id} opts))
       (rf/dispatch-sync [:capture] {:frame frame-id}))
     @captured)))

;; ===========================================================================
;; Envelope stamping
;; ===========================================================================

(deftest stamps-time-ms-when-absent
  (testing "the router stamps :rf.world/inputs {:time-ms ...} when the caller omits it"
    (rf/reg-frame :wi/stamp {:doc "ctx"})
    (let [env   (build-envelope [:noop] {:frame :wi/stamp})
          world (:rf.world/inputs env)]
      (is (map? world) ":rf.world/inputs is present on the envelope")
      (is (number? (:time-ms world)) ":time-ms is a stamped epoch-ms number")
      (is (= #{:time-ms} (set (keys world)))
          "only the framework-required :time-ms is stamped — no other keys invented"))))

(deftest preserves-caller-supplied-time-ms
  (testing "a caller-supplied :time-ms is preserved verbatim — the router does NOT overwrite it"
    (rf/reg-frame :wi/supplied {:doc "ctx"})
    (let [env (build-envelope [:noop]
                              {:frame :wi/supplied
                               :rf.world/inputs {:time-ms 1781078400123}})]
      (is (= 1781078400123 (get-in env [:rf.world/inputs :time-ms]))
          "the exact supplied :time-ms rides through (replay / SSR / fixtures)"))))

(deftest preserves-caller-supplied-extra-keys-and-fills-time-ms
  (testing "extra world-input slots ride through; a missing :time-ms is filled, supplied keys untouched"
    (rf/reg-frame :wi/extra {:doc "ctx"})
    (let [uuid  #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          env   (build-envelope [:noop]
                                {:frame :wi/extra
                                 :rf.world/inputs
                                 {:uuid   {:todo/id uuid}
                                  :random {:todo/color :green}}})
          world (:rf.world/inputs env)]
      (is (= {:todo/id uuid} (:uuid world)) "supplied :uuid slot preserved")
      (is (= {:todo/color :green} (:random world)) "supplied :random slot preserved")
      (is (number? (:time-ms world))
          "the framework-required :time-ms is filled in alongside the supplied keys"))))

;; ===========================================================================
;; Child dispatch gets its OWN world-input map (no :time-ms inheritance)
;; ===========================================================================

(deftest child-dispatch-gets-fresh-time-ms
  (testing "a :fx [[:dispatch ...]] child gets its OWN :rf.world/inputs — :time-ms NOT inherited"
    (rf/reg-frame :wi/cascade {:doc "ctx"})
    (let [envelopes (atom [])]
      ;; A user fx-handler receives the parent dispatch envelope under
      ;; (:envelope m); each handler in the cascade fires its own capture,
      ;; so we read both the parent's and child's stamped world map.
      (rf/reg-fx :wi/capture-env
        (fn [m _args] (swap! envelopes conj (:envelope m))))
      (rf/reg-event-fx :wi/parent
        (fn [_ _]
          {:fx [[:wi/capture-env]
                [:dispatch [:wi/child]]]}))
      (rf/reg-event-fx :wi/child
        (fn [_ _]
          {:fx [[:wi/capture-env]]}))

      ;; Parent supplies an explicit :time-ms; the child must NOT inherit it.
      (rf/dispatch-sync [:wi/parent]
                        {:frame :wi/cascade
                         :rf.world/inputs {:time-ms 1781078400000}})

      (let [[parent-env child-env] @envelopes
            parent-t (get-in parent-env [:rf.world/inputs :time-ms])
            child-t  (get-in child-env  [:rf.world/inputs :time-ms])]
        (is (= [:wi/parent] (:event parent-env)) "first capture is the parent")
        (is (= [:wi/child]  (:event child-env))  "second capture is the child")
        (is (= 1781078400000 parent-t) "parent carries the supplied :time-ms")
        (is (number? child-t) "child has its own stamped :time-ms")
        (is (not= 1781078400000 child-t)
            "child did NOT inherit the parent's :time-ms — distinct causal token (EP-0010)")))))

;; ===========================================================================
;; Coeffect visibility + trace projection filtering
;; ===========================================================================

(deftest world-inputs-visible-as-coeffect
  (testing "handlers read :rf.world/inputs from the coeffect map"
    (rf/reg-frame :wi/cofx {:doc "ctx"})
    (let [cofx (capture-coeffects :wi/cofx
                                  {:rf.world/inputs {:time-ms 1781078400456}})]
      (is (contains? cofx :rf.world/inputs)
          ":rf.world/inputs is a framework coeffect in the initial context")
      (is (= 1781078400456 (get-in cofx [:rf.world/inputs :time-ms]))
          "the supplied :time-ms is what the handler reads"))))

(deftest world-inputs-filtered-from-user-cofx-projection
  (testing "fx/user-injected-coeffects strips :rf.world/inputs like the other framework defaults"
    (is (contains? fx/framework-coeffect-keys :rf.world/inputs)
        ":rf.world/inputs is in the framework-coeffect-keys filter set")
    (let [cofx {:db {} :event [:e] :rf.db/runtime {} :rf.frame/id :f
                :rf.world/inputs {:time-ms 1781078400789}
                :my/cofx 1}]
      (is (= {:my/cofx 1} (fx/user-injected-coeffects cofx))
          ":rf.world/inputs does NOT appear in the user-cofx trace projection"))))

;; ===========================================================================
;; :dispatched-at is retired
;; ===========================================================================

(deftest dispatched-at-is-gone
  (testing "EP-0010 rider b: :dispatched-at is retired from the envelope (no coexistence)"
    (rf/reg-frame :wi/no-dispatched-at {:doc "ctx"})
    (testing "absent even with the dev gate ON (it is not merely prod-elided — it is gone)"
      (with-redefs [interop/debug-enabled? true]
        (is (not (contains? (build-envelope [:noop] {:frame :wi/no-dispatched-at})
                            :dispatched-at))
            "no :dispatched-at key on the envelope")))
    (testing "the durable causal-time fact is (:time-ms (:rf.world/inputs env)) instead"
      (let [env (build-envelope [:noop] {:frame :wi/no-dispatched-at})]
        (is (number? (get-in env [:rf.world/inputs :time-ms]))
            ":time-ms is the replacement for the retired :dispatched-at")))))
