(ns re-frame.routing-scroll-after-commit-reagent-dom-cljs-test
  "WHERE THE PAGE LANDS, measured in a real browser under
  the Reagent adapter's ordinary mount path.

  `:rf.nav/scroll` runs inside the navigating event, before any render; if
  its DOM half ran there it would read and move the page being LEFT. The
  pages here are UNEQUAL in height on purpose. The other witnesses
  (`re-frame.routing-conduct-dom-cljs-test`, the Fresco navigation conduct
  test) give every pane the same filler so a saved offset is never clamped,
  which would hide a scroll made BEFORE the new pane commits.

  Two rows:

    1. A cross-route `:fragment` whose element exists only on the arriving
       page lands on it.
    2. Back to a page TALLER than the one being left restores the full
       offset, with `history.scrollRestoration` set to \"manual\" so the
       browser's own restore cannot supply it.

  Every reading is taken inside an after-render callback queued behind the
  navigation — the same queue the scroll rides — so nothing flushes a render
  on the implementation's behalf.

  ns ends in `-dom-cljs-test`; under `:node-test` every row is a stated skip."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.test-support :as rf.test-support]))

(def ^:private install-id
  "The id the fragment names. Only the docs page renders it."
  "rf2-scroll-witness-install")

(def ^:private list-url "/rf2-scroll-witness/list")
(def ^:private detail-url "/rf2-scroll-witness/detail")

(def ^:private deep-offset 3000)

(defn- register-routes!
  "Called from the fixture: the reset fixture restores the registrar to its
  baseline, so a route registered at load would not survive to the first row."
  []
  (rf.routing/reg-route ::home {:doc "A SHORT page with no #install."}
                        "/rf2-scroll-witness/home")
  (rf.routing/reg-route ::docs {:doc "A TALL page; #install is 3000px down."}
                        "/rf2-scroll-witness/docs")
  (rf.routing/reg-route ::list {:doc "A TALL list."} list-url)
  (rf.routing/reg-route ::detail {:doc "A SHORT detail page."} detail-url)
  nil)

(rf/reg-view* ::page
  (fn page []
    (if (= ::docs @(rf/subscribe [:rf.route/id]))
      [:div
       [:div {:style {:height "3000px"}}]
       [:h2 {:id install-id} "Install"]
       [:div {:style {:height "3000px"}}]]
      [:div {:style {:height "100px"}} "home"])))

(rf/reg-view* ::back-page
  (fn back-page []
    (if (= ::list @(rf/subscribe [:rf.route/id]))
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
                      (register-routes!))}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a scroll witness needs a real browser — " why)))

(defn- mount! [view container frame-id]
  (let [root (rdc/create-root container)]
    ;; The INITIAL mount commits synchronously so there is a baseline page;
    ;; nothing after it forces a commit.
    (react-dom/flushSync
      (fn [] (rdc/render root [rf/frame-provider {:frame frame-id} [(rf/view view)]])))
    root))

(defn- after-commit
  "A promise of `(read)`, taken inside an after-render callback queued right
  after `(act!)` — behind whatever `act!` queued, and supplying no commit of
  its own."
  [act! read]
  (js/Promise.
    (fn [resolve]
      (act!)
      (rf.interop/after-render (fn [] (resolve (read)))))))

(defn- scroll-y [] (js/Math.round (.-scrollY js/window)))

(defn- max-scroll-y
  "How far this document can scroll. A restore past it is clamped."
  []
  (- (.-scrollHeight js/document.documentElement) (.-innerHeight js/window)))

(defn- isolate!
  "Hide every other child of `<body>` for the row's duration and answer the
  thunk that puts them back. The bundle shares one page, and what earlier
  namespaces leave behind makes it tens of thousands of pixels tall — tall
  enough that no \"short\" page is short, so a restore made on the page being
  left is never clamped and the Back row could not fail."
  [container]
  (let [saved (->> (js/Array.from (.-children js/document.body))
                   (remove #(identical? % container))
                   (mapv (fn [el] [el (.. el -style -display)])))]
    (doseq [[el _] saved] (set! (.. el -style -display) "none"))
    (fn restore! [] (doseq [[el d] saved] (set! (.. el -style -display) d)))))

(deftest a-cross-route-fragment-lands-on-the-arriving-section
  (if-not (browser?)
    (skip! ":node-test has no layout or scroll model")
    (async done
      (let [frame-id  ::fragment-frame
            container (.createElement js/document "div")
            _         (.appendChild js/document.body container)
            restore!  (isolate! container)]
        (rf/make-frame {:id frame-id})
        ;; The starting page, with no scroll of its own — so the fragment
        ;; navigation's scroll is the first after-render use of this mount.
        (rf/dispatch-sync [:rf.route/navigate {:to ::home :scroll false}] {:frame frame-id})
        (let [root (mount! ::page container frame-id)]
          (-> (after-commit
                #(rf/dispatch-sync [:rf.route/navigate {:to ::docs :fragment install-id}]
                                   {:frame frame-id})
                (fn []
                  (let [el (.getElementById js/document install-id)]
                    {:y   (scroll-y)
                     :top (when el (js/Math.round (.-top (.getBoundingClientRect el))))})))
              (.then
                (fn [{:keys [y top]}]
                  (is (and (some? top) (<= (js/Math.abs top) 2))
                      (str "#" install-id " sits at the top of the viewport — its top reads "
                           top "px and the page is at " y ". Reading 3000 or so at 0 means"
                           " the scroll looked the id up on the page being LEFT and fell"
                           " back to the top"))
                  (is (pos? y) "the page moved down to the section")))
              (.catch (fn [e] (is false (str "the row never settled: " e))))
              (.then (fn [_]
                       (try (.unmount root) (catch :default _ nil))
                       (try (.remove container) (catch :default _ nil))
                       (restore!)
                       (try (.scrollTo js/window 0 0) (catch :default _ nil))
                       (try (rf/destroy-frame! frame-id) (catch :default _ nil))
                       (done)))))))))

(deftest back-to-a-taller-page-restores-the-whole-offset
  (if-not (browser?)
    (skip! ":node-test has no history or scroll model")
    (async done
      (let [frame-id   ::back-frame
            runner-url (.-href js/location)
            prior      (.-scrollRestoration js/window.history)
            container  (.createElement js/document "div")
            _          (.appendChild js/document.body container)
            restore!   (isolate! container)]
        (set! (.-scrollRestoration js/window.history) "manual")
        (.replaceState js/window.history nil "" list-url)
        (rf/make-frame {:id frame-id :url-bound? true})
        (let [root (mount! ::back-page container frame-id)]
          (-> (after-commit (fn []) (fn [] nil))
              (.then
                (fn [_]
                  (.scrollTo js/window 0 deep-offset)
                  (is (= deep-offset (scroll-y))
                      (str "precondition: the tall list scrolls to " deep-offset
                           " — it reads " (scroll-y)))
                  (after-commit
                    #(rf/dispatch-sync [:rf.route/navigate {:to ::detail}] {:frame frame-id})
                    (fn [] [(scroll-y) (max-scroll-y)]))))
              (.then
                (fn [[y max-y]]
                  (is (= 0 y) "forward to the detail page lands at the top")
                  (is (< max-y deep-offset)
                      (str "precondition: the detail page can scroll only " max-y "px, so a"
                           " restore to " deep-offset " made on it would be clamped. Without"
                           " this the Back row cannot fail"))
                  ;; Read inside an after-render callback queued from a popstate
                  ;; listener installed AFTER the frame's own, so the reading runs
                  ;; behind the scroll the frame's listener queued.
                  (js/Promise.
                    (fn [resolve]
                      (let [on-pop (fn on-pop [_]
                                     (.removeEventListener js/window "popstate" on-pop)
                                     (rf.interop/after-render
                                       (fn [] (resolve (scroll-y)))))]
                        (.addEventListener js/window "popstate" on-pop)
                        (.back js/window.history))))))
              (.then
                (fn [y]
                  (is (= deep-offset y)
                      (str "Back restored " y " rather than " deep-offset ". A restore made"
                           " while the SHORT page was still mounted is clamped to its height,"
                           " and the list then renders at the clamped offset"))))
              (.catch (fn [e] (is false (str "the row never settled: " e))))
              (.then (fn [_]
                       (try (.unmount root) (catch :default _ nil))
                       (try (.remove container) (catch :default _ nil))
                       (restore!)
                       (.replaceState js/window.history nil "" runner-url)
                       (set! (.-scrollRestoration js/window.history) prior)
                       (try (.scrollTo js/window 0 0) (catch :default _ nil))
                       (try (rf/destroy-frame! frame-id) (catch :default _ nil))
                       (done)))))))))
