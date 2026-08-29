(ns re-frame.bench.hicasso.arm1.hydration-support
  "THE THREE THINGS EVERY HYDRATION WITNESS NEEDS, in one place.

  Lifted verbatim out of
  [[re-frame.bench.hicasso.arm1.hydrate-dom-cljs-test]] when the SSR spike
  (rf2-2rtt6.87) became its second caller. Nothing here is new; what is
  new is that there is one copy.

  That matters more than tidiness. All three are the reason a hydration
  row can answer FALSE, and a second copy of a refusal is the copy that
  quietly weakens:

  - [[capture-console!]] is the only reason a `cljs.test` row can see a
    hydration mismatch at all. React 19 reports one by `console.error`
    and routes a recoverable error to `reportError` — a `window` `error`
    event — and NEITHER reaches `cljs.test`. A row asserting \"nothing
    complained\" that watched one channel, or neither, is green over a
    live failure.
  - [[stamp-server-nodes!]] is the only reason \"React ADOPTED the DOM\"
    is a measurement rather than a claim. The stamp is an EXPANDO, so it
    cannot survive re-serialisation: a node still carrying it after
    hydration is the very node the server markup produced.
  - [[adopted!]] is the only completion signal a plain `hydrateRoot`
    offers. `mount/hydrate-root!` refuses to `flushSync` (inventing a
    schedule no shipped caller has), so a witness waits for the closer's
    passive effect on real timers instead.

  Not a namespace anything outside the bench lane may depend on."
  (:require [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; The page as it arrives
;; ---------------------------------------------------------------------------

(defn server-dom!
  "A container carrying `html`, attached to the document — the page as it
  arrives, before any JavaScript has adopted it."
  [html]
  (let [container (mount/fresh-container!)]
    (set! (.-innerHTML container) html)
    container))

;; ---------------------------------------------------------------------------
;; React's two failure channels
;; ---------------------------------------------------------------------------

(defn open-console-capture!
  "Start watching React's two failure channels. Answers
  `{:captured atom :close! fn}` — the atom holds one string per
  complaint, and `close!` restores both channels and is idempotent.

  React 19 reports a hydration mismatch by `console.error` in a
  development build and routes a recoverable error to `reportError`,
  which surfaces as a `window` `error` event — and an exception React
  reports that way never reaches `cljs.test`, so a row asserting
  \"nothing complained\" that watched neither channel would be green over
  a live failure.

  ## Why the window is the CALLER's to close

  `hydrateRoot` is called plain (`mount/hydrate-root!` refuses to
  `flushSync`), so it RETURNS BEFORE THE TREE IS ADOPTED: React schedules
  the hydration render and the bodies run in a later turn. A capture
  whose window closes when the call returns is therefore open across the
  call and shut across the render — which is the half that can complain.
  A caller whose claim is about the ADOPTION must hold the window open
  until [[adopted!]] resolves, and this shape is what lets it.

  [[capture-console!]] is the synchronous special case, kept because a
  claim about one synchronous call wants exactly that window.

  ## `:swallow-uncaught?` — for a row that MANUFACTURES a fault, and
  ## nowhere else

  React reports a recoverable hydration error through `reportError`,
  which becomes an UNCAUGHT error, which the browser lane's runner treats
  as fatal whatever the `cljs.test` tally says (rf2-mwx08 — \"the suite
  may simply not assert on the regression that threw\"). That rule is
  right and this option does not soften it: a MUTATION PROOF causes the
  fault on purpose and DOES assert on it, so the error is not unasserted
  — it is the row's subject. Setting this calls `preventDefault` on the
  `error` event, which marks it handled, and the capture still records
  it, so the signal moves into the row's own assertion instead of being
  lost.

  **It is off by default and it belongs at a call site that MANUFACTURES
  a recoverable error and asserts on it — nowhere else.** Anywhere else
  it would be exactly the fail-open the runner's rule exists to prevent.
  The uncaught error stays uncaught at the DOOR: `mount/hydrate-root!`'s
  reporter emits the framework diagnostic and then ALWAYS reports
  (rf2-2rtt6.97), so composing a diagnostic in did not — and must not —
  make a mismatch quieter than React left it. Every call site that sets
  this is therefore a deliberate fault, not a door that stopped
  complaining."
  ([] (open-console-capture! nil))
  ([{:keys [swallow-uncaught?]}]
   (let [captured (atom [])
         original (.-error js/console)
         on-error (fn [^js e]
                    (swap! captured conj (str "window.error: " (.-message e)))
                    (when swallow-uncaught? (.preventDefault e)))
         closed?  (volatile! false)]
     (.addEventListener js/window "error" on-error)
     (set! (.-error js/console)
           (fn [& args]
             (swap! captured conj (str "console.error: " (apply str args)))
             nil))
     {:captured captured
      :close!   (fn []
                  (when-not @closed?
                    (vreset! closed? true)
                    (set! (.-error js/console) original)
                    (.removeEventListener js/window "error" on-error))
                  @captured)})))

(defn capture-console!
  "Run `f` with React's two failure channels captured, and answer
  `[result captured]`, where `captured` is an atom holding a vector of
  one string per complaint.

  **The window is exactly `f`'s synchronous extent.** For a claim about
  a hydration — which React finishes in a LATER turn — use
  [[open-console-capture!]] and close it after [[adopted!]] resolves."
  [f]
  (let [{:keys [captured close!]} (open-console-capture!)]
    (try
      [(f) captured]
      (finally (close!)))))

;; ---------------------------------------------------------------------------
;; Node identity across adoption
;; ---------------------------------------------------------------------------

(def server-node-mark
  "An EXPANDO, not an attribute — it cannot survive being re-serialised,
  so a node still carrying it after adoption is the very node the server
  markup produced rather than a replacement that happens to look alike."
  "hicassoServerNode")

(defn stamp-server-nodes!
  "Mark every element in the server DOM, so identity is checkable
  anywhere in the tree. Answers `container`."
  [container]
  (doseq [n (array-seq (.querySelectorAll container "*"))]
    (unchecked-set n server-node-mark true))
  container)

(defn server-node?
  "Is `node` still the node the server markup produced?"
  [node]
  (and (some? node) (true? (unchecked-get node server-node-mark))))

(defn every-server-node?
  "Is every element matching `sel` inside `container` still the server's
  own node? Answers false for an EMPTY match, because `every?` over
  nothing is vacuously true and a selector that stopped matching is
  exactly the way this check would silently stop checking."
  [container sel]
  (let [ns (array-seq (.querySelectorAll container sel))]
    (and (seq ns) (every? server-node? ns))))

;; ---------------------------------------------------------------------------
;; Waiting for the adoption, without pulling it forward
;; ---------------------------------------------------------------------------

(defn adopted!
  "Wait for the adoption window to CLOSE — the closer component's passive
  effect — and then for the entry reap horizon to pass. Answers a promise
  of true, or false if the window never shut inside `budget-ms`.

  Real timers only: no `act`, no `flushSync`. The claim every caller
  makes is about React's own turn, and a witness that pulled the commit
  forward would be a witness about the pull."
  ([] (adopted! 3000))
  ([budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (cond
                     (not (rt/adopting?))
                     ;; Past the closer AND past `entry-reap-horizon-ms`,
                     ;; so a reading of the entry cache is a reading taken
                     ;; on the far side of the race.
                     (js/setTimeout (fn [] (resolve true)) 16)

                     (< deadline (js/Date.now))
                     (resolve false)

                     :else (js/setTimeout tick 4)))]
           (tick)))))))
