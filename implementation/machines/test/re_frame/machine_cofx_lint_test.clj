(ns re-frame.machine-cofx-lint-test
  "Dev-only consumer-attachment LINTS (`cofx-attach/lint-machine!`, wired
  into the registration home at `lifecycle_fx/registration.cljc:1140` via
  `(lint-machine! machine-id (index-ensure-sets machine))`). Two
  recommendation-grade diagnostics, both DCE'd in production:

    1. `:rf.warning/machine-cofx-ambient-durable` — a named `:actions` entry
       declaring an AMBIENT-grade (`:recordable? false`) cofx id in its
       `:rf.cofx/requires`. Folding a re-run-on-replay ambient read into a
       durable `:data` write is the \"durable state folds facts, never
       reads\" violation, mechanically checkable.

    2. `:rf.warning/machine-cofx-consume-undeclared` — a named guard/action
       whose `:fn` source reads a cofx leaf the entry did NOT declare in
       `:rf.cofx/requires` (a `tree-seq` token scan of the macro-captured
       `:source-code` filtered to qualified keywords that are REGISTERED cofx
       ids and NOT in the declared diet).

  Pre-rf2-hh4069 both diagnostics shipped registration-wired with ZERO test
  coverage. This suite pins them.

  ## rf2-ful212 + rf2-wbh50l (fixed): consume-undeclared alive end-to-end

  Driving the REAL path while writing rf2-hh4069's tests revealed the
  consume-undeclared diagnostic was UNREACHABLE end-to-end — two interacting
  bugs, both since fixed:

    - rf2-wbh50l — the `reg-machine` macro stamps each entry's `:source-code`
      as a `pr-str` STRING (`re-frame.source-coords/walk-element-source` →
      `collocate-element-source`), but the scan treated `:source-code` as a
      FORM (`(tree-seq coll? seq source-code)`). `tree-seq` over a STRING is a
      leaf — no keyword tokens — so the scan found nothing. FIXED:
      `lint-machine!` now parses the string back to a form (safe EDN reader,
      `source-code->form`) before the scan. The `*-form` tests still exercise
      the scan against form-shaped input; `consume-undeclared-fires-against-
      macro-string-source` now asserts the STRING path FIRES.

    - rf2-ful212 — a machine registered as an INLINE LITERAL (or via
      `defmachine`) whose `:guards` / `:actions` entry is the named cofx form
      `{:rf.cofx/requires [...] :fn (fn ...)}` was DOUBLE-WRAPPED by
      `collocate-element-source` (`{:fn <the-whole-entry-map> :source-code
      \"...\"}`), nesting `:rf.cofx/requires` under `:fn`. That emptied the
      cofx-ensure index (`by-entry`) AND broke guard/action resolution (the
      guard's `:fn` was a map, not a fn → the guard never fired). FIXED:
      `collocate-element-source` / `wrap-element-fns` now merge source onto an
      already-shaped entry-map without re-wrapping. (The regression proofs live
      in `machine_cofx_attach_test`; `consume-undeclared-fires-end-to-end-
      through-reg-machine` below proves the lint through the real macro path.)

  The ambient cases still register via the `def`/symbol path (no macro
  stamping); the `-form` consume cases drive `(lint-machine! id (index-ensure-
  sets machine))` directly with a hand-built `:source-code`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.machines :as machines]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  mtest/trace-capture-fixture)

(def ^:private AMBIENT :rf.warning/machine-cofx-ambient-durable)
(def ^:private CONSUME :rf.warning/machine-cofx-consume-undeclared)

(defn- lint!
  "Drive the lint exactly as the registration home does (registration.cljc
  :1140): index the raw machine, then run the lints tagged with `machine-id`."
  [machine-id machine]
  (cofx-attach/lint-machine! machine-id (cofx-attach/index-ensure-sets machine)))

(defn- warns [op] (mtest/events-of op))

;; ===========================================================================
;; 1. ambient-durable — POSITIVE + negatives (works end-to-end today)
;; ===========================================================================

