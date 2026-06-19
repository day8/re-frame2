(ns managed-http-counter.core
  "Managed-HTTP counter — Spec 014 §`:rf.http/managed` example app.

  A counter where each button issues a managed HTTP request and the
  reply lands back in app-db via the default reply-addressing path.

  Buttons:
    +1                — GET /api/inc.json (static asset; success path)
    Fail              — GET /api/does-not-exist (404 → :rf.http/http-4xx)
    Retry-recover     — GET /api/retry-recover (canned-stub at app level
                                                synthesises retry success)
    Start long        — seeds a GENUINE in-flight request handle in the
                        framework registry (a request that stays pending,
                        deterministically — no real long-running endpoint)
    Cancel            — :rf.http/managed-abort by :request-id, which
                        resolves the seeded handle through the LIVE abort
                        path → real :rf.http/aborted reply

  The +1 and Fail buttons exercise a REAL round-trip: Fetch hits the
  static file (or 404s), the response body is decoded (only on 2xx), and
  the reply lands back in app-db. The Retry-recover button exercises the
  canned-stub seam, which lets the app demonstrate the success/retry
  contract without needing a stub HTTP server.

  Start long / Cancel exercise the REAL abort contract: \"Start long\"
  records a genuine request-id-keyed handle in the framework in-flight
  registry (the same atom the live transport's `run-attempt!` records
  into), whose `:abort-fn` dispatches the canonical `:rf.http/aborted`
  reply and clears the registry slot. \"Cancel\" fires the LIVE
  `:rf.http/managed-abort` fx, which resolves that handle by `:request-id`
  and fires its `:abort-fn` — so the cancel path exercises the actual
  abort semantics, in-flight registry cleanup, and aborted classification
  end-to-end. Seeding a deterministic pending handle (rather than a real
  Fetch against a static dev-http server, which resolves instantly leaving
  nothing observably in-flight) is the proven testbed pattern — see
  `tools/xray/testbeds/managed_http/core.cljs`.

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
            ;; The Retry-recover button drives :rf.http/managed-canned-success
            ;; (the canned-success stub). The canned-stub fx ids register from
            ;; re-frame.http-test-support, not re-frame.http-managed.
            [re-frame.http-test-support]
            ;; The "Start long" demo seeds a genuine in-flight request
            ;; handle directly into the framework registry so the slot
            ;; persists deterministically (a real Fetch against a static
            ;; dev-http server resolves instantly, leaving nothing
            ;; observably in-flight to cancel). The index-write seam
            ;; (`record-in-flight!`) is not re-exported from
            ;; re-frame.http-managed (only the read-side snapshots +
            ;; clear/abort are), so we reach the registry ns directly —
            ;; the same atom the live transport's `run-attempt!` records
            ;; into, which the live `:rf.http/managed-abort` fx resolves.
            [re-frame.http-registry :as http-registry]
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

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _ev]
    {:db {:counter/count  0
     :counter/status :idle
     :counter/error  nil}}))

;; -- +1 (real round-trip via Fetch) ------------------------------------------
;;
;; The +1 button fires :counter/+1, which issues a real GET to
;; /api/inc.json. The response body — `{"delta": 1}` — is decoded
;; as JSON; the default reply-addressing path re-dispatches
;; [:counter/+1 (assoc msg :rf/reply ...)] to the originating handler,
;; which branches on (:rf/reply msg) to apply the increment.

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

      ;; Initial branch — issue the request. The `rf.http/get`
      ;; helper synthesises the same `[:rf.http/managed args-map]`
      ;; envelope a hand-written `:method :get` entry would; the
      ;; call-site reads as one line of intent.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/inc.json" {:decode :json})]})))

;; -- Fail (real 404 from http-server) ----------------------------------------

(rf/reg-event :counter/fail
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

(rf/reg-event :counter/retry-recover
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

;; -- Start long / Cancel: REAL abort of a REAL in-flight request -------------
;;
;; The Spec 014 contract is that an in-flight `:rf.http/managed` request
;; can be cancelled by `:request-id`: `:rf.http/managed-abort` resolves the
;; handle in the framework in-flight registry and fires its `:abort-fn`,
;; which produces a `:rf.http/aborted` reply and clears the registry slot.
;;
;; To demonstrate THAT contract — not a look-alike — "Start long" records
;; a genuine request-id-keyed handle in the framework registry (via
;; `http-registry/record-in-flight!`, the same atom the live transport's
;; `run-attempt!` records into). The handle's `:abort-fn` dispatches the
;; canonical `:rf.http/aborted` reply via default reply addressing back to
;; `:counter/start-long`, then clears the slot. "Cancel" fires the LIVE
;; `:rf.http/managed-abort` fx, which looks up THAT handle by request-id
;; and fires its `:abort-fn` — so the cancel path exercises the real abort
;; semantics, in-flight registry cleanup, and `:rf.http/aborted`
;; classification end-to-end (visible in Xray / traces).
;;
;; Why seed a handle rather than fire a real Fetch: this example serves
;; only static assets, so a real `:rf.http/managed` GET resolves instantly
;; (leaving nothing observably in-flight to cancel) or has no slow endpoint
;; to hang on. Seeding a deterministic pending handle is the proven testbed
;; pattern — see `tools/xray/testbeds/managed_http/core.cljs`. The seeded
;; handle is a REAL registry entry resolved by the REAL abort fx; only the
;; transport (which would otherwise need a backend) is stood in for.

(def long-request-id :counter/long)

;; A placeholder URL stamped on the seeded in-flight handle (for the abort
;; trace + any registry read-out). Same-origin so it reads naturally; no
;; live Fetch is issued — the slot is seeded directly so the pending state
;; is deterministic.
(def long-pending-url "api/long")

(rf/reg-fx :counter/seed-long-request
  {:doc       "Demo-only fx: record a genuine request-id-keyed in-flight
               handle in the framework registry whose :abort-fn dispatches
               the canonical :rf.http/aborted reply (addressed back to
               :counter/start-long) and clears the slot — so the LIVE
               :rf.http/managed-abort fx resolves it end-to-end. Mirrors
               the proven seed-in-flight pattern in
               tools/xray/testbeds/managed_http/core.cljs."
   :platforms #{:client}}
  (fn fx-seed-long-request [frame-ctx {:keys [request-id]}]
    ;; EP-0002 (rf2-9o48ih): the abort-fn fires later (when the LIVE
    ;; :rf.http/managed-abort fx resolves this handle), so it carries no
    ;; ambient frame of its own — a bare `rf/dispatch` inside it would raise
    ;; :rf.error/no-frame-context. Capture the fx-context's frame and pass it
    ;; explicitly on the deferred dispatch (Spec 002 §async fx capture the
    ;; frame — the same `{:frame (:frame frame-ctx)}` pattern the boot example
    ;; uses for its deferred reply).
    (let [frame (:frame frame-ctx)]
      (http-registry/record-in-flight!
        request-id nil
        {:url      long-pending-url
         ;; The abort-fn IS the in-flight request's cancellation hook. The
         ;; live :rf.http/managed-abort handler resolves this handle by
         ;; request-id and calls this fn with the abort `reason` (`:user`
         ;; here). We clear the registry slot and dispatch the canonical
         ;; :rf.http/aborted reply — the exact shape the live transport's
         ;; abort path emits (Spec 014 §Failure categories) — back through
         ;; default reply addressing to :counter/start-long.
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

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:counter/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [counter-app]])))
