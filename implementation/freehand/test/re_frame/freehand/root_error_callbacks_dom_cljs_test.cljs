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
            [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.root-views :as views]
            ;; The SSR artefact's manifest namespace publishes the discovery
            ;; hook the recoverable arm's hydrate needs, and owns the wire form
            ;; the arm plants. A test-tree require of the seam being exercised.
            [re-frame.ssr.manifest :as ssr-manifest]
            [re-frame.trace.tooling :as trace-tooling]))

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

;; ---------------------------------------------------------------------------
;; The DEFAULT arm's observation seams
;; ---------------------------------------------------------------------------
;;
;; Installing a stable delegate takes React's own default off the table for
;; every root, including one that authored no callback at all — so the
;; framework owes that root React's default, both tiers of it: the global
;; report a production build makes, AND the development guidance a programmer
;; debugs from. What is observable is what the default REPORTS THROUGH, so the
;; three host seams React's defaults use are redirected and read back.
;;
;; Two things make this non-vacuous where a React-warning assertion would not
;; be. React's own development warnings latch once per process, so a test that
;; asserted on one would pass or fail on what some earlier file rendered; every
;; record below is produced by the ROOT'S OWN default handler, which has no
;; latch and runs every time. And React's default and the framework's are told
;; apart by the component stack: React's message carries none, the framework's
;; carries the one React handed the delegate on the public `errorInfo`.
;;
;; Redirecting `reportError` is also what makes the no-callback arm safe to run
;; at all: an uncaught error with nothing to swallow it re-throws at the window
;; and fails the whole browser suite. The default arm IS the arm with no
;; callback, so its report has to land in the recorder rather than the window.

(defn- capture-reporting!
  "Redirect global `reportError`, `console.warn` and `console.error` into a
  record vector. Answers `[records restore!]`; `restore!` is idempotent."
  []
  (let [records  (atom [])
        report-0 (.-reportError js/globalThis)
        warn-0   (.-warn js/console)
        error-0  (.-error js/console)
        done?    (atom false)]
    (set! (.-reportError js/globalThis) (fn [e] (swap! records conj [:report e])))
    (set! (.-warn js/console)  (fn [& args] (swap! records conj (into [:warn] args))))
    (set! (.-error js/console) (fn [& args] (swap! records conj (into [:error] args))))
    [records (fn []
               (when (compare-and-set! done? false true)
                 (set! (.-reportError js/globalThis) report-0)
                 (set! (.-warn js/console) warn-0)
                 (set! (.-error js/console) error-0))
               nil)]))

