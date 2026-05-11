(ns re-frame.story-source-coords-test
  "Regression test for rf2-ulxi — `:rf.assert/*` events surfaced from
  `:play` sequences were emitting source-coords with `:file
  \"NO_SOURCE_PATH\"`. Root cause: `coords-form` read `*file*` at
  macro-expansion time, but the CLJS analyzer never binds Clojure's
  `*file*` during macro expansion (it binds `cljs.analyzer/*cljs-file*`
  instead), so `*file*` retained the JVM compiler's default
  `\"NO_SOURCE_PATH\"` sentinel.

  The fix prefers `(:file (meta &form))` — the CLJS analyzer reads
  source files via tools.reader's `indexing-push-back-reader` which
  attaches `{:file ...}` to every collection-form's metadata, so the
  form-meta path is the portable answer across both compilation hosts.

  This test is JVM-only because the macro helpers live in `.clj` (only
  visible from JVM). The CLJS path is exercised transitively: a
  `(meta &form)` with `:file \"some/file.cljs\"` is what the analyzer
  hands the macro on CLJS, and we assert that value lands in the
  emitted coords map.

  Failure mode on `main` (pre-fix): `coords-form` ignored `(:file
  form-meta)` entirely and used the `file` arg (`*file*`) — which the
  caller in `re-frame.story.cljc` passes as `*file*`. With `*file*` set
  to `\"NO_SOURCE_PATH\"`, the emitted coords map carries
  `:file \"NO_SOURCE_PATH\"`. The test below binds the `file` arg to
  the sentinel and asserts the form-meta wins."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.macros :as macros]))

;; ---- helper -----------------------------------------------------------------

(defn- eval-coords-form
  "Evaluate the syntax-quoted form `coords-form` returns. The form is a
  `(cond-> {:ns 'sym} <truthy-test> (assoc ...))` expression — `eval`
  in this ns produces the runtime map the macro expansion would build."
  [form-meta file ns-sym]
  (eval (macros/coords-form form-meta file ns-sym)))

;; ---- the regression --------------------------------------------------------

(deftest file-prefers-form-meta-over-bound-file
  (testing "rf2-ulxi: when (meta &form) carries :file, that wins over the
            *file* arg — covers the CLJS analyzer case where *file* is
            unbound (or stuck at NO_SOURCE_PATH) but the reader has
            attached :file to the form's metadata"
    (let [coords (eval-coords-form
                   {:line 196 :column 3 :file "counter_with_stories/stories.cljs"}
                   "NO_SOURCE_PATH"                ; the CLJS macro-expansion default
                   'counter-with-stories.stories)]
      (is (= "counter_with_stories/stories.cljs" (:file coords))
          ":file must come from the form-meta, not the NO_SOURCE_PATH sentinel")
      (is (not= "NO_SOURCE_PATH" (:file coords))
          ":file must NOT be the cljs.analyzer NO_SOURCE_PATH sentinel")
      (is (= 196 (:line coords)) ":line still captured")
      (is (= 3 (:column coords)) ":column still captured")
      (is (= 'counter-with-stories.stories (:ns coords))
          ":ns still captured"))))

(deftest file-falls-back-to-bound-file-when-form-meta-missing
  (testing "JVM compilation: (meta &form) has no :file (clojure.lang.LispReader
            only attaches :line/:column), so coords-form falls back to the
            *file* arg the macro captured"
    (let [coords (eval-coords-form
                   {:line 42 :column 1}            ; JVM reader — no :file
                   "src/my/ns.clj"
                   'my.ns)]
      (is (= "src/my/ns.clj" (:file coords))
          ":file falls back to the bound *file* on JVM")
      (is (= 42 (:line coords)))
      (is (= 1  (:column coords)))
      (is (= 'my.ns (:ns coords))))))

(deftest file-omitted-when-both-sources-are-sentinel
  (testing "rf2-ulxi: if BOTH (meta &form) :file and *file* resolve to
            NO_SOURCE_PATH (pathological cljs case), omit :file entirely
            rather than poisoning the slot with the sentinel"
    (let [coords (eval-coords-form
                   {:line 1 :column 1 :file "NO_SOURCE_PATH"}
                   "NO_SOURCE_PATH"
                   'some.ns)]
      (is (not (contains? coords :file))
          ":file is omitted rather than carrying the NO_SOURCE_PATH sentinel")
      (is (= 1 (:line coords)))
      (is (= 'some.ns (:ns coords))))))

(deftest file-omitted-when-both-sources-are-nil
  (testing "no :file anywhere — omit the slot"
    (let [coords (eval-coords-form
                   {:line 7 :column 3}             ; no :file in meta
                   nil                              ; no *file* either
                   'some.ns)]
      (is (not (contains? coords :file))
          ":file is omitted when no source is available")
      (is (= 7 (:line coords))))))

(deftest reg-variant-macro-end-to-end
  (testing "rf2-ulxi end-to-end: the gen-reg-call expansion (which
            reg-variant feeds) carries the form-meta :file through to
            the *pending-coords* binding form — covers the actual macro
            path Story uses for variants whose :play sequences emit
            :rf.assert/* events"
    (let [form-meta {:line 42 :column 3 :file "stories.cljs"}
          expansion (macros/gen-reg-call
                      form-meta
                      "NO_SOURCE_PATH"             ; simulate CLJS *file*
                      'my.app.stories
                      'irrelevant-reg-fn
                      :my.app/variant
                      {:doc "x"})
          ;; The expansion is `(when ... (binding [*pending-coords*
          ;; (cond-> ...)] ...))`. The coords map literal lives inside
          ;; the binding form — pull it out and evaluate to inspect.
          ;; Structure: (when _ (binding [_ <coords-form>] _))
          binding-form (-> expansion (nth 2))      ; (binding [...] ...)
          coords-form  (-> binding-form second second)  ; the (cond-> ...) form
          coords       (eval coords-form)]
      (is (= "stories.cljs" (:file coords))
          "the :file in the emitted *pending-coords* binding comes from form-meta")
      (is (not= "NO_SOURCE_PATH" (:file coords))
          "NO_SOURCE_PATH must not leak into the coords map")
      (is (= 42 (:line coords)))
      (is (= 3  (:column coords)))
      (is (= 'my.app.stories (:ns coords))))))
