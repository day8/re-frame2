(ns re-frame.on-spawn-ref-validation-test
  "rf2-b4qbx0 — an `:on-spawn` KEYWORD ref resolves through the machine's
  `:on-spawn-actions` map, falling back to `:actions`
  (`transition/apply-on-spawn`'s `(or (chase-ref (:on-spawn-actions
  machine) aref) (chase-ref (:actions machine) aref))`). BEFORE this fix,
  registration validated the `:spawn` / `:spawn-all` SHAPE (machine-id xor
  definition, unknown keys, …) but never chased the `:on-spawn` ref itself
  — a dangling ref (a typo, a retired action name, a broken multi-hop
  indirection, a cycle) registered cleanly, and at runtime `apply-on-spawn`
  treats the nil resolution EXACTLY like a genuinely-absent `:on-spawn`:
  the spawn completes silently, and the intended callback just never runs.

  `validate-on-spawn-ref!` closes the gap: it follows the FULL `chase-ref`
  indirection chain (through EITHER registry, in that order), exactly as
  the sibling `:guard` / `:action` registration-time checks already do.

  This suite pins:
   1. a dangling keyword :on-spawn ref (neither registry has it) fails at
      REGISTRATION with :rf.error/machine-unresolved-on-spawn;
   2. a ref that resolves via :on-spawn-actions registers fine;
   3. a ref that resolves via the :actions FALLBACK (absent from
      :on-spawn-actions) registers fine;
   4. an inline fn :on-spawn needs no resolution and registers fine;
   5. a :spawn-all CHILD's dangling :on-spawn ref is caught too, tagged
      :spawn-all-child;
   6. a single-hop keyword INDIRECTION within :on-spawn-actions resolves;
   7. a CYCLIC indirection (`:a` -> `:a`) is treated as unresolved, exactly
      like the runtime `chase-ref`'s cycle guard."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- registration-throws?
  "Try registering `machine` under `machine-id`. Returns the ExceptionInfo
  if registration threw, else nil."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e e)))

(def ^:private child-def
  "A trivial spawn target — shape is irrelevant to these tests."
  {:initial :running :data {} :states {:running {}}})

;; ---- (1) dangling keyword :on-spawn ref -------------------------------------

(deftest dangling-on-spawn-ref-rejected-at-registration
  (testing "a :spawn's :on-spawn keyword that resolves against NEITHER registry fails registration"
    (let [parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :rf.on-spawn-tv/child
                                               :on-spawn   :no-such-callback}}}}]
      (rf/reg-machine :rf.on-spawn-tv/child child-def)
      (let [thrown (registration-throws? :rf.on-spawn-tv/dangling parent)]
        (is (some? thrown)
            "a dangling :on-spawn ref SHOULD fail registration, not silently no-op at runtime")
        (is (= :rf.error/machine-unresolved-on-spawn (:rf.error/id (ex-data thrown)))
            "error category names the unresolved-on-spawn contract")
        (is (= :no-such-callback (:on-spawn (ex-data thrown))))
        (is (= :spawn (:where (ex-data thrown))))))))

;; ---- (2) resolves via :on-spawn-actions ------------------------------------

(deftest on-spawn-ref-resolving-via-on-spawn-actions-registers
  (testing "an :on-spawn ref found in :on-spawn-actions registers fine"
    (let [parent {:initial          :idle
                  :on-spawn-actions {:record (fn [_ctx] nil)}
                  :states           {:idle    {:on {:start :working}}
                                     :working {:spawn {:machine-id :rf.on-spawn-tv/child2
                                                        :on-spawn   :record}}}}]
      (rf/reg-machine :rf.on-spawn-tv/child2 child-def)
      (rf/reg-machine :rf.on-spawn-tv/via-on-spawn-actions parent)
      (rf/dispatch-sync [:rf.on-spawn-tv/via-on-spawn-actions [:start]])
      (is (= :working (:state (mtest/snapshot :rf.on-spawn-tv/via-on-spawn-actions)))
          "registration + dispatch both succeed — the spawn-bearing state was entered"))))

;; ---- (3) resolves via the :actions FALLBACK --------------------------------

(deftest on-spawn-ref-resolving-via-actions-fallback-registers
  (testing "an :on-spawn ref ABSENT from :on-spawn-actions but present in :actions resolves via the fallback"
    (let [observed (atom nil)
          parent {:initial :idle
                  ;; NO :on-spawn-actions at all — the ref must fall back
                  ;; to :actions, mirroring apply-on-spawn's `(or (chase-ref
                  ;; on-spawn-actions …) (chase-ref actions …))`.
                  :actions {:record (fn [{:keys [id]}] (reset! observed id) nil)}
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :rf.on-spawn-tv/child3
                                               :on-spawn   :record}}}}]
      (rf/reg-machine :rf.on-spawn-tv/child3 child-def)
      (rf/reg-machine :rf.on-spawn-tv/via-actions-fallback parent)
      (rf/dispatch-sync [:rf.on-spawn-tv/via-actions-fallback [:start]])
      (is (= :rf.on-spawn-tv/child3#1 @observed)
          "the :actions-fallback-resolved callback actually ran at spawn time"))))

;; ---- (4) inline fn :on-spawn needs no resolution ---------------------------

(deftest inline-fn-on-spawn-needs-no-resolution
  (testing "an inline fn :on-spawn registers fine — no keyword ref to chase"
    (let [observed (atom nil)
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :rf.on-spawn-tv/child4
                                               :on-spawn   (fn [{:keys [id]}] (reset! observed id) nil)}}}}]
      (rf/reg-machine :rf.on-spawn-tv/child4 child-def)
      (rf/reg-machine :rf.on-spawn-tv/inline-fn parent)
      (rf/dispatch-sync [:rf.on-spawn-tv/inline-fn [:start]])
      (is (= :rf.on-spawn-tv/child4#1 @observed)
          "the inline fn ran"))))

;; ---- (5) :spawn-all child's dangling :on-spawn -----------------------------

(deftest spawn-all-child-dangling-on-spawn-rejected
  (testing "a :spawn-all child's dangling :on-spawn ref fails registration, tagged :spawn-all-child"
    (let [gc-x   {:initial :running :data {} :states {:running {}}}
          gc-y   {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:fork :forking}}
                            :forking {:spawn-all
                                      {:children        [{:id :x :machine-id :rf.on-spawn-tv/gc-x
                                                          :on-spawn :missing-cb}
                                                         {:id :y :machine-id :rf.on-spawn-tv/gc-y}]
                                       :join            :all
                                       :on-child-done   :gc/done
                                       :on-child-error  :gc/failed
                                       :on-all-complete [:all/done]}}}}]
      (rf/reg-machine :rf.on-spawn-tv/gc-x gc-x)
      (rf/reg-machine :rf.on-spawn-tv/gc-y gc-y)
      (let [thrown (registration-throws? :rf.on-spawn-tv/spawn-all-dangling parent)]
        (is (some? thrown) "a dangling :spawn-all child :on-spawn ref SHOULD fail registration")
        (is (= :rf.error/machine-unresolved-on-spawn (:rf.error/id (ex-data thrown))))
        (is (= :spawn-all-child (:where (ex-data thrown))))
        (is (= :missing-cb (:on-spawn (ex-data thrown))))))))

;; ---- (6) single-hop indirection within :on-spawn-actions resolves ---------

(deftest on-spawn-ref-single-hop-indirection-resolves
  (testing "an :on-spawn ref that indirects ONE hop within :on-spawn-actions before hitting a fn resolves"
    (let [parent {:initial          :idle
                  :on-spawn-actions {:short :long
                                     :long  (fn [_ctx] nil)}
                  :states           {:idle    {:on {:start :working}}
                                     :working {:spawn {:machine-id :rf.on-spawn-tv/child6
                                                        :on-spawn   :short}}}}]
      (rf/reg-machine :rf.on-spawn-tv/child6 child-def)
      (is (nil? (registration-throws? :rf.on-spawn-tv/indirection parent))
          "a resolvable single-hop indirection registers cleanly"))))

;; ---- (7) cyclic indirection is treated as unresolved -----------------------

(deftest on-spawn-ref-cycle-rejected
  (testing "a CYCLIC :on-spawn-actions indirection (:a -> :a) is unresolved, like the runtime chase-ref"
    (let [parent {:initial          :idle
                  :on-spawn-actions {:a :a}
                  :states           {:idle    {:on {:start :working}}
                                     :working {:spawn {:machine-id :rf.on-spawn-tv/child7
                                                        :on-spawn   :a}}}}]
      (rf/reg-machine :rf.on-spawn-tv/child7 child-def)
      (let [thrown (registration-throws? :rf.on-spawn-tv/cycle parent)]
        (is (some? thrown) "a cyclic :on-spawn-actions indirection fails registration")
        (is (= :rf.error/machine-unresolved-on-spawn (:rf.error/id (ex-data thrown))))))))
