(ns day8.re-frame2-xray.focus-cljs-test
  "Coverage for the host-facing Story→Xray focus API (rf2-crtmq).

  Two bands:

  1. **Pure translation** (`focus-command->dispatches`) — JVM-runnable
     shape: a §D3 focus-command map maps to the ordered vector of
     `:rf.xray/*` events, every field optional, ordering frame-first.
  2. **End-to-end focus** (`focus!`) — driving the command into the
     real registered Xray events focuses the right panel + epoch +
     cascade + app-db path on Xray's spine / tab / path slots. Proves
     the command is host-agnostic: the same command shape drives Xray
     regardless of who sent it, and `:source` provenance round-trips
     untouched.

  Per `tools/xray/spec/008-Embedding-Contract.md` §Host-facing focus
  API + the §D3 decision (`ai/findings/StoryUI/06-open-decisions.md`):
  Story owns the intent/action (sends the command); Xray owns panel
  semantics (receives + focuses) — no second Xray runtime model."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.focus :as focus]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- cascade fixture (mirrors spine-cljs-test) --------------------------

(defn- cascade [dispatch-id frame-id]
  {:dispatch-id dispatch-id
   :frame       frame-id
   :event       nil :handler nil :fx nil
   :effects [] :subs [] :renders [] :other []})

(defn- seed-cascades!
  "Seed the Xray trace buffer with synthetic single-event runs so
  the spine's by-id / head walks resolve. Mirrors the trace-event shape
  spine-cljs-test/seed-cascades! uses — one `:rf.event/dispatched` per
  cascade so `group-by-event`' frame-index pairs the right events."
  [cascades-vec]
  (let [events (map-indexed
                 (fn [i {:keys [dispatch-id frame]}]
                   {:id        (inc i)
                    :op-type   :rf.event
                    :operation :rf.event/dispatched
                    :tags      {:rf.trace/dispatch-id dispatch-id
                                :frame                frame
                                :rf.event/v           [(keyword "evt" (name dispatch-id))]}})
                 cascades-vec)]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer (vec events)]))))

(defn- focus-sub []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/focus])))

(defn- selected-tab []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/selected-tab])))

(defn- view-scope-frame []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/view-scope-frame])))

(defn- focused-slice-path []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/focused-slice-path])))

;; =========================================================================
;; (1) Pure translation — focus-command->dispatches
;; =========================================================================

(deftest empty-command-yields-no-dispatches
  (is (= [] (focus/focus-command->dispatches {}))
      "an empty focus command is a well-formed no-op")
  (is (= [] (focus/focus-command->dispatches {:source {:kind :story/beat}}))
      ":source alone produces no dispatch — it is opaque provenance"))

(deftest panel-only-flips-the-tab
  (is (= [[:rf.xray/select-tab :trace]]
         (focus/focus-command->dispatches {:panel :trace}))
      "a terse narrative beat that only cares about a panel"))

(deftest frame-dispatches-first
  (testing "frame re-scope leads so the per-frame epoch ring re-seeds
            before any epoch / cascade pin resolves"
    (is (= [[:rf.xray/select-frame :checkout]
            [:rf.xray/focus-epoch 42]
            [:rf.xray/select-tab :app-db]]
           (focus/focus-command->dispatches
             {:frame :checkout :epoch-id 42 :panel :app-db})))))

(deftest dispatch-id-carries-frame-and-wins-over-epoch
  (testing "the cascade pin carries the frame (per-frame by-id
            disambiguation) and supersedes a co-supplied :epoch-id"
    (is (= [[:rf.xray/select-frame :checkout]
            [:rf.xray/focus-event 17 :checkout]
            [:rf.xray/select-tab :app-db]
            [:rf.xray/focus-slice-path [:checkout :state]]]
           (focus/focus-command->dispatches
             {:frame :checkout
              :panel :app-db
              :epoch-id 42
              :dispatch-id 17
              :path [:checkout :state]
              :source {:kind :story/assertion}}))
        "no :focus-epoch emitted when :dispatch-id is present")))

(deftest epoch-id-alone-uses-focus-epoch
  (is (= [[:rf.xray/focus-epoch 42]]
         (focus/focus-command->dispatches {:epoch-id 42}))
      ":epoch-id is the lighter selector for callers without a cascade id"))

