(ns button-deck.core
  "BUTTON-DECK testbed (rf2-gsr6z) — a deliberately simple Xray driving
  surface that supersedes the step-deck.

  ## Shape

  ONE tall column of NUMBERED buttons, top to bottom. Beside each button
  a one-line caption says WHAT TO WATCH in Xray when you press it. Each
  button is ONE test: it fires one event that

    (a) bumps a shared baseline counter (so App-db / Epoch always show a
        delta on every press), and
    (b) exercises exactly ONE additional feature.

  Progressive: button 1 is trivial; each later button layers one more
  concept. There are NO tabs, NO routing/URL machinery, NO machines, NO
  SSR — a single frame, plainly mounted, with Xray auto-mounting inline
  on the right (`[data-rf-xray-host]` + the xray preload) reading that
  one frame. No frame picker, no action→check meta-strip: the static
  caption is the 'what to look for', and Xray itself is the check.

  ## North star (acceptance)

  Pick ANY ONE Xray panel — Epoch, App-db, Views, Trace, or Issues — and
  click the buttons top to bottom: that panel is COMPLETELY exercised.
  The button set is chosen for per-panel coverage completeness, not
  one-lens-per-button.

  ## Per-panel coverage map

    Epoch   — every button is one epoch (db-before/after, dispatch);
              #2 adds a coeffect to the event detail, #4 a one-shot fx,
              #5 a cascade dispatch-id tree.
    App-db  — #1 scalar bump · #6 added key · #7 removed key · #8 changed
              nested value (diff-mode-3) · #9 large collection · #5 flow
              writes a derived slot.
    Views   — #10 mount a view (L2 sub created) · #11 mount with an arg
              ([:gt? N] cache entry) · #12 change the arg (new cache
              entry) · #13 unmount (node gone, last-reader L2 destroyed)
              · #14 recompute an L1→L2→L3 chain.
    Trace   — every button emits trace; #3 a managed fx, #4 a cascade,
              #5 a flow recompute, #14 a sub-chain recompute.
    Issues  — #15 handler exception (db rolls back) · #16 interceptor
              exception · #17 coeffect exception · #18 effect exception
              (post-commit, best-effort) · #19 slow fx flagged ·
              #20 event-args schema violation · #21 app-db schema
              violation (survives rollback).

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
            ;; fire (without it they soft-pass and buttons #20/#21 would
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
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:baseline` is the shared counter every button
;; bumps. `:shapes` is the playground the App-db diff buttons mutate.
;; `:base` feeds the L1→L2→L3 sub chain. `:view` holds the frame-scoped
;; UI state the Views buttons toggle (mount/threshold) — kept in app-db
;; (not a component-local atom) so it is observable in Xray.

(def initial-db
  {:baseline 0
   :shapes   {}
   :base     1
   :auth     {:token "seed-token"}
   :view     {:mounted?  false
              :threshold 5}})

(rf/reg-event-db :button-deck/reset
  {:doc "Button 0 — re-seed app-db and unmount any child view. Start clean."}
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
;; APP-DB SCHEMA (button #21)
;; ============================================================================
;;
;; The only constraint: [:auth :token] must be a string. Button #21
;; writes an int there; the post-handler app-db validation (Spec 010
;; §Validation order) rejects it and rolls the :db effect back, while the
;; schema-violation issue survives in Xray's Issues lens.

(def AuthSlice [:map [:token :string]])
(rf/reg-app-schema [:auth] AuthSlice)

;; ============================================================================
;; COEFFECT — :button-deck/now  (button #2)
;; ============================================================================
;;
;; A wall-clock injection point so the handler stays a pure fn of
;; (coeffects, event). Xray's Epoch event-detail shows this coeffect
;; feeding the handler.

(rf/reg-cofx :button-deck/now
  {:doc "Inject the current wall-clock time (ms since epoch) under
         `:button-deck/now`."}
  (fn cofx-now [ctx]
    (rf/assoc-coeffect ctx :button-deck/now (.getTime (js/Date.)))))

;; A coeffect that throws on injection (button #17). A FEATURE being
;; exercised — the supported way to light up the cofx error surface.
(rf/reg-cofx :button-deck/throwing-cofx
  {:doc "Throws during coeffect injection so Xray's Issues lens surfaces
         a cofx error. A feature being exercised, not a buggy demo."}
  (fn cofx-throws [_ctx]
    (throw (ex-info "button-deck / coeffect (intentional — exercises the cofx error surface)"
                    {:surface :coeffect-exception}))))

;; ============================================================================
;; INTERCEPTOR that throws in :before (button #16)
;; ============================================================================

(def throwing-interceptor
  (rf/->interceptor
    :id     :button-deck/throwing-interceptor
    :before (fn interceptor-throws [_ctx]
              (throw (ex-info "button-deck / interceptor (intentional — exercises the interceptor error surface)"
                              {:surface :interceptor-exception})))))

;; ============================================================================
;; EFFECTS
;; ============================================================================

;; A one-shot, instantaneous fx (button #3). Records its calls so the
;; effect genuinely runs; Xray's Trace / Effects show it fire this epoch.
(defonce ping-log (atom []))
(rf/reg-fx :button-deck/ping
  (fn fx-ping [_ctx args]
    (swap! ping-log conj args)))

;; A managed slow fx (~600ms, button #19). Resolves later with a
;; follow-on dispatch back onto the originating frame. ~600ms exceeds
;; Spec 009's slow-effect threshold, so Xray's Issues lens flags it as a
;; (non-bug) slow effect; the status moves :loading -> :loaded.
(def SLOW-MS 600)
(rf/reg-fx :button-deck/slow-fetch
  (fn fx-slow-fetch [{:keys [frame]} _args]
    (js/setTimeout
      (fn [] (rf/dispatch [:button-deck/slow-done] {:frame frame}))
      SLOW-MS)))

;; An fx whose body throws (button #18). The handler's :db commits first;
;; the throw fires later, during the post-commit fx walk — best-effort
;; per the FX atomicity asymmetry — so Xray's Issues lens shows the fx
;; error while the baseline bump survives.
(rf/reg-fx :button-deck/boom
  (fn fx-boom [_ctx _args]
    (throw (ex-info "button-deck / effect (intentional — exercises the fx error surface)"
                    {:surface :effect-exception}))))

;; ============================================================================
;; FLOW (button #5) — a reg-flow-derived slot recomputes into app-db
;; ============================================================================
;;
;; `:base` is doubled into `:derived` on every drain where `:base`
;; changed. Button #5 bumps `:base`; App-db shows `:derived` recompute
;; and Trace shows the flow run.

(rf/reg-flow
  {:id     :button-deck/derived
   :inputs [[:base]]
   :output (fn [base] (* 2 (or base 0)))
   :path   [:derived]
   :doc    "Derived = 2 × :base. Recomputes on the post-handler flows pass."})

;; ============================================================================
;; EVENTS — the button ladder
;; ============================================================================

;; -- 1. plain increment ------------------------------------------------------
(rf/reg-event-db :button-deck/increment
  {:doc "Button 1 — a plain event. App-db :baseline ++ ; Epoch shows the
         event with db-before / db-after."}
  (fn handler-increment [db _ev] (bump db)))

;; -- 2. increment + coeffect -------------------------------------------------
(rf/reg-event-fx :button-deck/increment-cofx
  {:doc "Button 2 — inject the `:button-deck/now` cofx. Epoch's event
         detail shows the coeffect feeding the handler."}
  [(rf/inject-cofx :button-deck/now)]
  (fn handler-increment-cofx [{:keys [db button-deck/now]} _ev]
    {:db (-> db bump (assoc :last-clicked now))}))

;; -- 3. increment + effect ---------------------------------------------------
(rf/reg-event-fx :button-deck/increment-fx
  {:doc "Button 3 — return a one-shot fx. Effects / Trace show
         `:button-deck/ping` fire this epoch."}
  (fn handler-increment-fx [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:button-deck/ping {:at (.getTime (js/Date.))}]]}))

;; -- 4. increment + cascade --------------------------------------------------
(rf/reg-event-fx :button-deck/increment-cascade
  {:doc "Button 4 — dispatch a follow-on event. Epoch's dispatch-id tree
         shows the cascade (this event → :button-deck/cascade-tail)."}
  (fn handler-increment-cascade [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:button-deck/cascade-tail]]]}))

(rf/reg-event-db :button-deck/cascade-tail
  {:doc "The follow-on event dispatched by button 4. Bumps baseline again
         so the cascade has two epochs under one root dispatch-id."}
  (fn handler-cascade-tail [db _ev] (bump db)))

;; -- 5. increment + flow -----------------------------------------------------
(rf/reg-event-db :button-deck/increment-flow
  {:doc "Button 5 — perturb :base so the `:button-deck/derived` flow
         recomputes a derived slot into app-db; App-db / Trace show it."}
  (fn handler-increment-flow [db _ev]
    (-> db bump (update :base inc))))

;; -- 6. add a nested key (assoc-in) → App-db diff: added ---------------------
(rf/reg-event-db :button-deck/add-key
  {:doc "Button 6 — assoc-in a new nested key. App-db diff: added."}
  (fn handler-add-key [db _ev]
    (-> db bump (assoc-in [:shapes :added] {:label "added" :n 42}))))

;; -- 7. remove a key (dissoc) → App-db diff: removed -------------------------
(rf/reg-event-db :button-deck/remove-key
  {:doc "Button 7 — dissoc a key. App-db diff: removed. (Adds a key first
         if absent so there is always something to remove.)"}
  (fn handler-remove-key [db _ev]
    (let [db (bump db)]
      (if (get-in db [:shapes :added])
        (update db :shapes dissoc :added)
        (assoc-in db [:shapes :removed-marker] true)))))

;; -- 8. change a nested value (update-in) → App-db diff: changed -------------
(rf/reg-event-db :button-deck/change-value
  {:doc "Button 8 — update-in a nested value. App-db diff: changed
         (diff-mode-3 shows the in-place value delta)."}
  (fn handler-change-value [db _ev]
    (-> db bump (update-in [:shapes :counter] (fnil inc 0)))))

;; -- 9. write a large collection → edn-inspector collapse/expand + elision --
(rf/reg-event-db :button-deck/write-large
  {:doc "Button 9 — write a large collection. The edn-inspector
         collapses / expands it and elides past its threshold."}
  (fn handler-write-large [db _ev]
    (-> db bump (assoc-in [:shapes :large] (vec (range 200))))))

;; -- 10..13. Views / subscriptions: mount, arg, change-arg, unmount ----------
(rf/reg-event-db :button-deck/mount-view
  {:doc "Button 10 — mount the child view (sets :view/mounted? true). The
         child subscribes :button-deck/parity, creating an L2 sub; Views
         shows the node + sub appear."}
  (fn handler-mount-view [db _ev]
    (-> db bump (assoc-in [:view :mounted?] true))))

(rf/reg-event-db :button-deck/set-threshold
  {:doc "Buttons 11/12 — set the threshold N (the changeable argument to
         the dynamic sub `[:button-deck/greater-than? N]`). A new N is a
         distinct sub cache entry."}
  (fn handler-set-threshold [db [_ n]]
    (-> db bump (assoc-in [:view :threshold] n))))

(rf/reg-event-db :button-deck/unmount-view
  {:doc "Button 13 — unmount the child view. Views shows the node
         disappear; its L2 subs are destroyed once the last reader is
         gone."}
  (fn handler-unmount-view [db _ev]
    (-> db bump (assoc-in [:view :mounted?] false))))

;; -- 14. recompute a sub chain (perturb :base with the chain mounted) --------
(rf/reg-event-db :button-deck/perturb-base
  {:doc "Button 14 — perturb :base. With the chain view mounted, Views
         shows the L1 (:base) → L2 (doubled) → L3 (labelled) invalidation
         recompute."}
  (fn handler-perturb-base [db _ev]
    (-> db bump (update :base inc))))

;; -- 15. exception in the handler → Issues: handler-exception, db rolls back -
(rf/reg-event-db :button-deck/throw-handler
  {:doc "Button 15 — throw in the handler. The router catches it; the :db
         effect (the baseline bump) rolls back; Issues shows
         `:rf.error/handler-exception` with the source coord."}
  (fn handler-throw [db _ev]
    (bump db) ;; would bump, but the throw below aborts the commit
    (throw (ex-info "button-deck / handler (intentional — exercises the handler error surface)"
                    {:surface :handler-exception}))))

;; -- 16. exception in an interceptor (:before) → Issues: interceptor exc. ----
(rf/reg-event-db :button-deck/throw-interceptor
  {:doc "Button 16 — an interceptor throws in :before. Issues shows the
         interceptor exception; the handler never runs."}
  [throwing-interceptor]
  (fn handler-after-throwing-interceptor [db _ev] (bump db)))

;; -- 17. exception in a coeffect handler → Issues: cofx error ----------------
(rf/reg-event-fx :button-deck/throw-cofx
  {:doc "Button 17 — a coeffect throws on injection. Issues shows the
         cofx error; the handler never runs."}
  [(rf/inject-cofx :button-deck/throwing-cofx)]
  (fn handler-after-throwing-cofx [{:keys [db]} _ev] {:db (bump db)}))

;; -- 18. exception in an effect handler (post-commit) → Issues: fx error -----
(rf/reg-event-fx :button-deck/throw-fx
  {:doc "Button 18 — the :db commits (baseline bumps), then a post-commit
         fx throws. Issues shows the fx error; post-commit fx are
         best-effort per the FX atomicity asymmetry, so the db delta
         survives."}
  (fn handler-throw-fx [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:button-deck/boom {}]]}))

;; -- 19. slow effect (~600ms managed fx) → Issues: slow-fx flagged -----------
(rf/reg-event-fx :button-deck/slow
  {:doc "Button 19 — issue a ~600ms managed fx. Status moves :loading;
         Issues flags the slow fx; the reply lands :loaded ~600ms later."}
  (fn handler-slow [{:keys [db]} _ev]
    {:db (-> db bump (assoc :slow-status :loading))
     :fx [[:button-deck/slow-fetch {}]]}))

(rf/reg-event-db :button-deck/slow-done
  {:doc "The deferred reply from the slow fx. Lands on the originating
         frame and flips status to :loaded."}
  (fn handler-slow-done [db _ev]
    (assoc db :slow-status :loaded)))

;; -- 20. schema violation, bad event args → Issues / Schema-timeline ---------
(rf/reg-event-db :button-deck/bad-event-args
  {:doc "Button 20 — dispatched with a bad arg (a string where a pos-int
         is required). The handler is skipped; Issues / Schema-timeline
         shows `:rf.error/schema-validation-failure :where :event`."
   :schema [:cat [:= :button-deck/bad-event-args] pos-int?]}
  (fn handler-bad-event-args [db _ev] (bump db)))

;; -- 21. schema violation, app-db write → Issues: app-db schema failure ------
(rf/reg-event-db :button-deck/bad-app-db-write
  {:doc "Button 21 — write an int into [:auth :token] (the registered
         app-schema requires a string). The post-handler app-db
         validation rolls the :db back; Issues shows the app-db schema
         failure, which survives the rollback."}
  (fn handler-bad-app-db-write [db _ev]
    (-> db bump (assoc-in [:auth :token] 42))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; L1 — read app-db directly.
(rf/reg-sub :button-deck/baseline  (fn [db _] (:baseline db)))
(rf/reg-sub :button-deck/mounted?  (fn [db _] (get-in db [:view :mounted?])))
(rf/reg-sub :button-deck/threshold (fn [db _] (get-in db [:view :threshold])))
(rf/reg-sub :button-deck/base      (fn [db _] (:base db)))

;; L2 — derived from :baseline. Created when the child view first reads it
;; and destroyed when the last reader unmounts — the observable Views
;; create/destroy surface.
(rf/reg-sub :button-deck/parity
  :<- [:button-deck/baseline]
  (fn [n _] (if (even? n) "even" "odd")))

;; L2 — DYNAMIC, parameterised by the threshold carried in the query
;; vector. `[:button-deck/greater-than? n]` cascades from :baseline; a
;; different n is a distinct cache entry over the same registration.
(rf/reg-sub :button-deck/greater-than?
  :<- [:button-deck/baseline]
  (fn [n [_ threshold]] (> n threshold)))

;; The L1 → L2 → L3 chain rooted at :base (button #14 perturbs the root).
(rf/reg-sub :button-deck/base-doubled         ;; L2
  :<- [:button-deck/base]
  (fn [base _] (* 2 base)))

(rf/reg-sub :button-deck/base-labelled        ;; L3
  :<- [:button-deck/base-doubled]
  (fn [doubled _] (str "2×base = " doubled)))

;; ============================================================================
;; CHILD VIEW (mounted / unmounted by buttons 10..14)
;; ============================================================================
;;
;; While mounted it reads the L2 parity sub, the changeable-arg
;; greater-than? sub, and the L1→L2→L3 chain — so the Views lens shows
;; the node + its sub-cache entries appear, change with the threshold,
;; and the chain recompute.

(reg-view child-view []
  (let [n         @(subscribe [:button-deck/baseline])
        threshold @(subscribe [:button-deck/threshold])]
    [:div {:data-testid "button-deck-child"
           :style {:border "1px solid #d8d2ff" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.5em 0"
                   :background "#fcfbff"}}
     [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Child view (mounted)"]
     [:div "parity: " [:strong @(subscribe [:button-deck/parity])]]
     [:div "greater-than? " threshold ": "
      [:strong (str @(subscribe [:button-deck/greater-than? threshold]))]]
     [:div "chain: " [:strong @(subscribe [:button-deck/base-labelled])]]
     [:div {:style {:color "#888" :font-size "11px"}}
      "baseline = " n]]))

;; ============================================================================
;; THE BUTTON LADDER
;; ============================================================================

(def ^:private ladder
  "The ordered button ladder. Each row: [n label caption event]. `event`
  is the dispatch vector; nil rows are section separators (label only)."
  [[:section "Events — Epoch / Trace / App-db scalar"]
   [1  "Increment"             "App-db :baseline ++ · Epoch: the event, db-before/after"
    [:button-deck/increment]]
   [2  "Increment + coeffect"  "Epoch event-detail: a `now` coeffect feeds the handler"
    [:button-deck/increment-cofx]]
   [3  "Increment + effect"    "Effects / Trace: a one-shot fx fires this epoch"
    [:button-deck/increment-fx]]
   [4  "Increment + cascade"   "Epoch: the dispatch-id tree — a follow-on event"
    [:button-deck/increment-cascade]]
   [5  "Increment + flow"      "App-db: a reg-flow-derived slot recomputes; Trace shows it"
    [:button-deck/increment-flow]]
   [:section "App-db shapes — diff modes / edn-inspector"]
   [6  "Add a nested key"      "App-db diff: added (assoc-in)"
    [:button-deck/add-key]]
   [7  "Remove a key"          "App-db diff: removed (dissoc)"
    [:button-deck/remove-key]]
   [8  "Change a nested value" "App-db diff: changed (update-in, diff-mode-3)"
    [:button-deck/change-value]]
   [9  "Write a large collection" "edn-inspector: collapse / expand + elision"
    [:button-deck/write-large]]
   [:section "Views / subscriptions"]
   [10 "Mount a view"          "Views: a new node (and its L2 sub) appears"
    [:button-deck/mount-view]]
   [11 "Mount-arg → threshold 5" "Views: a sub cache entry keyed by [:gt? 5]"
    [:button-deck/set-threshold 5]]
   [12 "Change the arg → 10"   "Views: a new cache entry for the new arg [:gt? 10]"
    [:button-deck/set-threshold 10]]
   [13 "Unmount the view"      "Views: node disappears; L2 sub destroyed (last reader gone)"
    [:button-deck/unmount-view]]
   [14 "Recompute a sub chain" "Views: L1 (:base) → L2 → L3 invalidation recompute"
    [:button-deck/perturb-base]]
   [:section "Errors / Issues — each a real feature, not a buggy demo"]
   [15 "Exception in the handler"     "Issues: handler-exception + source coord; db rolls back"
    [:button-deck/throw-handler]]
   [16 "Exception in an interceptor"  "Issues: interceptor exception (:before); handler skipped"
    [:button-deck/throw-interceptor]]
   [17 "Exception in a coeffect"      "Issues: cofx error; handler skipped"
    [:button-deck/throw-cofx]]
   [18 "Exception in an effect"       "Issues: fx error (post-commit, best-effort); db delta survives"
    [:button-deck/throw-fx]]
   [19 "Slow effect (~600ms)"         "Issues: slow-fx flagged; status loading → loaded"
    [:button-deck/slow]]
   [20 "Bad event args"               "Issues / Schema-timeline: event-args schema failure"
    [:button-deck/bad-event-args "not-a-number"]]
   [21 "Bad app-db write"             "Issues: app-db schema failure (survives rollback)"
    [:button-deck/bad-app-db-write]]])

(defn- testid-for [event]
  (-> (first event) name (str "-button")))

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on
  the right. Pressing it dispatches the row's event."
  [n label caption event]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (testid-for event)
             :on-click    #(dispatch event)
             :style {:min-width "16em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view section-heading
  "A section separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view root []
  [:div {:data-testid "button-deck-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "720px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "Button-deck"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, one tall column of test buttons. Each button bumps a shared "
     [:strong "baseline"] " counter (so App-db / Epoch always show a delta) and "
     "exercises exactly one more feature. The caption says what to watch in "
     "Xray on the right — click top to bottom and any one panel is fully "
     "exercised."]]
   ;; Button 0 — Reset.
   [:button {:data-testid "reset-button"
             :on-click #(dispatch [:button-deck/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — re-seed app-db, unmount the child view"]
   ;; The ladder.
   (for [row ladder]
     (if (= :section (first row))
       ^{:key (second row)} [section-heading (second row)]
       (let [[n label caption event] row]
         ^{:key n} [ladder-button n label caption event])))
   ;; The child view, mounted / unmounted by buttons 10..14.
   (when @(subscribe [:button-deck/mounted?])
     [child-view])])

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

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Single, plain frame — no URL machinery, no history listener (there is
  ;; no routing here). The default frame is the one Xray reads.
  (rf/dispatch-sync [:button-deck/reset])
  (rdc/render react-root [root]))
