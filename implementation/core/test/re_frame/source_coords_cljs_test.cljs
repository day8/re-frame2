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

  Failure mode on `main` (pre-fix): every reg-event (and every other
  reg-* macro) below would carry `:file \"NO_SOURCE_PATH\"` in its
  registered metadata. After the fix, `:file` either resolves to the
  real source path (the common shadow-cljs path) or is omitted entirely
  (when no form-meta `:file` is available and `*file*` is the sentinel)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.context :as adapter-context]
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

(deftest reg-event-file-is-not-no-source-path
  (testing "EP-0018 C (rf2-xhfxcs.3): the ONE public `reg-event` macro emits a
  real :file under CLJS, not NO_SOURCE_PATH — same coord-capture path as the
  legacy reg-event-* macros (consolidated macro layer)"
    (rf/reg-event :rf2-mdjp/reg-event-sample
                  (fn [{:keys [db]} _] {:db db}))
    (let [m (rf/handler-meta :event :rf2-mdjp/reg-event-sample)
          f (:file m)]
      (is (some? m))
      (is (not= "NO_SOURCE_PATH" f)
          ":file must NOT be the cljs.analyzer NO_SOURCE_PATH sentinel")
      (when (some? f)
        (is (file-is-real? f)
            ":file when present must be a real source path")))))

(deftest reg-event-db-return-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-event with a {:db ...} return emits a real :file under CLJS, not NO_SOURCE_PATH"
    (rf/reg-event :rf2-mdjp/reg-event-db-sample
                     (fn [{:keys [db]} _] {:db db}))
    (let [m (rf/handler-meta :event :rf2-mdjp/reg-event-db-sample)
          f (:file m)]
      (is (some? m))
      (is (not= "NO_SOURCE_PATH" f)
          ":file must NOT be the cljs.analyzer NO_SOURCE_PATH sentinel")
      (when (some? f)
        (is (file-is-real? f)
            ":file when present must be a real source path")))))

(deftest reg-event-fx-return-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-event with an effect-map return emits a real :file under CLJS"
    (rf/reg-event :rf2-mdjp/reg-event-fx-sample
                     (fn [_ _] {}))
    (let [f (:file (rf/handler-meta :event :rf2-mdjp/reg-event-fx-sample))]
      (is (not= "NO_SOURCE_PATH" f)))))

