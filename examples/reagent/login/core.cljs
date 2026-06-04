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
                                              read via [:rf/runtime :machines :snapshots :auth.login/flow]
   - State tags (Spec 005 §State tags)     — :auth/busy on :submitting,
                                              :auth/authenticated on :authed.
                                              Views query them via
                                              `(rf/machine-has-tag? :auth.login/flow ...)`
                                              instead of boolean-discriminator
                                              subs.
   - Open-map idiom                        — every shape on the wire is an open map

   Test-free per the examples policy (no inline test fn, no sibling
   `test/` tree): the login flow this file wires is a near-twin of the
   `:auth.login/flow` machine the sibling `state_machine_walkthrough`
   example exercises headlessly — the
   `state-machine-walkthrough-runs-headless` deftest in
   `implementation/core/test/re_frame/examples_test.clj` drives the
   happy-path / retry-then-lockout / pure machine-transition scenarios. The walkthrough
   registers its OWN parallel `:auth.login/flow` variant (same states,
   guards, actions; it adds an `:auth/locked` tag on `:locked-out`
   because its root-view renders a dedicated lockout panel — this file
   omits that tag since its view never branches on lockout). That is a
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
  ;; Substrate note: this example stays on STOCK Reagent
  ;; (`reagent.dom.client` + the `re-frame.adapter.reagent` adapter) rather
  ;; than reagent-slim. login is the canonical cross-substrate base — it is
  ;; mirrored 1:1 as `login-uix` and `login-helix` (Spec 006 §Adapter
  ;; shipping convention Decision 7), so keeping it on the reference
  ;; substrate makes the three substrate variants a clean apples-to-apples
  ;; comparison. (`counter` / `counter_slim_and_fast` are the dedicated
  ;; stock-vs-slim contrast pair; the rest of the catalogue defaults to
  ;; slim.)
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
            ;; registers its late-bind hooks so rf/make-machine-handler
            ;; (called below at ns-load) and the `:rf/machine` framework
            ;; sub resolve.
            [re-frame.machines]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Requiring re-frame.http-managed at app boot triggers its
            ;; load-time fx registrations (`:rf.http/managed` and
            ;; family); without it, dispatching `:rf.http/managed`
            ;; (used below) would fail with :rf.error/no-such-fx.
            [re-frame.http-managed]
            ;; This demo redirects :rf.http/managed to the canned
            ;; stubs via :fx-overrides (no real backend ships with the
            ;; example). The canned-stub fx ids
            ;; (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) register from
            ;; re-frame.http-test-support, not re-frame.http-managed —
            ;; the test-support require is the explicit opt-in.
            [re-frame.http-test-support]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SCHEMAS  (CP-8)
;; ============================================================================
;;
;; Open by default. The snapshot schema describes the shape of
;; [:rf/runtime :machines :snapshots :auth.login/flow] in app-db. It does not carry :closed
;; true — this isn't a system boundary.

;; The submit-event payload — the credentials map the view collects from
;; the form. Open by default; the regex/min-length checks describe the
;; shape the inner handler relies on.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Outer event-vector schema for the :auth.login/flow machine handler.
;; The login form is the canonical user-facing boundary, so we validate
;; the :submit payload against `Credentials`. Other sub-events
;; (:dismiss, :success, :failure) are dispatched internally by the
;; machine — their inner shape is framework-controlled, so we admit
;; them as :any. Per Spec 010 §Validation order step 1: a malformed
;; event vector is rejected at the boundary; the handler is NOT
;; invoked (recovery: :no-recovery).
;;
;; The trailing `[:? :any]` admits the managed-HTTP reply payload (Spec
;; 014 §Reply addressing): the framework appends `{:kind ... :value ...}`
;; / `{:kind ... :failure ...}` as the LAST arg of the explicit
;; `:on-success` / `:on-failure` event vector, so the delivered reply is
;; `[:auth.login/flow [:auth.login/success] <payload>]` — three top-level
;; elements. Without the optional trailing slot the `:cat` rejects every
;; reply with `:malli.core/input-remaining`, the boundary validation
;; fails BEFORE the machine handler runs, and the flow is stranded in
;; `:submitting` (rf2-1gz14).
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:cat [:= :auth.login/submit] Credentials]
    [:vector :any]]
   [:? :any]])

