(ns managed-http-counter.core
  "Managed-HTTP counter — a small app built on `:rf.http/managed`.

  You describe a request as data and the runtime owns its whole
  lifecycle: encode, send, decode, classify failures, retry, abort. It
  then dispatches the result back as an ordinary event. You never touch
  js/fetch. Each button issues a request and the reply lands back in
  app-db through default reply addressing, so \"send\" and \"receive\" are
  both just events hitting the same pure handler. See the HTTP guide:
  docs/resources/http.md.

  Buttons:
    +1             GET api/inc.json — a real success round-trip.
    Fail           GET api/does-not-exist — a real 404 (:rf.http/http-4xx).
    Retry-recover  drives the canned-success stub, which synthesises a
                   success reply with no real request.
    Start long     seeds a pending in-flight request that stays pending.
    Cancel         aborts that request by :request-id.

  +1 and Fail run a real Fetch: the request goes out, the body is decoded
  (only on 2xx), and the reply lands back in app-db. Retry-recover uses
  the canned-success stub so the app can show the success/retry contract
  without a stub HTTP server.

  Start long / Cancel show a real abort. This app serves only static
  assets, so a real GET resolves instantly and never stays observably
  in-flight. So \"Start long\" seeds a request-id-keyed handle directly
  into the framework in-flight registry — the same atom the live transport
  records into — giving a deterministically pending request. \"Cancel\"
  then fires the live `:rf.http/managed-abort` fx against that handle: the
  abort resolves it, fires its `:abort-fn`, and a `:rf.http/aborted` reply
  lands back at the issuing handler. Only the transport is stood in for;
  the registry entry, the abort fx, and the aborted classification are all
  real and visible in Xray and traces.

  This app is the runnable cross-substrate sanity check: the same fx, the
  same reply shape, end-to-end through Reagent and Fetch."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Managed HTTP ships in its own artefact, day8/re-frame2-http.
            ;; Requiring re-frame.http.managed once at boot registers
            ;; `:rf.http/managed` and family; without it, dispatching the
            ;; fx fails loud with a named "HTTP artefact missing" error.
            [re-frame.http.managed]
            ;; The canned-success stub the Retry-recover button uses
            ;; (`:rf.http/managed-canned-success`) registers here, not in
            ;; re-frame.http.managed.
            [re-frame.http.test-support]
            ;; The "Start long" button seeds a pending in-flight handle
            ;; directly into the registry. The write seam (`record-in-flight!`)
            ;; isn't on the public facade, so we reach the registry ns
            ;; directly — it's the same atom the live transport writes into
            ;; and the live `:rf.http/managed-abort` fx resolves.
            [re-frame.http.registry :as http-registry]
            ;; Call-site verb helpers (rf.http/get / post / put / delete /
            ;; patch / head / options) that build the canonical
            ;; [:rf.http/managed args-map] vector.
            [re-frame.http :as rf.http]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SHAPE
;; ============================================================================
;;
;; {:counter/count    <int>                     ;; current counter value
;;  :counter/status   <:idle|:loading|:error>   ;; the request lifecycle
;;  :counter/error    <failure-map-or-nil>}
;;
;; :status makes the in-flight lifecycle visible: :loading while the
;; runtime works a request, :idle (or :error) once the reply lands.
;;
;; Every app-db slot, sub-id, and event carries a :counter/ feature
;; prefix, so this app's keys never collide with another feature's.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _ev]
    {:db {:counter/count  0
     :counter/status :idle
     :counter/error  nil}}))

;; ============================================================================
;; +1  —  real round-trip via Fetch
;; ============================================================================
;;
;; Read this path first. One pure handler plays two roles. The :else
;; branch describes a request (it returns an :rf.http/managed effect).
;; The runtime sends the GET to api/inc.json, decodes the `{"delta": 1}`
;; body, and re-dispatches [:counter/+1 {:rf/reply ...}] back to this same
;; handler, which now takes the :success branch and applies the increment.
;; The asynchrony lives in the runtime; the handler stays pure.

