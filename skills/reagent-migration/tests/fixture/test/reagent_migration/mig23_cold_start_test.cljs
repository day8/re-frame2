(ns reagent-migration.mig23-cold-start-test
  "COLD-START EVIDENCE for the MIG-23 SSR recipe.

  The skill's MIG-23 recipe stands up a Node rendering service — a
  SEPARATE process from the browser — and both of its halves construct a
  frame. re-frame2 has no default-adapter registry: frame construction
  delegates to the installed adapter's `make-state-container`, which
  raises `:rf.error/no-adapter-installed` in a never-initialized process.
  This suite proves a recipe without a boot `rf/init!` is non-vacuously
  red and that the recipe's boot precondition is exactly what fixes it.

  ORDERED PHASES IN ONE DEFTEST BODY, because the never-installed state
  exists only once per process — at genuine cold start, before the first
  `rf/init!` (after `rf/destroy-adapter!` the same call sites raise
  `:rf.error/adapter-disposed` instead, a different lifecycle state):

    1. NEGATIVE (never-initialized): the recipe's two entry points —
       `rf.fresco.server/render` (the server half) and `rf/make-frame` (the client
       half's first call) — each raise `:rf.error/no-adapter-installed`
       before any render/hydration work; no `:document` is produced.
    2. POSITIVE server control: ONE `(rf/init! rf.ssr/adapter)` at process
       boot, then TWO `rf.fresco.server/render` requests both answer a `:document`
       and a payload, with no second adapter install attempted
       (`rf/current-adapter` identity is unchanged across both) —
       initialization is boot work, not request work.
    3. POSITIVE client-shaped control: install the migrating app's
       existing React-shaped adapter (the stock Reagent adapter MIG-15
       and MIG-23 both KEEP) and the same `rf/make-frame` call advances;
       a reload-path `rf/init!` re-run is a no-op (same installed spec),
       so the hydration/HMR path never reinstalls. The browser-side
       hydrate calls themselves need a DOM and are covered by the
       shipped Fresco/SSR suites — the entry point a cold recipe dies
       at is `rf/make-frame`, and that is what is proven to advance
       here.

  Run from skills/reagent-migration/tests/fixture/:
      npm install && npm run test:cold-start"
  (:require [cljs.test :refer [deftest testing is]]
            ["react-dom/server" :as react-server]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.server :as rf.fresco.server]))

(rf.fresco/defview page
  "Minimal deterministic root — no clock, no randomness, no browser
  global, so the server render is byte-stable."
  [_props]
  [:div "mig23 cold-start evidence"])

(def ^:private render-opts
  "The MIG-23 server half's options: a minimal deterministic
  hiccup root and a valid non-empty fail-closed payload allowlist."
  {:hiccup            [page {}]
   :payload           [:catalog/items]
   :snapshot          {:catalog/items ["a" "b"]}
   :client-frame-id   :app/main
   :identifier-prefix "main"})

(def ^:private bridge-props
  {:status :ready :item {:title "Task"} :on-select [:task/select 7]})

(rf.fresco/defview bridged-card [{:keys [status item on-select]}]
  [:p (str (keyword? status) "/" (get item :title "missing") "/"
           (= [:task/select 7] on-select))])

(def ^:private card-component (rf.fresco/as-component bridged-card))

(defn- reagent-parent [{:keys [mode]}]
  [:section
   (case mode
     "converted" [:> card-component bridge-props]
     "element" (rf.fresco/as-element [bridged-card bridge-props])
     "raw" (r/create-element card-component
                             #js {"status" (:status bridge-props)
                                  "item" (:item bridge-props)
                                  "onSelect" (:on-select bridge-props)}))])

(rf.fresco/defhost reagent-parent-host (r/reactify-component reagent-parent)
  {:server :render})

