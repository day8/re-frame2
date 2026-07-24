(ns re-frame.freehand.matrix-support
  "Shared plumbing for the F6b browser correctness MATRICES (EP-0036 §6,
  the donor deletion gate row \"browser correctness\").

  The matrices each prove one of the eight areas — controlled input,
  presence, top layer, behaviors, errors, routing, roots, hydration —
  green in a real browser, and (where both modes are meaningful) prove
  the INTERPRETED and COMPILED lowerings AGREE. Two claims per shape:
  each mode is individually correct, and the two produce the same DOM.

  Everything here is host-bearing — a real `react-dom/client` mount, a
  real `document` node, real keystrokes — so nothing in this namespace is
  a structural fact. The mode-agnostic pieces live here so eight matrix
  files assert against ONE mount discipline and ONE parity primitive
  rather than eight subtly different ones.

  The matrices ride the browser lane through their `-dom-cljs-test`
  suffix. They also match the node suites' broader regex, where there is
  no DOM to mount; each matrix says so rather than passing quietly, and
  the support here is written so its browser-only bodies are never CALLED
  under node (only referenced), so the namespace loads on both targets.

  NOT a test namespace (no `-cljs-test` suffix): it is required by the
  matrix files and carries no `deftest` of its own."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [clojure.string :as str]
            [cljs.test :refer [is]]))

;; ---------------------------------------------------------------------------
;; Runtime discrimination
;; ---------------------------------------------------------------------------

(defn browser?
  "True only where a real DOM the matrices can mount into exists."
  []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn skip!
  "The node arm of a browser matrix — records the reason the assertions
  did not run rather than passing an empty test silently."
  [why]
  (is true (str "a real browser mount is required — " why)))

;; ---------------------------------------------------------------------------
;; The act boundary
;; ---------------------------------------------------------------------------

(defn act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit and its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn live!
  "Leave React's act environment. A keystroke has to reach React as a
  genuine DISCRETE event, and a dependency-driven repaint must flush where
  the browser flushes it — both are diverted inside act."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn tick!
  "Yield one browser task, so React has flushed what a notification
  scheduled before anything is read off `document`."
  []
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0))))

;; ---------------------------------------------------------------------------
;; A React-DOM host, and a raw mount for frame-scoped forms
;; ---------------------------------------------------------------------------

(defn host-node!
  "A fresh container attached to the document body. Scoped to its own
  element on purpose — the browser runner renders its report into the
  page, so a matrix that reached for `document.body` would erase the
  thing reporting on it."
  []
  (let [container (.createElement js/document "div")]
    (.appendChild (.-body js/document) container)
    container))

(defn create-root!
  "A `react-dom/client` root over a fresh host; answers `[container root]`."
  []
  (let [container (host-node!)]
    [container (rdc/createRoot container)]))

(defn destroy-root!
  "Unmount `root` (inside the act environment, where React expects the
  teardown) and detach its container."
  [container root]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (.unmount root)
  (.remove container)
  nil)

;; ---------------------------------------------------------------------------
;; Typing, as the browser delivers it
;; ---------------------------------------------------------------------------

(defn set-native-value!
  "Write `s` through `HTMLInputElement`'s own prototype setter, so React's
  value tracker sees the mutation exactly as it does for a real keystroke.
  Assigning `.-value` directly leaves the tracker's record unchanged and
  React skips the change event."
  [node s]
  (.call (.-set (js/Object.getOwnPropertyDescriptor
                  (.-prototype js/HTMLInputElement) "value"))
         node s))

