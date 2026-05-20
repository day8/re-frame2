(ns day8.re-frame2-causa.panels.app-db-diff-downstream-cljs-test
  "CLJS unit tests for the App-DB panel's downstream-subs overlay
  (rf2-op9v2).

  Three test groups:

    1. **Subs install** — the leaf's `install-subs!` registers the
       three popover-state subs (`-slot`, `-open?`, `-path`) and the
       per-path downstream-subs query.

    2. **Events install + behaviour** — `:rf.causa.app-db/show-
       downstream` + `:rf.causa.app-db/hide-downstream` toggle the
       popover slot; `:rf.causa/navigate-to-panel` writes the
       `:nav-cursor` slot and dispatches `:rf.causa/select-tab`.

    3. **View shape** — the hover-trigger renders an inert chip when
       no popover is open; rendering becomes active when the slot
       matches the trigger's path; the popover body lists subs +
       views from the focused cascade's `:sub-runs` / `:renders`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.app-db-diff-downstream
             :as downstream]
            [day8.re-frame2-causa.panels.app-db-diff-subs
             :as app-db-subs]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (reset! app-db-subs/diff-cache {})
                (reset! app-db-subs/annotated-tree-cache {})
                (reset! app-db-subs/redacted-modified-cache {})
                (reset! app-db-subs/flow-writes-cache {}))}))

;; ---- helpers --------------------------------------------------------------

