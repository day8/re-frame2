(ns re-frame.ssr.multi-root-hydration-dom-cljs-test
  "Multi-root hydration against a REAL DOM — the browser half of
  `re-frame.ssr.multi-root-hydration-cljs-test`: preflight step 1, where a
  hydrating root finds its Root Manifest by ADJACENCY (the container's next
  element sibling), on a page whose manifests come from the shipped wire fn.

  `:node-test` loads this file too (it matches `cljs-test$`), where every
  test exits early without `js/document`; `:browser-test` is where it asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
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

(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client`-platform frame under an id no other test in this process used."
  []
  (let [fid (keyword "rf.multiroot.dom" (str "f" (swap! frame-counter inc)))]
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

(defn- render-page!
  "A real multi-root page, attached to the document: each container followed
  immediately by its own manifest script, from the shipped `manifest/script-html`."
  [root-ids]
  (let [page (.createElement js/document "div")]
    (set! (.-innerHTML page)
          (apply str
                 (for [rid root-ids]
                   (str "<div id=\"" (name rid) "\">server markup</div>"
                        (rf.ssr.manifest/script-html (manifest-for rid))))))
    (.appendChild (.-body js/document) page)
    page))

(defn- container [page root-id]
  (.querySelector page (str "#" (name root-id))))

(deftest each-container-discovers-its-own-adjacent-manifest
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (let [page (render-page! [:page/shop :page/cart :page/nav])]
      (try
        (doseq [rid [:page/shop :page/cart :page/nav]]
          (let [{:keys [root-id manifest]}
                (rf.ssr.install/preflight! 'test {:payload    (payload-for {:count 7})
                                                  :payload-id (fresh-frame!)
                                                  :container  (container page rid)})]
            (is (= rid root-id) (str "#" (name rid) " resolved its OWN manifest"))
            (is (= {:id (name rid)} (:element-locator manifest)))))
        (finally (.remove page))))))

(deftest a-container-with-no-adjacent-manifest-fails-loud
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "a hydrating root takes identity FROM its manifest, so a missing
              one fails loud and claims nothing"
      (let [page (.createElement js/document "div")]
        (set! (.-innerHTML page) "<div id=\"lonely\">server markup</div>")
        (.appendChild (.-body js/document) page)
        (try
          (let [fid  (fresh-frame!)
                data (try (rf.ssr.install/preflight!
                           'test {:payload    (payload-for {:count 7})
                                  :payload-id fid
                                  :container  (.querySelector page "#lonely")})
                          nil
                          (catch :default e (ex-data e)))]
            (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
            (is (= :manifest (:missing data)))
            (is (nil? (rf.ssr.install/installed-payload fid))))
          (finally (.remove page)))))))

(deftest the-payload-script-is-not-mistaken-for-a-manifest
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "an adjacent application/edn script without the bare data-rf-root
              marker is not a manifest"
      (let [page (.createElement js/document "div")]
        (set! (.-innerHTML page)
              (str "<div id=\"shop\">server markup</div>"
                   "<script type=\"application/edn\" id=\"__rf_payload\">"
                   "{:rf/app-db {:count 7}}</script>"))
        (.appendChild (.-body js/document) page)
        (try
          (is (= :rf.error/root-manifest-invalid
                 (try (rf.ssr.install/preflight!
                       'test {:payload    (payload-for {:count 7})
                              :payload-id (fresh-frame!)
                              :container  (.querySelector page "#shop")})
                      nil
                      (catch :default e (:rf.error/id (ex-data e))))))
          (finally (.remove page)))))))

(deftest two-roots-on-one-page-sharing-a-frame-install-the-payload-once
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "N roots referencing one frame: each discovers its own manifest,
              the first installs the payload, the second does not re-seed"
      (let [page (render-page! [:page/shop :page/cart])
            fid  (fresh-frame!)]
        (try
          (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
          (let [payload (payload-for {:count 7})]
            (rf.ssr.boot/hydrate! {:frame fid :payload payload
                                   :container (container page :page/shop)})
            (is (= {:count 7} (rf/app-db-value fid)))
            (rf/dispatch-sync [::bump] {:frame fid})
            (rf.ssr.boot/hydrate! {:frame fid :payload payload
                                   :container (container page :page/cart)})
            (is (= {:count 8} (rf/app-db-value fid))
                "the client mutation between the two boots survived")
            (is (= :page/shop (:installed-by (rf.ssr.install/installed-payload fid)))
                "the claim is attributed from the DISCOVERED manifest, and kept"))
          (finally (.remove page)))))))

(deftest roots-from-two-different-responses-conflict-by-content-digest
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "fragments from two server responses whose payloads for one frame
              disagree conflict, naming both parties from their manifests"
      (let [page (render-page! [:page/shop :page/cart])
            fid  (fresh-frame!)]
        (try
          (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                                 :container (container page :page/shop)})
          (let [data (try (rf.ssr.boot/hydrate! {:frame fid
                                                 :payload (payload-for {:count 99})
                                                 :container (container page :page/cart)})
                          nil
                          (catch :default e (ex-data e)))]
            (is (= :rf.error/frame-payload-conflict (:rf.error/id data)))
            (is (= :page/shop (get-in data [:installed :installed-by])))
            (is (= :page/cart (get-in data [:arriving :root-id]))))
          (is (= {:count 7} (rf/app-db-value fid))
              "the root that installed first is untouched by the conflict")
          (finally (.remove page)))))))
