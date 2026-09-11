(ns day8.re-frame2-xray.views.edn-widget-two-heads-dom-cljs-test
  "THE FACADE'S TWO HEADS, EACH READ OFF A REAL REACT COMMIT (rf2-k97c.3).

  `views/edn_widget.cljs` ships the T4 dual-head facade: `inspect` emits
  the Reagent head `[ei/edn-inspector …]` and `inspect-view` emits the
  Fresco boundary `[ei/edn-inspector-view …]`. Same value, same opts, same
  renderer — only the observer differs.

  ## WHY THIS FILE EXISTS, AND WHAT IT IS EVIDENCE *FOR*

  The Reagent lane of this facade is not vestigial. It has ONE live caller
  at tip — `static/routes/row_expand.cljs`'s `value-block`, which CALLS
  `(edn/inspect value node-key)` — and that caller renders inside the
  `as-child` REAGENT ISLAND `static/routes/panel.cljs` stands up, reached
  as `panel.cljs` → `(as-child [browse-list/render …])` →
  `browse_list.cljs` → `[row-expand/render …]`. `panel.cljs`'s own
  docstring names this crossing as the TENTH head its in-file census could
  not see, \"because a CALL into a fifth file RETURNS it\".

  So the standing claim is *this caller must keep the Reagent head*. Until
  this file, that claim rested entirely on READING — and the routes
  panel's own browser witness deliberately cannot carry it: its
  `base-routes` seed no `:params` / `:query` schema meta precisely so that
  `edn-inspector`, a `reg-view`, is never dragged into the island's
  subtree and cannot put a `:rf.view/*` op in that suite's trace census.
  The crossing the claim is about is therefore the one crossing no live
  row exercises.

  This file exercises it, in the shape `row_expand` uses: a plain Reagent
  fn CALLING the facade, inside a `frame-provider`.

  ## THE ROWS

    W1  the REAGENT head paints in a Reagent position — `row_expand`'s
        shape, committed and read back off the DOM
    W2  the FRESCO head THROWS in that same Reagent position, so \"must
        keep the Reagent head\" is measured rather than inferred
    W3  the FRESCO head DOES paint inside a real Fresco boundary — the
        positive control without which W2 reads as \"`inspect-view` is
        broken\" rather than as \"the head is wrong for this position\"

  W2 and W3 are a pair. Neither is evidence on its own.

  ## WHAT W2 ACTUALLY MEASURED, AND WHY IT IS NOT A RE-FRAME REFUSAL

  The failure is one level below re-frame. Reagent renders a fn in head
  position as a CLASS component, so `edn-inspector-view` — a minted Fresco
  boundary, i.e. a React FUNCTION component — runs inside
  `reagent.impl.component/do-render` under React's `updateClassComponent`.
  The first thing `collector/shell` does is `useContext`, and a hook called
  from a class render is refused by React itself:

      Invalid hook call. Hooks can only be called inside of the body of a
      function component.
        at exports.useContext … re_frame.fresco.impl.collector/shell
        at … edn-inspector-view … [as reagentRender]
        at reagent.impl.component/do-render … updateClassComponent

  So the crossing is illegal at the REACT tier, before any Fresco or
  re-frame check can speak — which is stronger than the
  `:rf.error/fresco-sub-outside-render` refusal one might expect, and is
  why the row asserts \"an error was raised\" rather than matching a
  refusal id.

  ## W2 MUST CONTAIN ITS OWN THROW, AND THAT IS THE RUNNER'S RULE

  `scripts/run-browser-tests.cjs` treats ANY Chromium `pageerror` as fatal
  BY DESIGN (rf2-wf5al / rf2-mwx08) — console noise stays diagnostic, an
  uncaught error does not. Measured: with W2's mount left bare, this file's
  three rows all passed, the summary read `0 failures, 0 errors`, and the
  runner still exited 1 on the single `pageerror` in the whole lane, which
  was W2's. A row that deliberately throws therefore has to CATCH, or it
  reddens every other suite on the page.

  So W2 mounts inside a minimal React class error boundary — error
  boundaries must be class components — modelled on the one
  `re_frame/frame_provider_context_dom_cljs_test.cljs` uses for the same
  purpose. `getDerivedStateFromError` both contains the throw and hands the
  row the error to assert on, which is better evidence than the window
  listener would have been.

  ## TWO INSTRUMENT FACTS INHERITED FROM THE EARLIER SLICES

  A refusal raised inside a React render does NOT reach a `try/catch`
  around `flushSync`. W1 and W3 therefore assert on the committed DOM and
  carry any captured window error only as a DIAGNOSTIC SUFFIX — a row that
  caught nothing and saw nothing would otherwise be indistinguishable from
  one that never mounted.

  And every DOM accessor here is `some->`-guarded. `shadow.test` runs the
  whole browser lane inside one `cljs.test/run-block` with no try/catch,
  so a bare accessor on an absent element aborts the run with no summary
  and every later namespace silently never executes — while the runner
  still exits 1, which looks exactly like an honest failure.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) — `npm run test:browser`. The
  `:node-test` build's `cljs-test$` regex also matches, so it LOADS under
  Node — where every row short-circuits through [[browser?]] and reports
  the skip rather than passing silently. The node lane could not carry
  these rows anyway: `expand-tree` INVOKES a fn head, so `[x …]` and
  `(x …)` expand identically there and head legality is invisible to it
  by construction."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

