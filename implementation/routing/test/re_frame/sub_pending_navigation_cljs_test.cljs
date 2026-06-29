(ns re-frame.sub-pending-navigation-cljs-test
  "CLJS coverage for the `sub-pending-navigation` reactive-read sugar — a thin
  fn over `(subscribe [:rf/pending-navigation])` returning a reaction over the
  pending-navigation slot. It lives on the `re-frame.routing` façade (NOT
  `re-frame.core`) per the routing bundle-isolation invariant, so the test
  reaches it as `routing/sub-pending-navigation`.

  Concerns covered:
    - happy path: the sugar reads the populated pending-nav slot (a blocked
      navigation) and is value-equal to the canonical `[:rf/pending-navigation]`
      subscription vector form;
    - adversarial: nil in the steady state (no navigation pending);
    - adversarial: the `{:frame f}` opts passthrough resolves the slot from the
      named frame, isolated from the ambient default frame.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up.
  Per Spec 012 §Navigation blocking — pending-nav protocol."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; load the routing artefact so its `:rf/pending-navigation`
            ;; reg-sub, the `:can-leave` / pending-nav events, and
            ;; `sub-pending-navigation` itself are available.
            [re-frame.routing :as routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

(defn- stub-push-url!
  "No-op `:rf.nav/push-url` — the real handler touches browser history, which
  the node-test runtime has no `window` for. Mirrors sub_route_cljs_test."
  []
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- block-a-navigation!
  "Register an editor route whose `:can-leave?` guard rejects while the form is
  dirty, land on it, dirty it, then request a different URL — leaving a
  populated `:rf/pending-navigation` slot (Spec 012 §Navigation blocking).
  `opts` (e.g. `{:frame :f2}`) targets the frame the whole flow runs in."
  ([] (block-a-navigation! {}))
  ([opts]
   (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"] opts)
   (rf/dispatch-sync [:editor/dirty true] opts)
   (rf/dispatch-sync [:rf/url-requested {:url "/cart"}] opts)))

(defn- register-blocking-routes!
  "Process-global registrations the block flow needs (routes + guard sub +
  dirty event). Registrations are process-global; the slice/db they read are
  per-frame."
  []
  (stub-push-url!)
  (rf/reg-route :editor/article
                {:params    [:map [:id :string]]
                 :can-leave :editor/can-leave?} "/editor/articles/:id")
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-event :editor/dirty
                (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
  (rf/reg-sub :editor/can-leave?
              (fn [db _] (not (get-in db [:editor :dirty?])))))

(deftest sub-pending-navigation-returns-slot-reaction
  (testing "sub-pending-navigation reads the populated pending-nav slot,
            value-equal to [:rf/pending-navigation]"
    (register-blocking-routes!)
    (block-a-navigation!)
    (let [pending @(routing/sub-pending-navigation)]
      (is (some? pending) "the sugar reads the populated pending-nav slot")
      (is (= "/cart" (:requested-url pending))
          "the blocked target URL is carried on the slot")
      ;; the sugar resolves through the SAME framework sub as the vector form.
      (is (= @(rf/subscribe [:rf/pending-navigation])
             @(routing/sub-pending-navigation))
          "sub-pending-navigation value == [:rf/pending-navigation] value"))))

(deftest sub-pending-navigation-nil-in-steady-state
  (testing "sub-pending-navigation yields nil when no navigation is pending
            (adversarial — the steady state)"
    (rf/reg-frame :sub-pending/fresh {:doc "frame with no pending navigation"})
    (is (nil? @(routing/sub-pending-navigation {:frame :sub-pending/fresh}))
        "no pending-nav slot in the steady state")))

(deftest sub-pending-navigation-frame-opts-passthrough
  (testing "the {:frame f} opts passthrough resolves the slot from the named
            frame, isolated from the ambient default frame (adversarial)"
    (register-blocking-routes!)
    (rf/reg-frame :sub-pending/f2 {:doc "second frame for the passthrough test"})
    ;; block a navigation ONLY in :sub-pending/f2 — the default frame never
    ;; navigates, so its pending-nav slot stays nil.
    (block-a-navigation! {:frame :sub-pending/f2})
    (is (= "/cart" (:requested-url @(routing/sub-pending-navigation
                                      {:frame :sub-pending/f2})))
        "{:frame f2} reads f2's pending-nav slot")
    (is (= @(rf/subscribe :sub-pending/f2 [:rf/pending-navigation])
           @(routing/sub-pending-navigation {:frame :sub-pending/f2}))
        "opts passthrough == the 2-arity frame-targeted vector subscription")
    (is (nil? @(routing/sub-pending-navigation))
        "the ambient default frame has no pending nav — frame isolation holds")))
