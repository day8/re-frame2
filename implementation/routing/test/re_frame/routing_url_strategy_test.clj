(ns re-frame.routing-url-strategy-test
  "URL-strategy seam tests (rf2-aerrz5). Locks the pure legs of the two
  shipped strategies — `history-url-strategy` (default, path-form) and
  `hash-url-strategy` (`#`-prefixed) — the `with-base-path` combinator
  (rf2-g8pbwg, for an app deployed under a sub-path) — plus the frame-config
  resolution the four egress/ingress consult points share.

  The side-effecting `:push!` / `:replace!` / `:install-listener!` keys are
  CLJS-only (a browser `window.history` / listener); the browser round-trip
  of those + the end-to-end route-link / history-fx integration is pinned in
  `routing_url_strategy_cljs_test.cljs`. This JVM suite pins the host-agnostic
  contract: encode/decode shape, the encode/decode ROUND-TRIP identity, the
  ADVERSARIAL negative fixtures (malformed / mismatched forms), and
  `url-strategy-from-config` / `url-strategy-for-frame-id`. Per Spec 012
  §URL strategies.

  ## Posture split (rf2-o5dbf)

  The fail-loud validation seam is FRAME CONSTRUCTION (rf2-ktmto9):
  `preflight-frame-config!` runs `validate-url-strategy!` UNCONDITIONALLY at
  `re-frame.frame/upsert-frame!`, the sole `frames`-store config writer. That
  is production-real and is asserted here WITHOUT a posture guard — the
  preflight tests, the engine-rejects-before-any-write test and the three
  trusted-read store invariants all run in the ordinary `clojure -M:test`
  suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  What is NOT production-real is the CONSULT-path tripwire. rf2-ecb4sx made
  `url-strategy-from-config` a trusted read that pays no per-render
  validation; what remains there is a `(when rf.interop/debug-enabled? …)`
  re-check, DCE'd from production CLJS bundles and dead on a JVM started with
  `-Dre-frame.debug=false`. The two tripwire deftests below therefore branch
  on `rf.interop/debug-enabled?`: the dev arm keeps the fail-loud assertions
  VERBATIM, and the production arm asserts what the trusted read actually
  does when the tripwire is gone — it returns the declared value verbatim,
  throwing nothing, which is the contract the ~30x consult speed-up rides on.
  Neither arm is vacuous; nothing was deleted or weakened."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.strategy :as rf.routing.strategy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- shipped-strategy shape ----------------------------------------------

(deftest history-strategy-shape
  (testing "history-url-strategy carries the two host-agnostic keys on JVM
            (the side-effecting keys are CLJS-only — SSR runs no browser side
            effects; the pure :encode it DOES run is pinned cross-host in
            route_link_ssr_parity_cljs_test)"
    (is (fn? (:encode rf.routing.strategy/history-url-strategy)))
    (is (fn? (:decode rf.routing.strategy/history-url-strategy)))
    ;; JVM half omits the side-effecting keys (see the ns docstring).
    (is (nil? (:push! rf.routing.strategy/history-url-strategy)))
    (is (nil? (:install-listener! rf.routing.strategy/history-url-strategy)))))

(deftest hash-strategy-shape
  (testing "hash-url-strategy carries the two host-agnostic keys on JVM"
    (is (fn? (:encode rf.routing.strategy/hash-url-strategy)))
    (is (fn? (:decode rf.routing.strategy/hash-url-strategy)))
    (is (nil? (:push! rf.routing.strategy/hash-url-strategy)))))

(deftest facade-re-exports-both-strategies
  (testing "the routing façade re-exports both shipped strategies (public surface)"
    (is (identical? rf.routing.strategy/history-url-strategy rf.routing/history-url-strategy))
    (is (identical? rf.routing.strategy/hash-url-strategy rf.routing/hash-url-strategy))))

;; ---- history strategy: encode is identity (path IS the URL) --------------

(deftest history-encode-is-identity
  (testing "history encode leaves a path-form URL unchanged"
    (doseq [p ["/" "/active" "/completed" "/articles/42?q=milk" "/x#frag"]]
      (is (= p (rf.routing.strategy/history-encode p))
          (str "history-encode is identity for " (pr-str p))))))

;; ---- hash strategy: encode `#`-prefixes the path -------------------------

(deftest hash-encode-prefixes-hash
  (testing "hash encode maps a path-form URL to its `#`-prefixed href"
    (is (= "#/" (rf.routing.strategy/hash-encode "/")))
    (is (= "#/active" (rf.routing.strategy/hash-encode "/active")))
    (is (= "#/completed" (rf.routing.strategy/hash-encode "/completed")))
    (is (= "#/articles/42?q=milk" (rf.routing.strategy/hash-encode "/articles/42?q=milk"))))
  (testing "hash encode is idempotent — an already-`#`-prefixed input is unchanged"
    (is (= "#/active" (rf.routing.strategy/hash-encode "#/active"))
        "a raw hash href is not double-hashed"))
  (testing "hash encode maps nil to the root hash (defensive)"
    (is (= "#/" (rf.routing.strategy/hash-encode nil)))))

;; ---- encode/decode ROUND-TRIP (the property the fixtures pin) ------------
;;
;; `:decode` reads the live browser URL (CLJS), so the JVM cannot exercise it
;; end-to-end. But the ROUND-TRIP identity — decode(encode(p)) recovers p — is
;; verified here against a pure JVM model of `window.location.hash`: encode a
;; path, then decode the `#`-tail the way `hash-decode` would. This is the
;; host-agnostic half of the round-trip the CLJS suite drives against a real
;; (stubbed) `window`.

(defn- hash-decode-model
  "Pure JVM model of `hash-decode` over a raw `window.location.hash` string
  (the value `hash-encode` produces). Mirrors the CLJS `hash-decode` branch
  logic without touching `js/window`."
  [raw-hash]
  (if (or (nil? raw-hash) (= "" raw-hash) (= "#" raw-hash))
    "/"
    (let [stripped (subs raw-hash 1)]
      (if (clojure.string/starts-with? stripped "/")
        stripped
        (str "/" stripped)))))

(deftest hash-encode-decode-round-trip
  (testing "decode(encode(path)) recovers the original path-form URL for hash"
    (doseq [p ["/" "/active" "/completed" "/articles/42" "/a/b/c" "/x?q=1&r=2"]]
      (is (= p (hash-decode-model (rf.routing.strategy/hash-encode p)))
          (str "hash round-trip recovers " (pr-str p))))))

(deftest history-encode-decode-round-trip-is-identity
  (testing "history encode is identity, so its round-trip is trivially the input"
    (doseq [p ["/" "/active" "/articles/42?q=milk"]]
      (is (= p (rf.routing.strategy/history-encode p))))))

;; ---- ADVERSARIAL / negative fixtures -------------------------------------

(deftest hash-decode-empty-and-bare-hash-is-root
  (testing "an empty / bare `#` hash decodes to the root route `/` (adversarial:
            a browser landing with no hash, or a bare `#`)"
    (is (= "/" (hash-decode-model "")))
    (is (= "/" (hash-decode-model "#")))
    (is (= "/" (hash-decode-model nil)))))

