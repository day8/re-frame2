(ns re-frame.scxml-conformance-cljs-test
  "SCXML / Harel semantic-core conformance of the pure machine engine
  (`machine-transition`, the finality predicates, the registration
  validator), dual-runtime. Self-transitions follow XState v5 (internal by
  default, external via `:reenter? true`), a deliberate divergence from
  SCXML's external default. The W3C IRP coverage matrix and the excluded
  datamodel / send-invoke families are in `scxml_irp_semantic_core_cljs_test`;
  the Mode-B fixtures under `spec/conformance/fixtures/` cover the rest."
  (:require
   #?(:clj  [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer-macros [deftest is]])
   [re-frame.machines :as rf.machines]
   [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize])
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(defn- step
  "The post-macrostep snapshot of `event` applied to `snapshot`."
  [machine snapshot event]
  (:snapshot (rf.machines/machine-transition machine snapshot event)))

(defn- order-recorder
  "`[log mk]`: `(mk tag)` is an action that appends `tag` to `log`."
  []
  (let [log (atom [])]
    [log (fn [tag] (fn [_ctx] (swap! log conj tag) nil))]))

;; ---- Transition selection (SCXML §3.13) ------------------------------------

(deftest scxml-transition-selection-first-matching-candidate-wins
  (let [m {:initial :a :data {}
           :guards  {:no  (fn [_] false)
                     :yes (fn [_] true)}
           :states  {:a {:on {:ev [{:guard :no  :target :x}
                                    {:guard :yes :target :y}
                                    {:target :z}]}}
                     :x {} :y {} :z {}}}]
    (is (= :y (:state (step m {:state :a :data {}} [:ev])))
        "the first candidate in document order whose guard passes is taken")))

;; ---- Entry/exit ordering along the LCCA ------------------------------------

(deftest scxml-lca-cascade-exit-deepest-first-enter-shallowest-first
  (let [[log mk] (order-recorder)
        m {:initial :p :data {}
           :states
           {:p {:entry (mk :entry-P) :exit (mk :exit-P)
                :initial :a
                :states
                {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                     :initial :x
                     :states
                     {:x {:entry (mk :entry-X) :exit (mk :exit-X)
                          :on {:go {:target [:p :b :y] :action (mk :ACTION)}}}}}
                 :b {:entry (mk :entry-B) :exit (mk :exit-B)
                     :initial :y
                     :states
                     {:y {:entry (mk :entry-Y) :exit (mk :exit-Y)}}}}}}}
        s (step m {:state [:p :a :x] :data {}} [:go])]
    (is (= [[:p :b :y] [:exit-X :exit-A :ACTION :entry-B :entry-Y]]
           [(:state s) @log])
        "exit deepest-first, action at the LCA, enter shallowest-first; the LCA :p neither exits nor enters")))

(deftest scxml-initial-cascade-enters-every-level-shallowest-first
  (let [[log mk] (order-recorder)
        m {:initial :start :data {}
           :states
           {:start {:on {:enter [:outer]}}
            :outer {:entry   (mk :outer)
                    :initial :mid
                    :states
                    {:mid {:entry   (mk :mid)
                           :initial :leaf
                           :states  {:leaf {:entry (mk :leaf)}}}}}}}
        s (step m {:state :start :data {}} [:enter])]
    (is (= [[:outer :mid :leaf] [:outer :mid :leaf]] [(:state s) @log]))))

;; ---- Parallel regions -------------------------------------------------------

(deftest scxml-parallel-done-when-every-region-final
  (let [m {:type :parallel :data {}
           :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                     :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}}
        s (step m {:state {:left :run :right :run} :data {}} [:fin])]
    (is (= [{:left :done :right :done} true]
           [(:state s) (rf.machines.lifecycle-fx.finalize/all-regions-final? m (:state s))]))))

