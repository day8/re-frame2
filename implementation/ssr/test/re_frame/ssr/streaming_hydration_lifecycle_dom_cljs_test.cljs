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
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent2.core :as r2]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.suspense :as suspense :refer [boundary]]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.install :as install]
            [re-frame.ssr.streaming.constants :as wire]
            [re-frame.ssr.streaming.client :as streaming-client]
            [re-frame.test-support :as test-support]
            ;; Publishes the React-context tier `frame-provider` resolves
            ;; through. Without it the provider renders nothing and every
            ;; assertion below would read an empty container.
            [re-frame.views]))

;; `installed-payloads` is a process-global `defonce` ledger keyed by
;; payload id — a table `clear-all!` and a `frame/frames` reset do NOT
;; touch. Any suite that installs a payload must reset it or it leaks
;; into siblings. These tests hydrate through `ssr/hydrate!` rather than
;; the root-manifest install path, but the reset is cheap and keeps the
;; suite honest if that ever changes.
;; `re-frame.ssr.suspense`'s failed-boundary record is a second such
;; table — process-global, additive, and read by every `boundary` render
;; on the page. A test that streams a failure MUST clear it.
(use-fixtures :each
  {:before (fn []
             (install/reset-installed-payloads!)
             (suspense/reset-failed-boundaries!))}
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
  (rf/reg-event :test/seed (fn [_ _] {:db {:cards {:revenue {:title "Revenue" :value 42375}}}}))
  nil)

;; `reg-view`, not plain fns: a registered view is what carries the frame
;; stamp down from the enclosing `frame-provider` (Spec 006 §Lookup
;; algorithm — the React-context tier resolves through the reg-view
;; wrapper). A plain fn that subscribes raises `:rf.error/no-frame-context`
;; under a provider, which is exactly what the first draft of this suite
;; did. The `boundary` component itself stays a plain fn and needs no
;; wrapper: it runs inside the enclosing registered view's scope.
(rf/reg-view ^{:rf/id :test.dash/card-skeleton} card-skeleton [card-id]
  [:div.card.skeleton [:h3 (str "Loading " (name card-id))]])

(rf/reg-view ^{:rf/id :test.dash/card} card-view [card-id]
  (let [c @(rf/subscribe [:card/by-id card-id])]
    [:div.card
     [:h3 (:title c)]
     [:p.value (str (:value c))]]))

;; The card that fails on purpose. It is only ever INVOKED on the server,
;; inside its continuation drain — the client's boundary short-circuits to
;; the declared fallback for a failed id and never calls it. That is the
;; whole point: one tree, and the failure is a server-side fact the client
;; is told about rather than something it has to re-discover.
(rf/reg-view ^{:rf/id :test.dash/throwing-card} throwing-card []
  (throw (ex-info "flaky third-party metric service" {})))

;; The CLIENT tree — what the browser renders. A plain fn, deliberately:
;; `reg-view` stamps dev-time `data-rf-view` / `data-rf2-source-coord`
;; attributes on its root element, and the two trees below must agree on
;; `<main>` exactly. The leaves ARE reg-views (they subscribe, and they
;; carry the frame), and the emitter stamps them identically on both
;; hosts — that parity is what `source_coord_parity_test` pins.
(defn- dashboard []
  [:main.dashboard
   [:section.cards
    [boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
     [card-view :revenue]]
    [boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
     [throwing-card]]]])

;; The SERVER tree — the same page, spelled with the internal
;; `:rf/suspense-boundary` wire marker that `boundary`'s `:clj` branch
;; expands to. It has to be written out here because this test runs on
;; CLJS, where the component takes its CLIENT branch and so can never
;; produce the marker: the shell walker would walk straight into the
;; throwing body. That the two spellings correspond is not assumed —
;; `streaming_component_cljs_test/component-expands-to-the-internal-wire-marker`
;; pins the expansion on the JVM, where it actually happens.
(defn- server-dashboard []
  [:main.dashboard
   [:section.cards
    [:rf/suspense-boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
     [card-view :revenue]]
    [:rf/suspense-boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
     [throwing-card]]]])

