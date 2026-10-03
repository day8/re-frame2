(ns re-frame.routing-url-strategy-test
  "URL-strategy seam tests. Locks the pure legs of the two
  shipped strategies — `history-url-strategy` (default, path-form) and
  `hash-url-strategy` (`#`-prefixed) — the `with-base-path` combinator
  (for an app deployed under a sub-path) — plus the frame-config
  resolution the four egress/ingress consult points share.

  The side-effecting `:push!` / `:replace!` / `:install-listener!` keys are
  CLJS-only (a browser `window.history` / listener); the browser round-trip
  of those + the end-to-end route-link / history-fx integration is pinned in
  `routing_url_strategy_cljs_test.cljs`. This JVM suite pins the host-agnostic
  contract: encode/decode shape, the encode/decode ROUND-TRIP identity, the
  ADVERSARIAL negative fixtures (malformed / mismatched forms), and
  `url-strategy-from-config` / `url-strategy-for-frame-id`. Per Spec 012
  §URL strategies.

  ## Posture split

  The fail-loud validation seam is FRAME CONSTRUCTION:
  `preflight-frame-config!` runs `validate-url-strategy!` UNCONDITIONALLY at
  `re-frame.frame/upsert-frame!`, the sole `frames`-store config writer. That
  is production-real and is asserted here WITHOUT a posture guard — the
  preflight tests, the engine-rejects-before-any-write test and the two
  trusted-read store invariants all run in the ordinary `clojure -M:test`
  suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  What is NOT production-real is the CONSULT-path tripwire.
  `url-strategy-from-config` is a trusted read that pays no per-render
  validation; all that sits there is a `(when rf.interop/debug-enabled? …)`
  re-check, DCE'd from production CLJS bundles and dead on a JVM started with
  `-Dre-frame.debug=false`. The two tripwire deftests below therefore branch
  on `rf.interop/debug-enabled?`: the dev arm makes the fail-loud assertions,
  and the production arm asserts what the trusted read actually
  does when the tripwire is gone — it returns the declared value verbatim,
  throwing nothing, which is the contract the ~30x consult speed-up rides on.
  Neither arm is vacuous."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing]
            [re-frame.routing.strategy :as rf.routing.strategy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- shipped-strategy shape ----------------------------------------------

(deftest jvm-strategies-omit-the-side-effecting-legs
  (testing "on the JVM the shipped strategies and a with-base-path wrapper
            carry only the pure :encode / :decode legs, which
            decode-inverts-encode-for-every-shipped-form calls. The
            side-effecting legs are CLJS-only — SSR runs no browser side
            effects; the pure :encode it DOES run is pinned cross-host in
            route_link_ssr_parity_cljs_test"
    (let [based (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/realworld")]
      (doseq [[label strategy leg]
              [["history"              rf.routing.strategy/history-url-strategy :push!]
               ["history"              rf.routing.strategy/history-url-strategy :install-listener!]
               ["hash"                 rf.routing.strategy/hash-url-strategy    :push!]
               ["history + /realworld" based                                    :push!]
               ["history + /realworld" based                                    :install-listener!]]]
        (is (nil? (get strategy leg))
            (str label " carries no " leg " on the JVM"))))))

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

;; ---- consult-path dev tripwire ---------------------------------------------
;;
;; A custom `:url-strategy` consumed VERBATIM with no shape/callability
;; check would let a typo / partial adapter / hot-reload intermediate value
;; fail only later — deep in a consult point — as a raw host nil-function /
;; TypeError. The FAIL-LOUD validation lives at the registration-time PREFLIGHT (the
;; sole frame-config commit chokepoint — see the preflight section below), so a
;; malformed strategy can never enter the `frames` store the consults read.
;;
;; `url-strategy-from-config` is therefore a TRUSTED READ: it does not
;; validate on the production consult path (which would cost `route-link`
;; ~90 ns per render). What sits at the consult is a
;; DEV-ONLY tripwire, gated on `re-frame.interop/debug-enabled?` (`goog.DEBUG`
;; on CLJS, the `re-frame.debug` gate on the JVM) and dead-code-eliminated from
;; production CLJS bundles. It re-runs `validate-url-strategy!` so a FUTURE
;; config-write bypass fails loud in development. These two tests run in
;; the dev-default JVM (`debug-enabled?` true), so the tripwire fires and its
;; fail-loud behaviour is pinned. On the JVM the required legs
;; are `:encode` / `:decode` only (the browser legs are reader-conditionally
;; absent from the shipped strategies — Spec 012 §URL strategies).

(deftest custom-url-strategy-non-map-tripwire
  (testing "with the dev tripwire active (debug-enabled? — the JVM
            test default), a truthy but NON-MAP :url-strategy fails loud
            with :rf.error/invalid-url-strategy at the consult, backstopping the
            registration preflight for any future config-write bypass"
    (if rf.interop/debug-enabled?
      ;; Dev-tripwire arm (see ns docstring).
      (let [ex (try (rf.routing.strategy/url-strategy-from-config {:url-strategy :not-a-map})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "a non-map :url-strategy throws under the dev tripwire")
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex)))
            "the canonical structured error id is stamped")
        (is (= :not-a-map (:url-strategy (ex-data ex)))
            "the offending value is carried in the ex-data"))
      ;; Production arm: the tripwire is DCE'd / gated off, so the
      ;; consult is a pure trusted read. It must not throw and must not
      ;; silently substitute a default — the preflight (asserted
      ;; posture-independently below) is what keeps a value of this shape out
      ;; of the store in the first place.
      (is (= :not-a-map (rf.routing.strategy/url-strategy-from-config {:url-strategy :not-a-map}))
          "under -Dre-frame.debug=false the consult returns the declared value
           verbatim and pays no validation — the trusted read"))))

(deftest custom-url-strategy-missing-leg-tripwire
  (testing "a custom strategy missing a
            callable :encode (only :decode supplied) fails loud under the dev
            tripwire naming the bad leg"
    (if rf.interop/debug-enabled?
      ;; Dev-tripwire arm (see ns docstring).
      (let [ex (try (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy {:decode (constantly "/")}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "a strategy missing a required callable leg throws")
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
        (is (contains? (set (:missing (ex-data ex))) :encode)
            "the missing :encode leg is named in :missing"))
      ;; Production arm (see ns docstring).
      (let [half {:decode (constantly "/")}]
        (is (identical? half (rf.routing.strategy/url-strategy-from-config {:url-strategy half}))
            "the production consult returns the declared value verbatim, throwing nothing"))))
  (testing "a non-callable leg (a non-fn value in a required slot) is rejected too"
    (if rf.interop/debug-enabled?
      ;; Dev-tripwire arm (see ns docstring).
      (let [ex (try (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy {:encode "not-a-fn" :decode (constantly "/")}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
        (is (contains? (set (:missing (ex-data ex))) :encode)
            "a non-callable :encode counts as missing"))
      ;; Production arm. The REJECTION of this exact value holds
      ;; under the gate: `preflight-carries-frame-id-in-ex-data` and
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
  (testing "a SHAPE-VALID declared strategy resolves by identity —
            the consult returns it verbatim, doing no work beyond the read (the
            production trusted-read path; the dev tripwire only re-checks, it
            does not transform)"
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/url-strategy-from-config
                      {:url-strategy rf.routing.strategy/hash-url-strategy})))
    (let [custom {:encode identity :decode (constantly "/")}]
      (is (identical? custom
                      (rf.routing.strategy/url-strategy-from-config {:url-strategy custom})))))
  (testing "absent / nil / non-map configs resolve to the history
            default with no validation (the default branch is trusted)"
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config {})))
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config {:url-strategy nil}))
        "an explicit-nil :url-strategy falls through to history at the CONSULT
         (presence semantics are enforced at the preflight, not the read)")
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/url-strategy-from-config nil)))))

