(ns managed-http-counter.core
  "A counter whose buttons don't add anything locally — each click asks
  the server what the new count should be.

  That's the whole idea, and it's built on one effect: `:rf.http/managed`.
  You describe a request as data; the runtime owns the rest of its life —
  encode, send, decode, sort the failures, retry, abort. When the answer
  comes home it arrives as an ordinary event, re-dispatched to the very
  handler that sent the request. So \"send\" and \"receive\" are two passes
  through one pure function, and you never go near js/fetch. The HTTP guide
  has the full contract: docs/resources/http.md.

  Five buttons walk the whole surface:

    +1             GET api/inc.json — a real, successful round-trip.
    Fail           GET api/does-not-exist — a real 404 (:rf.http/http-4xx).
    Retry-recover  the recovery-after-retry path, driven by a canned stub.
    Start long     a request that genuinely stays in flight.
    Cancel         aborts that request by :request-id.

  +1 and Fail are honest Fetches: the request goes out, the body is decoded
  (only on a 2xx), and the reply lands back in app-db. Retry-recover would
  need an endpoint that fails once then succeeds, which is a nuisance to
  stand up — so it uses a canned-success stub that synthesises the reply
  the live fx would have produced.

  Start long / Cancel are the interesting pair: a real abort of a request
  that is really in flight. There's a catch, though. This app serves only
  static assets, so a real GET resolves instantly and never lingers long
  enough to cancel. The fix is honest: \"Start long\" seeds a request-id-keyed
  handle straight into the framework's in-flight registry — the very atom
  the live transport writes into — so we have a deterministically pending
  request. \"Cancel\" then fires the live `:rf.http/managed-abort` fx at it:
  the abort resolves the handle, fires its `:abort-fn`, and a
  `:rf.http/aborted` reply comes home to the issuing handler. Only the
  transport is stood in for. The registry entry, the abort fx, and the
  aborted classification are all real — and all visible in Xray and traces.

  Put plainly: this is the runnable cross-substrate sanity check. The same
  fx and the same reply shape, end to end, through Reagent and Fetch."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Managed HTTP ships in its own artefact (day8/re-frame2-http).
            ;; Requiring this once at boot is what registers `:rf.http/managed`
            ;; and its family — skip it and the first fx dispatch fails loud
            ;; with a friendly "HTTP artefact missing" error.
            [re-frame.http.managed]
            ;; Home of the canned-success stub the Retry-recover button leans
            ;; on (`:rf.http/managed-canned-success`). It lives here, in the
            ;; test-support ns, rather than in re-frame.http.managed proper.
            [re-frame.http.test-support]
            ;; "Start long" seeds a pending handle straight into the registry.
            ;; The write seam (`record-in-flight!`) isn't on the public facade,
            ;; so we reach for the registry ns directly. It's the same atom the
            ;; live transport writes into and the live abort fx resolves.
            [re-frame.http.registry :as http-registry]
            ;; The verb helpers (rf.http/get / post / put / delete / patch /
            ;; head / options). Each builds the canonical
            ;; [:rf.http/managed args-map] vector so the call site stays a line.
            [re-frame.http :as rf.http]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SHAPE
;; ============================================================================
;;
;; Three slots, and that's the whole of this app's state:
;;
;; {:counter/count    <int>                     ;; the number on screen
;;  :counter/status   <:idle|:loading|:error>   ;; where the request is in its life
;;  :counter/error    <failure-map-or-nil>}
;;
;; :status is what makes the in-flight lifecycle visible: :loading while the
;; runtime is working a request, then :idle (or :error) once the reply lands.
;;
;; Every slot, sub-id, and event wears a :counter/ prefix. It's the feature
;; convention, and it's cheap insurance — this app's keys can't collide with
;; another feature's.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _ev]
    {:db {:counter/count  0
     :counter/status :idle
     :counter/error  nil}}))

;; ============================================================================
;; +1  —  real round-trip via Fetch
;; ============================================================================
;;
;; Start here — this is the pattern the whole example turns on. One pure
;; handler, two jobs. The first time through, the :else branch *describes* a
;; request and hands back an :rf.http/managed effect. The runtime takes it
;; from there: GET api/inc.json, decode the `{"delta": 1}` body, and
;; re-dispatch [:counter/+1 {:rf/reply ...}] right back to this same handler.
;; Second time through it's the :success branch, which applies the increment.
;; All the asynchrony lives in the runtime. The handler never stops being a
;; plain pure function — no callbacks, no promises to babysit.

