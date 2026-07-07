(ns re-frame.routing-can-leave-test
  "Leave-guard + pending-navigation protocol tests for re-frame.routing
  (`:can-leave`, `:rf/pending-navigation`, `:rf.route/continue` /
  `:rf.route/cancel` / `:rf.route/navigation-blocked`). Split from
  routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing :as routing]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- Spec 012 §Navigation blocking — pending-nav protocol ----------------

(deftest routing-pending-nav-protocol
  (testing "block via :can-leave; continue and cancel both clear the slot"
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol: a route
    ;; declares :can-leave (sub-id → boolean). When the sub returns false,
    ;; :rf/url-requested writes :rf/pending-navigation and does not push.
    ;; :rf.route/continue clears the slot AND completes the navigation.
    ;; :rf.route/cancel clears the slot WITHOUT navigating.
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")

    (rf/reg-event :editor/dirty
                     (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _]
                  ;; "OK to leave" = NOT dirty.
                  (not (get-in db [:editor :dirty?]))))

    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    ;; 1. Land on the editor route. nav-token allocates; slice is set.
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (is (= :editor/article (get-in (rf/runtime-db-value :rf/default)
                                   [:rf.runtime/routing :current :route-id]))
        "initial nav landed on :editor/article")

    ;; 2. Dirty the form so :can-leave? returns false.
    (rf/dispatch-sync [:editor/dirty true])

    ;; 3. Try to leave. Guard rejects → pending slot is set; URL unchanged.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation])]
      (is (some? pending)
          ":rf/pending-navigation is populated on guard rejection")
      (is (= "/cart" (:requested-url pending))
          "the requested URL is captured for resume (rf2-b8ugt slot shape)")
      (is (= :editor/article (:rejecting-route pending))
          ":rejecting-route names the route whose guard ran")
      (is (= :editor/can-leave? (:rejecting-guard pending))
          ":rejecting-guard names the sub-id that rejected")
      (is (vector? (:requested-by-event pending))
          ":requested-by-event captures the original :rf/url-requested vector")
      (is (= :editor/article
             (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
          "the :rf/route slice does NOT change when blocked"))

    ;; 4. CANCEL — slot clears; original route stays active.
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (nil? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
        "cancel clears :rf/pending-navigation")
    (is (= :editor/article
           (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
        "cancel does NOT navigate")

    ;; 5. Now block again — and CONTINUE this time.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (is (some? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
        "second blocked request reseats the slot")
    (rf/dispatch-sync [:rf.route/continue "pn-2"])
    (is (nil? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
        "continue clears :rf/pending-navigation")
    (is (= :route/cart (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
        "continue completes the original navigation")))

;; ---- pending-nav protocol: continue / cancel with NO pending slot --------
;;
;; `pending-nav-continue-and-cancel-require-matching-id` covers a WRONG id
;; while a pending nav EXISTS. The complementary case — dispatching continue
;; / cancel when the slot is empty (a stray event, a double-cancel) — must be
;; a clean no-op: continue's `(and pending ...)` guard and cancel's
;; `(= pn-id <nil id>)` both fall through to `{}`.

(deftest continue-and-cancel-no-op-when-no-pending-nav
  (testing ":rf.route/cancel and :rf.route/continue are safe no-ops when
            :rf/pending-navigation is empty (no slot to resolve)"
    (rf/reg-route :route/home {} "/")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on home; no navigation is pending.
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (is (nil? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
        "no pending navigation to begin with")
    (let [before (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
      ;; Stray cancel — nothing to clear.
      (rf/dispatch-sync [:rf.route/cancel "phantom-id"])
      (is (nil? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
          "cancel with no pending slot leaves the slot nil")
      (is (= before (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current]))
          "cancel with no pending slot does not perturb the route slice")
      ;; Stray continue — no original event to re-issue.
      (rf/dispatch-sync [:rf.route/continue "phantom-id"])
      (is (= before (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current]))
          "continue with no pending slot does not navigate")
      (is (= :route/home (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
          "the active route stays put"))))

;; ---- rf2-b8ugt: :rf/pending-navigation full slot shape -------------------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol and
;; Spec-Schemas.md §:rf/pending-navigation the slot carries
;; `{:id :requested-by-event :requested-url :reason :rejecting-route
;;   :rejecting-guard}`. Tools / dialogs read :rejecting-guard to render
;; meaningful "Discard changes on Editor?" prompts.

(deftest pending-navigation-slot-shape
  (testing ":rf/pending-navigation carries the full Spec-Schemas slot shape"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation])]
      (is (string? (:id pending))
          ":id is the opaque pending-nav id")
      (is (= "/cart" (:requested-url pending))
          ":requested-url carries the navigation target")
      (is (= :editor/article (:rejecting-route pending))
          ":rejecting-route names the active route at rejection time")
      (is (= :editor/can-leave? (:rejecting-guard pending))
          ":rejecting-guard names the rejecting sub-id (for tooling)")
      (is (= [:rf/url-requested {:url "/cart"}]
             (:requested-by-event pending))
          ":requested-by-event captures the original event vector"))))

(deftest navigation-blocked-trace-carries-rejecting-guard
  (testing ":rf.route/navigation-blocked trace carries :rejecting-guard
            so tooling can flag the rejecting sub-id"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::blocked (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/unregister-listener! :trace ::blocked)
      (is (some (fn [ev]
                  (and (= :rf.route/navigation-blocked (:operation ev))
                       (= :editor/can-leave? (-> ev :tags :rejecting-guard))))
                @traces)
          "navigation-blocked trace tags include :rejecting-guard"))))

;; ---- rf2-yursn: :rf.route/continue re-issues :rf/url-requested ----------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol continue must
;; "re-issue the original navigation request, *bypassing* the leave guard".
;; Pre-fix dispatched :rf.route/transitioned + :rf.nav/push-url directly, skipping
;; the :rf/url-requested policy chain.

(deftest continue-re-issues-via-url-requested-with-bypass
  (testing ":rf.route/continue re-emits :rf/url-requested with
            :bypass-guards? #{:leave}, running the policy chain (the enter
            guard still re-runs — rf2-p69yaz point 6)"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on editor; dirty the form; try to leave; guard blocks.
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (is (some? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
        "guard rejection set the pending slot")
    ;; CONTINUE — the slot clears AND the navigation completes
    ;; even though :editor/dirty? remains true (bypass flag wins).
    (rf/dispatch-sync [:rf.route/continue "pn-1"])
    (is (nil? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
        "continue cleared the pending slot")
    (is (= :route/cart
           (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
        "continue completed the navigation through :rf/url-requested → :rf.route/transitioned")
    (is (true? (get-in (rf/app-db-value :rf/default) [:editor :dirty?]))
        ":editor/dirty? remains true — bypass flag did NOT run the guard a second time")))

(deftest can-leave-query-vector-blocks-url-requested
  (testing "Spec-shaped :can-leave query vectors are subscribed directly"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation])]
      (is (some? pending)
          "query-vector guard returning false blocks the navigation")
      (is (= :editor/can-leave? (:rejecting-guard pending))
          "pending slot stores the guard id, not the whole query vector")
      (is (= [:editor/can-leave?] (:can-leave (rf/handler-meta :route :editor/article)))
          "route metadata preserves canonical query-vector semantics"))))

(deftest programmatic-navigate-runs-can-leave-guard
  (testing ":rf.route/navigate is guarded by the active route's :can-leave"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf.route/navigate :route/cart])
      (let [db (rf/runtime-db-value :rf/default)]
        (is (some? (get-in db [:rf.runtime/routing :pending-navigation]))
            "programmatic navigation sets pending-navigation when blocked")
        (is (= :editor/article (get-in db [:rf.runtime/routing :current :route-id]))
            "the active route does not change")
        (is (empty? @pushed)
            "pushState is not requested for a blocked programmatic nav")))))