(deftest ambient-durable-fires-for-action-declaring-ambient-cofx
  (testing "an :actions entry declaring an AMBIENT-grade cofx id emits
            :rf.warning/machine-cofx-ambient-durable with the machine-id,
            :actions slot, entry-id, and the offending :rf.cofx/id — driven
            through the REAL registration wiring (reg-machine → lint-machine!)"
    (rf/reg-cofx :lint/ambient {:doc "ambient (default grade — no :recordable?)"}
                 (fn [] :A))
    ;; def/let-bound symbol → NO macro source-stamping → by-entry intact.
    (let [m {:initial :idle
             :data    {}
             :actions {:fold-it {:rf.cofx/requires [:lint/ambient]
                                  :fn (fn [{:keys [data]}] {:data data})}}
             :states  {:idle {:on {:go {:target :done :action :fold-it}}}
                       :done {}}}]
      (rf/reg-machine :lint/ambient-machine m)
      (let [w (warns AMBIENT)]
        (is (= 1 (count w)) "exactly one ambient-durable warning fired")
        (is (= {:machine-id :lint/ambient-machine
                :slot       :actions
                :entry-id   :fold-it
                :rf.cofx/id :lint/ambient}
               (:tags (first w)))
            "the warning names the machine / slot / entry / offending cofx")
        (is (= :warning (:op-type (first w))) "emitted at :warning grade")
        (is (= :warned-and-proceeded (:recovery (first w)))
            "recommendation-grade — warned, never threw")))))

(deftest ambient-durable-silent-for-recordable-action
  (testing "an :actions entry declaring a RECORDABLE cofx id is legitimate
            (recordable facts are replay-safe to fold) → NO warning"
    (rf/reg-cofx :lint/recordable {:recordable? true} (fn [] :R))
    (let [m {:initial :idle
             :data    {}
             :actions {:fold-it {:rf.cofx/requires [:lint/recordable]
                                  :fn (fn [{:keys [data]}] {:data data})}}
             :states  {:idle {:on {:go {:target :done :action :fold-it}}}
                       :done {}}}]
      (rf/reg-machine :lint/recordable-machine m)
      (is (empty? (warns AMBIENT))
          "a recordable action declares a replay-safe fact — no warning"))))

(deftest ambient-durable-only-checks-actions-not-guards
  (testing "the ambient-durable check is scoped to the :actions slot (only an
            ACTION may write durable :data). A GUARD declaring the SAME
            ambient cofx is a pure read, not a durable fold → NO warning"
    (rf/reg-cofx :lint/ambient2 {:doc "ambient"} (fn [] :A))
    (let [m {:initial :idle
             :data    {}
             :guards  {:ok? {:rf.cofx/requires [:lint/ambient2]
                             :fn (fn [_] true)}}
             :states  {:idle {:on {:go {:target :done :guard :ok?}}}
                       :done {}}}]
      (rf/reg-machine :lint/guard-ambient-machine m)
      (is (empty? (warns AMBIENT))
          "an ambient cofx on a GUARD (not an action) does not warn — only
           :actions are checked (a guard cannot write durable :data)"))))

;; ===========================================================================
;; 2. consume-undeclared — the tree-seq token scan
;; ===========================================================================
;;
;; These drive `lint-machine!` directly with a hand-built `:source-code`
;; (the reg-machine macro path cannot deliver a scannable shape — see the ns
;; docstring). The `*-form` cases feed the FORM the scan's own contract
;; documents ("the macro-captured `:source-code` is a quoted form"); the
;; `*-string` case pins the diagnostic's death against the STRING the macro
;; actually stamps.

(deftest consume-undeclared-flags-undeclared-registered-cofx-read-form
  (testing "the scan flags a qualified-keyword token that is a REGISTERED
            cofx id and is NOT in the entry's declared diet, tagging the
            machine / slot / entry / offending :rf.cofx/id"
    (rf/reg-cofx :lint/declared   {:recordable? true} (fn [] :D))
    (rf/reg-cofx :lint/undeclared {:recordable? true} (fn [] :U))
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [:lint/declared]
                           :fn (fn [_] true)
                           ;; the fn reads :lint/undeclared, NOT declared
                           :source-code '(fn [{cofx :rf.cofx}]
                                           (:lint/undeclared cofx))}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
      (lint! :lint/consume-form m)
      (let [w (warns CONSUME)]
        (is (= 1 (count w)) "exactly one consume-undeclared warning")
        (is (= {:machine-id :lint/consume-form
                :slot       :guards
                :entry-id   :g
                :rf.cofx/id :lint/undeclared}
               (:tags (first w)))
            "flags the undeclared registered cofx the fn read")))))

