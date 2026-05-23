(ns re-frame.mcp-base.cljs-branches-cljs-test
  "CLJS-only coverage for re-frame2-mcp-base (rf2-80y2h).

  mcp-base ships as `.cljc` and both MCP servers consume it in CLJS
  (re-frame2-pair-mcp is a Node script). The canonical JVM suite
  (`clojure -M:test`) can only exercise the `:clj` reader-conditional
  arms, AND it always has Malli on the classpath — so three CLJS
  behaviours had ZERO executing coverage on any platform:

    1. The library actually loads and the diff algorithm round-trips
       under a CLJS runtime (not just the JVM).
    2. `diff-encode/validate-patches?` rides its `goog-define` default
       `true` in dev/test CLJS builds.
    3. The validation gates SOFT-PASS when Malli is not on the
       classpath — the `:cljs` arm of `resolve-malli-validate` returns
       nil, so `validate-patches!` / `validate-sections!` no-op rather
       than throw. This build runs WITHOUT Malli (see deps.edn
       `:cljs-test` — Malli is deliberately absent), so a malformed
       patch / section reaching the public decoder boundary is NOT
       rejected here, whereas the JVM suite (Malli present) DOES reject
       it. That observable contrast is the soft-pass branch pin.

  Coverage is via the PUBLIC API: the soft-pass branch is observable as
  'no throw on malformed input when Malli is absent', so we don't reach
  into the private helpers — we pin the behaviour the consumers depend
  on."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.cap :as cap]
            [re-frame.mcp-base.cursor :as cursor]
            [re-frame.mcp-base.diff-encode :as de]
            [re-frame.mcp-base.envelope :as envelope]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; 1. The .cljc library loads and the diff algorithm round-trips in CLJS.
;;    This is the load-bearing 'it runs on CLJS at all' pin — the JVM
;;    suite proved the algorithm; this proves the CLJS compile + runtime.
;; ---------------------------------------------------------------------------

(deftest diff-algorithm-round-trips-under-cljs
  (testing "collect-patches / apply-patches round-trip"
    (let [a {:user {:name "ada" :age 30} :session :idle}
          b {:user {:name "ada" :age 31 :role :admin}}
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p)))))
  (testing "diff-encode-db-after emits the :rf.mcp/diff-from marker"
    (let [epoch   {:db-before {:a 1 :b 2} :db-after {:a 1 :b 3}}
          encoded (de/diff-encode-db-after epoch)]
      (is (= :db-before (get-in encoded [:db-after vocab/diff-from-key])))
      (is (= epoch (de/decode-db-after encoded))
          "encode then decode reconstructs the original epoch")))
  (testing "diff-encode-epochs :full mode passes through"
    (let [epochs [{:db-before {:a 1} :db-after {:a 2}}]]
      (is (= epochs (de/diff-encode-epochs epochs :full))))))

;; ---------------------------------------------------------------------------
;; 2. The goog-define toggle rides its default `true` in dev/test builds.
;;    `validate-patches?` is a public `goog-define` def; pin its default
;;    so a build that flips it without intent (or a refactor that drops
;;    the default) trips here. Production bundles override it to false
;;    via :closure-defines.
;; ---------------------------------------------------------------------------

(deftest validate-patches?-goog-define-defaults-true
  (is (true? de/validate-patches?)
      "dev/test CLJS build leaves the validation toggle at its goog-define default"))

;; ---------------------------------------------------------------------------
;; 3. Soft-pass when Malli is absent (the :cljs resolve arm). This build
;;    runs WITHOUT Malli on the classpath, so `resolve-malli-validate`
;;    returns nil and the validation gates take their no-op branch. The
;;    JVM suite (Malli present) asserts the SAME inputs THROW; here they
;;    must NOT. That contrast is the soft-pass branch coverage.
;; ---------------------------------------------------------------------------

(deftest apply-patches-soft-passes-malformed-when-malli-absent
  ;; JVM `apply-patches-rejects-malformed-tuples` asserts these throw
  ;; :rf.error/bad-diff-patches (Malli present). With Malli absent the
  ;; validate-patches! gate is a no-op; the malformed tuple falls
  ;; through `apply-patches`'s own `cond` (:else acc) without a throw.
  (testing "unknown op does not throw (soft-pass) and is dropped by the cond"
    (is (= {} (de/apply-patches {} [[[:a] :replace 1]]))
        "no Malli ⇒ no validation throw; :replace falls through to :else acc"))
  (testing "well-formed patches still apply correctly"
    (is (= {:a 1 :b 2} (de/apply-patches {:a 1} [[[:b] :assoc 2]])))
    (is (= {:a 1} (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]])))))

(deftest decode-db-after-soft-passes-malformed-sections-when-malli-absent
  ;; JVM `decode-db-after-rejects-malformed-sections` asserts this
  ;; throws :rf.error/bad-diff-sections (Malli present). With Malli
  ;; absent the validate-sections! gate is a no-op, so decode proceeds
  ;; through the permissive `sections->patches` mapcat. The malformed
  ;; :section-kind / :section-path slots are cosmetic and ignored by the
  ;; replay; the :patches still apply.
  (let [epoch {:db-before {:a 1}
               :db-after  {:rf.mcp/diff-from :db-before
                           :sections [{:section-path :not-a-vector ;; malformed
                                       :section-kind :renamed       ;; not in enum
                                       :patches      [[[:a] :assoc 2]]}]}}
        decoded (de/decode-db-after epoch)]
    (is (= {:a 2} (:db-after decoded))
        "no Malli ⇒ no section-validation throw; patches replay regardless")))

