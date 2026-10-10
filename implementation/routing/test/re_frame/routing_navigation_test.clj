(ns re-frame.routing-navigation-test
  "Navigation tests for re-frame.routing's two doors — programmatic
  `:rf.route/navigate` and URL-driven `:rf.route/handle-url-change`:
  fragment handling, the not-found fallback and its address-bar parity, the
  fail-closed open-redirect matrices, the rule-3 no-op and fragment-only
  short-circuits, in-place `:query` / `:query-merge` edits, and the structural
  request gate.

  ## Posture split

  Every deftest runs in `clojure -M:test` and in
  `scripts/test-routing-prod-gate.sh` (`-Dre-frame.debug=false`). The gate
  closes the trace bus at namespace load, so each assertion that reads a
  trace — the lifecycle, nav-token and fragment-changed traces, the
  `:rf.warning/malformed-url` and `:rf.warning/no-not-found-route`
  advisories, and the `:rf.error/navigate-bad-request` and
  `:rf.error/schema-validation-failure` diagnostics — sits inside a
  `(when rf.interop/debug-enabled? …)` arm.

  Each arm stands beside an always-on assertion of the runtime-db fact its
  trace announces: the slice moved or stayed put, in the frame the `:frame`
  tag names, and the URL was pushed or not. A rejected request is rejected in
  both postures; only its diagnostic is dev-only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- frame-slice
  "The current route slice for `frame-id`."
  [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          [:rf.runtime/routing :current]))

(defn- nav-slice
  "The current route slice for the default frame."
  []
  (frame-slice :rf/default))

(defn- record-pushes!
  "Capture every `:rf.nav/push-url` URL, on both platforms so the JVM routes it."
  []
  (let [pushed (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                  (fn [_ url] (swap! pushed conj url)))
    pushed))

;; ---- a link click commits in its own event, state before URL ---------------
;;
;; Spec 012 §State-first, URL-second: a navigation queued behind a link click
;; must find the link's route already committed. Were the link to push now and
;; commit later, `[link-A navigate-B]` would end on route A with URL /b, and the
;; late commit would leave B without asking B's `:can-leave`.

(deftest a-navigate-queued-behind-a-link-click-sees-it-committed
  (rf/reg-route :r/home {} "/")
  (rf/reg-route :r/a {} "/a")
  (rf/reg-route :r/b {:can-leave :b/can-leave?} "/b")
  (rf/reg-sub :b/can-leave? (fn [_ _] false))
  (rf/reg-event :test/queue (fn [_ [_ & evs]] {:fx (mapv (fn [ev] [:dispatch ev]) evs)}))
  (let [history (atom [])]
    (doseq [[fx-id op] [[:rf.nav/push-url :push] [:rf.nav/replace-url :replace]]]
      (rf.fx/reg-fx fx-id {:platforms #{:server :client}} (fn [_ url] (swap! history conj [op url]))))
    (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :initial}])
    (rf/dispatch-sync [:test/queue [:rf.route/url-requested {:url "/a"}] [:rf.route/navigate {:to :r/b}]])
    (is (= [:r/b [[:push "/a"] [:push "/b"]] nil]
           [(:route-id (nav-slice))
            @history
            (get-in (rf/frame-state-value :rf/default)
                    [:rf.db/runtime :rf.runtime/routing :pending-navigation])])
        "the route and the last history op agree, and B — whose :can-leave refuses — is never left")))

;; ---- a qualified enum value survives a reload of the URL it pushed ----------

(deftest qualified-enum-navigation-re-matches-its-pushed-url
  (rf/reg-route :r/items {:query [:map [:sort {:optional true} [:enum :sort/asc :sort/desc]]]} "/items")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :r/items :query {:sort :sort/desc}}])
    (rf/dispatch-sync [:rf.route/handle-url-change (peek @pushed) {:rf.route/cause :popstate}])
    (is (= [:r/items {:sort :sort/desc}] ((juxt :route-id :query) (nav-slice)))
        "Back/Forward or a reload onto the pushed URL lands on the route, not not-found")))

;; ---- addresses --------------------------------------------------------------

(deftest routing-destination-query-is-literal
  ;; EP-0037 R5: a fresh destination gains no ambient query from the current route.
  (rf/reg-route :route/search {} "/search")
  (rf/reg-route :route/cart   {} "/cart")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/search?theme=dark&locale=en" {:rf.route/cause :link}])
    (is (= {"theme" "dark" "locale" "en"} (:query (nav-slice))))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/cart}])
    (is (= ["/cart"] @pushed))))