(deftest path-only-highlights-slice
  (is (= [[:rf.xray/focus-slice-path [:user :profile :name]]]
         (focus/focus-command->dispatches {:path [:user :profile :name]}))))

(deftest valid-panels-is-the-tab-inventory
  ;; rf2-1sddi6 / rf2-7ed9ms — `valid-panels` MIRRORS the live Dynamic
  ;; L4 tab registry. The Routing tab's id is `:routing` (renders as
  ;; "Routes"), and the EP-0016 / EP-0014 / EP-0013 cohesive-sub-domain
  ;; tabs `:resources` / `:derivation-graph` / `:module-view` ship — so
  ;; all nine live ids are focusable. (rf2-gbz39 removed the Issues tab
  ;; under Option (c) — `:issues` is no longer a focusable panel.)
  (is (= #{:epoch :app-db :views :trace :machines :routing
           :resources :derivation-graph :module-view}
         focus/valid-panels)))

(deftest valid-panels-mirrors-the-live-registry
  ;; rf2-1sddi6 / rf2-7ed9ms — the static mirror MUST equal the live
  ;; Dynamic L4 registry so focus can never drift from the shipped tab
  ;; inventory (the shell mounts a tab via the same registry; a drift
  ;; would let focus validate a panel that lands the unknown-tab stub,
  ;; or reject a shipped tab). `register-xray-handlers!` populates the
  ;; registry; the companion cross-check in `registry_cljs_test.cljs`
  ;; guards the same invariant from the registry side.
  (setup-xray-frame!)
  (is (= focus/valid-panels
         (panel-registry/tab-ids-for-mode :dynamic))
      "focus/valid-panels == the live Dynamic L4 tab registry ids"))

(deftest routes-alias-normalises-to-routing
  ;; rf2-7ed9ms F1 — a host that sends the display-noun `:routes`
  ;; lands the real `:routing` tab, not the unknown-tab stub.
  (is (= :routing (focus/normalize-panel :routes)))
  (is (= :app-db (focus/normalize-panel :app-db))
      "a non-aliased id passes through unchanged")
  (is (nil? (focus/normalize-panel nil)))
  (is (= [[:rf.xray/select-tab :routing]]
         (focus/focus-command->dispatches {:panel :routes}))
      "the emitted select-tab id is the live registry id, never :routes"))

;; =========================================================================
;; (2) End-to-end — focus! drives the real Xray events
;; =========================================================================

(def ^:private fixture-cascades
  [(cascade :c1 :checkout)
   (cascade :c2 :checkout)
   (cascade :c3 :checkout)])

(deftest focus-app-db-panel-via-command
  (testing "focusing app-db at a path from an assertion focuses the
            right Xray panel + cascade + slice"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (let [result (focus/focus! :checkout
                               {:panel :app-db
                                :dispatch-id :c1
                                :path [:checkout :state]
                                :source {:kind :story/assertion
                                         :assertion/id :checkout-state-submitted}
                                :sync? true})]
      (is (:ok? result))
      (is (= {:kind :story/assertion
              :assertion/id :checkout-state-submitted}
             (:source result))
          "Story's provenance round-trips back untouched")
      (is (= :app-db (selected-tab)) "App-db tab is selected")
      (is (= :c1 (:dispatch-id (focus-sub))) "spine pinned to the cascade")
      (is (= :checkout (:frame (focus-sub))) "spine bound to the host frame")
      (is (= :checkout (view-scope-frame)) "L2 view scope re-bound")
      (is (= [:checkout :state] (focused-slice-path)) "App-db slice highlighted"))))

(deftest focus-trace-panel-via-narrative-beat
  (testing "a narrative beat surfacing the trace panel for a frame"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (let [result (focus/focus! {:frame :checkout :panel :trace :sync? true})]
      (is (:ok? result))
      (is (= :trace (selected-tab)))
      (is (= :checkout (view-scope-frame))))))

(deftest focus-views-panel-via-command
  (testing "representative third panel — Views — focuses cleanly"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    (let [result (focus/focus! {:frame :checkout :panel :views
                                :dispatch-id :c2 :sync? true})]
      (is (:ok? result))
      (is (= :views (selected-tab)))
      (is (= :c2 (:dispatch-id (focus-sub)))))))

