(ns re-frame.routing-entry-denied-test
  "EP-0037 R4 — TERMINAL entry. `:can-enter` is a closed boolean over the
  resolved target consulted through EVERY door; a rejection commits nothing,
  creates NO pending value, and dispatches `:rf.route/entry-denied` exactly
  once. Also covers the guard-per-transition-kind rules (stage 3), the
  framework no-op default handler, the URL restore on a URL-driven door, the
  SSR `403` floor, and the fresh-return auth recipe.

  ## Posture split

  Almost everything here is production-real and carries NO posture
  guard: `capture-denials!` observes the denial through a PUBLIC
  `rf/reg-event` handler, not the trace stream, and the terminal-entry
  invariants (nothing commits, no pending value, exactly-once dispatch, the
  address-bar restore, the SSR 403 floor) are read off the runtime-db and the
  SSR response. Those run in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  Trace reads — the `:rf.error/can-enter-non-boolean` tags and the NEGATIVE
  `not-any?` reads for `:rf.error/no-such-handler` and
  `:rf.error/route-guard-loop` — sit behind `trace/emit!` /
  `trace/emit-error!`, gated on `rf.interop/debug-enabled?`. Under the gate the
  ring is EMPTY by design, so a negative there would pass vacuously; each sits
  inside a `(when rf.interop/debug-enabled? …)` arm with its production-visible
  half (the deny holds) left outside."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.ssr :as rf.ssr]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- register-common!
  "A `:home` route, an `:account` target guarded by `:can-enter`
  (`:auth/signed-in?` reads `[:auth :signed-in?]`), a `:login` fallback, and
  no-op nav fxs."
  []
  (rf/reg-route :home    {} "/home")
  (rf/reg-route :account {:can-enter [:auth/signed-in?]} "/account")
  (rf/reg-route :login   {} "/login")
  (rf/reg-event :auth/set (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:auth :signed-in?] v)}))
  (rf/reg-sub :auth/signed-in? (fn [db _] (boolean (get-in db [:auth :signed-in?]))))
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- rdb [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- current-id [] (get-in (rdb) [:rf.runtime/routing :current :route-id]))
(defn- pending []    (get-in (rdb) [:rf.runtime/routing :pending-navigation]))

(defn- capture-denials!
  "Re-register `:rf.route/entry-denied` with a recorder, returning the atom
  the denial payloads land in. This REPLACES the framework default handler,
  so a test that wants to prove default-handler safety must not call it.
  Registers through the PUBLIC `rf/reg-event`, the documented recipe."
  []
  (let [seen (atom [])]
    (rf/reg-event :rf.route/entry-denied (fn [_ [_ d]] (swap! seen conj d) {}))
    seen))

;; ===========================================================================
;; Denial through EVERY door — terminal, no pending, EXACTLY ONE event
;; ===========================================================================

(deftest entry-denied-through-link-door-fires-exactly-once
  (testing "the LINK door (:rf.route/url-requested) is a TWO-HOP path
            (url-requested → handle-url-change). A denial must fire the event
            exactly ONCE, not once per hop and not zero times."
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [seen   (capture-denials!)
          pushed (atom [])]
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/url-requested {:url "/account"}])
      (is (= 1 (count @seen))
          ":rf.route/entry-denied dispatched EXACTLY once across both hops")
      (is (empty? @pushed)
          "the link door decided BEFORE pushing — no history entry was added")
      (is (= :home (current-id)) "no transition"))))

(deftest entry-denied-through-popstate-door
  (testing ":rf.route/handle-url-change (deep-link / popstate) DENIES and
            restores the address bar by REPLACE (the browser already moved)"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [seen     (capture-denials!)
          replaced (atom [])]
      (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
                 (fn [_ url] (swap! replaced conj url)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/account"])
      (is (= 1 (count @seen)) "exactly one denial")
      (is (= ["/home"] @replaced)
          "the current slice's URL was restored by replace (no history entry)")
      (is (= :home (current-id))))))

