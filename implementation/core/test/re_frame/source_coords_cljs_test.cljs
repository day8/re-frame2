(ns re-frame.source-coords-cljs-test
  "CLJS-side regression test for rf2-mdjp — the `re-frame.core` reg-*
  macros were reading Clojure's `*file*` at expansion time, but the
  CLJS analyzer never binds `*file*` during macro expansion (it binds
  `cljs.analyzer/*cljs-file*` instead). On CLJS that left `*file*` at
  the JVM compiler's default `\"NO_SOURCE_PATH\"` sentinel, which then
  got baked into every registration's source-coord `:file` slot —
  defeating jump-to-source and tooling that reads `(rf/handler-meta
  kind id)`.

  The fix (mirroring rf2-ulxi / Story-side PR #340) prefers
  `(:file (meta &form))` over `*file*`. tools.reader's
  indexing-push-back-reader stamps `:file` on every collection-form's
  metadata, which survives the macro-expansion handoff to cljs.analyzer
  — so the form-meta path is the portable answer across both
  compilation hosts. The shared helper lives in
  `re-frame.source-coords/coords-form` and the existing
  `re-frame.source-coords-test` covers the JVM path; this test exercises
  the CLJS path end-to-end.

  Failure mode on `main` (pre-fix): every reg-event-db (and every other
  reg-* macro) below would carry `:file \"NO_SOURCE_PATH\"` in its
  registered metadata. After the fix, `:file` either resolves to the
  real source path (the common shadow-cljs path) or is omitted entirely
  (when no form-meta `:file` is available and `*file*` is the sentinel)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.source-coords :as source-coords]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture))

;; ---- helper -----------------------------------------------------------------

(defn- file-is-real?
  "A `:file` slot is real when it's a non-empty string that is NOT the
  cljs analyzer / JVM compiler `\"NO_SOURCE_PATH\"` sentinel."
  [f]
  (and (string? f)
       (seq f)
       (not= "NO_SOURCE_PATH" f)))

;; ---- per-kind assertions ----------------------------------------------------
;;
;; Each test registers a handler via the public re-frame.core macro
;; surface and asserts the resulting handler-meta carries either a real
;; `:file` (the common shadow-cljs path where tools.reader attached
;; `:file` to the form's metadata) OR omits the slot entirely (the
;; pathological case where both sources resolved to the sentinel). What
;; MUST NOT happen is the slot being present and equal to the
;; `\"NO_SOURCE_PATH\"` sentinel — that's the bug rf2-mdjp tracks.

(deftest reg-event-db-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-event-db emits a real :file under CLJS, not NO_SOURCE_PATH"
    (rf/reg-event-db :rf2-mdjp/reg-event-db-sample
                     (fn [db _] db))
    (let [m (rf/handler-meta :event :rf2-mdjp/reg-event-db-sample)
          f (:file m)]
      (is (some? m))
      (is (not= "NO_SOURCE_PATH" f)
          ":file must NOT be the cljs.analyzer NO_SOURCE_PATH sentinel")
      (when (some? f)
        (is (file-is-real? f)
            ":file when present must be a real source path")))))

(deftest reg-event-fx-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-event-fx emits a real :file under CLJS"
    (rf/reg-event-fx :rf2-mdjp/reg-event-fx-sample
                     (fn [_ _] {}))
    (let [f (:file (rf/handler-meta :event :rf2-mdjp/reg-event-fx-sample))]
      (is (not= "NO_SOURCE_PATH" f)))))

(deftest reg-event-ctx-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-event-ctx emits a real :file under CLJS"
    (rf/reg-event-ctx :rf2-mdjp/reg-event-ctx-sample
                      (fn [ctx] ctx))
    (let [f (:file (rf/handler-meta :event :rf2-mdjp/reg-event-ctx-sample))]
      (is (not= "NO_SOURCE_PATH" f)))))

(deftest reg-sub-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-sub emits a real :file under CLJS"
    (rf/reg-sub :rf2-mdjp/reg-sub-sample
                (fn [db _] db))
    (let [f (:file (rf/handler-meta :sub :rf2-mdjp/reg-sub-sample))]
      (is (not= "NO_SOURCE_PATH" f)))))

(deftest reg-fx-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-fx emits a real :file under CLJS"
    (rf/reg-fx :rf2-mdjp/reg-fx-sample (fn [_ _] nil))
    (let [f (:file (rf/handler-meta :fx :rf2-mdjp/reg-fx-sample))]
      (is (not= "NO_SOURCE_PATH" f)))))

(deftest reg-cofx-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-cofx emits a real :file under CLJS"
    (rf/reg-cofx :rf2-mdjp/reg-cofx-sample (fn [ctx] ctx))
    (let [f (:file (rf/handler-meta :cofx :rf2-mdjp/reg-cofx-sample))]
      (is (not= "NO_SOURCE_PATH" f)))))

;; reg-frame and reg-view exercise the same source-coords path; they
;; require an adapter to be installed for the underlying registration
;; (frame/reg-frame allocates substrate state, reg-view delegates to
;; the Reagent-aware impl). The JVM test ns covers those macros with
;; the plain-atom adapter installed in its fixture; the helper unit
;; tests below pin the actual rf2-mdjp invariant under CLJS so we
;; don't need adapter wiring here just to assert :file-resolution.

;; ---- direct helper tests (mirrors story_source_coords_test.clj) -------------
;;
;; The reg-* macros expand at compile time, so the assertions above
;; observe whatever the CLJS analyzer happens to provide for
;; `(meta &form)` in the test runner's build pipeline. To pin down the
;; failure-mode behaviour we also test the pure helper directly: when
;; `*file*` is the sentinel and form-meta carries a real `:file`, the
;; helper must prefer form-meta. When both are the sentinel, `:file`
;; must be omitted.

(deftest resolve-file-prefers-form-meta
  (testing "rf2-mdjp: resolve-file picks form-meta :file when *file* is NO_SOURCE_PATH"
    (is (= "src/my/app.cljs"
           (source-coords/resolve-file
             {:file "src/my/app.cljs"}
             "NO_SOURCE_PATH")))))

(deftest resolve-file-falls-back-to-bound-file
  (testing "rf2-mdjp: resolve-file falls back to *file* when form-meta lacks :file"
    (is (= "src/my/app.clj"
           (source-coords/resolve-file
             {:line 5}
             "src/my/app.clj")))))

(deftest resolve-file-omits-sentinel
  (testing "rf2-mdjp: resolve-file returns nil when both sources are NO_SOURCE_PATH"
    (is (nil? (source-coords/resolve-file
                {:file "NO_SOURCE_PATH"}
                "NO_SOURCE_PATH")))))

(deftest resolve-file-omits-when-both-nil
  (testing "rf2-mdjp: resolve-file returns nil when neither source supplies a file"
    (is (nil? (source-coords/resolve-file {} nil)))))