(deftest handle-url-change-runs-can-leave-guard
  (testing ":rf.route/handle-url-change is guarded by the active route's :can-leave"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf.route/handle-url-change "/cart"])
    (let [db (rf/runtime-db-value :rf/default)]
      (is (some? (get-in db [:rf.runtime/routing :pending-navigation]))
          "popstate/initial URL handling sets pending-navigation when blocked")
      (is (= :editor/article (get-in db [:rf.runtime/routing :current :route-id]))
          "the active route does not change while pending"))))

(deftest pending-nav-continue-and-cancel-require-matching-id
  (testing ":rf.route/continue and :rf.route/cancel ignore stale pending-nav ids"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending-id (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :pending-navigation :id])]
      (rf/dispatch-sync [:rf.route/cancel "stale-id"])
      (is (= pending-id (get-in (rf/runtime-db-value :rf/default)
                                [:rf.runtime/routing :pending-navigation :id]))
          "cancel with the wrong id leaves the pending navigation intact")
      (rf/dispatch-sync [:rf.route/continue "stale-id"])
      (is (= :editor/article (get-in (rf/runtime-db-value :rf/default)
                                     [:rf.runtime/routing :current :route-id]))
          "continue with the wrong id does not navigate")
      (is (= pending-id (get-in (rf/runtime-db-value :rf/default)
                                [:rf.runtime/routing :pending-navigation :id]))
          "continue with the wrong id leaves the pending navigation intact")
      (rf/dispatch-sync [:rf.route/cancel pending-id])
      (is (nil? (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :pending-navigation]))
          "cancel with the matching id clears the slot"))))

