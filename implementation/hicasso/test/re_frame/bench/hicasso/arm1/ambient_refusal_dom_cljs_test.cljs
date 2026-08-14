(ns re-frame.bench.hicasso.arm1.ambient-refusal-dom-cljs-test
  "THE SCOPING ROW, AGAINST REAL REACT (rf2-2rtt6.122).

  The refusal tier's one genuine risk is not that it fails to refuse — the
  node suite (`arm1/ambient_refusal_cljs_test`) settles that with the
  frame published on the context slot and a control row proving the same
  read succeeds one call outside a body. The risk is that it refuses TOO
  MUCH. A fence that also refused legitimate ambient use in an adapter
  island rendering under a Hicasso tree would be a worse bug than the
  silence it replaced, and it is the one claim no node test can settle,
  because it is a claim about WHEN REACT CALLS A CHILD.

  The reasoning says it cannot happen: React renders a child fiber only
  after the parent's render function has returned, so the body-scoped
  binding has already unwound before any child runs. That is reasoning
  about React, which is exactly the kind of claim this lane asserts
  against a real root rather than argues.

  So: a real `createRoot`, a real `frame-provider`, a real Hicasso
  boundary, and a real foreign React component under it through the one
  interop door — reading `rf/subscribe` AMBIENTLY, which is the normal and
  correct idiom on every adapter re-frame2 ships. It must read the frame,
  render the value, and follow it when the frame moves.

  The file is deliberately all-green: nothing here throws, so no row
  depends on catching a render-phase exception React routes to
  `reportError` where `cljs.test` cannot see it. Mounting the whole page
  at all is itself the second witness — the Hicasso shell's own
  `useContext`, the codec's root element, the error boundary and the
  presence tray all render inside or around the refusing extent, and any
  one of them reaching for an ambient frame would take this suite down."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]
            ["react" :as react])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview defhost]]))

(rf/reg-sub :rt122d/v (fn [db _] (:v db)))
(rf/reg-event :rt122d/bump (fn [{:keys [db]} _] {:db (update db :v inc)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::rt122-dom)

(defn- frame! []
  (lane/leave-act-environment!)
  (live-frame/make-frame {:id frame-id})
  (frame/replace-app-db! frame-id {:v 7})
  frame-id)

(defn- skip! [why]
  (is true (str "needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; The island — a foreign component doing what every adapter teaches
;; ---------------------------------------------------------------------------

(defn- island-component
  "A plain React function component that reads AMBIENTLY. Nothing about it
  knows Hicasso exists; it is written the way the Reagent, reagent-slim
  and UIx guides all teach, and it resolves its frame from the enclosing
  `frame-provider` through the shared adapter context. If the refusal
  leaked past the body that rendered it, this read is where it would
  surface — as a throw, not as a wrong value."
  [_props]
  (react/createElement "output" #js {"className" "island"}
                       (str @(rf/subscribe [:rt122d/v]))))

(defhost island island-component)

(defview page
  "One boundary. The collector's read is the substrate's own surface; the
  island's is the ambient one, in the one position where it stays legal."
  [_]
  [:div.page
   [:output.collector (str (rt/sub [:rt122d/v]))]
   [island {}]])

(defn- text-in [handle selector]
  (some-> (.querySelector (:container handle) selector) (.-textContent)))

;; ---------------------------------------------------------------------------

(deftest an-adapter-island-under-a-hicasso-tree-still-resolves-ambiently
  (if-not (mount/browser?)
    (skip! "the child-render ordering claim is React's, not the runtime's")
    (testing "a foreign component rendered from a Hicasso body reads its
             frame ambiently and renders the value — the binding unwound
             when the body returned, before React ever called the child"
      (let [f      (frame!)
            handle (mount/root! (mount/fresh-container!) f [page])]
        (try
          (is (= "7" (text-in handle ".collector"))
              "precondition: the boundary itself rendered through its collector")
          (is (= "7" (text-in handle ".island"))
              "and the ambient read inside the island resolved the same frame —
               a refusal leaking past the body would have thrown here instead")
          (testing "and it keeps following the frame when the state moves"
            (mount/dispatch! handle [:rt122d/bump])
            (mount/settle!)
            (is (= "8" (text-in handle ".collector")))
            (is (= "8" (text-in handle ".island"))
                "the island re-read ambiently on the boundary's re-render"))
          (finally (mount/release! handle)))))))

(deftest mounting-the-page-at-all-is-the-shells-own-witness
  (if-not (mount/browser?)
    (skip! "no DOM")
    (testing "the shell's `useContext`, the codec's root element and the
             boundary machinery all run inside or around the refusing
             extent; a page that mounts and paints is each of them proven
             not to have reached for an ambient frame"
      (let [f      (frame!)
            handle (mount/root! (mount/fresh-container!) f [page])]
        (try
          (is (some? (.querySelector (:container handle) ".page"))
              "the tree rendered")
          (is (nil? frame/*ambient-frame-refusal*)
              "and the extent is not live outside a body run")
          (finally (mount/release! handle)))))))