;; ---- the wire (EMITTED, not transcribed) ----------------------------------
;;
;; The server HTML below is produced by the SHIPPED emitter
;; (`ssr/render-to-string`, which is `.cljc` and runs here) over the SAME
;; hiccup the client tree renders, then wrapped in the chunk shapes the
;; Ring writer thread flushes (`ssr/streaming-{fallback,resolved,failed}-
;; template`, `ssr/streaming-hydrate-delta-script`).
;;
;; Hand-transcribing this markup was a mistake worth recording: a
;; `reg-view` root carries dev-time `data-rf-view` / `data-rf2-source-coord`
;; attributes that the emitter writes and a hand-written fixture cannot
;; know (the coord embeds a LINE NUMBER). Hydration then failed on
;; attributes rather than on anything the feature does. Emitting the
;; fixture makes the test a genuine cross-host parity check: same hiccup,
;; server render vs client render, reconciled by React.

(defn- server-render!
  "Run the REAL server pipeline over `[dashboard]` under a server frame:
  the shell walker registers a continuation per boundary, then each is
  drained. Returns the chunk bytes plus the failed set the drain
  reported — everything a streaming host would flush, produced by the
  shipped code rather than transcribed.

  Both halves are `.cljc`, so this runs here exactly as it does on the
  JVM. The server frame is destroyed before returning; only bytes escape."
  []
  (let [fid :test/server-render]
    (rf/make-frame {:id fid :platform :server})
    (rf/dispatch-sync [:test/seed] {:frame fid})
    (let [{:keys [shell-html continuations]}
          (rf/with-frame fid (ssr/streaming-render-shell [server-dashboard]))
          outcomes (mapv #(rf/with-frame fid (ssr/streaming-render-continuation fid %))
                         continuations)
          chunks   (mapv (fn [{:keys [id html delta failed?]}]
                           (str (if failed?
                                  (ssr/streaming-failed-template id html)
                                  (ssr/streaming-resolved-template id html))
                                (when (and (not failed?) (some? delta))
                                  (ssr/streaming-hydrate-delta-script id (pr-str delta)))))
                         outcomes)
          failed   (into #{} (comp (filter :failed?) (map :id)) outcomes)]
      (rf/destroy-frame! fid)
      {:shell shell-html :chunks chunks :failed failed
       :ids (mapv :id outcomes)})))

(defn- finalised-html
  "What the equivalent NON-streamed render produces for the same page:
  the resolved card, and the failed boundary's declared fallback. This is
  the shape the finalised streamed DOM must equal."
  []
  (let [fid :test/expected]
    (rf/make-frame {:id fid :platform :server})
    (rf/dispatch-sync [:test/seed] {:frame fid})
    (suspense/record-failed-boundaries! #{:card.flaky})
    (let [html (rf/with-frame fid (ssr/render-to-string [dashboard] nil))]
      (rf/destroy-frame! fid)
      html)))

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
                        (assoc-in {} suspense/failed-boundaries-path (set failed)))))
       "</script>"))

;; ---- DOM scaffolding -------------------------------------------------------

(defn- make-host!
  "A `#app` host seeded with the server's SHELL — the first bytes a
  streamed page delivers."
  [shell]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host) (str "<div id=\"app\">" shell "</div>"))
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

(defn- get-act
  "React's `act` — the only way to make a concurrent root commit
  SYNCHRONOUSLY. Without it `hydrateRoot` returns before React has
  touched the DOM and every assertion races the scheduler."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils"))
           (catch :default _ nil))))

