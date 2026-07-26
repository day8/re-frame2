(ns re-frame.routing-prefetch-test
  "EP-0037 R3 — resource-only intent prefetch: the PURE routing surfaces plus
  the event's own two pre-planning gates.

  The always-on prefetch-address structural gate (`address/prefetch-address-
  error`) and the pure `[:rf.route/prefetch …]` payload synthesis
  (`link/prefetch-payload`) the `rf/route-link` intent handlers + the substrate-
  neutral `:routing/prefetch-payload` seam are built on. The event's warm-plan
  behaviour (isolation, dedupe, reuse, planning failure) is proven end-to-end by
  the `ep-0037-r3-prefetch-*` conformance fixtures; the DOM intent arm (hover /
  focus / touch → dispatch) is proven by the CLJS route-link tests. Per Spec 012
  §Route-plan prefetch.

  The DESTINATION gate at the foot drives `:rf.route/prefetch` end-to-end
  against a stubbed `:routing/on-route-prefetch` warm hook, so a destination that
  does not resolve is proven to reject BEFORE the hook is consulted — with no
  ensures and no success summary trace.

  ## Posture split (rf2-o5dbf)

  The two GATES are production-real and carry no posture guard. The structural
  gate `address/prefetch-address-error` and the pure payload synthesis
  `link/prefetch-payload` are plain always-on functions, so the whole top half
  of this namespace already ran under the gate. So does the fact each
  destination case is really about: `@calls` is the stubbed
  `:routing/on-route-prefetch` warm hook — a late-bound fn, not a trace — so
  whether prefetch REACHED planning, and with which resolved identity, is
  readable in production. Those run in the ordinary `clojure -M:test` suite
  AND in `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false`
  lane).

  What is dev-only is the REPORTING: the `:rf.route/prefetched` summary trace
  and the `:rf.error/prefetch-bad-address` rejection both reach the caller
  through `trace/emit!` / `trace/emit-error!`, gated on
  `interop/debug-enabled?` and read once at load time. Those assertions are
  kept VERBATIM inside `(when interop/debug-enabled? …)` arms marked
  `rf2-o5dbf`.

  Pay attention to the CARRIER-ABSENCE trio at the foot —
  `(not (contains? tags :value))`, `(not (contains? tags :error))`,
  `(not (re-find #\"SECRET-100\" (pr-str tags)))`. With no trace, `tags` is nil
  and all three pass VACUOUSLY: they would certify a privacy guarantee about a
  rejection payload that was never built. They are inside the arm, and outside
  it the same ground is covered by two posture-independent facts — the
  offending value never reached the warm plan, and `route-url`'s own ex-data
  demonstrably DOES reproduce the raw params, so the hazard the emit-site
  projection exists for is proven real in both postures rather than assumed.
  Nothing was deleted or weakened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing :as routing]
            [re-frame.routing.address :as address]
            [re-frame.routing.link :as link]
            [re-frame.routing-test-support :as rts]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rts/reset-runtime)

(deftest prefetch-address-error-accepts-only-a-closed-route-address
  (testing "a well-formed named :rf/route-address passes the gate"
    (is (nil? (address/prefetch-address-error {:to :route/article})))
    (is (nil? (address/prefetch-address-error {:to :route/article :params {:slug "x"}})))
    (is (nil? (address/prefetch-address-error {:to :route/article :params {:slug "x"}
                                               :query {:tab "comments"} :fragment "reply"})))
    (is (nil? (address/prefetch-address-error {:to :route/article :fragment nil}))))

  (testing "a raw :url escape is NOT a RouteAddress — rejected as an unknown key"
    (is (= {:reason :unknown-keys :keys [:url]}
           (address/prefetch-address-error {:url "/articles/x"}))))

  (testing "a policy / edit key is rejected as unknown (prefetch takes address only)"
    (is (= {:reason :unknown-keys :keys [:replace?]}
           (address/prefetch-address-error {:to :route/article :replace? true})))
    (is (= {:reason :unknown-keys :keys [:query-merge]}
           (address/prefetch-address-error {:to :route/article :query-merge {:tab "x"}}))))

  (testing "a missing / non-keyword :to is rejected before planning"
    (is (= {:reason :missing-to :keys [:to]}
           (address/prefetch-address-error {:params {:slug "x"}})))
    (is (= {:reason :missing-to :keys [:to]}
           (address/prefetch-address-error {:to "route/article"}))))

  (testing "a structurally-wrong address value (non-map :params) is a bad address"
    (is (= {:reason :bad-address :keys []}
           (address/prefetch-address-error {:to :route/article :params [:not :a :map]}))))

  (testing "a non-map request rejects (never reaches planning)"
    (is (= {:reason :request-not-a-map :keys []}
           (address/prefetch-address-error nil)))))

