(ns re-frame.routing-nav-token-test
  "Navigation-token stale-result-suppression + `:on-match` loader tests
  for re-frame.routing (nav-token allocation, the `:nav-token` cofx, the
  `:rf.route/with-nav-token` fx, and multi-loader `:on-match` ordering /
  error precedence). Split from routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- Spec 012 §Navigation tokens — stale-result suppression --------------

(deftest routing-nav-token-staleness
  (testing "two in-flight navigations: the older nav-token's result is suppressed"
    ;; Per Spec 012 §Navigation tokens — stale-result suppression: each
    ;; navigation allocates a fresh nav-token; async results carry the
    ;; token captured at request time; when a result arrives whose token
    ;; mismatches the current slice's :nav-token, the runtime suppresses
    ;; it and emits :rf.route.nav-token/stale-suppressed.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))

    (let [traces (atom [])]
      (rf/register-listener! ::nav-token (fn [ev] (swap! traces conj ev)))

      ;; 1. Navigate to /articles/A. nav-token allocates → "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's response lands, navigate to /articles/B → "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= "nav-2" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale response carries "nav-1"; current is "nav-2";
      ;; the runtime suppresses [:article/loaded "A" "A-payload"].
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "A" "A-payload"]
                          :carried-nav-token  "nav-1"}])

      ;; 4. B's response carries "nav-2"; matches current; commits.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "B" "B-payload"]
                          :carried-nav-token  "nav-2"}])

      (rf/unregister-listener! ::nav-token)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "only B's payload committed; A's was suppressed")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "expected :rf.route.nav-token/stale-suppressed trace for the A response")

      ;; rf2-zqefg3.5 — the suppression trace is joined to the route
      ;; work-id `[:rf.work/route route-id nav-token loader-id]`
      ;; (EP-0011 §Route Loader Completion). The carried (stale) token
      ;; rides in the work-id tuple, so the suppressed attempt is
      ;; correlatable by `:work/id` in the trace stream.
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= [:rf.work/route :route/article "nav-1" :article/loaded]
                          (-> ev :tags :work/id))))
                @traces)
          "the stale-suppressed trace is joined to the route :work/id (the carried nav-token rides in the tuple)"))))

(deftest with-nav-token-fx-suppresses-stale-do-and-commits-fresh
  (testing ":rf.route/with-nav-token fx: stale `:do` is suppressed; fresh `:do` runs"
    ;; Per Spec 012 §Navigation tokens §Threading: a user event handler
    ;; emits an `:fx` entry of the form
    ;;
    ;;   [:rf.route/with-nav-token {:do        [:dispatch [<ev> args...]]
    ;;                              :nav-token <captured-token>}]
    ;;
    ;; …and the runtime threads the carried token against the current
    ;; route slice's `:nav-token` (read from
    ;; `[:rf.runtime/routing :current :nav-token]`). Match → the inner
    ;; fx runs (canonically a
    ;; `:dispatch` to the success continuation). Mismatch → the inner fx
    ;; is suppressed and `:rf.route.nav-token/stale-suppressed` emits.
    ;;
    ;; This test pins both branches via the production fx (no use of
    ;; the test-only `:rf.test/simulate-http-resolution` event). The
    ;; `:article/loaded` continuation is the user-facing handler the
    ;; wrapped dispatch would commit through; we observe it via the
    ;; resulting app-db slice.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))
    ;; Bridge event: a real :on-success handler. Carries the token it
    ;; captured at request time and re-emits an `:rf.route/with-nav-token`
    ;; fx entry. The runtime then either dispatches `[:article/loaded ...]`
    ;; (match) or suppresses (mismatch).
    (rf/reg-event-fx :article/loaded-via-nav-token
                     (fn [_ctx [_ {:keys [carried-token id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:do        [:dispatch [:article/loaded id payload]]
                               :nav-token carried-token}]]}))

    (let [traces (atom [])]
      (rf/register-listener! ::with-nav-token-fx
                             (fn [ev] (swap! traces conj ev)))

      ;; 1. Land on :route/article id="A" — nav-token allocates to "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's async :on-success lands, navigate to id="B" — "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= "nav-2" (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/routing :current :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale :on-success arrives carrying "nav-1" via the fx
      ;; wrapper. Current is "nav-2"; the inner :dispatch must be
      ;; suppressed and the trace must fire.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token "nav-1"
                          :id            "A"
                          :payload       "A-payload"}])

      ;; 4. B's fresh :on-success arrives carrying "nav-2"; matches
      ;; current; inner :dispatch fires; :article/loaded commits.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token "nav-2"
                          :id            "B"
                          :payload       "B-payload"}])

      (rf/unregister-listener! ::with-nav-token-fx)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "fresh :do ran end-to-end; stale :do was suppressed before commit")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "stale :do produced :rf.route.nav-token/stale-suppressed with the inner dispatch's event-id")

      ;; rf2-zqefg3.5 — the production fx path joins the suppression
      ;; trace to the route work-id. `route-id` is read from the live
      ;; slice (`:route/article`, the route B is on); `nav-token` is the
      ;; carried (stale) token "nav-1"; `loader-id` is the suppressed
      ;; inner dispatch's event-id.
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= [:rf.work/route :route/article "nav-1" :article/loaded]
                          (-> ev :tags :work/id))))
                @traces)
          "the production :rf.route/with-nav-token suppression is joined to the route :work/id")

      ;; Negative: no spurious suppressed-trace for the fresh path.
      (is (= 1 (count (filter (fn [ev]
                                (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev)))
                              @traces)))
          "exactly one stale-suppressed trace fired — the fresh :do did NOT trip the validation"))))

