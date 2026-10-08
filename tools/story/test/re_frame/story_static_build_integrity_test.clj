(ns re-frame.story-static-build-integrity-test
  "JVM tests pinning the static-build link-integrity contract
  (`tools/story/spec/015-Test-Coverage.md` §Static-build scenarios) over a
  seeded registry: every listed id resolves through the read surface, every
  share URL the build encodes decodes to a registered id, and ids stamped on
  data-test attributes round-trip through `pr-str` / `read-string`.
  `story_static_build_cljs_test.cljs` and `check-story-static.cjs` cover the
  built bundle's shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as rf.frame]
            [re-frame.registrar       :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story           :as rf.story]
            [re-frame.story.share     :as rf.story.share]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-all [t]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-all)

;; ---- helpers -------------------------------------------------------------

(defn- seed-bundle-like-registry!
  "Seed a registry shaped like the counter_with_stories static-export
  payload: several variants per parent story, modes, and a workspace."
  []
  (rf.story/reg-story :story.bundle.counter {:doc "counter story" :tags #{:dev}})
  (rf.story/reg-variant :story.bundle.counter/empty  {:setup []      :tags #{:dev}})
  (rf.story/reg-variant :story.bundle.counter/loaded {:setup []      :tags #{:dev}})
  (rf.story/reg-variant :story.bundle.counter/three  {:setup []      :tags #{:test}})
  (rf.story/reg-story :story.bundle.login {:doc "login story" :tags #{:dev}})
  (rf.story/reg-variant :story.bundle.login/empty       {:setup []})
  (rf.story/reg-variant :story.bundle.login/submitting  {:setup []})
  (rf.story/reg-variant :story.bundle.login/authenticated {:setup []})
  (rf.story/reg-mode :Mode.bundle.theme/dark  {:axis :theme :args {:theme :dark}})
  (rf.story/reg-mode :Mode.bundle.theme/light {:axis :theme :args {:theme :light}})
  (rf.story/reg-mode :Mode.bundle.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
  (rf.story/reg-workspace :Workspace.bundle/grid
    {:layout   :variants-grid
     :variants [:story.bundle.counter/empty
                :story.bundle.counter/loaded
                :story.bundle.counter/three]}))

(defn- url-param
  "The URL-decoded value of query param `k` in `url`, as URLSearchParams.get reads it."
  [url k]
  (some-> (re-find (re-pattern (str k "=([^&]+)")) url)
          second
          (java.net.URLDecoder/decode "UTF-8")))

;; After mount the shell walks the read surface to render the sidebar,
;; toolbar and workspace cells: every id it lists must resolve to a body, or
;; a row renders and clicking it shows nothing.

(deftest every-registered-variant-resolves-to-body
  (testing "every (ids :variant) entry resolves through handler-meta — the
            sidebar-to-canvas navigation contract"
    (seed-bundle-like-registry!)
    (let [vids (rf.story/ids :variant)]
      (is (seq vids) "fixture sanity")
      (is (= [] (remove #(rf.story/handler-meta :variant %) vids))))))

(deftest every-registered-story-resolves-to-body
  (testing "every (ids :story) entry resolves — variants fall back to their
            parent's :component, so an unresolvable parent blanks every child"
    (seed-bundle-like-registry!)
    (let [sids (rf.story/ids :story)]
      (is (seq sids))
      (is (= [] (remove #(rf.story/handler-meta :story %) sids))))))

(deftest every-registered-mode-resolves-to-body
  (testing "every listed mode resolves — the toolbar reads them at every render"
    (seed-bundle-like-registry!)
    (let [mids (rf.story/list-modes)]
      (is (seq mids))
      (is (= [] (remove #(rf.story/handler-meta :mode %) mids))))))

(deftest workspace-variant-refs-all-resolve
  (testing "every variant id a workspace's :variants names resolves, or the
            cell renders with no canvas content"
    (seed-bundle-like-registry!)
    (doseq [wid  (rf.story/ids :workspace)
            vid  (:variants (rf.story/handler-meta :workspace wid))]
      (is (rf.story/registered? :variant vid)
          (str "workspace " (pr-str wid) " references unresolvable " (pr-str vid))))))

(deftest read-surface-mirrors-id-set-and-body-map
  (testing "the MCP `list-variants` path and the renderer's registrations walk
            agree on every id set"
    (seed-bundle-like-registry!)
    (doseq [[kind listed] [[:variant (rf.story/ids :variant)]
                           [:story (rf.story/ids :story)]
                           [:mode (rf.story/list-modes)]]]
      (is (= listed (set (keys (rf.story/registrations kind)))) (str kind)))))

;; Share URLs the static export embeds encode `?variant=<id>` (and optionally
;; modes) that the receiving shell parses back into a registry lookup: each
;; must decode to the same keyword, and that keyword must resolve.

(deftest variant-share-url-encodes-id
  (testing "spec/013 + spec/014 §Share: each variant's share URL carries its id
            as a variant= query parameter"
    (seed-bundle-like-registry!)
    (doseq [vid (rf.story/ids :variant)]
      (is (str/includes? (rf.story/variant-share-url vid) "variant=") (pr-str vid)))))

(deftest share-url-variant-id-round-trips-to-registry
  (testing "every registered variant id round-trips through its share URL to
            the same, registered keyword — no escaping mismatch leaves a link
            pointing at an unresolvable id"
    (seed-bundle-like-registry!)
    (doseq [vid (rf.story/ids :variant)
            :let [decoded (some-> (url-param (rf.story/variant-share-url vid) "variant")
                                  rf.story.share/parse-keyword-token)]]
      (is (= vid decoded))
      (is (rf.story/registered? :variant decoded) (pr-str decoded)))))

(deftest share-url-with-modes-round-trips-active-modes
  (testing "encoded active modes decode back to the same registered modes, or
            the toolbar drops them at hydrate and the share loses fidelity"
    (seed-bundle-like-registry!)
    (let [active-modes [:Mode.bundle.theme/dark :Mode.bundle.vp/mobile]
          decoded      (some->> (url-param (rf.story/variant-share-url
                                             :story.bundle.counter/empty "" {:active-modes active-modes})
                                           "modes")
                                (#(str/split % #","))
                                (mapv rf.story.share/parse-keyword-token))]
      (is (= (set active-modes) (set decoded)))
      (is (every? #(rf.story/registered? :mode %) decoded)))))

(deftest share-url-rejects-unregistered-variant-as-broken-link
  (testing "a share URL naming a variant renamed between build and view still
            decodes, but the registry lookup fails, so the shell engages the
            spec/014 §Share no-such-variant empty state; encoding only
            registered ids is the build's job"
    (seed-bundle-like-registry!)
    (let [stale-id :story.bundle.counter/this-was-renamed
          decoded  (rf.story.share/parse-keyword-token
                     (url-param (rf.story/variant-share-url stale-id) "variant"))]
      (is (= [stale-id false] [decoded (boolean (rf.story/registered? :variant decoded))])))))

;; The shell stamps `data-test-variant` with `(pr-str variant-id)` and toolbar
;; chips stamp `data-toolbar-mode` likewise; selectors rebuild the keyword with
;; `read-string`, so ids must round-trip.

(deftest variant-id-pr-str-round-trip
  (seed-bundle-like-registry!)
  (doseq [vid (rf.story/ids :variant)]
    (is (= vid (read-string (pr-str vid))))))

(deftest mode-id-pr-str-round-trip
  (seed-bundle-like-registry!)
  (doseq [mid (rf.story/list-modes)]
    (is (= mid (read-string (pr-str mid))))))
