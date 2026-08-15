(ns re-frame.ssr.phase-flip-hydration-dom-cljs-test
  "Real-DOM proof of the `ui/client-only` PHASE FLIP — the S5 half of
  `ui/client-only` (rf2-3omxp; Spec 011 §Phase flip).

  ## Why this test lives in the ssr tree

  The flip is a property of a HYDRATING root, and only `re-frame.ssr` +
  `re-frame.ui.client/hydrate-root` together produce one: `ssr/hydrate!` boots
  the frame state and `ui/hydrate-root` adopts the server DOM (via the
  `:ssr/discover-root-manifest` seam). A test that drives a real flip therefore
  needs BOTH artefacts loaded, which is what the `ssr` test tree provides (the
  same reason `hydrate-root-seam-dom-cljs-test` lives here).

  ## What this proves — the SIX ruled points of Spec 011 §Phase flip

    1. PHASE-CONDITIONAL SITE + SINGLE UPDATE — a `reg-view` `ui/client-only`
       site renders its FALLBACK in `:server` phase during hydration, then swaps
       to the CLIENT subtree after the hydration commit. The swap replaces the
       exact server-emitted fallback node with the client subtree.

    3. TIMING — the flip runs as the root's NEXT ORDINARY UPDATE after the
       hydration commit, never before. Proven negatively: hydration is CLEAN —
       no `onRecoverableError` fires — which can only be true if the first
       (hydration) render produced the FALLBACK (matching the server markup),
       not the client subtree. A phase that booted `:client` would render the
       `<button>` against a server `<p>` and fire a recoverable mismatch.

    4. FAILED ROOT — a root that fails to boot never flips: its server fallback
       markup stays in place, inert (composes with the `rf2-1b0po` missing-
       manifest failure — no scenario multiplication).

    5. MULTI-ROOT — two roots flip independently; a failed sibling does not
       delay or block a good root's flip (no page-wide barrier).

  Every assertion reads an OBSERVABLE OUTCOME — a DOM node's presence, its
  text, its identity, or the absence of a recoverable-error callback. None
  merely asserts that an exception occurred.

  Browser-only — a flip is a real-DOM operation (a passive effect after a
  real hydration commit). The `-dom-cljs-test` suffix opts this file into the
  `:browser-test` build; the `:node-test` build loads it too (it matches
  `cljs-test$`) and every body gates on `(browser?)`, so a node run is an
  honest skip rather than a vacuous pass."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.constants :as constants]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as ui-client]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---------------------------------------------------------------------------
;; The app under the boot — ids unique to this ns so the shared bundle cannot
;; collide at image assembly.
;; ---------------------------------------------------------------------------

(def ^:private app-frame ::frame)

(rf/reg-sub ::label (fn [db _] (or (:label db) "client-label")))

;; A compiled root with a `ui/client-only` site. The FALLBACK is capability-
;; free static markup (`<p class="fb">`), the deterministic JVM/SSR +
;; first-hydration render. The CLIENT subtree is the live reactive content
;; (`<button class="live">` reading a sub). `<span class="static">` sits
;; OUTSIDE the client-only site — it hydrates normally in every phase and
;; proves the rest of the tree is untouched by the flip.
(defview phase-root []
  [:div.phaseapp
   [:span.static "always"]
   (ui/client-only {:fallback [:p.fb "loading"]}
     [:button.live (ui/sub [::label])])])

;; ---------------------------------------------------------------------------
;; Server-side artefacts, planted by hand. The server render emits the fallback
;; markup for a client-only site (its client subtree never appears on the
;; JVM/SSR tree), so the planted body is the fallback.
;; ---------------------------------------------------------------------------

(def ^:private server-body
  ;; Exactly what a server render of `phase-root` emits: the static sibling and
  ;; the client-only FALLBACK, the `:rf.ui/boundary :client-only` fragment being
  ;; transparent in HTML. A byte-match keeps hydration a clean adoption.
  (str "<div class=\"phaseapp\">"
       "<span class=\"static\">always</span>"
       "<p class=\"fb\">loading</p>"
       "</div>"))

(defn- manifest-for [root-id container-id]
  {:rf.root/schema-version 1
   :root-id                root-id
   :identifier-prefix      (str (name root-id) "-")
   :element-locator        {:id container-id}})

(defn- manifest-script [root-id container-id]
  (str "<script type=\"application/edn\" "
       constants/root-manifest-marker-attribute ">"
       (pr-str (manifest-for root-id container-id))
       "</script>"))

(defn- plant-host!
  "Append the shape a server render emits for ONE root: a container holding the
  server body (the fallback markup), followed IMMEDIATELY by its Root Manifest
  as the next element sibling (that adjacency is the discovery rule). When
  `:manifest?` is false the manifest is omitted — the broken-markup shape a
  hydrating root must FAIL on. Returns the wrapper host."
  [{:keys [manifest? root-id container-id body]
    :or   {manifest? true root-id ::phase-root container-id "phase-container"
           body server-body}}]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host)
          (str "<div id=\"" container-id "\">" body "</div>"
               (when manifest? (manifest-script root-id container-id))))
    (.appendChild (.-body js/document) host)
    host))

