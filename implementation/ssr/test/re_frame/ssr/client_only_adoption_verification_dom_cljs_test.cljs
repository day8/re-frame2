(ns re-frame.ssr.client-only-adoption-verification-dom-cljs-test
  "Real-DOM proof of the COMPILED-TIER hydration verification (rf2-6z1i2, the
  Path A ruling; Spec 011 §Hydration-mismatch detection — the two-tier split).

  ## What this proves

  A compiled `re-frame.ui` root has NO hashable client render-tree — its views
  compile to React elements, not the structural tree the server hashes — so the
  `:rf/render-hash` / `:render-tree-fn` hash channel is HICCUP-TIER-ONLY. A
  compiled root instead VERIFIES by React-native ADOPTION: its first
  `:server`-phase render (the `ui/client-only` fallbacks) is what React hydrates
  against the server DOM, and React reports the divergences it RECOVERS FROM
  (text-content / structural) through the root's `onRecoverableError` — NOT
  attribute-only mismatches (see vertical 2b). `hydrate-root*` surfaces that
  adoption-window divergence (before the phase flip) as the SAME
  `:rf.ssr/hydration-mismatch` diagnostic, composed OVER any host
  `:on-recoverable-error`.

  Four verticals, all through the canonical two-call boot `ssr/hydrate!` (state,
  WITHOUT `:render-tree-fn`) then `ui/hydrate-root` (DOM adoption):

    1. IDENTICAL root — the client's `:server`-phase fallbacks byte-match the
       server DOM ⇒ React adopts cleanly ⇒ NO `:rf.ssr/hydration-mismatch`
       trace; the two client-only sites then flip to their client subtrees in
       ONE root-scoped update (exactly one `:rf.ssr/phase-flip` trace, witnessed
       by a `:root-id`-SCOPED listener so a foreign root's flip cannot count);
       the flipped subtree renders the LIVE sub over the hydrated app-db.

    2. DIVERGENT root — the server DOM's fallback TEXT diverges from the client's
       ⇒ React fires `onRecoverableError` during adoption ⇒ the framework emits
       `:rf.ssr/hydration-mismatch` (tier-discriminated `:where`
       `re-frame.ui/hydrate-root`, no hash) AND the host's own
       `:on-recoverable-error` still fires (compose, never clobber).

    2b. ATTRIBUTE-ONLY divergence (the DOCUMENTED LIMITATION) — the server DOM's
        fallback carries a stale `class` but matching tag+text ⇒ React does NOT
        escalate an attribute mismatch to `onRecoverableError` (it takes React's
        dev-only warning path) ⇒ the compiled tier surfaces NO
        `:rf.ssr/hydration-mismatch`. React-native adoption reports only what
        React recovers from, NOT exhaustive server-vs-client divergence.

    3. NON-HYDRATING mount — `ui/mount` is born `:client`, renders the client
       subtree directly (no fallback, no flip), and installs NO wrapper.

  RED-BEFORE: without the `hydrate-root*` `onRecoverableError` wiring, a
  compiled root NEVER surfaces `:rf.ssr/hydration-mismatch` — the divergent
  vertical's assertion that the trace fires is the lever. The attribute-only
  vertical is the inverse lever: it pins that the trace STAYS SILENT for a class
  React does not recover from. Every assertion reads an OBSERVABLE outcome (a
  captured trace, a DOM node's text/identity), never that an exception was
  thrown.

  Browser-only — adoption is a real-DOM operation. The `-dom-cljs-test` suffix
  opts this into the `:browser-test` build; the `:node-test` build loads it too
  (matches `cljs-test$`) and every body gates on `(browser?)`, so a node run is
  an honest skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
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
;; The app — ids unique to this ns so the shared bundle cannot collide at image
;; assembly.
;; ---------------------------------------------------------------------------

(def ^:private app-frame ::frame)

(rf/reg-sub ::label (fn [db _] (or (:label db) "client-label")))

;; A compiled root with TWO `ui/client-only` sites (the single-flip proof). Each
;; FALLBACK is capability-free static markup (the JVM/SSR + first-hydration
;; render); each CLIENT subtree is live reactive content. `<span.static>` sits
;; outside both sites and hydrates in every phase.
(defview adopt-root []
  [:div.adoptapp
   [:span.static "always"]
   (ui/client-only {:fallback [:p.fb "loading"]}
     [:button.live (ui/sub [::label])])
   (ui/client-only {:fallback [:p.fb2 "loading"]}
     [:button.live2 "live2"])])

;; ---------------------------------------------------------------------------
;; Server-side artefacts, planted by hand — exactly what a server render of
;; `adopt-root` emits: the static sibling and the two client-only FALLBACKS (the
;; `:rf.ui/boundary :client-only` fragments are transparent in HTML). A
;; byte-match makes hydration a CLEAN adoption.
;; ---------------------------------------------------------------------------

(defn- server-body [fb-text]
  (str "<div class=\"adoptapp\">"
       "<span class=\"static\">always</span>"
       "<p class=\"fb\">" fb-text "</p>"
       "<p class=\"fb2\">loading</p>"
       "</div>"))

(def ^:private matching-body (server-body "loading"))
;; The DIVERGENT lever: the FIRST fallback's text disagrees with the client's
;; `:server`-phase render (`"loading"`), so React's adoption of this node is a
;; recoverable hydration mismatch.
(def ^:private divergent-body (server-body "SERVER-DIVERGES"))

;; The ATTRIBUTE-ONLY lever: same structure and TEXT as `matching-body`, but the
;; first fallback carries an EXTRA class token the client's `:server`-phase
;; render does not (`fb stale-server` vs `fb`). Tag and text still match, so this
;; is an attribute-only divergence — the class that pins the documented
;; limitation (React does NOT escalate attribute mismatches to
;; `onRecoverableError`; Spec 011 §Hydration-mismatch detection).
(def ^:private attr-divergent-body
  (str "<div class=\"adoptapp\">"
       "<span class=\"static\">always</span>"
       "<p class=\"fb stale-server\">loading</p>"
       "<p class=\"fb2\">loading</p>"
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

;; The hydration payload a server would embed: the app-db slice AND the
;; fallback-bearing render hash. The hash RIDES the payload (the hiccup-tier
;; channel) but the compiled tier IGNORES it — `ssr/hydrate!` is called WITHOUT
;; `:render-tree-fn`, so `verify-hydration!` never runs; verification is by
;; adoption. The presence-but-non-use of `:rf/render-hash` is the two-tier split.
(defn- payload []
  {:rf/frame-id   app-frame
   :rf/app-db     {:label "hydrated-label"}
   :rf/render-hash "fa11bac0"})

(defn- plant-host!
  "Append the shape a server render emits for ONE compiled root: a container
  holding `body` (the fallback markup), followed IMMEDIATELY by its Root Manifest
  as the next element sibling (that adjacency is the discovery rule). Returns the
  wrapper host."
  [{:keys [root-id container-id body]
    :or   {root-id ::adopt-root container-id "adopt-container" body matching-body}}]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host)
          (str "<div id=\"" container-id "\">" body "</div>"
               (manifest-script root-id container-id)))
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

;; Hydration commits on React's own schedule here (act off), so a genuine
;; adoption mismatch reaches `onRecoverableError` — same treatment
;; `phase-flip-hydration-dom-cljs-test` applies.
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(defn- restore-act-env! [prev]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter ui/adapter :async? true :ambient-frame nil}))

(defn- wait-for
  "Poll `pred` every 5ms up to ~1.5s, then call `k`. Bounded so an outcome that
  never arrives lets the assertions in `k` fail honestly rather than hang."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 300))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- with-trace-listener!
  "Register a `:trace` listener capturing matching events into `sink` (an atom
  vector) and return an unregister thunk. Used directly (not via
  `with-trace-recorder!`) because the events of interest fire ASYNCHRONOUSLY,
  after the sync body returns."
  [sink pred]
  (let [k (keyword (gensym "adopt-trace-"))]
    (rf/register-listener! :trace k
                           (fn [ev] (when (pred ev) (swap! sink conj ev))))
    (fn [] (rf/unregister-listener! :trace k))))

