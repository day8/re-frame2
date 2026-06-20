(ns re-frame.framework-zero-ownership-diagnostics-test
  "EP-0001 cross-subsystem conformance sweep: the FRAMEWORK must never trip
  its OWN runtime-db ownership diagnostics.

  The routing-authority gap shipped unnoticed because no test asserted that
  the framework does not trip its own EP-0001 ownership diagnostics — the
  routing fix added a routing-focused regression; THIS namespace is the
  BROADER cross-subsystem sweep. It exercises a representative set of real
  framework flows across EVERY runtime-db-writing subsystem Spec 002 §Write
  authority names — routing, machines, elision, and SSR hydration — while
  recording the trace stream, and asserts that NONE of the three runtime-db
  ownership diagnostics fire from framework-registered handlers:

    - `:rf.warning/app-handler-runtime-effect` — a handler returned the
      reserved `:rf.db/runtime` effect without framework-write authority
      (the diagnostic the routing gap was tripping on every navigation).
    - `:rf.error/legacy-runtime-root` — a handler returned a `:db` value
      carrying the retired `:rf/runtime` app-db root.
    - `:rf.error/effect-map-shape` — a malformed effect-map shape.

  Why this matters (Mike ruling #4 — convention + reliable diagnostics):
  `:rf.db/runtime` is reserved BY CONVENTION for framework / runtime-
  extension code, surfaced through a dev diagnostic rather than enforced.
  The diagnostic only retains teaching value if the framework itself never
  fires it. A framework subsystem that trips its own ownership diagnostic
  trains users that the warning is noise (it polluted the Xray Issues lens
  on every navigation before the fix). This sweep is the regression guard
  that keeps every runtime-db writer quiet.

  ## Home + fixture

  This lives in the SSR artefact's test tree because the SSR `:test` alias
  is the only one whose dep fan-out pulls in core + schemas + flows +
  routing + machines together (see ssr/deps.edn), and `tf/reset-runtime`
  is the only fixture that reloads routing / ssr / machines so all three
  subsystems' ns-load registrations resurrect between tests against the
  plain-atom-shaped SSR adapter. That single home lets one sweep cover
  every runtime-db-writing subsystem.

  ## Flows covered

    1. `:rf.route/navigate`             — programmatic navigation.
    2. `:rf.route/transitioned`         — URL-driven forward nav.
    3. `:rf.route/handle-url-change`    — popstate / initial / SSR URL feed.
    4. can-leave pending-nav protocol   — `:rf/url-requested` /
       `:rf.route/cancel` / `:rf.route/continue`.
    5. `:rf.route.internal/settle-transition` — per-route `:on-match` settle.
    6. machine lifecycle                — reg + first-dispatch bootstrap,
       declarative `:spawn`, explicit `[:rf.machine/destroy …]`.
    7. elision classification install   — frame-owned declaration install
       into `[:rf.runtime/elision …]`.
    8. SSR `:rf/hydrate`                — runtime-db partition install.

  The control deftest at the bottom proves the recorder + diagnostic are
  LIVE in this fixture: an ordinary (non-framework) app handler returning
  `:rf.db/runtime` DOES fire the warning, so the framework-quiet assertions
  above are not vacuously empty."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame-classification :as frame-class]
            ;; The routing / ssr / machines subsystem namespaces are loaded
            ;; (and re-installed between tests) by `tf/reset-runtime`, so
            ;; their `:rf.route/*` / `:rf/hydrate` / machine-lifecycle event
            ;; + fx registrations are live without an explicit require here.
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(def ^:private ownership-diagnostics
  "The three EP-0001 runtime-db ownership diagnostics. A FRAMEWORK-
  registered handler must never trip any of them."
  #{:rf.warning/app-handler-runtime-effect
    :rf.error/legacy-runtime-root
    :rf.error/effect-map-shape})

(defn- record-ownership-diagnostics!
  "Register a trace listener under `listener-id` that captures every
  trace event whose `:operation` is one of the three EP-0001 runtime-db
  ownership diagnostics (whether emitted as a `:warning` or an `:error`).
  Returns the capture atom. Matching on `:operation` alone catches all
  three regardless of `:op-type`."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace
      listener-id
      (fn [ev]
        (when (contains? ownership-diagnostics (:operation ev))
          (swap! a conj ev))))
    a))

