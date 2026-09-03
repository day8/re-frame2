(ns re-frame.routing-egress-test
  "EP-0015 (Spec 015 §Registration-owned transient classification) routing
  egress-projection regressions.

  Three findings from the EP-0015 review wave, all on the routing surface:

    - rf2-1wmni6 / rf2-pbbo68 — the rf.routing.scroll/history fx (`:rf.nav/scroll`,
      `:rf.nav/capture-scroll`, `:rf.nav/push-url`, `:rf.nav/replace-url`)
      build args carrying raw route params/query/fragment/URLs, and the core
      fx trace records `:rf.fx/args` verbatim onto `:rf.fx/handled`. Fixed by
      `:sensitive` path-marks on the fx registrations so the marks chokepoint
      redacts the carrier slots on the trace egress copy (handler input
      unaffected).
    - rf2-n1f4rh — the route-miss diagnostics (`:rf.warning/malformed-url`,
      `:rf.error/no-such-handler`) emit the raw requested URL under a custom
      `:url` slot the marks chokepoint does not walk. Fixed by a default-on
      URL-carrier scrub (`re-frame.privacy.url/redact-url-carriers`) at the
      emit site — no schema to consult on a route miss, so query/fragment
      values are redacted by default.
    - rf2-jfaucw — the blocked-navigation record keeps raw route carriers:
      the `:rf.route/navigation-blocked` TRACE carries `:requested-url`
      (custom slot → emit-site scrub) and the DISPATCHED event payload carries
      the pending-nav map with `:requested-url` + `:destination` / `:target`
      (event marks → marks chokepoint).

  The unifying EP-0015 invariant under test: the IN-PROCESS value stays raw
  (the handler / pending-nav sub / continue-cancel resume need it), only the
  EGRESS copy (trace bus / Xray / MCP / log / epoch) is projected.

  ## Posture split (rf2-o5dbf)

  Read this before adding a case here: the answer to \"is this leg
  production-real?\" is NOT uniform across this namespace, and getting it
  wrong is a privacy hole rather than a red test.

  PRODUCTION-REAL, asserted with NO posture guard (so they run in the
  ordinary `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh`,
  the `-Dre-frame.debug=false` lane):

    * the pure carrier scrub `rf.privacy.url/redact-url-carriers` /
      `rf.privacy.url/redact-url-tag` — plain functions the emit sites call
      unconditionally, no `rf.interop/debug-enabled?` anywhere near them (they
      live in core since rf2-6l2nc; their own unit battery moved with them to
      `re-frame.privacy-url-test`, and what is asserted here is that routing's
      emit sites reach them);
    * the `:sensitive` RETENTION itself (`rf/handler-meta`, both the
      positional and the frame-targeted public arities) — registrar state,
      posture-independent, and the mechanism every projection rides on. This
      is the rf2-kqxe6.20 fix proper;
    * the IN-PROCESS rawness — handlers, the durable pending-nav slot,
      continue/cancel resume;
    * the `:rf/route` SUB-EGRESS half (`rf.routing.sub-egress/route-sub-seed-path`, the
      `rf.elision/elide-wire-value` projections). rf2-u2x6w established that
      sub-classification genuinely egresses in production, and its always-on
      witnesses live in `re-frame.routing-sub-egress-production-test`, which
      is separately IN the lane.

  DEV-ONLY, and kept VERBATIM inside `(when rf.interop/debug-enabled? …)` arms
  marked `rf2-o5dbf`: every assertion read off the TRACE BUS — the
  `:rf.fx/handled` `:rf.fx/args` copy, the `:rf.error/no-such-handler` and
  `:rf.route/navigation-blocked` tag maps, and the dispatched-event payload
  copies. `trace/emit!` sits behind `rf.interop/debug-enabled?`, read once at
  load time, so under the real gate there is no trace bus to project onto.

  Two of those are NEGATIVE over the trace — `(not (re-find #\"SECRET100\"
  (pr-str payload)))` in the inverse-load-order case, and the
  `(not (re-find …))` pair in the route-miss case. With no trace, `payload` is
  nil and the assertion passes VACUOUSLY: it would report a privacy guarantee
  the framework never executed. They are inside the arm for that reason
  specifically. Nothing was deleted or weakened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.elision :as rf.elision]
            [re-frame.privacy :as rf.privacy]
            [re-frame.privacy.url :as rf.privacy.url]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing :as rf.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.routing.nav-fx :as rf.routing.nav-fx]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.routing.sub-egress :as rf.routing.sub-egress]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private sentinel-str (subs (str rf.privacy/redacted-sentinel) 1))  ;; "rf/redacted"

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
  ;; FN form (no source-coord capture): the override REPLACES the framework's
  ;; nil-provenance source-store slot instead of colliding as a cross-ns
  ;; duplicate at default-image assembly (rf2-h1vqa4).
  (rf.fx/reg-fx fx-id (assoc prod-meta :platforms #{:server :client}) handler))

;; ===========================================================================
;; The pure URL-carrier scrub itself is CORE's (rf2-6l2nc). Its unit cases —
;; the happy path plus the rf2-vh4lbf adversarial-input battery — moved to
;; `re-frame.privacy-url-test` when `redact-url-carriers` moved to
;; `re-frame.privacy.url`, so the policy is pinned where it is defined rather
;; than in one of the two artefacts that call it. What stays here is what is
;; about ROUTING: which emit sites reach the scrub, and what the egress copy
;; of each looks like.
;; ===========================================================================

;; ===========================================================================
;; rf2-1wmni6 / rf2-pbbo68 — rf.routing.scroll/history fx :sensitive marks project the
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
    (reg-jvm-fx! :rf.nav/scroll   rf.routing.scroll/scroll-fx-meta   (fn [_ _] nil))
    (reg-jvm-fx! :rf.nav/push-url rf.routing.nav-fx/push-url-meta     (fn [_ _] nil))
    ;; Land on a route WITH params so the next nav's :from carries :params.
    (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "secret-doc-id"}}])
    ;; SEMANTIC, posture-independent (rf2-o5dbf): the projection the trace copy
    ;; rides on is the fx registration's own `:sensitive` declaration, which is
    ;; registrar state and survives -Dre-frame.debug=false. If the mark is lost,
    ;; the trace assertions below could not hold in ANY posture.
    (is (= [[:from :params] [:from :query] [:to :params] [:to :query] [:fragment]]
           (:sensitive (rf/handler-meta :fx :rf.nav/scroll)))
        ":rf.nav/scroll declares the route-descriptor carrier slots :sensitive")
    ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (let [args (handled-trace-for
                   :rf.nav/scroll
                   [:rf.route/navigate {:to :route/article :params {:id "another-secret"} :fragment "tok-in-fragment"}])]
        (is (some? args) "a :rf.nav/scroll :rf.fx/handled trace was emitted")
        ;; The descriptor :id keyword survives (names the shape, no secret).
        (is (= :route/article (get-in args [:to :id]))
            ":to :id (route keyword) rides verbatim")
        ;; The carrier slots are redacted to the sentinel.
        (is (= rf.privacy/redacted-sentinel (get-in args [:to :params]))
            ":to :params (the document-id carrier) is redacted on the trace")
        (is (= rf.privacy/redacted-sentinel (get-in args [:from :params]))
            ":from :params is redacted on the trace")
        (is (= rf.privacy/redacted-sentinel (:fragment args))
            ":fragment is redacted on the trace")
        ;; :strategy is structural, not a carrier — it rides.
        (is (contains? args :strategy) ":strategy rides verbatim")))))

