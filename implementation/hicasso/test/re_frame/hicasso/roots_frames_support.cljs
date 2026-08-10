(ns re-frame.hicasso.roots-frames-support
  "THE MULTI-ROOT WITNESS HARNESS — the observables, and the reasons they
  are these observables (rf2-hic-012).

  Two suites read this file: `roots-frames-isolation-dom-cljs-test` and
  `roots-frames-hydration-dom-cljs-test`. It exists because both of them
  have to answer the same awkward question, and answering it twice by
  hand is how the two answers drift apart.

  ## The awkward question

  **React will make a broken frame boundary look correct in the final
  DOM.** A boundary that resolved the wrong frame still renders *a*
  value; a root whose adoption was thrown away still ends the event
  loop displaying the right markup, because React re-rendered it from
  the client model it already had. A witness that reads
  `.-textContent` after the dust settles is therefore not witnessing
  this runtime at all — it is witnessing React's willingness to repair,
  and it would stay green through a fault that deleted every property
  this bead exists to protect.

  So every observable below is one of three kinds, and each kind is
  chosen because **React cannot forge it**:

  1. **The cell table's KEYS** ([[cell-keys]], [[cell-frames]]). A
     sub-key is `[frame-kw query-v]`
     (`re-frame.hicasso.impl.collector`, the cell-table header), so two
     frames reading one query is two cells and one frame reading it
     twice is one cell with two readers. React has no opinion about
     this structure and no way to repair it: a frame leak is a *count*,
     visible before any DOM is read and unaffected by any re-render
     React performs afterwards.

  2. **Body runs** (`impl.collector/body-runs`, monotone and always
     on). It counts bodies actually INVOKED, so a commit that re-ran a
     boundary it should not have is a number, not an appearance. React's
     own end-of-event repair *raises* this count rather than hiding it —
     which is the direction that makes it safe: the repair cannot make a
     spurious run look like no run.

  3. **A DOM-node EXPANDO** ([[stamp-server-nodes!]]). An expando is a
     JS property on the node object. It cannot survive serialisation and
     it cannot be reconstructed, so a node still carrying it after
     hydration is *the very node the server markup produced*. Adoption
     and re-creation are indistinguishable in `innerHTML` and total
     opposites here. This is the only observable in the file that can
     tell adoption from a client render that happens to agree.

  ## Provenance

  The three techniques are the prototype's, in
  `re-frame.bench.hicasso.arm1.hydration-support` and
  `re-frame.bench.hicasso.arm1.hframe-dom-cljs-test`, reimplemented here
  rather than imported: the package may not `:require` the bench tree
  (`frozen-sources.edn` `:forbidden-import-prefixes`), and
  `hicasso/scripts/check_freeze.py` is what says so. Naming them in prose
  is provenance and is explicitly permitted — the gate parses `:require`
  forms, not docstrings."
  (:require [cljs.test :refer-macros [is]]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.trace.tooling :as trace-tooling]))

;; ---------------------------------------------------------------------------
;; Lane
;; ---------------------------------------------------------------------------

