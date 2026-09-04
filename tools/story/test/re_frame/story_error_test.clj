(ns re-frame.story-error-test
  "JVM tests for `re-frame.story.error` — the ONE shared Throwable→error-map
  projection + `:rf.error/exception` assertion-record builder (rf2-9kpsq).

  The projection used to be copy-pasted verbatim across five sites in four
  namespaces, with the wrapping record duplicated three times; the copies
  had begun to drift (one site guarded each accessor, another dropped
  `:stack` / `:data`). These tests pin the canonical shape and confirm the
  drift is gone — every routed site now yields the SAME
  `{:message :stack :data}` shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.error :as rf.story.error]))

;; ---- fixture ---------------------------------------------------------------
;;
;; Most assertions here are pure (`throwable->error-map` is data→data), but
;; the `exception-record` projection is FRAME-SCOPED egress (EP-0015 issue 1,
;; rf2-t55hxg.18): the `:data` slot is walked against the record's frame and
;; FAILS CLOSED when that frame is unresolvable. Tests that need a live
;; variant frame (`make-frame`) require an installed adapter, so init the
;; plain-atom adapter and clean frame state around each test.
(use-fixtures :each
  (fn [test-fn]
    (reset! rf.frame/frames {})
    (try (rf/init! rf.substrate.plain-atom/adapter)
         (catch clojure.lang.ExceptionInfo _ nil))
    (rf.frame/ensure-default-frame!)
    (test-fn)))

;; ---- throwable->error-map -------------------------------------------------

(deftest throwable->error-map-projects-the-canonical-shape
  (testing "an ExceptionInfo projects to {:message :stack :data} with every
            slot populated"
    (let [m (rf.story.error/throwable->error-map
              (ex-info "boom" {:why :test}))]
      (is (= #{:message :stack :data} (set (keys m)))
          "exactly the three canonical keys")
      (is (= "boom" (:message m)))
      (is (= {:why :test} (:data m))
          ":data is the ex-data of an ExceptionInfo")
      (is (string? (:stack m))
          ":stack is a string on the JVM")
      ;; rf2-qk0h9 regression guard: the trace must be CAPTURED into
      ;; :stack, not leaked to System/err. The buggy `with-out-str`
      ;; capture of the no-arg `.printStackTrace` returned "" (still a
      ;; string — so the bare `string?` assertion above passed while the
      ;; trace trailed every green run on stderr). Asserting the rendered
      ;; trace text is actually present pins the explicit-PrintWriter fix.
      (is (str/includes? (:stack m) "boom")
          ":stack carries the rendered trace (captured, not leaked to stderr)"))))

(deftest throwable->error-map-non-ex-info-has-nil-data
  (testing "a plain Throwable (no ex-data) yields :data nil WITHOUT an
            explicit instance? guard — ex-data already returns nil for a
            non-ExceptionInfo"
    (let [m (rf.story.error/throwable->error-map (RuntimeException. "plain"))]
      (is (= "plain" (:message m)))
      (is (nil? (:data m)))
      (is (string? (:stack m))))))

(deftest throwable->error-map-tolerates-nil-throwable
  (testing "a nil throwable yields all-nil slots (the trace-drain path may
            carry no exception object)"
    (let [m (rf.story.error/throwable->error-map nil)]
      (is (= {:message nil :stack nil :data nil} m)))))

(deftest throwable->error-map-explicit-message-override
  (testing "an explicit :message override wins over the throwable's own
            message (the trace-drain path threads a pre-extracted
            :exception-message)"
    (is (= "pre-extracted"
           (:message (rf.story.error/throwable->error-map
                       (ex-info "original" {}) {:message "pre-extracted"}))))
    (testing "and survives even without a throwable in hand"
      (is (= "pre-extracted"
             (:message (rf.story.error/throwable->error-map
                         nil {:message "pre-extracted"})))))
    (testing "a nil override falls back to the throwable's message"
      (is (= "original"
             (:message (rf.story.error/throwable->error-map
                         (ex-info "original" {}) {:message nil})))))))

;; ---- exception-record -----------------------------------------------------

(deftest exception-record-wraps-the-projection
  (testing "exception-record builds the full :rf.error/exception assertion
            record with the canonical error projection embedded"
    ;; EP-0015 issue 1 (rf2-t55hxg.18) — `exception-record` threads
    ;; `variant-id` as the `:frame` for the `:data` wire-elision. The
    ;; `:data` projection is FRAME-SCOPED egress: the frame must RESOLVE to
    ;; a live frame for its (empty) policy to be consulted; an unresolvable
    ;; frame FAILS CLOSED and redacts the whole `ex-data` map. In the real
    ;; Story runtime the variant frame is always live when an error is
    ;; recorded (`re-frame.story.frames` `make-frame`s `variant-id` before
    ;; the phases run), so register it here too — the public `{:k :v}` then
    ;; walks through verbatim under the empty policy. `throwable->error-map`
    ;; in the assertion below must be projected against the SAME live frame
    ;; (a frameless call would itself fail closed) to match `exception-record`.
    (rf/make-frame {:id :story.x/v})
    (try
      (let [e (ex-info "kaboom" {:k :v})
            r (rf.story.error/exception-record :story.x/v :phase-2-events
                                            [:some/event 1] e)]
        (is (= :rf.error/exception (:assertion r)))
        (is (= :story.x/v          (:variant-id r)))
        (is (= :phase-2-events     (:phase r)))
        (is (= [:some/event 1]     (:event r)))
        (is (false?                (:passed? r)))
        (is (= (rf.story.error/throwable->error-map e {:frame :story.x/v}) (:error r))
            "the :error slot is exactly the shared projection (same live frame)")
        (is (= "kaboom" (-> r :error :message)))
        (is (= {:k :v}  (-> r :error :data)))
        (is (string?    (-> r :error :stack))))
      (finally
        (rf/destroy-frame! :story.x/v)))))

(deftest exception-record-threads-message-override
  (testing "the opts arity threads :message through to the embedded
            projection (the drain path's pre-extracted message)"
    (let [r (rf.story.error/exception-record :story.x/v :phase-1-loaders nil nil
                                          {:message "from trace"})]
      (is (= "from trace" (-> r :error :message)))
      (is (nil? (-> r :error :stack)))
      (is (nil? (-> r :error :data))))))

;; ---- drift is gone: every routed record shares the projection -------------

(deftest all-exception-records-share-the-canonical-error-shape
  (testing "records built for every phase carry IDENTICAL :error key sets —
            the previously-drifted :stack / :data fields are now consistent
            across all sites (rf2-9kpsq)"
    (let [e          (ex-info "x" {:d 1})
          phases     [:phase-0-setup :phase-1-loaders :phase-2-events
                      :phase-4-play :phase-4-setup :phase-teardown
                      :phase-loaders-teardown]
          error-keys (->> phases
                          (map (fn [p]
                                 (set (keys (:error (rf.story.error/exception-record
                                                      :story.x/v p nil e))))))
                          set)]
      (is (= #{#{:message :stack :data}} error-keys)
          "every phase's :error sub-map has exactly the canonical key set —
           no site drops :stack or :data"))))