;; ---- with-base-path combinator --------------------------------------------
;;
;; The side-effecting `:push!` / `:replace!` / `:install-listener!` legs are
;; CLJS-only (a browser `window.history`); this JVM suite pins the
;; host-agnostic `:encode` / `:decode` wrapping + the blank-base no-op.

(deftest with-base-path-prefixes-the-base-on-encode
  (testing "with-base-path re-adds the base to every encoded href, the app
            root included"
    (let [wrapped (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/realworld")]
      (is (= "/realworld/active" ((:encode wrapped) "/active")))
      (is (= "/realworld/" ((:encode wrapped) "/"))))))

(deftest with-base-path-normalizes-the-base
  (testing "a base with no leading slash gets one; a trailing slash is stripped"
    (let [no-lead  (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "realworld")
          trailing (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/realworld/")]
      (is (= "/realworld/active" ((:encode no-lead) "/active")))
      (is (= "/realworld/active" ((:encode trailing) "/active"))))))

(deftest with-base-path-decode-leaves-unrelated-urls-unchanged
  (testing "strip-base-path is defensive — a decoded URL that does not start
            with the base is returned unchanged rather than mis-sliced"
    ;; `/elsewhere` is as long as `/realworld` and is followed by `/`, so only
    ;; the prefix check keeps it from being stripped to `/path`.
    (is (= "/elsewhere/path" (rf.routing.strategy/strip-base-path "/realworld" "/elsewhere/path")))))

(deftest with-base-path-strips-only-on-segment-boundary
  (testing "ADVERSARIAL: strip-base-path treats a URL as under the
            base ONLY at a path-SEGMENT boundary — the mount root (url = base)
            or `base/…`. A prefix-SHARING sibling that merely string-prefixes
            the base is returned UNCHANGED, not mis-sliced."
    ;; siblings that share the base as a bare STRING prefix must NOT be stripped
    ;; (a bare string-prefix strip would turn `/application` under `/app` into `/lication`).
    (is (= "/application/x" (rf.routing.strategy/strip-base-path "/app" "/application/x"))
        "/app must NOT strip /application (segment boundary, not string prefix)")
    (is (= "/apple" (rf.routing.strategy/strip-base-path "/app" "/apple")))
    (is (= "/app-admin" (rf.routing.strategy/strip-base-path "/app" "/app-admin")))
    (is (= "/realworld-demo" (rf.routing.strategy/strip-base-path "/realworld" "/realworld-demo"))
        "a mount-point sibling: /realworld must not mangle /realworld-demo")
    ;; genuine under-base URLs strip correctly.
    (is (= "/x" (rf.routing.strategy/strip-base-path "/app" "/app/x")))
    (is (= "/x/y" (rf.routing.strategy/strip-base-path "/app" "/app/x/y")))
    (is (= "/" (rf.routing.strategy/strip-base-path "/app" "/app"))
        "the bare mount root (url = base) strips to the app root `/`")))

(deftest with-base-path-strips-mount-root-before-query-and-fragment
  (testing "the mount root followed DIRECTLY by `?` or `#` is still
            the mount root. `?` and `#` end the pathname, so they are post-base
            boundaries alongside end-of-string and `/`; the suffix is kept
            verbatim behind the app-root slash"
    (doseq [[case-name url expected]
            [["root + query"                           "/app?tab=all"          "/?tab=all"]
             ["root + fragment"                        "/app#section"          "/#section"]
             ["root + query + fragment"                "/app?tab=all#section"  "/?tab=all#section"]
             ["bare mount root (control)"              "/app"                  "/"]
             ["slash root (control)"                   "/app/"                 "/"]
             ["slash root + query + fragment (control)" "/app/?tab=all#section" "/?tab=all#section"]
             ["nested path (control)"                  "/app/items/ok"         "/items/ok"]
             ["sibling + query (control)"              "/application?tab=all"  "/application?tab=all"]
             ["sibling + fragment (control)"           "/app-admin#x"          "/app-admin#x"]]]
      (is (= expected (rf.routing.strategy/strip-base-path "/app" url))
          (str case-name ": " url " strips to " expected))))
  (testing "the wrapped :decode delivers the app-relative root URL"
    (let [wrapped (rf.routing.strategy/with-base-path
                    {:encode identity :decode (constantly "/app?tab=all#section")}
                    "/app")]
      (is (= "/?tab=all#section" ((:decode wrapped) "/app?tab=all#section"))))))

;; ---- `:decode` is PURE, the exact inverse of `:encode` ----------------------
;;
;; `:decode` takes the origin-relative browser address rather than reading
;; `window.location`, so the law is checked against
;; the SHIPPED strategies themselves, base-path forms included, on the JVM.

(deftest decode-inverts-encode-for-every-shipped-form
  (testing "(decode (encode p)) = p — history, hash, and both based forms"
    (let [strategies {"history"        rf.routing.strategy/history-url-strategy
                      "hash"           rf.routing.strategy/hash-url-strategy
                      "history + /app" (rf.routing.strategy/with-base-path
                                         rf.routing.strategy/history-url-strategy "/app")
                      "hash + /app"    (rf.routing.strategy/with-base-path
                                         rf.routing.strategy/hash-url-strategy "/app")}
          paths      ["/" "/users/42" "/users/42?tab=2" "/users/42#section"
                      "/users/42?tab=2#section" "/app/item"]]
      (doseq [[label {:keys [encode decode]}] strategies
              p paths]
        (is (= p (decode (encode p)))
            (str label ": decode∘encode round-trips " p)))))
  (testing "the based hash form takes its ingress rule from the INNER strategy —
            the fragment never carried the base, so an app route whose first
            segment equals the base is not stripped a second time"
    (let [{:keys [encode decode]} (rf.routing.strategy/with-base-path
                                    rf.routing.strategy/hash-url-strategy "/app")]
      (is (= "/app#/app/item" (encode "/app/item")))
      (is (= "/app/item" (decode "/app#/app/item")))))
  (testing "hash :decode reads only what follows the first `#`"
    (is (= "/" (rf.routing.strategy/hash-decode "/")))
    (is (= "/" (rf.routing.strategy/hash-decode "/#")))
    (is (= "/active" (rf.routing.strategy/hash-decode "/#active")))
    (is (= "/users/7#section" (rf.routing.strategy/hash-decode "/app#/users/7#section")))))

(deftest with-base-path-blank-base-is-a-no-op
  (testing "a blank/nil base returns the wrapped strategy UNCHANGED — no
            wrapping cost for the common no-sub-path app"
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy nil)))
    (is (identical? rf.routing.strategy/history-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "")))
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy "  ")))))