(deftest hash-decode-missing-leading-slash-is-repaired
  (testing "a hash without a leading `/` after the `#` (`#active`, an
            adversarial / legacy secretary-style href) decodes to a rooted
            path `/active`, not `active`"
    (is (= "/active" (hash-decode-model "#active")))
    (is (= "/completed" (hash-decode-model "#completed")))))

(deftest strategy-forms-do-not-collide
  (testing "the two strategies produce DISTINCT hrefs for the same path, and
            the WRONG-strategy round-trip does NOT recover the path — proving
            the forms are genuinely different address-bar shapes that cannot be
            interchanged (the mismatch the seam exists to keep separate)"
    (let [p "/active"
          hist-href (rf.routing.strategy/history-encode p)  ;; "/active"
          hash-href (rf.routing.strategy/hash-encode p)]    ;; "#/active"
      (is (not= hist-href hash-href)
          "history and hash hrefs differ for the same path")
      ;; A HASH href fed to the HISTORY decode projection (identity over the
      ;; app-relative URL) is NOT the path — the leading `#` survives, so the
      ;; router would route-miss. The two forms are not interchangeable.
      (is (not= p (rf.routing.strategy/history-encode hash-href))
          "history projection of a hash href keeps the `#` — the forms are distinct")
      ;; And a HISTORY href (a bare path) has no `#`, so the HASH decoder's
      ;; bare-hash / empty-hash guard does NOT apply and it is NOT the empty
      ;; root — a further proof the two decoders read different shapes.
      (is (= "/x/y" (hash-decode-model "#/x/y"))
          "the hash decoder recovers a multi-segment path from a `#`-href")
      (is (not= "#/x/y" (hash-decode-model "#/x/y"))
          "the decoded path drops the `#` — decode is not identity for hash"))))

