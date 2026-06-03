(ns standard-epochs.core
  "STANDARD-EPOCHS testbed (rf2-gsr6z, runner-shaped rf2-3xakq) — a
  deliberately simple Xray driving surface that supersedes the step-deck.

  ## Shape (rf2-3xakq — adopt the shared queued-step RUNNER)

  ONE purple Step button (`<prefix>-step`) walks a step ladder top to
  bottom while the operator watches how Xray's panels render each step;
  EVERY step row's index is ALSO a RANDOM-ACCESS RUN-THIS-STEP button
  (`<prefix>-step-<n>-run`, n = 0-based step index) so any step can be
  driven directly. The runner (`runner.core`, the rf2-8pbjr pilot) is the
  shared harness; this deck supplies the `steps` vector (CODE DATA) and is
  mounted with a runner atom + a host-frame + a testid prefix. Each step
  DISPATCHES one event that

    (a) bumps a shared baseline counter (so App-db / Epoch always show a
        delta on every step), and
    (b) exercises exactly ONE additional feature.

  Progressive: step 1 is trivial; each later step layers one more concept.
  There are NO tabs, NO routing/URL machinery, NO machines, NO SSR. Read
  each step's `:watch` note; Xray itself is the check.

  ## Parameterised root (rf2-3xakq — the two-frame mount)

  `root` is a PURE ladder taking `[runner-state host-frame prefix]` — just
  the runner + the two children + the diamond probe, NO header, NO reset
  button (rf2-7prmj). The single-frame deck (`run`) mounts the `standalone`
  wrapper, which renders the deck's title + intro header ABOVE
  `[root runner-state :rf/default \"standard-epochs\"]` on the plain default
  frame, Xray auto-mounting inline. The TWO-FRAME isolation testbed
  (`two_frame_isolation`) mounts this SAME `root` once per `:above` /
  `:below` frame-provider, each with its OWN runner atom + host-frame + a
  DISTINCT prefix — so the two reactive contexts (and the two runner
  cursors) stay genuinely independent. Keeping the header OUT of `root` is
  what stops the two-frame cards showing the title twice (mislabel) and
  leaves them as clean per-frame ladders. This is what makes the deck
  reusable as the two-frame isolation PROOF without sharing a cursor or
  focusing the wrong frame.

  ## North star (acceptance)

  Pick ANY ONE Xray panel — Epoch, App-db, Views, Trace, or Issues — and
  step through top to bottom: that panel is COMPLETELY exercised. The step
  set is chosen for per-panel coverage completeness, not one-lens-per-step.

  ## Per-panel coverage map

    Epoch   — every button is one epoch (db-before/after, dispatch);
              #2 adds a coeffect to the event detail, #4 a one-shot fx,
              #5 a cascade dispatch-id tree.
    App-db  — #1 scalar bump · #5 flow writes a derived slot. (The rich
              App-db DIFF shapes — added / removed-to-empty / changed
              (diff-mode-3) — plus the large-collection / edn-inspector
              cases live in the sibling `edn_inspector` deck, which drives
              them through the App-db panel — rf2-74u2s → rf2-1niob.)
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
              intermediate sub per single root change (rf2-kt5nx).

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
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
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
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config]
            ;; The shared queued-step runner (rf2-8pbjr pilot). This deck
            ;; supplies a `steps` vector; the runner drives the ONE-button
            ;; series + the per-step RUN buttons. The two-frame testbed
            ;; mounts `root` twice with a distinct runner atom + host-frame.
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:baseline` is the shared counter every button
;; bumps. `:base` feeds button #5's flow only.
;;
;; `:views` holds the Views/subscriptions section's OWN state — kept on
;; its own slots, NOT linked to `:baseline`/`:base`, so the section's
;; mount/unmount/sub behaviour is exercised DIRECTLY (no start-at-button-1
;; sequencing, no :base/flow confound). It is app-db state (not a
;; component-local atom) so it is observable in Xray:
;;
;;   :a-mounted? / :b-mounted?  — the two children's mount slots.
;;   :threshold                 — N, the changeable arg to the dynamic
;;                                sub `[:standard-epochs/greater-than? N]`.
;;   :chain-input               — the root of Child A's own L1→L2→L3
;;                                chain (button #8 perturbs it).
;;   :b-prop                    — the prop fed to the props-driven
;;                                Child B (button #11 changes it).

(def initial-db
  {:baseline 0
   :base     1
   :auth     {:token "seed-token"}
   :views    {:a-mounted?  false
              :b-mounted?  false
              :threshold   5
              :chain-input 1
              :b-prop      "alpha"
              :diamond-root 0}})

(rf/reg-event-db :standard-epochs/reset
  {:doc "Seed event — re-seed app-db and unmount both child views. Used by
         `run` (dispatch-sync on load) and by two_frame_isolation's per-frame
         :on-create. There is no on-page reset button (rf2-7prmj); a reload
         re-seeds."}
  (fn handler-reset [_db _ev]
    initial-db))

;; ============================================================================
;; A small shared helper: every action event bumps the baseline counter.
;; ============================================================================
;;
;; Kept as a plain db->db fn (not an interceptor) so the baseline bump is
;; visible inline in each handler body — the App-db / Epoch delta on
;; every press comes from here.

(defn- bump [db] (update db :baseline inc))

;; ============================================================================
;; APP-DB SCHEMA (button #19)
;; ============================================================================
;;
;; The only constraint: [:auth :token] must be a string. Button #19
;; writes an int there; the post-handler app-db validation (Spec 010
;; §Validation order) rejects it and rolls the :db effect back, while the
;; schema-violation issue survives in Xray's Issues lens.

(def AuthSlice [:map [:token :string]])
(rf/reg-app-schema [:auth] AuthSlice)

;; ============================================================================
;; COEFFECT — :standard-epochs/now  (button #2)
;; ============================================================================
;;
;; A wall-clock injection point so the handler stays a pure fn of
;; (coeffects, event). Xray's Epoch event-detail shows this coeffect
;; feeding the handler.

(rf/reg-cofx :standard-epochs/now
  {:doc "Inject the current wall-clock time (ms since epoch) under
         `:standard-epochs/now`."}
  (fn cofx-now [ctx]
    (rf/assoc-coeffect ctx :standard-epochs/now (.getTime (js/Date.)))))

;; A coeffect that throws on injection (button #15). A FEATURE being
;; exercised — the supported way to light up the cofx error surface.
(rf/reg-cofx :standard-epochs/throwing-cofx
  {:doc "Throws during coeffect injection so Xray's Issues lens surfaces
         a cofx error. A feature being exercised, not a buggy demo."}
  (fn cofx-throws [_ctx]
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

;; Throws in :after — the handler runs to completion first, THEN this
;; throws on the way back out of the chain (button #14). The foil to the
;; :before interceptor above: the failing step is the interceptor's :after,
;; not the handler, so the per-step placement renders the exception under
;; the interceptor's :after step (rf2-yz57h) and the framework attributes
;; it to the interceptor rather than collapsing it into a handler
;; exception (rf2-mszrz).
(def throwing-interceptor-after
  (rf/->interceptor
    :id    :standard-epochs/throwing-interceptor-after
    :after (fn interceptor-after-throws [_ctx]
             (throw (ex-info "standard-epochs / interceptor :after (intentional — exercises the interceptor :after error surface)"
                             {:surface :interceptor-exception :phase :after})))))

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
;; Spec 009's slow-effect threshold, so Xray's Issues lens flags it as a
;; (non-bug) slow effect; the status moves :loading -> :loaded.
(def SLOW-MS 600)
(rf/reg-fx :standard-epochs/slow-fetch
  (fn fx-slow-fetch [{:keys [frame]} _args]
    (js/setTimeout
      (fn [] (rf/dispatch [:standard-epochs/slow-done] {:frame frame}))
      SLOW-MS)))

;; An fx whose body throws (button #16). The handler's :db commits first;
;; the throw fires later, during the post-commit fx walk — best-effort
;; per the FX atomicity asymmetry — so Xray's Issues lens shows the fx
;; error while the baseline bump survives.
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

(rf/reg-flow
  {:id     :standard-epochs/derived
   :inputs [[:base]]
   :output (fn [base] (* 2 (or base 0)))
   :path   [:derived]
   :doc    "Derived = 2 × :base. Recomputes on the post-handler flows pass."})

;; ============================================================================
;; EVENTS — the button ladder
;; ============================================================================

;; -- 1. plain increment ------------------------------------------------------
(rf/reg-event-db :standard-epochs/increment
  {:doc "Button 1 — a plain event. App-db :baseline ++ ; Epoch shows the
         event with db-before / db-after."}
  (fn handler-increment [db _ev] (bump db)))

;; -- 2. increment + coeffect -------------------------------------------------
(rf/reg-event-fx :standard-epochs/increment-cofx
  {:doc "Button 2 — inject the `:standard-epochs/now` cofx. Epoch's event
         detail shows the coeffect feeding the handler."}
  [(rf/inject-cofx :standard-epochs/now)]
  (fn handler-increment-cofx [{:keys [db standard-epochs/now]} _ev]
    {:db (-> db bump (assoc :last-clicked now))}))

;; -- 3. increment + effect ---------------------------------------------------
(rf/reg-event-fx :standard-epochs/increment-fx
  {:doc "Button 3 — return a one-shot fx. Effects / Trace show
         `:standard-epochs/ping` fire this epoch."}
  (fn handler-increment-fx [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:standard-epochs/ping {:at (.getTime (js/Date.))}]]}))

;; -- 4. increment + cascade --------------------------------------------------
(rf/reg-event-fx :standard-epochs/increment-cascade
  {:doc "Button 4 — dispatch a follow-on event. Epoch's dispatch-id tree
         shows the cascade (this event → :standard-epochs/cascade-tail)."}
  (fn handler-increment-cascade [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:standard-epochs/cascade-tail]]]}))

(rf/reg-event-db :standard-epochs/cascade-tail
  {:doc "The follow-on event dispatched by button 4. Bumps baseline again
         so the cascade has two epochs under one root dispatch-id."}
  (fn handler-cascade-tail [db _ev] (bump db)))

;; -- 5. increment + flow -----------------------------------------------------
(rf/reg-event-db :standard-epochs/increment-flow
  {:doc "Button 5 — perturb :base so the `:standard-epochs/derived` flow
         recomputes a derived slot into app-db; App-db / Trace show it."}
  (fn handler-increment-flow [db _ev]
    (-> db bump (update :base inc))))

;; -- 6..11. Views / subscriptions — sub-driven Child A + props-driven Child B
;;
;; The whole section lives on the `:views` slots, decoupled from the
;; counter: a button MAY bump `:baseline`, but mount/unmount/sub state is
;; never linked to a counter VALUE. Two children separate the re-render
;; CAUSE — Child A re-renders ← a SUB changed (#8); Child B re-renders ←
;; PROPS changed (#11).

(rf/reg-event-db :standard-epochs/mount-a
  {:doc "Button 6 — mount the SUBSCRIPTION-driven Child A (sets
         :views/a-mounted? true). On mount A subscribes its own
         L1→L2→L3 chain (:chain-root → :chain-doubled → :chain-labelled)
         PLUS the arg-keyed `[:standard-epochs/greater-than? N]` sub — so
         Views shows the node and those sub-cache entries appear."}
  (fn handler-mount-a [db _ev]
    (-> db bump (assoc-in [:views :a-mounted?] true))))

(rf/reg-event-db :standard-epochs/set-threshold
  {:doc "Button 7 — change the sub-arg N (5 → 10). `[:standard-epochs/
         greater-than? N]` is keyed by its arg, so the new N is a NEW,
         distinct sub-cache entry alongside the old one."}
  (fn handler-set-threshold [db [_ n]]
    (-> db bump (assoc-in [:views :threshold] n))))

(rf/reg-event-db :standard-epochs/perturb-chain
  {:doc "Button 8 — perturb Child A's chain input (:views/chain-input).
         With A mounted, Views shows the L1 (:chain-root) → L2
         (:chain-doubled) → L3 (:chain-labelled) invalidation recompute,
         and A re-renders BECAUSE A SUB CHANGED (← :standard-epochs/chain-
         labelled), not because its props changed."}
  (fn handler-perturb-chain [db _ev]
    (-> db bump (update-in [:views :chain-input] inc))))

(rf/reg-event-db :standard-epochs/unmount-a
  {:doc "Button 9 — unmount Child A (sets :views/a-mounted? false). The
         node disappears and ALL of A's subs are disposed once the last
         reader is gone (the chain L1/L2/L3 + every [:gt? N] cache
         entry); the unmount is recorded."}
  (fn handler-unmount-a [db _ev]
    (-> db bump (assoc-in [:views :a-mounted?] false))))

(rf/reg-event-db :standard-epochs/mount-b
  {:doc "Button 10 — mount the PROPS-driven Child B (sets
         :views/b-mounted? true). B receives a prop and subscribes
         NOTHING, so Views shows the node appear with NO new sub-cache
         entries."}
  (fn handler-mount-b [db _ev]
    (-> db bump (assoc-in [:views :b-mounted?] true))))

(rf/reg-event-db :standard-epochs/set-b-prop
  {:doc "Button 11 — change Child B's prop. B re-renders BECAUSE ITS
         PROPS CHANGED (no sub cause) — the foil to button 8's
         sub-driven re-render."}
  (fn handler-set-b-prop [db _ev]
    (-> db bump (update-in [:views :b-prop]
                           {"alpha" "beta" "beta" "gamma" "gamma" "alpha"}))))

;; -- 12. exception in the handler → Issues: handler-exception, db rolls back -
(rf/reg-event-db :standard-epochs/throw-handler
  {:doc "Button 12 — throw in the handler. The router catches it; the :db
         effect (the baseline bump) rolls back; Issues shows
         `:rf.error/handler-exception` with the source coord."}
  (fn handler-throw [db _ev]
    (bump db) ;; would bump, but the throw below aborts the commit
    (throw (ex-info "standard-epochs / handler (intentional — exercises the handler error surface)"
                    {:surface :handler-exception}))))

;; -- 13. exception in an interceptor :before → Issues: interceptor exc. ------
(rf/reg-event-db :standard-epochs/throw-interceptor
  {:doc "Button 13 — an interceptor throws in :before. The chain aborts on
         the way IN; Issues shows the interceptor :before exception and the
         handler never runs."}
  [throwing-interceptor]
  (fn handler-after-throwing-interceptor [db _ev] (bump db)))

;; -- 14. exception in an interceptor :after → Issues: interceptor exc. -------
(rf/reg-event-db :standard-epochs/throw-interceptor-after
  {:doc "Button 14 — an interceptor throws in :after. The foil to button
         13: the handler runs to completion (the :db is computed), THEN the
         interceptor throws on the way OUT. Issues shows the interceptor
         :after exception; per-step placement renders it under the
         interceptor's :after step, distinct from a handler exception."}
  [throwing-interceptor-after]
  (fn handler-before-throwing-after-interceptor [db _ev] (bump db)))

;; -- 15. exception in a coeffect handler → Issues: cofx error ----------------
(rf/reg-event-fx :standard-epochs/throw-cofx
  {:doc "Button 15 — a coeffect throws on injection. Issues shows the
         cofx error; the handler never runs."}
  [(rf/inject-cofx :standard-epochs/throwing-cofx)]
  (fn handler-after-throwing-cofx [{:keys [db]} _ev] {:db (bump db)}))

;; -- 16. exception in an effect handler (post-commit) → Issues: fx error -----
(rf/reg-event-fx :standard-epochs/throw-fx
  {:doc "Button 16 — the :db commits (baseline bumps), then a post-commit
         fx throws. Issues shows the fx error; post-commit fx are
         best-effort per the FX atomicity asymmetry, so the db delta
         survives."}
  (fn handler-throw-fx [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:standard-epochs/boom {}]]}))

;; -- 17. slow effect (~600ms managed fx) → Issues: slow-fx flagged -----------
(rf/reg-event-fx :standard-epochs/slow
  {:doc "Button 17 — issue a ~600ms managed fx. Status moves :loading;
         Issues flags the slow fx; the reply lands :loaded ~600ms later."}
  (fn handler-slow [{:keys [db]} _ev]
    {:db (-> db bump (assoc :slow-status :loading))
     :fx [[:standard-epochs/slow-fetch {}]]}))

(rf/reg-event-db :standard-epochs/slow-done
  {:doc "The deferred reply from the slow fx. Lands on the originating
         frame and flips status to :loaded."}
  (fn handler-slow-done [db _ev]
    (assoc db :slow-status :loaded)))

;; -- 18. schema violation, bad event args → Issues / Schema-timeline ---------
(rf/reg-event-db :standard-epochs/bad-event-args
  {:doc "Button 18 — dispatched with a bad arg (a string where a pos-int
         is required). The handler is skipped; Issues / Schema-timeline
         shows `:rf.error/schema-validation-failure :where :event`."
   :schema [:cat [:= :standard-epochs/bad-event-args] pos-int?]}
  (fn handler-bad-event-args [db _ev] (bump db)))

;; -- 19. schema violation, app-db write → Issues: app-db schema failure ------
(rf/reg-event-db :standard-epochs/bad-app-db-write
  {:doc "Button 19 — write an int into [:auth :token] (the registered
         app-schema requires a string). The post-handler app-db
         validation rolls the :db back; Issues shows the app-db schema
         failure, which survives the rollback."}
  (fn handler-bad-app-db-write [db _ev]
    (-> db bump (assoc-in [:auth :token] 42))))

;; -- 20. diamond probe — bump the join-sub root once -------------------------
(rf/reg-event-db :standard-epochs/bump-diamond
  {:doc "Button 20 — bump :views/diamond-root once. The join sub
         :standard-epochs/diamond-c (c ← a,b ← root) increments a recompute
         counter each time its compute fn runs. Press once: the counter should
         rise by 1 (clean); a rise of 2 means the diamond double-computes the
         intermediate sub. The count is shown by the diamond-display view."}
  (fn handler-bump-diamond [db _ev]
    (-> db bump (update-in [:views :diamond-root] (fnil inc 0)))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; L1 — read app-db directly.
(rf/reg-sub :standard-epochs/baseline    (fn [db _] (:baseline db)))
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
;; DIAMOND — redundant-recompute probe (rf2-kt5nx)
;; ============================================================================
;;
;; A classic reactive DIAMOND:
;;
;;        :diamond-root          (L1 — reads :views/diamond-root)
;;          /        \
;;   :diamond-a    :diamond-b    (L2 — each :<- root)
;;          \        /
;;        :diamond-c             (the JOINING sub :<- a,b)
;;
;; Button #20 bumps the root ONCE. The join sub `:diamond-c` increments a
;; counter each time its compute fn RUNS. Press once: the counter should rise
;; by exactly 1; a rise of 2 means the substrate recomputes the intermediate
;; join sub TWICE per single root change (the push-based diamond redundant-
;; recompute). The count is shown by the `diamond-display` view below and is
;; cross-checkable against Xray's Reactive / Trace lens.
;;
;; Side-effecting in a sub compute fn is deliberate HERE — this is a
;; diagnostic instrument, not app code, and the raw reaction-run count is the
;; thing we want to observe. `diamond-c-runs` is a plain atom (not a ratom)
;; and is NOT an input to any sub, so the swap! cannot feed back into the graph.

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
;; THE STEP VECTOR — code data (rf2-8pbjr: the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :settle-ms N
;;             :label "<short row label>"}. The runner renders :watch per
;; STEP, dispatches :event to the deck's host-frame, then waits :settle-ms
;; before advancing. The ladder walks Events → Views/subscriptions →
;; Errors/Issues → Reactive (the historical button order 1..20 preserved).
;;
;; Settle tuning (rf2-3xakq): most steps are synchronous db transitions —
;; a short pause is enough for the eye + the panel render. The async steps
;; get longer settles so the deferred work fires + renders before the
;; cursor advances:
;;   - #4 cascade — the follow-on :dispatch lands a second epoch.
;;   - #6/#9/#10 mount/unmount — a React render tick for the child node
;;     to appear / disappear + its subs to register / dispose.
;;   - #17 slow fx (~600ms) — the deferred :standard-epochs/slow-done reply
;;     must land (status :loading → :loaded) within the settle.
;;   - #12/#16/#19 exception / rollback steps — a beat for the Issues lens
;;     to surface the trace + the rolled-back / surviving db to settle.

(def steps
  [;; -- Events — Epoch / Trace / App-db scalar --
   {:label     "Increment"
    :event     [:standard-epochs/increment]
    :watch     "App-db :baseline ++ · Epoch: the event, db-before/after."
    :settle-ms 350}
   {:label     "Increment + coeffect"
    :event     [:standard-epochs/increment-cofx]
    :watch     "Epoch event-detail: a `now` coeffect feeds the handler."
    :settle-ms 350}
   {:label     "Increment + effect"
    :event     [:standard-epochs/increment-fx]
    :watch     "Effects / Trace: a one-shot :standard-epochs/ping fx fires this epoch."
    :settle-ms 350}
   {:label     "Increment + cascade"
    :event     [:standard-epochs/increment-cascade]
    :watch     "Epoch: the dispatch-id tree — a follow-on :cascade-tail event under one root."
    :settle-ms 450}
   {:label     "Increment + flow"
    :event     [:standard-epochs/increment-flow]
    :watch     "App-db: the :standard-epochs/derived reg-flow slot recomputes; Trace shows the flow run."
    :settle-ms 400}

   ;; -- Views / subscriptions — sub-driven A vs props-driven B --
   {:label     "Mount Child A (sub-driven)"
    :event     [:standard-epochs/mount-a]
    :watch     "Views: the Child-A node + A's sub-cache entries appear (chain L1/L2/L3 + [:gt? 5])."
    :settle-ms 450}
   {:label     "Change the sub-arg N → 10"
    :event     [:standard-epochs/set-threshold 10]
    :watch     "Views: a NEW cache entry [:gt? 10] (parameterized-sub cache keyed by arg) alongside [:gt? 5]."
    :settle-ms 400}
   {:label     "Perturb A's chain input"
    :event     [:standard-epochs/perturb-chain]
    :watch     "Views: L1→L2→L3 invalidation recompute; A re-renders ← a SUB changed (not props)."
    :settle-ms 400}
   {:label     "Unmount Child A"
    :event     [:standard-epochs/unmount-a]
    :watch     "Views: the node is gone; ALL of A's subs are disposed (last reader gone); the unmount is recorded."
    :settle-ms 450}
   {:label     "Mount Child B (props-driven)"
    :event     [:standard-epochs/mount-b]
    :watch     "Views: the Child-B node appears with NO subs created."
    :settle-ms 450}
   {:label     "Change B's prop"
    :event     [:standard-epochs/set-b-prop]
    :watch     "Views: B re-renders ← PROPS changed (the foil to step 8's sub-driven re-render)."
    :settle-ms 400}

   ;; -- Errors / Issues — each a real feature, not a buggy demo --
   {:label     "Exception in the handler"
    :event     [:standard-epochs/throw-handler]
    :watch     "Issues: handler-exception + source coord; the :db (baseline bump) rolls back."
    :settle-ms 450}
   {:label     "Exception in an interceptor :before"
    :event     [:standard-epochs/throw-interceptor]
    :watch     "Issues: interceptor :before exception; the handler is skipped (chain aborts on the way in)."
    :settle-ms 450}
   {:label     "Exception in an interceptor :after"
    :event     [:standard-epochs/throw-interceptor-after]
    :watch     "Issues: interceptor :after exception; the handler ran, threw on the way out (foil to step 13)."
    :settle-ms 450}
   {:label     "Exception in a coeffect"
    :event     [:standard-epochs/throw-cofx]
    :watch     "Issues: cofx error; the handler is skipped (the throw fires during coeffect injection)."
    :settle-ms 450}
   {:label     "Exception in an effect"
    :event     [:standard-epochs/throw-fx]
    :watch     "Issues: fx error (post-commit, best-effort per the FX atomicity asymmetry); the db delta survives."
    :settle-ms 450}
   {:label     "Slow effect (~600ms)"
    :event     [:standard-epochs/slow]
    :watch     "Issues: the ~600ms managed fx is flagged slow; status :loading → :loaded once the deferred reply lands."
    :settle-ms 800}
   {:label     "Bad event args"
    :event     [:standard-epochs/bad-event-args "not-a-number"]
    :watch     "Issues / Schema-timeline: an event-args schema failure (a string where pos-int? is required); the handler is skipped."
    :settle-ms 450}
   {:label     "Bad app-db write"
    :event     [:standard-epochs/bad-app-db-write]
    :watch     "Issues: an app-db schema failure ([:auth :token] must be a string); the :db rolls back, the issue survives."
    :settle-ms 450}

   ;; -- Reactive substrate — diamond recompute probe --
   {:label     "Bump diamond root"
    :event     [:standard-epochs/bump-diamond]
    :watch     "Diamond c ← a,b ← root: bump the root once; c's recompute count should rise by 1 (clean), 2 = double-compute."
    :settle-ms 400}])

;; ============================================================================
;; RUNNER STATE — LOCAL ATOM (rf2-8pbjr: not app-db, not a 2nd frame)
;; ============================================================================
;;
;; The single-frame deck's own runner atom (used by `run`). The two-frame
;; isolation testbed does NOT use this — it supplies a DISTINCT atom per
;; frame mount (so the two cursors stay independent), see that testbed's
;; `core.cljs`.

(defonce runner-state (r/atom (runner/initial-state)))

;; ============================================================================
;; ROOT — pure ladder, parameterised over (runner-state, host-frame, prefix)
;; ============================================================================
;;
;; rf2-3xakq — `root` takes the runner atom + host-frame + testid prefix so
;; the deck is mountable BOTH on its own single (`:rf/default`) frame (via
;; the `standalone` wrapper below) AND twice (once per `:above` / `:below`
;; frame) by the two-frame isolation testbed. rf2-7prmj — `root` is now a
;; PURE ladder (runner + children + diamond): the title + intro header live
;; in the standalone `run` path only, and there is no deck-reset button, so
;; the two-frame cards stay clean per-frame ladders (no title duplication,
;; no per-frame reset). The reg-view-injected `dispatch` / `subscribe` close
;; over the surrounding frame-provider's frame id, so the same source drives
;; an isolated reactive context per mount; the runner dispatches to
;; `host-frame` explicitly (see runner.core/dispatch+settle!), keeping each
;; runner's events scoped to the frame it inspects.

(reg-view root [runner-state host-frame prefix]
  [:div {:data-testid "standard-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "720px"}}
   ;; The runner — drives the step ladder against this mount's host-frame.
   [runner/runner prefix runner-state steps host-frame]
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

;; rf2-5dphw — open-in-editor project-root is derived from the build
;; environment, not a hardcoded personal path. `re-frame.testbed.config`
;; joins the build-time repo-root goog-define with this testbed's
;; tool-relative subdir; `?project-root=<path>` still overrides per
;; session. See that ns for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(def host-frame :rf/default)

;; ============================================================================
;; STANDALONE WRAPPER — header (title + intro) above the shared `root`
;; ============================================================================
;;
;; rf2-7prmj — the title + intro live HERE, not in the shared `root`, because
;; `root` is mounted TWICE by `two_frame_isolation` (once per :above / :below
;; frame). Extracting the header keeps the shared `root` a pure ladder, so the
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
   [root runner-state host-frame "standard-epochs"]])

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Single, plain frame — no URL machinery, no history listener (there is
  ;; no routing here). The default frame is the one Xray reads. Mount the
  ;; standalone wrapper (header + the parameterised `root`) with this deck's
  ;; own runner atom, the `:rf/default` host-frame, and the `standard-epochs`
  ;; testid prefix.
  (rf/dispatch-sync [:standard-epochs/reset])
  (rdc/render react-root [standalone]))
