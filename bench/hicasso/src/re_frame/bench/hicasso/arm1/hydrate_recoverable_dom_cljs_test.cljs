(ns re-frame.bench.hicasso.arm1.hydrate-recoverable-dom-cljs-test
  "THE HYDRATE DOOR'S RECOVERABLE-ERROR REPORTER (rf2-2rtt6.97).

  `arm1/mount/hydrate-root!` used to call `hydrateRoot` with no root
  options at all, so React's DEFAULT `onRecoverableError` ran and a
  hydration mismatch was an uncaught window error and NOTHING ELSE:
  Spec 011's `:rf.ssr/hydration-mismatch` never fired, and a mismatch was
  invisible to every tool that reads the instrumentation stream. This
  file is the witness for the repair.

  ## What it must prove, and the shape of each proof

  | Row | Claim | How it could lie |
  |---|---|---|
  | [[a-divergent-adoption-surfaces-the-framework-diagnostic]] | a real mismatch emits the diagnostic | the diagnostic could be emitted unconditionally |
  | [[a-clean-adoption-emits-no-diagnostic]] | a clean adoption stays silent | — it is the answer to the row above |
  | [[the-reporter-composes-over-the-default-and-does-not-swallow]] | the error is still REPORTED | a reporter that only emitted would fail open |
  | [[the-emit-is-bounded-to-the-adoption-window]] | a LATER recoverable error is not a mismatch | React holds the callback for the root's whole lifetime |

  ## Why row three exists at all

  Installing any `onRecoverableError` takes React's default OFF. A
  reporter that emitted the diagnostic and stopped would make the
  mismatch quieter than it was before the repair — the browser lane's
  runner treats an uncaught `pageerror` as fatal whatever the `cljs.test`
  tally says (`rf2-mwx08`), and a door that swallowed the error would
  have removed exactly that signal. So the reporter composes over
  React's default and never clobbers it, and the third row is what holds
  that true. **`rf2-mwx08` is not softened here and this file must not
  be read as softening it.**

  ## Why two rows call `preventDefault`

  Both rows that MANUFACTURE a recoverable error set
  `hydration-support/open-console-capture!`'s `:swallow-uncaught?`. Not a
  weakening: the error each provokes is the row's own subject and is
  asserted on, so it is not an unasserted throw — which is the case that
  rule exists for.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hydration-support
             :refer [adopted! open-console-capture! server-dom!]]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def ^:private frame-id ::arm1-hydrate-recoverable)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!) (rt/reset-body-runs!))}))

(defn- skip! [why]
  (is true (str "a hydration claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rt/reset-runtime!)
  (rt/reset-body-runs!)
  frame-id)

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------

(defview screen
  "One boundary with one text child, and the text is a PROP.

  A prop is what makes the divergence a one-argument change: the server
  bytes and the client tree can be made to disagree on text and on
  nothing else, with no second frame, no reseed, and no second
  subscription to explain away. A single text child on purpose — React's
  SSR puts a `<!-- -->` separator between adjacent text runs and a client
  render does not, so two children would be measuring rf2-2rtt6.88's
  known text-separator divergence instead of this reporter."
  [{:keys [label]}]
  [:div.screen [:h1.title label]])

;; ---------------------------------------------------------------------------
;; The fixture doors
;; ---------------------------------------------------------------------------

(defn- server-html!
  "The bytes an SSR route would deliver for `[screen {:label label}]`.

  This arm's own client render, captured as `innerHTML` under an OPEN
  adoption window — the substitution `arm1/hydrate_dom_cljs_test`
  documents. Honest for what this file asserts (whether a DIVERGENCE is
  reported) and irrelevant to what it does not (byte identity, which is
  the SSR spike's claim and is made against `react-dom/server`)."
  [label]
  (rt/open-adoption-window!)
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-id [screen {:label label}])
        html      (.-innerHTML container)]
    (mount/release! handle)
    html))

(defn- watch-mismatches!
  "Capture every `:rf.ssr/hydration-mismatch` the framework emits, on the
  live trace stream. Answers `{:seen atom :stop! fn}`; `stop!` is
  idempotent.

  The diagnostic is frameless — it fires from a React root-error
  callback, outside any dispatch scope — so it streams to listeners and
  never reaches a per-frame ring. A listener is therefore the only place
  it can be read."
  []
  (let [seen (atom [])
        k    (keyword (str "rf2-2rtt6-97-" (gensym)))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                   (swap! seen conj ev))))
    {:seen seen :stop! (fn [] (trace-tooling/unregister-listener! k) @seen)}))

(defn- tags-of
  "The diagnostic's tags, merged with its top level, so a read is robust
  to whichever slots the envelope hoists."
  [ev]
  (merge (:tags ev) ev))

;; ===========================================================================
;; 1 — a divergent adoption surfaces Spec 011's diagnostic
;; ===========================================================================