;; ---- frame-config resolution ---------------------------------------------

(deftest url-strategy-from-config-defaults-to-history
  (testing "a frame config with no :url-strategy resolves to the history default"
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config {})))
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config {:url-bound? true})))
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config nil))
        "a non-map config falls back to the history default")))

(deftest url-strategy-from-config-reads-declared-strategy
  (testing "a frame config declaring :url-strategy resolves to it"
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/url-strategy-from-config
                      {:url-bound? true :url-strategy rf.routing.strategy/hash-url-strategy})))
    ;; a SHAPE-VALID custom strategy map is honoured verbatim (the seam is
    ;; open — the two shipped strategies are the blessed pair, but a complete
    ;; custom config value passes through unchanged). On the JVM only the two
    ;; host-agnostic legs `:encode` / `:decode` are required (the browser legs
    ;; are reader-conditionally absent — see validate-url-strategy!).
    (let [custom {:encode identity :decode (constantly "/")}]
      (is (identical? custom
                      (rf.routing.strategy/url-strategy-from-config {:url-strategy custom}))))))

;; ---- consult-path dev tripwire (rf2-j538f7.11 → rf2-ecb4sx) ----------------
;;
;; A custom `:url-strategy` was once consumed VERBATIM with no shape/callability
;; check, so a typo / partial adapter / hot-reload intermediate value only
;; failed later — deep in a consult point — as a raw host nil-function /
;; TypeError. rf2-j538f7.11 added validation at the resolution seam; rf2-ktmto9
;; then moved the FAIL-LOUD validation to the registration-time PREFLIGHT (the
;; sole frame-config commit chokepoint — see the preflight section below), so a
;; malformed strategy can never enter the `frames` store the consults read.
;;
;; rf2-ecb4sx therefore made `url-strategy-from-config` a TRUSTED READ: it no
;; longer validates on the production consult path (the ~90 ns/consult
;; `route-link` used to pay per render). What REMAINS at the consult is a
;; DEV-ONLY tripwire, gated on `re-frame.interop/debug-enabled?` (`goog.DEBUG`
;; on CLJS, the `re-frame.debug` gate on the JVM) and dead-code-eliminated from
;; production CLJS bundles. It re-runs `validate-url-strategy!` so a FUTURE
;; config-write bypass still fails loud in development. These two tests run in
;; the dev-default JVM (`debug-enabled?` true), so the tripwire fires and the
;; historical fail-loud behaviour is still pinned. On the JVM the required legs
;; are `:encode` / `:decode` only (the browser legs are reader-conditionally
;; absent from the shipped strategies — Spec 012 §URL strategies).

