(ns re-frame.machine-source-unstamped-warning-test
  "Dev-only source-metadata advisory `:rf.warning/machine-source-unstamped`
  (`re-frame.machines.lifecycle-fx.registration/maybe-warn-source-unstamped!`,
  wired into the single registration home). The `reg-machine` macro / the
  `defmachine` def-shape co-locate per-element source (`:source-coords` /
  `:source-code`) onto each `:guards` / `:actions` entry
  and `:states`-tree map node — the surface Xray reads to navigate a live
  snapshot back to the guard / action / state DEFINITION (click-to-source). A
  plain `(def m {…})` + `(reg-machine :id m)` hands the macro only the `m`
  SYMBOL, so its literal-walk captures nothing and the spec arrives SOURCE-BLIND
  — Xray silently loses click-to-source for that machine.

  The advisory keys on the OBSERVABLE property (the arriving spec carries no
  per-element source metadata), NOT on symbol-vs-expression spelling — so an
  inline literal (macro-walked) and a `defmachine` value (definition-site walk)
  both arrive source-bearing and do NOT warn; a bare-def symbol and a genuinely
  runtime-built spec (`reg-machine*`) both arrive source-blind and DO warn. It
  is a WARNING (recoverable — the machine runs fine, only tooling nav degrades),
  dev-only (DCE'd in production), once per id.

  Cross-platform note: each `defmachine` / inline machine below carries a
  `:guards` fn LITERAL, so source is captured on BOTH JVM (the LispReader
  decorates list/fn forms) and CLJS — the negative cases are robust on either
  reader."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private WARN :rf.warning/machine-source-unstamped)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture
  ;; Start each case from a clean once-per-id slate.
  (fn [t] (rf.machines.lifecycle-fx.registration/clear-source-unstamped-warned!) (t)))

(defn- warns [] (rf.machines.test-support/events-of WARN))

;; A plain `(def …)` value — the SOURCE-BLIND foil. The macro sees only the
;; `blind-spec` symbol at the reg-machine call site, so nothing is stamped.
(def ^:private blind-spec
  {:initial :idle
   :guards  {:ok? (fn [_] true)}
   :actions {:go  (fn [_] {})}
   :states  {:idle {:on {:submit {:target :done :guard :ok? :action :go}}}
             :done {}}})

;; The SAME spec via `defmachine` — source captured at the definition site, so
;; it travels into `reg-machine` and does NOT warn.
(rf/defmachine stamped-spec
  {:initial :idle
   :guards  {:ok? (fn [_] true)}
   :actions {:go  (fn [_] {})}
   :states  {:idle {:on {:submit {:target :done :guard :ok? :action :go}}}
             :done {}}})

(deftest bare-def-spec-warns-once-per-id
  (testing "a plain (def m …) + (reg-machine :id m) arrives source-blind: one recoverable
            :warning advisory per id, so a re-registered id stays silent and a distinct
            id warns again"
    (rf/reg-machine :src-warn/dup blind-spec)
    (rf/reg-machine :src-warn/dup blind-spec)
    (rf/reg-machine :src-warn/b blind-spec)
    (is (= [[:src-warn/dup :warning :warned-and-proceeded true]
            [:src-warn/b :warning :warned-and-proceeded true]]
           (mapv (juxt (comp :machine-id :tags) :op-type :recovery (comp string? :reason :tags))
                 (warns))))))

(deftest silent-for-source-bearing-specs
  (testing "a defmachine value (stamped at its def site) and an inline reg-machine
            literal (macro-walked in place) both carry per-element source, so
            neither warns"
    (rf/reg-machine :src-warn/defm stamped-spec)
    (rf/reg-machine :src-warn/inline
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:go  (fn [_] {})}
       :states  {:idle {:on {:submit {:target :done :guard :ok? :action :go}}}
                 :done {}}})
    (is (empty? (warns)))))

(deftest noop-in-production
  (testing "under rf.interop/debug-enabled? false (the production gate) the
            advisory is not emitted — the consult + emit branch DCEs"
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-machine :src-warn/prod blind-spec))
    (is (empty? (warns)) "no advisory in production")))
