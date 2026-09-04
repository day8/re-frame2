(ns re-frame.story-mcp.tools.cursor-result-test
  "Focused unit coverage for the two shared result-shaping helpers the
  Docs `list-*` handlers compose on (rf2-ynjts.20 testing-review).

  The per-tool tests in `tools_test.clj` exercise `rf.story-mcp.tools.cursor/page` /
  `rf.story-mcp.tools.cursor/paged-result` and `rf.story-mcp.tools.result/edn-result` end-to-end through every
  `list-*` handler — but always THROUGH a handler, so the helpers' own
  contracts (the end-of-page nil guard on `encode-cursor`, the
  blank/absent recovery on `decode-cursor`, the dual-slot invariant on
  `edn-result`, and `paged-result`'s metadata-merge) were only asserted
  transitively. This ns pins them directly so a regression in a helper
  surfaces here rather than as a confusing failure three handlers away.

  Deterministic: no registry, no fixtures, no I/O — pure data over the
  public helper surface."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.story-mcp.tools.cursor :as rf.story-mcp.tools.cursor]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]))

(defn- forge-cursor
  "Mint a cursor token from a raw payload WITHOUT going through
  `rf.story-mcp.tools.cursor/encode-cursor` — so a regression test can craft the out-of-
  range payloads (`:offset -1`, an over-total offset) that the public
  encoder would never emit. Mirrors what an agent that hand-edits an
  opaque cursor token would produce on the wire."
  [payload]
  (rf.mcp-base.cursor/b64-encode (pr-str payload)))

(defn- live-sig-for
  "The whole-set fingerprint `rf.story-mcp.tools.cursor/page` will compute for `ids` — read
  it back off a legitimately-minted cursor so a forged-payload test can
  reuse the matching `:sig` (isolating the RANGE gate from the
  fingerprint-drift gate)."
  [entries ids]
  (let [[_ _ m] (rf.story-mcp.tools.cursor/page entries ids {:limit 1} "t")]
    (:sig (rf.story-mcp.tools.cursor/decode-cursor (:next-cursor m)))))

;; ---------------------------------------------------------------------------
;; encode-cursor / decode-cursor round-trip + the end-of-page guard.
;; ---------------------------------------------------------------------------

(deftest encode-cursor-returns-nil-at-end-of-page
  (testing "offset == total ⇒ nil (the absence-of-cursor IS the end signal)"
    (is (nil? (rf.story-mcp.tools.cursor/encode-cursor {:offset 10 :total 10 :sig "abc"}))
        "a fully-consumed set mints no continuation cursor"))
  (testing "offset > total ⇒ nil (defensive — never past the end)"
    (is (nil? (rf.story-mcp.tools.cursor/encode-cursor {:offset 11 :total 10 :sig "abc"}))))
  (testing "offset < total ⇒ a non-blank base64 string"
    (let [c (rf.story-mcp.tools.cursor/encode-cursor {:offset 2 :total 10 :sig "abc"})]
      (is (string? c))
      (is (seq c)))))

(deftest encode-then-decode-round-trips-the-payload
  (testing "a minted cursor decodes back to the {:v 1 :offset :total :sig} shape"
    (let [c (rf.story-mcp.tools.cursor/encode-cursor {:offset 5 :total 40 :sig "sig-xyz"})
          p (rf.story-mcp.tools.cursor/decode-cursor c)]
      (is (= 1 (:v p)) "the codec stamps the payload version")
      (is (= 5 (:offset p)))
      (is (= 40 (:total p)))
      (is (= "sig-xyz" (:sig p))))))

(deftest decode-cursor-absent-and-blank-read-as-nil
  (testing "nil / empty / blank cursor ⇒ nil (no pagination requested)"
    (is (nil? (rf.story-mcp.tools.cursor/decode-cursor nil)))
    (is (nil? (rf.story-mcp.tools.cursor/decode-cursor "")))
    (is (nil? (rf.story-mcp.tools.cursor/decode-cursor "   ")))))

(deftest decode-cursor-malformed-reads-as-malformed-sentinel
  (testing "a non-base64 / wrong-shape cursor decodes to the malformed sentinel, not nil"
    ;; The handler maps the malformed sentinel onto the stale-cursor
    ;; recovery (see `rf.story-mcp.tools.cursor/page`); the helper itself must distinguish
    ;; 'no cursor' (nil) from 'a cursor that won't decode' (malformed) so
    ;; the handler doesn't silently treat garbage as offset-0.
    (let [decoded (rf.story-mcp.tools.cursor/decode-cursor "!!!not-base64!!!")]
      (is (some? decoded) "a malformed cursor is NOT nil — that would silently restart at page 0")
      (is (rf.story-mcp.tools.cursor/decode-cursor "!!!not-base64!!!"))
      (is (= decoded (rf.story-mcp.tools.cursor/decode-cursor "!!!not-base64!!!"))
          "malformed decode is deterministic"))))

;; ---------------------------------------------------------------------------
;; parse-limit-arg clamps into [1, max-limit] with the story default.
;; ---------------------------------------------------------------------------

(deftest parse-limit-arg-clamps-and-defaults
  (testing "absent ⇒ default-limit"
    (is (= rf.story-mcp.tools.cursor/default-limit (rf.story-mcp.tools.cursor/parse-limit-arg nil))))
  (testing "above max ⇒ clamped to max-limit"
    (is (= rf.story-mcp.tools.cursor/max-limit (rf.story-mcp.tools.cursor/parse-limit-arg 99999))))
  (testing "in-range ⇒ rides through"
    (is (= 7 (rf.story-mcp.tools.cursor/parse-limit-arg 7))))
  (testing "below 1 ⇒ clamped up to 1 (a zero/negative page makes no sense)"
    (is (= 1 (rf.story-mcp.tools.cursor/parse-limit-arg 0)))
    (is (= 1 (rf.story-mcp.tools.cursor/parse-limit-arg -5)))))

;; ---------------------------------------------------------------------------
;; rf.story-mcp.tools.cursor/page — the windowing primitive `paged-result` builds on.
;; ---------------------------------------------------------------------------

(deftest page-small-set-no-metadata
  (testing "a set that fits in one page returns [:ok entries {}] — bare wire shape"
    (let [entries [:a :b :c]
          [res page-vec meta] (rf.story-mcp.tools.cursor/page entries entries {} "t")]
      (is (= :ok res))
      (is (= [:a :b :c] page-vec))
      (is (= {} meta) "no pagination metadata when everything fits"))))

(deftest page-over-limit-windows-and-mints-cursor
  (testing "entries > :limit ⇒ first page sliced, metadata + next-cursor present"
    (let [entries (vec (range 10))
          [res page-vec meta] (rf.story-mcp.tools.cursor/page entries entries {:limit 4} "t")]
      (is (= :ok res))
      (is (= [0 1 2 3] page-vec) "first page honours :limit")
      (is (= 10 (:total meta)))
      (is (= 4 (:limit meta)))
      (is (true? (:has-more? meta)))
      (is (string? (:next-cursor meta)))
      ;; Round-trip: the next page picks up at offset 4.
      (let [[_ p2 m2] (rf.story-mcp.tools.cursor/page entries entries {:limit 4 :cursor (:next-cursor meta)} "t")]
        (is (= [4 5 6 7] p2))
        (is (true? (:has-more? m2)))
        ;; Final page: remaining two, no further cursor.
        (let [[_ p3 m3] (rf.story-mcp.tools.cursor/page entries entries {:limit 4 :cursor (:next-cursor m2)} "t")]
          (is (= [8 9] p3))
          (is (false? (:has-more? m3)))
          (is (nil? (:next-cursor m3))))))))

(deftest page-stale-cursor-returns-err
  (testing "a cursor whose fingerprint no longer matches the live id-set ⇒ [:err …]"
    (let [entries (vec (range 5))
          [_ _ meta] (rf.story-mcp.tools.cursor/page entries entries {:limit 2} "list-things")
          ;; Mutate the id-set: the next deref's fingerprint won't match.
          mutated  (conj entries 99)
          [res err-result] (rf.story-mcp.tools.cursor/page mutated mutated {:limit 2 :cursor (:next-cursor meta)} "list-things")]
      (is (= :err res))
      (is (true? (:isError err-result)))
      (is (= :rf.mcp/cursor-stale (-> err-result :structuredContent :reason)))
      (is (= "list-things" (-> err-result :structuredContent :tool))))))

(deftest page-malformed-cursor-returns-err
  (testing "a malformed cursor reads as stale (same drop-and-restart recovery)"
    (let [entries (vec (range 5))
          [res err-result] (rf.story-mcp.tools.cursor/page entries entries {:cursor "garbage!!!"} "list-things")]
      (is (= :err res))
      (is (= :rf.mcp/cursor-stale (-> err-result :structuredContent :reason))))))

;; ---------------------------------------------------------------------------
;; rf2-to3q7 — wire-boundary range gate on the cursor payload.
;;
;; The cursor `:offset` / `:total` are NATURAL integers and `:offset <=
;; :total`. A forged/edited cursor that violates this (negative offset,
;; over-total offset) must decode to the malformed sentinel and recover
;; through the documented cursor-stale envelope — NOT feed `subvec` a
;; bad index (throwing a generic handler exception) or slice an empty
;; window (silently skipping the tail of the registry). These use a
;; matching live `:sig` so the RANGE gate is isolated from the
;; fingerprint-drift gate.
;; ---------------------------------------------------------------------------

(deftest decode-cursor-rejects-negative-offset
  (testing "a forged cursor with :offset -1 decodes to malformed, not a payload"
    (let [forged (forge-cursor {:v 1 :offset -1 :total 5 :sig "any"})]
      (is (rf.story-mcp.tools.cursor/decode-cursor forged)
          "a negative-offset cursor is NOT nil — it must surface as malformed")
      (is (= (rf.story-mcp.tools.cursor/decode-cursor "!!!garbage!!!") (rf.story-mcp.tools.cursor/decode-cursor forged))
          "the negative-offset cursor reads as the SAME malformed sentinel as raw garbage"))))

(deftest decode-cursor-rejects-negative-total
  (testing "a forged cursor with a negative :total decodes to malformed"
    (let [forged (forge-cursor {:v 1 :offset 0 :total -3 :sig "any"})]
      (is (= (rf.story-mcp.tools.cursor/decode-cursor "!!!garbage!!!") (rf.story-mcp.tools.cursor/decode-cursor forged))))))

(deftest decode-cursor-rejects-offset-over-total
  (testing "a forged cursor whose :offset exceeds :total decodes to malformed"
    (let [forged (forge-cursor {:v 1 :offset 99 :total 5 :sig "any"})]
      (is (= (rf.story-mcp.tools.cursor/decode-cursor "!!!garbage!!!") (rf.story-mcp.tools.cursor/decode-cursor forged))))))

(deftest decode-cursor-accepts-offset-equal-to-total
  (testing "offset == total is a VALID position (fully-consumed end-of-list)"
    (let [c (forge-cursor {:v 1 :offset 5 :total 5 :sig "any"})
          p (rf.story-mcp.tools.cursor/decode-cursor c)]
      (is (map? p) "offset == total must NOT be rejected — it is the legitimate end position")
      (is (= 5 (:offset p))))))

(deftest page-negative-offset-cursor-returns-cursor-stale-not-throw
  (testing "a tampered negative-offset cursor recovers via cursor-stale, never throwing into subvec"
    (let [entries (vec (range 5))
          forged  (forge-cursor {:v 1 :offset -1 :total 5 :sig (live-sig-for entries entries)})
          ;; Pre-fix this threw IndexOutOfBoundsException from subvec;
          ;; post-fix the bad offset is rejected at decode and page
          ;; returns the structured stale envelope.
          [res err-result] (rf.story-mcp.tools.cursor/page entries entries {:cursor forged} "list-things")]
      (is (= :err res))
      (is (true? (:isError err-result)))
      (is (= :rf.mcp/cursor-stale (-> err-result :structuredContent :reason))
          "the negative-offset cursor recovers through the documented cursor-stale contract")
      (is (= "list-things" (-> err-result :structuredContent :tool))))))

(deftest page-over-total-offset-cursor-returns-cursor-stale-not-empty-page
  (testing "an over-total offset recovers via cursor-stale rather than silently skipping the tail"
    (let [entries (vec (range 5))
          forged  (forge-cursor {:v 1 :offset 99 :total 5 :sig (live-sig-for entries entries)})
          [res err-result] (rf.story-mcp.tools.cursor/page entries entries {:cursor forged} "list-things")]
      (is (= :err res))
      (is (= :rf.mcp/cursor-stale (-> err-result :structuredContent :reason))
          "an over-total offset must NOT silently return an empty page that loses rows"))))

;; ---------------------------------------------------------------------------
;; paged-result — folds page + the tool-specific payload build + envelope.
;; ---------------------------------------------------------------------------

(deftest paged-result-small-set-merges-bare-payload
  (testing "small set ⇒ the page->payload shape with NO pagination metadata"
    (let [entries [:x :y]
          r       (rf.story-mcp.tools.cursor/paged-result entries entries {} "list-things"
                                       (fn [page] {:things page}))
          s       (:structuredContent r)]
      (is (= [:x :y] (:things s)))
      (is (not (contains? s :total)) "no pagination metadata on a one-page set")
      (is (not (contains? s :next-cursor)))
      ;; edn-result dual-slot invariant holds on the paged path too.
      (is (= (-> r :content first :text) (rf.story-mcp.tools.result/pr-edn s))))))

(deftest paged-result-over-limit-merges-metadata-into-payload
  (testing "large set ⇒ page->payload shape PLUS :total/:limit/:has-more?/:next-cursor"
    (let [entries (vec (range 6))
          r       (rf.story-mcp.tools.cursor/paged-result entries entries {:limit 2} "list-things"
                                       (fn [page] {:things page}))
          s       (:structuredContent r)]
      (is (= [0 1] (:things s)) "the tool-specific slot carries the sliced page")
      (is (= 6 (:total s)))
      (is (= 2 (:limit s)))
      (is (true? (:has-more? s)))
      (is (string? (:next-cursor s))))))

(deftest paged-result-stale-cursor-returns-error-envelope-directly
  (testing "on a stale cursor `paged-result` returns the cursor-stale error result, NOT a payload"
    (let [entries (vec (range 5))
          r1      (rf.story-mcp.tools.cursor/paged-result entries entries {:limit 2} "list-things"
                                       (fn [page] {:things page}))
          cursor  (-> r1 :structuredContent :next-cursor)
          mutated (conj entries 99)
          r2      (rf.story-mcp.tools.cursor/paged-result mutated mutated {:limit 2 :cursor cursor} "list-things"
                                       (fn [page] {:things page}))]
      (is (true? (:isError r2)))
      (is (= :rf.mcp/cursor-stale (-> r2 :structuredContent :reason)))
      (is (not (contains? (:structuredContent r2) :things))
          "the page->payload builder is NOT invoked on the error branch"))))

;; ---------------------------------------------------------------------------
;; rf.story-mcp.tools.result/edn-result + pr-edn — the dual-slot success envelope.
;; ---------------------------------------------------------------------------

(deftest edn-result-dual-codes-both-slots
  (testing "edn-result writes the EDN text AND the raw map; the text == pr-edn of structured"
    (let [payload {:id :story.x/y :tags #{:dev :docs} :n 3}
          r       (rf.story-mcp.tools.result/edn-result payload)]
      (is (= payload (:structuredContent r)) "structured slot is the raw payload")
      (is (= (rf.story-mcp.tools.result/pr-edn payload) (-> r :content first :text))
          "text slot is the pr-edn stringification of the same payload")
      (is (= "text" (-> r :content first :type)))
      (is (not (contains? r :isError)) "success envelope carries no :isError"))))

(deftest pr-edn-is-readable-round-trippable
  (testing "pr-edn output reads back to an = value (keyword keys + sets preserved)"
    (let [payload {:k :v :set #{:a :b} :vec [1 2 3] :nested {:deep "x"}}
          text    (rf.story-mcp.tools.result/pr-edn payload)
          back    (edn/read-string text)]
      (is (= payload back) "EDN round-trips losslessly — no JSON keyword/set lossiness"))))

(deftest error-result-shapes-iserror-envelope
  (testing "error-result carries :isError true + optional structuredContent"
    (let [r (rf.story-mcp.tools.result/error-result "boom" {:rf.error :x/y})]
      (is (true? (:isError r)))
      (is (= "boom" (-> r :content first :text)))
      (is (= {:rf.error :x/y} (:structuredContent r))))
    (testing "single-arity error-result omits structuredContent"
      (let [r (rf.story-mcp.tools.result/error-result "boom")]
        (is (true? (:isError r)))
        (is (not (contains? r :structuredContent)))))))
