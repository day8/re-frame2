(ns re-frame.routing-egress-test
  "EP-0015 (Spec 015 §Registration-owned transient classification) routing
  egress-projection regressions.

  Three findings from the EP-0015 review wave, all on the routing surface:

    - rf2-1wmni6 / rf2-pbbo68 — the scroll/history fx (`:rf.nav/scroll`,
      `:rf.nav/capture-scroll`, `:rf.nav/push-url`, `:rf.nav/replace-url`)
      build args carrying raw route params/query/fragment/URLs, and the core
      fx trace records `:rf.fx/args` verbatim onto `:rf.fx/handled`. Fixed by
      `:sensitive` path-marks on the fx registrations so the marks chokepoint
      redacts the carrier slots on the trace egress copy (handler input
      unaffected).
    - rf2-n1f4rh — the route-miss diagnostics (`:rf.warning/malformed-url`,
      `:rf.error/no-such-handler`) emit the raw requested URL under a custom
      `:url` slot the marks chokepoint does not walk. Fixed by a default-on
      URL-carrier scrub (`re-frame.routing.egress/redact-url-carriers`) at the
      emit site — no schema to consult on a route miss, so query/fragment
      values are redacted by default.
    - rf2-jfaucw — the blocked-navigation record keeps raw route carriers:
      the `:rf.route/navigation-blocked` TRACE carries `:requested-url`
      (custom slot → emit-site scrub) and the DISPATCHED event payload carries
      the pending-nav map with `:requested-url` + `:requested-by-event`
      (event marks → marks chokepoint).

  The unifying EP-0015 invariant under test: the IN-PROCESS value stays raw
  (the handler / pending-nav sub / continue-cancel resume need it), only the
  EGRESS copy (trace bus / Xray / MCP / log / epoch) is projected."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as privacy]
            [re-frame.routing :as routing]
            [re-frame.routing.egress :as egress]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.scroll :as scroll]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(def ^:private sentinel-str (subs (str privacy/redacted-sentinel) 1))  ;; "rf/redacted"

