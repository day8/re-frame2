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
    4. The PROJECTION (rf2-6jqa8 AUDIT-REOPEN). The record carries no URL,
       no URL COMPONENT, and never the app's own allowlist. The first shipped
       shape put a carrier-scrubbed `:location` on the record; that scrub is
       the right blanket policy over the app's OWN URL space, but it does
       string surgery only after the first `?` or `#`, so on an arbitrary
       FOREIGN URL the userinfo (`alice:pw@`), the whole path (a reset token)
       and any value-less query key rode out verbatim — and `:allowlist`
       shipped the app's security configuration beside them. One test per
       leak, each with its own sentinel.
    5. The VALUES, not merely the keys (rf2-6jqa8 second AUDIT-REOPEN). The
       first tightening closed the key SET but kept the parsed `:scheme` and
       `:host` as strings, on the premise that a parsed component is
       structural. It is not: a scheme is arbitrary text (RFC 3986 §3.1) and
       a rejected host is by construction one the app did NOT authorise, so
       each could carry a sentinel outright and each could be varied per
       request to write unbounded distinct values into a metrics dimension.
       `:scheme` is now the classified `:scheme-class`, `:host` is gone, and
       §(5) below pins BOTH properties — content and cardinality — with
       sentinel-bearing and oversized input in each position.
    6. The wire is UNTOUCHED. Promotion changes what shippers see, never
       what the wire does — the rule this artefact's `error_listener.cljc`
       states at the head of `non-projection-eligible-errors`. A rejected
       redirect must not become a 500, or `?next=javascript:alert(1)` would
       be a denial of service.

  Companion suites:
    - `re-frame.ssr-end-to-end-test` — the dev-posture safe-redirect
      cluster (the trace-bus half; held off this lane under rf2-dtpfv).
    - `re-frame.ssr-route-miss-404-production-test` (rf2-ov56u) — the
      always-on witness this one is modelled on."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.privacy.url :as url-egress]
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
        (is (= :javascript (:scheme-class r))
            "the probe CLASS is named, so a dashboard can rank it — a
             framework keyword drawn from the gate's own vocabulary, not the
             caller's scheme string")
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

            The per-arm sets differ only in which discriminator the arm was
            able to classify. No arm carries `:location`, none carries
            `:allowlist`, and — since the second AUDIT-REOPEN — none carries a
            raw URL component under any name."
    (let [scheme (first (safe-redirect-records
                          (:records (reject! {:location "javascript:alert(1)"}))))
          host   (first (safe-redirect-records
                          (:records (reject! {:location "https://evil.example.com/x"
                                              :allow    ["app.example.com"]}))))
          parse  (first (safe-redirect-records
                          (:records (reject! {:location ""}))))]
      (is (= #{:error :time :recovery :frame :scheme-class}
             (set (keys scheme)))
          "scheme rejection — the probe class is the discriminator; no raw URL
           and no raw scheme")
      (is (= #{:error :time :recovery :frame :reason}
             (set (keys host)))
          "host rejection — which policy arm fired, and nothing else. The
           target host is NOT here: on this arm it is by construction a name
           the app did not authorise, the redirect is already refused, and it
           was the record's last caller-authored string")
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
;; `:location` with `url-egress/redact-url-carriers ` and put the scrubbed string on
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
      (is (= :not-in-allowlist (:reason r))
          "and the record is still worth having: the arm that refused is
           named, which is what an operator counts"))))

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
      (is (= :javascript (:scheme-class js))
          "and the probe class, aggregatable across a spike")
      (is (= :rf.error/safe-redirect-host-disallowed (:error host))
          "the host arm names its own category")
      (is (= :not-in-allowlist (:reason host))
          "and which of its two modes fired — the discrimination an operator
           acts on, which survives dropping the host itself")
      (is (every? some? (map :frame [js host]))
          "frame-attributed, so a multi-tenant host knows WHICH app was probed"))))

(deftest the-probe-class-is-normalised-for-aggregation
  (testing "rf2-6jqa8: `:scheme-class` exists to be COUNTED. Schemes are
            case-insensitive (RFC 3986 §3.1), so a prober alternating case
            would otherwise fragment one spike across many dashboard buckets —
            an evasion that costs the attacker nothing. The classifier folds
            case before the lookup, so every spelling of one scheme lands in
            one bucket."
    (doseq [[loc expected] [["javascript:alert(1)"       :javascript]
                            ["JavaScript:alert(1)"       :javascript]
                            ["JAVASCRIPT:alert(1)"       :javascript]
                            ["data:text/html,x"          :data]
                            ["VBScript:x"                :vbscript]
                            ["MAILTO:evil@example.com"   :other]
                            ["https:evil.example.com"    :https]]]
      (let [r (first (safe-redirect-records (:records (reject! {:location loc}))))]
        (is (= expected (:scheme-class r)) (str loc " — probe class"))))))

(deftest the-scrub-is-total-over-the-shapes-a-location-can-take
  (testing "rf2-6jqa8: `url-egress/redact-url-carriers ` no longer stands between the
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
    (is (nil? (url-egress/redact-url-carriers nil)))
    (is (= 42 (url-egress/redact-url-carriers 42)))
    (is (= "" (url-egress/redact-url-carriers "")))
    (is (= "/plain/path" (url-egress/redact-url-carriers "/plain/path"))
        "a bare path is not a carrier this policy targets")
    (is (= "javascript:alert(1)" (url-egress/redact-url-carriers "javascript:alert(1)"))
        "the attack string itself survives intact when it carries no
         query / fragment — the common case, and the one a responder needs")
    (is (= "/x?a=rf/redacted&flag#rf/redacted"
           (url-egress/redact-url-carriers "/x?a=1&flag#frag"))
        "values redacted, keys kept, value-less flag key kept, fragment whole")))

;; ===========================================================================
;; (5) THE VALUES — bounded and framework-owned, not merely closed keys
;; ===========================================================================
;;
;; The SECOND AUDIT-REOPEN.  §(3) pinned the record's KEYS closed and §(4)
;; proved no URL, path or allowlist rides under them — and both were satisfied
;; by a record still carrying the parsed `:scheme` and `:host` as strings.  A
;; closed key set says nothing about what its values contain, and on this path
;; both were caller-authored:
;;
;;   - a scheme is `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` (RFC 3986
;;     §3.1), so `s3cr3t-probe-token:payload` reached the non-http(s) arm with
;;     the sentinel as its "parsed component",
;;   - a rejected host is by definition one the app did NOT authorise, so
;;     `https://s3cr3t-reset-token.evil.example/` shipped that name whole.
;;
;; Two distinct costs.  CONTENT: a sentinel egresses under a key every reader
;; treats as structural.  CARDINALITY: a probe run with a fresh host per
;; request writes unbounded distinct values into a metrics dimension — the
;; records meant to reveal a DoS becoming one.  The tests below pin both, in
;; BOTH positions, with sentinels that are present in the input by
;; construction and with input sized far past anything a real URL carries.
;;
;; §(4)'s tests scan every string in the record; these also assert the record
;; is bounded and drawn from a closed vocabulary, which is the property a
;; scan alone cannot express.

(def ^:private hostile-probes
  "One entry per rejection arm that ever carried a caller-authored
  discriminator, each with a sentinel planted in the position that arm reads.
  `:allow` is supplied where the arm needs it to fire."
  [{:label "sentinel in the SCHEME position (non-http(s) arm)"
    :args  {:location "s3cr3t-probe-scheme:payload"}
    :needle "s3cr3t-probe-scheme"}
   {:label "sentinel in the HOST position (allowlist arm)"
    :args  {:location "https://s3cr3t-reset-token.evil.example/x"
            :allow    ["app.example.com"]}
    :needle "s3cr3t-reset-token"}
   {:label "sentinel in the HOST position (relative-only arm)"
    :args  {:location       "https://s3cr3t-session-id.evil.example/x"
            :relative-only? true}
    :needle "s3cr3t-session-id"}
   {:label "sentinel in the HOST position (scheme-without-host arm)"
    :args  {:location "https:s3cr3t-opaque-token.evil.example"}
    :needle "s3cr3t-opaque-token"}
   {:label "sentinel in a SUBDOMAIN label, which survives any suffix trim"
    :args  {:location "https://a.b.c.s3cr3t-deep-label.evil.example/"
            :allow    ["app.example.com"]}
    :needle "s3cr3t-deep-label"}])

(deftest no-sentinel-reaches-the-record-from-any-discriminator-position
  (testing "rf2-6jqa8 second AUDIT-REOPEN: `:scheme` and `:host` were the last
            caller-authored strings on the record. A scheme is arbitrary text
            and a rejected host is a name the app never authorised, so each
            was a channel for exactly the material §(4) closed elsewhere —
            just under a key that reads as structural."
    (doseq [{:keys [label args needle]} hostile-probes]
      (let [{:keys [records response]} (reject! args)
            hits                       (safe-redirect-records records)
            r                          (first hits)]
        (is (= 1 (count hits)) (str label " — non-vacuity: a record did ship"))
        (is (not (ships? r needle))
            (str label " — the sentinel does not egress"))
        (is (nil? (:redirect response))
            (str label " — and the refusal still stands"))))))

(deftest the-record-carries-no-caller-authored-value-at-all
  (testing "rf2-6jqa8 second AUDIT-REOPEN: the stronger claim, and the one
            worth pinning because it cannot be evaded by a sentinel the test
            forgot to plant. Every value on the record is a framework keyword
            or the frame's own id — there is NO slot into which a caller's
            bytes could be written, so the leak class is closed by shape
            rather than by enumeration.

            `:time` is the host clock and `:frame` the frame id; both are the
            runtime's. Everything else must be a keyword."
    (doseq [{:keys [label args]} hostile-probes]
      (let [r (first (safe-redirect-records (:records (reject! args))))]
        (is (empty? (record-strings r))
            (str label " — no STRING value anywhere on the record; strings are
                 how caller bytes travel, and the record has none"))
        (is (every? keyword? (vals (dissoc r :time)))
            (str label " — every value is a keyword (the frame id included)"))))))

(deftest an-oversized-discriminator-does-not-inflate-the-record
  (testing "rf2-6jqa8 second AUDIT-REOPEN: size is the other half of the
            leak. A caller who cannot get a sentinel out can still make the
            record enormous — an off-box shipper is billed per byte and a
            sink can be filled — so the record's size must be a property of
            the FRAMEWORK's vocabulary rather than of the input's length. A
            scheme longer than the longest scheme the gate knows is
            classified without even being lower-cased."
    (let [huge-scheme (str "s" (apply str (repeat 20000 "x")))
          huge-label  (apply str (repeat 20000 "h"))]
      (doseq [[label args] [["10k-character scheme"
                             {:location (str huge-scheme ":payload")}]
                            ["10k-character host label"
                             {:location (str "https://" huge-label ".evil.example/")
                              :allow    ["app.example.com"]}]
                            ["10k-character path on a rejected host"
                             {:location (str "https://evil.example.com/"
                                             (apply str (repeat 20000 "p")))
                              :allow    ["app.example.com"]}]]]
        (let [r (first (safe-redirect-records (:records (reject! args))))]
          (is (some? r) (str label " — non-vacuity: a record shipped"))
          (is (> 400 (count (pr-str r)))
              (str label " — the serialised record stays small; its size is
                   the framework vocabulary's, not the input's")))))))

(deftest the-record-vocabulary-is-closed-so-cardinality-cannot-be-driven
  (testing "rf2-6jqa8 second AUDIT-REOPEN: the CARDINALITY half. A raw host
            on the record let one probe run write a fresh value per request
            into a metrics dimension — unbounded series, which is how the
            records meant to reveal an attack become one. Distinct hostile
            inputs must collapse onto a bounded set of records, or the
            aggregation the promotion exists to enable is the attacker's to
            fragment.

            Asserted over the record MINUS `:frame` / `:time`, which vary per
            rejection by design (attribution and clock) and are the runtime's
            own, not the caller's."
    (let [probes  (for [i (range 100)]
                    {:location (str "https://probe-" i ".evil.example/p" i "?q=" i)
                     :allow    ["app.example.com"]})
          shapes  (into #{}
                        (map (fn [args]
                               (-> (first (safe-redirect-records
                                            (:records (reject! args))))
                                   (dissoc :frame :time))))
                        probes)]
      (is (= #{{:error    :rf.error/safe-redirect-host-disallowed
                :reason   :not-in-allowlist
                :recovery :no-recovery}}
             shapes)
          "100 distinct hostile targets produce ONE record shape — the
           dimension a dashboard groups by is the framework's, so a prober
           cannot inflate it")))

  (testing "rf2-6jqa8: and the probe class itself is drawn from a closed
            vocabulary, so the one slot that DOES vary with the input varies
            over a set the framework owns."
    (let [classes (into #{}
                        (map (fn [scheme]
                               (egress/safe-redirect-scheme-class scheme)))
                        ["javascript" "DATA" "vbscript" "http" "https"
                         "mailto" "ftp" "file" "tel" "s3cr3t-probe-token"
                         (apply str (repeat 5000 "z"))])]
      (is (= #{:javascript :data :vbscript :http :https :other} classes)
          "every scheme the gate can meet lands in the six-member vocabulary")
      (is (nil? (egress/safe-redirect-scheme-class nil))
          "no scheme to classify yields nil, so the slot is omitted rather
           than carried as nil")
      (is (= :other (egress/safe-redirect-scheme-class
                      (apply str (repeat 50000 "q"))))
          "an oversized scheme is classified without being copied"))))

(deftest the-class-vocabulary-tracks-the-gates-own-scheme-sets
  (testing "rf2-6jqa8 second AUDIT-REOPEN: `egress/scheme-classes` must name
            exactly the schemes `safe-redirect-fx`'s gate names. A scheme
            added to the gate but not to the class map would silently degrade
            to `:other` — the record would stop distinguishing a category the
            gate went to the trouble of drawing, and nothing would fail. This
            is that failure, made loud."
    (let [gate-schemes (into (deref #'re-frame.ssr.response/rejected-schemes)
                             (deref #'re-frame.ssr.response/allowed-schemes))]
      (is (= gate-schemes (set (keys egress/scheme-classes)))
          "the class map's domain IS the gate's closed vocabulary")
      (is (= (into #{} (map keyword) gate-schemes)
             (set (vals egress/scheme-classes)))
          "and each maps to the keyword of its own name — no re-spelling, so
           a reader of the sink and a reader of the gate use one word")
      (is (not (contains? (set (vals egress/scheme-classes)) :other))
          ":other is the fallback for everything OUTSIDE the vocabulary; a
           scheme mapping TO it would make the fallback ambiguous"))))

;; ===========================================================================
;; (6) THE WIRE IS UNTOUCHED — promotion changes shippers, never the wire
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

;; ===========================================================================
;; (7) THE SCRUB REACHES A ROUTING-FREE SSR HOST (rf2-6l2nc)
;; ===========================================================================
;;
;; The `:location` scrub used to be a byte-identical COPY of routing's, kept
;; here on purpose: SSR depends on core alone, so the only ways to share
;; routing's copy were a production `:require` on routing (the whole route
;; grammar on the classpath of every SSR app that registers no routes) or a
;; late-bind hook to it — and a late-bind FAILS OPEN in exactly the common
;; case, a routing-free SSR host. No routing artefact, no scrub, secrets on
;; the wire. A fail-open scrub on a fail-closed egress boundary is the wrong
;; failure mode by construction, which is why the duplication was correct at
;; the time.
;;
;; rf2-6l2nc removed the duplication the only way that keeps the property:
;; the policy moved into CORE (`re-frame.privacy.url`), the one artefact BOTH
;; callers already depend on. This section is the regression net for that
;; property, because the property is invisible in an ordinary test run — the
;; `:test` alias puts routing on the classpath, so a reintroduced routing
;; dependency would go GREEN here and only fail on a real routing-free
;; deployment. So it is asserted STRUCTURALLY, off the artefact's own
;; production dependency declaration and source tree, which is where the
;; regression would actually live.

(defn- ssr-artefact-file
  "Resolve a path inside `implementation/ssr/` from the JVM test CWD (this
  artefact's own directory), falling back to a run from the repo root."
  [rel]
  (first (filter #(.exists ^java.io.File %)
                 [(io/file rel) (io/file "implementation/ssr" rel)])))

(defn- ssr-production-sources []
  (->> (file-seq (ssr-artefact-file "src"))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))))

(def ^:private routing-load-form
  "A routing namespace reached in a LOAD position — an `(:require [re-frame…])`
  vector, a bare `(:require re-frame.routing…)`, or a quoted symbol handed to
  `require` / `resolve` at runtime. Deliberately not a bare-name grep: two
  comments in `payload_policy.cljc` discuss routing's classification registry
  in prose, and prose is not a classpath dependency."
  #"(\[|'|\(:require\s+)re-frame\.routing")

(deftest the-location-scrub-is-on-ssrs-production-classpath-without-routing
  (testing "rf2-6l2nc: SSR's production `:deps` name core and nothing else, so
            the artefact a deployed SSR host loads is core + ssr. Routing
            appears only under the `:test` / `:prod-gate` aliases."
    (let [deps (-> (ssr-artefact-file "deps.edn") slurp edn/read-string :deps keys set)]
      (is (contains? deps 'day8/re-frame2)
          "non-vacuity: the production deps map really was read")
      (is (= #{'day8/re-frame2} deps)
          "core ALONE. A routing coordinate here would make every SSR app pay
           for the route grammar, which is the cost the duplication was
           avoiding — it is not the way to remove the duplication.")))

  (testing "rf2-6l2nc: no production source file under implementation/ssr/src
            LOADS a routing namespace — not in an `(ns … :require)`, not via a
            runtime `require` / `resolve`. There is therefore nothing for a
            routing-free host to fail to resolve."
    (let [sources (ssr-production-sources)
          guilty  (filter #(re-find routing-load-form (slurp %)) sources)]
      (is (< 10 (count sources))
          "non-vacuity: the source scan really reached the ssr src tree")
      (is (empty? (map #(.getPath ^java.io.File %) guilty))
          "a routing reference in ssr production source is the fail-open shape
           this consolidation exists to prevent")))

  (testing "rf2-6l2nc: and the scrub itself is a plain core fn — resolvable,
            with no hook to be unbound and no artefact to be absent. This is
            the assertion the late-bind design could not have made: under it,
            a routing-free host would take the unbound branch and ship the raw
            URL."
    (is (= "/cb?code=rf/redacted#rf/redacted"
           (url-egress/redact-url-carriers "/cb?code=secret#access_token=leak"))
        "the scrub runs here, on ssr's classpath, with nothing routing-shaped
         loaded on its behalf")
    (is (= "/cb?code=rf/redacted#rf/redacted"
           (:location (url-egress/redact-url-tag
                        {:location "/cb?code=secret#access_token=leak"}
                        :location)))
        "and the `:location` slot the rejection arms build reaches it")))

(deftest exactly-one-url-carrier-policy-survives-the-consolidation
  (testing "rf2-6l2nc: `re-frame.ssr.egress` no longer defines a scrub of its
            own. Two copies of one policy is how the two drift, and the drift
            would be silent — each artefact's own suite would stay green while
            the same URL scrubbed two different ways."
    (is (nil? (resolve 're-frame.ssr.egress/redact-url-carriers))
        "the SSR copy is gone, not shadowed")
    (is (nil? (resolve 're-frame.ssr.egress/redact-url-tag))
        "and so is its tag wrapper")
    (is (some? (resolve 're-frame.privacy.url/redact-url-carriers))
        "core owns the one implementation"))

  (testing "rf2-6l2nc: what `re-frame.ssr.egress` still owns is the ALLOW-list
            for the always-on record — a different instrument for a different
            job, and one the carrier scrub must never be mistaken for. It is
            built FROM its slot set rather than filtered down to it, so an
            unrecognised tag has no path into the record even in principle."
    (is (= #{:frame :recovery :reason :scheme-class}
           egress/safe-redirect-record-slots)
        "the closed set, unchanged by the consolidation")
    (is (= {:scheme-class :https}
           (egress/safe-redirect-record-tags
             {:scheme    "https"
              :host      "evil.example.com"
              :location  "https://alice:pw@evil.example.com/reset/tok-abc"
              :allowlist ["app.example.com"]}))
        "a scrubbed-or-not `:location`, the rejected `:host` and the app's own
         allowlist are BUILT OUT of the record, not filtered out of it — the
         userinfo password and the path-borne token the carrier scrub
         deliberately keeps never had a route here, and the parsed `:scheme`
         arrives only as the class it belongs to")))