(rf/reg-event :counter/+1
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; The reply came back happy — bump the count by the delta the server
      ;; sent, and settle the UI to :idle.
      (some-> msg :rf/reply :kind (= :success))
      {:db (-> db
               (update :counter/count + (or (:delta (:value (:rf/reply msg))) 1))
               (assoc :counter/status :idle :counter/error nil))}

      ;; The reply came back unhappy — stash the error so the view can show it.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (-> db
               (assoc :counter/status :error
                      :counter/error  (:failure (:rf/reply msg))))}

      ;; No reply yet, so this is the opening move: fire the request.
      ;; `rf.http/get` builds the same `[:rf.http/managed args-map]` vector a
      ;; hand-written `:method :get` entry would — just in one tidy line.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/inc.json" {:decode :json})]})))

;; ============================================================================
;; Fail  —  real 404 from the http-server
;; ============================================================================
;;
;; The same handler shape as +1, just walking the failure side of the reply.
;; We branch on the reply's :kind and, on :failure, keep the whole failure
;; map. Its own :kind is the :rf.http/* category — exactly what the view
;; reaches for to tell the user what went wrong.

(rf/reg-event :counter/fail
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc db :counter/status :error :counter/error (:failure (:rf/reply msg)))}

      (some-> msg :rf/reply :kind (= :success))
      ;; We never expect to land here — the URL is a deliberate 404.
      {:db (assoc db :counter/status :idle :counter/error nil)}

      ;; Same `rf.http/get` helper as above, and a worthwhile detail hides in
      ;; here: status is classified *before* the body is decoded. So a 404 that
      ;; answers with HTML or plain text is :rf.http/http-4xx (raw body at
      ;; :body), never :rf.http/decode-failure — even if you'd asked for
      ;; `:decode :json`. We leave :decode at its `:auto` default to show the
      ;; everyday case: a JSON endpoint that 404s with a load-balancer's HTML
      ;; error page. The classification order is in docs/resources/http.md.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [(rf.http/get "api/does-not-exist")]})))

;; ============================================================================
;; Retry-recover  —  canned-stub at app level
;; ============================================================================
;;
;; The "it failed, retried, and recovered" path — without needing a flaky
;; endpoint to make it happen on cue. The handler issues
;; :rf.http/managed-canned-success, which synthesises a
;; {:kind :success :value {:delta 5}} reply: the exact shape the live fx
;; would deliver if its retry policy hit a real endpoint that 503s once and
;; then 200s. The stub lets you exercise the contract without owning a
;; server, and — the part that matters — the handler can't tell the
;; difference. It reads the reply the same way either way.

(rf/reg-event :counter/retry-recover
  (fn [{:keys [db]} [_ msg]]
    (cond
      (some-> msg :rf/reply :kind (= :success))
      {:db (-> db
               (update :counter/count + (or (:delta (:value (:rf/reply msg))) 0))
               (assoc :counter/status :idle :counter/error nil))}

      :else
      {:db (assoc db :counter/status :loading)
       ;; The stub conjures the reply directly. We still spell out the
       ;; :request and :decode so the call site reads like the real thing —
       ;; the stub just quietly skips the trip over the network.
       :fx [[:rf.http/managed-canned-success
             {:request {:method :get :url "api/flaky"}
              :decode  :json
              :value   {:delta 5}}]]})))

;; ============================================================================
;; Start long / Cancel  —  a REAL abort of a REAL in-flight request
;; ============================================================================
;;
;; The abort contract is simple to state: you cancel an in-flight
;; `:rf.http/managed` request by its `:request-id`. The
;; `:rf.http/managed-abort` fx finds the handle in the framework's in-flight
;; registry, fires its `:abort-fn`, and a `:rf.http/aborted` reply comes home
;; to the handler that issued the request. The abort section of
;; docs/resources/http.md has the details.
;;
;; Here's the catch worth being honest about: this app serves only static
;; assets, so a real GET resolves in a blink and nothing ever lingers
;; in-flight long enough to cancel. Demoing a cancel against a request that's
;; already finished would be theatre. So instead, "Start long" seeds a
;; request-id-keyed handle straight into the registry — the very atom the
;; live transport writes into — which gives us a request that is genuinely,
;; deterministically pending. "Cancel" then fires the *live*
;; `:rf.http/managed-abort` fx at that handle. Only the transport is stood
;; in for. The registry entry, the abort fx, and the `:rf.http/aborted`
;; classification are all the real thing — and you can watch them in Xray
;; and the trace stream.

(def long-request-id :counter/long)

;; A placeholder URL we stamp on the seeded handle purely so it reads
;; naturally in the abort trace and any registry peek. Nothing fetches it —
;; the slot is seeded by hand, which is what makes the pending state
;; deterministic.
(def long-pending-url "api/long")

(rf/reg-fx :counter/seed-long-request
  {:doc       "Demo-only fx that fakes a request stuck in flight. It records a
               request-id-keyed handle in the framework registry whose
               :abort-fn clears the slot and dispatches the :rf.http/aborted
               reply back to :counter/start-long. That's the whole trick: it
               lets the live :rf.http/managed-abort fx resolve a real handle
               end-to-end, with no real request behind it."
   :platforms #{:client}}
  (fn fx-seed-long-request [frame-ctx {:keys [request-id]}]
    ;; A small but important wrinkle. The :abort-fn doesn't run now — it runs
    ;; later, whenever someone aborts this handle, and by then there's no
    ;; ambient frame around it. A bare `rf/dispatch` in there would raise
    ;; :rf.error/no-frame-context. In re-frame2 a frame's identity is *carried,
    ;; not found*, so the fix is to grab the frame from this fx's context right
    ;; here and hand it to the deferred dispatch ourselves. See
    ;; docs/guide/glossary.md#frame-identity-is-carried-not-found.
    (let [frame (:frame frame-ctx)]
      (http-registry/record-in-flight!
        request-id nil
        {:url      long-pending-url
         ;; The :abort-fn is the request's cancellation hook — the thing that
         ;; actually runs when someone pulls the plug. The live
         ;; :rf.http/managed-abort handler looks this handle up by request-id
         ;; and calls us with the abort `reason` (`:user` here). Our two jobs:
         ;; clear the registry slot, and dispatch the :rf.http/aborted reply —
         ;; the exact shape the live transport's abort path emits — home to
         ;; :counter/start-long via default reply addressing.
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
      ;; The reply branch. When Cancel fires, the :abort-fn dispatches
      ;; :rf.http/aborted, and default reply addressing routes it right back
      ;; here. We note the aborted classification and ease the UI to :idle.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc db
                  :counter/status :idle
                  :counter/error  (:failure (:rf/reply msg)))}

      (some-> msg :rf/reply :kind (= :success))
      {:db (assoc db :counter/status :idle :counter/error nil)}

      ;; The opening move: seed a request that's genuinely in flight, ready
      ;; for Cancel to abort for real. We stay in :loading until that abort
      ;; resolves the slot.
      :else
      {:db (assoc db :counter/status :loading :counter/error nil)
       :fx [[:counter/seed-long-request {:request-id long-request-id}]]})))

(rf/reg-event :counter/cancel
  (fn [{:keys [db]} _]
    {:db db
     ;; Abort by request-id, through the *live* :rf.http/managed-abort fx —
     ;; no shortcut. It finds the seeded handle in the in-flight registry and
     ;; fires its :abort-fn, which clears the slot and sends the
     ;; :rf.http/aborted reply back to :counter/start-long (handled above).
     ;; If nothing's in flight the registry lookup simply misses, and this is
     ;; a harmless no-op.
     :fx [[:rf.http/managed-abort long-request-id]]}))

;; ============================================================================
;; SUBS
;; ============================================================================
;;
;; Three plain reads, one per slot. Pure derivations the framework caches and
;; only recomputes when their input actually moves. Deliberately boring — the
;; HTTP machinery upstream is where the interesting stuff lives.

(rf/reg-sub :counter/count  (fn [db _] (:counter/count  db)))
(rf/reg-sub :counter/status (fn [db _] (:counter/status db)))
(rf/reg-sub :counter/error  (fn [db _] (:counter/error  db)))

;; ============================================================================
;; VIEWS
;; ============================================================================
;;
;; Subscription values in, hiccup out — one dispatch per button. No logic
;; sneaks in here. A view reads derived state and fires events; that's the
;; whole contract, and keeping it that thin is what keeps it predictable.

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
;; We keep the React root in an atom and only build it lazily inside `run`,
;; never at ns-load. That's the mount-isolation rule from examples/TESTING.md:
;; loading a namespace must have zero DOM side effects, so that two example
;; namespaces sharing a page can't race each other to call `create-root` on
;; the same `#app`.

(defonce react-root (atom nil))

;; The app stands its frame up in exactly one place: the render root's
;; `frame-provider {:id app-frame}`. On the first mount that provider creates
;; the frame, applies its config, and runs `:initial-events` once (our
;; `[:counter/initialise]` seed). On a hot reload it finds the frame already
;; there, reuses it, and skips the seed. From then on every `dispatch` and
;; `subscribe` in the tree resolves to this frame.
;;
;; `:rf/default` is just the id this app happened to pick — an ordinary frame
;; id with no special standing, which is why the runtime won't conjure it for
;; you. Same mount you'll find in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the Reagent adapter — and only the adapter. You hand it
  ;; the adapter spec map (every adapter ns exports one as `adapter`). It does
  ;; not create a frame; that's the frame-provider's job, just below.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))
