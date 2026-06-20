(ns {{namespace}}.events
  "Event handlers — the second domino in the re-frame2 pipeline.

   The handler is a pure function `(fn [cofx event] effects-map)` — it
   takes the coeffects map (its `:db` is the current app-db) and returns a
   closed effects map (`{:db new-db}`, plus `:fx` when it has side-effects).
   The runtime applies the returned `:db` to the frame and runs the `:fx`;
   views fan out from there. `reg-event` is the one public event-registration
   form (EP-0018)."
  (:require [re-frame.core :as rf]
            ;; The trace-listener surface (`register-listener!`)
            ;; lives in `re-frame.trace.tooling`, NOT `re-frame.core` —
            ;; CLJS production bundles DCE the tooling namespace
            ;; wholesale, so the `rf/...` alias for these fns is
            ;; deliberately JVM-only.
            ;;
            ;; Production elision has two halves and BOTH matter:
            ;;   1. `trace/emit!` is gated on `interop/debug-enabled?`
            ;;      and elides at compile time, so no trace events are
            ;;      ever built in `:advanced` + `goog.DEBUG=false`.
            ;;   2. The *registration call site* itself must be wrapped
            ;;      in a `goog.DEBUG` gate, or Closure keeps the listener
            ;;      closure (and the substituted "[{{namespace}}]" marker
            ;;      string) in the optimized bundle even though it can
            ;;      never fire. The framework contract is explicit on
            ;;      this — see `re-frame.core/register-listener!`'s
            ;;      docstring: "production CLJS bundles DCE the
            ;;      registration site when wrapped in a `goog.DEBUG`
            ;;      gate." That is why the `register-listener!` form
            ;;      below sits inside `(when ^boolean goog.DEBUG ...)`.
            [re-frame.trace.tooling :as trace-tooling]
            ;; Schema artefact — required as a side-effecting load so
            ;; the late-bind hooks publish before {{namespace}}.schema
            ;; runs its `reg-app-schema` registrations. Pulls in Malli
            ;; via the schemas artefact's deps.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            ;; Schema registrations — loaded for the
            ;; `(reg-app-schema [] {:schema CounterDb})` side-effect that
            ;; wires the root app-db validator before any handler commits.
            [{{namespace}}.schema]))

;; --- Errors are events too -------------------------------------------------
;;
;; Per [Spec 009 §The listener API](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md#the-listener-api)
;; every error inside the dispatch pipeline emits a structured trace
;; event with `:op-type :error` — schema violations, handler exceptions,
;; sub exceptions, fx exceptions, drain-depth exceeded, etc. The
;; framework does NOT decide what the user sees; an app-level listener
;; projects errors onto the UI.
;;
;; This default sink surfaces every error to the browser console so a
;; silent regression (a handler that swallowed an exception, a schema
;; that rejected a write, a fx that threw mid-cascade) is impossible
;; to miss. Replace `js/console.error` with whatever your app does for
;; user-visible error projection — toast, modal, error boundary, error
;; reporter (Sentry / Rollbar / etc.).
;;
;; The listener key (`::error-sink`) is namespace-qualified — same-key
;; re-registration replaces the previous callback atomically (per
;; Spec 009 §Re-registration semantics), so hot-reload re-runs this
;; form without leaking listeners.
;;
;; The whole registration is wrapped in `(when ^boolean goog.DEBUG ...)`
;; so Closure dead-code-eliminates it under `:advanced` +
;; `goog.DEBUG=false` — the dev-only console-error closure and its app
;; namespace marker never reach the production bundle (per the
;; `register-listener!` contract; see the require comment above). In
;; dev / `npx shadow-cljs watch app` (`goog.DEBUG=true`) it runs
;; unchanged. When you replace this with real production error
;; projection (toast / Sentry / etc.), register that sink OUTSIDE this
;; gate so it ships in the optimized build.

(when ^boolean goog.DEBUG
  (trace-tooling/register-listener!
    ::error-sink
    (fn [trace-event]
      (when (= :error (:op-type trace-event))
        (js/console.error "[{{namespace}}]"
                          (:operation trace-event)
                          (clj->js (:tags trace-event)))))))

;; --- Initialisation --------------------------------------------------------
;;
;; `:counter/initialise` seeds the app-db at app boot. It's invoked from
;; core/run via `dispatch-sync` so the first render sees the initial
;; value rather than a transient empty frame.

(rf/reg-event
  :counter/initialise
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

;; --- Counter increment -----------------------------------------------------
;;
;; The button in views.cljs dispatches this event. Pure update — no
;; effects, no side-channels. Everything else (re-render, trace emission,
;; epoch tagging, schema validation) is the runtime's job.

(rf/reg-event
  :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value inc)}))

