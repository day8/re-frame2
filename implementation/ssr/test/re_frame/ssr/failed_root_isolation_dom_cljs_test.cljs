(ns re-frame.ssr.failed-root-isolation-dom-cljs-test
  "Failed-root isolation against a REAL DOM (S5-C) — the browser half of
  `re-frame.ssr.failed-root-isolation-cljs-test`.

  The host-neutral suite proves the boundary from explicitly-supplied
  manifests. It cannot prove the half that only exists in a browser: a
  genuine N-root PAGE, where each root finds its manifest by ADJACENCY
  and a broken root is broken because its *markup* is broken — a missing
  manifest script, a manifest from a different server response. That is
  what a real failed root looks like, and it is the shape this file
  builds.

  It also proves the part app-db equality cannot: that a surviving root's
  DOM is still there and still WIRED — its container holds the server
  markup, and a dispatch through the frame it hydrated moves the state
  its view reads.

  The `-dom-cljs-test$` suffix opts this file into the `:browser-test`
  build. `:node-test` loads it too (it matches `cljs-test$`), where
  `js/document` is absent, so every DOM-dependent test gates on
  `(browser?)` and exits early. A node-only run of this namespace is
  therefore VACUOUS by construction — `npm run test:browser` is where it
  actually asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

(use-fixtures :once (fn [f] (rf/init! rf.ssr/adapter) (f)))

;; The process-global `defonce` install ledger — reset or it leaks into
;; every sibling test in the shared runner process.
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
  (let [fid (keyword "rf.isolation.dom" (str "f" (swap! frame-counter inc)))]
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

(defn- reg-bump! []
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)})))

(defn- render-page!
  "Build a real multi-root page. Each entry is `[root-id manifest?]`; a
  root whose `manifest?` is false gets a container with NO adjacent
  manifest script — the broken-markup shape a hydrating root must fail
  on, since it takes its identity FROM the manifest.

  Manifest markup comes from the shipped wire fn (`manifest/script-html`),
  so discovery is exercised against the bytes the server actually emits."
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

(defn- specs-for
  "One root spec per entry, each pointed at its own frame and its own
  container on the page."
  [page entries frames]
  (mapv (fn [[rid _] fid]
          {:frame fid :container (container page rid)
           :payload (payload-for {:count 7})})
        entries frames))

(defn- hydrated? [fid] (= {:count 7} (rf/app-db-value fid)))

(defn- interactive? [fid]
  (let [before (:count (rf/app-db-value fid))]
    (rf/dispatch-sync [::bump] {:frame fid})
    (= (inc before) (:count (rf/app-db-value fid)))))

;; ---------------------------------------------------------------------------
;; A root with broken markup fails alone — at every position
;; ---------------------------------------------------------------------------

(deftest a-root-whose-manifest-script-is-missing-fails-alone
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "the real failed-root shape: one region of the page shipped
              without its manifest script. That root cannot hydrate (it
              has no identity to hydrate AS), and every other root on the
              page must still hydrate AND still run."
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
                     (:rf.error/id (ex-data (:error (nth outcomes broken-idx)))))
                  "and failed for the right reason")
              (doseq [i (range 3) :when (not= i broken-idx)]
                (is (= :hydrated (:status (nth outcomes i))))
                (is (hydrated? (nth frames i))
                    (str "sibling root " i " hydrated"))
                (is (interactive? (nth frames i))
                    (str "sibling root " i " is RUNNING — a dispatch reaches it"))
                (is (some? (container page (nth ids i)))
                    (str "sibling root " i "'s container is still in the "
                         "document — a failed root does not blank the page"))))
            (finally (.remove page))))))))

(deftest a-root-from-another-response-fails-alone
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "a page composed from fragments rendered by two different
              server responses: one root's payload disagrees with the
              payload already installed for its frame"
      (reg-bump!)
      (let [ids     [:page/shop :page/cart :page/nav]
            entries (mapv (fn [rid] [rid true]) ids)
            page    (render-page! entries)
            frames  (vec (repeatedly 3 fresh-frame!))
            specs   (-> (specs-for page entries frames)
                        ;; The middle root carries a stale fragment's payload.
                        (assoc-in [1 :payload] (payload-for {:count 99})))]
        (try
          ;; The middle root's frame was already installed from THIS response.
          (rf.ssr.install/payload-install-decision!
           'test (nth frames 1)
           (rf.ssr.install/payload-content-digest (payload-for {:count 7}))
           :page/first-response)
          (let [outcomes (rf.ssr/hydrate-page! specs)]
            (is (= :rf.error/frame-payload-conflict
                   (:rf.error/id (ex-data (:error (nth outcomes 1)))))
                "the stale fragment's root failed loud")
            (doseq [i [0 2]]
              (is (hydrated? (nth frames i)) (str "root " i " hydrated"))
              (is (interactive? (nth frames i)) (str "root " i " is running"))))
          (finally (.remove page)))))))

(deftest a-root-whose-mount-throws-fails-alone-on-a-real-page
  (if-not (browser?)
    (is true "skipped under node — no js/document")
    (testing "the host's own mount is inside the boundary, so a root that
              hydrated but could not mount is contained too — and the
              roots that DID mount are still in the document"
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
                     (.-textContent (container page (nth ids i))))
                  (str "root " i "'s DOM was actually updated by its mount"))
              (is (interactive? (nth frames i))
                  (str "root " i " is running")))
            (is (= "server markup"
                   (.-textContent (container page (nth ids 1))))
                "the failed root's container still holds the server markup —
                 the page is degraded, not blanked"))
          (finally (.remove page)))))))
