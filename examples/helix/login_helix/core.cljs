(ns login-helix.core
  "Helix variant of the login example.

   Same dataflow, schemas, machine, and HTTP stub as
   examples/reagent/login and examples/uix/login_uix, but views are
   written as Helix `defnc` components and consume subs via the
   `use-subscribe` hook. Demonstrates that the Spec 005 state machine,
   Spec 010 schemas, and Spec 014 managed-HTTP surfaces are
   substrate-agnostic — only the view layer differs across substrates.

   Cross-substrate parity is exercised end-to-end: machine states carry
   Spec 005 `:tags` (`:auth/busy`, `:auth/authenticated`) and views read
   them via the `:rf/machine-has-tag?` framework sub — same tag taxonomy
   as the Reagent reference example, only the substrate's hook idiom
   differs.

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
            ;; rf2-cdmle — :fx-overrides into :rf.http/managed-canned-*;
            ;; the canned-stub fx ids register from re-frame.http-test-support.
            [re-frame.http-test-support]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SCHEMAS
;; ============================================================================

(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(def AuthLoginSnapshot
  [:map
   [:state [:enum :idle :submitting :error-shown :authed :locked-out]]
   [:data  [:map
            [:attempts {:default 0} :int]
            [:error    [:maybe :string]]]]])

(rf/reg-app-schema [:rf/machines :auth.login/flow] AuthLoginSnapshot)

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

(rf/reg-fx :rf.http/managed.login-demo
  {:doc       "Demo override for `:rf.http/managed`. Identical behaviour
               to the Reagent and UIx examples' stub.

               NOTE on the raw js/setTimeout below. The deferred work
               is an fx invocation (the canned-success / canned-failure
               stub), not a dispatch, so the framework's
               `:dispatch-later` path is not a 1:1 swap. The timer is
               purely demo-stub latency so the `:submitting` UI state
               is observable. Production app code should never use raw
               `js/setTimeout` — use `:dispatch-later` so framework
               time controls (Tool-Pair time-travel,
               `:dispatch-later` nil-override) still apply."
   :platforms #{:server :client}}
  (fn fx-managed-login-demo [frame-ctx args-map]
    (let [{:keys [url body]} (:request args-map)
          login?    (= "/api/login" url)
          success?  (and login? (= good-password (:password body)))
          ok-stub   (registrar/handler :fx :rf.http/managed-canned-success)
          fail-stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      ;; Demo-only artificial latency — see the fx doc above.
      (js/setTimeout
        (fn []
          (cond
            success?
            (ok-stub frame-ctx (assoc args-map
                                      :value {:user  {:id    (random-uuid)
                                                      :email (:email body)}
                                              :token "demo-token-123"}))

            login?
            (fail-stub frame-ctx (assoc args-map
                                        :kind :rf.http/http-4xx
                                        :tags {:status 401
                                               :message "Invalid credentials."}))

            :else
            (ok-stub frame-ctx (assoc args-map :value {}))))
        50))))

;; ============================================================================
;; STATE MACHINE
;; ============================================================================

(rf/reg-event-fx :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
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
;; The machine snapshot lives at [:rf/machines :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces. The
;; "in :submitting?" / "in :authed?" predicates moved to the
;; `:rf/machine-has-tag?` framework sub in views below (per Spec 005
;; §State tags).

(rf/reg-sub :auth.login/state
  (fn [db _]
    (get-in db [:rf/machines :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  (fn [db _]
    (get-in db [:rf/machines :auth.login/flow :data :error])))

;; ============================================================================
;; VIEWS  (Helix — defnc + use-subscribe)
;; ============================================================================

(defnc login-form []
  (let [busy?    (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                               :auth.login/flow :auth/busy])
        err      (helix-adapter/use-subscribe [:auth.login/error])
        dispatch (rf/dispatcher)
        [email    set-email!]    (helix-hooks/use-state "")
        [password set-password!] (helix-hooks/use-state "")]
    (d/form
       {:class "login-form"
        :data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (dispatch [:auth.login/flow
                                [:auth.login/submit {:email email
                                                     :password password}]]))}
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

(defnc login-banner []
  (let [authed? (helix-adapter/use-subscribe [:rf/machine-has-tag?
                                              :auth.login/flow :auth/authenticated])]
    (d/div
       {:class "banner"
        :data-testid "login-banner"}
       (if authed?
         (d/span "Welcome!")
         ($ login-form)))))

(defnc root-view []
  (d/div
     {:class "app"}
     (d/h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! helix-adapter/adapter)
  (rf/reg-frame :rf/default
    {:doc          "Login (Helix) demo frame."
     :fx-overrides {:rf.http/managed :rf.http/managed.login-demo}})
  (.render react-root ($ root-view)))
