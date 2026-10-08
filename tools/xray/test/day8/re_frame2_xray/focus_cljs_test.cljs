(ns day8.re-frame2-xray.focus-cljs-test
  "Coverage for the host-facing Story→Xray focus API.

  Two bands:

  1. **Pure translation** (`focus-command->dispatches`) — JVM-runnable
     shape: a §D3 focus-command map maps to the ordered vector of
     `:rf.xray/*` events, every field optional, ordering frame-first.
  2. **End-to-end focus** (`focus!`) — driving the command into the
     real registered Xray events focuses the right panel + epoch +
     cascade on Xray's spine / tab slots. Proves
     the command is host-agnostic: the same command shape drives Xray
     regardless of who sent it, and `:source` provenance round-trips
     untouched.

  Per `tools/xray/spec/008-Embedding-Contract.md` §Host-facing focus
  API + the §D3 decision:
  Story owns the intent/action (sends the command); Xray owns panel
  semantics (receives + focuses) — no second Xray runtime model."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.focus :as focus]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: plain-atom adapter
  ;; + the `:all` reset tier — install (== preload's alias) + registry +
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

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

;; =========================================================================
;; (1) Pure translation — focus-command->dispatches
;; =========================================================================

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
            [:rf.xray/select-tab :app-db]]
           (focus/focus-command->dispatches
             {:frame :checkout
              :panel :app-db
              :epoch-id 42
              :dispatch-id 17
              ;; `:path` is not a focus field and is IGNORED; it sits
              ;; in the command deliberately so this assert also
              ;; pins that a host command carrying it translates
              ;; cleanly rather than erroring.
              :path [:checkout :state]
              :source {:kind :story/assertion}}))
        "no :focus-epoch emitted when :dispatch-id is present")))

(deftest epoch-id-alone-uses-focus-epoch
  (is (= [[:rf.xray/focus-epoch 42]]
         (focus/focus-command->dispatches {:epoch-id 42}))
      ":epoch-id is the lighter selector for callers without a cascade id"))

(deftest path-is-ignored
  (testing "`:path` is not a focus field. `focus!` is PERMISSIVE:
            `:path` is ignored exactly like any
            other unknown key — no `:unknown-field` refusal."
    (is (= [] (focus/focus-command->dispatches {:path [:user :profile :name]}))
        "a path-only command is a well-formed no-op")
    ;; The control: the same command with a live field beside the ignored
    ;; one translates, so the `[]` above is `:path` being ignored
    ;; rather than the translator being broken.
    (is (= [[:rf.xray/select-tab :app-db]]
           (focus/focus-command->dispatches
             {:panel :app-db :path [:user :profile :name]}))
        ":path contributes nothing beside a field that does")))

;; =========================================================================
;; (2) End-to-end — focus! drives the real Xray events
;; =========================================================================

(def ^:private fixture-cascades
  [(cascade :c1 :checkout)
   (cascade :c2 :checkout)
   (cascade :c3 :checkout)])

(deftest focus-app-db-panel-via-command
  ;; The command also carries `:path`, which `focus!` ignores.
  (setup-xray-frame!)
  (seed-cascades! fixture-cascades)
  (let [source {:kind :story/assertion :assertion/id :checkout-state-submitted}
        result (focus/focus! :checkout {:panel       :app-db
                                        :dispatch-id :c1
                                        :path        [:checkout :state]
                                        :source      source
                                        :sync?       true})]
    (is (= [true source :app-db :c1 :checkout :checkout]
           [(:ok? result) (:source result) (selected-tab)
            (:dispatch-id (focus-sub)) (:frame (focus-sub)) (view-scope-frame)])
        "the tab, the spine cascade and frame, and the L2 scope all move, and
         Story's provenance round-trips untouched")))

(deftest focus-routes-alias-lands-the-routing-tab
  ;; `:routes` is the host-friendly display noun for the `:routing` tab.
  (setup-xray-frame!)
  (let [result (focus/focus! {:frame :checkout :panel :routes :sync? true})]
    (is (= [[:rf.xray/select-tab :routing] :routing]
           [(last (:applied result)) (selected-tab)]))))

(deftest focus-shipped-l4-tabs-select-real-panels
  ;; Walks `focus/valid-panels`, so no tab is skipped by omission; that the
  ;; walked set IS the shipped set is
  ;; `registry-cljs-test/focus-valid-panels-mirrors-live-dynamic-registry`.
  (setup-xray-frame!)
  (is (= focus/valid-panels
         (set (filter (fn [panel]
                        (focus/focus! {:frame :checkout :panel panel :sync? true})
                        (and (= panel (selected-tab))
                             (some? (panel-registry/tab-by-id :dynamic panel))))
                      focus/valid-panels)))
      "every shipped tab is focusable and resolves to an installed panel"))

(deftest unknown-panel-is-rejected
  ;; The one rejected case: it would otherwise land the unknown-tab stub.
  (setup-xray-frame!)
  (is (= {:ok? false :reason :unknown-panel :given :app-bd :valid focus/valid-panels}
         (select-keys (focus/focus! {:panel :app-bd}) [:ok? :reason :given :valid])))
  (is (= :epoch (selected-tab)) "Xray state is untouched"))

(deftest explicit-command-frame-wins-over-positional
  (testing "2-arity positional host-frame fills :frame only when the
            command didn't name one"
    (setup-xray-frame!)
    (seed-cascades! [(cascade :c1 :other-frame)])
    (let [result (focus/focus! :positional-frame
                               {:frame :command-frame :panel :epoch :sync? true})]
      (is (= [:rf.xray/select-frame :command-frame]
             (first (:applied result)))
          "command :frame is the more specific intent"))))

(deftest positional-host-frame-fills-frame-when-command-omits-it
  (testing "the §D3 `(focus! frame-id command)` shape — positional
            host-frame becomes the :frame when the command doesn't name one"
    (setup-xray-frame!)
    (let [result (focus/focus! :checkout {:panel :epoch :sync? true})]
      (is (= [:rf.xray/select-frame :checkout]
             (first (:applied result)))))))

(deftest empty-command-is-ok-noop
  (setup-xray-frame!)
  (let [result (focus/focus! {:sync? true})]
    (is (= [true []] [(:ok? result) (:applied result)])
        ":sync? is a control key, never translated to a dispatch")))
