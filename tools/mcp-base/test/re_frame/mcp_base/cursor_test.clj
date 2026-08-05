(ns re-frame.mcp-base.cursor-test
  "Tests for the shared cursor-pagination machinery.
  The base64 codec, the EDN-read-with-tagged-literal-rejection, the
  `::malformed` recovery contract, the `:limit` clamp, and the
  `cursor-stale-result` envelope are the cross-MCP pieces both servers
  consume; the JVM suite pins the `:clj` codec arm, the CLJS
  branches test (`cljs_branches_cljs_test`) pins the `js/Buffer` arm."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.cursor :as cursor]
            [re-frame.mcp-base.vocab :as vocab]))

;; A payload-shape predicate for the tests — mirrors story's cursor
;; shape closely enough to exercise the `valid?` parameterisation.
(defn- offset-cursor? [m]
  (and (map? m)
       (= 1 (:v m))
       (integer? (:offset m))
       (integer? (:total m))
       (string? (:sig m))))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn- pad-bit-alias
  "Return a NONCANONICAL alias of canonical b64 `token` that differs only
  in the trailing pad bits (same decoded bytes, DIFFERENT spelling), or
  nil if `token` carries no pad slack. Both `java.util.Base64` and
  `js/Buffer` ignore non-zero pad bits, so such an alias decodes to
  IDENTICAL bytes under a different token — the alias family a lexical
  alphabet/padding grammar admits but the round-trip gate must reject.
  Built from `b64-decode`, so the JVM and CLJS suites derive it the same
  way. (Mirrored verbatim in `cljs_branches_cljs_test`.)"
  [token]
  (let [decoded (cursor/b64-decode token)
        i       (dec (count (re-find #"[^=]+" token)))
        orig    (nth token i)]
    (some (fn [c]
            (when (not= c orig)
              (let [cand (str (subs token 0 i) c (subs token (inc i)))]
                (when (= decoded (cursor/b64-decode cand)) cand))))
          b64-alphabet)))

;; ---------------------------------------------------------------------------
;; base64 codec round-trip
;; ---------------------------------------------------------------------------

(deftest b64-round-trips
  (is (= "hello" (cursor/b64-decode (cursor/b64-encode "hello"))))
  (is (= "" (cursor/b64-decode (cursor/b64-encode ""))))
  (testing "non-ASCII survives the UTF-8 round-trip"
    (is (= "café — 日本" (cursor/b64-decode (cursor/b64-encode "café — 日本"))))))

;; ---------------------------------------------------------------------------
;; encode-cursor / decode-cursor round-trip
;; ---------------------------------------------------------------------------

(deftest encode-decode-round-trips
  (let [payload {:v 1 :offset 25 :total 137 :sig "abc123"}
        token   (cursor/encode-cursor payload)]
    (is (string? token))
    (is (= payload (cursor/decode-cursor token offset-cursor?))))
  (testing "a different shape (pair-style) round-trips under its own valid?"
    (let [payload {:v 1 :after-id "ev-9" :ms 500 :until-ms 1000 :frame :app}
          token   (cursor/encode-cursor payload)]
      (is (= payload (cursor/decode-cursor token #(and (map? %) (string? (:after-id %)))))))))

(deftest encode-cursor-rejects-non-map
  (is (nil? (cursor/encode-cursor nil)))
  (is (nil? (cursor/encode-cursor 42)))
  (is (nil? (cursor/encode-cursor "x"))))

;; ---------------------------------------------------------------------------
;; decode-cursor recovery contract
;; ---------------------------------------------------------------------------

(deftest decode-cursor-absent-is-nil
  (is (nil? (cursor/decode-cursor nil offset-cursor?)))
  (is (nil? (cursor/decode-cursor "" offset-cursor?)))
  (is (nil? (cursor/decode-cursor "   " offset-cursor?))))

(deftest decode-cursor-non-string-is-malformed
  (is (= ::cursor/malformed (cursor/decode-cursor 42 offset-cursor?)))
  (is (= ::cursor/malformed (cursor/decode-cursor {:offset 0} offset-cursor?))))

(deftest decode-cursor-garbage-is-malformed
  (is (= ::cursor/malformed (cursor/decode-cursor "!!!not-base64-edn!!!" offset-cursor?))))

(deftest decode-cursor-rejects-noncanonical-base64-aliases
  ;; The host base64 DECODERS are lenient in DIFFERENT ways: `js/Buffer`
  ;; DROPS characters outside the standard alphabet (where the JVM decoder
  ;; THROWS), and BOTH hosts ignore non-zero trailing pad bits. So a
  ;; corrupted / re-spelled token can decode to the SAME payload map under
  ;; a DIFFERENT wire string — an alias. `decode-canonical-b64` re-encodes
  ;; the decoded bytes and requires token equality with the local encoder,
  ;; rejecting every alias identically on CLJ + CLJS BEFORE EDN parsing.
  ;; (Mirrored in `cljs_branches_cljs_test/cursor-rejects-noncanonical-
  ;; base64-aliases-cljs`.)
  (let [payload   "{:v 1 :after-id \"e\"}"     ; a valid pair-style payload
        canonical (cursor/b64-encode payload)
        pair?     (fn [m] (and (map? m) (some? (:after-id m))))]
    (testing "sanity: the canonical token still decodes to its payload map"
      (is (= {:v 1 :after-id "e"} (cursor/decode-cursor canonical pair?))))
    (testing "non-alphabet char INSERTED into a valid token ⇒ ::malformed"
      (let [inserted (str (subs canonical 0 2) "!!" (subs canonical 2))]
        (is (not= inserted canonical))
        (is (= ::cursor/malformed (cursor/decode-cursor inserted pair?)))))
    (testing "non-alphabet chars APPENDED to a valid token ⇒ ::malformed"
      (is (= ::cursor/malformed (cursor/decode-cursor (str canonical "!!!!") pair?))))
    (testing "malformed / extra padding ⇒ ::malformed"
      (is (= ::cursor/malformed (cursor/decode-cursor (str canonical "==") pair?))))
    (testing "noncanonical pad-bit spelling (a lexical grammar admits it) ⇒ ::malformed"
      ;; This is the alias family a regex-only check would MISS and the one
      ;; the OLD JVM decoder accepted (java.util.Base64 ignores pad bits):
      ;; the alias decodes to the same valid map, so old code returned it.
      (let [alias (pad-bit-alias canonical)]
        (is (some? alias) "the canonical token has pad slack to alias")
        (is (not= alias canonical))
        (is (= (cursor/b64-decode alias) (cursor/b64-decode canonical))
            "the alias decodes to the SAME bytes — a true logical alias")
        (is (= ::cursor/malformed (cursor/decode-cursor alias pair?)))))))

(deftest decode-cursor-oversize-is-malformed-before-parse
  (let [oversize (apply str (repeat (inc cursor/max-cursor-chars) "a"))]
    (is (= ::cursor/malformed (cursor/decode-cursor oversize offset-cursor?)))))

(deftest decode-cursor-cap-is-characters-and-the-unit-is-unobservable
  ;; rf2-2rtt6.132 - the cap was named `max-cursor-bytes` while the guard
  ;; was `(> (count s) ...)`, i.e. UTF-16 CODE UNITS on both hosts. It was
  ;; RELABELLED rather than converted, and this pins the reasoning so a
  ;; later author does not "fix" it into UTF-8 bytes.
  ;;
  ;; The two rulers agree exactly on ASCII, so they can only diverge on a
  ;; NON-ASCII token - and a non-ASCII token is `::malformed` regardless,
  ;; because `decode-canonical-b64` refuses anything outside the base64
  ;; alphabet. The cap's unit is therefore NOT observable through
  ;; `decode-cursor`: both units answer `::malformed` on every input that
  ;; could tell them apart. Converting would move no behaviour at all.
  ;;
  ;; Fixtures are \uXXXX escapes so the source stays pure ASCII, and are
  ;; asserted DISCRIMINATING before they are used: U+2014 EM DASH is 1
  ;; code unit / 1 code point / 3 UTF-8 bytes, and the ASTRAL U+1D11E is
  ;; 2 code units / 1 code point / 4 UTF-8 bytes.
  (let [utf8-len #(alength (.getBytes ^String % "UTF-8"))
        dashes   (apply str (repeat 400 "\u2014"))
        astral   (apply str (repeat 400 "\uD834\uDD1E"))]
    (testing "the fixtures make code units, code points and bytes three different numbers"
      (is (= 400 (count dashes)))
      (is (= 400 (.codePointCount ^String dashes 0 (count dashes))))
      (is (= 1200 (utf8-len dashes)))
      (is (= 800 (count astral)) "the astral fixture is 2 code units per code point")
      (is (= 400 (.codePointCount ^String astral 0 (count astral))))
      (is (= 1600 (utf8-len astral))))
    (testing "under the CHARACTER cap but over a hypothetical BYTE cap - still ::malformed"
      ;; Both sit under 1,024 code units and over 1,024 UTF-8 bytes, so a
      ;; byte cap would refuse them AT THE GUARD while the character cap
      ;; lets them through to the decoder. Same verdict either way, which
      ;; is exactly why the relabel is a complete fix.
      (is (<= (count dashes) cursor/max-cursor-chars))
      (is (> (utf8-len dashes) cursor/max-cursor-chars))
      (is (= ::cursor/malformed (cursor/decode-cursor dashes offset-cursor?))
          "non-base64 => ::malformed for a reason independent of the cap")
      (is (<= (count astral) cursor/max-cursor-chars))
      (is (> (utf8-len astral) cursor/max-cursor-chars))
      (is (= ::cursor/malformed (cursor/decode-cursor astral offset-cursor?))))
    (testing "every legitimate cursor is base64, where the two rulers agree exactly"
      (let [token (cursor/encode-cursor {:v 1 :offset 25 :total 137
                                         :sig "abc\u2014123\uD834\uDD1E"})]
        (is (= (count token) (utf8-len token))
            "a cursor whose PAYLOAD carries an em-dash and an astral glyph still encodes to a pure-ASCII token")
        (is (<= (count token) cursor/max-cursor-chars))))))

(deftest decode-cursor-failing-payload-predicate-is-malformed
  ;; Valid base64+EDN map, but the consumer's shape predicate rejects it.
  (let [token (cursor/encode-cursor {:v 1 :offset 0 :total 5 :sig "s"})]
    (is (= ::cursor/malformed (cursor/decode-cursor token (constantly false))))
    (is (= ::cursor/malformed
           (cursor/decode-cursor (cursor/encode-cursor {:wrong :shape}) offset-cursor?)))))

(deftest decode-cursor-non-map-edn-is-malformed-without-consulting-predicate
  ;; A cursor that base64+EDN-decodes to a well-formed but NON-MAP
  ;; value (`"5"` ⇒ 5, `"[1 2 3]"` ⇒ vector, `":kw"` ⇒ keyword) hits
  ;; the `(map? v)` short-circuit in `decode-cursor` — `valid?` is
  ;; never consulted. This pins the non-map arm (where `valid?` would
  ;; throw if called on, say, a number): we pass a `valid?` that THROWS
  ;; on non-map input to prove the guard runs first — a throw here
  ;; would mean `valid?` was reached.
  (let [strict-map-pred (fn [m]
                          ;; would explode on a non-map if reached
                          (and (map? m) (pos? (count m))))
        num-token  (cursor/b64-encode "5")
        vec-token  (cursor/b64-encode "[1 2 3]")
        kw-token   (cursor/b64-encode ":some-keyword")
        str-token  (cursor/b64-encode "\"a string\"")]
    (is (= ::cursor/malformed (cursor/decode-cursor num-token strict-map-pred))
        "numeric EDN cursor ⇒ ::malformed (map? guard, valid? not consulted)")
    (is (= ::cursor/malformed (cursor/decode-cursor vec-token strict-map-pred))
        "vector EDN cursor ⇒ ::malformed")
    (is (= ::cursor/malformed (cursor/decode-cursor kw-token strict-map-pred))
        "keyword EDN cursor ⇒ ::malformed")
    (is (= ::cursor/malformed (cursor/decode-cursor str-token strict-map-pred))
        "string EDN cursor ⇒ ::malformed")))

(deftest decode-cursor-at-inclusive-size-boundary-is-not-rejected
  ;; The size guard is a STRICT `>`
  ;; (`(> (count s) max-cursor-chars)` ⇒ ::malformed). This pins the
  ;; INCLUSIVE side (a real cursor whose token length is
  ;; `<= max-cursor-chars`), complementing the oversize test that feeds
  ;; `(inc max-cursor-chars)` chars. A regression that flipped the guard
  ;; to `>=` would reject a legitimate at-or-near-cap cursor; this test
  ;; trips on that flip.
  ;;
  ;; Build the largest real, decodable cursor whose token length is
  ;; still `<= max-cursor-chars` (grow the payload's `:sig` to the cap).
  ;; Under strict `>` it round-trips; under `>=` (if the token landed
  ;; exactly on the cap) it would size-reject. The round-trip is the
  ;; load-bearing assertion.
  (let [grow      (fn [n] {:v 1 :offset 0 :total 1
                           :sig (apply str (repeat n \s))})
        token-len (fn [n] (count (cursor/encode-cursor (grow n))))
        ;; largest sig length whose token is still within the cap.
        max-n     (loop [n 0]
                    (if (> (token-len (inc n)) cursor/max-cursor-chars)
                      n
                      (recur (inc n))))
        payload   (grow max-n)
        token     (cursor/encode-cursor payload)]
    (is (<= (count token) cursor/max-cursor-chars)
        "constructed cursor sits AT or just under the inclusive boundary")
    (is (> (token-len (inc max-n)) cursor/max-cursor-chars)
        "one more sig char would push the token over the cap — boundary is tight")
    (is (= payload (cursor/decode-cursor token offset-cursor?))
        "a cursor at the inclusive size boundary is NOT size-rejected (strict >)"))
  ;; Unconditional companion: a realistic small cursor is well under cap.
  (let [payload {:v 1 :offset 25 :total 137 :sig "abc123"}
        token   (cursor/encode-cursor payload)]
    (is (< (count token) cursor/max-cursor-chars)
        "a realistic cursor is well under the byte cap")
    (is (= payload (cursor/decode-cursor token offset-cursor?)))))

(deftest decode-cursor-rejects-tagged-literals
  ;; The hardening contract: a cursor smuggling a tagged literal must
  ;; be rejected by the reader → ::malformed, never evaluated.
  (let [evil-inst (cursor/b64-encode "#inst \"2024-01-01\"")
        evil-map  (cursor/b64-encode "{:v 1 :offset #foo/bar 0 :total 5 :sig \"s\"}")]
    (is (= ::cursor/malformed (cursor/decode-cursor evil-inst offset-cursor?)))
    (is (= ::cursor/malformed (cursor/decode-cursor evil-map offset-cursor?)))))

(deftest decode-cursor-rejects-builtin-tags-inside-valid-map
  ;; The BUILT-IN EDN tags `#inst` / `#uuid` have registered
  ;; readers (`clojure.edn` / `cljs.reader` resolve them from
  ;; `*data-readers*` / the tag-table) and BYPASS the `:default`
  ;; handler entirely. Without an override they would decode to a host
  ;; `java.util.Date` / `UUID` (JVM) / `js/Date` / `cljs.core/UUID`
  ;; (CLJS), smuggling a host object through the cursor boundary inside
  ;; an OTHERWISE-VALID payload map. Pair-mcp's predicate is permissive
  ;; (`some? :after-id`), so the tagged value would survive validation
  ;; instead of being treated as malformed.
  ;;
  ;; The `:readers` overrides throw on `#inst` / `#uuid`, so EVERY
  ;; tag is rejected. Covered for both a story-style strict predicate
  ;; and a pair-style permissive predicate; both expect ::malformed.
  (let [permissive? (fn [m] (and (map? m) (some? (:after-id m))))
        ;; pair-style permissive map with a built-in tag in a junk slot
        pair-inst (cursor/b64-encode
                    "{:v 1 :after-id 1 :junk #inst \"2024-01-01T00:00:00.000-00:00\"}")
        pair-uuid (cursor/b64-encode
                    "{:v 1 :after-id 1 :junk #uuid \"00000000-0000-0000-0000-000000000000\"}")
        ;; story-style map with a built-in tag in a valid-shape slot
        story-inst (cursor/b64-encode
                     "{:v 1 :offset 0 :total 5 :sig #inst \"2024-01-01\"}")
        story-uuid (cursor/b64-encode
                     "{:v 1 :offset 0 :total #uuid \"00000000-0000-0000-0000-000000000000\" :sig \"s\"}")]
    (testing "pair-style permissive predicate — #inst / #uuid in a valid map ⇒ ::malformed"
      (is (= ::cursor/malformed (cursor/decode-cursor pair-inst permissive?)))
      (is (= ::cursor/malformed (cursor/decode-cursor pair-uuid permissive?))))
    (testing "story-style strict predicate — #inst / #uuid in a valid map ⇒ ::malformed"
      (is (= ::cursor/malformed (cursor/decode-cursor story-inst offset-cursor?)))
      (is (= ::cursor/malformed (cursor/decode-cursor story-uuid offset-cursor?))))
    (testing "a clean pair-style cursor still passes the permissive predicate"
      (is (= {:v 1 :after-id "ev-9"}
             (cursor/decode-cursor (cursor/encode-cursor {:v 1 :after-id "ev-9"})
                                   permissive?))))))

(deftest decode-cursor-rejects-trailing-forms
  ;; A cursor is ONE opaque EDN payload map. A plain `read-string`
  ;; reads only the FIRST form and never checks the input is exhausted:
  ;; a token whose decoded text is a valid map FOLLOWED by a second form
  ;; would decode as the valid map, silently IGNORING the trailing
  ;; object — accepting mutated multi-form cursor text and weakening the
  ;; opacity / corruption guard. `read-edn-no-tags` requires the decoded
  ;; text to be exactly one form; any trailing form ⇒ ::malformed.
  (testing "valid map + trailing #inst ⇒ ::malformed (not the silently-accepted first form)"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"} #inst \"2024-01-01\"")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "valid map + trailing custom tag ⇒ ::malformed"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"} #foo/bar 1")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "valid map + trailing ordinary EDN ⇒ ::malformed"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"} {:junk 1}")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "valid map + trailing scalar ⇒ ::malformed"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"} 42")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  ;; `]`-injection. A naive wrap-in-`[…]` guard that accepted a
  ;; ONE-element vector would be bypassable: attacker text `{…}] <junk>`
  ;; wraps to `[{…}] <junk>]` where the injected `]` closes the wrapper
  ;; EARLY, so `read-string` reads the clean 1-element vector `[{…}]`
  ;; and silently discards the trailing junk → the cursor accepted. The
  ;; EOF-sentinel exhaustion check rejects it: the injected `]` truncates
  ;; the read before the appended sentinel, so the result no longer ends
  ;; with the sentinel ⇒ ::malformed.
  (testing "valid map + injected ] + trailing map ⇒ ::malformed (not silently accepted)"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"}] {:junk 1}")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "valid map + injected ] + trailing scalar ⇒ ::malformed"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"}] 42")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "valid map + injected ] + trailing tagged literal ⇒ ::malformed"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"}] #foo/bar 1")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "valid map + injected ] alone (no trailing form) ⇒ ::malformed"
    (let [evil (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"}]")]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "attacker reproduces the eof sentinel keyword as a trailing form ⇒ ::malformed"
    (let [evil (cursor/b64-encode
                 (str "{:v 1 :offset 0 :total 1 :sig \"s\"} "
                      (pr-str :re-frame.mcp-base.cursor/cursor-eof-sentinel)))]
      (is (= ::cursor/malformed (cursor/decode-cursor evil offset-cursor?)))))
  (testing "a single clean cursor (incl. trailing whitespace) still round-trips"
    (let [clean (cursor/encode-cursor {:v 1 :offset 0 :total 1 :sig "s"})]
      (is (= {:v 1 :offset 0 :total 1 :sig "s"}
             (cursor/decode-cursor clean offset-cursor?))))
    (let [trailing-ws (cursor/b64-encode "{:v 1 :offset 0 :total 1 :sig \"s\"}   ")]
      (is (= {:v 1 :offset 0 :total 1 :sig "s"}
             (cursor/decode-cursor trailing-ws offset-cursor?))
          "trailing whitespace is absorbed — one form, valid"))))

(deftest malformed?-predicate
  (is (true? (cursor/malformed? ::cursor/malformed)))
  (is (false? (cursor/malformed? nil)))
  (is (false? (cursor/malformed? {:offset 0}))))

;; ---------------------------------------------------------------------------
;; parse-limit-arg
;; ---------------------------------------------------------------------------

(deftest parse-limit-arg-defaults-and-clamps
  (testing "absent ⇒ default"
    (is (= 25 (cursor/parse-limit-arg nil 25 200))))
  (testing "in-range ⇒ passthrough"
    (is (= 50 (cursor/parse-limit-arg 50 25 200)))
    (is (= 50 (cursor/parse-limit-arg "50" 25 200))))
  (testing "above max ⇒ clamp down"
    (is (= 200 (cursor/parse-limit-arg 5000 25 200))))
  (testing "non-positive ⇒ clamp up to 1 (positive-int floor)"
    (is (= 1 (cursor/parse-limit-arg 0 25 200))))
  (testing "trailing-garbage string ⇒ default (shared strict-parse)"
    (is (= 25 (cursor/parse-limit-arg "50abc" 25 200)))))

;; ---------------------------------------------------------------------------
;; cursor-stale-result
;; ---------------------------------------------------------------------------

(deftest cursor-stale-result-shape
  ;; A trivial error-result builder that captures [message data] so we
  ;; can assert the cross-MCP slot vocabulary the helper owns.
  (let [captured (atom nil)
        builder  (fn [message data] (reset! captured {:message message :data data}) data)]
    (testing "default message + hint, cross-MCP reason slot"
      (let [r (cursor/cursor-stale-result builder "list-stories" {})]
        (is (false? (:ok? r)))
        (is (= vocab/cursor-stale-reason (:reason r)))
        (is (= "list-stories" (:tool r)))
        (is (string? (:hint r)))
        (is (string? (:message @captured)))))
    (testing "override message + hint + extra slots merge"
      (let [r (cursor/cursor-stale-result builder "watch-epochs"
                                          {:message "custom"
                                           :hint    "rewind"
                                           :extra   {:requested-id "ev-1" :head-id "ev-9"}})]
        (is (= "custom" (:message @captured)))
        (is (= "rewind" (:hint r)))
        (is (= "ev-1" (:requested-id r)))
        (is (= "ev-9" (:head-id r)))
        (is (= vocab/cursor-stale-reason (:reason r)))))))