(defn- find-hiccup
  "Walk a hiccup tree depth-first; return the first vector whose
  attribute map has `[:data-testid testid]` (or whose attribute map
  contains a `:data-testid` key matching the predicate `pred`)."
  [tree pred]
  (cond
    (and (vector? tree)
         (map? (second tree))
         (pred (:data-testid (second tree))))
    tree

    (vector? tree)
    (some #(find-hiccup % pred) (rest tree))

    (seq? tree)
    (some #(find-hiccup % pred) tree)

    :else nil))

(defn- find-by-testid [tree testid]
  (find-hiccup tree #(= testid %)))

;; ---- subs install ---------------------------------------------------------

(deftest install-subs-registers-popover-state-subs
  (downstream/install-subs!)
  (is (some? (registrar/handler :sub :rf.causa.app-db/popover-slot)))
  (is (some? (registrar/handler :sub :rf.causa.app-db/popover-open?)))
  (is (some? (registrar/handler :sub :rf.causa.app-db/popover-path)))
  (is (some? (registrar/handler :sub :rf.causa/app-db-downstream-for-path))))

;; ---- events install -------------------------------------------------------

(deftest install-events-registers-show-hide-and-navigate
  (downstream/install-events!)
  (is (some? (registrar/handler :event :rf.causa.app-db/show-downstream)))
  (is (some? (registrar/handler :event :rf.causa.app-db/hide-downstream)))
  (is (some? (registrar/handler :event :rf.causa/navigate-to-panel))))

;; ---- show / hide event behaviour ------------------------------------------

(deftest show-downstream-writes-popover-slot
  (downstream/install!)
  (rf/dispatch-sync [:rf.causa.app-db/show-downstream [:cart :state]])
  (is (= [:cart :state]
         @(rf/subscribe [:rf.causa.app-db/popover-path]))
      "popover-path subscribe reads the path the show event wrote")
  (is @(rf/subscribe [:rf.causa.app-db/popover-open?])
      "popover-open? becomes true when slot is non-nil"))

(deftest hide-downstream-clears-the-slot
  (downstream/install!)
  (rf/dispatch-sync [:rf.causa.app-db/show-downstream [:cart :state]])
  (rf/dispatch-sync [:rf.causa.app-db/hide-downstream])
  (is (nil? @(rf/subscribe [:rf.causa.app-db/popover-path])))
  (is (false? @(rf/subscribe [:rf.causa.app-db/popover-open?]))))

;; ---- navigate-to-panel ----------------------------------------------------

(deftest navigate-to-panel-dispatches-select-tab-and-writes-cursor
  ;; The chained dispatch lands on the substrate's `:rf.causa/select-
  ;; tab` event — register a capture so we can assert the chain. We
  ;; don't stub `:dispatch` because re-frame's built-in `:dispatch`
  ;; fx routes through the registrar's own seam; the simplest
  ;; assertion is that the target event ran and wrote the
  ;; `:selected-tab` slot in app-db.
  (downstream/install!)
  (rf/reg-event-db :rf.causa/select-tab
    (fn [db [_ tab-id]]
      (assoc db ::captured-tab tab-id)))
  (rf/dispatch-sync [:rf.causa/navigate-to-panel
                     {:target  :views
                      :sub-id  :cart/state
                      :query-v [:cart/state]}])
  (rf/reg-sub ::captured-tab (fn [db _] (::captured-tab db)))
  (is (= :views @(rf/subscribe [::captured-tab]))
      "navigate-to-panel chains :rf.causa/select-tab via :fx")
  ;; nav-cursor was written for the Reactive panel (rf2-wyvf2 / PR
  ;; #1741) to consume when scrolling.
  (rf/reg-sub ::nav-cursor (fn [db _] (get-in db [:app-db :nav-cursor])))
  (is (= {:target :views :sub-id :cart/state :query-v [:cart/state]}
         @(rf/subscribe [::nav-cursor]))
      ":nav-cursor slot carries the sub-id + query-v + target"))

(deftest navigate-to-panel-defaults-target-to-views
  (downstream/install!)
  (rf/reg-event-db :rf.causa/select-tab
    (fn [db [_ tab-id]]
      (assoc db ::captured-tab tab-id)))
  (rf/dispatch-sync [:rf.causa/navigate-to-panel {}])
  (rf/reg-sub ::captured-tab (fn [db _] (::captured-tab db)))
  (is (= :views @(rf/subscribe [::captured-tab]))
      "default target is :views"))

(deftest navigate-to-panel-closes-open-popover
  (downstream/install!)
  ;; Stub select-tab so the chained dispatch does NOT fall through to
  ;; the substrate's real handler (which isn't registered in this leaf
  ;; test).
  (rf/reg-event-db :rf.causa/select-tab (fn [db _] db))
  (rf/dispatch-sync [:rf.causa.app-db/show-downstream [:cart :state]])
  (rf/dispatch-sync [:rf.causa/navigate-to-panel
                     {:target  :views
                      :sub-id  :cart/state
                      :query-v [:cart/state]}])
  (is (nil? @(rf/subscribe [:rf.causa.app-db/popover-path]))
      "navigation dismisses any open popover (no orphan tooltip)"))

;; ---- downstream-for-path sub ----------------------------------------------

(deftest downstream-for-path-defaults-when-no-history
  (downstream/install!)
  (let [out @(rf/subscribe [:rf.causa/app-db-downstream-for-path
                            [:cart :state]])]
    (is (= [] (:subs-recomputed out)))
    (is (= [] (:subs-skipped out)))
    (is (= [] (:views-rendered out)))
    (is (true? (:path-filtered? out))
        "rf2-gblq6 — sub now reports path-filtered? true")))

(deftest downstream-for-path-projects-sub-runs-and-renders
  ;; Seed a minimal epoch-history sub + focus pair so the downstream
  ;; sub has something to project. The focus-eid is matched against
  ;; the seeded record's :epoch-id so the projection picks the right
  ;; cascade.
  (downstream/install!)
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs  [{:sub-id :cart/state    :recomputed? true}
                    {:sub-id :cart/items    :recomputed? false}]
        :renders   [{:render-key  [:checkout-button "t1"]
                     :triggered-by :cart/state
                     :elapsed-ms  0.4}]}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _]
      {:epoch-id :ep-1 :dispatch-id 1 :frame :rf/default}))

  (let [out @(rf/subscribe [:rf.causa/app-db-downstream-for-path
                            [:cart :state]])]
    (is (= [{:sub-id :cart/state :recomputed? true}]
           (:subs-recomputed out)))
    (is (= [{:sub-id :cart/items :recomputed? false}]
           (:subs-skipped out)))
    (is (= 1 (count (:views-rendered out))))
    (is (= :cart/state (:triggered-by (first (:views-rendered out)))))))

(deftest downstream-for-path-filters-by-registry-walk
  ;; rf2-gblq6 — register two layer-1 subs whose ids overlap
  ;; different paths. The popover should only include subs whose
  ;; registry walk overlaps the hovered path.
  (downstream/install!)
  ;; Register `:cart/state` as layer-1 — its sub-id segments
  ;; `#{:cart :state}` overlap path `[:cart :state]`.
  (rf/reg-sub :cart/state (fn [db _] (get-in db [:cart :state])))
  ;; Register `:user/profile` as layer-1 — its segments
  ;; `#{:user :profile}` DON'T overlap `[:cart :state]`.
  (rf/reg-sub :user/profile (fn [db _] (get-in db [:user :profile])))
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs [{:sub-id :cart/state   :recomputed? true}
                   {:sub-id :user/profile :recomputed? true}]
        :renders  []}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _] {:epoch-id :ep-1 :dispatch-id 1 :frame :rf/default}))

  (let [out @(rf/subscribe [:rf.causa/app-db-downstream-for-path
                            [:cart :state]])]
    (is (= [{:sub-id :cart/state :recomputed? true}]
           (:subs-recomputed out))
        "only the sub whose layer-1 leaves overlap the path survives")
    (is (true? (:path-filtered? out))
        "rf2-gblq6 marker — path-filter is active")))

