(ns re-frame.story-authoring-validation-test
  "JVM tests closing the docs-promised gap on macro-time validation +
  source-coordinate stamping at the authoring surface.

  Spec coverage (rf2-ub1n4): `tools/story/spec/001-Authoring.md` §
  Source-coord stamping and § Registration macros.

  Two surfaces are exercised here that the browser smoke does not:

  - **Macro-time validation.** The `reg-story` / `reg-variant` /
    `reg-workspace` / `reg-decorator` / `reg-mode` / `reg-story-panel`
    / `reg-tag` macros all funnel through `re-frame.story.macros/
    gen-reg-call` and `expand-reg-story`. The expansion form binds
    `*pending-coords*` from `(meta &form)` and calls the runtime
    `reg-*!` helper, which runs the malli schema check + tag-vocab
    cross-check + extends resolution. The cross-cutting contract is:
    an invalid body raises `:rf.error/<kind>-shape` / `:rf.error/
    unknown-tag` / `:rf.error/extends-unknown` / `:rf.error/extends-
    cycle` with a clear message and an `ex-data` map carrying the
    error key. We assert the error shape for each kind so a future
    schema change that drops a key from `ex-data` is caught.

  - **Source-coord stamping.** Every `reg-*` macro stamps `:file` +
    `:line` + `:ns` + `:column` from `&form` meta into the registered
    body's `:source` slot. The `story_source_coords_test` covers the
    `coords-form` helper in isolation; here we cover the end-to-end
    path through `expand-reg-story` for the Form-B `:variants` sugar
    — the parent story AND each generated child variant must carry
    the same source-coord stamp because both originate from the
    same `&form`.

  Per spec/001 §Source-coord stamping: variants generated from the
  combined `reg-story` form inherit the parent's `&form` meta, since
  the macro expands them at the parent's expansion site. A consumer
  authoring the combined form expects every generated variant's
  `:source` to point back at the same `(reg-story ...)` call."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.macros :as macros]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; ===========================================================================
;; ERROR-SHAPE CONTRACT — every registration raises with structured ex-data
;; ===========================================================================

(deftest reg-variant-bad-shape-carries-error-key
  (testing "an invalid variant body raises with :rf.error in ex-data"
    (try
      (story/reg-variant :story.auth.bad/v
        {:tags "not-a-set"})                         ; :tags must be a set
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error (ex-data e)))
            "ex-data carries the :rf.error/variant-shape sentinel")
        (is (re-find #"variant schema" (.getMessage e))
            "message names the failing kind")))))

(deftest reg-workspace-bad-shape-carries-error-key
  (testing "an invalid workspace body raises with :rf.error in ex-data"
    (try
      (story/reg-workspace :Workspace.bad/empty
        {:layout :unknown-layout})                   ; :layout must be one of five
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/workspace-shape (:rf.error (ex-data e))))
        (is (re-find #"workspace schema" (.getMessage e)))))))

(deftest reg-decorator-bad-shape-carries-error-key
  (testing "an invalid decorator body raises with :rf.error in ex-data"
    (try
      (story/reg-decorator :bad-decorator
        {:kind :not-a-decorator-kind})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/decorator-shape (:rf.error (ex-data e))))))))

(deftest reg-tag-bad-shape-carries-error-key
  (testing "an invalid tag body raises with :rf.error in ex-data"
    (try
      (story/reg-tag :bad/default-filter
        {:default-filter :sometimes})                ; only :include / :exclude
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/tag-shape (:rf.error (ex-data e))))))))

(deftest reg-mode-bad-shape-carries-error-key
  (testing "an invalid mode body raises with :rf.error in ex-data"
    (try
      (story/reg-mode :Mode.bad/missing-args
        {:args "not-a-map"})                         ; :args must be a map
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/mode-shape (:rf.error (ex-data e))))))))

(deftest reg-story-panel-bad-shape-carries-error-key
  (testing "an invalid story-panel body raises with :rf.error in ex-data"
    (try
      (story/reg-story-panel :rf.story/bad-panel
        {:placement :nowhere                          ; not one of the five
         :render    :some/view})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-panel-shape (:rf.error (ex-data e))))))))

;; ===========================================================================
;; UNKNOWN-TAG CONTRACT — tag-vocab cross-check error carries the offending set
;; ===========================================================================

