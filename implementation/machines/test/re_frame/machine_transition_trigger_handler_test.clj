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
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Routed through the shared `mtest/with-trace-capture` — guaranteed
;; unregister in a `finally`.
(defn- record-traces
  [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- transitions-of [evs]
  (filterv #(= :rf.machine/transition (:operation %)) evs))

;; ---- :rf.machine/transition carries the machine's registration coord ------

(deftest machine-transition-carries-trigger-handler
  (testing ":rf.machine/transition fires inside the machine's event-handler
   scope, so :rf.trace/trigger-handler rides the trace with the machine's
   registration coord — Xray's machine-inspector renders jump-to-source
   from this field"
    (rf/reg-machine :rf2-lf84g/tl
      {:initial :red
       :states  {:red    {:on {:tick {:target :green}}}
                 :green  {:on {:tick {:target :yellow}}}
                 :yellow {:on {:tick {:target :red}}}}})
    (let [evs    (record-traces
                   (fn [] (rf/dispatch-sync [:rf2-lf84g/tl [:tick]])))
          trans  (transitions-of evs)
          [first-trans] trans]
      (is (some? first-trans) ":rf.machine/transition fired")
      (let [t (:rf.trace/trigger-handler first-trans)]
        (is (some? t) ":rf.trace/trigger-handler present on transition trace")
        (is (= :event (:kind t)) "kind is :event (machines register as event handlers)")
        (is (= :rf2-lf84g/tl (:id t)) "id is the machine-id")
        (let [c (:source-coord t)]
          (is (map? c) ":source-coord present")
          (is (symbol? (:ns c))    ":ns is a symbol")
          (is (string? (:file c))  ":file is a string")
          (is (integer? (:line c)) ":line is an integer"))))))

(deftest machine-transition-trigger-rides-at-top-level
  (testing ":rf.trace/trigger-handler is a top-level field on the
   :rf.machine/transition event, NOT nested under :tags — mirrors the
   error-path shape"
    (rf/reg-machine :rf2-lf84g/top-level
      {:initial :a
       :states  {:a {:on {:go {:target :b}}}
                 :b {}}})
    (let [evs    (record-traces
                   (fn [] (rf/dispatch-sync [:rf2-lf84g/top-level [:go]])))
          [tr]   (transitions-of evs)]
      (is (some? tr))
      (is (contains? tr :rf.trace/trigger-handler)
          ":rf.trace/trigger-handler lives at top level")
      (is (not (contains? (:tags tr) :rf.trace/trigger-handler))
          ":rf.trace/trigger-handler does NOT live under :tags"))))

(deftest machine-transition-coord-matches-registrar
  (testing "the :source-coord under :rf.trace/trigger-handler equals what
   the registrar holds on the machine's slot — same comparison the
   trigger-handler-coord-test does for the error path"
    (rf/reg-machine :rf2-lf84g/coord
      {:initial :a
       :states  {:a {:on {:go {:target :b}}}
                 :b {}}})
    (let [reg-meta (rf/handler-meta :event :rf2-lf84g/coord)
          evs      (record-traces
                     (fn [] (rf/dispatch-sync [:rf2-lf84g/coord [:go]])))
          [tr]     (transitions-of evs)
          coord    (-> tr :rf.trace/trigger-handler :source-coord)]
      (is (some? tr))
      (is (= (:ns     reg-meta) (:ns coord)))
      (is (= (:file   reg-meta) (:file coord)))
      (is (= (:line   reg-meta) (:line coord)))
      (is (= (:column reg-meta) (:column coord))))))