(deftest focus-routes-alias-lands-the-routing-tab
  (testing "rf2-7ed9ms F1 — `{:panel :routes}` (the host-friendly
            display-noun) renders the live Dynamic Routing tab
            (`:routing`), NOT the unknown-tab stub"
    (setup-xray-frame!)
    (let [result (focus/focus! {:frame :checkout :panel :routes :sync? true})]
      (is (:ok? result) ":routes is accepted (alias), not rejected")
      (is (= [:rf.xray/select-tab :routing]
             (last (:applied result)))
          "the alias normalised to the live `:routing` registry id")
      (is (= :routing (selected-tab)) "the real Routing tab is selected")
      (is (some? (panel-registry/tab-by-id :dynamic (selected-tab)))
          "the selected id resolves to an installed tab — no unknown-tab stub"))))

(deftest focus-shipped-l4-tabs-select-real-panels
  (testing "rf2-1sddi6 / rf2-7ed9ms acceptance — every shipped Dynamic
            tab id (incl. the L4-only Graph + Modules tabs) is focusable
            and resolves to an installed panel, never the unknown-tab stub"
    (setup-xray-frame!)
    (doseq [panel [:resources :derivation-graph :module-view]]
      (let [result (focus/focus! {:frame :checkout :panel panel :sync? true})]
        (is (:ok? result) (str panel " is a focusable shipped tab"))
        (is (= panel (selected-tab)) (str panel " tab is selected"))
        (is (some? (panel-registry/tab-by-id :dynamic (selected-tab)))
            (str panel " resolves to an installed tab — no unknown-tab stub"))))))

(deftest command-is-host-agnostic
  (testing "the SAME command shape drives Xray regardless of who built
            it — no Story-specific knowledge in the channel; even a
            command with no :source (a docs/test link, not a Story beat)
            focuses identically"
    (setup-xray-frame!)
    (seed-cascades! fixture-cascades)
    ;; rf2-gbz39 — uses :trace (the Issues panel was removed under
    ;; Option (c)); the point of this test is host-agnostic + no-source
    ;; focusing, independent of which panel is targeted.
    (let [result (focus/focus! {:frame :checkout :panel :trace :sync? true})]
      (is (:ok? result))
      (is (nil? (:source result)) ":source is optional — absent is fine")
      (is (= :trace (selected-tab))))))

(deftest unknown-panel-is-rejected
  (testing "a typo'd panel selector is the one rejected case — would
            otherwise silently land the L4 unknown-tab stub"
    (setup-xray-frame!)
    (let [result (focus/focus! {:panel :app-bd})]  ;; typo
      (is (false? (:ok? result)))
      (is (= :unknown-panel (:reason result)))
      (is (= :app-bd (:given result)))
      (is (= focus/valid-panels (:valid result)))
      (is (= :epoch (selected-tab))
          "Xray state is untouched — still on the default Epoch tab"))))

(deftest explicit-command-frame-wins-over-positional
  (testing "2-arity positional host-frame fills :frame only when the
            command didn't name one"
    (setup-xray-frame!)
    (seed-cascades! [(cascade :c1 :other-frame)])
    (let [result (focus/focus! :positional-frame
                               {:frame :command-frame :panel :epoch :sync? true})]
      (is (:ok? result))
      (is (= [:rf.xray/select-frame :command-frame]
             (first (:applied result)))
          "command :frame is the more specific intent"))))

(deftest positional-host-frame-fills-frame-when-command-omits-it
  (testing "the §D3 `(focus! frame-id command)` shape — positional
            host-frame becomes the :frame when the command doesn't name one"
    (setup-xray-frame!)
    (let [result (focus/focus! :checkout {:panel :epoch :sync? true})]
      (is (:ok? result))
      (is (= [:rf.xray/select-frame :checkout]
             (first (:applied result)))))))

(deftest empty-command-is-ok-noop
  (setup-xray-frame!)
  (let [result (focus/focus! {:sync? true})]
    (is (:ok? result))
    (is (= [] (:applied result))
        ":sync? is a control key, never translated to a dispatch")))
