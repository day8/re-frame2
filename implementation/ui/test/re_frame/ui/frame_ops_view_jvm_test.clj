(ns re-frame.ui.frame-ops-view-jvm-test
  "rf2-vxgfnd.184 — the compiled `(frame)` body form on the JVM Tier-1
  structural host: the exact Guide 05 spelling compiles, renders, and
  returns the frame-locked operation bundle against a REAL frame.

  `(frame)` is NOT a host-only op: core's dispatch/dispatch-sync/subscribe
  are host-neutral (`rf/dispatch-sync` itself drives them on the JVM), so
  a Tier-1 structural render returns the SAME live bundle the client gets —
  no `:rf.error/jvm-host-op`. Covered here: valid placement (explicit
  `{:frame …}` opts AND ambient `frame-provider` scoping — the same
  authoritative resolution chain as `sub`), the no-frame diagnostic,
  destroyed-frame + same-id-replacement fencing, and per-incarnation
  bundle stability across renders. Placement REJECTIONS ride the
  analyzer suites (analyze-reject + the error roster, both hosts)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rframe]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame frame-provider]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            ;; no default ambient frame — the
                                            ;; frameless-render negative needs
                                            ;; a genuinely scope-free world
                                            :ambient-frame nil})
  (fn [t]
    (frames/reset-frame-ops-cache!)
    (try (t) (finally (frames/reset-frame-ops-cache!)))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *capture!*
  "Render-seam: the ops-probe view hands its `(frame)` bundle out through
  this dynamic var (a top-level view cannot close over a test's atom —
  the same seam shape as test-render-jvm-test's *render-gate*)."
  (fn [_bundle]))

(defview ops-probe
  "The Guide 05 spelling: destructure the ops bundle in the view body."
  []
  (let [{:keys [frame dispatch] :as bundle} (frame)
        _ (*capture!* bundle)]
    [:div.probe (str "frame=" frame " dispatch=" (fn? dispatch))]))

(defview provider-host
  "Ambient selection: the probe sits under a frame-provider, a descendant
  view boundary — the provider's frame is the committed frame."
  [{:keys [target]}]
  [:div.host [frame-provider {:frame target} [ops-probe]]])

(defn- render-capturing
  "Run `render-thunk` (a closure over a literal uit/render form — render is
  a macro needing the compile-resolved view symbol) with the capture seam
  bound, returning [tree captured-bundles]."
  [render-thunk]
  (let [captured (atom [])]
    (binding [*capture!* (fn [b] (swap! captured conj b))]
      (let [tree (render-thunk)]
        [tree @captured]))))

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo ex
         (:rf.error/id (ex-data ex)))))

(defn- reg! []
  (rf/reg-event :ops/set-n (fn [_ [_ n]] {:db {:n n}}))
  (rf/reg-sub :ops/n (fn [db _] (:n db))))

;; ---------------------------------------------------------------------------
;; Valid placement — explicit {:frame …} opts (the dynamic-tier scope)
;; ---------------------------------------------------------------------------

(deftest explicit-frame-render-returns-the-locked-bundle
  (reg!)
  (let [f  (rf/make-frame {:initial-events [[:rf/set-db {:n 5}]]})
        id (rframe/frame-target->id f)
        [tree [bundle :as all]] (render-capturing #(rf/with-frame f (uit/render [ops-probe])))]
    (is (= 1 (count all)) "one site, one bundle")
    (is (= #{:frame :dispatch :dispatch-sync :subscribe} (set (keys bundle)))
        "the standard capture-frame bundle shape")
    (is (= id (:frame bundle)) ":frame names the committed frame")
    (is (= 1 (:rf.ui/tree-version tree)))
    (is (= (str "frame=" id " dispatch=true")
           (-> (some #(when (= :div (:tag %)) %) (tree-seq map? :children tree)) uit/text))
        "the bundle is a render-time VALUE — the structural tree renders it")
    (testing "the ops are LIVE on the JVM host — not a jvm-host-op stub"
      ((:dispatch-sync bundle) [:ops/set-n 6])
      (is (= {:n 6} (rf/app-db-value id)))
      (is (= 6 @((:subscribe bundle) [:ops/n]))))))

;; ---------------------------------------------------------------------------
;; Valid placement — ambient frame-provider scoping (same rules as sub)
;; ---------------------------------------------------------------------------

(deftest ambient-provider-scope-binds-the-bundle
  (reg!)
  (let [f  (rf/make-frame {:initial-events [[:rf/set-db {:n 1}]]})
        id (rframe/frame-target->id f)
        [_ [bundle]] (render-capturing #(uit/render [provider-host {:target f}]))]
    (is (= id (:frame bundle))
        "the provider's frame is the committed frame — the ambient chain,
         not a dynamic-tier leak")
    ((:dispatch-sync bundle) [:ops/set-n 11])
    (is (= {:n 11} (rf/app-db-value id)))))

;; ---------------------------------------------------------------------------
;; No frame anywhere — the canonical diagnostic
;; ---------------------------------------------------------------------------

(deftest frameless-render-fails-loud
  (is (= :rf.error/no-frame-context
         (err-id #(uit/render [ops-probe])))
      "a frameless structural render raises honestly at the (frame) site —
       the runtime never invents a frame"))

;; ---------------------------------------------------------------------------
;; Stability + teardown/replacement fencing
;; ---------------------------------------------------------------------------

(deftest bundle-is-stable-across-renders-within-one-incarnation
  (let [f (rf/make-frame {:initial-events [[:rf/set-db {}]]})
        [_ [b1]] (render-capturing #(rf/with-frame f (uit/render [ops-probe])))
        [_ [b2]] (render-capturing #(rf/with-frame f (uit/render [ops-probe])))]
    (is (identical? b1 b2)
        "two renders against one live incarnation observe the IDENTICAL
         bundle — stable operation identity, no per-render construction")))

(deftest destroyed-and-replaced-frames-fence-the-carried-bundle
  (reg!)
  (live-frame/make-frame {:id :ops.jvm/reused})
  (rframe/replace-app-db! :ops.jvm/reused {:n 1})
  (let [[_ [stale]] (render-capturing #(rf/with-frame :ops.jvm/reused (uit/render [ops-probe])))]
    (rframe/destroy-frame! :ops.jvm/reused)
    (testing "destroyed frame — carried ops fail with the canonical id"
      (is (= :rf.error/frame-destroyed
             (err-id #((:dispatch-sync stale) [:ops/set-n 2])))))
    (testing "a same-id REPLACEMENT never silently receives the stale ops"
      (live-frame/make-frame {:id :ops.jvm/reused})
      (rframe/replace-app-db! :ops.jvm/reused {:n 41})
      (is (= :rf.error/frame-destroyed
             (err-id #((:dispatch-sync stale) [:ops/set-n 999]))))
      (is (= {:n 41} (rf/app-db-value :ops.jvm/reused))
          "the replacement frame is untouched by the stale bundle"))
    (testing "a fresh render against the replacement mints a fresh bundle"
      (let [[_ [fresh]] (render-capturing #(rf/with-frame :ops.jvm/reused (uit/render [ops-probe])))]
        (is (not (identical? stale fresh)))
        ((:dispatch-sync fresh) [:ops/set-n 42])
        (is (= {:n 42} (rf/app-db-value :ops.jvm/reused)))))))
