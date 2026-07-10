(ns re-frame.machine-source-unstamped-warning-test
  "Dev-only source-metadata advisory `:rf.warning/machine-source-unstamped`
  (`re-frame.machines.lifecycle-fx.registration/maybe-warn-source-unstamped!`,
  wired into the single registration home). The `reg-machine` macro / the
  `defmachine` def-shape co-locate per-element source (`:source-coords` /
  `:source-code`) onto each `:guards` / `:actions` / `:on-spawn-actions` entry
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
            [re-frame.interop :as interop]
            [re-frame.machines :as machines]
            [re-frame.machines.lifecycle-fx.registration :as registration]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(def ^:private WARN :rf.warning/machine-source-unstamped)

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  mtest/trace-capture-fixture
  ;; Start each case from a clean once-per-id slate.
  (fn [t] (registration/clear-source-unstamped-warned!) (t)))

(defn- warns [] (mtest/events-of WARN))

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

;; ---- fires: source-blind ---------------------------------------------------

(deftest warns-for-bare-def-source-blind-spec
  (testing "a plain (def m …) + (reg-machine :id m): the macro sees only the
            symbol so the spec arrives source-blind — emits the advisory once,
            at :warning grade, naming the machine and carrying :warned-and-
            proceeded recovery (recoverable — registration still succeeds)"
    (rf/reg-machine :src-warn/blind blind-spec)
    (let [w (warns)]
      (is (= 1 (count w)) "exactly one source-unstamped advisory")
      (is (= :src-warn/blind (:machine-id (:tags (first w))))
          "the advisory names the machine")
      (is (= :warning (:op-type (first w))) "emitted at :warning grade")
      (is (= :warned-and-proceeded (:recovery (first w)))
          "advisory — warned, never threw; registration proceeded")
      (is (string? (:reason (:tags (first w))))
          "carries a :reason naming what is lost and the fix"))))

;; ---- silent: source-bearing (defmachine / inline) --------------------------

(deftest silent-for-defmachine-value
  (testing "a defmachine value carries per-element source (stamped at the def
            site) → NO advisory"
    (rf/reg-machine :src-warn/defm stamped-spec)
    (is (empty? (warns)) "defmachine value is source-bearing — no advisory")))

(deftest silent-for-inline-literal
  (testing "an inline reg-machine literal is macro-walked in place → source
            captured → NO advisory (the second blessed form)"
    (rf/reg-machine :src-warn/inline
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:go  (fn [_] {})}
       :states  {:idle {:on {:submit {:target :done :guard :ok? :action :go}}}
                 :done {}}})
    (is (empty? (warns)) "inline literal is source-bearing — no advisory")))

;; ---- once-per-id -----------------------------------------------------------

(deftest once-per-id-on-reregistration
  (testing "re-registering the SAME source-blind id emits the advisory only
            once (dev-only once-per-id de-dup)"
    (rf/reg-machine :src-warn/dup blind-spec)
    (rf/reg-machine :src-warn/dup blind-spec)
    (is (= 1 (count (warns)))
        "one advisory across two registrations of the same id")))

(deftest distinct-ids-each-warn-once
  (testing "two DIFFERENT source-blind ids each warn once — the de-dup is
            per-id, not a single global one-shot"
    (rf/reg-machine :src-warn/a blind-spec)
    (rf/reg-machine :src-warn/b blind-spec)
    (is (= #{:src-warn/a :src-warn/b}
           (set (map #(:machine-id (:tags %)) (warns))))
        "each distinct id gets its own single advisory")))

;; ---- runtime-built (reg-machine*) also warns -------------------------------

(deftest reg-machine-star-runtime-built-warns
  (testing "the plain-fn reg-machine* surface (runtime-built spec) also arrives
            source-blind and warns — the check keys on the OBSERVABLE property,
            not the registration surface. The message tells a runtime-built
            author they may ignore it"
    (machines/reg-machine* :src-warn/plain blind-spec)
    (is (= 1 (count (warns)))
        "a runtime-built spec is source-blind — the advisory fires")))

;; ---- production DCE gate ---------------------------------------------------

(deftest noop-in-production
  (testing "under interop/debug-enabled? false (the production gate) the
            advisory is not emitted — the consult + emit branch DCEs"
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-machine :src-warn/prod blind-spec))
    (is (empty? (warns)) "no advisory in production")))