;; ---- Spec 012 §Navigation tokens step 2 — the `:nav-token` cofx ----------
;;
;; rf2-8fnwq: the spec promised an `:on-match`-reachable `:nav-token` cofx
;; (step 2 / step 4 "validating cofx") but no `reg-cofx :nav-token` was
;; registered. A handler that followed the spec — declared
;; `(inject-cofx :nav-token)` and read `{:keys [db nav-token]}` — threaded
;; `nil`, which mismatched the current token in `:rf.route/with-nav-token`
;; EVERY time, so the documented stale-suppression pattern silently ate
;; the result. These tests are the failing-before / passing-after guard.

(deftest nav-token-cofx-injects-the-live-token
  (testing "(inject-cofx :nav-token) injects the current slice token — not nil"
    ;; The minimal contract: a handler declaring the cofx sees the live
    ;; navigation epoch. Pre-fix this was nil (no reg-cofx :nav-token).
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [seen (atom :unset)]
      ;; An :on-match-reached handler that captures the injected token.
      (rf/reg-event-fx :article/capture-token
                       [(rf/inject-cofx :nav-token)]
                       (fn [{:keys [nav-token]} _]
                         (reset! seen nav-token)
                         {}))
      ;; Land on the route, then fire the on-match-style continuation.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (let [current (get-in (rf/runtime-db-value :rf/default)
                            [:rf.runtime/routing :current :nav-token])]
        (rf/dispatch-sync [:article/capture-token])
        (is (= current @seen)
            "the cofx injected the slice's live :nav-token (pre-fix: nil)")
        (is (some? @seen)
            "the injected token is non-nil (pre-fix the documented shape threaded nil)")))))

(deftest nav-token-cofx-drives-documented-stale-suppression
  (testing "the spec step-2/step-3 example runs: capture via cofx, thread via
            :rf.route/with-nav-token; stale → suppressed, fresh → applied"
    ;; This is the documented pattern end-to-end, using ONLY the public
    ;; surface (no :rf.test/simulate-http-resolution). The :on-match-reached
    ;; loader declares the cofx and captures `nav-token` live; we stash the
    ;; captured token so the test can replay the async completion AFTER a
    ;; superseding navigation (modelling a real out-of-order http race —
    ;; A's request started first but its response lands after B's). The
    ;; completion threads the captured token through :rf.route/with-nav-token,
    ;; which validates against the current slice: stale → suppressed,
    ;; fresh → applied.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Terminal commit handler.
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))
    ;; The async completion: threads a captured token through the framework
    ;; fx, which validates against the current slice.
    (rf/reg-event-fx :article/completed
                     (fn [_ctx [_ {:keys [captured-token id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:do        [:dispatch [:article/loaded id payload]]
                               :nav-token captured-token}]]}))

    (let [traces   (atom [])
          captured (atom {})]
      (rf/register-listener! ::cofx-flow (fn [ev] (swap! traces conj ev)))
      ;; The :on-match-reached loader: declares the cofx, captures the live
      ;; token at scheduling time (exactly the documented step-2 shape).
      ;; The capture closes over `captured` so the test replays the
      ;; completion later, out of order.
      (rf/reg-event-fx :article/load
                       [(rf/inject-cofx :nav-token)]
                       (fn [{:keys [nav-token]} [_ id]]
                         (swap! captured assoc id nav-token)
                         {}))

      ;; 1. Navigate to A; the loader captures A's token via the cofx.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:article/load "A"])

      ;; 2. Navigate to B BEFORE A's response lands — fresh token captured.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (rf/dispatch-sync [:article/load "B"])

      ;; 3. A's response lands LATE, carrying the stale cofx-captured token.
      (rf/dispatch-sync [:article/completed
                         {:captured-token (@captured "A") :id "A" :payload "A-payload"}])
      ;; 4. B's response lands, carrying the fresh cofx-captured token.
      (rf/dispatch-sync [:article/completed
                         {:captured-token (@captured "B") :id "B" :payload "B-payload"}])

      (rf/unregister-listener! ::cofx-flow)

      (is (not= (@captured "A") (@captured "B"))
          "the cofx injected DIFFERENT live tokens for the two navigations")
      (is (every? some? (vals @captured))
          "both captured tokens are non-nil (pre-fix the cofx threaded nil)")
      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "only B committed — A's stale completion was suppressed via the cofx-captured token")
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "A's stale completion produced :rf.route.nav-token/stale-suppressed")
      (is (= 1 (count (filter #(= :rf.route.nav-token/stale-suppressed
                                  (:operation %))
                              @traces)))
          "exactly one suppression — B's fresh completion applied cleanly"))))

