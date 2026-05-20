(ns day8.re-frame2-causa.test-helpers.host-fixtures.deep-machine
  "Headless host fixture exercising a real machine — narrowed from the
  full `testbeds/deep_machine/core.cljs` to two states + one
  transition. The point of this fixture is to drive a real
  `:rf.machine/transition` trace event through the framework's
  machines plumbing, so Causa's Machine Inspector + Machines tab subs
  have real data to project.

  ## Why the narrow surface

  The full deep-machine testbed declares parallel regions,
  hierarchical compound 5-deep, `:always`, `:after`, `:spawn`,
  `:spawn-all` — every Spec 005 grammar surface. That's important
  for visual diff coverage; for the e2e helper's bug-catching
  contract (does the `:frame` tag survive into Causa's epoch
  capture?) a single flat transition is enough.

  Tests that need richer machine grammar should add a sibling
  fixture (e.g. `deep-machine-full/install!`) once the e2e harness
  proves out on this narrower surface.

  ## Bug class this catches

  rf2-hwuki — `:rf.machine/transition` emit dropped the `:frame`
  tag, which meant Causa's epoch-capture filter rejected the event
  (no `:frame` → can't route into any frame's epoch ring), and the
  Machine Inspector panel stayed empty even after a real machine
  transition fired. With this fixture installed in the host and
  Causa subscribed to `:rf.causa/machine-snapshots`, a real
  `[:deep/main [:work/go]]` dispatch into the host MUST appear in
  Causa's snapshot map — if it doesn't, the bug is back."
  (:require [re-frame.core :as rf]
            [re-frame.machines]))

(def ^:private mini-machine
  "Minimal two-state machine — `:idle` → `:active` on `:work/go`,
  `:active` → `:idle` on `:work/reset`. The transition action
  records the tick so the test can assert the action's `:data`
  write actually committed (which it CAN'T if the cascade rolled
  back)."
  {:initial :idle
   :actions
   {:bump-tick
    (fn action-bump-tick [{data :data}]
      {:data (update data :tick-count (fnil inc 0))})}
   :states
   {:idle
    {:tags #{:deep/idle}
     :on   {:work/go {:target :active :action :bump-tick}}}

    :active
    {:tags #{:deep/active}
     :on   {:work/reset :idle}}}})

(defn install!
  "Register the `:deep/main` machine with re-frame's machines layer.
  No initial dispatch — the test fires `[:rf.machine/bootstrap]`
  itself when it wants the machine's initial-entry cascade."
  []
  (rf/reg-machine :deep/main mini-machine)
  nil)

(defn install-and-init!
  "Install + bootstrap. After this returns the machine is settled in
  `:idle` state and Causa's `:rf.causa/registered-machines` sub
  includes `:deep/main`."
  []
  (install!)
  (rf/dispatch-sync [:deep/main [:rf.machine/bootstrap]])
  nil)
