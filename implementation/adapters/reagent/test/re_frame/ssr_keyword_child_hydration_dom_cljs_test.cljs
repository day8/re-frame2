(ns re-frame.ssr-keyword-child-hydration-dom-cljs-test
  "rf2-53lsj — does the JVM emitter's markup actually HYDRATE?

  The two keyword-head contract suites
  (`re-frame.ssr-keyword-head-contract-test` and its CLJS twin) pin the
  bytes each host produces. Matching bytes is necessary but it is not the
  claim that matters: the claim is that server markup and client render
  reconcile without React complaining and without the text changing under
  the user. This file makes React itself the judge.

  ## Why this file exists at all

  #6378 asserted the element structure agreed and reasoned that the
  differing TEXT was harmless because \"element structure is what
  hydration reconciles\". React reconciles text nodes too. Rather than
  argue the point again in a comment, the test below hands React the
  server bytes and the client tree and asks it.

  ## The vacuity lever

  A hydration probe that never fails proves nothing, and a silent
  `console.error` capture is exactly the shape that rots. So the same
  harness is run TWICE:

    - over the bytes the emitter produces NOW (`<card>revenue</card>`)
      — React must be silent;
    - over the bytes it produced BEFORE the fix (`<card>:revenue</card>`)
      — React must COMPLAIN.

  The second case is the red-before, kept permanently executable. If
  React ever stops reporting text mismatches, or the capture stops
  working, that test goes red and this file stops making a claim it can
  no longer support — rather than going quietly green.

  The `-dom-cljs-test$` suffix opts this file into the `:browser-test`
  build. `:node-test` loads it too (it matches `cljs-test$`), where
  `js/document` is absent and every test here gates on `(browser?)` and
  exits early. **A node-only run of this namespace is VACUOUS by
  construction** — `npm run test:browser` is where it asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.ssr.install :as install]
            [re-frame.test-support :as test-support]))

(def ^:private test-frame :ssr-keyword-child-hydration/frame)

