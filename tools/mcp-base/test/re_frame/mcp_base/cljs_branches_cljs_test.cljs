(ns re-frame.mcp-base.cljs-branches-cljs-test
  "CLJS-only coverage for re-frame2-mcp-base.

  mcp-base ships as `.cljc` and both MCP servers consume it in CLJS
  (re-frame2-pair-mcp is a Node script). The canonical JVM suite
  (`clojure -M:test`) only exercises the `:clj` reader-conditional
  arms, AND it always has Malli on the classpath — so this suite owns
  three CLJS behaviours that only run here:

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
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.cap :as cap]
            [re-frame.mcp-base.cursor :as cursor]
            [re-frame.mcp-base.diff-encode :as de]
            [re-frame.mcp-base.envelope :as envelope]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.section-grouping :as sg]
            [re-frame.mcp-base.sensitive :as sensitive]
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
;; 4. The cap pipeline (the extracted two-stage gate) runs
;;    under CLJS too. `over-cap?` / `reported-count` are pure CLJC; pin
;;    that the secondary char gate trips in isolation on the CLJS side
;;    as well — both servers cap on the wire.
;; ---------------------------------------------------------------------------

(def map-io
  (reify cap/ResultIO
    (wire-payload-strings [_ result] (map :text (:content result)))
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
;; 4b. structuredContent must count toward the budget on the CLJS/pair path.
;;     re-frame2-pair-mcp's `wire/ok-text` / `err-text` emit
;;     a `:structuredContent` JS projection alongside the `:content[*].text`
;;     EDN on EVERY result — the SAME payload, twice on the wire. The
;;     mcp-base `wire-payload-strings` contract requires a
;;     dual-coding consumer to surface a stable string of that slot too;
;;     otherwise the cap undercounts by ~50% and a small-`:content` /
;;     huge-`:structuredContent` response slips past the overflow marker.
;;
;;     These are JS-object-shaped reifies (matching pair-mcp's real
;;     `#js {:content #js [...] :structuredContent ...}` envelope) so the
;;     regression bites on the CLJS/pair shape, not just story's CLJ map.
;; ---------------------------------------------------------------------------

(defn- js-text-slots
  "Pull the `:text` strings off a pair-mcp-shaped `#js [...]` content
  array via native interop (mcp-base carries no `js-interop` dep)."
  [^js content]
  (let [n (if (array? content) (.-length content) 0)]
    (loop [i 0 acc []]
      (if (< i n)
        (recur (inc i) (conj acc (.-text ^js (aget content i))))
        acc))))

(def ^:private structured-pair-io
  "Pair-shaped reify that HONOURS the dual-slot contract: `wire-payload-strings`
  surfaces both the `#js`-array `:text` slots AND a `js/JSON.stringify`
  of the `:structuredContent` JS object — the exact bytes the npm SDK
  serialises. This is the shape pair-mcp's `result-io` must adopt.
  Mirrors story-mcp's `structured-io` on the JS side."
  (reify cap/ResultIO
    (wire-payload-strings [_ result]
      (let [sc      (.-structuredContent ^js result)
            sc-text (when (some? sc) (js/JSON.stringify sc))]
        (cond-> (js-text-slots (.-content ^js result))
          (some? sc-text) (conj sc-text))))
    (build-overflow-result [_ marker _original]
      #js {:content          #js [#js {:type "text" :text (pr-str marker)}]
           :structuredContent (clj->js marker)})))

(def ^:private content-only-pair-io
  "Pair-shaped reify that IGNORES `:structuredContent`. Used only to
  demonstrate the undercount the contract forbids — the same payload
  passes the cap here while `structured-pair-io` trips it."
  (reify cap/ResultIO
    (wire-payload-strings [_ result]
      (js-text-slots (.-content ^js result)))
    (build-overflow-result [_ marker _original]
      #js {:content          #js [#js {:type "text" :text (pr-str marker)}]
           :structuredContent (clj->js marker)})))

