(ns day8.re-frame2-xray.views.edn-inspector-popup-stack-boundary-dom-cljs-test
  "THE POPUP STACK'S LIVE COLLECTOR WITNESS.

  `views.edn-inspector-popup/edn-inspector-popup-stack-view` is a Fresco
  BOUNDARY, crossed into the Reagent shell through `rf.fresco/as-component`,
  and it claims a concrete cost: the closed stack holds ONE live
  subscription edge instead of three, because `rf.fresco/sub` records its
  edge WHERE THE READ HAPPENS and the other two reads sit inside the `when`.
  No node row mounts a boundary, crosses the bridge, establishes a
  React-context frame or asks the collector anything, so every assertion
  here reads `container.querySelector` — the DOM React committed — or the
  collector's own tables.

  [[mount-stack!]] reproduces `shell.cljs`'s crossing exactly: the plain
  Reagent hiccup head `[edn-inspector-popup/edn-inspector-popup-stack]`
  inside a `[rf/frame-provider …]`.

    W1  the stack paints through the bridge, with the FRESCO head embedded
        and the payload read from the provider's frame.
    W3  a real click on the top popup's ✕ closes it alone.
    W4  the collector's reader edges: one closed, three open, one again
        once closed.

  This ns matches the `:browser-test` build's regex and also loads under
  `:node-test`, where every row short-circuits through [[browser?]] and
  reports the skip. `npm run test:browser` is its lane."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [clojure.string :as str]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector-popup :as popup]))

(def ^:private stack-frame
  "The frame this suite's stack instance owns. NOT `:rf/xray`, which is shared
  with every other suite on the page: a private frame is what makes W4's
  census a census of THIS boundary, since the collector's cell table is keyed
  `[frame-kw query-v]`."
  ::stack)

;; ---- the diagnostic ------------------------------------------------------
;;
;; A re-frame refusal raised inside a React render does NOT reach a
;; `try/catch` around `flushSync`: React 19 reports it to the console and
;; re-raises it as an UNCAUGHT window error, so its message appears nowhere a
;; row can read. Declared ABOVE the fixture because the fixture resets the atom.

(defonce ^:private !last-uncaught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the cell table would
                      ;; make W4 count a residue that is not this stack's.
                      (rf.fresco.impl.collector/reset-runtime!)
                      ;; Several suites on this page throw DELIBERATELY, so
                      ;; [[uncaught-note]] must not report a neighbour's error.
                      (reset! !last-uncaught nil))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; A Fresco boundary is NOT in Reagent's render queue, so draining Reagent's
;; queue commits nothing of this boundary's. Mount is committed with
;; `flushSync` and everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a real
  chance to commit — two animation frames and a macrotask. React registers a
  boundary's read set in a PASSIVE EFFECT, so W4's literal edge counts are
  taken after this rather than in the turn that moved the world."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- poll-until
  "Resolve as soon as `pred` answers truthy, or after `budget-ms`. The
  resolution value is `pred`'s last answer, so a caller asserts on the value
  rather than on the fact that polling ended."
  ([pred] (poll-until pred 2000))
  ([pred budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (let [v (pred)]
                     (cond
                       v                          (resolve v)
                       (> (js/Date.now) deadline) (resolve v)
                       :else (js/requestAnimationFrame (fn [_] (tick))))))]
           (tick)))))))

;; ---- the world -----------------------------------------------------------

(defn- setup!
  "Register Xray's handlers — which installs the popup's subs and events —
  and make the frames. `:rf/xray` (`shell/default-frame-id`) is made too:
  several Xray registrations reach it by name whichever frame an instance is
  mounted at, and a missing frame is a refusal raised inside a React render."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id shell/default-frame-id})
  (rf/make-frame {:id stack-frame})
  nil)

