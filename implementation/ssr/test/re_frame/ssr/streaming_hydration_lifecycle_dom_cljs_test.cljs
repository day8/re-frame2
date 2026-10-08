(ns re-frame.ssr.streaming-hydration-lifecycle-dom-cljs-test
  "The streaming READINESS + HYDRATION lifecycle and the cross-host suspense
  `boundary` component (Spec 011 §Streaming SSR — client-side hydration).

  `install!` turns each boundary's inert fallback `<template>` into a live
  `<rf-suspense data-rf2-suspense-mount>` wrapper, which no render tree can
  express. So finalization must unwrap every mount before hydration, and the
  `boundary` component must render, on the client, exactly the markup the
  server streamed — its body, or its declared fallback for a failed boundary.

  These tests call React's `hydrateRoot` directly with their own
  `onRecoverableError`, so no wrapper can drop it, and
  `harness-captures-a-known-bad-hydration` proves the harness reds on a real
  mismatch. `:node-test` loads this file too and every test exits early
  without `js/document`."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent2.core :as r2]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.suspense :as rf.ssr.suspense :refer [boundary]]
            [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.streaming.constants :as rf.ssr.streaming.constants]
            [re-frame.ssr.streaming.client :as rf.ssr.streaming.client]
            [re-frame.test-support :as rf.test-support]
            ;; Publishes the React-context tier `frame-provider` resolves through.
            [re-frame.views]))

