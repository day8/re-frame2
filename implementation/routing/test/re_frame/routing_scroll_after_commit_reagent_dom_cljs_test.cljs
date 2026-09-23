(ns re-frame.routing-scroll-after-commit-reagent-dom-cljs-test
  "rf2-3x7nj.12.3 — WHERE THE PAGE LANDS, measured in a real browser under
  the Reagent adapter's ordinary mount path.

  The pages here are UNEQUAL in height on purpose. The older witnesses
  (`re-frame.routing-conduct-dom-cljs-test`, the Fresco navigation conduct
  test) give every pane the same filler so a saved offset is never clamped,
  which is exactly what hid a scroll made BEFORE the new pane committed.

  Three rows:

    1. A cross-route `:fragment` whose element exists only on the arriving
       page lands on it (shared with the reagent-slim and UIx witnesses).
    2. Back to a page TALLER than the one being left restores the full
       offset, with `history.scrollRestoration` set to \"manual\" so the
       browser's own restore cannot supply it.
    3. The rf2-pk4i6.7 #3 check, under the browser's default \"auto\": a
       route declaring `:scroll :top` is at the top after Back, and stays
       there — the browser's traversal restore does not win the race.

  Every reading is taken inside an after-render callback queued behind the
  navigation (or two frames later, for row 3's settle check); nothing flushes
  a render on the implementation's behalf.

  ns ends in `-dom-cljs-test`; under `:node-test` every row is a stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.routing-scroll-witness-test-support :as witness]
            [re-frame.test-support :as rf.test-support]))

(defn- skip! [why]
  (is true (str "a scroll witness needs a real browser — " why)))

(def ^:private list-url "/rf2-scroll-witness/list")
(def ^:private top-list-url "/rf2-scroll-witness/top-list")
(def ^:private detail-url "/rf2-scroll-witness/detail")

(defn- register-back-routes! []
  (rf.routing/reg-route ::list {:doc "A TALL list."} list-url)
  (rf.routing/reg-route ::top-list {:doc "A TALL list that always opens at the top."
                                    :scroll :top}
                        top-list-url)
  (rf.routing/reg-route ::detail {:doc "A SHORT detail page."} detail-url)
  nil)

(rf/reg-view* ::back-page
  (fn back-page []
    (if (#{::list ::top-list} @(rf/subscribe [:rf.route/id]))
      [:div {:style {:height "6000px"}} "list"]
      [:div {:style {:height "100px"}} "detail"])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.routing/reset-counters!)
                      (rf.routing/reset-scroll-cache!)
                      (witness/register-routes!)
                      (register-back-routes!))}))

(defn- mount! [view container frame-id]
  (let [root (rdc/create-root container)]
    ;; The INITIAL mount commits synchronously so there is a baseline page;
    ;; nothing after it forces a commit.
    (react-dom/flushSync
      (fn [] (rdc/render root [rf/frame-provider {:frame frame-id} [(rf/view view)]])))
    root))

(deftest a-cross-route-fragment-lands-on-the-arriving-section
  (if-not (witness/browser?)
    (skip! ":node-test has no layout or scroll model")
    (async done
      (witness/run-fragment-row!
        {:adapter-name "Reagent"
         :mount!       (partial mount! ::witness/page)
         :unmount!     #(.unmount %)}
        done))))

;; ---- Back ------------------------------------------------------------------

(def ^:private deep-offset 3000)

(defn- back-after-deep-scroll!
  "On `from-url` (a tall page) scroll to `deep-offset`, navigate forward to
  the short detail page, press the browser's Back button, and resolve with
  `scrollY` read inside an after-render callback queued from a `popstate`
  listener installed AFTER the frame's own — so it runs behind the scroll the
  frame's listener queued."
  [{:keys [from-url scroll-restoration]} check done]
  (let [frame-id   ::back-frame
        runner-url (.-href js/location)
        prior      (.-scrollRestoration js/window.history)
        container  (.createElement js/document "div")
        _          (.appendChild js/document.body container)
        restore!   (witness/isolate! container)]
    (set! (.-scrollRestoration js/window.history) scroll-restoration)
    (.replaceState js/window.history nil "" from-url)
    (rf/make-frame {:id frame-id :url-bound? true})
    (let [root (mount! ::back-page container frame-id)]
      (-> (witness/after-commit (fn []) (fn [] nil))
          (.then
            (fn [_]
              (.scrollTo js/window 0 deep-offset)
              (is (= deep-offset (witness/scroll-y))
                  (str "precondition: the tall page scrolls to " deep-offset
                       " — it reads " (witness/scroll-y)))
              (witness/after-commit
                #(rf/dispatch-sync [:rf.route/navigate {:to ::detail}] {:frame frame-id})
                (fn [] [(witness/scroll-y) (witness/max-scroll-y)]))))
          (.then
            (fn [[y max-y]]
              (is (= 0 y) "precondition: forward to the short detail page lands at the top")
              (is (< max-y deep-offset)
                  (str "precondition: the detail page can scroll only " max-y "px, so a"
                       " restore to " deep-offset " made on it would be clamped. Without"
                       " this the Back row cannot fail"))
              (js/Promise.
                (fn [resolve]
                  (let [on-pop (fn on-pop [_]
                                 (.removeEventListener js/window "popstate" on-pop)
                                 (rf.interop/after-render
                                   (fn [] (resolve (witness/scroll-y)))))]
                    (.addEventListener js/window "popstate" on-pop)
                    (.back js/window.history))))))
          (.then check)
          (.catch (fn [e] (is false (str "the row never settled: " e))))
          (.then (fn [_]
                   (try (.unmount root) (catch :default _ nil))
                   (try (.remove container) (catch :default _ nil))
                   (restore!)
                   (.replaceState js/window.history nil "" runner-url)
                   (set! (.-scrollRestoration js/window.history) prior)
                   (try (.scrollTo js/window 0 0) (catch :default _ nil))
                   (try (rf/destroy-frame! frame-id) (catch :default _ nil))
                   (done)))))))

(deftest back-to-a-taller-page-restores-the-whole-offset
  (if-not (witness/browser?)
    (skip! ":node-test has no history or scroll model")
    (async done
      (back-after-deep-scroll!
        {:from-url list-url :scroll-restoration "manual"}
        (fn [y]
          (is (= deep-offset y)
              (str "Back restored " y " rather than " deep-offset ". A restore made"
                   " while the SHORT page was still mounted is clamped to its height,"
                   " and the list then renders at the clamped offset")))
        done))))

(deftest a-top-route-stays-at-the-top-after-back-under-auto-restoration
  (if-not (witness/browser?)
    (skip! ":node-test has no history or scroll model")
    (async done
      (back-after-deep-scroll!
        {:from-url top-list-url :scroll-restoration "auto"}
        (fn [y]
          (testing "rf2-pk4i6.7 #3: the route asks for :top on every arrival"
            (is (= 0 y)
                (str "Back left the page at " y ". The route declares `:scroll :top`,"
                     " so a non-zero reading is the browser's own traversal restore"
                     " overriding the runtime")))
          ;; The browser restores on its own schedule. Read again two frames
          ;; on: this adds no commit, it only gives a late restore room to land.
          (js/Promise.
            (fn [resolve]
              (js/requestAnimationFrame
                (fn [] (js/requestAnimationFrame
                         (fn []
                           (is (= 0 (witness/scroll-y))
                               (str "two frames later the page reads " (witness/scroll-y)
                                    " — the browser's restore landed after the runtime's"))
                           (resolve nil))))))))
        done))))
