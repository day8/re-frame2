(ns day8.re-frame2-xray.panels.epoch.inline-action-source-cljs-test
  "rf2-se70xj — END-TO-END rendering fidelity for an INLINE machine action's
  source code in the Epoch panel cascade.

  ## The gap it closes

  Before rf2-se70xj the `reg-machine` macro stamped `:source-code` (the
  `pr-str` of the fn literal) onto NAMED `:guards` / `:actions` entries but
  NOT onto INLINE-fn slots (`:entry` / `:exit` / `:guard` / `:action`)
  written directly inside the `:states` tree. An inline-fn slot holds a fn
  VALUE, not a map (per rf2-vqja2 — the same reason `:source-coords` lives on
  the enclosing node), so Xray's `cascade-row-source-form` fell through to the
  bare compiled fn and `source-form->string` rendered it as an opaque
  `#object[Function]` token. Guards rendered their CLJS source correctly; an
  inline action did not.

  The fix co-locates each inline fn's source string onto the ENCLOSING
  `:states`-tree map node under `:source-code` (a `{<slot> <source-string>}`
  map), parallel to the reference-site `:source-coords` already co-located
  there. The view reads the inline source off the enclosing node.

  ## What this test drives + asserts

  It registers a machine with an INLINE transition `:action` (and inline
  `:entry` / `:exit`) via the `reg-machine` MACRO (a LITERAL spec — the macro
  walks it and stamps source), drives a real macrostep through the LIVE
  substrate, projects the cascade Xray's Epoch panel reads, renders the
  HANDLER step, and asserts the inline action's source CODE renders (the
  `pr-str` of the fn body), NOT the `#object[Function]` token. This is a
  Xray/Story-style CLJS unit test (NOT a Playwright spec)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.epoch.view :as view]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) replaces the bespoke
  ;; `xray-init!` (preload/registry/trace three-liner): the `:all` reset
  ;; tier — install (== preload's alias) + registry + mount idempotency
  ;; sentinels plus the trace-collector rings — over the Reagent adapter
  ;; this suite renders through.
  (xray-test-support/make-xray-runtime-fixture {:adapter rf.adapter.reagent/adapter}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  ;; INLINE spec via the MACRO — the literal walk stamps inline source.
  ;; A distinctive marker (`:inline-action-fired? true`) rides the action
  ;; body so the rendered source string is unambiguous to grep for.
  (rf/reg-machine :inline/sample
    {:initial :idle
     :data    {}
     :states
     {:idle {:entry (fn [_] {:data {:inline-entry-fired? true}})
             :on    {:go {:target :done
                          :action (fn [{data :data}]
                                    {:data (assoc data :inline-action-fired? true)})}}}
      :done {}}})
  (rf/dispatch-sync [:inline/sample [:rf.machine/start]]))

(defn- drive! [event-v]
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [:inline/sample event-v])
  (vec (trace-collector/buffer-for-test)))

(deftest inline-transition-action-renders-its-source-not-object-function
  (testing "rf2-se70xj — an INLINE transition `:action` renders its CLJS
            source CODE in the Epoch cascade, NOT `#object[Function]`. The
            macro-stamped inline `:source-code` (co-located on the enclosing
            transition map) is what the view reads."
    (setup!)
    ;; The transition map carries the inline action's source on the enclosing
    ;; node (the machine-side contract this test consumes).
    (is (string? (get-in (rf.machines/machine-meta :inline/sample)
                         [:states :idle :on :go :source-code :action]))
        "the inline transition :action's :source-code is co-located on the
         enclosing transition map (rf2-se70xj machine-meta contract)")
    ;; Drive the real macrostep and render the HANDLER step. The body embeds
    ;; edn-inspectors that subscribe against the surrounding frame.
    (rf/make-frame {:id :rf/xray})
    (let [trace  (drive! [:go])
          rows   (proj/machine-cascade-rows trace)
          action-rows (filterv #(= :action (:kind %)) rows)
          tree   (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-machine :event-id :inline/sample
                      :fx [] :machine {:cascade rows
                                       :transition nil :guards []
                                       :lifecycle [] :timers []}}))
          ;; The full rendered text of every action source body.
          source-bodies (->> action-rows
                             (keep (fn [r]
                                     (some-> tree
                                             (rf.test-helpers/find-by-testid
                                               (str "rf-xray-epoch-machine-cascade-source-body-"
                                                    (:step r)))
                                             rf.test-helpers/text-content)))
                             (string/join "\n"))]
      (is (seq action-rows)
          "the macrostep produced at least one :action cascade row")
      (is (string/includes? source-bodies ":inline-action-fired?")
          "the inline transition action's CLJS source CODE renders in the
           cascade source body (rf2-se70xj — was #object[Function])")
      ;; The regression token must be ABSENT — the bug symptom was the
      ;; compiled fn falling through pr-str.
      (is (not (string/includes? source-bodies "#object"))
          "no #object[Function] token renders for the inline action source
           (the rf2-se70xj regression symptom)"))))