(deftest scxml-parallel-region-ancestor-restart-is-region-local
  (let [[log mk] (order-recorder)
        m {:type :parallel :data {}
           :regions
           {:left {:initial :grp
                   :states
                   {:grp {:entry (mk :entry-grp) :exit (mk :exit-grp)
                          :initial :one
                          :on {:reset {:target [:grp] :reenter? true :action (mk :L-action)}}
                          :states {:one {:entry (mk :entry-1) :exit (mk :exit-1)
                                         :on {:adv :two}}
                                   :two {:entry (mk :entry-2) :exit (mk :exit-2)}}}}}
            :right {:initial :idle :states {:idle {:entry (mk :entry-R)} :busy {}}}}}
        s (step m {:state {:left [:grp :two] :right :idle} :data {}} [:reset])]
    (is (= [{:left [:grp :one] :right :idle}
            [:exit-2 :exit-grp :L-action :entry-grp :entry-1]]
           [(:state s) @log])
        "only :left's subtree restarts; :right is untouched")))

;; ---- done.state.<id> (SCXML §3.7) -------------------------------------------

(deftest scxml-compound-done-handled-by-ancestor-on-clause
  ;; No :on-done on the done node, so the ancestor's explicit
  ;; :on {:rf.machine/done …} takes it; its guard reads the raised path.
  (let [m {:initial :outer :data {}
           :guards {:flow-done? (fn [{ev :event}] (= [:outer :flow] (second ev)))}
           :states
           {:outer {:initial :flow
                    :on {:rf.machine/done {:guard  :flow-done?
                                           :target [:elsewhere]}}
                    :states {:flow {:initial :step
                                    :states  {:step {:on {:finish :inner-done}}
                                              :inner-done {:final? true}}}}}
            :elsewhere {}}}]
    (is (= [:elsewhere] (:state (step m {:state [:outer :flow :step] :data {}} [:finish]))))))

(deftest scxml-compound-NOT-done-when-child-non-final
  (let [m {:initial :flow :data {}
           :states
           {:flow {:initial :step
                   :on-done :next
                   :states  {:step {:on {:advance :step2}}
                             :step2 {}
                             :inner-done {:final? true}}}
            :next {}}}]
    (is (= [:flow :step2] (:state (step m {:state [:flow :step] :data {}} [:advance])))
        "landing on a non-final child does not fire :on-done")))

(deftest scxml-nested-compound-done-propagates
  (let [m {:initial :outer :data {}
           :states
           {:outer {:initial :inner
                    :states {:inner {:initial :leaf
                                     :on-done :inner-next
                                     :states {:leaf {:on {:finish :fin}}
                                              :fin  {:final? true}}}
                             :inner-next {}}}}}]
    (is (= [:outer :inner-next] (:state (step m {:state [:outer :inner :leaf] :data {}} [:finish])))
        "only the final leaf's immediate parent is done; the grandparent stays active")))

;; A region's done is scoped by region IDENTITY: a sibling region whose
;; compound shares the done compound's name must not catch it.

(deftest scxml-parallel-region-done-arm2-not-caught-by-sibling-shared-state-name
  (let [m {:type :parallel :data {}
           :regions
           {:work {:initial :flow
                   :on {:rf.machine/done :work-done}
                   :states {:flow {:initial :step
                                   :states {:step {:on {:finish :inner-done}}
                                            :inner-done {:final? true}}}
                            :work-done {}}}
            :other {:initial :flow
                    :on {:rf.machine/done :hijacked}
                    :states {:flow {:initial :wait
                                    :states {:wait {}}}
                             :hijacked {}}}}}]
    (is (= {:work [:work-done] :other [:flow :wait]}
           (:state (step m {:state {:work [:flow :step] :other [:flow :wait]} :data {}}
                         [:finish]))))))

(deftest scxml-parallel-region-done-arm1-not-caught-by-sibling-shared-state-name
  (let [m {:type :parallel :data {}
           :regions
           {:work {:initial :flow
                   :states {:flow {:initial :step
                                   :on-done :work-done
                                   :states {:step {:on {:finish :inner-done}}
                                            :inner-done {:final? true}}}
                            :work-done {}}}
            :other {:initial :flow
                    :states {:flow {:initial :wait
                                    :on-done :hijacked
                                    :states {:wait {}}}
                             :hijacked {}}}}}]
    (is (= {:work [:work-done] :other [:flow :wait]}
           (:state (step m {:state {:work [:flow :step] :other [:flow :wait]} :data {}}
                         [:finish]))))))

