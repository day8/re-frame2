(ns re-frame.story-mcp.tools.cursor-result-test
  "Focused unit coverage for the shared result-shaping helpers the
  Docs `list-*` handlers compose on.

  Paging itself (windows, cursor round trips, stale cursors, pagination
  metadata) is pinned end-to-end through the `list-*` handlers in
  `tools_test.clj`. This ns pins what no handler call reaches: the
  default and ceiling story-mcp bakes into `parse-limit-arg`, the payload
  range gate on `decode-cursor` (exercised with forged cursors the encoder
  never mints), the dual-slot invariant on `edn-result`, and
  `error-result`'s envelope.

  Deterministic: no registry, no fixtures, no I/O — pure data over the
  public helper surface."
  (:require [clojure.test :refer [deftest is testing]]
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
;; parse-limit-arg clamps into [1, max-limit] with the story default. The
;; clamp itself is mcp-base's and is pinned there; this pins the two numbers
;; story-mcp bakes in.
;; ---------------------------------------------------------------------------

(deftest parse-limit-arg-clamps-and-defaults
  (testing "absent ⇒ default-limit"
    (is (= rf.story-mcp.tools.cursor/default-limit (rf.story-mcp.tools.cursor/parse-limit-arg nil))))
  (testing "above max ⇒ clamped to max-limit"
    (is (= rf.story-mcp.tools.cursor/max-limit (rf.story-mcp.tools.cursor/parse-limit-arg 99999)))))

;; ---------------------------------------------------------------------------
;; Wire-boundary range gate on the cursor payload.
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
          ;; Unchecked, this offset would throw IndexOutOfBoundsException
          ;; from subvec; the bad offset is rejected at decode and page
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
;; rf.story-mcp.tools.result/edn-result + error-result — the result envelopes.
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
