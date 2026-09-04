(ns managed-http-counter.core
  "A counter whose +1 action asks the server what the new count should be.

  The live request path is built on one effect: `:rf.http/managed`.
  You describe a request as data; the runtime owns the rest of its life —
  encode, send, decode, sort the failures, retry, abort. When the answer
  comes home it arrives as an ordinary event: you address the reply with
  `:reply-to`, and the runtime appends the canonical reply envelope as the
  event's last argument. Point `:reply-to` back at the very handler that sent
  the request and \"send\" and \"receive\" become two passes through one pure
  function — you never go near js/fetch. The HTTP guide has the full
  contract: docs/async/http.md.

  Five buttons cover the live and controlled paths used by this example:

    +1             GET api/inc.json — a real, successful round-trip.
    Fail           GET api/does-not-exist — a real 404 (:rf.http/http-4xx).
    Retry-recover  a canonical success reply, driven by a canned stub.
    Start long     a request handle seeded into the in-flight registry.
    Cancel         aborts that handle by :request-id.

  +1 and Fail use Fetch: the request goes out, the body is decoded (only on a
  2xx), and the reply lands back in app-db. Retry-recover uses a
  canned-success stub. It demonstrates the canonical reply envelope, not the
  retry scheduler or a sequence of transport attempts.

  Start long / Cancel exercise the managed-abort registry contract without a
  network request. A static GET resolves too quickly to cancel reliably, so
  \"Start long\" seeds a request-id-keyed handle through the same registry API
  used by the live transport. \"Cancel\" then fires the production
  `:rf.http/managed-abort` fx: it resolves the handle, invokes its `:abort-fn`,
  clears the slot, and dispatches a canonical `:rf.http/aborted` reply. This
  covers the framework path, but not `AbortController` or network cancellation.

  The Fetch branches exercise managed HTTP end to end through Reagent; the
  controlled branches isolate canonical reply handling and registry abort."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP ships in its own artefact (day8/re-frame2-http).
            ;; Requiring this once at boot is what registers `:rf.http/managed`
            ;; and its family — skip it and the first fx dispatch fails loud
            ;; with a friendly "HTTP artefact missing" error.
            [re-frame.http.managed]
            ;; Home of the canned-success reply seam used by Retry-recover.
            ;; It emits a canonical reply but does not execute retry attempts.
            [re-frame.http.test-support]
            ;; "Start long" seeds a pending handle straight into the registry.
            ;; The write seam (`record-in-flight!`) isn't on the public facade,
            ;; so we reach for the registry ns directly. It's the same atom the
            ;; live transport writes into and the live abort fx resolves.
            [re-frame.http.registry :as rf.http.registry]
            ;; The verb helpers (rf.http/get / post / put / delete / patch /
            ;; head / options). Each builds the canonical
            ;; [:rf.http/managed args-map] vector so the call site stays a line.
            [re-frame.http :as rf.http]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; ============================================================================
;; APP-DB SHAPE
;; ============================================================================
;;
;; Three slots, and that's the whole of this app's state:
;;
;; {:http-counter/count    <int>                     ;; the number on screen
;;  :http-counter/status   <:idle|:loading|:error>   ;; where the request is in its life
;;  :http-counter/error    <failure-map-or-nil>}
;;
;; :status is what makes the in-flight lifecycle visible: :loading while the
;; runtime is working a request, then :idle (or :error) once the reply lands.
;;
;; Every slot, sub-id, and event wears a :http-counter/ prefix. It's the feature
;; convention, and it's cheap insurance — this app's keys can't collide with
;; another feature's.

(rf/reg-event :http-counter/initialise
  (fn [{:keys [db]} _ev]
    {:db {:http-counter/count  0
     :http-counter/status :idle
     :http-counter/error  nil}}))

;; ============================================================================
;; +1  —  real round-trip via Fetch
;; ============================================================================
;;
;; Start here — this is the pattern the whole example turns on. One pure
;; handler, two jobs. The first time through, the :else branch *describes* a
;; request and hands back an :rf.http/managed effect whose `:reply-to` points
;; back at THIS event. The runtime takes it from there: GET api/inc.json,
;; decode the `{"delta": 1}` body, and re-dispatch [:http-counter/+1 <reply>] right
;; back to this same handler — the canonical reply envelope appended as the
;; last arg. Second time through it's the :ok branch, which applies the
;; increment. All the asynchrony lives in the runtime. The handler never stops
;; being a plain pure function — no callbacks, no promises to babysit.

(rf/reg-event :http-counter/+1
  (fn [{:keys [db]} [_ reply]]
    (cond
      ;; The reply came back happy — bump the count by the delta the server
      ;; sent, and settle the UI to :idle. `reply` is the canonical envelope,
      ;; delivered as the event's last arg; success is `:status :ok`, the
      ;; decoded body at `:value`.
      (some-> reply :status (= :ok))
      {:db (-> db
               (update :http-counter/count + (or (:delta (:value reply)) 1))
               (assoc :http-counter/status :idle :http-counter/error nil))}

      ;; The reply came back unhappy — stash the error so the view can show it.
      ;; Failure is `:status :error`, the classified `:rf.http/*` map at `:error`.
      (some-> reply :status (= :error))
      {:db (-> db
               (assoc :http-counter/status :error
                      :http-counter/error  (:error reply)))}

      ;; No reply yet, so this is the opening move: fire the request and
      ;; address its reply back to THIS event with `:reply-to`. `rf.http/get`
      ;; builds the same `[:rf.http/managed args-map]` vector a hand-written
      ;; `:method :get` entry would — just in one tidy line.
      :else
      {:db (assoc db :http-counter/status :loading :http-counter/error nil)
       :fx [(rf.http/get "api/inc.json" {:decode :json :reply-to [:http-counter/+1]})]})))

;; ============================================================================
;; Fail  —  real 404 from the http-server
;; ============================================================================
;;
;; The same handler shape as +1, just walking the failure side of the reply.
;; We branch on the reply's :status and, on :error, keep the whole failure
;; map (it rides under :error). Its own :kind is the :rf.http/* category —
;; exactly what the view reaches for to tell the user what went wrong.

(rf/reg-event :http-counter/fail
  (fn [{:keys [db]} [_ reply]]
    (cond
      (some-> reply :status (= :error))
      {:db (assoc db :http-counter/status :error :http-counter/error (:error reply))}

      (some-> reply :status (= :ok))
      ;; We never expect to land here — the URL is a deliberate 404.
      {:db (assoc db :http-counter/status :idle :http-counter/error nil)}

      ;; Same `rf.http/get` helper as above, and a worthwhile detail hides in
      ;; here: status is classified *before* the body is decoded. So a 404 that
      ;; answers with HTML or plain text is :rf.http/http-4xx (raw body at
      ;; :body), never :rf.http/decode-failure — even if you'd asked for
      ;; `:decode :json`. We leave :decode at its `:auto` default to show the
      ;; everyday case: a JSON endpoint that 404s with a load-balancer's HTML
      ;; error page. The classification order is in docs/async/http.md.
      :else
      {:db (assoc db :http-counter/status :loading :http-counter/error nil)
       :fx [(rf.http/get "api/does-not-exist" {:reply-to [:http-counter/fail]})]})))

;; ============================================================================
;; Retry-recover  —  canned-stub at app level
;; ============================================================================
;;
;; This path synthesises the success envelope a recovered request would
;; eventually deliver: {:status :ok :value {:delta 5} …}. It deliberately does
;; not exercise retry policy, attempt counting, or backoff. The point here is
;; narrower: the handler consumes the same canonical reply whether it came from
;; the live transport or a controlled test seam.

(rf/reg-event :http-counter/retry-recover
  (fn [{:keys [db]} [_ reply]]
    (cond
      (some-> reply :status (= :ok))
      {:db (-> db
               (update :http-counter/count + (or (:delta (:value reply)) 0))
               (assoc :http-counter/status :idle :http-counter/error nil))}

      :else
      {:db (assoc db :http-counter/status :loading)
       ;; The stub conjures the reply directly. We still spell out the
       ;; :request and :decode so the call site reads like the real thing —
       ;; the stub just quietly skips the trip over the network. `:reply-to`
       ;; addresses the synthesised reply exactly as it would the live fx's.
       :fx [[:rf.http/managed-canned-success
             {:request  {:method :get :url "api/flaky"}
              :decode   :json
              :value    {:delta 5}
              :reply-to [:http-counter/retry-recover]}]]})))

;; ============================================================================
;; Start long / Cancel  —  managed-abort over a seeded registry handle
;; ============================================================================
;;
;; The abort contract is simple to state: you cancel an in-flight
;; `:rf.http/managed` request by its `:request-id`. The
;; `:rf.http/managed-abort` fx finds the handle in the framework's in-flight
;; registry, fires its `:abort-fn`, and a `:rf.http/aborted` reply comes home
;; to the handler that issued the request. The abort section of
;; docs/async/http.md has the details.
;;
;; This app serves only static assets, so a GET resolves too quickly to cancel
;; reliably. "Start long" instead seeds a request-id-keyed handle through the
;; same registry API used by the live transport. "Cancel" fires the production
;; `:rf.http/managed-abort` fx at that handle. The demo therefore covers handle
;; lookup, registry cleanup, and the `:rf.http/aborted` reply, while explicitly
;; leaving network transport and `AbortController` out of scope.

(def long-request-id :http-counter/long)

;; A placeholder URL we stamp on the seeded handle purely so it reads
;; naturally in the abort trace and any registry peek. Nothing fetches it —
;; the slot is seeded by hand, which is what makes the pending state
;; deterministic.
(def long-pending-url "api/long")

(rf/reg-fx :http-counter/seed-long-request
  {:doc       "Demo-only fx that seeds a request handle in the in-flight
               registry. It records a request-id-keyed handle whose
               :abort-fn clears the slot and dispatches the :rf.http/aborted
               reply back to :http-counter/start-long. That's the whole trick: it
               lets :rf.http/managed-abort exercise its production lookup,
               cleanup, and reply path, with no network request behind it."
   :platforms #{:client}}
  (fn fx-seed-long-request [frame-ctx {:keys [request-id]}]
    ;; A small but important wrinkle. The :abort-fn doesn't run now — it runs
    ;; later, whenever someone aborts this handle, and by then there's no
    ;; ambient frame around it. A bare `rf/dispatch` in there would raise
    ;; :rf.error/no-frame-context. In re-frame2 a frame's identity is *carried,
    ;; not found*, so the fix is to grab the frame from this fx's context right
    ;; here and hand it to the deferred dispatch ourselves. See
    ;; docs/core/glossary.md#frame-identity-is-carried-not-found.
    (let [frame (:frame frame-ctx)]
      (rf.http.registry/record-in-flight!
        request-id nil
        {:url      long-pending-url
         ;; The :abort-fn is the request's cancellation hook — the thing that
         ;; actually runs when someone pulls the plug. The live
         ;; :rf.http/managed-abort handler looks this handle up by request-id
         ;; and calls us with the abort `reason` (`:user` here). Our two jobs:
         ;; clear the registry slot, and dispatch the :rf.http/aborted reply —
         ;; the exact shape the live transport's abort path emits — home to
         ;; :http-counter/start-long. The live fx would deliver it via `:reply-to`,
         ;; so we mirror that: the canonical envelope is the appended last arg.
         :abort-fn (fn [reason]
                     (rf.http.registry/clear-in-flight! request-id)
                     ;; The canonical abort reply uses `:status :cancelled`
                     ;; with `:cancelled? true`, carries the abort reason
                     ;; under the uniform reply contract's namespaced field
                     ;; `:rf.reply/cancel-reason`, and rides the
                     ;; `:rf.http/aborted` map under `:error` — the exact
                     ;; shape the live transport's abort path emits (see
                     ;; `re-frame.http.reply/failure-reply`). It passes
                     ;; `re-frame.reply/validate-reply` unchanged.
                     (rf/dispatch [:http-counter/start-long
                                   {:status                 :cancelled
                                    :cancelled?             true
                                    :rf.reply/cancel-reason reason
                                    :error                  {:kind       :rf.http/aborted
                                                             :request-id request-id
                                                             :reason     reason}}]
                                  {:frame frame}))}))
    nil))

(rf/reg-event :http-counter/start-long
  (fn [{:keys [db]} [_ reply]]
    (cond
      ;; The reply branch. When Cancel fires, the :abort-fn dispatches the
      ;; canonical :status :cancelled reply as this event's last arg, routing
      ;; it right back here. We note the aborted classification (under :error)
      ;; and ease the UI to :idle.
      (some-> reply :status (= :cancelled))
      {:db (assoc db
                  :http-counter/status :idle
                  :http-counter/error  (:error reply))}

      (some-> reply :status (= :ok))
      {:db (assoc db :http-counter/status :idle :http-counter/error nil)}

      ;; The opening move: seed a request that's genuinely in flight, ready
      ;; for Cancel to abort for real. We stay in :loading until that abort
      ;; resolves the slot.
      :else
      {:db (assoc db :http-counter/status :loading :http-counter/error nil)
       :fx [[:http-counter/seed-long-request {:request-id long-request-id}]]})))

(rf/reg-event :http-counter/cancel
  (fn [{:keys [db]} _]
    {:db db
     ;; Abort by request-id, through the *live* :rf.http/managed-abort fx —
     ;; no shortcut. It finds the seeded handle in the in-flight registry and
     ;; fires its :abort-fn, which clears the slot and sends the
     ;; :rf.http/aborted reply back to :http-counter/start-long (handled above).
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

(rf/reg-sub :http-counter/count  (fn [db _] (:http-counter/count  db)))
(rf/reg-sub :http-counter/status (fn [db _] (:http-counter/status db)))
(rf/reg-sub :http-counter/error  (fn [db _] (:http-counter/error  db)))

;; ============================================================================
;; VIEWS
;; ============================================================================
;;
;; Subscription values in, hiccup out — one dispatch per button. No logic
;; sneaks in here. A view reads derived state and fires events; that's the
;; whole contract, and keeping it that thin is what keeps it predictable.

(rf/reg-view counter-view []
  (let [count  @(subscribe [:http-counter/count])
        status @(subscribe [:http-counter/status])
        error  @(subscribe [:http-counter/error])]
    [:div {:style {:font-family "sans-serif" :padding "1em"}}
     [:h1 "Managed HTTP counter"]
     [:p "Count: " [:span {:data-testid "count"} count]]
     [:p "Status: " [:span {:data-testid "status"} (name status)]]
     (when error
       [:p {:data-testid "error"
            :style       {:color "crimson"}}
        "Error kind: " (str (:kind error))])
     [:div {:style {:display :flex :gap "0.5em"}}
      [:button {:on-click #(dispatch [:http-counter/+1])}              "+1"]
      [:button {:on-click #(dispatch [:http-counter/fail])}            "Fail"]
      [:button {:on-click #(dispatch [:http-counter/retry-recover])}   "Retry-recover"]
      [:button {:on-click #(dispatch [:http-counter/start-long])}      "Start long"]
      [:button {:on-click #(dispatch [:http-counter/cancel])}          "Cancel"]]]))

(rf/reg-view counter-app []
  [counter-view])

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; We keep the React root in an atom and only build it lazily inside `run`,
;; never at ns-load. That's the mount-isolation rule from examples/TESTING.md
;; §Example mount-isolation convention: loading a namespace must have zero DOM
;; side effects, so that two example namespaces sharing a page can't race each
;; other to call `create-root` on the same `#app`.

(defonce app-root (rf.adapter.reagent/client-root))

;; The app stands its frame up in exactly one place: the render root's
;; `frame-root {:id app-frame}`. On the first mount that provider creates
;; the frame, applies its config, and runs `:initial-events` once (our
;; `[:http-counter/initialise]` seed). On a hot reload it finds the frame already
;; there, reuses it, and skips the seed. From then on every `dispatch` and
;; `subscribe` in the tree resolves to this frame.
;;
;; `:rf/default` is just the id this app happened to pick — an ordinary frame
;; id with no special standing, which is why the runtime won't conjure it for
;; you. Same mount you'll find in examples/core/counter/core.cljs.
(def app-frame :rf/default)

;; `mount!` is browser setup: create the root lazily, then render the view tree
;; inside the frame-root. `^:dev/after-load` is shadow's cue to re-run it on
;; each reload so your edited views re-render into the same root and same frame.
;; This is the canonical mount/boot shape, spelled the same in the counter and
;; todomvc examples. See `docs/core/how-to/boot-and-mount-an-app.md`.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (rf.adapter.reagent/render! app-root
      [rf/frame-root {:id app-frame
                      :initial-events [[:http-counter/initialise]]}
       [counter-app]]
      el)))

(defn run []
  ;; `init!` installs the Reagent adapter — and only the adapter. You hand it
  ;; the adapter spec map (every adapter ns exports one as `adapter`). It does
  ;; not create a frame; that's the frame-root's job, in `mount!`.
  (rf/init! rf.adapter.reagent/adapter)
  (mount!))
