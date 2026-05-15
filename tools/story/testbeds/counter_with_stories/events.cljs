(ns counter-with-stories.events
  "Counter events. The events module is the canonical app slice the
  stories namespace pivots around — three plain `reg-event-db`
  handlers + one `reg-event-fx` initialiser. Nothing Story-specific
  lives here; the same handlers run in the live counter app and in
  the Story playground.

  Per IMPL-SPEC §1.1 + spec/007 §Variants: the variant body is data;
  it references these event ids in its `:events` slot. The handlers
  are owned by the app, not the story.

  Domain shape: a single `:count` slot in the default frame's
  `app-db`. Two button events (`:counter/inc` / `:counter/dec`) and
  one initialiser (`:counter/initialise`) that seeds the slot."
  (:require [re-frame.core :as rf]))

;; -- Events --

;; Initialise the count. The Story playground's `:events` slot dispatches
;; this before any variant-specific events fire, so every variant starts
;; from a known shape. We `assoc` rather than replace the whole `:db`
;; so the Story runtime's per-frame lifecycle machine slot
;; (`:rf/machines`) — which sits in app-db before `:counter/initialise`
;; runs — survives. Returning `{:db {:count ...}}` would clobber it
;; and the variant's lifecycle would stall at `:pre-mount`.
(rf/reg-event-fx :counter/initialise
  (fn [{:keys [db]} [_ initial-count]]
    {:db (assoc db :count (or initial-count 0))}))

(rf/reg-event-db :counter/inc
  (fn [db _event]
    (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event]
    (update db :count dec)))

(rf/reg-event-db :counter/set
  (fn [db [_ n]]
    (assoc db :count n)))

(rf/reg-event-db :counter/throw-deterministic
  (fn [_db _event]
    (throw (ex-info "story-load deterministic event handler failure"
                    {:surface :story-load
                     :kind    :event-handler-exception}))))

(rf/reg-event-db :counter/throw-loader-rejection
  (fn [_db _event]
    (throw (ex-info "story-load deterministic loader rejection"
                    {:surface :story-load
                     :kind    :loader-rejection}))))

(rf/reg-event-db :counter/loader-never-ready?
  (fn [db _event]
    (assoc db :rf.story/loaders-complete? false)))

;; A user-fx so the `force-fx-stub` decorator story has something
;; concrete to stub. In the live app this would talk to a server;
;; for the example we keep it tiny — the point is the stub, not the
;; server.
(rf/reg-fx :counter/sync-to-server
  (fn [_ctx _args]
    ;; In a real app this would issue an HTTP request. For the example
    ;; we noop — the Story playground's `force-fx-stub` decorator
    ;; intercepts the fx-id well before this fires.
    nil))

(rf/reg-event-fx :counter/save
  (fn [{:keys [db]} _event]
    {:db (assoc db :saving? true)
     :fx [[:counter/sync-to-server {:value (:count db)}]]}))