(defn- mount-stack!
  "Mount the popup stack the way `shell.cljs` mounts it, the enclosing
  `frame-provider` included. Committed synchronously — React 19's
  `root.render` is otherwise async and the first assertion would read an
  empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [popup/edn-inspector-popup-stack]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects have RUN by the time
  the next line reads anything. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- open!
  "Fire the popup stack's PUBLIC programmatic open against `frame` — the
  event a context-menu handler dispatches in production."
  [frame mount-id title value]
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open mount-id
                     {:value value :opts {:title title}}]
                    {:frame frame})
  nil)

(defn- uncaught-note
  "A suffix naming the last uncaught error, for a row whose subject is
  missing. Empty when nothing was thrown."
  []
  (if-some [e (when error-capture-armed? @!last-uncaught)]
    (str " — an uncaught error was raised during render: "
         (:rf.error/id (ex-data e) (ex-message e))
         " · at: "
         (let [s (str (.-stack ^js e))]
           (->> (str/split-lines s)
                (remove #(str/blank? %))
                (filter #(str/includes? % "at "))
                (take 14)
                (str/join " | "))))
    ""))

;; ---- reading the committed DOM back --------------------------------------

(defn- q [container sel] (.querySelector container sel))

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(defn- stack-el [container]
  (testid container "rf-xray-edn-inspector-popup-stack"))

(defn- popup-count
  "The stack container's own `data-rf-popup-count`, as the string React
  committed, or nil when the boundary rendered nothing at all."
  [container]
  (some-> (stack-el container) (.getAttribute "data-rf-popup-count")))

(defn- dialog-el [container id]
  (testid container (str "rf-xray-edn-inspector-popup-dialog-" id)))

(defn- title-text
  "The committed header label for `id` — the `:title` the OPEN event put in
  the entries slot, so it witnesses the entries read."
  [container id]
  (some-> (testid container (str "rf-xray-edn-inspector-popup-title-" id))
          (.-textContent)))

(defn- embedded-widget
  "The embedded edn-inspector's own committed container for `id`. The widget
  stamps `data-rf-mount-id` with the string `fresco-inspector` composed —
  `rf-xray-edn-inspector-popup-<mount-id>` — so finding it is evidence that
  the FRESCO head ran, not merely that a body div exists."
  [container id]
  (q container
     (str "[data-rf-mount-id=\"rf-xray-edn-inspector-popup-" id "\"]")))

;; ---- reading the collector back ------------------------------------------

(def ^:private boundary-reads
  "Every query the boundary reads, gate first — the order its body reads
  them in. Only the first is read while the stack is closed."
  [[popup/stack-slot] [popup/entries-slot] [:rf.xray/modal-positioning]])

(defn- reader-edges
  "The reader slots each of the three cells holds under `frame`, in
  `boundary-reads` order — the closed/open cost the boundary claims, read
  off the runtime. A vector rather than a total, so a failure names WHICH
  read is held."
  [frame]
  (mapv #(count (rf.fresco.test.runtime/cell-readers [frame %]))
        boundary-reads))

;; ===========================================================================
;; W1 — the stack PAINTS through the bridge, with the FRESCO head embedded
;; ===========================================================================

(deftest w1-popup-stack-paints-through-the-as-component-bridge
  ;; The embedded head is the half the node lane cannot reach:
  ;; `popup-stack-tree` passes `fresco-inspector` rather than `popup-chrome`'s
  ;; Reagent default, and a node-lane expansion INVOKES a fn head, so
  ;; `[x …]` and `(x …)` expand identically. The popup is opened only on the
  ;; provider's frame, so its title on screen says the reads resolved there.
  (if-not (browser?)
    (is true "skipped: no DOM under the :node-test build")
    (async done
      (setup!)
      (let [{:keys [container root]} (mount-stack! stack-frame)
            id "popupwit-w1"]
        (-> (settle)
            (.then
              (fn [_]
                (open! stack-frame id "Popupwit W1" {:alpha 1 :beta [:x :y]})
                (poll-until #(dialog-el container id))))
            (.then
              (fn [_]
                (is (= ["Popupwit W1" true]
                       [(title-text container id)
                        (some? (embedded-widget container id))])
                    (str "the popup committed with its title from the entries "
                         "slot and its widget from the FRESCO head"
                         (uncaught-note)))
                (teardown! root container)
                (done))))))))

;; ===========================================================================
;; W3 — a real click closes the top popup and only it
;; ===========================================================================

(deftest w3-a-real-click-closes-only-the-top-popup
  ;; A REAL click on the committed ✕, through the handler `popup-chrome`
  ;; built from the frame the BOUNDARY's render captured. A test
  ;; `dispatch-sync` would bypass that captured frame, which is the half a
  ;; wrong frame would break.
  (if-not (browser?)
    (is true "skipped: no DOM under the :node-test build")
    (async done
      (setup!)
      (let [{:keys [container root]} (mount-stack! stack-frame)
            a "popupwit-w3-a"
            b "popupwit-w3-b"]
        (open! stack-frame a "First" {:n 1})
        (open! stack-frame b "Second" {:n 2})
        (-> (poll-until #(= "2" (popup-count container)))
            (.then
              (fn [two?]
                (is two?
                    (str "two opens commit two popups. got="
                         (pr-str (popup-count container))
                         (uncaught-note)))
                ;; `some->`: on a tree where the boundary does not paint, a
                ;; bare `.click` raises an uncaught TypeError the browser
                ;; runner treats as a PAGEERROR, aborting every later row.
                (some-> (testid container
                                (str "rf-xray-edn-inspector-popup-close-" b))
                        (.click))
                (poll-until #(nil? (dialog-el container b)))))
            (.then
              (fn [_]
                (is (= [false true]
                       [(some? (dialog-el container b))
                        (some? (dialog-el container a))])
                    (str "the clicked top popup closed and the one beneath it "
                         "still stands" (uncaught-note)))
                (teardown! root container)
                (done))))))))

;; ===========================================================================
;; W4 — the collector's reader edges: one closed, three open, one again
;; ===========================================================================

(deftest w4-closed-stack-holds-one-edge-and-open-holds-three
  ;; The boundary's claimed CLOSED-STATE COST, measured. Every number is a
  ;; literal: a row comparing against something the boundary computed would
  ;; agree with itself under a regression. Each census follows a settle,
  ;; because React registers a boundary's read set in a passive effect, and
  ;; the row starts from the kit's `quiesced!` because a released cell is
  ;; reaped after a macrotask of grace.
  (if-not (browser?)
    (is true "skipped: no DOM under the :node-test build")
    (async done
      (setup!)
      (-> (rf.fresco.test.runtime/quiesced!)
          (.then
            (fn [_]
              (let [{:keys [container root]} (mount-stack! stack-frame)
                    id "popupwit-w4"]
                (-> (poll-until #(= 1 (first (reader-edges stack-frame))))
                    (.then (fn [_] (settle)))
                    (.then
                      (fn [_]
                        (is (= [1 0 0] (reader-edges stack-frame))
                            (str "CLOSED COSTS ONE EDGE: the gate read is held "
                                 "and the two gated reads are not"
                                 (uncaught-note)))
                        (open! stack-frame id "Popupwit W4" {:n 4})
                        (poll-until #(dialog-el container id))))
                    (.then (fn [_] (settle)))
                    (.then
                      (fn [_]
                        (is (= [1 1 1] (reader-edges stack-frame))
                            (str "OPEN COSTS THREE: the gated arm took an edge "
                                 "on entries and on positioning"
                                 (uncaught-note)))
                        (rf/dispatch-sync
                          [:rf.xray.edn-inspector-popup/close id]
                          {:frame stack-frame})
                        (poll-until #(nil? (stack-el container)))))
                    (.then (fn [_] (settle)))
                    (.then
                      (fn [_]
                        (is (= [1 0 0] (reader-edges stack-frame))
                            (str "CLOSING RETURNS TO ONE EDGE: the gated reads "
                                 "are released when the branch stops being taken"))
                        (teardown! root container)))))))
          (.catch (fn [e]
                    (is false (str "W4 never settled: " (.-message e)
                                   " " (pr-str (reader-edges stack-frame))))
                    nil))
          (.then (fn [_] (done)))))))
