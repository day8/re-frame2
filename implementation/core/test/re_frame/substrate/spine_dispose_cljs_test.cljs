(ns re-frame.substrate.spine-dispose-cljs-test
  "The substrate spine's `dispose-adapter!` factory (Spec 006 §Adapter disposal
  lifecycle): every cleanup step is attempted, the FIRST failure is rethrown
  (kept by presence, so a falsey throw still surfaces), and later failures ride
  it as `rfAdapterTeardownSecondaryErrors`. Every frame's sub-cache is core's
  teardown step, pinned in `re-frame.adapter.capabilities-cljs-test`.

  Fake roots (objects with an `unmount` slot) stand in for React roots, so this
  runs on node with no DOM.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.substrate.spine :as rf.substrate.spine]))

(defn- fake-root []
  (let [unmount-count (atom 0)]
    {:root          #js {:unmount #(swap! unmount-count inc)}
     :unmount-count unmount-count}))

(defn- cells [driver-root]
  {:active-roots-cell             (rf.substrate.spine/make-active-roots-cell)
   :emitter-cell                  (rf.substrate.spine/make-hiccup-emitter-cell)
   :after-render-driver-root-cell (atom driver-root)
   :after-render-set-tick-ref     (atom :stale-setter)})

(defn- dispose-thrown
  "Run the factory-built `dispose-adapter!` over `c`; return what it threw, or
  ::returned-normally."
  [c]
  (try ((rf.substrate.spine/make-dispose-adapter! c)) ::returned-normally
       (catch :default e e)))

(defn- cleared
  "What a finished teardown leaves in each cell."
  [{:keys [active-roots-cell emitter-cell after-render-driver-root-cell
           after-render-set-tick-ref]}]
  [@active-roots-cell @emitter-cell @after-render-driver-root-cell
   @after-render-set-tick-ref])

(def ^:private all-cleared [#{} nil nil nil])

(deftest dispose-drains-every-root-then-rethrows
  (let [c        (cells nil)
        good-1   (fake-root)
        good-2   (fake-root)
        sentinel (js/Error. "boom")]
    ;; A set does not keep insertion order: both good roots must unmount
    ;; wherever the bad one falls.
    (swap! (:active-roots-cell c) conj (:root good-1) #js {:unmount #(throw sentinel)}
           (:root good-2))
    (let [thrown (dispose-thrown c)]
      (is (= [true 1 1 all-cleared]
             [(identical? sentinel thrown) @(:unmount-count good-1) @(:unmount-count good-2)
              (cleared c)])))))

(deftest dispose-surfaces-a-driver-root-only-unmount-failure-as-the-primary
  ;; The driver root lives outside `active-roots-cell`; when its unmount is the
  ;; only failure it must reach the caller, after every other step ran.
  (let [sentinel (js/Error. "driver root unmount failed")
        c        (cells #js {:unmount #(throw sentinel)})
        healthy  (fake-root)]
    (swap! (:active-roots-cell c) conj (:root healthy))
    (reset! (:emitter-cell c) (fn fake-emit [_ _] "<html/>"))
    (let [thrown (dispose-thrown c)]
      (is (= [true 1 all-cleared]
             [(identical? sentinel thrown) @(:unmount-count healthy) (cleared c)])))))

(deftest dispose-attaches-a-driver-root-failure-behind-an-earlier-primary
  (let [root-boom   (js/Error. "app root unmount failed")
        driver-boom (js/Error. "driver root unmount failed")
        c           (cells #js {:unmount #(throw driver-boom)})
        healthy     (fake-root)]
    (swap! (:active-roots-cell c) conj (:root healthy) #js {:unmount #(throw root-boom)})
    (let [thrown (dispose-thrown c)]
      (is (= [true 1 all-cleared [driver-boom]]
             [(identical? root-boom thrown) @(:unmount-count healthy) (cleared c)
              (vec (aget thrown "rfAdapterTeardownSecondaryErrors"))])))))

(deftest dispose-captures-a-falsey-driver-root-throw-by-presence
  (let [c (cells #js {:unmount #(throw false)})]
    (is (= [false nil] [(dispose-thrown c) @(:after-render-driver-root-cell c)]))))

(deftest spine-derived-value-dispose-is-idempotent-and-re-entrant-safe
  ;; A callback that re-enters `-dispose`, and a second plain `-dispose`, must
  ;; each leave every callback fired exactly once.
  (let [scheduler (rf.substrate.spine/make-scheduler)
        make-dv   (rf.substrate.spine/make-derived-value-fn "rf-dispose-test-" scheduler)
        dv        (make-dv [(rf.substrate.spine/make-state-container 0)] identity)
        fire-log  (atom [])]
    (rf.disposable/-add-on-dispose dv (fn []
                                        (swap! fire-log conj :re-entrant-cb)
                                        (rf.disposable/-dispose dv)))
    (rf.disposable/-add-on-dispose dv #(swap! fire-log conj :after-cb))
    (rf.disposable/-dispose dv)
    (rf.disposable/-dispose dv)
    (is (= [:re-entrant-cb :after-cb] @fire-log))))
