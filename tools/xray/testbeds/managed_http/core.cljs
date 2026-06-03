(ns managed-http.core
  "MANAGED-HTTP testbed (rf2-6nolv) — the PILOT consumer of the shared
  queued-step RUNNER (rf2-8pbjr; `runner.core`).

  An operator visually inspects how the Xray panels (Epoch · Trace ·
  App-db · Machine Inspector) render the managed-HTTP lifecycle by
  pressing ONE button (`▶ Run series`) and watching each interesting
  step play out, with per-step commentary on what to look for. Title:
  `Xray Testbed: Managed HTTP`.

  ## What it exercises (Spec 014 — `:rf.http/managed` and family)

  The step vector (`steps`, below) is CODE DATA — a `def`ed vector of
  step maps, each `{:event … :watch … :settle-ms …}`. It walks the
  managed-HTTP lifecycle:

    1. Fire a request           — in-flight render (`:status :loading`,
                                  the in-flight registry gains a slot).
    2. Success response         — 2xx decoded; reply `:kind :success`.
    3. Error response (4xx)     — status 404; decode skipped; reply
                                  `:failure :kind :rf.http/http-4xx`.
    4. Error response (5xx)     — status 500; the `:rf.http/http-5xx`
                                  error trace.
    5. Abort in-flight          — fire a deferred request, then abort it
                                  before the reply lands
                                  (`:rf.http/aborted`).
    6. Concurrent by same actor — TWO requests issued under the SAME
                                  actor-id; the actor-in-flight index
                                  carries both (Spec 014 §Abort on actor
                                  destroy, rf2-wvkn).
    7. Outlives a frame teardown — an in-flight request whose actor is
                                  destroyed mid-flight is aborted via
                                  `abort-on-actor-destroy`
                                  (`:rf.http/aborted-on-actor-destroy`).

  ## Runner state = LOCAL ATOM (rf2-8pbjr contract)

  The runner cursor/phase lives in `runner-state` — a LOCAL Reagent atom
  in THIS ns. It is NEVER written to the inspected app-db (that would
  pollute the App-db panel under inspection) and is NOT a second frame
  (only the inspected app frame, `:rf/default`, is Xray-relevant). After
  each auto-advance dispatch the runner focuses the latest `:rf/default`
  epoch so the operator sees the just-dispatched render.

  ## Real registry vs canned replies (deterministic, clean)

  Following the established `testbeds/http_toggle` pattern: the HOT path
  (success) rides a REAL Fetch against the static `api/ok.json` shipped
  beside the testbed; the 4xx / 5xx error paths use the framework-shipped
  canned-stub fx (`:rf.http/managed-canned-failure`) wrapped to replay
  the live error trace, so the lifecycle is deterministic across CI
  environments — no flaky outbound network. The in-flight + actor-in-
  flight registries are seeded with genuine handles in the framework
  registry (the same atoms the real transport's `run-attempt!` records
  into) so the pending slots render DETERMINISTICALLY — a real Fetch
  against a dev-http static server resolves instantly, leaving nothing
  observably in-flight across a settle window. The abort path resolves a
  seeded handle through the LIVE `:rf.http/managed-abort` fx. A TEST
  surface — no deliberate bugs, no teaching layers
  (feedback_testbeds_are_test_surfaces).

  ## Bundle isolation

  Lives under `tools/xray/testbeds/`; requires the managed-HTTP artefact
  (`re-frame.http-managed`) + its test-support (`re-frame.http-test-
  support` — a testbed IS a test affordance) + the shared `runner.core`.
  Nothing under `implementation/` requires this."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]
            ;; Managed-HTTP ships in day8/re-frame2-http. Requiring at app
            ;; boot triggers its load-time fx registrations
            ;; (`:rf.http/managed` / `:rf.http/managed-abort`).
            [re-frame.http-managed :as http-managed]
            ;; The actor-in-flight INDEX-WRITE seam (`record-in-flight!`)
            ;; is not re-exported from re-frame.http-managed (only the
            ;; read-side snapshots + clear/abort are). The testbed reaches
            ;; the registry ns directly to seed the actor-in-flight index
            ;; for the concurrent-by-actor + outlives-teardown steps — the
            ;; same atom the real transport's run-attempt! records into.
            [re-frame.http-registry :as http-registry]
            ;; The canned-stub fx ids (`:rf.http/managed-canned-failure`)
            ;; register from re-frame.http-test-support per rf2-cdmle. A
            ;; testbed IS a test affordance, so requiring it is correct.
            [re-frame.http-test-support]
            [re-frame.http :as rf.http]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.interop :as interop]
            [day8.re-frame2-xray.config :as xray-config]
            [re-frame.testbed.config :as testbed-config]
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; The inspected app frame (rf2-8pbjr — only the app frame is Xray-relevant)
;; ============================================================================

