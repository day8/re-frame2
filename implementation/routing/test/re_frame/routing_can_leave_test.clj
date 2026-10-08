(ns re-frame.routing-can-leave-test
  "Leave-guard + pending-navigation protocol tests for re-frame.routing
  (`:can-leave`, `:rf/pending-navigation`, `:rf.route/continue` /
  `:rf.route/cancel` / `:rf.route/navigation-blocked`).

  ## Posture split

  Leave-guard SEMANTICS are production-real and are asserted here WITHOUT a
  posture guard: a rejecting `:can-leave` blocks the transition, a truthy
  NON-BOOLEAN return fails CLOSED, the `:rf/pending-navigation`
  slot is written with `:rejecting-route` / `:rejecting-guard` /
  `:requested-url`, an unbound `:subs/subscribe-once` hook degrades to
  ALLOW, and an external URL never touches the routing slice. Those run in
  the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  The `:trace` stream is not
  production-real: every `trace/emit!` / `trace/emit-error!` site sits behind
  `rf.interop/debug-enabled?`, read once at load time, so under the real gate the
  framework emits nothing here BY DESIGN. Trace assertions are correct
  dev-posture coverage and sit inside `(when
  rf.interop/debug-enabled? …)` arms marked as dev-instrumentation arms.

  Where the trace would be the ONLY witness — the four `:frame`-stamp
  tests, one of which also reads `:rejecting-guard` off the trace — a
  production-visible witness sits beside it: the
  same facts are readable off the frame's runtime-db, because
  `re-frame.routing.decisions/decide` writes `:rejecting-route` and
  `:rejecting-guard` into the pending-navigation slot itself."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- routing
  ([] (routing :rf/default))
  ([frame] (get-in (:rf.db/runtime (rf/frame-state-value frame)) [:rf.runtime/routing])))

(defn- pending
  ([] (pending :rf/default))
  ([frame] (:pending-navigation (routing frame))))

(defn- current-id
  ([] (current-id :rf/default))
  ([frame] (:route-id (:current (routing frame)))))

(defn- editor!
  "An `:editor/article` route guarded by `can-leave` (`:editor/can-leave?` is
  false while the editor is dirty), a `:route/cart` target, the `:editor/dirty`
  event and a no-op push fx."
  [can-leave]
  (rf/reg-route :editor/article
                {:params [:map [:id :string]] :can-leave can-leave} "/editor/articles/:id")
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
  (rf/reg-sub :editor/can-leave? (fn [db _] (not (get-in db [:editor :dirty?]))))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- dirty-editor!
  "Land `frame` on the editor and dirty it with `v`, so leaving blocks."
  ([] (dirty-editor! :rf/default true))
  ([frame v]
   (rf/dispatch-sync [:rf.route/handle-url-change "/editor/articles/A" {:rf.route/cause :link}]
                     {:frame frame})
   (rf/dispatch-sync [:editor/dirty v] {:frame frame})))

;; ---- Spec 012 §Navigation blocking — pending-nav protocol ----------------

(deftest routing-pending-nav-protocol
  (testing "block via :can-leave; cancel clears the slot without navigating,
            continue clears it and completes the navigation"
    (editor! :editor/can-leave?)
    (dirty-editor!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (is (= [true :editor/article] [(some? (pending)) (current-id)])
        "blocked: the slot is populated and the route does not change")
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (= [nil :editor/article] [(pending) (current-id)]) "cancel clears; no navigation")
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (is (some? (pending)) "a second blocked request reseats the slot")
    (rf/dispatch-sync [:rf.route/continue "pn-2"])
    (is (= [nil :route/cart] [(pending) (current-id)])
        "continue clears and completes the original navigation")))

;; ---- :rf/pending-navigation full slot shape ------------------------------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol and
;; Spec-Schemas.md §:rf/pending-navigation. Tools / dialogs read
;; :rejecting-guard to render "Discard changes on Editor?" prompts.

(deftest pending-navigation-slot-shape
  (editor! :editor/can-leave?)
  (dirty-editor!)
  (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
  (let [p (pending)]
    (is (string? (:id p)) ":id is the opaque pending-nav id")
    (is (= {:requested-url   "/cart"
            :rejecting-route :editor/article
            :rejecting-guard :editor/can-leave?
            :destination     {:to :route/cart}
            :target          {:route-id :route/cart :params {} :query {} :fragment nil :url "/cart"}
            :cause           :link
            :policy          {}}
           (select-keys p [:requested-url :rejecting-route :rejecting-guard
                           :destination :target :cause :policy])))))

;; ---- :can-leave query vectors, and the leave guard on every door ---------

(deftest can-leave-query-vector-blocks-url-requested
  (testing "a Spec-shaped :can-leave query vector is subscribed directly; the
            slot stores the guard id, not the whole vector"
    (editor! [:editor/can-leave?])
    (dirty-editor!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (is (= :editor/can-leave? (:rejecting-guard (pending))))))

(deftest programmatic-navigate-runs-can-leave-guard
  (testing ":rf.route/navigate is guarded by the active route's :can-leave"
    (editor! [:editor/can-leave?])
    (let [pushed (atom [])]
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                    (fn [_ url] (swap! pushed conj url)))
      (dirty-editor!)
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/cart}])
      (is (= [true :editor/article] [(some? (pending)) (current-id)]))
      (is (empty? @pushed) "pushState is not requested for a blocked programmatic nav"))))

