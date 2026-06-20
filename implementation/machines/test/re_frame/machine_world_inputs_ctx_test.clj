(ns re-frame.machine-world-inputs-ctx-test
  "Per EP-0010 §The World-Input Rule + Spec 005 §Guards / §Actions.

  EP-0010 requires that durable machine decisions and snapshot writes
  depending on time / random / UUID-style host facts fold the CAUSAL
  world-input token (the router's `:rf.cofx` coeffect, stamped at
  the dispatch envelope's causal boundary — Spec 002 §The World-Input
  Rule) rather than reading an ambient clock (`interop/now-ms`). Folding
  the token is what makes the decision deterministic under restore /
  replay / SSR hydration: the SAME token replays the SAME decision.

  The machine transition path threads the handler's `:rf.cofx` coeffect
  onto the machine def (`prepare-machine-ctx`, alongside `:rf/frame` /
  `:rf/platform` / `:rf/parent-id`, propagated to region specs) and
  surfaces it on the guard / action / entry / exit callback ctx under the
  `:rf.cofx` key — so a durable machine guard / action reads host facts
  causally rather than from an ambient clock.

  Coverage:
    (a) a flat-machine GUARD reads exactly the scripted `:time-ms` from
        `(:rf.cofx ctx)` — no ambient clock — and decides on it.
    (b) a flat-machine ACTION reads exactly the scripted `:time-ms` and
        folds it into a `:data` write (a durable snapshot write).
    (c) an :entry action (on the initial-entry boot cascade) reads the
        scripted token.
    (d) a PARALLEL-REGION guard reads the scripted token (proving the
        token propagates through the synthetic region-spec).
    (e) a non-time fact (random / UUID-style) supplied in the SAME token
        map is readable verbatim — the token carries arbitrary host facts.
    (f) the absent-token path: a pure `machine-transition` call (no router
        coeffect) surfaces NO `:rf.cofx` ctx key — the key keys
        off presence, so pure-fn callers (conformance corpus) are
        unaffected."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

;; A clearly-non-ambient sentinel: a fixed wall-clock epoch ms that the
;; host clock will never spontaneously return. If a callback read the
;; ambient clock instead of the causal token, it would NOT equal this.
(def ^:private SCRIPTED-TIME-MS 1234500000)

;; ---- (a) a flat-machine GUARD reads the scripted :time-ms -----------------

(deftest guard-reads-causal-time-ms
  (testing "a guard reads exactly the dispatch's :rf.cofx :time-ms —
            the causal token, not an ambient clock"
    (let [seen (atom nil)
          ;; The guard fires the transition iff the causal :time-ms equals
          ;; the scripted sentinel. An ambient-clock read would never match.
          m {:initial :idle
             :data    {}
             :guards  {:at-scripted-time?
                       (fn [{cofx :rf.cofx}]
                         (reset! seen (:rf/time-ms cofx))
                         (= SCRIPTED-TIME-MS (:rf/time-ms cofx)))}
             :states  {:idle {:on {:go {:target :done :guard :at-scripted-time?}}}
                       :done {}}}]
      (rf/reg-machine :world/guard m)
      (rf/dispatch-sync [:world/guard [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= SCRIPTED-TIME-MS @seen)
          "the guard read the scripted :time-ms off (:rf.cofx ctx)")
      (is (= :done (:state (snapshot :world/guard)))
          "the guard fired the transition on the causal :time-ms"))))

(deftest guard-blocks-on-wrong-causal-time-ms
  (testing "the same guard BLOCKS when the scripted :time-ms differs — proving
            the decision folds the token's value, not an ambient read"
    (let [m {:initial :idle
             :data    {}
             :guards  {:at-scripted-time?
                       (fn [{cofx :rf.cofx}]
                         (= SCRIPTED-TIME-MS (:rf/time-ms cofx)))}
             :states  {:idle {:on {:go {:target :done :guard :at-scripted-time?}}}
                       :done {}}}]
      (rf/reg-machine :world/guard-block m)
      (rf/dispatch-sync [:world/guard-block [:go]]
                        {:rf.cofx {:rf/time-ms (inc SCRIPTED-TIME-MS)}})
      (is (= :idle (:state (snapshot :world/guard-block)))
          ":go blocked — the causal :time-ms did not match the guard's predicate"))))

;; ---- (b) a flat-machine ACTION folds the scripted :time-ms into :data ------

(deftest action-folds-causal-time-ms-into-data
  (testing "an action reads the causal :time-ms and folds it into a durable
            :data write (a snapshot write that must replay deterministically)"
    (let [m {:initial :idle
             :data    {}
             :actions {:stamp-time
                       (fn [{cofx :rf.cofx}]
                         {:data {:stamped-at (:rf/time-ms cofx)}})}
             :states  {:idle {:on {:go {:target :done :action :stamp-time}}}
                       :done {}}}]
      (rf/reg-machine :world/action m)
      (rf/dispatch-sync [:world/action [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= SCRIPTED-TIME-MS (:stamped-at (:data (snapshot :world/action))))
          "the action wrote the causal :time-ms into :data — folded from the
           token, not from an ambient clock"))))

;; ---- (c) an :entry action (boot cascade) reads the causal token -----------

(deftest entry-action-reads-causal-time-ms
  (testing "the initial-state :entry action — running on the boot cascade of
            the first dispatch — reads the causal :time-ms"
    (let [m {:initial :ready
             :data    {}
             :actions {:stamp-birth
                       (fn [{cofx :rf.cofx}]
                         {:data {:born-at (:rf/time-ms cofx)}})}
             :states  {:ready {:entry :stamp-birth}}}]
      (rf/reg-machine :world/entry m)
      ;; First real dispatch boots the machine; the :entry cascade runs under
      ;; the same handler call and so reads this dispatch's causal token.
      (rf/dispatch-sync [:world/entry [:noop]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= SCRIPTED-TIME-MS (:born-at (:data (snapshot :world/entry))))
          "the :entry action read the causal :time-ms on the boot cascade"))))

;; ---- (d) a PARALLEL-REGION guard reads the causal token --------------------

(deftest region-guard-reads-causal-time-ms
  (testing "a guard running inside a parallel REGION reads the causal :time-ms
            — proving the token propagates through the synthetic region-spec"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:at-scripted-time?
                       (fn [{cofx :rf.cofx}]
                         (= SCRIPTED-TIME-MS (:rf/time-ms cofx)))}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:go {:target :done
                                             :guard  :at-scripted-time?}}}
                            :done {}}}
              :b {:initial :x :states {:x {}}}}}]
      (rf/reg-machine :world/region m)
      (rf/dispatch-sync [:world/region [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= :done (get-in (snapshot :world/region) [:state :a]))
          "the region guard fired on the causal :time-ms — the token reached
           the region callback ctx")))
  (testing "and the same region guard blocks on a non-matching causal :time-ms"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:at-scripted-time?
                       (fn [{cofx :rf.cofx}]
                         (= SCRIPTED-TIME-MS (:rf/time-ms cofx)))}
             :regions
             {:a {:initial :idle
                  :states  {:idle {:on {:go {:target :done
                                             :guard  :at-scripted-time?}}}
                            :done {}}}
              :b {:initial :x :states {:x {}}}}}]
      (rf/reg-machine :world/region-block m)
      (rf/dispatch-sync [:world/region-block [:go]]
                        {:rf.cofx {:rf/time-ms (inc SCRIPTED-TIME-MS)}})
      (is (= :idle (get-in (snapshot :world/region-block) [:state :a]))
          "the region guard blocked — wrong causal :time-ms"))))

