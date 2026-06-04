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
  static file (or 404s), the response body is decoded (only on 2xx), and
  the reply lands back in app-db. The Retry-recover and Cancel buttons
  exercise the canned-stub seam, which lets the app demonstrate the
  contract without needing a stub HTTP server.

  This example is intentionally minimal — the heavy contract testing
  lives in the JVM smoke (re-frame.http-managed-test) and the
  conformance fixtures (spec/conformance/fixtures/
  http-managed-*.edn). The example itself is the runnable
  cross-substrate sanity check: the same fx, the same reply shape,
  end-to-end through Reagent and Fetch."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Requiring re-frame.http-managed at app boot is what
            ;; triggers its load-time fx registrations (`:rf.http/managed`
            ;; and family) and publishes the late-bind hooks; without
            ;; it, dispatching `:rf.http/managed` would fail with
            ;; :rf.error/no-such-fx.
            [re-frame.http-managed]
            ;; This counter demo wires :fx-overrides into
            ;; :rf.http/managed-canned-success (the canned-success stub).
            ;; The canned-stub fx ids register from
            ;; re-frame.http-test-support, not re-frame.http-managed.
            [re-frame.http-test-support]
            ;; Call-site helpers (rf.http/get / post / put / delete /
            ;; patch / head / options) that synthesise the canonical
            ;; [:rf.http/managed args-map] envelope.
            [re-frame.http :as rf.http]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- App-db shape ------------------------------------------------------------
;;
;; {:counter/count    <int>           ;; current counter value
;;  :counter/status   <:idle|:loading|:error>
;;  :counter/error    <failure-map-or-nil>}
;;
;; Per spec/Conventions §Feature-modularity prefix convention every
;; app-db slot and sub-id carries the feature prefix; events already do.

(rf/reg-event-db :counter/initialise
  (fn [_db _ev]
    {:counter/count  0
     :counter/status :idle
     :counter/error  nil}))

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
               (update :counter/count + (or (:delta (:value (:rf/reply msg))) 1))
               (assoc :counter/status :idle :counter/error nil))}

      ;; Failure branch — record the error.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (-> db
               (assoc :counter/status :error
                      :counter/error  (:failure (:rf/reply msg))))}

      ;; Initial branch — issue the request. The `rf.http/get`
      ;; helper synthesises the same `[:rf.http/managed args-map]`
      ;; envelope a hand-written `:method :get` entry would; the
      ;; call-site reads as one line of intent.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/inc.json" {:decode :json})]})))

;; -- Fail (real 404 from http-server) ----------------------------------------

(rf/reg-event-fx :counter/fail
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc db :counter/status :error :counter/error (:failure (:rf/reply msg)))}

      (some-> msg :rf/reply :kind (= :success))
      ;; Should not happen — the URL is intentionally 404.
      {:db (assoc db :counter/status :idle :counter/error nil)}

      ;; Same `rf.http/get` helper as above. Per Spec 014
      ;; §Classification order, status-check fires before decode — so
      ;; even with `:decode :json`, a 404 with an HTML or plain-text
      ;; body classifies as :rf.http/http-4xx (the raw body lands at
      ;; :body), not :rf.http/decode-failure. Leaving :decode at the
      ;; default `:auto` here exercises the common case: a JSON
      ;; endpoint that 404s with a load-balancer HTML page.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/does-not-exist")]})))

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
               (update :counter/count + (or (:delta (:value (:rf/reply msg))) 0))
               (assoc :counter/status :idle :counter/error nil))}

      :else
      {:db (assoc db :counter/status :loading)
       ;; The canned-success stub synthesises the reply per Spec 014
       ;; §Testing. The retry policy is declared so user code reads
       ;; the same as a live retry-recover; the stub short-circuits.
       :fx [[:rf.http/managed-canned-success
             {:request {:method :get :url "api/flaky"}
              :decode  :json
              :value   {:delta 5}}]]})))

;; -- Cancel an in-flight request ---------------------------------------------
;;
;; A "long" request defers its synthetic failure-reply via the
;; canned-failure stub's `:after-ms` (rf2-j1mo4) so the :loading state is
;; observable in a browser long enough for the cancel UI to interact with
;; it. The Spec 014 contract is that an in-flight request dispatches
;; `:rf.http/aborted` on cancellation; routing a deferred
;; `:rf.http/managed-canned-failure` reply via `:after-ms` mimics that
;; contract end-to-end without requiring a real long-running endpoint —
;; the framework schedules it through `:dispatch-later` internally, so
;; time controls (Tool-Pair time-travel, the documented `:dispatch-later`
;; nil-override) apply automatically. `:after-ms` collapses the former
;; `:dispatch-later` → deliver-long-reply → canned-failure chain into one
;; parameter of the same canned effect.

(rf/reg-event-fx :counter/start-long
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — the live fx may dispatch :rf.http/aborted on
      ;; cancellation, which lands here via default reply addressing.
      ;; Either kind (success / failure) returns the UI to :idle.
      (some-> msg :rf/reply :kind some?)
      {:db (assoc db :counter/status :idle)}

      ;; Defer the canned-failure via :after-ms so the :loading UI state
      ;; is observable before it lands. The +1 / Fail / Retry-recover
      ;; paths above still hit the framework's :rf.http/managed; only this
      ;; "long-running" demo defers a synthetic reply.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [[:rf.http/managed-canned-failure
             {:request    {:method :get :url "api/long"}
              :request-id :counter/long
              :decode     :json
              :after-ms   750
              :kind       :rf.http/transport
              :tags       {:message "Demo: long request did not resolve."}}]]})))

(rf/reg-event-fx :counter/cancel
  (fn [{:keys [db]} _]
    {:db (assoc db :counter/status :idle)
     ;; Abort by request-id. The live fx fires the abort handle and
     ;; the in-flight request emits :on-failure :rf.http/aborted via
     ;; default reply addressing back to :counter/start-long, which
     ;; handles the reply by clearing :counter/status (see above).
     :fx [[:rf.http/managed-abort :counter/long]]}))

;; -- Subs --------------------------------------------------------------------

(rf/reg-sub :counter/count  (fn [db _] (:counter/count  db)))
(rf/reg-sub :counter/status (fn [db _] (:counter/status db)))
(rf/reg-sub :counter/error  (fn [db _] (:counter/error  db)))

;; -- Views -------------------------------------------------------------------

(reg-view counter-view []
  (let [count  @(subscribe [:counter/count])
        status @(subscribe [:counter/status])
        error  @(subscribe [:counter/error])]
    [:div {:style {:font-family "sans-serif" :padding "1em"}}
     [:h1 "Managed HTTP counter"]
     [:p "Count: " [:span {:data-testid "count"} count]]
     [:p "Status: " [:span {:data-testid "status"} (name status)]]
     (when error
       [:p {:data-testid "error"
            :style       {:color "crimson"}}
        "Error kind: " (str (:kind error))])
     [:div {:style {:display :flex :gap "0.5em"}}
      [:button {:on-click #(dispatch [:counter/+1])}              "+1"]
      [:button {:on-click #(dispatch [:counter/fail])}            "Fail"]
      [:button {:on-click #(dispatch [:counter/retry-recover])}   "Retry-recover"]
      [:button {:on-click #(dispatch [:counter/start-long])}      "Start long"]
      [:button {:on-click #(dispatch [:counter/cancel])}          "Cancel"]]]))

(reg-view counter-app []
  [counter-view])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [counter-app])))