;; ---- rf2-ee38b.8: :rf.route/navigation-blocked is a DISPATCHED event --
;;
;; Spec 012 §Navigation blocking §Default flow step 4d: the runtime
;; DISPATCHES [:rf.route/navigation-blocked pending-nav]. An app that
;; registers its own :rf.route/navigation-blocked handler must see it
;; fire. Pre-fix only the trace (step 4e) was emitted; no event dispatch.

(deftest navigation-blocked-is-dispatched-as-an-event
  (testing "rf2-ee38b.8: a :can-leave rejection DISPATCHES
            [:rf.route/navigation-blocked pending-nav] so an
            app-registered handler fires (Spec 012 §Default flow 4d)"
    (let [seen (atom nil)]
      (rf/reg-route :editor/article
                    {:params    [:map [:id :string]]
                     :can-leave :editor/can-leave?} "/editor/articles/:id")
      (rf/reg-route :route/cart {} "/cart")
      (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
      (rf/reg-sub :editor/can-leave?
                  (fn [db _] (not (get-in db [:editor :dirty?]))))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; App registers its OWN handler over the framework default no-op.
      (rf/reg-event :rf.route/navigation-blocked
                       (fn [_ [_ pending-nav]]
                         (reset! seen pending-nav)
                         {}))
      (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (is (some? @seen)
          "app-registered :rf.route/navigation-blocked handler fired")
      (is (= "/cart" (:requested-url @seen))
          "the dispatched event carried the pending-nav map as its arg")
      (is (= :editor/article (:rejecting-route @seen))
          "pending-nav names the rejecting route")
      (is (= :editor/can-leave? (:rejecting-guard @seen))
          "pending-nav names the rejecting guard sub-id"))))

(deftest can-leave-non-boolean-trace-tags-real-route-id
  (testing "rf2-ee38b.8: :rf.error/can-leave-non-boolean tags :route-id
            with the route-id KEYWORD, not the :path pattern string"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/set-dirty
                     (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    ;; Polarity bug: returns a truthy non-boolean (42).
    (rf/reg-sub :editor/leave? (fn [db _] (get-in db [:editor :dirty?])))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/set-dirty 42])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::nb-id (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/unregister-listener! :trace ::nb-id)
      (let [nb (first (filter #(= :rf.error/can-leave-non-boolean (:operation %))
                              @traces))]
        (is (some? nb) ":rf.error/can-leave-non-boolean fired")
        (is (= :editor/article (-> nb :tags :route-id))
            ":route-id is the route-id KEYWORD, not the \"/editor/articles/:id\" path string")))))

;; ============================================================================
;; rf2-5pyyl — :can-leave non-boolean → BLOCK + :rf.error/can-leave-non-boolean
;; ============================================================================

(deftest can-leave-non-boolean-blocks-navigation
  (testing "a :can-leave sub that returns a non-boolean truthy value
            BLOCKS navigation and emits :rf.error/can-leave-non-boolean
            (rf2-5pyyl). Closed contract — blocking ensures the polarity
            bug (returning the dirty-flag value rather than (not dirty?))
            cannot silently strand form state."
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/set-dirty
                     (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    ;; Polarity bug: return the dirty-flag directly (truthy when dirty).
    ;; A truthy non-boolean must BLOCK nav (rf2-5pyyl).
    (rf/reg-sub :editor/leave?
                (fn [db _] (get-in db [:editor :dirty?])))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    ;; A non-truthy non-false value (`nil`) would also be a non-boolean
    ;; — but we want to specifically exercise the "truthy non-boolean"
    ;; polarity bug, so dirty the editor first.
    (rf/dispatch-sync [:editor/set-dirty 42])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::can-leave-nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/unregister-listener! :trace ::can-leave-nb)
      (let [db        (rf/runtime-db-value :rf/default)
            pending   (get-in db [:rf.runtime/routing :pending-navigation])
            nb-traces (filter #(= :rf.error/can-leave-non-boolean
                                   (:operation %))
                              @traces)]
        (is (= :editor/article (get-in db [:rf.runtime/routing :current :route-id]))
            "navigation BLOCKED — slice still on the source route")
        (is (some? pending)
            ":rf/pending-navigation slot is populated (block path)")
        (is (= :rf.error/can-leave-non-boolean
               (-> nb-traces first :operation))
            ":rf.error/can-leave-non-boolean trace fired (rf2-5pyyl)")
        (is (= 42 (-> nb-traces first :tags :value))
            "trace carries the offending non-boolean value")
        (is (= :blocked-navigation (-> nb-traces first :recovery))
            "trace hoists :recovery :blocked-navigation (Spec 009 §error contract)")))))

