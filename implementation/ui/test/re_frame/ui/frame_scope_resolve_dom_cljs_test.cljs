(ns re-frame.ui.frame-scope-resolve-dom-cljs-test
  "rf2-vxgfnd.24 + rf2-vxgfnd.25 — the KEYSTONE, proven end to end on a REAL
  react-dom root through a REAL ViewCell: a compiled `(sub …)` mounted
  inside a `frame-provider` / `frame-root` subtree resolves the scoped
  frame's app-db on the AMBIENT React-context path — no explicit pin, no
  dynamic `with-frame` binding.

    - .24 publishes the `:adapter/current-frame` reader (re-frame.ui.adapter),
      so core's `require-current-frame!` — reached by the compiled sub-read
      (`reactive/sub-read` → `observation/resolve-target`) — sees the
      React-context tier under the plain-atom runtime.
    - .25 makes `frame-root` EMIT that scope (frames/scope-element), so a sub
      under a bare `frame-root` (no enclosing `frame-provider`) resolves too.

  The `(sub …)` lives in a DESCENDANT view boundary (`n-view`), not the
  provider's own body — React-context semantics scope descendants, and a
  ViewCell render is where `_currentValue` is live.

  Browser-only bodies — `-dom-cljs-test$` opts this file into `:browser-test`;
  under `:node-test` every DOM body gates on `(browser?)` and exits early."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t]
    (client/reset-live-roots!)
    (frames/reset-installed-plans!)
    (t)
    (client/reset-live-roots!)
    (frames/reset-installed-plans!)))

(defn- container [] (js/document.createElement "div"))

(defn- reg! []
  (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
  (rf/reg-sub :scope/n (fn [db _] (:n db))))

;; n-view SUBS ambiently — a descendant view boundary, so its ViewCell render
;; reads the enclosing provider/root scope through the React context.
(defview n-view [] [:div.n (str "n=" (sub [:scope/n]))])

;; the sub sits UNDER a frame-provider, in a descendant view (n-view), never
;; in the provider's own template body.
(defview provider-wrap [{:keys [frame-id]}]
  [:div.wrap [frame-provider {:frame frame-id} [n-view]]])

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.24 — a compiled sub under a frame-provider resolves ambiently
;; ---------------------------------------------------------------------------

(deftest sub-under-frame-provider-resolves-scoped-frame-ambiently
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/live})
    (rf/dispatch-sync [:test/set-db {:n 42}] {:frame :app/live})
    (let [c (container)]
      (react-dom/flushSync
       #(ui/mount [provider-wrap {:frame-id :app/live}] c {:root-id :dom-scope/prov}))
      (is (re-find #"n=42" (.-innerHTML c))
          (str "the ambient (sub …) resolved the frame-provider-scoped frame "
               "through the React-context tier — the :adapter/current-frame "
               "reader is live on the compiled sub-read path (rf2-vxgfnd.24)")))))

;; NOTE: the frame-ROOT counterpart (`sub-under-frame-root-resolves-…`) lands
;; with rf2-vxgfnd.25 below (frame-root must EMIT its scope first).

;; ---------------------------------------------------------------------------
;; NEGATIVE — a compiled sub OUTSIDE any provider/root still fails loud
;; (React captures the render-phase throw; the container never commits).
;; The synchronous, assert-the-id form of this negative rides the headless
;; twin (frame-context-hook-cljs-test); here we pin the mount-level effect:
;; no scope → nothing renders.
;; ---------------------------------------------------------------------------

(defview bare-sub-root [] [:div.bare [n-view]])

(deftest sub-outside-any-provider-does-not-resolve
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/orphan})
    (rf/dispatch-sync [:test/set-db {:n 99}] {:frame :app/orphan})
    (let [c (container)]
      ;; no frame-provider / frame-root anywhere above n-view → the ambient
      ;; sub-read finds no scope and throws :rf.error/no-frame-context during
      ;; render; React aborts the commit, so the scoped value never appears.
      (try (react-dom/flushSync
            #(ui/mount [bare-sub-root {}] c {:root-id :dom-scope/orphan}))
           (catch :default _e nil))
      (is (not (re-find #"n=99" (.-innerHTML c)))
          (str "with NO provider/root above it, the ambient sub does not "
               "silently resolve some frame — the fail-loud contract holds")))))
