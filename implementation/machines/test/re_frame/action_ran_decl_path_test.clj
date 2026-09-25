(ns re-frame.action-ran-decl-path-test
  "Every `:rf.machine/action-ran` trace names the node that DECLARES the
  action under `:decl-path` — per Spec 009 §`:rf.machine/action-ran` — so a
  tool can address a root's own `:entry` / `:exit` rather than borrowing a
  state from the surrounding transition.

  - The machine root's `:entry` / `:exit`: `[]`.
  - A state's `:entry` / `:exit`: that state's path, each level of a
    compound chain its own.
  - A transition `:action`: the path of the state whose table declares it,
    `[]` for the root's `:on`.
  - A parallel region: the path is region-relative and `:region` names the
    region (`[]` for the region body); the parallel root's own `:on` and
    `:on-done` actions are `[]` with no `:region`.

  Live runtime (plain-atom substrate)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- noop [_] nil)

(defn- ran
  "Every captured action-ran as `[action-id phase decl-path region]`."
  []
  (mapv (fn [{{:keys [action-id phase decl-path region]} :tags}]
          [action-id phase decl-path region])
        (rf.machines.test-support/events-of :rf.machine/action-ran)))

(deftest flat-machine-actions-name-their-declaring-node
  (rf/reg-machine :dp/flat
    {:initial :o
     :entry   :root-in
     :exit    :root-out
     :actions {:root-in noop :root-out noop :o-in noop :i-in noop :i-out noop
               :o-out noop :go noop :home noop :b-in noop}
     :on      {:home {:target :b :action :home}}
     :states  {:o {:initial :i
                   :entry   :o-in
                   :exit    :o-out
                   :states  {:i {:entry :i-in :exit :i-out
                                 :on    {:go {:target :i :reenter? true :action :go}}}}}
               :b {:entry :b-in :final? true}}})
  (rf/dispatch-sync [:dp/flat [:rf.machine/start]])
  (rf/dispatch-sync [:dp/flat [:go]])
  (rf/dispatch-sync [:dp/flat [:home]])
  (is (= [[:root-in  :initial-entry []      nil]
          [:o-in     :initial-entry [:o]    nil]
          [:i-in     :initial-entry [:o :i] nil]
          ;; `:go` re-enters `:i` from `:i`'s own table
          [:i-out    :exit          [:o :i] nil]
          [:go       :transition    [:o :i] nil]
          [:i-in     :entry         [:o :i] nil]
          ;; the root's `:on`
          [:i-out    :exit          [:o :i] nil]
          [:o-out    :exit          [:o]    nil]
          [:home     :transition    []      nil]
          [:b-in     :entry         [:b]    nil]
          ;; `:b` is final: the teardown
          [:root-out :destroy-exit  []      nil]]
         (ran))))

(deftest parallel-machine-actions-name-their-declaring-node
  (rf/reg-machine :dp/par
    {:type    :parallel
     :actions {:x-in noop :x1-in noop :root-go noop :done noop}
     :on      {:root-go {:target [:x :x2] :action :root-go}}
     :on-done {:action :done}
     :regions {:x {:initial :x1
                   :entry   :x-in
                   :states  {:x1 {:entry :x1-in}
                             :x2 {:final? true}}}}})
  (rf/dispatch-sync [:dp/par [:rf.machine/start]])
  (rf/dispatch-sync [:dp/par [:root-go]])
  (is (= [[:x-in    :initial-entry []    :x]
          [:x1-in   :initial-entry [:x1] :x]
          [:root-go :transition    []    nil]
          [:done    :transition    []    nil]]
         (ran))))
