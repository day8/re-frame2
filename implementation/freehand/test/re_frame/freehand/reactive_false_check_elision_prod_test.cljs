(ns re-frame.freehand.reactive-false-check-elision-prod-test
  "SPIKE rf2-3slzz — the PRODUCTION half of the `{:reactive false}`
  totality question, proved under real `:advanced` + `goog.DEBUG=false`
  compilation rather than argued from source.

  The flag's check is a SAFETY mechanism, not authoring lint: a boundary
  the author wrongly declared shell-free must fail at the first offending
  render in a shipped bundle, not only in development. This repository
  has repeatedly found checks that exist only in dev — `reg-app-schema`
  validation is a production no-op, and several \"production\" suites
  turned out to rebind `interop/debug-enabled?` with `with-redefs`, which
  cannot reach a load-time gate at all. So the claim is made HERE, where
  Closure has already folded the gate to `false` and DCE'd everything
  behind it.

  What it pins:

  1. the posture is real — `interop/debug-enabled?` is genuinely false;
  2. a reactive read with no candidate still raises, and still carries
     its stable `:rf.error/view-read-outside-render` id;
  3. the same refusal reaches through an ordinary helper, which is the
     shape no build-time analysis can see;
  4. and it fires on a REAL MOUNT of a genuinely shell-free boundary —
     a `{:compiled true}` declaration the analyzer proved inert, whose
     body reads through that helper anyway.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked
  up ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, runner `re-frame.prod-elision-runner`). The
  default `:browser-test` / `:node-test` regexes do not match this
  suffix, so nothing here runs in a development posture."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.tree :as tree]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.test-support :as test-support]))

(def ^:private fid :spike-prod/frame)
(def ^:private outside-render :rf.error/view-read-outside-render)

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; `react/act` is a DEVELOPMENT-only export: the production React build
;; does not carry it, and reaching for it here answered
;; `TypeError: act is not a function` — which is itself a small reminder
;; that a browser assertion written in a dev posture is not automatically
;; a production one. `react-dom/flushSync` is the production-available
;; way to force the mount's render to run synchronously, so a render that
;; throws rethrows to THIS caller instead of surfacing as an unhandled
;; asynchronous error nothing here could observe.

(defn- caught-id
  [thunk]
  (try (thunk) ::no-throw (catch :default e (or (:rf.error/id (ex-data e)) ::no-id))))

(defn- register! []
  (rf/reg-sub :spike/total (fn [db _] (:total db))))

(defn- seed! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  fid)

(defn- helper-sub
  "An ORDINARY defn performing the read — the helper-mediated shape."
  []
  (str (v/sub [:spike/total])))

(v/defview reads-through-helper
  "Interpreted; reads through the helper."
  [_]
  [:p (helper-sub)])

(v/defview elided-but-reads
  "`{:compiled true}` and PROVED inert by the analyzer — no lexical `sub`
  site, so `:view-cell :elided` and NO ViewCell is minted — yet the body
  reads through an ordinary helper. The compiled tier's own proof is
  defeated here; the runtime candidate check is what stops it becoming a
  silently stale page, and it is the same check `{:reactive false}` would
  rest on."
  {:compiled true}
  [_]
  [:p#leak (helper-sub)])

;; ===========================================================================

(deftest the-posture-is-genuinely-production
  (testing "Non-vacuity for everything below: this build really did fold
            the debug gate away. A `with-redefs` rebind could not have
            produced this — `debug-enabled?` is a load-time constant."
    (is (false? interop/debug-enabled?)
        "interop/debug-enabled? is false under :advanced + goog.DEBUG=false")))

(deftest a-read-with-no-candidate-still-raises-in-production
  (testing "The candidate consultation in `cell/observe!` is an
            unconditional `when`, and `error/throw-error!` is not gated —
            so the refusal survives `:advanced`, with its stable
            diagnostic id intact. A keyword id is data, not a message, so
            it is the thing a production caller can still branch on."
    (register!)
    (seed! {:total 7})
    (is (false? (cell/observing?)) "non-vacuous: no candidate is open")
    (is (= outside-render (caught-id #(rf/with-frame fid (v/sub [:spike/total]))))
        "refused, with the id, in a production bundle")))

(deftest a-helper-mediated-read-with-no-candidate-still-raises-in-production
  (testing "The shape a build-time proof cannot see. Capture is dynamic,
            so the refusal reaches through an arbitrary ordinary helper
            exactly as it does an inline read — in production as in
            development."
    (register!)
    (seed! {:total 7})
    (is (= outside-render
           (caught-id #(rf/with-frame fid (tree/render [reads-through-helper {}]))))
        "the helper-mediated read was refused in a production bundle")))

(deftest a-mounted-shell-free-boundary-refuses-its-read-in-production
  (testing "The whole question, in the configuration it will actually
            ship in: a boundary with NO ViewCell, mounted through React,
            in an `:advanced` bundle with the debug gate folded away. The
            body reads; the read has no owner; the render fails loud and
            NOTHING is committed, rather than a page that will never
            update again being put on screen.

            HOW the failure arrives is a production fact worth recording,
            because it is not how it arrives in development. React 19
            routes an uncaught render error to the root's
            `onUncaughtError` and does NOT rethrow it to the caller that
            asked for the render — not even inside `flushSync`. In
            development `react/act` collects and rethrows it, so a dev
            assertion can simply catch the mount; in production the same
            error reaches the page as an uncaught error instead. It is
            still loud (a `window` error event, the console, and any
            `onerror` reporter an application has installed) and the
            boundary is still not committed — but a caller cannot
            `try`/`catch` it, which is exactly the kind of difference this
            file exists to find."
    (if-not (browser?)
      (is true "a real React mount needs a DOM host")
      (let [container  (js/document.createElement "div")
            caught     (atom nil)
            react-root (rdc/createRoot
                         container
                         #js {:onUncaughtError (fn [e _info] (reset! caught e))})]
        (register!)
        (seed! {:total 41})
        (is (= :elided (:view-cell (v/manifest elided-but-reads)))
            "non-vacuous: the analysis really did elide the ViewCell")
        (.appendChild js/document.body container)
        (try
          (try
            (react-dom/flushSync
              (fn [] (.render react-root (fr/element [elided-but-reads {}]))))
            (catch :default e (reset! caught e)))
          (is (= outside-render (:rf.error/id (ex-data @caught)))
              (str "refused with the stable id in a production bundle; got "
                   (pr-str @caught)))
          (is (nil? (some-> (.querySelector container "#leak") .-textContent))
              "and NOTHING was committed — no silently stale boundary reached the page")
          (finally
            (.remove container)))))))
