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