(deftest downstream-for-path-includes-unknown-subs-conservatively
  ;; rf2-gblq6 — when a sub isn't in the registrar (cascade-captured
  ;; but registration cleared), resolve-input-paths returns nil
  ;; (unknown) and the conservative-include branch keeps it in the
  ;; popover so we never lie by omission.
  (downstream/install!)
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs [{:sub-id :totally-unregistered :recomputed? true}]
        :renders  []}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _] {:epoch-id :ep-1 :dispatch-id 1 :frame :rf/default}))

  (let [out @(rf/subscribe [:rf.causa/app-db-downstream-for-path
                            [:cart :state]])]
    (is (= [{:sub-id :totally-unregistered :recomputed? true}]
           (:subs-recomputed out))
        "unknown sub-ids fall through to conservative-include")))

(deftest downstream-for-path-includes-layer-2-via-transitive-walk
  ;; rf2-gblq6 — a layer-2 sub depending on a layer-1 sub whose
  ;; id overlaps the path should be included via the transitive
  ;; walk. Validates that the walk follows `:<-` chains.
  (downstream/install!)
  (rf/reg-sub :cart/state (fn [db _] (get-in db [:cart :state])))
  (rf/reg-sub :cart/can-submit?
    :<- [:cart/state]
    (fn [state _] (= state :ready)))
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs [{:sub-id :cart/can-submit? :recomputed? true}]
        :renders  []}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _] {:epoch-id :ep-1 :dispatch-id 1 :frame :rf/default}))

  (let [out @(rf/subscribe [:rf.causa/app-db-downstream-for-path
                            [:cart :state]])]
    (is (= [{:sub-id :cart/can-submit? :recomputed? true}]
           (:subs-recomputed out))
        "layer-2 sub kept via transitive walk to its layer-1 upstream")))

(deftest downstream-for-path-falls-back-to-head-when-focus-stale
  ;; When :rf.causa/focus carries an :epoch-id NOT in history, the
  ;; sub falls back to the head record (last entry). Mirrors the
  ;; views_subs.cljs fallback so the popover stays useful when the
  ;; selected epoch ages out of the ring buffer.
  (downstream/install!)
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs [{:sub-id :stale  :recomputed? true}]
        :renders  []}
       {:epoch-id :ep-2
        :sub-runs [{:sub-id :head/sub :recomputed? true}]
        :renders  []}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _]
      {:epoch-id :missing-from-history :dispatch-id 99 :frame :rf/default}))

  (let [out @(rf/subscribe [:rf.causa/app-db-downstream-for-path [:any]])]
    (is (= [{:sub-id :head/sub :recomputed? true}]
           (:subs-recomputed out))
        "head record is the fallback when focus epoch is missing")))

