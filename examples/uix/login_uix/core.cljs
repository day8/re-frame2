(ns login-uix.core
  "UIx variant of the login example.

   Same dataflow, schemas, machine, and HTTP stub as examples/reagent/login,
   but views are written as UIx `defui` components and consume subs
   via the `use-subscribe` hook. Demonstrates that the Spec 005 state
   machine, Spec 010 schemas, and Spec 014 managed-HTTP surfaces are
   substrate-agnostic — only the view layer differs across substrates.

   Cross-substrate parity is exercised end-to-end: machine states carry
   Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`) and views read
   them via the `:rf/machine-has-tag?` framework sub — same tag taxonomy
   as the Reagent reference example, only the substrate's hook idiom
   differs.

   `reg-view` stays Reagent-only; UIx components are plain `defui`.
   There is no auto-injection."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http-managed]
            ;; :fx-overrides redirect :rf.http/managed to :rf.http/managed-canned-*;
            ;; the canned-stub fx ids register from re-frame.http-test-support.
            [re-frame.http-test-support]
            [re-frame.adapter.uix :as uix-adapter]))

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
(def AuthLoginEvent
  [:cat [:= :auth.login/flow]
   [:or
    [:cat [:= :auth.login/submit] Credentials]
    [:vector :any]]])

(def AuthLoginSnapshot
  [:map
   [:state [:enum :idle :submitting :error-shown :authed :locked-out]]
   [:data  [:map
            [:attempts {:default 0} :int]
            [:error    [:maybe :string]]]]])

(rf/reg-app-schema [:rf/runtime :machines :snapshots :auth.login/flow] AuthLoginSnapshot)

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

(rf/reg-event-fx :auth.login/flow
  {:doc    "Login flow: idle → submitting → authed / error-shown / locked-out."
   :schema AuthLoginEvent}
  (rf/make-machine-handler
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
      (fn [_]
        {:fx [[:rf.http/managed
               {:request {:method :post :url "/api/auth/lock"}}]]})

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
      {:meta {:terminal? true}}}}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The machine snapshot lives at [:rf/runtime :machines :snapshots :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" / "in :authed?" predicates moved to the
;; `:rf/machine-has-tag?` framework sub in views below (per Spec 005
;; §State tags).

(rf/reg-sub :auth.login/state
  (fn [db _]
    (get-in db [:rf/runtime :machines :snapshots :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  (fn [db _]
    (get-in db [:rf/runtime :machines :snapshots :auth.login/flow :data :error])))

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
                     (dispatch [:auth.login/flow
                                [:auth.login/submit {:email email
                                                     :password password}]]))}
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

(defui login-banner []
  (let [authed? (uix-adapter/use-subscribe [:rf/machine-has-tag?
                                            :auth.login/flow :auth/authenticated])]
    ($ :div.banner {:data-testid "login-banner"}
       (if authed?
         ($ :span "Welcome!")
         ($ login-form)))))

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
  ;; [:rf/runtime :machines :snapshots :auth.login/flow] when the flow first runs (per
  ;; Spec 005 §Restore semantics), so no separate :initialise is needed.
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    (uix-dom/render-root ($ root-view) @react-root)))
