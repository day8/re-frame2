(ns login-uix.core
  "UIx variant of the login example.

   Same dataflow, schemas, machine, and HTTP stub as examples/reagent/login,
   but views are written as UIx `defui` components and consume subs
   via the `use-subscribe` hook. Demonstrates that the Spec 005 state
   machine, Spec 010 schemas, and Spec 014 managed-HTTP surfaces are
   substrate-agnostic — only the view layer differs across substrates.

   Cross-substrate parity is exercised end-to-end: machine states carry
   Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`, `:auth/locked`)
   and views read them via the `:rf/machine-has-tag?` framework sub —
   same tag taxonomy as the state-machines walkthrough, only the
   substrate's hook idiom differs. The terminal `:locked-out` state is
   surfaced as a non-interactive locked-account panel.

   `reg-view` stays Reagent-only; UIx components are plain `defui`.
   There is no auto-injection."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            ;; :fx-overrides redirect :rf.http/managed to :rf.http/managed-canned-*;
            ;; the canned-stub fx ids register from re-frame.http.test-support.
            [re-frame.http.test-support]
            [re-frame.adapter.uix :as uix-adapter]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================

;; EP-0025 (rf2-398kql): schema-attached `:sensitive?` / `:large?` field
;; classification is REMOVED — frame-declared `:sensitive` / `:large {:app-db …}`
;; paths (`reg-frame`, EP-0015) are the SOLE app-db classification mechanism.
;; The password never lands in app-db (it rides the machine EVENT-arg schema and
;; the HTTP body), so there is no app-db path to frame-declare; the prior
;; `{:sensitive? true}` slot prop was a documentary no-op and is dropped. The
;; managed-HTTP request below still carries `:sensitive? true` to scrub the
;; password off the wire — a DIFFERENT axis (Spec 014 §Privacy, KEPT) and the
;; working, observable redaction. Parity across reagent/login + helix.
(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Outer event-vector schema for the :auth.login/flow machine handler —
;; see examples/reagent/login for the full rationale. The :submit
;; sub-event is validated STRICTLY against `Credentials`; framework-internal
;; sub-events (:dismiss, :success, :failure) admit a framework-controlled
;; tail.
;;
;; The :submit branch is a `:tuple` (NOT `:cat`): the outer `:cat` consumes
;; the nested sub-event vector as a SINGLE element, so the branch must match
;; that one element AS a vector. A `:cat` branch would apply sequence-regex
;; semantics and — paired with a permissive `[:vector :any]` fallback —
;; silently re-admit a `:submit` whose `Credentials` failed (the original
;; bug: malformed submit payloads passed the `:where :event` boundary). With
;; the strict `:tuple` and no `[:vector :any]` escape hatch, a short-password
;; or bad-email submit is rejected at the boundary BEFORE the machine
;; transitions or issues the login HTTP effect (Spec 010 §Validation order
;; step 1, recovery `:no-recovery`).
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

;; The machine's `:data-schema` validates the machine's `:data` slot ONLY —
;; the user-domain extended state `{:attempts ... :error ...}` — NOT the whole
;; `{:state ... :data ...}` snapshot. Per Spec 005 §Schema validation
;; (005-StateMachines.md:182, :200, :429-435) and Spec 010
;; (010-Schemas.md:52, :162, :178): `:data-schema` sits as a top-level key on
;; the machine spec map beside `:data`, and the framework validates it at every
;; macrostep-commit boundary + at bootstrap, emitting
;; `:rf.error/schema-validation-failure :where :machine-data` and rolling back
;; the cascade on a violation. The snapshot's `:state` slot is validated
;; structurally at registration time (an unknown target fails registration),
;; so it is NOT this schema's job — describing the whole snapshot here would
;; be the wrong shape for the slot the framework actually validates.
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Machine snapshots are runtime-db state, not app-db — a `reg-app-schema` on a
;; machine-snapshot path validates nothing (app schemas validate the app-db
;; partition only). The machine's own `:data-schema` (attached to the spec map
;; below) is the live validation surface for `:data`, so no app-schema reg is
;; needed.

;; ============================================================================
;; FX
;; ============================================================================

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Persist session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :auth.login.demo/managed-stub
  {:doc       "Demo override for `:rf.http/managed`. Identical behaviour
               to the Reagent and Helix examples' stub — delegates straight
               to the framework-shipped canned-success / canned-failure
               fxs with `:after-ms`, so the framework defers the reply via
               `:dispatch-later` (50 ms) — observable in the tape,
               time-travel-safe, NOT raw `js/setTimeout`. The `:after-ms`
               parameter carries the schedule-reply → `:dispatch-later` →
               deliver-reply chain as one arg on the canned effect."
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
;; STATE MACHINE
;; ============================================================================

;; The login flow's machine spec. `:data-schema` is a TOP-LEVEL key on the
;; spec map (Spec 005 §Schema validation) — it validates the `:data` slot
;; (`{:attempts ... :error ...}`), NOT the whole snapshot.
(def auth-login-machine
  {:initial     :idle
   :data        {:attempts 0 :error nil}
   :data-schema AuthLoginData

   :guards
   {:under-retry-limit
    (fn [{data :data}] (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             ;; EP-0015 / Spec 014 §Privacy: `:sensitive? true` redacts the
             ;; request body (carrying the `:password`) from every `:rf.http/*`
             ;; trace event — the observable EP-0015 redaction (see
             ;; examples/reagent/login for the full rationale).
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json
                           :sensitive? true}
              :decode     :json
              :on-success [:auth.login/flow [:auth.login/success]]
              :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc :error (or (:message failure) "Login failed.")))})

    :lock-account
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
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :action :clear-error}}}

    :submitting
    ;; :auth/busy tag — views query
    ;; [:rf/machine-has-tag? :auth.login/flow :auth/busy] to disable
    ;; inputs and re-label the submit button while the request is in
    ;; flight.
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
    ;; still applied to the request now in flight. Same `:clear-error` action as
    ;; the :idle entry transition.
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
    ;; [:rf/machine-has-tag? :auth.login/flow :auth/locked] to swap the
    ;; form for a locked-account panel and refuse further submits — same
    ;; tag + locked-panel pattern as the state-machines walkthrough. A
    ;; terminal lockout must be visible and non-interactive, not a live
    ;; form.
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; A machine that ALSO validates its outer event vector. The canonical
;; surface for registering a machine is `reg-machine` / `reg-machine*`
;; (Spec 005 §reg-machine — the form tools, examples, and scaffolds default
;; to). This login flow needs BOTH a live machine `:data-schema` AND an
;; event-vector `:schema` (`AuthLoginEvent`, the `:where :event` boundary on
;; the dispatched vector) — the machine + event-vector-schema shape — so it
;; uses `reg-machine`'s event-`:schema` arity: the optional opts map carries
;; the event `:schema` alongside the machine spec.
;;
;; `reg-machine` is the single registration home: it stamps the `:rf/machine?`
;; / `:rf/machine` metadata that `(machine-meta :auth.login/flow)` reads (so
;; the `:where :machine-data` walker resolves the `:data-schema` and it
;; VALIDATES) AND bridges the schema's `:sensitive?` / `:large?` slots into
;; snapshot-egress redaction — both in one place, regardless of registration
;; path.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  auth-login-machine)

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The machine snapshot lives at [:rf.runtime/machines :snapshots :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" / "in :authed?" predicates moved to the
;; `:rf/machine-has-tag?` framework sub in views below (per Spec 005
;; §State tags).

;; Machine snapshots are durable runtime-db state — read them through the
;; framework `:rf/machine` sub.
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (:state snapshot)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [snapshot _] (get-in snapshot [:data :error])))

;; ============================================================================
;; VIEWS  (UIx — defui + use-subscribe)
;; ============================================================================

(defui login-form []
  (let [busy?    (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                             :auth.login/flow :auth/busy])
        err      (uix-adapter/use-subscribe [:auth.login/error])
        dispatch (:dispatch (rf/frame-handle))
        [email    set-email!]    (uix/use-state "")
        [password set-password!] (uix/use-state "")]
    ($ :form.login-form
       {:data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (when-not busy?
                       (dispatch [:auth.login/flow
                                  [:auth.login/submit {:email email
                                                       :password password}]])))}
       ($ :input  {:type        "email"
                   :placeholder "Email"
                   :disabled    busy?
                   :data-testid "login-email"
                   :on-change   #(set-email! (.. % -target -value))})
       ($ :input  {:type        "password"
                   :placeholder "Password"
                   :disabled    busy?
                   :data-testid "login-password"
                   :on-change   #(set-password! (.. % -target -value))})
       ($ :button {:type "submit" :disabled busy?
                   :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err ($ :p.error {:data-testid "login-error"} err)))))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(defui locked-panel []
  ($ :div.locked {:data-testid "locked-panel"}
     ($ :h2 "Account locked")
     ($ :p "Three failed attempts. Contact support to unlock.")))

(defui login-banner []
  (let [authed? (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                            :auth.login/flow :auth/authenticated])
        locked? (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                            :auth.login/flow :auth/locked])]
    ($ :div.banner {:data-testid "login-banner"}
       (cond
         authed? ($ :span "Welcome!")
         locked? ($ locked-panel)
         :else   ($ login-form)))))

(defui root-view []
  ($ :div.app
     ($ :h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! uix-adapter/adapter)
  (rf/reg-frame :rf/default
    {:doc          "Login (UIx) demo frame."
     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})
  ;; No `dispatch-sync` seed here (unlike counter / dashboard): the
  ;; machine handler is self-initialising — its `:initial`/`:data` seed
  ;; [:rf.runtime/machines :snapshots :auth.login/flow] when the flow first runs (per
  ;; Spec 005 §Restore semantics), so no separate :initialise is needed.
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    ;; Wrap the render in the UIx `frame-provider` so the `use-subscribe` hook +
    ;; the render-time `(rf/frame-handle)` capture in `login-form` resolve to
    ;; `:rf/default` via React context. With NO provider the tree observes the
    ;; no-provider sentinel and those reads raise `:rf.error/no-frame-context`
    ;; (there is no `:rf/default` floor).
    (uix-dom/render-root
      ($ uix-adapter/frame-provider-existing {:frame :rf/default}
         ($ root-view))
      @react-root)))