(def ^:private xray-frame
  "The frame the enclosing `frame-provider` names. `:rf/xray` is the
  production singleton several Xray registrations reach by name regardless
  of which frame an instance mounts at."
  :rf/xray)

(def ^:private node-key
  "The facade's per-mount qualifier — the single string `inspect` turns
  into a `:panel-id`, and `inspect-view` turns into BOTH a `:panel-id` and
  the boundary's required `:mount-id`. Named once because every selector
  below is composed from it."
  "two-heads")

(def ^:private sentinel
  "A leaf string distinctive enough that finding it in `.textContent` is
  evidence the RENDERER ran, not merely that a container div committed."
  "two-heads-sentinel-value")

(def ^:private subject-value
  "Wide on purpose. The widget's width-aware heuristic inlines anything
  whose `pr-str` fits the measured column, so a small value legitimately
  CHANGES SHAPE once between the mount and the first settle. That is fatal
  to a row asserting a container node exists. This value cannot fit any
  plausible column, so it renders as a container at every width."
  {:sentinel sentinel
   :padding  (into {} (for [i (range 12)]
                        [(keyword (str "key-" i))
                         (str "a deliberately long value string number " i)]))})

;; ---- the diagnostic ------------------------------------------------------
;;
;; Declared ABOVE the fixture, because the fixture resets the atom.

(defonce ^:private !last-uncaught (atom nil))

;; What W2's error boundary caught, if anything. An atom rather than boundary
;; state because the assertion is made after the commit, from outside React.
;; (`defonce` takes no docstring, which is why this one is a comment.)
(defonce ^:private !caught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because every row is an `async` one, and `cljs.test`
     ;; refuses a FUNCTION fixture in any namespace carrying one.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the cell table would
                      ;; make W3 read a residue that is not this file's.
                      (rf.fresco.impl.collector/reset-runtime!)
                      ;; The window listener is process-global too, and
                      ;; several suites on this page throw DELIBERATELY —
                      ;; a diagnostic naming the wrong file is worse than
                      ;; none.
                      (reset! !last-uncaught nil)
                      (reset! !caught nil))}))

;; ---- W2's containment ----------------------------------------------------