(defn fire-input!
  "A real bubbling `input` event carrying `data`."
  ([node] (fire-input! node nil))
  ([node data]
   (.dispatchEvent node (js/InputEvent. "input"
                                        #js {:bubbles true :cancelable false :data data}))))

(defn keystroke!
  "One keystroke: APPEND `ch` to whatever the node holds, then fire a real
  bubbling `input`. Appending is what makes a dropped character visible as
  a loss rather than papered over."
  [node ch]
  (set-native-value! node (str (.-value node) ch))
  (fire-input! node ch))

(defn type-string!
  "Type `s` one appended keystroke at a time."
  [node s]
  (doseq [ch (seq s)] (keystroke! node (str ch))))

(defn insert-at!
  "Insert `ch` at offset `at`, the way a browser does: the text grows at
  the caret, the caret follows it, then `input` fires."
  [node at ch]
  (let [text (.-value node)]
    (.focus node)
    (.setSelectionRange node at at)
    (set-native-value! node (str (subs text 0 at) ch (subs text at)))
    (.setSelectionRange node (inc at) (inc at))
    (fire-input! node ch)))

(defn caret [node] [(.-selectionStart node) (.-selectionEnd node)])

;; ---------------------------------------------------------------------------
;; Reading the DOM back
;; ---------------------------------------------------------------------------

(defn q
  "The first descendant of `container` matching `selector`, or nil."
  [container selector]
  (.querySelector container selector))

(defn text-of
  "The text content at `selector`, or nil when nothing matched."
  [container selector]
  (some-> (q container selector) .-textContent))

(defn attrs-of
  "EVERY attribute the matched element carries, as a plain map keyed by
  attribute name. Read off `document` rather than named one by one, so an
  attribute that should not be there fails a comparison too."
  [el]
  (when el
    (into {} (map (fn [a] [(.-name a) (.-value a)])) (array-seq (.-attributes el)))))

;; ---------------------------------------------------------------------------
;; The parity primitive — a canonical outline of a real subtree
;; ---------------------------------------------------------------------------

(defn outline
  "A canonical, comparable string for the real subtree rooted at `el`:
  tag name, attributes in NAME ORDER, and text, recursing into element
  children. Two mode renders that denote the same page produce the same
  outline; a divergence in tag, attribute, attribute value, text or child
  order is a difference the string carries.

  Attribute order is normalized (sorted) because the DOM's attribute
  order is an artefact of how each emitter wrote them, not a fact about
  the page. Everything else is preserved verbatim — this is the DOM the
  two lowerings actually built, not a projection that could hide a
  divergence the way a sampled assertion can."
  [el]
  (letfn [(node-str [n]
            (case (.-nodeType n)
              3 (.-nodeValue n)                     ; TEXT_NODE
              1 (let [tag   (str/lower-case (.-tagName n))
                      attrs (->> (array-seq (.-attributes n))
                                 (map (fn [a] [(.-name a) (.-value a)]))
                                 (sort-by first)
                                 (map (fn [[k v]] (str k "=" (pr-str v))))
                                 (str/join " "))
                      head  (if (str/blank? attrs) tag (str tag " " attrs))
                      kids  (str/join (map node-str (array-seq (.-childNodes n))))]
                  (str "<" head ">" kids "</" tag ">"))
              ""))]                                 ; comments etc. contribute nothing
    (node-str el)))

(defn outlines-agree?
  "Assert the two mode renders produce the SAME canonical outline —
  the load-bearing parity claim. Returns the shared outline on success so
  a caller can assert the render was non-empty too."
  [interpreted-el compiled-el note]
  (let [a (outline interpreted-el)
        b (outline compiled-el)]
    (is (= a b) (str note " — interpreted and compiled build the same real DOM"))
    a))

;; ---------------------------------------------------------------------------
;; The matrix sequencer — one mode, then the next, then done
;; ---------------------------------------------------------------------------

(defn each-mode
  "Run `f` (which returns a promise) for each entry in `modes`, in sequence,
  then call `done` EXACTLY ONCE. A rejection in one mode is caught as an
  assertion failure named by that mode and does NOT break the chain or the
  `done` accounting — a single broken cell can neither hang the suite nor
  double-fire `done` (which corrupts cljs.test's async state and stalls the
  whole run). This is the shape a matrix ROW takes: the same claim,
  asserted once per mode, named by the mode label."
  [modes f done]
  (-> (reduce (fn [p mode]
                (.then p (fn [_]
                           (-> (js/Promise.resolve (f mode))
                               (.catch (fn [e]
                                         (is false (str "mode " (pr-str (first mode))
                                                        " rejected: " e))
                                         nil))))))
              (js/Promise.resolve nil)
              modes)
      (.then (fn [_] (done)))
      (.catch (fn [e]
                (is false (str "a matrix row rejected: " e))
                (done)))))

(defn run-async
  "Run `thunk` (which returns a promise) and then call `done` EXACTLY ONCE.
  A rejection — or a SYNCHRONOUS throw inside `thunk` — is a named assertion
  failure followed by the single `done`. `thunk` runs inside a `.then` so a
  synchronous throw becomes a rejection rather than escaping the chain and
  stranding `done`. The terminal shape for a cross-mode cell that mounts
  both modes itself: the body never calls `done`, so it cannot double-fire
  it."
  [thunk done]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (thunk)))
      (.then (fn [_] (done)))
      (.catch (fn [e]
                (is false (str "an async matrix cell rejected: " e))
                (done)))))