(defn leave-act-environment!
  "React's `act` queue is not the browser's scheduler, and every reading
  in these suites is taken outside it — a commit measured here is the
  commit a user's page performs.

  Set outright rather than imported: the helper that carries this line in
  the prototype (`re-frame.bench.hicasso.lane/leave-act-environment!`)
  lives in the bench tree, which the freeze gate forbids this package
  from importing. The package smoke carries the same inlining for the
  same reason."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn skip!
  "The node lane's stated skip. `:node-test` compiles these namespaces
  too (`:ns-regexp \"cljs-test$\"` matches `-dom-cljs-test`), and every
  DOM claim in this arm degrades to a stated skip there rather than to a
  false green."
  [why]
  (is true (str "a multi-root claim needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; Kernel observables — the cell table, read as a SHAPE
;; ---------------------------------------------------------------------------

(defn cell-keys
  "Every live sub-key, as a set. A sub-key is `[frame-kw query-v]`.

  **This is the frame-isolation observable.** One view mounted under two
  frames, each reading the same query, must produce two keys that differ
  only in their frame. A runtime that resolved one frame for both would
  produce ONE key — and would still render two visually plausible
  subtrees, which is exactly why the DOM is not where this is read."
  []
  (set (keys @collector/!cells)))

(defn cell-frames
  "The frames the live cell table mentions, as a set."
  []
  (into #{} (map first) (cell-keys)))

(defn readers-of
  "How many registrations read `sub-key` — the cell's own reader list,
  which since rf2-dabt3 is both the reverse edge and the reference.

  Two isolated frames give one reader each. A leak gives two readers on
  one key, and `(+ 1 1)` and `2` are different numbers on different
  keys."
  [sub-key]
  (count (inventory/cell-readers sub-key)))

(defn frame-memo-frames
  "The frames the arm's frame memo holds a row for.

  Populated by RENDER: `impl.collector/run-once` binds
  `(frame-dispatch frame-kw)` for each body's dynamic extent, and that
  lookup is what mints the frame's row. So two frames rendering is two
  entries, and a runtime that resolved one frame for both mounts would
  hold one — a second frame-keyed table saying the same thing the cell
  keys say, on a different axis.

  Since rf2-x874 `impl.frames/!frame-ops` is ONE row per frame rather
  than two sibling tables: the row carries the incarnation, the
  `capture-frame` bundle pinned to it, and the ambient dispatch closure
  over that bundle. Acquiring the dispatch acquires the bundle in the
  same act, which is what stops the two from ever describing different
  incarnations — and which is why a render, not a dispatch, is now what
  fills this. `impl.inventory/stats`'s `:frames` counts the same rows."
  []
  (set (keys @frames/!frame-ops)))

(defn frame-memo-row
  "The arm's memo row for `frame-kw`, or nil — `{:incarnation :ops
  :dispatch}`. Read by IDENTITY, never by value: what a witness wants to
  know is whether this is the same row it saw before."
  [frame-kw]
  (get @frames/!frame-ops frame-kw))

(defn body-runs-delta!
  "Run `f`, answer how many boundary bodies ran while it did.

  Zeroes the counter first, so the reading is a delta across `f` and not
  a reading of everything since the page loaded — the counter is monotone
  by design and `reset-runtime!` deliberately leaves it alone."
  [f]
  (collector/reset-body-runs!)
  (f)
  (collector/body-runs))

;; ---------------------------------------------------------------------------
;; Teardown census — read BETWEEN the unmount and the reset
;; ---------------------------------------------------------------------------

(def released
  "What [[teardown-census!]] must read after a clean teardown of the last
  live root."
  {:cell-refs 0 :boundaries 0 :edges 0})

(defn census
  "The three counts that are exact the instant React's unmount returns.

  `:cells` and `:entries` belong to the reapers a macrotask later, and
  asserting them here would be asserting a schedule rather than a
  release — [[quiesced!]] is where those two become readable."
  []
  (select-keys (inventory/residue) [:cell-refs :boundaries :edges]))

(defn teardown-census!
  "Unmount `handle`, read the census, and only THEN finish the release.

  The order is the whole load-bearing part. `mount/release!` calls
  `collector/reset-runtime!`, which disposes every cell and empties every
  table BY FIAT — so a census taken after it reads zeros whether the
  teardown released anything or not. That is the shape of gate that
  cannot go red, and this arm has already been bitten by it
  (`impl.mount/unmount!`'s own docstring, rf2-2rtt6.48).

  `:root nil` on the way out so the arm's teardown door does the rest —
  reset the runtime, drop the container — without unmounting a root that
  is already gone."
  [handle]
  (mount/unmount! handle)
  (let [c (census)]
    (mount/release! (assoc handle :root nil))
    c))

(defn quiesced!
  "The runtime's own settling point — every reaper armed before this call
  has run. `impl.inventory/quiesced!`, re-exported so a suite settles
  against the runtime's horizon rather than against a copy of it."
  []
  (inventory/quiesced!))

;; ---------------------------------------------------------------------------
;; Server bytes, and the node identity that survives them
;; ---------------------------------------------------------------------------

(defn server-html!
  "The bytes an SSR route would deliver for `hiccup` under `frame-kw`.

  Rendered on an ordinary root and released afterwards, so the runtime
  the hydration is measured on is empty. The prototype's
  `arm1.hydration-support/server-html!` does the same, and the honesty
  caveat is the same: these are the client tier's own bytes, so a row
  that cares about a *particular* string reads it back out of the
  captured markup rather than assuming it.

  **It no longer opens an adoption window** (rf2-6tmu). It used to, on
  the grounds that an open window is the state a server render is in —
  but the window was page-global then, so \"opening\" one was a free
  module write. A window is now minted per root and reaches a subtree
  only through the provider `impl.mount` installs for a HYDRATING root,
  and there is no product door that opens one around an ordinary render;
  giving the harness a private one would be inventing product API for a
  test. Nothing is lost, because the only reader is presence and the
  trees rendered through this fn carry none — see
  [[settled-server-html!]] for the ones that do."
  [frame-kw hiccup]
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-kw hiccup)
        html      (.-innerHTML container)]
    (mount/release! handle)
    html))

(defn server-dom!
  "A container carrying `html`, attached to the document — the page as it
  arrives, before any JavaScript has adopted it."
  [html]
  (let [container (mount/fresh-container!)]
    (set! (.-innerHTML container) html)
    container))

(def server-node-mark
  "An EXPANDO, not an attribute.

  An attribute round-trips through `innerHTML`; an expando does not. A
  node still answering to this after hydration is therefore the very node
  the server markup produced, rather than a replacement that happens to
  look alike — and telling those two apart is the entire difference
  between *adopted* and *re-rendered*."
  "hicassoRootsFramesServerNode")

(defn stamp-server-nodes!
  "Stamp every element in `container`, and answer it."
  [container]
  (doseq [n (array-seq (.querySelectorAll container "*"))]
    (unchecked-set n server-node-mark true))
  container)

(defn server-node?
  "Is `node` a node the server markup produced?"
  [node]
  (and (some? node) (true? (unchecked-get node server-node-mark))))

(defn every-server-node?
  "Do all of `container`'s `sel` matches still carry the stamp?

  **False for an EMPTY match**, because `every?` over nothing is
  vacuously true and a selector that quietly stopped matching is exactly
  how this check would quietly stop checking."
  [container sel]
  (let [ns' (array-seq (.querySelectorAll container sel))]
    (and (seq ns') (every? server-node? ns'))))

(defn adopted!
  "Wait for `handle`'s OWN adoption window to shut — that root's closer
  component's passive effect — and then for the reapers. Answers a
  promise of true, or of false if the window never shut inside
  `budget-ms`.

  Real timers only: no `act`, no `flushSync`. `impl.mount/hydrate-root!`
  deliberately does not force hydration synchronously, so there is no
  flush that would make this a straight line, and manufacturing one
  would manufacture a schedule no shipped caller has.

  **This is a NAMED root's completion signal, and before rf2-6tmu it
  could not be one.** The window it polls used to be one boolean for the
  whole page, so it answered *some* root's closer and never said which —
  which is why the two-root rows below were written against
  [[wait-until!]] and the cell table instead. Now the window is the one
  this root minted and nothing else can shut it, so waiting on it is
  waiting on this root."
  ([handle] (adopted! handle 3000))
  ([handle budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)
             window   (:adoption handle)]
         (letfn [(tick []
                   (cond
                     (not (roots/adopting? window))
                     ;; Past the closer AND past the entry reap horizon,
                     ;; so a reading of the entry cache is taken on the
                     ;; far side of the race.
                     (js/setTimeout (fn [] (resolve true)) 16)

                     (< deadline (js/Date.now))
                     (resolve false)

                     :else (js/setTimeout tick 4)))]
           (tick)))))))

(defn wait-until!
  "Poll `pred?` on real timers until it answers truthy. Answers a promise
  of true, or of false if `budget-ms` elapses first.

  How a two-root row waits on something that is not a window: the cell
  table, which acquires at COMMIT and therefore cannot mention a frame
  before that frame's root committed. [[adopted!]] is the other per-root
  signal and since rf2-6tmu it is a genuine one; the rows below use
  whichever names the fact they are about."
  ([pred?] (wait-until! pred? 3000))
  ([pred? budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (cond
                     (pred?)                   (js/setTimeout (fn [] (resolve true)) 16)
                     (< deadline (js/Date.now)) (resolve false)
                     :else                     (js/setTimeout tick 4)))]
           (tick)))))))

(defn settled-server-html!
  "The bytes an SSR route delivers for a tree that contains a PRESENCE
  tray: every child already PRESENT, which is what a server emits because
  a server has no enter transition to play. Answers a promise of the
  html; `settled?` is called with the container and says when the tray
  has stopped moving.

  Down here rather than beside [[server-html!]] only because it waits,
  and [[wait-until!]] is what it waits with.

  [[server-html!]] cannot be used for such a tree. It reads `innerHTML`
  on the line after a `flushSync` render, and on an ordinary root that is
  the FIRST pass — every child `:mounting`, wearing its `::h/mounting`
  overrides. Hydrating against those bytes would compare a settled client
  tree with an entering server tree and manufacture a divergence no row
  here is about.

  So the harness lets the client tier's own enter flip land and reads
  afterwards. That is the honest way to get settled bytes out of a client
  renderer, and it needs no adoption window: the tray reaches `:present`
  by PLAYING its transition, which is precisely the thing the hydrated
  root must not do."
  [frame-kw hiccup settled?]
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-kw hiccup)]
    (-> (wait-until! (fn [] (settled? container)))
        (.then (fn [ok]
                 (is (true? ok)
                     "premise: the server tree settled before its bytes were read")
                 (let [html (.-innerHTML container)]
                   (mount/release! handle)
                   html))))))

;; ---------------------------------------------------------------------------
;; The two complaint channels
;; ---------------------------------------------------------------------------

(defn open-console-capture!
  "Capture what the page COMPLAINS — `console.error` and uncaught window
  errors — until `close!` is called.

  The window/close split is the point: a capture whose window shuts when
  the call returns is shut across the render, and the render is the half
  that can complain. `impl.mount/hydrate-root!` returns before the tree is
  adopted, so a capture that closed on its return would see nothing.

  `:swallow-uncaught?` calls `preventDefault` on the error event.
  **Off by default, and it belongs only at a call site that MANUFACTURES
  a recoverable error and asserts on it** — anywhere else it is the
  fail-open the browser runner's pageerror rule exists to prevent
  (rf2-mwx08)."
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

(defn watch-mismatches!
  "Capture every `:rf.ssr/hydration-mismatch` the framework emits, on the
  live trace stream. Answers `{:seen atom :stop! fn}`; `stop!` is
  idempotent.

  A listener is the only place this can be read: the diagnostic fires
  from a React root-error callback, outside any dispatch scope, so it is
  frameless and never reaches a per-frame ring. `with-trace-recorder!`
  is the sanctioned macro for the same job but it brackets a SYNCHRONOUS
  body, and an adoption is not one."
  []
  (let [seen (atom [])
        k    (keyword (str "rf2-hic-012-" (gensym)))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                   (swap! seen conj ev))))
    {:seen seen :stop! (fn [] (trace-tooling/unregister-listener! k) @seen)}))

(defn tags-of
  "A diagnostic's tags merged with its top level, so a read is robust to
  whichever slots the envelope hoists."
  [ev]
  (merge (:tags ev) ev))