(deftest reg-event-with-interceptor-file-is-not-no-source-path
  (testing "rf2-mdjp: reg-event with a full-context interceptor emits a real :file under CLJS"
    (rf/reg-interceptor* :rf2-mdjp/ctx-probe {:before (fn [ctx] ctx)})
    (rf/reg-event :rf2-mdjp/reg-event-ctx-sample
                  {:interceptors [:rf2-mdjp/ctx-probe]}
                  (fn [_ _] {}))
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
    (rf/reg-cofx :rf2-mdjp/reg-cofx-sample (fn [] :sample))
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

;; ---- parse-source-coord (rf2-nr7vf2) ----------------------------------------
;;
;; The canonical inverse of `format-source-coord`, collapsing the parser
;; that was reimplemented near-byte-for-byte in Story's element_inspector
;; and the re-frame2-pair preload runtime. These tests pin the four-segment
;; parse contract (Spec 006 §Attribute value format) and the round-trip
;; against the REAL `re-frame.adapter.context/format-source-coord` so the
;; format + parse pair can never drift apart.

(deftest parse-source-coord-canonical-shape
  (testing "rf2-nr7vf2: a four-segment value parses to {:ns :handler-id :line :col}"
    (is (= {:ns "counter.core" :handler-id "counter-buttons" :line 47 :col 11}
           (source-coords/parse-source-coord "counter.core:counter-buttons:47:11")))))

(deftest parse-source-coord-degraded-placeholders
  (testing "rf2-nr7vf2: `?` placeholders parse the id portion; line/col are nil"
    (is (= {:ns "rf.x" :handler-id "programmatic" :line nil :col nil}
           (source-coords/parse-source-coord "rf.x:programmatic:?:?")))
    (is (= {:ns "ns.x" :handler-id "view" :line 42 :col nil}
           (source-coords/parse-source-coord "ns.x:view:42:?")))))

(deftest parse-source-coord-dotted-and-hyphenated
  (testing "rf2-nr7vf2: dotted ns + hyphenated handler-id parse cleanly"
    (is (= {:ns "my-app.cart.view" :handler-id "apply-coupon-button" :line 125 :col 4}
           (source-coords/parse-source-coord "my-app.cart.view:apply-coupon-button:125:4")))))

(deftest parse-source-coord-malformed-returns-nil
  (testing "rf2-nr7vf2: malformed input returns nil and never throws"
    (is (nil? (source-coords/parse-source-coord "ns:view:42")))      ; too few
    (is (nil? (source-coords/parse-source-coord "ns:view")))
    (is (nil? (source-coords/parse-source-coord "a:b:1:2:3")))       ; too many
    (is (nil? (source-coords/parse-source-coord ":handler:1:2")))    ; empty ns
    (is (nil? (source-coords/parse-source-coord "ns::1:2")))         ; empty sym
    (is (nil? (source-coords/parse-source-coord "")))
    (is (nil? (source-coords/parse-source-coord nil)))
    (is (nil? (source-coords/parse-source-coord 42)))
    (is (nil? (source-coords/parse-source-coord :keyword)))))

(deftest format-then-parse-round-trips
  (testing "rf2-nr7vf2: (parse-source-coord (format-source-coord id coords)) recovers id + coords"
    (let [round-trip (fn [id coords]
                       (source-coords/parse-source-coord
                         (adapter-context/format-source-coord id coords)))]
      ;; numeric line + col
      (is (= {:ns "counter.core" :handler-id "counter-buttons" :line 47 :col 11}
             (round-trip :counter.core/counter-buttons {:line 47 :column 11})))
      ;; line only (column not captured -> `?` -> nil)
      (is (= {:ns "ns.x" :handler-id "view" :line 42 :col nil}
             (round-trip :ns.x/view {:line 42})))
      ;; no coords at all (programmatic) -> both `?` -> nil
      (is (= {:ns "ns.x" :handler-id "view" :line nil :col nil}
             (round-trip :ns.x/view {})))
      ;; namespaceless id -> formatter emits `?` ns, which the parser
      ;; treats as a non-empty segment (the `?` is a literal char, distinct
      ;; from an empty segment) so the round trip still recovers it
      (is (= {:ns "?" :handler-id "bare" :line 1 :col 2}
             (round-trip :bare {:line 1 :column 2}))))))

;; ---- parse-view-id (rf2-ztxnm8 / rf2-16znzb) --------------------------------
;;
;; The canonical inverse of `format-view-id`, collapsing the reader that was
;; reimplemented inline in Xray's fallback view-walker and the re-frame2-pair
;; preload runtime's `view-entity`. These tests pin the read contract (Spec 006
;; §View tagging contract §Attribute value format) and the round-trip against
;; the REAL `re-frame.adapter.context/format-view-id` so the format + parse pair
;; can never drift apart — the data-rf-view analogue of the parse-source-coord
;; round-trip above.

(deftest parse-view-id-namespaced-keyword
  (testing "rf2-ztxnm8: a stringified namespaced keyword parses back to the keyword"
    (is (= :rf.foo/bar (source-coords/parse-view-id ":rf.foo/bar")))))

(deftest parse-view-id-bare-keyword
  (testing "rf2-ztxnm8: a leading-colon body with no slash → unqualified keyword"
    (is (= :bare (source-coords/parse-view-id ":bare")))))

(deftest parse-view-id-raw-string
  (testing "rf2-ztxnm8: a non-colon-prefixed value is a non-keyword id, returned verbatim"
    (is (= "raw-string" (source-coords/parse-view-id "raw-string")))))

(deftest parse-view-id-nil-and-non-string
  (testing "rf2-ztxnm8: nil / non-string input returns nil and never throws"
    (is (nil? (source-coords/parse-view-id nil)))
    (is (nil? (source-coords/parse-view-id 42)))
    (is (nil? (source-coords/parse-view-id :keyword)))))

(deftest format-view-id-then-parse-round-trips
  (testing "rf2-ztxnm8: (parse-view-id (format-view-id id)) recovers the registry id"
    (let [round-trip (fn [id]
                       (source-coords/parse-view-id
                         (adapter-context/format-view-id id)))]
      ;; namespaced keyword id
      (is (= :rf.foo/bar (round-trip :rf.foo/bar)))
      ;; unqualified keyword id (legal at the registrar)
      (is (= :bare (round-trip :bare)))
      ;; dotted ns + hyphenated name
      (is (= :my-app.cart.view/apply-coupon-button
             (round-trip :my-app.cart.view/apply-coupon-button))))))