;; ---- registration-time frame-config preflight ----------------------------
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
  (testing "a config with NO :url-strategy key preflights clean —
            omission alone selects the default history strategy, so an
            ordinary frame (url-bound or not) pays no validation"
    (is (nil? (rf.routing.strategy/preflight-frame-config! :t/plain {})))
    (is (nil? (rf.routing.strategy/preflight-frame-config! :t/owner {:url-bound? true})))))

(deftest preflight-explicit-nil-url-strategy-is-malformed
  (testing "an EXPLICIT nil :url-strategy is a PRESENT declaration
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
  (testing "a malformed declaration's :rf.error/invalid-url-strategy
            ex-data carries the frame id, so the diagnostic names WHICH frame
            declared the bad strategy"
    (let [ex (try (rf.routing.strategy/preflight-frame-config!
                    :t/owner {:url-strategy {:decode (constantly "/")}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex))))
      (is (= :t/owner (:frame (ex-data ex))))
      (is (contains? (set (:missing (ex-data ex))) :encode)))))

(deftest make-frame-engine-rejects-malformed-strategy-before-any-write
  (testing "the core engine (rf.frame/upsert-frame!) preflights the
            declared :url-strategy BEFORE any write — a malformed first
            registration throws :rf.error/invalid-url-strategy and leaves NO
            frame record (the fail-loud + no-residue
            invariant; a throw from a post-create hook would come after
            the container + :initial-events had already run)"
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
          "the failed frame is invisible to frame-meta")
      (is (identical? rf.routing.strategy/history-url-strategy
                      (rf.routing.strategy/url-strategy-for-frame-id :ktmto9/bad-jvm))
          "the consult reads the history default — the rejected strategy never
           reaches the store a consult reads, so the trusted read stays safe
           on the failure path too"))))

(deftest make-frame-missing-routing-artefact-fails-loud
  (testing "a config declaring :url-strategy while the
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
            frames store (frames have no registrar rows),
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
;; (the gate the trusted consult-path read rides on)
;;
;; The four consult points (route-link render, the :rf.nav/push-url /
;; :rf.nav/replace-url fxs, the URL-change listener install) resolve through
;; `url-strategy-for-frame-id` → `rf.frame/frame-meta` → the `frames` store, and
;; that read is TRUSTED (no per-consult validation). These tests
;; pin the invariant that makes the trusted read safe: the ONLY writer of a
;; frame's `:config` (hence its `:url-strategy`) into the `frames` store is
;; `rf.frame/upsert-frame!`, which runs the registration-time PREFLIGHT
;; BEFORE the store write — so no code path can seat an
;; unvalidated strategy. Load-order (declaring `:url-strategy` before routing
;; loads fails loud — `make-frame-missing-routing-artefact-fails-loud` above)
;; and the absence of internal direct-write bypasses are pinned here.

(deftest store-only-ever-holds-validated-strategies-inv
  (testing "every frame reachable via frame-meta carries a strategy
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

(deftest set-generation!-does-not-install-an-unvalidated-strategy-inv
  (testing "`rf.frame/set-generation!` — the only `frames`-store writer
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
