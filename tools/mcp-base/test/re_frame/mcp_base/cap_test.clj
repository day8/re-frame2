(ns re-frame.mcp-base.cap-test
  "Unit tests for the cross-MCP cap pipeline.

  Exercises the algorithm via a mock `ResultIO` reifying the protocol
  over CLJ maps. The per-server IO instances (re-frame2-pair-mcp's JS-object
  reify, story-mcp's CLJ-map reify) are exercised against the real
  pipeline in their respective test suites; the unit tests here pin
  the algorithm itself."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.cap :as cap]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; Mock ResultIO — CLJ-map shape, mirrors story-mcp's runtime instance.
;; ---------------------------------------------------------------------------

(def map-io
  "ResultIO over `{:content [{:type \"text\" :text \"...\"} ...]}` maps.
  Mirrors story-mcp's runtime instance; story-mcp's tests exercise the
  full registry-backed pipeline."
  (reify cap/ResultIO
    (wire-payload-strings [_ result]
      (map :text (:content result)))
    (build-overflow-result [_ marker _original]
      {:content          [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

(defn- ok-text-result [v]
  {:content [{:type "text" :text (pr-str v)}]})

(defn- big-string [n]
  (apply str (repeat n "x")))

;; ---------------------------------------------------------------------------
;; max-tokens — per-call cap resolution.
;; ---------------------------------------------------------------------------

(deftest max-tokens-default-when-nil
  (is (= overflow/default-max-tokens (cap/max-tokens nil))))

(deftest max-tokens-zero-disables-cap
  (is (nil? (cap/max-tokens 0))))

(deftest max-tokens-positive-integer-passed-through
  (is (= 100 (cap/max-tokens 100)))
  (is (= 1000 (cap/max-tokens 1000)))
  (is (= 50000 (cap/max-tokens 50000))))

(deftest max-tokens-non-number-falls-back-to-default
  (is (= overflow/default-max-tokens (cap/max-tokens "bogus")))
  (is (= overflow/default-max-tokens (cap/max-tokens :not-a-number)))
  (is (= overflow/default-max-tokens (cap/max-tokens [1 2 3]))))

(deftest max-tokens-only-zero-disables-not-other-numbers
  ;; ONLY a literal `0` disables the cap (returns nil). A NEGATIVE number
  ;; is neither a disable signal nor a passthrough cap — it is REJECTED
  ;; as an out-of-domain arg (see
  ;; `max-tokens-negative-rejected-with-invalid-arg` below). Smallest
  ;; positive passes through unchanged.
  (is (nil? (cap/max-tokens 0)) "0 is the sole disable signal")
  (is (= 1 (cap/max-tokens 1)) "smallest positive passes through")
  (is (cap/invalid-arg? (cap/max-tokens -1))
      "a negative number is NOT a disable signal and NOT a cap — it is rejected"))

(deftest max-tokens-negative-rejected-with-invalid-arg
  ;; A negative `:max-tokens` is REJECTED. Left unguarded it would
  ;; fall through to `(long raw)`, producing a negative cap that
  ;; over-trips `apply-cap`'s `over-cap?` so EVERY response (even a
  ;; 2-char one) would be replaced by the overflow marker — locking the
  ;; agent out of all tool data and emitting a nonsensical
  ;; `:cap-tokens -1`. The resolver returns an `{:rf.mcp/invalid-arg
  ;; {...}}` rejection the consumer surfaces as an `isError: true`
  ;; result.
  (let [out (cap/max-tokens -1)]
    (is (cap/invalid-arg? out)
        "negative max-tokens resolves to an :rf.mcp/invalid-arg rejection, NOT a negative cap")
    (is (not (number? out)) "the rejection is a marker map, not a (negative) cap integer")
    (let [body (get out vocab/invalid-arg-key)]
      (is (= :max-tokens (:arg body)) "rejection names the offending arg")
      (is (= -1 (:value body)) "rejection echoes the rejected value")
      (is (string? (:hint body)) "rejection carries an actionable recovery hint")
      (is (re-find #"(?i)0 disables" (:hint body))
          "hint states the disable sentinel so the agent's next call is correct")))
  (is (cap/invalid-arg? (cap/max-tokens -5)) "larger-magnitude negatives reject too")
  (is (cap/invalid-arg? (cap/max-tokens -1.5)) "negative doubles reject too"))

(deftest max-tokens-fractional-positive-rejected-with-invalid-arg
  ;; A fractional positive in (0,1) — e.g. 0.5 — is neither
  ;; caught by (zero? raw) nor (neg? raw); left unguarded it would fall
  ;; to (long raw), flooring to a REAL 0 cap (non-nil, NOT the disable
  ;; sentinel). That 0 would then over-trip `apply-cap`'s `over-cap?` on
  ;; EVERY non-empty payload, locking the agent out — the same lockout
  ;; class negatives hit. The resolver rejects it honestly as an
  ;; `{:rf.mcp/invalid-arg {...}}` marker rather than flooring to a 0-cap
  ;; lockout.
  (let [out (cap/max-tokens 0.5)]
    (is (cap/invalid-arg? out)
        "fractional positive in (0,1) resolves to an :rf.mcp/invalid-arg rejection, NOT a floored 0 cap")
    (is (not (number? out)) "the rejection is a marker map, not a (0) cap integer")
    (is (not= 0 out)
        "must NOT return a real 0 cap — that is the apply-cap lockout shape, distinct from the nil disable-sentinel")
    (is (some? out)
        "must NOT return nil — nil is the disable-sentinel, a rejection must be distinguishable")
    (let [body (get out vocab/invalid-arg-key)]
      (is (= :max-tokens (:arg body)) "rejection names the offending arg")
      (is (= 0.5 (:value body)) "rejection echoes the rejected value")
      (is (string? (:hint body)) "rejection carries an actionable recovery hint")))
  ;; Other sub-1 positives that would floor to 0 reject identically.
  (is (cap/invalid-arg? (cap/max-tokens 0.1)) "0.1 rejects")
  (is (cap/invalid-arg? (cap/max-tokens 0.999)) "0.999 rejects (would floor to 0)")
  ;; Boundary: exactly 1 (and >= 1 fractionals) are valid usable caps —
  ;; they floor to >= 1, NOT to a 0-cap lockout.
  (is (= 1 (cap/max-tokens 1)) "exactly 1 is the smallest valid cap")
  (is (= 1 (cap/max-tokens 1.0)) "1.0 floors to a usable 1")
  (is (= 2 (cap/max-tokens 2.9)) "2.9 floors to a usable 2 (benign silent floor, still >= 1)"))

(deftest invalid-arg?-predicate-discriminates
  ;; The predicate consumers gate on. True only for the rejection marker;
  ;; false for every valid `max-tokens` return (cap int, nil-disable,
  ;; default) and for unrelated maps.
  (is (cap/invalid-arg? (cap/max-tokens -1)))
  (is (not (cap/invalid-arg? (cap/max-tokens 0))) "nil disable is not a rejection")
  (is (not (cap/invalid-arg? (cap/max-tokens 100))) "a valid cap is not a rejection")
  (is (not (cap/invalid-arg? (cap/max-tokens nil))) "the default is not a rejection")
  (is (not (cap/invalid-arg? {:other :map})) "an unrelated map is not a rejection")
  (is (not (cap/invalid-arg? nil)) "nil is not a rejection"))

(deftest max-tokens-coerces-double-to-long
  (is (= 1000 (cap/max-tokens 1000.0)))
  (is (integer? (cap/max-tokens 1000.0))))

(deftest max-tokens-non-finite-out-of-range-rejected-not-crash-not-0-cap
  ;; `(long raw)` is UNSAFE on non-finite / out-of-range
  ;; numerics. On the JVM `##Inf` and `1.0E20` THROW
  ;; IllegalArgumentException (a crash at the wire boundary), and `##NaN`
  ;; truncates to a real `0` cap — the same 0-cap lockout shape negatives
  ;; and fractionals hit. The resolver rejects each honestly as an
  ;; {:rf.mcp/invalid-arg} marker, the recoverable cross-runtime posture.
  (doseq [[label raw] [["##Inf" ##Inf]
                       ["##NaN" ##NaN]
                       ["1.0E20" 1.0E20]
                       ["-1.0E20" -1.0E20]]]
    (let [out (cap/max-tokens raw)]
      (is (cap/invalid-arg? out)
          (str label " resolves to an :rf.mcp/invalid-arg rejection, not a crash / cap"))
      (is (not (number? out))
          (str label " is NOT a (real) cap integer"))
      (is (not= 0 out)
          (str label " must NOT be a real 0 cap (the apply-cap lockout shape)"))
      (is (some? out)
          (str label " must NOT be nil (nil is the disable sentinel, distinct from a rejection)"))))
  ;; ##-Inf is caught earlier by the (neg? raw) arm — still a rejection.
  (is (cap/invalid-arg? (cap/max-tokens ##-Inf)) "##-Inf rejects (via the negative arm)")
  ;; The legitimate surface is untouched.
  (is (= 5000 (cap/max-tokens 5000)) "in-range cap still passes through")
  (is (= 9007199254740991 (cap/max-tokens 9007199254740991))
      "the safe-integer ceiling itself is an in-domain cap"))

;; ---------------------------------------------------------------------------
;; sum-payload-tokens — sums every :text slot via ResultIO.
;; ---------------------------------------------------------------------------

(deftest sum-payload-tokens-single-slot
  (let [r (ok-text-result {:hello "world"})]
    (is (pos? (cap/sum-payload-tokens map-io r)))
    (is (= (overflow/token-estimate (pr-str {:hello "world"}))
           (cap/sum-payload-tokens map-io r)))))

(deftest sum-payload-tokens-empty-content-is-zero
  (is (zero? (cap/sum-payload-tokens map-io {:content []})))
  (is (zero? (cap/sum-payload-tokens map-io {:content nil}))))

(deftest sum-payload-tokens-aggregates-across-slots
  (let [r {:content [{:type "text" :text (big-string 4000)}
                     {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (cap/sum-payload-tokens map-io r)))))

(deftest sum-payload-tokens-skips-non-string-slots
  (let [r {:content [{:type "text" :text (big-string 4000)}
                     {:type "image"}
                     {:type "text"}]}]
    (is (= 1000 (cap/sum-payload-tokens map-io r)))))

;; ---------------------------------------------------------------------------
;; apply-cap — the strategy entry point.
;; ---------------------------------------------------------------------------

(deftest apply-cap-passes-under-budget-payload-untouched
  (let [r   (ok-text-result {:small :payload})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap overflow/default-max-tokens})]
    (is (identical? r out))))

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r   (ok-text-result {:k (big-string 100000)})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap nil})]
    (is (identical? r out))))

(deftest apply-cap-nil-result-passes-through
  (is (nil? (cap/apply-cap map-io nil {:tool "snapshot" :cap 5000}))))

(deftest apply-cap-over-budget-emits-overflow-marker
  (let [big (big-string 4000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap 500 :hint "narrow scope"})
        marker (:structuredContent out)
        body   (get marker vocab/overflow-key)]
    (is (contains? marker vocab/overflow-key))
    (is (= :reached (:limit body)))
    (is (= "snapshot" (:tool body)))
    (is (= 500 (:cap-tokens body)))
    (is (pos? (:token-count body)))
    (is (> (:token-count body) 500))
    (is (= "narrow scope" (:hint body)))))

(deftest apply-cap-overflow-payload-is-itself-under-cap
  (let [big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap 500})]
    (is (<= (cap/sum-payload-tokens map-io out) 500)
        "The overflow marker itself must be under the cap")))

(deftest apply-cap-absent-hint-uses-fallback
  (let [big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "no-such-tool" :cap 500})
        body (get-in out [:structuredContent vocab/overflow-key])]
    (is (= overflow/overflow-hint-fallback (:hint body)))))

(deftest apply-cap-at-cap-exact-boundary-passes
  ;; <= cap passes; only > cap trips. Boundary check pins inclusive-low.
  (let [s    (big-string 400)
        r    (ok-text-result s)
        toks (cap/sum-payload-tokens map-io r)
        out  (cap/apply-cap map-io r {:tool "snapshot" :cap toks})]
    (is (identical? r out))))

(deftest apply-cap-uses-result-io-build-fn
  ;; Verify the build-overflow-result hook is what produces the new
  ;; result — a custom IO can shape the result however it likes.
  (let [marker-only-io (reify cap/ResultIO
                         (wire-payload-strings [_ result] (map :text (:content result)))
                         (build-overflow-result [_ marker _]
                           {::custom-shape true :marker marker}))
        big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap marker-only-io r {:tool "snapshot" :cap 500})]
    (is (true? (::custom-shape out))
        "build-overflow-result is the sole producer of the over-cap shape")
    (is (contains? (:marker out) vocab/overflow-key))))

;; ---------------------------------------------------------------------------
;; Secondary char-byte cap — defence in depth against the
;; `(quot count 4)` token undercount on CJK / emoji / base64 / dense
;; code. `sum-payload-tokens` divides by 4; a payload where 1 char ≈ 2-3
;; tokens still trips the cap because the secondary char check uses
;; `cap * byte-cap-multiplier`.
;; ---------------------------------------------------------------------------

(deftest sum-payload-chars-aggregates-across-slots
  (let [r {:content [{:type "text" :text (big-string 1000)}
                     {:type "text" :text (big-string 2000)}]}]
    (is (= 3000 (cap/sum-payload-chars map-io r)))))

(deftest sum-payload-chars-empty-content-is-zero
  (is (zero? (cap/sum-payload-chars map-io {:content []})))
  (is (zero? (cap/sum-payload-chars map-io {:content nil}))))

(deftest byte-cap-multiplier-pinned-at-8x
  ;; The multiplier is part of the cap contract — call out a change.
  (is (= 8 cap/byte-cap-multiplier)))

(deftest apply-cap-cjk-payload-over-budget-tripped
  ;; CJK / emoji / base64 payloads pass through the same `(quot c 4)`
  ;; rule but each glyph carries ~2-3 tokens on a real tokenizer ⇒
  ;; the heuristic under-reports. The cap MUST still trip over-budget
  ;; CJK content under the current rule (the heuristic is not exact,
  ;; but the absolute count grows with input size). Regression pin:
  ;; a CJK payload of 5000 glyphs at cap=100 trips overflow.
  (let [big-cjk (apply str (repeat 5000 \日))
        r       {:content [{:type "text" :text big-cjk}]}
        out     (cap/apply-cap map-io r {:tool "cjk-test" :cap 100})
        body    (get-in out [:structuredContent vocab/overflow-key])]
    (is (= :reached (:limit body)))
    (is (pos? (:token-count body)))))

(deftest byte-cap-multiplier-and-char-sum-are-pinned
  ;; Defence-in-depth shape pin. The
  ;; secondary byte cap is `cap * byte-cap-multiplier` and reads from
  ;; the same `wire-payload-strings` seq the token sum does.
  ;;
  ;; Pin the two shape invariants — `byte-cap-multiplier = 8` and
  ;; `sum-payload-chars` and `sum-payload-tokens` read the same content-
  ;; texts seq.
  (is (= 8 cap/byte-cap-multiplier))
  (let [r {:content [{:type "text" :text (big-string 100)}]}]
    (is (= 100 (cap/sum-payload-chars map-io r)))
    (is (= 25 (cap/sum-payload-tokens map-io r)))))

;; ---------------------------------------------------------------------------
;; Two-stage gate, unit-trippable in isolation + reachable through the
;; live `apply-cap` path.
;;
;; The `over-cap?` / `reported-count` predicates are extracted as pure fns
;; over already-summed tokens/chars so the secondary char gate is
;; trippable in ISOLATION (feed decoupled sums directly), independent of
;; how `apply-cap` happens to derive the sums.
;;
;; The char gate IS genuinely reachable through `apply-cap`:
;; `token-estimate` floors PER STRING, so `Σ (quot len_i 4)` collapses
;; toward 0 for a content vector of many sub-4-char slots while the char
;; sum stays large. `apply-cap-many-short-strings-trips-char-gate` below
;; exercises the char-gated arm THROUGH the live `apply-cap` path — no
;; custom-sum trick, just a realistic many-small-slots payload (the
;; `watch-epochs` / `trace-window` slice shape). The isolation tests
;; remain valuable (they pin both disjuncts crisply); the char gate is
;; load-bearing, not merely future defence-in-depth.
;; ---------------------------------------------------------------------------

(deftest over-cap?-primary-token-gate-trips
  ;; The common path: token sum exceeds cap, chars well under byte-cap.
  (is (true?  (cap/over-cap? 5001 6000 5000)) "tokens > cap trips")
  (is (false? (cap/over-cap? 5000 6000 5000)) "tokens = cap does NOT trip (inclusive-low)")
  (is (false? (cap/over-cap? 4999 6000 5000)) "tokens < cap does NOT trip"))

(deftest over-cap?-secondary-char-gate-trips-in-isolation
  ;; The branch the live `apply-cap` path cannot reach: tokens UNDER
  ;; cap but chars OVER `cap * byte-cap-multiplier`. Reachable only when
  ;; a future token rule decouples chars from tokens (e.g. base64 / CJK
  ;; recognised at a lower per-char token cost). `over-cap?` MUST still
  ;; trip — that is the whole point of the defence-in-depth gate.
  (let [cap-tokens 100
        byte-cap   (* cap-tokens cap/byte-cap-multiplier)] ;; 800
    (is (true? (cap/over-cap? 50 (inc byte-cap) cap-tokens))
        "tokens under cap but chars > cap*8 ⇒ secondary gate trips")
    (is (false? (cap/over-cap? 50 byte-cap cap-tokens))
        "chars = cap*8 does NOT trip (strict >)")
    (is (false? (cap/over-cap? 50 (dec byte-cap) cap-tokens))
        "chars < cap*8 and tokens under cap ⇒ no trip")))

(deftest reported-count-selects-chars-when-char-gate-tripped
  ;; The `reported = (if (> chars byte-cap) chars tokens)` selector. The
  ;; chars arm is the one the live path never reaches; pin both arms.
  (let [cap-tokens 100
        byte-cap   (* cap-tokens cap/byte-cap-multiplier)] ;; 800
    (is (= 999 (cap/reported-count 50 999 cap-tokens))
        "char gate tripped (999 > 800) ⇒ report the char count")
    (is (= 50 (cap/reported-count 50 800 cap-tokens))
        "char gate NOT tripped (800 = byte-cap, not >) ⇒ report tokens")
    (is (= 150 (cap/reported-count 150 700 cap-tokens))
        "only the token gate tripped ⇒ report tokens")))

(deftest over-cap?-and-reported-count-agree-with-apply-cap
  ;; Consistency pin: the extracted predicates are exactly what
  ;; `apply-cap` uses. A token-gated over-budget response reports the
  ;; token count via `reported-count`, and `apply-cap`'s marker carries
  ;; the same number. (The char-gate arm IS reachable through the live
  ;; `apply-cap` path too — see
  ;; `apply-cap-many-short-strings-trips-char-gate` — but this case is a
  ;; single big string, so the token gate is the one that trips.)
  (let [big (big-string 4000)        ;; 4000 chars ⇒ 1000 tokens
        r   (ok-text-result {:huge big})
        cap-tokens 500
        toks (cap/sum-payload-tokens map-io r)
        chrs (cap/sum-payload-chars map-io r)
        out  (cap/apply-cap map-io r {:tool "snapshot" :cap cap-tokens})
        body (get-in out [:structuredContent vocab/overflow-key])]
    (is (true? (cap/over-cap? toks chrs cap-tokens)))
    (is (= (cap/reported-count toks chrs cap-tokens) (:token-count body))
        "apply-cap's reported :token-count matches reported-count over the same sums")))

(deftest apply-cap-many-short-strings-trips-char-gate
  ;; Regression pin: the secondary char gate is reachable through the
  ;; LIVE `apply-cap` path, not merely in isolation.
  ;;
  ;; `token-estimate` floors PER STRING: 3000 slots of 3 chars each give
  ;; `tokens = Σ (quot 3 4) = 0` while `chars = 9000`. With `cap = 1` the
  ;; primary token gate is QUIET (`0 > 1` is false) — only the secondary
  ;; char gate (`9000 > 1*8`) trips. This is the `watch-epochs` /
  ;; `trace-window` slice shape: a long vector of small text slots.
  (let [r        {:content (vec (repeat 3000 {:type "text" :text "xxx"}))}
        cap-toks 1]
    ;; Confirm the precondition that makes this the SOLE char-gate trip.
    (is (zero? (cap/sum-payload-tokens map-io r))
        "many sub-4-char slots ⇒ token sum floors to 0 (token gate quiet)")
    (is (= 9000 (cap/sum-payload-chars map-io r))
        "char sum is large — only the secondary gate can trip")
    (is (false? (> (cap/sum-payload-tokens map-io r) cap-toks))
        "primary token gate does NOT trip on its own")
    (let [out  (cap/apply-cap map-io r {:tool "trace-window" :cap cap-toks})
          body (get-in out [:structuredContent vocab/overflow-key])]
      (is (contains? (:structuredContent out) vocab/overflow-key)
          "live apply-cap replaces the payload via the secondary char gate")
      (is (= :reached (:limit body)))
      (is (= 9000 (:token-count body))
          "reported :token-count is the CHAR count — the char-gated arm of reported-count, reached live")
      (is (= cap-toks (:cap-tokens body))))))

;; ---------------------------------------------------------------------------
;; structuredContent counted toward the budget — story-mcp
;; pattern: its reify surfaces `:structuredContent` as one extra
;; `pr-str`-ed string in `wire-payload-strings`. Pin the contract via a
;; mirror reify here.
;; ---------------------------------------------------------------------------

(def structured-io
  "Mirrors story-mcp's runtime reify: `wire-payload-strings` surfaces both
  the `:content[*].text` slots and a `pr-str`'d `:structuredContent`
  payload. This is the cross-MCP convention pin — a consumer that
  duplicates a payload into `:structuredContent` MUST count both
  copies."
  (reify cap/ResultIO
    (wire-payload-strings [_ result]
      (cond-> (mapv :text (:content result))
        (some? (:structuredContent result))
        (conj (pr-str (:structuredContent result)))))
    (build-overflow-result [_ marker _original]
      {:content          [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

(deftest structured-content-counted-toward-budget
  ;; A response where the `:content[*].text` slot is small but
  ;; `:structuredContent` is large. The cap MUST trip — `:structuredContent`
  ;; rides the wire and counts toward the budget when the consumer's
  ;; reify surfaces it via `wire-payload-strings`.
  (let [small-text "ok"
        huge       {:big-payload (big-string 30000)}
        r          {:content          [{:type "text" :text small-text}]
                    :structuredContent huge}
        out        (cap/apply-cap structured-io r {:tool "snapshot" :cap 1000})
        marker     (:structuredContent out)]
    (is (contains? marker vocab/overflow-key)
        "structuredContent payload MUST count toward the cap")))

(deftest structured-content-under-budget-passes
  (let [r {:content          [{:type "text" :text "ok"}]
           :structuredContent {:small :payload}}
        out (cap/apply-cap structured-io r {:tool "snapshot" :cap 5000})]
    (is (identical? r out))))