(deftest structured-content-counted-on-pair-shape
  ;; A pair-mcp-shaped result: tiny `:content` text, huge dual-coded
  ;; `:structuredContent`. The honest reify MUST trip the cap; the
  ;; structuredContent-ignoring reify under-counts and lets it through.
  (let [huge   (clj->js {:big (apply str (repeat 30000 "x"))})
        result #js {:content          #js [#js {:type "text" :text "ok"}]
                    :structuredContent huge}
        cap    1000
        out-honest (cap/apply-cap structured-pair-io result {:tool "snapshot" :cap cap})
        out-under  (cap/apply-cap content-only-pair-io result {:tool "snapshot" :cap cap})
        ;; the overflow REPLACEMENT carries the marker's pr-str in its
        ;; `:content[0].text` slot — `:rf.mcp/overflow` is the tell.
        honest-text (.-text ^js (aget (.-content ^js out-honest) 0))]
    (testing "honest dual-slot reify trips the cap (structuredContent counts)"
      (is (not (identical? result out-honest))
          "the over-budget response was replaced, not passed through")
      (is (re-find #":rf\.mcp/overflow" honest-text)
          "overflow marker emitted — the structured slot pushed it over budget"))
    (testing "the ~50% undercount the contract forbids: ignoring structuredContent passes a huge response"
      (is (identical? result out-under)
          "structuredContent-blind reify under-counts and lets the over-budget payload through unchanged"))))

;; ---------------------------------------------------------------------------
;; 5. Cross-host strict integer-parse contract. The string
;;    arm of `parse-int*` could diverge: raw `js/parseInt` parses a
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
;; 5b. Cross-runtime finite/range guard on numeric arg coercion.
;;     A bare `(long raw)` with no guard is unsafe:
;;     on the JVM `##Inf` / `1.0E20` THROW and `##NaN` truncates to a real
;;     value, while CLJS would silently coerce. Both hosts route
;;     through one safe-integer-windowed guard so out-of-domain numerics
;;     DEFAULT (never crash, never become a real value) IDENTICALLY. These
;;     CLJS assertions mirror the JVM ones in args_test / cap_test.
;; ---------------------------------------------------------------------------

(deftest parse-int-finite-range-guard-cljs
  (testing "out-of-domain numerics default (mirrors JVM)"
    (is (= 50 (args/parse-positive-int js/Infinity 50)) "Infinity defaults")
    (is (= 50 (args/parse-positive-int (- js/Infinity) 50)) "-Infinity defaults")
    (is (= 50 (args/parse-positive-int js/NaN 50)) "NaN defaults (not a real floor)")
    (is (= 50 (args/parse-positive-int 1e20 50)) "1e20 defaults (past safe-integer window)")
    (is (= 5000 (args/parse-non-negative-int js/NaN 5000)) "NaN defaults (not a real 0)"))
  (testing "in-domain numerics still parse"
    (is (= 5 (args/parse-positive-int 5 50)))
    (is (= 2 (args/parse-positive-int 2.9 50)) "in-range fractional floors")
    (is (= 1 (args/parse-positive-int 0.5 50)) "sub-1 positive floors then clamps to floor 1")
    (is (= 9007199254740991 (args/parse-positive-int 9007199254740991 50))
        "safe-integer ceiling is in-domain"))
  (testing "string threshold aligns to the safe-integer window on both hosts"
    (is (= 50 (args/parse-positive-int "9007199254740992" 50))
        "one past the ceiling defaults on CLJS AND JVM (JVM no longer parses it)")
    (is (= 9007199254740991 (args/parse-positive-int "9007199254740991" 50))
        "exactly the ceiling still parses on both hosts")))