(deftest consume-undeclared-silent-for-declared-read-form
  (testing "a fn reading ONLY the cofx it declared is correct → NO warning"
    (rf/reg-cofx :lint/declared2 {:recordable? true} (fn [] :D))
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [:lint/declared2]
                           :fn (fn [_] true)
                           :source-code '(fn [{cofx :rf.cofx}]
                                           (:lint/declared2 cofx))}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
      (lint! :lint/consume-declared m)
      (is (empty? (warns CONSUME))
          "the read cofx is declared — no consume-undeclared warning"))))

(deftest consume-undeclared-silent-for-non-cofx-keyword-form
  (testing "a qualified-keyword token that is NOT a registered cofx id (an
            ordinary :data / domain key) is skipped — the `registrar/lookup
            :cofx` guard prevents flagging non-cofx keywords"
    (rf/reg-cofx :lint/declared3 {:recordable? true} (fn [] :D))
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [:lint/declared3]
                           :fn (fn [_] true)
                           ;; reads an ordinary domain key, not a cofx
                           :source-code '(fn [{:keys [data]}]
                                           (:some.domain/plain-key data))}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
      (lint! :lint/consume-noncofx m)
      (is (empty? (warns CONSUME))
          "an unregistered qualified keyword is not a cofx — not flagged"))))

(deftest consume-undeclared-skips-sub-source-fact-id-form
  (testing "a sub-valued recordable source `{:rf/sub q :as fact-id}` in the
            requires: reading the FACT-ID is legitimate (it IS declared, and
            is not a registered cofx), so the fact-id and the `:rf/sub`
            marker are skipped — while a genuinely-undeclared registered cofx
            read in the SAME fn still fires (proving the scan ran, not a
            vacuous pass)"
    (rf/reg-cofx :lint/undeclared2 {:recordable? true} (fn [] :U))
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [{:rf/sub [:some/q] :as :lint/fact}]
                           :fn (fn [_] true)
                           ;; reads the sub-source fact-id (OK) AND an
                           ;; undeclared registered cofx (must fire)
                           :source-code '(fn [{cofx :rf.cofx}]
                                           [(:lint/fact cofx)
                                            (:lint/undeclared2 cofx)])}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
      (lint! :lint/consume-sub m)
      (let [ids (set (map #(:rf.cofx/id (:tags %)) (warns CONSUME)))]
        (is (contains? ids :lint/undeclared2)
            "the undeclared registered cofx still fires — the scan ran")
        (is (not (contains? ids :lint/fact))
            "the sub-source :as fact-id is NOT flagged (declared + not a cofx)")))))

;; ---- the string-shaped :source-code the macro actually stamps -------------

(deftest consume-undeclared-fires-against-macro-string-source
  (testing "rf2-wbh50l FIXED: the reg-machine macro stamps `:source-code` as a
            pr-str STRING; the scan now parses it back to a form (via the safe
            EDN reader) before `tree-seq`, so the identical read that fires as
            a FORM (see -form test above) NOW fires as the STRING the macro
            actually stamps. Previously a STRING was a `tree-seq` LEAF → zero
            keyword tokens → the diagnostic was dead against real macro output."
    (rf/reg-cofx :lint/declared4   {:recordable? true} (fn [] :D))
    (rf/reg-cofx :lint/undeclared3 {:recordable? true} (fn [] :U))
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [:lint/declared4]
                           :fn (fn [_] true)
                           ;; the SAME read as the -form positive, but as the
                           ;; STRING the macro actually stamps
                           :source-code "(fn [{cofx :rf.cofx}] (:lint/undeclared3 cofx))"}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
      (lint! :lint/consume-string m)
      (let [w (warns CONSUME)]
        (is (= 1 (count w))
            "the scan now reads the STRING source-code as a form (rf2-wbh50l)")
        (is (= {:machine-id :lint/consume-string
                :slot       :guards
                :entry-id   :g
                :rf.cofx/id :lint/undeclared3}
               (:tags (first w)))
            "flags the undeclared registered cofx the fn read")))))