(def host-frame :rf/default)

;; ============================================================================
;; APP-DB
;; ============================================================================
;;
;; Minimal — :status is :idle | :loading | :done | :error; :reply carries
;; the last reply for the operator to read in the DOM mirror; :log is a
;; short narrative of the last lifecycle events. The Xray Epoch / Trace
;; panels are the primary surface; the DOM strip is a standalone fall-back.

(rf/reg-event-db ::initialise
  {:doc "Seed app-db: idle, no reply, empty log."}
  (fn [_db _ev]
    {:status :idle :reply nil :log []}))

(defn- log-line [db line]
  (update db :log (fn [xs] (vec (take-last 8 (conj (or xs []) line))))))

;; ----------------------------------------------------------------------------
;; Reply addressing — one handler per logical request receives the reply.
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::reply
  {:doc "Reply sink for the success / failure paths. Receives the reply
         payload DIRECTLY (explicit `:on-success` / `:on-failure`
         addressing per Spec 014 §Reply addressing appends the
         `{:kind …}` payload as the last arg — not wrapped in
         `:rf/reply`). Files it and narrates the lifecycle outcome
         (`:done` on success, `:error` + the failure :kind otherwise)."}
  (fn [db [_ reply]]
    (let [ok? (= :success (:kind reply))]
      (-> db
          (assoc :status (if ok? :done :error))
          (assoc :reply reply)
          (log-line (if ok?
                      (str "✓ success: " (pr-str (:value reply)))
                      (str "✗ failure: " (pr-str (get-in reply [:failure :kind])))))))))

;; ============================================================================
;; REQUEST IDS / URLS
;; ============================================================================

(def ok-request-id     ::ok)
(def in-flight-id      ::in-flight)
(def abortable-id      ::abortable)
(def actor-a          :managed-http/actor-a)
(def actor-b          :managed-http/actor-b)

;; A placeholder URL stamped on the seeded in-flight handles (for the
;; registry strip + abort trace). Same-origin so it reads naturally; no
;; live Fetch is issued for the in-flight / actor steps — those seed the
;; registry directly so the pending slots are DETERMINISTIC (a real Fetch
;; against a dev-http static server resolves instantly, leaving nothing
;; observably in-flight across the settle window).
(def pending-url "api/pending")

;; ============================================================================
;; CANNED-FAILURE-WITH-TRACE — the http_toggle pattern (rf2-3g16l)
;; ============================================================================
;;
;; The framework `:rf.http/managed-canned-failure` synthesises a failure
;; reply but does NOT emit the category-attributed `:rf.http/<kind>`
;; error trace the live failure path emits. The Xray Trace / Epoch panels
;; render that trace, so this testbed-local wrapper replays the live
;; path's `trace/emit-error!` before delegating to the canned stub — so
;; the panels show the same `:operation :rf.http/<kind>` row a live
;; failure would. Honours the canned stub's optional `:after-ms`
;; deferral (rf2-j1mo4) so a deferred failure is observably in-flight.