;; ---- Eventless :always (SCXML §3.13 macrostep) -------------------------------

(deftest scxml-eventless-settles-to-fixed-point
  (let [m {:initial :a :data {:n 0}
           :guards  {:more? (fn [{d :data}] (< (:n d) 3))}
           :actions {:bump  (fn [{d :data}] {:data (update d :n inc)})}
           :states  {:a {:always [{:guard :more? :action :bump}]}}}
        s (step m {:state :a :data {:n 0}} [:kick])]
    (is (= [:a 3] [(:state s) (get-in s [:data :n])])
        "the targetless :always loops within one macrostep until its guard is false")))

(deftest scxml-eventless-deepest-first-in-compound
  ;; Both levels' :always are enabled; the leaf's action disables the shared
  ;; guard, so landing at :outer-redirect would mean the ancestor went first.
  (let [m {:initial :outer :data {:go? true}
           :guards  {:go? (fn [{d :data}] (true? (:go? d)))}
           :actions {:stop (fn [{d :data}] {:data (assoc d :go? false)})}
           :states  {:outer {:always [{:guard :go? :target :outer-redirect}]
                             :initial :leaf
                             :states  {:leaf {:always [{:guard  :go?
                                                        :target :leaf-redirect
                                                        :action :stop}]}
                                       :leaf-redirect {}}}
                     :outer-redirect {}}}]
    (is (= [:outer :leaf-redirect] (:state (step m {:state [:outer :leaf] :data {:go? true}} [:kick]))))))

;; ---- Guards and unhandled events --------------------------------------------

(deftest scxml-guard-false-blocks-transition
  (let [m {:initial :a :data {:allowed? false}
           :guards  {:allowed? (fn [{d :data}] (true? (:allowed? d)))}
           :states  {:a {:on {:go {:guard :allowed? :target :b}}}
                     :b {}}}]
    (is (= [:a :b]
           [(:state (step m {:state :a :data {:allowed? false}} [:go]))
            (:state (step m {:state :a :data {:allowed? true}} [:go]))]))))

(deftest scxml-unhandled-event-is-noop-not-error
  ;; xstate-v5 parity: an event no state handles is ignored, never an error.
  (let [m {:initial :a :data {:k 1}
           :states  {:a {:on {:real :b}} :b {}}}
        r (rf.machines/machine-transition m {:state :a :data {:k 1}} [:never-declared])]
    (is (= [:ok {:state :a :data {:k 1}} []]
           [(:status r) (:snapshot r) (vec (:fx r))]))))

;; ---- Self-transitions and on-path targets (XState v5) ------------------------
;;
;; Targetless: action only, descendants preserved. Explicit self / declaring-
;; state target without :reenter?: the target stands, its active descendants
;; re-resolve to :initial. :reenter? true: the target exits and re-enters.
;; A target that is a proper ancestor of the declaring state restarts that
;; ancestor with or without :reenter? (the SCXML LCCA rule).

(deftest scxml-default-self-transition-is-internal
  (doseq [target [:same-state :a]]
    (let [[log mk] (order-recorder)
          m {:initial :a :data {}
             :states {:a {:entry (mk :entry) :exit (mk :exit)
                          :on {:self {:target target :action (mk :action)}}}}}]
      (is (= [:a [:action]] [(:state (step m {:state :a :data {}} [:self])) @log])
          (str "self target " target " without :reenter? fires only the action")))))

