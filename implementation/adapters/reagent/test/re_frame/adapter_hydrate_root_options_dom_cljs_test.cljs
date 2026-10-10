(ns re-frame.adapter-hydrate-root-options-dom-cljs-test
  "A hydrating `render!` through the Reagent adapter creates its React root
  with the options the hook spine's hydrating roots get: the composed
  `onRecoverableError` reporter bounded to the adoption window, and the
  `:identifier-prefix` as React's `identifierPrefix`.

  Real DOM, with act OFF, so React reports adoption errors on its own
  schedule. The views delegate (`[layout [page label]]`), because that is
  where the render-tree hash cannot see: it hashes every fn head to the same
  token, so a divergence below the root view reaches no re-frame channel
  unless the root reports it. The slim twin is
  `re-frame.adapter.reagent-slim-hydrate-root-options-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/server" :as react-dom-server]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.substrate.spine :as rf.substrate.spine]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Async rows need the map-form fixture. The trees are plain hiccup, so no
;; ambient frame.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- host!
  "A fresh container attached to the document (hydration needs a connected
  node), pre-filled with `server-html`."
  [server-html]
  (let [el (.createElement js/document "div")]
    (set! (.-innerHTML el) server-html)
    (.appendChild (.-body js/document) el)
    el))

(defn- drop-host! [el]
  (when-let [p (.-parentNode el)] (.removeChild p el)))

(defn- poll-until
  "Poll `pred` every 5ms for up to ~2s, then call `k`, so an outcome that
  never arrives fails its assertions rather than hanging the lane."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- host-root-fiber
  "The HostRoot fiber React records on a root's container, under its
  `__reactContainer$<id>` expando."
  [el]
  (some #(when (.startsWith % "__reactContainer$") (unchecked-get el %))
        (js/Object.keys el)))

(defn- fiber-root
  "The FiberRoot behind a container: the object holding the root options
  React was created with."
  [el]
  (some-> (host-root-fiber el) .-stateNode))

(defn- committed-top
  "The top fiber of the tree a root has COMMITTED, or nil before its first
  commit. Read through the FiberRoot, because the fiber on the container is
  the one the root started with, which is the alternate after a commit."
  [el]
  (some-> (fiber-root el) .-current .-child))

(defn- listen-for-mismatches!
  "Collect every `:rf.ssr/hydration-mismatch` trace into a fresh atom.
  Returns `[atom listener-id]`."
  []
  (let [seen (atom [])
        lk   (keyword (gensym "rf.hydrate-root-options-"))]
    (rf.trace.tooling/register-listener!
      lk (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                    (swap! seen conj ev))))
    [seen lk]))

(defn- act-off!
  "Leave the act environment so hydration runs on React's own schedule.
  Returns the restore thunk."
  []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (fn [] (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))))

;; ---- views ----------------------------------------------------------------

(defn- page [label] [:p.page label])
(defn- layout [child] [:main [:h1 "title"] child])
(defn- app [label] [layout [page label]])

(def ^:private clean-html
  "<main><h1>title</h1><p class=\"page\">same</p></main>")

;; ---- nested mismatches reach the composed reporter ------------------------

(deftest nested-mismatches-reach-the-composed-reporter
  (testing "a hydrating render! reports a nested text mismatch and a nested
            extra element as :rf.ssr/hydration-mismatch, and calls the host's
            :on-recoverable-error for each; a clean root reports nothing"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [restore-act        (act-off!)
              [mismatches lk]    (listen-for-mismatches!)
              text-host          (atom [])
              struct-host        (atom [])
              clean-host         (atom [])
              text-el            (host! "<main><h1>title</h1><p class=\"page\">server</p></main>")
              struct-el          (host! "<main><h1>title</h1><p class=\"page\">same</p><aside>extra</aside></main>")
              clean-el           (host! clean-html)
              handles            [(rf.adapter.reagent/client-root)
                                  (rf.adapter.reagent/client-root)
                                  (rf.adapter.reagent/client-root)]]
          (try
            (rf.adapter.reagent/render! (handles 0) [app "client"] text-el
                                        {:hydrate? true
                                         :on-recoverable-error (fn [e _] (swap! text-host conj e))})
            (rf.adapter.reagent/render! (handles 1) [app "same"] struct-el
                                        {:hydrate? true
                                         :on-recoverable-error (fn [e _] (swap! struct-host conj e))})
            (rf.adapter.reagent/render! (handles 2) [app "same"] clean-el
                                        {:hydrate? true
                                         :on-recoverable-error (fn [e _] (swap! clean-host conj e))})
            (catch :default e
              (is false (str "hydrating render! threw: " (pr-str e)))))
          (poll-until
            #(and (seq @text-host) (seq @struct-host))
            (fn []
              (try
                (is (seq @text-host)
                    "the host :on-recoverable-error was called for the nested text mismatch")
                (is (seq @struct-host)
                    "the host :on-recoverable-error was called for the nested extra element")
                (is (empty? @clean-host)
                    "a root whose server markup matches reports nothing")
                (is (and (<= 2 (count @mismatches))
                         (= (count @mismatches) (+ (count @text-host) (count @struct-host))))
                    (str "each adoption error emitted one :rf.ssr/hydration-mismatch before "
                         "reaching the host. Saw: " (pr-str @mismatches)))
                (is (every? #(= 're-frame.substrate.spine/make-render
                                (:where (merge (:tags %) %)))
                            @mismatches)
                    "the trace carries the adoption tier's :where")
                (finally
                  (rf.trace.tooling/unregister-listener! lk)
                  (doseq [h handles]
                    (try (react-dom/flushSync #(rf.adapter.reagent/unmount! h))
                         (catch :default _ nil)))
                  (run! drop-host! [text-el struct-el clean-el])
                  (restore-act)
                  (done))))))))))

;; ---- prefix, the window after commit, and later updates -------------------

(def ^:private probe-ids
  "Every id `id-probe` has read, server render and client render alike."
  (atom []))

(def ^:private probe-effects (atom 0))

(defn- id-probe []
  (let [id (react/useId)]
    (swap! probe-ids conj id)
    (react/useEffect (fn [] (swap! probe-effects inc) js/undefined) #js [])
    [:span {:data-id id} "probe"]))

;; A sibling ahead of the probe, so the probe's useId carries a tree-fork
;; position: a wrapper that added a sibling at the root would shift it.
(defn- id-tree [] [:section [:h2 "ids"] [:f> id-probe]])

(def ^:private counter (r/atom 1))

(defn- counter-view [] [:span.count @counter])

(deftest hydrated-root-carries-prefix-closes-its-window-and-stays-live
  (testing "the :identifier-prefix reaches React (a client useId carries it and
            matches the server's), a recoverable error after the hydration
            commit reaches the host without the framework emit, and the
            hydrated root still updates through Reagent"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [restore-act     (act-off!)
              [mismatches lk] (listen-for-mismatches!)
              host-calls      (atom [])
              prefix          "rf-pfx-"
              server-html     (.renderToString react-dom-server
                                               (r/as-element [id-tree])
                                               #js {:identifierPrefix prefix})
              server-id       (last @probe-ids)
              _               (reset! probe-ids [])
              _               (reset! probe-effects 0)
              _               (reset! counter 1)
              id-el           (host! server-html)
              count-el        (host! "<div><span class=\"count\">1</span></div>")
              id-h            (rf.adapter.reagent/client-root)
              count-h         (rf.adapter.reagent/client-root)]
          (try
            (rf.adapter.reagent/render! id-h [id-tree] id-el
                                        {:hydrate? true
                                         :identifier-prefix prefix
                                         :on-recoverable-error (fn [e _] (swap! host-calls conj e))})
            (rf.adapter.reagent/render! count-h [:div [counter-view]] count-el {:hydrate? true})
            (catch :default e
              (is false (str "hydrating render! threw: " (pr-str e)))))
          ;; The probe's passive effect runs in the same flush as the effects
          ;; above it, the window closer's included, so once it has run the
          ;; hydration commit and its effects are done.
          (poll-until
            #(and (pos? @probe-effects) (committed-top count-el))
            (fn []
              (try
                (testing "identifier prefix"
                  (is (= prefix (some-> (fiber-root id-el) .-identifierPrefix))
                      "the root was created with the prefix as identifierPrefix")
                  (is (some-> (last @probe-ids) (.includes prefix))
                      (str "a client useId carries the prefix. Ids: " (pr-str @probe-ids)))
                  (is (= server-id (last @probe-ids))
                      "the client's useId equals the server's: the root's own wrapping moved no tree position"))
                (testing "a clean hydration reports nothing"
                  (is (empty? @mismatches) (str "no trace for a clean root. Saw: " (pr-str @mismatches)))
                  (is (empty? @host-calls) "no host call for a clean root"))
                (testing "after the hydration commit the reporter delegates without emitting"
                  (let [on-recoverable (some-> (fiber-root id-el) .-onRecoverableError)
                        later          (js/Error. "after the hydration commit")]
                    (when on-recoverable (on-recoverable later #js {}))
                    (is (= [later] @host-calls)
                        "the root's onRecoverableError still reaches the host callback")
                    (is (empty? @mismatches)
                        "a recoverable error after the hydration commit is not a hydration mismatch")))
                (testing "the hydrated root still updates through Reagent"
                  (reset! counter 2)
                  (r/flush)
                  (is (= "2" (some-> (.querySelector count-el ".count") .-textContent))
                      "a ratom change re-rendered the hydrated component through Reagent's queue")
                  (react-dom/flushSync #(rf.adapter.reagent/render! count-h [:div [:em "v2"]] count-el))
                  (is (= "v2" (some-> (.querySelector count-el "em") .-textContent))
                      "a later render! committed through the hydrated root"))
                (catch :default e
                  (is false (str "assertions threw: " (pr-str e))))
                (finally
                  (rf.trace.tooling/unregister-listener! lk)
                  (doseq [h [id-h count-h]]
                    (try (react-dom/flushSync #(rf.adapter.reagent/unmount! h))
                         (catch :default _ nil)))
                  (run! drop-host! [id-el count-el])
                  (restore-act)
                  (done))))))))))

;; ---- the root element is Reagent's own ------------------------------------

(deftest hydrated-root-element-is-reagents-own
  (testing "the adapter hydrates the element `reagent.dom.client/hydrate-root`
            builds: the same root component at the top, the same DOM, and the
            window closer wrapping the tree beneath Reagent's root"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [restore-act (act-off!)
              stock-el    (host! clean-html)
              adapter-el  (host! clean-html)
              stock-root  (rdc/hydrate-root stock-el [app "same"])
              h           (rf.adapter.reagent/client-root)]
          (rf.adapter.reagent/render! h [app "same"] adapter-el {:hydrate? true})
          (poll-until
            #(and (committed-top stock-el) (committed-top adapter-el))
            (fn []
              (try
                (let [stock-top   (committed-top stock-el)
                      adapter-top (committed-top adapter-el)]
                  (is (identical? @#'rdc/reagent-root (some-> stock-top .-type))
                      "control: stock hydrate-root's top component is Reagent's root")
                  (is (identical? (some-> stock-top .-type) (some-> adapter-top .-type))
                      "the adapter's hydrated root has the same top component")
                  (is (identical? rf.substrate.spine/adoption-window-closer
                                  (some-> adapter-top .-child .-child .-type))
                      "beneath Reagent's root and its tree thunk, the closer wraps the tree")
                  (is (= (.-innerHTML stock-el) (.-innerHTML adapter-el))
                      "both roots committed the same DOM"))
                (catch :default e
                  (is false (str "assertions threw: " (pr-str e))))
                (finally
                  (try (react-dom/flushSync #(rdc/unmount stock-root)) (catch :default _ nil))
                  (try (react-dom/flushSync #(rf.adapter.reagent/unmount! h)) (catch :default _ nil))
                  (run! drop-host! [stock-el adapter-el])
                  (restore-act)
                  (done))))))))))
