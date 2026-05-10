(ns login-helix.core
  "Helix variant of the login example (rf2-2qit Decision 7).

   Same dataflow, schemas, machine, and HTTP stub as
   examples/reagent/login and examples/uix/login_uix, but views are
   written as Helix `defnc` components and consume subs via the
   `use-subscribe` hook. Demonstrates that the Spec 005 state machine,
   Spec 010 schemas, and Spec 014 managed-HTTP surfaces are
   substrate-agnostic — only the view layer differs across substrates.

   Per rf2-2qit Decision 4 reg-view stays Reagent-only; Helix
   components are plain `defnc`. Per Decision 3 there is no
   auto-injection."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :as helix :refer [$ defnc]]
            [helix.dom          :as d]
            [helix.hooks        :as helix-hooks]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http-managed]
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
               to the Reagent and UIx examples' stub."
   :platforms #{:server :client}}
  (fn fx-managed-login-demo [frame-ctx args-map]
    (let [{:keys [url body]} (:request args-map)
          login?    (= "/api/login" url)
          success?  (and login? (= good-password (:password body)))
          ok-stub   (registrar/handler :fx :rf.http/managed-canned-success)
          fail-stub (registrar/handler :fx :rf.http/managed-canned-failure)]
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
  (rf/create-machine-handler
    {:initial :idle
     :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      (fn [data _event] (< (:attempts data) 3))}

     :actions
     {:clear-error
      (fn [_data _event] {:data {:error nil}})

      :issue-request
      (fn [_data [_ creds]]
        {:fx [[:rf.http/managed
               {:request    {:method :post
                             :url    "/api/login"
                             :body   creds
                             :request-content-type :json}
                :decode     :json
                :on-success [:auth.login/flow [:auth.login/success]]
                :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

      :record-error
      (fn [data [_ {:keys [failure]}]]
        {:data (-> data
                   (update :attempts inc)
                   (assoc :error (or (:message failure) "Login failed.")))})

      :lock-account
      (fn [_data _event]
        {:fx [[:rf.http/managed
               {:request {:method :post :url "/api/auth/lock"}}]]})

      :store-session
      (fn [_data [_ {:keys [value]}]]
        {:fx [[:auth.session/store {:token (:token value)}]]})}

     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :action :clear-error}}}

      :submitting
      {:entry :issue-request
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
      {:meta {:terminal? true}}

      :locked-out
      {:meta {:terminal? true}}}}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :auth.login/state
  (fn [db _]
    (get-in db [:rf/machines :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  (fn [db _]
    (get-in db [:rf/machines :auth.login/flow :data :error])))

(rf/reg-sub :auth.login/submitting?
  :<- [:auth.login/state]
  (fn [state _] (= :submitting state)))

(rf/reg-sub :auth.login/authenticated?
  :<- [:auth.login/state]
  (fn [state _] (= :authed state)))

;; ============================================================================
;; VIEWS  (Helix — defnc + use-subscribe)
;; ============================================================================

(defnc login-form []
  (let [submitting? (helix-adapter/use-subscribe [:auth.login/submitting?])
        err         (helix-adapter/use-subscribe [:auth.login/error])
        dispatch    (rf/dispatcher)
        [email    set-email!]    (helix-hooks/use-state "")
        [password set-password!] (helix-hooks/use-state "")]
    (d/form
       {:class "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (dispatch [:auth.login/flow
                                [:auth.login/submit {:email email
                                                     :password password}]]))}
       (d/input  {:type        "email"
                  :placeholder "Email"
                  :disabled    submitting?
                  :on-change   #(set-email! (.. % -target -value))})
       (d/input  {:type        "password"
                  :placeholder "Password"
                  :disabled    submitting?
                  :on-change   #(set-password! (.. % -target -value))})
       (d/button {:type "submit" :disabled submitting?}
          (if submitting? "Signing in…" "Sign in"))
       (when err (d/p {:class "error"} err)))))

(defnc login-banner []
  (let [authed? (helix-adapter/use-subscribe [:auth.login/authenticated?])]
    (d/div
       {:class "banner"}
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
  ;; rf2-84po: re-frame.adapter.helix ns-load auto-registers as default.
  (rf/init!)
  (rf/reg-frame :rf/default
    {:doc          "Login (Helix) demo frame."
     :fx-overrides {:rf.http/managed :rf.http/managed.login-demo}})
  (.render react-root ($ root-view)))
