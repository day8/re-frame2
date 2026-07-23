(ns re-frame.freehand.top-layer
  "The DOM TOP LAYER — a closed pair of qualified desired-state properties
  over the browser's own top layer (Spec 004 §The DOM top layer, F4; ruled
  by D015).

  A popover and a modal dialog are not ordinary attributes. The browser
  promotes them to the TOP LAYER — above every stacking context, outside
  every clipping ancestor — and it does so through IMPERATIVE calls:
  `showPopover()` / `hidePopover()` and `showModal()` / `close()`. There is
  no attribute an author can set that means \"be open\". So Freehand
  recognises exactly two qualified properties whose value is the DESIRED
  state, and performs the matching idempotent browser call at the selected
  commit:

      [:div    {:popover :auto ::web/popover-open? open?} …]
      [:dialog {::web/modal-open? open?} …]

  ## What this is NOT

  There is **no neutral portal**, and that absence is the design (D015
  rejects option D). A portal is a re-parenting protocol whose target is a
  live host container — not portable data, with no honest JVM
  representation — and it solves none of dismissal, focus, accessibility or
  teardown. A general re-parenting primitive is the one primitive that can
  do anything, which is exactly what makes a substrate unanalysable. The
  top layer solves the problem a portal was reached for, and it solves it
  natively.

  There is also no overlay framework here: no stacking ladder, no
  placement, no ARIA, no keyboard policy, no transition. Those belong to a
  component library. This namespace owns two idempotent host calls and the
  rules for when they are legal.

  ## The tracking frequency, declared

  **Commit, and nothing between commits.** The host call happens once per
  selected commit whose desired state DIFFERS from the node's live state,
  and the runtime observes nothing in between: no document listener, no
  resize or intersection observer, no animation-frame loop, no timer. The
  only thing it retains is one commit's batch of pending operations, and
  it retains that for less than a frame — the flush drains it whole. There
  is nothing to leak because there is nothing left, so cleanup is total by
  construction rather than by discipline, and a test can assert the exact
  count zero instead of trusting a teardown path.

  That is also why the diff is read from the LIVE NODE (`:popover-open` /
  `:modal` match) rather than from remembered state. Remembered state would
  be a second source of truth that browser-initiated dismissal
  immediately invalidates; the node already knows, and asking it is both
  correct and free.

  ## Commit, not render

  The host call rides a React ref, which React runs only for a render it
  COMMITTED — the same law [[re-frame.freehand.cell]] states for the
  reactive bundle. A render the host abandons runs no ref, so its desired
  state never reaches the host; building elements performs no host work at
  all. A detach (`nil` node) does nothing: a node leaving the document
  leaves the top layer by itself, and a superseded generation must never
  act on its replacement.

  What the ref enqueues, the commit's microtask checkpoint performs — in
  DOCUMENT ORDER, so an ancestor overlay is shown before the overlay
  nested inside it. React attaches refs bottom-up, and the browser decides
  what an opening popover closes from the DOM as it stands at that
  instant, so ref order alone would collapse a nested pair to its outer
  half. The checkpoint runs before the browser paints, so the ordering
  costs no frame.

  ## The two halves

    `desired` / `without` / `extract`   COMMON — recognition, the validity
                                        rules, and the structural fact
    `install!`                          ClojureScript — the ref that carries
                                        the idempotent host call

  On the JVM the pair projects structurally as `:rf.ui/top-layer` and makes
  no claim that anything was promoted: a server cannot display a browser
  top layer, and saying otherwise in the tree would be a lie a hydration
  would then have to unpick.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §The DOM top
  layer; the reserved structural key is
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)
  §Reserved `:rf.ui/*` keys."
  (:require [re-frame.error :as error]
            [re-frame.freehand.conversion :as conv]
            #?@(:cljs [[goog.object :as gobj]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The closed pair
;; ---------------------------------------------------------------------------

(def popover-open?-key
  "The desired-state property for a POPOVER — legal only on an element
  carrying a valid `:popover` mode."
  :re-frame.freehand.web/popover-open?)

(def modal-open?-key
  "The desired-state property for a MODAL dialog — legal only on
  `<dialog>`, and only about the MODAL axis. A non-modal dialog uses the
  platform's ordinary `:open` attribute, which needs no intrinsic."
  :re-frame.freehand.web/modal-open?)

(def ^:private structural-key
  "The reserved diagnostic key the structural tree records the desired
  state under (004B §Reserved `:rf.ui/*` keys)."
  :rf.ui/top-layer)

(def ^:private popover-modes
  "The `:popover` values that promote an element to the top layer. `true`
  is the bare boolean presence and `\"\"` the empty attribute; HTML maps
  both onto `auto`."
  #{true "" "auto" "manual" "hint"})

(defn present?
  "Does `attrs` carry either desired-state property? The one question asked
  on every element of every render, so it is two map lookups and nothing
  else — the validity rules below are paid only by an element that
  actually declares one."
  [attrs]
  (or (contains? attrs popover-open?-key)
      (contains? attrs modal-open?-key)))

(defn without
  "`attrs` with the pair removed — what the emitters spell onto the DOM and
  into `:attrs`. The properties are Freehand vocabulary, not attributes:
  the namespace an emitter would drop is the whole of their meaning, so
  a leaked one would reach the DOM as a garbage `popoverOpen?` prop."
  [attrs]
  (if (present? attrs)
    (dissoc attrs popover-open?-key modal-open?-key)
    attrs))

(defn- illegal!
  [reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    're-frame.freehand/render
    reason
    {:recovery :no-recovery :extra extra}))

(defn- desired-state
  "One property's desired state: `true`, `false`, or nil when the property
  is absent or its value is nil. A nil value drops the entry exactly as a
  nil attribute value does, so `(when open? true)` reads as \"no desired
  state expressed\" rather than as a third state."
  [tag attrs k]
  (let [v (get attrs k)]
    (cond
      (nil? v)     nil
      (boolean? v) v
      :else
      (illegal!
        (str "The " k " property on " tag " carries a "
             (name (:type (error/diag-value-summary v)))
             ". A top-layer desired state is a boolean — the value IS the state the "
             "browser should be in after this commit — or nil, which expresses no "
             "desired state at all.")
        {:attr k :value (error/diag-value-summary v)}))))

(defn desired
  "The desired top-layer state `attrs` declares on a `tag` element, as the
  structural fact `{:popover-open? bool}` or `{:modal-open? bool}` — or nil
  when the element declares none.

  The validity rules are the whole surface, and they are common: an
  emitter that accepted a property the other refused would be two answers
  for one declaration.

  - **`popover-open?` needs a popover.** The property means \"call
    `showPopover()`\", and that call is defined only on an element with a
    valid `:popover` mode. Without one there is nothing to show.
  - **`modal-open?` needs a `<dialog>`.** It maps to `showModal()` /
    `close()`, which no other element has. A non-modal dialog is the
    platform's ordinary `:open` attribute and needs no intrinsic.
  - **One mechanism per element.** A popover and a modal dialog are
    different browser operations with different dismissal, focus and
    stacking behaviour. Declaring both desired states on one node asks for
    both at once, and there is no honest order in which to perform them."
  [tag attrs]
  (when (present? attrs)
    (let [pop-want   (desired-state tag attrs popover-open?-key)
          modal-want (desired-state tag attrs modal-open?-key)]
      (when (and (some? pop-want) (some? modal-want))
        (illegal!
          (str "The element " tag " declares both " popover-open?-key " and "
               modal-open?-key ". A popover and a modal dialog are different browser "
               "operations — different dismissal, different focus behaviour, different "
               "stacking — so one element expresses one of them. Keep the mechanism this "
               "element actually is.")
          {:tag tag}))
      (cond
        (some? pop-want)
        (do (when-not (contains? popover-modes (conv/attr-value (get attrs :popover)))
              (illegal!
                (str "The element " tag " declares " popover-open?-key " without a valid "
                     ":popover mode. The property means \"call showPopover()\", which the "
                     "browser defines only on a popover element. Add :popover :auto (light "
                     "dismiss and one-at-a-time stacking), :popover :manual, or :popover "
                     ":hint.")
                {:tag tag :attr popover-open?-key :popover (get attrs :popover)}))
            {:popover-open? pop-want})

        (some? modal-want)
        (do (when-not (= :dialog tag)
              (illegal!
                (str "The element " tag " declares " modal-open?-key ", which maps to "
                     "showModal() / close() — operations only <dialog> has. Declare it on "
                     "a :dialog element, or, for a non-modal dialog, use the platform's "
                     "ordinary :open attribute, which needs no intrinsic.")
                {:tag tag :attr modal-open?-key}))
            {:modal-open? modal-want})))))

(defn extract
  "`[attrs* fact]` — the attribute map with the pair removed, and the
  structural top-layer fact (or nil). The one call the shared node
  canonicaliser makes, so both execution modes record the same fact from
  the same rules."
  [tag attrs]
  (if-let [fact (desired tag attrs)]
    [(without attrs) fact]
    [attrs nil]))

(defn fact-key
  "The reserved structural key a top-layer fact is recorded under."
  []
  structural-key)

;; ---------------------------------------------------------------------------
;; The host half — the idempotent call at the selected commit
;; ---------------------------------------------------------------------------

#?(:cljs
   (do

(defonce ^:private operations
  ;; Every host call this runtime has made. A test seam, and the exact
  ;; number the "repeated equal values are no-ops" and "tracking frequency
  ;; is commit" claims are asserted against — an integer, never a
  ;; threshold. It is the ONLY state the runtime holds, and it holds no
  ;; node, no listener and no observer.
  (atom 0))

(defn operation-count
  "How many host top-layer operations this runtime has performed."
  []
  @operations)

(defn reset-operation-count!
  "Zero the operation counter — a test-isolation seam."
  []
  (reset! operations 0)
  nil)

(defn- advise!
  "A host call the browser refused. DEV-only advisory, stripped in
  production by `goog.DEBUG`: the operation is mechanical and the failure
  is an authoring mistake the next render can fix, so it must not become
  an exception that takes a page down, and it must not be swallowed
  either."
  [op ^js node e]
  (when ^boolean js/goog.DEBUG
    (js/console.warn
      (str "top layer: " op " was refused by the browser on <"
           (some-> node .-tagName .toLowerCase) ">. A top-layer call is refused when the "
           "node is not in the document, or when the element is already open through the "
           "other mechanism (an already-open non-modal dialog cannot be promoted with "
           "showModal). Render the node before asking for it to be open, and keep one "
           "mechanism per element.")
      e))
  nil)

(defn- reconcile-popover!
  [^js node want]
  ;; The diff is read from the NODE, not from remembered state: browser
  ;; light-dismiss changes it without telling us, and a remembered value
  ;; would make the next equal desired state a spurious call.
  (when (not= (boolean want) (.matches node ":popover-open"))
    (swap! operations inc)
    (try
      (if want (.showPopover node) (.hidePopover node))
      (catch :default e (advise! (if want "showPopover()" "hidePopover()") node e))))
  nil)

(defn- reconcile-modal!
  [^js node want]
  ;; `:modal` and not `.-open`: a `<dialog>` opened non-modally through the
  ;; ordinary `:open` attribute is open but NOT modal, and this property
  ;; owns only the modal axis.
  (when (not= (boolean want) (.matches node ":modal"))
    (swap! operations inc)
    (try
      (if want (.showModal node) (.close node))
      (catch :default e (advise! (if want "showModal()" "close()") node e))))
  nil)

(defn apply-desired!
  "Perform the desired state's host call on `node`, if the node is not
  already in it. Idempotent, and a no-op for a detach (`nil` node)."
  [node fact]
  (when (some? node)
    (when-some [want (:popover-open? fact)] (reconcile-popover! node want))
    (when-some [want (:modal-open? fact)]   (reconcile-modal! node want)))
  nil)

;; ---------------------------------------------------------------------------
;; The commit batch — one flush, in document order, before paint
;; ---------------------------------------------------------------------------
;;
;; NESTING is why this exists, and it is the case hand-rolled overlays get
;; wrong. The browser computes a popover's ancestor from the DOM at the
;; instant it is shown: showing a popover closes everything that is not an
;; ancestor of it. React attaches refs BOTTOM-UP, so a commit that opens a
;; nested pair would show the inner popover (no open ancestor yet) and then
;; show the outer one — which closes the inner as a non-ancestor. The pair
;; would collapse to the outer alone, in the one arrangement an author most
;; expects to work.
;;
;; So the commit's operations are collected and performed in DOCUMENT ORDER
;; at the microtask checkpoint the commit opens — the same checkpoint the
;; reactive cell closes its repaint window on, and one a browser runs BEFORE
;; it paints. An ancestor is shown before its descendant, the descendant is
;; then genuinely nested, and no frame is painted in between.
;;
;; The batch is the only thing this runtime retains, and it retains it for
;; less than one frame: the flush drains it whole. There is no registry, no
;; listener and no observer to survive a teardown.

(defonce ^:private pending (atom nil))
(defonce ^:private flush-scheduled? (atom false))

(declare flush-top-layer!)

(defn pending-count
  "How many nodes are waiting in the open commit batch. Zero everywhere
  except inside one commit's microtask window — a test asserts the exact
  integer rather than trusting a teardown path."
  []
  (if-some [^js m @pending] (.-size m) 0))

(defn- enqueue!
  [node fact]
  (let [^js m (or @pending (let [m (js/Map.)] (reset! pending m) m))]
    ;; Keyed by NODE, so a node enqueued twice in one commit keeps the last
    ;; desired state rather than performing two calls for one commit.
    (.set m node fact))
  (when (compare-and-set! flush-scheduled? false true)
    (js/queueMicrotask flush-top-layer!))
  nil)

(defn- doc-order
  "Sort comparator putting `a` before `b` when `a` precedes or CONTAINS
  `b` — `compareDocumentPosition` reports containment as `following`, so an
  ancestor sorts ahead of its descendant with no special case."
  [^js a ^js b]
  (let [p (.compareDocumentPosition a b)]
    (cond
      (pos? (bit-and p 4)) -1                               ; b follows / is contained by a
      (pos? (bit-and p 2)) 1                                ; b precedes / contains a
      :else                0)))

(defn flush-top-layer!
  "Perform the open commit batch, in document order, and drain it. Runs on
  its own at the commit's microtask checkpoint; a test calls it to drive
  the window deterministically. Idempotent when nothing is pending.

  A node that left the document before the flush is SKIPPED: it took its
  top-layer state with it when it left, and asking a disconnected node to
  open is the one call the browser refuses outright."
  []
  (reset! flush-scheduled? false)
  (when-some [^js m @pending]
    (reset! pending nil)
    (let [entries (array)]
      (.forEach m (fn [fact node] (.push entries #js [node fact])))
      (.sort entries (fn [a b] (doc-order (aget a 0) (aget b 0))))
      (.forEach entries
                (fn [entry]
                  (let [^js node (aget entry 0)]
                    (when (.-isConnected node)
                      (apply-desired! node (aget entry 1))))))))
  nil)

(defn- reconciled?
  "Does the element handle the browser's own dismissal? Native dismissal —
  Escape, light dismiss, the close button — happens without asking the
  application, so a controlled top-layer node whose author never reads the
  resulting event will spring back open on the next render."
  [attrs fact]
  (let [ks (if (contains? fact :popover-open?)
             [:on-toggle :on-before-toggle]
             [:on-close :on-cancel])]
    (boolean (some #(some? (get attrs %)) ks))))

(defn- advise-unreconciled!
  [tag attrs fact]
  (when ^boolean js/goog.DEBUG
    (when-not (reconciled? attrs fact)
      (js/console.warn
        (str "top layer: " tag " declares a controlled top-layer state with no handler for "
             "the browser's own dismissal. Escape, light dismiss and the dialog's own close "
             "button all close the node WITHOUT asking the application, and the substrate "
             "never writes application state on their behalf — so the next render will "
             "re-open it. Handle "
             (if (contains? fact :popover-open?) ":on-toggle" ":on-close / :on-cancel")
             " with ordinary event intent and move the state that drives this property."))))
  nil)

(defn install!
  "Install the top-layer host call onto a React props object, answering the
  props. A no-op — and no allocation — for an element that declares no
  desired state.

  The call rides `ref`, which is the one React position that runs at
  COMMIT with the real node in hand and never runs for an abandoned
  render. A fresh closure per render is deliberate: React then detaches
  and re-attaches, which re-enters the batch on every commit, and the
  diff against the live node is what makes a repeated equal desired state
  cost nothing. A detach enqueues nothing — a node leaving the document
  takes its top-layer state with it, and a superseded generation must
  never act on its replacement. `ref` returns `js/undefined` so React 19
  reads it as \"no cleanup\" rather than as a cleanup value."
  [props tag attrs]
  (if-let [fact (desired tag attrs)]
    (do (advise-unreconciled! tag attrs fact)
        (gobj/set props "ref"
                  (fn top-layer-ref [node]
                    (when (some? node) (enqueue! node fact))
                    js/undefined))
        props)
    props))

   ))