(def ^:private error-boundary-class
  "A minimal React class-component error boundary. Error boundaries MUST be
  class components, so this is hand-rolled rather than reached for from
  Reagent — the same shape, and for the same reason, as the one
  `implementation/adapters/reagent/test/re_frame/frame_provider_context_dom_cljs_test.cljs`
  stands up.

  `getDerivedStateFromError` does double duty: it CONTAINS the throw, so
  React reports it to the console instead of re-raising it as the
  `pageerror` the runner treats as fatal, and it records the error for the
  row to assert on."
  (let [ctor (fn XrayTwoHeadsErrorBoundary [props]
               (this-as this
                 (.call (.-Component React) this props)
                 (set! (.-state this) #js {:caught false})
                 this))]
    (set! (.-prototype ctor) (js/Object.create (.-prototype (.-Component React))))
    (set! (.. ctor -prototype -constructor) ctor)
    (set! (.-getDerivedStateFromError ctor)
          (fn [err] (reset! !caught err) #js {:caught true}))
    (set! (.. ctor -prototype -render)
          (fn []
            (this-as this
              (if (.-caught (.-state ^js this))
                (React/createElement "div"
                                     #js {"data-testid" "rf-xray-two-heads-caught"}
                                     "caught")
                (.-children (.-props ^js this))))))
    ctor))

(defn- contained
  "`hiccup` as a React element, wrapped in [[error-boundary-class]]."
  [hiccup]
  (React/createElement error-boundary-class nil (r/as-element hiccup)))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for W2: an absence asserted immediately after a mount is a race, and an
  absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 30))))))))

(defn- setup!
  "Register Xray's handlers — which is what installs the widget's
  expansion / zoom / width subs and events — and make the frame the
  provider names."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id xray-frame})
  nil)

;; ---- the two hosts, each in the shape its lane really uses ---------------

(defn- ReagentHost
  "`row_expand.cljs`'s shape, in miniature: an ordinary Reagent fn whose
  body CALLS the facade and splices the returned vector in as a child. The
  returned head is `ei/edn-inspector`, an `rf/reg-view`, which is what a
  Reagent parent mounts."
  [inspect-fn]
  [:div {:data-testid "rf-xray-two-heads-reagent-host"}
   (inspect-fn subject-value node-key)])

(rf.fresco/defview FrescoHost
  "A migrated panel, in miniature. Holds the value on the Fresco side and
  reaches the widget through the facade's Fresco head, which is what a
  migrated panel writes."
  [_props]
  [:div {:data-testid "rf-xray-two-heads-fresco-host"}
   (edn/inspect-view subject-value node-key)])

(def ^:private FrescoHost-component
  "Declared ONCE at top level, as `as-component`'s contract requires:
  deriving one per render mints a fresh element type and remounts the
  subtree."
  (rf.fresco/as-component FrescoHost))

;; ---- mounting ------------------------------------------------------------

(defn- mount!
  "Commit `tree` under a `frame-provider` naming [[xray-frame]].

  Synchronously, with React's own door — `root.render` is otherwise async
  under React 19 and the first assertion would read an empty container.
  `mount.cljs` wraps the real shell in exactly this provider; a suite that
  mounted BARE would take `:rf.error/no-frame-context` failures that are
  its own mount's rather than the view's."
  [tree]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame xray-frame} tree])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads and where React detaches the
  widget's container `:ref` — have RUN before the next row mounts. A bare
  `.unmount` merely schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

;; ---- reading the committed DOM back --------------------------------------

(defn- q [container sel] (some-> container (.querySelector sel)))

(defn- host-el
  "The host's own div. Present in every row, including W2 — the host is
  ordinary markup and commits whatever the widget does, which is what
  makes W2's absence a statement about the HEAD rather than about the
  mount."
  [container testid]
  (q container (str "[data-testid=\"" testid "\"]")))

(defn- widget-el
  "The widget's own root element. The renderer composes its testid as
  `rf-xray-edn-inspector-<panel-id name>-<mount-id>`, and `inspect` builds
  the panel-id as `:rf.xray.inspect/<node-key>` — so the name half is the
  node-key and the mount-id half is a UUID the form-2 head mints per
  mount. PREFIX-matched for that reason: the Reagent lane's id is
  unpredictable by design (rf2-sndui — the public API takes no
  `:render-id`)."
  [container]
  (q container (str "[data-testid^=\"rf-xray-edn-inspector-" node-key "-\"]")))