(deftest scroll-fx-handler-still-receives-raw-args-in-process
  (testing "rf2-1wmni6/rf2-pbbo68: the marks projection touches ONLY the trace
            egress copy — the in-process handler still receives the raw args
            (scroll restoration / fragment scrolling unaffected)"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [seen (atom nil)]
      ;; Capture what the HANDLER actually receives (not the trace).
      (reg-jvm-fx! :rf.nav/scroll   rf.routing.scroll/scroll-fx-meta
                   (fn [_ args] (reset! seen args)))
      (reg-jvm-fx! :rf.nav/push-url rf.routing.nav-fx/push-url-meta (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "doc-42"} :fragment "section-3"}])
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
    (reg-jvm-fx! :rf.nav/scroll   rf.routing.scroll/scroll-fx-meta (fn [_ _] nil))
    (reg-jvm-fx! :rf.nav/push-url rf.routing.nav-fx/push-url-meta  (fn [_ _] nil))
    ;; SEMANTIC, posture-independent (rf2-o5dbf): the DELIBERATE ABSENCE of a
    ;; `:sensitive` mark is registrar state, so it is assertable under the
    ;; production gate — and it is the fact the trace expectation below rests
    ;; on. A mark added here by accident would fail this, in both postures.
    (is (nil? (:sensitive (rf/handler-meta :fx :rf.nav/push-url)))
        ":rf.nav/push-url declares NO :sensitive marks — the pushed URL is the
         navigation's behavioural identity, not a diagnostic carrier")
    ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (let [args (handled-trace-for
                   :rf.nav/push-url
                   [:rf.route/url-requested {:url "/articles/intro"}])]
        ;; A normal same-origin app URL rides verbatim on the trace (behavioural
        ;; identity, not a redacted carrier) — push-url carries no :sensitive mark.
        (is (= "/articles/intro" args)
            "the push-url URL routes/traces the real same-origin URL")))))

;; ===========================================================================
;; rf2-n1f4rh — route-miss diagnostics redact the raw requested URL.
;; ===========================================================================

