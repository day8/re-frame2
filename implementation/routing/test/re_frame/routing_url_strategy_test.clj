(ns re-frame.routing-url-strategy-test
  "URL-strategy seam tests (rf2-aerrz5). Locks the pure legs of the two
  shipped strategies — `history-url-strategy` (default, path-form) and
  `hash-url-strategy` (`#`-prefixed) — plus the frame-config resolution
  the four egress/ingress consult points share.

  The side-effecting `:push!` / `:replace!` / `:install-listener!` keys are
  CLJS-only (a browser `window.history` / listener); the browser round-trip
  of those + the end-to-end route-link / history-fx integration is pinned in
  `routing_url_strategy_cljs_test.cljs`. This JVM suite pins the host-agnostic
  contract: encode/decode shape, the encode/decode ROUND-TRIP identity, the
  ADVERSARIAL negative fixtures (malformed / mismatched forms), and
  `url-strategy-from-config` / `url-strategy-for-frame-id`. Per Spec 012
  §URL strategies."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as registrar]
            [re-frame.routing :as routing]
            [re-frame.routing.strategy :as strategy]))

;; ---- shipped-strategy shape ----------------------------------------------

(deftest history-strategy-shape
  (testing "history-url-strategy carries the two host-agnostic keys on JVM
            (the side-effecting keys are CLJS-only — SSR ignores strategies)"
    (is (fn? (:encode strategy/history-url-strategy)))
    (is (fn? (:decode strategy/history-url-strategy)))
    ;; JVM half omits the side-effecting keys (see the ns docstring).
    (is (nil? (:push! strategy/history-url-strategy)))
    (is (nil? (:install-listener! strategy/history-url-strategy)))))

(deftest hash-strategy-shape
  (testing "hash-url-strategy carries the two host-agnostic keys on JVM"
    (is (fn? (:encode strategy/hash-url-strategy)))
    (is (fn? (:decode strategy/hash-url-strategy)))
    (is (nil? (:push! strategy/hash-url-strategy)))))

(deftest facade-re-exports-both-strategies
  (testing "the routing façade re-exports both shipped strategies (public surface)"
    (is (identical? strategy/history-url-strategy routing/history-url-strategy))
    (is (identical? strategy/hash-url-strategy routing/hash-url-strategy))))

;; ---- history strategy: encode is identity (path IS the URL) --------------

(deftest history-encode-is-identity
  (testing "history encode leaves a path-form URL unchanged"
    (doseq [p ["/" "/active" "/completed" "/articles/42?q=milk" "/x#frag"]]
      (is (= p (strategy/history-encode p))
          (str "history-encode is identity for " (pr-str p))))))

;; ---- hash strategy: encode `#`-prefixes the path -------------------------

(deftest hash-encode-prefixes-hash
  (testing "hash encode maps a path-form URL to its `#`-prefixed href"
    (is (= "#/" (strategy/hash-encode "/")))
    (is (= "#/active" (strategy/hash-encode "/active")))
    (is (= "#/completed" (strategy/hash-encode "/completed")))
    (is (= "#/articles/42?q=milk" (strategy/hash-encode "/articles/42?q=milk"))))
  (testing "hash encode is idempotent — an already-`#`-prefixed input is unchanged"
    (is (= "#/active" (strategy/hash-encode "#/active"))
        "a raw hash href is not double-hashed"))
  (testing "hash encode maps nil to the root hash (defensive)"
    (is (= "#/" (strategy/hash-encode nil)))))

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
      (is (= p (hash-decode-model (strategy/hash-encode p)))
          (str "hash round-trip recovers " (pr-str p))))))

(deftest history-encode-decode-round-trip-is-identity
  (testing "history encode is identity, so its round-trip is trivially the input"
    (doseq [p ["/" "/active" "/articles/42?q=milk"]]
      (is (= p (strategy/history-encode p))))))

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
          hist-href (strategy/history-encode p)  ;; "/active"
          hash-href (strategy/hash-encode p)]    ;; "#/active"
      (is (not= hist-href hash-href)
          "history and hash hrefs differ for the same path")
      ;; A HASH href fed to the HISTORY decode projection (identity over the
      ;; app-relative URL) is NOT the path — the leading `#` survives, so the
      ;; router would route-miss. The two forms are not interchangeable.
      (is (not= p (strategy/history-encode hash-href))
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
    (is (identical? strategy/history-url-strategy
                    (strategy/url-strategy-from-config {})))
    (is (identical? strategy/history-url-strategy
                    (strategy/url-strategy-from-config {:url-bound? true})))
    (is (identical? strategy/history-url-strategy
                    (strategy/url-strategy-from-config nil))
        "a non-map config falls back to the history default")))

(deftest url-strategy-from-config-reads-declared-strategy
  (testing "a frame config declaring :url-strategy resolves to it"
    (is (identical? strategy/hash-url-strategy
                    (strategy/url-strategy-from-config
                      {:url-bound? true :url-strategy strategy/hash-url-strategy})))
    ;; a custom strategy map is honoured verbatim (the seam is open — the two
    ;; shipped strategies are the blessed pair, but the config value passes
    ;; through unchanged).
    (let [custom {:encode identity :decode (constantly "/")}]
      (is (identical? custom
                      (strategy/url-strategy-from-config {:url-strategy custom}))))))

(deftest url-strategy-for-frame-id-reads-registry
  (testing "url-strategy-for-frame-id reads the frame's stored config, defaulting
            to history for an unregistered / nil frame"
    (try
      ;; A hash-bound frame resolves to the hash strategy.
      (registrar/register! :frame :test/hash-owner
                           {:url-bound? true :url-strategy strategy/hash-url-strategy})
      (registrar/register! :frame :test/plain {:url-bound? true})
      (is (identical? strategy/hash-url-strategy
                      (strategy/url-strategy-for-frame-id :test/hash-owner)))
      (is (identical? strategy/history-url-strategy
                      (strategy/url-strategy-for-frame-id :test/plain))
          "a frame declaring no :url-strategy resolves to history")
      (is (identical? strategy/history-url-strategy
                      (strategy/url-strategy-for-frame-id :test/never-registered))
          "an unregistered frame resolves to the history default")
      (is (identical? strategy/history-url-strategy
                      (strategy/url-strategy-for-frame-id nil))
          "a nil frame-id resolves to the history default")
      (finally
        (registrar/unregister! :frame :test/hash-owner)
        (registrar/unregister! :frame :test/plain)))))
