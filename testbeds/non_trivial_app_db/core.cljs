(ns non-trivial-app-db.core
  "Shared framework-behavior testbed — an app-db with ~5 levels of nesting
  and ~50 leaf paths, deliberately shaped to exercise a consumer
  (Causa, Story, pair2-mcp) diffing visualisation under realistic
  depth. Each button mutates a different slot at a different depth so
  the consumer's diff renderer must:

    - Highlight the changed leaf without re-rendering the whole tree.
    - Collapse-by-default the unchanged subtrees while keeping the
      changed path expanded.
    - Show the before/after value pair on the changed leaf with the
      full key path leading down to it.
    - Diff structurally distinct value shapes (scalar swap, map merge,
      vector append, vector swap-by-index, set add, set remove).

  This is NOT a tutorial. The app-db shape is the *only* point — a
  realistic shape against which a diffing UI's visual hierarchy is
  exercised. The handlers below are minimal — one shot per button, one
  diff slot per click.

  App-db shape (5 deep, 50+ leaves):

    {:user        {...4 leaves at 2 levels...}            ← 2-deep
     :settings    {...8 leaves at 3 levels...}            ← 3-deep
     :cart        {...12 leaves at 4 levels...}           ← 4-deep
     :catalog     {...18 leaves at 5 levels...}           ← 5-deep
     :session     {...8 leaves at 2 levels...}            ← 2-deep
     :metrics     {...5 scalar leaves at 1 level...}}     ← 1-deep

  Total: 55 leaf paths across 6 top-level keys, max depth 5."
  (:require [cljs.pprint :as pprint]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; The canonical app-db shape
;; ----------------------------------------------------------------------------
;;
;; Shape is the surface's load-bearing artefact. The values are picked
;; to render legibly in a diff view (short strings, small numbers, no
;; multi-line text). The structure is realistic-looking: a SaaS-style
;; app with a user profile, configuration tree, a multi-item cart, a
;; nested catalogue, session state, and a flat metrics dashboard.

(def initial-db
  {;; :user — 2 levels deep, 4 leaves.
   :user      {:id       "user-42"
               :profile  {:name  "Ada Lovelace"
                          :email "ada@example.com"
                          :role  :admin}}

   ;; :settings — 3 levels deep, 8 leaves.
   :settings  {:theme         :dark
               :locale        "en-AU"
               :notifications {:email     true
                               :push      false
                               :marketing false}
               :feature-flags {:beta?      true
                               :new-cart?  false
                               :ai-search? true}}

   ;; :cart — 4 levels deep, 12 leaves. Two items, each with nested
   ;; metadata. Demonstrates vector-of-maps diffing.
   :cart      {:items    [{:sku      "BK-001"
                           :title    "Refactoring"
                           :qty      2
                           :price    {:currency :AUD
                                      :amount   42.50}}
                          {:sku      "BK-002"
                           :title    "Domain Modelling"
                           :qty      1
                           :price    {:currency :AUD
                                      :amount   35.00}}]
               :total    120.00
               :coupon   nil}

   ;; :catalog — 5 levels deep, 18 leaves. Two categories, each with
   ;; nested product groups carrying SKUs and feature sets.
   :catalog   {:categories
               {:books    {:name     "Books"
                           :groups   {:tech    {:name "Technical"
                                                :skus #{"BK-001" "BK-002" "BK-003"}}
                                      :fiction {:name "Fiction"
                                                :skus #{"BK-101" "BK-102"}}}}
                :gadgets  {:name     "Gadgets"
                           :groups   {:audio   {:name "Audio"
                                                :skus #{"AU-201" "AU-202"}}
                                      :video   {:name "Video"
                                                :skus #{"VI-301"}}}}}}

   ;; :session — 2 levels deep, 8 leaves.
   :session   {:auth          {:token-valid? true
                               :expires-at   1700000000
                               :scopes       #{:read :write}}
               :ui            {:sidebar-open?    true
                               :selected-route   :home
                               :pending-modal    nil
                               :last-interaction 1700000000}}

   ;; :metrics — 1 level deep, 5 scalar leaves. Demonstrates that the
   ;; surface's overall depth doesn't preclude a flat slice for the
   ;; uninteresting-but-numerous parts of an app-db.
   :metrics   {:requests     0
               :errors       0
               :renders      0
               :sub-runs     0
               :flow-evals   0}})

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    initial-db))

;; ----------------------------------------------------------------------------
;; Six handlers — one for each structural diff shape a consumer must
;; render distinctively.
;; ----------------------------------------------------------------------------

;; --- 1 · Scalar swap deep in the tree ---
;; Toggles :settings :theme between :dark and :light. The diff renderer
;; should highlight the single leaf and leave the surrounding 7 leaves
;; of :settings collapsed.

(rf/reg-event-db ::toggle-theme
  (fn [db _ev]
    ;; HOT PATH — single-leaf scalar swap at depth 2.
    (update-in db [:settings :theme]
               {:dark :light :light :dark})))

;; --- 2 · Map merge at depth 3 ---
;; Flips two leaves of :settings :notifications in one write. Verifies
;; the diff renderer can show two simultaneously-changed siblings
;; without collapsing them under a single "this map changed" line.

(rf/reg-event-db ::toggle-notifications
  (fn [db _ev]
    (update-in db [:settings :notifications]
               (fn [m]
                 (-> m
                     (update :email     not)
                     (update :marketing not))))))

;; --- 3 · Vector append (cart line item) ---
;; Adds a new cart line item. Tests vector-as-collection diffing —
;; the renderer should show the new index without re-marking the
;; existing items as changed.

(rf/reg-event-db ::add-cart-item
  (fn [db _ev]
    (let [new-item {:sku   "BK-099"
                    :title "Patterns of Distributed Systems"
                    :qty   1
                    :price {:currency :AUD :amount 55.00}}]
      (-> db
          (update-in [:cart :items] (fnil conj []) new-item)
          (update-in [:cart :total] (fnil + 0) 55.00)))))

;; --- 4 · Vector swap-by-index ---
;; Mutates an existing cart line's :qty AND its nested :price :amount.
;; Tests that the diff renderer correctly drills *into* a vector
;; element without flagging siblings, AND that the descendant change
;; on the price subtree is rendered as part of the same item's diff.

(rf/reg-event-db ::bump-first-item-qty
  (fn [db _ev]
    (-> db
        (update-in [:cart :items 0 :qty] inc)
        (update-in [:cart :items 0 :price :amount] + 42.50)
        (update-in [:cart :total] + 42.50))))

;; --- 5 · Set add (deepest, level 5) ---
;; Adds a SKU to :catalog :categories :books :groups :tech :skus.
;; This is the deepest mutation in the surface — exercises the
;; diff renderer's vertical compression at five levels of nesting.

(rf/reg-event-db ::register-new-sku
  (fn [db _ev]
    ;; HOT PATH — five-level-deep set mutation.
    (update-in db [:catalog :categories :books :groups :tech :skus]
               (fnil conj #{})
               "BK-099")))

;; --- 6 · Set remove + flag flip in one drain ---
;; Removes a scope from :session :auth :scopes AND flips
;; :session :ui :sidebar-open?. Tests sibling-key diffs at two
;; distinct sub-paths of :session.

(rf/reg-event-db ::revoke-write-and-collapse-sidebar
  (fn [db _ev]
    (-> db
        (update-in [:session :auth :scopes]
                   (fnil disj #{}) :write)
        (update-in [:session :ui :sidebar-open?] not))))

;; --- Reset ---
(rf/reg-event-db ::reset
  (fn [_db _ev]
    initial-db))

;; ----------------------------------------------------------------------------
;; Subs — one per top-level slice so a consumer can re-render only the
;; changed slice. Selecting from sub level 1 prevents an unnecessary
;; re-render cascade across the whole tree.
;; ----------------------------------------------------------------------------

(rf/reg-sub :user     (fn [db _] (:user     db)))
(rf/reg-sub :settings (fn [db _] (:settings db)))
(rf/reg-sub :cart     (fn [db _] (:cart     db)))
(rf/reg-sub :catalog  (fn [db _] (:catalog  db)))
(rf/reg-sub :session  (fn [db _] (:session  db)))
(rf/reg-sub :metrics  (fn [db _] (:metrics  db)))

;; Aggregate sub — full db pr-str for the rare consumer that wants
;; the whole snapshot in one slot (e.g. snapshot identity verification).

(rf/reg-sub :app-db-snapshot
  :<- [:user] :<- [:settings] :<- [:cart] :<- [:catalog]
  :<- [:session] :<- [:metrics]
  (fn [[user settings cart catalog session metrics] _]
    {:user user :settings settings :cart cart
     :catalog catalog :session session :metrics metrics}))

;; ----------------------------------------------------------------------------
;; View
;; ----------------------------------------------------------------------------

(reg-view db-pre []
  (let [snapshot @(subscribe [:app-db-snapshot])]
    [:pre {:data-testid "app-db-pr-str"
           :style       {:white-space :pre-wrap :font-size "0.9em"
                         :background  "#f5f5f5" :padding "0.5em"
                         :margin      "0.5em 0"
                         :max-height  "20em" :overflow :auto}}
     (with-out-str (pprint/pprint snapshot))]))

(reg-view buttons []
  [:div {:data-testid "non-trivial-app-db"
         :style       {:font-family "sans-serif" :padding "1em"}}
   [:h1 "non-trivial-app-db testbed"]
   [:p "55 leaves across 6 top-level keys, max depth 5. Each button
        triggers one structural diff shape at a known path. Diff
        visualisation should highlight only the changed slot."]

   [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
    [:button {:data-testid "toggle-theme"
              :on-click    #(dispatch [::toggle-theme])}
     "1 · scalar swap @ [:settings :theme] (d=2)"]
    [:button {:data-testid "toggle-notifications"
              :on-click    #(dispatch [::toggle-notifications])}
     "2 · map merge @ [:settings :notifications] (d=3)"]
    [:button {:data-testid "add-cart-item"
              :on-click    #(dispatch [::add-cart-item])}
     "3 · vector append @ [:cart :items] (d=2→item)"]
    [:button {:data-testid "bump-first-item-qty"
              :on-click    #(dispatch [::bump-first-item-qty])}
     "4 · vector swap-by-index @ [:cart :items 0 …] (d=4)"]
    [:button {:data-testid "register-new-sku"
              :on-click    #(dispatch [::register-new-sku])}
     "5 · set add @ [:catalog …books…tech :skus] (d=5)"]
    [:button {:data-testid "revoke-write-and-collapse"
              :on-click    #(dispatch [::revoke-write-and-collapse-sidebar])}
     "6 · set remove + flag flip @ :session siblings (d=3)"]
    [:button {:data-testid "reset"
              :on-click    #(dispatch [::reset])}
     "Reset"]]

   [:h3 {:style {:margin-top "1em"}} "app-db snapshot"]
   [db-pre]])

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  (rdc/render react-root [root]))