(deftest navigate-present-but-nil-query-clears-like-an-empty-map
  ;; Presence, not truthiness, discriminates: `:query nil` is the same "clear
  ;; the query" as `:query {}`, on the destination and the in-place branch.
  (rf/reg-route :route/search {} "/search")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/search :query nil}])
  (is (= {} (:query (nav-slice))))
  (rf/dispatch-sync [:rf.route/navigate {:to :route/search :query {:sort "asc"}}])
  (is (= {"sort" "asc"} (:query (nav-slice))))
  (rf/dispatch-sync [:rf.route/navigate {:query nil}])
  (is (= {} (:query (nav-slice)))))

(deftest navigate-url-form-preserves-fragment
  (rf/reg-route :route/docs {} "/docs/:page")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/navigate {:url "/docs/routing#scroll-restoration"}])
    (is (= ["/docs/routing#scroll-restoration"] @pushed))))

(deftest navigate-accepts-the-request-map-forms
  ;; A policy key rides beside the address: `:replace? true` commits the
  ;; path-params and replaces the history entry.
  (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
  (let [replaced (atom [])]
    (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
                  (fn [_ url] (swap! replaced conj url)))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "two"} :replace? true}])
    (is (= ["/articles/two"] @replaced))
    (is (= {:id "two"} (:params (nav-slice))))))

;; ---- the not-found fallback -------------------------------------------------