(deftest custom-url-strategy-non-map-tripwire
  (testing "rf2-ecb4sx: with the dev tripwire active (debug-enabled? — the JVM
            test default), a truthy but NON-MAP :url-strategy still fails loud
            with :rf.error/invalid-url-strategy at the consult, backstopping the
            registration preflight for any future config-write bypass"
    (if rf.interop/debug-enabled?
      ;; rf2-o5dbf — dev-tripwire arm (see ns docstring), kept verbatim.
      (let [ex (try (rf.routing.strategy/url-strategy-from-config {:url-strategy :not-a-map})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "a non-map :url-strategy throws under the dev tripwire")
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex)))
            "the canonical structured error id is stamped")
        (is (= :not-a-map (:url-strategy (ex-data ex)))
            "the offending value is carried in the ex-data"))
      ;; rf2-o5dbf — production arm: the tripwire is DCE'd / gated off, so the
      ;; consult is a pure trusted read. It must not throw and must not
      ;; silently substitute a default — the preflight (asserted
      ;; posture-independently below) is what keeps a value of this shape out
      ;; of the store in the first place.
      (is (= :not-a-map (rf.routing.strategy/url-strategy-from-config {:url-strategy :not-a-map}))
          "under -Dre-frame.debug=false the consult returns the declared value
           verbatim and pays no validation — the trusted read rf2-ecb4sx bought"))))

(deftest custom-url-strategy-missing-leg-tripwire
  (testing "rf2-ecb4sx: the confirmed repro — a custom strategy missing a
            callable :encode (only :decode supplied) fails loud under the dev
            tripwire naming the bad leg"
    (if rf.interop/debug-enabled?
      ;; rf2-o5dbf — dev-tripwire arm (see ns docstring), kept verbatim.
      (let [ex (try (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy {:decode (constantly "/")}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "a strategy missing a required callable leg throws")
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
        (is (contains? (set (:missing (ex-data ex))) :encode)
            "the missing :encode leg is named in :missing"))
      ;; rf2-o5dbf — production arm (see ns docstring).
      (let [half {:decode (constantly "/")}]
        (is (identical? half (rf.routing.strategy/url-strategy-from-config {:url-strategy half}))
            "the production consult returns the declared value verbatim, throwing nothing"))))
  (testing "a non-callable leg (a non-fn value in a required slot) is rejected too"
    (if rf.interop/debug-enabled?
      ;; rf2-o5dbf — dev-tripwire arm (see ns docstring), kept verbatim.
      (let [ex (try (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy {:encode "not-a-fn" :decode (constantly "/")}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
        (is (contains? (set (:missing (ex-data ex))) :encode)
            "a non-callable :encode counts as missing"))
      ;; rf2-o5dbf — production arm. The REJECTION of this exact value is not
      ;; lost under the gate: `preflight-carries-frame-id-in-ex-data` and
      ;; `make-frame-engine-rejects-malformed-strategy-before-any-write` below
      ;; assert it posture-independently at the registration chokepoint.
      (let [non-callable {:encode "not-a-fn" :decode (constantly "/")}]
        (is (identical? non-callable
                        (rf.routing.strategy/url-strategy-from-config {:url-strategy non-callable}))
            "the production consult returns the declared value verbatim, throwing nothing")
        (is (thrown? clojure.lang.ExceptionInfo
                     (rf.routing.strategy/preflight-frame-config! :t/owner {:url-strategy non-callable}))
            "…and the ungated registration preflight still rejects it LOUD")))))

(deftest url-strategy-from-config-is-a-trusted-read
  (testing "rf2-ecb4sx: a SHAPE-VALID declared strategy resolves by identity —
            the consult returns it verbatim, doing no work beyond the read (the
            production trusted-read path; the dev tripwire only re-checks, it
            does not transform)"
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy rf.routing.strategy/hash-url-strategy})))
    (let [custom {:encode identity :decode (constantly "/")}]
      (is (identical? custom
                      (rf.routing.strategy/url-strategy-from-config {:url-strategy custom})))))
  (testing "rf2-ecb4sx: absent / nil / non-map configs resolve to the history
            default with no validation (the default branch was always trusted)"
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config {})))
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config {:url-strategy nil}))
        "an explicit-nil :url-strategy falls through to history at the CONSULT
         (presence semantics are enforced at the preflight, not the read)")
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config nil)))))

