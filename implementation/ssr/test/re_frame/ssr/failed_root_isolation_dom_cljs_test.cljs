(ns re-frame.ssr.failed-root-isolation-dom-cljs-test
  "Failed-root isolation against a REAL DOM — the browser half of
  `re-frame.ssr.failed-root-isolation-cljs-test`: a genuine N-root page whose
  healthy roots ADOPT their server markup through the Reagent adapter's
  `render!` with `{:hydrate? true}`, exactly as `hydrate-page!`'s docstring
  example mounts them, while a root whose mount throws fails alone and keeps
  its server markup.

  `:node-test` loads this file too (it matches `cljs-test$`), where every
  test exits early without `js/document`; `:browser-test` is where it asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

;; Map-form fixtures, because the test is async. Seat the client adapter this
;; ns mounts through, cold-starting the slot: the shared bundle runs suites
;; that seat other adapters, and `init!` with a different one raises
;; `:rf.error/adapter-already-installed`.
(use-fixtures :once
  {:before (fn []
             (rf/destroy-adapter!)
             (rf/init! rf.adapter.reagent/adapter))
   :after  (fn [] (rf/destroy-adapter!))})

;; The install ledger is a process-global `defonce`.
(use-fixtures :each {:before (fn [] (rf.ssr.install/reset-installed-payloads!))})

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client`-platform frame under an id no other test in this process used."
  []
  (let [fid (keyword "rf.isolation.dom" (str "f" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- payload-for [db]
  (rf.ssr.payload-policy/build-payload nil db "server-hash-1" {}))

;; Registered inside the test: in the shared process a sibling's
;; `registrar/clear-all!` wipes ns-load registrations.
(defn- reg-app! []
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (rf/reg-sub ::count (fn [db _] (:count db))))

;; A plain component has no frame context of its own, so it names its frame.
(defn- root-view [fid label]
  [:button {:class    "probe"
            :on-click #(rf/dispatch [::bump] {:frame fid})}
   (str label " " @(rf/subscribe [::count] {:frame fid}))])

(defn- server-markup
  "What a server emits for `root-view` on `{:count 7}`; it byte-matches the
  client render, so hydration adopts it with no mismatch."
  [label]
  (str "<button class=\"probe\">" label " 7</button>"))

(defn- render-page!
  "A real multi-root page: one server-rendered container per root."
  [labels]
  (let [page (.createElement js/document "div")]
    (set! (.-innerHTML page)
          (apply str (for [label labels]
                       (str "<div id=\"" label "\">" (server-markup label) "</div>"))))
    (.appendChild (.-body js/document) page)
    page))

(defn- button [page label]
  (.querySelector page (str "#" label " button.probe")))

;; Other browser suites leave `IS_REACT_ACT_ENVIRONMENT` on, which defers
;; renders outside `act`; hydration commits on React's own schedule.
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(deftest a-root-whose-mount-throws-fails-alone-on-a-real-page
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "a root whose mount throws is contained; the healthy roots adopt
              their server DOM through `render!` and are wired, and the failed
              root keeps its server markup"
      (async done
        (reg-app!)
        (let [act-prev (disable-act-env!)
              labels   ["shop" "cart" "nav"]
              page     (render-page! labels)
              frames   (vec (repeatedly 3 fresh-frame!))
              handles  (vec (repeatedly 3 rf.adapter.reagent/client-root))
              servers  (mapv #(button page %) labels)
              specs    (mapv (fn [i label fid]
                               {:frame    fid
                                :root-id  (keyword "page" label)
                                :payload  (payload-for {:count 7})
                                :mount-fn (if (= i 1)
                                            (fn [] (throw (ex-info "mount blew up" {})))
                                            (fn []
                                              (rf.adapter.reagent/render!
                                                (nth handles i)
                                                [rf/frame-provider {:frame fid}
                                                 [root-view fid label]]
                                                (.querySelector page (str "#" label))
                                                {:hydrate? true})))})
                             (range 3) labels frames)
              finish!  (fn []
                         (doseq [h handles]
                           (try (rf.adapter.reagent/unmount! h) (catch :default _ nil)))
                         (.remove page)
                         (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) act-prev)
                         (done))]
          (is (every? some? servers) "control: every server button is in the document")
          (let [outcomes (rf.ssr/hydrate-page! specs)]
            (is (= [:hydrated :failed :hydrated] (mapv :status outcomes))))
          ;; Hydration commits asynchronously, so adoption is read after a yield.
          (js/setTimeout
            (fn []
              (doseq [i [0 2]]
                (is (identical? (nth servers i) (button page (nth labels i)))
                    (str "root " i " adopted the server node; html="
                         (pr-str (.-innerHTML page)))))
              (is (= "cart 7" (.-textContent (button page "cart")))
                  "the failed root keeps its server markup: degraded, not blanked")
              (.click (nth servers 0))
              ;; `dispatch` is async and the re-render commits on React's schedule.
              (js/setTimeout
                (fn []
                  (is (= {:count 8} (rf/app-db-value (nth frames 0)))
                      "clicking the adopted node ran ::bump")
                  (is (= "shop 8" (.-textContent (nth servers 0)))
                      "the re-render updated the adopted node in place")
                  (finish!))
                50))
            50))))))
