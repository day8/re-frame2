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
   surfaced as a non-interactive locked-account panel (rf2-q6bm7d).

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
            [re-frame.http-managed]
            ;; :fx-overrides redirect :rf.http/managed to :rf.http/managed-canned-*;
            ;; the canned-stub fx ids register from re-frame.http-test-support.
            [re-frame.http-test-support]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SUBSTRATE-AGNOSTIC ARTEFACT LAYER  (schemas + fx + machine + subs)
;; ============================================================================
;;
;; Everything from here down to the SUBSTRATE BOUNDARY divider — schemas, the
;; managed-HTTP stub fx, the `:auth.login/flow` state machine, and the named
;; subs — is the artefact layer. It is byte-for-byte IDENTICAL across the
;; Reagent, UIx, and Helix login examples: same `:auth.login/*` ids, same
;; machine spec, same `:auth.login.demo/managed-stub`. That sameness is
;; deliberate and load-bearing — the id-identity *is* the cross-substrate
;; parity demonstration (examples/TESTING.md §Exception 2). It is NOT
;; extracted into a shared namespace on purpose: each substrate example is a
;; self-contained `:browser` build, and `npm run test:bundle-isolation` proves
;; a Helix bundle carries no Reagent/UIx code (and vice versa). A shared model
;; required into all three builds would defeat that isolation and the parity
;; claim it underwrites. The boundary to learn here is the SUBSTRATE BOUNDARY
;; below — one dataflow, three view layers — not a file-extraction boundary.

;; ============================================================================
;; SCHEMAS
;; ============================================================================

(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; Outer event-vector schema for the :auth.login/flow machine handler —
;; see examples/reagent/login for the full rationale. The :submit
;; sub-event is validated against `Credentials`; framework-internal
;; sub-events (:dismiss, :success, :failure) admit :any.
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

(def AuthLoginSnapshot
  [:map
   [:state [:enum :idle :submitting :error-shown :authed :locked-out]]
   [:data  [:map
            [:attempts {:default 0} :int]
            [:error    [:maybe :string]]]]])

;; EP-0001 (rf2-vzld77): machine snapshots are runtime-db state, not app-db —
;; an `reg-app-schema` on a machine-snapshot path validates nothing (app
;; schemas validate the app-db partition only, Mike ruling #11). The
;; machine's own `:data-schema` is the snapshot-validation surface, so the
;; vestigial app-schema reg is removed.

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
               fxs with `:after-ms` (rf2-j1mo4), so the framework defers
               the reply via `:dispatch-later` (50 ms) — observable in
               the tape, time-travel-safe, NOT raw `js/setTimeout`. The
               `:after-ms` parameter collapses the former schedule-reply →
               `:dispatch-later` → deliver-reply chain into one arg on the
               same canned effect."
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

;; rf2-wgmipl — the machine + event-vector-schema shape: `reg-machine`'s
;; event-`:schema` arity carries the event `:schema` (the `:where :event`
;; boundary on the dispatched outer vector) alongside the machine spec.
;; `reg-machine` is the single registration home — it stamps the
;; `:rf/machine?` / `:rf/machine` metadata and (for a machine carrying a
;; `:data-schema`) bridges its redaction marks, replacing the former
;; hand-composed `reg-event-fx` + `make-machine-handler` form.
(rf/reg-machine :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  {:initial :idle
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
             {:request    {:method :post
                           :url    "/api/login"
                           :body   creds
                           :request-content-type :json}
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
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

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
    ;; form (rf2-q6bm7d).
    {:tags #{:auth/locked}
     :meta {:terminal? true}}}})

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The machine snapshot lives at [:rf.runtime/machines :snapshots :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" / "in :authed?" predicates moved to the
;; `:rf/machine-has-tag?` framework sub in views below (per Spec 005
;; §State tags).

;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state — read
;; them through the framework `:rf/machine` sub.
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

(defnc login-form []
  (let [busy?    (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                               :auth.login/flow :auth/busy])
        err      (helix-adapter/use-subscribe [:auth.login/error])
        dispatch (:dispatch (rf/frame-handle))
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
    ;; EP-0002 (rf2-9o48ih): wrap the render in the Helix `frame-provider` so the
    ;; `use-subscribe` hook + the render-time `(rf/frame-handle)` capture in
    ;; `login-form` resolve to `:rf/default` via React context. With NO provider
    ;; the tree observes the no-provider sentinel and those reads raise
    ;; `:rf.error/no-frame-context` (there is no `:rf/default` floor).
    (.render @react-root
             ($ helix-adapter/frame-provider {:frame :rf/default}
                ($ root-view)))))