(deftest route-miss-no-such-handler-redacts-url-carriers
  (testing "rf2-n1f4rh: an unmatched URL with query/fragment token carriers →
            :rf.error/no-such-handler trace has the carrier VALUES redacted
            (path + :reason kept for app error handling)"
    ;; No route registered for /oauth → route-miss → fallback to not-found.
    (rf/reg-route :rf.route/not-found {} "/404")
    (let [raw "/oauth/callback?code=topsecret&state=xyz#access_token=leak"
          traces (atom [])]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the scrub the emit site
      ;; applies is `rf.privacy.url/redact-url-tag`, an ALWAYS-ON pure function — no
      ;; `rf.interop/debug-enabled?` between it and the caller. Assert it on the
      ;; exact tag map the route-miss telemetry builds, so the scrub itself is
      ;; proven under the production gate even though the trace is not emitted
      ;; there.
      (let [scrubbed (:url (rf.privacy.url/redact-url-tag {:url raw :kind :route} :url))]
        (is (re-find #"^/oauth/callback" scrubbed) "the PATH is preserved")
        (is (not (re-find #"topsecret" scrubbed)) "the query secret is NOT raw")
        (is (not (re-find #"leak" scrubbed)) "the fragment secret is NOT raw")
        (is (re-find (re-pattern sentinel-str) scrubbed)
            "carrier values replaced with the rf/redacted sentinel"))
      (rf/register-listener! :trace ::miss (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned raw])
      (rf/unregister-listener! :trace ::miss)
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The two
      ;; `(not (re-find …))` legs are NEGATIVE: with no trace `url` is nil and
      ;; they would pass vacuously, which is why they live in here rather than
      ;; beside the semantics.
      (when rf.interop/debug-enabled?
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
          (is (= :route (-> err :tags :kind)) ":kind :route discriminator kept"))))))

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
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
  (rf/dispatch-sync [:editor/dirty true]))

(deftest navigation-blocked-trace-redacts-requested-url-carriers
  (testing "rf2-jfaucw: the :rf.route/navigation-blocked TRACE redacts the
            :requested-url query/fragment carriers"
    (block-fixture!)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::blocked (fn [ev] (swap! traces conj ev)))
      ;; Try to leave to a URL carrying a query secret → blocked.
      (rf/dispatch-sync [:rf.route/url-requested {:url "/cart?coupon=SECRET100&ref=x"}])
      (rf/unregister-listener! :trace ::blocked)
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the `:requested-url` scrub
      ;; the emit site applies (decisions.cljc §301-309) is the ALWAYS-ON
      ;; `rf.privacy.url/redact-url-tag` on the `:requested-url` slot. Assert it
      ;; there, so the scrub itself is proven under the production gate.
      (let [scrubbed (:requested-url
                       (rf.privacy.url/redact-url-tag
                         {:requested-url "/cart?coupon=SECRET100&ref=x"
                          :rejecting-guard :editor/can-leave?}
                         :requested-url))]
        (is (re-find #"^/cart" scrubbed) "the path is preserved")
        (is (not (re-find #"SECRET100" scrubbed)) "the query secret is NOT raw")
        (is (re-find (re-pattern sentinel-str) scrubbed) "carrier value redacted"))
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
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
              ":rejecting-guard kept"))))))

(deftest navigation-blocked-dispatched-event-payload-redacts-carriers
  (testing "rf2-jfaucw: the DISPATCHED [:rf.route/navigation-blocked pending-nav]
            event trace redacts the pending-nav :requested-url +
            :destination / :target carrier slots via event marks"
    (block-fixture!)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/url-requested {:url "/cart?coupon=SECRET100"}])
      (rf/unregister-listener! :trace ::nb)
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the marks chokepoint reads
      ;; the event registration's `:sensitive` declaration, which is registrar
      ;; state. That declaration is what the redaction below IS — assert it
      ;; where the production gate can see it.
      (is (= [[:requested-url] [:destination] [:target]]
             (:sensitive (rf/handler-meta :event :rf.route/navigation-blocked)))
          "the framework declares the pending-nav carrier slots :sensitive")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
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
            (is (= rf.privacy/redacted-sentinel (:requested-url pending-nav))
                ":requested-url redacted in the dispatched-event trace payload")
            (is (= rf.privacy/redacted-sentinel (:destination pending-nav))
                ":destination redacted in the dispatched-event trace payload")
            (is (= rf.privacy/redacted-sentinel (:target pending-nav))
                ":target redacted in the dispatched-event trace payload")
            ;; Structural slots survive.
            (is (= :link (:cause pending-nav)) ":cause kept")
            (is (contains? pending-nav :id) ":id (pending-nav handle) kept")))))))

(deftest navigation-blocked-pending-nav-slot-keeps-raw-in-process
  (testing "rf2-jfaucw: the DURABLE pending-nav runtime-db slot keeps the RAW
            :requested-url / :destination / :target so continue/cancel resume
            still work (marks/scrub touch only the egress copy)"
    (block-fixture!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart?coupon=SECRET100"}])
    (let [pending (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :pending-navigation])]
      (is (some? pending) "the block wrote the pending-nav slot")
      ;; The in-process durable value is RAW (not redacted) — resume needs it.
      (is (= "/cart?coupon=SECRET100" (:requested-url pending))
          "the durable :requested-url is the RAW URL (continue re-dispatches it)")
      (is (= {:to :route/cart :query {"coupon" "SECRET100"}} (:destination pending))
          "the durable :destination is the raw replayable destination"))
    ;; And continue actually completes the navigation (resume works).
    (rf/dispatch-sync [:rf.route/continue (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                                              (get-in [:rf.runtime/routing :pending-navigation :id]))])
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :pending-navigation]))
        "continue cleared the pending slot (resume completed from the raw value)")))

;; ===========================================================================
;; rf2-kqxe6.20 — the carrier classification SURVIVES a public behaviour
;; override of a replaceable framework default.
;;
;; `:rf.route/entry-denied` / `:rf.route/navigation-blocked` are replaceable
;; framework defaults (Spec 012 §Replaceable framework defaults): an ordinary
;; `rf/reg-event` under the same id is the documented auth recipe. The payload
;; those events carry is FRAMEWORK-constructed, so its URL carriers are the
;; framework's own `:sensitive` declaration — and it must not evaporate because
;; the application supplied its own handler. Before rf2-kqxe6.20 it did: the
;; canonical auth recipe (which declares no metadata at all) silently shipped
;; the full denied destination to trace / off-box observation.
;;
;; These cases drive the PUBLIC `rf/reg-event` spelling every doc, example and
;; skill teaches — no `:sensitive` boilerplate — and assert redaction where
;; egress actually happens, not merely that metadata is present.
;; ===========================================================================

