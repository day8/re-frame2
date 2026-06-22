(ns re-frame.security.classification-fail-open-security-cljs-test
  "Adversarial-property SECURITY tier — the EP-0025 / Spec 015 fail-open
  data-classification contract at the egress boundary.

  ## The contract under test

  Data classification is **path-based** and **fail-open**: the framework
  redacts a value at a mediated-egress boundary IFF the app-db **PATH** it
  occupies has been classified `:sensitive` (via the commit-plane
  classification effect a handler returns alongside `:db`). The deliberate
  corollary — ruled, per Spec 015 §The north star + §No propagation — is that
  classification does NOT propagate and there is NO value-match / taint engine:

    - a CLASSIFIED app-db path redacts to `:rf/redacted` at egress;
    - an UNCLASSIFIED sibling at a different path ships RAW — even when it
      holds a BYTE-IDENTICAL copy of the classified secret (the re-keyed /
      rendered-copy fail-open case).

  This is the security-relevant edge of the design: a team that relies on
  classification as a SECRECY guarantee will leak a re-keyed secret. The
  test pins the contract so a regression that quietly re-introduces value-match
  redaction (which would mask the fail-open hazard) goes RED, and a regression
  that fails to redact a genuinely-classified path ALSO goes RED.

  ## Net property (verify-by-revert)

  - Reverting the walker to redact by VALUE (any leaf `=` to a classified
    leaf, anywhere) makes `unclassified-copy-of-secret-ships-raw` go RED — the
    re-keyed copy would wrongly redact.
  - Reverting the commit-plane `:sensitive` effect to a no-op makes
    `classified-path-redacts-at-egress` go RED — the classified path would
    ship raw.

  Dual-runtime `.cljc` (JVM `clojure -M:test` + shadow `:node-test` / the
  security `:test` alias). Uses the plain-atom substrate so a real
  `dispatch-sync` cascade installs the commit-plane classification."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The reset fixture auto-registers + scope-pins a default frame (the
;; carried-invariant equivalent of `(with-frame :rf/default …)`), so a bare
;; `dispatch-sync` cascades into it and a zero-arity `elide-wire-value` /
;; registry read resolves the frame.
(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; A distinctive secret so a deep substring scan is unambiguous.
(def ^:private secret "S3CR3T-rf2-nk2h6m-DO-NOT-LEAK")

(defn- contains-secret?
  "Deep substring scan over the printed form — true when the raw secret
  survives ANYWHERE in `x`."
  [x]
  (str/includes? (pr-str x) secret))

;; ---------------------------------------------------------------------------
;; 1. A CLASSIFIED app-db path redacts at egress (the positive half).
;; ---------------------------------------------------------------------------

(deftest classified-path-redacts-at-egress
  (testing "a path classified :sensitive by the commit-plane effect redacts to
            :rf/redacted at the egress walker; the app sees the raw value"
    (rf/reg-event :auth/login
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:auth :token] secret)
         :sensitive [[:auth :token]]}))
    (rf/dispatch-sync [:auth/login])
    ;; the application still sees the REAL value (classification is read
    ;; only at egress)
    (is (= secret (get-in (frame/frame-app-db-value :rf/default) [:auth :token]))
        "app-db holds the raw value — classification is read only at egress")
    ;; ... but the egress projection redacts the classified path
    (let [wire (rf/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= privacy/redacted-sentinel (get-in wire [:auth :token]))
          "the classified path projects :rf/redacted at egress")
      (is (not (contains-secret? wire))
          "the raw secret does not survive anywhere in the projected value"))))

;; ---------------------------------------------------------------------------
;; 2. An UNCLASSIFIED re-keyed COPY of the secret ships RAW (the fail-open
;;    half — the ruled, deliberate hazard). No value-match / taint.
;; ---------------------------------------------------------------------------

(deftest unclassified-copy-of-secret-ships-raw
  (testing "a byte-identical COPY of a classified secret at a DIFFERENT,
            unclassified path ships RAW at egress — classification is
            path-based and fail-open; there is no value-match redaction"
    (rf/reg-event :auth/login-and-copy
      (fn [{:keys [db]} _]
        {:db        (-> db
                        (assoc-in [:auth :token] secret)
                        ;; the SAME secret copied to an UNCLASSIFIED path —
                        ;; a re-keyed / rendered copy
                        (assoc-in [:ui :rendered-token] secret))
         :sensitive [[:auth :token]]}))   ;; ONLY the durable path is classified
    (rf/dispatch-sync [:auth/login-and-copy])
    (let [wire (rf/elide-wire-value (frame/frame-app-db-value :rf/default))]
      ;; the classified path is redacted ...
      (is (= privacy/redacted-sentinel (get-in wire [:auth :token]))
          "the classified path is redacted")
      ;; ... but the unclassified copy ships RAW (fail-open; no taint)
      (is (= secret (get-in wire [:ui :rendered-token]))
          "the unclassified re-keyed copy ships RAW — fail-open, no value-match")
      ;; so the secret DOES survive in the projected value, via the
      ;; unclassified path — the design's documented hazard.
      (is (contains-secret? wire)
          "the secret survives at the unclassified path (the fail-open contract)"))))

;; ---------------------------------------------------------------------------
;; 3. A wholly-UNCLASSIFIED secret ships raw (forgotten classification).
;; ---------------------------------------------------------------------------

(deftest forgotten-classification-ships-raw
  (testing "a secret written with NO classification at all ships raw —
            forgetting to classify is fail-open, not a guarded leak"
    (rf/reg-event :auth/login-unclassified
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:auth :token] secret)}))   ;; no :sensitive effect
    (rf/dispatch-sync [:auth/login-unclassified])
    (let [wire (rf/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= secret (get-in wire [:auth :token]))
          "the unclassified path ships raw — fail-open on omission")
      (is (contains-secret? wire)))))