(rf/reg-event :counter/+1
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

      ;; Initial branch — issue the request. `rf.http/get` builds the
      ;; same `[:rf.http/managed args-map]` vector a hand-written
      ;; `:method :get` entry would, in one line.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/inc.json" {:decode :json})]})))

;; ============================================================================
;; Fail  —  real 404 from the http-server
;; ============================================================================
;;
;; Same shape as +1, but the failure side of the reply. Branch on the
;; reply's :kind; on :failure, record the failure map whole — its own
;; :kind is the :rf.http/* category the view surfaces.

(rf/reg-event :counter/fail
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc db :counter/status :error :counter/error (:failure (:rf/reply msg)))}

      (some-> msg :rf/reply :kind (= :success))
      ;; Should not happen — the URL is intentionally 404.
      {:db (assoc db :counter/status :idle :counter/error nil)}

      ;; Same `rf.http/get` helper as above. Status is classified before
      ;; the body is decoded, so a 404 with an HTML or plain-text body is
      ;; :rf.http/http-4xx (raw body at :body), never :rf.http/decode-failure
      ;; — even with `:decode :json`. Leaving :decode at its default `:auto`
      ;; here shows the common case: a JSON endpoint that 404s with a
      ;; load-balancer HTML page. See docs/resources/http.md.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/does-not-exist")]})))

;; ============================================================================
;; Retry-recover  —  canned-stub at app level
;; ============================================================================
;;
;; Shows the recovery-after-retry path without a flaky endpoint. The
;; handler issues :rf.http/managed-canned-success, which synthesises a
;; {:kind :success :value {:delta 5}} reply — the exact shape the live fx
;; lands if its retry policy hits a real endpoint that 503s once then
;; 200s. The stub lets you exercise the contract without owning a server,
;; and the handler reads the same either way.

(rf/reg-event :counter/retry-recover
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :success))
      {:db (-> db
               (update :counter/count + (or (:delta (:value (:rf/reply msg))) 0))
               (assoc :counter/status :idle :counter/error nil))}

      :else
      {:db (assoc db :counter/status :loading)
       ;; The canned-success stub synthesises the reply directly. The
       ;; request and decode are declared so the call reads like a live
       ;; retry-recover; the stub short-circuits the network.
       :fx [[:rf.http/managed-canned-success
             {:request {:method :get :url "api/flaky"}
              :decode  :json
              :value   {:delta 5}}]]})))

;; ============================================================================
;; Start long / Cancel  —  a REAL abort of a REAL in-flight request
;; ============================================================================
;;
;; The abort contract: an in-flight `:rf.http/managed` request is cancelled
;; by `:request-id`. `:rf.http/managed-abort` resolves the handle in the
;; framework in-flight registry, fires its `:abort-fn`, and a
;; `:rf.http/aborted` reply lands back at the issuing handler. See the
;; abort section of docs/resources/http.md.
;;
;; This app serves only static assets, so a real GET resolves instantly
;; and nothing stays observably in-flight to cancel. So "Start long" seeds
;; a request-id-keyed handle directly into the registry (the same atom the
;; live transport writes into), giving a deterministically pending request,
;; and "Cancel" fires the live `:rf.http/managed-abort` fx against that
;; handle. Only the transport is stood in for; the registry entry, the
;; abort fx, and the `:rf.http/aborted` classification are all real and
;; visible in Xray and traces.

(def long-request-id :counter/long)

;; A placeholder URL stamped on the seeded in-flight handle, so it reads
;; naturally in the abort trace and any registry read-out. No Fetch is
;; issued — the slot is seeded directly so the pending state is deterministic.
(def long-pending-url "api/long")

(rf/reg-fx :counter/seed-long-request
  {:doc       "Demo-only fx: record a request-id-keyed in-flight handle in
               the framework registry whose :abort-fn clears the slot and
               dispatches the :rf.http/aborted reply back to
               :counter/start-long — so the live :rf.http/managed-abort fx
               resolves it end-to-end."
   :platforms #{:client}}
  (fn fx-seed-long-request [frame-ctx {:keys [request-id]}]
    ;; The abort-fn fires later, when the live :rf.http/managed-abort fx
    ;; resolves this handle, so it has no frame of its own — a bare
    ;; `rf/dispatch` inside it would raise :rf.error/no-frame-context. A
    ;; frame's identity is carried, not found, so we capture the fx
    ;; context's frame here and pass it explicitly on the deferred
    ;; dispatch. See docs/guide/glossary.md#frame-identity-is-carried-not-found.
    (let [frame (:frame frame-ctx)]
      (http-registry/record-in-flight!
        request-id nil
        {:url      long-pending-url
         ;; The abort-fn is the in-flight request's cancellation hook. The
         ;; live :rf.http/managed-abort handler resolves this handle by
         ;; request-id and calls this fn with the abort `reason` (`:user`
         ;; here). We clear the registry slot and dispatch the
         ;; :rf.http/aborted reply — the exact shape the live transport's
         ;; abort path emits — back through default reply addressing to
         ;; :counter/start-long.
         :abort-fn (fn [reason]
                     (http-registry/clear-in-flight! request-id)
                     (rf/dispatch [:counter/start-long
                                   {:rf/reply {:kind    :failure
                                               :failure {:kind       :rf.http/aborted
                                                         :request-id request-id
                                                         :reason     reason}}}]
                                  {:frame frame}))}))
    nil))

(rf/reg-event :counter/start-long
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — the abort-fn dispatches :rf.http/aborted on
      ;; cancellation, which lands here via default reply addressing.
      ;; Record the aborted classification and return the UI to :idle.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc db
                  :counter/status :idle
                  :counter/error  (:failure (:rf/reply msg)))}

      (some-> msg :rf/reply :kind (= :success))
      {:db (assoc db :counter/status :idle :counter/error nil)}

      ;; Initial branch — seed a genuine pending in-flight request that
      ;; the Cancel button aborts for real. The :loading UI state persists
      ;; until the abort resolves the slot.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [[:counter/seed-long-request {:request-id long-request-id}]]})))

(rf/reg-event :counter/cancel
  (fn [{:keys [db]} _]
    {:db db
     ;; Abort by request-id via the LIVE :rf.http/managed-abort fx. It
     ;; resolves the seeded handle in the in-flight registry and fires its
     ;; :abort-fn, which clears the slot and dispatches the
     ;; :rf.http/aborted reply back to :counter/start-long (handled above).
     ;; A no-op when nothing is in flight (the registry lookup misses).
     :fx [[:rf.http/managed-abort long-request-id]]}))

;; ============================================================================
;; SUBS
;; ============================================================================
;;
;; Three direct reads of the slice — pure derivations the framework caches
;; and recomputes only when their input moves. The boring kind, on purpose.

(rf/reg-sub :counter/count  (fn [db _] (:counter/count  db)))
(rf/reg-sub :counter/status (fn [db _] (:counter/status db)))
(rf/reg-sub :counter/error  (fn [db _] (:counter/error  db)))

;; ============================================================================
;; VIEWS
;; ============================================================================
;;
;; Pure render from subscription values to hiccup; one dispatch per button.
;; No business logic here — a view reads derived state and dispatches.

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

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

;; The app establishes its frame explicitly, in one spot: the render
;; root's `frame-provider {:id app-frame}`. On first mount it creates the
;; app frame, applies its config, and runs `:initial-events` once (the
;; `[:counter/initialise]` seed); on hot reload it reuses that frame and
;; skips the seed. Every in-tree `dispatch`/`subscribe` then resolves to
;; the app frame.
;;
;; `:rf/default` is just the id this app chose — an ordinary frame id with
;; no framework privilege, so the runtime won't infer it for you. Matches
;; the canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the Reagent adapter. Pass the adapter spec map
  ;; (each adapter ns exports an `adapter` var) directly. It installs the
  ;; adapter only; the frame-provider below creates the frame.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))
