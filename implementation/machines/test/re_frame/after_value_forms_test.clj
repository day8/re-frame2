(ns re-frame.after-value-forms-test
  "An `:after` table value admits the same value-form grammar as an `:on`
  clause (Spec 005 §Delayed `:after` transitions, §Multiple-candidate
  transitions): a bare keyword target, a transition map, or a guarded
  candidate vector whose first passing candidate wins, with an unguarded
  candidate as the fallback.

  These drive the pure `machine-transition` with the synthetic
  `[:rf.machine.timer/after-elapsed delay-key epoch decl-path]` event. A timer
  is live when its carried epoch equals its declaring node's
  `:rf/after-epoch` entry."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(defn- fire
  "The `machine-transition` result of `delay-key`'s timer, carried at `epoch`
  from `decl-path`, firing against `snapshot`."
  [spec snapshot delay-key epoch decl-path]
  (rf.machines/machine-transition
    spec snapshot [:rf.machine.timer/after-elapsed delay-key epoch decl-path]))

(defn- at-loading [epoch data]
  {:state :loading :data (assoc data :rf/after-epoch {[:loading] epoch})})

(deftest after-single-candidate-forms-transition
  (doseq [[label after-value] [["(a) a bare keyword target" :timeout]
                               ["(b) a single transition map" {:target :timeout}]]]
    (is (= :timeout
           (-> (fire {:initial :idle
                      :states  {:idle    {:on {:go :loading}}
                                :loading {:after {5000 after-value}}
                                :timeout {}}}
                     (at-loading 1 {}) 5000 1 [:loading])
               :snapshot :state))
        label)))

(deftest after-guarded-vector-resolves-the-first-passing-candidate
  (let [spec {:initial :idle
              :guards  {:handshake-ok? (fn [{:keys [data]}] (:handshake-ok? data))}
              :states  {:idle          {:on {:go :authenticating}}
                        :authenticating
                        {:after {6000 [{:guard :handshake-ok? :target :connected}
                                       {:target :failed :action :record-error}]}}
                        :connected     {}
                        :failed        {}}
              :actions {:record-error (fn [{:keys [data]}]
                                        {:data (assoc data :error :handshake)})}}]
    (doseq [[label handshake-ok? expected]
            [["(e) the first guard passes: its target fires and the fallback's :action does not run"
              true [:connected nil]]
             ["(f) the first guard fails: the unguarded fallback's target fires and its :action runs"
              false [:failed :handshake]]]]
      (let [{snap :snapshot} (fire spec
                                   {:state :authenticating
                                    :data  {:handshake-ok? handshake-ok?
                                            :rf/after-epoch {[:authenticating] 1}}}
                                   6000 1 [:authenticating])]
        (is (= expected [(:state snap) (get-in snap [:data :error])]) label)))))

;; Spec 005 §Multi-stage interaction with :guard: a guard-suppressed timer is
;; fired and discarded — no transition, no epoch advance. A stale one (its
;; node's epoch moved on, as on re-entry) drops the same way.
(deftest after-suppressed-and-stale-firings-leave-the-snapshot-unchanged
  (let [spec {:initial :idle
              :guards  {:never? (fn [_] false)}
              :states  {:idle    {:on {:go :loading}}
                        :loading {:after {5000 {:guard :never? :target :warn}
                                          6000 [{:guard :never? :target :x}
                                                {:guard :never? :target :y}]
                                          7000 :timeout}}
                        :warn    {}
                        :x       {}
                        :y       {}
                        :timeout {}}}]
    (doseq [[label delay-key current-epoch]
            [["(d) a single guarded map whose guard fails" 5000 1]
             ["(g) a guarded candidate vector whose every guard fails, with no fallback" 6000 1]
             ["a timer carried at an epoch its still-active node has moved past" 7000 2]]]
      (let [snap (at-loading current-epoch {})]
        (is (= {:snapshot snap :fx []}
               (select-keys (fire spec snap delay-key 1 [:loading]) [:snapshot :fx]))
            label)))))

;; The carried decl-path routes the firing to its declaring node: a nested
;; child, or a state inside one parallel region (the region-name-prefixed
;; path), where the sibling region declines.
(deftest after-guarded-vector-resolves-at-its-decl-path
  (let [ready {:ready? (fn [_] true)}]
    (doseq [[label spec snap decl-path expected]
            [["a child's guarded vector, at the child decl-path"
              {:initial :p
               :guards  ready
               :states  {:p {:initial :a
                             :after   {30000 :timed-out}
                             :states  {:a      {:after {5000 [{:guard :ready? :target :a-hot}
                                                              {:target :a-warm}]}}
                                       :a-hot  {}
                                       :a-warm {}}}
                         :timed-out {}}}
              {:state [:p :a] :data {:rf/after-epoch {[:p] 1 [:p :a] 1}}}
              [:p :a]
              [:p :a-hot]]
             ["a region's guarded vector, at the region-prefixed decl-path"
              {:type    :parallel
               :guards  ready
               :regions {:net   {:initial :connecting
                                 :states  {:connecting {:after {5000 [{:guard :ready? :target :online}
                                                                      {:target :degraded}]}}
                                           :online     {}
                                           :degraded   {}}}
                         :audio {:initial :muted
                                 :states  {:muted {}}}}}
              {:state {:net :connecting :audio :muted}
               :data  {:rf/after-epoch-by-region {:net {[:connecting] 1} :audio {}}}}
              [:net :connecting]
              {:net :online :audio :muted}]]]
      (is (= expected (-> (fire spec snap 5000 1 decl-path) :snapshot :state)) label))))
