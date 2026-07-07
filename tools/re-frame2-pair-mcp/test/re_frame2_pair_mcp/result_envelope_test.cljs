(ns re-frame2-pair-mcp.result-envelope-test
  "Unit tests for the typed result codec.

  Two halves:

    1. `wrap-form` — the runtime-side classifier source. We assert the
       emitted CLJS source SHAPE (substring pins) — the actual runtime
       behaviour is exercised end-to-end against a live shadow build;
       here we pin that the wrap routes through `cljs.reader/read-string`
       (the round-trip serializability probe) and tags the four
       outcomes.

    2. `envelope->result` / `error?` — the server-side projection. This
       is the load-bearing logic: a tagged envelope MUST project onto
       the tool's `:ok?` vocabulary so nil / eval-error / unserializable
       are DISTINCT, and an untagged value MUST flow through unchanged
       (additive codec)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.result-envelope :as renv]))

;; ---------------------------------------------------------------------------
;; wrap-form — runtime-side classifier source.
;; ---------------------------------------------------------------------------

(deftest wrap-form-embeds-the-user-form
  (let [src (renv/wrap-form "(+ 1 2)")]
    (is (str/includes? src "(+ 1 2)")
        "the user form rides inside the wrap")))

(deftest wrap-form-round-trip-probes-serializability
  (let [src (renv/wrap-form "(some-form)")]
    (is (str/includes? src "cljs.reader/read-string")
        "the wrap round-trip-probes via read-string — the serializability test")
    (is (str/includes? src "cljs.core/pr-str")
        "the wrap pr-str's the value to probe + preview")))

(deftest wrap-form-tags-all-four-outcomes
  (let [src (renv/wrap-form "(form)")]
    (is (str/includes? src ":eval-error") "carries the eval-error tag")
    (is (str/includes? src ":nil") "carries the genuine-nil tag")
    (is (str/includes? src ":value") "carries the value tag")
    (is (str/includes? src ":unserializable") "carries the unserializable tag")
    (is (str/includes? src "#object") "detects the #object unreadable token")
    (is (str/includes? src "#js") "detects the #js unreadable token")))

(deftest wrap-form-catches-classifier-failure
  (let [src (renv/wrap-form "(form)")]
    (is (str/includes? src ":rf.error/result-envelope-wrap-failed")
        "an outer try guards the classifier itself so it never throws on the wire")))

;; ---------------------------------------------------------------------------
;; REPL-special pass-through.
;;
;; shadow only honours `require` / `ns` / etc. special compilation at the
;; top level; wrapping them in the classifier's expression context breaks
;; them (`SyntaxError: Unexpected token ';'` → the require's module never
;; loads). `wrap-form` MUST return these verbatim.
;; ---------------------------------------------------------------------------

(deftest repl-special?-matches-the-special-heads
  (is (renv/repl-special? "(require 're-frame.epoch)") "require")
  (is (renv/repl-special? "  (require 'foo)") "leading whitespace tolerated")
  (is (renv/repl-special? "(ns my.app)") "ns")
  (is (renv/repl-special? "(in-ns 'foo)") "in-ns")
  (is (renv/repl-special? "(import [goog.string])") "import")
  (is (renv/repl-special? "(require-macros 'm)") "require-macros")
  (is (renv/repl-special? "(load-file \"x.cljs\")") "load-file"))

(deftest repl-special?-rejects-ordinary-forms
  (is (not (renv/repl-special? "(+ 1 2)")) "arithmetic is not special")
  (is (not (renv/repl-special? "(require-something)"))
      "a longer symbol that merely starts with 'require' is NOT the require special")
  (is (not (renv/repl-special? "(requirements)")) "near-miss head rejected")
  (is (not (renv/repl-special? "42")) "a bare value is not special")
  (is (not (renv/repl-special? "(re-frame.core/dispatch [:e])")) "namespaced call")
  (is (not (renv/repl-special? "'(require 'x)"))
      "a QUOTED require is data, not a special invocation"))

(deftest wrap-form-returns-repl-special-verbatim
  (let [form "(require 're-frame.epoch)"]
    (is (= form (renv/wrap-form form))
        "a require form rides VERBATIM — NOT wrapped (shadow needs it top-level)")
    (is (not (str/includes? (renv/wrap-form form) ":rf.mcp/result"))
        "no codec wrapper around a REPL special"))
  ;; an ordinary form is still wrapped
  (is (str/includes? (renv/wrap-form "(+ 1 2)") ":rf.mcp/result")
      "ordinary forms still get the classifier wrap"))

;; ---------------------------------------------------------------------------
;; envelope->result — the four tags.
;; ---------------------------------------------------------------------------

(defn- echo-value
  "Trivial on-value shaper for the tests — wraps the genuine value in
  the eval-cljs-shaped success map."
  [v]
  {:ok? true :value v})

(deftest projects-value-tag-through-on-value
  (let [r (renv/envelope->result {:rf.mcp/result :value :value 42} echo-value)]
    (is (= {:ok? true :value 42} r) "the genuine value reaches on-value")
    (is (not (renv/error? r)) "a value is not an error")))

(deftest projects-nil-tag-as-genuine-nil
  (let [r (renv/envelope->result {:rf.mcp/result :nil} echo-value)]
    (is (= {:ok? true :value nil} r)
        "the :nil tag delivers a GENUINE nil to on-value — distinct from a collapsed null")
    (is (not (renv/error? r)))))

