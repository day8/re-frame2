(ns re-frame.ssr.failed-root-isolation-dom-cljs-test
  "Failed-root isolation against a REAL DOM — the browser half of
  `re-frame.ssr.failed-root-isolation-cljs-test`: a genuine N-root page where
  each root finds its manifest by ADJACENCY, a broken root is broken because
  its markup is, and a surviving root's DOM stays in place and WIRED.

  `:node-test` loads this file too (it matches `cljs-test$`), where every
  test exits early without `js/document`; `:browser-test` is where it asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

;; Seat the adapter this ns names, cold-starting the slot: the shared bundle
;; runs suites that seat other adapters, and `init!` with a different one
;; raises `:rf.error/adapter-already-installed`.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

;; The install ledger is a process-global `defonce`.
(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

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

(defn- manifest-for [root-id]
  {:rf.root/schema-version rf.ssr.manifest/schema-version
   :root-id                root-id
   :view-id                :app/root
   :element-locator        {:id (name root-id)}
   :phase                  :server})

(defn- reg-bump! []
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)})))

(defn- render-page!
  "A real multi-root page from `[root-id manifest?]` entries; a root whose
  `manifest?` is false has no adjacent manifest script. Manifest markup comes
  from the shipped `manifest/script-html`."
  [entries]
  (let [page (.createElement js/document "div")]
    (set! (.-innerHTML page)
          (apply str
                 (for [[rid manifest?] entries]
                   (str "<div id=\"" (name rid) "\">server markup</div>"
                        (when manifest?
                          (rf.ssr.manifest/script-html (manifest-for rid)))))))
    (.appendChild (.-body js/document) page)
    page))

(defn- container [page root-id]
  (.querySelector page (str "#" (name root-id))))

(defn- specs-for [page entries frames]
  (mapv (fn [[rid _] fid]
          {:frame fid :container (container page rid)
           :payload (payload-for {:count 7})})
        entries frames))

(defn- hydrated? [fid] (= {:count 7} (rf/app-db-value fid)))

(defn- interactive? [fid]
  (let [before (:count (rf/app-db-value fid))]
    (rf/dispatch-sync [::bump] {:frame fid})
    (= (inc before) (:count (rf/app-db-value fid)))))

(deftest a-root-whose-manifest-script-is-missing-fails-alone
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "a root shipped without its manifest script fails; every other
              root on the page still hydrates and runs — at every position"
      (doseq [broken-idx (range 3)]
        (reg-bump!)
        (rf.ssr.install/reset-installed-payloads!)
        (let [ids     [:page/shop :page/cart :page/nav]
              entries (map-indexed (fn [i rid] [rid (not= i broken-idx)]) ids)
              page    (render-page! entries)
              frames  (vec (repeatedly 3 fresh-frame!))]
          (try
            (let [outcomes (rf.ssr/hydrate-page! (specs-for page entries frames))]
              (is (= :failed (:status (nth outcomes broken-idx)))
                  (str "the manifest-less root at " broken-idx " failed"))
              (is (= :rf.error/root-manifest-invalid
                     (:rf.error/id (ex-data (:error (nth outcomes broken-idx))))))
              (doseq [i (range 3) :when (not= i broken-idx)]
                (is (= :hydrated (:status (nth outcomes i))))
                (is (hydrated? (nth frames i)) (str "sibling root " i " hydrated"))
                (is (interactive? (nth frames i)) (str "sibling root " i " is running"))
                (is (some? (container page (nth ids i)))
                    (str "sibling root " i "'s container is still in the document"))))
            (finally (.remove page))))))))

(deftest a-root-whose-mount-throws-fails-alone-on-a-real-page
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "a root whose mount throws is contained; the roots that mounted
              updated their DOM, and the failed root keeps the server markup"
      (reg-bump!)
      (let [ids     [:page/shop :page/cart :page/nav]
            entries (mapv (fn [rid] [rid true]) ids)
            page    (render-page! entries)
            frames  (vec (repeatedly 3 fresh-frame!))
            mounted (atom #{})
            specs   (map-indexed
                     (fn [i spec]
                       (assoc spec :mount-fn
                              (if (= i 1)
                                (fn [] (throw (ex-info "mount blew up" {})))
                                (fn []
                                  (set! (.-textContent
                                         (container page (nth ids i)))
                                        "client mounted")
                                  (swap! mounted conj i)))))
                     (specs-for page entries frames))]
        (try
          (let [outcomes (rf.ssr/hydrate-page! specs)]
            (is (= :failed (:status (nth outcomes 1))))
            (is (= #{0 2} @mounted) "both surviving roots mounted")
            (doseq [i [0 2]]
              (is (= "client mounted"
                     (.-textContent (container page (nth ids i)))))
              (is (interactive? (nth frames i)) (str "root " i " is running")))
            (is (= "server markup"
                   (.-textContent (container page (nth ids 1))))
                "the page is degraded, not blanked"))
          (finally (.remove page)))))))
