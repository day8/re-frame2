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
            [re-frame.mcp-base.cap :as cap]
            [re-frame.mcp-base.diff-encode :as de]
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