(deftest a-divergent-adoption-surfaces-the-framework-diagnostic
  (testing "**the repair, stated as a measurement.** Server bytes saying
           `server` adopted by a client tree saying `client` is a text
           divergence React RECOVERS FROM, and a recovered divergence is
           exactly what `onRecoverableError` carries. The door must
           surface it as `:rf.ssr/hydration-mismatch`, tagged with its own
           site — with no root options this emitted NOTHING and the whole
           complaint was an uncaught window error"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (fresh!)
          (let [html      (server-html! "server")
                container (server-dom! html)
                {:keys [seen stop!]} (watch-mismatches!)
                ;; MANUFACTURED fault, asserted on — see the ns docstring.
                {:keys [captured close!]} (open-console-capture!
                                            {:swallow-uncaught? true})
                handle    (mount/hydrate-root! container frame-id
                                               [screen {:label "client"}])]
            (-> (adopted!)
                (.then
                  (fn [ok]
                    (close!)
                    (stop!)
                    (try
                      (is (true? ok) "the adoption completed")
                      (is (= 1 (count @seen))
                          (str "the door emitted exactly one "
                               ":rf.ssr/hydration-mismatch. Saw: "
                               (pr-str @seen)))
                      (when-let [mm (first @seen)]
                        (let [tags (tags-of mm)]
                          (is (= :rf.ssr/hydration-mismatch (:operation mm)))
                          (is (= 're-frame.bench.hicasso.arm1.mount/hydrate-root!
                                 (:where tags))
                              "tier-discriminated by :where — this door's site")
                          (is (= :warned-and-replaced (:recovery tags))
                              "React had already patched the DOM when the
                               callback ran, so there is no escalation to
                               report")
                          (is (string? (:error tags))
                              "and the recoverable error's own message rides
                               along")
                          (is (not (contains? (or (:tags mm) {}) :server-hash))
                              "no fabricated :server-hash — a React-element
                               root has no hash channel")))
                      (is (= "client" (.-textContent
                                        (.querySelector container ".title")))
                          "and the CLIENT's model won, as a recovered
                           hydration mismatch's repair must")
                      (is (seq @captured)
                          (str "the complaint also reached a channel this
                                witness watches: " (pr-str @captured)))
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; 2 — and it is a READING, not a constant
;; ===========================================================================

(deftest a-clean-adoption-emits-no-diagnostic
  (testing "**the answer to row one.** A diagnostic that fired on every
           adoption would agree with every page. The same door, the same
           screen, and server bytes that MATCH the client tree: nothing is
           emitted, and nothing is reported on either console channel
           either"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (fresh!)
          (let [html      (server-html! "agreed")
                container (server-dom! html)
                {:keys [seen stop!]} (watch-mismatches!)
                {:keys [captured close!]} (open-console-capture!)
                handle    (mount/hydrate-root! container frame-id
                                               [screen {:label "agreed"}])]
            (-> (adopted!)
                (.then
                  (fn [ok]
                    (close!)
                    (stop!)
                    (try
                      (is (true? ok) "the adoption completed")
                      (is (= [] @seen)
                          (str "a clean adoption emits no "
                               ":rf.ssr/hydration-mismatch. Saw: "
                               (pr-str @seen)))
                      (is (= [] @captured)
                          (str "and React reported nothing on either channel: "
                               (pr-str @captured)))
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; 3 — the reporter COMPOSES over React's default; it does not swallow
;; ===========================================================================

(deftest the-reporter-composes-over-the-default-and-does-not-swallow
  (testing "**the row that keeps `rf2-mwx08` intact.** Installing any
           `onRecoverableError` takes React's default OFF, so a reporter
           that only emitted the diagnostic would make a mismatch QUIETER
           than it was before the repair — the uncaught `pageerror` the
           runner treats as fatal would simply stop happening. Drive the
           REAL callback and read both outcomes: the diagnostic fires AND
           the error is still reported"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (rt/open-adoption-window!)
        (let [{:keys [seen stop!]} (watch-mismatches!)
              ;; MANUFACTURED fault, asserted on — see the ns docstring.
              {:keys [captured close!]} (open-console-capture!
                                          {:swallow-uncaught? true})]
          (try
            (mount/hydration-reporter (js/Error. "manufactured recoverable") nil)
            (is (= 1 (count @seen)) "the diagnostic fired")
            (is (some #(re-find #"manufactured recoverable" %) @captured)
                (str "and the error was STILL reported — the reporter composes
                      over React's default rather than replacing it. Saw: "
                     (pr-str @captured)))
            (finally
              (close!)
              (stop!)
              (rt/close-adoption-window!))))))))

;; ===========================================================================
;; 4 — the emit is bounded to the adoption window
;; ===========================================================================

(deftest the-emit-is-bounded-to-the-adoption-window
  (testing "React holds a hydrating root's `onRecoverableError` for the
           root's WHOLE LIFETIME and fires it for later recoverable errors
           too — a concurrent render it retried and recovered. Labelling
           one of those a hydration mismatch would be a false diagnostic,
           so the emit is bounded to the window `hydrate-root!` opens and
           the closer's passive effect shuts. Drive the REAL callback
           across that boundary: the report happens in BOTH windows, the
           emit in only one"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [{:keys [seen stop!]} (watch-mismatches!)
              ;; MANUFACTURED faults, asserted on — see the ns docstring.
              {:keys [captured close!]} (open-console-capture!
                                          {:swallow-uncaught? true})]
          (try
            (rt/open-adoption-window!)
            (mount/hydration-reporter (js/Error. "inside the window") nil)
            (is (= 1 (count @seen)) "inside the window it is a mismatch")
            (rt/close-adoption-window!)
            (mount/hydration-reporter (js/Error. "after the window") nil)
            (is (= 1 (count @seen))
                (str "and after the window it is NOT — no second emit. Saw: "
                     (pr-str @seen)))
            (is (= 2 (count @captured))
                (str "while BOTH were reported, because delegation is
                      unconditional. Saw: " (pr-str @captured)))
            (finally
              (close!)
              (stop!)
              (rt/close-adoption-window!))))))))
