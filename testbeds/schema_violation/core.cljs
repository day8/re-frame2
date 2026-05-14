(ns schema-violation.core
  "Shared framework-behavior testbed — four trigger sites that each fail
  schema validation at a distinct check-point in the per-event order
  (per [spec/010 §Per-step recovery]). One button per :where surface so
  a consumer (Causa, Story, pair2-mcp) observes the corresponding
  :rf.error/schema-validation-failure shape emerge once per click.

  Triggered :where surfaces:

    Button A → :where :app-db     (post-handler :db fails its registered :rf/app-schema)
    Button B → :where :event      (dispatched vector fails the handler's :spec)
    Button C → :where :cofx       (cofx :spec rejects the injected value)
    Button D → :where :fx-args    (fx :spec rejects the offending fx's args)

  Each recovery differs (rollback, skip-handler, skip-fx, ...) — see
  README.md for the per-button matrix."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Loads the schemas artefact's late-bind hooks (rf2-p7va).
            ;; Required before any reg-app-schema / :spec metadata is
            ;; consumed by validation.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; App-db + schemas
;; ----------------------------------------------------------------------------
;;
;; Minimal — three counters plus an :auth slice with a schema. The
;; schema's only constraint is :token must be a string; Button A
;; deliberately writes an int there.

(def AuthSlice
  [:map [:token :string]])

(rf/reg-app-schema [:auth] AuthSlice)

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {:auth        {:token "seed-token"}
     :click-count {:app-db 0 :event 0 :cofx 0 :fx 0}}))

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

(rf/reg-event-db ::violate-app-db
  (fn [db _ev]
    ;; HOT PATH — the commit site for :where :app-db.
    ;; The runtime's post-handler validation re-reads [:auth :token]
    ;; against AuthSlice and rejects the int 42.
    (assoc-in db [:auth :token] 42)))

;; ----------------------------------------------------------------------------
;; Button B — :where :event (dispatched vector fails the handler's :spec)
;; ----------------------------------------------------------------------------
;;
;; The handler declares :spec requiring a positive int as arg-1. Button
;; B dispatches it with the string "not-a-number". Per [spec/010
;; §Per-step recovery] step 1, the handler is NOT invoked; the failure
;; trace fires with :where :event; the downstream queue continues to
;; drain (the cascade stops only at this event).

(rf/reg-event-db ::violate-event
  {:spec [:cat [:= ::violate-event] pos-int?]}
  (fn [db _ev]
    (update-in db [:click-count :event] inc)))

;; ----------------------------------------------------------------------------
;; Button C — :where :cofx (cofx :spec rejects the injected value)
;; ----------------------------------------------------------------------------
;;
;; The cofx :spec demands the injected value is a positive int; the
;; cofx body deliberately injects -1. The handler is NOT invoked; the
;; failure trace fires with :where :cofx and :failing-id naming the
;; cofx. The downstream queue continues to drain.

(rf/reg-cofx ::bad-counter
  {:spec pos-int?}
  (fn [coeffects _]
    ;; HOT PATH — the injection site for :where :cofx.
    ;; Returns a value that the registered :spec will reject.
    (assoc coeffects ::bad-counter -1)))

(rf/reg-event-fx ::violate-cofx
  [(rf/inject-cofx ::bad-counter)]
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
  {:spec [:map [:url :string]]}
  (fn [_frame-ctx _args]
    ;; This body never runs in the canonical Button-D path — the :spec
    ;; rejects the args before the fx is invoked. (If the user disables
    ;; validation in production, this body would run with the
    ;; misshapen args, which is harmless for this testbed.)
    nil))

(rf/reg-event-fx ::violate-fx-args
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
   [:p "Each button triggers exactly one :where surface of "
    [:code ":rf.error/schema-validation-failure"] "."]
   [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
    [:button {:data-testid "violate-app-db"
              :on-click #(dispatch [::violate-app-db])}
     "A · :where :app-db (rolled back)"]
    [:button {:data-testid "violate-event"
              :on-click #(dispatch [::violate-event "not-a-number"])}
     "B · :where :event (handler skipped)"]
    [:button {:data-testid "violate-cofx"
              :on-click #(dispatch [::violate-cofx])}
     "C · :where :cofx (handler skipped)"]
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
