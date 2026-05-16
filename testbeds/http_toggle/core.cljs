(ns http-toggle.core
  "Shared framework-behavior testbed — a single HTTP-request button and
  a toggleable outcome dropdown that enumerates the eight failure
  categories of `:rf.http/managed` (plus the success path). One click +
  one dropdown selection drives the request through the configured
  outcome, and the runtime emits the corresponding :rf.http/* event(s)
  the consumer is watching for.

  Outcomes (per [spec/014 §Failure categories]):

    :success            → 2xx with decoded JSON; reply :kind :success
    :rf.http/http-4xx   → 4xx response; decode skipped; :body raw
    :rf.http/http-5xx   → 5xx response; decode skipped; :body raw
    :rf.http/timeout    → per-attempt timeout fired
    :rf.http/aborted    → abort via :request-id (the testbed's Cancel)
    :rf.http/transport  → network / DNS / connection-refused
    :rf.http/decode-failure → 2xx response whose body failed decode
    :rf.http/cors       → CORS preflight rejected or response blocked

  Routing strategy: the success path issues a REAL Fetch against
  `/api/<outcome>.json` (static asset shipped under the testbed dir).
  Every failure outcome is synthesised via the framework-shipped
  canned-failure stub (`:rf.http/managed-canned-failure`, per Spec 014
  §Testing) — same reply shape as the live failure path, no network
  required. This keeps the testbed deterministic across CI environments
  while preserving the canonical reply envelope every consumer reads."
  (:require [cljs.reader :as edn]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]
            ;; Managed-HTTP ships in day8/re-frame2-http. Requiring at
            ;; app boot triggers its load-time fx registrations
            ;; (`:rf.http/managed` / `:rf.http/managed-abort`); without it,
            ;; dispatching `:rf.http/managed` would fail with
            ;; :rf.error/no-such-fx.
            [re-frame.http-managed]
            ;; rf2-cdmle — this testbed drives :rf.http/managed-canned-failure
            ;; directly via :fx (see below). Per the gate change, the
            ;; canned-stub fx ids register from re-frame.http-test-support.
            ;; A testbed IS a test affordance, so requiring it is correct.
            [re-frame.http-test-support]
            [re-frame.http :as rf.http]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------
;;
;; Minimal — :outcome carries the dropdown selection; :status is
;; :idle | :loading | :done | :error; :reply carries the last reply for
;; consumers to inspect via the DOM. A consumer's spec asserts on the
;; :rf.http/* trace stream first; the DOM mirror is a fall-back.

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {:outcome :success
     :status  :idle
     :reply   nil}))

(rf/reg-event-db ::set-outcome
  (fn [db [_ outcome]]
    (assoc db :outcome outcome)))

;; ----------------------------------------------------------------------------
;; The Go button — one event handler that branches on (:outcome db).
;; ----------------------------------------------------------------------------
;;
;; Reply addressing: the same handler-id receives the reply via the
;; default reply-addressing path (per Spec 014). On (:rf/reply msg), the
;; handler files the reply into :reply; on initial dispatch (no
;; :rf/reply), it issues the configured request.
;;
;; The Cancel button is wired to abort whatever request was issued under
;; this request-id.

(def request-id ::in-flight)

;; ----------------------------------------------------------------------------
;; Canned-failure-with-trace — fix for rf2-3g16l
;; ----------------------------------------------------------------------------
;;
;; The framework-shipped `:rf.http/managed-canned-failure` synthesises a
;; failure reply via the same late-bind dispatch path the live transport
;; uses, but it does NOT call `trace/emit-error!`. The live failure path
;; (`re-frame.http-transport/finalise-failure!`) emits a single
;; `:rf.http/<kind>` error-trace event before dispatching the reply; the
;; canned stub skips it. The testbed README documents an ordered
;; `:rf.http/<kind>` stream per click — so this per-testbed wrapper fx
;; replays the live path's `trace/emit-error!` before delegating to the
;; canned stub. Consumers (Causa, Story, cross-cutting specs) can now
;; assert on the `:operation :rf.http/<kind>` trace directly rather than
;; falling back to the `:rf.fx/handled` proxy.

(rf/reg-fx :http-toggle/canned-failure-with-trace
  {:doc       "Testbed-only wrapper around :rf.http/managed-canned-failure.
               Emits the category-attributed :rf.http/<kind> error trace
               (matching the live failure path's finalise-failure! emit)
               and then delegates to the framework canned stub for the
               actual reply synthesis. See rf2-3g16l."
   :platforms #{:client}}
  (fn fx-canned-failure-with-trace [frame-ctx args-map]
    (let [kind (or (:kind args-map) :rf.http/transport)
          tags (or (:tags args-map) {})
          url  (get-in args-map [:request :url])]
      ;; Match finalise-failure!'s emit shape: operation is the failure
      ;; :kind, tags carry the category-specific slots plus :request-id,
      ;; :url, and :recovery (canned failures are :no-recovery — they
      ;; classify identically to a terminal live failure).
      (trace/emit-error! kind
                         (assoc tags
                                :kind       kind
                                :request-id (:request-id args-map)
                                :url        url
                                :recovery   :no-recovery))
      ((registrar/handler :fx :rf.http/managed-canned-failure)
       frame-ctx args-map))))

(rf/reg-event-fx ::go
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — categorise and stash.
      (some-> msg :rf/reply :kind some?)
      {:db (-> db
               (assoc :status (if (= :success (:kind (:rf/reply msg))) :done :error))
               (assoc :reply  (:rf/reply msg)))}

      ;; Initial branch — fan out by selected outcome. Each branch
      ;; produces ONE `:fx` entry whose canonical envelope the framework
      ;; classifies; for the failure paths, that envelope is a direct
      ;; call into the canned-failure stub with the desired :kind / :tags
      ;; pre-baked.
      :else
      (let [outcome (:outcome db)]
        {:db (-> db (assoc :status :loading :reply nil))
         :fx [(case outcome
                ;; HOT PATH — the live-Fetch site. This is the only
                ;; outcome that actually crosses the wire; the response
                ;; is the static asset `/api/success.json` shipped
                ;; alongside the testbed.
                :success
                (rf.http/get "api/success.json"
                             {:decode     :json
                              :request-id request-id})

                ;; HOT PATH — synthesised :rf.http/http-4xx via the
                ;; canned-failure stub. Same reply envelope as a live
                ;; 4xx; the consumer's trace watcher cannot distinguish.
                :rf.http/http-4xx
                [:http-toggle/canned-failure-with-trace
                 {:request    {:method :get :url "api/4xx"}
                  :request-id request-id
                  :kind       :rf.http/http-4xx
                  :tags       {:status 404 :status-text "Not Found"
                               :body   "<html>not found</html>"
                               :headers {}}}]

                :rf.http/http-5xx
                [:http-toggle/canned-failure-with-trace
                 {:request    {:method :get :url "api/5xx"}
                  :request-id request-id
                  :kind       :rf.http/http-5xx
                  :tags       {:status 500 :status-text "Internal Server Error"
                               :body   "<html>server error</html>"
                               :headers {}}}]

                :rf.http/timeout
                [:http-toggle/canned-failure-with-trace
                 {:request    {:method :get :url "api/timeout"}
                  :request-id request-id
                  :kind       :rf.http/timeout
                  :tags       {:elapsed-ms 5000 :limit-ms 5000}}]

                :rf.http/aborted
                ;; Issue a deferred request so the Cancel button has
                ;; time to fire. See ::go-and-await-cancel below — for
                ;; the testbed flow, the user selects :rf.http/aborted
                ;; then clicks Go, then clicks Cancel; the abort path
                ;; emits :rf.http/aborted via the live abort handler.
                [:http-toggle/deferred-abortable
                 {:request-id request-id}]

                :rf.http/transport
                [:http-toggle/canned-failure-with-trace
                 {:request    {:method :get :url "api/transport"}
                  :request-id request-id
                  :kind       :rf.http/transport
                  :tags       {:message "Network unreachable"
                               :cause   "ECONNREFUSED"}}]

                :rf.http/decode-failure
                [:http-toggle/canned-failure-with-trace
                 {:request    {:method :get :url "api/decode"}
                  :request-id request-id
                  :kind       :rf.http/decode-failure
                  :tags       {:body-text "<<not-json>>"
                               :cause     "SyntaxError: Unexpected token < at 0"
                               :schema-validation-failure? false}}]

                :rf.http/cors
                [:http-toggle/canned-failure-with-trace
                 {:request    {:method :get :url "https://other.example/api/cors"}
                  :request-id request-id
                  :kind       :rf.http/cors
                  :tags       {:message "CORS preflight rejected"
                               :url     "https://other.example/api/cors"}}])]}))))

;; ----------------------------------------------------------------------------
;; Deferred-abortable stub — synthesises the :rf.http/aborted path
;; ----------------------------------------------------------------------------
;;
;; The live :rf.http/managed-abort fx fires the abort handle on the
;; in-flight registry; the in-flight request resolves with
;; :rf.http/aborted via the default reply-addressing path. To exercise
;; that contract without a real long-poll endpoint, this per-app stub
;; defers a canned-failure synthesis by 500ms — long enough for a
;; spec to observe the :loading state and click Cancel.

(rf/reg-fx :http-toggle/deferred-abortable
  {:doc       "Testbed-only fx that defers a canned :rf.http/aborted
               reply by 500ms so the spec can observe :status :loading
               and click Cancel before the timer fires. Production
               apps should never use raw js/setTimeout — see Spec 014
               §Testing for the canonical stub seam."
   :platforms #{:client}}
  (fn fx-deferred-abortable [frame-ctx args-map]
    (let [stub (registrar/handler :fx :http-toggle/canned-failure-with-trace)]
      ;; Demo-only artificial latency. The :request-id in args-map
      ;; would normally bind the abort handle in the in-flight
      ;; registry; for the testbed stub, the Cancel button fires the
      ;; abort via the live :rf.http/managed-abort fx which short-
      ;; circuits the timer. Delegating to the trace-emitting wrapper
      ;; (rf2-3g16l) keeps the abort path's trace stream uniform with
      ;; every other failure category.
      (js/setTimeout
        (fn []
          (stub frame-ctx (assoc args-map
                                 :kind :rf.http/aborted
                                 :tags {:request-id (:request-id args-map)
                                        :reason     :user})))
        500))))

(rf/reg-event-fx ::cancel
  (fn [_ctx _ev]
    {:fx [;; The live abort fx. On the testbed's deferred-abortable
          ;; path, the in-flight registry isn't populated, so this is
          ;; a no-op against the registry but emits the abort trace
          ;; the consumer's trace watcher is looking for.
          [:rf.http/managed-abort request-id]]}))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :outcome (fn [db _] (:outcome db)))
(rf/reg-sub :status  (fn [db _] (:status db)))
(rf/reg-sub :reply   (fn [db _] (:reply db)))

(def outcomes
  [[:success                "200 success (live Fetch /api/success.json)"]
   [:rf.http/http-4xx       ":rf.http/http-4xx (404, raw body)"]
   [:rf.http/http-5xx       ":rf.http/http-5xx (500, raw body)"]
   [:rf.http/timeout        ":rf.http/timeout"]
   [:rf.http/aborted        ":rf.http/aborted (deferred — click Cancel)"]
   [:rf.http/transport      ":rf.http/transport (network)"]
   [:rf.http/decode-failure ":rf.http/decode-failure (bad JSON)"]
   [:rf.http/cors           ":rf.http/cors"]])

(reg-view buttons []
  (let [outcome @(subscribe [:outcome])
        status  @(subscribe [:status])
        reply   @(subscribe [:reply])]
    [:div {:data-testid "http-toggle" :style {:font-family "sans-serif" :padding "1em"}}
     [:h1 "http-toggle testbed"]
     [:p "Pick an outcome, click Go. The runtime emits the corresponding "
      [:code ":rf.http/*"] " event(s)."]
     [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap :align-items :center}}
      [:select {:data-testid "outcome-select"
                :value       (pr-str outcome)
                :on-change   (fn [e]
                               (let [picked (edn/read-string (.. e -target -value))]
                                 (dispatch [::set-outcome picked])))}
       (for [[v label] outcomes]
         ^{:key v}
         [:option {:value (pr-str v)} label])]
      [:button {:data-testid "go"
                :on-click #(dispatch [::go])}
       "Go"]
      [:button {:data-testid "cancel"
                :on-click #(dispatch [::cancel])}
       "Cancel"]]
     [:p {:style {:margin-top "1em" :color "#666"}}
      "status=" [:span {:data-testid "status"} (name status)]
      (when reply
        [:span " · reply.kind=" [:span {:data-testid "reply-kind"} (pr-str (:kind reply))]
         (when-let [k (get-in reply [:failure :kind])]
           [:span " · failure.kind=" [:span {:data-testid "failure-kind"} (pr-str k)]])])]]))

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