(deftest entry-denied-on-initial-load
  (testing "initial load has no current route to leave but evaluates target
            entry policy normally — a guarded deep-link is denied"
    (register-common!)
    (let [seen (capture-denials!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/account"])
      (is (= 1 (count @seen)) "exactly one denial on the initial load")
      (is (nil? (current-id)) "no route committed at all"))))

;; ---- the exactly-once floor: the framework DEFAULT handler --------------

(deftest denial-is-safe-with-no-application-handler
  (testing "the framework registers a no-op default `:rf.route/entry-denied`
            handler, so denial is safe when the app registers none — the
            dispatch resolves (no :rf.error/no-such-handler) and the deny
            still holds. This is the ZERO-times half of exactly-once."
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::d (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :account}])
      (rf/unregister-listener! :trace ::d)
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count (filter #(= :rf.route/entry-denied (:operation %)) @traces)))
            "exactly one :rf.route/entry-denied trace — counted independently of
             any application handler")
        (is (not-any? #(= :rf.error/no-such-handler (:operation %)) @traces)
            "no :rf.error/no-such-handler — the default handler resolved the dispatch"))
      (is (= :home (current-id)) "with the default handler, denial is a HARD deny"))))

(deftest application-handler-registers-cleanly-and-fires-exactly-once
  (testing "an application registers its OWN :rf.route/entry-denied through the
            PUBLIC rf/reg-event, then builds a frame: the registration coexists
            with the framework's shipped no-op default (rf/make-frame does not
            throw :rf.error/image-duplicate-id), and the app handler — not the
            default — receives the denial exactly once"
    (register-common!)
    (let [calls (atom 0)]
      (rf/reg-event :rf.route/entry-denied (fn [_ _] (swap! calls inc) {}))
      (let [probe (rf/make-frame {:id :rf2-0r6q4/probe})]
        (rf/dispatch-sync [:rf.route/navigate {:to :account}] {:frame probe})
        (is (= 1 @calls))))))

;; ---- denial payload shape ------------------------------------------------

(deftest entry-denied-payload-shape
  (testing "the denial carries :destination (a :rf/route-destination),
            :target, :cause, :requested-url and :guard — and NO :id, because
            there is nothing to continue or cancel"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [seen (capture-denials!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :account}])
      (let [d (first @seen)]
        (is (= {:destination   {:to :account}
                :target        {:route-id :account :url "/account"}
                :cause         :navigate
                :requested-url "/account"
                :guard         :auth/signed-in?}
               (-> d
                   (select-keys [:destination :target :cause :requested-url :guard])
                   (update :target select-keys [:route-id :url]))))
        (is (nil? (:id d)) "a denial has no pending id — it is terminal")))))

(deftest entry-denied-raw-url-destination-normalises
  (testing "a MATCHING raw-URL request denies with the canonical NAMED
            destination recovered from the resolved target, and :requested-url
            preserves the caller's input"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [seen (capture-denials!)]
      (rf/dispatch-sync [:rf.route/navigate {:url "/account"}])
      (is (= {:destination {:to :account} :requested-url "/account"}
             (select-keys (first @seen) [:destination :requested-url]))))))

;; ---- the closed boolean contract ----------------------------------------

(deftest can-enter-non-boolean-denies-and-signals
  (testing "a :can-enter sub returning a non-boolean DENIES and emits
            :rf.error/can-enter-non-boolean (fail closed)"
    (rf/reg-route :home    {} "/home")
    (rf/reg-route :account {:can-enter [:auth/weird?]} "/account")
    (rf/reg-sub :auth/weird? (fn [_ _] "yes"))
    (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
    (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [traces (atom [])
          seen   (capture-denials!)]
      (rf/register-listener! :trace ::nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :account}])
      (rf/unregister-listener! :trace ::nb)
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (some (fn [ev] (and (= :rf.error/can-enter-non-boolean (:operation ev))
                                (= :account (-> ev :tags :route-id))))
                  @traces)
            ":rf.error/can-enter-non-boolean fired, tagged with the target route"))
      (is (= [:account] (map (comp :route-id :target) @seen))
          "the non-boolean DENIED, exactly once, naming the guarded target")
      (is (= :home (current-id))))))

(deftest can-enter-guard-receives-resolved-target
  (testing "the :can-enter sub receives the RESOLVED target appended to its
            query — (fn [db [_ target]])"
    (rf/reg-route :home    {} "/home")
    (rf/reg-route :account {:can-enter [:auth/target-aware?]} "/account")
    (let [seen (atom nil)]
      (rf/reg-sub :auth/target-aware?
                  (fn [_ [_ target]] (reset! seen target) (= "/account" (:url target))))
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (rf/dispatch-sync [:rf.route/navigate {:to :account}])
      (is (= [:account "/account"] ((juxt :route-id :url) @seen)))
      (is (= :account (current-id)) "guard allowed — entry completed"))))