(deftest projects-eval-error-tag-as-structured-failure
  (let [env {:rf.mcp/result :eval-error
             :reason :rf.error/eval-cljs-threw
             :ex "#error {:message \"boom\"}"
             :message "boom"
             :ex-data {:foo 1}}
        r   (renv/envelope->result env echo-value)]
    (is (false? (:ok? r)))
    (is (= :rf.error/eval-cljs-threw (:reason r)))
    (is (= "#error {:message \"boom\"}" (:ex r)) "the ex text rides back")
    (is (= "boom" (:message r)))
    (is (= {:foo 1} (:ex-data r)) "ex-data carried when present")
    (is (renv/error? r) "an eval-error IS a codec error → err-text")))

(deftest projects-unserializable-tag-with-preview
  (let [env {:rf.mcp/result :unserializable
             :type "function"
             :preview "#object[Function ...]"}
        r   (renv/envelope->result env echo-value)]
    (is (false? (:ok? r)))
    (is (= :rf.error/unserializable (:reason r)))
    (is (= "function" (:type r)))
    (is (= "#object[Function ...]" (:preview r)) "the preview shows WHAT it was")
    (is (string? (:hint r)) "a corrective hint suggests projecting to data")
    (is (renv/error? r))))

;; ---------------------------------------------------------------------------
;; The three outcomes are DISTINCT — the headline codec invariant.
;; ---------------------------------------------------------------------------

(deftest nil-eval-error-unserializable-are-distinct
  (let [nil-r   (renv/envelope->result {:rf.mcp/result :nil} echo-value)
        err-r   (renv/envelope->result {:rf.mcp/result :eval-error
                                        :reason :rf.error/eval-cljs-threw :ex "x"}
                                       echo-value)
        unser-r (renv/envelope->result {:rf.mcp/result :unserializable
                                        :type "object" :preview "#js {}"}
                                       echo-value)]
    (is (not (renv/error? nil-r)) "genuine nil is a SUCCESS")
    (is (renv/error? err-r) "eval-error is a FAILURE")
    (is (renv/error? unser-r) "unserializable is a FAILURE")
    (is (not= (:reason err-r) (:reason unser-r))
        "eval-error and unserializable carry DISTINCT reasons")
    (is (not (contains? nil-r :reason))
        "the genuine-nil success carries no failure reason")))

;; ---------------------------------------------------------------------------
;; Untagged values flow through unchanged (additive codec).
;; ---------------------------------------------------------------------------

(deftest untagged-value-flows-through-on-value
  (testing "a runtime that predates the wrap (or a tool that didn't wrap) is unaffected"
    (is (= {:ok? true :value 7} (renv/envelope->result 7 echo-value)))
    (is (= {:ok? true :value {:a 1}} (renv/envelope->result {:a 1} echo-value)))
    (is (= {:ok? true :value nil} (renv/envelope->result nil echo-value))
        "an untagged nil still reaches on-value — no tag, no special-casing")))

(deftest untagged-map-without-result-key-is-not-tagged
  (testing "a runtime map that merely has other keys is NOT mistaken for an envelope"
    (let [m {:ok? false :reason :not-registered}]
      (is (not (renv/tagged? m)))
      (is (= m (renv/envelope->result m identity))
          "the on-value receives the map verbatim — :not-registered stays a legit answer")
      (is (not (renv/error? (renv/envelope->result m identity)))
          "an on-value result with :ok? false is NOT a codec error — rides back as ok-text"))))

;; ---------------------------------------------------------------------------
;; error? reads metadata, not :ok? — so an on-value result carrying
;; :ok? false (a legitimate structured answer) is NOT a transport error.
;; ---------------------------------------------------------------------------

(deftest error-predicate-distinguishes-codec-error-from-ok-false-answer
  (let [legit-miss (renv/envelope->result {:rf.mcp/result :value
                                           :value {:ok? false :reason :not-registered}}
                                          identity)
        codec-err  (renv/envelope->result {:rf.mcp/result :eval-error :ex "x"}
                                           identity)]
    (is (not (renv/error? legit-miss))
        "a value that happens to be {:ok? false} is NOT a codec error")
    (is (renv/error? codec-err)
        "a codec error tag IS a codec error")))

(deftest mark-codec-error-flags-a-tool-built-defect-map
  ;; rf2-acckgr: a tool's OWN `on-value` shaper can detect a defect the
  ;; runtime should never produce against a healthy build (handler-meta's
  ;; `:unexpected-shape`) — that isn't one of `envelope->result`'s own
  ;; tagged outcomes, but IS a genuine fault, not a legitimate structured
  ;; miss like `:not-registered`. `mark-codec-error` lets the tool opt
  ;; that map into the SAME `error?` routing the codec's own tags get.
  (let [defect (renv/mark-codec-error {:ok? false :reason :unexpected-shape :value 42})]
    (is (= {:ok? false :reason :unexpected-shape :value 42} defect)
        "the map's data is unchanged — only metadata is added")
    (is (renv/error? defect)
        "a marked map routes through error? as a genuine codec error")
    (is (renv/error? (assoc defect :kind :event))
        "the mark survives assoc — metadata propagates through persistent map ops, matching how handler-meta's stamp-frame further shapes the map")))

(deftest truncate-preview-caps-long-text
  (let [long-text (apply str (repeat 500 "x"))
        out       (tu/truncate-preview long-text)]
    (is (str/includes? out "chars)") "a long preview carries the char-count suffix")
    (is (< (count out) 500) "the preview is truncated under the source length"))
  (is (= "short" (tu/truncate-preview "short"))
      "a short preview is returned verbatim"))