;; The spec defaults the scroll / history fx to :platforms #{:client}, so on
;; the JVM they emit `:rf.fx/skipped-on-platform` rather than running. The
;; routing tests re-register them #{:server :client} to exercise the drain.
;; CRUCIAL for EP-0015 (rf2-1wmni6/rf2-pbbo68): a bare re-registration would
;; REPLACE the marks entry (register-marks! replaces in full), wiping the
;; `:sensitive` declarations under test — so each override MERGES the
;; production meta (carrying `:sensitive`) and only OVERRIDES `:platforms`.
;; This keeps the test honest: it exercises the SAME marks the production
;; registration ships, not a marks-stripped stub.
(defn- reg-jvm-fx!
  "Re-register `fx-id` for the JVM drain (#{:server :client}) WITHOUT dropping
  its production `:sensitive` marks. `prod-meta` is the production meta def;
  `handler` is the test handler."
  [fx-id prod-meta handler]
  (rf/reg-fx fx-id (assoc prod-meta :platforms #{:server :client}) handler))

;; ===========================================================================
;; The pure URL-carrier scrub (rf2-n1f4rh) — fast, host-symmetric.
;; ===========================================================================

(deftest redact-url-carriers-keeps-path-redacts-query-values
  (testing "rf2-n1f4rh: query KEYS are preserved (shape), VALUES redacted"
    (is (= (str "/oauth/callback?code=" sentinel-str "&state=" sentinel-str)
           (egress/redact-url-carriers "/oauth/callback?code=secret123&state=xyz"))
        "each query value → rf/redacted; keys + path intact")))

(deftest redact-url-carriers-redacts-fragment-whole
  (testing "rf2-n1f4rh: the whole #fragment is opaque → redacted wholesale"
    (is (= (str "/login#" sentinel-str)
           (egress/redact-url-carriers "/login#access_token=abc.def.ghi"))
        "the fragment carrier is redacted entirely")
    (is (= (str "/search?q=" sentinel-str "#" sentinel-str)
           (egress/redact-url-carriers "/search?q=ssn-123#tok"))
        "both query values and fragment redacted")))

(deftest redact-url-carriers-bare-path-rides-verbatim
  (testing "rf2-n1f4rh: a path with no query/fragment is not a carrier target"
    (is (= "/admin/users/42" (egress/redact-url-carriers "/admin/users/42"))
        "bare path verbatim")
    (is (= "/" (egress/redact-url-carriers "/")) "root verbatim")))

(deftest redact-url-carriers-value-less-flag-key-kept
  (testing "rf2-n1f4rh: a value-less flag query key is kept (no = → no secret)"
    (is (= (str "/x?debug&token=" sentinel-str)
           (egress/redact-url-carriers "/x?debug&token=abc"))
        "the bare `debug` flag rides; `token=abc` value is redacted")))

(deftest redact-url-carriers-nil-safe
  (testing "a non-string input rides back unchanged"
    (is (nil? (egress/redact-url-carriers nil)))))

;; ===========================================================================
;; rf2-1wmni6 / rf2-pbbo68 — scroll/history fx :sensitive marks project the
;; :rf.fx/args carrier slots on the :rf.fx/handled trace egress copy.
;; ===========================================================================

(defn- handled-trace-for
  "Dispatch `event`, capture every `:rf.fx/handled` trace whose `:rf.fx/id`
  is `fx-id`, and return the LAST one's `:rf.fx/args` (the projected egress
  copy). The fx handlers are re-registered #{:server :client} so the JVM
  drain actually invokes them (the spec default is #{:client})."
  [fx-id event]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::egress (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync event)
    (rf/unregister-listener! :trace ::egress)
    (->> @traces
         (filter (fn [ev] (and (= :rf.fx/handled (:operation ev))
                               (= fx-id (-> ev :tags :rf.fx/id)))))
         last
         :tags
         :rf.fx/args)))

(deftest scroll-fx-handled-trace-redacts-route-descriptor-carriers
  (testing "rf2-1wmni6/rf2-pbbo68: :rf.nav/scroll's :rf.fx/handled trace has
            :from/:to :params/:query and :fragment redacted; :strategy + the
            route :id ride verbatim"
    (rf/reg-route :route/articles {} "/articles")
    (rf/reg-route :route/article  {:params [:map [:id :string]]} "/articles/:id")
    ;; Make the fx invoke on the JVM so :rf.fx/handled emits — but KEEP the
    ;; production `:sensitive` marks (the thing under test).
    (reg-jvm-fx! :rf.nav/scroll   scroll/scroll-fx-meta   (fn [_ _] nil))
    (reg-jvm-fx! :rf.nav/push-url nav-fx/push-url-meta     (fn [_ _] nil))
    ;; Land on a route WITH params so the next nav's :from carries :params.
    (rf/dispatch-sync [:rf.route/navigate :route/article {:id "secret-doc-id"}])
    (let [args (handled-trace-for
                 :rf.nav/scroll
                 [:rf.route/navigate :route/article {:id "another-secret"}
                  {:fragment "tok-in-fragment"}])]
      (is (some? args) "a :rf.nav/scroll :rf.fx/handled trace was emitted")
      ;; The descriptor :id keyword survives (names the shape, no secret).
      (is (= :route/article (get-in args [:to :id]))
          ":to :id (route keyword) rides verbatim")
      ;; The carrier slots are redacted to the sentinel.
      (is (= privacy/redacted-sentinel (get-in args [:to :params]))
          ":to :params (the document-id carrier) is redacted on the trace")
      (is (= privacy/redacted-sentinel (get-in args [:from :params]))
          ":from :params is redacted on the trace")
      (is (= privacy/redacted-sentinel (:fragment args))
          ":fragment is redacted on the trace")
      ;; :strategy is structural, not a carrier — it rides.
      (is (contains? args :strategy) ":strategy rides verbatim"))))

(deftest scroll-fx-handler-still-receives-raw-args-in-process
  (testing "rf2-1wmni6/rf2-pbbo68: the marks projection touches ONLY the trace
            egress copy — the in-process handler still receives the raw args
            (scroll restoration / fragment scrolling unaffected)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [seen (atom nil)]
      ;; Capture what the HANDLER actually receives (not the trace).
      (reg-jvm-fx! :rf.nav/scroll   scroll/scroll-fx-meta
                   (fn [_ args] (reset! seen args)))
      (reg-jvm-fx! :rf.nav/push-url nav-fx/push-url-meta (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "doc-42"}
                         {:fragment "section-3"}])
      (is (= {:id "doc-42"} (get-in @seen [:to :params]))
          "the handler receives the RAW :to :params (not redacted)")
      (is (= "section-3" (:fragment @seen))
          "the handler receives the RAW :fragment (scrolling needs it)"))))

