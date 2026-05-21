(ns re-frame.adapter.reagent-slim-dispose-drain-roots-cljs-test
  "Pins the reagent-slim adapter's `dispose-adapter!` four-MUST list
  items 2 (release host-specific resources: drain active React roots)
  and 3 (discard internal caches: clear the hiccup-emitter) — Spec 006
  §Adapter disposal lifecycle, rf2-7v82h.

  Before rf2-7v82h the slim adapter's `dispose-adapter!` only ran the
  per-frame sub-cache walk (MUST 1); it never tracked active React
  roots and never cleared the SSR hiccup-emitter, so an
  `init! → render → dispose-adapter!` cycle (test fixtures, hot-reload,
  SSR string-serialise without mount) left React roots mounted and the
  installed emitter alive across teardown. The Reagent adapter already
  honoured both MUSTs (reagent.cljs:100-137); this brings slim to
  parity.

  Strategy mirrors `re-frame.adapter-render-cljs-test`: spy on
  `reagent2.dom.client`'s create-root / render / unmount via
  `with-redefs` so the test runs under :node-test with no real DOM.
  We drive the adapter's private `:render` slot to register stranded
  roots, install a hiccup-emitter, then call `dispose-adapter!` and
  assert every stranded root was unmounted and the emitter is nil.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start. The unit under test IS `dispose-adapter!`, so we install
;; the slim adapter ourselves and let the test body drive render +
;; dispose. Each test cleans up after itself so a re-run is idempotent.
;; Frames are wiped so the MUST-1 sub-cache walk inside dispose sees an
;; empty registry (this file pins MUST 2 + 3, not MUST 1).

(defn fresh-slim [test-fn]
  (adapter/reset-lifecycle-state-for-tests!)
  (reset! frame/frames {})
  (adapter/install-adapter! reagent-slim-adapter/adapter)
  (test-fn)
  (reset! frame/frames {})
  (adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each fresh-slim)

;; ---- helpers --------------------------------------------------------------

(defn- mk-fake-root
  "A fake Root identity — an object carrying an `.unmount` method so
  `reagent2.dom.client/unmount`'s `(some? (.-unmount root))` guard
  passes. The spy on `rdc/unmount` records calls; the method body is
  never reached because we redef the var."
  [tag]
  #js {:rf-test-root-tag tag
       :unmount          (fn [] nil)})

;; ---- MUST 2: drain active roots -------------------------------------------

(deftest dispose-adapter-drains-stranded-active-roots
  (testing "dispose-adapter! unmounts every root mounted-but-not-unmounted
            (the headless / hot-reload path) — rf2-7v82h MUST 2"
    (let [unmount-calls (atom [])
          root-a        (mk-fake-root :a)
          root-b        (mk-fake-root :b)
          root-c        (mk-fake-root :c)
          roots         (atom [root-a root-b root-c])]
      (with-redefs [rdc/create-root (fn
                                      ([_]   (let [[r] @roots] (swap! roots rest) r))
                                      ([_ _] (let [[r] @roots] (swap! roots rest) r)))
                    rdc/render      (fn
                                      ([_ _]     nil)
                                      ([_ _ _]   nil)
                                      ([_ _ _ _] nil))
                    rdc/unmount     (fn [root] (swap! unmount-calls conj root) nil)]
        (let [render-fn (:render reagent-slim-adapter/adapter)]
          ;; Mount three roots; do NOT call the returned unmount thunks
          ;; — they are now "stranded" (mounted-but-not-unmounted).
          (render-fn [:div "a"] #js {} nil)
          (render-fn [:div "b"] #js {} nil)
          (render-fn [:div "c"] #js {} nil)
          (is (empty? @unmount-calls)
              "precondition: no roots unmounted before dispose")

          ;; Drive the drain.
          (adapter/dispose-adapter!)

          (is (= 3 (count @unmount-calls))
              "dispose-adapter! unmounted all three stranded roots")
          (doseq [r [root-a root-b root-c]]
            (is (some #(identical? r %) @unmount-calls)
                (str "stranded root " (pr-str r) " was drained by dispose-adapter!"))))))))

(deftest dispose-adapter-does-not-double-unmount-explicitly-unmounted-roots
  (testing "a root whose unmount thunk already fired is removed from the
            active set, so dispose-adapter! does NOT unmount it again
            — rf2-7v82h MUST 2 (the thunk disj's itself before unmount)"
    (let [unmount-calls (atom [])
          root-live     (mk-fake-root :live)
          root-gone     (mk-fake-root :gone)
          roots         (atom [root-live root-gone])]
      (with-redefs [rdc/create-root (fn
                                      ([_]   (let [[r] @roots] (swap! roots rest) r))
                                      ([_ _] (let [[r] @roots] (swap! roots rest) r)))
                    rdc/render      (fn ([_ _] nil) ([_ _ _] nil) ([_ _ _ _] nil))
                    rdc/unmount     (fn [root] (swap! unmount-calls conj root) nil)]
        (let [render-fn (:render reagent-slim-adapter/adapter)
              _live-thunk (render-fn [:div "live"] #js {} nil)
              gone-thunk  (render-fn [:div "gone"] #js {} nil)]
          ;; Explicitly unmount the second root via its thunk.
          (gone-thunk)
          (is (= [root-gone] @unmount-calls)
              "precondition: explicit unmount fired exactly once for the gone root")

          ;; Dispose: only the still-live root should be drained.
          (adapter/dispose-adapter!)

          (is (= 1 (count (filter #(identical? root-gone %) @unmount-calls)))
              "the explicitly-unmounted root is NOT unmounted a second time")
          (is (some #(identical? root-live %) @unmount-calls)
              "the still-live root IS drained by dispose-adapter!"))))))

(deftest dispose-adapter-tolerates-throwing-root
  (testing "one root whose unmount throws does not strand the rest of
            the drain — rf2-7v82h MUST 2 (per-root try/catch)"
    (let [unmount-calls (atom [])
          bad-root      (mk-fake-root :bad)
          good-root     (mk-fake-root :good)
          roots         (atom [bad-root good-root])]
      (with-redefs [rdc/create-root (fn
                                      ([_]   (let [[r] @roots] (swap! roots rest) r))
                                      ([_ _] (let [[r] @roots] (swap! roots rest) r)))
                    rdc/render      (fn ([_ _] nil) ([_ _ _] nil) ([_ _ _ _] nil))
                    rdc/unmount     (fn [root]
                                      (swap! unmount-calls conj root)
                                      (when (identical? root bad-root)
                                        (throw (ex-info "boom" {:root root})))
                                      nil)]
        (let [render-fn (:render reagent-slim-adapter/adapter)]
          (render-fn [:div "bad"] #js {} nil)
          (render-fn [:div "good"] #js {} nil)
          ;; Must not propagate the bad root's throw.
          (is (nil? (adapter/dispose-adapter!))
              "dispose-adapter! swallows the per-root throw and returns nil")
          (is (some #(identical? bad-root %) @unmount-calls)
              "the throwing root's unmount was attempted")
          (is (some #(identical? good-root %) @unmount-calls)
              "the good root was still drained despite the earlier throw"))))))

;; ---- MUST 3: clear the hiccup-emitter -------------------------------------

(deftest dispose-adapter-clears-hiccup-emitter
  (testing "dispose-adapter! resets the SSR hiccup-emitter to nil so the
            installed fn (which captures re-frame.ssr state) does not
            survive teardown — rf2-7v82h MUST 3"
    ;; Install an emitter and prove render-to-string resolves it.
    (reagent-slim-adapter/set-hiccup-emitter!
      (fn [tree _opts] (str "EMITTED:" (pr-str tree))))
    (is (= "EMITTED:[:p \"hi\"]"
           ((:render-to-string reagent-slim-adapter/adapter) [:p "hi"] nil))
        "precondition: the installed emitter is live before dispose")

    ;; Dispose drains the emitter.
    (adapter/dispose-adapter!)

    ;; Post-dispose: render-to-string raises the no-emitter-bound error
    ;; (the only black-box proof the emitter atom was reset to nil).
    (is (thrown-with-msg?
          cljs.core.ExceptionInfo
          #":rf.error/no-hiccup-emitter-bound"
          ((:render-to-string reagent-slim-adapter/adapter) [:p "hi"] nil))
        "after dispose the emitter is nil; render-to-string raises no-emitter-bound")))