(deftest consume-undeclared-tolerates-unreadable-string-source
  (testing "an unreadable `:source-code` STRING (an exotic reader macro the
            safe EDN reader chokes on) is a tolerated false-NEGATIVE: the scan
            parses nothing and emits no warning rather than throwing —
            recommendation-grade, never a hard gate (rf2-wbh50l)"
    (rf/reg-cofx :lint/declared5   {:recordable? true} (fn [] :D))
    (rf/reg-cofx :lint/undeclared5 {:recordable? true} (fn [] :U))
    (let [m {:initial :idle
             :data    {}
             :guards  {:g {:rf.cofx/requires [:lint/declared5]
                           :fn (fn [_] true)
                           ;; `#=` eval / unbalanced form the EDN reader rejects
                           :source-code "(fn [] #=(str :lint/undeclared5"}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
      (is (nil? (lint! :lint/consume-unreadable m))
          "lint-machine! returns without throwing on an unreadable source")
      (is (empty? (warns CONSUME))
          "an unreadable source is silently un-scanned, never mis-flagged"))))

;; ---- end-to-end through the REAL reg-machine macro wiring -----------------

(deftest consume-undeclared-fires-end-to-end-through-reg-machine
  (testing "rf2-ful212 + rf2-wbh50l END-TO-END: a named guard registered as an
            INLINE LITERAL via `reg-machine` (the macro stamps :source-code as a
            pr-str STRING and collocates the named-cofx entry) that DECLARES one
            cofx but READS a different undeclared registered cofx now trips
            consume-undeclared through the real registration wiring
            (reg-machine → lint-machine!). This exercises BOTH fixes at once:
            the entry-shape fix (so the ensure-index sees the entry and
            lint-machine! iterates it) AND the string-parse fix (so the scan
            reads the macro-stamped STRING). Before, the diagnostic was
            doubly-dead — empty index + unscannable string."
    (rf/reg-cofx :lint/e2e-declared   {:recordable? true} (fn [] :D))
    (rf/reg-cofx :lint/e2e-undeclared {:recordable? true} (fn [] :U))
    (rf/reg-machine :lint/e2e-consume
      {:initial :idle
       :data    {}
       :guards  {:g {:rf.cofx/requires [:lint/e2e-declared]
                     ;; declares :lint/e2e-declared but READS :lint/e2e-undeclared
                     :fn (fn [{cofx :rf.cofx}]
                           (some? (:lint/e2e-undeclared cofx)))}}
       :states  {:idle {:on {:go {:target :done :guard :g}}}
                 :done {}}})
    (let [ids (set (map #(:rf.cofx/id (:tags %)) (warns CONSUME)))]
      (is (contains? ids :lint/e2e-undeclared)
          "the undeclared registered cofx read is flagged through real macro
           output — the diagnostic is alive end-to-end")
      (is (not (contains? ids :lint/e2e-declared))
          "the DECLARED cofx (present in the :rf.cofx/requires the whole entry
           map pr-strs into) is NOT flagged — it is in the declared diet"))))

;; ===========================================================================
;; 3. production gate — both lints DCE / no-op under debug-enabled? false
;; ===========================================================================

(deftest lint-is-a-noop-in-production
  (testing "under `interop/debug-enabled? false` (the production gate)
            lint-machine! runs no checks and emits nothing — for BOTH a
            machine that would trip ambient-durable and one that would trip
            consume-undeclared"
    (rf/reg-cofx :lint/ambient3   {:doc "ambient"}     (fn [] :A))
    (rf/reg-cofx :lint/undeclared4 {:recordable? true} (fn [] :U))
    (let [ambient-m {:initial :idle :data {}
                     :actions {:fold {:rf.cofx/requires [:lint/ambient3]
                                      :fn (fn [{:keys [data]}] {:data data})}}
                     :states  {:idle {:on {:go {:target :done :action :fold}}}
                               :done {}}}
          consume-m {:initial :idle :data {}
                     :guards  {:g {:rf.cofx/requires [:lint/undeclared4]
                                   :fn (fn [_] true)
                                   :source-code '(fn [{cofx :rf.cofx}]
                                                   (:lint/undeclared4 cofx))}}
                     :states  {:idle {:on {:go {:target :done :guard :g}}}
                               :done {}}}]
      (with-redefs [interop/debug-enabled? false]
        (lint! :prod/ambient ambient-m)
        (lint! :prod/consume consume-m))
      (is (empty? (warns AMBIENT))  "no ambient-durable warning in production")
      (is (empty? (warns CONSUME)) "no consume-undeclared warning in production"))))