(deftest navigate-url-form-unmatched-without-not-found-route-commits-and-warns
  ;; With no :rf.route/not-found registered, an unmatched {:url} still commits
  ;; the not-found slice and pushes the requested URL verbatim — never a
  ;; route-url call on the unregistered id, which would reject — and warns.
  (rf/reg-route :route/home {} "/")
  (let [pushed (record-pushes!)
        traces (atom [])]
    (rf/register-listener! :trace ::nav-nf-warn (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
    (rf/unregister-listener! :trace ::nav-nf-warn)
    (is (= {:route-id :rf.route/not-found :params {:url "/no/such/path"}}
           (select-keys (nav-slice) [:route-id :params])))
    (is (= ["/no/such/path"] @pushed))
    (when rf.interop/debug-enabled?
      (is (some #(= :rf.warning/no-not-found-route (:operation %)) @traces)))))

(deftest not-found-address-bar-parity-programmatic-vs-url-driven
  ;; Both doors keep the requested URL in the address bar: the programmatic
  ;; door pushes it (never the not-found route's own /404), and the URL-driven
  ;; door pushes nothing because the bar already moved.
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/404")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
    (is (= {:route-id :rf.route/not-found :params {:url "/no/such/path"}}
           (select-keys (nav-slice) [:route-id :params])))
    (is (= ["/no/such/path"] @pushed))
    (reset! pushed [])
    (rf/dispatch-sync [:rf.route/handle-url-change "/another/miss" {:rf.route/cause :link}])
    (is (= {:route-id :rf.route/not-found :params {:url "/another/miss"}}
           (select-keys (nav-slice) [:route-id :params])))
    (is (empty? @pushed))))

(deftest navigate-unmatched-url-honours-explicit-fragment-override
  ;; `{:url raw :fragment f}` on an unmatched URL rebuilds ONE effective URL —
  ;; the raw path and query with the explicit fragment (nil clears it) — and
  ;; feeds it to the slice, the not-found :params and the push. Without an
  ;; explicit :fragment the raw URL rides verbatim.
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/404")
  (let [pushed (record-pushes!)]
    (doseq [[request url slice]
            [[{:url "/no/such/path#old" :fragment "new"} "/no/such/path#new"  {:fragment "new"}]
             [{:url "/no/such/path#old" :fragment nil}   "/no/such/path"      {:fragment nil}]
             [{:url "/no/such/path#keep"}                "/no/such/path#keep" {}]]]
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate request])
      (let [expected (assoc slice :route-id :rf.route/not-found :params {:url url})]
        (is (= expected (select-keys (nav-slice) (keys expected))) (pr-str request)))
      (is (= [url] @pushed) (pr-str request)))))

(deftest handle-url-change-writes-full-slice-shape
  ;; The URL-driven door writes the same seven-key slice the programmatic one does.
  (rf/reg-route :route/docs {} "/docs/:page")
  (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#scroll-restoration"])
  (is (= {:route-id   :route/docs
          :params     {:page "routing"}
          :query      {}
          :fragment   "scroll-restoration"
          :transition :idle
          :error      nil
          :nav-token  "nav-1"}
         (nav-slice))))

(deftest transitioned-malformed-url-routes-to-not-found-with-reason
  ;; Spec 012 §Routing failure semantics: a malformed %-encoding lands on the
  ;; not-found slice with `:reason :malformed-url`, the discriminator a
  ;; per-route error UI branches on. The per-position decode verdicts are
  ;; `routing-registry-test`'s.
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/404")
  (let [traces (atom [])]
    (rf/register-listener! :trace ::malformed-trace (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/%" {:rf.route/cause :link}])
    (rf/unregister-listener! :trace ::malformed-trace)
    (is (= {:route-id :rf.route/not-found :params {:url "/articles/%" :reason :malformed-url}}
           (select-keys (nav-slice) [:route-id :params])))
    (when rf.interop/debug-enabled?
      (is (some (fn [ev] (and (= :rf.warning/malformed-url (:operation ev))
                              (= "/articles/%" (-> ev :tags :url))))
                @traces))
      (is (some (fn [ev] (and (= :rf.error/no-such-handler (:operation ev))
                              (= :route (-> ev :tags :kind))
                              (= :malformed-url (-> ev :tags :reason))))
                @traces)))))

(deftest transitioned-forward-nav-traces-carry-frame-rf2-w3qgc
  ;; A forward URL-driven nav threads its frame onto the route-miss
  ;; diagnostics (Spec 009 requires `:frame` on `:rf.error/no-such-handler
  ;; {:kind :route}`). The attribution is also a frame-state fact, read in
  ;; both postures: the miss committed in :route/owner and left :rf/default alone.
  (rf/make-frame {:id :rf/default})
  (rf/make-frame {:id :route/owner})
  (rf/reg-route :route/home {} "/")
  (let [traces (atom [])]
    (rf/register-listener! :trace ::w3qgc-malformed (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/%" {:rf.route/cause :link}] {:frame :route/owner})
    (rf/unregister-listener! :trace ::w3qgc-malformed)
    (is (= {:url "/articles/%" :reason :malformed-url} (:params (frame-slice :route/owner))))
    (is (nil? (:route-id (frame-slice :rf/default))))
    (when rf.interop/debug-enabled?
      (is (some (fn [ev] (and (= :rf.warning/malformed-url (:operation ev))
                              (= "/articles/%" (-> ev :tags :url))
                              (= :route/owner (-> ev :tags :frame))))
                @traces))
      (is (some (fn [ev] (and (= :rf.error/no-such-handler (:operation ev))
                              (= :route (-> ev :tags :kind))
                              (= :route/owner (-> ev :tags :frame))))
                @traces)))))

;; ---- :on-match and rule 3 ---------------------------------------------------

(deftest on-match-dispatches-fire-and-forget-idle
  ;; EP-0037 R1: every :on-match event fires, in declaration order, and none
  ;; drives readiness — :transition is :idle for the handlers and after.
  (let [observed (atom [])
        observer (fn [tag]
                   (fn [{:keys [db] rt :rf.db/runtime} _]
                     (swap! observed conj [tag (get-in rt [:rf.runtime/routing :current :transition])])
                     {:db db}))]
    (rf/reg-event :load/a (observer :a))
    (rf/reg-event :load/b (observer :b))
    (rf/reg-route :route/dashboard {:on-match [[:load/a] [:load/b]]} "/dashboard")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/dashboard}])
    (is (= [[:a :idle] [:b :idle]] @observed))
    (is (= :idle (:transition (nav-slice))))))

(deftest changed-params-still-refires-on-match
  ;; Spec 012 rule 3's no-op skip must not swallow a real param change.
  (let [on-match-calls (atom 0)]
    (rf/reg-event :article/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
    (rf/reg-route :route/article {:on-match [[:article/load]]} "/articles/:id")
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}])
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/B" {:rf.route/cause :link}])
    (is (= 2 @on-match-calls))
    (is (= "nav-2" (:nav-token (nav-slice))))))

;; ---- param validation at the programmatic door ----------------------------

(deftest navigate-validation-failure-rejects-and-leaves-slice-unchanged
  ;; Spec 012 §Param validation at the call site: params that fail the route's
  ;; schema REJECT — slice unchanged, nothing pushed, in both postures. A
  ;; `{:url}` that MATCHES the route but fails its schema rejects the same way;
  ;; it does not degrade to the 404 view the URL-driven door would show.
  (let [restore (rf.routing-test-support/with-stub-validator)
        pushed  (record-pushes!)]
    (try
      (rf/reg-route :route/article
                    {:params (fn [{:keys [id]}]
                               (str/starts-with? (or id "") "a"))} "/articles/:id")
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "aardvark"}}])
      (reset! pushed [])
      (let [before (nav-slice)
            traces (atom [])]
        (rf/register-listener! :trace ::reject (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}])
        (rf/unregister-listener! :trace ::reject)
        (is (= before (nav-slice)))
        (when rf.interop/debug-enabled?
          (is (= :event (->> @traces
                             (filter #(= :rf.error/schema-validation-failure (:operation %)))
                             first :tags :where))))
        (rf/dispatch-sync [:rf.route/navigate {:url "/articles/zoo"}])
        (is (= before (nav-slice)))
        (is (empty? @pushed)))
      (finally (restore)))))

;; ---- the fail-closed open-redirect matrices --------------------------------
;;
;; With no browser origin, `external-url?` classes every ambiguous or absolute
;; URL external: no push, no slice rewrite. Both URL-taking sinks gate on it.

(deftest url-requested-fails-closed-on-ambiguous-urls-jvm
  (rf/reg-route :route/home {} "/")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :link}])
    (doseq [hostile ["https://evil.invalid/cart"   ;; scheme
                     "//evil.invalid/cart"          ;; protocol-relative
                     "/\\evil.invalid"              ;; backslash authority
                     " /cart"                       ;; leading-space scheme-anchor bypass
                     "\thttps://evil.invalid"       ;; embedded tab (browser-stripped)
                     "javascript:alert(1)"          ;; non-http scheme
                     "mailto:a@b.c"
                     "cart"                         ;; bare relative segment (not rooted)
                     ""]]
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/url-requested {:url hostile}])
      (is (= [[] :route/home] [@pushed (:route-id (nav-slice))]) (pr-str hostile)))
    (doseq [safe ["/cart" "/a/b/c" "/cart?q=1" "/cart#frag" "?q=1" "#frag"]]
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/url-requested {:url safe}])
      (is (= [safe] @pushed) (pr-str safe)))))

