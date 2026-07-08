(ns re-frame.routing-can-enter-test
  "Enter-guard tests for re-frame.routing (rf2-p69yaz Option A — `:can-enter`
  as the first-class mirror of `:can-leave`). Covers the ONE gate blocking
  through EACH of the three entry doors (navigate / link / popstate), the
  `:direction` field, the `:rf.route/entry-blocked` event, continue RE-RUNNING
  `:can-enter`, the loop guard, the closed non-boolean contract, and the
  `:bypass-guards?` set. Adversarial + negative fixtures per the bead brief."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(defn- register-common!
  "Register a `:home` route, an `:account` target guarded by `:can-enter`
  (`:auth/signed-in?` reads `[:auth :signed-in?]`), a `:login` fallback, and a
  no-op `:rf.nav/push-url`. Returns nothing — the caller lands the frame."
  []
  (rf/reg-route :home    {} "/home")
  (rf/reg-route :account {:can-enter [:auth/signed-in?]} "/account")
  (rf/reg-route :login   {} "/login")
  (rf/reg-event :auth/set (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:auth :signed-in?] v)}))
  (rf/reg-sub :auth/signed-in? (fn [db _] (boolean (get-in db [:auth :signed-in?]))))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- current-id []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :route-id]))

(defn- pending []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :pending-navigation]))

;; ---- :can-enter BLOCKS through EACH of the three doors --------------------

(deftest can-enter-blocks-through-navigate-door
  (testing "programmatic :rf.route/navigate to a :can-enter target BLOCKS when
            the guard returns false (fail-closed, :direction :enter)"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])   ;; land signed-out
    (rf/dispatch-sync [:rf.route/navigate :account])
    (let [p (pending)]
      (is (some? p) "programmatic entry to a guarded route sets the pending slot")
      (is (= :enter    (:direction p)) ":direction is :enter")
      (is (= :can-enter (:reason p))   ":reason is :can-enter")
      (is (= :account   (:rejecting-route p)) ":rejecting-route is the TARGET route")
      (is (= :auth/signed-in? (:rejecting-guard p)) ":rejecting-guard names the :can-enter sub"))
    (is (= :home (current-id)) "the slice did NOT move — entry was refused")))

(deftest can-enter-blocks-through-link-door
  (testing ":rf/url-requested (a link click) to a :can-enter target BLOCKS
            (the classic fail-open door — must fail CLOSED here)"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf/url-requested {:url "/account"}])
    (is (some? (pending)) "link click to a guarded route sets the pending slot")
    (is (= :enter (:direction (pending))))
    (is (= :home (current-id)) "no transition — the link click was refused")))

(deftest can-enter-blocks-through-popstate-door
  (testing ":rf.route/handle-url-change (deep-link / popstate) to a :can-enter
            target BLOCKS (the other classic fail-open door)"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf.route/handle-url-change "/account"])
    (is (some? (pending)) "popstate / deep-link to a guarded route sets the pending slot")
    (is (= :enter (:direction (pending))))
    (is (= :home (current-id)) "no transition — the deep-link was refused")))

;; ---- entry-blocked event + trace phase tag -------------------------------

(deftest entry-blocked-event-and-phase-trace
  (testing ":rf.route/entry-blocked fires with :direction :enter and the trace
            carries :phase :can-enter (021 §7 route-phase taxonomy)"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [events (atom [])
          traces (atom [])]
      (rf/reg-event :rf.route/entry-blocked
                    (fn [_ [_ pn]] (swap! events conj pn) {}))
      (rf/register-listener! :trace ::enter (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :account])
      (rf/unregister-listener! :trace ::enter)
      (is (= 1 (count @events)) ":rf.route/entry-blocked was dispatched once")
      (is (= :enter (:direction (first @events))) "the dispatched pending-nav carries :direction :enter")
      (is (some (fn [ev]
                  (and (= :rf.route/entry-blocked (:operation ev))
                       (= :can-enter (-> ev :tags :phase))
                       (= :enter (-> ev :tags :direction))))
                @traces)
          "the entry-blocked trace carries :phase :can-enter + :direction :enter"))))

;; ---- continue RE-RUNS :can-enter (rf2-p69yaz point 6) --------------------

(deftest continue-re-runs-can-enter
  (testing "continue RE-RUNS :can-enter: still-signed-out re-blocks; sign in then
            continue completes the entry"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf.route/navigate :account])
    (let [p1 (pending)]
      (is (some? p1) "entry blocked while signed out")
      ;; continue WITHOUT signing in — :can-enter re-runs and re-blocks.
      (rf/dispatch-sync [:rf.route/continue (:id p1)])
      (is (some? (pending)) "continue re-ran :can-enter and RE-BLOCKED (still signed out)")
      (is (= :home (current-id)) "no entry — the guard still refuses"))
    ;; now sign in, continue again — :can-enter re-runs and PASSES.
    (rf/dispatch-sync [:auth/set true])
    (let [p2 (pending)]
      (rf/dispatch-sync [:rf.route/continue (:id p2)])
      (is (nil? (pending)) "continue cleared the slot")
      (is (= :account (current-id)) "continue re-ran :can-enter — now signed in, entry completes"))))

