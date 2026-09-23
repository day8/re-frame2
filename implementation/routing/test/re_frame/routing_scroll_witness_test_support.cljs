(ns re-frame.routing-scroll-witness-test-support
  "rf2-3x7nj.12.3 — the scenario shared by the per-adapter
  `routing-scroll-after-commit-*-dom-cljs-test` witnesses.

  A cross-route navigation carries `:fragment`, and the element it names
  exists ONLY on the page being navigated to. `:rf.nav/scroll` runs inside
  the navigating event, before any render; if its DOM half ran there it would
  look the id up on the page being LEFT, find nothing, and land at the top.

  Each reading is taken INSIDE an after-render callback queued right behind
  the navigation — the same queue the scroll rides, so the reading follows
  the scroll without an act, a manual flush or an extra awaited frame
  supplying the commit on the implementation's behalf.

  Ends in `-test-support`, so neither the `:node-test` (`cljs-test$`) nor
  the `:browser-test` (`-dom-cljs-test$`) regex runs it as a test."
  (:require [cljs.test :refer-macros [is]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]))

(def home-route ::home)
(def docs-route ::docs)

(def install-id
  "The id the fragment names. Only the docs page renders it."
  "rf2-scroll-witness-install")

(defn register-routes!
  "Called from each witness's fixture: the reset fixture restores the
  registrar to its baseline, so a route registered at load would not survive
  to the first row."
  []
  (rf.routing/reg-route home-route {:doc "A SHORT page with no #install."}
                        "/rf2-scroll-witness/home")
  (rf.routing/reg-route docs-route {:doc "A TALL page; #install is 3000px down."}
                        "/rf2-scroll-witness/docs")
  nil)

(rf/reg-view* ::page
  (fn page []
    (if (= docs-route @(rf/subscribe [:rf.route/id]))
      [:div
       [:div {:style {:height "3000px"}}]
       [:h2 {:id install-id} "Install"]
       [:div {:style {:height "3000px"}}]]
      [:div {:style {:height "100px"}} "home"])))

(defn browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn after-commit
  "A promise of `(read)`, taken inside an after-render callback queued right
  after `(act!)` — behind whatever `act!` queued, and supplying no commit of
  its own."
  [act! read]
  (js/Promise.
    (fn [resolve]
      (act!)
      (rf.interop/after-render (fn [] (resolve (read)))))))

(defn scroll-y [] (js/Math.round (.-scrollY js/window)))

(defn- fragment-reading []
  (let [el (.getElementById js/document install-id)]
    {:scroll-y (scroll-y)
     :top      (when el (js/Math.round (.-top (.getBoundingClientRect el))))}))

(defn run-fragment-row!
  "Mount the two-route app through `mount!` (the adapter's ordinary mount
  path), navigate from the short home page to the docs page's `#install`, and
  read where the page landed. `unmount!` takes what `mount!` returned."
  [{:keys [adapter-name mount! unmount!]} done]
  (let [frame-id  ::fragment-frame
        container (.createElement js/document "div")]
    (.appendChild js/document.body container)
    (rf/make-frame {:id frame-id})
    ;; The starting page, with no scroll of its own — so the fragment
    ;; navigation's scroll is the first after-render use of this mount.
    (rf/dispatch-sync [:rf.route/navigate {:to home-route :scroll false}] {:frame frame-id})
    (let [handle (mount! container frame-id)]
      (-> (after-commit
            #(rf/dispatch-sync [:rf.route/navigate {:to docs-route :fragment install-id}]
                               {:frame frame-id})
            fragment-reading)
          (.then
            (fn [{:keys [scroll-y top]}]
              (is (some? top)
                  (str adapter-name ": the arriving page rendered #" install-id))
              (is (and (some? top) (<= (js/Math.abs top) 2))
                  (str adapter-name ": #" install-id " sits at the top of the viewport —"
                       " its top reads " top "px and the page is at " scroll-y
                       ". Reading 3000 or so at 0 means the scroll looked the id up"
                       " on the page being LEFT and fell back to the top"))
              (is (pos? scroll-y)
                  (str adapter-name ": the page moved down to the section"))))
          (.catch (fn [e] (is false (str adapter-name ": the row never settled: " e))))
          (.then (fn [_]
                   (try (unmount! handle) (catch :default _ nil))
                   (try (.remove container) (catch :default _ nil))
                   (try (.scrollTo js/window 0 0) (catch :default _ nil))
                   (try (rf/destroy-frame! frame-id) (catch :default _ nil))
                   (done)))))))
