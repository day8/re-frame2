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

  Four buttons cover the live and controlled paths used by this example:

    +1             GET api/inc.json — a real, successful round-trip.
    Fail           GET api/does-not-exist — a real 404 (:rf.http/http-4xx).
    Retry-recover  a canonical success reply, driven by a canned stub.
    Cancel         aborts the in-flight +1 request by :request-id.

  +1 and Fail use Fetch: the request goes out, the body is decoded (only on a
  2xx), and the reply lands back in app-db. Retry-recover uses a
  canned-success stub. It demonstrates the canonical reply envelope, not the
  retry scheduler or a sequence of transport attempts.

  Cancellation goes through the public surface, and it is two small things.
  The +1 request carries `:request-id :http-counter/+1`. Cancel fires
  `:rf.http/managed-abort` at that id, and the framework does the rest:
  resolving the handle, firing its `AbortController`, clearing the registry
  slot, and delivering a canonical `:status :cancelled` reply back to the very
  handler that issued the request. You write the id and the abort; nothing
  else.

  A static GET usually lands before you can click, and an abort that arrives
  after the reply is a documented no-op. To watch one actually cancel,
  throttle the network in DevTools (Slow 3G), then click +1 and Cancel.

  The Fetch branches exercise managed HTTP end to end through Reagent; the
  canned branch isolates canonical reply handling."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP ships in its own artefact (day8/re-frame2-http).
            ;; Requiring this once at boot is what registers `:rf.http/managed`
            ;; and its family — skip it and the first fx dispatch fails loud
            ;; with a friendly "HTTP artefact missing" error.
            [re-frame.http.managed]
            ;; Home of the canned-success reply seam used by Retry-recover.
            ;; It emits a canonical reply but does not execute retry attempts.
            [re-frame.http.test-support]
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
;; handler, two jobs. The first time through, the (nil? reply) branch
;; *describes* a request and hands back an :rf.http/managed effect whose
;; `:reply-to` points back at THIS event. The runtime takes it from there: GET api/inc.json,
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

      ;; The request was cancelled. This is the reply
      ;; `[:rf.http/managed-abort :http-counter/+1]` delivers, and the
      ;; framework builds every part of it — never the app: `:status
      ;; :cancelled` with `:cancelled? true`, the abort reason under the
      ;; uniform reply contract's `:rf.reply/cancel-reason`, and the
      ;; classified `{:kind :rf.http/aborted :reason :user}` map under
      ;; `:error`. A cancellation is not a failure, so we settle to :idle
      ;; rather than :error — but we keep the classified map, which is what
      ;; makes the view's existing error line read "Error kind:
      ;; :rf.http/aborted" instead of going quietly blank.
      (some-> reply :status (= :cancelled))
      {:db (assoc db
                  :http-counter/status :idle
                  :http-counter/error  (:error reply))}

      ;; No reply yet, so this is the opening move: fire the request and
      ;; address its reply back to THIS event with `:reply-to`. The request
      ;; is DATA — a `[:rf.http/managed args-map]` vector, with the method
      ;; and URL sitting under `:request` beside the wire fields. An app that
      ;; issues many requests writes its own builder fn over this map (a base
      ;; URL, default headers, a default `:decode`); see
      ;; examples/real-apps/realworld_http/http.cljs for that pattern.
      ;;
      ;; `:request-id` is the public cancellation handle, and it is the whole
      ;; of what this app does to become cancellable (Spec 014 §E). It sits at
      ;; the top level of the effect map, beside `:request` and `:reply-to`.
      ;; Cancel names this same id — the two halves are pinned together by
      ;; implementation/core/test/re_frame/managed_http_counter_cljs_test.cljs,
      ;; because changing one alone still compiles and simply cancels nothing.
      ;; A `:request-id` is frame-LOCAL (Spec 014 §Frame scope), so two mounts
      ;; of this example on one page cannot cancel each other.
      ;;
      ;; Note this arm tests for the ABSENCE of a reply rather than sitting in
      ;; the `:else` slot. A reply-to-self handler is two handlers in one, and
      ;; the question that separates them is "is there a reply?", not "is the
      ;; status one I listed". Written the other way, a status the `cond` does
      ;; not enumerate — `:stale`, say — would fall through to the initiation
      ;; arm and RE-ISSUE the request.
      (nil? reply)
      {:db (assoc db :http-counter/status :loading :http-counter/error nil)
       :fx [[:rf.http/managed {:request    {:method :get :url "api/inc.json"}
                               :decode     :json
                               :request-id :http-counter/+1
                               :reply-to   [:http-counter/+1]}]]}

      ;; A reply arrived carrying some other status. Settle the UI; never
      ;; re-issue.
      :else
      {:db (assoc db :http-counter/status :idle)})))

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

      ;; Same request shape as above, and a worthwhile detail hides in
      ;; here: status is classified *before* the body is decoded. So a 404 that
      ;; answers with HTML or plain text is :rf.http/http-4xx (raw body at
      ;; :body), never :rf.http/decode-failure — even if you'd asked for
      ;; `:decode :json`. We leave :decode at its `:auto` default to show the
      ;; everyday case: a JSON endpoint that 404s with a load-balancer's HTML
      ;; error page. The classification order is in docs/async/http.md.
      ;;
      ;; As in `:http-counter/+1`, the initiation arm asks whether a reply is
      ;; ABSENT rather than serving as the catch-all — so an unenumerated
      ;; status cannot be mistaken for "no reply yet" and re-issue the request.
      (nil? reply)
      {:db (assoc db :http-counter/status :loading :http-counter/error nil)
       :fx [[:rf.http/managed {:request  {:method :get :url "api/does-not-exist"}
                               :reply-to [:http-counter/fail]}]]}

      :else
      {:db (assoc db :http-counter/status :idle)})))

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

      ;; Initiation is guarded on the ABSENCE of a reply — see the note in
      ;; `:http-counter/+1` for why that is not the same as `:else`.
      (nil? reply)
      {:db (assoc db :http-counter/status :loading)
       ;; The stub conjures the reply directly. We still spell out the
       ;; :request and :decode so the call site reads like the real thing —
       ;; the stub just quietly skips the trip over the network. `:reply-to`
       ;; addresses the synthesised reply exactly as it would the live fx's.
       :fx [[:rf.http/managed-canned-success
             {:request  {:method :get :url "api/flaky"}
              :decode   :json
              :value    {:delta 5}
              :reply-to [:http-counter/retry-recover]}]]}

      :else
      {:db (assoc db :http-counter/status :idle)})))

;; ============================================================================
;; Cancel  —  abort the live +1 request by :request-id
;; ============================================================================
;;
;; This is the whole of cancellation in an app. `:rf.http/managed-abort` takes
;; the `:request-id` the +1 request stamped on itself, and the framework does
;; the rest: resolve the handle, fire its `AbortController`, clear the registry
;; slot, and deliver the canonical `:status :cancelled` reply back to
;; `:http-counter/+1`, which has an arm for it. No app-owned registry, no
;; hand-built envelope, no cleanup of our own. The abort section of
;; docs/async/http.md has the contract.
;;
;; It aborts whichever +1 request THIS frame has in flight under that id. A
;; `:request-id` is frame-local (Spec 014 §Frame scope), so mounting this
;; example twice on one page cannot make one copy cancel the other's request.
;;
;; And once the reply has landed the registry lookup simply misses —
;; cancellation is opportunistic — so a late Cancel is a documented no-op
;; rather than an error. A static GET usually wins that race; throttle the
;; network in DevTools (Slow 3G) to watch a real one cancel.

(rf/reg-event :http-counter/cancel
  (fn [{:keys [db]} _]
    {:db db
     :fx [[:rf.http/managed-abort :http-counter/+1]]}))

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