(deftest reg-variant-unknown-tag-lists-offenders
  (testing "an unregistered tag on a variant raises with the offender list"
    (try
      (story/reg-variant :story.tag/v
        {:events []
         :tags   #{:dev :totally-made-up :also-fake}})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/unknown-tag (:rf.error (ex-data e))))
        (let [unknown (set (:unknown (ex-data e)))]
          (is (contains? unknown :totally-made-up))
          (is (contains? unknown :also-fake))
          (is (not (contains? unknown :dev))
              "registered canonical tags are not offenders"))))))

;; ===========================================================================
;; EXTENDS ERROR-SHAPE
;; ===========================================================================

(deftest reg-variant-extends-unknown-carries-parent-id
  (testing ":extends to an unregistered variant carries the missing parent id"
    (try
      (story/reg-variant :story.x/child
        {:extends :story.x/no-such-parent
         :events  []})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/extends-unknown (:rf.error (ex-data e))))
        (is (= :story.x/no-such-parent (:parent (ex-data e))))))))

;; ===========================================================================
;; CANONICAL-ID-GRAMMAR ERROR
;; ===========================================================================

(deftest reg-variant-non-keyword-id-rejected
  (testing "a non-keyword variant id is rejected — the id-grammar check
            fires first and complains, carrying :rf.error/variant-id-shape"
    (try
      (story/reg-variant* "not-a-keyword" {:events []})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-id-shape (:rf.error (ex-data e))))
        (is (re-find #"canonical id grammar" (.getMessage e)))))))

(deftest reg-workspace-bad-id-grammar-rejected
  (testing "a workspace id outside :Workspace.<path>/<name> is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"canonical id grammar"
                          (story/reg-workspace* :NotAWorkspaceId {:layout :variants-grid})))))

;; ===========================================================================
;; SOURCE-COORD STAMPING — END-TO-END VIA THE FORM-B EXPANSION
;; ===========================================================================
;;
;; The story_source_coords_test covers `coords-form` in isolation. Here
;; we exercise the path the *combined form* takes — `expand-reg-story`
;; emits N independent `reg-variant*` calls, each of which must inherit
;; the parent's source-coord stamp because all the expansions originate
;; from the same `&form` meta.

(deftest combined-form-stamps-source-on-parent-story
  (testing "a Form-B (:variants sugar) reg-story stamps :source on the parent"
    (story/reg-story :story.combined.src
      {:doc      "parent."
       :variants {:a {:events [[:init]]}}})
    (let [body (story/handler-meta :story :story.combined.src)]
      (is (map? (:source body)))
      (is (= 're-frame.story-authoring-validation-test (:ns (:source body))))
      (is (integer? (:line (:source body)))))))

(deftest combined-form-stamps-source-on-generated-variants
  (testing "a Form-B reg-story stamps :source on each generated child variant
            — the variants are expanded at the parent's macro site, so each
            child inherits the same `&form` line/file/ns. Per spec/001
            §Source-coord stamping the IDE 'Open in editor' affordance reads
            this slot for both stories and variants generated from sugar."
    (story/reg-story :story.combined.gen
      {:doc      "parent with two generated variants."
       :variants {:a {:events [[:init-a]]}
                  :b {:events [[:init-b]]}}})
    (let [body-a (story/handler-meta :variant :story.combined.gen/a)
          body-b (story/handler-meta :variant :story.combined.gen/b)]
      (is (map? (:source body-a)) "child :a carries :source")
      (is (map? (:source body-b)) "child :b carries :source")
      (is (= 're-frame.story-authoring-validation-test
             (:ns (:source body-a))))
      (is (= 're-frame.story-authoring-validation-test
             (:ns (:source body-b))))
      (is (= (:line (:source body-a))
             (:line (:source body-b)))
          "both generated variants share the parent's expansion line"))))

;; ===========================================================================
;; MACRO HELPER — `variant-id-for` enforces keyword grammar
;; ===========================================================================
;;
;; The Form-B desugaring relies on `variant-id-for` to build the
;; per-variant id (e.g. `:story.foo` × `:a` → `:story.foo/a`). The helper
;; rejects non-keyword arguments synchronously so a typo in a `:variants`
;; map key (`"a"` vs `:a`) trips at macro-expansion rather than producing
;; a malformed registry id.

(deftest variant-id-for-rejects-non-keyword-story-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"story id must be a keyword"
                        (macros/variant-id-for "story.foo" :a))))

(deftest variant-id-for-rejects-non-keyword-variant-name
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"variant-name in :variants map"
                        (macros/variant-id-for :story.foo "a"))))

(deftest variant-id-for-builds-canonical-id
  (testing "the canonical :story.<path>/<variant> id shape"
    (is (= :story.auth.login-form/empty
           (macros/variant-id-for :story.auth.login-form :empty)))
    (is (= :story.foo/bar
           (macros/variant-id-for :story.foo :bar)))))
