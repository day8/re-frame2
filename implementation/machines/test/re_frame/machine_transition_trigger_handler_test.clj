(ns re-frame.machine-transition-trigger-handler-test
  "`:rf.trace/trigger-handler` rides `:rf.machine/transition`.

  Spec 009 §Trace correlation: every trace event emitted inside a
  handler's execution scope carries the in-scope handler's registration
  coord under the top-level `:rf.trace/trigger-handler` slot. Machines
  register as event handlers via `reg-event` (per
  `reg-machine*` in `lifecycle_fx/registration.cljc`), so when a machine transition
  trace fires inside the machine's event-handler scope it picks up the
  machine's own registration coord via `emit!`'s hoist of
  `*current-trigger-handler*`.

  Xray's machine-inspector wants 'jump to the action that just ran'
  from a transition trace. The registration coord rides on every
  `:rf.machine/transition` event — tools render the click-to-jump link
  from the same slot they read on error events.

  Locked shape:

    {:kind         :event           ;; machines register under :event kind
     :id           <machine-id>
     :source-coord {:ns <sym> :file <string> :line <int> :column <int>}}

  JVM-only — the dynamic-var binding is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Routed through the shared `rf.machines.test-support/with-trace-capture` — guaranteed
;; unregister in a `finally`.
(defn- record-traces
  [body-fn]
  (rf.machines.test-support/with-trace-capture seen
    (body-fn)
    @seen))

(defn- transitions-of [evs]
  (filterv #(= :rf.machine/transition (:operation %)) evs))

;; ---- :rf.machine/transition carries the machine's registration coord ------

(deftest machine-transition-carries-trigger-handler
  (testing ":rf.machine/transition fires inside the machine's event-handler
   scope, so :rf.trace/trigger-handler rides the trace — at the top level,
   NOT nested under :tags, mirroring the error-path shape — carrying the
   machine's registration coord exactly as the registrar holds it. Xray's
   machine-inspector renders jump-to-source from this field"
    (rf/reg-machine :rf2-lf84g/tl
      {:initial :red
       :states  {:red    {:on {:tick {:target :green}}}
                 :green  {:on {:tick {:target :yellow}}}
                 :yellow {:on {:tick {:target :red}}}}})
    (let [reg-meta (rf/handler-meta {:source :store :kind :event :id :rf2-lf84g/tl})
          evs      (record-traces
                     (fn [] (rf/dispatch-sync [:rf2-lf84g/tl [:tick]])))
          [tr]     (transitions-of evs)
          t        (:rf.trace/trigger-handler tr)
          c        (:source-coord t)]
      (is (some? tr) ":rf.machine/transition fired")
      (is (some? t) ":rf.trace/trigger-handler present at the trace's top level")
      (is (not (contains? (:tags tr) :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler does NOT live under :tags")
      (is (= :event (:kind t)) "kind is :event (machines register as event handlers)")
      (is (= :rf2-lf84g/tl (:id t)) "id is the machine-id")
      (is (symbol? (:ns c))    ":ns is a symbol")
      (is (string? (:file c))  ":file is a string")
      (is (integer? (:line c)) ":line is an integer")
      (is (= (:ns     reg-meta) (:ns c)))
      (is (= (:file   reg-meta) (:file c)))
      (is (= (:line   reg-meta) (:line c)))
      (is (= (:column reg-meta) (:column c))))))
