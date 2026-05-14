(ns login-form.views
  "Login-form testbed views (rf2-0sg12).

  Two registered views — `login-form` (the form proper) and
  `login-card` (the framed container that swaps banner / form
  depending on state). The same views render in the live testbed at
  `#/` and in every Story variant.

  Per spec/004 §reg-view: `dispatch` and `subscribe` are auto-
  injected lexical bindings; both resolve at render time to the
  surrounding frame. The live app's default frame and each Story
  variant's allocated frame both work without code changes — the
  payoff Story is built around."
  (:require [reagent.core   :as r]
            [re-frame.core  :as rf]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; login-form — the form proper
;;
;; Form-2 so the email/password inputs can hold component-local state
;; without colonising app-db with every keystroke. Per Story's design,
;; local component state is fine — Story assertions read app-db, not
;; component-local atoms, so the input state stays where it belongs.
;; ---------------------------------------------------------------------------

(reg-view login-form []
  (let [state (r/atom {:email "" :password ""})]
    (fn []
      (let [busy?    @(rf/has-tag? :login/flow :auth/busy)
            retry?   @(rf/has-tag? :login/flow :auth/retry)
            err      @(subscribe [:login/error])
            attempts @(subscribe [:login/attempts])
            submit-evt (if (pos? attempts) :login/retry :login/submit)]
        [:form {:data-test "login-form"
                :on-submit (fn [e]
                             (.preventDefault e)
                             (dispatch [:login/flow [submit-evt @state]]))}
         [:div {:style {:margin "0.5em 0"}}
          [:input {:type        "email"
                   :placeholder "Email"
                   :disabled    busy?
                   :data-test   "login-email"
                   :value       (:email @state)
                   :on-change   #(swap! state assoc :email (.. % -target -value))
                   :style       {:padding "6px 8px" :width "240px"
                                 :border  "1px solid #ccc"
                                 :border-radius "3px"}}]]
         [:div {:style {:margin "0.5em 0"}}
          [:input {:type        "password"
                   :placeholder "Password"
                   :disabled    busy?
                   :data-test   "login-password"
                   :value       (:password @state)
                   :on-change   #(swap! state assoc :password (.. % -target -value))
                   :style       {:padding "6px 8px" :width "240px"
                                 :border  "1px solid #ccc"
                                 :border-radius "3px"}}]]
         [:div {:style {:margin "0.75em 0 0.25em" :display "flex" :gap "8px"
                        :align-items "center"}}
          [:button {:type      "submit"
                    :disabled  busy?
                    :data-test "login-submit"
                    :style     {:padding "6px 14px"
                                :background (if busy? "#aaa" "#1f6feb")
                                :color   "white"
                                :border  "none"
                                :border-radius "3px"
                                :cursor (if busy? "wait" "pointer")}}
           (cond
             retry? "Retrying…"
             busy?  "Signing in…"
             :else  (if (pos? attempts) "Try again" "Sign in"))]
          (when (and err (not busy?))
            [:button {:type      "button"
                      :on-click  #(dispatch [:login/flow [:login/dismiss]])
                      :data-test "login-dismiss"
                      :style     {:padding "6px 12px" :background "#f5f5f5"
                                  :border "1px solid #ccc" :border-radius "3px"}}
             "Cancel"])]
         (when err
           [:p {:style     {:color "#d83b01" :font-size "13px"
                            :margin "0.75em 0 0"}
                :data-test "login-error"}
            err
            (when (pos? attempts)
              [:span {:style {:color "#666" :margin-left "8px"}}
               (str "(attempt " attempts ")")])])]))))

;; ---------------------------------------------------------------------------
;; login-card — the framed container that swaps banner / form
;;
;; This is the view a Story variant points at via `:component`. The
;; same view renders in the live testbed at `#/`.
;; ---------------------------------------------------------------------------

(reg-view login-card [{:keys [heading]}]
  (let [authed? @(rf/has-tag? :login/flow :auth/authenticated)
        email   @(subscribe [:login/email])
        state   @(subscribe [:login/state])]
    [:section {:style     {:padding         "1.25em 1.5em"
                           :border          "1px solid #ddd"
                           :border-radius   "6px"
                           :background      "#fff"
                           :font-family     "system-ui, sans-serif"
                           :min-width       "320px"
                           :max-width       "360px"}
               :data-test "login-card"}
     [:h3 {:style {:margin "0 0 1em"}
           :data-test "login-heading"}
      (or heading "Sign in")]
     ;; The state-pill — surfaces the current FSM state to the
     ;; browser. Useful for the Story workspace's grid view where
     ;; five cards sit side-by-side and the only thing that
     ;; distinguishes them visually is which state they're in.
     [:div {:style {:font-size "11px" :color "#666" :margin "0 0 1em"
                    :font-family "monospace"}
            :data-test "login-state-pill"}
      (str "state: " state)]
     (if authed?
       [:div {:data-test "login-welcome"}
        [:p {:style {:margin "0 0 0.5em" :font-size "16px"}}
         "Welcome, " [:strong email] "."]
        [:button {:on-click  #(dispatch [:login/flow [:login/sign-out]])
                  :data-test "login-sign-out"
                  :style     {:padding "4px 10px" :background "#f5f5f5"
                              :border "1px solid #ccc" :border-radius "3px"}}
         "Sign out"]]
       [login-form])]))
