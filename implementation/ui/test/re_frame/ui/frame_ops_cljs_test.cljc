(ns re-frame.ui.frame-ops-cljs-test
  "rf2-vxgfnd.184 — the `(frame)` operation-bundle runtime bridge
  (`re-frame.ui.frames/frame-ops`), host-shared (.cljc: node `test:cljs` /
  `test:ui` AND JVM `clojure -M:test`) against the REAL core machinery:

    - the bundle is the standard capture-frame shape
      `{:frame :dispatch :dispatch-sync :subscribe}`, minted by core's
      `make-capture-frame` and locked to the committed frame;
    - identity is STABLE across repeated reads within one live frame
      incarnation (rf= memo-friendly; no per-read construction);
    - ops survive scope unwind (the HOLD semantics) yet are
      INCARNATION-FENCED: a destroyed frame — and a destroyed-then-
      re-created SAME-ID frame — fails loud with the canonical
      `:rf.error/frame-destroyed`, never a silent retarget;
    - no ambient frame → the canonical `:rf.error/no-frame-context`;
      an ambient stamp naming no live frame → `:rf.error/frame-destroyed`.

  The compiled-view placement grammar rides the analyzer suites
  (analyze-accept/-reject + the error roster); mounted/structural
  placement rides frame-ops-view-jvm-test and frame-ops-dom-cljs-test."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.frames            :as frames]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            ;; no default ambient frame — the
                                            ;; no-frame-context negative needs
                                            ;; a genuinely scope-free world
                                            :ambient-frame nil})
  (fn [f]
    (frames/reset-frame-ops-cache!)
    (try (f) (finally (frames/reset-frame-ops-cache!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- reg! []
  (rf/reg-event :ops/set-n (fn [_ [_ n]] {:db {:n n}}))
  (rf/reg-sub :ops/n (fn [db _] (:n db))))

;; ===========================================================================
;; Bundle contents + committed-frame lock
;; ===========================================================================

(deftest bundle-shape-and-frame-lock
  (reg!)
  (make-frame! :ops/a {:n 1})
  (make-frame! :ops/b {:n 100})
  (let [b (rf/with-frame :ops/a (frames/frame-ops))]
    (testing "the standard capture-frame bundle keys"
      (is (= #{:frame :dispatch :dispatch-sync :subscribe} (set (keys b))))
      (is (= :ops/a (:frame b)) ":frame names the committed frame id")
      (is (fn? (:dispatch b)))
      (is (fn? (:dispatch-sync b)))
      (is (fn? (:subscribe b))))
    (testing "ops fire into the LOCKED frame after the scope has unwound"
      ((:dispatch-sync b) [:ops/set-n 7])
      (is (= {:n 7} (rf/app-db-value :ops/a)))
      (is (= {:n 100} (rf/app-db-value :ops/b)) "the sibling frame is untouched"))
    (testing "a per-call {:frame …} opt CANNOT override the lock"
      ((:dispatch-sync b) [:ops/set-n 9] {:frame :ops/b})
      (is (= {:n 9} (rf/app-db-value :ops/a)))
      (is (= {:n 100} (rf/app-db-value :ops/b))))
    (testing ":subscribe reads the locked frame"
      (is (= 9 @((:subscribe b) [:ops/n]))))))

;; ===========================================================================
;; Stable identity per live incarnation
;; ===========================================================================

(deftest bundle-identity-is-stable-within-one-incarnation
  (make-frame! :ops/stable {:n 0})
  (let [b1 (rf/with-frame :ops/stable (frames/frame-ops))
        b2 (rf/with-frame :ops/stable (frames/frame-ops))]
    (is (identical? b1 b2)
        "repeated reads return the IDENTICAL bundle — one map lookup, no
         per-render construction")
    (is (identical? (:dispatch b1) (:dispatch b2)))
    (is (identical? (:subscribe b1) (:subscribe b2)))))

(deftest bundles-are-per-frame
  (make-frame! :ops/x {})
  (make-frame! :ops/y {})
  (let [bx (rf/with-frame :ops/x (frames/frame-ops))
        by (rf/with-frame :ops/y (frames/frame-ops))]
    (is (not (identical? bx by)))
    (is (= :ops/x (:frame bx)))
    (is (= :ops/y (:frame by)))))

;; ===========================================================================
;; The canonical no-frame / dead-frame diagnostics
;; ===========================================================================

(deftest no-ambient-frame-fails-loud
  (is (= :rf.error/no-frame-context (err-id #(frames/frame-ops)))
      "no scope anywhere — the runtime never invents a frame"))

(deftest ambient-stamp-naming-no-live-frame-fails-loud
  (is (= :rf.error/frame-destroyed
         (err-id #(binding [frame/*current-frame* :ops/ghost]
                    (frames/frame-ops))))
      "a scope stamp naming an absent/destroyed frame is the
       registry-lookup category, not no-frame-context"))

;; ===========================================================================
;; Teardown + same-id replacement — the no-silent-retarget fence
;; ===========================================================================

(deftest destroyed-frame-ops-fail-loud
  (reg!)
  (make-frame! :ops/doomed {:n 1})
  (let [b (rf/with-frame :ops/doomed (frames/frame-ops))]
    (frame/destroy-frame! :ops/doomed)
    (is (= :rf.error/frame-destroyed (err-id #((:dispatch b) [:ops/set-n 2])))
        "a carried dispatch outliving its frame fails loud")
    (is (= :rf.error/frame-destroyed (err-id #((:dispatch-sync b) [:ops/set-n 2]))))
    (is (= :rf.error/frame-destroyed (err-id #((:subscribe b) [:ops/n]))))))

(deftest same-id-replacement-never-silently-retargets
  (reg!)
  (make-frame! :ops/reused {:n 1})
  (let [stale (rf/with-frame :ops/reused (frames/frame-ops))]
    (frame/destroy-frame! :ops/reused)
    (make-frame! :ops/reused {:n 41})
    (testing "the STALE bundle fails loud against the fresh same-id frame"
      (is (= :rf.error/frame-destroyed
             (err-id #((:dispatch-sync stale) [:ops/set-n 999])))
          "ops are locked to the exact incarnation they captured")
      (is (= {:n 41} (rf/app-db-value :ops/reused))
          "the replacement frame was not written by the stale bundle"))
    (testing "a fresh read returns a NEW working bundle"
      (let [fresh (rf/with-frame :ops/reused (frames/frame-ops))]
        (is (not (identical? stale fresh))
            "a new incarnation mints a new bundle identity")
        ((:dispatch-sync fresh) [:ops/set-n 42])
        (is (= {:n 42} (rf/app-db-value :ops/reused)))))))
