(ns reagent-migration.mig23-cold-start-test
  "COLD-START EVIDENCE for the MIG-23 SSR recipe (rf2-vpdrf).

  The skill's MIG-23 recipe stands up a Node rendering service — a
  SEPARATE process from the browser — and both of its halves construct a
  frame. re-frame2 has no default-adapter registry: frame construction
  delegates to the installed adapter's `make-state-container`, which
  raises `:rf.error/no-adapter-installed` in a never-initialized process.
  This suite proves the pre-correction recipe was non-vacuously red and
  the corrected recipe's boot precondition is exactly what fixes it.

  ORDERED PHASES IN ONE DEFTEST BODY, because the never-installed state
  exists only once per process — at genuine cold start, before the first
  `rf/init!` (after `rf/destroy-adapter!` the same call sites raise
  `:rf.error/adapter-disposed` instead, a different lifecycle state):

    1. NEGATIVE (never-initialized): the recipe's two entry points —
       `rf.hicasso.server/render` (the server half) and `rf/make-frame` (the client
       half's first call) — each raise `:rf.error/no-adapter-installed`
       before any render/hydration work; no `:document` is produced.
    2. POSITIVE server control: ONE `(rf/init! rf.ssr/adapter)` at process
       boot, then TWO `rf.hicasso.server/render` requests both answer a `:document`
       and a payload, with no second adapter install attempted
       (`rf/current-adapter-spec` identity is unchanged across both) —
       initialization is boot work, not request work.
    3. POSITIVE client-shaped control: install the migrating app's
       existing React-shaped adapter (the stock Reagent adapter MIG-15
       and MIG-23 both KEEP) and the same `rf/make-frame` call advances;
       a reload-path `rf/init!` re-run is a no-op (same installed spec),
       so the hydration/HMR path never reinstalls. The browser-side
       hydrate calls themselves need a DOM and are covered by the
       shipped Hicasso/SSR suites — the entry point the cold recipe dies
       at, per the finding, is `rf/make-frame`, and that is what is
       proven to advance here.

  Run from skills/reagent-migration/tests/fixture/:
      npm install && npm run test:cold-start"
  (:require [cljs.test :refer [deftest testing is]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.server :as rf.hicasso.server]))

(rf.hicasso/defview page
  "Minimal deterministic root — no clock, no randomness, no browser
  global, so the server render is byte-stable."
  [_props]
  [:div "mig23 cold-start evidence"])

(def ^:private render-opts
  "The corrected MIG-23 server half's options: a minimal deterministic
  hiccup root and a valid non-empty fail-closed payload allowlist."
  {:hiccup            [page {}]
   :payload           [:catalog/items]
   :snapshot          {:catalog/items ["a" "b"]}
   :client-frame-id   :app/main
   :identifier-prefix "main"})

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
           (rf-error-id #(rf.hicasso.server/render render-opts)))
        "the server half without rf/init!: rf.hicasso.server/render reaches rf/make-frame and throws; no :document is returned")
    (is (= :rf.error/no-adapter-installed
           (rf-error-id #(rf/make-frame {:id :app/main :platform :client})))
        "the client half without rf/init!: its first rf/make-frame throws the same error, so rf.ssr/hydrate! / rf.hicasso/hydrate! never run")
    (is (nil? (rf/current-adapter))
        "the failed calls did not install anything either — render never auto-installs"))

  (testing "POSITIVE server control — one (rf/init! rf.ssr/adapter) at process boot, then requests just work"
    (rf/init! rf.ssr/adapter)
    (is (= :rf.adapter/ssr (rf/current-adapter))
        "the corrected server half installs the headless server-side adapter once")
    (let [spec-before (rf/current-adapter-spec)
          r1          (rf.hicasso.server/render render-opts)
          r2          (rf.hicasso.server/render render-opts)]
      (is (string? (:document r1))
          "first request: the existing render call now returns a document")
      (is (map? (:payload r1))
          "first request: and a hydration payload")
      (is (string? (:document r2))
          "second request: works with NO second install")
      (is (identical? spec-before (rf/current-adapter-spec))
          "two requests after ONE rf/init! left the installed adapter untouched — initialization is process boot, not request work")))

  (testing "POSITIVE client-shaped control — the migrating app's existing Reagent adapter advances the same frame entry point"
    (rf/destroy-adapter!)
    (rf/init! rf.adapter.reagent/adapter)
    (is (= :rf.adapter/reagent (rf/current-adapter))
        "the client keeps its existing Reagent adapter — no silent adapter switch")
    (rf/make-frame {:id :app/main :platform :client})
    (is (contains? (rf/frame-ids) :app/main)
        "the exact rf/make-frame call the cold client died at now advances")
    (let [spec (rf/current-adapter-spec)]
      (rf/init! rf.adapter.reagent/adapter)
      (is (identical? spec (rf/current-adapter-spec))
          "a reload-path rf/init! re-run is a no-op — the hydration/HMR path never reinstalls"))))
