(ns re-frame.ui.reactive-reincarnation-dom-cljs-test
  "rf2-vxgfnd.93 — the same-id reincarnation correction fires in the layout
  commit BEFORE paint, under REAL React 19 + real DOM (bead rf2-vxgfnd.14 AC #2
  on the React spine).

  `reactive-reconcile-cljs-test` graft-checks the `:node-key` reincarnation
  detection headlessly (and fails-pre-fix there); this file proves the SAME
  correction rides the `useLayoutEffect` commit on the real spine. The staging is
  deterministic: the `reactive/*commit-barrier* :pre-acquire` seam fires inside
  the leaf's layout commit — after the render probed incarnation A but BEFORE the
  commit's stage-acquire — where the fixture DESTROYS + RECREATES the frame under
  the same id. The commit then ACQUIRES the fresh incarnation (a strictly-greater
  node-key) while holding the render's probe of the destroyed one, so
  `evidence-moved?` classifies it MOVED via the node-key axis and advances the
  revision, which re-renders SYNCHRONOUSLY inside the same `flushSync` — before
  any paint. The DOM reads the reincarnated value with no macrotask/paint between.

  Browser-only bodies — `-dom-cljs-test$` opts into `:browser-test`; `:node-test`
  loads it too, where the DOM body gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            ;; the CLJS emitter wraps a sub-bearing view in viewcell/render.
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil})
  (fn [f]
    (reactive/reset-scheduler!)
    (client/reset-live-roots!)
    (try (f) (finally (reactive/reset-scheduler!) (client/reset-live-roots!)))))

(def ^:private frame-kw :rre/frame)

(defn- container [] (js/document.createElement "div"))

(defview leaf [_] [:span.leaf (str (sub [:rre/n]))])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "reincarnation before-paint probe frame"})
  (rf/reg-sub :rre/n (fn [db _] (:n db)))
  (rf/reg-event :rre/seed (fn [_ _] {:db {:n 1}})))

(deftest reincarnation-in-the-gap-corrects-before-paint
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rre/seed] {:frame frame-kw})
      (let [c     (container)
            armed (atom true)]
        ;; Arm the :pre-acquire seam to reincarnate the frame ONCE, inside the
        ;; mount's layout commit: destroy A (which the not-yet-connected leaf cell
        ;; escapes — it enrols only at the post-publish connect!) and recreate B
        ;; under the same id with a DIFFERENT value. The commit then acquires B's
        ;; fresh node (a distinct node-key) while holding the render's probe of A.
        (binding [reactive/*commit-barrier*
                  (fn [phase _cell]
                    (when (and @armed (= :pre-acquire phase))
                      (reset! armed false)              ;; once — disarm the re-commit
                      (frame/destroy-frame! frame-kw)
                      (rf/make-frame {:id frame-kw})
                      (frame/replace-app-db! frame-kw {:n 2})))]
          (let [root (react-dom/flushSync
                      #(ui/mount [frame-provider {:frame frame-kw} [leaf {}]]
                                 c {:root-id :rre/root}))]
            (testing "the node-key reincarnation was corrected in the layout commit, before paint"
              (is (= "2" (.-textContent (.querySelector c ".leaf")))
                  "the corrective render fired synchronously inside flushSync (before
                   any paint/macrotask) — the reincarnated value is on the DOM"))
            (react-dom/flushSync #(ui/unmount! root))))))))
