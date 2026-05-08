(ns managed-http-counter.core
  "Managed-HTTP counter — Spec 014 §`:rf.http/managed` example app.

  A counter where each button issues a managed HTTP request and the
  reply lands back in app-db via the default reply-addressing path.

  Buttons:
    +1                — GET /api/inc.json (static asset; success path)
    Fail              — GET /api/does-not-exist (404 → :rf.http/http-4xx)
    Retry-recover     — GET /api/retry-recover (canned-stub at app level
                                                synthesises retry success)
    Cancel            — :rf.http/managed-abort by :request-id

  The +1 and Fail buttons exercise a REAL round-trip: Fetch hits the
  static file (or 404s), the response body is decoded, and the reply
  lands back in app-db. The Retry-recover and Cancel buttons exercise
  the canned-stub seam, which lets the app demonstrate the contract
  without needing a stub HTTP server.

  This example is intentionally minimal — the heavy contract testing
  lives in the JVM smoke (re-frame.http-managed-test) and the
  conformance fixtures (docs/specification/conformance/fixtures/
  http-managed-*.edn). Playwright's role is the cross-substrate sanity
  check: the same fx, the same reply shape, end-to-end through Reagent
  and Fetch."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.substrate.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; -- App-db shape ------------------------------------------------------------
;;
;; {:count    <int>           ;; current counter value
;;  :status   <:idle|:loading|:error>
;;  :error    <failure-map-or-nil>}

(rf/reg-event-db :counter/initialise
  (fn [_db _ev]
    {:count 0 :status :idle :error nil}))

;; -- +1 (real round-trip via Fetch) ------------------------------------------
;;
;; The +1 button fires :counter/+1, which issues a real GET to
;; /api/inc.json. The response body — `{"delta": 1}` — is decoded
;; as JSON; the default reply-addressing path re-dispatches
;; [:counter/+1 (assoc msg :rf/reply ...)] to the originating handler,
;; which branches on (:rf/reply msg) to apply the increment.

(rf/reg-event-fx :counter/+1
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — increment by the delta the server returned.
      (some-> msg :rf/reply :kind (= :success))
      {:db (-> db
               (update :count + (or (:delta (:value (:rf/reply msg))) 1))
               (assoc :status :idle :error nil))}

      ;; Failure branch — record the error.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (-> db
               (assoc :status :error
                      :error  (:failure (:rf/reply msg))))}

      ;; Initial branch — issue the request.
      :else
      {:db (assoc db :status :loading :error nil)
       :fx [[:rf.http/managed
             {:request {:method :get :url "api/inc.json"}
              :decode  :json}]]})))

;; -- Fail (real 404 from http-server) ----------------------------------------

(rf/reg-event-fx :counter/fail
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc db :status :error :error (:failure (:rf/reply msg)))}

      (some-> msg :rf/reply :kind (= :success))
      ;; Should not happen — the URL is intentionally 404.
      {:db (assoc db :status :idle :error nil)}

      :else
      {:db (assoc db :status :loading :error nil)
       :fx [[:rf.http/managed
             {:request {:method :get :url "api/does-not-exist"}
              ;; :decode :text — http-server returns plain text for a 404
              ;; ("Not found"). We decode-as-text so the 4xx classification
              ;; reaches the failure branch with :rf.http/http-4xx; if we
              ;; asked for :json the decode would fail FIRST and the
              ;; failure would classify as :rf.http/decode-failure (a
              ;; classification-ordering quirk in the Phase 1 impl that
              ;; this app doesn't try to exercise).
              :decode  :text}]]})))

;; -- Retry-recover (canned-stub at app level) --------------------------------
;;
;; This demonstrates the recovery-after-retry path without needing a
;; flaky HTTP endpoint. The :counter/recover handler issues
;; :rf.http/managed redirected to :rf.http/managed-canned-success,
;; which synthesises a {:kind :success :value {:delta 5}} reply. The
;; same reply shape would land if the live fx ran the retry policy
;; against a real endpoint that 503'd once and then 200'd.

(rf/reg-event-fx :counter/retry-recover
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :success))
      {:db (-> db
               (update :count + (or (:delta (:value (:rf/reply msg))) 0))
               (assoc :status :idle :error nil))}

      :else
      {:db (assoc db :status :loading)
       ;; The canned-success stub synthesises the reply per Spec 014
       ;; §Testing. The retry policy is declared so user code reads
       ;; the same as a live retry-recover; the stub short-circuits.
       :fx [[:rf.http/managed-canned-success
             {:request {:method :get :url "api/flaky"}
              :decode  :json
              :value   {:delta 5}}]]})))

;; -- Cancel an in-flight request ---------------------------------------------
;;
;; A "long" request is issued to /api/never (a path http-server takes
;; some non-zero time to 404), and the cancel button aborts by
;; :request-id. The Spec 014 contract is that the in-flight request
;; dispatches :rf.http/aborted on cancellation. We use a canned-failure
;; stub to make the example deterministic — the live fx would also
;; resolve the same way once the abort fires.

(rf/reg-event-fx :counter/start-long
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — the live fx may dispatch :rf.http/aborted on
      ;; cancellation, which lands here via default reply addressing.
      ;; Either kind (success / failure) returns the UI to :idle.
      (some-> msg :rf/reply :kind some?)
      {:db (assoc db :status :idle)}

      :else
      {:db (assoc db :status :loading :error nil)
       :fx [[:rf.http/managed
             {:request    {:method :get :url "api/long"}
              :request-id :counter/long
              :decode     :json}]]})))

(rf/reg-event-fx :counter/cancel
  (fn [{:keys [db]} _]
    {:db (assoc db :status :idle)
     ;; Abort by request-id. The live fx fires the abort handle and
     ;; the in-flight request emits :on-failure :rf.http/aborted via
     ;; default reply addressing back to :counter/start-long, which
     ;; handles the reply by clearing :status (see above).
     :fx [[:rf.http/managed-abort :counter/long]]}))

;; -- Subs --------------------------------------------------------------------

(rf/reg-sub :count  (fn [db _] (:count db)))
(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :error  (fn [db _] (:error db)))

;; -- Views -------------------------------------------------------------------

(reg-view :counter-view
  (fn []
    (let [d (rf/dispatcher)
          s (rf/subscriber)
          count  @(s [:count])
          status @(s [:status])
          error  @(s [:error])]
      [:div {:style {:font-family "sans-serif" :padding "1em"}}
       [:h1 "Managed HTTP counter"]
       [:p "Count: " [:span {:data-testid "count"} count]]
       [:p "Status: " [:span {:data-testid "status"} (name status)]]
       (when error
         [:p {:data-testid "error"
              :style       {:color "crimson"}}
          "Error kind: " (str (:kind error))])
       [:div {:style {:display :flex :gap "0.5em"}}
        [:button {:on-click #(d [:counter/+1])}              "+1"]
        [:button {:on-click #(d [:counter/fail])}            "Fail"]
        [:button {:on-click #(d [:counter/retry-recover])}   "Retry-recover"]
        [:button {:on-click #(d [:counter/start-long])}      "Start long"]
        [:button {:on-click #(d [:counter/cancel])}          "Cancel"]]])))

(reg-view :counter-app
  (fn []
    [counter-view]))

;; -- Mount -------------------------------------------------------------------

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter-app]))
