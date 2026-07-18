(ns re-frame.ssr.streaming-hydration-lifecycle-dom-cljs-test
  "Acceptance coverage for the streaming READINESS + HYDRATION lifecycle
  and the cross-host suspense COMPONENT (rf2-ycz3k, closing rf2-j81hs
  SS2 + SS3). Per Spec 011 §Streaming SSR — client-side hydration
  semantics.

  ## The gap these tests close

  rf2-o4rbh measured it by running it. On a genuinely streamed page
  `install!` materialises each boundary's inert fallback `<template>`
  into a LIVE `<rf-suspense data-rf2-suspense-mount>` wrapper — the
  visible skeleton and the stable swap target. That wrapper is
  client-invented protocol DOM: no render tree on any host can express
  it. So the DOM at hydration time was

      <section class=\"cards\"><rf-suspense …><div class=\"card\">…

  while the client tree the author wrote describes

      <section class=\"cards\"><div class=\"card\">…

  and `hydrateRoot` saw a structural mismatch at every boundary, on a
  page whose content was otherwise byte-correct. Spec 011 never said
  what a client tree should contain at a boundary, which is why the
  shipped example had nothing correct to copy.

  The fix is two halves, and both are exercised here:

    - FINALIZATION unwraps every mount before hydration, so the DOM is
      exactly the tree the author's hiccup describes.
    - The `boundary` COMPONENT is that hiccup — one `.cljc` form that
      expands to the wire marker on the server and renders its body (or
      its declared fallback, for a failed boundary) on the client.

  ## Harness honesty — we drive React's `hydrateRoot` DIRECTLY

  Hydration mismatches surface through React's `onRecoverableError`
  callback, which must be passed to `hydrateRoot` in its options object.
  A harness built on an adapter wrapper can silently drop that option
  and then capture NOTHING — passing identically on broken and fixed
  HTML, certifying a fix it never observed. So these tests call
  `react-dom-client/hydrateRoot` themselves with their own options
  object, and `harness-captures-a-known-bad-hydration` feeds the harness
  deliberately-wrong HTML FIRST and asserts it reds. Every
  no-mismatch assertion below is only worth what that probe proves.

  Browser-only: `-dom-cljs-test$` opts this file into `:browser-test`;
  `:node-test` loads it too (its `cljs-test$` regexp matches both
  suffixes) and every DOM-dependent body gates on `(browser?)`, exiting
  early under Node where `js/document` is absent."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom/client" :as react-dom-client]
            [reagent2.core :as r2]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.boundary :as boundary :refer [boundary]]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.install :as install]
            [re-frame.ssr.streaming.constants :as wire]
            [re-frame.ssr.streaming.client :as streaming-client]
            [re-frame.test-support :as test-support]))

