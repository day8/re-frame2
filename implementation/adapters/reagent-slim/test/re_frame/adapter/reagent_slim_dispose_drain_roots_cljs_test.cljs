(ns re-frame.adapter.reagent-slim-dispose-drain-roots-cljs-test
  "The reagent-slim `dispose-adapter!` drains every still-mounted React root
  and clears the SSR hiccup-emitter (Spec 006 §Adapter disposal lifecycle,
  MUSTs 2 and 3), so an `init! → render → dispose-adapter!` cycle leaves
  nothing alive. `reagent2.dom.client` is spied with `with-redefs`, so this
  runs headless."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; Cold-start with no frames, so dispose's sub-cache walk has nothing to do.

(defn with-fresh-slim-adapter [test-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/install-adapter! rf.adapter.reagent-slim/adapter)
  (test-fn)
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each with-fresh-slim-adapter)

;; ---- helpers --------------------------------------------------------------

(defn- make-fake-root
  "A fake Root carrying the `.unmount` method `rdc/unmount`'s guard checks."
  [tag]
  #js {:rf-test-root-tag tag
       :unmount          (fn [] nil)})

;; ---- MUST 2: drain active roots -------------------------------------------

(deftest dispose-adapter-drains-stranded-active-roots
  (testing "dispose-adapter! unmounts every root mounted-but-not-unmounted
            (the headless / hot-reload path) — MUST 2"
    (let [unmount-calls (atom [])
          root-a        (make-fake-root :a)
          root-b        (make-fake-root :b)
          root-c        (make-fake-root :c)
          roots         (atom [root-a root-b root-c])]
      (with-redefs [rdc/create-root (fn
                                      ([_]   (let [[r] @roots] (swap! roots rest) r))
                                      ([_ _] (let [[r] @roots] (swap! roots rest) r)))
                    rdc/render      (fn
                                      ([_ _]     nil)
                                      ([_ _ _]   nil)
                                      ([_ _ _ _] nil))
                    rdc/unmount     (fn [root] (swap! unmount-calls conj root) nil)]
        (let [render-fn (:render rf.adapter.reagent-slim/adapter)]
          ;; Mount three roots; do NOT call the returned unmount thunks
          ;; — they are now "stranded" (mounted-but-not-unmounted).
          (render-fn [:div "a"] #js {} nil)
          (render-fn [:div "b"] #js {} nil)
          (render-fn [:div "c"] #js {} nil)
          (is (empty? @unmount-calls)
              "precondition: no roots unmounted before dispose")
          (rf.substrate.adapter/dispose-adapter!)
          (is (= [3 #{root-a root-b root-c}] [(count @unmount-calls) (set @unmount-calls)])
              "dispose-adapter! unmounted each of the three stranded roots once"))))))

(deftest dispose-adapter-tolerates-throwing-root
  (testing "one root whose unmount throws does not strand the rest of
            the drain — MUST 2 (per-root try/catch) — and the
            failure is then rethrown rather than discarded"
    (let [unmount-calls (atom [])
          sentinel      (ex-info "boom" {:root :bad})
          bad-root      (make-fake-root :bad)
          good-root     (make-fake-root :good)
          roots         (atom [bad-root good-root])]
      (with-redefs [rdc/create-root (fn
                                      ([_]   (let [[r] @roots] (swap! roots rest) r))
                                      ([_ _] (let [[r] @roots] (swap! roots rest) r)))
                    rdc/render      (fn ([_ _] nil) ([_ _ _] nil) ([_ _ _ _] nil))
                    rdc/unmount     (fn [root]
                                      (swap! unmount-calls conj root)
                                      (when (identical? root bad-root)
                                        (throw sentinel))
                                      nil)]
        (let [render-fn (:render rf.adapter.reagent-slim/adapter)]
          (render-fn [:div "bad"] #js {} nil)
          (render-fn [:div "good"] #js {} nil)
          (let [thrown (try (rf.substrate.adapter/dispose-adapter!)
                            ::returned-normally
                            (catch :default e e))]
            (is (= #{bad-root good-root} (set @unmount-calls))
                "the throwing root's unmount was attempted and the good root still drained")
            (is (identical? sentinel thrown)
                "dispose-adapter! rethrew the per-root failure unchanged after
                the drain, instead of reporting a clean nil")))))))

;; ---- MUST 3: clear the hiccup-emitter -------------------------------------

(deftest dispose-adapter-clears-hiccup-emitter
  (testing "dispose-adapter! resets the SSR hiccup-emitter to nil so the
            installed fn (which captures re-frame.ssr state) does not
            survive teardown — MUST 3"
    (rf.adapter.reagent-slim/set-hiccup-emitter!
      (fn [tree _opts] (str "EMITTED:" (pr-str tree))))
    (is (= "EMITTED:[:p \"hi\"]"
           ((:render-to-string rf.adapter.reagent-slim/adapter) [:p "hi"] nil))
        "precondition: the installed emitter is live before dispose")

    (rf.substrate.adapter/dispose-adapter!)
    (is (thrown-with-msg?
          cljs.core.ExceptionInfo
          #":rf.error/no-hiccup-emitter-bound"
          ((:render-to-string rf.adapter.reagent-slim/adapter) [:p "hi"] nil))
        "after dispose the emitter is nil; render-to-string raises no-emitter-bound")))