(deftest push-url-not-marked-routes-real-url
  (testing "rf2-1wmni6/rf2-pbbo68: :rf.nav/push-url is deliberately NOT
            `:sensitive` — the pushed URL is the navigation's behavioural
            identity (the open-redirect gate already cleared it), and the
            `:effects-routed` conformance contract + epoch :effects projection
            assert the ACTUAL routed URL. Carrier-bearing route-miss / blocked
            URLs are scrubbed at their diagnostic emit sites instead, so
            push-url's :rf.fx/handled trace shows the real same-origin URL."
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (reg-jvm-fx! :rf.nav/scroll   scroll/scroll-fx-meta (fn [_ _] nil))
    (reg-jvm-fx! :rf.nav/push-url nav-fx/push-url-meta  (fn [_ _] nil))
    (let [args (handled-trace-for
                 :rf.nav/push-url
                 [:rf/url-requested {:url "/articles/intro"}])]
      ;; A normal same-origin app URL rides verbatim on the trace (behavioural
      ;; identity, not a redacted carrier) — push-url carries no :sensitive mark.
      (is (= "/articles/intro" args)
          "the push-url URL routes/traces the real same-origin URL"))))

;; ===========================================================================
;; rf2-n1f4rh — route-miss diagnostics redact the raw requested URL.
;; ===========================================================================