(defn- stub-push-url! []
  (rf/reg-fx :rf.nav/push-url
             {:platforms #{:server :client}}
             (fn [_ _] nil)))

(defn- diagnostic-ids
  "The `:operation` ids captured, for readable failure messages."
  [warns]
  (mapv :operation @warns))

;; ===========================================================================
;; Routing — every navigation event is a framework-authority writer
;; ===========================================================================

(deftest routing-flows-fire-no-ownership-diagnostic
  (testing "navigate / transitioned / handle-url-change / settle / can-leave stay silent"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/search  {} "/search")
    ;; `:on-match` route → drives the FIFO settle handler
    ;; (`:rf.route.internal/settle-transition`), which writes the
    ;; `:transition` state into the runtime-db slice via `:rf.db/runtime`.
    (rf/reg-event :load/noop (fn [{:keys [db]} _] {:db db}))
    (rf/reg-route :route/loaded  {:on-match [[:load/noop]]} "/loaded")
    (stub-push-url!)
    (let [diags (record-ownership-diagnostics! ::routing)]
      ;; (1) :rf.route/navigate — programmatic navigation.
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (is (= :route/article (get-in (rf/runtime-db-value :rf/default)
                                    [:rf.runtime/routing :current :route-id]))
          ":rf.route/navigate wrote the route slice (:rf.db/runtime applied)")

      ;; (2) :rf.route/transitioned — URL-driven forward nav.
      (rf/dispatch-sync [:rf.route/transitioned "/search?q=widgets"])
      (is (= :route/search (get-in (rf/runtime-db-value :rf/default)
                                   [:rf.runtime/routing :current :route-id]))
          ":rf.route/transitioned wrote the route slice")

      ;; (3) :rf.route/handle-url-change — popstate / initial / SSR feed.
      (rf/dispatch-sync [:rf.route/handle-url-change "/"])
      (is (= :route/home (get-in (rf/runtime-db-value :rf/default)
                                 [:rf.runtime/routing :current :route-id]))
          ":rf.route/handle-url-change wrote the route slice")

      ;; (5) :rf.route.internal/settle-transition — per-route :on-match settle.
      (rf/dispatch-sync [:rf.route/transitioned "/loaded"])
      (is (= :route/loaded (get-in (rf/runtime-db-value :rf/default)
                                   [:rf.runtime/routing :current :route-id]))
          "the :on-match route settled onto the slice")

      (is (empty? @diags)
          (str "routing navigation events are framework-authority writers — "
               "no ownership diagnostic; got " (diagnostic-ids diags))))))

(deftest can-leave-pending-nav-fires-no-ownership-diagnostic
  (testing "the pending-nav protocol (url-requested / cancel / continue) stays silent"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty
                     (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (stub-push-url!)
    (let [diags (record-ownership-diagnostics! ::can-leave)]
      ;; Land on the guarded route, dirty it, attempt to leave → blocked
      ;; (`:rf/url-requested` writes the pending slot via `:rf.db/runtime`).
      (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (is (some? (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/routing :pending-navigation]))
          ":rf/url-requested wrote the pending-navigation slot")
      ;; CANCEL clears the slot (a :rf.db/runtime write).
      (rf/dispatch-sync [:rf.route/cancel "pn-1"])
      (is (nil? (get-in (rf/runtime-db-value :rf/default)
                        [:rf.runtime/routing :pending-navigation]))
          ":rf.route/cancel cleared the pending slot")
      ;; Re-block, then CONTINUE (a :rf.db/runtime write + completion).
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/dispatch-sync [:rf.route/continue "pn-2"])
      (is (= :route/cart (get-in (rf/runtime-db-value :rf/default)
                                 [:rf.runtime/routing :current :route-id]))
          ":rf.route/continue completed the navigation")
      (is (empty? @diags)
          (str "url-requested / cancel / continue are framework-authority "
               "writers — no ownership diagnostic; got " (diagnostic-ids diags))))))

;; ===========================================================================
;; Machines — :rf/machine? implies framework-write authority
;; ===========================================================================

(deftest machine-lifecycle-fires-no-ownership-diagnostic
  (testing "reg + first-dispatch bootstrap, declarative :spawn, explicit destroy stay silent"
    ;; A standalone child whose snapshot is written to runtime-db on its
    ;; first dispatch (bootstrap cascade). A parent that spawns the child
    ;; declaratively on entry, and tears it down via an explicit
    ;; `[:rf.machine/destroy …]` fx — exercising the spawn + destroy
    ;; lifecycle fxs that write `[:rf.runtime/machines …]` via :rf.db/runtime.
    (rf/reg-machine :zod/child
                    {:initial :running
                     :data    {}
                     :states  {:running {}}})
    (rf/reg-machine :zod/parent
                    {:initial :idle
                     :data    {}
                     :states
                     {:idle    {:on {:start :working}}
                      :working {:spawn {:machine-id :zod/child}
                                :on    {:kill   :tearing
                                        :done   :idle}}
                      :tearing {:entry (fn [_] {:fx [[:rf.machine/destroy :zod/child]]})
                                :on    {:done :idle}}}})
    (let [diags (record-ownership-diagnostics! ::machines)]
      ;; (a) singleton bootstrap: first dispatch synthesises + commits the
      ;; snapshot into runtime-db.
      (rf/dispatch-sync [:zod/child [:rf.machine/noop]])
      (is (some? (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/machines :snapshots :zod/child]))
          "machine bootstrap committed a snapshot to runtime-db")

      ;; (b) declarative :spawn — parent enters :working, spawn fx allocates
      ;; the child actor into runtime-db.
      (rf/dispatch-sync [:zod/parent [:start]])
      (is (some? (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/machines :snapshots :zod/parent]))
          "the parent machine committed its snapshot to runtime-db")

      ;; (c) explicit destroy — the destroy fx clears the actor from runtime-db.
      (rf/dispatch-sync [:zod/parent [:kill]])

      (is (empty? @diags)
          (str "machine reg / dispatch / spawn / destroy are framework-"
               "authority writers (via :rf/machine?) — no ownership "
               "diagnostic; got " (diagnostic-ids diags))))))

