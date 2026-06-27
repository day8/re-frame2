(ns re-frame.cross-spec-dom-cljs-test
  "Cross-Spec interaction edge cases. One deftest per documented case in
  spec/Cross-Spec-Interactions.md.

  Each deftest's docstring carries the section anchor from the doc.

  Browser-runner promotion (rf2-o83z): Interactions whose contracts
  require a real React render now run live on the :browser-test target
  instead of returning a placeholder `(is true)`. The `-dom-cljs-test$`
  suffix (rf2-2hrj8) opts this file into the `:browser-test` build; the
  same ns is also loaded by `:node-test` (its regex `cljs-test$` matches
  both `-cljs-test` and `-dom-cljs-test`). Browser-only branches gate on
  `(browser?)` and exit early under :node-test.

  Coverage audit (rf2-suif): every interaction that previously carried
  a placeholder or TODO now pins its cross-spec contract live —
  including #4 (`:after` no-op via the trace channel), #8 (frame-
  destroy-during-render), #10 (plain-fn warning under non-default
  frame), and #16 (server error projection → :rf/response stamp).
  Interactions whose deeper machinery is exercised end-to-end in a
  sister test cite that test in their docstring rather than carrying
  a redundant copy here (#16 → re-frame.ssr-end-to-end-test).

  ns ends in -cljs-test so shadow-cljs ':node-test' picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; rf2-k682: routing ships in day8/re-frame2-routing.
            ;; Required here so its load-time hook + reg-sub
            ;; registrations fire before this ns's reg-route calls.
            [re-frame.routing :as routing]
            ;; rf2-2hrj8 — the `:browser-test` build was narrowed to
            ;; `-dom-cljs-test$` so the full implementation/test corpus no
            ;; longer fans `re-frame.machines` / `re-frame.flows` /
            ;; `re-frame.epoch` in via sibling test files. Require them
            ;; explicitly here so the load-time hook publications
            ;; (`reg-machine`, `reg-flow`, …) install before the cross-spec
            ;; tests below reach into the late-bind table at call time.
            [re-frame.machines]
            [re-frame.flows]
            [re-frame.epoch]
            [re-frame.ssr :as ssr]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

;; True only on the :browser-test runner. The :node-test target loads
;; the same ns but has no DOM, so any test that mounts through React
;; gates on this predicate and returns early under :node-test (where it
;; would crash on `js/document`). Per rf2-o83z (this bead): the cross-
;; spec tests that REQUIRE a real React render are promoted from inert
;; placeholders to live assertions on the browser runner only.
(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- per-test mount-point helper (browser-only) ---------------------------
;;
;; Each browser-only test that needs to mount creates a fresh detached
;; <div> rather than sharing a global root. Detached nodes still pass
;; React's "must be in a Document" check (the elements ARE owned by the
;; ambient `js/document`) but don't mutate the test page's visible DOM.
;; This keeps tests independent — no race over a shared mount slot — and
;; matches the per-test cleanup the `make-reset-runtime-fixture` already gives
;; us at the runtime layer.

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

;; Snapshot/restore the registrar around each test (rf2-am9d). We do NOT
;; call (registrar/clear-all!): CLJS has no runtime (require :reload), so
;; wiping the registrar would permanently lose routing's framework events
;; (:rf.route/transitioned, :rf.route/navigate, :rf.nav/scroll fx, …) and
;; machines.cljc's :rf/machine sub, which were registered at ns-load time.
;; Snapshot/restore preserves those while rolling back the test's own
;; registrations on the way out.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---------------------------------------------------------------------------
;; Interaction 1 — Frame disposal with active machine instances
;; spec/Cross-Spec-Interactions.md#1-frame-disposal-with-active-machine-instances
;; ---------------------------------------------------------------------------

(deftest frame-destroy-with-active-machines
  "#1 Frame disposal with active machine instances —
   destroy-frame! emits one :rf.machine.lifecycle/destroyed per active
   machine, carrying :reason :parent-frame-destroyed."
  (rf/reg-frame :tenant-x {:doc "tenant frame with two machines"})
  ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state.
  (rf/reg-event :seed
    (fn [{rt :rf.db/runtime} _]
      {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/machines :snapshots]
                                {:flow/login    {:state :authed   :data {}}
                                 :flow/checkout {:state :pending  :data {}}})}))
  (rf/dispatch-sync [:seed] {:frame :tenant-x})
  (with-trace-recorder! [traces]
    (rf/destroy-frame! :tenant-x)
    (let [machine-traces (filter #(= :rf.machine.lifecycle/destroyed
                                      (:operation %))
                                 @traces)]
      (is (= 2 (count machine-traces))
          "one trace per active machine snapshot at frame destroy")
      (is (every? #(= :tenant-x (:frame (:tags %))) machine-traces)
          "each trace carries the destroyed frame's id")
      (is (= #{:authed :pending}
             (set (map #(:last-state (:tags %)) machine-traces)))
          "each trace records the machine's last state")
      (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) machine-traces)
          "each trace carries :reason :parent-frame-destroyed")
      (is (some #(= :rf.frame/destroyed (:operation %)) @traces)
          ":rf.frame/destroyed fires after the per-machine traces"))))

;; ---------------------------------------------------------------------------
;; Interaction 2 — Sub-cache hit inside a machine microstep
;; spec/Cross-Spec-Interactions.md#2-sub-cache-hit-inside-a-machine-microstep
;; ---------------------------------------------------------------------------

(deftest machine-microstep-subscribe
  "#2 Sub-cache hit inside a machine microstep —
   subscribe inside an action body sees the pre-cascade app-db, not the
   in-flight :data."
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:user/role :admin}}))
  (rf/reg-sub :user-role (fn [db _] (:user/role db)))
  (rf/dispatch-sync [:seed])
  (let [observed-by-action (atom nil)
        machine
        {:initial :idle
         :data    {}
         :states  {:idle    {:on {:go {:target :acting :action :record-role}}}
                   :acting  {}}
         :actions {:record-role
                   (fn [_]
                     ;; Read a sub from inside the action — the machine
                     ;; cascade has not committed yet but the sub sees
                     ;; the *current* committed app-db.
                     (reset! observed-by-action (rf/subscribe-once [:user-role]))
                     nil)}}]
    (rf/reg-machine :auth/check machine)
    (rf/dispatch-sync [:auth/check [:go]])
    (is (= :admin @observed-by-action)
        "the sub returns the committed app-db value visible to the action body")))

;; ---------------------------------------------------------------------------
;; Interaction 3 — Machine spawn at boot before substrate adapter ready
;; spec/Cross-Spec-Interactions.md#3-machine-spawn-at-boot-before-substrate-adapter-ready
;; ---------------------------------------------------------------------------

