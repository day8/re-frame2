(ns re-frame.ui.adapter-conformance-dom-cljs-test
  "Browser proof for the native adapter's render/flush/dispose lifecycle.

  Container and SSR semantics live in adapter-cljs-test; this focused arm
  exercises the two contract functions that need a real DOM and proves
  `rf/destroy-adapter!` drains adapter-owned React roots before re-init."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf]
            [re-frame.router :as router]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui :as ui :refer [defview frame-root]]
            [re-frame.ui.reactive :as reactive]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; React's act() — the canonical committing-mount helper (mirrors the shared
;; suite / root_mount pattern). Used to COMMIT the initial compiled mount; the
;; rf2-vxgfnd.207 proof itself drives the production `flush-render!` (a real
;; flushSync commit, act env OFF) on the already-mounted view.
(defn- get-act []
  (or (when (exists? (.-act react)) (.-act react))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- act! [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

(use-fixtures
  :each
  (fn [f]
    (substrate-adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (substrate-adapter/reset-lifecycle-state-for-tests!)))))

(defn- render-element! [container element]
  (let [unmount (atom nil)]
    ((:flush-render! ui/adapter)
     #(reset! unmount
              ((:render ui/adapter)
               element
               container
               {})))
    @unmount))

(defn- render-label! [container label]
  (render-element!
   container
   (react/createElement "span" #js {:className "native-adapter-probe"} label)))

(defn- current-frame-probe []
  (react/createElement
   "span"
   #js {:className "native-provider-frame"}
   (str (adapter-context/function-component-current-frame))))

(deftest registered-provider-mounts-and-establishes-the-frame-context
  (if-not (browser?)
    (is true ":node — browser-test exercises the mounted provider contract")
    (let [frame-id  :rf.ui-adapter-test/provider-frame
          container (js/document.createElement "div")]
      (is (nil? (rf/init! ui/adapter)))
      (rf/make-frame {:id frame-id :doc "native adapter provider proof"})
      (let [provider ((:register-context-provider ui/adapter) frame-id)
            unmount  (render-element!
                      container
                      (react/createElement
                       provider
                       #js {:frame frame-id}
                       (react/createElement current-frame-probe nil)))]
        (try
          (is (= (str frame-id) (.-textContent container))
              "the registered component really mounted and its descendant observed the requested frame through React context")
          (finally
            (unmount)
            (rf/destroy-frame! frame-id)))))))

(deftest render-dispose-and-reinit-own-the-react-root-lifecycle
  (if-not (browser?)
    (is true ":node — browser-test exercises the DOM contract")
    (let [container (js/document.createElement "div")]
      (is (nil? (rf/init! ui/adapter)))
      (let [unmount (render-label! container "first")]
        (is (fn? unmount) ":render returns its root-unmount handle")
        (is (= "first" (.-textContent container))
            ":flush-render! commits the adapter-owned React root synchronously"))

      (is (nil? (rf/destroy-adapter!)))
      (is (= "" (.-textContent container))
          "adapter disposal drains every active root")

      (is (nil? (rf/init! ui/adapter)))
      (render-label! container "second")
      (is (= "second" (.-textContent container))
          "re-init re-arms render ownership after disposal"))))

;; ---------------------------------------------------------------------------
;; flush-render! settles pending compiled ViewCells before returning
;; (rf2-vxgfnd.207)
;;
;; The existing conformance fixtures exercise flush-render! with STATIC react
;; elements, where a bare `react-dom/flushSync` suffices. This arm proves the
;; first-party adapter's flush-render! against a REACTIVE compiled view: a
;; `dispatch-sync!` inside the flush changes a subscribed value, which marks the
;; ViewCell dirty and arms a LATER microtask — so the inherited spine verb would
;; return with the DOM still stale. The override must settle the pending ViewCell
;; (and its React commit) before returning.
;; ---------------------------------------------------------------------------

(defview greeter []
  [:span.greet (str (ui/sub [::greeting]))])

(deftest flush-render-settles-pending-viewcells-before-returning
  (if-not (browser?)
    (is true ":node — browser-test exercises the mounted flush-render! contract")
    (let [container (js/document.createElement "div")
          flush!    (:flush-render! ui/adapter)
          root      (atom nil)
          prior-act (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
      (js/document.body.appendChild container)
      (is (nil? (rf/init! ui/adapter)))
      ;; Register the sub/event PER-TEST (after the fixture reset), like the other
      ;; mounted suites — the fixture clears the registrar between tests.
      (rf/reg-sub ::greeting (fn [db _] (:greeting db)))
      (rf/reg-event ::set-greeting (fn [{:keys [db]} [_ v]] {:db (assoc db :greeting v)}))
      (try
        ;; COMMIT the initial compiled mount under React act: frame-root creates +
        ;; seeds the frame ("old") at preflight, so the mounted sub-bearing view
        ;; reads the seeded value. (The rf2-vxgfnd.207 proof is the DISPATCH below,
        ;; on this already-mounted view — the bead's "a mounted view CHANGED by
        ;; dispatch-sync! inside flush-render!".)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
        (act!
         #(reset! root
                  (ui/mount [frame-root {:id :rf.ui-adapter-test/flush-render-frame
                                         :initial-events [[::set-greeting "old"]]}
                             [greeter]]
                            container
                            {:root-id :rf.ui-adapter-test/flush-render})))
        (is (= "old" (.-textContent container))
            "the mounted compiled view reads the frame-root-seeded value")

        ;; THE PROOF (RED pre-fix): a dispatch-sync! INSIDE flush-render! must
        ;; expose the new DOM before the call returns. The dispatch changes the
        ;; subscribed value → `reactive/enrol-dirty!` marks the ViewCell and arms
        ;; a LATER microtask WITHOUT notifying React; the inherited spine
        ;; flush-render! (bare flushSync) therefore returns with the DOM still
        ;; "old" and the cell still pending. Drive the PRODUCTION flush-render!
        ;; with the act env OFF — a real flushSync commit path.
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (flush! #(router/dispatch-sync! [::set-greeting "new"]
                                        {:frame :rf.ui-adapter-test/flush-render-frame}))
        (is (= "new" (.-textContent container))
            "flush-render! settled the pending ViewCell + React commit before returning (rf2-vxgfnd.207)")
        (is (zero? (reactive/pending-cell-count))
            "the dirty registry is empty on return — no redundant later microtask render")
        (finally
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (when @root (act! #(ui/unmount! @root)))
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior-act)
          (.remove container))))))