(defn- entry-fixture!
  "A `/account` route guarded by a `:can-enter` that says NO, plus no-op nav fx —
  so any entry door produces a terminal denial."
  []
  (rf/reg-route :route/account {:can-enter [:auth/signed-in?]} "/account")
  (rf/reg-route :route/home    {} "/home")
  (rf/reg-sub   :auth/signed-in? (fn [_ _] false))
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/transitioned "/home"]))

(defn- traced-event-payload
  "Run `f` with a trace listener attached and return the arg-map of the first
  traced dispatched-event vector whose id is `event-id` — i.e. the EGRESS copy
  of the payload, after classification projection."
  [event-id f]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::carrier (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace ::carrier)))
    (->> @traces
         (keep (fn [ev]
                 (let [v (or (-> ev :tags :rf.event/v) (-> ev :tags :event))]
                   (when (and (vector? v) (= event-id (first v)))
                     (second v)))))
         first)))

(deftest public-entry-denied-override-still-redacts-carriers-on-egress
  (testing "rf2-kqxe6.20: the canonical auth recipe — a bare public
            `rf/reg-event :rf.route/entry-denied` with NO :sensitive
            boilerplate — still redacts :requested-url / :destination / :target
            in the dispatched-event trace, while the handler itself receives the
            RAW payload in-process"
    (entry-fixture!)
    (let [seen (atom [])]
      ;; THE canonical recipe, verbatim: behaviour only, no metadata map.
      (rf/reg-event :rf.route/entry-denied
                    (fn [{:keys [db]} [_ {:keys [destination] :as denial}]]
                      (swap! seen conj denial)
                      {:db (assoc-in db [:auth :return-to] destination)}))
      ;; SEMANTIC, posture-independent (rf2-o5dbf): rf2-kqxe6.20's actual fix is
      ;; the RETENTION — the framework's carrier declaration survives a bare
      ;; public override that declares no metadata at all. That is registrar
      ;; state, so it is provable under the production gate, and it is the
      ;; mechanism the trace redaction below rides on.
      (is (= [[:requested-url] [:destination] [:target]]
             (:sensitive (rf/handler-meta :event :rf.route/entry-denied)))
          "the framework's carriers survive the bare public override")
      ;; The dispatch runs in BOTH postures — the in-process assertions below
      ;; depend on it. Only the trace-payload readings are posture-gated.
      (let [payload (traced-event-payload
                      :rf.route/entry-denied
                      #(rf/dispatch-sync
                         [:rf.route/handle-url-change "/account?invite=SECRET100"]))]
        ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (some? payload) "the entry-denied event vector was traced")
          (is (= rf.privacy/redacted-sentinel (:requested-url payload))
              ":requested-url redacted on the egress copy after the override")
          (is (= rf.privacy/redacted-sentinel (:destination payload))
              ":destination redacted on the egress copy after the override")
          (is (= rf.privacy/redacted-sentinel (:target payload))
              ":target redacted on the egress copy after the override")
          (is (= :auth/signed-in? (:guard payload)) "the structural :guard slot kept")))
      ;; Handler invocation is unchanged — called exactly once, with RAW values.
      (is (= 1 (count @seen)) "the app handler ran exactly once")
      (let [denial (first @seen)]
        (is (= "/account?invite=SECRET100" (:requested-url denial))
            "the in-process handler saw the RAW requested URL")
        (is (= {"invite" "SECRET100"} (:query (:destination denial)))
            "the in-process handler saw the RAW replayable destination — the
             recipe stashes it and replays it after a successful sign-in")))))

(deftest public-navigation-blocked-override-still-redacts-carriers-on-egress
  (testing "rf2-kqxe6.20: the leave half behaves identically — a bare public
            override of :rf.route/navigation-blocked keeps the pending-nav
            carrier redaction on the dispatched-event trace"
    (block-fixture!)
    (let [seen (atom [])]
      (rf/reg-event :rf.route/navigation-blocked
                    (fn [_ [_ pending]] (swap! seen conj pending) {}))
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the retention itself — see
      ;; the entry-denied twin above.
      (is (= [[:requested-url] [:destination] [:target]]
             (:sensitive (rf/handler-meta :event :rf.route/navigation-blocked)))
          "the framework's carriers survive the bare public override")
      (let [payload (traced-event-payload
                      :rf.route/navigation-blocked
                      #(rf/dispatch-sync
                         [:rf.route/url-requested {:url "/cart?coupon=SECRET100"}]))]
        ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (some? payload) "the navigation-blocked event vector was traced")
          (is (= rf.privacy/redacted-sentinel (:requested-url payload)))
          (is (= rf.privacy/redacted-sentinel (:destination payload)))
          (is (= rf.privacy/redacted-sentinel (:target payload)))
          (is (= :link (:cause payload)) "the structural :cause slot kept")))
      (is (= 1 (count @seen)) "the app handler ran exactly once")
      (is (= "/cart?coupon=SECRET100" (:requested-url (first @seen)))
          "the in-process handler saw the RAW requested URL (resume needs it)"))))

