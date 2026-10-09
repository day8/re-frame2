(ns re-frame.mcp-base.envelope-test
  "Tests for the shared response-envelope helpers: the indicator-field
  'omit when zero' splice and wire-bounded marker detection."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.envelope :as rf.mcp-base.envelope]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(deftest with-indicators-omits-zero-counts
  (let [env {:trace [1]}]
    (is (identical? env (rf.mcp-base.envelope/with-indicators env {:dropped 0 :elided 0})))
    (is (identical? env (rf.mcp-base.envelope/with-indicators env {:dropped nil :elided nil})))
    (are [counts expected] (= expected (rf.mcp-base.envelope/with-indicators env counts))
      {:dropped 3 :elided 0} {:trace [1] :dropped-sensitive 3}
      {:dropped 0 :elided 2} {:trace [1] :elided-large 2}
      {:dropped 3 :elided 2} {:trace [1] :dropped-sensitive 3 :elided-large 2})))

(deftest marker-text?-recognises-both-print-forms
  ;; JVM `pr-str` emits the namespaced-map form and CLJS the flat form;
  ;; both are markers, and additive fields inside the body are allowed.
  (are [text] (true? (rf.mcp-base.envelope/marker-text? text))
    (pr-str {rf.mcp-base.vocab/overflow-key {:limit :reached :token-count 9000 :cap-tokens 5000
                                             :tool "snapshot" :hint "narrow"}})
    "#:rf.mcp{:cache-hit {:tool \"x\"}}"
    "{:rf.mcp/cache-hit {:tool \"x\"}}"
    "{:rf.mcp/overflow {:limit :reached :extra :ok}}"))

(deftest marker-text?-rejects-anything-but-a-closed-sub-cap-single-key-marker
  ;; A marker skips cap enforcement, so each of these would be a cap
  ;; bypass if it were called one.
  (are [text] (false? (rf.mcp-base.envelope/marker-text? text))
    nil
    (pr-str {:trace [1 2 3]})
    "{:rf.mcp/overflowed {:x 1}}"                                      ; lookalike key
    "#:rf.mcp{:overflowed {:x 1}}"                                     ; lookalike key, namespaced form
    "{:rf.mcp/overflow {:limit :reached} :unexpected 1}"               ; a top-level sibling
    "{:rf.mcp/overflow {:limit :reached}} 42"                          ; a trailing form
    "{:rf.mcp/overflow {:limit :reached}}] {:junk 1}"                  ; `]` ending the read early
    "{:rf.mcp/overflow {:at #inst \"2024-01-01T00:00:00.000-00:00\"}}" ; a tagged literal
    "{:rf.mcp/overflow 42}"                                            ; a non-map body
    "{:rf.mcp/overflow {:limit :reached}"                              ; truncated
    (pr-str {rf.mcp-base.vocab/overflow-key                            ; a body over the default cap
             {:limit :reached
              :blob  (apply str (repeat (* 8 rf.mcp-base.overflow/default-max-tokens) "x"))}})))