;; ===========================================================================
;; Elision — frame-owned classification install goes through privileged
;; frame-state helpers
;; ===========================================================================

(deftest elision-population-fires-no-ownership-diagnostic
  (testing "frame-owned classification install stays silent"
    ;; EP-0015 §8 (rf2-d2r3um): durable app-db egress classification is
    ;; FRAME-OWNED. `frame-class/install!` writes the `:source :frame`
    ;; declarations into `[:rf.runtime/elision …]` through
    ;; `elision/swap-elision-slot!` → `frame/swap-runtime-db!` (a privileged
    ;; frame-state helper) — NOT through an event-effect, so it never reaches
    ;; the `:rf.db/runtime` effect path. This guards against a future change
    ;; that routes it through an app-visible event handler. (The frame
    ;; container — `:rf/default` — already exists via `tf/reset-runtime`.)
    (let [diags (record-ownership-diagnostics! ::elision)]
      (frame-class/install!
        :rf/default
        (frame-class/validate+extract
          :rf/default
          {:large     {:app-db [[:profile :avatar]]}
           :sensitive {:app-db [[:profile :ssn]]}}))
      (is (seq (elision/declarations :rf/default))
          "the :large frame path was installed into the elision registry")
      (is (seq (elision/sensitive-declarations :rf/default))
          "the :sensitive frame path was installed into the elision registry")
      (is (some? (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/elision]))
          "the frame-owned install wrote its declaration registry into runtime-db")
      (is (empty? @diags)
          (str "frame-owned classification install writes runtime-db through "
               "privileged frame-state helpers — no ownership diagnostic; got "
               (diagnostic-ids diags))))))

;; ===========================================================================
;; SSR hydrate — :rf/hydrate is stamped :rf/framework-authority? true
;; ===========================================================================

(deftest ssr-hydrate-fires-no-ownership-diagnostic
  (testing ":rf/hydrate installs the runtime-db partition without tripping the diagnostic"
    ;; The :rf/hydrate handler returns BOTH `:db` (the server app-db slice)
    ;; AND `:rf.db/runtime` (the hydration metadata + server-settled
    ;; runtime-db slice). It is stamped `{:rf/framework-authority? true}` at
    ;; registration, so it writes the reserved partition in-bounds.
    (let [diags (record-ownership-diagnostics! ::ssr)
          payload {:rf/version     1
                   :rf/frame-id    :rf/default
                   :rf/app-db      {:greeting "hello from server"}
                   :rf/runtime-db  {:rf.runtime/machines {:snapshots {}}}
                   :rf/render-hash "deadbeef"}]
      (rf/dispatch-sync [:rf/hydrate payload])
      (is (= "hello from server" (:greeting (rf/app-db-value :rf/default)))
          ":rf/hydrate replaced app-db with the server slice")
      (is (= "deadbeef" (get-in (rf/runtime-db-value :rf/default)
                                [:rf.runtime/ssr :hydration :server-hash]))
          ":rf/hydrate stashed the server-hash into the runtime-db partition")
      (is (empty? @diags)
          (str ":rf/hydrate is a framework-authority writer "
               "(:rf/framework-authority? true) — no ownership diagnostic; got "
               (diagnostic-ids diags))))))

;; ===========================================================================
;; Control — an ordinary app handler DOES fire the diagnostic
;; ===========================================================================

(deftest ordinary-app-handler-returning-runtime-db-still-warns
  (testing "a non-framework handler returning :rf.db/runtime DOES fire the diagnostic"
    ;; Proves the recorder + diagnostic are live in this fixture — every
    ;; framework-quiet assertion above is therefore meaningful, not
    ;; vacuously empty.
    (rf/reg-event :app/sneaky-runtime-write
                     (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :hijacked}}}}))
    (let [diags (record-ownership-diagnostics! ::app-sneaky)]
      (rf/dispatch-sync [:app/sneaky-runtime-write])
      (is (= [:rf.warning/app-handler-runtime-effect] (diagnostic-ids diags))
          "a non-framework handler writing :rf.db/runtime trips exactly the warning")
      (is (= :app/sneaky-runtime-write (-> @diags first :tags :rf.trace/event-id))
          "the diagnostic names the offending app event-id"))))
