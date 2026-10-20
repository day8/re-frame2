(ns re-frame.fresco.server-node-module-ssr-cljs-test
  "`re-frame.fresco.server/node-module` — the render module a Node sidecar
  loads, built from one options map.

  Every row hands the module what `implementation/ssr-node`'s worker hands
  it: `boot` once per isolate, then `render` with a frozen `{entry, state,
  runtime, args}` call whose two partitions are key text -> EDN text, built
  here by `re-frame.ssr.render-state/serialize` exactly as the JVM renderer
  builds them. The product witness over the real login example is
  `re-frame.fresco.login-server-crossing-ssr-dom-cljs-test`; this file is the
  narrow one, so a failure names the helper rather than the example.

  The fixture installs NO adapter, so the `:boot` rows can see what the
  module seats, and every render row boots first, as the sidecar does.

  Runtime: `-cljs-test`, so the focused `:node-test-fresco` build and the
  always-on `:node-test`. Every row renders to a string; none needs a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.server :as rf.fresco.server]
            [re-frame.fresco.substrate :as rf.fresco.substrate]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.subs :as rf.subs]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]))

;; Registered ABOVE `use-fixtures`, for the sibling suites' reason: the reset
;; fixture captures its registrar baseline when the `use-fixtures` form is
;; EVALUATED, so a registration written below it is erased before the first
;; row runs.

(rf/reg-sub ::greeting (fn [db _] (:greeting db)))

(rf.subs/reg-runtime-sub ::flavour
  (fn [runtime-db _] (get-in runtime-db [::kitchen :flavour])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    ;; No `:adapter`: the module's `boot` is what seats one. `:ambient-frame
    ;; nil` because `render-body` makes its own top-level frame.
    {:ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The probes
;; ---------------------------------------------------------------------------

(rf.fresco/defview both-partitions
  "Reads one app-db sub and one runtime-db sub, so a row can tell WHICH
  partition failed to arrive rather than only that something did."
  [_]
  [:div.page
   [:p.greeting (str (rf.fresco/sub [::greeting]))]
   [:p.flavour (str (rf.fresco/sub [::flavour]))]])

(defn- id-probe
  "One `useId`, rendered as text, behind a `{:server :render}` host."
  [^js _props]
  (react/createElement "b" #js {:className "probe"} (react/useId)))

(rf.fresco/defhost id-host id-probe {:server :render})

(rf.fresco/defview id-page
  [_]
  [:div.page [id-host {}]])

;; ---------------------------------------------------------------------------
;; The module and the call
;; ---------------------------------------------------------------------------

(def ^:private entry "test/root")

(defn- seat-fresco!
  "The `:boot` a Fresco bundle passes."
  []
  (rf/init! rf.fresco.substrate/adapter))

(defn- module
  ([] (module {}))
  ([opts]
   (rf.fresco.server/node-module
     (merge {:build-id "node-module-test"
             :entries  [entry]
             :hiccup   [both-partitions {}]
             :boot     seat-fresco!}
            opts))))

(defn- frozen-call
  "The call `worker.cjs` hands a module: frozen, with each partition frozen
  and carried as key text -> EDN text."
  [partitions]
  (let [wire (rf.ssr.render-state/serialize partitions)]
    (js/Object.freeze
      #js {:entry   entry
           :state   (js/Object.freeze (clj->js (:rf/app-db wire)))
           :runtime (js/Object.freeze (clj->js (:rf/runtime-db wire)))
           :args    js/undefined})))

(def ^:private partitions
  {:rf/app-db     {:greeting "hello"}
   :rf/runtime-db {::kitchen {:flavour "vanilla"}}})

(defn- boot-and-render!
  [^js m call]
  ((.-boot m))
  ((.-render m) call))

;; ---------------------------------------------------------------------------
;; The contract fields
;; ---------------------------------------------------------------------------

(deftest the-module-carries-the-render-module-contract
  (let [^js m (module)]
    (is (= 1 (.-protocol m)) "protocol 1, the version the sidecar speaks")
    (is (= "node-module-test" (.-buildId m)))
    (is (array? (.-entries m)) "the entry table is a JS array, as the sidecar reads it")
    (is (= [entry] (vec (.-entries m))))
    (is (identical? seat-fresco! (.-boot m)) "the caller's `:boot`, published as given")
    (is (fn? (.-render m)))))

;; ---------------------------------------------------------------------------
;; render
;; ---------------------------------------------------------------------------

(deftest render-answers-the-body-markup-for-a-frozen-call
  (let [html (boot-and-render! (module) (frozen-call partitions))]
    (is (string? html) "a string is the module's only output channel")
    (is (str/includes? html "hello") "the app-db partition was read back and reached the view")
    (is (str/includes? html "vanilla") "and so was the runtime-db partition")
    (testing "body markup alone"
      (is (not (str/includes? html "<script")) "no payload script")
      (is (not (str/includes? html "<body")) "no shell"))))

(deftest render-hands-the-identifier-prefix-to-react
  (let [m    (module {:hiccup [id-page {}] :identifier-prefix "pfx-node-"})
        html (boot-and-render! m (frozen-call partitions))]
    (is (str/includes? html "pfx-node-"))))

;; ---------------------------------------------------------------------------
;; boot
;; ---------------------------------------------------------------------------

(deftest the-boot-seats-what-the-caller-names
  (is (nil? (rf/current-adapter)) "the control: the fixture seated nothing")
  ((.-boot ^js (module)))
  (is (= :rf.adapter/fresco (:kind (rf/current-adapter)))))

(deftest without-a-boot-the-module-publishes-none-and-a-render-fails-loud
  (let [^js m  (module {:boot nil})
        thrown (try ((.-render m) (frozen-call partitions))
                    nil
                    (catch :default e (ex-data e)))]
    (is (not (js-in "boot" m)) "no default substrate: the sidecar runs no boot")
    (is (nil? (rf/current-adapter)) "and nothing seated one")
    ;; A fresh isolate has never seated an adapter and reports
    ;; `no-adapter-installed`; this process has, and the reset fixture
    ;; disposed it, so it reports `adapter-disposed`. Both name the absence.
    (is (contains? #{:rf.error/no-adapter-installed :rf.error/adapter-disposed}
                   (:rf.error/id thrown))
        "so the first render names the missing adapter rather than rendering")))
