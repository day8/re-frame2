(ns re-frame.freehand.error-matrix-dom-cljs-test
  "F6b matrix 5/8 — ERROR CONTAINMENT, in a real browser, across BOTH
  execution modes (EP-0036 §6, gate row \"browser correctness\").

  A render-class failure inside a guarded region must be CONTAINED — the
  fallback on screen, the failing subtree gone — and REPORTED once through
  the private always-on egress, without leaking the failure's private
  payload to the page. The React commit runs `componentDidMount` before
  the update queue's error callback, so a boundary can be perfectly shaped
  around a lifecycle that never reports; only a real mount proves the
  report fires. So this mounts, and reads containment off `document` and
  the report off the egress registry.

  ## The mode dimension for error containment

  `v/error-boundary` is a recognised special form both emitters lower — the
  analyzer arm is `analyze-accept-cljs-test/error-boundary-lowers`. Its
  place, though, is the OWNING interpreted view or a mounted root: a
  compiled body's guarded region is inline markup, which never throws, and
  a throwing CHILD is a component boundary the compiled tier refuses inside
  markup. So the matrix's mode dimension is the CHILD: a boundary contains
  a throwing view whether that view is INTERPRETED or COMPILED, and it
  contains, reports and recovers the same way for both. That is the
  cross-mode claim that matters here — a compiled view's render error is
  caught exactly as an interpreted view's is.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node it
  has no DOM and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.freehand :as v]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (fr/reset-boundaries!)
                      (error-emit/clear-error-listeners!))}))

(def ^:private fid :matrix.error/frame)
(def ^:private secret "must-not-leak")

;; The failing child in each mode — the SAME body, marker only. `throwing?`
;; is the seam a retry flips. A boundary contains a throwing view whichever
;; lowering built it.
(defonce ^:private throwing? (atom true))

(v/defview thrower-interpreted
  [_]
  (if @throwing?
    (throw (ex-info "a child render threw" {:secret secret}))
    [:p {:id "child"} "recovered"]))

(v/defview thrower-compiled
  {:compiled true}
  [_]
  (if @throwing?
    (throw (ex-info "a child render threw" {:secret secret}))
    [:p {:id "child"} "recovered"]))

;; ONE interpreted boundary, taking the guarded child as a runtime prop, so
;; one declaration guards either mode's thrower — the boundary's containment
;; is what is under test, not a per-child boundary.
(v/defview boundary
  [{:keys [child reset-key]}]
  [v/error-boundary {:fallback  [:p {:id "fallback"} "contained"]
                     :reset-key reset-key
                     :on-error  [:matrix.error/caught]}
   child])

(def ^:private child-modes
  [["interpreted child" [thrower-interpreted {}]]
   ["compiled child"    [thrower-compiled {}]]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private egress (atom []))

(defn- setup! []
  (reset! egress [])
  (reset! throwing? true)
  (live-frame/make-frame {:id fid})
  ;; The :on-error intent rides the router; register a target so the
  ;; boundary's dispatch lands rather than erroring. Its EXACT count is
  ;; owned by errors-cljs-test / errors-dom-cljs-test (counted at the
  ;; router seam); here the private egress is the report channel under
  ;; test, because it fires synchronously in the failing commit.
  (rf/reg-event :matrix.error/caught (fn [{:keys [db]} _] {:db db}))
  (error-emit/register-error-listener! ::recorder (fn [r] (swap! egress conj r)))
  fid)

(defn- render! [root child reset-key]
  (ms/act #(.render root (shell/provide-frame fid (fr/element [boundary {:child child :reset-key reset-key}])))))

;; ===========================================================================
;; Row 1 — a throwing child is contained and reported once, both child modes
;; ===========================================================================

(deftest error-matrix-a-throwing-child-is-contained-and-reported-in-both-modes
  (testing "A child that throws on its first mounted render is CONTAINED —
            the fallback is on screen and the failing subtree is gone — and
            REPORTED exactly once through the private egress, without the
            failure's private payload reaching the page. Asserted for a
            throwing INTERPRETED child and a throwing COMPILED one: both are
            contained the same way."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the containment assertions")
      (async done
        (ms/each-mode
          child-modes
          (fn [[label child]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root child 0)
                  (.then (fn [_]
                           (is (some? (ms/q container "#fallback"))
                               (str label ": the fallback is on screen"))
                           (is (nil? (ms/q container "#child"))
                               (str label ": the failing subtree is gone, not merely hidden"))
                           (is (not (re-find (re-pattern secret) (.-innerHTML container)))
                               (str label ": the failure's private payload did not reach the page"))
                           (is (= 1 (count @egress))
                               (str label ": exactly one private egress record for the generation"))
                           (is (map? (first @egress))
                               (str label ": and the report is a structured record"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 2 — recovery follows a changed reset key, both child modes
;; ===========================================================================

(deftest error-matrix-recovery-follows-a-changed-reset-key-in-both-modes
  (testing "Once the failure clears, a CHANGED reset key retries the guarded
            region: the child renders normally, the fallback is gone, and no
            second report fires for the recovered generation. A stale reset
            key would leave the fallback latched. Asserted for both a
            recovered interpreted child and a recovered compiled one."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the recovery assertions")
      (async done
        (ms/each-mode
          child-modes
          (fn [[label child]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root child 0)
                  (.then (fn [_]
                           (is (some? (ms/q container "#fallback")) (str label ": contained first"))
                           (is (= 1 (count @egress)) (str label ": one report so far"))
                           (reset! throwing? false)
                           (render! root child 1)))
                  (.then (fn [_]
                           (is (= "recovered" (ms/text-of container "#child"))
                               (str label ": a changed reset key retried the guarded region"))
                           (is (nil? (ms/q container "#fallback"))
                               (str label ": and the fallback is gone"))
                           (is (= 1 (count @egress))
                               (str label ": the recovered generation reported nothing new"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 3 — both child modes are contained the same way
;; ===========================================================================

(deftest error-matrix-both-child-modes-are-contained-the-same-way
  (testing "The contained page — the fallback subtree React committed after
            the throw — is the SAME real DOM whether the failing child was
            interpreted or compiled. Containment is a property of the
            boundary, not of what threw, so a compiled child's render error
            lands the same fallback an interpreted child's does."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the parity assertion")
      (async done
        (setup!)
        (let [[ci ri] (ms/create-root!)
              [cc rc] (ms/create-root!)]
          (-> (render! ri [thrower-interpreted {}] 0)
              (.then (fn [_] (render! rc [thrower-compiled {}] 0)))
              (.then (fn [_]
                       (ms/outlines-agree? (ms/q ci "#fallback") (ms/q cc "#fallback")
                                           "contained fallback")
                       (is (= "contained" (ms/text-of ci "#fallback"))
                           "non-vacuous: the fallback really is on screen")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "a boundary mount rejected: " e)) nil))
              ;; Both arms tore both roots down, identically, so the teardown
              ;; rides the single trailing step: written once, run once per path.
              (.then (fn [_] (ms/destroy-root! ci ri) (ms/destroy-root! cc rc) (done)))))))))
