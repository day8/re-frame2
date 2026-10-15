(ns re-frame.adapter.test-react-cljs-test
  (:require [re-frame.adapter.test-react :as rf.adapter.test-react]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])))

(defn- install-test-react! [t]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter)
  (try
    (t)
    (finally
      (rf.substrate.adapter/dispose-adapter!))))

(use-fixtures :each install-test-react!)

(defn- phase-count [mount phase]
  (->> (rf.adapter.test-react/lifecycle-log mount)
       (filter (comp #{phase} :phase))
       count))

(defn- phase-first-seq
  "The per-adapter counter makes teardown order deterministic."
  [mount phase]
  (->> (rf.adapter.test-react/lifecycle-log mount)
       (filter (comp #{phase} :phase))
       first
       :seq))

(defn- phases [mount]
  (mapv :phase (rf.adapter.test-react/lifecycle-log mount)))

(defn- live [] (count (rf.adapter.test-react/mounted-components)))

(defn- mounted? [mount] @(:mounted? mount))

(defn- tree [mount] (rf.adapter.test-react/current-render-tree mount))

(deftest happy-path-lifecycle-ordering
  (let [mount (rf.adapter.test-react/mount! [:div "v1"])]
    (rf.adapter.test-react/trigger-update! mount [:div "v2"])
    (rf.adapter.test-react/unmount! mount)
    (is (= [:constructor :render :did-mount :render :did-update :will-unmount]
           (phases mount)))))

(deftest mounted-components-and-current-render-tree
  (let [mount (rf.adapter.test-react/mount! [:div "initial"])]
    (is (= [mount] (rf.adapter.test-react/mounted-components)))
    (is (= [:div "initial"] (tree mount)))
    (rf.adapter.test-react/trigger-update! mount [:div "updated"])
    (is (= [:div "updated"] (tree mount)))
    (rf.adapter.test-react/unmount! mount)
    (is (nil? (tree mount)))))

(deftest dispose-adapter-drains-stranded-mounts
  (let [mount (rf.adapter.test-react/mount! [:div "leaked"])]
    (rf.substrate.adapter/dispose-adapter!)
    (is (not (mounted? mount)))
    (is (some #{:forced-teardown} (phases mount)))
    (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter)))

(deftest render-to-string-survives-dispose-reinstall
  ;; SSR may replay its own emitter on reinstall.
  (rf.adapter.test-react/set-hiccup-emitter! (fn [tree _opts] (str "HTML:" (pr-str tree))))
  (try
    (is (= "HTML:[:div \"a\"]" (rf.substrate.adapter/render-to-string [:div "a"] nil)))
    (rf.substrate.adapter/dispose-adapter!)
    (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter)
    (is (re-find #"b" (rf.substrate.adapter/render-to-string [:div "b"] nil)))
    (finally
      (rf.adapter.test-react/set-hiccup-emitter! nil))))

(deftest deep-cascade-tears-down-root-downward
  (let [grandchild-ref (atom nil)
        child-ref      (atom nil)
        parent (rf.adapter.test-react/mount!
                 {:rf/component
                  (fn [_parent]
                    (reset! child-ref
                            (rf.adapter.test-react/mount-child!
                              {:rf/component
                               (fn [_child]
                                 (reset! grandchild-ref
                                         (rf.adapter.test-react/mount-child! [:span "leaf"])))})))})]
    (is (false? (rf.adapter.test-react/rendering?)))
    (is (= 3 (live)))
    (is (= [@child-ref] (rf.adapter.test-react/mounted-children parent)))
    (is (= [@grandchild-ref] (rf.adapter.test-react/mounted-children @child-ref)))
    (rf.adapter.test-react/unmount! parent)
    (is (zero? (live)))
    (is (< (phase-first-seq parent :will-unmount)
           (phase-first-seq @child-ref :will-unmount)
           (phase-first-seq @grandchild-ref :will-unmount)))))

(deftest organic-sync-unmount-during-render-rf2-4l7t2
  (let [panel-a (rf.adapter.test-react/mount! [:div.panel "A"])]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/sync-unmount-during-render"
          (rf.adapter.test-react/mount!
            {:rf/component (fn [_host] (rf.adapter.test-react/unmount! panel-a))})))
    (is (false? (rf.adapter.test-react/rendering?)))
    (is (= [panel-a] (rf.adapter.test-react/mounted-components)))
    (rf.adapter.test-react/unmount! panel-a)))

(deftest failed-mount-leaves-no-did-mount-and-spares-live-sibling-rf2-3fc89f2
  (let [survivor       (rf.adapter.test-react/mount! [:div "survivor"])
        parent-ref     (atom nil)
        child-ref      (atom nil)
        grandchild-ref (atom nil)]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #"boom-with-sibling"
          (rf.adapter.test-react/mount!
            {:rf/component
             (fn [parent]
               (reset! parent-ref parent)
               (reset! child-ref
                       (rf.adapter.test-react/mount-child!
                         {:rf/component
                          (fn [_child]
                            (reset! grandchild-ref
                                    (rf.adapter.test-react/mount-child! [:span "leaf"])))}))
               (throw (ex-info "boom-with-sibling" {})))})))
    (is (= [survivor] (rf.adapter.test-react/mounted-components)))
    (is (zero? (phase-count survivor :forced-teardown)))
    (is (false? (mounted? @child-ref)))
    (is (= [1 1] [(phase-count @child-ref :forced-teardown)
                  (phase-count @grandchild-ref :forced-teardown)]))
    (is (< (phase-first-seq @grandchild-ref :forced-teardown)
           (phase-first-seq @child-ref :forced-teardown)))
    (is (false? (mounted? @parent-ref)))
    (is (nil? (tree @parent-ref)))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/update-after-unmount"
          (rf.adapter.test-react/trigger-update! @parent-ref [:div :impossible-update])))
    (is (= [:constructor :render :forced-teardown] (phases @parent-ref)))
    (rf.adapter.test-react/unmount! survivor)))

(deftest failed-child-render-rolls-back-its-own-descendants-rf2-3fc89f2
  (let [child-ref      (atom nil)
        grandchild-ref (atom nil)]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #"boom-child-render"
          (rf.adapter.test-react/mount!
            {:rf/component
             (fn [_parent]
               (rf.adapter.test-react/mount-child!
                 {:rf/component
                  (fn [child]
                    (reset! child-ref child)
                    (reset! grandchild-ref
                            (rf.adapter.test-react/mount-child! [:span "leaf"]))
                    (throw (ex-info "boom-child-render" {})))}))})))
    (is (false? (rf.adapter.test-react/rendering?)))
    (is (zero? (live)))
    (is (= [false false] [(mounted? @grandchild-ref) (mounted? @child-ref)]))
    (is (nil? (tree @child-ref)))
    (is (zero? (phase-count @child-ref :did-mount)))))

