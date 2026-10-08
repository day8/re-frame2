(ns re-frame.substrate.spine-dispose-cljs-test
  "The substrate spine's `dispose-adapter!` factory and sub-cache walk (Spec 006
  §Adapter disposal lifecycle): every cleanup step is attempted, the FIRST
  failure is rethrown (kept by presence, so a falsey throw still surfaces), and
  later failures ride it as `rfAdapterTeardownSecondaryErrors`.

  Fake roots (objects with an `unmount` slot) and fake cached reactions stand in
  for React roots and Reactions, so this runs on node with no DOM.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.spine :as rf.substrate.spine]))

(defn- fake-root []
  (let [unmount-count (atom 0)]
    {:root          #js {:unmount #(swap! unmount-count inc)}
     :unmount-count unmount-count}))

(defn- fake-reaction []
  (let [dispose-count (atom 0)]
    {:reaction      (reify rf.disposable/IDisposable
                      (-dispose [_] (swap! dispose-count inc))
                      (-add-on-dispose [_ _f] nil))
     :dispose-count dispose-count}))

(defn- fake-frame [cache-map]
  {:sub-cache (atom cache-map)})

(defn- cells [driver-root]
  {:active-roots-cell             (rf.substrate.spine/make-active-roots-cell)
   :warn-cache                    (rf.substrate.spine/make-warn-once-cache)
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
  [{:keys [active-roots-cell warn-cache emitter-cell after-render-driver-root-cell
           after-render-set-tick-ref]}]
  [@active-roots-cell @warn-cache @emitter-cell @after-render-driver-root-cell
   @after-render-set-tick-ref])

(def ^:private all-cleared [#{} #{} nil nil nil])

;; The walk disposes through the `:adapter/dispose!` hook, so seed it with the
;; protocol fn and restore the globals afterwards.
(use-fixtures :each
  (fn [test-fn]
    (let [saved-frames @rf.frame/frames
          saved-hook   (rf.late-bind/get-fn-cached :adapter/dispose!)]
      (reset! rf.frame/frames {})
      (rf.late-bind/set-fn! :adapter/dispose! rf.disposable/-dispose)
      (try (test-fn)
           (finally
             (reset! rf.frame/frames saved-frames)
             (when saved-hook
               (rf.late-bind/set-fn! :adapter/dispose! saved-hook)))))))

(deftest dispose-drains-every-root-walks-the-sub-caches-then-rethrows
  (let [c        (cells nil)
        good-1   (fake-root)
        good-2   (fake-root)
        sentinel (js/Error. "boom")
        r        (fake-reaction)
        frm      (fake-frame {[:sub :x] (select-keys r [:reaction])})]
    (reset! rf.frame/frames {:walk/a frm})
    ;; A set does not keep insertion order: both good roots must unmount
    ;; wherever the bad one falls.
    (swap! (:active-roots-cell c) conj (:root good-1) #js {:unmount #(throw sentinel)}
           (:root good-2))
    (reset! (:warn-cache c) #{:some-stale-warn-key})
    (let [thrown (dispose-thrown c)]
      (is (= [true 1 1 1 {} all-cleared]
             [(identical? sentinel thrown) @(:unmount-count good-1) @(:unmount-count good-2)
              @(:dispose-count r) @(:sub-cache frm) (cleared c)])))))

(deftest dispose-surfaces-a-driver-root-only-unmount-failure-as-the-primary
  ;; The driver root lives outside `active-roots-cell`; when its unmount is the
  ;; only failure it must reach the caller, after every other step ran.
  (let [sentinel (js/Error. "driver root unmount failed")
        c        (cells #js {:unmount #(throw sentinel)})
        healthy  (fake-root)]
    (swap! (:active-roots-cell c) conj (:root healthy))
    (reset! (:warn-cache c) #{:some-stale-warn-key})
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

(deftest dispose-frame-sub-caches-walks-every-live-frame
  ;; Two healthy entries in one cache: a walk cut short to each cache's first
  ;; entry fails here.
  (let [r-a-x (fake-reaction)
        r-a-y (fake-reaction)
        r-b   (fake-reaction)
        frm-a (fake-frame {[:sub :x] (select-keys r-a-x [:reaction])
                           [:sub :y] (select-keys r-a-y [:reaction])})
        frm-b (fake-frame {[:sub :z] (select-keys r-b [:reaction])})]
    (reset! rf.frame/frames {:walk/a frm-a :walk/b frm-b})
    (rf.substrate.spine/dispose-frame-sub-caches!)
    (is (= [1 1 1 {} {}]
           [@(:dispose-count r-a-x) @(:dispose-count r-a-y) @(:dispose-count r-b)
            @(:sub-cache frm-a) @(:sub-cache frm-b)]))))

(deftest dispose-frame-sub-caches-is-best-effort
  ;; The poison entry is not IDisposable, so the seeded hook throws on it.
  (let [good-1 (fake-reaction)
        good-2 (fake-reaction)
        frm-a  (fake-frame {[:sub :good-1] (select-keys good-1 [:reaction])
                            [:sub :poison] {:reaction (js-obj "not" "a reaction")}})
        frm-b  (fake-frame {[:sub :good-2] (select-keys good-2 [:reaction])})]
    (reset! rf.frame/frames {:walk/a frm-a :walk/b frm-b})
    (rf.substrate.spine/dispose-frame-sub-caches!)
    (is (= [1 1 {} {}]
           [@(:dispose-count good-1) @(:dispose-count good-2)
            @(:sub-cache frm-a) @(:sub-cache frm-b)]))))

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