(deftest max-tokens-finite-range-guard-cljs
  ;; rf2-ykv9a0 — non-finite / out-of-range `:max-tokens` rejects with an
  ;; {:rf.mcp/invalid-arg} marker on CLJS too, never a crash / real 0-cap.
  (doseq [raw [js/Infinity js/NaN 1e20 (- 1e20)]]
    (let [out (cap/max-tokens raw)]
      (is (cap/invalid-arg? out) "non-finite / out-of-range max-tokens rejects")
      (is (not (number? out)) "rejection is a marker, not a real cap")
      (is (some? out) "not nil — distinct from the disable sentinel")))
  (is (cap/invalid-arg? (cap/max-tokens (- js/Infinity))) "-Infinity rejects (negative arm)")
  (is (= 5000 (cap/max-tokens 5000)) "in-range cap passes through"))

;; ---------------------------------------------------------------------------
;; 5c. Cursor rejects trailing forms on CLJS too (rf2-ykv9a0). A cursor is
;;     ONE opaque payload map; decoded text with a trailing form (tagged
;;     literal, ordinary EDN, scalar) ⇒ ::malformed. The wrap-in-[...]
;;     one-form check runs identically on cljs.reader.
;; ---------------------------------------------------------------------------

(deftest cursor-rejects-trailing-forms-cljs
  (let [pair? (fn [m] (and (map? m) (some? (:after-id m))))]
    (testing "valid map + trailing form ⇒ ::malformed"
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor
               (cursor/b64-encode "{:v 1 :after-id 1} #inst \"2024-01-01T00:00:00.000-00:00\"")
               pair?)))
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor
               (cursor/b64-encode "{:v 1 :after-id 1} {:junk 1}")
               pair?)))
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor
               (cursor/b64-encode "{:v 1 :after-id 1} 42")
               pair?))))
    ;; rf2-rtg9t1 — `]`-injection runs identically on cljs.reader. The
    ;; injected `]` closes the wrap-in-`[…]` early; the EOF-sentinel
    ;; exhaustion check rejects because the truncated read no longer ends
    ;; with the appended sentinel.
    (testing "valid map + injected ] + trailing junk ⇒ ::malformed"
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor
               (cursor/b64-encode "{:v 1 :after-id 1}] {:junk 1}")
               pair?)))
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor
               (cursor/b64-encode "{:v 1 :after-id 1}] 42")
               pair?)))
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor
               (cursor/b64-encode "{:v 1 :after-id 1}]")
               pair?))))
    (testing "a single clean cursor still round-trips"
      (is (= {:v 1 :after-id "ev-9"}
             (cursor/decode-cursor (cursor/encode-cursor {:v 1 :after-id "ev-9"}) pair?))))))

;; ---------------------------------------------------------------------------
;; 5d. apply-patches nested :dissoc no-op + section-kind :db-before
;;     classification run under CLJS too (rf2-ykv9a0).
;; ---------------------------------------------------------------------------

(deftest apply-patches-nested-dissoc-noop-cljs
  (testing "missing / scalar parent ⇒ no-op (no nil branches, no host throw)"
    (is (= {} (de/apply-patches {} [[[:missing :leaf] :dissoc]])))
    (is (= {:a {}} (de/apply-patches {:a {}} [[[:a :b :c] :dissoc]])))
    (is (= {:a 1} (de/apply-patches {:a 1} [[[:a :b] :dissoc]]))))
  (testing "valid nested + root dissoc unchanged"
    (is (= {:a {:c 2}} (de/apply-patches {:a {:b 1 :c 2}} [[[:a :b] :dissoc]])))
    (is (= {:a 1} (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]])))))

