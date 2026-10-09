(ns re-frame.mcp-base.cursor-test
  "Tests for the shared cursor-pagination machinery. The JVM suite pins
  the `:clj` codec arm; `cljs_branches_cljs_test` pins the `js/Buffer`
  arm."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

;; Story's cursor shape, as a consumer `valid?` predicate.
(defn- offset-cursor? [m]
  (and (map? m)
       (= 1 (:v m))
       (integer? (:offset m))
       (integer? (:total m))
       (string? (:sig m))))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn- pad-bit-alias
  "A noncanonical alias of canonical b64 `token` that differs only in the
  trailing pad bits, so it decodes to the same bytes under a different
  spelling; nil if `token` has no pad slack. Mirrored in
  `cljs_branches_cljs_test`."
  [token]
  (let [decoded (rf.mcp-base.cursor/b64-decode token)
        i       (dec (count (re-find #"[^=]+" token)))
        orig    (nth token i)]
    (some (fn [c]
            (when (not= c orig)
              (let [cand (str (subs token 0 i) c (subs token (inc i)))]
                (when (= decoded (rf.mcp-base.cursor/b64-decode cand)) cand))))
          b64-alphabet)))

(deftest b64-round-trips
  (is (= "café — 日本" (rf.mcp-base.cursor/b64-decode (rf.mcp-base.cursor/b64-encode "café — 日本")))))

(deftest encode-decode-round-trips
  (let [payload {:v 1 :offset 25 :total 137 :sig "abc123"}]
    (is (= payload (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/encode-cursor payload)
                                                     offset-cursor?)))))

(deftest encode-cursor-rejects-non-map
  (is (nil? (rf.mcp-base.cursor/encode-cursor nil)))
  (is (nil? (rf.mcp-base.cursor/encode-cursor 42))))

;; ---------------------------------------------------------------------------
;; decode-cursor recovery contract
;; ---------------------------------------------------------------------------

(deftest decode-cursor-absent-is-nil
  (is (nil? (rf.mcp-base.cursor/decode-cursor nil offset-cursor?)))
  (is (nil? (rf.mcp-base.cursor/decode-cursor "   " offset-cursor?))))

(deftest decode-cursor-non-string-is-malformed
  (is (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor 42 offset-cursor?)))
  (is (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor {:offset 0} offset-cursor?))))

(deftest decode-cursor-rejects-noncanonical-base64-aliases
  ;; The host decoders are lenient in different ways (js/Buffer drops
  ;; non-alphabet chars where the JVM throws; both ignore non-zero pad
  ;; bits), so a re-spelled token can decode to the same payload. Every
  ;; such alias is ::malformed on both hosts; the CLJS mirror is
  ;; `cursor-rejects-noncanonical-base64-aliases-cljs`.
  (let [canonical (rf.mcp-base.cursor/b64-encode "{:v 1 :after-id \"e\"}")
        pair?     (fn [m] (and (map? m) (some? (:after-id m))))]
    (are [token] (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor token pair?))
      (str (subs canonical 0 2) "!!" (subs canonical 2)) ; inserted non-alphabet chars
      (str canonical "!!!!")                             ; appended non-alphabet chars
      (str canonical "==")                               ; extra padding
      (pad-bit-alias canonical))))                       ; same bytes, other pad bits

(deftest decode-cursor-cap-is-characters-and-the-unit-is-unobservable
  ;; The cap's guard is `(> (count s) ...)`, UTF-16 code units on both
  ;; hosts. Code units and UTF-8 bytes agree on ASCII, and a non-ASCII
  ;; token is ::malformed regardless (`decode-canonical-b64` refuses it),
  ;; so the unit cannot be observed through `decode-cursor`. Each fixture
  ;; sits under the character cap but over a byte cap, and is ::malformed.
  (let [utf8-len #(alength (.getBytes ^String % "UTF-8"))]
    (doseq [s [(apply str (repeat 400 "—"))           ; 1 code unit, 3 bytes
               (apply str (repeat 400 "𝄞"))]]   ; 2 code units, 4 bytes
      (is (<= (count s) rf.mcp-base.cursor/max-cursor-chars))
      (is (> (utf8-len s) rf.mcp-base.cursor/max-cursor-chars))
      (is (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor s offset-cursor?))))))