(defn- rf-error-id
  "Run `f`; answer the thrown `:rf.error/id`, or `[:no-throw <result>]`
  when it returned — so a passing call can never satisfy an error
  assertion."
  [f]
  (try [:no-throw (f)]
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest mig23-cold-start-contract
  (testing "NEGATIVE — never-initialized process: both recipe entry points are red before any render/hydration work"
    (is (nil? (rf/current-adapter))
        "cold start: no adapter is installed and none is defaulted")
    (is (= :rf.error/no-adapter-installed
           (rf-error-id #(rf.fresco.server/render render-opts)))
        "the server half without rf/init!: rf.fresco.server/render reaches rf/make-frame and throws; no :document is returned")
    (is (= :rf.error/no-adapter-installed
           (rf-error-id #(rf/make-frame {:id :app/main :platform :client})))
        "the client half without rf/init!: its first rf/make-frame throws the same error, so rf.ssr/hydrate! / rf.fresco/render! never run")
    (is (nil? (rf/current-adapter))
        "the failed calls did not install anything either — render never auto-installs"))

  (testing "POSITIVE server control — one (rf/init! rf.ssr/adapter) at process boot, then requests just work"
    (rf/init! rf.ssr/adapter)
    (is (= :rf.adapter/ssr (:kind (rf/current-adapter)))
        "the server half installs the headless server-side adapter once")
    (let [spec-before (rf/current-adapter)
          r1          (rf.fresco.server/render render-opts)
          r2          (rf.fresco.server/render render-opts)]
      (is (string? (:document r1))
          "first request: the existing render call now returns a document")
      (is (map? (:payload r1))
          "first request: and a hydration payload")
      (is (string? (:document r2))
          "second request: works with NO second install")
      (is (identical? spec-before (rf/current-adapter))
          "two requests after ONE rf/init! left the installed adapter untouched — initialization is process boot, not request work")))

  (testing "MIG-22 — a retained Reagent parent must preserve the converted child's Clojure props"
    (let [render (fn [mode]
                   (:html (rf.fresco.server/render
                            (assoc render-opts :hiccup
                                   [reagent-parent-host {:mode mode}]))))]
      (is (re-find #"^<section><p[^>]*>false/missing/false</p></section>$" (render "converted"))
          "Reagent [:>] converts keyword, map and intent-vector props before as-component decodes them")
      (is (re-find #"^<section><p[^>]*>true/Task/true</p></section>$" (render "element"))
          "a Fresco element in the Reagent parent's child position preserves its Clojure values")
      (is (= (render "element") (render "raw"))
          "r/create-element with a raw JS props object preserves those values too")))

  (testing "POSITIVE client-shaped control — the migrating app's existing Reagent adapter advances the same frame entry point"
    (rf/destroy-adapter!)
    (rf/init! rf.adapter.reagent/adapter)
    (is (= :rf.adapter/reagent (:kind (rf/current-adapter)))
        "the client keeps its existing Reagent adapter — no silent adapter switch")
    (rf/make-frame {:id :app/main :platform :client})
    (is (contains? (rf/frame-ids) :app/main)
        "the exact rf/make-frame call the cold client died at now advances")
    (let [spec (rf/current-adapter)]
      (rf/init! rf.adapter.reagent/adapter)
      (is (identical? spec (rf/current-adapter))
          "a reload-path rf/init! re-run is a no-op — the hydration/HMR path never reinstalls"))))

(deftest mig14-preserve-empty-boolean-children
  (let [render #(react-server/renderToStaticMarkup %)]
    (doseq [hidden? [true false]]
      (let [donor [:div (or hidden? [:span "Details"])]]
        (is (= (if hidden? "<div></div>" "<div><span>Details</span></div>")
               (render (r/as-element donor)))
            "the Reagent donor omits true and renders the visible branch")
        (when hidden?
          (is (= :rf.error/fresco-true-child
                 (rf-error-id #(rf.fresco/as-element donor)))
              "an unchanged true child is refused by Fresco"))
        (is (= (render (r/as-element donor))
               (render (rf.fresco/as-element
                         [:div (when-not hidden? [:span "Details"])])))
            "the explicit empty branch preserves both rendered outcomes")))))

(deftest mig34-preserve-working-unsafe-html
  (let [html "<b>already trusted</b>"
        wrapped (r/unsafe-html html)
        render #(react-server/renderToStaticMarkup %)
        original (render (r/as-element [:div {:dangerouslySetInnerHTML wrapped}]))]
    (is (= "<div><b>already trusted</b></div>" original)
        "the stock Reagent donor really renders its wrapped HTML")
    (is (thrown-with-msg? js/Error #"__html"
          (render (rf.fresco/as-element [:div {:dangerouslySetInnerHTML wrapped}]))))
    (is (= original
           (render (rf.fresco/as-element [:div {:dangerouslySetInnerHTML {:__html html}}])))
        "MIG-34 replaces the donor wrapper and preserves the rendered bytes")
    (is (= "<div></div>"
           (render (r/as-element [:div {:dangerouslySetInnerHTML {:__html html}}]))))
    (is (= :re-frame.fresco/value ::rf.fresco/value)
        "the existing Fresco alias resolves the same marker namespace")))
