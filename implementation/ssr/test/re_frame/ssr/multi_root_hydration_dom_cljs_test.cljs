(ns re-frame.ssr.multi-root-hydration-dom-cljs-test
  "Multi-root hydration against a REAL DOM (S5-B) — the browser half of
  `re-frame.ssr.multi-root-hydration-cljs-test`.

  The host-neutral suite proves the install ledger's idempotence from an
  explicitly-supplied payload. It cannot prove the half that only exists
  in a browser: **preflight step 1**, where a hydrating root finds its
  Root Manifest by ADJACENCY (the container's immediately following
  element sibling) rather than being handed one.

  These tests build a genuine N-roots-on-one-page document — several
  containers, each followed by its own manifest script, emitted by the
  SHIPPED wire fn (`manifest/script-html`) rather than hand-written
  markup — and hydrate roots out of it.

  The `-dom-cljs-test$` suffix opts this file into the `:browser-test`
  build. `:node-test` loads it too (it matches `cljs-test$`), where
  `js/document` is absent, so every DOM-dependent test gates on
  `(browser?)` and exits early. A node-only run of this namespace is
  therefore VACUOUS by construction — `npm run test:browser` is where it
  actually asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

(use-fixtures :once (fn [f] (rf/init! rf.ssr/adapter) (f)))

(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client`-platform frame under an id no other test in this shared
  process has used.

  `make-frame` opts are FLAT — `:platform` sits alongside `:id`. A nested
  `{:config {:platform :client}}` stores `:config {:config {…}}` and the
  frame is never platform-tagged at all; every test below would still pass,
  because the runtime resolves the platform as
  `(or (-> rec :config :platform) (interop/active-platform))` and the
  host-wide marker is already `:client` on CLJS.
  `the-fixture-frames-are-actually-platform-tagged` is what keeps that
  accident from coming back."
  []
  (let [fid (keyword "rf.multiroot.dom" (str "f" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(deftest the-fixture-frames-are-actually-platform-tagged
  (testing "`fresh-frame!` asks for `:platform :client` and the frame CARRIES
            it. Read through `frame-meta`, the canonical `:rf/frame-meta`
            shape, which flattens the frame's OWN config and does not fall
            back to the host-wide platform marker — so this discriminates a
            tagged frame from an untagged one, where the DOM tests below
            cannot: they would pass either way on CLJS.

            Deliberately NOT gated on `(browser?)`. Building a frame and
            reading its tag needs no `js/document`, so this one assertion
            runs under `:node-test` as well as in the browser — the only
            test in this namespace that is not vacuous under node."
    (is (= :client (:platform (rf/frame-meta (fresh-frame!)))))))

(defn- payload-for [db]
  (rf.ssr.payload-policy/build-payload nil db "server-hash-1" {}))

(defn- manifest-for [root-id]
  {:rf.root/schema-version rf.ssr.manifest/schema-version
   :root-id                root-id
   :view-id                :app/root
   :element-locator        {:id (name root-id)}
   :phase                  :server})

(defn- render-page!
  "Build a real multi-root page: for each root-id, a container followed
  IMMEDIATELY by its own manifest script. The manifest markup comes from
  the shipped `manifest/script-html`, so discovery is tested against the
  bytes the server actually emits.

  Returns the page element (already in the document, so `getElementById`
  and sibling walks behave as they do on a live page)."
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

;; ---------------------------------------------------------------------------
;; Preflight step 1 — positional discovery on a real page
;; ---------------------------------------------------------------------------

(deftest each-container-discovers-its-own-adjacent-manifest
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "on a page of N roots, adjacency binds each manifest to ITS
              root; no document scan, no id lookup"
      (let [page (render-page! [:page/shop :page/cart :page/nav])]
        (try
          (doseq [rid [:page/shop :page/cart :page/nav]]
            (let [fid (fresh-frame!)
                  {:keys [root-id manifest]}
                  (rf.ssr.install/preflight! 'test {:payload    (payload-for {:count 7})
                                             :payload-id fid
                                             :container  (container page rid)})]
              (is (= rid root-id)
                  (str "container #" (name rid) " resolved its OWN manifest, "
                       "not a sibling's"))
              (is (= {:id (name rid)} (:element-locator manifest)))))
          (finally (.remove page)))))))

(deftest a-container-with-no-adjacent-manifest-fails-loud
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "a hydrating root takes identity FROM its manifest, so a
              missing one is :rf.error/root-manifest-invalid {:missing
              :manifest} — never a guess"
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
            (is (nil? (rf.ssr.install/installed-payload fid))
                "preflight failed at step 1 — the payload was never claimed"))
          (finally (.remove page)))))))

(deftest the-payload-script-is-not-mistaken-for-a-manifest
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "an ordinary application/edn script adjacent to a container
              lacks the bare data-rf-root marker, so discovery rejects it"
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
                      (catch :default e (:rf.error/id (ex-data e)))))
              "the hydration payload script is not a root manifest")
          (finally (.remove page)))))))

;; ---------------------------------------------------------------------------
;; The whole thing together — N roots, one frame, one page
;; ---------------------------------------------------------------------------

(deftest two-roots-on-one-page-sharing-a-frame-install-the-payload-once
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "the shape the feature exists for: a page of N roots
              referencing M frames, M < N, each root discovering its own
              manifest and hydrating the shared frame"
      (let [page (render-page! [:page/shop :page/cart])
            fid  (fresh-frame!)]
        (try
          (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
          (let [payload (payload-for {:count 7})]
            ;; Root shop boots off the page.
            (rf.ssr.boot/hydrate! {:frame fid :payload payload
                            :container (container page :page/shop)})
            (is (= {:count 7} (rf/app-db-value fid)))
            (is (= :page/shop (:installed-by (rf.ssr.install/installed-payload fid)))
                "the installer was attributed from the DISCOVERED manifest —
                 no :root-id was passed in")

            (rf/dispatch-sync [::bump] {:frame fid})
            (is (= {:count 8} (rf/app-db-value fid)))

            ;; Root cart boots second, off the same page.
            (rf.ssr.boot/hydrate! {:frame fid :payload payload
                            :container (container page :page/cart)})
            (is (= {:count 8} (rf/app-db-value fid))
                "the second root found the payload live and did not re-seed —
                 the client mutation between the two boots survived")
            (is (= :page/shop (:installed-by (rf.ssr.install/installed-payload fid)))
                "root cart did not take over the claim"))
          (finally (.remove page)))))))

(deftest roots-from-two-different-responses-conflict-by-content-digest
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "the failure this catches: a page composed from fragments
              rendered by two different server responses, whose payloads
              for one frame disagree (004C §7)"
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
            (is (= :page/cart (get-in data [:arriving :root-id]))
                "both parties are named from their own discovered manifests"))
          (is (= {:count 7} (rf/app-db-value fid))
              "the root that installed first is untouched by the conflict")
          (finally (.remove page)))))))