;; ---- entry has NO bypass -------------------------------------------------

(deftest bypass-leave-does-not-bypass-entry
  (testing ":bypass-leave? is LEAVE-only (OI-3) — it never lets a caller past
            a :can-enter guard"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [seen (capture-denials!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :account :bypass-leave? true}])
      (is (= 1 (count @seen)) "still denied — there is no entry bypass")
      (is (= :home (current-id))))))

;; ---- the fresh-return auth recipe ---------------------------------------

(deftest fresh-return-after-sign-in
  (testing "the documented auth recipe: stash the denied :destination,
            replace-navigate to login, then after sign-in dispatch a FRESH
            navigate with the stored destination — the guard re-evaluates
            because that is an ordinary new attempt"
    (register-common!)
    (rf/reg-event :rf.route/entry-denied
      (fn [{:keys [db]} [_ {:keys [destination]}]]
        {:db (assoc-in db [:auth :return-to] destination)
         :fx [[:dispatch [:rf.route/navigate {:to :login :replace? true}]]]}))
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf.route/navigate {:to :account}])
    (is (= :login (current-id)) "the denial handler redirected to login")
    (is (= {:to :account} (get-in (rf/app-db-value :rf/default) [:auth :return-to]))
        "the denied destination was stashed as a replayable RouteDestination")
    (rf/dispatch-sync [:auth/set true])
    (rf/dispatch-sync [:rf.route/navigate
                       (get-in (rf/app-db-value :rf/default) [:auth :return-to])])
    (is (= :account (current-id))
        "the fresh navigate re-ran :can-enter, which now allows")))

;; ===========================================================================
;; Guard behaviour per transition kind (EP-0037 stage 3)
;; ===========================================================================

(defn- counting-guards!
  "Register a route `:page` carrying BOTH guards, each counting its calls."
  [leave-calls enter-calls]
  (rf/reg-route :page
                {:query     [:map]
                 :can-leave [:page/can-leave?]
                 :can-enter [:page/can-enter?]}
                "/page")
  (rf/reg-sub :page/can-leave? (fn [_ _] (swap! leave-calls inc) true))
  (rf/reg-sub :page/can-enter? (fn [_ _] (swap! enter-calls inc) true))
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/scroll         {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}} (fn [_ _] nil)))

(deftest exact-no-op-runs-neither-guard
  (testing "an EXACT no-op evaluates NEITHER guard — nothing is being left or
            entered, and a redundant request cannot create pending state"
    (let [leave (atom 0) enter (atom 0)]
      (counting-guards! leave enter)
      (rf/dispatch-sync [:rf.route/handle-url-change "/page"])
      (reset! leave 0) (reset! enter 0)
      ;; every door, same URL / same target
      (rf/dispatch-sync [:rf.route/navigate {:to :page}])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/page"}])
      (rf/dispatch-sync [:rf.route/handle-url-change "/page"])
      (rf/dispatch-sync [:rf.route/handle-url-change "/page" {:rf.route/cause :link}])
      (is (= [0 0] [@leave @enter]) "neither :can-leave nor :can-enter was evaluated"))))

(deftest full-transition-runs-both-guards
  (testing "a FULL transition — including a changed in-place :query, which is
            data-bearing — evaluates both :can-leave and :can-enter"
    (let [leave (atom 0) enter (atom 0)]
      (counting-guards! leave enter)
      (rf/dispatch-sync [:rf.route/handle-url-change "/page"])
      (reset! leave 0) (reset! enter 0)
      (rf/dispatch-sync [:rf.route/navigate {:query {:tab "history"}}])
      (is (= [1 1] [@leave @enter]) "the in-place form is a full transition, not a bypass")
      ;; `/page` declares no query vocabulary, so `:tab` is
      ;; committed the way the URL spells it.
      (is (= {"tab" "history"} (get-in (rdb) [:rf.runtime/routing :current :query]))))))

(deftest fragment-only-transition-runs-both-guards
  (testing "a FRAGMENT-ONLY transition preserves the both-guard contract"
    (let [leave (atom 0) enter (atom 0)]
      (counting-guards! leave enter)
      (rf/dispatch-sync [:rf.route/handle-url-change "/page"])
      (reset! leave 0) (reset! enter 0)
      (rf/dispatch-sync [:rf.route/navigate {:fragment "errors"}])
      (is (= [1 1] [@leave @enter]))
      (is (= "errors" (get-in (rdb) [:rf.runtime/routing :current :fragment]))))))

