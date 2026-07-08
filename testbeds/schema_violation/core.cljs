(ns schema-violation.core
  "Shared framework-behavior testbed — four trigger sites that each fail
  a distinct schema check-point in the per-event order (per [spec/010
  §Per-step recovery]). Three are `:where` surfaces of the dev-only
  `:rf.error/schema-validation-failure`; the fourth (Button C) is the
  separate, halting `:rf.error/cofx-value-invalid` hard error. One button
  per surface so a consumer (Xray, Story, re-frame2-pair-mcp) observes the
  corresponding failure shape emerge once per click.

  Triggered surfaces:

    Button A → :rf.error/schema-validation-failure :where :app-db
               (post-handler :db fails its registered :rf/app-schema)
    Button B → :rf.error/schema-validation-failure :where :event
               (dispatched vector fails the handler's :schema)
    Button C → :rf.error/cofx-value-invalid
               (a recordable cofx's value fails its reg-cofx :schema — a
               PRODUCTION HARD ERROR that THROWS and halts the run, NOT a
               :where :cofx trace; that surface was retired in EP-0017)
    Button D → :rf.error/schema-validation-failure :where :fx-args
               (fx :schema rejects the offending fx's args)

  The recoveries differ (rollback, skip-handler, hard-error/throw,
  skip-fx) — see README.md for the per-button matrix."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loads the schemas artefact's late-bind hooks (rf2-p7va).
            ;; Required before any reg-app-schema / :spec metadata is
            ;; consumed by validation.
            [re-frame.schemas]
            ;; Publish Malli into the schemas artefact. Without this adapter
            ;; CLJS schema checks soft-pass by design, so the browser testbed
            ;; would expose counters but no recovery traces.
            [re-frame.schemas.malli]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ----------------------------------------------------------------------------
;; App-db + schemas
;; ----------------------------------------------------------------------------
;;
;; Minimal — three counters plus an :auth slice with a schema. The
;; schema's only constraint is :token must be a string; Button A
;; deliberately writes an int there.

(def AuthSlice
  [:map [:token :string]])

;; EP-0002 (rf2-5q7um6): reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This testbed hosts on
;; :rf/default, so name it explicitly.
(with-frame :rf/default
  (rf/reg-app-schema [:auth] AuthSlice))

(rf/reg-event ::initialise
  (fn [_cofx _ev]
    {:db {:auth        {:token "seed-token"}
          :click-count {:app-db 0 :event 0 :cofx 0 :fx 0}}}))

;; ----------------------------------------------------------------------------
;; Button A — :where :app-db (handler commits a value that fails the
;;            registered :rf/app-schema at the registered path)
;; ----------------------------------------------------------------------------
;;
;; The handler returns a `:db` whose [:auth :token] slot holds an int.
;; The post-handler app-db validation step (per [spec/010 §Validation
;; order] step 4) catches it and emits the failure trace with
;; :rollback? true. The :db effect is rolled back — :auth :token stays
;; at "seed-token" — and the dispatch is treated as failed (flows do
;; not evaluate, :fx does not walk).

(rf/reg-event ::violate-app-db
  (fn [{:keys [db]} _ev]
    ;; HOT PATH — the commit site for :where :app-db.
    ;; The runtime's post-handler validation re-reads [:auth :token]
    ;; against AuthSlice and rejects the int 42.
    {:db (assoc-in db [:auth :token] 42)}))

;; ----------------------------------------------------------------------------
;; Button B — :where :event (dispatched vector fails the handler's :spec)
;; ----------------------------------------------------------------------------
;;
;; The handler declares :spec requiring a positive int as arg-1. Button
;; B dispatches it with the string "not-a-number". Per [spec/010
;; §Per-step recovery] step 1, the handler is NOT invoked; the failure
;; trace fires with :where :event; the downstream queue continues to
;; drain (the cascade stops only at this event).

(rf/reg-event ::violate-event
  {:schema [:cat [:= ::violate-event] pos-int?]}
  (fn [{:keys [db]} _ev]
    {:db (update-in db [:click-count :event] inc)}))

;; ----------------------------------------------------------------------------
;; Button C — :rf.error/cofx-value-invalid (a recordable cofx's value
;;            fails its reg-cofx :schema — a HALTING production hard error)
;; ----------------------------------------------------------------------------
;;
;; EP-0017: `reg-cofx` is a value-returning supplier — the v1 ctx->ctx
;; idiom and `inject-cofx` are removed. A recordable coeffect
;; (`:rf.cofx/requires`) rides the durable causal record (epoch ledger,
;; replay, SSR payload, Xray), so a bad value is NOT a recoverable
;; dev-only trace: it is `:rf.error/cofx-value-invalid`, a PRODUCTION
;; HARD ERROR that THROWS and halts the run. The old `:where :cofx`
;; schema-validation surface (skip-handler, queue-continues) was RETIRED
;; in EP-0017 (rf2-nkf4l3) — a recordable-coeffect schema miss does NOT
;; emit `:rf.error/schema-validation-failure` at all.
;;
;; The cofx :schema demands a positive int; the supplier deliberately
;; returns -1. Per [spec/010 §Validation order] step 2 the supplied
;; value is validated as it is satisfied, before it folds into the
;; handler context: the mismatch emits `:rf.error/cofx-value-invalid`
;; (`:recovery :no-recovery`) and THROWS. The handler NEVER runs, and
;; because the throw halts the run the dispatch does not settle — this is
;; a hard error, not the "handler skipped, queue continues" recovery of
;; the schema-validation `:where` surfaces.

(rf/reg-cofx ::bad-counter
  {:doc    "Deliberately returns a value its own :schema rejects, to
            light up the :rf.error/cofx-value-invalid hard-error surface."
   :schema pos-int?}
  (fn []
    ;; HOT PATH — the supply site for :rf.error/cofx-value-invalid.
    ;; Returns a value the registered :schema rejects → throws.
    -1))

(rf/reg-event ::violate-cofx
  {:rf.cofx/requires [::bad-counter]}
  (fn [{:keys [db]} _ev]
    {:db (update-in db [:click-count :cofx] inc)}))

;; ----------------------------------------------------------------------------
;; Button D — :where :fx-args (fx :spec rejects the offending fx's args)
;; ----------------------------------------------------------------------------
;;
;; The fx's :spec demands a map with :url string. Button D's handler
;; dispatches it with a vector. Per [spec/010 §Per-step recovery] step
;; 5, the offending fx is skipped (the failure trace fires with :where
;; :fx-args); other fx in the same :fx vector continue. The handler's
;; :db already committed.

(rf/reg-fx ::violate-fx
  {:schema [:map [:url :string]]}
  (fn [_frame-ctx _args]
    ;; This body never runs in the canonical Button-D path — the :schema
    ;; rejects the args before the fx is invoked. (If the user disables
    ;; validation in production, this body would run with the
    ;; misshapen args, which is harmless for this testbed.)
    nil))

(rf/reg-event ::violate-fx-args
  (fn [{:keys [db]} _ev]
    {:db (update-in db [:click-count :fx] inc)
     :fx [;; HOT PATH — the fx-args site for :where :fx-args.
          ;; The registered :spec is [:map [:url :string]]; supplying
          ;; a vector triggers the per-fx skip path.
          [::violate-fx ["not-a-map"]]]}))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :auth-token   (fn [db _] (get-in db [:auth :token])))
(rf/reg-sub :app-db-count (fn [db _] (get-in db [:click-count :app-db])))
(rf/reg-sub :event-count  (fn [db _] (get-in db [:click-count :event])))
(rf/reg-sub :cofx-count   (fn [db _] (get-in db [:click-count :cofx])))
(rf/reg-sub :fx-count     (fn [db _] (get-in db [:click-count :fx])))

(reg-view buttons []
  [:div {:data-testid "schema-violation" :style {:font-family "sans-serif" :padding "1em"}}
   [:h1 "schema-violation testbed"]
   [:p "Buttons A, B, D each trigger one :where surface of "
    [:code ":rf.error/schema-validation-failure"]
    "; Button C triggers the separate, halting "
    [:code ":rf.error/cofx-value-invalid"] " hard error."]
   [:p {:data-testid "schema-recovery-browser-semantics"
        :style       {:color "#666"}}
    "Malli validation is loaded for this browser build; the feature gate
     asserts rollback, skipped handlers, the cofx-value-invalid throw,
     skipped fx, and the matching Xray timeline traces."]
   [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
    [:button {:data-testid "violate-app-db"
              :on-click #(dispatch [::violate-app-db])}
     "A · :where :app-db (rolled back)"]
    [:button {:data-testid "violate-event"
              :on-click #(dispatch [::violate-event "not-a-number"])}
     "B · :where :event (handler skipped)"]
    [:button {:data-testid "violate-cofx"
              ;; :rf.error/cofx-value-invalid THROWS (halting hard error).
              ;; Use `dispatch-sync` (not the reg-view-injected `dispatch`
              ;; local) so the throw is synchronous at the click boundary
              ;; and can be caught here — the demo then
              ;; surfaces the error via the trace / error-emit stream (which
              ;; fires BEFORE the throw) without an uncaught exception
              ;; escaping to window.onerror. The handler never ran, so
              ;; :cofx-count stays at 0. (The other buttons use async
              ;; dispatch; this one must be sync to catch the throw at its
              ;; call site.)
              :on-click (fn []
                          (try
                            (rf/dispatch-sync [::violate-cofx] {:frame :rf/default})
                            (catch :default _ nil)))}
     "C · :rf.error/cofx-value-invalid (throws — run halts)"]
    [:button {:data-testid "violate-fx-args"
              :on-click #(dispatch [::violate-fx-args])}
     "D · :where :fx-args (fx skipped)"]]
   [:p {:style {:margin-top "1em" :color "#666"}}
    "token=" [:span {:data-testid "auth-token"} @(subscribe [:auth-token])]
    " · app-db=" [:span {:data-testid "app-db-count"} @(subscribe [:app-db-count])]
    " · event=" [:span {:data-testid "event-count"} @(subscribe [:event-count])]
    " · cofx=" [:span {:data-testid "cofx-count"} @(subscribe [:cofx-count])]
    " · fx=" [:span {:data-testid "fx-count"} @(subscribe [:fx-count])]]])

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  (rdc/render react-root [root]))
