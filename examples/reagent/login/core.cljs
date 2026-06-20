(ns login.core
  "Worked end-to-end example: a login feature using the current re-frame2 API.

   Demonstrates:
   - Feature scaffold (CP-6)              — the :auth.login/* registry slice
   - Schema attachment (CP-8)              — Malli schema for the machine snapshot
   - Event handlers (CP-1)                 — pure (state, event) → effects
   - Subscriptions (CP-2)                  — pure derivations off the machine snapshot
   - Managed HTTP (Spec 014)                — :rf.http/managed plus a per-app
                                                demo stub that resolves the
                                                request locally so the example
                                                runs without a backend.
   - Registered view (CP-4)                — Var reference (canonical), Form-1 only
   - State machine (CP-5)                  — login flow as a transition table
                                              read via [:rf.runtime/machines :snapshots :auth.login/flow]
   - State tags (Spec 005 §State tags)     — :auth/busy on :submitting,
                                              :auth/authenticated on :authed,
                                              :auth/locked on :locked-out.
                                              Views query them via
                                              `(rf/machine-has-tag? :auth.login/flow ...)`
                                              instead of boolean-discriminator
                                              subs. The terminal :locked-out
                                              state (reached after the fourth
                                              failed submit) is surfaced as a
                                              non-interactive locked-account
                                              panel rather than a dead-but-enabled
                                              form.
   - Open-map idiom                        — every shape on the wire is an open map

   Test-free per the examples policy (no inline test fn, no sibling
   `test/` tree): the login flow this file wires is a near-twin of the
   `:auth.login/flow` machine the sibling `state_machine_walkthrough`
   example exercises headlessly — the
   `state-machine-walkthrough-runs-headless` deftest in
   `implementation/core/test/re_frame/examples_test.clj` drives the
   happy-path / retry-then-lockout / pure machine-transition scenarios. The walkthrough
   registers its OWN parallel `:auth.login/flow` variant (same states,
   guards, actions; like this file it tags `:locked-out` with
   `:auth/locked` and renders a dedicated locked-account panel). That is a
   deliberate pedagogical variant, not the literal same registration:
   a machine id is a per-frame registry key, never a global handle, so
   two examples may name-share without colliding (and they never co-load
   — the walkthrough runs JVM-headless with `remove-ns` between runs;
   login builds standalone). Broader login contract coverage lives in
   the substrate contract suite (`npm run test:cljs`) and the framework
   gates (see `examples/README.md`).

   In a real codebase, this single file would be split per CP-6 conventions:
     login/schema.cljc | events.cljs | subs.cljs | views.cljs |
     machines.cljs | events_test.cljs

   Kept as a single file here for brevity."
  ;; Substrate note: this example runs on STOCK Reagent
  ;; (`reagent.dom.client` + the `re-frame.adapter.reagent` adapter), like
  ;; the rest of the `examples/reagent/` catalogue. login is the canonical
  ;; cross-substrate base — it is mirrored 1:1 as `login-uix` and
  ;; `login-helix` (Spec 006 §Adapter shipping convention Decision 7), so
  ;; keeping it on the reference substrate makes the three substrate
  ;; variants a clean apples-to-apples comparison. (`counter` /
  ;; `counter_slim_and_fast` are the dedicated stock-vs-slim contrast pair;
  ;; the slim build is the only one that mounts `reagent-slim`, and it
  ;; lives under `examples/reagent-slim/`.)
  (:require [reagent.dom.client :as rdc]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; The Spec 010 schema-attachment ns lives in
            ;; the day8/re-frame2-schemas artefact. The require here
            ;; loads the ns so its late-bind hooks register before
            ;; `(rf/reg-app-schema ...)` runs below.
            [re-frame.schemas]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine
            ;; (called below at ns-load) and the `:rf/machine` framework
            ;; sub resolve.
            [re-frame.machines]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Requiring re-frame.http.managed at app boot triggers its
            ;; load-time fx registrations (`:rf.http/managed` and
            ;; family); without it, dispatching `:rf.http/managed`
            ;; (used below) would fail with :rf.error/no-such-fx.
            [re-frame.http.managed]
            ;; This demo redirects :rf.http/managed to the canned
            ;; stubs via :fx-overrides (no real backend ships with the
            ;; example). The canned-stub fx ids
            ;; (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) register from
            ;; re-frame.http.test-support, not re-frame.http.managed —
            ;; the test-support require is the explicit opt-in.
            [re-frame.http.test-support]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SCHEMAS  (CP-8)
;; ============================================================================
;;
;; Open by default. The snapshot schema describes the shape of
;; [:rf.runtime/machines :snapshots :auth.login/flow] in runtime-db (NOT
;; app-db — per [005 §Where snapshots live]; see :136-138 below). It does not
;; carry :closed true — this isn't a system boundary.

;; The submit-event payload — the credentials map the view collects from
;; the form. Open by default; the regex/min-length checks describe the
;; shape the inner handler relies on.
;;
;; Frame-declared `:sensitive` / `:large {:app-db …}` paths (`reg-frame`)
;; are the sole app-db data-classification mechanism. `Credentials` is the
;; machine's EVENT-arg schema (it rides `AuthLoginEvent` via the machine's
;; event `:schema`); the password is never written to durable app-db or the
;; machine `:data` slot, so there is no app-db path to frame-declare for it.
;;
;; The password's real off-box egress path is the HTTP request body — redacted
;; by the per-request `:sensitive? true` flag on the managed-HTTP call in
;; `:issue-request` below (Spec 014 §Privacy, a different axis from app-db
;; classification). That carrier-level flag is the working, observable
;; redaction for this login flow.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Outer event-vector schema for the :auth.login/flow machine handler.
;; The login form is the canonical user-facing boundary, so we validate
;; the :submit payload STRICTLY against `Credentials`. Other sub-events
;; (:dismiss, :success, :failure) are dispatched internally by the
;; machine — their inner shape is framework-controlled, so we admit a
;; framework-controlled tail. Per Spec 010 §Validation order step 1: a
;; malformed event vector is rejected at the boundary; the handler is NOT
;; invoked (recovery: :no-recovery).
;;
;; The :submit branch is a `:tuple` (NOT `:cat`): the outer `:cat` consumes
;; the nested sub-event vector as a SINGLE element, so the branch must match
;; that one element AS a vector. A `:cat` branch would apply sequence-regex
;; semantics and — paired with a permissive `[:vector :any]` fallback —
;; silently re-admit a `:submit` whose `Credentials` failed (the original
;; bug: malformed submit payloads passed the `:where :event` boundary). With
;; the strict `:tuple` and no `[:vector :any]` escape hatch, a short-password
;; or bad-email submit is rejected at the boundary BEFORE the machine
;; transitions or issues the login HTTP effect.
;;
;; The trailing `[:? :any]` admits the managed-HTTP reply payload (Spec
;; 014 §Reply addressing): the framework appends `{:kind ... :value ...}`
;; / `{:kind ... :failure ...}` as the LAST arg of the explicit
;; `:on-success` / `:on-failure` event vector, so the delivered reply is
;; `[:auth.login/flow [:auth.login/success] <payload>]` — three top-level
;; elements. Without the optional trailing slot the `:cat` rejects every
;; reply with `:malli.core/input-remaining`, the boundary validation
;; fails BEFORE the machine handler runs, and the flow is stranded in
;; `:submitting`.
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:tuple [:= :auth.login/submit] Credentials]
    [:cat [:enum :auth.login/dismiss :auth.login/success :auth.login/failure]
     [:* :any]]]
   [:? :any]])

;; The login flow's runtime state lives in the machine snapshot at
;; [:rf.runtime/machines :snapshots :auth.login/flow] (runtime-db, NOT
;; app-db — per [005 §Where snapshots live]). Per Spec 010 §Machine data
;; schema + Spec 005 §Schema validation, a machine declares a top-level
;; `:data-schema` that validates the snapshot's `:data` SLOT only — not
;; the whole `{:state … :data …}` snapshot, and not an app-db path. So
;; this schema describes the `:data` map (`:attempts` + `:error`) the
;; machine seeds and the actions evolve; it is attached via the machine's
;; `:data-schema` slot on the `reg-machine` spec below and
;; validates at the `:where :machine-data` boundary.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; EP-0001: machine snapshots are runtime-db state, not app-db —
;; an `reg-app-schema` on a machine-snapshot path validates nothing (app
;; schemas validate the app-db partition only, Mike ruling #11). The
;; machine's own `:data-schema` (attached below) is the snapshot-validation
;; surface, so no app-schema reg applies to the login snapshot.

;; ============================================================================
;; FX  (Spec 014 + per-app demo stub)
;; ============================================================================
;;
;; HTTP requests go via the framework-shipped `:rf.http/managed` (Spec 014).
;; The example demo would normally hit `/api/login`, which we don't ship —
;; instead we register a per-app demo stub at `:auth.login.demo/managed-stub`
;; and override `:rf.http/managed` to it on the default frame in `run`. The
;; stub inspects the request body's `:password` and synthesises either a
;; success or failure reply via the framework-shipped canned-success /
;; canned-failure fxs (Spec 014 §Testing) so the canonical reply shape is
;; preserved end-to-end.
;;
;; `:auth.session/store` is client-only — localStorage is a browser API.

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Persist the session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "Demo override for `:rf.http/managed`: routes by URL +
               request body to canned login responses so the example
               runs standalone without a backend.

               POST /api/login with `:password good-password` → success
                 with `{:user {...} :token \"demo-token-123\"}`.
               POST /api/login otherwise → 401 failure.
               Anything else (e.g. /api/auth/lock) → empty success.

               Delegates straight to the framework-shipped canned-success
               / canned-failure fxs (Spec 014 §Testing) with `:after-ms`
               the framework defers the reply via
               `:dispatch-later` (50 ms) — observable in the tape,
               time-travel-safe, NOT raw `js/setTimeout`. The delay lets
               the `:submitting` UI state be observable; the reply shape
               (`{:kind :success :value ...}` / `{:kind :failure
               :failure ...}`) reaches the inner `:auth.login/success` /
               `:auth.login/failure` sub-events via the explicit
               `:on-success` / `:on-failure` form. The
               schedule-reply → `:dispatch-later` → deliver-reply chain
               is expressed as one `:after-ms` parameter (the delay is a
               parameter of the same canned effect, not a new fx)."
   :platforms #{:server :client}}
  (fn fx-managed-login-demo [frame-ctx args-map]
    (let [{:keys [url body]} (:request args-map)
          login? (= "/api/login" url)]
      (cond
        (and login? (= good-password (:password body)))
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map
                                 :after-ms 50
                                 :value {:user  {:id    (random-uuid)
                                                 :email (:email body)}
                                         :token "demo-token-123"})))

        login?
        (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
          (stub frame-ctx (assoc args-map
                                 :after-ms 50
                                 :kind :rf.http/http-4xx
                                 :tags {:status  401
                                        :message "Invalid credentials."})))

        :else
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :after-ms 50 :value {})))))))

;; ============================================================================
;; STATE MACHINE  (CP-5)
;; ============================================================================
;;
;; The login flow is a finite state machine. Five states, named events. All
;; non-trivial guards and actions live in the machine's :guards / :actions
;; maps and are referenced by keyword from the transition table; resolution
;; is machine-local (no global registry).

;; The login flow's machine spec.
(def auth-login-machine
  ;; Per Spec 005 §Where snapshots live: the spec map does NOT carry :id;
  ;; the machine's id is the surrounding registration id.
  {:initial :idle
   ;; Spec 010 §Machine data schema — `:data-schema` validates the
   ;; snapshot's `:data` slot (not the whole snapshot) at the
   ;; `:where :machine-data` boundary. The macrostep walker resolves it
   ;; via `(machine-meta :auth.login/flow)`; the `reg-machine` event-`:schema`
   ;; arity below stamps the machine metadata that makes it live. Malformed
   ;; `:data` (e.g. a non-string `:error`) fails the run with
   ;; `:rf.error/schema-validation-failure :where :machine-data`.
   :data-schema AuthLoginData
   :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      ;; True if the flow has had fewer than 3 prior failed attempts.
      (fn [{data :data}]
        (< (:attempts data) 3))}

     :actions
     {:clear-error
      ;; Reset error and prepare to submit.
      (fn [_]
        {:data {:error nil}})

      :issue-request
      ;; Issue the login HTTP request. Returns effects, not side-effects.
      ;; Spec 014 reply: explicit :on-success / :on-failure events have the
      ;; reply payload (`{:kind :success :value ...}` / `{:kind :failure
      ;; :failure ...}`) appended as their last arg by the runtime.
      (fn [{[_ creds] :event}]
        {:fx [[:rf.http/managed
               ;; EP-0015 / Spec 014 §Privacy: `:sensitive? true` on the request
               ;; redacts the request body (carrying the `:password`) and all
               ;; params from every `:rf.http/*` trace event — the password's
               ;; real off-box egress path. This is the observable EP-0015
               ;; redaction for the login flow (the credentials event-arg
               ;; `:sensitive?` slot classifies the shape; this scrubs the wire).
               {:request    {:method :post
                             :url    "/api/login"
                             :body   creds
                             :request-content-type :json
                             :sensitive? true}
                :decode     :json
                :on-success [:auth.login/flow [:auth.login/success]]
                :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

      :record-error
      ;; Record the failure into :data and bump the attempt counter.
      (fn [{data :data [_ {:keys [failure]}] :event}]
        {:data (-> data
                   (update :attempts inc)
                   (assoc :error (or (:message failure) "Login failed.")))})

      :lock-account
      ;; Mark the account as locked after too many failed attempts.
      ;; Fire-and-forget telemetry beacon (Spec 014 §Reply addressing
      ;; "Silenced"): the lockout POST wants no reply folded back into the
      ;; machine. `:on-success nil` / `:on-failure nil` silence both reply
      ;; branches explicitly — without them the omitted-target default
      ;; (co-located addressing) would dispatch
      ;; `[:auth.login/flow {:rf/reply ...}]`, a map in the sub-event slot
      ;; that `AuthLoginEvent` rejects, stranding noise after lockout.
      (fn [_]
        {:fx [[:rf.http/managed
               {:request    {:method :post :url "/api/auth/lock"}
                :on-success nil
                :on-failure nil}]]})

      :store-session
      ;; Persist the session token returned by a successful login.
      (fn [{[_ {:keys [value]}] :event}]
        {:fx [[:auth.session/store {:token (:token value)}]]})}

     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :action :clear-error}}}

      :submitting
      ;; :auth/busy tag — views query (rf/machine-has-tag? :auth.login/flow
      ;; :auth/busy) to disable inputs and re-label the submit button
      ;; while the request is in flight.
      {:tags  #{:auth/busy}
       :entry :issue-request
       :on    {:auth.login/success {:target :authed
                                    :action :store-session}
               :auth.login/failure [{:target :error-shown
                                     :guard  :under-retry-limit
                                     :action :record-error}
                                    {:target :locked-out
                                     :action :lock-account}]}}

      :error-shown
      ;; Direct retry from :error-shown re-enters :submitting and must clear the
      ;; prior `:error` first — otherwise the obsolete failure message stays
      ;; visible (the view renders `:auth.login/error` whenever non-nil) as if it
      ;; still applied to the request now in flight. Same `:clear-error` action
      ;; as the :idle entry transition.
      {:on {:auth.login/dismiss {:target :idle}
            :auth.login/submit  {:target :submitting
                                 :action :clear-error}}}

      :authed
      ;; :auth/authenticated tag — the banner swaps to "Welcome!" once
      ;; the flow reaches this terminal state.
      {:tags #{:auth/authenticated}
       :meta {:terminal? true}}

      :locked-out
      ;; :auth/locked tag — after the fourth failed submit the flow lands
      ;; in this terminal state. Views query
      ;; (rf/machine-has-tag? :auth.login/flow :auth/locked) to swap the
      ;; form for a locked-account panel and refuse further submits — same
      ;; tag + locked-panel pattern as the state-machines walkthrough. A
      ;; terminal lockout must be visible and non-interactive, not a live
      ;; form.
      {:tags #{:auth/locked}
       :meta {:terminal? true}}}})

;; Register the machine as the `:auth.login/flow` event handler.
;;
;; This machine ALSO validates its dispatched event VECTOR (against
;; `AuthLoginEvent`), so it uses `reg-machine`'s event-`:schema` arity:
;; the optional opts map carries the event `:schema` (the
;; `:where :event` boundary on the dispatched outer vector) alongside the
;; machine spec. `reg-machine` is the blessed registration home — it stamps
;; the `:rf/machine?` / `:rf/machine` metadata that `(machine-meta
;; :auth.login/flow)` reads, so the `:where :machine-data` walker resolves the
;; `:data-schema` and it VALIDATES. Durable machine `:data` egress
;; classification is frame-owned, like every other app-db path.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; EVENTS  (CP-1)
;; ============================================================================
;;
;; The machine handler (registered above as :auth.login/flow via reg-machine)
;; is self-initialising: its `:initial` state and
;; `:data` seed [:rf.runtime/machines :snapshots :auth.login/flow] when the machine first runs.
;; No separate :initialise event is required (per [005 §Restore semantics]).
;;
;; Sub-events route in via:
;;   (rf/dispatch [:auth.login/flow [:auth.login/submit creds]])

;; ============================================================================
;; SUBSCRIPTIONS  (CP-2)
;; ============================================================================

;; The machine snapshot lives at [:rf.runtime/machines :snapshots :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" and "in :authed?" predicates live as the
;; `rf/machine-has-tag?` queries in views below (per Spec 005 §State tags).

;; EP-0001: machine snapshots are durable runtime-db state — read
;; them through the framework `:rf/machine` sub (the public surface).
(rf/reg-sub :auth.login/state
  {:doc "Current state of the login flow."}
  :<- [:rf/machine :auth.login/flow]
  (fn sub-auth-login-state [snapshot _]
    (:state snapshot)))

(rf/reg-sub :auth.login/error
  {:doc "Current error message, if any."}
  :<- [:rf/machine :auth.login/flow]
  (fn sub-auth-login-error [snapshot _]
    (get-in snapshot [:data :error])))

;; ============================================================================
;; VIEWS  (CP-4)
;; ============================================================================
;;
;; Var-reference style (canonical per [004 §How registered views are used in
;; hiccup]). `reg-view` auto-injects `dispatch` / `subscribe` as lexical
;; bindings inside the view body — they are the ops of a frame-handle the
;; macro captures at render time, so they stay bound to the render-time
;; frame (no ambient lookup, survives async callbacks).

;; Form-2 view: the outer fn captures local component state in `state`
;; — a *reactive* `reagent.core/atom` kept across renders, the idiomatic
;; Reagent primitive for component-local render state (matching todomvc,
;; which reserves a bare `(atom ...)` only for non-render refs). The
;; inner fn is the actual render fn. dispatch / subscribe are
;; auto-injected and visible in both the outer and inner fn bodies.
(reg-view ^{:doc "The login form view: email + password + submit button + error display."}
          login-form []
  (let [state (reagent/atom {:email "" :password ""})]
    (fn []
      (let [busy? @(rf/machine-has-tag? :auth.login/flow :auth/busy)
            err   @(subscribe [:auth.login/error])]
        [:form.login-form
         {:data-testid "login-form"
          :on-submit (fn [e]
                       (.preventDefault e)
                       (when-not busy?
                         (dispatch [:auth.login/flow [:auth.login/submit @state]])))}
         [:input  {:type        "email"
                   :placeholder "Email"
                   :disabled    busy?
                   :data-testid "login-email"
                   :on-change   #(swap! state assoc :email (.. % -target -value))}]
         [:input  {:type        "password"
                   :placeholder "Password"
                   :disabled    busy?
                   :data-testid "login-password"
                   :on-change   #(swap! state assoc :password (.. % -target -value))}]
         [:button {:type "submit" :disabled busy?
                   :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in")]
         (when err [:p.error {:data-testid "login-error"} err])]))))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(reg-view ^{:doc "Locked-account panel shown when the login flow reaches :locked-out."}
          locked-panel []
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Three failed attempts. Contact support to unlock."]])

(reg-view ^{:doc "Shows the user's logged-in state and a sign-out button."}
          login-banner []
  (let [authed? @(rf/machine-has-tag? :auth.login/flow :auth/authenticated)
        locked? @(rf/machine-has-tag? :auth.login/flow :auth/locked)]
    [:div.banner {:data-testid "login-banner"}
     (cond
       authed? [:span "Welcome!"]
       locked? [locked-panel]
       :else   [login-form])]))

(reg-view root-view []
  [:div.app
   [:h1 "Sign in"]
   [login-banner]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  ;; Install the demo override so `:rf.http/managed` calls route to the
  ;; in-process login stub above. The example runs standalone — no
  ;; backend required.
  (rf/reg-frame :rf/default
    {:doc          "Login demo frame."
     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; EP-0002: wrap the render in a `frame-provider` so the
    ;; `reg-view`-injected `dispatch`/`subscribe` (and the login machine reads)
    ;; resolve to `:rf/default` via React context. With NO provider a `reg-view`
    ;; reads the no-provider sentinel and those calls raise
    ;; `:rf.error/no-frame-context` (there is no `:rf/default` floor).
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame :rf/default}
                 [root-view]])))
