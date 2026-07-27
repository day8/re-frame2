(ns re-frame.ssr-safe-redirect-production-test
  "rf2-6jqa8 ACCEPTANCE — an attempted open redirect reaches an off-box
  shipper under the REAL production gate, and does NOT touch the wire.

  THE GAP. `:rf.server/safe-redirect`'s five-step gate (parse → scheme →
  relative-only → allowlist → pass) REJECTS correctly under
  `-Dre-frame.debug=false`; that half was never in doubt and is asserted
  below for its own sake. What did not survive was the RECORD. Every
  rejection went out through one helper, `emit-safe-redirect-error!`, and
  that helper called `trace/emit-error!` alone — the dev bus, inside
  `interop/debug-enabled?`. So on a production JVM an attacker-supplied
  `?next=javascript:alert(1)` was rejected into total silence: no Sentry
  event, no Datadog metric, no frame-owned `:observability :errors` record,
  and no wire signal either (the fx no-ops; the response simply carries no
  redirect). A security team could not see open-redirect probing against
  their own app.

  Note the asymmetry that made it worth a bead: the CRLF / NUL gate on the
  SAME fx THROWS, so it rides `:rf.error/fx-handler-exception` and has
  always been always-on. Two halves of one security surface with opposite
  production observability.

  WHY THIS SUITE IS POSTURE-INDEPENDENT, AND WHY THAT MATTERS. Every
  assertion below is true under BOTH postures and mentions the dev trace bus
  NOWHERE, so the namespace joins `scripts/test-ssr-prod-gate.sh` by default
  (that roster is an EXCLUSION list) and executes under
  `-Dre-frame.debug=false` for real. A `with-redefs [interop/debug-enabled?
  false]` rebind CANNOT reach a load-time gate — it is not evidence for this
  bead, which is exactly why the dev-posture safe-redirect cluster in
  `ssr-end-to-end-test` stayed green while production shipped silence.

  WHAT IS PINNED:

    1. The mitigation. Every rejection arm still leaves `:redirect` nil
       under the gate — the security-load-bearing half, asserted here so a
       future change to the record cannot quietly cost the refusal.
    2. The record. EXACTLY ONE always-on record per rejection reaches an
       off-box shipper, carrying the specific `:rf.error/safe-redirect-*`
       category and the discriminating tags.
    3. The key set, pinned CLOSED. A slot added to this record reaches
       Sentry / Datadog in a production build, so it must be a deliberate
       change rather than a drift.
    4. The PROJECTION (rf2-6jqa8 AUDIT-REOPEN). The record carries parsed
       STRUCTURAL components only — never a URL, and never the app's own
       allowlist. The first shipped shape put a carrier-scrubbed `:location`
       on the record; that scrub is the right blanket policy over the app's
       OWN URL space, but it does string surgery only after the first `?` or
       `#`, so on an arbitrary FOREIGN URL the userinfo (`alice:pw@`), the
       whole path (a reset token) and any value-less query key rode out
       verbatim — and `:allowlist` shipped the app's security configuration
       beside them. One test per leak, each with its own sentinel.
    5. The wire is UNTOUCHED. Promotion changes what shippers see, never
       what the wire does — the rule this artefact's `error_listener.cljc`
       states at the head of `non-projection-eligible-errors`. A rejected
       redirect must not become a 500, or `?next=javascript:alert(1)` would
       be a denial of service.

  Companion suites:
    - `re-frame.ssr-end-to-end-test` — the dev-posture safe-redirect
      cluster (the trace-bus half; held off this lane under rf2-dtpfv).
    - `re-frame.ssr-route-miss-404-production-test` (rf2-ov56u) — the
      always-on witness this one is modelled on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.egress :as egress]
            [re-frame.ssr.test-fixture :as tf]))

;; NOTE the fixture does NOT clear the always-on error-listener registry.
;; `re-frame.ssr` installs its own `::error-projection` listener there at
;; ns-load time, and (5) below is precisely a claim about what that listener
;; does with these categories. Each test unregisters only the shipper
;; stand-in it registered.
(use-fixtures :each tf/reset-runtime)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- server-frame []
  (frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}}))