(deftest an-app-classification-is-additive-over-the-retained-carriers
  (testing "rf2-kqxe6.20: an override that DOES declare :sensitive gets both —
            its own paths AND the framework's carriers. The retention is a
            union, not a replacement in the other direction"
    (entry-fixture!)
    (rf/reg-event :rf.route/entry-denied
                  {:sensitive [[:guard]]}
                  (fn [_ _] {}))
    (is (= [[:requested-url] [:destination] [:target] [:guard]]
           (:sensitive (rf/handler-meta :event :rf.route/entry-denied)))
        "framework carriers first, then the app's own declaration")
    (let [payload (traced-event-payload
                    :rf.route/entry-denied
                    #(rf/dispatch-sync
                       [:rf.route/handle-url-change "/account?invite=SECRET100"]))]
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The UNION
      ;; itself — the property this deftest is named for — is asserted above on
      ;; `rf/handler-meta`, posture-independently.
      (when rf.interop/debug-enabled?
        (is (= rf.privacy/redacted-sentinel (:requested-url payload))
            "the framework's carrier still redacts")
        (is (= rf.privacy/redacted-sentinel (:guard payload))
            "the app's own declared path redacts too")))))

(deftest the-retained-carriers-survive-a-hot-reload-re-registration
  (testing "rf2-kqxe6.20: re-evaluating the app namespace re-registers the
            override. The framework's own copy is retained in the source store,
            so the carriers ride the SECOND registration too — the classification
            does not decay across a hot reload"
    (entry-fixture!)
    (rf/reg-event :rf.route/entry-denied (fn [_ _] {}))
    (rf/reg-event :rf.route/entry-denied (fn [_ _] {}))
    (is (= [[:requested-url] [:destination] [:target]]
           (:sensitive (rf/handler-meta :event :rf.route/entry-denied)))
        "no duplication, no decay")
    (let [payload (traced-event-payload
                    :rf.route/entry-denied
                    #(rf/dispatch-sync
                       [:rf.route/handle-url-change "/account?invite=SECRET100"]))]
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
      ;; no-duplication / no-decay property is asserted above on
      ;; `rf/handler-meta`, posture-independently.
      (when rf.interop/debug-enabled?
        (is (= rf.privacy/redacted-sentinel (:requested-url payload))
            "still redacted after the re-registration")))))