;; ============================================================================
;; rf2-25i7r7 — finding 3: route failure semantics across multiple :on-match
;;              events (continuation + first-error-wins + final :error)
;; ============================================================================
;;
;; The navigation cascade runs inside the locked FIFO run-to-completion
;; drain (Spec 002), which does not cancel already-queued events.
;; `commit-navigation` queues every :on-match dispatch (and the FIFO
;; settle) up front; the on-match-error trap dispatches its :error flip to
;; the BACK of the queue. So a later loader runs after an earlier one
;; fails (documented continuation), and the slice lands :error regardless
;; of interleaving. When MULTIPLE loaders throw, first-error-wins.

(deftest on-match-later-loader-runs-after-earlier-failure
  (testing "rf2-25i7r7 finding 3: an :on-match [[:load/fail] [:load/next]]
            where the first event throws still RUNS the later loader
            (continuation under the locked FIFO drain), and the slice
            settles to :transition :error attributed to the FIRST failure"
    (let [order (atom [])]
      (rf/reg-event-db :load/fail
                       (fn [_db _]
                         (swap! order conj :fail)
                         (throw (ex-info "first-boom" {:why :test}))))
      (rf/reg-event-db :load/next
                       (fn [db _]
                         (swap! order conj :next)
                         (assoc db :load/next-ran? true)))
      (rf/reg-route :route/two-loaders
                    {:path     "/two-loaders"
                     :on-match [[:load/fail] [:load/next]]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/two-loaders"])
      (is (= [:fail :next] @order)
          "the later loader RAN after the earlier one threw (FIFO continuation)")
      (is (true? (:load/next-ran? (rf/app-db-value :rf/default)))
          "the later loader's :db write committed")
      (let [slice (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
        (is (= :error (:transition slice))
            "final :transition is :error regardless of queue interleaving")
        (is (= :load/fail (:event-id (:error slice)))
            ":rf.route/error is attributed to the FIRST failing loader")
        (is (= :load/fail (:rf.route/on-match-id (:error slice)))
            ":rf.route/on-match-id names the first failure, not the later loader")))))

(deftest on-match-first-error-wins-when-multiple-loaders-throw
  (testing "rf2-25i7r7 finding 3: when BOTH :on-match loaders throw in the
            same transition, the FIRST attributed failure is the recorded
            :rf.route/error (the second does NOT clobber it) and a declared
            :on-error dispatches EXACTLY ONCE — xstate-v5 errored-transition
            semantics"
    (let [on-error-count (atom 0)]
      (rf/reg-event-db :load/fail-1
                       (fn [_db _] (throw (ex-info "boom-1" {:n 1}))))
      (rf/reg-event-db :load/fail-2
                       (fn [_db _] (throw (ex-info "boom-2" {:n 2}))))
      (rf/reg-event-db :route/double-fail-on-error
                       (fn [db _]
                         (swap! on-error-count inc)
                         db))
      (rf/reg-route :route/double-fail
                    {:path     "/double-fail"
                     :on-match [[:load/fail-1] [:load/fail-2]]
                     :on-error [:route/double-fail-on-error]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/double-fail"])
      (let [slice (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
        (is (= :error (:transition slice))
            "final :transition is :error after both loaders throw")
        (is (= :load/fail-1 (:event-id (:error slice)))
            "first-error-wins: :rf.route/error is the FIRST failure, not clobbered by the second")
        (is (= :load/fail-1 (:rf.route/on-match-id (:error slice)))
            "first-error-wins: attribution names the first failing loader")
        (is (= 1 @on-error-count)
            ":on-error dispatched exactly once despite two throws in one transition")))))

(deftest on-match-error-then-newer-navigation-records-new-failure
  (testing "rf2-25i7r7 finding 3: the first-error-wins guard is scoped to
            the CURRENT nav-token — a NEWER navigation resets the slice off
            :error through its own commit, so a failure on the later
            navigation still records (failure-after-recovery is not
            suppressed)"
    (rf/reg-event-db :load/ok
                     (fn [db _] (assoc db :ok? true)))
    (rf/reg-event-db :load/late-fail
                     (fn [_db _] (throw (ex-info "late-boom" {}))))
    (rf/reg-route :route/clean {:path "/clean" :on-match [[:load/ok]]})
    (rf/reg-route :route/dirty {:path "/dirty" :on-match [[:load/late-fail]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; First navigation succeeds (slice :idle), then a second navigation to
    ;; a failing route must still record :error.
    (rf/dispatch-sync [:rf.route/transitioned "/clean"])
    (is (= :idle (get-in (rf/runtime-db-value :rf/default)
                         [:rf.runtime/routing :current :transition]))
        "clean navigation settles to :idle")
    (rf/dispatch-sync [:rf.route/transitioned "/dirty"])
    (let [slice (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current])]
      (is (= :error (:transition slice))
          "the newer failing navigation records :error (not suppressed by a prior token's state)")
      (is (= :load/late-fail (:event-id (:error slice)))
          "the new failure is attributed to the new navigation's loader"))))