(defn- widget-text [container]
  (some-> (widget-el container) (.-textContent)))

(defn- uncaught-note
  "A suffix naming the last uncaught error, for a row whose subject is
  missing. EMPTY when nothing was thrown — in which case the absence is
  the finding rather than a hidden exception, and the row says so."
  []
  (if-some [e (when error-capture-armed? @!last-uncaught)]
    (str " — an uncaught error was raised during render: "
         (:rf.error/id (ex-data e) (ex-message e))
         " · at: "
         (let [s (str (.-stack ^js e))]
           (->> (str/split-lines s)
                (remove str/blank?)
                (filter #(str/includes? % "at "))
                (take 8)
                (str/join " | "))))
    " — and NOTHING was thrown, so the head rendered nothing rather than refusing"))

;; ===========================================================================
;; W1 — the REAGENT head paints in a Reagent position
;; ===========================================================================

(deftest w1-reagent-head-paints-under-a-reagent-parent
  (testing "rf2-k97c.3 — `edn-widget/inspect`, CALLED from an ordinary
            Reagent fn the way `static/routes/row_expand.cljs` calls it,
            commits the widget's own container and renders the value. This
            is the live evidence for the standing claim that the routes
            island's caller must keep the Reagent head; before this row the
            claim rested on reading the call chain."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount! [ReagentHost edn/inspect])]
          (-> (settle)
              (.then
                (fn [_]
                  (is (some? (host-el container "rf-xray-two-heads-reagent-host"))
                      "the Reagent host itself committed")
                  (is (some? (widget-el container))
                      (str "the Reagent head committed the widget's own container"
                           (uncaught-note)))
                  (is (some-> (widget-text container) (str/includes? sentinel))
                      "and the RENDERER ran — the leaf value is in the committed text")
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W2 — the FRESCO head does not paint in that same Reagent position
;; ===========================================================================

(deftest w2-fresco-head-throws-under-a-reagent-parent
  (testing "rf2-k97c.3 — the SAME Reagent host, handed `inspect-view`
            instead of `inspect`, RAISES during render and commits no
            widget. So `row_expand`'s caller cannot be moved onto the
            Fresco side while it renders inside `static/routes/panel.cljs`'s
            `as-child` Reagent island — measured, not inferred.

            The error is contained by a class error boundary, which is
            mandatory rather than tidy: the browser runner treats an
            uncaught `pageerror` as fatal by design, so a bare mount here
            would redden every other suite on the page. See the ns
            docstring for the measurement and for what React refuses."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]}
              (mount! (contained [ReagentHost edn/inspect-view]))]
          (-> (settle)
              (.then
                (fn [_]
                  (is (some? @!caught)
                      "the Fresco head RAISED in a Reagent position — the
                       crossing is refused rather than merely empty")
                  (is (nil? (widget-el container))
                      (str "and no widget committed, which is why the facade
                            keeps two heads — caught: "
                           (some-> @!caught (ex-message))))
                  (is (not (some-> (widget-text container) (str/includes? sentinel)))
                      "and the renderer did not run")
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W3 — the FRESCO head paints inside a real Fresco boundary
;; ===========================================================================

(deftest w3-fresco-head-paints-inside-a-fresco-boundary
  (testing "rf2-k97c.3 — the positive control W2 needs. The same
            `inspect-view` call, made from a `defview` body and mounted
            through `as-component`, commits the widget and renders the
            value. Without this row W2 is consistent with `inspect-view`
            simply being broken; with it, W2 is a statement about the
            POSITION."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount! [:> FrescoHost-component {}])]
          (-> (settle)
              (.then
                (fn [_]
                  (is (some? (host-el container "rf-xray-two-heads-fresco-host"))
                      "the Fresco host committed")
                  (is (some? (widget-el container))
                      (str "the Fresco head committed the widget's own container"
                           (uncaught-note)))
                  (is (some-> (widget-text container) (str/includes? sentinel))
                      "and the RENDERER ran on the Fresco side too — one
                       renderer, two heads")
                  (teardown! root container)
                  (done)))))))))