(deftest shipped-and-complete-custom-strategies-pass-validation
  (testing "rf2-j538f7.11: validation does NOT disturb the valid paths — the
            shipped strategies, a with-base-path wrapper, and a shape-complete
            custom map all resolve unchanged"
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy rf.routing.strategy/history-url-strategy}))
        "the shipped history strategy passes")
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy rf.routing.strategy/hash-url-strategy}))
        "the shipped hash strategy passes")
    (let [wrapped (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/demos")]
      (is (identical? wrapped (rf.routing.strategy/url-strategy-from-config {:url-strategy wrapped}))
          "a with-base-path wrapper (encode/decode present) passes"))
    ;; validate-url-strategy! returns its input unchanged on success.
    (let [custom {:encode identity :decode (constantly "/")}]
      (is (identical? custom (rf.routing.strategy/validate-url-strategy! custom 'test nil))
          "validate-url-strategy! is identity on a valid strategy"))))

;; ---- with-base-path combinator (rf2-g8pbwg) -------------------------------
;;
;; The side-effecting `:push!` / `:replace!` / `:install-listener!` legs are
;; CLJS-only (a browser `window.history`); this JVM suite pins the
;; host-agnostic `:encode` / `:decode` wrapping + the blank-base no-op.

(deftest with-base-path-wraps-encode-and-decode
  (testing "with-base-path re-adds the base on :encode and strips it on :decode"
    (let [wrapped (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/realworld")]
      (is (= "/realworld/active" ((:encode wrapped) "/active")))
      (is (= "/realworld/" ((:encode wrapped) "/")))
      ;; :decode strips the base off whatever the wrapped strategy decodes —
      ;; simulate that by composing over a fixed decode via a custom strategy.
      (let [fake-decode (rf.routing.strategy/with-base-path
                          {:encode identity :decode (constantly "/realworld/active")}
                          "/realworld")]
        (is (= "/active" ((:decode fake-decode))))))))

(deftest with-base-path-normalizes-the-base
  (testing "a base with no leading slash gets one; a trailing slash is stripped"
    (let [no-lead  (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "realworld")
          trailing (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/realworld/")]
      (is (= "/realworld/active" ((:encode no-lead) "/active")))
      (is (= "/realworld/active" ((:encode trailing) "/active"))))))

(deftest with-base-path-decode-leaves-unrelated-urls-unchanged
  (testing "strip-base-path is defensive — a decoded URL that does not start
            with the base is returned unchanged rather than mis-sliced"
    (is (= "/other/path" (rf.routing.strategy/strip-base-path "/realworld" "/other/path")))))

(deftest with-base-path-strips-only-on-segment-boundary
  (testing "ADVERSARIAL (rf2-vuv84a): strip-base-path treats a URL as under the
            base ONLY at a path-SEGMENT boundary — the mount root (url = base)
            or `base/…`. A prefix-SHARING sibling that merely string-prefixes
            the base is returned UNCHANGED, not mis-sliced."
    ;; siblings that share the base as a bare STRING prefix must NOT be stripped
    ;; (the pre-fix defect: `/app` string-prefixed `/application` -> `/lication`).
    (is (= "/application/x" (rf.routing.strategy/strip-base-path "/app" "/application/x"))
        "/app must NOT strip /application (segment boundary, not string prefix)")
    (is (= "/apple" (rf.routing.strategy/strip-base-path "/app" "/apple")))
    (is (= "/app-admin" (rf.routing.strategy/strip-base-path "/app" "/app-admin")))
    (is (= "/realworld-demo" (rf.routing.strategy/strip-base-path "/realworld" "/realworld-demo"))
        "the motivating case: /realworld must not mangle /realworld-demo")
    ;; genuine under-base URLs still strip correctly.
    (is (= "/x" (rf.routing.strategy/strip-base-path "/app" "/app/x")))
    (is (= "/x/y" (rf.routing.strategy/strip-base-path "/app" "/app/x/y")))
    (is (= "/" (rf.routing.strategy/strip-base-path "/app" "/app"))
        "the bare mount root (url = base) strips to the app root `/`")))

(deftest with-base-path-blank-base-is-a-no-op
  (testing "a blank/nil base returns the wrapped strategy UNCHANGED — no
            wrapping cost for the common no-sub-path app"
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy nil)))
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "")))
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy "  ")))))

(deftest with-base-path-composes-over-hash-strategy
  (testing "with-base-path composes over hash-url-strategy too — the base
            prefixes the pathname portion, the wrapped strategy's own `#`
            form is preserved underneath"
    (let [wrapped (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy "/demos")]
      (is (= "/demos#/active" ((:encode wrapped) "/active"))
          "the wrapped hash encode still `#`-prefixes; the base sits in front of it"))))

