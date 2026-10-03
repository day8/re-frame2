(ns re-frame2-pair-mcp.cursor-pagination-test
  "Unit tests for the cursor-pagination mechanism on `trace-window`
  and `watch-epochs`.

  Both tools accept `:limit` (int, default 50) + `:cursor` (opaque
  string). Responses carry `:next-cursor`, `:has-more?` and
  `:estimated-remaining`. A cursor whose epoch-id has aged out of the
  runtime ring surfaces as a `:rf.mcp/cursor-stale` error rather than
  silently restarting.

  These tests pin the private helpers in
  `re-frame2-pair-mcp.tools.cursor` directly (`parse-limit-arg`,
  `encode-cursor`, `decode-cursor`, `cursor-stale-result`), the sticky
  `:pred` a watch-epochs cursor carries, and each handler's rejection of
  a noncanonical cursor. The paging itself runs inside the runtime form,
  behind the nREPL eval boundary, and is covered by the stdio-roundtrip
  harness; this layer pins the cursor contract."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame2-pair-mcp.tools.watch-epochs :as we]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(def default-limit cursor/default-limit)

;; The `decode-cursor` impl returns the malformed sentinel keyword
;; from the source ns; tests assert against that same value.
(def malformed :re-frame2-pair-mcp.tools.cursor/malformed)

;; ---------------------------------------------------------------------------
;; parse-limit-arg — MCP-arg normalisation.
;; ---------------------------------------------------------------------------

(deftest parse-limit-arg-resolution
  (doseq [[input expected note]
          [[nil default-limit "absent ⇒ the default"]
           [js/undefined default-limit "undefined ⇒ the default"]
           [10 10 "a positive integer passes through"]
           [1 1 "the smallest positive integer passes through"]
           [1000 1000 "a large positive integer passes through"]
           [0 1 "zero clamps to one"]
           [-5 1 "a negative clamps to one"]
           ["25" 25 "a numeric string parses"]
           ["bogus" default-limit "a non-numeric string falls back to the default"]]]
    (is (= expected (cursor/parse-limit-arg input)) note)))

;; ---------------------------------------------------------------------------
;; encode-cursor / decode-cursor — opaque round-trip.
;; ---------------------------------------------------------------------------

(deftest encode-cursor-nil-when-no-after-id
  (is (nil? (cursor/encode-cursor nil)))
  (is (nil? (cursor/encode-cursor {})))
  (is (nil? (cursor/encode-cursor {:v 1 :after-id nil}))))

(deftest cursor-round-trips-every-after-id-shape
  ;; The reference epoch runtime (`re-frame/epoch/state.cljc`
  ;; `next-epoch-id` = `(swap! counter inc)`) emits INTEGER epoch-ids, and
  ;; Spec-Schemas declares `:epoch-id` as `:any`. `decode-cursor` must
  ;; accept any `:after-id` shape: an integer-bearing second-page cursor
  ;; round-trips intact rather than decoding as `::malformed` and tripping
  ;; a spurious `:rf.mcp/cursor-stale`, and encode's "is there an
  ;; after-id?" guard is `some?`, not `string?` — integer 0 included. The
  ;; rows use REAL integer ids, not synthesised strings, so a regression
  ;; to a `string?` guard fails here; equality also proves an integer id
  ;; is not coerced to a string.
  (doseq [payload [{:v 1 :after-id "epoch-42" :ms 5000 :until-ms 1234567890
                    :frame :rf/default}
                   {:v 1 :after-id 7 :ms 1000 :until-ms 1234567890 :frame :rf/default}
                   {:v 1 :after-id 0}
                   {:v 1 :after-id :ev/login :ms nil :until-ms nil :frame nil}]]
    (is (= payload (cursor/decode-cursor (cursor/encode-cursor payload)))
        (str "after-id " (pr-str (:after-id payload)) " round-trips losslessly"))))

(deftest decode-cursor-nil-on-nil-input
  (is (nil? (cursor/decode-cursor nil)))
  (is (nil? (cursor/decode-cursor "")))
  (is (nil? (cursor/decode-cursor js/undefined))))

(deftest decode-cursor-malformed-on-junk
  (is (= malformed (cursor/decode-cursor "not-real-base64-edn-juzlblahHFGYbn")))
  (is (= malformed (cursor/decode-cursor 12345)))
  ;; base64 of "not-a-map" — decodes but isn't a map with :after-id
  (let [bogus (.toString (js/Buffer.from "[1 2 3]" "utf8") "base64")]
    (is (= malformed (cursor/decode-cursor bogus)))))

(deftest decode-cursor-malformed-on-missing-after-id
  (let [bogus (.toString (js/Buffer.from "{:v 1}" "utf8") "base64")]
    (is (= malformed (cursor/decode-cursor bogus)))))

(deftest cursor-is-opaque-on-wire
  ;; The agent has no business decoding the cursor — but the encoding
  ;; MUST be a self-contained string (no embedded JSON-confusing chars
  ;; that would break round-trip through bencode + JSON-RPC).
  (let [c (cursor/encode-cursor {:v 1 :after-id "abc-def"})]
    (is (re-matches #"^[A-Za-z0-9+/=]+$" c))))

;; ---------------------------------------------------------------------------
;; watch-epochs carries the sticky :pred in the continuation cursor.
;;
;; `watch-epochs`'s first-call `:next-cursor` encodes :after-id / :frame
;; AND the predicate. An agent paginating via the documented opaque-cursor
;; flow (pass back JUST `:cursor`, no `:pred`) keeps both the sticky frame
;; and the predicate, so page 2+ runs `epoch-matches?` with the original
;; predicate rather than degrading to `{}` (MATCH-ALL) — which would
;; return every epoch after the watermark unfiltered, with an envelope
;; identical to a correctly-filtered page so the agent couldn't detect it.
;;
;; These pins exercise BOTH ends of the round-trip:
;;   1. The first-call `:next-cursor` actually carries the predicate.
;;   2. A page-2 call with ONLY `:cursor` (no `:pred` arg) still emits a
;;      filtering `epoch-matches?` form — the sticky pred from the cursor,
;;      NOT the match-all `{}`.
;; ---------------------------------------------------------------------------

(defn- with-form-capture!
  "Install a `nrepl/cljs-eval-value` stub that answers the runtime
  preload-probe with `true` and the real epoch form with `canned`,
  capturing the NON-probe (epoch) form-strings into `forms-atom`.
  Restores the original in `.finally`. Mirrors the substr-matching
  installer in `watch_epochs_test`; here we additionally need the form
  string, not just the value. The probe form is identified by the
  `__re_frame2_pair_runtime` marker so it is neither captured nor
  answered with the matches map (which `runtime-preloaded?` would read
  as a failed probe)."
  [forms-atom canned body-fn]
  (let [orig  nrepl/cljs-eval-value
        probe? (fn [form-str]
                 (and (string? form-str)
                      (re-find #"__re_frame2_pair_runtime" form-str)))
        answer (fn [form-str]
                 (if (probe? form-str)
                   true
                   (do (swap! forms-atom conj form-str)
                       canned)))
        stub  (fn
                ([_conn _build-id form-str]
                 (js/Promise.resolve (answer form-str)))
                ([_conn _build-id form-str _opts]
                 (js/Promise.resolve (answer form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest watch-epochs-next-cursor-carries-pred
  (testing "first-call :next-cursor round-trips the :pred so page 2 can resume filtered"
    (async done
      (let [forms (atom [])
            ;; Runtime form output: a page with a :next-id so a
            ;; continuation cursor is minted.
            canned {:matches       [{:epoch-id :e1}]
                    :id-aged-out?  false
                    :requested-id  nil
                    :head-id       :e2
                    :next-id       :e1
                    :history-count 5
                    :since-count   5
                    :remaining     4}]
        (-> (with-form-capture! forms canned
              (fn []
                (-> (we/watch-epochs-tool
                      nil
                      (tu/args->js {:pred #js {:event-id ":ev/login"} :limit 1}))
                    (.then (fn [result]
                             (let [edn         (tu/extract-edn result)
                                   next-cursor (:next-cursor edn)
                                   decoded     (cursor/decode-cursor next-cursor)]
                               (is (some? next-cursor) "a continuation cursor was minted")
                               ;; `js->clj :keywordize-keys true` keywordizes
                               ;; the KEYS only — the pred VALUE rides as the
                               ;; JSON string it arrived as (the runtime side
                               ;; coerces). The cursor round-trips it verbatim.
                               (is (= {:event-id ":ev/login"} (:pred decoded))
                                   "the cursor carries the first-call predicate verbatim")
                               (is (= :e1 (:after-id decoded)))
                               (done))))))))))))

(deftest watch-epochs-page-2-with-only-cursor-still-filters
  (testing "page-2 call with ONLY :cursor (no :pred arg) emits a filtering epoch-matches? form, NOT match-all"
    (async done
      (let [forms (atom [])
            ;; A cursor as it would have been minted on page 1, carrying
            ;; the sticky predicate (and frame/after-id). This is exactly
            ;; what the agent passes back verbatim.
            page-1-cursor (cursor/encode-cursor
                            {:v 1 :after-id :e1 :ms nil :until-ms nil
                             :frame :rf/default
                             :pred {:event-id :ev/login}})
            canned {:matches       []
                    :id-aged-out?  false
                    :requested-id  :e1
                    :head-id       :e9
                    :next-id       nil
                    :history-count 9
                    :since-count   4
                    :remaining     0}]
        (-> (with-form-capture! forms canned
              (fn []
                ;; The continuation call passes ONLY :cursor — no :pred,
                ;; no :frame, no :since-id. The documented opaque-cursor
                ;; flow.
                (-> (we/watch-epochs-tool nil (tu/args->js {:cursor page-1-cursor}))
                    (.then (fn [_result]
                             (let [form-str (first @forms)]
                               (is (some? form-str) "a runtime form was emitted")
                               ;; The emitted epoch-matches? call carries
                               ;; the sticky predicate from the cursor —
                               ;; QUOTED, since a cursor is caller data.
                               (is (re-find #"epoch-matches\? \(quote \{:event-id :ev/login\}\)"
                                            form-str)
                                   "page-2 form filters by the sticky pred from the cursor")
                               ;; And NOT the match-all empty map.
                               (is (not (re-find #"epoch-matches\? \(quote \{\}\)" form-str))
                                   "page-2 form must NOT degrade to match-all {}")
                               (done))))))))))))

;; ---------------------------------------------------------------------------
;; Handler-level noncanonical-cursor rejection — the END-TO-END counterpart
;; to the shared mcp-base codec pin.
;;
;; The mcp-base codec rejects a NONCANONICAL Base64 cursor alias: a
;; re-spelled token that decodes to the SAME logical cursor on the
;; host-lenient decoder — `js/Buffer` silently DROPS non-alphabet chars,
;; and both hosts ignore non-zero trailing pad bits — but is NOT the
;; canonical spelling `b64-encode` emits. `decode-canonical-b64` re-encodes
;; the decoded bytes and demands token equality, so every alias fails BEFORE
;; EDN parsing. `mcp-base`'s `cursor_test` / `cljs_branches_cljs_test` pin
;; that at the `decode-cursor` UNIT boundary.
;;
;; This suite is the pair-mcp HANDLER counterpart AC#5 asked for: a
;; noncanonical alias driven through the ACTUAL paginated tool handler
;; (`watch-epochs` / `trace-window`) must surface the structured
;; `:rf.mcp/cursor-stale` rejection rather than silently continue pagination
;; from the alias's decoded position. The handler decodes the cursor BEFORE
;; any runtime eval (so a nil conn reaches the rejection), translating the
;; codec's `::malformed` into the same cursor-stale envelope a real
;; ring-rotation age-out produces. These pins exercise that handler path,
;; not the codec unit.
;; ---------------------------------------------------------------------------

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

;; A real, canonical pair cursor payload — an INTEGER `:after-id`, exactly
;; the shape the reference epoch runtime emits (`(swap! counter inc)`), so
;; every alias below is built from a token a genuine second-page
;; continuation would actually carry.
(def ^:private canonical-payload
  {:v 1 :after-id 9 :ms 1000 :until-ms 1234567890 :frame :rf/default})

(defn- inserted-char-alias
  "A GUARANTEED noncanonical alias: splice two non-alphabet chars into the
  canonical `token`. `js/Buffer` DROPS them on decode — the exact
  host-lenient family (`ez!!p2…`) — so the alias
  decodes to the SAME bytes (thus the SAME logical cursor) under a DIFFERENT
  wire string. No pad-slack dependency: it always exists. Mirrors the
  construction the mcp-base CLJS suite proves rejected at the codec unit."
  [token]
  (str (subs token 0 2) "!!" (subs token 2)))

(defn- pad-bit-alias
  "A noncanonical alias of canonical `token` differing ONLY in the trailing
  pad bits (same decoded bytes, DIFFERENT spelling), or nil if `token` has
  no pad slack. Both hosts ignore non-zero pad bits, so the alias decodes to
  IDENTICAL bytes — the family a lexical alphabet/padding grammar admits but
  the round-trip-equality gate rejects. Verbatim mirror of the mcp-base
  cursor suites' helper; here it feeds the HANDLER, not the codec unit."
  [token]
  (let [decoded (rf.mcp-base.cursor/b64-decode token)
        i       (dec (count (re-find #"[^=]+" token)))
        orig    (nth token i)]
    (some (fn [c]
            (when (not= c orig)
              (let [cand (str (subs token 0 i) c (subs token (inc i)))]
                (when (= decoded (rf.mcp-base.cursor/b64-decode cand)) cand))))
          b64-alphabet)))

(defn- assert-true-alias!
  "Precondition: `alias` is a GENUINE noncanonical alias of `canonical` — a
  DIFFERENT wire string that decodes (on the raw host-lenient codec) to the
  SAME bytes, i.e. the SAME logical cursor. Proves the token under test is a
  real alias that a naive host decoder would ACCEPT, not mere garbage the
  handler would reject for unrelated reasons."
  [alias canonical]
  (is (some? alias) "the alias exists")
  (is (not= alias canonical) "the alias is a DIFFERENT wire string than canonical")
  (is (= (rf.mcp-base.cursor/b64-decode alias) (rf.mcp-base.cursor/b64-decode canonical))
      "the alias decodes to the SAME bytes — a true logical alias, not garbage"))

(defn- assert-cursor-stale-envelope!
  "Assert an MCP result envelope carries the canonical cursor-rejection
  shape: `:isError true` on the wire, and an EDN payload with
  `{:ok? false :reason :rf.mcp/cursor-stale :tool <tool>}` — the same
  structured recovery signal a real ring-rotation age-out surfaces."
  [result tool]
  (is (tu/error? result) "the envelope is flagged :isError on the wire")
  (let [edn (tu/extract-edn result)]
    (is (false? (:ok? edn)) ":ok? is false")
    (is (= rf.mcp-base.vocab/cursor-stale-reason (:reason edn))
        ":reason is the cross-MCP :rf.mcp/cursor-stale")
    (is (= tool (:tool edn)) ":tool names the rejecting paginated tool")))

(deftest handler-rejects-inserted-char-cursor-alias-watch-epochs
  (testing "a noncanonical (inserted-char) cursor alias driven through watch-epochs-tool is REJECTED as cursor-stale, not resumed from its decoded position"
    (async done
      (let [canonical (cursor/encode-cursor canonical-payload)
            alias     (inserted-char-alias canonical)]
        (assert-true-alias! alias canonical)
        (-> (we/watch-epochs-tool nil (tu/args->js {:cursor alias}))
            (.then (fn [result]
                     (assert-cursor-stale-envelope! result "watch-epochs")
                     (done))))))))

(deftest handler-rejects-inserted-char-cursor-alias-trace-window
  (testing "the same inserted-char alias driven through trace-window-tool is REJECTED as cursor-stale"
    (async done
      (let [canonical (cursor/encode-cursor canonical-payload)
            alias     (inserted-char-alias canonical)]
        (assert-true-alias! alias canonical)
        (-> (tw/trace-window-tool nil (tu/args->js {:cursor alias}))
            (.then (fn [result]
                     (assert-cursor-stale-envelope! result "trace-window")
                     (done))))))))

(deftest handler-rejects-pad-bit-cursor-alias-watch-epochs
  ;; The pad-bit family is the one a lexical alphabet/padding grammar would
  ;; ADMIT and the old JVM decoder ACCEPTED (java.util.Base64 ignores pad
  ;; bits) — only the decode→re-encode round-trip gate rejects it. Driving
  ;; it end-to-end through the handler pins that the pair inherits the gate.
  (testing "a noncanonical PAD-BIT cursor alias driven through watch-epochs-tool is REJECTED as cursor-stale"
    (async done
      (let [canonical (cursor/encode-cursor canonical-payload)
            alias     (pad-bit-alias canonical)]
        (assert-true-alias! alias canonical)
        (-> (we/watch-epochs-tool nil (tu/args->js {:cursor alias}))
            (.then (fn [result]
                     (assert-cursor-stale-envelope! result "watch-epochs")
                     (done))))))))

(deftest handler-accepts-canonical-cursor-of-same-position-watch-epochs
  ;; TEETH — the rejection is SPECIFIC to the noncanonical spelling, not a
  ;; blanket "reject every cursor". The CANONICAL token for the SAME logical
  ;; position (the identical decoded payload the aliases above carry) is NOT
  ;; rejected: it drives past the cursor gate into the runtime eval and
  ;; returns an ok page. Were the handler rejecting everything (or accepting
  ;; aliases indiscriminately), this control would move in lock-step with
  ;; the rejection tests and expose a hollow regression. Uses the probe-aware
  ;; `with-form-capture!` stub so the post-gate runtime eval is hermetic.
  (testing "the canonical cursor of the same position is accepted end-to-end (NOT cursor-stale)"
    (async done
      (let [forms     (atom [])
            canonical (cursor/encode-cursor canonical-payload)
            ;; A live, non-aged-out continuation: `:id-aged-out? false` keeps
            ;; the handler off BOTH the pre-eval malformed path and the
            ;; post-eval age-out path, so reaching an ok page proves the
            ;; canonical cursor passed the gate cleanly.
            canned    {:matches       []
                       :id-aged-out?  false
                       :requested-id  9
                       :head-id       12
                       :next-id       nil
                       :history-count 3
                       :since-count   1
                       :remaining     0}]
        (-> (with-form-capture! forms canned
              (fn []
                (-> (we/watch-epochs-tool nil (tu/args->js {:cursor canonical}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (not (tu/error? result))
                                   "the canonical cursor is NOT surfaced as an error")
                               (is (not= rf.mcp-base.vocab/cursor-stale-reason (:reason edn))
                                   "the canonical cursor is NOT rejected as cursor-stale")
                               (is (true? (:ok? edn))
                                   "the canonical cursor drives an ok page past the gate")
                               (done))))))))))))
