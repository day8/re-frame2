(ns re-frame.mcp-base.cursor-test
  "Tests for the shared cursor-pagination machinery (rf2-ee38b.19).
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

(deftest decode-cursor-oversize-is-malformed-before-parse
  (let [oversize (apply str (repeat (inc cursor/max-cursor-bytes) "a"))]
    (is (= ::cursor/malformed (cursor/decode-cursor oversize offset-cursor?)))))

(deftest decode-cursor-failing-payload-predicate-is-malformed
  ;; Valid base64+EDN map, but the consumer's shape predicate rejects it.
  (let [token (cursor/encode-cursor {:v 1 :offset 0 :total 5 :sig "s"})]
    (is (= ::cursor/malformed (cursor/decode-cursor token (constantly false))))
    (is (= ::cursor/malformed
           (cursor/decode-cursor (cursor/encode-cursor {:wrong :shape}) offset-cursor?)))))

(deftest decode-cursor-non-map-edn-is-malformed-without-consulting-predicate
  ;; Coverage gap (rf2-ynjts.17): a cursor that base64+EDN-decodes to a
  ;; well-formed but NON-MAP value (`"5"` ⇒ 5, `"[1 2 3]"` ⇒ vector,
  ;; `":kw"` ⇒ keyword) hits the `(map? v)` short-circuit in
  ;; `decode-cursor` — `valid?` is never consulted. The existing tests
  ;; only exercised a map that FAILS `valid?`; the non-map arm (where
  ;; `valid?` would throw if called on, say, a number) had no pin. We
  ;; pass a `valid?` that THROWS on non-map input to prove the guard
  ;; runs first: a throw here would mean `valid?` was reached.
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
  ;; Coverage gap (rf2-ynjts.17): the size guard is a STRICT `>`
  ;; (`(> (count s) max-cursor-bytes)` ⇒ ::malformed). The existing
  ;; oversize test only feeds `(inc max-cursor-bytes)` chars — the
  ;; over-limit side. The INCLUSIVE side (a real cursor whose token
  ;; length is `<= max-cursor-bytes`) was never pinned. A regression
  ;; that flipped the guard to `>=` would reject a legitimate at-or-near-
  ;; cap cursor; this test trips on that flip.
  ;;
  ;; Build the largest real, decodable cursor whose token length is
  ;; still `<= max-cursor-bytes` (grow the payload's `:sig` to the cap).
  ;; Under strict `>` it round-trips; under `>=` (if the token landed
  ;; exactly on the cap) it would size-reject. The round-trip is the
  ;; load-bearing assertion.
  (let [grow      (fn [n] {:v 1 :offset 0 :total 1
                           :sig (apply str (repeat n \s))})
        token-len (fn [n] (count (cursor/encode-cursor (grow n))))
        ;; largest sig length whose token is still within the cap.
        max-n     (loop [n 0]
                    (if (> (token-len (inc n)) cursor/max-cursor-bytes)
                      n
                      (recur (inc n))))
        payload   (grow max-n)
        token     (cursor/encode-cursor payload)]
    (is (<= (count token) cursor/max-cursor-bytes)
        "constructed cursor sits AT or just under the inclusive boundary")
    (is (> (token-len (inc max-n)) cursor/max-cursor-bytes)
        "one more sig char would push the token over the cap — boundary is tight")
    (is (= payload (cursor/decode-cursor token offset-cursor?))
        "a cursor at the inclusive size boundary is NOT size-rejected (strict >)"))
  ;; Unconditional companion: a realistic small cursor is well under cap.
  (let [payload {:v 1 :offset 25 :total 137 :sig "abc123"}
        token   (cursor/encode-cursor payload)]
    (is (< (count token) cursor/max-cursor-bytes)
        "a realistic cursor is well under the byte cap")
    (is (= payload (cursor/decode-cursor token offset-cursor?)))))

(deftest decode-cursor-rejects-tagged-literals
  ;; The hardening contract: a cursor smuggling a tagged literal must
  ;; be rejected by the reader's :default handler → ::malformed, never
  ;; evaluated.
  (let [evil-inst (cursor/b64-encode "#inst \"2024-01-01\"")
        evil-map  (cursor/b64-encode "{:v 1 :offset #foo/bar 0 :total 5 :sig \"s\"}")]
    (is (= ::cursor/malformed (cursor/decode-cursor evil-inst offset-cursor?)))
    (is (= ::cursor/malformed (cursor/decode-cursor evil-map offset-cursor?)))))

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
