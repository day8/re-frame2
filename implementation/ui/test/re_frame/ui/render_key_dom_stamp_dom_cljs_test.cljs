(ns re-frame.ui.render-key-dom-stamp-dom-cljs-test
  "rf2-ny34u (.98.1 slice c follow-up) — the render-key DOM stamp at commit time.

  rf2-hac8p ships the compiler's STATIC host-root annotation (`data-rf-view` /
  `data-rf2-source-coord`) at render; rf2-rvs56 ships the integer `:render-key`
  minted at CONNECTED COMMIT. This proves the third leg: the substrate stamps
  that per-commit render-key onto the committed host-root DOM node as
  `data-rf-render-key`, in the ViewCell's reconcile layout effect (after
  `reactive/commit!` mints it). DOM-oriented tooling reads it to learn which
  render produced the on-screen node.

  Real DOM only: the stamp runs in a React layout effect against a committed
  host node, so the proof mounts a compiled sub ViewCell through
  `ui/create-root` and reads the attribute off the committed element. The
  `browser?` gate makes the node runtime (`npm run test:cljs`) a no-op and the
  browser runtime (`npm run test:browser`) the real body. Production erasure is
  the twin `render_key_dom_stamp_elision_prod_test`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]))

(def ^:private frame-id :render-key-stamp/frame)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :init-fn reactive/reset-scheduler!}))

(defn- browser? [] (exists? js/document))

(defview stamped-view
  "A sub-bearing view with a compiler-owned host root — the root that carries the
  static `data-rf-view` annotation and, at commit, the `data-rf-render-key`
  stamp. `:label` changes force a fresh connected commit (hence a fresh key)
  independently of the subscription value."
  [{:keys [label]}]
  [:div {:data-role "stamped"} (str label ":" (ui/sub [:rk/n]))])

(defview authored-render-key-view
  "A sub-bearing view whose compiler-owned host root carries a PROGRAMMER-AUTHORED
  `data-rf-render-key`. Trust the programmer (rf2-vxgfnd.98.1.3 / the rf2-x1nbv
  authored-wins law): the framework's owned stamp must never clobber it, and must
  never re-stamp it on later commits."
  [{:keys [label]}]
  [:div {:data-role "authored" :data-rf-render-key "author-owned"}
   (str label ":" (ui/sub [:rk/n]))])

(defview transitioning-root-view
  "A sub-bearing view whose compiler-owned host root gains an authored `:ref`
  across a re-render (nil ref → object ref, the preferred authored form). React
  reuses the same DOM node; the framework, on losing host ownership to the
  authored ref, must remove ITS OWN stale stamp and leave the ref intact
  (rf2-vxgfnd.98.1.3)."
  [{:keys [node-ref]}]
  [:div {:data-role "transition" :ref node-ref} (str (ui/sub [:rk/n]))])

(defn- stamped-node [container]
  (.querySelector container "[data-role='stamped']"))

(defn- stamp [container]
  (some-> (stamped-node container) (.getAttribute "data-rf-render-key")))

(defn- owning-cell []
  ;; The one live cell that committed ownership of [:rk/n] under this frame.
  (let [want #{[:sub frame-id [:rk/n]]}]
    (some (fn [cell]
            (when (= want (reactive/committed-target-keys cell)) cell))
          (reactive/current-live-cells))))

(deftest render-key-is-stamped-on-the-committed-dom-node-and-updates-per-commit
  (if-not (browser?)
    (is true ":node — the browser gate owns the real committed-DOM proof")
    (do
      (rf/reg-sub :rk/n (fn [db _] (:n db)))
      (rf/make-frame {:id frame-id :doc "render-key DOM stamp proof"})
      (frame/replace-app-db! frame-id {:n 1})
      (let [container (js/document.createElement "div")
            root      (ui/create-root container {:root-id :render-key-stamp/root})]
        (.appendChild js/document.body container)
        (try
          ;; --- mount: the committed node carries the render-key ---------------
          (ReactDOM/flushSync
           #(ui/render! root [ui/frame-provider {:frame frame-id}
                              [stamped-view {:label "a"}]]))
          (let [cell (owning-cell)
                k1   (some-> (stamp container) js/parseInt)]
            (is (some? (stamped-node container)) "the host root committed")
            (is (some? cell) "exactly one cell owns the subscription")
            (is (some? (stamp container))
                "GREEN: the committed host node carries data-rf-render-key")
            (is (and (integer? k1) (pos? k1))
                "the stamped value is a positive integer render-key")
            (is (= (reactive/render-key cell) k1)
                "the DOM stamp equals the substrate's minted render-key")

            ;; --- re-render: a fresh commit re-stamps a greater key ------------
            (ReactDOM/flushSync
             #(ui/render! root [ui/frame-provider {:frame frame-id}
                                [stamped-view {:label "b"}]]))
            (let [k2 (some-> (stamp container) js/parseInt)]
              (is (= (reactive/render-key cell) k2)
                  "the re-render re-stamps the node with the new minted key")
              (is (> k2 k1)
                  "render-key is monotonic per connected commit — the on-screen
                   node reports the render that produced it")
              (testing "the rendered text still reflects the live subscription"
                (is (= "b:1" (.-textContent (stamped-node container)))))))
          (finally
            (ReactDOM/flushSync #(ui/unmount! root))
            (.remove container)))))))

