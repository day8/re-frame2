(ns re-frame2-pair-mcp.result-envelope-test
  "The typed result codec. `wrap-form` builds the classifier source the
  runtime evaluates — it runs only in a live runtime, so its source is
  pinned here — and `envelope->result` projects the tagged outcomes onto a
  tool's `:ok?` vocabulary, keeping nil, eval-error and unserializable
  distinct while untagged values pass straight through."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.result-envelope :as renv]))

(deftest wrap-form-carries-every-classifier-piece
  (let [src (renv/wrap-form "(form)")]
    (doseq [needle ["cljs.reader/read-string"
                    ":eval-error" ":nil" ":value" ":unserializable"
                    "#object" "#js"
                    ":rf.error/result-envelope-wrap-failed"
                    (str "(if (> n# " renv/preview-cap ")")
                    (str "(str (subs text# 0 " renv/preview-cap ") \" …(\" n# \" chars)\")")]]
      (is (str/includes? src needle) needle))))

(defn- readable? [src]
  (try (cljs.reader/read-string src) true
       (catch :default _ false)))

(deftest wrap-form-survives-a-trailing-line-comment
  ;; Closing delimiters appended to the caller's last line would be
  ;; swallowed by a trailing `;` comment, and the form would not read.
  (let [commented "(+ 20 22) ; expected answer"]
    (is (readable? (renv/wrap-form commented)))
    (is (str/includes? (renv/wrap-form commented) "; expected answer")
        "the caller's source rides through verbatim")))

(deftest wrap-form-returns-repl-special-verbatim
  ;; shadow compiles `require`, `ns` and the other REPL specials only at
  ;; the top level, so wrapping one breaks it.
  (doseq [[form special?] [["(require 're-frame.epoch)" true]
                           ["  (require 'foo)" true]
                           ["(+ 1 2)" false]
                           ["(require-something)" false]
                           ["'(require 'x)" false]]]
    (is (= special? (= form (renv/wrap-form form))) form)))

(deftest non-error-results-reach-on-value
  (doseq [[v delivered] [[{:rf.mcp/result :value :value 42} 42]
                         [{:rf.mcp/result :nil} nil]
                         [7 7]
                         ;; a runtime map that merely lacks the tag is a legitimate answer
                         [{:ok? false :reason :not-registered} {:ok? false :reason :not-registered}]]]
    (is (= [:on-value delivered] (renv/envelope->result v #(vector :on-value %))) (pr-str v))))

(deftest projects-eval-error-tag-as-structured-failure
  (let [r (renv/envelope->result {:rf.mcp/result :eval-error
                                  :reason        :rf.error/eval-cljs-threw
                                  :ex            "#error {:message \"boom\"}"
                                  :message       "boom"
                                  :ex-data       {:foo 1}}
                                 identity)]
    (is (= {:ok? false :reason :rf.error/eval-cljs-threw :ex "#error {:message \"boom\"}"
            :message "boom" :ex-data {:foo 1}}
           r))
    (is (renv/error? r))))

(deftest projects-unserializable-tag-with-preview
  (let [r (renv/envelope->result {:rf.mcp/result :unserializable
                                  :type          "function"
                                  :preview       "#object[Function ...]"}
                                 identity)]
    (is (= {:ok? false :reason :rf.error/unserializable :type "function" :preview "#object[Function ...]"}
           (dissoc r :hint)))
    (is (string? (:hint r)) "a corrective hint suggests projecting to data")
    (is (renv/error? r))))

(deftest mark-codec-error-flags-a-tool-built-defect-map
  ;; `error?` reads the codec mark, not `:ok?`: a tool's `:ok? false` answer
  ;; rides back as ok-text unless the tool marks it as a defect.
  (let [m {:ok? false :reason :unexpected-shape :value 42}]
    (is (not (renv/error? m)))
    (is (renv/error? (renv/mark-codec-error m)))))