(deftest apply-patches-nested-assoc-scalar-parent-structured-error-cljs
  ;; rf2-glybzz — the `:assoc` peer of the dissoc guard, on CLJS.
  ;; CRUCIAL: this build runs WITHOUT Malli on the classpath, so the
  ;; grammar gate (`validate-patches!`) SOFT-PASSES — yet the replay
  ;; guard still fires. The guard is a pure structural walk during
  ;; replay, NOT a Malli schema check, so it is independent of Malli
  ;; presence and of the `validate-patches?` goog-define. A
  ;; mismatched-base `:assoc` therefore raises the documented structured
  ;; failure on CLJS too, never the raw host (`#object[TypeError]`)
  ;; exception that `assoc-in` into a scalar would otherwise produce.
  (testing "scalar intermediate parent ⇒ structured :rf.error/bad-diff-replay (Malli absent)"
    (let [e (try (de/apply-patches {:a 1} [[[:a :b] :assoc 2]]) nil
                 (catch :default ex ex))]
      (is (some? e) "must throw — the replay guard is not Malli-gated")
      (is (= :rf.error/bad-diff-replay (:rf.error/id (ex-data e))))
      (is (= 'mcp-base/apply-patches (:where (ex-data e))))
      (is (= [:a :b] (:patch-path (ex-data e))))
      (is (= [:a] (:at (ex-data e))))))
  (testing "vector parent reached by a non-integer key ⇒ structured error"
    (let [e (try (de/apply-patches {:a [1 2]} [[[:a :b] :assoc 9]]) nil
                 (catch :default ex ex))]
      (is (= :rf.error/bad-diff-replay (:rf.error/id (ex-data e))))))
  (testing "MISSING / nil intermediate parent still auto-vivifies (create-if-absent grammar)"
    (is (= {:a {:b 2}} (de/apply-patches {} [[[:a :b] :assoc 2]])))
    (is (= {:a {:b 2}} (de/apply-patches {:a nil} [[[:a :b] :assoc 2]]))))
  (testing "decode-db-after surfaces the same structured error via its own boundary"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections [{:section-path [:a] :section-kind :modified
                                         :patches [[[:a :b] :assoc 2]]}]}}
          e     (try (de/decode-db-after epoch) nil
                     (catch :default ex ex))]
      (is (= :rf.error/bad-diff-replay (:rf.error/id (ex-data e))))
      (is (= 'mcp-base/decode-db-after (:where (ex-data e)))))))

(deftest section-kind-db-before-classification-cljs
  (let [patches [[[:user :name]  :assoc "ada"]
                 [[:user :email] :assoc "x"]]]
    (is (= :modified (:section-kind (first (sg/group-patches-into-sections patches))))
        "no :db-before ⇒ conservative :modified")
    (is (= :modified (:section-kind (first (sg/group-patches-into-sections
                                             patches {:db-before {:user {:name "bob"}}}))))
        "existing container ⇒ :modified, not a false :added")
    (is (= :added (:section-kind (first (sg/group-patches-into-sections
                                          patches {:db-before {}}))))
        "absent container + direct-child assocs ⇒ :added")))

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
             (cursor/decode-cursor evil any?)))))
  (testing "built-in #inst / #uuid tags in a valid map are rejected on CLJS too (rf2-13wbe)"
    ;; `cljs.reader` resolves built-in `inst` / `uuid` from its tag-table
    ;; and BYPASSES `:default` — without the `:readers` override these
    ;; decode to a host `js/Date` / `cljs.core/UUID` and smuggle a host
    ;; object through the boundary inside an otherwise-valid map.
    (let [permissive? (fn [m] (and (map? m) (some? (:after-id m))))
          pair-inst   (cursor/b64-encode
                        "{:v 1 :after-id 1 :junk #inst \"2024-01-01T00:00:00.000-00:00\"}")
          pair-uuid   (cursor/b64-encode
                        "{:v 1 :after-id 1 :junk #uuid \"00000000-0000-0000-0000-000000000000\"}")]
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor pair-inst permissive?)))
      (is (= :re-frame.mcp-base.cursor/malformed
             (cursor/decode-cursor pair-uuid permissive?)))
      ;; a clean cursor still survives the permissive predicate
      (is (= {:v 1 :after-id "ev-9"}
             (cursor/decode-cursor (cursor/encode-cursor {:v 1 :after-id "ev-9"})
                                   permissive?))))))

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