(deftest navigate-url-target-fails-closed-on-ambiguous-urls-jvm
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :route/cart {} "/cart")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/cart" {:rf.route/cause :link}])
    (doseq [hostile ["https://evil.invalid/phish"   ;; absolute cross-origin
                     "//evil.invalid/phish"          ;; protocol-relative
                     "/\\evil.invalid"               ;; backslash authority
                     " /cart"                        ;; leading-space scheme-anchor bypass
                     "\thttps://evil.invalid"        ;; embedded tab (browser-stripped)
                     "javascript:alert(1)"           ;; non-http scheme
                     "https://good.com@evil.com/x"   ;; userinfo confusion
                     "mailto:a@b.c"
                     "cart"]]                        ;; bare relative segment (not rooted)
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:url hostile}])
      (is (= [[] :route/cart] [@pushed (:route-id (nav-slice))]) (pr-str hostile)))
    ;; Each safe nav starts on a DIFFERENT route so the rule-3 no-op cannot mask the push.
    (doseq [[safe land-elsewhere] [["/cart"      "/"]
                                   ["/"          "/cart"]
                                   ["/cart?q=1"  "/"]
                                   ["/cart#frag" "/"]]]
      (rf/dispatch-sync [:rf.route/handle-url-change land-elsewhere {:rf.route/cause :link}])
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:url safe}])
      (is (seq @pushed) (pr-str safe)))))

;; ---- commit lifecycle traces carry :frame ---------------------------------
;;
;; `commit-navigation` stamps the carried frame on `:rf.route.nav-token/allocated`,
;; `:rf.route/deactivated` and `:rf.route/activated`; epoch capture and the
;; frame-level trace-disable gate both key off `:tags :frame`. Each door runs in
;; a NON-DEFAULT frame, so a commit path that hardcodes `:rf/default` fails here.

(defn- commit-traces
  "`[operation route-id frame]` for each commit lifecycle trace `dispatch!` emits."
  [dispatch!]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::commit-traces (fn [ev] (swap! traces conj ev)))
    (dispatch!)
    (rf/unregister-listener! :trace ::commit-traces)
    (set (for [{:keys [operation tags]} @traces
               :when (#{:rf.route.nav-token/allocated :rf.route/deactivated :rf.route/activated}
                      operation)]
           [operation (:route-id tags) (:frame tags)]))))

