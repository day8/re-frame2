(ns re-frame.mcp-base.cljs-branches-cljs-test
  "CLJS-only coverage for re-frame2-mcp-base: the `:cljs` arms of its
  `.cljc` reader conditionals, which the JVM suite cannot reach. This build
  runs without Malli on the classpath (see deps.edn `:cljs-test`), so it
  also pins the validation gates' soft-pass branch. Platform-neutral
  logic is pinned once, by the JVM suite."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [are deftest is]]
            [re-frame.mcp-base.args :as rf.mcp-base.args]
            [re-frame.mcp-base.cursor :as rf.mcp-base.cursor]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]
            [re-frame.mcp-base.envelope :as rf.mcp-base.envelope]
            [re-frame.mcp-base.section-grouping :as rf.mcp-base.section-grouping]
            [re-frame.mcp-base.sensitive :as rf.mcp-base.sensitive]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

;; ---------------------------------------------------------------------------
;; diff-encode: the goog-define default and the Malli-absent soft-pass.
;; ---------------------------------------------------------------------------

(deftest validate-patches?-goog-define-defaults-true
  (is (true? rf.mcp-base.diff-encode/validate-patches?)
      "production bundles override it to false via :closure-defines"))

(deftest validation-gates-soft-pass-when-malli-absent
  ;; The JVM suite, with Malli present, asserts these inputs throw. Here
  ;; the `:cljs` resolve arm finds no Malli and both gates no-op: the
  ;; unknown op falls through the replay, and the malformed section's
  ;; cosmetic slots are ignored while its patches still apply.
  (is (= {} (rf.mcp-base.diff-encode/apply-patches {} [[[:a] :replace 1]])))
  (is (= {:a 2} (:db-after (rf.mcp-base.diff-encode/decode-db-after
                             {:db-before {:a 1}
                              :db-after  {:rf.mcp/diff-from :db-before
                                          :sections [{:section-path :not-a-vector
                                                      :section-kind :renamed
                                                      :patches      [[[:a] :assoc 2]]}]}})))))

;; ---------------------------------------------------------------------------
;; args: the `:cljs` arms of the integer parser and the finite/range guard.
;; ---------------------------------------------------------------------------

(deftest parse-positive-int-cljs-arms
  ;; js/parseInt reads a numeric prefix and JS numbers lose precision past
  ;; the safe-integer window, so both arms are guarded to agree with the
  ;; JVM half in args_test.
  (are [raw expected] (= expected (rf.mcp-base.args/parse-positive-int raw 50))
    "12abc"            50
    "+12"              12
    "-5"               1
    "9007199254740991" 9007199254740991
    "9007199254740992" 50
    js/Infinity        50
    js/NaN             50
    1e20               50
    2.9                2
    9007199254740991   9007199254740991))

;; ---------------------------------------------------------------------------
;; cursor: the js/Buffer codec and the cljs.reader read.
;; ---------------------------------------------------------------------------

(defn- pair? [m]
  (and (map? m) (some? (:after-id m))))