;; ---------------------------------------------------------------------------
;; 8. `sensitive.cljc` CLJS reader-conditional arms (rf2-k76ohl — SECURITY).
;;
;;    `re-frame.mcp-base.sensitive` is the spec/009 §Privacy default-suppress
;;    filter every MCP forwarder routes trace-like data through. Its `:cljs`
;;    arms — the `(atom 0)` malformed-counter, the 11-branch `stamp-type-tag`
;;    cond, and the `js/console.warn` egress in `log-malformed!` — run in
;;    PRODUCTION inside re-frame2-pair-mcp (a CLJS Node bundle). Yet no CLJS
;;    test pinned them: the JVM suite (sensitive_test.clj) proves the log-egress
;;    redaction (`malformed-warning-redacts-raw-stamp-value`, using `*err*`) but
;;    the CLJS runtime path — the one the MCP servers actually run — had no
;;    counterpart. pair-mcp's own CLJS suite wraps every call in
;;    `(with-redefs [js/console (clj->js {:warn (fn [& _])})] …)`, ABSORBING the
;;    warning and asserting nothing about its content; the security-tier CLJS
;;    property test asserts the counter + that a VALUE-slot secret never
;;    survives egress, but never captures the `console.warn` bytes to prove the
;;    STAMP value (the log-boundary leak surface) is redacted.
;;
;;    This block closes that gap on CLJS: (a) the malformed warning is
;;    value-free (raw stamp absent, `:rf/redacted` + reason present); (b) the
;;    malformed counter is exactly-once-per-event through `strip-sensitive` and
;;    resets to zero; (c) representative `stamp-type-tag` branches.
;; ---------------------------------------------------------------------------

(defn- capture-console-warn
  "Run `thunk`, returning a vector of every `js/console.warn` call joined to a
  string. Directly saves + swaps + restores the REAL `js/console.warn` property
  that `sensitive/log-malformed!` calls — so the assertion exercises the ACTUAL
  CLJS log-egress path, not a stand-in. (The test-quiet node runner also
  replaces `console.warn` to buffer expected warnings; we save + restore
  whatever is installed, so this composes with the runner.)"
  [thunk]
  (let [captured (atom [])
        orig     (.-warn js/console)]
    (set! (.-warn js/console) (fn [& args] (swap! captured conj (apply str args))))
    (try
      (thunk)
      (finally
        (set! (.-warn js/console) orig)))
    @captured))

(deftest sensitive-malformed-warning-redacts-raw-stamp-value-cljs
  ;; CLJS counterpart to JVM `malformed-warning-redacts-raw-stamp-value`. The
  ;; contract-drift warning must carry a value-free type tag + the fixed
  ;; `:rf/redacted` sentinel — NEVER the raw stamp, which on a serialisation bug
  ;; could be a secret-bearing string / keyword / map / vector / number. This
  ;; is the log-boundary egress guarantee on the CLJS runtime pair-mcp runs.
  (sensitive/reset-malformed-count!)
  (doseq [[stamp leak-needle]
          [["sk_live_SECRET_TOKEN_abc123" "sk_live_SECRET_TOKEN_abc123"]
           [:secret/api-key-VALUE          "api-key-VALUE"]
           [{:api_key "AKIA_LEAK"
             :token   "bearer_LEAK"}       "AKIA_LEAK"]
           [["leaky-vector-ELEMENT"]       "leaky-vector-ELEMENT"]
           [42424242                        "42424242"]]]
    (let [warns (capture-console-warn
                  #(sensitive/sensitive-event? {:operation :rf.event/dispatched
                                                :sensitive? stamp}))
          text  (str/join "\n" warns)]
      (is (= 1 (count warns))
          (str "exactly one console.warn fired for " (pr-str stamp)
               " (proves the capture is non-vacuous)"))
      (is (not (str/includes? text leak-needle))
          (str "malformed warning leaked the raw stamp payload for " (pr-str stamp)))
      (is (str/includes? text ":rf/redacted")
          (str "malformed warning must emit the :rf/redacted sentinel for " (pr-str stamp)))
      (is (str/includes? text "non-boolean truthy")
          "warning must still surface the fail-closed contract-drift reason")))
  (sensitive/reset-malformed-count!))