(rf/reg-fx :managed-http/canned-failure-with-trace
  {:doc       "Testbed-only wrapper around :rf.http/managed-canned-failure.
               Emits the category-attributed :rf.http/<kind> error trace
               (matching the live failure path's finalise-failure! emit),
               then delegates to the framework canned stub for the reply
               synthesis. See rf2-3g16l / testbeds/http_toggle."
   :platforms #{:client}}
  (fn fx-canned-failure-with-trace [frame-ctx args-map]
    (let [kind (or (:kind args-map) :rf.http/transport)
          tags (or (:tags args-map) {})
          url  (get-in args-map [:request :url])]
      (trace/emit-error! kind
                         (assoc tags
                                :kind       kind
                                :request-id (:request-id args-map)
                                :url        url
                                :recovery   :no-recovery))
      ((registrar/handler :fx :rf.http/managed-canned-failure)
       frame-ctx args-map))))

;; ============================================================================
;; LIFECYCLE EVENTS — one per interesting step
;; ============================================================================

;; (1) Fire a request that stays IN-FLIGHT across the settle window.
;; Seeds a genuine request-id-keyed handle in the framework registry —
;; deterministic, so the in-flight strip shows a pending slot for the
;; whole settle (a real Fetch against a dev-http static server resolves
;; instantly, leaving nothing observably in-flight). The handle's
;; abort-fn dispatches the aborted reply, so the later abort step
;; resolves it through the LIVE :rf.http/managed-abort path.
(rf/reg-fx :managed-http/seed-in-flight
  {:doc       "Testbed-only fx: seed a request-id-keyed in-flight handle
               in the framework registry whose abort-fn dispatches a
               :rf.http/aborted reply + clears the slot — so the LIVE
               :rf.http/managed-abort fx resolves it end-to-end."
   :platforms #{:client}}
  (fn fx-seed-in-flight [_frame-ctx {:keys [request-id]}]
    (http-registry/record-in-flight!
      request-id nil
      {:url      pending-url
       :abort-fn (fn [reason]
                   (http-registry/clear-in-flight! request-id)
                   (rf/dispatch [::reply {:kind    :failure
                                          :failure {:kind   :rf.http/aborted
                                                    :reason reason}}]))})
    nil))

(rf/reg-event-fx ::fire-in-flight
  {:doc "Seed an in-flight request that stays pending across the settle
         window (deterministic). Populates the in-flight registry;
         status → :loading."}
  (fn [{:keys [db]} _ev]
    {:db (-> db (assoc :status :loading :reply nil) (log-line "→ fired (in-flight)"))
     :fx [[:managed-http/seed-in-flight {:request-id in-flight-id}]]}))

;; (2) Success — a real Fetch against the static asset api/ok.json.
(rf/reg-event-fx ::success
  {:doc "Live GET against the static api/ok.json. 2xx → decoded JSON →
         reply :kind :success."}
  (fn [{:keys [db]} _ev]
    {:db (-> db (assoc :status :loading :reply nil) (log-line "→ GET api/ok.json"))
     :fx [(rf.http/get "api/ok.json"
                       {:decode     :json
                        :request-id ok-request-id
                        :on-success [::reply]
                        :on-failure [::reply]})]}))

;; (3) Error response — 4xx. Canned-failure-with-trace synthesises the
;; same reply envelope + error trace a live 404 would.
(rf/reg-event-fx ::error-4xx
  {:doc "Synthesised :rf.http/http-4xx (404, raw body) via the canned-
         failure-with-trace wrapper. Same reply envelope as a live 4xx."}
  (fn [{:keys [db]} _ev]
    {:db (-> db (assoc :status :loading :reply nil) (log-line "→ request (will 404)"))
     :fx [[:managed-http/canned-failure-with-trace
           {:request    {:method :get :url "api/items/missing"}
            :request-id ok-request-id
            :on-failure [::reply]
            :kind       :rf.http/http-4xx
            :tags       {:status 404 :status-text "Not Found"
                         :body   "<html>not found</html>" :headers {}}}]]}))

;; (4) Error response — 5xx.
(rf/reg-event-fx ::error-5xx
  {:doc "Synthesised :rf.http/http-5xx (500, raw body)."}
  (fn [{:keys [db]} _ev]
    {:db (-> db (assoc :status :loading :reply nil) (log-line "→ request (will 500)"))
     :fx [[:managed-http/canned-failure-with-trace
           {:request    {:method :get :url "api/items"}
            :request-id ok-request-id
            :on-failure [::reply]
            :kind       :rf.http/http-5xx
            :tags       {:status 500 :status-text "Internal Server Error"
                         :body   "<html>server error</html>" :headers {}}}]]}))

;; (5a) Abort — seed a fresh in-flight request that stays pending so the
;; NEXT step can abort it before any reply lands. Self-contained (does
;; not depend on step 1's slot): a distinct :request-id whose abort-fn
;; dispatches the :rf.http/aborted reply.
(rf/reg-event-fx ::fire-abortable
  {:doc "Seed a fresh abortable in-flight request (status :loading) so the
         next step aborts it while still pending."}
  (fn [{:keys [db]} _ev]
    {:db (-> db (assoc :status :loading :reply nil) (log-line "→ fired (abortable)"))
     :fx [[:managed-http/seed-in-flight {:request-id abortable-id}]]}))

;; (5b) Abort the in-flight request via the LIVE :rf.http/managed-abort
;; fx — it resolves the seeded handle and fires its abort-fn, which
;; dispatches the :rf.http/aborted reply and clears the registry slot.
(rf/reg-event-fx ::abort
  {:doc "Abort the in-flight request by :request-id via the live
         :rf.http/managed-abort fx. The handle's abort-fn dispatches the
         :rf.http/aborted reply and clears the registry slot."}
  (fn [{:keys [db]} _ev]
    {:db (log-line db "→ abort")
     :fx [[:rf.http/managed-abort abortable-id]]}))

;; (6) Concurrent requests by the SAME actor. Two real :rf.http/managed
;; requests issued under the same actor-id populate the actor-in-flight
;; index with TWO entries (Spec 014 §Abort on actor destroy). They never
;; resolve within the settle window; the reset cleans up.
(rf/reg-fx :managed-http/issue-as-actor
  {:doc       "Testbed-only fx: issue a real :rf.http/managed request under a
               supplied actor-id so the actor-in-flight registry index is
               populated, exercising the per-actor in-flight surface
               (rf2-wvkn) without spawning a real machine actor."
   :platforms #{:client}}
  (fn fx-issue-as-actor [_frame-ctx {:keys [actor-id request-id]}]
    (http-registry/record-in-flight!
      request-id actor-id
      {:abort-fn (fn [_reason] nil)
       :url      pending-url})
    nil))

(rf/reg-event-fx ::concurrent-by-actor
  {:doc "Issue TWO requests under the SAME actor-id so the actor-in-flight
         index carries both (Spec 014 §Abort on actor destroy, rf2-wvkn)."}
  (fn [{:keys [db]} _ev]
    {:db (log-line db (str "→ two requests as " (pr-str actor-a)))
     :fx [[:managed-http/issue-as-actor {:actor-id actor-a :request-id ::actor-a-1}]
          [:managed-http/issue-as-actor {:actor-id actor-a :request-id ::actor-a-2}]
          [:managed-http/issue-as-actor {:actor-id actor-b :request-id ::actor-b-1}]]}))

;; (7) A request that OUTLIVES a frame/actor teardown. The actor's
;; in-flight requests are aborted via abort-on-actor-destroy — the same
;; path a :rf.machine/destroy cascade / frame teardown drives.
(rf/reg-fx :managed-http/destroy-actor
  {:doc       "Testbed-only fx: drive the framework abort-on-actor-destroy
               for the supplied actor-id — the SAME path a state-machine
               actor destroy / frame teardown takes (Spec 014 §Abort on
               actor destroy)."
   :platforms #{:client}}
  (fn fx-destroy-actor [_frame-ctx {:keys [actor-id]}]
    (http-managed/abort-on-actor-destroy actor-id)
    nil))

(rf/reg-event-fx ::actor-teardown
  {:doc "Tear down actor-a: its in-flight requests outlive the actor and
         are aborted via abort-on-actor-destroy (:rf.http/aborted-on-
         actor-destroy). actor-b's slot is untouched."}
  (fn [{:keys [db]} _ev]
    {:db (log-line db (str "→ destroy " (pr-str actor-a) " (outliving requests aborted)"))
     :fx [[:managed-http/destroy-actor {:actor-id actor-a}]]}))

;; ----------------------------------------------------------------------------
;; RESET — clean the registry + re-seed app-db. Button 0 of the deck.
;; ----------------------------------------------------------------------------

(rf/reg-fx :managed-http/clear-registry
  {:doc       "Testbed-only fx: drop both in-flight registries (request-id
               and actor-id keyed) so a fresh run starts clean."
   :platforms #{:client}}
  (fn fx-clear-registry [_frame-ctx _]
    (http-managed/clear-all-in-flight!)
    nil))

(rf/reg-event-fx ::reset
  {:doc "Re-seed app-db (idle, no reply, empty log) and clear the in-flight
         registries. Start clean."}
  (fn [_ctx _ev]
    {:db {:status :idle :reply nil :log []}
     :fx [[:managed-http/clear-registry]]}))

;; ============================================================================
;; SUBSCRIPTIONS — live read-out (the standalone-legible status strip)
;; ============================================================================

(rf/reg-sub ::status (fn [db _] (:status db)))
(rf/reg-sub ::reply  (fn [db _] (:reply db)))
(rf/reg-sub ::log    (fn [db _] (:log db)))

;; ============================================================================
;; THE STEP VECTOR — code data (rf2-8pbjr: the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :settle-ms N
;;             :label "<short row label>"}. The runner renders :watch per
;; STEP (per-occurrence narration, NOT a handler :doc), dispatches :event,
;; focuses the latest :rf/default epoch, then waits :settle-ms before
;; advancing. The fire-abortable step (5) seeds a pending request; the
;; abort step (6) resolves it through the live :rf.http/managed-abort fx.

(def steps
  [{:label     "Fire request (in-flight)"
    :event     [::fire-in-flight]
    :watch     "App-db diff: :status flips to :loading. In-flight registry strip: a pending request-id slot appears (the request is still in flight). Epoch: the fire cascade with NO reply child yet."
    :settle-ms 700}
   {:label     "Success response"
    :event     [::success]
    :watch     "Epoch: a two-event cascade — :rf.http/managed dispatch → reply lands at ::reply. App-db diff: :status :done, :reply :kind :success, :value decoded from api/ok.json."
    :settle-ms 700}
   {:label     "Error response (4xx)"
    :event     [::error-4xx]
    :watch     "Trace: an :operation :rf.http/http-4xx error row (:op-type :error, pink-wash on the event row). App-db: :status :error, reply :failure :kind :rf.http/http-4xx, :status 404."
    :settle-ms 600}
   {:label     "Error response (5xx)"
    :event     [::error-5xx]
    :watch     "Trace: an :operation :rf.http/http-5xx error row. App-db diff: reply :failure :kind :rf.http/http-5xx, :status 500. The Epoch shows the failure cascade attribution."
    :settle-ms 600}
   {:label     "Abort: fire an abortable request"
    :event     [::fire-abortable]
    :watch     ":status :loading; the in-flight registry strip gains another pending slot. The request stays in flight — the NEXT step aborts it before any reply lands."
    :settle-ms 500}
   {:label     "Abort the in-flight request"
    :event     [::abort]
    :watch     "Trace/Epoch: the live :rf.http/managed-abort fx resolves the handle → its abort-fn dispatches the :rf.http/aborted reply + clears the slot. App-db: :status :error, reply :kind :failure (:rf.http/aborted). In-flight strip: the abortable slot is gone."
    :settle-ms 700}
   {:label     "Concurrent requests by same actor"
    :event     [::concurrent-by-actor]
    :watch     "Actor-in-flight strip: actor-a now carries TWO pending entries, actor-b ONE (Spec 014 §Abort on actor destroy). Epoch: the fan-out cascade of three issue fxs."
    :settle-ms 700}
   {:label     "Request outlives a frame teardown"
    :event     [::actor-teardown]
    :watch     "Trace: two :rf.http/aborted-on-actor-destroy rows for actor-a's outliving requests. Actor-in-flight strip: actor-a's slot is GONE; actor-b's pending entry remains."
    :settle-ms 700}])

;; ============================================================================
;; RUNNER STATE — LOCAL ATOM (rf2-8pbjr: not app-db, not a 2nd frame)
;; ============================================================================

(defonce runner-state (r/atom (runner/initial-state)))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view in-flight-strip
  "A live read-out of the managed-HTTP in-flight registries (request-id-
  keyed + actor-id-keyed). Pure snapshot reads of the framework atoms —
  the Xray panels are the primary surface; this keeps the deck legible
  standalone. Re-derefs the atoms on every render via the :log sub —
  every lifecycle event appends a log line, so the strip re-renders and
  re-reads the registry even when :status is unchanged (the
  concurrent-by-actor step only touches :log)."
  []
  (let [_log      @(subscribe [::log])
        in-flight (http-managed/in-flight-snapshot)
        per-actor (http-managed/actor-in-flight-snapshot)]
    [:div {:data-testid "managed-http-registry-strip"
           :style {:border "1px solid #d8d2ff" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.5em 0"
                   :background "#fcfbff" :font-size "12px"}}
     [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Managed-HTTP in-flight registry"]
     [:div {:data-testid "managed-http-in-flight"}
      "request-id in-flight: " [:strong (pr-str (vec (keys in-flight)))]]
     [:div {:data-testid "managed-http-actor-in-flight"}
      "actor in-flight: "
      [:strong (pr-str (into {} (map (fn [[a hs]] [a (count hs)]) per-actor)))]]]))

(reg-view status-strip
  "The lifecycle status read-out: current :status, last reply, and a short
  log of recent lifecycle events. Pure snapshot reads."
  []
  (let [status @(subscribe [::status])
        reply  @(subscribe [::reply])
        log    @(subscribe [::log])]
    [:div {:data-testid "managed-http-status-strip"
           :style {:border "1px solid #d8d2ff" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.5em 0"
                   :background "#fcfbff" :font-size "12px"}}
     [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Lifecycle status"]
     [:div "status: " [:strong {:data-testid "managed-http-lifecycle-status"} (name status)]
      (when reply
        [:span " · reply.kind: "
         [:strong {:data-testid "managed-http-reply-kind"} (pr-str (:kind reply))]
         (when-let [k (get-in reply [:failure :kind])]
           [:span " · failure.kind: "
            [:strong {:data-testid "managed-http-failure-kind"} (pr-str k)]])])]
     (when (seq log)
       [:ul {:data-testid "managed-http-log"
             :style {:margin "0.35em 0 0 0" :padding-left "1.2em" :color "#555"}}
        (for [[i line] (map-indexed vector log)]
          ^{:key i} [:li line])])]))

(reg-view root []
  [:div {:data-testid "managed-http-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "880px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:data-testid "managed-http-title" :style {:margin 0}}
     "Xray Testbed: Managed HTTP"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One button (" [:strong "▶ Run series"] ") walks the managed-HTTP "
     "lifecycle — fire · success · 4xx · 5xx · abort · concurrent-by-actor · "
     "request-outlives-teardown. After each dispatch the runner pins Xray "
     "focus to the latest " [:code ":rf/default"] " epoch; read each step's "
     [:strong "Watch"] " note, then watch the Epoch / Trace / App-db / "
     "Machine panels render it. The runner cursor lives in a "
     [:strong "local atom"] " — it never touches the inspected app-db."]]
   [status-strip]
   [in-flight-strip]
   [runner/runner "managed-http" runner-state steps host-frame]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  (rdc/render react-root [root]))
