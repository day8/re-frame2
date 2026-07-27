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
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
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

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

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
            body reads; the read has no owner; the render fails loud
            instead of committing a page that will never update again."
    (if-not (browser?)
      (is true "a real React mount needs a DOM host")
      (async done
        (register!)
        (seed! {:total 41})
        (is (= :elided (:view-cell (v/manifest elided-but-reads)))
            "non-vacuous: the analysis really did elide the ViewCell")
        (let [container (js/document.createElement "div")]
          (.appendChild js/document.body container)
          (-> (act #(v/mount [elided-but-reads {}] container {:frame fid}))
              (.then (fn [mounted]
                       (is false
                           (str "the shell-free mount SUCCEEDED in production and rendered "
                                (pr-str (some-> (.querySelector container "#leak")
                                                .-textContent))
                                " — a read with no owner was not refused"))
                       (when mounted (.unmount (.-react-root ^root/Root mounted)))
                       (.remove container)
                       (done)))
              (.catch (fn [e]
                        (is (= outside-render (:rf.error/id (ex-data e)))
                            (str "refused loudly at the read, in production; got "
                                 (pr-str e)))
                        (.remove container)
                        (done)))))))))