;; ---- loop guard (rf2-p69yaz point 7) -------------------------------------

(deftest can-enter-loop-guard-fails-closed
  (testing "a :can-enter that keeps re-blocking across repeated continue resumes
            eventually emits :rf.error/route-guard-loop and STOPS re-issuing"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::loop (fn [ev] (swap! traces conj ev)))
      ;; First block, then keep continuing without ever signing in.
      (rf/dispatch-sync [:rf.route/navigate :account])
      (dotimes [_ 12]
        (when-let [p (pending)]
          (rf/dispatch-sync [:rf.route/continue (:id p)])))
      (rf/unregister-listener! :trace ::loop)
      (is (some (fn [ev] (= :rf.error/route-guard-loop (:operation ev))) @traces)
          ":rf.error/route-guard-loop fired once the loop ceiling was crossed")
      (is (nil? (pending))
          "the loop guard cleared the slot rather than stranding a resumable one")
      (is (= :home (current-id)) "the navigation never completed — fail-closed"))))

;; ---- closed non-boolean contract -----------------------------------------

(deftest can-enter-non-boolean-blocks-and-signals
  (testing "a :can-enter sub returning a non-boolean BLOCKS and emits
            :rf.error/can-enter-non-boolean (closed contract mirror)"
    (rf/reg-route :home    {} "/home")
    (rf/reg-route :account {:can-enter [:auth/weird?]} "/account")
    (rf/reg-sub :auth/weird? (fn [_ _] "yes"))   ;; non-boolean → BLOCK
    (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :account])
      (rf/unregister-listener! :trace ::nb)
      (is (some (fn [ev]
                  (and (= :rf.error/can-enter-non-boolean (:operation ev))
                       (= :account (-> ev :tags :route-id))))
                @traces)
          ":rf.error/can-enter-non-boolean fired, tagged with the target route")
      (is (some? (pending)) "the non-boolean return BLOCKED (fail-closed)")
      (is (= :home (current-id)) "the entry was refused"))))

;; ---- guard sub receives the pending target as an argument ----------------

(deftest can-enter-guard-receives-target-argument
  (testing "the :can-enter sub receives the pending TARGET appended to its
            query — (fn [db [_ target]]) — so it can branch on the destination"
    (rf/reg-route :home    {} "/home")
    (rf/reg-route :account {:can-enter [:auth/target-aware?]} "/account")
    (let [seen (atom nil)]
      (rf/reg-sub :auth/target-aware?
                  (fn [_ [_ target]]
                    (reset! seen target)
                    ;; allow only when the target is the /account URL we expect
                    (= "/account" (:url target))))
      (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (rf/dispatch-sync [:rf.route/navigate :account])
      (is (map? @seen) "the guard received a target map argument")
      (is (= :account (:route-id @seen)) "the target carries the resolved :route-id")
      (is (= "/account" (:url @seen)) "the target carries the requested :url")
      (is (= :account (current-id)) "guard allowed — entry completed (target matched)"))))

;; ---- :bypass-guards? #{:enter} skips ONLY the enter gate ------------------

(deftest bypass-guards-enter-skips-only-enter
  (testing ":bypass-guards? #{:enter} skips the :can-enter gate (enters while
            signed out); #{:leave} does NOT skip the enter gate"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    ;; #{:enter} → enters despite the guard.
    (rf/dispatch-sync [:rf.route/navigate :account {} {:bypass-guards? #{:enter}}])
    (is (nil? (pending)) ":bypass-guards? #{:enter} skipped the enter gate — no block")
    (is (= :account (current-id)) "entered while signed out because :enter was bypassed")
    ;; Reset to home; #{:leave} must NOT bypass enter.
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf.route/navigate :account {} {:bypass-guards? #{:leave}}])
    (is (some? (pending)) ":bypass-guards? #{:leave} left the enter gate ACTIVE — blocked")
    (is (= :home (current-id)) "did not enter — only :leave was bypassed")))

;; ---- leave THEN enter ordering -------------------------------------------

(deftest leave-runs-before-enter
  (testing "the current route's :can-leave is consulted BEFORE the target's
            :can-enter — a leave block wins and never runs the enter guard"
    (rf/reg-route :editor  {:can-leave [:editor/clean?]} "/editor")
    (rf/reg-route :account {:can-enter [:auth/signed-in?]} "/account")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/clean? (fn [db _] (not (get-in db [:editor :dirty?]))))
    (let [enter-ran (atom false)]
      (rf/reg-sub :auth/signed-in? (fn [_ _] (reset! enter-ran true) false))
      (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor"])
      (rf/dispatch-sync [:editor/dirty true])          ;; make :can-leave block
      (rf/dispatch-sync [:rf.route/navigate :account])
      (is (= :leave (:direction (pending))) "the LEAVE guard blocked first")
      (is (false? @enter-ran) "the enter guard was NOT consulted — leave short-circuited"))))