(deftest scxml-child-declared-ancestor-target-exits-and-re-enters-the-ancestor
  (let [[log mk] (order-recorder)
        m {:initial :p :data {}
           :states
           {:p {:entry (mk :entry-P) :exit (mk :exit-P)
                :initial :a
                :states
                {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                     :initial :one
                     :states
                     {:one {:entry (mk :entry-1) :exit (mk :exit-1)}
                      :two {:entry (mk :entry-2) :exit (mk :exit-2)
                            :on {:touch {:target [:p :a] :action (mk :ACTION)}}}}}}}}}
        s (step m {:state [:p :a :two] :data {}} [:touch])]
    (is (= [[:p :a :one] [:exit-2 :exit-A :ACTION :entry-A :entry-1]] [(:state s) @log])
        "declared on the leaf :two, targeting its ancestor :a: :a exits, re-enters and re-descends"))
  (let [[log mk] (order-recorder)
        m {:initial :p :data {}
           :states
           {:p {:entry (mk :entry-P) :exit (mk :exit-P)
                :initial :a
                :states
                {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                     :initial :one
                     :on {:restart-p {:target [:p] :action (mk :ACTION)}}
                     :states
                     {:one {:entry (mk :entry-1) :exit (mk :exit-1)}
                      :two {:entry (mk :entry-2) :exit (mk :exit-2)}}}}}}}
        s (step m {:state [:p :a :two] :data {}} [:restart-p])]
    (is (= [[:p :a :one] [:exit-2 :exit-A :exit-P :ACTION :entry-P :entry-A :entry-1]]
           [(:state s) @log])
        "declared on the compound :a, targeting its ancestor :p: :p restarts too")))

(deftest scxml-ancestor-declared-target-keeps-the-ancestor
  ;; The control for the test above: declared ON :a, targeting :a itself.
  (let [[log mk] (order-recorder)
        m {:initial :p :data {}
           :states
           {:p {:entry (mk :entry-P) :exit (mk :exit-P)
                :initial :a
                :states
                {:a {:entry (mk :entry-A) :exit (mk :exit-A)
                     :initial :one
                     :on {:touch {:target [:p :a] :action (mk :ACTION)}}
                     :states
                     {:one {:entry (mk :entry-1) :exit (mk :exit-1)}
                      :two {:entry (mk :entry-2) :exit (mk :exit-2)}}}}}}}
        s (step m {:state [:p :a :two] :data {}} [:touch])]
    (is (= [[:p :a :one] [:exit-2 :ACTION :entry-1]] [(:state s) @log])
        "only the active descendant exits and :a's :initial re-descends; :a and :p stand")))

(deftest scxml-external-transition-to-ancestor-via-same-state-on-ancestor
  (let [[log mk] (order-recorder)
        m {:initial :session :data {}
           :states
           {:session
            {:entry (mk :entry-session) :exit (mk :exit-session)
             :initial :active
             :on {:reauth {:target :same-state :reenter? true :action (mk :renew)}}
             :states
             {:active {:entry (mk :entry-active) :exit (mk :exit-active)}}}}}
        s (step m {:state [:session :active] :data {}} [:reauth])]
    (is (= [[:session :active] [:exit-active :exit-session :renew :entry-session :entry-active]]
           [(:state s) @log]))))

(deftest scxml-explicit-current-compound-target-re-resolves-to-initial
  (let [[log mk] (order-recorder)
        m {:initial :process :data {}
           :states
           {:process
            {:entry (mk :entry-process) :exit (mk :exit-process)
             :initial :step1
             :on {:resolve {:target :process :action (mk :ACTION)}}
             :states
             {:step1 {:entry (mk :entry-1) :exit (mk :exit-1)}
              :step2 {:entry (mk :entry-2) :exit (mk :exit-2)}
              :step3 {:entry (mk :entry-3) :exit (mk :exit-3)}}}}}
        s (step m {:state [:process :step3] :data {}} [:resolve])]
    (is (= [[:process :step1] [:exit-3 :ACTION :entry-1]] [(:state s) @log])
        "the active child re-resolves to :initial; :process is not exited")))

(deftest scxml-child-declared-sibling-reenter-does-NOT-re-enter-parent
  ;; :reenter? is a no-op for a disjoint-subtree target.
  (let [[log mk] (order-recorder)
        m {:initial :editor :data {}
           :states
           {:editor
            {:entry (mk :entry-editor) :exit (mk :exit-editor)
             :initial :draft
             :states
             {:draft   {:entry (mk :entry-draft) :exit (mk :exit-draft)
                        :on {:to-preview {:target [:editor :preview]
                                          :reenter? true :action (mk :ACTION)}}}
              :preview {:entry (mk :entry-preview) :exit (mk :exit-preview)}}}}}
        s (step m {:state [:editor :draft] :data {}} [:to-preview])]
    (is (= [[:editor :preview] [:exit-draft :ACTION :entry-preview]] [(:state s) @log]))))