;; ---- hover-trigger render shape ------------------------------------------

(deftest hover-trigger-renders-with-stable-testid
  (downstream/install!)
  (let [hiccup [downstream/hover-trigger [:cart :state]]
        ;; Drive the reagent component fn directly (it returns hiccup
        ;; for the current subscribe values without a React mount).
        out    (downstream/hover-trigger [:cart :state])]
    (is (vector? out))
    (is (some? (find-by-testid
                 out
                 "rf-causa-app-db-downstream-trigger-[:cart :state]"))
        "trigger carries a path-keyed testid")))

(deftest hover-trigger-omits-popover-body-when-inactive
  (downstream/install!)
  (let [out (downstream/hover-trigger [:cart :state])]
    (is (nil? (find-by-testid out "rf-causa-app-db-popover-body"))
        "no popover body when the slot is closed")))

(deftest hover-trigger-renders-popover-body-when-path-active
  ;; Seed a focused cascade so the popover-body sub returns content
  ;; and the popover renders the subs section.
  (downstream/install!)
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs [{:sub-id :cart/state :recomputed? true}]
        :renders  [{:render-key [:checkout-button "t1"]
                    :triggered-by :cart/state
                    :elapsed-ms 0.1}]}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _] {:epoch-id :ep-1 :dispatch-id 1 :frame :rf/default}))

  (rf/dispatch-sync [:rf.causa.app-db/show-downstream [:cart :state]])

  (let [out (downstream/hover-trigger [:cart :state])]
    (is (some? (find-by-testid out "rf-causa-app-db-popover-body"))
        "body renders when this trigger's path matches the open slot")
    (is (some? (find-by-testid out
                                "rf-causa-app-db-popover-jump-:cart/state"))
        "⤴ jump button per recomputed sub")
    (is (some? (find-by-testid out
                                "rf-causa-app-db-popover-jump-reactive"))
        "⤴ footer 'jump to Reactive panel' affordance")
    (is (nil? (find-by-testid out
                               "rf-causa-app-db-popover-mvp-note"))
        "rf2-gblq6 — MVP-note affordance removed once true path-filter ships")))

(deftest hover-trigger-popover-empty-state
  (downstream/install!)
  ;; No epoch-history → empty cascade → popover renders the empty
  ;; state instead of the subs/views lists.
  (rf/reg-sub :rf.causa/epoch-history (fn [_ _] []))
  (rf/reg-sub :rf.causa/focus (fn [_ _] nil))
  (rf/dispatch-sync [:rf.causa.app-db/show-downstream [:cart :state]])
  (let [out (downstream/hover-trigger [:cart :state])]
    (is (some? (find-by-testid out "rf-causa-app-db-popover-empty")))))

(deftest hover-trigger-other-paths-do-not-render-overlapping-popovers
  ;; Two simultaneous triggers (paths A + B); only the trigger whose
  ;; path matches the open slot renders its body. Ensures the global
  ;; slot serialises the popover — clicking from one section to
  ;; another swaps the popover without leaking ghosts.
  (downstream/install!)
  (rf/reg-sub :rf.causa/epoch-history
    (fn [_ _]
      [{:epoch-id :ep-1
        :sub-runs [{:sub-id :s :recomputed? true}]
        :renders  []}]))
  (rf/reg-sub :rf.causa/focus
    (fn [_ _] {:epoch-id :ep-1 :dispatch-id 1 :frame :rf/default}))
  (rf/dispatch-sync [:rf.causa.app-db/show-downstream [:a]])

  (let [active   (downstream/hover-trigger [:a])
        inactive (downstream/hover-trigger [:b])]
    (is (some? (find-by-testid active   "rf-causa-app-db-popover-body")))
    (is (nil?  (find-by-testid inactive "rf-causa-app-db-popover-body"))
        "only the matching-path trigger renders its body")))