(deftest failed-update-unmounts-the-whole-root-rf2-j538f71
  (let [sibling     (rf.adapter.test-react/mount! [:div "sibling"])
        child-a-ref (atom nil)
        child-b-ref (atom nil)
        mount       (rf.adapter.test-react/mount!
                      {:rf/component
                       (fn [_parent]
                         (reset! child-a-ref
                                 (rf.adapter.test-react/mount-child! [:span "child-a"])))})]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #"boom-update-body"
          (rf.adapter.test-react/trigger-update!
            mount
            {:rf/component
             (fn [_parent]
               (reset! child-b-ref (rf.adapter.test-react/mount-child! [:span "child-b"]))
               (throw (ex-info "boom-update-body" {})))})))
    (is (false? (rf.adapter.test-react/rendering?)))
    (is (false? (mounted? mount)))
    (is (nil? (tree mount)))
    (is (= [sibling] (rf.adapter.test-react/mounted-components)))
    (is (= [1 1] [(phase-count @child-a-ref :forced-teardown)
                  (phase-count @child-b-ref :forced-teardown)]))
    (is (= [:constructor :render :did-mount :render :forced-teardown] (phases mount)))
    (let [parent-seq (phase-first-seq mount :forced-teardown)]
      (is (and (< (phase-first-seq @child-a-ref :forced-teardown) parent-seq)
               (< (phase-first-seq @child-b-ref :forced-teardown) parent-seq))))
    (is (= [:div "sibling"] (tree sibling)))
    (is (zero? (phase-count sibling :forced-teardown)))
    (rf.adapter.test-react/unmount! sibling)))

(deftest unmount-is-idempotent
  (let [mount (rf.adapter.test-react/mount! [:div "once"])]
    (rf.adapter.test-react/unmount! mount)
    (is (nil? (rf.adapter.test-react/unmount! mount)))
    (is (= 1 (phase-count mount :will-unmount)))))

(deftest mount-child-outside-render-body-throws
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
        #":rf.error/mount-child-outside-render"
        (rf.adapter.test-react/mount-child! [:span "orphan"])))
  (is (zero? (live))))

(deftest mount-under-wrong-adapter-throws
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! {:kind             :rf.adapter/plain-atom
                                          :dispose-adapter! (fn [] nil)})
  (try
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/test-react-not-installed"
          (rf.adapter.test-react/mount! [:div "nope"])))
    (finally
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter))))

(deftest mount-under-copied-test-react-map-succeeds
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter!
    (assoc rf.adapter.test-react/adapter :rf.test/instrumentation-wrapper true))
  (try
    (let [mount (rf.adapter.test-react/mount! [:div "via-copied-map"])]
      (is (= [:constructor :render :did-mount] (phases mount)))
      (rf.adapter.test-react/unmount! mount))
    (finally
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter))))

(deftest render-to-string-without-emitter-throws
  (rf.adapter.test-react/set-hiccup-emitter! nil)
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
        #":rf.error/no-hiccup-emitter-bound"
        (rf.substrate.adapter/render-to-string [:div "x"] nil))))

(deftest substrate-render-entry-point-returns-working-thunk
  (let [thunk (rf.substrate.adapter/render [:div "via-render"] nil nil)]
    (is (= 1 (live)))
    (thunk)
    (is (zero? (live)))))
