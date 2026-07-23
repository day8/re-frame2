(ns re-frame.freehand.root-error-callbacks-dom-cljs-test
  "FH-ROOT-002, the fourth arm — a root's host ERROR CALLBACKS are honest
  across an idempotent remount.

  React fixes a root's options at `createRoot`, so a reload that re-renders
  the live host root cannot hand React a fresh callback object. A root that
  passed the opts' callbacks straight to React would therefore install the
  FIRST mount's closures and silently ignore every later one — especially
  surprising on the HMR path, where a fresh closure per reload is ordinary.

  The fix is a stable delegate per key that reads the CURRENT callback off a
  mutable per-root cell, advanced by an accepted remount. This file is the
  adversarial proof of that, on the REAL React root path — real
  `createRoot`/`hydrateRoot` options, real React error kinds, the callback
  read back off what actually ran:

    - `:on-caught-error`      — a child throws under a real error boundary on
                                the reload; React catches and calls the root's
                                onCaughtError. The NEW callback runs, the
                                stale one does not.
    - `:on-uncaught-error`    — the same throw with NO boundary; React reports
                                it uncaught. Advanced across a remount, AND
                                honored when the first mount omitted the key
                                entirely (the delegate is installed regardless
                                of first-mount opts — a fix that only wired
                                keys present at createRoot would silently drop
                                a callback a reload adds).
    - `:on-recoverable-error` — a hydration mismatch, the canonical trigger,
                                proving the recoverable delegate reads the
                                authored callback off the same cell on the
                                real `hydrateRoot` path.

  An uncaught error with NO callback re-throws at the window and the browser
  runner fails the whole suite on it (the same reason the hydration suite
  always supplies `:on-recoverable-error`); every throw here is triggered
  only under a callback that swallows it.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no DOM
  to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]
            ;; The SSR artefact's manifest namespace publishes the discovery
            ;; hook the recoverable arm's hydrate needs, and owns the wire form
            ;; the arm plants. A test-tree require of the seam being exercised.
            [re-frame.ssr.manifest :as ssr-manifest]))

;; ---------------------------------------------------------------------------
;; The declarations. Module-level, because a declared view cannot close over a
;; test's locals. `poison?` toggles whether the shared child throws on its
;; NEXT render, so one view-id can be mounted clean and then re-mounted
;; throwing — which is exactly the reload shape under test.
;; ---------------------------------------------------------------------------

(defonce ^:private poison? (atom false))

