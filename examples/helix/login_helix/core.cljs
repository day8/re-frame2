(ns login-helix.core
  "Helix variant of the login example.

   Same dataflow, schemas, machine, and HTTP stub as
   examples/reagent/login and examples/uix/login_uix, but views are
   written as Helix `defnc` components and consume subs via the
   `use-subscribe` hook. Demonstrates that the Spec 005 state machine,
   Spec 010 schemas, and Spec 014 managed-HTTP surfaces are
   substrate-agnostic — only the view layer differs across substrates.

   Cross-substrate parity is exercised end-to-end: machine states carry
   Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`, `:auth/locked`)
   and views read them via the `:rf/machine-has-tag?` framework sub —
   same tag taxonomy as the state-machines walkthrough, only the
   substrate's hook idiom differs. The terminal `:locked-out` state is
   surfaced as a non-interactive locked-account panel.

   `reg-view` stays Reagent-only; Helix components are plain `defnc`.
   There is no auto-injection."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [helix.hooks        :as helix-hooks]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            ;; :fx-overrides redirect :rf.http/managed to :rf.http/managed-canned-*;
            ;; the canned-stub fx ids register from re-frame.http.test-support.
            [re-frame.http.test-support]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SUBSTRATE-AGNOSTIC ARTEFACT LAYER  (schemas + fx + machine + subs)
;; ============================================================================
;;
;; Everything from here down to the SUBSTRATE BOUNDARY divider — schemas, the
;; managed-HTTP stub fx, the `:auth.login/flow` state machine, and the named
;; subs — is the artefact layer. It is in semantic + id parity across the
;; Reagent, UIx, and Helix login examples: the same `:auth.login/*` ids, the
;; same machine spec (a named `auth-login-machine` def passed to `reg-machine`
;; in all three), the same schemas, and the same `:auth.login.demo/managed-stub`
;; — the registered shapes are identical; only the per-file explanatory
;; comments differ. That sameness is deliberate and load-bearing — the
;; id-identity *is* the cross-substrate parity demonstration
;; (examples/TESTING.md §Exception 2). It is NOT
;; extracted into a shared namespace on purpose: each substrate example is a
;; self-contained `:browser` build, and `npm run test:bundle-isolation` proves
;; a Helix bundle carries no Reagent/UIx code (and vice versa). A shared model
;; required into all three builds would defeat that isolation and the parity
;; claim it underwrites. The boundary to learn here is the SUBSTRATE BOUNDARY
;; below — one dataflow, three view layers — not a file-extraction boundary.

;; ============================================================================
;; SCHEMAS
;; ============================================================================

;; The EP-0025 commit-plane `:sensitive` / `:large` classification effects (a
;; `reg-event` returns them alongside `:db`) are the durable app-db
;; classification mechanism. The password never lands in app-db (it rides the
;; machine EVENT-arg schema and the HTTP body), so there is no app-db path to
;; classify. The managed-HTTP request below
;; carries `:sensitive? true` to scrub the password off the wire — a
;; different axis (Spec 014 §Privacy) and the working, observable
;; redaction. Parity across reagent/login + uix.
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

;; The machine's `[:schemas :data]` validates the machine's `:data` slot ONLY —
;; the user-domain extended state `{:attempts ... :error ...}` — NOT the whole
;; `{:state ... :data ...}` snapshot. Per Spec 005 §Schema validation and Spec
;; 010 §Machine data schema, `[:schemas :data]` sits as a top-level key on the
;; machine spec beside `:data`, and the framework validates it at every
;; macrostep-commit boundary + at bootstrap (`:where :machine-data`). The same
;; `[:schemas :data]` shape as examples/reagent/login + examples/uix/login_uix —
;; the artefact layer is in semantic + id parity across the three substrates
;; (see the divider above).
(def AuthLoginData
  [:map
   [:attempts {:default 0} :int]
   [:error    [:maybe :string]]])

;; Machine snapshots are runtime-db state, not app-db — a `reg-app-schema` on a
;; machine-snapshot path validates nothing (app schemas validate the app-db
;; partition only). The machine's own `[:schemas :data]` (attached below) is the
;; snapshot-validation surface, so no app-schema reg applies to the login
;; snapshot.

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
               to the Reagent and UIx examples' stub — delegates straight
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

;; The login flow's machine spec. `[:schemas :data]` is a TOP-LEVEL key on the
;; spec map (Spec 005 §Schema validation) — it validates the `:data` slot
;; (`{:attempts ... :error ...}`), NOT the whole snapshot. Factored into a
;; named `def` (then passed to `reg-machine` below) so the machine spec sits
;; in the same artefact-layer shape as the Reagent and UIx login siblings —
;; same registry ids, same machine spec, same schemas + HTTP stub.
(def auth-login-machine
  {:initial :idle
   ;; Spec 005 §Schema validation — `[:schemas :data]` validates the snapshot's
   ;; `:data` slot (not the whole snapshot) at the `:where :machine-data`
   ;; boundary. This `:data` (`{:attempts :error}`) holds no secret, so the
   ;; machine declares no `:sensitive` / `:large`. (EP-0025: durable machine
   ;; `:data` snapshot redaction is a projection-relative `:sensitive` / `:large`
   ;; declaration on the `reg-machine` spec — NOT a `[:schemas :data]` prop, which
   ;; now drives only validation-failure-trace redaction.) Parity with
   ;; reagent/login + uix.
   :schemas {:data AuthLoginData}
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit
    (fn [{data :data}] (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :issue-request
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             ;; Spec 014 §Privacy (per-request `:sensitive?`): `:sensitive? true`
             ;; redacts the request body (carrying the `:password`) from every
             ;; `:rf.http/*` trace event — the coarse per-request wire scrub, a
             ;; distinct surface from EP-0025 path classification (see
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

;; The machine + event-vector-schema shape: `reg-machine`'s event-`:schema`
;; arity carries the event `:schema` (the `:where :event` boundary on the
;; dispatched outer vector) alongside the machine spec. `reg-machine` is the
;; single registration home — it stamps the `:rf/machine?` / `:rf/machine`
;; metadata and (for a machine carrying a `[:schemas :data]`) bridges its redaction
;; marks. The machine spec is the named `auth-login-machine` def above — the
;; same def-then-register shape the Reagent and UIx login siblings use.
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
;; "in :submitting?" / "in :authed?" predicates live as the
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
;; ──────────────────────────  SUBSTRATE BOUNDARY  ──────────────────────────
;; ============================================================================
;;
;; Below this line is the only substrate-specific code in this example: the
;; Helix views + the mount. The Reagent and UIx login examples share every
;; line ABOVE this divider and differ only in what sits BELOW it (Reagent
;; `reg-view`, UIx `defui` + `use-subscribe`, Helix `defnc` + `use-subscribe`).

;; ============================================================================
;; VIEWS  (Helix — defnc + use-subscribe)
;; ============================================================================
;;
;; The Helix view idiom, and the only place this example differs from the
;; Reagent reference: a view is a plain `defnc`; it reads a subscription
;; through the adapter's `use-subscribe` hook; and it takes `dispatch` off a
;; `frame-handle` rather than having it lexically injected (`reg-view` is
;; Reagent-only — there is no auto-injection here). `:rf/machine-has-tag?`
;; reads are *ask, don't tell* state-tag queries; `:auth.login/error` is a
;; named sub. To the call site they're identical — both are just subscriptions.

(defnc login-form []
  (let [busy?    (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                               :auth.login/flow :auth/busy])
        err      (helix-adapter/use-subscribe [:auth.login/error])
        ;; No global dispatch in re-frame2 — every dispatch goes to a frame.
        ;; Pull this frame's `dispatch` off the render-time frame-handle; it
        ;; resolves to `:rf/default` via the provider in `run`.
        dispatch (:dispatch (rf/frame-handle))
        ;; Transient pre-submit input lives in component-local use-state — it
        ;; isn't application state, it's keystrokes in flight. It crosses into
        ;; the machine (and gets validated) only at the :on-submit dispatch.
        [email    set-email!]    (helix-hooks/use-state "")
        [password set-password!] (helix-hooks/use-state "")]
    (d/form
       {:class "login-form"
        :data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (when-not busy?
                       (dispatch [:auth.login/flow
                                  [:auth.login/submit {:email email
                                                       :password password}]])))}
       (d/input  {:type        "email"
                  :placeholder "Email"
                  :disabled    busy?
                  :data-testid "login-email"
                  :on-change   #(set-email! (.. % -target -value))})
       (d/input  {:type        "password"
                  :placeholder "Password"
                  :disabled    busy?
                  :data-testid "login-password"
                  :on-change   #(set-password! (.. % -target -value))})
       (d/button {:type "submit" :disabled busy?
                  :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err (d/p {:class "error" :data-testid "login-error"} err)))))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(defnc locked-panel []
  (d/div
     {:class "locked"
      :data-testid "locked-panel"}
     (d/h2 "Account locked")
     (d/p "Three failed attempts. Contact support to unlock.")))

(defnc login-banner []
  (let [authed? (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                              :auth.login/flow :auth/authenticated])
        locked? (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                              :auth.login/flow :auth/locked])]
    (d/div
       {:class "banner"
        :data-testid "login-banner"}
       (cond
         authed? (d/span "Welcome!")
         locked? ($ locked-panel)
         :else   ($ login-form)))))

(defnc root-view []
  (d/div
     {:class "app"}
     (d/h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `createRoot` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! helix-adapter/adapter)
  (rf/reg-frame :rf/default
    {:doc          "Login (Helix) demo frame."
     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})
  ;; No `dispatch-sync` seed here (unlike counter / dashboard): the
  ;; machine handler is self-initialising — its `:initial`/`:data` seed
  ;; [:rf.runtime/machines :snapshots :auth.login/flow] when the flow first runs (per
  ;; Spec 005 §Restore semantics), so no separate :initialise is needed.
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; Wrap the render in the Helix `frame-provider` so the `use-subscribe` hook
    ;; + the render-time `(rf/frame-handle)` capture in `login-form` resolve to
    ;; `:rf/default` via React context. With NO provider the tree observes the
    ;; no-provider sentinel and those reads raise `:rf.error/no-frame-context`
    ;; (there is no `:rf/default` floor).
    (.render @react-root
             ($ helix-adapter/frame-provider-existing {:frame :rf/default}
                ($ root-view)))))