(deftest prefetch-payload-synthesises-the-event-only-on-intent-opt-in
  (testing "a link that opts into :prefetch :intent yields the address-only event"
    (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"}}]
           (link/prefetch-payload {:to :route/article :params {:slug "x"}
                                   :prefetch :intent :class "title"})))
    (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"} :query {:tab "c"}}]
           (link/prefetch-payload {:to :route/article :params {:slug "x"}
                                   :query {:tab "c"} :prefetch :intent}))))

  (testing "the payload carries ONLY the address — no policy / DOM / behaviour keys leak"
    (is (= [:rf.route/prefetch {:to :route/article}]
           (link/prefetch-payload {:to :route/article :prefetch :intent
                                   :class "title" :on-click identity
                                   :on-mouse-enter identity}))))

  (testing "an ABSENT :prefetch yields nil — passive by default, and the ONLY
            way to be passive"
    (is (nil? (link/prefetch-payload {:to :route/article})))))

;; ---- the :prefetch behaviour value is VALIDATED, not silently stripped -----

(defn- bad-prefetch-ex-data
  "The `ex-data` of the throw a link calculation raises for `props`, or nil if
  it did not throw."
  [calc props]
  (try (calc props) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-present-but-unsupported-prefetch-value-fails-loud
  (testing ":intent is the ONLY accepted value (Spec 012 §:prefetch :intent) —
            an unsupported mode is a caller bug, NOT a silently passive link.
            Returning nil for it made `:prefetch :render` and a plain typo
            indistinguishable from a link that never asked to prefetch."
    (doseq [v [true false nil :render :viewport :hover "intent" 1]]
      (let [data (bad-prefetch-ex-data link/prefetch-payload
                                       {:to :route/article :prefetch v})]
        (is (= :rf.error/route-link-bad-prefetch (:rf.error/id data))
            (str "prefetch value " (pr-str v) " must fail loud"))
        (is (= :prefetch (:slot data)))
        (is (= :intent (:accepted data)))
        (is (= 'rf/route-link (:where data)))))
    (testing "and the message is didactic — it names the accepted value and the
              way to be passive"
      (let [msg (try (link/prefetch-payload {:to :route/article :prefetch :render})
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (re-find #":intent" msg))
        (is (re-find #"(?i)omit" msg)))))

  (testing ":prefetch :intent and an absent key both pass validation"
    (is (nil? (link/validate-prefetch! {:to :route/article :prefetch :intent})))
    (is (nil? (link/validate-prefetch! {:to :route/article})))))

(deftest every-link-surface-validates-the-prefetch-value-on-both-hosts
  ;; `href-attrs` is private but is reached through both `rf/route-link` render
  ;; halves; `route-link-render-ssr` is the JVM half and is public, so the SSR
  ;; shell is exercised directly here. `link-model` is the one calculation the
  ;; compiled `ui/route-link` runs on both hosts.
  (testing "the rf/route-link SSR shell rejects it (it used to render the anchor
            with the bad value merely stripped)"
    (is (= :rf.error/route-link-bad-prefetch
           (:rf.error/id (bad-prefetch-ex-data
                           #(link/route-link-render-ssr %)
                           {:to :route/article :prefetch :render})))))
  (testing "the ui/route-link model rejects it on the JVM too — the arm that
            never called prefetch-payload server-side"
    (is (= :rf.error/route-link-bad-prefetch
           (:rf.error/id (bad-prefetch-ex-data
                           #(link/link-model % :rf/default)
                           {:to :route/article :prefetch true})))))
  (testing "and a valid :intent link still renders / models normally, with
            :prefetch stripped before DOM emission"
    (routing/reg-route :route/article {} "/articles/:slug")
    (let [props {:to :route/article :params {:slug "x"} :prefetch :intent :class "t"}
          [_tag attrs] (link/route-link-render-ssr props)]
      (is (= "/articles/x" (:href attrs)))
      (is (not (contains? attrs :prefetch)) ":prefetch never reaches the <a>")
      (is (= "t" (:class attrs)) "passthrough attrs survive"))
    (is (= "/articles/x" (:href (link/link-model {:to :route/article
                                                  :params {:slug "x"}
                                                  :prefetch :intent}
                                                 :rf/default))))))

;; ===========================================================================
;; The DESTINATION gate — prefetch warms the destination a NAVIGATION would
;; ===========================================================================
;;
;; MERGED-PR AUDIT #6878 (rf2-kqxe6.7): the structural gate proves the request
;; is a closed `:rf/route-address`, but it cannot know whether that address
;; RESOLVES. While it was the only gate, `[:rf.route/prefetch {:to
;; :route/does-not-exist}]` returned `{}` AFTER a success summary trace (the
;; trace said the warm-up worked; the caller got nothing), and a registered
;; `/probe/:id` with `:id` omitted reached the warm hook as `{:params {}}` — the
;; WRONG resource identity. Both are addresses `route-url` refuses. The
;; destination now resolves through that same boundary BEFORE planning.

(defn- with-warm-hook
  "Publish a stub `:routing/on-route-prefetch` that RECORDS every warm-plan call
  and reports one warmed requirement, call `(f calls)`, then unpublish it. The
  Resources artefact is not on the routing test classpath, so the hook is
  unbound by default — recording it is how we observe whether prefetch reached
  planning at all."
  [f]
  (let [calls (atom [])]
    (late-bind/set-fn! :routing/on-route-prefetch
                       (fn [plan]
                         (swap! calls conj plan)
                         {:warmed 1 :fx [[:dispatch [:warm/ensured]]]}))
    (try (f calls)
         (finally (late-bind/set-fn! :routing/on-route-prefetch nil)))))

(defn- prefetch!
  "Dispatch `[:rf.route/prefetch address]` synchronously and return
  `{:prefetched [...] :rejected [...]}` — the summary traces and the
  bad-address rejections it emitted."
  [address]
  (with-trace-recorder! [traces {:pred #(contains? #{:rf.route/prefetched
                                                     :rf.error/prefetch-bad-address}
                                                   (:operation %))
                                 :shape :by-op}]
    (rf/dispatch-sync [:rf.route/prefetch address])
    {:prefetched (:rf.route/prefetched @traces)
     :rejected   (:rf.error/prefetch-bad-address @traces)}))

(deftest prefetch-resolves-the-named-destination-before-planning
  (routing/reg-route :route/probe {} "/probe/:id")
  (with-warm-hook
    (fn [calls]
      (testing "POSITIVE CONTROL — a registered destination with its required
                params still reaches the warm plan and emits its ONE summary
                trace (the gate rejects only what route-url refuses)"
        (let [{:keys [prefetched rejected]}
              (prefetch! {:to :route/probe :params {:id "7"}})]
          ;; SEMANTIC, posture-independent (rf2-o5dbf): the warm hook is a
          ;; late-bound fn, not a trace — this is what "reached the warm plan"
          ;; means, and it is what makes the `(empty? @calls)` assertions in
          ;; the rejection blocks below non-vacuous.
          (is (= [{:route-id :route/probe :params {:id "7"}}]
                 (mapv #(select-keys % [:route-id :params]) @calls))
              "the warm hook saw the resolved destination and its params")
          ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
          (when interop/debug-enabled?
            (is (empty? rejected))
            (is (= 1 (count prefetched)))
            (is (= {:route-id :route/probe :warmed 1}
                   (select-keys (:tags (first prefetched)) [:route-id :warmed]))))))

      (testing "an UNREGISTERED destination rejects BEFORE planning — no warm
                hook call, and critically NO success summary trace (it used to
                emit one and then return {})"
        (reset! calls [])
        (let [{:keys [prefetched rejected]}
              (prefetch! {:to :route/does-not-exist})]
          ;; SEMANTIC, posture-independent (rf2-o5dbf): the REJECTION is real —
          ;; the warm plan was never consulted. The positive control above
          ;; proves this atom does fill when prefetch reaches planning, so an
          ;; empty one here is evidence rather than an artefact of the posture.
          (is (empty? @calls) "the warm plan was never consulted — no ensures")
          ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
          ;; `(empty? prefetched)` leg is NEGATIVE over the trace ring.
          (when interop/debug-enabled?
            (is (empty? prefetched) "no :rf.route/prefetched — the trace no longer lies")
            (is (= 1 (count rejected)))
            (is (= :no-recovery (:recovery (first rejected)))
                "the rejection is terminal — nothing was warmed to recover")
            (let [tags (:tags (first rejected))]
              (is (= :no-such-route (:reason tags)))
              (is (= :route/does-not-exist (:route-id tags)))
              (is (= [:to] (:keys tags)))
              (is (= :event (:where tags)))))))

      (testing "a REGISTERED destination with a required path param OMITTED
                rejects too — it used to reach the warm hook as {:params {}},
                warming the wrong resource identity"
        (reset! calls [])
        (let [{:keys [prefetched rejected]} (prefetch! {:to :route/probe})]
          ;; SEMANTIC, posture-independent (rf2-o5dbf): the pre-fix bug was that
          ;; the warm hook was reached as `{:params {}}` — the WRONG resource
          ;; identity. An empty `@calls` is precisely the absence of that.
          (is (empty? @calls) "the warm hook was never reached with {:params {}}")
          ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
          (when interop/debug-enabled?
            (is (empty? prefetched))
            (is (= 1 (count rejected)))
            (let [tags (:tags (first rejected))]
              (is (= :missing-route-param (:reason tags)))
              (is (= [:id] (:keys tags)) "the offending param KEY is named")
              (is (= :route/probe (:route-id tags))))))
        (testing "and an empty-string param — the un-round-trippable zero-length
                  segment — rejects on the same channel"
          (reset! calls [])
          (let [{:keys [prefetched rejected]}
                (prefetch! {:to :route/probe :params {:id ""}})]
            ;; SEMANTIC, posture-independent (rf2-o5dbf).
            (is (empty? @calls) "the zero-length segment never reached the warm hook")
            ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
            (when interop/debug-enabled?
              (is (empty? prefetched))
              (is (= :missing-route-param (:reason (:tags (first rejected)))))))))

      (testing "the STRUCTURAL gate still wins — a malformed request never
                reaches the registry, so its own :reason is reported"
        (reset! calls [])
        (let [{:keys [rejected]} (prefetch! {:url "/probe/7"})]
          ;; SEMANTIC, posture-independent (rf2-o5dbf): the STRUCTURAL gate
          ;; (`address/prefetch-address-error`) is always-on, so it rejects a
          ;; `:url`-bearing prefetch request in both postures — the warm hook
          ;; is never reached, and the gate names the offending key itself.
          (is (empty? @calls) "the structural gate refused before any planning")
          (is (= :unknown-keys
                 (:reason (address/prefetch-address-error {:url "/probe/7"})))
              "the always-on structural gate classifies it :unknown-keys")
          ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
          (when interop/debug-enabled?
            (is (= :unknown-keys (:reason (:tags (first rejected)))))))))))

(deftest prefetch-rejects-params-that-fail-the-routes-schema-without-leaking-them
  (let [restore (rts/with-stub-validator)]
    (try
      ;; A `:params` schema the stub validator adjudicates as a predicate.
      (routing/reg-route :route/guarded
                         {:params (fn [{:keys [id]}] (= "ok" id))} "/guarded/:id")
      (with-warm-hook
        (fn [calls]
          (testing "conforming params warm normally"
            (let [{:keys [prefetched rejected]}
                  (prefetch! {:to :route/guarded :params {:id "ok"}})]
              ;; SEMANTIC, posture-independent (rf2-o5dbf): the conforming
              ;; address really reached the warm plan. This is the live control
              ;; for the `(empty? @calls)` assertion in the rejection block.
              (is (= 1 (count @calls)))
              ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
              (when interop/debug-enabled?
                (is (empty? rejected))
                (is (= 1 (count prefetched))))))
          (testing "non-conforming params reject before planning — prefetch
                    adjudicates the address against the SAME schemas a
                    navigation does"
            (reset! calls [])
            (let [{:keys [prefetched rejected]}
                  (prefetch! {:to :route/guarded :params {:id "SECRET-100"}})]
              ;; SEMANTIC, posture-independent (rf2-o5dbf): the adjudication is
              ;; real — the non-conforming address never reached the warm plan,
              ;; so `SECRET-100` never became a warmed resource identity. The
              ;; conforming case above proves this atom does fill.
              (is (empty? @calls))
              (is (not (re-find #"SECRET-100" (pr-str @calls)))
                  "the offending param value never reached the warm plan")
              ;; …and the HAZARD the redaction exists for is real in BOTH
              ;; postures: `route-url` itself throws ex-data carrying the raw
              ;; params under :value / :error. That is what must not be copied
              ;; onto the rejection payload.
              (let [ex-data' (try (routing/route-url
                                    {:to :route/guarded :params {:id "SECRET-100"}})
                                  nil
                                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
                (is (re-find #"SECRET-100" (pr-str ex-data'))
                    "route-url's own ex-data DOES reproduce the raw params —
                     the leak the emit-site projection has to stop"))
              ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
              ;; three carrier-absence legs are NEGATIVE over the rejection's
              ;; trace tags: with no trace `tags` is nil, so each would report
              ;; a privacy guarantee about a payload that was never built.
              (when interop/debug-enabled?
                (is (empty? prefetched))
                (is (= 1 (count rejected)))
                (let [tags (:tags (first rejected))]
                  (is (= :route-url-validation (:reason tags)))
                  (is (= [:params] (:keys tags)) "the offending SLOT is named")
                  (testing "and the rejection carries NO carrier value — route-url's
                            ex-data embeds :value (the raw params) and :error (an
                            explainer that reproduces them), the class the navigate
                            door has to redact at its own emit site"
                    (is (not (contains? tags :value)))
                    (is (not (contains? tags :error)))
                    (is (not (re-find #"SECRET-100" (pr-str tags)))))))))))
      (finally (restore)))))

;; ===========================================================================
;; The warmed identity IS the activated identity
;; ===========================================================================
;;
;; MERGED-PR AUDIT #6976 (rf2-kqxe6.7): resolving the destination is not the
;; same as resolving the TARGET. The destination gate above proves prefetch
;; refuses what `route-url` refuses; it says nothing about whether the facts it
;; hands the warm plan are the facts the activation will commit — and a warm-up
;; keyed on a different `:params` / `:query` / `:fragment` than the click is
;; R3's original failure mode: two cache entries for one destination, the warm
;; one ownerless, GC-eligible and never reused.
;;
;; Two pairings are proven, because prefetch has two counterparts and they are
;; NOT the same door. A link's hover dispatches `link/prefetch-payload` and that
;; same link's CLICK dispatches `link-model`'s `[:rf.route/url-requested …]` —
;; a URL door. Programmatic `[:rf.route/navigate {:to …}]` is a named-address
;; door. The link pairing is the one R3 exists for, and it is also the honest
;; cross-door test: its two halves resolve through DIFFERENT seams, so it cannot
;; pass by both sides agreeing on one wrong value.
;;
;; Each case additionally pins the canonical expected identity as a literal, so
;; neither arm can pass merely because its two sides match.
;;
;; The former KNOWN GAP here — a path param the route PATTERN does not capture
;; (`{:id "7" :extra "x"}` on `/probe/:id`), which the programmatic door
;; committed, a link click dropped, and prefetch inherited from the
;; programmatic door — is SETTLED (rf2-0iuh3): neither committed value wins;
;; the address rejects LOUD at `route-url`, the shared emission boundary all
;; three named-address doors already run through. Prefetch therefore refuses it
;; through the destination gate above, needing no code of its own. Asserted in
;; `re-frame.routing-uncaptured-param-test`.

(defn- with-identity-hooks
  "Publish BOTH late-bound resource hooks — the prefetch WARM plan and the
  navigation ENTRY plan — each recording only the identity facts it is handed,
  and call `(f warm entry)`. Those two maps are what a cache entry is keyed on,
  so comparing them compares the thing that actually matters."
  [f]
  (let [identity-of #(select-keys % [:route-id :params :query :fragment])
        warm        (atom [])
        entry       (atom [])]
    (late-bind/set-fn! :routing/on-route-prefetch
                       (fn [plan]
                         (swap! warm conj (identity-of plan))
                         {:warmed 1 :fx []}))
    (late-bind/set-fn! :routing/on-route-entry
                       (fn [plan] (swap! entry conj (identity-of plan)) nil))
    (try (f warm entry)
         (finally (late-bind/set-fn! :routing/on-route-prefetch nil)
                  (late-bind/set-fn! :routing/on-route-entry nil)))))

(defn- register-probe-routes! []
  (routing/reg-route :route/elsewhere {} "/elsewhere")
  (routing/reg-route :route/probe {:query-defaults {:tab :overview}} "/probe/:id"))

(deftest prefetch-warms-the-identity-a-link-click-activates
  ;; The pairing R3 exists for, driven through the REAL link callers on both
  ;; sides: `link/prefetch-payload` is what the three intent handlers dispatch
  ;; on hover / focus / touch, and `link-model`'s `:payload` is what the click
  ;; handler dispatches. They resolve through different seams (named-address vs
  ;; URL), which is what makes this a genuine cross-door proof.
  (register-probe-routes!)
  (let [props    {:to    :route/probe :params {:id "7"}
                  :query {:drop nil} :prefetch :intent :class "nav-link"}
        expected {:route-id :route/probe :params {:id "7"}
                  :query    {:tab :overview} :fragment nil}]
    (with-identity-hooks
      (fn [warm entry]
        (is (= [:rf.route/prefetch {:to :route/probe :params {:id "7"}
                                    :query {:drop nil}}]
               (link/prefetch-payload props))
            "the hover payload carries the caller's nil-valued query key — the
             normalisation under test is the SEAM's, not the payload's")
        ;; HOVER.
        (rf/dispatch-sync (link/prefetch-payload props))
        ;; Park elsewhere so the click below is never a stage-3 exact no-op.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
        ;; CLICK — the same link, through the URL door.
        (rf/dispatch-sync (:payload (link/link-model props :rf/default)))
        (let [activated (filterv #(= :route/probe (:route-id %)) @entry)]
          (is (= [expected] @warm)
              "the warm plan is handed the canonical resolved identity")
          (is (= [expected] activated)
              "and the click's activation is handed the same one")
          (is (= @warm activated)
              "so hovering then clicking ONE link warms ONE cache entry — the
               warm entry is reused, not orphaned"))))))

(deftest prefetch-warms-the-identity-a-programmatic-navigation-commits
  ;; The named-address pairing, which additionally reaches the empty-fragment
  ;; case: `prefetch-payload` deliberately omits `:fragment` (a `#fragment` is
  ;; never a resource input), so the link surface cannot express it and only the
  ;; event-level pair can prove it.
  (register-probe-routes!)
  (doseq [[label address expected]
          [["a nil-valued query key — route-url ELIDES it from the URL, so a
             target that keeps it describes a place its own URL cannot spell"
            {:to :route/probe :params {:id "7"} :query {:drop nil}}
            {:route-id :route/probe :params {:id "7"}
             :query    {:tab :overview} :fragment nil}]

           ["an empty-string fragment — route-url emits no trailing #, and \"\"
             is truthy, so an un-normalised one is a slice/URL divergence"
            {:to :route/probe :params {:id "7"} :fragment ""}
            {:route-id :route/probe :params {:id "7"}
             :query    {:tab :overview} :fragment nil}]]]
    (testing label
      (with-identity-hooks
        (fn [warm entry]
          (rf/dispatch-sync [:rf.route/prefetch address])
          (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
          (rf/dispatch-sync [:rf.route/navigate address])
          (let [activated (filterv #(= :route/probe (:route-id %)) @entry)]
            (is (= [expected] @warm))
            (is (= [expected] activated))
            (is (= @warm activated))))))))