;; The install ledger and the failed-boundary record are process-global.
(use-fixtures :each
  {:before (fn []
             (rf.ssr.install/reset-installed-payloads!)
             (rf.ssr.suspense/reset-failed-boundaries!))}
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the application under test: ONE tree, both hosts --------------------

(defn- register-app! [_frame-id]
  (rf/reg-sub :card/by-id (fn [db [_ id]] (get-in db [:cards id])))
  (rf/reg-event :test/seed (fn [_ _] {:db {:cards {:revenue {:title "Revenue" :value 42375}}}}))
  nil)

;; Registered views carry the frame down from `frame-provider`.
(rf/reg-view ^{:rf/id :test.dash/card-skeleton} card-skeleton [card-id]
  [:div.card.skeleton [:h3 (str "Loading " (name card-id))]])

(rf/reg-view ^{:rf/id :test.dash/card} card-view [card-id]
  (let [c @(rf/subscribe [:card/by-id card-id])]
    [:div.card
     [:h3 (:title c)]
     [:p.value (str (:value c))]]))

;; Only ever invoked on the server: the client boundary renders the declared
;; fallback for a failed id instead.
(rf/reg-view ^{:rf/id :test.dash/throwing-card} throwing-card []
  (throw (ex-info "flaky third-party metric service" {})))

;; The client tree. A plain fn, so its root carries no dev-time view attributes.
(defn- dashboard []
  [:main.dashboard
   [:section.cards
    [boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
     [card-view :revenue]]
    [boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
     [throwing-card]]]])

;; The same page spelled with the `:rf/suspense-boundary` wire marker that
;; `boundary` expands to on the JVM; on CLJS the component takes its client
;; branch, so the server render needs the marker written out.
(defn- server-dashboard []
  [:main.dashboard
   [:section.cards
    [:rf/suspense-boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
     [card-view :revenue]]
    [:rf/suspense-boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
     [throwing-card]]]])

;; ---- the wire: emitted by the shipped server pipeline, not transcribed -----

(defn- server-render!
  "Shell walk + continuation drain over the server tree, on a server frame
  destroyed before returning. Returns the shell, the chunk bytes and the
  failed set."
  []
  (let [fid :test/server-render]
    (rf/make-frame {:id fid :platform :server})
    (rf/dispatch-sync [:test/seed] {:frame fid})
    (let [{:keys [shell-html continuations]}
          (rf/with-frame fid (rf.ssr/streaming-render-shell [server-dashboard]))
          outcomes (mapv #(rf/with-frame fid (rf.ssr/streaming-render-continuation fid %))
                         continuations)
          chunks   (mapv (fn [{:keys [id html delta failed?]}]
                           (str (if failed?
                                  (rf.ssr/streaming-failed-template id html)
                                  (rf.ssr/streaming-resolved-template id html))
                                (when (and (not failed?) (some? delta))
                                  (rf.ssr/streaming-hydrate-delta-script id (pr-str delta)))))
                         outcomes)
          failed   (into #{} (comp (filter :failed?) (map :id)) outcomes)]
      (rf/destroy-frame! fid)
      {:shell shell-html :chunks chunks :failed failed})))

(defn- finalised-html
  "The equivalent NON-streamed render of the page: the shape a finalised
  streamed DOM must equal."
  []
  (let [fid :test/expected]
    (rf/make-frame {:id fid :platform :server})
    (rf/dispatch-sync [:test/seed] {:frame fid})
    (rf.ssr.suspense/record-failed-boundaries! #{:card.flaky})
    (let [html (rf/with-frame fid (rf.ssr/render-to-string [dashboard] nil))]
      (rf/destroy-frame! fid)
      html)))

(defn- payload-chunk
  "The final `__rf_payload`, its runtime-db slice carrying the failed set."
  [failed]
  (str "<script id=\"" rf.ssr.constants/payload-script-id "\" type=\"application/edn\">"
       (pr-str (cond-> {:rf/version   1
                        :rf/app-db    {:cards {:revenue {:title "Revenue" :value 42375}}}
                        :rf/render-hash "00000000"}
                 (seq failed)
                 (assoc :rf/runtime-db
                        (assoc-in {} rf.ssr.suspense/failed-boundaries-path (set failed)))))
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
  (array-seq (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-mount "]"))))

(defn- normalise-html
  "Collapse insignificant whitespace so a structural comparison is not
  defeated by formatting."
  [s]
  (-> s (str/replace #">\s+<" "><") str/trim))

;; ---- the hydration harness -------------------------------------------------

(defn- get-act
  "React's `act`, which makes a concurrent root commit synchronously."
  []
  (when (exists? (.-act React)) (.-act React)))

(defn- hydrate-capturing!
  "Hydrate `container` against `tree` with real React inside `act`,
  returning `{:complaints [str …] :html \"…\"}` — everything React reported
  through `onRecoverableError`, `console.error` or `console.warn`."
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
  "Only the complaints about hydration; React also emits unrelated warnings."
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
  (testing "A genuinely staggered stream, finalised, hydrates
            with ONE ordinary whole-root hydration and no mismatch"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/streamed
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            {:keys [shell chunks failed]} (server-render!)
            host     (make-host! shell)
            ready    (atom nil)]
        (rf.ssr.streaming.client/install!
          {:frame frame-id :root host :on-ready #(reset! ready %)})
        (is (= 2 (count (mounts host)))
            "install materialises each inert fallback template into a visible mount")
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
                    ;; A throw in a `setTimeout` callback would abort the
                    ;; whole browser suite; report it as a failure instead.
                    (fn []
                     (try
                      (is (= {:resolved #{:card.revenue} :failed #{:card.flaky}}
                             (select-keys @ready [:resolved :failed])))
                      (is (zero? (count (array-seq (.querySelectorAll host "template[data-rf2-suspense-id]"))))
                          "no suspense <template> survives finalization")
                      (rf.ssr/hydrate! {:frame frame-id})
                      (let [tree [rf/frame-provider {:frame frame-id} [dashboard]]
                            {:keys [complaints html]} (hydrate-capturing! (app-el host) tree)]
                        (is (empty? (hydration-complaints complaints))
                            (str "hydrateRoot must reconcile the finalised streamed DOM "
                                 "with no structural mismatch; got: " (pr-str complaints)))
                        ;; A client tree that rendered nothing would also
                        ;; report no mismatch: the content must survive.
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
        (rf.ssr.streaming.client/install! {:frame frame-id :root host})
        (doseq [c chunks] (append-chunk! host c))
        (async done
          (js/setTimeout
            (fn []
              (append-chunk! host (payload-chunk failed))
              (js/setTimeout
                (fn []
                  ;; The whole claim of the lifecycle, as one equality.
                  (is (= (normalise-html (finalised-html))
                         (normalise-html (.-innerHTML (app-el host))))
                      "the finalised DOM is exactly the non-streamed render of the same tree")
                  (remove-host! host)
                  (done))
                60))
            60))))))

;; ---- the `install!` Usage recipe, executed ----------------------------------

(defn- recipe-bootstrap!
  "`install!`'s Usage recipe on this fixture: everything the callback touches
  is bound before `install!` (readiness can fire inside it), and the frame is
  seeded and the root hydrated only from `:on-ready`, each readiness appending
  one entry to `log`."
  [frame-id host log]
  (let [container (app-el host)]
    (rf.ssr.streaming.client/install!
      {:frame    frame-id
       :root     host
       :on-ready (fn [_report]
                   (try
                     (let [wrappers-at-ready (count (mounts host))
                           payload           (rf.ssr/hydrate! {:frame frame-id})]
                       (swap! log conj
                              {:wrappers-at-ready wrappers-at-ready
                               :payload           payload
                               :hydration         (hydrate-capturing!
                                                    container
                                                    [rf/frame-provider {:frame frame-id}
                                                     [dashboard]])}))
                     (catch :default t
                       (swap! log conj {:error t}))))})))

(defn- assert-recipe-booted-once! [log]
  (is (= 1 (count @log)) "exactly one seed + hydration, and it came from readiness")
  (let [{:keys [error wrappers-at-ready payload hydration]} (first @log)]
    (is (nil? error) (str "the recipe's :on-ready did not throw: " error))
    (is (zero? wrappers-at-ready)
        "no <rf-suspense> wrapper remained when the recipe took ownership")
    (is (some? payload) "hydrate! read and installed the final payload")
    (is (empty? (hydration-complaints (:complaints hydration)))
        (str "hydrate-root reconciled the finalised DOM with no mismatch; got: "
             (pr-str (:complaints hydration))))
    (is (str/includes? (str (:html hydration)) "42375")
        "React adopted the server DOM rather than discarding it")))

(deftest the-install-recipe-boots-once-from-readiness-on-a-live-stream
  (testing "With the payload still in flight the recipe owns
            nothing; once it lands, exactly one seed + hydration runs, after
            every wrapper is gone"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/recipe-live
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            {:keys [shell chunks failed]} (server-render!)
            host     (make-host! shell)
            log      (atom [])]
        (recipe-bootstrap! frame-id host log)
        (append-chunk! host (first chunks))
        (async done
          (js/setTimeout
            (fn []
              (is (empty? @log)
                  "nothing seeds or hydrates while the payload is still in flight")
              (append-chunk! host (second chunks))
              (append-chunk! host (payload-chunk failed))
              (js/setTimeout
                (fn []
                  (try
                    (assert-recipe-booted-once! log)
                    (catch :default t
                      (is false (str "threw asserting the recipe: " t)))
                    (finally
                      (remove-host! host)
                      (done))))
                40))
            40))))))

(deftest the-install-recipe-boots-once-on-an-already-buffered-response
  (testing "A fully buffered response boots the recipe
            synchronously inside install!, once, without a later tick"
    (if-not (browser?)
      (is true "skipped under node — no js/document")
      (let [frame-id :test/recipe-buffered
            _        (do (rf/make-frame {:id frame-id :platform :client})
                         (register-app! frame-id))
            {:keys [shell chunks failed]} (server-render!)
            host     (make-host! shell)
            log      (atom [])]
        (doseq [c chunks] (append-chunk! host c))
        (append-chunk! host (payload-chunk failed))
        (recipe-bootstrap! frame-id host log)
        (assert-recipe-booted-once! log)
        (async done
          (js/setTimeout
            (fn []
              (is (= 1 (count @log)) "a later tick does not boot it a second time")
              (remove-host! host)
              (done))
            60))))))