(deftest handle-url-change-runs-can-leave-guard
  (testing ":rf.route/handle-url-change is guarded by the active route's :can-leave"
    (editor! [:editor/can-leave?])
    (dirty-editor!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/cart"])
    (is (= [true :editor/article] [(some? (pending)) (current-id)]))))

(deftest pending-nav-continue-and-cancel-require-matching-id
  (testing ":rf.route/continue and :rf.route/cancel ignore stale pending-nav ids"
    (editor! [:editor/can-leave?])
    (dirty-editor!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (let [pending-id (:id (pending))]
      (rf/dispatch-sync [:rf.route/cancel "stale-id"])
      (is (= pending-id (:id (pending))) "cancel with the wrong id leaves the slot intact")
      (rf/dispatch-sync [:rf.route/continue "stale-id"])
      (is (= [pending-id :editor/article] [(:id (pending)) (current-id)])
          "continue with the wrong id neither navigates nor clears the slot")
      (rf/dispatch-sync [:rf.route/cancel pending-id])
      (is (nil? (pending)) "cancel with the matching id clears the slot"))))

;; ---- :rf.route/navigation-blocked is a DISPATCHED event -------------------
;;
;; Spec 012 §Navigation blocking §Default flow step 4d: the runtime
;; DISPATCHES [:rf.route/navigation-blocked pending-nav], so an app-registered
;; handler fires; the trace (step 4e) alone would not fire it.

(deftest navigation-blocked-is-dispatched-as-an-event
  (let [seen (atom nil)]
    (editor! :editor/can-leave?)
    (rf/reg-event :rf.route/navigation-blocked
                  (fn [_ [_ pending-nav]] (reset! seen pending-nav) {}))
    (dirty-editor!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (is (= {:requested-url "/cart" :rejecting-route :editor/article :rejecting-guard :editor/can-leave?}
           (select-keys @seen [:requested-url :rejecting-route :rejecting-guard]))
        "the app handler received the pending-nav map")))

;; ============================================================================
;; can-leave / external-url diagnostics carry :frame
;; ============================================================================
;;
;; `re-frame.epoch.capture/capture-event!` admits ONLY frame-tagged traces and
;; the frame-level trace-disable gate in `re-frame.trace/emit!` keys off
;; `:tags :frame`, so an untagged frame-known diagnostic would drop from epoch /
;; Xray AND leak past a `:rf.trace/frame-no-emit?` tool frame. These tests use
;; a NON-DEFAULT frame so a regression that drops the tag fails here.

(defn- two-frames! []
  (rf/make-frame {:id :rf/default})
  (rf/make-frame {:id :route/owner}))

(defmacro ^:private traced
  "The trace events emitted while `body` runs."
  [& body]
  `(let [traces# (atom [])]
     (rf/register-listener! :trace ::traced (fn [ev#] (swap! traces# conj ev#)))
     (try ~@body (finally (rf/unregister-listener! :trace ::traced)))
     @traces#))

(deftest navigation-blocked-trace-carries-frame-rf2-dbmj6x
  (two-frames!)
  (editor! :editor/can-leave?)
  (dirty-editor! :route/owner true)
  (let [traces (traced (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}] {:frame :route/owner}))]
    ;; SEMANTIC, posture-independent: the block landed in the NON-DEFAULT
    ;; frame and nowhere else.
    (is (= :editor/can-leave? (:rejecting-guard (pending :route/owner))))
    (is (nil? (pending)) ":rf/default saw no pending navigation — the block is frame-local")
    ;; Dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (is (some (fn [ev]
                  (and (= :rf.route/navigation-blocked (:operation ev))
                       (= :editor/can-leave? (-> ev :tags :rejecting-guard))
                       (= :route/owner (-> ev :tags :frame))))
                traces)
          ":rf.route/navigation-blocked carries :frame :route/owner"))))

(deftest can-leave-non-boolean-trace-carries-frame-rf2-dbmj6x
  (two-frames!)
  (editor! [:editor/leave?])
  ;; Polarity bug: return the dirty-flag directly → truthy non-boolean.
  (rf/reg-sub :editor/leave? (fn [db _] (get-in db [:editor :dirty?])))
  (dirty-editor! :route/owner 42)
  (let [traces (traced (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}] {:frame :route/owner}))]
    ;; SEMANTIC, posture-independent: the non-boolean guard failed CLOSED
    ;; against the NON-DEFAULT frame, and :rejecting-route is the route-id
    ;; KEYWORD, not the "/editor/articles/:id" path string.
    (is (= [:editor/article :editor/article]
           [(current-id :route/owner) (:rejecting-route (pending :route/owner))]))
    (is (nil? (pending)) ":rf/default saw no pending navigation — the guard is frame-local")
    ;; Dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (is (some (fn [ev]
                  (and (= :rf.error/can-leave-non-boolean (:operation ev))
                       (= 42 (-> ev :tags :value))
                       (= :editor/article (-> ev :tags :route-id))
                       (= :blocked-navigation (:recovery ev))
                       (= :route/owner (-> ev :tags :frame))))
                traces)
          ":rf.error/can-leave-non-boolean carries the offending value, the route-id
           keyword, :recovery :blocked-navigation and :frame :route/owner"))))

(deftest can-leave-subs-artefact-missing-trace-carries-frame-rf2-dbmj6x
  (two-frames!)
  (editor! :editor/can-leave?)
  (rf/dispatch-sync [:rf.route/handle-url-change "/editor/articles/A" {:rf.route/cause :link}]
                    {:frame :route/owner})
  ;; The subs hook lives in core/subs.cljc, which the routing fixture does NOT
  ;; reload, so it is put back explicitly.
  (let [prior (rf.late-bind/get-fn :subs/subscribe-once)]
    (try
      (rf.late-bind/set-fn! :subs/subscribe-once nil)
      (let [traces (traced (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}]
                                             {:frame :route/owner}))]
        ;; SEMANTIC, posture-independent: the unevaluable guard degrades to
        ;; ALLOW — :route/owner navigated and nothing was left pending.
        (is (= [:route/cart nil] [(current-id :route/owner) (pending :route/owner)]))
        ;; Dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (some (fn [ev]
                      (and (= :rf.warning/can-leave-subs-artefact-missing (:operation ev))
                           (= :route/owner (-> ev :tags :frame))))
                    traces)
              ":rf.warning/can-leave-subs-artefact-missing carries :frame :route/owner")))
      (finally
        (rf.late-bind/set-fn! :subs/subscribe-once prior)))))

(deftest external-url-requested-trace-carries-frame-rf2-dbmj6x
  (two-frames!)
  (rf/reg-route :route/home {} "/")
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :link}] {:frame :route/owner})
  (let [traces (traced (rf/dispatch-sync [:rf.route/url-requested {:url "https://example.invalid/cart"}]
                                         {:frame :route/owner}))]
    ;; SEMANTIC, posture-independent: an EXTERNAL URL is not an in-app
    ;; navigation — :route/owner stays on `/` with nothing pending.
    (is (= [:route/home nil] [(current-id :route/owner) (pending :route/owner)]))
    ;; Dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (is (some (fn [ev]
                  (and (= :rf.route/external-url-requested (:operation ev))
                       (= :route/owner (-> ev :tags :frame))))
                traces)
          ":rf.route/external-url-requested carries :frame :route/owner"))))

;; ============================================================================
;; nav-guard phase fails CLOSED on a throwing/hostile URL
;; ============================================================================
;;
;; The guard-phase target comes from `rf.routing.resolve/url-resolution`, which
;; resolves through `match-url-fail-closed` — the SAME wrapper `url-change-fx`
;; uses. It runs on every navigation, guards or not, so a raw `match-url` there
;; would let an unexpected throw escape `dispatch-sync` and crash the event
;; drain for every nav entry point instead of failing closed to
;; `:rf.route/not-found` like a bare miss.

(deftest nav-guard-hostile-url-fails-closed-not-found-rf2-dqlfty
  (rf/reg-route :route/home {} "/")
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :link}])
  (with-redefs [rf.routing.registry/match-url
                (fn [_] (throw (ex-info "simulated hostile-URL parse failure" {})))]
    (rf/dispatch-sync [:rf.route/url-requested {:url "/hostile"}])
    (is (= :rf.route/not-found (current-id))
        "the throwing URL fails closed to :rf.route/not-found rather than
         crashing the event drain")))

(deftest nav-guard-hostile-url-does-not-bypass-declared-can-leave-guard-rf2-dqlfty
  (testing "only the TARGET derived from the hostile URL degrades to a miss; a
            declared dirty-form :can-leave still runs against the CURRENT route
            and blocks the attempt"
    (editor! :editor/can-leave?)
    (dirty-editor!)
    (with-redefs [rf.routing.registry/match-url
                  (fn [_] (throw (ex-info "simulated hostile-URL parse failure" {})))]
      (rf/dispatch-sync [:rf.route/url-requested {:url "/hostile"}])
      (is (= [true :editor/article] [(some? (pending)) (current-id)])))))