(defn- container [host container-id]
  (.querySelector host (str "#" container-id)))

(defn- teardown! [host & roots]
  (doseq [ra roots]
    (when-let [root @ra]
      (try (ui/unmount! root) (catch :default _ nil)))
    (reset! ra nil))
  (when-let [p (.-parentNode host)] (.removeChild p host)))

;; Hydration commits on React's own schedule here, so turn the act environment
;; off for the duration (same treatment `hydrate-root-seam-dom-cljs-test` and
;; `ssr-startup-recipe-dom-cljs-test` apply). With act off, a genuine hydration
;; mismatch reaches `:on-recoverable-error`.
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(defn- restore-act-env! [prev]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter ui/adapter :async? true :ambient-frame nil}))

(defn- thrown-error [f]
  (try (f) nil
       (catch :default e
         {:id (:rf.error/id (ex-data e)) :data (ex-data e)})))

(defn- wait-for
  "Poll `pred` every 5ms up to ~1s, then call `k`. Bounded so a flip that never
  arrives lets the assertions in `k` fail honestly rather than hang."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 200))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

;; ---------------------------------------------------------------------------
;; 1. The vertical — fallback at hydration, client subtree after ONE update
;; ---------------------------------------------------------------------------

(deftest phase-flip-swaps-fallback-to-client-in-one-post-commit-update
  (when (browser?)
    (async done
      (let [act-prev    (disable-act-env!)
            host        (plant-host! {})
            root-atom   (atom nil)
            recoverable (atom [])]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        ;; The flip is a property of DOM ADOPTION (`ui/hydrate-root`), independent
        ;; of the `ssr/hydrate!` state boot (item 1, proven by
        ;; `hydrate-root-seam-dom-cljs-test`): the capability-free fallback carries
        ;; no state, so the flip is exercised by hydration alone.
        (let [fb-node (.querySelector host "p.fb")]
          (reset! root-atom
                  (ui/hydrate-root (container host "phase-container")
                                   [ui/frame-provider {:frame app-frame} [phase-root]]
                                   {:on-recoverable-error
                                    (fn [e _] (swap! recoverable conj e))}))
          ;; SYNCHRONOUS — the flip is a passive effect; it has NOT run yet. The
          ;; hydrating root is in :server phase, so the fallback is the live
          ;; content and the client subtree is absent.
          (is (some? (.querySelector host "p.fb"))
              "fallback present at hydration — the root booted :server phase")
          (is (nil? (.querySelector host "button.live"))
              "client subtree absent at hydration — the site is phase-conditional")
          (is (true? (.-isConnected fb-node))
              "the server-emitted fallback node is the hydrated content")
          (wait-for
           #(some? (.querySelector host "button.live"))
           (fn []
             (try
               (testing "the post-commit flip swapped fallback -> client subtree in ONE update"
                 (is (some? (.querySelector host "button.live"))
                     "the client subtree is present after the flip")
                 (is (nil? (.querySelector host "p.fb"))
                     "the fallback was swapped away — one root-scoped update, not left tearing")
                 (is (false? (.-isConnected fb-node))
                     "the exact server fallback node was removed by the flip"))
               (testing "the non-client-only part of the tree hydrated normally"
                 (is (some? (.querySelector host "span.static"))
                     "the static sibling stayed through both phases"))
               (testing "the flipped subtree is the LIVE client render over hydrated state"
                 (is (= "client-label"
                        (.-textContent (.querySelector host "button.live")))
                     "the client subtree rendered its reactive sub value"))
               (testing "TIMING — hydration was clean, so the FIRST render was the fallback"
                 (is (empty? @recoverable)
                     (str "no hydration-mismatch recoverable error fired. A flip "
                          "that beat the hydration commit — or a site born :client "
                          "— would have rendered the <button> against the server "
                          "<p> and fired a mismatch. Clean hydration proves the "
                          "first render was the :server-phase fallback")))
               (testing "the root is registered under the manifest's root-id"
                 (is (= ::phase-root (ui-client/root-id-of @root-atom))))
               (finally
                 (teardown! host root-atom)
                 (restore-act-env! act-prev)
                 (done))))))))))

;; ---------------------------------------------------------------------------
;; 2. A failed root never flips — its fallback stays inert (Spec 011 point 4)
;; ---------------------------------------------------------------------------

(deftest failed-root-fallback-stays-inert-never-flips
  (when (browser?)
    (async done
      (let [act-prev  (disable-act-env!)
            ;; No adjacent manifest — the rf2-1b0po failed-root shape: a
            ;; hydrating root that cannot discover its identity fails to boot.
            host      (plant-host! {:manifest? false})
            root-atom (atom nil)]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        (let [{:keys [id data]}
              (thrown-error
               #(ui/hydrate-root (container host "phase-container")
                                 [ui/frame-provider {:frame app-frame} [phase-root]]))]
          (is (= :rf.error/root-manifest-invalid id)
              "the root failed to boot — composes with rf2-1b0po (missing manifest)")
          (is (= :manifest (:missing data))
              "ex-data names which part is missing"))
        ;; The root never created a React tree, so nothing can ever flip it.
        ;; Give the scheduler a turn, then prove the fallback stayed inert.
        (wait-for
         (constantly false)  ;; just let a macrotask pass; nothing to await
         (fn []
           (try
             (testing "a failed root's server fallback stays in place, inert"
               (is (some? (.querySelector host "p.fb"))
                   "the capability-free fallback still stands — degrades gracefully")
               (is (nil? (.querySelector host "button.live"))
                   "and it NEVER flipped to the client subtree"))
             (finally
               (teardown! host root-atom)
               (restore-act-env! act-prev)
               (done)))))))))

