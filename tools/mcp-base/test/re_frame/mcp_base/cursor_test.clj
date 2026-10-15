(ns re-frame.mcp-base.cursor-test
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
  "Change pad bits without changing the decoded bytes."
  [token]
  (let [decoded (rf.mcp-base.cursor/b64-decode token)
        i       (dec (count (re-find #"[^=]+" token)))
        orig    (nth token i)]
    (some (fn [c]
            (when (not= c orig)
              (let [cand (str (subs token 0 i) c (subs token (inc i)))]
                (when (= decoded (rf.mcp-base.cursor/b64-decode cand)) cand))))
          b64-alphabet)))

(deftest encode-decode-round-trips
  ;; A non-ASCII payload pins the UTF-8 codec under the base64.
  (let [payload {:v 1 :offset 25 :total 137 :sig "café — 日本"}]
    (is (= payload (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/encode-cursor payload)
                                                     offset-cursor?)))))

(deftest encode-cursor-rejects-non-map
  (is (nil? (rf.mcp-base.cursor/encode-cursor nil)))
  (is (nil? (rf.mcp-base.cursor/encode-cursor 42))))

(deftest decode-cursor-absent-is-nil
  (is (nil? (rf.mcp-base.cursor/decode-cursor nil offset-cursor?)))
  (is (nil? (rf.mcp-base.cursor/decode-cursor "   " offset-cursor?))))

(deftest decode-cursor-non-string-is-malformed
  (is (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor 42 offset-cursor?)))
  (is (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor {:offset 0} offset-cursor?))))

(deftest decode-cursor-rejects-noncanonical-base64-aliases
  ;; Lenient host decoders can accept several spellings of the same bytes.
  (let [canonical (rf.mcp-base.cursor/b64-encode "{:v 1 :after-id \"e\"}")
        pair?     (fn [m] (and (map? m) (some? (:after-id m))))]
    (are [token] (= ::rf.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor token pair?))
      (str (subs canonical 0 2) "!!" (subs canonical 2)) ; inserted non-alphabet chars
      (str canonical "!!!!")                             ; appended non-alphabet chars
      (str canonical "==")                               ; extra padding
      (pad-bit-alias canonical))))                       ; same bytes, other pad bits

(deftest decode-cursor-cap-is-characters-and-the-unit-is-unobservable
  ;; Non-ASCII tokens are malformed regardless of the cap's unit.
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
  (let [payload {:v 1 :offset 0 :total 1 :sig (apply str (repeat 732 \s))}
        token   (rf.mcp-base.cursor/encode-cursor payload)]
    (is (= rf.mcp-base.cursor/max-cursor-chars (count token)))
    (is (= payload (rf.mcp-base.cursor/decode-cursor token offset-cursor?)))))

(deftest decode-cursor-rejects-tagged-literals
  ;; Built-in tags bypass :default unless explicitly rejected.
  (let [permissive? (fn [m] (and (map? m) (some? (:after-id m))))]
    (are [text] (= ::rf.mcp-base.cursor/malformed
                   (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode text) permissive?))
      "{:v 1 :after-id 1 :junk #foo/bar 0}"
      "{:v 1 :after-id 1 :junk #inst \"2024-01-01T00:00:00.000-00:00\"}"
      "{:v 1 :after-id 1 :junk #uuid \"00000000-0000-0000-0000-000000000000\"}")))

(deftest decode-cursor-rejects-trailing-forms
  ;; Injected brackets and sentinels must not disguise a trailing form.
  (let [m "{:v 1 :offset 0 :total 1 :sig \"s\"}"]
    (are [text] (= ::rf.mcp-base.cursor/malformed
                   (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode text) offset-cursor?))
      (str m " {:junk 1}")
      (str m "] {:junk 1}")
      (str m " " (pr-str :re-frame.mcp-base.cursor/cursor-eof-sentinel)))
    (is (= {:v 1 :offset 0 :total 1 :sig "s"}
           (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode (str m "   "))
                                             offset-cursor?)))))

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
