(ns re-frame.final-region-sourcing-test
  "A parallel region resting on a `:final?` leaf does not halt (Spec 005
  §`:final?` constraints, Parallel regions and `:final?`): a transition on an
  ancestor of the leaf, or on the region body's root `:on`, still selects for
  the region and moves it off the leaf. So a completed region still competes
  with the parallel root's `:on` and suppresses it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- states-after
  "Register `machine` as `id`, dispatch each of `events` to it, and return
  the `:state` after each."
  [id machine events]
  (rf/reg-machine id machine)
  (mapv (fn [ev]
          (rf/dispatch-sync [id ev])
          (rf.machines.test-support/machine-state id))
        events))

(deftest final-region-sources-always-via-ancestor
  ;; No event reaches :mode: its ancestor's guarded :always fires in the
  ;; eventless round after the LIVE :work region moves. The target escapes
  ;; :phase, because an :always re-entering its own source loops to the depth limit.
  (is (= [{:mode [:phase :done] :work :live}
          {:mode [:escaped] :work :armed}]
         (states-after
           :hu69/anc-always
           {:type   :parallel
            :guards {:work-armed? (fn [{:keys [all-state]}] (= :armed (:work all-state)))}
            :regions
            {:mode {:initial :phase
                    :states  {:phase   {:initial :working
                                        :always  [{:target :escaped :guard :work-armed?}]
                                        :states  {:working {:on {:finish {:target :done}}}
                                                  :done    {:final? true}}}
                              :escaped {}}}
             :work {:initial :live
                    :states  {:live  {:on {:arm {:target :armed}}}
                              :armed {}}}}}
           [[:finish] [:arm]]))))

(deftest final-region-sources-after-via-ancestor
  ;; A leaf-only move onto the final sibling keeps :phase on the active path
  ;; with its epoch unchanged, so its :after is still live.
  (is (= [{:mode [:phase :done] :work :live}
          {:mode [:phase :expired] :work :live}]
         (states-after
           :hu69/anc-after
           {:type :parallel
            :regions
            {:mode {:initial :phase
                    :states  {:phase {:initial :working
                                      :after   {1000 {:target [:phase :expired]}}
                                      :states  {:working {:on {:finish {:target :done}}}
                                                :done    {:final? true}
                                                :expired {}}}}}
             :work {:initial :live
                    :states  {:live {}}}}}
           [[:finish] [:rf.machine.timer/after-elapsed 1000 1 [:mode :phase]]]))))

(deftest root-transition-suppressed-by-final-region
  ;; The completed :mode region takes :bump through its body's root :on, so
  ;; the root transition that would have rescued the LIVE :work region is
  ;; suppressed entirely.
  (is (= [{:mode :done :work :live}
          {:mode :working :work :live}]
         (states-after
           :hu69/suppress
           {:type :parallel
            :on   {:bump {:target [:work :rescued]}}
            :regions
            {:mode {:initial :working
                    :on      {:bump {:target :working}}
                    :states  {:working {:on {:finish {:target :done}}}
                              :done    {:final? true}}}
             :work {:initial :live
                    :states  {:live    {}
                              :rescued {}}}}}
           [[:finish] [:bump]]))))