(defn- capture-always-on!
  "Register an off-box-shipper stand-in on the ALWAYS-ON error axis (the
  `:errors` stream of `register-listener!` — surface #4, not the dev trace
  bus). Returns the atom collecting every record it receives."
  [id]
  (let [seen (atom [])]
    (rf/register-listener! :errors id (fn [record] (swap! seen conj record)))
    seen))

(defn- reject!
  "Drive `:rf.server/safe-redirect` with `args` on a fresh server frame and
  return `{:frame :records :response}` — the always-on records the shipper
  stand-in saw, and the resolved response accumulator."
  [args]
  (let [f    (server-frame)
        id   (keyword "rf2-6jqa8" (str "cap-" (name (gensym "s"))))
        seen (capture-always-on! id)]
    (rf/reg-event ::attempt (fn [_ [_ a]] {:fx [[:rf.server/safe-redirect a]]}))
    (rf/dispatch-sync [::attempt args] {:frame f})
    (rf/unregister-listener! :errors id)
    {:frame    f
     :records  @seen
     :response (ssr/flush-response! f)}))

(defn- safe-redirect-records [records]
  (filter #(str/starts-with? (name (:error %)) "safe-redirect-") records))

;; ===========================================================================
;; (1) THE MITIGATION — every arm still refuses, under the gate
;; ===========================================================================

(deftest every-rejection-arm-still-refuses-under-the-production-gate
  (testing "rf2-6jqa8: the five-step gate is production-real. This is the
            security-load-bearing half, asserted here in its own right so a
            change to the RECORD can never quietly cost the REFUSAL — the
            failure mode a bead about observability is most at risk of."
    (doseq [[label args] [["javascript: scheme"
                           {:location "javascript:alert(1)"}]
                          ["data: scheme"
                           {:location "data:text/html,<script>alert(1)</script>"}]
                          ["vbscript: scheme"
                           {:location "vbscript:msgbox(1)"}]
                          ["non-http(s) scheme"
                           {:location "mailto:evil@example.com"}]
                          ["scheme-bearing opaque host bypass"
                           {:location "http:evil.example.com"}]
                          ["absolute URL under :relative-only?"
                           {:location "https://evil.example.com/" :relative-only? true}]
                          ["protocol-relative under :relative-only?"
                           {:location "//evil.example.com/" :relative-only? true}]
                          ["off-allowlist host"
                           {:location "https://evil.example.com/" :allow ["app.example.com"]}]
                          ["unparseable location"
                           {:location ""}]]]
      (let [{:keys [response]} (reject! args)]
        (is (nil? (:redirect response))
            (str label " — rejected: the response accumulator's :redirect "
                 "slot is untouched under -Dre-frame.debug=false"))))))

;; ===========================================================================
;; (2) THE RECORD — the rejection reaches an off-box shipper in production
;; ===========================================================================

(deftest a-scheme-rejection-reaches-the-always-on-axis
  (testing "rf2-6jqa8: THE BEAD. Before the promotion this record did not
            exist in a production build — the rejection went out on the dev
            trace bus alone, so an attempted `javascript:` redirect was
            silently no-op'd with nothing for a shipper to see."
    (let [{:keys [records]} (reject! {:location "javascript:alert(1)"})
          hits              (safe-redirect-records records)]
      (is (= 1 (count hits))
          "exactly one always-on record — one per rejection, not one per axis")
      (let [r (first hits)]
        (is (= :rf.error/safe-redirect-scheme-rejected (:error r)))
        (is (= "javascript" (:scheme r))
            "the rejected scheme is named, so a dashboard can rank the probe class")
        (is (= :no-recovery (:recovery r)))
        (is (some? (:frame r))
            "frame-attributed, so the record routes to the frame's
             :observability :errors sink")
        (is (number? (:time r)))))))

(deftest each-rejection-arm-names-its-own-category-and-reason
  (testing "rf2-6jqa8: the three categories and their `:reason` vocabulary
            are what a security team discriminates on. Asserted per arm, off
            the production axis, so the discrimination survives the gate
            rather than living only on the dev trace."
    (doseq [[args expected-error expected-reason]
            [[{:location "mailto:evil@example.com"}
              :rf.error/safe-redirect-scheme-rejected :scheme-not-allowed]
             [{:location "http:evil.example.com"}
              :rf.error/safe-redirect-invalid-url :scheme-without-host]
             [{:location "https://evil.example.com/" :relative-only? true}
              :rf.error/safe-redirect-host-disallowed :relative-only-violation]
             [{:location "https://evil.example.com/" :allow ["app.example.com"]}
              :rf.error/safe-redirect-host-disallowed :not-in-allowlist]
             ;; rf2-6jqa8 AUDIT-REOPEN: this arm's `:reason` used to be the
             ;; free prose "URL did not parse". A closed keyword is what makes
             ;; the slot aggregatable AND keeps the vocabulary framework-owned
             ;; on the one arm whose input did not parse.
             [{:location ""}
              :rf.error/safe-redirect-invalid-url :parse-failed]]]
      (let [hits (safe-redirect-records (:records (reject! args)))
            r    (first hits)]
        (is (= 1 (count hits)) (str args " — exactly one record"))
        (is (= expected-error (:error r)) (str args " — category"))
        (is (= expected-reason (:reason r)) (str args " — reason"))))))

(deftest a-passing-redirect-emits-no-record-at-all
  (testing "rf2-6jqa8 non-vacuity: the always-on axis is silent on the happy
            path. Without this, every assertion above would be satisfied by
            an emit site that fired unconditionally."
    (let [{:keys [records response]} (reject! {:location "/dashboard"
                                               :relative-only? true})]
      (is (empty? (safe-redirect-records records))
          "a passing relative redirect ships no rejection record")
      (is (= "/dashboard" (:location (:redirect response)))
          "and it really did pass — the :redirect slot carries the target,
           so the silence above is not the silence of a refused redirect"))))

;; ===========================================================================
;; (3) THE KEY SET — pinned CLOSED
;; ===========================================================================

(deftest the-production-record-carries-exactly-the-enumerated-slots
  (testing "rf2-6jqa8 EGRESS REVIEW: this record is what a
            `-Dre-frame.debug=false` build sends off-box, so pinning the shape
            is the job. The key set is CLOSED because it is produced by a
            `select-keys` against one allowlist — a slot added to a tag map at
            any of the eight emit arms simply does not reach Sentry / Datadog,
            and WIDENING the record is an edit to
            `egress/safe-redirect-record-slots` that reddens this test.

            The per-arm sets differ only in which STRUCTURAL discriminator the
            arm was able to parse. No arm carries `:location`, and none carries
            `:allowlist`."
    (let [scheme (first (safe-redirect-records
                          (:records (reject! {:location "javascript:alert(1)"}))))
          host   (first (safe-redirect-records
                          (:records (reject! {:location "https://evil.example.com/x"
                                              :allow    ["app.example.com"]}))))
          parse  (first (safe-redirect-records
                          (:records (reject! {:location ""}))))]
      (is (= #{:error :time :recovery :frame :scheme}
             (set (keys scheme)))
          "scheme rejection — the scheme is the discriminator; no raw URL")
      (is (= #{:error :time :recovery :frame :host :reason}
             (set (keys host)))
          "host rejection — the target host and which policy arm fired; the
           app's OWN allowlist does NOT ship")
      (is (= #{:error :time :recovery :frame :reason}
             (set (keys parse)))
          "parse failure — nothing parsed, so the category and a closed
           :reason are all there is to say")
      (is (every? keyword? (keep :reason [scheme host parse]))
          "every :reason is a closed keyword, never free prose — prose on an
           attacker-influenced arm is how raw material re-enters a record"))))

;; ===========================================================================
;; (4) THE PROJECTION — no URL secrets, no allowlist
;; ===========================================================================
;;
;; The AUDIT-REOPEN half of rf2-6jqa8.  The first shipped shape scrubbed the
;; `:location` with `egress/redact-url-carriers` and put the scrubbed string on
;; the record.  That is the right blanket policy for ordinary route diagnostics
;; over the app's OWN URL space (rf2-ov56u's route-miss `:url`), and it is NOT
;; a fail-closed projection of an ARBITRARY ATTACKER-SUPPLIED FOREIGN URL: the
;; policy does string surgery only after the first `?` or `#`, so everything to
;; the LEFT of them — userinfo, the whole path — rode out verbatim, as did a
;; value-less query key.  The record also shipped the application's own
;; `:allowlist`.
;;
;; So the production record is now a strict STRUCTURAL PROJECTION of the
;; diagnostics rather than a scrubbed copy of them: parsed components only,
;; never a URL.  Below is one test per leak, each with a sentinel that is
;; present in the input by construction, so a regression names its own slot.

(defn- record-strings
  "Every string anywhere in `record` — the values a shipper serialises. The
  leak assertions below scan THIS rather than a named slot, so a secret that
  reappears under some *other* key is caught just as well as one that
  reappears under `:location`."
  [record]
  (filter string? (tree-seq coll? seq record)))

(defn- ships? [record needle]
  (boolean (some #(str/includes? % needle) (record-strings record))))

(deftest userinfo-does-not-egress
  (testing "rf2-6jqa8 AUDIT-REOPEN leak 1: a URL's userinfo component carries
            credentials outright (RFC 3986 §3.2.1; OpenTelemetry's URL
            conventions say user / password MUST NOT be recorded). It sits
            LEFT of any `?`, so the carrier scrub never reached it and
            `https://alice:pw@host/` shipped whole."
    (let [r (first (safe-redirect-records
                     (:records (reject! {:location "https://alice:s3cr3t-userinfo-pw@evil.example.com/private"
                                         :allow    ["app.example.com"]}))))]
      (is (not (ships? r "s3cr3t-userinfo-pw"))
          "the password in the userinfo component does not egress")
      (is (not (ships? r "alice"))
          "nor the username")
      (is (= "evil.example.com" (:host r))
          "and the record is still worth having: the parsed host — the thing
           an operator ranks probes by — is named structurally, WITHOUT the
           credentials that travelled beside it"))))

(deftest path-borne-secrets-do-not-egress
  (testing "rf2-6jqa8 AUDIT-REOPEN leak 1 (second half): opaque secrets live
            in PATH segments too — a password-reset token is the canonical
            case. The path is left of the `?`, so the carrier scrub kept it
            deliberately ('keep the structured path'), which is right for the
            app's own URL space and wrong for a foreign one."
    (let [r (first (safe-redirect-records
                     (:records (reject! {:location "https://evil.example.com/reset/s3cr3t-path-token?x=1"
                                         :allow    ["app.example.com"]}))))]
      (is (not (ships? r "s3cr3t-path-token"))
          "the opaque path segment does not egress")
      (is (not (ships? r "/reset/"))
          "nor the path structure around it"))))

(deftest value-less-query-keys-do-not-egress
  (testing "rf2-6jqa8 AUDIT-REOPEN leak 1 (third half): the carrier policy
            PRESERVES a value-less query key by design — the key names the
            shape, not the secret. True over the app's own URL space; over an
            attacker's, the attacker chooses the key, so the key IS the
            payload."
    (let [r (first (safe-redirect-records
                     (:records (reject! {:location "https://evil.example.com/x?s3cr3t-bare-flag"
                                         :allow    ["app.example.com"]}))))]
      (is (not (ships? r "s3cr3t-bare-flag"))
          "an attacker-chosen value-less query key does not egress"))))

(deftest the-applications-own-allowlist-does-not-egress
  (testing "rf2-6jqa8 AUDIT-REOPEN leak 2: `:allowlist` is not user data —
            it is worse. It is the app's SECURITY CONFIGURATION, unbounded in
            size, and shipping it off-box hands whoever reads the sink the
            exact boundary they are probing. `:reason :not-in-allowlist`
            already says which arm fired; the contents add nothing an operator
            cannot read from their own config."
    (let [r (first (safe-redirect-records
                     (:records (reject! {:location "https://evil.example.com/"
                                         :allow    ["app.example.com"
                                                    "s3cr3t-internal-admin.example.com"]}))))]
      (is (not (ships? r "s3cr3t-internal-admin.example.com"))
          "no allowlist entry egresses")
      (is (not (contains? r :allowlist))
          "and the slot itself is gone, not merely emptied")
      (is (= :not-in-allowlist (:reason r))
          "the DISCRIMINATION survives — an operator still knows the
           allowlist arm rejected this, which is the actionable half"))))

(deftest the-record-still-names-the-failure-it-exists-to-report
  (testing "rf2-6jqa8: a record scrubbed to uselessness fails the ruling's own
            purpose — an opt-in security gate must be observable to the person
            who opted in. Tightening is only defensible while the STRUCTURAL
            facts survive, so pin them here rather than trusting the key-set
            test to imply it. The raw URL was never the value; the probe class
            and the target were."
    (let [js   (first (safe-redirect-records
                        (:records (reject! {:location "javascript:alert(1)"}))))
          host (first (safe-redirect-records
                        (:records (reject! {:location "https://evil.example.com/x"
                                            :allow    ["app.example.com"]}))))]
      (is (= :rf.error/safe-redirect-scheme-rejected (:error js))
          "which gate refused")
      (is (= "javascript" (:scheme js))
          "and the probe class, aggregatable across a spike")
      (is (= "evil.example.com" (:host host))
          "and the target host — an operator can block it, or recognise their
           own misconfiguration in it")
      (is (every? some? (map :frame [js host]))
          "frame-attributed, so a multi-tenant host knows WHICH app was probed"))))

(deftest structural-discriminators-are-normalised-for-aggregation
  (testing "rf2-6jqa8: `:scheme` / `:host` exist to be COUNTED. Schemes are
            case-insensitive (RFC 3986 §3.1) and DNS names likewise (RFC 1035
            §2.3.3), so a prober alternating case would otherwise fragment one
            spike across many dashboard buckets — an evasion that costs the
            attacker nothing. The projection lower-cases both."
    (let [scheme (first (safe-redirect-records
                          (:records (reject! {:location "MAILTO:evil@example.com"}))))
          host   (first (safe-redirect-records
                          (:records (reject! {:location "https://EVIL.Example.COM/x"
                                              :allow    ["app.example.com"]}))))]
      (is (= "mailto" (:scheme scheme)))
      (is (= "evil.example.com" (:host host))))))

(deftest the-scrub-is-total-over-the-shapes-a-location-can-take
  (testing "rf2-6jqa8: `redact-url-carriers` no longer stands between the
            attacker's URL and PRODUCTION — the structural projection above
            does — but it still scrubs the `:location` on the DEV diagnostics
            (and rf2-ov56u's route-miss `:url`), so its totality is still a
            claim worth pinning: it is reached for EVERY rejection arm
            including the parse-failure one, where `:location` may be nil,
            blank or a non-string. Asserted on the pure fn so the totality
            claim needs no bus at all.

            Read the two together and the EP-0015 relationship is the point:
            the dev operator sees their own process in full detail, and the
            production record is a strict projection of it."
    (is (nil? (egress/redact-url-carriers nil)))
    (is (= 42 (egress/redact-url-carriers 42)))
    (is (= "" (egress/redact-url-carriers "")))
    (is (= "/plain/path" (egress/redact-url-carriers "/plain/path"))
        "a bare path is not a carrier this policy targets")
    (is (= "javascript:alert(1)" (egress/redact-url-carriers "javascript:alert(1)"))
        "the attack string itself survives intact when it carries no
         query / fragment — the common case, and the one a responder needs")
    (is (= "/x?a=rf/redacted&flag#rf/redacted"
           (egress/redact-url-carriers "/x?a=1&flag#frag"))
        "values redacted, keys kept, value-less flag key kept, fragment whole")))

;; ===========================================================================
;; (5) THE WIRE IS UNTOUCHED — promotion changes shippers, never the wire
;; ===========================================================================

(deftest a-rejected-redirect-does-not-become-a-500
  (testing "rf2-6jqa8 ADJACENT CORRECTION: the three categories are on
            `non-projection-eligible-errors`. Without that, promoting them
            would let `error-emit-projection-listener` buffer the record and
            the default projector's `:else` arm stamp the locked generic 500
            — so `?next=javascript:alert(1)` would be a trivial denial of
            service, the inverse of this bead's intent. `safe-redirect-fx`
            is deliberately emit-and-no-op: the cascade continues and the
            page renders."
    (doseq [[label args] [["javascript:" {:location "javascript:alert(1)"}]
                          ["off-allowlist" {:location "https://evil.example.com/"
                                            :allow    ["app.example.com"]}]
                          ["unparseable" {:location ""}]]]
      (let [{:keys [response records]} (reject! args)]
        (is (= 1 (count (safe-redirect-records records)))
            (str label " — the record DID ship (non-vacuity for the status "
                 "assertion beside it)"))
        (is (= 200 (:status response))
            (str label " — the wire is untouched: a working mitigation is not "
                 "a server error"))
        (is (nil? (:redirect response))
            (str label " — and still no redirect"))))))
