(ns re-frame.freehand.mount-support
  "The ONE mounted-lifecycle facade for the Freehand browser tier: the
  `act` boundary, root/container lifecycle, and the residue assertion that
  reads a torn-down mount's books EMPTY.

  Everything here is host-bearing — a real `react-dom/client` commit, a
  real `document` node, real keystrokes — so nothing in this namespace is
  a structural fact. Structural claims belong to
  [[re-frame.freehand.test]], which runs headlessly on both hosts and has
  no roots to leak.

  ## Why one facade, and why this one is it (rf2-n9rzw)

  This namespace was `matrix-support`, scoped by its name to the F6b
  browser correctness matrices. The name was the defect. Three fixture
  suites in the spine roster — the behaviors teardown suite, its deferred
  foreign-handle sibling, the controls kit and the mounted host door — each
  grew their OWN `act`, their own `[container root]` pair and their own
  two-line teardown, because nothing called `matrix-support` told them it
  was theirs to use. The `act` body was byte-identical in every copy.

  So the facade is not new; it is the existing one, renamed to what it
  actually is and given the piece it was missing. A second facade
  alongside it would have been the same defect with an extra file.

  ## The residue assertion is the load-bearing piece

  A teardown that unmounts and removes proves nothing about what SURVIVED
  it, and a leaked React root contaminates every later suite in a
  shared-process bundle — this corpus has seen one leak produce failures
  across dozens of unrelated suites. Cleanup in a per-test `:init-fn` is
  not the answer either: it runs BEFORE each test, so a retained root is
  masked by the next reset rather than reported.

  [[residue-clean!]] is therefore an assertion that runs AFTER teardown,
  over books the facade OWNS — every root it handed out, and what each
  one left behind — plus whatever substrate books the caller names. That
  is what makes `:residue :none` in a roster record a claim a run can
  falsify rather than a claim a reader has to trust.

  ## Using it

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {… :init-fn (fn [] (ms/reset-ledger!))}))

      (let [[container root] (ms/create-root!)]
        (-> (ms/act #(.render root …))
            (.then (fn [_] (ms/act #(ms/destroy-root! container root))))
            (.then (fn [_]
                     (ms/residue-clean! \"FH-…\" [[\"the connection table\"
                                                 #(behaviors/connection-count)]])
                     (done)))))

  The ledger is scoped to one test by [[reset-ledger!]] in the fixture's
  `:init-fn`, and read by [[residue-clean!]] at the END of the test. The
  reset is bookkeeping, not cleanup: it clears the facade's RECORD of the
  roots a previous test made, and tears nothing down, so it cannot hide a
  leak the way an `:init-fn` teardown does.

  The suites ride the browser lane through their `-dom-cljs-test` suffix.
  They also match the node suites' broader regex, where there is no DOM to
  mount; each says so rather than passing quietly, and everything here is
  written so its browser-only bodies are never CALLED under node (only
  referenced), so the namespace loads on both targets.

  NOT a test namespace (no `-cljs-test` suffix): it is required by the
  mounted suites and carries no `deftest` of its own. Dev/test scope
  ONLY — it lives under `test/` and nothing in a production bundle may
  reach it."
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
;; Root / container lifecycle, over a ledger the residue assertion reads
;; ---------------------------------------------------------------------------
;;
;; Every container this namespace creates is RECORDED, and every teardown
;; records what the torn-down mount left behind. That record is the whole
;; mechanism behind `residue-clean!`: without it, "nothing survived" would
;; be a claim about books the facade cannot see, and a suite that simply
;; never tore a root down would satisfy it by having nothing to check.
;;
;; The ledger is bookkeeping, never cleanup. `reset-ledger!` forgets what a
;; previous test made and tears nothing down — which is the difference
;; between scoping the record to a test and the anti-pattern this facade
;; exists to remove, where a per-test `:init-fn` reset runs BEFORE each test
;; and masks the residue the previous one left.

(defonce ^:private ledger (atom {:live [] :retired []}))

(defn reset-ledger!
  "Forget the roots a previous test created — the one line a mounted
  suite's `:init-fn` owes this facade.

  Tears NOTHING down, on purpose. A teardown here would run before each
  test and hide exactly what [[residue-clean!]] is looking for."
  []
  (reset! ledger {:live [] :retired []})
  nil)

(defn- enrol!
  "Record `container` as a root this test created and has not torn down.
  An unlabelled root is named by its position, so a residue failure still
  says WHICH of two mounts survived."
  [container label]
  (swap! ledger
         (fn [{:keys [live] :as l}]
           (assoc l :live (conj live {:label     (or label (str "root-" (count live)))
                                      :container container}))))
  container)

(defn host-node!
  "A fresh container attached to the document body, enrolled in the
  lifecycle ledger under `label`.

  Scoped to its own element on purpose — the browser runner renders its
  report into the page, so a suite that reached for `document.body` would
  erase the thing reporting on it.

  Use this when the mount goes through the door (`v/mount`) and the door
  owns the React root; retire it with [[remove-node!]] after
  `v/unmount!`. For a raw `react-dom/client` root, [[create-root!]] does
  both."
  ([] (host-node! nil))
  ([label]
   (let [container (.createElement js/document "div")]
     (.appendChild (.-body js/document) container)
     (enrol! container label))))

(defn create-root!
  "A `react-dom/client` root over a fresh enrolled host; answers
  `[container root]`.

  `opts` is an optional map: `:label` names the root in a residue failure,
  and `:on-uncaught-error` / `:on-caught-error` / `:on-recoverable-error`
  are handed to React as its own root-level error callbacks — spelled the
  way the door spells them (Spec 004 §root options), so a suite that wants
  a throw React would otherwise report to the page delivered somewhere it
  can assert on says so once, here."
  ([] (create-root! nil))
  ([{:keys [label on-uncaught-error on-caught-error on-recoverable-error]}]
   (let [container (host-node! label)
         js-opts   #js {}
         any?*     (or on-uncaught-error on-caught-error on-recoverable-error)]
     ;; Only the callbacks actually supplied are set. React reads these by
     ;; PRESENCE, so handing it an explicit `nil` would install a null
     ;; reporter and crash the first time it had something to report.
     (when on-uncaught-error    (set! (.-onUncaughtError js-opts) on-uncaught-error))
     (when on-caught-error      (set! (.-onCaughtError js-opts) on-caught-error))
     (when on-recoverable-error (set! (.-onRecoverableError js-opts) on-recoverable-error))
     [container (if any?*
                  (rdc/createRoot container js-opts)
                  (rdc/createRoot container))])))

(defn remove-node!
  "Detach `container` and retire its ledger entry, RECORDING what the
  mount left behind: how many child nodes survived the unmount, and
  whether the container was still attached afterwards.

  Both facts are read here rather than asserted here, so a failure is
  attributed to [[residue-clean!]] — which names the law — instead of to
  a helper. The children count is the one that catches a MISSING unmount:
  React empties a container when its root goes, so a container that still
  holds nodes was never unmounted."
  [container]
  (let [children-left (.-length (.-childNodes container))]
    (.remove container)
    (swap! ledger
           (fn [{:keys [live retired]}]
             (let [[gone staying] ((juxt filter remove) #(identical? container (:container %)) live)]
               {:live    (vec staying)
                :retired (into retired
                               (map (fn [entry]
                                      {:label           (:label entry)
                                       :children-left   children-left
                                       :still-attached? (.-isConnected container)}))
                               gone)})))
    nil))

(defn destroy-root!
  "Unmount `root` (inside the act environment, where React expects the
  teardown), then retire its container through [[remove-node!]].

  The whole teardown, in one place. It used to be two pasted lines in
  every mounted suite, which is how three of them came to tear down
  without ever asserting what survived (rf2-n9rzw)."
  [container root]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (.unmount root)
  (remove-node! container)
  nil)

;; ---------------------------------------------------------------------------
;; The residue assertion — an ABSENCE, read AFTER teardown
;; ---------------------------------------------------------------------------

(defn- read-as-empty?
  "Is `v` the emptiness a residue book reads when nothing survived — the
  exact integer ZERO for a count, or an empty collection for an index?

  A threshold is refused by construction: there is no `small enough`
  here, and a book that answers something which is neither a number nor a
  collection is a broken reader rather than a clean one."
  [v]
  (if (number? v) (zero? v) (and (coll? v) (empty? v))))

(defn residue-clean!
  "Assert that NOTHING a mounted run created survives it. `where` names
  the law — a fixture id and the moment — so a red row is attributable
  without a lookup.

  Three claims over the facade's OWN books, and then one per book the
  caller names:

    * every root this test created was torn down. A suite that simply
      returned without tearing down reds HERE, which no per-suite
      teardown can catch because it is the absence of a call.
    * every torn-down root left its container EMPTY and DETACHED. An
      unmount that did not happen leaves child nodes behind (React
      empties a container when its root goes); a `.remove` that did not
      happen leaves the container attached to the document.
    * at least one root was actually torn down — non-vacuity, so a suite
      that mounts nothing through this facade cannot satisfy the row by
      having no books to read.

  `books` is a sequence of `[description read-fn]` pairs naming the
  SUBSTRATE's own books: `read-fn` answers a count or a collection and
  the assertion is that it reads empty. They are read HERE, after
  teardown, which is the whole point — the same read in a per-test
  `:init-fn` runs BEFORE each test and masks a retained boundary instead
  of reporting it.

      (ms/residue-clean! \"FH-BEHAVIOR-005 — after the last unmount\"
                         [[\"the connection table\" #(behaviors/connection-count)]
                          [\"the live target index\" #(behaviors/target-ids)]])

  A roster record may claim `:residue :none` only where every mounted
  projection calls this (`roster-jvm-test`); that is what makes the claim
  falsifiable rather than decorative."
  ([where] (residue-clean! where nil))
  ([where books]
   (let [{:keys [live retired]} @ledger
         undestroyed (mapv :label live)
         dirty       (vec (for [entry retired
                                :when (or (pos? (:children-left entry))
                                          (:still-attached? entry))]
                            entry))]
     (is (= [] undestroyed)
         (str where " — every root created here was torn down; these were not: "
              (pr-str undestroyed)))
     (is (= [] dirty)
         (str where " — every torn-down root left its container empty and "
              "detached; these did not (a surviving child means the unmount "
              "never happened, a still-attached container means the removal "
              "did not): " (pr-str dirty)))
     (is (pos? (count retired))
         (str where " — non-vacuity: this test really did tear a root down "
              "through the shared lifecycle, so the rows above had something "
              "to read"))
     (doseq [[description read-fn] books]
       (let [v (read-fn)]
         (is (read-as-empty? v)
             (str where " — " description " must read EMPTY after teardown "
                  "(an exact zero, not a threshold); got " (pr-str v)))))
     nil)))

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
  asserted once per mode, named by the mode label.

  **The row's rejection handler sits UPSTREAM of the single trailing `done`
  (rf2-qpns).** `cljs.test/run-block` hands `done` a continuation that runs
  the WHOLE REMAINDER of the run synchronously — every later row, then every
  namespace after this one — so anything out there that throws unwinds back
  into the callback that called `done`. A `.catch` placed downstream of that
  callback claims the foreign failure as this row's, prints it against
  whichever test is current by then, and calls `done` a SECOND time, which
  re-forces `run-block`'s still-unrealized delay and re-runs the offending
  namespace. So the handler reports and falls through; it never finishes."
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
      (.catch (fn [e]
                (is false (str "a matrix row rejected: " e))
                nil))
      (.then (fn [_] (done)))))

(defn run-async
  "Run `thunk` (which returns a promise) and then call `done` EXACTLY ONCE.
  A rejection — or a SYNCHRONOUS throw inside `thunk` — is a named assertion
  failure followed by the single `done`. `thunk` runs inside a `.then` so a
  synchronous throw becomes a rejection rather than escaping the chain and
  stranding `done`. The terminal shape for a cross-mode cell that mounts
  both modes itself: the body never calls `done`, so it cannot double-fire
  it.

  The rejection handler is upstream of the single trailing `done` for the
  reason [[each-mode]] states (rf2-qpns)."
  [thunk done]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (thunk)))
      (.catch (fn [e]
                (is false (str "an async matrix cell rejected: " e))
                nil))
      (.then (fn [_] (done)))))