;; `installed-payloads` is a process-global `defonce` ledger keyed by
;; payload id — a table `clear-all!` and a `frame/frames` reset do NOT
;; touch. Any suite that installs a payload must reset it or it leaks
;; into siblings. These tests hydrate through `ssr/hydrate!` rather than
;; the root-manifest install path, but the reset is cheap and keeps the
;; suite honest if that ever changes.
(use-fixtures :each
  {:before (fn [] (install/reset-installed-payloads!))}
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the application under test --------------------------------------------
;;
;; ONE tree, both hosts. No reader conditional, no `card-slot` two-programs
;; split, and no defensive nil branch in the card view: the boundary that
;; declared the fallback is the one that re-renders it.

(defn- register-app! [_frame-id]
  (rf/reg-sub :card/by-id (fn [db [_ id]] (get-in db [:cards id])))
  nil)

(defn- card-skeleton [card-id]
  [:div.card.skeleton [:h3 (str "Loading " (name card-id))]])

(defn- card-view [card-id]
  (let [c @(rf/subscribe [:card/by-id card-id])]
    [:div.card
     [:h3 (:title c)]
     [:p.value (str (:value c))]]))

(defn- dashboard []
  [:main.dashboard
   [:section.cards
    [boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
     [card-view :revenue]]
    [boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
     [card-view :flaky]]]])

;; ---- the wire (authored through the SHIPPED server façade fns) -------------
;;
;; The JVM shell walker is `.cljc` but registry-and-frame bound, so the
;; chunk bytes here are assembled from the same façade fns the Ring writer
;; thread flushes — `ssr/streaming-{fallback,resolved,failed}-template` and
;; `ssr/streaming-hydrate-delta-script`. The HTML they produce is pinned
;; against the real JVM emitter by the cross-host suite
;; (`streaming_component_cljs_test`) and the JVM streaming tests.

(def ^:private revenue-resolved-html
  "<div class=\"card\"><h3>Revenue</h3><p class=\"value\">42375</p></div>")

(def ^:private flaky-fallback-html
  "<div class=\"card skeleton\"><h3>Loading flaky</h3></div>")

(def ^:private revenue-fallback-html
  "<div class=\"card skeleton\"><h3>Loading revenue</h3></div>")

(defn- shell-html []
  (str "<main class=\"dashboard\"><section class=\"cards\">"
       (ssr/streaming-fallback-template :card.revenue revenue-fallback-html)
       (ssr/streaming-fallback-template :card.flaky flaky-fallback-html)
       "</section></main>"))

(defn- revenue-chunk []
  (str (ssr/streaming-resolved-template :card.revenue revenue-resolved-html)
       (ssr/streaming-hydrate-delta-script
         :card.revenue (pr-str {:cards {:revenue {:title "Revenue" :value 42375}}}))))

(defn- flaky-chunk []
  ;; A FAILED continuation: fallback html, `data-rf2-suspense-failed`, no delta.
  (ssr/streaming-failed-template :card.flaky flaky-fallback-html))

(defn- payload-chunk
  "The final `__rf_payload`. Its runtime-db slice carries the
  failed-boundary set exactly as `build-final-payload` assembles it —
  the JVM half of that assembly is pinned in
  `streaming_component_cljs_test`."
  [failed]
  (str "<script id=\"" constants/payload-script-id "\" type=\"application/edn\">"
       (pr-str (cond-> {:rf/version   1
                        :rf/app-db    {:cards {:revenue {:title "Revenue" :value 42375}}}
                        :rf/render-hash "00000000"}
                 (seq failed)
                 (assoc :rf/runtime-db
                        (assoc-in {} boundary/failed-boundaries-path (set failed)))))
       "</script>"))

;; ---- DOM scaffolding -------------------------------------------------------

(defn- make-host! []
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host) (str "<div id=\"app\">" (shell-html) "</div>"))
    (.appendChild (.-body js/document) host)
    host))

(defn- app-el [host] (.querySelector host "#app"))

(defn- append-chunk! [host chunk-html]
  (let [parser (.createElement js/document "template")]
    (set! (.-innerHTML parser) chunk-html)
    (.appendChild host (.-content parser))))

(defn- remove-host! [host]
  (when-let [p (.-parentNode host)]
    (.removeChild p host)))

(defn- mounts [host]
  (array-seq (.querySelectorAll host (str "[" wire/attr-suspense-mount "]"))))

(defn- normalise-html
  "Collapse insignificant whitespace so a structural comparison is not
  defeated by formatting."
  [s]
  (-> s (str/replace #">\s+<" "><") str/trim))

;; ---- the hydration harness -------------------------------------------------

(defn- hydrate-capturing!
  "Hydrate `container` against `tree` by calling React's `hydrateRoot`
  DIRECTLY, with our own options object carrying `onRecoverableError`.

  Returns `{:root <React root> :errors (atom [...])}`. Every recoverable
  error React reports — hydration mismatches among them — lands in
  `:errors`. Nothing between us and React can drop the option, because
  we build the options object here.

  `r2/as-element` is used only to turn hiccup into a React element; it
  is not a hydration wrapper and never sees the options object."
  [container tree]
  (let [errors (atom [])
        root   (react-dom-client/hydrateRoot
                 container
                 (r2/as-element tree)
                 #js {:onRecoverableError (fn [err _info]
                                            (swap! errors conj (str (.-message err) "")))})]
    {:root root :errors errors}))

(defn- mismatch-errors
  "Only the hydration-mismatch reports. React phrases them variously
  across builds ('Hydration failed', 'did not match', 'server rendered
  HTML didn't match'), so match on the stable substrings rather than one
  exact sentence."
  [errors]
  (filterv #(or (str/includes? % "Hydration")
                (str/includes? % "hydration")
                (str/includes? % "did not match")
                (str/includes? % "didn't match"))
           @errors))