(defn- op= [operation]
  (fn [ev] (= operation (:operation ev))))

(defn- op-for-root
  "Like `op=`, but ALSO scopes to `root-id` — matches only a trace of `operation`
  whose `:root-id` (top-level or under `:tags`) is `root-id`. Used for the
  `:rf.ssr/phase-flip` witness so a FOREIGN root's flip in the same process
  cannot satisfy this vertical's sole positive count (rf2-6z1i2 / PR #6512
  audit residual — the producer already tags `:root-id`)."
  [operation root-id]
  (fn [ev]
    (and (= operation (:operation ev))
         (= root-id (or (:root-id ev) (get-in ev [:tags :root-id]))))))

;; ---------------------------------------------------------------------------
;; 1. Identical root — clean adoption, NO mismatch trace, ONE flip
;; ---------------------------------------------------------------------------

(deftest identical-compiled-root-adopts-cleanly-then-flips-in-one-update
  (when (browser?)
    (async done
      (let [act-prev  (disable-act-env!)
            ;; A root id UNIQUE to this vertical (rf2-6z1i2 / PR #6512 audit
            ;; residual). The `:rf.ssr/phase-flip` witness below is SCOPED to it
            ;; via `[:tags :root-id]`, so a FOREIGN root's flip in the same
            ;; process cannot satisfy this test's sole positive count.
            root-id   ::identical-root
            host      (plant-host! {:root-id root-id})
            root-atom (atom nil)
            mismatches (atom [])
            flips      (atom [])
            flip-witness (op-for-root :rf.ssr/phase-flip root-id)
            stop-mm   (with-trace-listener! mismatches (op= :rf.ssr/hydration-mismatch))
            stop-fl   (with-trace-listener! flips flip-witness)]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        ;; Canonical TWO-CALL boot. Step one: seed state via ssr/hydrate! WITHOUT
        ;; :render-tree-fn (the compiled root has no hashable client tree). The
        ;; payload carries :rf/render-hash, which the compiled tier ignores.
        (ssr/hydrate! {:frame    app-frame
                       :payload  (payload)
                       :manifest (manifest-for root-id "adopt-container")
                       :root-id  root-id})
        (let [fb-node (.querySelector host "p.fb")]
          ;; Step two: adopt the server DOM.
          (reset! root-atom
                  (ui/hydrate-root (container host "adopt-container")
                                   [ui/frame-provider {:frame app-frame} [adopt-root]]))
          ;; SYNCHRONOUS — the fallbacks are the live content; the flip is a
          ;; passive effect that has not run yet.
          (is (some? (.querySelector host "p.fb"))
              "fallback present at hydration — the root booted :server phase")
          (is (nil? (.querySelector host "button.live"))
              "client subtree absent at hydration — the site is phase-conditional")
          ;; Poll the FLIP TRACE, not the DOM. `PhaseFlipper` flips via a POST-
          ;; PAINT passive `useEffect` (no `flushSync`): the `:client` commit
          ;; paints `button.live` FIRST, and only afterwards does the passive
          ;; effect run `emit-phase-flip!`. Polling `button.live` can therefore
          ;; fire `k` in the window after the commit but before the effect's
          ;; emit — `@flips` still 0 — which flakes `(= 1 (count @flips))` on a
          ;; slow host (green locally, red on CI). Waiting on the very trace the
          ;; assertions read closes that race, and because the flip trace fires
          ;; STRICTLY after the client subtree is committed, every DOM assertion
          ;; below still holds. Bounded, so a genuinely-missing flip still fails
          ;; honestly. Mirrors test 2's `#(seq @mismatches)` idiom.
          (wait-for
           #(seq @flips)
           (fn []
             (try
               (testing "React adopted the :server-phase fallbacks with NO framework mismatch"
                 (is (empty? @mismatches)
                     (str "an identical compiled root adopts cleanly — no "
                          ":rf.ssr/hydration-mismatch. Saw: " (pr-str @mismatches))))
               (testing "the server-emitted fallback node WAS the hydrated content"
                 (is (false? (.-isConnected fb-node))
                     "the exact server fallback node was adopted then swapped by the flip"))
               (testing "the flip witness is root-scoped — a foreign root's flip is NOT counted"
                 (is (not (flip-witness {:operation :rf.ssr/phase-flip
                                         :tags {:root-id ::some-foreign-root}}))
                     (str "a :rf.ssr/phase-flip tagged with a DIFFERENT :root-id must not "
                          "satisfy this vertical's sole positive witness — the [:tags :root-id] "
                          "filter is load-bearing (drop it and this vertical reds)")))
               (testing "ONE root-scoped flip swapped BOTH client-only sites"
                 (is (= 1 (count @flips))
                     (str "exactly one :rf.ssr/phase-flip for the root. Saw: "
                          (count @flips)))
                 (is (some? (.querySelector host "button.live"))
                     "first client-only site swapped to its client subtree")
                 (is (some? (.querySelector host "button.live2"))
                     "second client-only site swapped in the SAME update")
                 (is (nil? (.querySelector host "p.fb"))
                     "first fallback swapped away")
                 (is (nil? (.querySelector host "p.fb2"))
                     "second fallback swapped away — one root-scoped update, no tear"))
               (testing "the flipped subtree renders the LIVE sub over the hydrated app-db"
                 (is (= "hydrated-label"
                        (.-textContent (.querySelector host "button.live")))
                     "ssr/hydrate! seeded {:label \"hydrated-label\"}; the flipped client subtree reads it"))
               (testing "the static sibling stayed through both phases"
                 (is (some? (.querySelector host "span.static"))))
               (finally
                 (stop-mm) (stop-fl)
                 (teardown! host root-atom)
                 (restore-act-env! act-prev)
                 (done))))))))))