(deftest programmer-authored-data-rf-render-key-survives-development-commits
  ;; OWNED + TRUTHFUL (rf2-vxgfnd.98.1.3). Before the ownership fix the reconcile
  ;; effect unconditionally `setAttribute`d the render-key, clobbering an authored
  ;; value; now an authored `data-rf-render-key` the framework does not own wins.
  (if-not (browser?)
    (is true ":node — the browser gate owns the real committed-DOM proof")
    (do
      (rf/reg-sub :rk/n (fn [db _] (:n db)))
      (rf/make-frame {:id frame-id :doc "authored render-key survives"})
      (frame/replace-app-db! frame-id {:n 1})
      (let [container (js/document.createElement "div")
            root      (ui/create-root container {:root-id :render-key-stamp/authored-root})]
        (.appendChild js/document.body container)
        (try
          (ReactDOM/flushSync
           #(ui/render! root [ui/frame-provider {:frame frame-id}
                              [authored-render-key-view {:label "a"}]]))
          (let [node (.querySelector container "[data-role='authored']")]
            (is (some? node) "the authored host root committed")
            (is (= "author-owned" (.getAttribute node "data-rf-render-key"))
                "RED→GREEN: the authored value survives the first connected commit
                 (the framework opts out rather than clobbering it)")
            ;; A fresh connected commit must STILL leave the authored value alone —
            ;; the framework never claimed it, so it never re-stamps it.
            (ReactDOM/flushSync
             #(ui/render! root [ui/frame-provider {:frame frame-id}
                                [authored-render-key-view {:label "b"}]]))
            (is (= "author-owned" (.getAttribute node "data-rf-render-key"))
                "the authored value survives subsequent commits — never re-stamped")
            (is (= "b:1" (.-textContent node))
                "the view still rendered its live subscription value"))
          (finally
            (ReactDOM/flushSync #(ui/unmount! root))
            (.remove container)))))))

(deftest no-ref-to-authored-ref-transition-removes-the-framework-stamp
  ;; The truthfulness half (rf2-vxgfnd.98.1.3): when a stamped host root gains an
  ;; authored :ref while React REUSES the DOM node, the framework's host callback
  ;; detaches. Before the fix it detached without removing its stamp, leaving a
  ;; stale render-key on a now-opted-out node; now it removes ONLY its own stamp.
  (if-not (browser?)
    (is true ":node — the browser gate owns the real committed-DOM proof")
    (do
      (rf/reg-sub :rk/n (fn [db _] (:n db)))
      (rf/make-frame {:id frame-id :doc "render-key ownership cleanup"})
      (frame/replace-app-db! frame-id {:n 1})
      (let [container (js/document.createElement "div")
            root      (ui/create-root container {:root-id :render-key-stamp/transition-root})
            obj-ref   #js {:current nil}]
        (.appendChild js/document.body container)
        (try
          ;; --- render 1: un-reffed root → the framework claims + stamps -------
          (ReactDOM/flushSync
           #(ui/render! root [ui/frame-provider {:frame frame-id}
                              [transitioning-root-view {:node-ref nil}]]))
          (let [node (.querySelector container "[data-role='transition']")]
            (is (some? node) "the un-reffed host root committed")
            (is (some? (.getAttribute node "data-rf-render-key"))
                "the framework stamped its owned key on the un-reffed root")
            ;; --- render 2: gain an authored object :ref → node reused, stamp
            ;; removed (React swaps host-cb → obj-ref on the same DOM node) -------
            (ReactDOM/flushSync
             #(ui/render! root [ui/frame-provider {:frame frame-id}
                                [transitioning-root-view {:node-ref obj-ref}]]))
            (is (identical? node (.querySelector container "[data-role='transition']"))
                "React reused the same DOM node across the ref transition")
            (is (nil? (.getAttribute node "data-rf-render-key"))
                "RED→GREEN: the framework removed its OWN stale stamp when
                 ownership moved to the authored ref")
            (is (identical? node (.-current obj-ref))
                "the authored object ref received the reused node — ref
                 semantics preserved, only the framework attribute was touched"))
          (finally
            (ReactDOM/flushSync #(ui/unmount! root))
            (.remove container)))))))