;; ============================================================================
;; rf2-dbmj6x — can-leave / external-url diagnostics carry :frame
;; ============================================================================
;;
;; The finding: `can_leave.cljc` had `frame` / `frame-id` in hand at every
;; emit site but did not stamp it — `:rf.error/can-leave-non-boolean`,
;; `:rf.warning/can-leave-subs-artefact-missing`,
;; `:rf.route/navigation-blocked`, and `:rf.route/external-url-requested`
;; all emitted untagged. Because `re-frame.epoch.capture/capture-event!`
;; admits ONLY frame-tagged traces (capture.cljc §168-221) and the
;; frame-level trace-disable gate (`re-frame.trace/emit!` §397-398) keys off
;; `:tags :frame`, those frame-known diagnostics dropped from epoch / Xray
;; AND leaked past a `:rf.trace/frame-no-emit?` tool frame. These tests use
;; a NON-DEFAULT frame so a regression that drops the tag fails here.

(deftest navigation-blocked-trace-carries-frame-rf2-dbmj6x
  (testing "rf2-dbmj6x: :rf.route/navigation-blocked stamps :frame for a
            non-default frame"
    (rf/reg-frame :rf/default {})
    (rf/reg-frame :route/owner {})
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"] {:frame :route/owner})
    (rf/dispatch-sync [:editor/dirty true] {:frame :route/owner})
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-blocked (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}] {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-blocked)
      (is (some (fn [ev]
                  (and (= :rf.route/navigation-blocked (:operation ev))
                       (= :editor/can-leave? (-> ev :tags :rejecting-guard))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.route/navigation-blocked carries :frame :route/owner (pre-fix: absent)"))))

(deftest can-leave-non-boolean-trace-carries-frame-rf2-dbmj6x
  (testing "rf2-dbmj6x: :rf.error/can-leave-non-boolean stamps :frame for a
            non-default frame (the guard already resolves the sub against it)"
    (rf/reg-frame :rf/default {})
    (rf/reg-frame :route/owner {})
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave [:editor/leave?]} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-event :editor/set-dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    ;; Polarity bug: return the dirty-flag directly → truthy non-boolean.
    (rf/reg-sub :editor/leave? (fn [db _] (get-in db [:editor :dirty?])))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"] {:frame :route/owner})
    (rf/dispatch-sync [:editor/set-dirty 42] {:frame :route/owner})
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}] {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-nb)
      (is (some (fn [ev]
                  (and (= :rf.error/can-leave-non-boolean (:operation ev))
                       (= 42 (-> ev :tags :value))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.error/can-leave-non-boolean carries :frame :route/owner (pre-fix: absent)"))))

(deftest can-leave-subs-artefact-missing-trace-carries-frame-rf2-dbmj6x
  (testing "rf2-dbmj6x: :rf.warning/can-leave-subs-artefact-missing stamps
            :frame for a non-default frame when the subs hook is unbound"
    (rf/reg-frame :rf/default {})
    (rf/reg-frame :route/owner {})
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-route :route/cart {} "/cart")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"] {:frame :route/owner})
    ;; Unbind the subs hook so `can-leave?` falls through to the
    ;; subs-artefact-missing warning branch (the consumer-opted-out path).
    (let [prior (late-bind/get-fn :subs/subscribe-once)]
      (try
        (late-bind/set-fn! :subs/subscribe-once nil)
        (let [traces (atom [])]
          (rf/register-listener! :trace ::dbmj6x-missing (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:rf/url-requested {:url "/cart"}] {:frame :route/owner})
          (rf/unregister-listener! :trace ::dbmj6x-missing)
          (is (some (fn [ev]
                      (and (= :rf.warning/can-leave-subs-artefact-missing (:operation ev))
                           (= :route/owner (-> ev :tags :frame))))
                    @traces)
              ":rf.warning/can-leave-subs-artefact-missing carries :frame :route/owner"))
        (finally
          ;; Restore the hook (it lives in core/subs.cljc, which the routing
          ;; fixture does NOT reload, so it must be put back explicitly).
          (late-bind/set-fn! :subs/subscribe-once prior))))))

(deftest external-url-requested-trace-carries-frame-rf2-dbmj6x
  (testing "rf2-dbmj6x: :rf/url-requested external-URL branch stamps :frame
            for a non-default frame (symmetric with the programmatic
            `:rf.route/navigate {:url ...}` external path, which already tags)"
    (rf/reg-frame :rf/default {})
    (rf/reg-frame :route/owner {})
    (rf/reg-route :route/home {} "/")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/"] {:frame :route/owner})
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-external (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "https://example.invalid/cart"}]
                        {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-external)
      (is (some (fn [ev]
                  (and (= :rf.route/external-url-requested (:operation ev))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.route/external-url-requested carries :frame :route/owner (pre-fix: absent)"))))

;; ============================================================================
;; rf2-dqlfty — nav-guard phase fails CLOSED on a throwing/hostile URL
;; ============================================================================
;;
;; The finding: `pending-target` (the target the leave/enter guards branch
;; on) called raw `registry/match-url`, bypassing `match-url-fail-closed` —
;; the SAME wrapper `url-change-fx` already routes through (rf2-6t1xb).
;; `pending-target` runs UNCONDITIONALLY inside `maybe-block-navigation`,
;; even when the route declares no `:can-leave` / `:can-enter` at all, so
;; any unexpected throw out of `match-url` escaped the guard phase and
;; crashed the event drain for EVERY nav entry point (`:rf/url-requested`,
;; `:rf.route/navigate`, `:rf.route/transitioned`,
;; `:rf.route/handle-url-change`) instead of failing closed to
;; `:rf.route/not-found` like a bare miss.

(deftest nav-guard-hostile-url-fails-closed-not-found-rf2-dqlfty
  (testing "rf2-dqlfty: a URL that makes `match-url` THROW during the
            nav-guard phase must not crash the event drain — the
            guard-phase target now resolves through `match-url-fail-closed`
            exactly like `url-change-fx`, so the throw is swallowed and the
            navigation proceeds to :rf.route/not-found instead of an
            uncaught exception escaping `dispatch-sync`"
    (rf/reg-route :route/home {} "/")
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/"])
    (with-redefs [registry/match-url
                  (fn [_] (throw (ex-info "simulated hostile-URL parse failure" {})))]
      ;; Pre-fix this call throws straight out of dispatch-sync (crashes
      ;; the event drain); post-fix it completes cleanly.
      (rf/dispatch-sync [:rf/url-requested {:url "/hostile"}])
      (is (= :rf.route/not-found
             (get-in (rf/runtime-db-value :rf/default)
                     [:rf.runtime/routing :current :route-id]))
          "the throwing URL fails closed to :rf.route/not-found rather than
           crashing the event drain"))))

(deftest nav-guard-hostile-url-does-not-bypass-declared-can-leave-guard-rf2-dqlfty
  (testing "rf2-dqlfty: the fail-closed target still lets a DECLARED
            :can-leave guard run against the CURRENT route (only the
            TARGET half of `pending-target` — derived from the hostile
            URL — is nil'd out); a dirty-form guard still blocks a hostile
            navigation attempt rather than silently crashing past it"
    (rf/reg-route :editor/article
                  {:params    [:map [:id :string]]
                   :can-leave :editor/can-leave?} "/editor/articles/:id")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (with-redefs [registry/match-url
                  (fn [_] (throw (ex-info "simulated hostile-URL parse failure" {})))]
      (rf/dispatch-sync [:rf/url-requested {:url "/hostile"}])
      (is (some? (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/routing :pending-navigation]))
          "the dirty-form :can-leave guard still blocks the throwing URL —
           no crash, no silent bypass")
      (is (= :editor/article
             (get-in (rf/runtime-db-value :rf/default)
                     [:rf.runtime/routing :current :route-id]))
          "the active route is unchanged (leave was blocked, not crashed
           past)"))))