(defn- said?
  "Did ONE record of `kind` carry every `needle` across its string
  arguments? One record, not one anywhere — a message assembled from two
  unrelated logs is not the message."
  [records kind & needles]
  (boolean
    (some (fn [r]
            (and (= kind (first r))
                 (let [t (str/join " " (filter string? (rest r)))]
                   (every? #(str/includes? t %) needles))))
          @records)))

(defn- reported? [records]
  (boolean (some #(= :report (first %)) @records)))

(defn- carried-error?
  "Did one record of `kind` carry the thrown value itself, rather than only
  prose about it?"
  [records kind]
  (boolean (some (fn [r] (and (= kind (first r))
                              (some #(instance? js/Error %) (rest r))))
                 @records)))

;; The two phrases React's own defaults exist to say, and the marker that says
;; the framework's replacement said them WITH the context React gave it.
(def ^:private uncaught-guidance "Consider adding an error boundary")
(def ^:private caught-guidance   "React will try to recreate this component tree")
(def ^:private stack-marker      "\n    at ")

;; ===========================================================================
;; The no-callback arm — uncaught
;; ===========================================================================

(deftest a-remount-that-omits-the-uncaught-callback-gets-reacts-whole-default
  (testing "Per FH-ROOT-002 (browser): a mount that omits `:on-uncaught-error`
            — a fresh root that never supplied one, or a reload that removed
            the one it had — falls back to REACT'S DEFAULT, not to a stub
            shaped like half of it. React's default reports globally AND, in a
            development build, says that nothing contained this failure, where
            to read about boundaries, and where in the tree it happened. A
            delegate that answered only the global report would delete the
            development half silently: the page still 'reports errors', and
            the programmer loses the only message that says what to do about
            it. The stale callback the reload removed must not run either."
    (if-not (browser?)
      (skip! "the browser job runs the default-diagnostic assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (reset! poison? false)
        (let [c       (host-node!)
              calls   (atom [])
              mounted (v/mount [cb-bare {}] c (cbs :A calls))]
          (settle
            (fn []
              (let [[records restore!] (capture-reporting!)]
                (reset! poison? true)
                (v/mount [cb-bare {}] c {})
                (poll-until
                  #(reported? records)
                  (fn []
                    (restore!)
                    (is (reported? records)
                        "the global report React's default makes still happened")
                    (is (said? records :warn uncaught-guidance "react.dev/link/error-boundaries")
                        (str "and so did the development guidance. Saw: "
                             (pr-str (mapv first @records))))
                    (is (said? records :warn uncaught-guidance stack-marker)
                        "carrying the component context React handed the delegate")
                    (is (not (fired? calls :A :uncaught))
                        "and the callback the reload removed did NOT run")
                    (v/unmount! mounted)
                    (.remove c)
                    (done)))))))))))

;; ===========================================================================
;; The no-callback arm — caught
;; ===========================================================================

(deftest a-remount-that-omits-the-caught-callback-gets-reacts-whole-default
  (testing "Per FH-ROOT-002 (browser): the same claim for `:on-caught-error`.
            A boundary handled the failure, so React's default is
            informational rather than a re-throw — but informational is not
            the same as bare. React's development default names the component
            tree and the boundary that will recreate it, and that is the whole
            reason a programmer reads it. A remount that removes the callback
            gets that message back, not just the Error object."
    (if-not (browser?)
      (skip! "the browser job runs the default-diagnostic assertions")
      (async done
        (let [c     (host-node!)
              calls (atom [])]
          (reset! poison? false)
          (-> (act #(v/mount [cb-guarded {}] c (cbs :A calls)))
              (.then (fn [mounted]
                       (is (= "ok" (text c "#ok")) "the clean first mount rendered")
                       (let [[records restore!] (capture-reporting!)]
                         (reset! poison? true)
                         (-> (act #(v/mount [cb-guarded {}] c {}))
                             (.then (fn [_] (restore!) [mounted records])
                                    (fn [e] (restore!) (js/Promise.reject e)))))))
              (.then (fn [[mounted records]]
                       (is (= "caught" (text c "#fb"))
                           "the boundary caught the reload's throw")
                       (is (carried-error? records :error)
                           "the default logged the error itself")
                       (is (said? records :error caught-guidance "error boundary you provided")
                           (str "and named the boundary that will recreate the tree. Saw: "
                                (pr-str (mapv first @records))))
                       (is (said? records :error caught-guidance stack-marker)
                           "with the component context React handed the delegate")
                       (is (not (fired? calls :A :caught))
                           "and the callback the reload removed did NOT run")
                       (is (not (reported? records))
                           "a CAUGHT error is not re-thrown to the window")
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove c) (done))
                     (fn [e]
                       (is false (str "the caught-default suite rejected: " e))
                       (.remove c)
                       (done)))))))))

;; ===========================================================================
;; A supplied callback REPLACES the default — it does not run beside it
;; ===========================================================================

(deftest a-supplied-caught-callback-replaces-the-default-rather-than-adding-to-it
  (testing "Per FH-ROOT-002 (browser): restoring React's default for the
            no-callback arm must not turn into reporting twice for the arm
            that HAS one. An authored callback owns the failure: it runs, the
            default does not, and the reload's callback is the one that runs.
            Without this row a fix for the missing default could be a delegate
            that always reports and then also calls the author's handler."
    (if-not (browser?)
      (skip! "the browser job runs the replacement assertions")
      (async done
        (let [c     (host-node!)
              calls (atom [])]
          (reset! poison? false)
          (-> (act #(v/mount [cb-guarded {}] c (cbs :A calls)))
              (.then (fn [mounted]
                       (let [[records restore!] (capture-reporting!)]
                         (reset! poison? true)
                         (-> (act #(v/mount [cb-guarded {}] c (cbs :B calls)))
                             (.then (fn [_] (restore!) [mounted records])
                                    (fn [e] (restore!) (js/Promise.reject e)))))))
              (.then (fn [[mounted records]]
                       (is (fired? calls :B :caught)
                           (str "the remount's caught callback ran. Saw: " (pr-str @calls)))
                       (is (not (fired? calls :A :caught))
                           "the stale one did not")
                       (is (not (said? records :error caught-guidance))
                           (str "and the default did NOT also report. Saw: "
                                (pr-str (mapv first @records))))
                       (is (not (reported? records))
                           "nor did anything reach the window")
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (.remove c) (done))
                     (fn [e]
                       (is false (str "the replacement suite rejected: " e))
                       (.remove c)
                       (done)))))))))

;; ===========================================================================
;; :on-recoverable-error across a real hydrate-then-remount
;; ===========================================================================

(deftest a-hydrated-roots-recoverable-callback-advances-and-then-falls-back
  (testing "Per FH-ROOT-002/006 (browser): the recoverable key is the one a
            single hydration can only exercise ONCE, so proving the delegate
            fires on the initial hydrate proves nothing about a reload. This
            drives the root's OWN live callback cell — the cell an accepted
            `v/mount` advances, over the real composed hydration reporter —
            across both remount shapes: a reload that CHANGES the callback,
            and a reload that REMOVES it. The framework's own hydration
            mismatch signal stays composed over both, because composing was
            never the author's to switch off."
    (if-not (browser?)
      (skip! "the browser job runs the recoverable-remount assertions")
      (async done
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [root-id   :fh.root/cb-advance
              prefix    "cb7-"
              manifest  {:rf.root/schema-version 1
                         :root-id                root-id
                         :identifier-prefix      prefix}
              node      (server-node!
                          (str "<section id=\"greeting\"><h1 id=\"title\">Hello</h1>"
                               "<p id=\"who\">SERVER</p></section>")
                          manifest)
              calls     (atom [])
              mounted   (v/hydrate-root node [views/greeting {:name "client"}]
                                        (cbs :A calls))]
          (is (root/hydrated? mounted) "the container hydrated")
          (poll-until
            #(fired? calls :A :recoverable)
            (fn []
              (is (fired? calls :A :recoverable)
                  (str "React's own hydration mismatch reached :A. Saw: " (pr-str @calls)))
              ;; The reporter React installed reads the root's live cell. Build
              ;; one over that SAME cell to drive the two remount shapes a
              ;; single hydration cannot produce a second mismatch for.
              (let [cell     (.-callbacks ^root/Root mounted)
                    window   #js {:adopting true}
                    reporter (root/hydration-reporter window root-id cell)
                    signals  (atom [])
                    k        (keyword (gensym "rf.fh-cb-advance-"))
                    info     #js {:componentStack "\n    at greeting (root_views.cljc)"}]
                (trace-tooling/register-listener!
                  k (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                               (swap! signals conj ev))))
                (try
                  (reset! calls [])
                  (v/mount [views/greeting {:name "client"}] node
                           (assoc (cbs :B calls) :root-id root-id :identifier-prefix prefix))
                  (reporter (js/Error. "recoverable probe B") info)
                  (is (fired? calls :B :recoverable)
                      (str "a reload that CHANGED the callback advanced it. Saw: "
                           (pr-str @calls)))
                  (is (not (fired? calls :A :recoverable))
                      "and the stale callback did not run")
                  (is (= 1 (count @signals))
                      "the framework's mismatch signal composed over the author's")

                  (let [[records restore!] (capture-reporting!)]
                    (reset! calls [])
                    (v/mount [views/greeting {:name "client"}] node
                             {:root-id root-id :identifier-prefix prefix})
                    (reporter (js/Error. "recoverable probe default") info)
                    (restore!)
                    (is (empty? @calls)
                        (str "a reload that REMOVED the callback called neither. Saw: "
                             (pr-str @calls)))
                    (is (reported? records)
                        "React's default for a recoverable error reported globally")
                    (is (= 2 (count @signals))
                        "and the framework's mismatch signal still composed"))
                  (finally
                    (trace-tooling/unregister-listener! k)
                    (v/unmount! mounted)
                    (remove-node! node)
                    (done)))))))))))

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