(deftest cursor-codec-round-trips-under-cljs
  ;; cljs.reader refuses a trailing form, an injected `]`, and the
  ;; built-in #inst / #uuid tags its tag table would otherwise resolve
  ;; past the :default handler.
  (let [payload {:v 1 :after-id "ev-42" :ms 1000}]
    (is (= payload (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/encode-cursor payload) pair?))))
  (is (nil? (rf.mcp-base.cursor/decode-cursor nil pair?)))
  (are [text] (= :re-frame.mcp-base.cursor/malformed
                 (rf.mcp-base.cursor/decode-cursor (rf.mcp-base.cursor/b64-encode text) pair?))
    "{:v 1 :after-id 1} {:junk 1}"
    "{:v 1 :after-id 1}] {:junk 1}"
    "{:v 1 :after-id 1 :junk #inst \"2024-01-01T00:00:00.000-00:00\"}"
    "{:v 1 :after-id 1 :junk #uuid \"00000000-0000-0000-0000-000000000000\"}"))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn- pad-bit-alias
  "A noncanonical alias of canonical b64 `token` that differs only in the
  trailing pad bits, so it decodes to the same bytes under a different
  spelling; nil if `token` has no pad slack. Mirror of the `cursor_test`
  helper."
  [token]
  (let [decoded (rf.mcp-base.cursor/b64-decode token)
        i       (dec (count (re-find #"[^=]+" token)))
        orig    (nth token i)]
    (some (fn [c]
            (when (not= c orig)
              (let [cand (str (subs token 0 i) c (subs token (inc i)))]
                (when (= decoded (rf.mcp-base.cursor/b64-decode cand)) cand))))
          b64-alphabet)))

(deftest cursor-rejects-noncanonical-base64-aliases-cljs
  ;; js/Buffer drops non-alphabet chars where the JVM decoder throws, and
  ;; both ignore non-zero pad bits; the canonical re-encode check rejects
  ;; every alias exactly as the JVM does.
  (let [canonical (rf.mcp-base.cursor/b64-encode "{:v 1 :after-id \"e\"}")]
    (are [token] (= :re-frame.mcp-base.cursor/malformed (rf.mcp-base.cursor/decode-cursor token pair?))
      (str (subs canonical 0 2) "!!" (subs canonical 2))
      (str canonical "!!!!")
      (str canonical "==")
      (pad-bit-alias canonical))))

;; ---------------------------------------------------------------------------
;; envelope: marker-text?'s cljs.reader read, on the flat print form CLJS
;; emits. pair-mcp skips cache and cap work for anything called a marker.
;; ---------------------------------------------------------------------------

(deftest marker-text?-requires-closed-single-key-wrapper-cljs
  (is (true? (rf.mcp-base.envelope/marker-text?
               (pr-str {rf.mcp-base.vocab/overflow-key {:limit :reached :token-count 9000
                                                        :cap-tokens 5000 :tool "snapshot"}}))))
  (are [text] (false? (rf.mcp-base.envelope/marker-text? text))
    "{:rf.mcp/overflow {:limit :reached}} 42"
    "{:rf.mcp/overflow {:limit :reached}}] {:junk 1}"
    "{:rf.mcp/overflow {:at #inst \"2024-01-01T00:00:00.000-00:00\"}}"
    "{:rf.mcp/overflow {:limit :reached}"))

;; ---------------------------------------------------------------------------
;; section-grouping: the `js-obj` absent sentinel.
;; ---------------------------------------------------------------------------

(deftest section-kind-db-before-classification-cljs
  ;; The sentinel tells an absent container from a present one, a stored
  ;; nil included.
  (let [patches [[[:user :name] :assoc "ada"] [[:user :email] :assoc "x"]]]
    (are [db-before kind]
         (= kind (:section-kind (first (rf.mcp-base.section-grouping/group-patches-into-sections
                                         patches {:db-before db-before}))))
      {}                    :added
      {:user nil}           :modified
      {:user {:name "bob"}} :modified)))

;; ---------------------------------------------------------------------------
;; sensitive: the atom counter, the type-tag cond and the console.warn
;; egress, which run in production inside re-frame2-pair-mcp.
;; ---------------------------------------------------------------------------

(defn- capture-console-warn
  "Run `thunk` with `js/console.warn` swapped for a recorder, restoring
  whatever was installed (the quiet test runner installs its own), and
  return every warning as a string."
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
  ;; The log is an egress boundary too: the warning carries a value-free
  ;; type tag and the :rf/redacted sentinel, never the stamp.
  (doseq [[stamp secret] [["sk_live_SECRET_TOKEN" "sk_live_SECRET_TOKEN"]
                          [{:api_key "AKIA_LEAK"} "AKIA_LEAK"]]]
    (let [text (str/join "\n" (capture-console-warn
                                #(rf.mcp-base.sensitive/sensitive-event? {:sensitive? stamp})))]
      (is (str/includes? text ":rf/redacted"))
      (is (not (str/includes? text secret))))))

(deftest sensitive-malformed-count-exactly-once-per-event-cljs
  ;; The `(atom 0)` counter arm: two malformed stamps, two bumps, and the
  ;; well-formed `true` none.
  (rf.mcp-base.sensitive/reset-malformed-count!)
  (capture-console-warn
    #(rf.mcp-base.sensitive/strip-sensitive [{:id 1 :sensitive? "true"}
                                             {:id 2 :sensitive? :yes}
                                             {:id 3 :sensitive? true}]
                                            false))
  (is (= 2 (rf.mcp-base.sensitive/malformed-count))))
