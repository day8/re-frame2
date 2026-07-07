(ns re-frame.event-context-coeffect-keys-test
  "rf2-1m6rf1 — the EXACT event-context coeffect key set.

  EP-0002 R3 (one carrier, one name) retired the bare `:frame` coeffect:
  the frame stamp travels in the event context under `:rf.frame/id` ONLY
  (per Spec 002 §Event context threads both partitions). The duplicate
  `:frame` injection that `assemble-initial-ctx` used to add is gone.

  This is the framework-must-not-violate-its-own-contract guard: it pins
  the framework-injected coeffect key set EXACTLY so a stray parallel
  `:frame` (or any other key) cannot silently ride back in. It is the
  coeffect-key sibling of the rf2-o4dmp8 framework-conformance test —
  held in a DISTINCT file to keep the two assertions independent.

  Sanctioned `:frame` survivors (NOT coeffects, NOT covered by this set):
    - the public `:frame` dispatch / subscribe opt;
    - the dispatch envelope `:frame` key;
    - the binary fx-handler ctx `:frame` (Spec 002 §The binary fx-handler
      signature) + the HTTP-interceptor ctx `:frame` (Spec 014 §Middleware);
    - trace / error-record `:frame` tags."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
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

;; ---- helpers --------------------------------------------------------------

(defn- capture-coeffects
  "Dispatch `[:capture]` on `frame-id` (optionally threading `opts`) and
  return the coeffects map the handler saw."
  ([frame-id] (capture-coeffects frame-id nil))
  ([frame-id opts]
   (let [captured (atom nil)]
     (rf/reg-interceptor :capture/probe
       {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
     (rf/reg-event :capture
       {:interceptors [:capture/probe]}
       (fn [_ _] {}))
     (if opts
       (rf/dispatch-sync [:capture] (merge {:frame frame-id} opts))
       (rf/dispatch-sync [:capture] {:frame frame-id}))
     @captured)))

;; ===========================================================================
;; The exact framework-injected coeffect key set
;; ===========================================================================

(deftest framework-coeffect-key-set-is-exact
  (testing "a vanilla event with no user cofx sees EXACTLY the framework keys"
    (rf/reg-frame :ck/exact {:doc "ctx"})
    (let [cofx (capture-coeffects :ck/exact)]
      ;; rf2-n0myjq — `:rf.cofx/mint-policy` (the resolved effective mint policy)
      ;; is a framework coeffect stamped by `assemble-initial-ctx` so the machine
      ;; ensure path can read it; it joins the framework default key set.
      (is (= #{:db :event :rf.db/runtime :rf.frame/id :rf.cofx :rf.cofx/mint-policy :source}
             (set (keys cofx)))
          "the coeffect key set is exactly the framework defaults — no bare :frame")
      (is (not (contains? cofx :frame))
          "the retired bare :frame coeffect is absent (one carrier, one name)")
      (is (= :ck/exact (:rf.frame/id cofx))
          ":rf.frame/id carries the running frame's id")
      (is (number? (get-in cofx [:rf.cofx :rf/time-ms]))
          ":rf.cofx carries a stamped :rf/time-ms (EP-0010 rf2-s9ss0t)"))))

(deftest no-frame-coeffect-even-with-trace-id
  (testing "threading a :trace-id adds :trace-id but never re-introduces :frame"
    (rf/reg-frame :ck/traced {:doc "ctx"})
    (let [cofx (capture-coeffects :ck/traced {:trace-id "tid-1"})]
      (is (= #{:db :event :rf.db/runtime :rf.frame/id :rf.cofx :rf.cofx/mint-policy :source :trace-id}
             (set (keys cofx)))
          ":trace-id appears when threaded; :frame still does not")
      (is (not (contains? cofx :frame))
          ":frame is absent regardless of envelope keys"))))

(deftest user-injected-cofx-do-not-mask-the-absence-of-frame
  (testing "a declared cofx adds its own key but :frame stays gone"
    (rf/reg-frame :ck/user {:doc "ctx"})
    (rf/reg-cofx :ck/now (fn [] 42))
    (let [captured (atom nil)]
      (rf/reg-interceptor :capture/probe
        {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
      (rf/reg-event :capture
        {:rf.cofx/requires [:ck/now]
         :interceptors [:capture/probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:capture] {:frame :ck/user})
      (let [cofx @captured]
        (is (contains? cofx :ck/now)
            "the user-injected coeffect is present")
        (is (= 42 (:ck/now cofx)))
        (is (not (contains? cofx :frame))
            "user cofx do not bring the bare :frame coeffect back")
        (is (= :ck/user (:rf.frame/id cofx))
            "the frame stamp is still :rf.frame/id only")))))

;; ===========================================================================
;; framework-coeffect-keys (the filter set) excludes the retired :frame
;; ===========================================================================

(deftest framework-coeffect-keys-excludes-frame
  (testing "fx/framework-coeffect-keys no longer codifies the bare :frame duplicate"
    (is (not (contains? fx/framework-coeffect-keys :frame))
        ":frame is not a framework coeffect key (it was the retired duplicate)")
    (is (contains? fx/framework-coeffect-keys :rf.frame/id)
        ":rf.frame/id is the retained frame-stamp coeffect key")
    (is (contains? fx/framework-coeffect-keys :rf.cofx)
        ":rf.cofx is a framework coeffect key (EP-0010 rf2-s9ss0t)")
    (is (contains? fx/framework-coeffect-keys :rf.cofx/mint-policy)
        ":rf.cofx/mint-policy is a framework coeffect key (rf2-n0myjq)")
    (is (= #{:db :event :source :trace-id :rf.db/runtime :rf.frame/id :rf.cofx
             :rf.cofx/mint-policy}
           fx/framework-coeffect-keys)
        "the framework-coeffect-keys set is exactly the framework defaults")))

(deftest user-injected-coeffects-projection-drops-all-framework-defaults
  (testing "user-injected-coeffects strips every framework default incl. :rf.frame/id"
    (let [cofx {:db {} :event [:e] :source :unknown :trace-id "t"
                :rf.db/runtime {} :rf.frame/id :f
                :rf.cofx {:rf/time-ms 1781078400123}
                :my/cofx 1}]
      (is (= {:my/cofx 1} (fx/user-injected-coeffects cofx))
          "only the genuinely user-injected coeffect survives the projection (EP-0010: :rf.cofx filtered too)"))))