;; ===========================================================================
;; rf2-kqxe6.20 (reopened by the merged-PR audit of #6949) — the retention is
;; ORDER-INDEPENDENT.
;;
;; Every case above loads `re-frame.routing` FIRST, which is only one of the two
;; legal orders. `re-frame.core` does not pull the routing artefact in, so an
;; application namespace that registers `:rf.route/entry-denied` — the canonical
;; auth recipe — and never requires the facade itself is loaded FIRST whenever
;; something else requires `re-frame.routing` later.
;;
;; Registration-time retention alone cannot cover that order: when the app
;; registration is recorded there is no framework descriptor in the source store
;; to read, so the app descriptor was stored carrier-less and the framework's
;; later seeding did not reconcile it. The app handler still won the frame, so
;; the ONLY observable difference was at egress — the framework's URL carriers
;; shipped RAW to every trace / off-box projection, purely because of require
;; order. The seam therefore reconciles both ways.
;; ===========================================================================

(defn- restage-app-registered-before-routing!
  "Re-stage the process in the INVERSE namespace-load order, leaving a live
  URL-owning `:rf/default`: wipe the registry (so the facade's replaceable
  defaults are gone), run `register-app!`, and only THEN reload
  `re-frame.routing` so it seeds its defaults over an ALREADY-PRESENT
  application registration.

  This is the suite fixture's own clear-and-reload recovery sequence
  (`re-frame.routing-test-support/reset-runtime`) with the application
  registration moved AHEAD of the facade reload — the one difference under test."
  [register-app!]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (register-app!)
  (require 're-frame.routing :reload)
  (require 're-frame.routing.test-support :reload)
  (rf.routing/reset-counters!)
  (rf.routing/reset-scroll-cache!)
  (rf.routing/reset-nav-counters!)
  (rf.routing/reset-url-claims!)
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Inverse-load-order default app frame (explicit URL owner)."}))

(defn- frame-targeted-meta
  "The registration metadata `:rf/default` ITSELF resolves for `(kind, id)`, read
  through the PUBLIC frame-targeted arity `(rf/handler-meta {:frame … :kind …
  :id …})`.

  This is the arity Spec 012 names as the effective read, so the test drives the
  public map form directly rather than reaching for the internal
  `call-with-frame-resolution` seam it is built on: the contract that is
  documented is then the contract that is proven, on the surface a tool (Xray,
  re-frame-pair) actually calls."
  [kind id]
  (rf/handler-meta {:frame :rf/default :kind kind :id id}))

(defn- frame-targeted-sensitive
  "The `:sensitive` declaration the frame-targeted public read reports — the
  EFFECTIVE classification, as distinct from the POSITIONAL `(rf/handler-meta
  kind id)` read, which is the process resolver map (last-write-wins)."
  [kind id]
  (:sensitive (frame-targeted-meta kind id)))

(deftest app-registered-before-routing-still-redacts-framework-carriers
  (testing "rf2-kqxe6.20: the application namespace registers
            :rf.route/entry-denied BEFORE re-frame.routing seeds its replaceable
            defaults. The app handler is still the frame's winner, and the
            classification the frame EFFECTIVELY resolves is the same union the
            default-first order produces — so the framework's URL carriers
            redact at the trace chokepoint even though there was no framework
            descriptor to retain from when the app registered"
    (let [seen (atom [])]
      (restage-app-registered-before-routing!
        (fn []
          ;; The canonical recipe, plus an app-owned declaration so ONE pass
          ;; pins both directions of the union.
          (rf/reg-event :rf.route/entry-denied
                        {:sensitive [[:guard]]}
                        (fn [{:keys [db]} [_ {:keys [destination] :as denial}]]
                          (swap! seen conj denial)
                          {:db (assoc-in db [:auth :return-to] destination)}))))
      (entry-fixture!)
      ;; (1) FRAME-TARGETED metadata through the PUBLIC map arity — the read
      ;;     Spec 012 names as effective, and what every dispatch / projection
      ;;     resolves.
      (is (= [[:requested-url] [:destination] [:target] [:guard]]
             (frame-targeted-sensitive :event :rf.route/entry-denied))
          "the frame resolves the SAME union as the default-first order:
           framework carriers first, then the app's own declaration")
      (let [m (frame-targeted-meta :event :rf.route/entry-denied)]
        (is (nil? (:rf/framework-default? m))
            "the frame-targeted read resolves the APPLICATION descriptor — the
             framework's own copy stopped being projected once the app
             registered the id")
        (is (some? (:rf.provenance/ns m))
            "and it carries application provenance, so a tool can say WHOSE
             handler this is"))
      ;; (1b) The POSITIONAL read is the process resolver map, and its documented
      ;;      semantics are process-global LAST-WRITE-WINS — not the effective
      ;;      classification. In THIS order the framework's own seeding is the
      ;;      last writer, so the positional read reports the framework no-op and
      ;;      omits the app's own path. Spec 012 states this rather than claiming
      ;;      the positional form converges; pinned here so the documented
      ;;      semantics cannot drift silently, and so the difference between the
      ;;      two public arities stays visible to whoever reads this next.
      ;;
      ;;      This is NOT a carrier leak: the framework carriers ARE present at
      ;;      this surface, and every dispatch / egress projection resolves
      ;;      through the frame (assertion 2 below is the proof).
      (let [positional (rf/handler-meta :event :rf.route/entry-denied)]
        (is (= [[:requested-url] [:destination] [:target]] (:sensitive positional))
            "last writer in this order is the framework's own seeding")
        (is (true? (:rf/framework-default? positional))
            "so the positional read identifies the framework no-op")
        (is (not= (:sensitive positional)
                  (frame-targeted-sensitive :event :rf.route/entry-denied))
            "the two public arities answer DIFFERENTLY under this load order —
             which is exactly why the frame-targeted form is the effective read"))
      ;; (2) TRACE PROJECTION — the egress the classification exists for.
      ;;     rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
      ;;     RECONCILIATION this deftest exists for is assertion (1) above,
      ;;     which is registrar state and posture-independent. Note the final
      ;;     leg is NEGATIVE over the trace copy: with no trace `payload` is
      ;;     nil, `(pr-str nil)` is "nil", and it would report a privacy
      ;;     guarantee the framework never executed.
      (let [payload (traced-event-payload
                      :rf.route/entry-denied
                      #(rf/dispatch-sync
                         [:rf.route/handle-url-change "/account?invite=SECRET100"]))]
        (when rf.interop/debug-enabled?
          (is (some? payload) "the entry-denied event vector was traced")
          (is (= rf.privacy/redacted-sentinel (:requested-url payload))
              ":requested-url redacted at egress under the inverse load order")
          (is (= rf.privacy/redacted-sentinel (:destination payload))
              ":destination redacted at egress under the inverse load order")
          (is (= rf.privacy/redacted-sentinel (:target payload))
              ":target redacted at egress under the inverse load order")
          (is (= rf.privacy/redacted-sentinel (:guard payload))
              "the app's OWN declared path still redacts — the union is not a
               replacement in either direction")
          (is (not (re-find #"SECRET100" (pr-str payload)))
              "GUARD: the query secret appears NOWHERE on the egress copy")))
      ;; (3) APP BEHAVIOUR — unchanged: the app handler is the frame's winner,
      ;;     runs exactly once, and receives the RAW payload in-process.
      (is (= 1 (count @seen))
          "the APP handler ran exactly once (it is the frame's winner)")
      (let [denial (first @seen)]
        (is (= "/account?invite=SECRET100" (:requested-url denial))
            "the in-process handler saw the RAW requested URL")
        (is (= {"invite" "SECRET100"} (:query (:destination denial)))
            "the in-process handler saw the RAW replayable destination")))))

;; ===========================================================================
;; rf2-mtzv5m — route classification applied at :rf/route SUB-EGRESS surfaces.
;;
;; A route declares projection-relative `:sensitive` / `:large` paths; at
;; activation they re-root ABSOLUTE under `[:rf.runtime/routing :current …]`
;; in the per-frame elision registry. But the `:rf/route` sub returns the BARE
;; slice (`{:route-id :params :query …}`), so a whole-value-rooted egress walk
;; of the sub value never matched the re-rooted decls — a `:sensitive` query /
;; param shipped RAW on the trace bus / Pair MCP / Xray wire, contradicting
;; Spec 012 §Lowering and re-rooting. The fix is a routing-owned / late-bound
;; route-sub egress projector with a sub-id → runtime-path SEED TABLE
;; (`re-frame.routing.sub-egress`) that re-seeds the egress walk at the slice's
;; storage position — the direct-read sibling of the SSR #4896 fix.
;;
;; The invariant under test: a `:sensitive [[:query :token]]` route redacts in
;; BOTH the `:rf.sub/run` trace (`re-frame.classification/project-trace-event`)
;; AND the Pair MCP `read-sub` path (`elide-wire-value` with `:query-v`), while
;; in-process `@(rf/subscribe [:rf/route])` stays RAW. `:rf.route/query` /
;; `:rf.route/params` (whose bare values also carry no route seed) are covered
;; too, plus the `list-subscriptions :include-values` / `snapshot :sub-cache`
;; re-seeding shape.
;; ===========================================================================

(defn- nav-to-sensitive-oauth!
  "Register + navigate to a :sensitive [[:query :token]] / :large
  [[:query :payload]] oauth route so the route classification lowers into
  :rf/default's elision registry and the slice publishes. The :query schema
  promotes the keys to keyword slots (undeclared query keys stay strings)."
  []
  (rf/reg-route :route/oauth
                {:sensitive [[:query :token]]
                 :large     [[:query :payload]]
                 :query     [:map [:token :string] [:payload :string]]}
                "/oauth")
  (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123&payload=blobdata"]))

(defn- route-slice
  "The raw current route slice from :rf/default's runtime-db — exactly what the
  `:rf/route` sub returns in-process."
  []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))

;; ---- the seed table (the projector's data) --------------------------------

(deftest seed-table-covers-the-route-read-subs
  (testing "the three route read subs map to their runtime-db storage positions"
    (is (= [:rf.runtime/routing :current]          (rf.routing.sub-egress/route-sub-seed-path :rf/route)))
    (is (= [:rf.runtime/routing :current :query]   (rf.routing.sub-egress/route-sub-seed-path :rf.route/query)))
    (is (= [:rf.runtime/routing :current :params]  (rf.routing.sub-egress/route-sub-seed-path :rf.route/params))))
  (testing "a non-route sub-id resolves nil (NARROW — no generic propagation)"
    (is (nil? (rf.routing.sub-egress/route-sub-seed-path :some-app/sub)))
    (is (nil? (rf.routing.sub-egress/route-sub-seed-path :rf.route/id)))
    (is (nil? (rf.routing.sub-egress/route-sub-seed-path nil)))))

;; ---- the :rf.sub/run TRACE egress (the dev-trace / Xray wire) --------------

(defn- project-sub-run-trace
  "Run a synthetic `:rf.sub/run` trace event for `sub-id` carrying `value`
  through the framework trace chokepoint `re-frame.classification/project-trace-event`
  — the SINGLE site `re-frame.trace/build-event` consults before delivery to
  every trace listener (the dev-trace bus, Xray, the epoch assembler). Returns
  the projected `:rf.sub/value`."
  [sub-id value]
  (-> (rf.classification/project-trace-event
        {:operation :rf.sub/run
         :tags      {:rf.sub/id sub-id :rf.sub/value value :frame :rf/default}})
      :tags
      :rf.sub/value))

(deftest sub-run-trace-redacts-route-sensitive-query
  (testing "rf2-mtzv5m: the :rf.sub/run trace of [:rf/route] redacts the
            :sensitive query value and elides the :large one — the bare slice
            value is re-seeded at [:rf.runtime/routing :current]"
    (nav-to-sensitive-oauth!)
    (let [projected (project-sub-run-trace :rf/route (route-slice))]
      (is (= rf.privacy/redacted-sentinel (get-in projected [:query :token]))
          "the :sensitive [:query :token] redacts on the :rf.sub/run trace")
      (is (rf.elision/marker? (get-in projected [:query :payload]))
          "the :large [:query :payload] elides to the size marker on the trace")
      (is (= :route/oauth (:route-id projected))
          "non-classified slice fields ride verbatim"))))

(deftest sub-run-trace-redacts-rf-route-query-and-params-subs
  (testing "rf2-mtzv5m: :rf.route/query (the bare :query map) redacts at egress —
            its value carries no route seed without the projector"
    (rf/reg-route :route/oauth
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/oauth")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123"])
    (let [query-map (get-in (route-slice) [:query])
          projected (project-sub-run-trace :rf.route/query query-map)]
      (is (= rf.privacy/redacted-sentinel (:token projected))
          ":rf.route/query value redacts the :token at egress")))
  (testing "rf2-mtzv5m: :rf.route/params (the bare :params map) redacts at egress"
    (rf/reg-route :route/upload
                  {:sensitive [[:params :secret]]}
                  "/upload/:secret")
    (rf/dispatch-sync [:rf.route/transitioned "/upload/topsecret"])
    (let [params-map (get-in (route-slice) [:params])
          projected  (project-sub-run-trace :rf.route/params params-map)]
      (is (= rf.privacy/redacted-sentinel (:secret projected))
          ":rf.route/params value redacts the :secret at egress"))))

;; ---- the Pair MCP read-sub / direct-read egress (elide-wire-value) ---------
;;
;; The Pair MCP `read-sub` / `list-subscriptions :include-values` /
;; `snapshot :sub-cache` / Xray surfaces all route a route sub's value through
;; `re-frame.core/elide-wire-value` server-side, naming the sub via `:query-v`.
;; This pins THAT path: the bare slice walked with `{:query-v [:rf/route]
;; :frame …}` redacts the :sensitive query, while the same call WITHOUT
;; `:query-v` (a non-route value) leaves it raw — proving the re-seed is the
;; mechanism.

(deftest read-sub-elide-wire-value-redacts-route-sub-via-query-v
  (testing "rf2-mtzv5m: elide-wire-value with :query-v [:rf/route] re-seeds at
            the slice storage position and redacts the :sensitive query value
            (the Pair MCP read-sub server-side call shape)"
    (nav-to-sensitive-oauth!)
    (let [slice   (route-slice)
          elided  (rf/elide-wire-value slice {:query-v [:rf/route] :frame :rf/default})]
      (is (= rf.privacy/redacted-sentinel (get-in elided [:query :token]))
          "the route :sensitive query value redacts on the read-sub wire")
      (is (= :route/oauth (:route-id elided))
          "non-classified slice fields ride verbatim")))
  (testing "rf2-mtzv5m: WITHOUT :query-v the bare slice ships raw (the whole-value
            root never matches the re-rooted decl) — confirms the re-seed is
            load-bearing, and that NON-route values are untouched"
    (nav-to-sensitive-oauth!)
    (let [slice  (route-slice)
          elided (rf/elide-wire-value slice {:frame :rf/default})]
      (is (= "secret123" (get-in elided [:query :token]))
          "no :query-v ⇒ no route re-seed ⇒ the bare slice walks at the root and rides raw"))))

(deftest read-sub-elide-wire-value-redacts-query-and-params-subs
  (testing "rf2-mtzv5m: :rf.route/query / :rf.route/params re-seed via :query-v"
    (rf/reg-route :route/oauth
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/oauth")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123"])
    (let [query-map (get-in (route-slice) [:query])
          elided    (rf/elide-wire-value query-map {:query-v [:rf.route/query] :frame :rf/default})]
      (is (= rf.privacy/redacted-sentinel (:token elided))
          ":rf.route/query value redacts via the :query-v re-seed"))
    (rf/reg-route :route/upload {:sensitive [[:params :secret]]} "/upload/:secret")
    (rf/dispatch-sync [:rf.route/transitioned "/upload/topsecret"])
    (let [params-map (get-in (route-slice) [:params])
          elided     (rf/elide-wire-value params-map {:query-v [:rf.route/params] :frame :rf/default})]
      (is (= rf.privacy/redacted-sentinel (:secret elided))
          ":rf.route/params value redacts via the :query-v re-seed"))))

;; ---- the IN-PROCESS read stays RAW (the NARROW invariant) ------------------

(deftest in-process-route-read-stays-raw
  (testing "rf2-mtzv5m: classification is read ONLY at egress — the in-process
            durable slice (what @(rf/subscribe [:rf/route]) returns) keeps the
            RAW :token / :payload so the handler / views / app subs see real
            values"
    (nav-to-sensitive-oauth!)
    (let [slice (route-slice)]
      (is (= "secret123" (get-in slice [:query :token]))
          "the in-process :token is RAW (subs / views need it)")
      (is (= "blobdata" (get-in slice [:query :payload]))
          "the in-process :large :payload is RAW in-process"))
    (testing "and the route-sub egress projector itself is a no-op on a non-route sub"
      (is (= {:query {:token "x"}}
             (rf.routing.sub-egress/project-route-sub-egress :some-app/sub {:query {:token "x"}}
                                                  {:frame :rf/default}))
          "NARROW: a non-route sub value rides verbatim (no generic propagation)"))))

;; ---- the snapshot :sub-cache per-entry re-seed shape -----------------------
;;
;; The Pair MCP snapshot :sub-cache slice is `{query-v {:value v …}}`; the fix
;; walks it PER ENTRY threading each entry's query-v. This pins the per-entry
;; semantics directly against the projector (the MCP eval-form-string shape is
;; gated by the JS-side egress-elision tests).

(deftest snapshot-sub-cache-per-entry-reseed-redacts-route-entry
  (testing "rf2-mtzv5m: a :sub-cache entry keyed by [:rf/route] redacts its
            :value's :sensitive query when walked per-entry with its query-v,
            while a non-route entry rides raw"
    (nav-to-sensitive-oauth!)
    (let [sub-cache {[:rf/route]      {:value (route-slice) :ref-count 1}
                     [:some-app/data] {:value {:query {:token "not-a-route"}} :ref-count 1}}
          ;; Mirror the snapshot tool's per-entry walk: for each entry, run
          ;; :value through elide-wire-value with :query-v = the entry's key.
          walked    (reduce-kv
                      (fn [m qv entry]
                        (assoc m qv
                               (update entry :value
                                       #(rf/elide-wire-value % {:query-v qv :frame :rf/default}))))
                      {} sub-cache)]
      (is (= rf.privacy/redacted-sentinel
             (get-in walked [[:rf/route] :value :query :token]))
          "the [:rf/route] sub-cache entry's :sensitive query redacts per-entry")
      (is (= "not-a-route"
             (get-in walked [[:some-app/data] :value :query :token]))
          "a non-route sub-cache entry rides raw (NARROW — only route subs re-seed)"))))
