(ns re-frame.story-static-build-integrity-test
  "JVM tests pinning the static-build link-integrity contract (rf2-5onip).

  Per `tools/story/spec/015-Test-Coverage.md` §Static-build scenarios:
  the existing `story_static_build_cljs_test.cljs` covers the goog-
  define + help-suppression branches; the existing `check-story-static.cjs`
  drives a Playwright smoke against the built bundle. Both are
  post-build-shape checks. What the bead calls out as missing:

  - **all-stories-resolvable** — every registered variant id in a
    seeded registry resolves to a renderable variant body
    (`handler-meta` returns a non-nil body, OR a tagged
    `:rf.error/not-found` projection that the renderer surfaces as an
    explicit not-found state). The contract: a registered id must
    always round-trip through the read surface.

  - **link-integrity (share URLs)** — every share URL the build
    encodes resolves to a registered variant id. Pinning this against
    the share-URL builder + parse-keyword-token round-trip covers the
    integrity contract without needing a live browser.

  - **bundle-relative selectors** — the variant id parser must
    accept ids stamped onto data-test attributes in the built HTML.
    The canonical form `(pr-str variant-id)` round-trips through
    `read-string` to the same keyword. Pinning the round-trip pins
    the deep-link recovery contract for shared URLs that name
    in-bundle variants.

  These are JVM-pure-data tests over the seeded registry — no
  shadow-cljs release build required. They run on the default CI gate
  via `npm run test:cljs` (CLJ arm)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as frame]
            [re-frame.registrar       :as rf-registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story           :as story]
            [re-frame.story.share     :as share]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-all [t]
  (story/clear-all!)
  (rf-registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-all)

;; ---- helpers -------------------------------------------------------------