;; ---- tests -----------------------------------------------------------------

(deftest harness-captures-a-known-bad-hydration
  (testing "the harness reds on deliberately-wrong server HTML — without
            this, every no-mismatch assertion below would be vacuous"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [host      (.createElement js/document "div")
            _         (.appendChild (.-body js/document) host)
            ;; Server painted a <span>; the client tree says <div>. React
            ;; must report a recoverable hydration error.
            _         (set! (.-innerHTML host) "<span>server</span>")
            {:keys [errors root]} (hydrate-capturing! host [:div "client"])]
        (async done
          (js/setTimeout
            (fn []
              (is (seq (mismatch-errors errors))
                  (str "EXPECTED the harness to capture a hydration mismatch on "
                       "known-bad HTML; captured: " (pr-str @errors) ". "
                       "A harness that captures nothing here proves nothing anywhere."))
              (.unmount root)
              (remove-host! host)
              (done))
            60))))))

(deftest streamed-page-hydrates-without-structural-mismatch
  (testing "rf2-o4rbh: a genuinely staggered stream, finalised, hydrates
            with ONE ordinary whole-root hydration and no mismatch"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/streamed
            host     (make-host!)
            ready    (atom nil)]
        (rf/make-frame {:id frame-id :platform :client})
        (register-app! frame-id)
        (streaming-client/install!
          {:frame frame-id :root host :on-ready #(reset! ready %)})
        ;; Fallbacks are now LIVE mounts — the page paints its skeletons.
        (is (= 2 (count (mounts host)))
            "install materialises each inert fallback template into a visible mount")
        (is (nil? @ready) "not ready until the final payload lands")
        ;; Stagger the stream: one resolved chunk, one failed chunk, then
        ;; the payload — each in its own observer batch.
        (append-chunk! host (revenue-chunk))
        (async done
          (js/setTimeout
            (fn []
              (append-chunk! host (flaky-chunk))
              (js/setTimeout
                (fn []
                  (append-chunk! host (payload-chunk #{:card.flaky}))
                  (js/setTimeout
                    (fn []
                      ;; --- FINALIZATION ---
                      (is (some? @ready) ":on-ready fired when the payload landed")
                      (is (= #{:card.revenue} (:resolved @ready)))
                      (is (= #{:card.flaky} (:failed @ready)))
                      (is (zero? (count (mounts host)))
                          "every <rf-suspense> mount is unwrapped at readiness — protocol DOM is transport, never part of the application tree")
                      (is (zero? (count (array-seq (.querySelectorAll host "template[data-rf2-suspense-id]"))))
                          "no suspense <template> survives finalization")
                      ;; The resolved card is now a DIRECT child of <section>,
                      ;; exactly as the author's tree describes it.
                      (is (some? (.querySelector host "section.cards > div.card"))
                          "resolved content sits where the client tree puts it")
                      ;; --- HYDRATION ---
                      (rf/dispatch-sync [:rf/hydrate (ssr/read-server-payload)] {:frame frame-id})
                      (let [tree [rf/frame-provider {:frame frame-id} [dashboard]]
                            {:keys [errors root]} (hydrate-capturing! (app-el host) tree)]
                        (js/setTimeout
                          (fn []
                            (is (empty? (mismatch-errors errors))
                                (str "hydrateRoot must reconcile the finalised streamed DOM "
                                     "with no structural mismatch; got: " (pr-str @errors)))
                            (.unmount root)
                            (remove-host! host)
                            (done))
                          80)))
                    40))
                40))
            40))))))

(deftest failed-boundary-renders-its-declared-fallback
  (testing "the failed set rides the payload's runtime slice, so the
            client boundary re-renders the DECLARED fallback — the exact
            markup the failed chunk left in the DOM"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/failed-fallback]
        (rf/make-frame {:id frame-id :platform :client})
        (register-app! frame-id)
        ;; Seed the frame's runtime-db the way :rf/hydrate would.
        (rf/dispatch-sync
          [:rf/hydrate {:rf/version 1
                        :rf/app-db  {:cards {:revenue {:title "Revenue" :value 42375}}}
                        :rf/runtime-db (assoc-in {} boundary/failed-boundaries-path
                                                 #{:card.flaky})}]
          {:frame frame-id})
        (is (= #{:card.flaky} (boundary/failed-boundaries frame-id))
            "the failed set survives the hydration round-trip into runtime-db")
        (let [host (.createElement js/document "div")]
          (.appendChild (.-body js/document) host)
          (let [root (react-dom-client/createRoot host)]
            (.render root (r2/as-element
                            [rf/frame-provider {:frame frame-id} [dashboard]]))
            (async done
              (js/setTimeout
                (fn []
                  (let [html (.-innerHTML host)]
                    (is (str/includes? html "Loading flaky")
                        "the FAILED boundary renders its declared fallback")
                    (is (str/includes? html "42375")
                        "the resolved boundary renders its body")
                    (is (not (str/includes? html "suspense-boundary"))
                        "no phantom <suspense-boundary> element — the component is not a keyword head")
                    (is (not (str/includes? html "rf-suspense"))
                        "the component renders no protocol DOM of its own"))
                  (.unmount root)
                  (remove-host! host)
                  (done))
                60))))))))

(deftest finalization-unwraps-mounts-preserving-children
  (testing "unwrapping splices the mount's children into its position,
            leaving the DOM the equivalent non-streamed render produces"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/unwrap
            host     (make-host!)]
        (rf/make-frame {:id frame-id :platform :client})
        (register-app! frame-id)
        (streaming-client/install! {:frame frame-id :root host})
        (append-chunk! host (revenue-chunk))
        (append-chunk! host (flaky-chunk))
        (async done
          (js/setTimeout
            (fn []
              (append-chunk! host (payload-chunk #{:card.flaky}))
              (js/setTimeout
                (fn []
                  (is (= (normalise-html
                           (str "<main class=\"dashboard\"><section class=\"cards\">"
                                revenue-resolved-html
                                flaky-fallback-html
                                "</section></main>"))
                         (normalise-html (.-innerHTML (app-el host))))
                      "the finalised DOM is exactly the non-streamed render of the same tree")
                  (remove-host! host)
                  (done))
                60))
            60))))))

(deftest readiness-fires-once-on-an-already-complete-page
  (testing "a fully-buffered response (payload already present at install)
            still finalises and signals readiness — synchronously — so a
            readiness-driven bootstrap cannot hang"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/already-complete
            host     (make-host!)
            calls    (atom 0)]
        (rf/make-frame {:id frame-id :platform :client})
        (register-app! frame-id)
        ;; Everything arrived before the bundle booted.
        (append-chunk! host (revenue-chunk))
        (append-chunk! host (flaky-chunk))
        (append-chunk! host (payload-chunk #{:card.flaky}))
        (streaming-client/install!
          {:frame frame-id :root host :on-ready (fn [_] (swap! calls inc))})
        (is (= 1 @calls) ":on-ready fires synchronously during install!")
        (is (zero? (count (mounts host)))
            "mounts are unwrapped even on the already-complete path — otherwise the fast page is the unhydratable one")
        (async done
          (js/setTimeout
            (fn []
              (is (= 1 @calls) ":on-ready is once-only — a later mutation cannot re-fire it")
              (remove-host! host)
              (done))
            60))))))

(deftest no-mounts-and-no-readiness-before-the-payload
  (testing "readiness is the hydration trigger: before the payload lands
            the DOM still carries protocol wrappers, so a bootstrap that
            hydrated early would be hydrating the mismatched tree"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/pre-readiness
            host     (make-host!)
            ready    (atom false)]
        (rf/make-frame {:id frame-id :platform :client})
        (register-app! frame-id)
        (streaming-client/install!
          {:frame frame-id :root host :on-ready (fn [_] (reset! ready true))})
        (append-chunk! host (revenue-chunk))
        (async done
          (js/setTimeout
            (fn []
              (is (false? @ready) "no readiness before the final payload")
              (is (pos? (count (mounts host)))
                  "mounts are still present pre-readiness — this is precisely why hydration must wait")
              (is (some? (.querySelector host (str "[" wire/attr-suspense-mount "] > div.card")))
                  "the resolved card is nested inside its mount pre-readiness — the o4rbh mismatch shape")
              (remove-host! host)
              (done))
            60))))))