;; ---------------------------------------------------------------------------
;; 2. Divergent root — mismatch trace fires from the adoption window AND the
;;    host :on-recoverable-error still fires (compose, never clobber)
;; ---------------------------------------------------------------------------

(deftest divergent-compiled-root-surfaces-mismatch-and-composes-host-callback
  (when (browser?)
    (async done
      (let [act-prev  (disable-act-env!)
            host      (plant-host! {:body divergent-body})
            root-atom (atom nil)
            mismatches (atom [])
            host-recoverable (atom [])
            stop-mm   (with-trace-listener! mismatches (op= :rf.ssr/hydration-mismatch))]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        (ssr/hydrate! {:frame    app-frame
                       :payload  (payload)
                       :manifest (manifest-for ::adopt-root "adopt-container")
                       :root-id  ::adopt-root})
        (reset! root-atom
                (ui/hydrate-root (container host "adopt-container")
                                 [ui/frame-provider {:frame app-frame} [adopt-root]]
                                 {:on-recoverable-error
                                  (fn [e _] (swap! host-recoverable conj e))}))
        (wait-for
         #(seq @mismatches)
         (fn []
           (try
             (testing "the framework surfaced :rf.ssr/hydration-mismatch from the adoption window"
               (is (seq @mismatches)
                   (str "a divergent compiled root's adoption fires onRecoverableError, "
                        "which hydrate-root* surfaces as :rf.ssr/hydration-mismatch"))
               (when-let [mm (first @mismatches)]
                 (is (= :rf.ssr/hydration-mismatch (:operation mm)))
                 ;; Merge tag-level + top-level so the assertion is robust to
                 ;; whichever slots the envelope hoists.
                 (let [tags (merge (:tags mm) mm)]
                   (is (= 're-frame.ui/hydrate-root (:where tags))
                       "tier-discriminated by :where — the compiled adoption site")
                   (is (= ::adopt-root (:root-id tags))
                       "carries the root-id")
                   (is (not (contains? (or (:tags mm) {}) :server-hash))
                       "NO fabricated :server-hash — the compiled tier has no hash")
                   (is (not (contains? (or (:tags mm) {}) :client-hash))
                       "NO fabricated :client-hash"))))
             (testing "the host's own :on-recoverable-error STILL fired (compose, not clobber)"
               (is (seq @host-recoverable)
                   "the framework composed OVER the host callback — both fired"))
             (finally
               (stop-mm)
               (teardown! host root-atom)
               (restore-act-env! act-prev)
               (done)))))))))

;; ---------------------------------------------------------------------------
;; 2b. Attribute-only mismatch — the DOCUMENTED LIMITATION. React-native
;;     adoption surfaces ONLY the divergences React recovers from, so an
;;     attribute-only mismatch raises NO framework trace (rf2-6z1i2 residual).
;; ---------------------------------------------------------------------------

(deftest attribute-only-mismatch-hydrates-silently-with-no-framework-trace
  ;; The honest limitation (rf2-6z1i2 audit residual; Spec 011 §Hydration-mismatch
  ;; detection, the attribute-only boundary). React-native adoption surfaces ONLY
  ;; the divergences React AUTOMATICALLY RECOVERS FROM — text-content and
  ;; structural (missing / extra / wrong-type node). An ATTRIBUTE-ONLY mismatch
  ;; (here a stale `class` on a `<p>` whose tag and text still match) takes
  ;; React's DEV-ONLY warning path: React does NOT call `onRecoverableError` and
  ;; makes no promise to patch the attribute. So `hydrate-root*`'s adoption-window
  ;; wrapper never fires and the compiled tier emits NO :rf.ssr/hydration-mismatch.
  ;; This is intrinsic to React-native adoption (the compiled tier carries no
  ;; structural hash), NOT a re-frame2 bug — a stale attribute is a real,
  ;; deterministic bug the framework simply does not TRACE on this tier. The pin:
  ;; were React to escalate attribute mismatches to `onRecoverableError`, or were
  ;; the tier to grow a structural hash, this vertical would red.
  (when (browser?)
    (async done
      (let [act-prev  (disable-act-env!)
            root-id   ::attr-only-root
            host      (plant-host! {:root-id root-id :body attr-divergent-body})
            root-atom (atom nil)
            mismatches (atom [])
            flips      (atom [])
            flip-witness (op-for-root :rf.ssr/phase-flip root-id)
            stop-mm   (with-trace-listener! mismatches (op= :rf.ssr/hydration-mismatch))
            stop-fl   (with-trace-listener! flips flip-witness)]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        (ssr/hydrate! {:frame    app-frame
                       :payload  (payload)
                       :manifest (manifest-for root-id "adopt-container")
                       :root-id  root-id})
        (reset! root-atom
                (ui/hydrate-root (container host "adopt-container")
                                 [ui/frame-provider {:frame app-frame} [adopt-root]]))
        ;; Wait on the FLIP — it fires strictly AFTER the hydration commit, so by
        ;; the time it lands the adoption window has closed and any recoverable
        ;; error would already have surfaced. A clean flip therefore proves the
        ;; root hydrated AND that the attribute divergence raised nothing.
        (wait-for
         #(seq @flips)
         (fn []
           (try
             (testing "an attribute-only divergence surfaces NO framework mismatch trace"
               (is (empty? @mismatches)
                   (str "React-native adoption does not report attribute-only "
                        "mismatches — a stale className takes React's dev-only "
                        "warning path, not onRecoverableError, so the compiled "
                        "tier emits no :rf.ssr/hydration-mismatch. Saw: "
                        (pr-str @mismatches))))
             (testing "the root still hydrated and completed its single flip"
               (is (= 1 (count @flips))
                   "the root adopted the server DOM and flipped once — hydration was not aborted")
               (is (some? (.querySelector host "button.live"))
                   "the client-only site swapped to its client subtree after the flip"))
             (finally
               (stop-mm) (stop-fl)
               (teardown! host root-atom)
               (restore-act-env! act-prev)
               (done)))))))))

;; ---------------------------------------------------------------------------
;; 3. Non-hydrating mount — direct :client, no wrapper, no flip
;; ---------------------------------------------------------------------------

(deftest non-hydrating-mount-is-direct-client-no-wrapper-no-flip
  (when (browser?)
    (async done
      (let [act-prev  (disable-act-env!)
            ;; A bare container — no server markup, no manifest: a non-hydrating
            ;; mount does not adopt anything.
            host      (.createElement js/document "div")
            _         (set! (.-innerHTML host) "<div id=\"mount-container\"></div>")
            _         (.appendChild (.-body js/document) host)
            root-atom (atom nil)
            mismatches (atom [])
            flips      (atom [])
            stop-mm   (with-trace-listener! mismatches (op= :rf.ssr/hydration-mismatch))
            stop-fl   (with-trace-listener! flips (op= :rf.ssr/phase-flip))]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        (reset! root-atom
                (ui/mount [ui/frame-provider {:frame app-frame} [adopt-root]]
                          (container host "mount-container")
                          {:root-id ::direct-mount}))
        (wait-for
         #(some? (.querySelector host "button.live"))
         (fn []
           (try
             (testing "the non-hydrating mount is born :client — client subtree renders directly"
               (is (some? (.querySelector host "button.live"))
                   "first client-only site rendered its CLIENT subtree on the first render")
               (is (some? (.querySelector host "button.live2"))
                   "second site likewise — direct :client")
               (is (nil? (.querySelector host "p.fb"))
                   "no fallback pass — nothing to flip away from")
               (is (nil? (.querySelector host "p.fb2"))
                   "no second fallback either"))
             (testing "no phase flip and no adoption wrapper — non-hydrating installs neither"
               (is (empty? @flips)
                   "a non-hydrating mount never flips")
               (is (empty? @mismatches)
                   "and never surfaces an adoption-window mismatch (no wrapper installed)"))
             (finally
               (stop-mm) (stop-fl)
               (teardown! host root-atom)
               (restore-act-env! act-prev)
               (done)))))))))