(deftest decode-cursor-failing-payload-predicate-is-malformed
  (is (= ::rf.mcp-base.cursor/malformed
         (rf.mcp-base.cursor/decode-cursor
           (rf.mcp-base.cursor/encode-cursor {:v 1 :offset 0 :total 5 :sig "s"})
           (constantly false)))))

(deftest decode-cursor-non-map-edn-is-malformed-without-consulting-predicate
  ;; The predicate admits everything, so only the `map?` guard can turn a
  ;; well-formed non-map payload into ::malformed.
  (let [consulted (atom [])
        admit-all (fn [m] (swap! consulted conj m) true)]
    (is (= ::rf.mcp-base.cursor/malformed
           (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode "[1 2 3]") admit-all)))
    (is (= [] @consulted))))

(deftest decode-cursor-at-inclusive-size-boundary-is-not-rejected
  ;; The size guard is a strict `>`: a real cursor whose token is exactly
  ;; max-cursor-chars long still decodes.
  (let [payload {:v 1 :offset 0 :total 1 :sig (apply str (repeat 732 \s))}
        token   (rf.mcp-base.cursor/encode-cursor payload)]
    (is (= rf.mcp-base.cursor/max-cursor-chars (count token)))
    (is (= payload (rf.mcp-base.cursor/decode-cursor token offset-cursor?)))))

(deftest decode-cursor-rejects-tagged-literals
  ;; Every tagged literal is refused, the built-in #inst / #uuid included:
  ;; their registered readers would otherwise bypass the :default handler
  ;; and carry a host Date / UUID through a permissive predicate.
  (let [permissive? (fn [m] (and (map? m) (some? (:after-id m))))]
    (are [text] (= ::rf.mcp-base.cursor/malformed
                   (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode text) permissive?))
      "{:v 1 :after-id 1 :junk #foo/bar 0}"
      "{:v 1 :after-id 1 :junk #inst \"2024-01-01T00:00:00.000-00:00\"}"
      "{:v 1 :after-id 1 :junk #uuid \"00000000-0000-0000-0000-000000000000\"}")))

(deftest decode-cursor-rejects-trailing-forms
  ;; A cursor is ONE payload map. A trailing form, a `]` injected to close
  ;; the reader's wrapping vector early, or a reproduced EOF sentinel each
  ;; make it ::malformed; trailing whitespace does not.
  (let [m "{:v 1 :offset 0 :total 1 :sig \"s\"}"]
    (are [text] (= ::rf.mcp-base.cursor/malformed
                   (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode text) offset-cursor?))
      (str m " {:junk 1}")
      (str m "] {:junk 1}")
      (str m " " (pr-str :re-frame.mcp-base.cursor/cursor-eof-sentinel)))
    (is (= {:v 1 :offset 0 :total 1 :sig "s"}
           (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode (str m "   "))
                                             offset-cursor?)))))

;; ---------------------------------------------------------------------------
;; parse-limit-arg and cursor-stale-result
;; ---------------------------------------------------------------------------

(deftest parse-limit-arg-defaults-and-clamps
  (are [raw expected] (= expected (rf.mcp-base.cursor/parse-limit-arg raw 25 200))
    nil  25
    50   50
    "50" 50
    5000 200
    0    1))

(deftest cursor-stale-result-shape
  (let [builder (fn [message data] (assoc data ::message message))
        r       (rf.mcp-base.cursor/cursor-stale-result builder "list-stories" {})]
    (is (= {:ok? false :reason rf.mcp-base.vocab/cursor-stale-reason :tool "list-stories"}
           (dissoc r :hint ::message)))
    (is (every? string? [(:hint r) (::message r)]) "default hint and message")
    (is (= {:ok?          false
            :reason       rf.mcp-base.vocab/cursor-stale-reason
            :tool         "watch-epochs"
            :hint         "rewind"
            :requested-id "ev-1"
            :head-id      "ev-9"
            ::message     "custom"}
           (rf.mcp-base.cursor/cursor-stale-result
             builder "watch-epochs"
             {:message "custom" :hint "rewind" :extra {:requested-id "ev-1" :head-id "ev-9"}})))))
