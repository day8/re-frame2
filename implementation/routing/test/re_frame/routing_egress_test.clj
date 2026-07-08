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
            [re-frame.classification :as classification]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.privacy :as privacy]
            [re-frame.routing :as routing]
            [re-frame.routing.egress :as egress]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.scroll :as scroll]
            [re-frame.routing.sub-egress :as sub-egress]
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
;; rf2-vh4lbf — ADVERSARIAL-INPUT battery for redact-url-carriers (review F5).
;;
;; The core cases above cover the happy path; these pin the wrong-SHAPED edge
;; inputs. Every one is redaction-SAFE today (no secret leaks), but each
;; produces a cosmetically odd output a future refactor could turn LEAKY — so
;; they are refactor-fragility guards. The two load-bearing ones (a
;; parsing-order regression COULD expose a value):
;;   - trailing `&`: the empty trailing pair must not resurrect a raw value;
;;   - fragment-before-query ordering (`#a=1?b=2`): the `?` lives INSIDE the
;;     fragment, so the whole fragment must redact wholesale — the query-split
;;     must NOT reach across the `#` boundary and treat `b=2` as a live query.
;; ===========================================================================

(deftest redact-url-carriers-empty-query-rides-bare-question-mark
  (testing "rf2-vh4lbf: `/x?` (empty query) keeps the bare `?` — no pair to
            redact, nothing leaks (shape is cosmetic, not a carrier)"
    (is (= "/x?" (egress/redact-url-carriers "/x?"))
        "an empty query string rides as a bare `?` (no `key=value` to scrub)")))