(deftest with-base-path-jvm-side-omits-side-effecting-keys
  (testing "the JVM half of a wrapped strategy carries only :encode/:decode,
            matching the two shipped strategies' own JVM shape"
    (let [wrapped (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/realworld")]
      (is (fn? (:encode wrapped)))
      (is (fn? (:decode wrapped)))
      (is (nil? (:push! wrapped)))
      (is (nil? (:install-listener! wrapped))))))

(deftest facade-re-exports-with-base-path
  (testing "the routing façade re-exports with-base-path (public surface)"
    (is (= ((:encode (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/x")) "/a")
           ((:encode (rf.routing/with-base-path rf.routing.strategy/history-url-strategy "/x")) "/a")))))

;; ---- registration-time frame-config preflight (rf2-ktmto9) ----------------
;;
;; `preflight-frame-config!` is the PURE registration-time preflight the core
;; engine (`re-frame.frame/make-frame`) invokes through the
;; `:routing/preflight-frame-config!` late-bind hook with the FINAL expanded
;; config, BEFORE any candidate-derived write. PRESENCE semantics: an ABSENT
;; `:url-strategy` key is a no-op (omission alone selects the default); a
;; PRESENT key — including an explicit nil — is an explicit declaration and
;; must be a valid strategy map. On the JVM the host-required legs are
;; `:encode` / `:decode` only (Spec 012 §URL strategies — SSR never executes
;; the browser legs).

(deftest preflight-absent-url-strategy-is-a-no-op
  (testing "rf2-ktmto9: a config with NO :url-strategy key preflights clean —
            omission alone selects the default history strategy, so an
            ordinary frame (url-bound or not) pays no validation"
    (is (nil? (rf.routing.strategy/preflight-frame-config! :t/plain {})))
    (is (nil? (rf.routing.strategy/preflight-frame-config! :t/owner {:url-bound? true})))))

(deftest preflight-explicit-nil-url-strategy-is-malformed
  (testing "rf2-ktmto9: an EXPLICIT nil :url-strategy is a PRESENT declaration
            and fails loud — presence semantics, not truthiness; only omission
            selects the default"
    (let [ex (try (rf.routing.strategy/preflight-frame-config!
                    :t/owner {:url-bound? true :url-strategy nil})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "an explicit nil :url-strategy throws")
      (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
      (is (= :t/owner (:frame (ex-data ex)))
          "the ex-data names the offending frame"))))

(deftest preflight-carries-frame-id-in-ex-data
  (testing "rf2-ktmto9: a malformed declaration's :rf.error/invalid-url-strategy
            ex-data carries the frame id, so the diagnostic names WHICH frame
            declared the bad strategy"
    (let [ex (try (rf.routing.strategy/preflight-frame-config!
                    :t/owner {:url-strategy {:decode (constantly "/")}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
      (is (= :t/owner (:frame (ex-data ex))))
      (is (contains? (set (:missing (ex-data ex))) :encode)))))

(deftest preflight-valid-shipped-and-custom-strategies-pass
  (testing "rf2-ktmto9: the shipped strategies, a with-base-path wrapper, and a
            JVM-shape-complete custom map all preflight clean"
    (is (nil? (rf.routing.strategy/preflight-frame-config!
                :t/owner {:url-strategy rf.routing.strategy/history-url-strategy})))
    (is (nil? (rf.routing.strategy/preflight-frame-config!
                :t/owner {:url-strategy rf.routing.strategy/hash-url-strategy})))
    (is (nil? (rf.routing.strategy/preflight-frame-config!
                :t/owner {:url-strategy (rf.routing.strategy/with-base-path
                                          rf.routing.strategy/history-url-strategy "/demos")})))
    (is (nil? (rf.routing.strategy/preflight-frame-config!
                :t/owner {:url-strategy {:encode identity
                                         :decode (constantly "/")}})))))

(deftest make-frame-engine-rejects-malformed-strategy-before-any-write
  (testing "rf2-ktmto9: the core engine (rf.frame/upsert-frame!) preflights the
            declared :url-strategy BEFORE any write — a malformed first
            registration throws :rf.error/invalid-url-strategy and leaves NO
            frame record (the fail-loud + no-residue
            invariant; pre-fix the throw came from the post-create hook, after
            the container + :initial-events already ran)"
    (let [ex (try (rf.frame/upsert-frame! :ktmto9/bad-jvm
                                       {:url-bound?   true
                                        :url-strategy {:decode (constantly "/")}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the malformed first registration throws")
      (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
      (is (= :ktmto9/bad-jvm (:frame (ex-data ex))))
      (is (not (contains? (set (rf.frame/frame-ids)) :ktmto9/bad-jvm))
          "no frame record was created")
      (is (nil? (rf.frame/frame-meta :ktmto9/bad-jvm))
          "the failed frame is invisible to frame-meta"))))

(deftest make-frame-missing-routing-artefact-fails-loud
  (testing "rf2-ktmto9: a config declaring :url-strategy while the
            :routing/preflight-frame-config! hook is UNPUBLISHED (routing not
            loaded before construction) fails loud with
            :rf.error/routing-artefact-missing — storing a strategy nobody can
            validate or execute is worse than a dependency error"
    (try
      (rf.late-bind/set-fn! :routing/preflight-frame-config! nil)
      (let [ex (try (rf.frame/upsert-frame! :ktmto9/no-artefact
                                         {:url-bound?   true
                                          :url-strategy rf.routing.strategy/history-url-strategy})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "declaring :url-strategy without the routing artefact throws")
        (is (= :rf.error/routing-artefact-missing (:rf.error/id (ex-data ex))))
        (is (= :ktmto9/no-artefact (:frame (ex-data ex))))
        (is (nil? (rf.frame/frame-meta :ktmto9/no-artefact))
            "no frame record was seated"))
      (finally
        (rf.late-bind/set-fn! :routing/preflight-frame-config!
                           rf.routing.strategy/preflight-frame-config!)))))

(deftest url-strategy-for-frame-id-reads-frames-store
  (testing "url-strategy-for-frame-id reads the frame's stored config off the
            frames store (rf2-h1vqa4 — frames have no registrar rows),
            defaulting to history for an unregistered / nil frame"
    ;; Real seated frames — the resolver reads `rf.frame/frame-meta`, so the test
    ;; must go through the engine (container creation needs an adapter).
    (rf/init! rf.substrate.plain-atom/adapter)
    (try
      ;; A hash-bound frame resolves to the hash strategy.
      (rf.frame/upsert-frame! :test/hash-owner
                           {:url-bound? true :url-strategy rf.routing.strategy/hash-url-strategy})
      (rf.frame/upsert-frame! :test/plain {:url-bound? true})
      (is (identical? rf.routing.strategy/hash-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :test/hash-owner)))
      (is (identical? rf.routing.strategy/history-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :test/plain))
          "a frame declaring no :url-strategy resolves to history")
      (is (identical? rf.routing.strategy/history-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :test/never-registered))
          "an unregistered frame resolves to the history default")
      (is (identical? rf.routing.strategy/history-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id nil))
          "a nil frame-id resolves to the history default")
      (finally
        (rf.frame/destroy-frame! :test/hash-owner)
        (rf.frame/destroy-frame! :test/plain)))))

;; ---- trusted-read invariant: the store never holds an unvalidated strategy --
;; (rf2-ecb4sx Part 1 — the gate the consult-path simplification rides on)
;;
;; The four consult points (route-link render, the :rf.nav/push-url /
;; :rf.nav/replace-url fxs, the URL-change listener install) resolve through
;; `url-strategy-for-frame-id` → `rf.frame/frame-meta` → the `frames` store, and
;; rf2-ecb4sx makes that read TRUSTED (no per-consult validation). These tests
;; pin the invariant that makes the trusted read safe: the ONLY writer of a
;; frame's `:config` (hence its `:url-strategy`) into the `frames` store is
;; `rf.frame/upsert-frame!`, which runs the registration-time PREFLIGHT
;; (rf2-ktmto9) BEFORE the store write — so no code path can seat an
;; unvalidated strategy. Load-order (declaring `:url-strategy` before routing
;; loads fails loud — `make-frame-missing-routing-artefact-fails-loud` above)
;; and the absence of internal direct-write bypasses are pinned here.

(deftest store-only-ever-holds-validated-strategies-inv
  (testing "rf2-ecb4sx: every frame reachable via frame-meta carries a strategy
            the consult resolves WITHOUT throwing — even with the dev tripwire
            active — because only the preflighting engine can seat one. This is
            the invariant the trusted-read consult relies on: whatever is in the
            store already passed validation."
    (rf/init! rf.substrate.plain-atom/adapter)
    (try
      ;; Seat a representative mix through the engine (the sole config writer):
      ;; default, hash, a JVM-shape-complete custom, and a with-base-path wrap.
      (rf.frame/upsert-frame! :ecb4sx-inv/default {:url-bound? true})
      (rf.frame/upsert-frame! :ecb4sx-inv/hash
                           {:url-bound? true :url-strategy rf.routing.strategy/hash-url-strategy})
      (rf.frame/upsert-frame! :ecb4sx-inv/custom
                           {:url-bound? true
                            :url-strategy {:encode identity :decode (constantly "/")}})
      (rf.frame/upsert-frame! :ecb4sx-inv/based
                           {:url-bound? true
                            :url-strategy (rf.routing.strategy/with-base-path
                                            rf.routing.strategy/history-url-strategy "/demos")})
      (let [ids (rf.frame/frame-ids "ecb4sx-inv")]
        (is (every? ids [:ecb4sx-inv/default :ecb4sx-inv/hash
                         :ecb4sx-inv/custom :ecb4sx-inv/based])
            "all four seated under the ecb4sx-inv prefix")
        (doseq [id ids]
          ;; The trusted read resolves a valid strategy for every seated frame,
          ;; and the dev tripwire (active in this JVM) does NOT throw — proof the
          ;; store holds only validated strategies (no direct-write bypass).
          (let [strat (rf.routing.strategy/url-strategy-for-frame-id id)]
            (is (map? strat) (str "resolved a strategy map for " id))
            (is (fn? (:encode strat)) (str ":encode is callable for " id))
            (is (fn? (:decode strat)) (str ":decode is callable for " id)))))
      (finally
        (rf.frame/destroy-frame! :ecb4sx-inv/default)
        (rf.frame/destroy-frame! :ecb4sx-inv/hash)
        (rf.frame/destroy-frame! :ecb4sx-inv/custom)
        (rf.frame/destroy-frame! :ecb4sx-inv/based)))))

(deftest rejected-strategy-never-reaches-the-consult-inv
  (testing "rf2-ecb4sx: a malformed first registration is rejected at the
            preflight and leaves NO store residue, so the consult
            (url-strategy-for-frame-id) reads the history DEFAULT for that id —
            the rejected strategy is never installed where a consult could see
            it, so the trusted read stays safe on the failure path too"
    (rf/init! rf.substrate.plain-atom/adapter)
    (let [ex (try (rf.frame/upsert-frame! :ecb4sx-inv/rejected
                                       {:url-bound?   true
                                        :url-strategy {:decode (constantly "/")}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the malformed registration throws at the preflight")
      (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
      (is (nil? (rf.frame/frame-meta :ecb4sx-inv/rejected))
          "no config was seated (no residue)")
      (is (identical? rf.routing.strategy/history-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :ecb4sx-inv/rejected))
          "the consult reads the history default — the rejected strategy is
           invisible to it"))))

(deftest set-generation!-does-not-install-an-unvalidated-strategy-inv
  (testing "rf2-ecb4sx: `rf.frame/set-generation!` — the only `frames`-store writer
            OTHER than the preflighting engine — touches only the :generation
            slot, never :config/:url-strategy, so it cannot smuggle an
            unvalidated strategy past the consult. After a generation swap the
            consult still resolves the originally-validated hash strategy."
    (rf/init! rf.substrate.plain-atom/adapter)
    (try
      (rf.frame/upsert-frame! :ecb4sx-inv/gen
                           {:url-bound? true :url-strategy rf.routing.strategy/hash-url-strategy})
      (is (identical? rf.routing.strategy/hash-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :ecb4sx-inv/gen)))
      (rf.frame/set-generation! :ecb4sx-inv/gen :some-generation)
      (is (identical? rf.routing.strategy/hash-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :ecb4sx-inv/gen))
          "the generation swap left the validated :url-strategy untouched")
      (finally
        (rf.frame/destroy-frame! :ecb4sx-inv/gen)))))