(def ^:private owner-cross-route-traces
  #{[:rf.route.nav-token/allocated :route/to   :route/owner]
    [:rf.route/deactivated         :route/from :route/owner]
    [:rf.route/activated           :route/to   :route/owner]})

(deftest commit-traces-carry-frame-programmatic-rf2-dbmj6x
  (rf/make-frame {:id :rf/default})
  (rf/make-frame {:id :route/owner})
  (rf/reg-route :route/from {} "/from")
  (rf/reg-route :route/to   {} "/to")
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/navigate {:to :route/from}] {:frame :route/owner})
  (let [traces (commit-traces #(rf/dispatch-sync [:rf.route/navigate {:to :route/to}] {:frame :route/owner}))]
    (is (= :route/to (:route-id (frame-slice :route/owner))))
    (is (nil? (:route-id (frame-slice :rf/default))))
    (when rf.interop/debug-enabled?
      (is (= owner-cross-route-traces traces)))))

(deftest commit-traces-carry-frame-url-driven-rf2-dbmj6x
  (rf/make-frame {:id :rf/default})
  (rf/make-frame {:id :route/owner})
  (rf/reg-route :route/from {} "/from")
  (rf/reg-route :route/to   {} "/to")
  (rf/dispatch-sync [:rf.route/handle-url-change "/from" {:rf.route/cause :link}] {:frame :route/owner})
  (let [traces (commit-traces #(rf/dispatch-sync [:rf.route/handle-url-change "/to"] {:frame :route/owner}))]
    (is (= :route/to (:route-id (frame-slice :route/owner))))
    (is (nil? (:route-id (frame-slice :rf/default))))
    (when rf.interop/debug-enabled?
      (is (= owner-cross-route-traces traces)))))

;; ---- in-place navigation (Spec 012 §In-place navigation) ------------------

(deftest routing-in-place-target-stays-on-current-route
  ;; No :to / :url holds the route and its path-params; a wholesale :query
  ;; replaces the current query.
  (rf/reg-route :route/article
                {:params [:map [:id :string]]
                 :query  [:map [:tab {:optional true} :string]]}
                "/articles/:id")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/intro?tab=notes" {:rf.route/cause :link}])
    (rf/dispatch-sync [:rf.route/navigate {:query {:tab "history"}}])
    (is (= {:route-id :route/article :params {:id "intro"} :query {:tab "history"}}
           (select-keys (nav-slice) [:route-id :params :query])))
    (is (= ["/articles/intro?tab=history"] @pushed))))

(deftest routing-query-merge-nil-removes-a-key
  ;; A nil :query-merge value removes the key from the slice as well as the
  ;; URL, while the rest of the current query folds through.
  (rf/reg-route :route/search
                {:query [:map [:q {:optional true} :string]
                              [:sort {:optional true} :string]]}
                "/search")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/search?q=clojure&sort=recent" {:rf.route/cause :link}])
    (rf/dispatch-sync [:rf.route/navigate {:query-merge {:sort nil}}])
    (is (= {:q "clojure"} (:query (nav-slice))))
    (is (= ["/search?q=clojure"] @pushed))))

(deftest routing-in-place-query-merge-no-op-when-unchanged
  ;; Spec 012 rule 3: a merge that leaves the query as it was is the complete
  ;; no-op — nothing pushed, no fresh nav-token.
  (rf/reg-route :route/search {:query [:map [:page {:optional true} :int]]} "/search")
  (let [pushed (record-pushes!)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/search?page=2" {:rf.route/cause :link}])
    (let [token-before (:nav-token (nav-slice))]
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 2}}])
      (is (empty? @pushed))
      (is (= token-before (:nav-token (nav-slice)))))))

;; ---- fragment-only navigation (Spec 012 §Fragments rules 3-4) -------------
;;
;; A target that differs from the current slice ONLY in its #fragment updates
;; `:fragment`, emits one `:rf.route/fragment-changed`, drives history and scroll
;; through effects, and never reaches `commit-navigation`: no fresh nav-token,
;; no `:on-match` re-fire, no resource re-plan.