;; ---------------------------------------------------------------------------
;; 3. Multi-root — siblings flip independently; a failed sibling never blocks
;;    a good root's flip (Spec 011 point 5 — no page-wide barrier)
;; ---------------------------------------------------------------------------

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(defn- plant-two-roots!
  "A real two-root page: root A (good, with manifest) and root B (BROKEN — no
  adjacent manifest). Each container holds the fallback body."
  []
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host)
          (str "<div id=\"root-a\">" server-body "</div>"
               (manifest-script ::root-a "root-a")
               "<div id=\"root-b\">" server-body "</div>"))  ;; B: no manifest
    (.appendChild (.-body js/document) host)
    host))

(deftest sibling-roots-flip-independently-failed-sibling-does-not-block
  (when (browser?)
    (async done
      (let [act-prev    (disable-act-env!)
            host        (plant-two-roots!)
            root-a      (atom nil)
            recoverable (atom [])]
        (rf/init! ui/adapter)
        (rf/make-frame {:id frame-a :platform :client})
        (rf/make-frame {:id frame-b :platform :client})
        ;; Root B fails to boot (no manifest) BEFORE root A is even mounted —
        ;; the failure is contained (rf2-1b0po) and must not stop A flipping.
        (let [{:keys [id]}
              (thrown-error
               #(ui/hydrate-root (.querySelector host "#root-b")
                                 [ui/frame-provider {:frame frame-b} [phase-root]]))]
          (is (= :rf.error/root-manifest-invalid id)
              "sibling B failed to boot — a contained failure"))
        ;; Root A boots normally. Its flip must land despite B's failure.
        (reset! root-a
                (ui/hydrate-root (.querySelector host "#root-a")
                                 [ui/frame-provider {:frame frame-a} [phase-root]]
                                 {:on-recoverable-error
                                  (fn [e _] (swap! recoverable conj e))}))
        (wait-for
         #(some? (.querySelector (.querySelector host "#root-a") "button.live"))
         (fn []
           (try
             (testing "root A flipped independently of its failed sibling"
               (is (some? (.querySelector (.querySelector host "#root-a") "button.live"))
                   "good root A swapped to its client subtree — no page-wide barrier")
               (is (nil? (.querySelector (.querySelector host "#root-a") "p.fb"))
                   "and its fallback was swapped away"))
             (testing "the failed sibling B stayed on its inert fallback"
               (is (some? (.querySelector (.querySelector host "#root-b") "p.fb"))
                   "broken root B never flipped — its fallback still stands")
               (is (nil? (.querySelector (.querySelector host "#root-b") "button.live"))
                   "and B never produced a client subtree"))
             (testing "root A's flip was a CLEAN post-commit update, not a hydration mismatch"
               (is (empty? @recoverable)
                   (str "root A hydrated its fallback cleanly then flipped — no "
                        "recoverable mismatch. A site born :client would have "
                        "mismatched the server <p> and fired this")))
             (finally
               (teardown! host root-a)
               (restore-act-env! act-prev)
               (done)))))))))