(deftest scxml-targetless-on-compound-preserves-active-descendants
  (let [[log mk] (order-recorder)
        m {:initial :process :data {:n 0}
           :states
           {:process
            {:entry (mk :entry-process) :exit (mk :exit-process)
             :initial :step1
             :on {:tick {:action :bump}}
             :states
             {:step1 {:entry (mk :entry-1) :exit (mk :exit-1)}
              :step3 {:entry (mk :entry-3) :exit (mk :exit-3)}}}}
           :actions {:bump (fn [{d :data}] (swap! log conj :action)
                             {:data (update d :n inc)})}}
        s (step m {:state [:process :step3] :data {:n 0}} [:tick])]
    (is (= [[:process :step3] 1 [:action]] [(:state s) (get-in s [:data :n]) @log])
        "the action runs with no exit/entry and the non-initial child is kept")))

;; ---- History (SCXML §3.10) ---------------------------------------------------
;; The W3C 387/579/580 adaptations run as Mode-B fixtures
;; (`spec/conformance/fixtures/scxml-history-test*.edn`).

(defn- history-rejection-id
  "The `:rf.error/id` `validate-machine!` throws for `machine`, or `::no-throw`."
  [machine]
  (try
    (rf.machines/validate-machine! machine)
    ::no-throw
    (catch #?(:clj ExceptionInfo :cljs :default) e
      (:rf.error/id (ex-data e)))))

(deftest scxml-history-grammar-violations-rejected
  (is (= {:region-direct       :rf.error/machine-history-misplaced
          :extra-keys          :rf.error/machine-history-extra-keys
          :duplicate           :rf.error/machine-history-duplicate
          :bad-default-target  :rf.error/machine-history-bad-default-target}
         (update-vals
           {:region-direct      {:type    :parallel
                                 :regions {:r {:type :history :initial :a :states {:a {}}}}}
            :extra-keys         {:initial :c
                                 :states  {:c {:initial :a
                                               :states  {:a {}
                                                         :h {:type :history :on {:go :a}}}}}}
            :duplicate          {:initial :s0
                                 :states  {:s0 {:initial :s01
                                                :states  {:s01          {}
                                                          :s02          {}
                                                          :hist-deep    {:type :history :deep? true}
                                                          :hist-shallow {:type :history}}}}}
            :bad-default-target {:initial :s0
                                 :states  {:s0 {:initial :s01
                                                :states  {:s01  {}
                                                          :hist {:type :history :default-target :nonexistent}}}}}}
           history-rejection-id))))

(deftest scxml-history-test388-deep-restores-exact-leaf
  (let [m {:initial :s0
           :states  {:s0   {:initial :s01
                            :on      {:leave :away}
                            :states  {:s01  {:initial :s011 :states {:s011 {} :s012 {}}}
                                      :s02  {:initial :s021 :states {:s021 {} :s022 {}}}
                                      :hist {:type :history :deep? true :default-target :s022}}}
                     :away {:on {:return [:s0 :hist]}}}}
        left (step m {:state [:s0 :s01 :s012] :data {}} [:leave])]
    (is (= [[:s0 :s01 :s012] [:s0 :s01 :s012]]
           [(get-in left [:rf/history [:s0]]) (:state (step m left [:return]))])
        "deep history records and restores the exact leaf")))

(deftest scxml-history-test388-shallow-restores-child-then-initial
  (let [m {:initial :s0
           :states  {:s0   {:initial :s01
                            :on      {:leave :away}
                            :states  {:s01  {:initial :s011 :states {:s011 {} :s012 {}}}
                                      :s02  {:initial :s021 :states {:s021 {} :s022 {}}}
                                      :hist {:type :history :default-target :s02}}}
                     :away {:on {:return [:s0 :hist]}}}}
        left (step m {:state [:s0 :s01 :s012] :data {}} [:leave])]
    (is (= [:s01 [:s0 :s01 :s011]]
           [(get-in left [:rf/history [:s0]]) (:state (step m left [:return]))])
        "shallow history records the direct child and restores it through its :initial")))