(defn- reg-nav-fxs-capturing!
  "Register the four routing history / scroll fxs on both platforms with
  capturing handlers. Returns atoms `:push` / `:replace` (URLs), `:scroll`
  (args) and `:order` (an ordered `[fx-id args]` log across all four)."
  []
  (let [pushed   (atom [])
        replaced (atom [])
        scrolled (atom [])
        order    (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                  (fn [_ url]  (swap! pushed conj url)   (swap! order conj [:rf.nav/push-url url])))
    (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
                  (fn [_ url]  (swap! replaced conj url) (swap! order conj [:rf.nav/replace-url url])))
    (rf.fx/reg-fx :rf.nav/scroll {:platforms #{:server :client}}
                  (fn [_ args] (swap! scrolled conj args) (swap! order conj [:rf.nav/scroll args])))
    (rf.fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}}
                  (fn [_ args] (swap! order conj [:rf.nav/capture-scroll args])))
    {:push pushed :replace replaced :scroll scrolled :order order}))

(defn- fx-log
  "The `[fx-id args]` log with each scroll's args cut to `:strategy` / `:fragment`."
  [order]
  (map (fn [[id args]]
         [id (cond-> args (= :rf.nav/scroll id) (select-keys [:strategy :fragment]))])
       order))

(defn- fragment-changed-tags
  [traces]
  (->> traces
       (filter #(= :rf.route/fragment-changed (:operation %)))
       (map #(select-keys (:tags %) [:route-id :prev-fragment :next-fragment :frame]))))

(deftest navigate-fragment-only-short-circuits-no-refire-no-token-rf2-k4exp1
  (let [on-match-calls (atom 0)
        traces         (atom [])]
    (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
    (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
    (let [fxs (reg-nav-fxs-capturing!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
      (let [slice-before (nav-slice)]
        (rf/register-listener! :trace ::k4exp1-frag (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}])
        (rf/unregister-listener! :trace ::k4exp1-frag)
        (is (= 1 @on-match-calls))
        ;; Only :fragment moved — the nav-1 token included, nothing was re-allocated.
        (is (= (assoc slice-before :fragment "b") (nav-slice)))
        (is (= ["/docs/routing#a" "/docs/routing#b"] @(:push fxs)))
        (is (empty? @(:replace fxs)))
        (when rf.interop/debug-enabled?
          (is (= [{:route-id :route/docs :prev-fragment "a" :next-fragment "b" :frame :rf/default}]
                 (fragment-changed-tags @traces))))))))

(deftest navigate-fragment-only-effect-ordering-rf2-k4exp1
  ;; State first, then: capture the leaving position, push, scroll to the new fragment.
  (rf/reg-route :route/docs {} "/docs/:page")
  (let [fxs (reg-nav-fxs-capturing!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
    (reset! (:order fxs) [])
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}])
    (is (= [[:rf.nav/capture-scroll {:url "/docs/routing#a"}]
            [:rf.nav/push-url "/docs/routing#b"]
            [:rf.nav/scroll {:strategy :top :fragment "b"}]]
           (fx-log @(:order fxs))))))

(deftest navigate-fragment-only-replace-uses-replace-url-rf2-k4exp1
  (rf/reg-route :route/docs {} "/docs/:page")
  (let [fxs (reg-nav-fxs-capturing!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
    (reset! (:push fxs) [])
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b" :replace? true}])
    (is (= ["/docs/routing#b"] @(:replace fxs)))
    (is (empty? @(:push fxs)))))

(deftest navigate-fragment-only-scroll-false-suppresses-scroll-rf2-k4exp1
  (rf/reg-route :route/docs {} "/docs/:page")
  (let [fxs (reg-nav-fxs-capturing!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
    (reset! (:scroll fxs) [])
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b" :scroll false}])
    (is (= "b" (:fragment (nav-slice))))
    (is (empty? @(:scroll fxs)))))

(deftest navigate-fragment-only-clearing-fragment-rf2-k4exp1
  ;; Dropping the fragment is still fragment-only: nil is written, the bare URL
  ;; pushed, and no nav-token minted.
  (rf/reg-route :route/docs {} "/docs/:page")
  (let [fxs (reg-nav-fxs-capturing!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
    (reset! (:push fxs) [])
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}}])
    (is (= {:fragment nil :nav-token "nav-1"} (select-keys (nav-slice) [:fragment :nav-token])))
    (is (= ["/docs/routing"] @(:push fxs)))))

(deftest navigate-identical-target-with-replace-stays-noop-rf2-k4exp1
  ;; Identical wins over fragment-only: an exactly identical target is the
  ;; complete no-op even with :replace?.
  (rf/reg-route :route/docs {} "/docs/:page")
  (let [fxs (reg-nav-fxs-capturing!)]
    (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
    (let [before (nav-slice)]
      (reset! (:push fxs) [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a" :replace? true}])
      (is (= before (nav-slice)))
      (is (empty? @(:push fxs)))
      (is (empty? @(:replace fxs))))))

(deftest url-driven-fragment-only-emits-scroll-rf2-p1aipi
  ;; The URL-driven door short-circuits a fragment-only change the same way,
  ;; and must still scroll — pushState and popstate do not scroll to a fragment
  ;; natively. It captures the leaving position, scrolls, and pushes nothing:
  ;; the address bar already moved.
  (let [on-match-calls (atom 0)
        traces         (atom [])]
    (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
    (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
    (let [fxs (reg-nav-fxs-capturing!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#a" {:rf.route/cause :link}])
      (reset! (:order fxs) [])
      (rf/register-listener! :trace ::p1aipi-frag (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#b" {:rf.route/cause :link}])
      (rf/unregister-listener! :trace ::p1aipi-frag)
      (is (= {:fragment "b" :nav-token "nav-1"} (select-keys (nav-slice) [:fragment :nav-token])))
      (is (= 1 @on-match-calls))
      (is (= [[:rf.nav/capture-scroll {:url "/docs/routing#a"}]
              [:rf.nav/scroll {:strategy :top :fragment "b"}]]
             (fx-log @(:order fxs))))
      (when rf.interop/debug-enabled?
        (is (= [{:route-id :route/docs :prev-fragment "a" :next-fragment "b" :frame :rf/default}]
               (fragment-changed-tags @traces)))))))

;; ---- the structural request gate (Spec 012 §Validity rules) ---------------

(defn- gate-reject
  "Dispatch `event` and return the tags of the `:rf.error/navigate-bad-request`
  it emits, or nil."
  [event]
  (let [errors (atom [])]
    (rf/register-listener! :trace ::gate
                           (fn [ev] (when (= :rf.error/navigate-bad-request (:operation ev))
                                      (swap! errors conj ev))))
    (rf/dispatch-sync event)
    (rf/unregister-listener! :trace ::gate)
    (:tags (first @errors))))

(deftest navigate-structural-gate-rejects-malformed-requests
  ;; The event-shape gate, then `re-frame.routing.address/classify` over the
  ;; request map (its per-rule verdicts are `routing-address-extraction-test`'s),
  ;; reject before any guard runs: the slice stays put, nothing is pushed and
  ;; :can-leave is never consulted, in both postures. Ungated, each row would
  ;; commit a navigation, throw a raw host exception, or silently drop part of
  ;; the request.
  (rf/reg-route :route/gate-a {:query     [:map [:q {:optional true} :string]
                                                [:page {:optional true} :int]]
                               :can-leave :gate/can-leave?}
                "/gate-a")
  (rf/reg-route :route/gate-b {} "/gate-b")
  (let [pushed      (record-pushes!)
        leave-calls (atom 0)]
    (rf/reg-sub :gate/can-leave? (fn [_ _] (swap! leave-calls inc) true))
    (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
                  (fn [_ url] (swap! pushed conj url)))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/gate-a :query {:q "x"}}])
    (reset! pushed [])
    (reset! leave-calls 0)
    (let [before (nav-slice)]
      (doseq [[event reason ks]
              [[[:rf.route/navigate {:query-merge [:page 2]}]                  :query-merge-not-map         [:query-merge]]
               [[:rf.route/navigate {:to :route/gate-b :my-app/replace? true}] :unknown-keys                [:my-app/replace?]]
               [[:rf.route/navigate {:params {:q "y"} :fragment "x"}]          :params-requires-destination [:params]]
               [[:rf.route/navigate "/gate-b"]                                 :request-not-a-map           []]
               [[:rf.route/navigate {:to :route/gate-b} {:replace? true}]      :bad-event-arity             []]]]
        (let [tags (gate-reject event)]
          (is (= before (nav-slice)) (pr-str event))
          (when rf.interop/debug-enabled?
            (is (= {:where :event :reason reason :keys ks}
                   (select-keys tags [:where :reason :keys]))
                (pr-str event)))))
      (is (empty? @pushed))
      (is (zero? @leave-calls)))))