(v/defview cb-child
  "The child that throws on render when the module toggle is set. The poison
  a boundary above catches, or — mounted bare — React reports uncaught."
  [_]
  (if @poison?
    (throw (ex-info "cb-child poison" {}))
    [:span#ok "ok"]))

(v/defview cb-guarded
  "`cb-child` under a REAL React error boundary, so a throw from the child is
  CAUGHT and surfaces at the root's onCaughtError. No `:on-error` — the
  boundary then dispatches nothing, so this needs no frame in scope."
  [_]
  [v/error-boundary {:fallback [:p#fb "caught"] :reset-key 1}
   [cb-child {}]])

(v/defview cb-bare
  "`cb-child` with NO boundary, so a throw surfaces at the root's
  onUncaughtError."
  [_]
  [cb-child {}])

(use-fixtures :each
  {:before (fn [] (reset! poison? false) (root/reset-registry!) (fr/reset-boundaries!))
   :after  (fn [] (reset! poison? false) (root/reset-registry!) (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- server-node!
  "A container carrying `html` plus its Root Manifest as the container's
  immediately following element sibling — the shape a hydrating page
  presents. Answers the container."
  [html manifest]
  (let [host (js/document.createElement "div")]
    (set! (.-innerHTML host)
          (str "<div>" html "</div>" (ssr-manifest/script-html manifest)))
    (.appendChild js/document.body host)
    (.-firstElementChild host)))

(defn- remove-node! [container]
  (some-> container .-parentElement .remove))

(defn- poll-until
  "Poll `pred` every 5ms up to ~2s, then call `k`. Bounded, so an outcome
  that never arrives lets the assertions fail honestly rather than hang."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- settle
  "Give a clean commit a moment to land, then call `k`."
  [k]
  (js/setTimeout k 60))

(defn- cbs
  "The three host error callbacks, each recording `[tag kind]` into `calls`
  when React runs it — so an assertion can read back WHICH mount's callback
  actually fired for a given error kind."
  [tag calls]
  {:on-uncaught-error    (fn [_ _] (swap! calls conj [tag :uncaught]))
   :on-caught-error      (fn [_ _] (swap! calls conj [tag :caught]))
   :on-recoverable-error (fn [_ _] (swap! calls conj [tag :recoverable]))})

(defn- fired? [calls tag kind]
  (boolean (some #{[tag kind]} @calls)))

;; ===========================================================================
;; :on-caught-error — a reload advances the caught callback (act, deterministic)
;; ===========================================================================

(deftest an-accepted-remount-advances-the-caught-error-callback
  (testing "Per FH-ROOT-002 (browser): a re-mount re-renders the live host
            root, whose options React fixed at createRoot. The first mount's
            :on-caught-error must NOT be the one React invokes after a reload
            changes it. A child throws under a real error boundary on the
            reload; React catches it and calls the root's onCaughtError. The
            NEW callback runs and the stale one does not — the stable delegate
            read the cell the remount advanced, not a closure React fixed."
    (if-not (browser?)
      (skip! "the browser job runs the caught-callback assertions")
      (async done
        (let [c     (host-node!)
              calls (atom [])]
          (reset! poison? false)
          (-> (act #(v/mount [cb-guarded {}] c (cbs :A calls)))
              (.then (fn [mounted]
                       (is (= "ok" (text c "#ok"))
                           "the clean first mount rendered its child")
                       (reset! poison? true)
                       (-> (act #(v/mount [cb-guarded {}] c (cbs :B calls)))
                           (.then (fn [_] mounted)))))
              (.then (fn [mounted]
                       (is (= "caught" (text c "#fb"))
                           "the boundary caught the reload's throw")
                       (is (fired? calls :B :caught)
                           (str "the remount's caught callback ran. Saw: " (pr-str @calls)))
                       (is (not (fired? calls :A :caught))
                           "the first mount's stale caught callback did NOT run")
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove c) (done))
                     (fn [e]
                       (is false (str "caught suite rejected: " e))
                       (.remove c)
                       (done)))))))))

;; ===========================================================================
;; :on-uncaught-error — a reload advances the uncaught callback (poll)
;; ===========================================================================

(deftest an-accepted-remount-advances-the-uncaught-error-callback
  (testing "Per FH-ROOT-002 (browser): the same claim for onUncaughtError, the
            key with no boundary above it. React reports the reload's throw
            uncaught, on its own schedule, so the assertion polls; the mount's
            own :on-uncaught-error swallows it, so the window sees no error and
            the runner is not tripped. The remount's callback runs; the first
            mount's does not."
    (if-not (browser?)
      (skip! "the browser job runs the uncaught-callback assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (reset! poison? false)
        (let [c       (host-node!)
              calls   (atom [])
              mounted (v/mount [cb-bare {}] c (cbs :A calls))]
          (settle
            (fn []
              (reset! poison? true)
              (v/mount [cb-bare {}] c (cbs :B calls))
              (poll-until
                #(fired? calls :B :uncaught)
                (fn []
                  (is (fired? calls :B :uncaught)
                      (str "the remount's uncaught callback ran. Saw: " (pr-str @calls)))
                  (is (not (fired? calls :A :uncaught))
                      "the first mount's stale uncaught callback did NOT run")
                  (v/unmount! mounted)
                  (.remove c)
                  (done))))))))))

(deftest a-remount-installs-an-uncaught-callback-the-first-mount-omitted
  (testing "Per FH-ROOT-002 (browser): the delegate is installed for every key
            regardless of the first mount's opts — so a callback a reload ADDS
            is honored. The first mount supplies NO callbacks; the reload adds
            :on-uncaught-error and throws. A fix that only wired the keys
            present at createRoot would install no delegate here, and React's
            default would re-throw the reload's error at the window instead —
            failing the runner rather than recording the callback."
    (if-not (browser?)
      (skip! "the browser job runs the added-callback assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (reset! poison? false)
        (let [c       (host-node!)
              calls   (atom [])
              mounted (v/mount [cb-bare {}] c {})]
          (settle
            (fn []
              (reset! poison? true)
              (v/mount [cb-bare {}] c (cbs :B calls))
              (poll-until
                #(fired? calls :B :uncaught)
                (fn []
                  (is (fired? calls :B :uncaught)
                      (str "a callback the first mount omitted took effect on the reload. "
                           "Saw: " (pr-str @calls)))
                  (v/unmount! mounted)
                  (.remove c)
                  (done))))))))))

;; ===========================================================================
;; :on-recoverable-error — the third key, on the real hydrateRoot path
;; ===========================================================================

(deftest a-hydrating-root-runs-the-recoverable-callback-through-the-cell
  (testing "Per FH-ROOT-002/006 (browser): the recoverable delegate reads the
            authored :on-recoverable-error off the SAME live cell the other
            two keys use — proven on the real hydrateRoot path by a hydration
            mismatch, React's canonical onRecoverableError trigger. The
            server's #who text disagrees with the client's, React recovers,
            and the host callback runs (composed under the framework's own
            mismatch signal)."
    (if-not (browser?)
      (skip! "the browser job runs the recoverable-callback assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [manifest {:rf.root/schema-version 1
                        :root-id                :fh.root/cb-hydrate
                        :identifier-prefix      "cb6-"}
              node     (server-node!
                         (str "<section id=\"greeting\"><h1 id=\"title\">Hello</h1>"
                              "<p id=\"who\">SERVER</p></section>")
                         manifest)
              calls    (atom [])
              mounted  (v/hydrate-root node [views/greeting {:name "client"}]
                                       (cbs :A calls))]
          (is (root/hydrated? mounted)
              "a container carrying server markup and a manifest hydrated")
          (poll-until
            #(fired? calls :A :recoverable)
            (fn []
              (is (fired? calls :A :recoverable)
                  (str "the recoverable callback fired through the cell. "
                       "Saw: " (pr-str @calls)))
              (v/unmount! mounted)
              (remove-node! node)
              (done))))))))
