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
  all. A detach (`nil` node) RETIRES that generation: it withdraws
  whatever the generation still had pending, and nothing else — a
  superseded generation must never act, and must never act on its
  successor either.

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
            #?@(:cljs [[goog.object :as gobj]
                       [re-frame.freehand.refs :as refs]
                       [re-frame.interop :as interop]
                       [re-frame.trace :as trace]])))

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

(def ^:private popover-reconcilers
  "The event positions that reconcile a POPOVER's own dismissal."
  [:on-toggle :on-before-toggle])

(def ^:private modal-reconcilers
  "The event positions that reconcile a modal DIALOG's own dismissal."
  [:on-close :on-cancel])

(def context-keys
  "Beyond the desired-state pair itself, the element facts a top-layer
  declaration is judged AGAINST: the `:popover` mode that makes
  `popover-open?` legal at all, and the event positions that reconcile
  the browser's own dismissal.

  An interpreted element hands the whole authored attribute map to the
  emitter, so it reads these for free. A COMPILED element has no runtime
  attribute map — its props are written imperatively onto a JS object —
  so its emitter carries exactly these facts alongside the pair. The list
  living here, with the rules that consume it, is what keeps two modes
  judging one declaration by one rule."
  (into #{:popover} cat [popover-reconcilers modal-reconcilers]))

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

;; ---------------------------------------------------------------------------
;; Development evidence — typed, and published only from a selected occurrence
;; ---------------------------------------------------------------------------
;;
;; Two advisories, one seam. Both are DEVELOPMENT evidence about a mistake the
;; next render can fix, so neither throws and neither is swallowed. What makes
;; them evidence rather than console noise is a pair of rules they now share.
;;
;; TYPED. Each carries a stable, catalogued diagnostic id and a `:recovery` on
;; the trace DIAGNOSTIC channel — the surface a listener, an inspector or an
;; AI reading a session already consumes. A console sentence names the same
;; fact for the person it is addressed to, but prose is not a contract:
;; scraping English out of a log is not a way for a tool to learn anything.
;;
;; PUBLISHED FROM A SELECTED OCCURRENCE. Evidence rides a committed callback
;; ref or a host call that actually ran, never the construction of props. A
;; render may restart or be abandoned, and an advisory published from one names
;; a candidate that never existed — it would spam a page that re-renders and
;; accuse an author of a mistake the page never made. It is the same passive-
;; render law the reactive bundle obeys, obeyed by the same means.

(def ^:private unreconciled-id
  "A controlled top-layer declaration with no handler for the browser's
  own dismissal (Spec 009 §Error / warning catalogue)."
  :rf.warning/view-top-layer-unreconciled)

(def ^:private refused-id
  "A top-layer host call the browser refused (Spec 009 §Error / warning
  catalogue)."
  :rf.warning/view-top-layer-refused)

(defn- evidence!
  "Publish one advisory: the typed record on the trace diagnostic channel,
  and `sentence` on the console for the reader.

  `interop/debug-enabled?` gates both, standing alone so Closure folds it
  under `:advanced` + `goog.DEBUG=false` and neither the record nor the
  sentence reaches a production build."
  [id tags sentence]
  (when interop/debug-enabled?
    (trace/emit! :warning id tags)
    (js/console.warn (str "[" id "] " sentence)))
  nil)

(defn- refused!
  "A host call the browser refused, as evidence naming the call, the
  element and the browser's own reason. Not an exception: the operation is
  mechanical and the failure is an authoring mistake the next render can
  fix, so it must not take a page down — and not silence either."
  [host-call ^js node e]
  (when interop/debug-enabled?
    (let [tag (some-> node .-tagName .toLowerCase)]
      (evidence!
        refused-id
        {:host-call  host-call
         :tag        (some-> tag keyword)
         :element-id (not-empty (.-id node))
         :exception  (error/ex-message-safe e)
         :reason     (str "the browser refused " host-call " — a top-layer call is refused "
                          "when the node is not in the document, or when the element is "
                          "already open through the other mechanism")
         :recovery   :warned-and-continued}
        (str "top layer: " host-call " was refused by the browser on <" tag ">. A top-layer "
             "call is refused when the node is not in the document, or when the element is "
             "already open through the other mechanism (an already-open non-modal dialog "
             "cannot be promoted with showModal). Render the node before asking for it to be "
             "open, and keep one mechanism per element. The browser said: "
             (error/ex-message-safe e)))))
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
      (catch :default e (refused! (if want "showPopover()" "hidePopover()") node e))))
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
      (catch :default e (refused! (if want "showModal()" "close()") node e))))
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
  [node fact owner]
  (let [^js m (or @pending (let [m (js/Map.)] (reset! pending m) m))]
    ;; Keyed by NODE, so a node enqueued twice in one commit keeps the last
    ;; desired state rather than performing two calls for one commit. The
    ;; OWNER rides with the fact: an entry belongs to the ref generation
    ;; that queued it, and a later generation claiming the same node takes
    ;; ownership along with the value.
    (.set m node #js [fact owner]))
  (when (compare-and-set! flush-scheduled? false true)
    (js/queueMicrotask flush-top-layer!))
  nil)

(defn- retire!
  "Withdraw whatever `owner` still has pending — what a detach performs.

  OWNERSHIP, not connectivity, is what retires a queued operation. A ref
  generation retires while its node stays in the document every time a
  commit removes the intrinsic without removing the element, and the
  microtask would then open a node nothing controls any more, on behalf
  of a generation that had already let go. `isConnected` cannot see that:
  the node is still there. So the batch fences an entry the way the
  reactive commit fences a candidate — by asking whether the generation
  that staged it is still the one that speaks.

  The identity check is equally what stops a retirement taking a
  SUCCESSOR down with it: a newer generation's enqueue has already
  replaced the entry and its owner, so the retiring one finds an entry
  that is not its own and leaves it exactly where it is — in whichever
  order the host runs attach and detach."
  [owner]
  (when-some [^js m @pending]
    (.forEach m (fn [entry node]
                  (when (identical? owner (aget entry 1))
                    (.delete m node)))))
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
  open is the one call the browser refuses outright. An entry whose
  generation retired is not here to skip at all — [[retire!]] withdrew it
  when the generation let go."
  []
  (reset! flush-scheduled? false)
  (when-some [^js m @pending]
    (reset! pending nil)
    (let [entries (array)]
      (.forEach m (fn [entry node] (.push entries #js [node (aget entry 0)])))
      (.sort entries (fn [a b] (doc-order (aget a 0) (aget b 0))))
      (.forEach entries
                (fn [entry]
                  (let [^js node (aget entry 0)]
                    (when (.-isConnected node)
                      (apply-desired! node (aget entry 1))))))))
  nil)

(defn- reconciling-handlers
  "The event positions that reconcile this mechanism's own dismissal —
  the popover's toggle pair, or the dialog's close pair."
  [fact]
  (if (contains? fact :popover-open?)
    popover-reconcilers
    modal-reconcilers))

(defn- reconciled?
  "Does the element handle the browser's own dismissal? Native dismissal —
  Escape, light dismiss, the close button — happens without asking the
  application, so a controlled top-layer node whose author never reads the
  resulting event will spring back open on the next render."
  [attrs fact]
  (boolean (some #(some? (get attrs %)) (reconciling-handlers fact))))

(defn- advise-unreconciled!
  "A controlled declaration with nothing listening for the browser's own
  dismissal, as evidence naming the mechanism and the handlers that would
  reconcile it. Published from the COMMITTED ref, not from the props: a
  candidate React never selected declares nothing, and accusing its author
  would be an accusation about a page that does not exist."
  [tag attrs fact]
  (when interop/debug-enabled?
    (when-not (reconciled? attrs fact)
      (let [popover? (contains? fact :popover-open?)
            handlers (reconciling-handlers fact)]
        (evidence!
          unreconciled-id
          {:tag       tag
           :mechanism (if popover? :popover :modal)
           :handlers  handlers
           :reason    (str "a controlled top-layer state with no handler for the browser's "
                           "own dismissal springs back open on the next render, because the "
                           "desired state still says open and the substrate writes no "
                           "application state on the browser's behalf")
           :recovery  :warned-and-continued}
          (str "top layer: " tag " declares a controlled top-layer state with no handler for "
               "the browser's own dismissal. Escape, light dismiss and the dialog's own close "
               "button all close the node WITHOUT asking the application, and the substrate "
               "never writes application state on their behalf — so the next render will "
               "re-open it. Handle "
               (if popover? ":on-toggle" ":on-close / :on-cancel")
               " with ordinary event intent and move the state that drives this property.")))))
  nil)

(defn install!
  "Install the top-layer host call onto a React props object, answering the
  props. A no-op — and no allocation — for an element that declares no
  desired state.

  The call rides `ref`, which is the one React position that runs at
  COMMIT with the real node in hand and never runs for an abandoned
  render. A fresh closure per render is deliberate twice over: React
  detaches and re-attaches on a new identity, which re-enters the batch
  on every commit — the diff against the live node is what makes a
  repeated equal desired state cost nothing — and that closure IS the
  generation, so the batch can record who queued an operation without a
  registry, a token or a counter. A detach [[retire!]]s it. `ref` returns
  `js/undefined` so React 19 reads it as \"no cleanup\" rather than as a
  cleanup value.

  The install CHAINS onto whatever ref the props already carry rather than
  writing over it. Nothing else writes one today — the walk refuses an
  authored `:ref` outright — but a bare write is the shape of the bug this
  namespace and the behavior boundary would otherwise have on an element
  carrying both, and one rule at every writer is what keeps the substrate
  composable by default ([[re-frame.freehand.refs]]). An intrinsic is a
  property of the ELEMENT, so it takes the node first and releases last.

  Chaining and the generation compose without either knowing about the
  other, which is the point of both. A chain of ONE is that participant
  IDENTICALLY, so the ordinary element's ref IS `top-layer-ref` and React
  retires it by calling it with nil. A longer chain answers a cleanup
  function, and the release arm for a participant that returned no
  cleanup of its own — which this one does not — is to call it with nil.
  Either way the retiring call reaches this exact closure, which is what
  makes it the generation.

  The unreconciled-declaration advisory rides the same ref, for the same
  reason the host call does: building props is not a commit, and evidence
  from a render the host may abandon names nothing real."
  [props tag attrs]
  (if-let [fact (desired tag attrs)]
    (do (gobj/set props "ref"
                  (refs/chain (gobj/get props "ref")
                              (fn top-layer-ref [node]
                                (if (some? node)
                                  (do (advise-unreconciled! tag attrs fact)
                                      (enqueue! node fact top-layer-ref))
                                  (retire! top-layer-ref))
                                js/undefined)))
        props)
    props))

   ))