(deftest sensitive-malformed-count-exactly-once-per-event-cljs
  ;; CLJS counterpart to JVM `strip-sensitive-malformed-count-is-exactly-one-
  ;; per-event`. `sensitive-event?` is side-effecting on the malformed path
  ;; (bump + log); the single-pass classifier in `strip-sensitive` runs it
  ;; exactly once per event, so the `(atom 0)` malformed-counter is a faithful
  ;; per-event metric on the CLJS atom path too. A two-pass shape would bump ~2×.
  (sensitive/reset-malformed-count!)
  (is (zero? (sensitive/malformed-count))
      "precondition: counter starts at zero after reset")
  (capture-console-warn ; absorb the expected contract-drift warnings
    (fn []
      (let [evts           [{:id 1 :sensitive? false}
                            {:id 2 :sensitive? "true"} ; malformed → drop, bump 1
                            {:id 3}
                            {:id 4 :sensitive? :yes}    ; malformed → drop, bump 1
                            {:id 5 :sensitive? true}]   ; well-formed → drop, NO bump
            [kept dropped] (sensitive/strip-sensitive evts false)]
        (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
        (is (= 3 dropped) "all three sensitive events drop")
        (is (= 2 (sensitive/malformed-count))
            "exactly one bump per MALFORMED event — single-pass, not ~2× per scan"))))
  ;; Well-formed stamps do NOT bump the counter.
  (capture-console-warn
    (fn []
      (sensitive/sensitive-event? {:sensitive? true})
      (sensitive/sensitive-event? {:sensitive? false})
      (sensitive/sensitive-event? {:sensitive? nil})
      (sensitive/sensitive-event? {})))
  (is (= 2 (sensitive/malformed-count))
      "true / false / nil / absent stamps do NOT bump the counter")
  (is (zero? (sensitive/reset-malformed-count!))
      "reset returns the new value (zero)")
  (is (zero? (sensitive/malformed-count))
      "reset zeroes the counter for the next test"))

(deftest sensitive-stamp-type-tag-branches-cljs
  ;; The `:cljs` arm of `stamp-type-tag` is an 11-branch cond (the JVM arm is a
  ;; one-liner `.getSimpleName`), reachable only through the fail-closed log
  ;; path. `stamp-type-tag` / `log-malformed!` are private, so we observe each
  ;; branch via the value-free `type=<tag>` token in the captured warning —
  ;; pinning that each truthy non-boolean shape maps to the right tag AND that
  ;; the value is redacted regardless of the branch taken.
  (sensitive/reset-malformed-count!)
  (doseq [[stamp expected-tag] [["a-string"          "string"]
                                [:a-keyword          "keyword"]
                                ['a-symbol           "symbol"]
                                [42                   "number"]
                                [{:a 1}              "map"]
                                [[1 2]               "vector"]
                                [#{1 2}              "set"]
                                [(map identity [1])  "seq"]]]
    (let [warns (capture-console-warn
                  #(sensitive/sensitive-event? {:sensitive? stamp}))
          text  (str/join "\n" warns)]
      (is (= 1 (count warns))
          (str "one warning fired for stamp " (pr-str stamp)))
      (is (str/includes? text (str "type=" expected-tag))
          (str "stamp " (pr-str stamp) " must log the value-free type tag "
               expected-tag))
      (is (str/includes? text "value=:rf/redacted")
          (str "the value stays redacted regardless of the type tag ("
               expected-tag ")"))))
  (sensitive/reset-malformed-count!))