;; ---- (e) arbitrary host facts (random / uuid-style) ride the token --------

(deftest action-reads-arbitrary-host-facts-from-token
  (testing "the token carries arbitrary host facts (a random seed / uuid-style
            value), readable verbatim by an action — not just :time-ms"
    (let [seed     987654321
          gen-uuid "11111111-2222-3333-4444-555555555555"
          m {:initial :idle
             :data    {}
             :actions {:stamp-facts
                       (fn [{cofx :rf.cofx}]
                         {:data {:seed (:rng-seed cofx)
                                 :id   (:new-uuid cofx)}})}
             :states  {:idle {:on {:go {:target :done :action :stamp-facts}}}
                       :done {}}}]
      (rf/reg-machine :world/facts m)
      (rf/dispatch-sync [:world/facts [:go]]
                        {:rf.cofx {:rf/time-ms  SCRIPTED-TIME-MS
                                           :rng-seed seed
                                           :new-uuid gen-uuid}})
      (let [d (:data (snapshot :world/facts))]
        (is (= seed (:seed d))
            "the action read the causal random seed off the token")
        (is (= gen-uuid (:id d))
            "the action read the causal uuid-style fact off the token")))))

;; ---- (f) absent token — pure-fn caller surfaces no :rf.cofx key ----

(deftest pure-fn-callback-ctx-has-no-world-inputs-key
  (testing "a pure machine-transition (no router coeffect, the conformance /
            JVM-fixture path) surfaces NO :rf.cofx ctx key — the key
            is presence-gated, so pure-fn callers are unaffected"
    (let [captured (atom nil)
          m {:initial :idle
             :data    {}
             :guards  {:capture (fn [ctx] (reset! captured ctx) true)}
             :states  {:idle {:on {:go {:target :done :guard :capture}}}
                       :done {}}}]
      ;; Drive the PURE engine directly — no dispatch, no handler, so the
      ;; machine def carries no :rf/cofx stamp.
      (machines/machine-transition m {:state :idle :data {}} [:go])
      (is (some? @captured) "the guard ran")
      (is (not (contains? @captured :rf.cofx))
          "no :rf.cofx key when the engine is driven without a token")
      (is (= #{:data :event :state :meta} (set (keys @captured)))
          "the pure-fn guard ctx is exactly the four base keys"))))
