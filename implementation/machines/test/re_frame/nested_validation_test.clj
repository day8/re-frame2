(ns re-frame.nested-validation-test
  "Registration-time validation of a machine's whole state tree.

  Per Spec 005, the registration validator walks every state node — top
  level, nested compounds and parallel regions — and every transition-bearing
  slot (`:on`, `:always` in both its vector and single-map forms, `:entry`,
  `:exit`, `:after`, `:on-done`, `:spawn :on-error`). A malformed machine fails
  registration with a documented error id rather than surfacing at the first
  dispatch that reaches the bad node.

  Each table row registers a machine carrying ONE defect at ONE site and
  asserts the error id plus the ex-data key naming the offending value, so a
  failure names the row (via `testing`) and the value the validator reported.
  The last table holds the well-formed controls for the rejection tables."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- registration-error
  "The ex-data of the error `machine` throws at registration, or nil when it
  registers cleanly."
  [machine]
  (try (rf/reg-machine (keyword "rf.nested-validation" (str (gensym "m"))) machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- rejects
  "Assert every `[label machine error-id ex-data-key value]` row fails
  registration with `error-id`, its ex-data carrying `value` under
  `ex-data-key`."
  [rows]
  (doseq [[label machine error-id k v] rows]
    (let [data (registration-error machine)]
      (is (= [error-id v] [(:rf.error/id data) (get data k)]) label))))

;; ---- :guard / :action keyword refs that name no entry ---------------------
;;
;; The validator follows the same `chase-ref` chain the runtime resolver
;; does, so a multi-hop indirection whose terminal hop is missing, or a cyclic
;; one, is rejected here rather than at the first transition that reaches it.
;; The ex-data names the HEAD ref the author wrote.

(deftest dangling-guard-and-action-refs-fail-registration
  (rejects
    [["top-level :on :guard"
      {:initial :idle :guards {} :actions {}
       :states  {:idle {:on {:go [{:target :other :guard :no-such-guard}]}} :other {}}}
      :rf.error/machine-unresolved-guard :guard :no-such-guard]
     ["top-level :on :action"
      {:initial :idle :guards {} :actions {}
       :states  {:idle {:on {:go [{:target :other :action :no-such-action}]}} :other {}}}
      :rf.error/machine-unresolved-action :action :no-such-action]
     ["nested :on :action"
      {:initial :outer :guards {} :actions {}
       :states  {:outer {:initial :inner
                         :states  {:inner {:on {:go [{:target :other :action :no-such-action}]}}
                                   :other {}}}}}
      :rf.error/machine-unresolved-action :action :no-such-action]
     ["top-level :always (vector) :guard"
      {:initial :idle :guards {} :actions {}
       :states  {:idle {:always [{:target :other :guard :no-such-guard}]} :other {}}}
      :rf.error/machine-unresolved-guard :guard :no-such-guard]
     ["top-level :always (single map) :action"
      {:initial :idle :guards {} :actions {}
       :states  {:idle {:always {:target :other :action :no-such-action}} :other {}}}
      :rf.error/machine-unresolved-action :action :no-such-action]
     ["top-level :entry"
      {:initial :idle :guards {} :actions {}
       :states  {:idle {:entry :no-such-action}}}
      :rf.error/machine-unresolved-action :action :no-such-action]
     ["top-level :exit"
      {:initial :idle :guards {} :actions {}
       :states  {:idle {:exit :no-such-action :on {:go :other}} :other {}}}
      :rf.error/machine-unresolved-action :action :no-such-action]
     ["parallel region :on :guard"
      {:type :parallel :guards {} :actions {}
       :regions {:region-a {:initial :a
                            :states  {:a {:on {:go [{:target :b :guard :no-such-guard}]}} :b {}}}
                 :region-b {:initial :x :states {:x {} :y {}}}}}
      :rf.error/machine-unresolved-guard :guard :no-such-guard]
     ["parallel region, compound state nested inside the region"
      {:type :parallel :guards {} :actions {}
       :regions {:region-a {:initial :outer
                            :states  {:outer {:initial :inner
                                              :states  {:inner {:on {:go [{:target :sibling
                                                                           :action :no-such-action}]}}
                                                        :sibling {}}}}}
                 :region-b {:initial :x :states {:x {}}}}}
      :rf.error/machine-unresolved-action :action :no-such-action]
     ["multi-hop :guard indirection dangling at its terminal hop"
      {:initial :idle :guards {:a :b} :actions {}
       :states  {:idle {:on {:go [{:target :other :guard :a}]}} :other {}}}
      :rf.error/machine-unresolved-guard :guard :a]
     ["cyclic :guard indirection (never reaches a fn)"
      {:initial :idle :guards {:a :b :b :a} :actions {}
       :states  {:idle {:on {:go [{:target :other :guard :a}]}} :other {}}}
      :rf.error/machine-unresolved-guard :guard :a]]))

;; ---- an :always that targets its own declaring state ----------------------
;;
;; Per Spec 005 §Self-loop forbidden at registration. Desugaring runs BEFORE
;; the self-loop check, so the bare-keyword and bare-vector shorthands cannot
;; slip past it; the bare vector is the absolute-path sugar, distinct from a
;; vector OF candidate maps. The ex-data names the declaring leaf state.

(deftest always-self-loops-fail-registration
  (rejects
    [["keyword :target naming its own state"
      {:initial :checking
       :guards  {:ready? (fn [_] true)}
       :states  {:checking {:always [{:guard :ready? :target :checking}]}}}
      :rf.error/machine-always-self-loop :state :checking]
     ["bare vector-target :always shorthand"
      {:initial :outer
       :states  {:outer {:initial :inner
                         :states  {:inner {:always [:outer :inner]}}}}}
      :rf.error/machine-always-self-loop :state :inner]
     ["inside a parallel region"
      {:type :parallel
       :guards  {:p? (fn [_] true)}
       :actions {}
       :regions {:region-a {:initial :a :states {:a {:always [{:guard :p? :target :a}]}}}
                 :region-b {:initial :x :states {:x {}}}}}
      :rf.error/machine-always-self-loop :state :a]]))

;; ---- a compound state with no :initial ------------------------------------
;;
;; Per Spec 005 §Initial-state cascading: every compound state-node declares
;; `:initial`. Without it the cascade has no entry point and would yield a
;; non-leaf `:state` snapshot.

(deftest compound-states-missing-initial-fail-registration
  (rejects
    [["top-level compound"
      {:initial :authenticated
       :states  {:authenticated {:states {:dashboard {} :settings {}}}}}
      :rf.error/machine-compound-state-missing-initial :state :authenticated]]))

;; ---- transition :target shape and resolution ------------------------------
;;
;; Per Spec 005 §Transition resolution and Spec-Schemas §TransitionTarget
;; (`[:or :keyword [:vector :keyword]]`): every transition slot's `:target`
;; is well formed and names a declared state. A keyword target names a
;; SIBLING of the declaring state, so a keyword reaching past the immediate
;; parent level does not resolve.

(deftest bad-and-unresolved-targets-fail-registration
  (rejects
    [[":on keyword :target naming no sibling"
      {:initial :idle :states {:idle {:on {:go :nowhere}} :other {}}}
      :rf.error/machine-unresolved-target :target :nowhere]
     ["keyword :target reaching past the parent level"
      {:initial :outer
       :states  {:outer {:initial :mid
                         :states  {:mid {:initial :leaf
                                         :states  {:leaf {:on {:go :elsewhere}}}}
                                   :elsewhere {}}}}}
      :rf.error/machine-unresolved-target :target :elsewhere]
     [":after :target"
      {:initial :idle :states {:idle {:after {1000 :nowhere}} :other {}}}
      :rf.error/machine-unresolved-target :target :nowhere]
     ["compound :on-done :target"
      {:initial :outer
       :states  {:outer {:initial :done
                         :on-done :nowhere
                         :states  {:done {:final? true}}}}}
      :rf.error/machine-unresolved-target :target :nowhere]
     [":spawn :on-error :target"
      {:initial :working
       :states  {:working {:spawn {:machine-id :rf.nested-validation/some-child
                                   :on-error {:target :nowhere}}}
                 :other   {}}}
      :rf.error/machine-unresolved-target :target :nowhere]
     ["parallel region :on :target"
      {:type :parallel
       :regions {:region-a {:initial :a :states {:a {:on {:go :nowhere}} :b {}}}
                 :region-b {:initial :x :states {:x {}}}}}
      :rf.error/machine-unresolved-target :target :nowhere]]))

;; ---- well-formed controls for the rejection tables above ------------------

(deftest well-formed-machines-register-cleanly
  (doseq [[label machine]
          [["internal :always (no :target, just an :action) is not a self-loop"
            {:initial :working
             :guards  {:more? (fn [_] false)}
             :actions {:step (fn [{d :data}] {:data d})}
             :states  {:working {:always [{:guard :more? :action :step}]}}}]
           ["multi-hop :guard indirection terminating at a fn"
            {:initial :idle
             :guards  {:a :b :b :c :c (fn [_] true)}
             :actions {}
             :states  {:idle {:on {:go [{:target :other :guard :a}]}} :other {}}}]
           ["vector :target naming a :type :history pseudo-state"
            {:initial :idle
             :states  {:idle     {:on {:resume [:compound :hist]}}
                       :compound {:initial :a
                                  :states  {:a {} :hist {:type :history}}}}}]]]
    (is (nil? (registration-error machine)) label)))