(deftest link-door-decides-exactly-once
  (testing "the link door decides ONCE — it decides and commits in one event,
            so an ALLOWED link click does not re-run the guards"
    (let [leave (atom 0) enter (atom 0)]
      (counting-guards! leave enter)
      (rf/reg-route :other {} "/other")
      (rf/dispatch-sync [:rf.route/handle-url-change "/other"])
      (reset! leave 0) (reset! enter 0)
      (rf/dispatch-sync [:rf.route/url-requested {:url "/page"}])
      (is (= :page (current-id)) "the link click completed")
      (is (= 1 @enter) ":can-enter evaluated exactly once"))))

;; ===========================================================================
;; SSR — the default 403 floor (Spec 011)
;; ===========================================================================

(defn- server-frame! []
  (rf.frame/make-anon-frame-record! {:platform :server}))

(deftest ssr-hard-deny-stamps-403
  (testing "on a server frame a denial stamps the default 403; with no
            replacement the response stays 403 and the protected route is
            uncommitted"
    (register-common!)
    (let [f (server-frame!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/account"] {:frame f})
      (is (= 403 (:status (rf.ssr/get-response f)))
          "hard deny with no replacement → 403")
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value f))
                        [:rf.runtime/routing :current]))
          "no route committed — no resource or hydration data for the denied target")
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value f))
                        [:rf.runtime/routing :pending-navigation]))
          "and no pending value"))))

(deftest ssr-client-frame-stamps-no-status
  (testing "the 403 floor is SERVER-ONLY — a client frame denial emits no
            :rf.server/set-status at all"
    (register-common!)
    (let [statuses (atom [])]
      (rf.fx/reg-fx :rf.server/set-status {:platforms #{:server :client}}
                 (fn [_ s] (swap! statuses conj s)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (rf/dispatch-sync [:rf.route/navigate {:to :account}])
      (is (empty? @statuses) "no status fx on a client frame"))))

(deftest ssr-application-may-set-another-status
  (testing "the 403 is stamped BEFORE the denial event drains, so an
            application handler may set another status under Spec 011's
            last-write-wins multiple-status policy"
    (register-common!)
    (rf/reg-event :rf.route/entry-denied
      (fn [_ _] {:fx [[:rf.server/set-status 404]]}))
    (let [f (server-frame!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/account"] {:frame f})
      (is (= 404 (:status (rf.ssr/get-response f)))
          "last write wins — the app's explicit status supersedes the 403 floor"))))

(deftest repeated-denials-never-emit-a-loop-error
  (testing "entry is terminal, so a repeatedly-denied target cannot spin —
            :rf.error/route-guard-loop is retired and never fires"
    (register-common!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (let [traces (atom [])
          seen   (capture-denials!)]
      (rf/register-listener! :trace ::loop (fn [ev] (swap! traces conj ev)))
      (dotimes [_ 12] (rf/dispatch-sync [:rf.route/navigate {:to :account}]))
      (rf/unregister-listener! :trace ::loop)
      (is (= 12 (count @seen)) "each fresh attempt denies once — 12 attempts, 12 denials")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (not-any? #(= :rf.error/route-guard-loop (:operation %)) @traces)
            ":rf.error/route-guard-loop is retired"))
      (is (nil? (pending)) "no pending value ever accumulated")
      (is (= :home (current-id))))))

;; ---- leave short-circuits entry ------------------------------------------

(deftest leave-runs-before-entry
  (testing "the current route's :can-leave is consulted BEFORE the target's
            :can-enter — a leave block wins and never runs the entry guard"
    (rf/reg-route :editor  {:can-leave [:editor/clean?]}   "/editor")
    (rf/reg-route :account {:can-enter [:auth/signed-in?]} "/account")
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/clean? (fn [db _] (not (get-in db [:editor :dirty?]))))
    (let [enter-ran (atom false)]
      (rf/reg-sub :auth/signed-in? (fn [_ _] (reset! enter-ran true) false))
      (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
      (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/handle-url-change "/editor"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf.route/navigate {:to :account}])
      (is (= {:rejecting-route :editor :destination {:to :account}}
             (select-keys (pending) [:rejecting-route :destination]))
          "the LEAVE guard blocked first — a resumable pending value against the
           CURRENT route, carrying the replayable destination")
      (is (false? @enter-ran) "the entry guard was NOT consulted"))))
