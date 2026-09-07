(ns re-frame.flows-path-focused-no-db-cljs-test
  "rf2-bw76 — DUAL-HOST router+flows integration coverage for the path
  interceptor's `:db` coeffect unwind.

  ## What this pins

  The standard `[:rf.interceptor/path p]` interceptor focuses `[:coeffects :db]`
  on the slice at `p` for the handler. That focus is HANDLER-scoped: the
  `:after` unwind must restore the ORIGINAL full app-db object (Spec 002
  §Standard `:rf.interceptor/path` rule 6), because the framework's own
  outermost flow stage runs AFTER it and falls back to `[:coeffects :db]` as
  the pending app-db whenever the handler emitted no `:db` effect
  (`router.cljc`, `pending-db`).

  With the coeffect left focused, an ordinary no-op / effect-only focused
  event handed the flow pass its own sub-slice AS THE ROOT. The flow pass then
  read its root input paths off that slice (getting `nil`), recomputed, and
  staged the result as a ROOT `:db` effect — erasing every sibling key. Returning
  `nil`, `{}` or `{:fx []}` all reproduced it, and the event need not touch a
  flow at all.

  ## Why this file exists beside the JVM coverage

  `implementation/core/test/re_frame/interceptor_test.clj` already pins this,
  but it is a `.clj` namespace: it runs on the JVM ONLY. The event / path /
  flow branches involved are shared `.cljc`, so the CLJS host was uncovered —
  and the two existing CLJC path cases cannot reach the fallback (one has no
  registered flow, the other emits `:db`, whose widening succeeds).

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it AND the cognitect JVM runner runs it
  (the `-test` suffix). It lives in the FLOWS artefact's test tree because the
  regression is the ACTUAL router driving the ACTUAL flow evaluator — core's
  own test tree cannot require `re-frame.flows`.

  ## The real-effect case

  `{:fx []}` pins the no-`:db` SHAPE but executes nothing, so it cannot witness
  the ORDERING the bead asks for: an effect-only focused event must run its
  effect exactly ONCE, and that effect must see the intact committed root. The
  `:bw76/record-root` fx below records `app-db-value` at the moment it fires."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Required for its side effect: loading the artefact publishes the
   ;; late-bound flow hooks, without which `rf/reg-flow` fails closed with
   ;; `:rf.error/flows-artefact-missing`.
   [re-frame.flows]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

;; The seed root. `:cart` is the focused sub-tree; `:counter` is the ROOT input
;; the flow reads (only reachable from the unfocused root); `:sibling` is the
;; bystander whose survival is the whole point.
(def ^:private seed-db {:cart {:items [1]} :counter 2 :sibling :keep})

;; The root INCLUDING the flow's settled output.
(def ^:private settled-db (assoc seed-db :derived 2))

(defn- register-fixture!
  "Seed event + the root-input flow. `:derived` is only 2 when the flow pass
  ran against the FULL root; against the `[:cart]` slice it reads `nil` and
  derives 0."
  []
  (rf/reg-event :bw76/init (fn [_ _] {:db seed-db}))
  (rf/reg-flow :bw76/derived {:inputs [[:counter]] :output-path [:derived]}
               (fn [n] (or n 0)))
  (rf/dispatch-sync [:bw76/init]))

;; ===========================================================================
;; 1. nil / {} / effect-only / NESTED focus all preserve the root
;; ===========================================================================

(deftest path-focused-no-db-preserves-root-under-flows-on-this-host
  (testing "rf2-bw76: a path-focused handler emitting NO :db effect must not let
            the outermost flow pass overwrite the root with its focused slice —
            nil, {} and {:fx []} all preserve every sibling key and the flow's
            root-derived value"
    (register-fixture!)
    (rf/reg-event :bw76/unfocused (fn [_ _] {}))
    (rf/reg-event :bw76/nil-result
                  {:interceptors [[:rf.interceptor/path [:cart]]]}
                  (fn [_ _] nil))
    (rf/reg-event :bw76/empty-result
                  {:interceptors [[:rf.interceptor/path [:cart]]]}
                  (fn [_ _] {}))
    (rf/reg-event :bw76/fx-only
                  {:interceptors [[:rf.interceptor/path [:cart]]]}
                  (fn [_ _] {:fx []}))
    ;; NESTED focus: the inner path's `:after` restores the OUTER slice, and the
    ;; outer's restores the root. A unwind that only restored one level would
    ;; hand the flow pass `{:items [1]}`'s child instead.
    (rf/reg-event :bw76/nested-fx-only
                  {:interceptors [[:rf.interceptor/path [:cart]]
                                  [:rf.interceptor/path [:items]]]}
                  (fn [_ _] {:fx []}))

    (rf/dispatch-sync [:bw76/unfocused])
    (is (= settled-db (rf/app-db-value :rf/default))
        "control: an UNFOCUSED no-db event leaves the root intact and the flow
         derives 2 from the root [:counter] input")

    (doseq [event-id [:bw76/nil-result :bw76/empty-result :bw76/fx-only
                      :bw76/nested-fx-only]]
      (rf/dispatch-sync [event-id])
      (is (= settled-db (rf/app-db-value :rf/default))
          (str "a path-focused handler returning no :db effect (" event-id
               ") preserves every root key and the flow's root-derived value")))))

;; ===========================================================================
;; 2. an ACTUAL effect runs ONCE and observes the intact COMMITTED root
;; ===========================================================================

(deftest path-focused-real-effect-sees-committed-root-on-this-host
  (testing "rf2-bw76: an effect-only focused event executes its effect exactly
            once, and that effect observes the intact COMMITTED root — not the
            focused slice, and not a root the flow pass fabricated from nil
            inputs"
    (register-fixture!)
    (let [observed (atom [])]
      (rf/reg-fx :bw76/record-root
                 (fn [_ _] (swap! observed conj (rf/app-db-value :rf/default))))
      (rf/reg-event :bw76/effect-only
                    {:interceptors [[:rf.interceptor/path [:cart]]]}
                    (fn [{:keys [db]} _]
                      (is (= {:items [1]} db)
                          "the handler still receives its FOCUSED slice")
                      {:fx [[:bw76/record-root nil]]}))
      (rf/dispatch-sync [:bw76/effect-only])

      (is (= 1 (count @observed))
          "the effect ran exactly ONCE — no second install re-ran the fx")
      (is (= settled-db (first @observed))
          "the effect saw the intact committed root, with the flow output
           derived from the ROOT [:counter] input")
      (is (= settled-db (rf/app-db-value :rf/default))
          "and the root is still intact after the effect returned"))))

;; ===========================================================================
;; 3. controls — the focused WRITE path and the no-op commit are untouched
;; ===========================================================================

(deftest path-focused-db-write-still-widens-under-flows-on-this-host
  (testing "rf2-bw76 control: a focused handler that DOES emit :db still has its
            slice widened back into the root (rule 5), and the flow still reads
            the root input"
    (register-fixture!)
    (rf/reg-event :bw76/focused-write
                  {:interceptors [[:rf.interceptor/path [:cart]]]}
                  (fn [{:keys [db]} _] {:db (update db :items conj 2)}))
    (rf/dispatch-sync [:bw76/focused-write])
    (is (= {:cart {:items [1 2]} :counter 2 :sibling :keep :derived 2}
           (rf/app-db-value :rf/default))
        "the changed slice widened at [:cart]; siblings and the derived value survive")))

(deftest path-focused-no-db-makes-no-root-write-on-this-host
  (testing "rf2-bw76: with the flow already settled, a no-db focused event
            manufactures NO root state write — the committed app-db object is
            identical?, so the commit boundary stayed a no-op (one deferred
            install, no synthetic :db effect)"
    (register-fixture!)
    (rf/reg-event :bw76/quiet
                  {:interceptors [[:rf.interceptor/path [:cart]]]}
                  (fn [_ _] {:fx []}))
    ;; Settle first: the initial dispatch installs :derived.
    (rf/dispatch-sync [:bw76/quiet])
    (let [before (rf/app-db-value :rf/default)]
      (rf/dispatch-sync [:bw76/quiet])
      (is (identical? before (rf/app-db-value :rf/default))
          "no new root object was installed for a no-db focused event with no
           pending flow changes"))))