(defn- seed-bundle-like-registry!
  "Seed a registry resembling the counter_with_stories static-export
  payload — multiple variants per parent story, modes, workspaces.
  Mirrors the shape `re-frame.story.testbeds.counter-with-stories`
  registers at boot."
  []
  (story/reg-story :story.bundle.counter {:doc "counter story" :tags #{:dev}})
  (story/reg-variant :story.bundle.counter/empty  {:events []      :tags #{:dev}})
  (story/reg-variant :story.bundle.counter/loaded {:events []      :tags #{:dev}})
  (story/reg-variant :story.bundle.counter/three  {:events []      :tags #{:test}})
  (story/reg-story :story.bundle.login {:doc "login story" :tags #{:dev}})
  (story/reg-variant :story.bundle.login/empty       {:events []})
  (story/reg-variant :story.bundle.login/submitting  {:events []})
  (story/reg-variant :story.bundle.login/authenticated {:events []})
  (story/reg-mode :Mode.bundle.theme/dark  {:axis :theme :args {:theme :dark}})
  (story/reg-mode :Mode.bundle.theme/light {:axis :theme :args {:theme :light}})
  (story/reg-mode :Mode.bundle.vp/mobile   {:axis :viewport :args {:viewport :mobile}})
  (story/reg-workspace :Workspace.bundle/grid
    {:layout   :variants-grid
     :variants [:story.bundle.counter/empty
                :story.bundle.counter/loaded
                :story.bundle.counter/three]}))

;; ===========================================================================
;; rf2-5onip — all-stories-resolvable post-build
;;
;; The static export walks the registry at build time to produce its
;; deployable artefact. After mount, the shell reads (ids :variant) and
;; iterates the resulting set to render the sidebar. Every id MUST
;; resolve to a renderable body via handler-meta — else the sidebar
;; row exists but clicking it produces nothing.
;; ===========================================================================

(deftest every-registered-variant-resolves-to-body
  (testing "post-build assertion: walk (ids :variant), call handler-meta
            on each id, assert each one returns a non-nil body. This is
            the contract the shell relies on for sidebar → canvas
            navigation; broken would mean an empty canvas on click"
    (seed-bundle-like-registry!)
    (let [vids   (story/ids :variant)
          unresolved (filter (fn [vid]
                               (nil? (story/handler-meta :variant vid)))
                             vids)]
      (is (seq vids)
          "the bundle carries at least one variant — fixture sanity")
      (is (= [] (vec unresolved))
          "no registered variant id is unresolvable through handler-meta"))))

(deftest every-registered-story-resolves-to-body
  (testing "the parent-story side of the read-surface: every (ids :story)
            entry resolves via handler-meta. Variants depend on their
            parent's :component fallback (per canvas.cljs §variant-
            component) — an unresolvable parent would blank the canvas
            for every child"
    (seed-bundle-like-registry!)
    (let [sids       (story/ids :story)
          unresolved (filter #(nil? (story/handler-meta :story %)) sids)]
      (is (seq sids))
      (is (= [] (vec unresolved))))))

(deftest every-registered-mode-resolves-to-body
  (testing "modes are read by the toolbar at every render — an
            unresolvable mode id means a chip rendered with no :args
            payload, silently broken"
    (seed-bundle-like-registry!)
    (let [mids       (story/list-modes)
          unresolved (filter #(nil? (story/handler-meta :mode %)) mids)]
      (is (seq mids))
      (is (= [] (vec unresolved))))))

(deftest workspace-variant-refs-all-resolve
  (testing "workspaces carry `:variants` vectors naming variant ids;
            each named id MUST resolve through the read surface. A
            broken ref would render a cell with no canvas content"
    (seed-bundle-like-registry!)
    (let [wids (story/ids :workspace)]
      (doseq [wid wids]
        (let [body  (story/handler-meta :workspace wid)
              vids  (or (:variants body) [])]
          (doseq [vid vids]
            (is (story/registered? :variant vid)
                (str "workspace " (pr-str wid)
                     " references variant " (pr-str vid)
                     " which does not resolve in the registry — broken cell ref"))))))))

(deftest read-surface-mirrors-id-set-and-body-map
  (testing "the bundle's introspection paths (the agent's
            `list-variants` MCP tool + the renderer's registrations
            walk) must agree on the variant set. Pinning this is
            the cross-check the static-export bundle needs: (ids :kind)
            keyset must equal (keys (registrations :kind))"
    (seed-bundle-like-registry!)
    (is (= (story/ids :variant)
           (set (keys (story/registrations :variant))))
        "ids ↔ registrations agree on the variant id-set")
    (is (= (story/ids :story)
           (set (keys (story/registrations :story))))
        "ids ↔ registrations agree on the story id-set")
    (is (= (story/list-modes)
           (set (keys (story/registrations :mode))))
        "list-modes ↔ registrations agree on the mode id-set")))

;; ===========================================================================
;; rf2-5onip — link integrity (share URLs round-trip to registered ids)
;;
;; The static export embeds share URLs in prose blocks, docs panels, and
;; the per-variant share affordance. Each URL encodes a `?variant=<id>`
;; (and optionally modes / args) that the receiving shell parses back
;; into a registry lookup. The contract: every encoded variant id can
;; be decoded back to the same keyword AND that keyword resolves in
;; the registry. A broken link would mean a share URL the build emitted
;; lands on the no-such-variant empty state.
;; ===========================================================================

(deftest variant-share-url-encodes-id
  (testing "spec/013 + spec/014 §Share: variant-share-url produces a
            URL string that carries the variant's id as a `variant=`
            query parameter. Pinning the encode side of the contract;
            the decode side is exercised below"
    (seed-bundle-like-registry!)
    (doseq [vid (story/ids :variant)]
      (let [url (story/variant-share-url vid)]
        (is (string? url)
            (str "share URL for " (pr-str vid) " is a string"))
        (is (str/includes? url "variant=")
            (str "share URL for " (pr-str vid)
                 " carries the variant= query param"))))))

(deftest share-url-variant-id-round-trips-to-registry
  (testing "round-trip: encode every registered variant id into a share
            URL; parse the variant= param out; decode the keyword; assert
            the decoded id round-trips to the registered id. This pins
            the link-integrity contract across the share boundary — no
            silent escaping mismatch can leave a URL pointing at an
            unresolvable id"
    (seed-bundle-like-registry!)
    (doseq [vid (story/ids :variant)]
      (let [url      (story/variant-share-url vid)
            param    (some->> url
                              (re-find #"variant=([^&]+)")
                              second)
            decoded  (when param
                       (-> param
                           (java.net.URLDecoder/decode "UTF-8")
                           share/parse-keyword-token))]
        (is (= vid decoded)
            (str "variant id " (pr-str vid)
                 " round-trips through share URL → " (pr-str decoded)))
        (is (story/registered? :variant decoded)
            (str "decoded id " (pr-str decoded)
                 " resolves in the registry — no broken link"))))))

(deftest share-url-with-modes-round-trips-active-modes
  (testing "share URLs that encode active modes deep-link the toolbar.
            Every encoded mode id MUST decode back to a registered mode
            — else the toolbar drops it at hydrate time and the share
            silently loses fidelity. Pin the round-trip"
    (seed-bundle-like-registry!)
    (let [active-modes [:Mode.bundle.theme/dark :Mode.bundle.vp/mobile]
          url     (story/variant-share-url
                    :story.bundle.counter/empty
                    ""
                    {:active-modes active-modes})
          ;; Extract the modes param.
          modes-param (some->> url
                               (re-find #"modes=([^&]+)")
                               second
                               (#(java.net.URLDecoder/decode % "UTF-8")))
          decoded (when modes-param
                    (->> (str/split modes-param #",")
                         (map share/parse-keyword-token)
                         vec))]
      (is (seq decoded)
          "modes param decoded to a non-empty vector")
      (is (= (set active-modes) (set decoded))
          "encoded modes round-trip exactly")
      (doseq [mid decoded]
        (is (story/registered? :mode mid)
            (str "decoded mode " (pr-str mid) " resolves in the registry"))))))

(deftest share-url-rejects-unregistered-variant-as-broken-link
  (testing "the inverse — if a share URL points at a variant id that
            was renamed/removed between build and view, the parse
            still succeeds (the keyword decodes) but the registry
            lookup must return false. This is the empty-state branch
            the spec/014 §Share contract names: the renderer falls
            back to 'no such variant' rather than rendering garbage"
    (seed-bundle-like-registry!)
    (let [stale-id :story.bundle.counter/this-was-renamed]
      (is (not (story/registered? :variant stale-id))
          "the renamed id is not in the registry")
      ;; The share URL build still encodes whatever id you give it —
      ;; integrity is the BUILD's responsibility (only encode currently-
      ;; registered ids). At decode time the registered? check is the
      ;; integrity gate.
      (let [url     (story/variant-share-url stale-id)
            param   (some->> url
                             (re-find #"variant=([^&]+)")
                             second
                             (#(java.net.URLDecoder/decode % "UTF-8")))
            decoded (share/parse-keyword-token param)]
        (is (= stale-id decoded)
            "the URL still round-trips the id verbatim")
        (is (not (story/registered? :variant decoded))
            "but the registry check correctly reports the broken link
             — the shell's no-such-variant empty state engages")))))

;; ===========================================================================
;; rf2-5onip — bundle-relative selectors (pr-str round-trip)
;;
;; The shell stamps `data-test-variant="(pr-str variant-id)"` on the
;; canvas root (per canvas.cljs §reagent-render). Playwright specs and
;; bundle-introspection tools select on this stamp. The id MUST
;; round-trip through `pr-str` ↔ `read-string` so the selector value
;; reproduces the original keyword exactly.
;; ===========================================================================

(deftest variant-id-pr-str-round-trip
  (testing "every registered variant id round-trips through
            (read-string (pr-str id)) to the SAME keyword. This is the
            invariant data-test-variant selectors depend on — Playwright
            specs read the stamp's string and reconstruct the keyword"
    (seed-bundle-like-registry!)
    (doseq [vid (story/ids :variant)]
      (let [stamped (pr-str vid)
            parsed  (read-string stamped)]
        (is (= vid parsed)
            (str "id " (pr-str vid) " round-trips through pr-str ↔ read-string"))))))

(deftest mode-id-pr-str-round-trip
  (testing "mode ids round-trip the same way — toolbar chips stamp the
            mode id into a data-toolbar-mode attribute"
    (seed-bundle-like-registry!)
    (doseq [mid (story/list-modes)]
      (let [stamped (pr-str mid)
            parsed  (read-string stamped)]
        (is (= mid parsed)
            (str "mode id " (pr-str mid) " round-trips"))))))