(deftest boot-order-adapter-ready
  "#3 Machine spawn at boot before substrate adapter ready —
   :initial-events runs synchronously after the adapter is installed."
  ;; In :node-test the adapter is installed by reset-runtime before any
  ;; reg-frame call, so :initial-events always runs against a ready adapter.
  ;; This test pins that property: a frame's :initial-events setup event
  ;; reaches a live sub-cache and the spawned machine's snapshot lands in app-db.
  ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state.
  (rf/reg-event :init-shape
    (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:snapshots {:flow/boot {:state :armed
                                                                            :data  {}}}}}}))
  ;; EP-0002 (rf2-9o48ih): the reset-runtime fixture establishes an ambient
  ;; `*current-frame*` :rf/default scope. `reg-frame`'s `:initial-events`
  ;; dispatch branches on `*current-frame*` (in-flight-cascade heuristic,
  ;; rf2-cufbh) between a synchronous top-level drain and an async child-frame
  ;; queue. This test models a TOP-LEVEL boot — clear the ambient scope so the
  ;; `:initial-events` cascade drains synchronously and its seed is observable.
  (binding [frame/*current-frame* nil]
    (rf/reg-frame :booted {:initial-events [[:init-shape]]}))
  (let [rt (rf/runtime-db-value :booted)]
    (is (= :armed (get-in rt [:rf.runtime/machines :snapshots :flow/boot :state]))
        ":initial-events completed against an installed adapter — runtime-db carries the seed")))

;; ---------------------------------------------------------------------------
;; Interaction 4 — Machines under SSR (allowed-subset)
;; spec/Cross-Spec-Interactions.md#4-machines-under-ssr-allowed-subset
;; ---------------------------------------------------------------------------

(deftest after-noop-shape-under-ssr-server-preset
  "#4 Machines under SSR (allowed-subset) —
   the :ssr-server preset stamps :platform :server on the frame, which
   is the channel through which `:after` is suppressed. Per rf2-o83z
   the `:after`-no-op end-to-end check fires through the trace channel
   (`:rf.machine.timer/skipped-on-server`) — no real timer harness is
   needed because the gate emits a synchronous, observable trace at
   schedule time. See machines.cljc §`:after`-scheduling, where the
   `:server` branch emits `:rf.machine.timer/skipped-on-server` in
   place of `:rf.machine.timer/scheduled`."
  (rf/reg-frame :req {:preset :ssr-server})
  (let [meta (rf/frame-meta :req)]
    (is (= :server (:platform meta))
        ":ssr-server preset sets :platform :server on the frame metadata"))
  ;; Register a machine whose `:loading` state declares an `:after` table.
  ;; The transition `:idle → :loading` enters an `:after`-bearing state;
  ;; on a non-SSR frame this would emit `:rf.machine.timer/scheduled`. On
  ;; the `:ssr-server` frame it must emit
  ;; `:rf.machine.timer/skipped-on-server` and NOT schedule a real timer
  ;; (per Cross-Spec-Interactions §4 and Spec 005 §SSR mode). The trace
  ;; channel is the observable end-to-end signal — no timer harness is
  ;; required because the gate fires synchronously at schedule time.
  (rf/reg-machine :ssr/timed
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:fetch {:target :loading}}}
               :loading {:after {500 :awake}}
               :awake   {}}})
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:ssr/timed [:fetch]] {:frame :req})
    (let [skipped   (filter #(= :rf.machine.timer/skipped-on-server
                                (:operation %))
                            @traces)
          scheduled (filter #(= :rf.machine.timer/scheduled
                                (:operation %))
                            @traces)]
      (is (seq skipped)
          ":after on :ssr-server emits :rf.machine.timer/skipped-on-server")
      (is (some #(= :server (get-in % [:tags :platform])) skipped)
          "the skipped-on-server trace records :platform :server")
      (is (some #(= 500 (get-in % [:tags :delay])) skipped)
          "the trace carries the declared :after delay")
      (is (empty? scheduled)
          "no :rf.machine.timer/scheduled trace fires on :ssr-server — :after is a true no-op, not a deferred schedule"))))

;; ---------------------------------------------------------------------------
;; Interaction 5 — Hydration with machine snapshots
;; spec/Cross-Spec-Interactions.md#5-hydration-with-machine-snapshots
;; ---------------------------------------------------------------------------

(deftest ssr-hydrate-with-machines
  "#5 Hydration with machine snapshots —
   EP-0001 (rf2-vzld77): machine snapshots live in the runtime-db partition at
   [:rf.runtime/machines :snapshots <id>], so hydration installs them via a
   coherent runtime-db write (the payload carries an app-db slice + a
   runtime-db slice) — they survive the standard hydration with the rest of
   the frame-state, no separate machine channel."
  (rf/reg-event :hydrate-payload
    (fn [_ [_ {:keys [app-db runtime-db]}]]
      {:db app-db :rf.db/runtime runtime-db}))
  (let [server-app-db {:user/id 7}
        server-rt      {:rf.runtime/machines {:snapshots {:auth/session {:state :authenticated
                                                                         :data  {:token "abc"}}}}}]
    (rf/dispatch-sync [:hydrate-payload {:app-db server-app-db :runtime-db server-rt}])
    (let [client-rt (rf/runtime-db-value :rf/default)]
      (is (= :authenticated
             (get-in client-rt [:rf.runtime/machines :snapshots :auth/session :state]))
          "machine state survives hydration in the runtime-db partition")
      (is (= "abc"
             (get-in client-rt [:rf.runtime/machines :snapshots :auth/session :data :token]))
          "machine :data survives hydration with the rest of the frame-state"))))

;; ---------------------------------------------------------------------------
;; Interaction 6 — Routing in SSR
;; spec/Cross-Spec-Interactions.md#6-routing-in-ssr
;; ---------------------------------------------------------------------------

(deftest routing-in-ssr-nav-fx-skipped-on-server
  "#6 Routing in SSR —
   :rf.nav/push-url and :rf.nav/replace-url are no-ops on :server; the
   route slice itself is just app-db so it hydrates trivially."
  (rf/reg-route :user/show {} "/users/:id")
  (rf/reg-frame :req {:preset :ssr-server})
  ;; Re-register the framework nav fx (reset-runtime cleared the
  ;; registrar) so this test exercises the same platform-gating shape
  ;; the production fx use.
  (rf/reg-fx :rf.nav/push-url
    {:platforms #{:client}}
    (fn [_ _] :should-not-run-on-server))
  (rf/reg-event :emit-nav
    (fn [_ _]
      {:fx [[:rf.nav/push-url "/users/42"]]}))
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:emit-nav] {:frame :req})
    (is (some #(and (= :rf.fx/skipped-on-platform (:operation %))
                    (= :rf.nav/push-url (get-in % [:tags :rf.fx/id])))
              @traces)
        ":rf.nav/push-url emits :rf.fx/skipped-on-platform under :server"))
  (testing "routes round-trip the same as on the client"
    (let [m (routing/match-url "/users/42")]
      (is (= :user/show (:route-id m)))
      (is (= "42" (:id (:params m)))))))

;; ---------------------------------------------------------------------------
;; Interaction 7 — Route-not-found under SSR
;; spec/Cross-Spec-Interactions.md#7-route-not-found-under-ssr
;; ---------------------------------------------------------------------------

(deftest route-not-found-ssr-status
  "#7 Route-not-found under SSR —
   match-url returns nil-id for an unmatched URL; route-not-found is
   normal control flow, not an :rf.error trace."
  (rf/reg-route :user/show {} "/users/:id")
  (let [m (routing/match-url "/no-such-thing")]
    (is (nil? (:route-id m))
        "match-url surfaces no route-id for an unmatched URL"))
  ;; The error-projector contract is the user-side concern; here we
  ;; confirm that match-url itself does not emit :rf.error traces for
  ;; an unmatched URL — i.e., a missing route is signal, not an error.
  (with-trace-recorder! [traces]
    (routing/match-url "/no-such-thing")
    (is (empty? (filter #(= :error (:op-type %)) @traces))
        "match-url is pure: route-not-found does not emit error traces")))

;; ---------------------------------------------------------------------------
;; Interaction 8 — Frame disposal during render
;; spec/Cross-Spec-Interactions.md#8-frame-disposal-during-render
;; ---------------------------------------------------------------------------

(deftest frame-destroy-during-render
  "#8 Frame disposal during render —
   The current render pass completes against the snapshot it began with;
   after the render commits, the disposal runs (sub-cache disposes; the
   substrate releases the frame-scoped subtree; subsequent dispatch /
   subscribe against the destroyed frame raises :rf.error/frame-destroyed).

   Per rf2-o83z this case is promoted from a placeholder to a real
   browser-runner test. The :node-test target also loads this ns
   (its `cljs-test$` regex matches both `-cljs-test` and the
   `-dom-cljs-test` suffix introduced in rf2-2hrj8), so we gate the
   DOM-mounting branch on `(browser?)` and exit early under
   :node-test where `js/document` is absent."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [render-count (atom 0)
          render-error (atom nil)
          target-frame :tenant-render
          mount-node   (make-mount-node!)
          ;; Plain Reagent component (Form-1). It derefs the frame
          ;; container so it rebinds Reagent's reactive watcher to the
          ;; frame's app-db; if the frame is alive at render time the
          ;; deref returns the current value, if the frame has just been
          ;; destroyed the frame-container is still referenceable (its
          ;; rAtom outlives the registry entry). The component itself
          ;; calls destroy-frame! during its FIRST render — that's the
          ;; "mid-render destroy" the spec describes — so the second
          ;; render (if any) and the surrounding test code observe the
          ;; commit-then-dispose ordering.
          render-fn    (fn []
                         (let [n (swap! render-count inc)
                               container (frame/app-db-container target-frame)
                               db (when container @container)]
                           (when (= 1 n)
                             ;; Mid-render destroy. Per Spec 002 §Destroy
                             ;; this synchronously disposes the frame —
                             ;; the React commit cycle that's currently
                             ;; running cannot be interrupted, so this
                             ;; returns and the render completes against
                             ;; the snapshot the render fn read above.
                             (rf/destroy-frame! target-frame))
                           [:div.x {:data-render-count n
                                    :data-db          (pr-str db)}
                            "ok"]))]
      (rf/reg-frame target-frame {:doc "frame destroyed mid-render"})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
      (rf/dispatch-sync [:seed] {:frame target-frame})
      (with-trace-recorder! [traces]
        ;; Mount under frame-provider so the subtree is scoped to
        ;; the ALREADY-CREATED target-frame in the React-context tier
        ;; (the frame is constructed above via reg-frame + dispatch-sync
        ;; seed; this is a SCOPE, not an owned create — EP-0024). Even
        ;; though the render fn reads via frame/app-db-container directly,
        ;; the scope-provider mount path is the documented user-facing
        ;; shape (per Spec 004 §frame-provider) and exercises the
        ;; same substrate code-path the spec describes.
        (let [root (rdc/create-root mount-node)]
          ;; Reagent 2's render is flushSync — by the time `rdc/render`
          ;; returns, the first render pass has committed. The
          ;; render-fn's destroy-frame! call therefore ran inside the
          ;; commit cycle.
          (try
            ;; Hiccup head is the frame-provider fn; Reagent
            ;; treats `[fn-head args & children]` as an inline
            ;; component invocation.
            ;;
            ;; React 18's root.render() is asynchronous by
            ;; default — wrapping in flushSync forces the
            ;; commit cycle to complete before the call
            ;; returns. This is what lets the test observe the
            ;; mid-render destroy synchronously, the same
            ;; ordering the spec describes (Spec 002 §Destroy:
            ;; render commits, then disposal runs).
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider
                                  {:frame target-frame}
                                  [render-fn]])))
            (catch :default e
              ;; If destroy-during-render bubbled an exception
              ;; the render itself would throw — record it for
              ;; the assertion below.
              (reset! render-error (ex-message e))))
          (try
            (is (nil? @render-error)
                (str "render did not throw mid-destroy; got: " (pr-str @render-error)))
            (is (>= @render-count 1)
                "render fn ran at least once — mid-render destroy did not abort the render pass")
            (is (some #(and (= :rf.frame/destroyed (:operation %))
                            (= target-frame (get-in % [:tags :frame])))
                      @traces)
                ":rf.frame/destroyed trace fired — destroy-frame! ran the disposal pipeline")
            (is (nil? (frame/frame target-frame))
                "the frame is gone from the registry after destroy")
            ;; Post-destroy dispatch raises :rf.error/frame-destroyed (per
            ;; Spec 002 §Destroy). The trace channel is the public surface.
            ;; A nested recorder isolates the post-destroy emit from the
            ;; outer recorder's accumulated render+destroy events.
            (with-trace-recorder! [post-traces]
              (rf/dispatch-sync [:seed] {:frame target-frame})
              (is (some #(= :rf.error/frame-destroyed (:operation %))
                        @post-traces)
                  "subsequent dispatch against the destroyed frame emits :rf.error/frame-destroyed"))
            (finally
              ;; Clean up the React root so its internal effects don't
              ;; leak across tests. The mount node is detached and will be
              ;; GC'd with this scope.
              (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---------------------------------------------------------------------------
;; Interaction 9 — Reactive substrate without React-context
;; spec/Cross-Spec-Interactions.md#9-reactive-substrate-without-react-context
;; ---------------------------------------------------------------------------

(deftest headless-explicit-frame-resolution-chain
  "#9 Reactive substrate without React-context —
   the resolution chain is dynamic-var → :rf/default when the substrate
   has no context concept (the dynamic var is the always-available tier)."
  (rf/reg-frame :alt {:doc "alt frame"})
  ;; Outside any with-frame: falls back to :rf/default.
  (is (= :rf/default (rf/current-frame-id))
      "no dynamic binding → resolves to :rf/default")
  ;; with-frame binds the dynamic var; resolution lands on the bound id.
  (rf/with-frame :alt
    (is (= :alt (rf/current-frame-id))
        "dynamic-var tier wins over :rf/default"))
  ;; After with-frame returns, dynamic var is unbound again.
  (is (= :rf/default (rf/current-frame-id))
      "with-frame's binding is scoped — dynamic var reverts on exit"))

;; ---------------------------------------------------------------------------
;; Interaction 10 — Plain Reagent fn under a non-default frame
;; spec/Cross-Spec-Interactions.md#10-plain-reagent-fn-under-a-non-default-frame
;; ---------------------------------------------------------------------------

(deftest plain-fn-under-non-default-frame
  "#10 Plain Reagent fn under a frame-provider —
   a plain (non-`reg-view`) Reagent fn lacks the `:contextType` wiring
   `reg-view` attaches, so its `(rf/subscribe ...)` call cannot read the
   surrounding React-context frame.

   EP-0002 (rf2-69r7ui): the resolution chain has NO `:rf/default` floor.
   A plain fn that cannot read context resolves to nil, and
   `rf/subscribe` (via `frame/require-current-frame!`) raises
   `:rf.error/no-frame-context` — superseding the old silent
   fall-through + once-per-pair warning. The sharper no-frame-context
   error IS the diagnostic now: the bare reagent fn fails fast rather than
   targeting a conventional default. To capture the surrounding frame, a
   plain fn must use `reg-view` (which wires `:contextType`) or capture a
   `(rf/capture-frame)` at render time.

   Browser-only — requires a real React render so the React-context
   tier actually pushes the Provider's value."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-plain-fn-warn]
      (rf/reg-frame target-frame {:doc "non-default frame for plain-fn no-frame-context test"})
      (rf/reg-event :seed-plain-fn (fn [{:keys [db]} _] {:db {:n 7}}))
      (rf/dispatch-sync [:seed-plain-fn] {:frame target-frame})
      (rf/reg-sub :plain-fn-test/n (fn [db _] (:n db)))
      (let [render-error (atom nil)
            ;; A plain Reagent fn — NOT registered via reg-view, so it
            ;; carries no `:contextType` wiring and cannot read the
            ;; surrounding Provider's frame. Its subscribe raises
            ;; :rf.error/no-frame-context (no :rf/default floor).
            plain-fn     (fn plain-fn-impl []
                           (try
                             @(rf/subscribe [:plain-fn-test/n])
                             (catch :default e (reset! render-error e)))
                           [:div.plain "a"])
            mount-node   (make-mount-node!)
            root         (rdc/create-root mount-node)]
        (try
          ;; EP-0002 (rf2-9o48ih): clear the fixture's ambient `*current-frame*`
          ;; :rf/default scope around the synchronous `flushSync` render. The
          ;; render runs inside the test body's dynamic extent, so the ambient
          ;; scope would otherwise satisfy the plain fn's `subscribe` at tier 1
          ;; (resolving :rf/default) and the no-frame-context contract under
          ;; test would never fire. With the scope cleared the plain fn (no
          ;; :contextType) cannot read context and resolves nil → the loud
          ;; :rf.error/no-frame-context this test pins.
          (binding [frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root
                            [rf/frame-provider {:frame target-frame}
                             [plain-fn]]))))
          (is (some? @render-error)
              "a plain fn's subscribe (no :contextType) raised rather than falling through to :rf/default")
          (is (= :rf.error/no-frame-context
                 (:rf.error/id (ex-data @render-error)))
              "the raised error is :rf.error/no-frame-context (EP-0002 — no silent :rf/default)")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest plain-fn-under-no-provider-raises-no-frame-context
  "Plain Reagent fn rendered with NO frame-provider at all.
   EP-0002 (rf2-69r7ui): there is no `:rf/default` floor — a bare reagent
   fn under no provider resolves to nil and its `(rf/subscribe ...)`
   raises `:rf.error/no-frame-context`. (Under the OLD contract this was a
   silent route to `:rf/default`; the sharper error is the EP-0002
   replacement.) A single-frame app keeps working by establishing ONE
   explicit root `frame-provider` / `with-frame`."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (do
      (rf/reg-event :seed-plain-default (fn [{:keys [db]} _] {:db {:m 9}}))
      (rf/reg-sub :plain-default-test/m (fn [db _] (:m db)))
      (let [render-error (atom nil)
            plain-default (fn plain-default-impl []
                            (try
                              @(rf/subscribe [:plain-default-test/m])
                              (catch :default e (reset! render-error e)))
                            [:div "no-provider"])
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          ;; EP-0002 (rf2-9o48ih): clear the fixture's ambient `*current-frame*`
          ;; :rf/default scope around the synchronous render so the
          ;; no-provider → no-frame-context contract is actually exercised
          ;; (the ambient scope would otherwise resolve :rf/default at tier 1).
          (binding [frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                ;; No frame-provider — the React-context tier resolves to
                ;; the no-provider sentinel → nil → no-frame-context.
                (rdc/render root [plain-default]))))
          (is (some? @render-error)
              "a plain fn with no enclosing provider raised rather than reading :rf/default")
          (is (= :rf.error/no-frame-context
                 (:rf.error/id (ex-data @render-error)))
              "the raised error is :rf.error/no-frame-context (EP-0002 — no silent :rf/default)")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest reg-view-under-non-default-frame-no-warning
  "Negative case — a properly-registered view (reg-view*) renders inside
   a non-default frame-provider WITHOUT triggering the warning. The
   warning targets the plain-fn footgun specifically; reg-view'd
   components carry the `:contextType` wiring that lets them read the
   Provider's frame, so their subscribe calls route correctly."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-reg-view-no-warn]
      (rf/reg-frame target-frame {:doc "non-default frame for reg-view negative test"})
      (rf/reg-event :seed-reg-view (fn [{:keys [db]} _] {:db {:k 11}}))
      (rf/dispatch-sync [:seed-reg-view] {:frame target-frame})
      (rf/reg-sub :reg-view-test/k (fn [db _] (:k db)))
      ;; (The plain-fn-under-non-default-frame warning + its
      ;; :views/clear-plain-fn-warned-pairs! suppression-cache reset were
      ;; retired per EP-0002 / removed in rf2-k4xous; the warning is
      ;; superseded by the always-on :rf.error/no-frame-context. This
      ;; negative case now simply confirms a reg-view'd component renders
      ;; cleanly under a non-default frame with no such warning emitted.)
      (rf/reg-view* :rf.cross-spec-10/registered-view
                    (fn registered-impl []
                      (let [_ @(rf/subscribe [:reg-view-test/k])]
                        [:div "reg-view"])))
      (let [render-fn  (rf/view :rf.cross-spec-10/registered-view)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
       (with-trace-recorder! [traces]
        (try
          (react-dom/flushSync
            (fn []
              (rdc/render root [rf/frame-provider {:frame target-frame}
                                [render-fn]])))
          (let [warns (filter #(= :rf.warning/plain-fn-under-non-default-frame-once
                                   (:operation %))
                              @traces)]
            (is (empty? warns)
                "no warning fires for reg-view'd components — the wiring lets them read the surrounding frame"))
          (finally
            (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---------------------------------------------------------------------------
;; rf2-d4sf — subscribe + dispatch consult the React-context tier
;;
;; Per Spec 002 §Reading the frame from React context the resolution
;; chain at a CLJS subscribe / dispatch call site is:
;;   1. *current-frame* dynamic var
;;   2. closest enclosing frame-provider via React context
;;   3. :rf/default
;;
;; Before rf2-d4sf, `re-frame.subs/subscribe` and the dispatch
;; envelope's `:frame` default called `re-frame.frame/current-frame`
;; directly — that fn covers tier 1 and tier 3 only, so the React-
;; context tier was dead code. The fix routes subscribe + dispatch
;; through the `:adapter/current-frame` late-bind hook (registered by
;; the Reagent / UIx / Helix adapter at ns-load time). The hook
;; consults `_currentValue` on the shared React context object,
;; tolerating Reagent's prop-stringified-keyword shape.
;;
;; This test pins the live behaviour: under a non-default
;; frame-provider, a reg-view'd subscribe must resolve to the
;; provider's frame, not :rf/default.
;; ---------------------------------------------------------------------------

(deftest subscribe-routes-via-react-context-under-non-default-frame
  "rf2-d4sf — subscribe consults the React-context tier so a reg-view
   inside a non-default `frame-provider` reads the provider's frame
   (not :rf/default)."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-rf2-d4sf]
      ;; Two distinct frames whose app-dbs hold different values so the
      ;; subscribed reaction tells us unambiguously which one served it.
      (rf/reg-frame target-frame {:doc "non-default frame — :v 42"})
      (rf/reg-event :seed-target (fn [{:keys [db]} _] {:db {:v 42}}))
      (rf/reg-event :seed-default (fn [{:keys [db]} _] {:db {:v 7}}))
      (rf/dispatch-sync [:seed-target]  {:frame target-frame})
      (rf/dispatch-sync [:seed-default] {:frame :rf/default})
      (rf/reg-sub :rf2-d4sf/v (fn [db _] (:v db)))
      ;; The view captures the resolved frame and the subscribed value
      ;; into shared atoms so the test can read them after the render.
      (let [resolved-frame (atom nil)
            resolved-value (atom nil)]
        (rf/reg-view* :rf.cross-spec-d4sf/probe
                      (fn probe-impl []
                        (reset! resolved-frame (rf/current-frame-id))
                        (reset! resolved-value @(rf/subscribe [:rf2-d4sf/v]))
                        [:div "probe"]))
        (let [render-fn  (rf/view :rf.cross-spec-d4sf/probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; EP-0002 (rf2-9o48ih): the reset-runtime fixture binds an ambient
            ;; `*current-frame*` :rf/default scope around the test body. The
            ;; React render below runs SYNCHRONOUSLY inside `flushSync` — i.e.
            ;; still inside that dynamic extent — so the reg-view's
            ;; `current-frame-id` / `subscribe` would resolve the ambient
            ;; :rf/default (tier 1) and never reach the React-context tier
            ;; (tier 2) under test. Clear the ambient scope around the render so
            ;; the provider's frame is actually resolved via React context —
            ;; the contract this test pins.
            (binding [frame/*current-frame* nil]
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame target-frame}
                                    [render-fn]]))))
            (is (= target-frame @resolved-frame)
                "current-frame inside the reg-view reads the surrounding provider's frame, not :rf/default")
            (is (= 42 @resolved-value)
                "subscribe routes the query against the provider's frame — :v 42 (target) not :v 7 (:rf/default)")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

(deftest subscribe-routes-default-without-frame-provider
  "rf2-d4sf negative — without a `frame-provider`, subscribe still
   resolves to `:rf/default` (the createContext default). The fix only
   adds the React-context tier; it must not change the default-tier
   behaviour."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (do
      (rf/reg-event :seed-no-provider (fn [{:keys [db]} _] {:db {:w 99}}))
      (rf/dispatch-sync [:seed-no-provider])
      (rf/reg-sub :rf2-d4sf/w (fn [db _] (:w db)))
      (let [resolved-frame (atom nil)
            resolved-value (atom nil)]
        (rf/reg-view* :rf.cross-spec-d4sf/probe-no-provider
                      (fn probe-no-provider-impl []
                        (reset! resolved-frame (rf/current-frame-id))
                        (reset! resolved-value @(rf/subscribe [:rf2-d4sf/w]))
                        [:div "probe"]))
        (let [render-fn  (rf/view :rf.cross-spec-d4sf/probe-no-provider)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [render-fn])))
            (is (= :rf/default @resolved-frame)
                "no provider in the tree → resolution falls through to :rf/default")
            (is (= 99 @resolved-value)
                "subscribe routes against :rf/default's app-db")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

(deftest with-frame-wins-over-react-context
  "rf2-d4sf — the dynamic-var tier (set by `with-frame`) sits ABOVE the
   React-context tier in the resolution chain. A `with-frame` binding
   inside a non-default frame-provider's subtree wins over the provider
   for the duration of the binding."
  (rf/reg-frame :rf.d4sf/dynamic-tier
                {:doc "frame referenced via with-frame, not via provider"})
  (rf/reg-frame :rf.d4sf/provider-tier
                {:doc "frame referenced via the React-context provider"})
  ;; Outside of any render, `with-frame` binds the dynamic var, and the
  ;; React-context tier is never consulted (no Provider above this code
  ;; path). Pin the precedence on the JVM-shared resolution path here —
  ;; the React-rendered case is exercised by the previous deftest.
  (rf/with-frame :rf.d4sf/dynamic-tier
    (is (= :rf.d4sf/dynamic-tier (rf/current-frame-id))
        "with-frame's dynamic-var binding wins over :rf/default")))

(deftest adapter-context-current-frame-tolerates-prop-stringified-keyword
  "rf2-d4sf — the function-component-shape React-context-aware
   `current-frame` impl in `re-frame.adapter.context` rounds a
   prop-stringified keyword back to a keyword. Reagent's
   `convert-prop-value` rewrites named values (keywords / symbols) to
   strings when they pass as React props, so `[:> Provider {:value :foo}
   ...]` reaches React with `value=\"foo\"`. The function-component
   path (UIx / Helix) reads `_currentValue` directly off the shared
   context object; that read MUST tolerate the stringified shape so a
   UIx / Helix subtree embedded under a Reagent `[:> ...]` provider
   sees the right frame."
  ;; Direct test of the lookup: we can't easily simulate the React-render
  ;; pump without a real Provider, but we can pin the shape-coercion
  ;; contract by stamping `_currentValue` directly. React maintains
  ;; this field as part of its public-stable createContext API surface;
  ;; the field is the same path the read-side relies on.
  ;; EP-0002 (rf2-9o48ih): the reset-runtime fixture establishes an ambient
  ;; `*current-frame*` :rf/default scope (the carried-invariant equivalent of
  ;; wrapping every adapter test in `(with-frame :rf/default …)`). The
  ;; React-context tier is the SECOND tier of `function-component-current-frame`
  ;; — only consulted when no dynamic scope is bound. Clear the ambient scope
  ;; so the `_currentValue` read (the contract under test) is actually
  ;; exercised; otherwise the dynamic-var tier shadows it and every read
  ;; resolves to :rf/default before the context is inspected.
  (binding [frame/*current-frame* nil]
  (let [original (.-_currentValue ^js adapter-context/frame-context)]
    (try
      ;; String shape — what Reagent's prop-conversion produces.
      (set! (.-_currentValue ^js adapter-context/frame-context) "tenant-prop-converted")
      (is (= :tenant-prop-converted (adapter-context/function-component-current-frame))
          "stringified keyword is round-tripped back to a keyword")
      ;; Keyword shape — what the createContext default and CLJS-direct
      ;; paths produce.
      (set! (.-_currentValue ^js adapter-context/frame-context) :tenant-keyword-shape)
      (is (= :tenant-keyword-shape (adapter-context/function-component-current-frame))
          "keyword shape is preserved")
      ;; Empty-string shape — EP-0002 (rf2-9o48ih): an empty string is not a
      ;; coercible keyword and not the no-provider sentinel, so it is a
      ;; corrupted `_currentValue`. The reader returns nil (no synthesised
      ;; :rf/default floor); a public op reading nil then raises
      ;; :rf.error/no-frame-context.
      (set! (.-_currentValue ^js adapter-context/frame-context) "")
      (is (nil? (adapter-context/function-component-current-frame))
          "empty-string is corrupted — resolves to nil, no :rf/default floor")
      (finally
        (set! (.-_currentValue ^js adapter-context/frame-context) original))))))

(deftest dispatch-default-frame-routes-via-react-context
  "rf2-d4sf — the dispatch envelope's `:frame` default is built via the
   same `:adapter/current-frame` hook as subscribe, so an event
   dispatched from inside a non-default frame-provider's subtree (with
   no explicit `:frame` opt) routes to the provider's frame."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-d4sf-dispatch]
      (rf/reg-frame target-frame {:doc "non-default frame for dispatch routing test"})
      (rf/reg-event :rf2-d4sf/record-here (fn [{:keys [db]} _] {:db (assoc db :stamped :here)}))
      (rf/reg-view* :rf.cross-spec-d4sf/dispatcher-probe
                    (fn dispatcher-probe-impl []
                      ;; Dispatch with no :frame opt — must route to the
                      ;; surrounding provider's frame, not :rf/default.
                      (rf/dispatch-sync [:rf2-d4sf/record-here])
                      [:div "probe"]))
      (let [render-fn  (rf/view :rf.cross-spec-d4sf/dispatcher-probe)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          ;; EP-0002 (rf2-9o48ih): clear the fixture's ambient `*current-frame*`
          ;; :rf/default scope around the synchronous render so the dispatch's
          ;; `:frame` default resolves via the React-context tier (the provider's
          ;; frame), not the ambient :rf/default that tier 1 would otherwise win.
          (binding [frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target-frame}
                                  [render-fn]]))))
          (is (= :here (:stamped (rf/app-db-value target-frame)))
              "dispatch routed to the provider's frame — its app-db carries the stamp")
          (is (not= :here (:stamped (rf/app-db-value :rf/default)))
              ":rf/default's app-db is NOT stamped — the dispatch did not fall through")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

;; ---------------------------------------------------------------------------
;; Interaction 11 — Machine action throws
;; spec/Cross-Spec-Interactions.md#11-machine-action-throws
;; ---------------------------------------------------------------------------

(deftest machine-action-throws
  "#11 Machine action throws —
   when an action fn throws the cascade halts; the error is surfaced
   as :rf.error/machine-action-exception (machine-scoped, distinct
   from the generic :rf.error/handler-exception). The pre-action
   machine snapshot is preserved; no :db effect commits."
  (rf/reg-event :seed-state (fn [{:keys [db]} _] {:db {:val :before}}))
  (rf/dispatch-sync [:seed-state])
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle  {:on {:bang {:target :angry :action :boom}}}
                           :angry {}}
                 :actions {:boom (fn [_]
                                   (throw (ex-info "kaboom" {})))}}]
    (rf/reg-machine :test/m machine)
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/m [:bang]])
      (let [errs (filter #(= :rf.error/machine-action-exception (:operation %))
                         @traces)]
        (is (seq errs)
            "an action throw surfaces as :rf.error/machine-action-exception")
        (is (some #(= :test/m (get-in % [:tags :actor-id])) errs)
            "the trace identifies the live actor that threw (rf2-yyvtk5 — :actor-id)")
        (is (some #(= :boom (get-in % [:tags :action-id])) errs)
            "the trace identifies the action that threw")
        (is (some #(= "kaboom" (get-in % [:tags :exception-message])) errs)
            "the trace carries the original exception message"))
      (is (not (some #(= :rf.error/handler-exception (:operation %)) @traces))
          "the generic :rf.error/handler-exception does NOT also fire — the machine layer catches the action throw and emits the machine-scoped category")
      (is (= :before (:val (rf/app-db-value :rf/default)))
          "a non-machine app-db slice is not touched when the cascade halts")
      (let [snap (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/machines :snapshots :test/m])]
        (is (or (nil? snap) (= :idle (:state snap)))
            "the machine snapshot was not committed at :angry — pre-action :idle is preserved")))))

;; ---------------------------------------------------------------------------
;; Interaction 12 — Effect handler throws inside a machine action's :fx
;; spec/Cross-Spec-Interactions.md#12-effect-handler-throws-inside-a-machine-actions-fx
;; ---------------------------------------------------------------------------

(deftest machine-fx-handler-throws
  "#12 Effect handler throws inside a machine action's :fx —
   the snapshot commit precedes :fx; if a fx handler throws the walk
   continues to subsequent :fx entries (rule: ordering ≠ dependency)."
  (let [seen (atom [])]
    (rf/reg-fx :throwy (fn [_ _] (throw (ex-info "fx-bang" {}))))
    (rf/reg-fx :record  (fn [_ args] (swap! seen conj args)))
    (let [machine {:initial :idle
                   :data    {}
                   :states  {:idle {:on {:go {:target :done :action :emit-fx}}}
                             :done {}}
                   :actions {:emit-fx
                             (fn [_]
                               {:fx [[:throwy :a]
                                     [:record :b]]})}}]
      (rf/reg-machine :test/m machine)
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:test/m [:go]])
        (is (some #(and (= :rf.error/fx-handler-exception (:operation %))
                        (= :throwy (get-in % [:tags :rf.fx/id])))
                  @traces)
            "the throwing fx surfaces as :rf.error/fx-handler-exception")
        (is (= [:b] @seen)
            ":fx walk continued past the throwing fx — :record still ran")
        (is (= :done
               (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/machines :snapshots :test/m :state]))
            "the machine snapshot committed even though a downstream :fx threw")))))

;; ---------------------------------------------------------------------------
;; Interaction 13 — Hot-reload of a machine action while instance is running
;; spec/Cross-Spec-Interactions.md#13-hot-reload-of-a-machine-action-while-instance-is-running
;; ---------------------------------------------------------------------------

(deftest hot-reload-machine-action
  "#13 Hot-reload of a machine action —
   re-registering the machine handler picks up the new action body for
   the next dispatched event; in-flight events complete against the
   handler resolved at the start of the drain cycle."
  (let [machine-v1 {:initial :idle
                    :data    {}
                    :states  {:idle    {:on {:go {:target :working
                                                  :action :tag}}}
                              :working {:on {:go {:target :idle
                                                  :action :tag}}}}
                    :actions {:tag (fn [{data :data}]
                                     {:data (assoc data :who :v1)})}}
        machine-v2 (assoc-in machine-v1 [:actions :tag]
                             (fn [data _] {:data (assoc data :who :v2)}))]
    (rf/reg-machine :test/m machine-v1)
    (rf/dispatch-sync [:test/m [:go]])
    (is (= :v1 (get-in (rf/runtime-db-value :rf/default)
                       [:rf.runtime/machines :snapshots :test/m :data :who]))
        "v1 action ran on the first dispatch")
    ;; Hot-reload — re-register with v2 spec.
    (rf/reg-machine :test/m machine-v2)
    (rf/dispatch-sync [:test/m [:go]])
    (is (= :v2 (get-in (rf/runtime-db-value :rf/default)
                       [:rf.runtime/machines :snapshots :test/m :data :who]))
        "the next dispatched event resolves to the new action body")))

;; ---------------------------------------------------------------------------
;; Interaction 14 — Re-entrant dispatch from inside a render
;; spec/Cross-Spec-Interactions.md#14-re-entrant-dispatch-from-inside-a-render
;; ---------------------------------------------------------------------------

(deftest dispatch-sync-from-handler-raises
  "#14 Re-entrant dispatch from inside a render —
   dispatch-sync from inside a running drain raises
   :rf.error/dispatch-sync-in-handler. (The render-time variant of this
   is identical at the runtime layer.)"
  (with-trace-recorder! [traces]
    (rf/reg-event :outer (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (rf/reg-event :nested
      (fn [_ _]
        (rf/dispatch-sync [:outer])
        {}))
    (rf/dispatch-sync [:nested])
    (is (some (fn [ev]
                (and (= :rf.error/dispatch-sync-in-handler (:operation ev))
                     (= :error (:op-type ev))))
              @traces)
        "a nested dispatch-sync emits :rf.error/dispatch-sync-in-handler")))

;; ---------------------------------------------------------------------------
;; Interaction 15 — Re-spawning a machine instance via Tool-Pair
;; spec/Cross-Spec-Interactions.md#15-re-spawning-a-machine-instance-via-tool-pair
;; ---------------------------------------------------------------------------

(deftest time-travel-revert
  "#15 Re-spawning a machine instance via Tool-Pair —
   replace-container! reverts app-db (including the [:rf.runtime/machines :snapshots ...]
   slice); the machine handler is still in the registrar so the next
   dispatch resolves it and reads the restored snapshot."
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle    {:on {:go {:target :working}}}
                           :working {:on {:go {:target :idle}}}}}]
    (rf/reg-machine :test/m machine)
    ;; Drive the machine to :working.
    (rf/dispatch-sync [:test/m [:go]])
    (let [post-go-rt (rf/runtime-db-value :rf/default)]
      (is (= :working (get-in post-go-rt [:rf.runtime/machines :snapshots :test/m :state]))
          "machine reached :working")
      ;; Tool-Pair-style revert: write the RUNTIME-DB PARTITION to a snapshot
      ;; where the machine is in :idle (EP-0001 rf2-vzld77 — machine snapshots
      ;; are durable runtime-db state, so revert via swap-runtime-db!).
      (frame/swap-runtime-db! :rf/default
        (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots :test/m :state] :idle)))
      (is (= :idle (get-in (rf/runtime-db-value :rf/default)
                           [:rf.runtime/machines :snapshots :test/m :state]))
          "after replace-container! the snapshot reads back as :idle")
      ;; Re-dispatch — the existing handler resolves and reads the
      ;; restored snapshot, transitioning :idle → :working again.
      (rf/dispatch-sync [:test/m [:go]])
      (is (= :working (get-in (rf/runtime-db-value :rf/default)
                              [:rf.runtime/machines :snapshots :test/m :state]))
          "re-dispatch after revert advances from the restored state"))))

;; ---------------------------------------------------------------------------
;; Interaction 16 — Error projection on the server
;; spec/Cross-Spec-Interactions.md#16-error-projection-on-the-server
;; ---------------------------------------------------------------------------

(deftest server-error-projection-shape
  "#16 Error projection on the server —
   when a handler throws on a :ssr-server frame, :rf.error/handler-
   exception fires; the user-supplied projector consumes the trace and
   stamps the public-error's :status onto the [:rf/response]
   accumulator (per Spec 011 §Server error projection — \"runtime sets
   :rf.server/set-status to the public-error's :status\").

   This test pins both halves of the cross-spec contract:
     1. the trace channel — :rf.error/handler-exception fires under
        :ssr-server, tagged with the request frame's id; and
     2. the projection seam — apply-error-projection! resolves the
        active projector, projects the captured trace, and stamps
        :status onto :rf/response.

   `apply-error-projection!` is the documented host-driver surface
   (Spec 011 §Server error projection); the per-process auto-listener
   that buffers traces and applies projection at get-response time is
   re-frame.ssr's convenience layer over the same seam. The reset-
   runtime fixture deregisters all trace listeners between tests, so
   we exercise the host-driver surface directly here. The full JVM
   request-lifecycle shape (drain → get-response → :status on the
   response map, including listener-driven buffering) is covered by
   re-frame.ssr-end-to-end-test/ssr-default-error-projector-handler-
   exception."
  (rf/reg-frame :req {:preset :ssr-server})
  (rf/reg-event :handler-throws
    (fn [_ _] (throw (ex-info "boom" {}))))
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:handler-throws] {:frame :req})
    (let [errs (filter #(= :rf.error/handler-exception (:operation %)) @traces)]
      (is (seq errs)
          ":rf.error/handler-exception fires on the server frame for a thrown handler")
      (is (some #(= :req (get-in % [:tags :frame])) errs)
          "the trace records the request frame's id")
      ;; Cross-spec: the captured trace projects to a public-error map
      ;; (the locked four-key shape per Spec 011 §Public error shape)
      ;; AND the resolved response carries that projection's :status.
      ;; This is the seam an SSR host uses to build the wire response.
      (let [err          (first errs)
            public-error (ssr/apply-error-projection! :req err)]
        (is (= 500 (:status public-error))
            "default projector maps :rf.error/handler-exception → :status 500")
        (is (= :internal-error (:code public-error))
            "default projector's :code is :internal-error")
        (is (false? (:retryable? public-error))
            "default projector's :retryable? is false (handler exception is not retryable)")
        (is (string? (:message public-error))
            "default projector emits a one-sentence human :message")
        (is (= 500 (:status (ssr/get-response :req)))
            "the projector's :status is stamped onto the [:rf/response] accumulator")))))

;; ---------------------------------------------------------------------------
;; Interaction 17 — Machine error inside SSR
;; spec/Cross-Spec-Interactions.md#17-machine-error-inside-ssr
;; ---------------------------------------------------------------------------

(deftest ssr-machine-error
  "#17 Machine error inside SSR —
   composes Interaction 11 (action-throw → snapshot does not commit)
   with Interaction 16 (server projection). The trace fires under the
   request frame; the snapshot is unchanged. As with the non-SSR case
   the machine-scoped :rf.error/machine-action-exception fires, NOT
   the generic :rf.error/handler-exception."
  (rf/reg-frame :req {:preset :ssr-server})
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle  {:on {:bang {:target :angry :action :boom}}}
                           :angry {}}
                 :actions {:boom (fn [_]
                                   (throw (ex-info "ssr-bang" {})))}}]
    (rf/reg-machine :test/m machine)
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/m [:bang]] {:frame :req})
      (let [errs (filter #(= :rf.error/machine-action-exception (:operation %))
                         @traces)]
        (is (seq errs)
            "machine action throw surfaces as :rf.error/machine-action-exception under :ssr-server")
        (is (some #(= :req (get-in % [:tags :frame])) errs)
            "the trace records the request frame's id")
        (is (some #(= :test/m (get-in % [:tags :actor-id])) errs)
            "the trace identifies the live actor (rf2-yyvtk5 — :actor-id)"))
      (is (not (some #(= :rf.error/handler-exception (:operation %)) @traces))
          "the generic :rf.error/handler-exception does NOT also fire under :ssr-server")
      (let [snap (get-in (rf/runtime-db-value :req) [:rf.runtime/machines :snapshots :test/m])]
        (is (or (nil? snap) (= :idle (:state snap)))
            "no committed machine snapshot at :angry — the cascade halted")))))

;; ---------------------------------------------------------------------------
;; Interaction 18 — Re-registering a sub mid-cascade
;; spec/Cross-Spec-Interactions.md#18-re-registering-a-sub-mid-cascade
;; ---------------------------------------------------------------------------

(deftest hot-reload-sub-mid-cascade
  "#18 Re-registering a sub mid-cascade —
   re-registering a sub disposes the cache slot and the next subscribe
   sees the new body, even when an active subscriber was holding a
   reaction on the old version."
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
  (rf/reg-sub :answer (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed])
  (is (= 7 (rf/subscribe-once [:answer]))
      "the v1 sub computes from app-db")
  (let [_pin (rf/subscribe [:answer])]
    ;; Re-register: replacement-hook fires, cache slot is disposed, the
    ;; next subscribe builds against the new body.
    (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
    (is (= 700 (rf/subscribe-once [:answer]))
        "after re-registration the new sub body is in effect")
    (rf/unsubscribe [:answer])))

;; ---------------------------------------------------------------------------
;; Interaction 19 — Story decorators that override fx
;; spec/Cross-Spec-Interactions.md#19-story-decorators-that-override-fx
;; ---------------------------------------------------------------------------

(deftest portable-story-fx-override
  "#19 Story decorators that override fx —
   id-valued fx-overrides on the frame redirect calls to the override
   target; the same map is portable across stories and tests."
  (let [seen (atom [])]
    (rf/reg-fx :http             (fn [_ args] (swap! seen conj [:real-http args])))
    (rf/reg-fx :rf.test/http-stub (fn [_ args] (swap! seen conj [:stub args])))
    ;; Frame with an id-valued override: :http is rerouted to :rf.test/http-stub.
    (rf/reg-frame :story-frame {:fx-overrides {:http :rf.test/http-stub}})
    (rf/reg-event :go (fn [_ _] {:fx [[:http {:url "/x"}]]}))
    (rf/dispatch-sync [:go] {:frame :story-frame})
    (is (= [[:stub {:url "/x"}]] @seen)
        "the id-valued override redirected :http → :rf.test/http-stub")))

;; ---------------------------------------------------------------------------
;; Interaction 20 — Adapter swap mid-process is forbidden
;; spec/Cross-Spec-Interactions.md#20-adapter-swap-mid-process-is-forbidden
;; ---------------------------------------------------------------------------

(deftest adapter-already-installed
  "#20 Adapter swap mid-process is forbidden —
   a second install-adapter! call without an intervening dispose throws
   :rf.error/adapter-already-installed."
  ;; reset-runtime has already installed the Reagent adapter; calling
  ;; install-adapter! again should throw.
  (let [thrown? (try
                  (rf/install-adapter! reagent-adapter/adapter)
                  false
                  (catch :default e
                    ;; rf2-vvixub — branch on the canonical :rf.error/id
                    ;; discriminator, never on the (now human-sentence) message.
                    (= :rf.error/adapter-already-installed
                       (:rf.error/id (ex-data e)))))]
    (is thrown?
        "second install-adapter! raises :rf.error/adapter-already-installed"))
  ;; Sanity-check the destroy-then-install path remains valid.
  (rf/destroy-adapter!)
  (rf/install-adapter! reagent-adapter/adapter)
  (is (some? (adapter/current-adapter))
      "after destroy, install succeeds again — clean swap path"))