(defn- hydrate-capturing!
  "Hydrate `container` against `tree` with REAL React and report
  everything React said about it.

  Returns `{:complaints [str …] :html \"…\"}`.

  Two deliberate choices:

    - React's `hydrateRoot` is called DIRECTLY, with an options object we
      construct here, so nothing between us and React can drop
      `onRecoverableError`. (`r2/as-element` only builds the element
      tree; it never sees the options.)
    - React ALSO reports mismatches through `console.error`, so both
      `error` and `warn` are captured for the duration and restored
      after — a mismatch that arrives on the console rather than the
      callback is still heard.

  The hydrate runs inside `act` so the commit completes before we read
  the DOM."
  [container tree]
  (let [complaints (atom [])
        orig-error (.-error js/console)
        orig-warn  (.-warn js/console)
        record!    (fn [& args]
                     (swap! complaints conj (str/join " " (map #(str %) args))))
        act-fn     (get-act)
        root       (atom nil)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (set! (.-error js/console) record!)
    (set! (.-warn js/console) record!)
    (try
      (act-fn
        (fn []
          (reset! root
                  (react-dom-client/hydrateRoot
                    container
                    (r2/as-element tree)
                    #js {:onRecoverableError
                         (fn [err _info] (record! "onRecoverableError" err))}))))
      (let [html (.-innerHTML container)]
        (act-fn (fn [] (some-> @root .unmount)))
        {:complaints @complaints :html html})
      (finally
        (set! (.-error js/console) orig-error)
        (set! (.-warn js/console) orig-warn)))))

(defn- hydration-complaints
  "Only the complaints that are about HYDRATION. React emits unrelated
  development warnings, and counting those would make the green case
  flaky and the red case dishonest."
  [complaints]
  (filterv #(let [s (str/lower-case %)]
              (or (str/includes? s "hydrat")
                  (str/includes? s "did not match")
                  (str/includes? s "didn't match")
                  (str/includes? s "server rendered")))
           complaints))

;; ---- tests -----------------------------------------------------------------

(deftest harness-captures-a-known-bad-hydration
  (testing "the harness reds on deliberately-wrong server HTML — without
            this, every no-mismatch assertion below would be vacuous"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [host (.createElement js/document "div")]
        (.appendChild (.-body js/document) host)
        ;; Server painted a <span>; the client tree says <div>. React must
        ;; report a recoverable hydration error.
        (set! (.-innerHTML host) "<span>server</span>")
        (let [{:keys [complaints]} (hydrate-capturing! host [:div "client"])]
          (is (seq (hydration-complaints complaints))
              (str "EXPECTED the harness to capture a hydration mismatch on "
                   "known-bad HTML; captured: " (pr-str complaints) ". "
                   "A harness that captures nothing here proves nothing anywhere."))
          (remove-host! host))))))

(deftest streamed-page-hydrates-without-structural-mismatch
  (testing "rf2-o4rbh: a genuinely staggered stream, finalised, hydrates
            with ONE ordinary whole-root hydration and no mismatch"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/streamed
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            ;; The REAL server pipeline: shell walk + continuation drain.
            {:keys [shell chunks failed ids]} (server-render!)
            host     (make-host! shell)
            ready    (atom nil)]
        (is (= [:card.revenue :card.flaky] ids)
            "the shell walk registered both boundaries in document order")
        (is (= #{:card.flaky} failed)
            "the drain reported the throwing boundary as failed")
        (streaming-client/install!
          {:frame frame-id :root host :on-ready #(reset! ready %)})
        ;; Fallbacks are now LIVE mounts — the page paints its skeletons.
        (is (= 2 (count (mounts host)))
            "install materialises each inert fallback template into a visible mount")
        (is (nil? @ready) "not ready until the final payload lands")
        ;; Stagger the stream: the resolved chunk, then the failed chunk,
        ;; then the payload — each in its own observer batch.
        (append-chunk! host (first chunks))
        (async done
          (js/setTimeout
            (fn []
              (append-chunk! host (second chunks))
              (js/setTimeout
                (fn []
                  (append-chunk! host (payload-chunk failed))
                  (js/setTimeout
                    ;; Guarded: a throw inside a `setTimeout` callback is an
                    ;; UNCAUGHT page error that aborts the whole browser
                    ;; suite rather than failing this test. Surface it as a
                    ;; normal failure and let the run continue.
                    (fn []
                     (try
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
                      ;; The public boot path: read `__rf_payload`, validate
                      ;; it, dispatch `:rf/hydrate` into the explicit frame.
                      ;; State only — it never touches the DOM.
                      (is (some? (ssr/hydrate! {:frame frame-id}))
                          "the final payload is readable and hydrates the frame")
                      (let [tree [rf/frame-provider {:frame frame-id} [dashboard]]
                            {:keys [complaints html]} (hydrate-capturing! (app-el host) tree)]
                        (is (empty? (hydration-complaints complaints))
                            (str "hydrateRoot must reconcile the finalised streamed DOM "
                                 "with no structural mismatch; got: " (pr-str complaints)))
                        ;; NON-VACUITY: a client tree that rendered NOTHING
                        ;; would ALSO report no mismatch — React would simply
                        ;; drop the server DOM and be done. Assert the content
                        ;; SURVIVED hydration; that is what proves the two
                        ;; trees actually agreed. (This assertion caught the
                        ;; first version of this suite rendering an empty
                        ;; tree and passing.)
                        (is (str/includes? html "42375")
                            "the resolved card survives hydration — React adopted the server DOM rather than discarding it")
                        (is (str/includes? html "Loading flaky")
                            "the failed boundary's declared fallback survives hydration")
                        (is (not (str/includes? html "rf-suspense"))
                            "no protocol DOM in the hydrated tree"))
                      (catch :default t
                        (is false (str "threw during finalization/hydration: " t)))
                      (finally
                        (remove-host! host)
                        (done))))
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
        (rf/dispatch-sync
          [:rf/hydrate {:rf/version 1
                        :rf/app-db  {:cards {:revenue {:title "Revenue" :value 42375}}}
                        :rf/runtime-db (assoc-in {} suspense/failed-boundaries-path
                                                 #{:card.flaky})}]
          {:frame frame-id})
        (is (= #{:card.flaky} (suspense/frame-failed-boundaries frame-id))
            "the DURABLE set survives the hydration round-trip into runtime-db")
        ;; The RENDER-TIME record — what the component consults. On a real
        ;; page the streaming client writes this at finalization; here we
        ;; stand in for it directly.
        (suspense/record-failed-boundaries! #{:card.flaky})
        ;; Hydrate the SAME tree against the markup the finalised stream
        ;; leaves behind — which is, by construction, what the equivalent
        ;; non-streamed render of the same tree produces.
        (let [host (.createElement js/document "div")]
          (.appendChild (.-body js/document) host)
          (set! (.-innerHTML host) (finalised-html))
          (let [tree [rf/frame-provider {:frame frame-id} [dashboard]]
                {:keys [complaints html]} (hydrate-capturing! host tree)]
            (is (str/includes? html "Loading flaky")
                "the FAILED boundary renders its declared fallback")
            (is (str/includes? html "42375")
                "the resolved boundary renders its body")
            (is (not (str/includes? html "suspense-boundary"))
                "no phantom <suspense-boundary> element — the component is not a keyword head")
            (is (not (str/includes? html "rf-suspense"))
                "the component renders no protocol DOM of its own")
            (is (empty? (hydration-complaints complaints))
                (str "the component's client render matches the markup the "
                     "server painted for a failed boundary; got: " (pr-str complaints)))
            (remove-host! host)))))))

(deftest finalization-unwraps-mounts-preserving-children
  (testing "unwrapping splices the mount's children into its position,
            leaving the DOM the equivalent non-streamed render produces"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/unwrap
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            {:keys [shell chunks failed]} (server-render!)
            host     (make-host! shell)]
        (streaming-client/install! {:frame frame-id :root host})
        (doseq [c chunks] (append-chunk! host c))
        (async done
          (js/setTimeout
            (fn []
              (append-chunk! host (payload-chunk failed))
              (js/setTimeout
                (fn []
                  ;; The whole claim of the lifecycle, as one equality: a
                  ;; streamed page, finalised, is byte-identical to the
                  ;; equivalent non-streamed render of the same tree. That
                  ;; is what makes ONE ordinary hydration correct.
                  (is (= (normalise-html (finalised-html))
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
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            {:keys [shell chunks failed]} (server-render!)
            host     (make-host! shell)
            calls    (atom 0)]
        ;; Everything arrived before the bundle booted.
        (doseq [c chunks] (append-chunk! host c))
        (append-chunk! host (payload-chunk failed))
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
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            {:keys [shell chunks]} (server-render!)
            host     (make-host! shell)
            ready    (atom false)]
        (streaming-client/install!
          {:frame frame-id :root host :on-ready (fn [_] (reset! ready true))})
        (append-chunk! host (first chunks))
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
