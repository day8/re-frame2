(ns standard-epochs.core
  "STANDARD-EPOCHS testbed — a deliberately simple Xray driving surface.

  ## Shape — the shared queued-step RUNNER

  ONE purple Step button (`<prefix>-step`) walks a step ladder top to
  bottom while the operator watches how Xray's panels render each step;
  EVERY step row's index is ALSO a RANDOM-ACCESS RUN-THIS-STEP button
  (`<prefix>-step-<n>-run`, n = 0-based step index) so any step can be
  driven directly. The runner (`runner.core`) is the shared harness; this
  deck supplies the `steps` vector (CODE DATA) and is mounted with a
  host-frame + a testid prefix (the cursor lives in app-db's `:step`, not
  a passed atom). Each step DISPATCHES one event that

    (a) lands on the run-step epoch as the `:step` app-db delta the panels
        show (the runner sets `:step = n`), and
    (b) exercises exactly ONE additional feature via a child dispatch.

  Progressive: step 1 is trivial; each later step layers one more concept.
  There are NO tabs, NO routing/URL machinery, NO machines, NO SSR. Read
  each step's `:watch` note; Xray itself is the check.

  ## Parameterised root — the two-frame mount

  `root` is a PURE ladder taking `[host-frame prefix run-step-event]` —
  just the runner + the two children + the diamond probe, NO header, NO
  reset button, NO runner atom (the runner cursor lives in app-db's
  `:step`, not a passed atom). The single-frame deck (`run`) mounts
  the `standalone` wrapper, which renders the deck's title + intro header
  ABOVE `[root :rf/default \"standard-epochs\"]` on the plain default frame,
  Xray auto-mounting inline. The TWO-FRAME isolation testbed
  (`two_frame_isolation`) mounts this SAME `root` once per `:above` /
  `:below` frame-provider, each with its OWN host-frame + a DISTINCT prefix
  — so the two reactive contexts (and the two per-frame `:step` cursors)
  stay genuinely independent (per-frame app-db gives each mount its own
  `:step`). Keeping the header OUT of `root` is what stops the two-frame
  cards showing the title twice (mislabel) and leaves them as clean
  per-frame ladders.

  ## North star (acceptance)

  Pick ANY ONE Xray panel — Epoch, App-db, Views, or Trace — (or the
  inline issue surfacing — Epoch issue blocks · L2 event-row wash · the
  issues ribbon signal; the standalone Issues tab was removed per
  rf2-gbz39) and step through top to bottom: that panel is COMPLETELY
  exercised. The step set is chosen for per-panel coverage completeness,
  not one-lens-per-step.

  ## Per-panel coverage map

    Epoch   — every step is a run-step epoch (the `:step` delta + the
              dispatched child event); #2 adds a coeffect to the event
              detail, #4 a one-shot fx, #5 a cascade dispatch-id tree.
    App-db  — the `:step` cursor churns every step · #5 flow writes a
              derived slot. (The rich App-db DIFF shapes — added /
              removed-to-empty / changed — plus the large-collection /
              edn-inspector cases live in the sibling `edn_inspector` deck,
              which drives them through the App-db panel.)
    Views   — two children make re-render CAUSES separable. Child A is
              SUBSCRIPTION-driven (an own L1→L2→L3 chain + the arg-keyed
              `[:standard-epochs/greater-than? N]` sub); Child B is
              PROPS-driven (a prop, NO subs). #6 mount A (node + the
              sub-cache entries appear) · #7 change the arg N (a NEW
              [:gt? N] cache entry — cache keyed by arg) · #8 change a
              chain input (L1→L2→L3 invalidation recompute; A re-renders
              ← a SUB changed) · #9 unmount A (node gone, ALL of A's
              subs disposed, the unmount recorded) · #10 mount B (props
              view, NO subs created) · #11 change B's prop (B re-renders
              ← PROPS changed — the foil to #8). The section lives on
              its OWN slots (`:views/*`), so mount/unmount/sub state is
              exercised DIRECTLY — no start-at-button-1 sequencing, no
              :base/flow confound.
    Trace   — every button emits trace; #3 a managed fx, #4 a cascade,
              #5 a flow recompute, #8 a sub-chain recompute.
    Issues  — #12 handler exception (db rolls back) · #13 interceptor
              :before exception (handler skipped) · #14 interceptor :after
              exception (handler ran, threw on the way out) · #15 coeffect
              exception · #16 effect exception (post-commit, best-effort) ·
              #17 slow fx flagged · #18 event-args schema violation · #19
              app-db schema violation (survives rollback).
    Reactive— #20 diamond probe (c ← a,b ← root): the join sub's recompute
              count surfaces whether the substrate double-computes an
              intermediate sub per single root change.

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: no deliberate bugs as
  anti-patterns, no teaching layers. The exception / slow-fx / schema
  buttons exercise the REAL error / Issues surface — each is a feature
  being driven, not a buggy demo. Captions are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; regression coverage
  lives in the substrate contract tests + the Xray feature-matrix gate.
  The events / subs / views below are OWNED here — this deck does NOT
  reuse the shared `testdeck.*` modules (whose coupling to the two-frame
  + routing surfaces is what made the step-deck inflexible)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Flows artefact — load-time hook so `reg-flow` resolves
            ;; (button #5 writes a derived slot via a flow).
            [re-frame.flows]
            ;; Schemas artefact — load-time hook so `reg-app-schema` and
            ;; the `:schema` event metadata resolve. The Malli adapter
            ;; publishes the validator so CLJS schema checks actually
            ;; fire (without it they soft-pass and buttons #18/#19 would
            ;; produce no Issues row).
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's `configure!` to seed `:project-root` so the Event
            ;; lens 'open' chip resolves a classpath-relative `:file` to
            ;; an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper: derives the open-in-editor
            ;; project-root from the build env.
            [re-frame.testbed.config :as testbed-config]
            ;; The shared step-driver runner. This deck supplies a `steps`
            ;; vector + registers `:standard-epochs/run-step` via
            ;; `runner/reg-runner!`; the runner drives the ONE-button series
            ;; + the per-step RUN buttons off app-db `:step`. The two-frame
            ;; testbed reuses `root` + `steps` with its own per-frame
            ;; host-frame + run-step events.
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:step` is the runner cursor — the index of the
;; last-run step, written by `runner.core`'s run-step event. `:step`
;; churning every step IS the per-step App-db / Epoch delta the panels
;; show. `:base` feeds button #5's flow only.
;;
;; `:views` holds the Views/subscriptions section's OWN state — kept on
;; its own slots, so the section's mount/unmount/sub behaviour is exercised
;; DIRECTLY (no start-at-button-1 sequencing, no :base/flow confound). It is
;; app-db state (not a component-local atom) so it is observable in Xray:
;;
;;   :a-mounted? / :b-mounted?  — the two children's mount slots.
;;   :threshold                 — N, the changeable arg to the dynamic
;;                                sub `[:standard-epochs/greater-than? N]`.
;;   :chain-input               — the root of Child A's own L1→L2→L3
;;                                chain (button #8 perturbs it).
;;   :b-prop                    — the prop fed to the props-driven
;;                                Child B (button #11 changes it).

(def initial-db
  {:step     nil
   :base     1
   :auth     {:token "seed-token"}
   :views    {:a-mounted?  false
              :b-mounted?  false
              :threshold   5
              :chain-input 1
              :b-prop      "alpha"
              :diamond-root 0}})

(rf/reg-event :standard-epochs/reset
  {:doc "Seed event — re-seed app-db and unmount both child views. Used by
         `run` (dispatch-sync on load) and by two_frame_isolation's per-frame
         :on-create. There is no on-page reset button; a reload re-seeds.
         The runner cursor `:step` re-seeds to nil here."}
  (fn handler-reset [_ _ev]
    {:db initial-db}))

;; ============================================================================
;; APP-DB SCHEMA (button #19)
;; ============================================================================
;;
;; The only constraint: [:auth :token] must be a string. Button #19
;; writes an int there; the post-handler app-db validation (Spec 010
;; §Validation order) rejects it and rolls the :db effect back, while the
;; schema-violation issue surfaces inline — the Epoch panel's issue block,
;; the L2 event-row wash, and the issues ribbon signal (the standalone
;; Issues tab was removed per rf2-gbz39).

(def AuthSlice [:map [:token :string]])
;; EP-0002 (rf2-5q7um6): reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This testbed's deck
;; hosts on :rf/default (see `host-frame` below), so name it explicitly.
(with-frame :rf/default
  (rf/reg-app-schema [:auth] AuthSlice))

;; ============================================================================
;; COEFFECT — :standard-epochs/now  (button #2)
;; ============================================================================
;;
;; A wall-clock supplier so the handler stays a pure fn of (coeffects,
;; event). EP-0017: `reg-cofx` is value-returning + graded; this is an
;; AMBIENT cofx (a host-transient read, never recorded — display chrome,
;; not durable state). Xray's Epoch event-detail shows this coeffect
;; feeding the handler.

(rf/reg-cofx :standard-epochs/now
  {:doc "Ambient wall-clock supplier — the current time (ms since epoch),
         delivered under `:standard-epochs/now` to declaring handlers."}
  (fn cofx-now [] (.getTime (js/Date.))))

;; A coeffect supplier that throws when it runs (button #15). A FEATURE
;; being exercised — the supported way to light up the cofx error surface
;; (`:rf.error/coeffect-exception`, EP-0017 §8).
(rf/reg-cofx :standard-epochs/throwing-cofx
  {:doc "Throws when its supplier runs at context assembly so Xray's Issues
         lens surfaces a cofx error. A feature being exercised, not a buggy
         demo."}
  (fn cofx-throws []
    (throw (ex-info "standard-epochs / coeffect (intentional — exercises the cofx error surface)"
                    {:surface :coeffect-exception}))))

;; ============================================================================
;; INTERCEPTORS that throw — one in :before (button #13), one in :after
;; (button #14)
;; ============================================================================
;;
;; Two throwing interceptors so the per-step placement work can tell the
;; two halves of the interceptor chain apart. The :before interceptor
;; aborts on the way IN (before the handler runs); the :after interceptor
;; runs the handler successfully, then throws on the way OUT. Pairing them
;; makes the framework's per-step exception attribution (the :before-chain
;; vs interceptor-:after distinction) visible live in Xray.

;; Throws in :before — aborts before the handler runs (button #13).
(def throwing-interceptor
  (rf/->interceptor
    :id     :standard-epochs/throwing-interceptor
    :before (fn interceptor-before-throws [_ctx]
              (throw (ex-info "standard-epochs / interceptor :before (intentional — exercises the interceptor :before error surface)"
                              {:surface :interceptor-exception :phase :before})))))

;; EP-0022 reference-only flip: chains carry references only, so register the
;; interceptor value and reference it by id from the event chain (button #13).
(rf/reg-interceptor* :standard-epochs/throwing-interceptor throwing-interceptor)

;; Throws in :after — the handler runs to completion first, THEN this
;; throws on the way back out of the chain (button #14). The foil to the
;; :before interceptor above: the failing step is the interceptor's :after,
;; not the handler, so the per-step placement renders the exception under
;; the interceptor's :after step and the framework attributes it to the
;; interceptor rather than collapsing it into a handler exception.
(def throwing-interceptor-after
  (rf/->interceptor
    :id    :standard-epochs/throwing-interceptor-after
    :after (fn interceptor-after-throws [_ctx]
             (throw (ex-info "standard-epochs / interceptor :after (intentional — exercises the interceptor :after error surface)"
                             {:surface :interceptor-exception :phase :after})))))

;; EP-0022 reference-only flip: register the value and reference it by id from
;; the event chain (button #14).
(rf/reg-interceptor* :standard-epochs/throwing-interceptor-after throwing-interceptor-after)

;; ============================================================================
;; EFFECTS
;; ============================================================================

;; A one-shot, instantaneous fx (button #3). Records its calls so the
;; effect genuinely runs; Xray's Trace / Effects show it fire this epoch.
(defonce ping-log (atom []))
(rf/reg-fx :standard-epochs/ping
  (fn fx-ping [_ctx args]
    (swap! ping-log conj args)))

;; A managed slow fx (~600ms, button #17). Resolves later with a
;; follow-on dispatch back onto the originating frame. ~600ms exceeds
;; Spec 009's slow-effect threshold, so Xray's inline issue surfacing
;; (the Epoch panel + the issues ribbon signal) flags it as a (non-bug)
;; slow effect; the status moves :loading -> :loaded.
(def SLOW-MS 600)
(rf/reg-fx :standard-epochs/slow-fetch
  (fn fx-slow-fetch [{:keys [frame]} _args]
    (js/setTimeout
      (fn [] (rf/dispatch [:standard-epochs/slow-done] {:frame frame}))
      SLOW-MS)))

;; An fx whose body throws (button #16). The handler's :db commits first;
;; the throw fires later, during the post-commit fx walk — best-effort
;; per the FX atomicity asymmetry — so Xray's inline issue surfacing
;; (the Epoch panel + the L2 event-row wash + the issues ribbon signal)
;; shows the fx error while the baseline bump survives.
(rf/reg-fx :standard-epochs/boom
  (fn fx-boom [_ctx _args]
    (throw (ex-info "standard-epochs / effect (intentional — exercises the fx error surface)"
                    {:surface :effect-exception}))))

;; ============================================================================
;; FLOW (button #5) — a reg-flow-derived slot recomputes into app-db
;; ============================================================================
;;
;; `:base` is doubled into `:derived` on every drain where `:base`
;; changed. Button #5 bumps `:base`; App-db shows `:derived` recompute
;; and Trace shows the flow run.

;; EP-0002 (rf2-5q7um6): reg-flow is context-required frame-local; name the
;; :rf/default host frame explicitly for this ns-load registration.
(with-frame :rf/default
  (rf/reg-flow
    {:id     :standard-epochs/derived
     :inputs [[:base]]
     :output (fn [base] (* 2 (or base 0)))
     :path   [:derived]
     :doc    "Derived = 2 × :base. Recomputes on the post-handler flows pass."}))

;; ============================================================================
;; EVENTS — the button ladder
;; ============================================================================

;; -- 1. plain increment ------------------------------------------------------
(rf/reg-event :standard-epochs/increment
  {:doc "Step 1 — a plain event. The per-step App-db delta is the runner's
         `:step` write on the parent run-step epoch; Epoch shows THIS event
         firing with db-before / db-after (no further write)."}
  (fn handler-increment [{:keys [db]} _ev] {:db db}))

;; -- 2. increment + coeffect -------------------------------------------------
(rf/reg-event :standard-epochs/increment-cofx
  {:doc "Button 2 — declare the `:standard-epochs/now` cofx via
         `:rf.cofx/requires`. Epoch's event detail shows the coeffect
         feeding the handler (EP-0017 declared-only delivery)."
   :rf.cofx/requires [:standard-epochs/now]}
  (fn handler-increment-cofx [{:keys [db standard-epochs/now]} _ev]
    {:db (assoc db :last-clicked now)}))

;; -- 3. increment + effect ---------------------------------------------------
(rf/reg-event :standard-epochs/increment-fx
  {:doc "Button 3 — return a one-shot fx. Effects / Trace show
         `:standard-epochs/ping` fire this epoch."}
  (fn handler-increment-fx [{:keys [db]} _ev]
    {:db db
     :fx [[:standard-epochs/ping {:at (.getTime (js/Date.))}]]}))

;; -- 4. increment + cascade --------------------------------------------------
(rf/reg-event :standard-epochs/increment-cascade
  {:doc "Button 4 — dispatch a follow-on event. Epoch's dispatch-id tree
         shows the cascade (this event → :standard-epochs/cascade-tail)."}
  (fn handler-increment-cascade [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:standard-epochs/cascade-tail]]]}))

(rf/reg-event :standard-epochs/cascade-tail
  {:doc "The follow-on event dispatched by button 4 — the second epoch in
         the cascade under one root dispatch-id."}
  (fn handler-cascade-tail [{:keys [db]} _ev] {:db db}))

;; -- 5. increment + flow -----------------------------------------------------
(rf/reg-event :standard-epochs/increment-flow
  {:doc "Button 5 — perturb :base so the `:standard-epochs/derived` flow
         recomputes a derived slot into app-db; App-db / Trace show it."}
  (fn handler-increment-flow [{:keys [db]} _ev]
    {:db (update db :base inc)}))

;; -- 6..11. Views / subscriptions — sub-driven Child A + props-driven Child B
;;
;; The whole section lives on the `:views` slots: mount/unmount/sub state is
;; never linked to the runner cursor. Two children separate the re-render
;; CAUSE — Child A re-renders ← a SUB changed (#8); Child B re-renders ←
;; PROPS changed (#11).

(rf/reg-event :standard-epochs/mount-a
  {:doc "Button 6 — mount the SUBSCRIPTION-driven Child A (sets
         :views/a-mounted? true). On mount A subscribes its own
         L1→L2→L3 chain (:chain-root → :chain-doubled → :chain-labelled)
         PLUS the arg-keyed `[:standard-epochs/greater-than? N]` sub — so
         Views shows the node and those sub-cache entries appear."}
  (fn handler-mount-a [{:keys [db]} _ev]
    {:db (assoc-in db [:views :a-mounted?] true)}))

(rf/reg-event :standard-epochs/set-threshold
  {:doc "Button 7 — change the sub-arg N (5 → 10). `[:standard-epochs/
         greater-than? N]` is keyed by its arg, so the new N is a NEW,
         distinct sub-cache entry alongside the old one."}
  (fn handler-set-threshold [{:keys [db]} [_ n]]
    {:db (assoc-in db [:views :threshold] n)}))

(rf/reg-event :standard-epochs/perturb-chain
  {:doc "Button 8 — perturb Child A's chain input (:views/chain-input).
         With A mounted, Views shows the L1 (:chain-root) → L2
         (:chain-doubled) → L3 (:chain-labelled) invalidation recompute,
         and A re-renders BECAUSE A SUB CHANGED (← :standard-epochs/chain-
         labelled), not because its props changed."}
  (fn handler-perturb-chain [{:keys [db]} _ev]
    {:db (update-in db [:views :chain-input] inc)}))

(rf/reg-event :standard-epochs/unmount-a
  {:doc "Button 9 — unmount Child A (sets :views/a-mounted? false). The
         node disappears and ALL of A's subs are disposed once the last
         reader is gone (the chain L1/L2/L3 + every [:gt? N] cache
         entry); the unmount is recorded."}
  (fn handler-unmount-a [{:keys [db]} _ev]
    {:db (assoc-in db [:views :a-mounted?] false)}))

(rf/reg-event :standard-epochs/mount-b
  {:doc "Button 10 — mount the PROPS-driven Child B (sets
         :views/b-mounted? true). B receives a prop and subscribes
         NOTHING, so Views shows the node appear with NO new sub-cache
         entries."}
  (fn handler-mount-b [{:keys [db]} _ev]
    {:db (assoc-in db [:views :b-mounted?] true)}))

(rf/reg-event :standard-epochs/set-b-prop
  {:doc "Button 11 — change Child B's prop. B re-renders BECAUSE ITS
         PROPS CHANGED (no sub cause) — the foil to button 8's
         sub-driven re-render."}
  (fn handler-set-b-prop [{:keys [db]} _ev]
    {:db (update-in db [:views :b-prop]
               {"alpha" "beta" "beta" "gamma" "gamma" "alpha"})}))

;; -- 12. exception in the handler → Issues: handler-exception, db rolls back -
(rf/reg-event :standard-epochs/throw-handler
  {:doc "Button 12 — throw in the handler. The router catches it; the
         handler's :db never commits; Issues shows
         `:rf.error/handler-exception` with the source coord."}
  (fn handler-throw [_ _ev]
    (throw (ex-info "standard-epochs / handler (intentional — exercises the handler error surface)"
                    {:surface :handler-exception}))))

;; -- 13. exception in an interceptor :before → Issues: interceptor exc. ------
(rf/reg-event :standard-epochs/throw-interceptor
  {:doc "Button 13 — an interceptor throws in :before. The chain aborts on
         the way IN; Issues shows the interceptor :before exception and the
         handler never runs."
   :interceptors [:standard-epochs/throwing-interceptor]}
  (fn handler-after-throwing-interceptor [{:keys [db]} _ev] {:db db}))

;; -- 14. exception in an interceptor :after → Issues: interceptor exc. -------
(rf/reg-event :standard-epochs/throw-interceptor-after
  {:doc "Button 14 — an interceptor throws in :after. The foil to button
         13: the handler runs to completion (the :db is computed), THEN the
         interceptor throws on the way OUT. Issues shows the interceptor
         :after exception; per-step placement renders it under the
         interceptor's :after step, distinct from a handler exception."
   :interceptors [:standard-epochs/throwing-interceptor-after]}
  (fn handler-before-throwing-after-interceptor [{:keys [db]} _ev] {:db db}))

;; -- 15. exception in a coeffect supplier → Issues: cofx error ---------------
(rf/reg-event :standard-epochs/throw-cofx
  {:doc "Button 15 — a declared coeffect's supplier throws at context
         assembly. Issues shows the cofx error; the handler never runs."
   :rf.cofx/requires [:standard-epochs/throwing-cofx]}
  (fn handler-after-throwing-cofx [{:keys [db]} _ev] {:db db}))

;; -- 16. exception in an effect handler (post-commit) → Issues: fx error -----
(rf/reg-event :standard-epochs/throw-fx
  {:doc "Button 16 — the :db commits, then a post-commit fx throws. Issues
         shows the fx error; post-commit fx are best-effort per the FX
         atomicity asymmetry, so the committed db survives. (The visible
         per-step delta is the runner's `:step` write on the parent epoch.)"}
  (fn handler-throw-fx [{:keys [db]} _ev]
    {:db db
     :fx [[:standard-epochs/boom {}]]}))

;; -- 17. slow effect (~600ms managed fx) → Issues: slow-fx flagged -----------
(rf/reg-event :standard-epochs/slow
  {:doc "Button 17 — issue a ~600ms managed fx. Status moves :loading;
         Issues flags the slow fx; the reply lands :loaded ~600ms later."}
  (fn handler-slow [{:keys [db]} _ev]
    {:db (assoc db :slow-status :loading)
     :fx [[:standard-epochs/slow-fetch {}]]}))

(rf/reg-event :standard-epochs/slow-done
  {:doc "The deferred reply from the slow fx. Lands on the originating
         frame and flips status to :loaded."}
  (fn handler-slow-done [{:keys [db]} _ev]
    {:db (assoc db :slow-status :loaded)}))

;; -- 18. schema violation, bad event args → Issues / Schema-timeline ---------
(rf/reg-event :standard-epochs/bad-event-args
  {:doc "Button 18 — dispatched with a bad arg (a string where a pos-int
         is required). The handler is skipped; Issues / Schema-timeline
         shows `:rf.error/schema-validation-failure :where :event`."
   :schema [:cat [:= :standard-epochs/bad-event-args] pos-int?]}
  (fn handler-bad-event-args [{:keys [db]} _ev] {:db db}))

;; -- 19. schema violation, app-db write → Issues: app-db schema failure ------
(rf/reg-event :standard-epochs/bad-app-db-write
  {:doc "Button 19 — write an int into [:auth :token] (the registered
         app-schema requires a string). The post-handler app-db
         validation rolls the :db back; Issues shows the app-db schema
         failure, which survives the rollback."}
  (fn handler-bad-app-db-write [{:keys [db]} _ev]
    {:db (assoc-in db [:auth :token] 42)}))

;; -- 20. diamond probe — bump the join-sub root once -------------------------
(rf/reg-event :standard-epochs/bump-diamond
  {:doc "Button 20 — bump :views/diamond-root once. The join sub
         :standard-epochs/diamond-c (c ← a,b ← root) increments a recompute
         counter each time its compute fn runs. Press once: the counter should
         rise by 1 (clean); a rise of 2 means the diamond double-computes the
         intermediate sub. The count is shown by the diamond-display view."}
  (fn handler-bump-diamond [{:keys [db]} _ev]
    {:db (update-in db [:views :diamond-root] (fnil inc 0))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; L1 — read app-db directly.
(rf/reg-sub :standard-epochs/a-mounted?  (fn [db _] (get-in db [:views :a-mounted?])))
(rf/reg-sub :standard-epochs/b-mounted?  (fn [db _] (get-in db [:views :b-mounted?])))
(rf/reg-sub :standard-epochs/threshold   (fn [db _] (get-in db [:views :threshold])))
(rf/reg-sub :standard-epochs/b-prop      (fn [db _] (get-in db [:views :b-prop])))

;; Child A's OWN L1 → L2 → L3 chain, rooted at :views/chain-input (NOT
;; :base — that feeds button #5's flow). Button #8 perturbs the root;
;; with A mounted, Views shows the L1 → L2 → L3 invalidation recompute.
(rf/reg-sub :standard-epochs/chain-root            ;; L1
  (fn [db _] (get-in db [:views :chain-input])))

(rf/reg-sub :standard-epochs/chain-doubled         ;; L2
  :<- [:standard-epochs/chain-root]
  (fn [root _] (* 2 root)))

(rf/reg-sub :standard-epochs/chain-labelled        ;; L3
  :<- [:standard-epochs/chain-doubled]
  (fn [doubled _] (str "2×input = " doubled)))

;; DYNAMIC, parameterised by the threshold N carried in the query
;; vector: `[:standard-epochs/greater-than? n]`. It cascades from the chain
;; root (a section-owned value, so the sub's behaviour is NOT linked to
;; a counter value). A different n is a DISTINCT cache entry over the
;; same registration — button #7 (5 → 10) creates a new [:gt? 10]
;; entry alongside the original [:gt? 5].
(rf/reg-sub :standard-epochs/greater-than?
  :<- [:standard-epochs/chain-root]
  (fn [root [_ threshold]] (> root threshold)))

;; ============================================================================
;; DIAMOND — redundant-recompute probe
;; ============================================================================
;;
;; MEASUREMENT INSTRUMENT — DO NOT COPY THIS PATTERN. The `:diamond-c` sub
;; below deliberately side-effects in its compute fn (`swap!` a counter) to
;; MEASURE how many times the substrate runs it. That is the one and only
;; reason a sub here breaks subs-must-be-pure: the raw reaction-run count is
;; precisely the thing under observation. It is a diagnostic probe, NOT app
;; code, and NOT a pattern to lift into a real subscription. The safety is
;; structural: `diamond-c-runs` is a plain atom (NOT a ratom) and is NOT an
;; input to any sub, so the `swap!` cannot feed back into the reactive graph.
;;
;; A classic reactive DIAMOND:
;;
;;        :diamond-root          (L1 — reads :views/diamond-root)
;;          /        \
;;   :diamond-a    :diamond-b    (L2 — each :<- root)
;;          \        /
;;        :diamond-c             (the JOINING sub :<- a,b)
;;
;; Button #20 bumps the root ONCE. The join sub `:diamond-c` increments the
;; counter each time its compute fn RUNS. Press once: the counter should rise
;; by exactly 1; a rise of 2 means the substrate recomputes the intermediate
;; join sub TWICE per single root change (the push-based diamond redundant-
;; recompute). The count is shown by the `diamond-display` view below and is
;; cross-checkable against Xray's Reactive / Trace lens.

(defonce diamond-c-runs (atom 0))

(rf/reg-sub :standard-epochs/diamond-root          ;; L1
  (fn [db _] (get-in db [:views :diamond-root])))

(rf/reg-sub :standard-epochs/diamond-a             ;; L2 — left arm
  :<- [:standard-epochs/diamond-root]
  (fn [root _] (* 10 (or root 0))))

(rf/reg-sub :standard-epochs/diamond-b             ;; L2 — right arm
  :<- [:standard-epochs/diamond-root]
  (fn [root _] (inc (or root 0))))

(rf/reg-sub :standard-epochs/diamond-c             ;; join — c = a + b
  :<- [:standard-epochs/diamond-a]
  :<- [:standard-epochs/diamond-b]
  (fn [[a b] _]
    (swap! diamond-c-runs inc)
    (+ a b)))

;; ============================================================================
;; CHILD VIEWS — two children, separable re-render causes
;; ============================================================================
;;
;; Child A is SUBSCRIPTION-driven; Child B is PROPS-driven. Keeping the
;; two causes on two separate components is what lets Xray attribute a
;; re-render to "← a sub changed" (A) vs "← props changed" (B).

;; --- Child A — subscription-driven -----------------------------------------
;;
;; On mount A subscribes its own L1→L2→L3 chain AND the arg-keyed
;; `[:standard-epochs/greater-than? threshold]` sub — so the Views lens
;; shows the node + those sub-cache entries appear, the chain recompute
;; (button #8), and a NEW [:gt? N] cache entry when the arg changes
;; (button #7). `threshold` arrives as a PROP from the root (which
;; reads :views/threshold), so the arg-key is driven by app-db state
;; while the deref itself is A's own subscription.

(reg-view child-a [threshold]
  (let [labelled @(subscribe [:standard-epochs/chain-labelled])
        over?    @(subscribe [:standard-epochs/greater-than? threshold])]
    [:div {:data-testid "standard-epochs-child-a"
           :style {:border "1px solid #d8d2ff" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.5em 0"
                   :background "#fcfbff"}}
     [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Child A — subscription-driven"]
     [:div "chain (L1→L2→L3): " [:strong labelled]]
     [:div "greater-than? " threshold ": " [:strong (str over?)]]]))

;; --- Child B — props-driven ------------------------------------------------
;;
;; B receives a single prop and subscribes NOTHING. Mounting it (button
;; #10) creates no sub-cache entries; changing the prop (button #11)
;; re-renders B because its PROPS changed — the foil to A's sub-driven
;; re-render.

(reg-view child-b [prop]
  [:div {:data-testid "standard-epochs-child-b"
         :style {:border "1px solid #d8efd8" :border-radius "6px"
                 :padding "0.5em 0.75em" :margin "0.5em 0"
                 :background "#fbfffb"}}
   [:div {:style {:font-size "11px" :color "#2e8b57" :font-weight "bold"
                  :text-transform "uppercase" :letter-spacing "0.04em"}}
    "Child B — props-driven (no subs)"]
   [:div "prop: " [:strong prop]]])

;; --- Diamond display — always mounted so the join reaction is live ----------
;;
;; Derefs `:diamond-root` (changes on every press) so the view re-renders each
;; bump, then reads the plain `diamond-c-runs` counter at render time. Derefing
;; `:diamond-c` keeps the join sub subscribed (and forces its recompute). The
;; counter delta per press is the answer: 1 = clean, 2 = the intermediate
;; recomputed twice.

(reg-view diamond-display []
  (let [root @(subscribe [:standard-epochs/diamond-root])
        c    @(subscribe [:standard-epochs/diamond-c])
        runs @diamond-c-runs]
    [:div {:data-testid "standard-epochs-diamond"
           :style {:border "1px solid #ffd8a8" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.5em 0"
                   :background "#fffaf3"}}
     [:div {:style {:font-size "11px" :color "#e8590c" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Diamond probe — c ← a,b ← root"]
     [:div "root: " [:strong root] " · c (= a + b): " [:strong c]]
     [:div "c recompute count: "
      [:strong {:data-testid "diamond-c-runs"} runs]
      [:span {:style {:color "#888" :font-size "11px" :margin-left "0.5em"}}
       "(press #20 once → +1 clean, +2 double-compute)"]]]))

;; ============================================================================
;; THE STEP VECTOR — code data (the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :label "<short row
;; label>"}. The runner renders :watch per STEP; pressing Step (or a per-row
;; RUN button) dispatches `[:standard-epochs/run-step n]`, which sets app-db
;; `:step = n` (the per-step delta the panels show) and dispatches the step's
;; `:event` into the host-frame. The ladder walks Events →
;; Views/subscriptions → Errors/Issues → Reactive (steps 1..20). Manual
;; stepping needs no pacing — each async step (cascade, mount/unmount,
;; slow fx) fires + renders on its own schedule while the operator watches.

(def steps
  [;; -- Events — Epoch / Trace / App-db scalar --
   {:label "Increment"
    :event [:standard-epochs/increment]
    :watch "Epoch: this event, db-before/after. The per-step App-db delta is the runner's :step write on the parent run-step epoch."}
   {:label "Increment + coeffect"
    :event [:standard-epochs/increment-cofx]
    :watch "Epoch event-detail: a `now` coeffect feeds the handler."}
   {:label "Increment + effect"
    :event [:standard-epochs/increment-fx]
    :watch "Effects / Trace: a one-shot :standard-epochs/ping fx fires this epoch."}
   {:label "Increment + cascade"
    :event [:standard-epochs/increment-cascade]
    :watch "Epoch: the dispatch-id tree — a follow-on :cascade-tail event under one root."}
   {:label "Increment + flow"
    :event [:standard-epochs/increment-flow]
    :watch "App-db: the :standard-epochs/derived reg-flow slot recomputes; Trace shows the flow run."}

   ;; -- Views / subscriptions — sub-driven A vs props-driven B --
   {:label "Mount Child A (sub-driven)"
    :event [:standard-epochs/mount-a]
    :watch "Views: the Child-A node + A's sub-cache entries appear (chain L1/L2/L3 + [:gt? 5])."}
   {:label "Change the sub-arg N → 10"
    :event [:standard-epochs/set-threshold 10]
    :watch "Views: a NEW cache entry [:gt? 10] (parameterized-sub cache keyed by arg) alongside [:gt? 5]."}
   {:label "Perturb A's chain input"
    :event [:standard-epochs/perturb-chain]
    :watch "Views: L1→L2→L3 invalidation recompute; A re-renders ← a SUB changed (not props)."}
   {:label "Unmount Child A"
    :event [:standard-epochs/unmount-a]
    :watch "Views: the node is gone; ALL of A's subs are disposed (last reader gone); the unmount is recorded."}
   {:label "Mount Child B (props-driven)"
    :event [:standard-epochs/mount-b]
    :watch "Views: the Child-B node appears with NO subs created."}
   {:label "Change B's prop"
    :event [:standard-epochs/set-b-prop]
    :watch "Views: B re-renders ← PROPS changed (the foil to step 8's sub-driven re-render)."}

   ;; -- Errors / Issues — each a real feature, not a buggy demo --
   {:label "Exception in the handler"
    :event [:standard-epochs/throw-handler]
    :watch "Issues: handler-exception + source coord; the handler's :db never commits."}
   {:label "Exception in an interceptor :before"
    :event [:standard-epochs/throw-interceptor]
    :watch "Issues: interceptor :before exception; the handler is skipped (chain aborts on the way in)."}
   {:label "Exception in an interceptor :after"
    :event [:standard-epochs/throw-interceptor-after]
    :watch "Issues: interceptor :after exception; the handler ran, threw on the way out (foil to step 13)."}
   {:label "Exception in a coeffect"
    :event [:standard-epochs/throw-cofx]
    :watch "Issues: cofx error; the handler is skipped (the throw fires during coeffect injection)."}
   {:label "Exception in an effect"
    :event [:standard-epochs/throw-fx]
    :watch "Issues: fx error (post-commit, best-effort per the FX atomicity asymmetry); the committed db survives."}
   {:label "Slow effect (~600ms)"
    :event [:standard-epochs/slow]
    :watch "Issues: the ~600ms managed fx is flagged slow; status :loading → :loaded once the deferred reply lands."}
   {:label "Bad event args"
    :event [:standard-epochs/bad-event-args "not-a-number"]
    :watch "Issues / Schema-timeline: an event-args schema failure (a string where pos-int? is required); the handler is skipped."}
   {:label "Bad app-db write"
    :event [:standard-epochs/bad-app-db-write]
    :watch "Issues: an app-db schema failure ([:auth :token] must be a string); the :db rolls back, the issue survives."}

   ;; -- Reactive substrate — diamond recompute probe --
   {:label "Bump diamond root"
    :event [:standard-epochs/bump-diamond]
    :watch "Diamond c ← a,b ← root: bump the root once; c's recompute count should rise by 1 (clean), 2 = double-compute."}])

;; ============================================================================
;; THE HOST FRAME + THE RUNNER WIRING
;; ============================================================================
;;
;; `host-frame` is the inspected app frame the runner drives. The standalone
;; deck uses `:rf/default`; the two-frame isolation testbed reuses this deck's
;; `root` + steps but supplies its OWN host-frame (:above / :below) and
;; registers its OWN run-step events (see that testbed). Here, the single-frame
;; deck registers `:standard-epochs/run-step` against `:rf/default`.

(def host-frame :rf/default)

(runner/reg-runner! {:id         :standard-epochs/run-step
                     :steps      steps
                     :host-frame host-frame})

;; ============================================================================
;; ROOT — pure ladder, parameterised over (host-frame, prefix, run-step-event)
;; ============================================================================
;;
;; `root` takes the host-frame + testid prefix + the deck's run-step event
;; id so the deck is mountable BOTH on its own single (`:rf/default`) frame
;; (via the `standalone` wrapper below) AND twice (once per `:above` /
;; `:below` frame) by the two-frame isolation testbed. `root` is a PURE
;; ladder (runner + children + diamond): the title + intro header live in
;; the standalone `run` path only, and there is no deck-reset button, so the
;; two-frame cards stay clean per-frame ladders. The
;; reg-view-injected `subscribe` closes over the surrounding frame-provider's
;; frame id, so the same source drives an isolated reactive context (and an
;; isolated per-frame `:step`) per mount; the runner dispatches the run-step
;; event into `host-frame` explicitly, keeping each runner's events scoped to
;; the frame it inspects. NO runner atom is threaded — the cursor lives in
;; app-db's `:step`.

(reg-view root [host-frame prefix run-step-event]
  [:div {:data-testid "standard-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "720px"}}
   ;; The runner — drives the step ladder against this mount's host-frame.
   [runner/runner {:run-step-event run-step-event
                   :steps          steps
                   :prefix         prefix
                   :host-frame     host-frame}]
   ;; The two children, mounted / unmounted by steps 6..11. Child A
   ;; (sub-driven) takes the threshold N as a prop — read from
   ;; :views/threshold here so the arg-key is driven by app-db state
   ;; while A owns the actual deref. Child B (props-driven) takes its
   ;; prop directly.
   (when @(subscribe [:standard-epochs/a-mounted?])
     [child-a @(subscribe [:standard-epochs/threshold])])
   (when @(subscribe [:standard-epochs/b-mounted?])
     [child-b @(subscribe [:standard-epochs/b-prop])])
   ;; Diamond probe — always mounted so the c ← a,b ← root reaction is live;
   ;; step #20 bumps the root and the display shows c's recompute count.
   [diamond-display]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; The open-in-editor project-root is derived from the build environment,
;; not a hardcoded personal path. `re-frame.testbed.config` joins the
;; build-time repo-root goog-define with this testbed's tool-relative
;; subdir; `?project-root=<path>` still overrides per session. See that ns
;; for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

;; ============================================================================
;; STANDALONE WRAPPER — header (title + intro) above the shared `root`
;; ============================================================================
;;
;; The title + intro live HERE, not in the shared `root`, because `root` is
;; mounted TWICE by `two_frame_isolation` (once per :above / :below frame).
;; Extracting the header keeps the shared `root` a pure ladder, so the
;; two-frame cards stay clean (no per-frame title duplication) while the
;; standalone deck still shows the Epochs header. There is no deck-reset
;; button: reloading re-seeds via `run` (the frames re-seed via :on-create),
;; so the manual reset is unnecessary.

(reg-view standalone []
  [:div {:style {:font-family "system-ui, sans-serif" :max-width "720px"}}
   [:header {:style {:padding "1em 1em 0 1em"}}
    [:h2 {:style {:margin 0}} "Xray Testbed: Epochs"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "The Epoch panel is the centerpiece of xray design. Use the "
     [:strong "⏭ Step"] " button to see how different pipeline scenarios "
     "are rendered."]]
   [root host-frame "standard-epochs" :standard-epochs/run-step]])

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Single, plain frame — no URL machinery, no history listener (there is
  ;; no routing here). The host frame is the one Xray reads. Mount the
  ;; standalone wrapper (header + the parameterised `root`) on the
  ;; host-frame with the `standard-epochs` testid prefix and the deck's
  ;; run-step event. The runner cursor lives in app-db `:step`.
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — register the host frame, scope the boot dispatch, and wrap
  ;; the render in a `frame-provider-existing` (scope-only — the host
  ;; frame is already `reg-frame`'d; the carried invariant).
  (rf/reg-frame host-frame {})
  (rf/with-frame host-frame
    (rf/dispatch-sync [:standard-epochs/reset]))
  (rdc/render react-root [rf/frame-provider-existing {:frame host-frame} [standalone]]))