(defn- init! []
  (rf/make-frame {:id       test-frame
                  :doc      "keyword-child hydration probe frame"
                  :platform :client})
  (rf/reg-view* :dashboard/card {}
                (fn [card-id] [:div.card [:h3 (str card-id)]])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn init!})
  ;; No test here INSTALLS a hydration payload, but the ledger is a
  ;; process-global `defonce` that neither `clear-all!` nor a frames
  ;; reset touches (rf2-aorfy), so a future edit that adds an install
  ;; would leak into sibling namespaces rather than fail here. Resetting
  ;; unconditionally keeps that trap shut.
  (fn [f] (install/reset-installed-payloads!) (f)))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- hydrate-over
  "Put `server-html` into a detached-then-attached container, hydrate
  `tree` over it with REAL React, and report what React said.

  -> `{:complaints [str …] :text \"…\"}`.

  React reports hydration mismatches through `console.error`, so both
  `error` and `warn` are captured for the duration and restored after —
  the runner installs its own `console.warn` buffer, and this save/
  restore pairs with it rather than fighting it."
  [server-html tree]
  (let [container  (.createElement js/document "div")
        complaints (atom [])
        orig-error (.-error js/console)
        orig-warn  (.-warn js/console)
        record!    (fn [& args]
                     (swap! complaints conj
                            (str/join " " (map #(str %) args))))]
    (set! (.-innerHTML container) server-html)
    (.appendChild (.-body js/document) container)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (set! (.-error js/console) record!)
    (set! (.-warn js/console) record!)
    (let [act-fn (get-act)
          root   (atom nil)]
      (try
        ;; React is driven DIRECTLY here rather than through
        ;; `reagent.dom.client/hydrate-root`. Reagent 2.0.1 destructures
        ;; `:on-recoverable-error` from its options map and then never
        ;; passes it to `hydrateRoot`, so the callback React uses to
        ;; report a recovered hydration mismatch is unreachable through
        ;; that wrapper. `as-element` still builds the tree the Reagent
        ;; way, so what is under test is unchanged — only the root
        ;; handle is ours, so the probe can hear React.
        (act-fn
          (fn []
            (reset! root
                    (react-dom-client/hydrateRoot
                      container
                      (r/as-element [rf/frame-provider {:frame test-frame} tree])
                      #js {:onRecoverableError
                           (fn [err _info] (record! "onRecoverableError" err))}))))
        (let [text (.-textContent container)]
          (act-fn (fn [] (some-> @root .unmount)))
          {:complaints @complaints :text text})
        (finally
          (set! (.-error js/console) orig-error)
          (set! (.-warn js/console) orig-warn)
          (.remove container))))))

(defn- hydration-complaints
  "Only the complaints that are about HYDRATION — React emits unrelated
  development warnings, and counting those would make the green case
  flaky and the red case dishonest."
  [complaints]
  (filter #(let [s (str/lower-case %)]
             (or (str/includes? s "hydrat")
                 (str/includes? s "did not match")
                 (str/includes? s "didn't match")
                 (str/includes? s "server rendered")))
          complaints))

;; ---------------------------------------------------------------------------
;; The claim
;; ---------------------------------------------------------------------------

(deftest jvm-markup-hydrates-without-a-text-mismatch
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "rf2-53lsj — the bytes the JVM emitter produces for
              `[:dashboard/card :revenue]` hydrate silently, and the text
              the user sees is the text the server sent"
      (let [{:keys [complaints text]}
            (hydrate-over "<card>revenue</card>" [:dashboard/card :revenue])]
        (is (empty? (hydration-complaints complaints))
            (str "React complained about hydrating the emitter's own "
                 "markup: " (pr-str (hydration-complaints complaints))))
        (is (= "revenue" text)
            "the intended text survives hydration — not blanked, not
             rewritten")))))

(deftest hydration-probe-detects-a-real-mismatch
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (testing "THE RED-BEFORE for rf2-53lsj, kept executable: the SAME
              harness over the bytes the emitter produced BEFORE the fix
              (`<card>:revenue</card>`) must make React complain.

              This is what licenses the green assertion above. Without it
              an empty-complaints result could mean `the markup hydrates`
              or `the capture is broken`, and those are indistinguishable
              from a passing test. That is not hypothetical: the first
              version of this harness went through
              `reagent.dom.client/hydrate-root` and captured NOTHING on
              either input, because the wrapper drops the
              `onRecoverableError` it accepts.

              React 19's verdict on the pre-fix bytes, verbatim:

                Hydration failed because the server rendered text didn't
                match the client. …
                  <card>
                +       revenue
                -       :revenue"
      (let [{:keys [complaints]}
            (hydrate-over "<card>:revenue</card>" [:dashboard/card :revenue])]
        (is (seq (hydration-complaints complaints))
            "React must report the pre-fix server text as a hydration
             mismatch — if this is silent, the probe above proves nothing
             and the `hydration only reconciles element structure` claim
             would have been right after all")))))

(deftest namespaced-keyword-child-hydrates
  (if-not (browser?)
    (is true "skipped under node — no js/document; npm run test:browser asserts")
    (do
      (testing "rf2-53lsj — the namespace-dropping case, which a
                colon-stripping fix would have got wrong: the emitter sends
                `b` for `:a/b`, and that is what hydrates clean"
        (let [{:keys [complaints text]}
              (hydrate-over "<div>b</div>" [:div :a/b])]
          (is (empty? (hydration-complaints complaints))
              (str "got: " (pr-str (hydration-complaints complaints))))
          (is (= "b" text))))

      (testing "…and the spelling a colon-strip would have produced does NOT
                hydrate, which is why the `name` spelling is the fix"
        (let [{:keys [complaints]} (hydrate-over "<div>a/b</div>" [:div :a/b])]
          (is (seq (hydration-complaints complaints))
              "`a/b` must be a mismatch — pinning this stops a future
               `simplification` to colon-stripping from landing green"))))))