(deftest route-miss-no-such-handler-redacts-url-carriers
  (testing "rf2-n1f4rh: an unmatched URL with query/fragment token carriers →
            :rf.error/no-such-handler trace has the carrier VALUES redacted
            (path + :reason kept for app error handling)"
    ;; No route registered for /oauth → route-miss → fallback to not-found.
    (rf/reg-route :rf.route/not-found {} "/404")
    (let [traces (atom [])]
      (rf/register-listener! :trace ::miss (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned
                         "/oauth/callback?code=topsecret&state=xyz#access_token=leak"])
      (rf/unregister-listener! :trace ::miss)
      (let [err (->> @traces
                     (filter #(= :rf.error/no-such-handler (:operation %)))
                     first)]
        (is (some? err) ":rf.error/no-such-handler was emitted for the route miss")
        (let [url (-> err :tags :url)]
          (is (string? url) "the :url slot is present (structured path kept)")
          (is (re-find #"^/oauth/callback" url) "the PATH is preserved")
          (is (not (re-find #"topsecret" url)) "the query secret is NOT raw")
          (is (not (re-find #"leak" url)) "the fragment secret is NOT raw")
          (is (re-find (re-pattern sentinel-str) url)
              "carrier values replaced with the rf/redacted sentinel"))
        ;; The structured discriminator the app error handler needs survives.
        (is (= :route (-> err :tags :kind)) ":kind :route discriminator kept")))))

;; ===========================================================================
;; rf2-jfaucw — blocked-navigation record keeps no raw route carriers on
;; egress, while continue/cancel resume still work in-process.
;; ===========================================================================

(defn- block-fixture!
  "Land on an editor route guarded by a blocking :can-leave."
  []
  (rf/reg-route :editor/article
                {:params    [:map [:id :string]]
                 :can-leave :editor/can-leave?} "/editor/articles/:id")
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
  (rf/reg-sub :editor/can-leave? (fn [db _] (not (get-in db [:editor :dirty?]))))
  (rf/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
  (rf/dispatch-sync [:editor/dirty true]))

(deftest navigation-blocked-trace-redacts-requested-url-carriers
  (testing "rf2-jfaucw: the :rf.route/navigation-blocked TRACE redacts the
            :requested-url query/fragment carriers"
    (block-fixture!)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::blocked (fn [ev] (swap! traces conj ev)))
      ;; Try to leave to a URL carrying a query secret → blocked.
      (rf/dispatch-sync [:rf/url-requested {:url "/cart?coupon=SECRET100&ref=x"}])
      (rf/unregister-listener! :trace ::blocked)
      (let [blocked (->> @traces
                         (filter #(= :rf.route/navigation-blocked (:operation %)))
                         first)]
        (is (some? blocked) "a navigation-blocked trace fired")
        (let [url (-> blocked :tags :requested-url)]
          (is (re-find #"^/cart" url) "the path is preserved")
          (is (not (re-find #"SECRET100" url)) "the query secret is NOT raw on the trace")
          (is (re-find (re-pattern sentinel-str) url) "carrier value redacted"))
        ;; The structural discriminator survives.
        (is (= :editor/can-leave? (-> blocked :tags :rejecting-guard))
            ":rejecting-guard kept")))))

(deftest navigation-blocked-dispatched-event-payload-redacts-carriers
  (testing "rf2-jfaucw: the DISPATCHED [:rf.route/navigation-blocked pending-nav]
            event trace redacts the pending-nav :requested-url +
            :requested-by-event carrier slots via event marks"
    (block-fixture!)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart?coupon=SECRET100"}])
      (rf/unregister-listener! :trace ::nb)
      ;; Find a dispatched-event trace carrying the navigation-blocked event vec.
      (let [dispatched (->> @traces
                            (keep (fn [ev]
                                    (let [v (or (-> ev :tags :rf.event/v)
                                                (-> ev :tags :event))]
                                      (when (and (vector? v)
                                                 (= :rf.route/navigation-blocked (first v)))
                                        v))))
                            first)]
        (is (some? dispatched) "the navigation-blocked event vector was traced")
        (let [pending-nav (second dispatched)]
          (is (= privacy/redacted-sentinel (:requested-url pending-nav))
              ":requested-url redacted in the dispatched-event trace payload")
          (is (= privacy/redacted-sentinel (:requested-by-event pending-nav))
              ":requested-by-event redacted in the dispatched-event trace payload")
          ;; Structural slots survive.
          (is (= :can-leave (:reason pending-nav)) ":reason kept")
          (is (contains? pending-nav :id) ":id (pending-nav handle) kept"))))))

(deftest navigation-blocked-pending-nav-slot-keeps-raw-in-process
  (testing "rf2-jfaucw: the DURABLE pending-nav runtime-db slot keeps the RAW
            :requested-url / :requested-by-event so continue/cancel resume
            still work (marks/scrub touch only the egress copy)"
    (block-fixture!)
    (rf/dispatch-sync [:rf/url-requested {:url "/cart?coupon=SECRET100"}])
    (let [pending (get-in (rf/runtime-db-value :rf/default)
                          [:rf.runtime/routing :pending-navigation])]
      (is (some? pending) "the block wrote the pending-nav slot")
      ;; The in-process durable value is RAW (not redacted) — resume needs it.
      (is (= "/cart?coupon=SECRET100" (:requested-url pending))
          "the durable :requested-url is the RAW URL (continue re-dispatches it)")
      (is (vector? (:requested-by-event pending))
          "the durable :requested-by-event is the raw original event vector"))
    ;; And continue actually completes the navigation (resume works).
    (rf/dispatch-sync [:rf.route/continue (-> (rf/runtime-db-value :rf/default)
                                              (get-in [:rf.runtime/routing :pending-navigation :id]))])
    (is (nil? (get-in (rf/runtime-db-value :rf/default)
                      [:rf.runtime/routing :pending-navigation]))
        "continue cleared the pending slot (resume completed from the raw value)")))
