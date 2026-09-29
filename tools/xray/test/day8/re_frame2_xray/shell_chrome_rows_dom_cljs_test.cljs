(ns day8.re-frame2-xray.shell-chrome-rows-dom-cljs-test
  "Real-DOM witness that Xray's fixed chrome rows keep their natural height
  when the host gives the shell a definite height.

  ## Why it can go wrong

  The shell is a flex column. L4, the detail panel, is `flex 1 1 auto` with
  `min-height 0`, so it is the one child meant to give up space and scroll.
  Every other child — the chrome ribbon, the events-ribbon track, the event
  list, the L2/L3 seam and the tab bar — is fixed chrome. The recommended
  inline host pins the pane to the viewport's height, so the column has a
  definite height, and whenever L4's content is taller than the room left
  for it the column must shrink its children. A chrome row left at the
  default `flex-shrink: 1` gives up height in proportion to its size, and
  the ribbon and the tab bar are squeezed below their design height.

  ## What the row measures

  The same shell is mounted twice with the same tall L4 content: once in a
  host of fixed height, and once in a host that grows with its content. The
  unconstrained host is the control — nothing has to shrink there, so its
  heights are each row's natural ones — and every chrome row must read the
  same height in both.

  The tall content is a filler appended to the committed L4 panel. It stands
  for any panel body taller than the viewport (a long Epoch, a large app-db),
  without tying the row to what one panel happens to render.

  ## Node-lane behaviour

  This ns matches the `:browser-test` build's regex and also loads under
  `:node-test`, where the row short-circuits through [[browser?]] and
  reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private shell-frame
  "A private frame for this suite's shell, so nothing here shares state with
  the production singleton or a neighbouring suite."
  ::shell)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving after two animation frames and a macrotask, so layout
  reflects everything committed before it."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- poll-until
  "Resolve as soon as `pred` answers truthy, or after `budget-ms`, with
  `pred`'s last answer."
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

(defn- setup!
  "Register Xray's handlers, which registers every Dynamic panel's L4 tab,
  and make the frames the shell reads. `:rf/xray` is made too: several
  registrations reach the production singleton by name whatever frame an
  instance is mounted at."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id shell/default-frame-id})
  (rf/make-frame {:id shell-frame})
  nil)

(def ^:private chrome-rows
  "The shell column's fixed children, by testid. The events-ribbon TRACK is
  the flex child rather than the toolbar inside it."
  ["rf-xray-ribbon"
   "rf-xray-events-ribbon-collapse"
   "rf-xray-event-list-wrap"
   "rf-xray-event-list-seam"
   "rf-xray-tab-bar"])

(def ^:private rows-with-height
  "The chrome rows that are open in an empty shell, so their natural height is
  positive. The events-ribbon track is closed with no filters and reads 0 in
  both hosts, which is compared but proves nothing on its own."
  #{"rf-xray-ribbon" "rf-xray-event-list-wrap" "rf-xray-event-list-seam"
    "rf-xray-tab-bar"})

(defn- testid [container id]
  (.querySelector container (str "[data-testid=\"" id "\"]")))

(defn- l4-node [container]
  (.querySelector container "[data-testid^=\"rf-xray-detail-panel-\"][role=\"tabpanel\"]"))

(defn- make-host!
  "A host element in the document. `:fixed` gives it a definite height, the
  way the pinned inline host layout does; `:grows` leaves its height to its
  content."
  [kind]
  (let [outer (.createElement js/document "div")
        host  (.createElement js/document "div")]
    (case kind
      :fixed (do (set! (.. outer -style -cssText) "display: flex; height: 400px;")
                 (set! (.. host -style -cssText)
                       "flex: 0 0 600px; min-width: 0; overflow: hidden;"))
      :grows (set! (.. host -style -cssText) "width: 600px;"))
    (.appendChild outer host)
    (.appendChild (.-body js/document) outer)
    {:outer outer :host host}))

(defn- filler-px
  "Taller than any viewport the runner uses, so L4's content always exceeds
  the room a definite-height shell leaves it."
  []
  (max 3000 (* 3 (.-innerHeight js/window))))

(defn- mount-and-measure!
  "Mount the shell into a `kind` host through Xray's own Fresco root, give L4
  tall content, and resolve with each chrome row's height and L4's box."
  [kind]
  (let [{:keys [outer host]} (make-host! kind)
        root   (rf.fresco/client-root)
        filler (filler-px)]
    (rf.fresco/render! root
                       [rf.fresco/frame-provider {:frame shell-frame}
                        [shell/ShellView {:frame-id shell-frame}]]
                       host)
    (-> (poll-until #(l4-node host))
        (.then (fn [l4]
                 (when l4
                   (let [tall (.createElement js/document "div")]
                     (set! (.. tall -style -height) (str filler "px"))
                     (.appendChild l4 tall)))
                 (settle)))
        (.then (fn [_]
                 (let [l4 (l4-node host)
                       m  {:rows      (into {}
                                            (map (fn [id]
                                                   [id (some-> (testid host id)
                                                               (.getBoundingClientRect)
                                                               (.-height))]))
                                            chrome-rows)
                           :l4-client (some-> l4 (.-clientHeight))
                           :l4-scroll (some-> l4 (.-scrollHeight))
                           :filler    filler}]
                   (rf.fresco/unmount! root)
                   (.remove outer)
                   m))))))

(deftest chrome-rows-keep-their-height-in-a-definite-height-host
  (testing "with L4's content taller than the viewport, every fixed chrome row
            reads the same height in a fixed-height host as in a host that
            grows with its content, and only L4 gives up space"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [!natural (atom nil)]
          (-> (mount-and-measure! :grows)
              (.then (fn [natural]
                       (reset! !natural natural)
                       (mount-and-measure! :fixed)))
              (.then
                (fn [pinned]
                  (let [natural @!natural]
                    (is (>= (:l4-client natural) (:filler natural))
                        (str "CONTROL: in the growing host nothing is squeezed, "
                             "so L4 holds all of its content. L4 "
                             (:l4-client natural) "px, filler "
                             (:filler natural) "px"))
                    (is (< (:l4-client pinned) (:l4-scroll pinned))
                        (str "NON-VACUITY: in the fixed-height host L4 gave up "
                             "space and scrolls, so the shell really was "
                             "constrained. L4 client " (:l4-client pinned)
                             "px, scroll " (:l4-scroll pinned) "px"))
                    (doseq [id rows-with-height]
                      (is (pos? (or (get-in natural [:rows id]) 0))
                          (str "NON-VACUITY: " id " is committed with a positive "
                               "natural height. Got: " (get-in natural [:rows id]))))
                    (doseq [id chrome-rows]
                      (is (= (get-in natural [:rows id]) (get-in pinned [:rows id]))
                          (str id " keeps its natural height in a definite-height "
                               "host. Natural " (get-in natural [:rows id])
                               "px, pinned " (get-in pinned [:rows id]) "px"))))))
              (.catch (fn [e]
                        (is false (str "the row never settled: " (.-message e)))
                        nil))
              (.then (fn [_] (done)))))))))