(deftest redact-url-carriers-trailing-ampersand-drops-empty-pair
  (testing "rf2-vh4lbf: `/x?a=1&` (trailing &) redacts the real pair and drops
            the empty trailing pair — no raw value survives the split/rejoin"
    (let [out (egress/redact-url-carriers "/x?a=1&")]
      (is (= (str "/x?a=" sentinel-str) out)
          "the real value redacts; the empty trailing pair is dropped (no `&` tail)")
      (is (not (re-find #"=1" out))
          "GUARD: the raw value `1` never survives the trailing-& split")
      ;; Two trailing ampersands collapse the same way — still no raw value.
      (is (not (re-find #"=1" (egress/redact-url-carriers "/x?a=1&&")))
          "GUARD: doubled trailing `&` still drops the raw value"))))

(deftest redact-url-carriers-empty-fragment-synthesizes-sentinel
  (testing "rf2-vh4lbf: `/x#` (empty fragment) synthesizes `/x#rf/redacted` —
            cosmetic noise on an empty fragment, but never a leak"
    (is (= (str "/x#" sentinel-str) (egress/redact-url-carriers "/x#"))
        "an empty fragment still redacts to the sentinel (the whole fragment is opaque)")))

(deftest redact-url-carriers-question-mark-inside-fragment-redacts-whole
  (testing "rf2-vh4lbf: `/p#a=1?b=2` — the `?` lives INSIDE the fragment, so the
            WHOLE fragment redacts and the query-split never crosses the `#`"
    (let [out (egress/redact-url-carriers "/p#a=1?b=2")]
      (is (= (str "/p#" sentinel-str) out)
          "the fragment (incl. its embedded `?b=2`) redacts wholesale; no live query")
      ;; The crucial ordering guard: a parsing-order regression that split on
      ;; `?` BEFORE `#` would treat `b=2` as a live query and could expose a
      ;; fragment value as a raw query value.
      (is (not (re-find #"=2" out))
          "GUARD: the fragment-internal `?b=2` value never escapes as a raw query")
      (is (not (re-find #"a=1" out))
          "GUARD: the fragment-internal `a=1` never escapes raw")))
  (testing "rf2-vh4lbf: a REAL query BEFORE a `?`-bearing fragment scrubs both
            sides correctly (the `#` split precedes the `?` split)"
    (let [out (egress/redact-url-carriers "/p?q=secret#frag?x=y")]
      (is (= (str "/p?q=" sentinel-str "#" sentinel-str) out)
          "the real query value redacts; the whole fragment (with its `?x=y`) redacts")
      (is (not (re-find #"secret" out)) "GUARD: the real query secret never rides raw")
      (is (not (re-find #"x=y" out)) "GUARD: the fragment-internal query never escapes"))))

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
    (let [pending (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :pending-navigation])]
      (is (some? pending) "the block wrote the pending-nav slot")
      ;; The in-process durable value is RAW (not redacted) — resume needs it.
      (is (= "/cart?coupon=SECRET100" (:requested-url pending))
          "the durable :requested-url is the RAW URL (continue re-dispatches it)")
      (is (vector? (:requested-by-event pending))
          "the durable :requested-by-event is the raw original event vector"))
    ;; And continue actually completes the navigation (resume works).
    (rf/dispatch-sync [:rf.route/continue (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                                              (get-in [:rf.runtime/routing :pending-navigation :id]))])
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :pending-navigation]))
        "continue cleared the pending slot (resume completed from the raw value)")))

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
    (is (= [:rf.runtime/routing :current]          (sub-egress/route-sub-seed-path :rf/route)))
    (is (= [:rf.runtime/routing :current :query]   (sub-egress/route-sub-seed-path :rf.route/query)))
    (is (= [:rf.runtime/routing :current :params]  (sub-egress/route-sub-seed-path :rf.route/params))))
  (testing "a non-route sub-id resolves nil (NARROW — no generic propagation)"
    (is (nil? (sub-egress/route-sub-seed-path :some-app/sub)))
    (is (nil? (sub-egress/route-sub-seed-path :rf.route/id)))
    (is (nil? (sub-egress/route-sub-seed-path nil)))))

;; ---- the :rf.sub/run TRACE egress (the dev-trace / Xray wire) --------------

(defn- project-sub-run-trace
  "Run a synthetic `:rf.sub/run` trace event for `sub-id` carrying `value`
  through the framework trace chokepoint `re-frame.classification/project-trace-event`
  — the SINGLE site `re-frame.trace/build-event` consults before delivery to
  every trace listener (the dev-trace bus, Xray, the epoch assembler). Returns
  the projected `:rf.sub/value`."
  [sub-id value]
  (-> (classification/project-trace-event
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
      (is (= privacy/redacted-sentinel (get-in projected [:query :token]))
          "the :sensitive [:query :token] redacts on the :rf.sub/run trace")
      (is (elision/marker? (get-in projected [:query :payload]))
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
      (is (= privacy/redacted-sentinel (:token projected))
          ":rf.route/query value redacts the :token at egress")))
  (testing "rf2-mtzv5m: :rf.route/params (the bare :params map) redacts at egress"
    (rf/reg-route :route/upload
                  {:sensitive [[:params :secret]]}
                  "/upload/:secret")
    (rf/dispatch-sync [:rf.route/transitioned "/upload/topsecret"])
    (let [params-map (get-in (route-slice) [:params])
          projected  (project-sub-run-trace :rf.route/params params-map)]
      (is (= privacy/redacted-sentinel (:secret projected))
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
      (is (= privacy/redacted-sentinel (get-in elided [:query :token]))
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
      (is (= privacy/redacted-sentinel (:token elided))
          ":rf.route/query value redacts via the :query-v re-seed"))
    (rf/reg-route :route/upload {:sensitive [[:params :secret]]} "/upload/:secret")
    (rf/dispatch-sync [:rf.route/transitioned "/upload/topsecret"])
    (let [params-map (get-in (route-slice) [:params])
          elided     (rf/elide-wire-value params-map {:query-v [:rf.route/params] :frame :rf/default})]
      (is (= privacy/redacted-sentinel (:secret elided))
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
             (sub-egress/project-route-sub-egress :some-app/sub {:query {:token "x"}}
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
      (is (= privacy/redacted-sentinel
             (get-in walked [[:rf/route] :value :query :token]))
          "the [:rf/route] sub-cache entry's :sensitive query redacts per-entry")
      (is (= "not-a-route"
             (get-in walked [[:some-app/data] :value :query :token]))
          "a non-route sub-cache entry rides raw (NARROW — only route subs re-seed)"))))