;; --- HTTP failure matrix (commented exemplar) ------------------------------
;;
;; The shape below is the canonical `:rf.http/managed` call-site (per
;; [Spec 014 §`:rf.http/managed`](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md)).
;; Adopt it as-is when your app starts talking to an HTTP backend:
;;
;; The `(:rf/reply msg)` / `{:kind :success|:failure …}` payload this
;; handler reads is managed-HTTP **public compatibility sugar** — NOT the
;; framework-wide managed-async model. Every managed-HTTP completion
;; lowers internally onto re-frame2's
;; [uniform reply envelope](https://github.com/day8/re-frame2/blob/main/spec/Managed-Effects.md#the-uniform-reply-envelope)
;; (Managed-Effects property 9; rationale record
;; [EP-0011](https://github.com/day8/re-frame2/blob/main/docs/EP/EP-0011-uniform-async-reply-envelope.md)):
;; one canonical reply map with a single closed `:status`
;; (`:ok` / `:error` / `:cancelled`; `:stale` is suppressed before app
;; delivery and never reaches this handler), `:value` / `:error`,
;; `:work/id`, and `:completed-at`. `:on-success` / `:on-failure` and the
;; co-located `(:rf/reply msg)` form are sugar that lowers to the
;; framework target `:rf/reply-to` and reshapes the canonical reply back
;; into the `{:kind …}` payload below — so the event shape is preserved
;; exactly. Do NOT read `{:kind …}` as the general async contract: any
;; non-HTTP managed-async surface you build reports completion through the
;; same envelope's `:status` / `:value` / `:error` / `:completed-at`
;; directly, with mandatory stale suppression. See
;; [Spec 014 §Lowering onto the uniform reply envelope](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md#lowering-onto-the-uniform-reply-envelope).
;;
;;   1. Require `[re-frame.http.managed]` at app boot — that's the load-
;;      time side-effect that registers the `:rf.http/managed` fx and
;;      publishes the call-site helpers. Without it, dispatching the fx
;;      below would raise `:rf.error/no-such-fx`.
;;   2. Add `day8/re-frame2-http {:mvn/version "{{rf2-version}}"}` to
;;      your `deps.edn` (the artefact is split out so apps that don't
;;      talk HTTP don't pay for it).
;;   3. Uncomment the handler below.
;;
;; The closed `:retry :on` set is drawn from the **retryable subset** of
;; the `:rf.http/*` failure-category vocabulary (Spec 014 §Failure
;; categories): `:rf.http/transport` (network/DNS/connection-refused),
;; `:rf.http/http-5xx` (server-side; the canonical retryable case), and
;; `:rf.http/timeout` (per-attempt). The other categories
;; (`:rf.http/cors`, `:rf.http/decode-failure`, `:rf.http/http-4xx`,
;; `:rf.http/aborted`, `:rf.http/accept-failure`) are **non-retryable
;; by construction** and the framework rejects them in `:retry :on` at
;; fx-call time with `:rf.error/http-bad-retry-on`.
;;
;; The single `:on-failure` branch sees the resolved failure category
;; via `(:kind (:failure reply))` — branch on the keyword to project
;; each error case onto the UI shape your app wants. Body-conditional
;; retry (e.g. "the server sent `:retry-after`, wait that long") is
;; **out of scope** for `:retry`; lift those into a state machine per
;; Spec 014 §Boundary — transport vs semantic retry.
;;
#_(rf/reg-event
    :counter/load
    (fn [{:keys [db]} [_ msg]]
      (cond
        ;; Success branch — server returned `{"delta": N}` (decoded
        ;; per :decode :auto; see the :decode classification note on the
        ;; request below).
        (some-> msg :rf/reply :kind (= :success))
        {:db (-> db
                 (update :counter/value + (:delta (:value (:rf/reply msg))))
                 (assoc :counter/status :idle))}

        ;; Failure branch — exactly one dispatch per request, even
        ;; with retry, per Spec 014 §Retry × :on-failure semantics.
        ;; Project each :rf.http/* category onto a UI-facing message.
        (some-> msg :rf/reply :kind (= :failure))
        (let [failure (:failure (:rf/reply msg))]
          {:db (assoc db :counter/status :error
                         :counter/error
                         (case (:kind failure)
                           :rf.http/transport      "Network unavailable."
                           :rf.http/cors           "Misconfigured CORS — check the server."
                           :rf.http/timeout        "Server took too long to respond."
                           :rf.http/http-4xx       "Client error — request rejected."
                           :rf.http/http-5xx       "Server error — try again later."
                           :rf.http/decode-failure "Bad response from server."
                           :rf.http/aborted        "Request cancelled."
                           "Something went wrong."))})

        ;; Initial branch — issue the request.
        :else
        {:db (assoc db :counter/status :loading :counter/error nil)
         :fx [[:rf.http/managed
               ;; `:decode :auto` is the simple, non-sensitive case — fine
               ;; for this public counter response. A real body that
               ;; carries secrets (tokens, credentials, session material)
               ;; or is large should use a `:decode` SCHEMA and classify
               ;; per-slot with `:sensitive?` / `:large?` Malli props: the
               ;; body is a transient payload owned by its request's
               ;; `:decode` schema (NOT the frame). An unschematized body
               ;; is whole-sensitive (fail-closed) — off-box traces/captures
               ;; omit it unless classified projection is requested. See
               ;; [Spec 015 §HTTP response bodies](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md#http-response-bodies).
               {:request {:method :get :url "/api/counter"}
                :decode  :auto
                :retry   {:on           #{:rf.http/transport
                                          :rf.http/http-5xx
                                          :rf.http/timeout}
                          :max-attempts 3
                          :backoff      [200 1000 3000]}}]]})))
