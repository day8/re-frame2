(ns http-toggle.core
  "Shared framework-behavior testbed — a single HTTP-request button and
  a toggleable outcome dropdown that enumerates the failure categories
  of `:rf.http/managed` (plus the success path). One click +
  one dropdown selection drives the request through the configured
  outcome, and the runtime emits the corresponding :rf.http/* event(s)
  the consumer is watching for.

  Outcomes (per [spec/014 §Failure categories]):

    :success            → 2xx with decoded JSON; reply :status :ok
    :rf.http/http-4xx   → 4xx response; decode skipped; :body raw
    :rf.http/http-5xx   → 5xx response; decode skipped; :body raw
    :rf.http/timeout    → per-attempt timeout fired
    :rf.http/aborted    → abort via :request-id (the testbed's Cancel)
    :rf.http/transport  → network / DNS / connection-refused
    :rf.http/decode-failure → 2xx response whose body failed decode
    :http-toggle/cross-origin → :rf.http/transport carrying :cross-origin? true
                        (a Fetch rejection against another origin: a
                        network drop or a CORS refusal, which the
                        browser reports the same way)

  Routing strategy: the success path issues a REAL Fetch against
  `/api/<outcome>.json` (static asset shipped under the testbed dir).
  Every failure outcome goes out through `failure-backend`, a stub
  transport (Spec 014 §Testing) that answers the way a server or the
  network would for its category; the real `:rf.http/managed` then
  classifies it, emits its `:rf.http/<kind>` trace and delivers the reply.
  This keeps the testbed deterministic across CI environments while every
  failure travels the live failure path.

  Selecting an outcome installs the matching backend on the frame, and Go
  is a plain dispatch queued behind that selection, so a Go clicked any
  time after a selection issues the selected outcome through its backend,
  however fast the click follows."
  (:require [cljs.reader :as edn]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed-HTTP ships in day8/re-frame2-http. Requiring at
            ;; app boot triggers its load-time fx registrations
            ;; (`:rf.http/managed` / `:rf.http/managed-abort`); without it,
            ;; dispatching `:rf.http/managed` would fail with
            ;; :rf.error/no-such-fx.
            [re-frame.http.managed]
            ;; The failure outcomes go out through a stub transport (see
            ;; `failure-backend`). A testbed IS a test affordance, so a
            ;; test scope in its click handler is correct.
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.views]
            [re-frame.adapter.reagent :as rf.adapter.reagent])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------
;;
;; Minimal — :outcome carries the dropdown selection; :status is
;; :idle | :loading | :done | :error; :reply carries the last reply for
;; consumers to inspect via the DOM. A consumer's spec asserts on the
;; :rf.http/* trace stream first; the DOM mirror is a fall-back.

(rf/reg-event ::initialise
  (fn [_cofx _ev]
    {:db {:outcome :success
          :status  :idle
          :reply   nil}
     :fx [[::backend :success]]}))

(rf/reg-event ::set-outcome
  (fn [{:keys [db]} [_ outcome]]
    {:db (assoc db :outcome outcome)
     :fx [[::backend outcome]]}))

;; ----------------------------------------------------------------------------
;; The Go button — one event handler that branches on (:outcome db).
;; ----------------------------------------------------------------------------
;;
;; Go and the outcome select both dispatch onto the frame's queue, so Go
;; runs after every selection made before it, reads that outcome from
;; app-db, and issues it through the backend the selection installed.
;;
;; Reply addressing: the same handler-id receives the reply via the
;; unified `:reply-to [::go msg]` on every issued request (per Spec 014).
;; When the canonical envelope arrives appended as the last arg, the
;; handler files it into :reply; on initial dispatch (no reply), it issues
;; the configured request.
;;
;; The Cancel button is wired to abort whatever request was issued under
;; this request-id.

(def request-id ::in-flight)

;; ----------------------------------------------------------------------------
;; The failure backend
;; ----------------------------------------------------------------------------
;;
;; One route per failure outcome, each answering the way a server or the
;; network would for its category. The request runs through the real
;; `:rf.http/managed`, which classifies the answer, emits the
;; category-attributed `:rf.http/<kind>` trace and delivers the reply, so a
;; consumer's trace watcher sees exactly the live failure path.

(def ^:private held-ms
  "Longer than anyone waits: a route held this long is answered by the
   request's own timeout, or by Cancel."
  600000)

(def ^:private failure-backend
  (rf.http.stub-transport/route-transport
    {[:get "api/4xx"]       {:status      404
                             :status-text "Not Found"
                             :headers     {"content-type" "text/html"}
                             :body        "<html>not found</html>"}
     [:get "api/5xx"]       {:status      500
                             :status-text "Internal Server Error"
                             :headers     {"content-type" "text/html"}
                             :body        "<html>server error</html>"}
     ;; Held past the request's `:timeout-ms`.
     [:get "api/timeout"]   {:delay-ms held-ms}
     ;; Held until the Cancel button aborts it.
     [:get "api/slow"]      {:delay-ms held-ms}
     [:get "api/transport"] {:failure {:kind    :rf.http/transport
                                       :message "Network unreachable"
                                       :cause   "ECONNREFUSED"}}
     ;; A 2xx whose body is not JSON.
     [:get "api/decode"]    {:headers {"content-type" "application/json"}
                             :body    "<<not-json>>"}
     ;; The browser reports a CORS refusal and a network drop against another
     ;; origin as the same Fetch `TypeError`.
     [:get "https://other.example/api/cross-origin"]
     {:failure {:kind          :rf.http/transport
                :message       "Failed to fetch"
                :cause         "TypeError"
                :cross-origin? true}}}))

(rf/reg-fx ::backend
  {:doc       "Answer the frame's `:rf.http/managed` requests the way `outcome`
               needs: from `failure-backend` for a failure outcome, and from
               the network for `:success`."
   :platforms #{:client}}
  (fn [{:keys [frame]} outcome]
    (rf.http.stub-transport/install-transport!
      frame (when-not (= :success outcome) failure-backend))))

(rf/reg-event ::go
  (fn [{:keys [db]} [_ msg reply]]
    (cond
      ;; Reply branch — categorise and stash. `reply` is the canonical
      ;; envelope, delivered as this event's last arg via `:reply-to`;
      ;; :status :ok is success, anything else (:error/:cancelled) is a
      ;; failure/abort for this testbed's binary done/error mirror.
      (some-> reply :status some?)
      {:db (-> db
               (assoc :status (if (= :ok (:status reply)) :done :error))
               (assoc :reply  reply))}

      ;; Initial branch — fan out by selected outcome. Each branch
      ;; produces ONE `:rf.http/managed` request whose canonical envelope
      ;; the framework classifies; a failure outcome's URL picks its
      ;; `failure-backend` route. Every branch addresses its reply back to
      ;; THIS event with `:reply-to [::go msg]` (Spec 014 §Reply
      ;; addressing).
      :else
      (let [outcome (:outcome db)]
        {:db (-> db (assoc :status :loading :reply nil))
         :fx [[:rf.http/managed
               (case outcome
                 ;; HOT PATH — the live-Fetch site. This is the only
                 ;; outcome that actually crosses the wire; the response
                 ;; is the static asset `/api/success.json` shipped
                 ;; alongside the testbed.
                 :success
                 {:request    {:method :get :url "api/success.json"}
                  :decode     :json
                  :request-id request-id
                  :reply-to   [::go msg]}

                 :rf.http/http-4xx
                 {:request    {:method :get :url "api/4xx"}
                  :decode     :json
                  :request-id request-id
                  :reply-to   [::go msg]}

                 :rf.http/http-5xx
                 {:request    {:method :get :url "api/5xx"}
                  :decode     :json
                  :request-id request-id
                  :reply-to   [::go msg]}

                 :rf.http/timeout
                 {:request    {:method :get :url "api/timeout"}
                  :timeout-ms 1000
                  :request-id request-id
                  :reply-to   [::go msg]}

                 ;; Held until the user clicks Cancel, whose
                 ;; `:rf.http/managed-abort` aborts it for real.
                 :rf.http/aborted
                 {:request    {:method :get :url "api/slow"}
                  :request-id request-id
                  :reply-to   [::go msg]}

                 :rf.http/transport
                 {:request    {:method :get :url "api/transport"}
                  :request-id request-id
                  :reply-to   [::go msg]}

                 :rf.http/decode-failure
                 {:request    {:method :get :url "api/decode"}
                  :decode     :json
                  :request-id request-id
                  :reply-to   [::go msg]}

                 :http-toggle/cross-origin
                 {:request    {:method :get :url "https://other.example/api/cross-origin"}
                  :request-id request-id
                  :reply-to   [::go msg]})]]}))))

(rf/reg-event ::cancel
  (fn [_ctx _ev]
    {:fx [;; The live abort fx: it aborts whatever request is in flight
          ;; under `request-id`, and the framework delivers the
          ;; `:status :cancelled` reply.
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
   [:rf.http/aborted        ":rf.http/aborted (held — click Cancel)"]
   [:rf.http/transport      ":rf.http/transport (network)"]
   [:rf.http/decode-failure ":rf.http/decode-failure (bad JSON)"]
   [:http-toggle/cross-origin ":rf.http/transport (cross-origin)"]])

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
        [:span " · reply.status=" [:span {:data-testid "reply-status"} (pr-str (:status reply))]
         (when-let [k (get-in reply [:error :kind])]
           [:span " · failure.kind=" [:span {:data-testid "failure-kind"} (pr-str k)]])])]]))

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! rf.adapter.reagent/adapter)
  ;; Per EP-0002, the runtime never synthesises a frame from
  ;; absence — `:rf/default` is this testbed's app frame, registered
  ;; explicitly here (init! installs only the adapter). The boot dispatch
  ;; runs under the frame scope and the render is wrapped in a
  ;; `frame-provider` so in-tree dispatch/subscribe resolve to it.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [::initialise]))
  (rdc/render react-root [rf/frame-provider {:frame :rf/default} [root]]))