;; The login flow's runtime state lives in the machine snapshot at
;; [:rf/runtime :machines :snapshots :auth.login/flow] (per [005 §Where snapshots live]).
;; The snapshot shape is {:state <kw> :data <map>} per Spec 005.
(def AuthLoginSnapshot
  [:map
   [:state [:enum :idle :submitting :error-shown :authed :locked-out]]
   [:data  [:map
            [:attempts {:default 0} :int]
            [:error    [:maybe :string]]]]])

(rf/reg-app-schema [:rf/runtime :machines :snapshots :auth.login/flow] AuthLoginSnapshot)

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
               (rf2-j1mo4): the framework defers the reply via
               `:dispatch-later` (50 ms) — observable in the tape,
               time-travel-safe, NOT raw `js/setTimeout`. The delay lets
               the `:submitting` UI state be observable; the reply shape
               (`{:kind :success :value ...}` / `{:kind :failure
               :failure ...}`) reaches the inner `:auth.login/success` /
               `:auth.login/failure` sub-events via the explicit
               `:on-success` / `:on-failure` form. Collapses the former
               schedule-reply → `:dispatch-later` → deliver-reply chain
               into one `:after-ms` parameter (the delay is a parameter
               of the same canned effect, not a new fx)."
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

(rf/reg-event-fx :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  (rf/make-machine-handler
    ;; Per Spec 005 §Where snapshots live: the spec map does NOT carry
    ;; :id; the machine's id is the surrounding reg-event-fx id.
    {:initial :idle
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
               {:request    {:method :post
                             :url    "/api/login"
                             :body   creds
                             :request-content-type :json}
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
      (fn [_]
        {:fx [[:rf.http/managed
               {:request {:method :post :url "/api/auth/lock"}}]]})

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
      {:on {:auth.login/dismiss {:target :idle}
            :auth.login/submit  {:target :submitting}}}

      :authed
      ;; :auth/authenticated tag — the banner swaps to "Welcome!" once
      ;; the flow reaches this terminal state.
      {:tags #{:auth/authenticated}
       :meta {:terminal? true}}

      :locked-out
      {:meta {:terminal? true}}}}))

;; ============================================================================
;; EVENTS  (CP-1)
;; ============================================================================
;;
;; The machine handler (registered above as :auth.login/flow via reg-event-fx
;; + make-machine-handler) is self-initialising: its `:initial` state and
;; `:data` seed [:rf/runtime :machines :snapshots :auth.login/flow] when the machine first runs.
;; No separate :initialise event is required (per [005 §Restore semantics]).
;;
;; Sub-events route in via:
;;   (rf/dispatch [:auth.login/flow [:auth.login/submit creds]])

;; ============================================================================
;; SUBSCRIPTIONS  (CP-2)
;; ============================================================================

;; The machine snapshot lives at [:rf/runtime :machines :snapshots :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" and "in :authed?" predicates moved to the
;; `rf/machine-has-tag?` queries in views below (per Spec 005 §State tags).

(rf/reg-sub :auth.login/state
  {:doc "Current state of the login flow."}
  (fn sub-auth-login-state [db _]
    (get-in db [:rf/runtime :machines :snapshots :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  {:doc "Current error message, if any."}
  (fn sub-auth-login-error [db _]
    (get-in db [:rf/runtime :machines :snapshots :auth.login/flow :data :error])))

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
                       (dispatch [:auth.login/flow [:auth.login/submit @state]]))}
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

(reg-view ^{:doc "Shows the user's logged-in state and a sign-out button."}
          login-banner []
  (let [authed? @(rf/machine-has-tag? :auth.login/flow :auth/authenticated)]
    [:div.banner {:data-testid "login-banner"}
     (if authed?
       [:span "Welcome!"]
       [login-form])]))

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
    (rdc/render @react-root [root-view])))