;; ---------------------------------------------------------------------------
;; 4. The cap pipeline (the extracted two-stage gate, rf2-80y2h) runs
;;    under CLJS too. `over-cap?` / `reported-count` are pure CLJC; pin
;;    that the secondary char gate trips in isolation on the CLJS side
;;    as well — both servers cap on the wire.
;; ---------------------------------------------------------------------------

(def map-io
  (reify cap/ResultIO
    (content-texts [_ result] (map :text (:content result)))
    (build-overflow-result [_ marker _original]
      {:content           [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

(deftest cap-two-stage-gate-runs-under-cljs
  (testing "primary token gate"
    (is (true?  (cap/over-cap? 5001 6000 5000)))
    (is (false? (cap/over-cap? 5000 6000 5000))))
  (testing "secondary char gate trips in isolation"
    (is (true?  (cap/over-cap? 50 801 100)) "chars > cap*8 trips even with tokens under cap")
    (is (= 801  (cap/reported-count 50 801 100)) "char-gated ⇒ report chars")
    (is (= 50   (cap/reported-count 50 700 100)) "token-gated ⇒ report tokens"))
  (testing "apply-cap emits the overflow marker on a real over-budget payload"
    (let [r   {:content [{:type "text" :text (apply str (repeat 4000 "x"))}]}
          out (cap/apply-cap map-io r {:tool "snapshot" :cap 500 :hint "narrow scope"})
          body (get-in out [:structuredContent vocab/overflow-key])]
      (is (= :reached (:limit body)))
      (is (= 500 (:cap-tokens body)))
      (is (> (:token-count body) 500))))
  (testing "under-budget payload passes through untouched"
    (let [r   {:content [{:type "text" :text (pr-str {:small :payload})}]}
          out (cap/apply-cap map-io r {:tool "snapshot" :cap overflow/default-max-tokens})]
      (is (identical? r out)))))

;; ---------------------------------------------------------------------------
;; 5. Cross-host strict integer-parse contract (rf2-ee38b.19). The string
;;    arm of `parse-int*` previously diverged: raw `js/parseInt` parses a
;;    numeric PREFIX (`"12abc"` ⇒ 12) while JVM `Long/parseLong` rejects
;;    trailing garbage and falls back to default. This pins the CLJS half
;;    of the contract; the JVM half lives in args_test.clj. Both MUST
;;    agree (byte-identical default-fallback posture).
;; ---------------------------------------------------------------------------

(deftest parse-int-strict-cross-host-cljs
  (testing "trailing garbage falls back to default on CLJS too"
    (is (= 50 (args/parse-positive-int "12abc" 50)) "was 12 on CLJS before the fix")
    (is (= 50 (args/parse-positive-int "5xyz" 50)))
    (is (= 50 (args/parse-positive-int "1.5" 50)))
    (is (= 50 (args/parse-positive-int "1e3" 50)))
    (is (= 5000 (args/parse-non-negative-int "100x" 5000))))
  (testing "clean and signed strings still parse"
    (is (= 12 (args/parse-positive-int "12" 50)))
    (is (= 12 (args/parse-positive-int "+12" 50)))
    (is (= 1 (args/parse-positive-int "-5" 50)) "clamps to floor"))
  (testing "out-of-safe-range digit string rejected (mirrors JVM long overflow)"
    (is (= 50 (args/parse-positive-int "99999999999999999999999999" 50)))))

;; ---------------------------------------------------------------------------
;; 6. Shared cursor codec round-trips under CLJS (rf2-ee38b.19). The base64
;;    codec is reader-conditional (`js/Buffer` on CLJS); pin the encode →
;;    decode round-trip and the malformed/oversize sentinels on the
;;    Node runtime the pair-mcp server actually runs on.
;; ---------------------------------------------------------------------------

(deftest cursor-codec-round-trips-under-cljs
  (testing "encode then decode reproduces the payload"
    (let [payload {:v 1 :after-id "ev-42" :ms 1000}
          token   (cursor/encode-cursor payload)
          back    (cursor/decode-cursor token (fn [m] (and (map? m) (string? (:after-id m)))))]
      (is (string? token))
      (is (= payload back))))
  (testing "absent cursor decodes to nil"
    (is (nil? (cursor/decode-cursor nil any?)))
    (is (nil? (cursor/decode-cursor "" any?))))
  (testing "garbage / oversize / failing-payload predicate => ::malformed"
    (is (= :re-frame.mcp-base.cursor/malformed
           (cursor/decode-cursor "!!!not-base64-edn!!!" any?)))
    (is (= :re-frame.mcp-base.cursor/malformed
           (cursor/decode-cursor (apply str (repeat 2000 "a")) any?)))
    (let [token (cursor/encode-cursor {:v 1 :after-id "x"})]
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor token (fn [_] false))))))
  (testing "tagged literals in the cursor are rejected"
    (let [evil (cursor/b64-encode "#js {:a 1}")]
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor evil any?))))))

;; ---------------------------------------------------------------------------
;; 7. Shared with-indicators envelope helper under CLJS (rf2-ee38b.19).
;;    The MUST-level "omit when zero" parity rule runs identically on
;;    both hosts; pin the CLJS half.
;; ---------------------------------------------------------------------------

(deftest with-indicators-omit-when-zero-cljs
  (is (= {:trace [1]} (envelope/with-indicators {:trace [1]} {:dropped 0 :elided 0})))
  (is (= {:trace [1] :dropped-sensitive 3}
         (envelope/with-indicators {:trace [1]} {:dropped 3 :elided 0})))
  (is (= {:trace [1] :elided-large 2}
         (envelope/with-indicators {:trace [1]} {:dropped 0 :elided 2})))
  (is (= {:trace [1] :dropped-sensitive 3 :elided-large 2}
         (envelope/with-indicators {:trace [1]} {:dropped 3 :elided 2}))))
