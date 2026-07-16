(ns re-frame.ui.bench.hand
  "G-1 HAND-WRITTEN baseline: the same four components written directly
  against react/jsx-runtime by a competent CLJS author — inline #js
  props literals, no manual hoisting (what Babel emits for JSX without
  the constant-elements plugin). Semantics must match the compiled
  views byte-for-byte on renderToStaticMarkup output; the bench runner
  asserts that before measuring."
  (:require ["react" :as react]
            ["react/jsx-runtime" :as jsx]
            [re-frame.ui.eq :as eq]
            [re-frame.ui.events :as events]
            [re-frame.ui.viewcell :as viewcell]))

(defonce ^:private sink (volatile! nil))

(defn dispatch!
  "Handler sink — never invoked during a server render; exists so the
  hand-written handlers close over real work Closure cannot elide."
  [ev]
  (vreset! sink ev))

(defn static-tree* [^js _props]
  (jsx/jsxs "div"
            #js {:className "panel" :id "about"
                 :style #js {:padding 16 :borderTop "1px solid #ccc"}
                 :children
                 #js [(jsx/jsx "h2" #js {:className "title" :children "About"})
                      (jsx/jsxs "p" #js {:children
                                         #js ["A fully static subtree, "
                                              (jsx/jsx "em" #js {:children "hoisted"})
                                              " to a module constant."]})
                      (jsx/jsxs "ul"
                                #js {:className "links"
                                     :children
                                     #js [(jsx/jsx "li" #js {:children (jsx/jsx "a" #js {:href "/docs" :title "Docs & guides" :children "Docs"})})
                                          (jsx/jsx "li" #js {:children (jsx/jsx "a" #js {:href "/api" :children "API"})})
                                          (jsx/jsx "li" #js {:children (jsx/jsx "a" #js {:href "/blog" "data-section" "blog" :children "Blog"})})]})
                      (jsx/jsx "footer" #js {"aria-label" "footer" :tabIndex 0
                                             :children "(c) 2026 <re-frame2> & friends"})]}))

;; `defview` wraps EVERY compiled view in `runtime/memo-view` (= React.memo)
;; with a generated straight-line `rf=` comparator over its declared slots
;; (emit-cljs `comparator-form`). The G-1 gate divides compiled/hand, so every
;; hand DENOMINATOR must carry the SAME React.memo boundary and an equivalent
;; comparator — otherwise the ratio measures the cost of crossing a memo
;; boundary rather than the compiler's lowering overhead, and a React.memo cost
;; change moves the gate with no lowering regression. static-tree declares no
;; slots, so its compiled comparator is the always-equal `(fn [_ _] true)`.
(def static-tree*-memo
  (react/memo static-tree* (fn [_prev _next] true)))

(defn counter* [^js props]
  (let [n       (unchecked-get props "n")
        step    (unchecked-get props "step")
        locked? (unchecked-get props "locked?")]
    (jsx/jsxs "div"
              #js {:className "counter"
                   :children
                   #js [(jsx/jsxs "h1" #js {:children #js ["Count: " n]})
                        (jsx/jsxs "div"
                                  #js {:className "controls"
                                       :children
                                       #js [(jsx/jsx "button" #js {:className "btn"
                                                                   :onClick (fn [_e] (dispatch! [:counter/inc step]))
                                                                   :disabled locked?
                                                                   :children "+"})
                                            (jsx/jsx "button" #js {:className "btn"
                                                                   :onClick (fn [_e] (dispatch! [:counter/dec step]))
                                                                   :disabled locked?
                                                                   :children "-"})
                                            (jsx/jsx "button" #js {:className "btn reset"
                                                                   :onClick (fn [_e] (dispatch! [:counter/reset]))
                                                                   :children "Reset"})]})
                        (jsx/jsx "input" #js {:type "text"
                                              :value (str n)
                                              :onInput (fn [e] (dispatch! [:counter/set (unchecked-get (unchecked-get e "target") "value")]))})
                        (jsx/jsx "div"
                                 #js {:className "stars"
                                      :children
                                      (let [arr #js []]
                                        (doseq [i (range (min 5 (max 0 n)))]
                                          (.push arr (jsx/jsx "span" #js {:className "star" :children "*"} i)))
                                        arr)})
                        (if locked?
                          (jsx/jsx "p" #js {:className "warn" :children "Locked"})
                          nil)
                        (if (neg? n)
                          (jsx/jsx "p" #js {:className "neg" :children "negative"})
                          (jsx/jsx "p" #js {:className "pos" :children "non-negative"}))]})))

;; ---------------------------------------------------------------------------
;; Committed-event gate denominators (rf2-vxgfnd.95.1)
;;
;; `defview` gives EVERY compiled event-bearing view committed-event ownership:
;; a per-instance EventOwner, a per-render candidate capture, and a per-site
;; committed descriptor behind a stable callback (`re-frame.ui.events`), all
;; inside a `viewcell/render-events` wrapper. That is a re-frame2 runtime POLICY
;; — a framework guarantee (abandoned renders cannot retarget a callback; the
;; DOM sees one stable callback per site; invocation reads the latest COMMITTED
;; template and locked frame) that a naive raw-jsx handler `(fn [e] (dispatch!
;; ...))` does NOT provide and does NOT pay for. It is the exact counterpart of
;; the always-memo policy: a cost the compiled numerator pays on every render
;; that is NOT lowering overhead.
;;
;; The G-1 gate divides compiled/hand, so — precisely as every denominator
;; carries the same React.memo boundary to factor out the memo policy — each
;; EVENT-BEARING denominator carries the SAME committed-event ownership to
;; factor out the committed-event policy. Otherwise the ratio measures a
;; framework guarantee's cost, not the compiler's lowering overhead, and a
;; change to `re-frame.ui.events` would move the gate with no lowering
;; regression. The denominators call the REAL primitives (`render-events` +
;; `data-handler`), so they pay the identical unavoidable cost to the byte — no
;; hand-rolled approximation that could drift. The handler VECTOR is rebuilt per
;; render on both sides (its dynamic slot, e.g. the row `id`, changes), matching
;; the compiler's `data-handler` argument. `:rf.ui/value`/`:rf.ui/checked` lower
;; to the module placeholder sentinels the emitter uses. Only the element tree
;; stays hand-written #js literals — the one thing the ratio is meant to isolate.
;; Under the SSR bench the layout-commit/native-invocation arms never run (React
;; skips layout effects on the server; renderToStaticMarkup drops handlers), so
;; output stays byte-identical; the measured cost is exactly the per-render
;; capture the client also pays. The site-key per denominator is a plain 0-based
;; index — the production site-key shape — unique per event site.

(defn counter*-committed [^js props]
  (let [n       (unchecked-get props "n")
        step    (unchecked-get props "step")
        locked? (unchecked-get props "locked?")]
    (jsx/jsxs "div"
              #js {:className "counter"
                   :children
                   #js [(jsx/jsxs "h1" #js {:children #js ["Count: " n]})
                        (jsx/jsxs "div"
                                  #js {:className "controls"
                                       :children
                                       #js [(jsx/jsx "button" #js {:className "btn"
                                                                   :onClick (events/data-handler 0 [:counter/inc step] 0 nil)
                                                                   :disabled locked?
                                                                   :children "+"})
                                            (jsx/jsx "button" #js {:className "btn"
                                                                   :onClick (events/data-handler 1 [:counter/dec step] 0 nil)
                                                                   :disabled locked?
                                                                   :children "-"})
                                            (jsx/jsx "button" #js {:className "btn reset"
                                                                   :onClick (events/data-handler 2 [:counter/reset] 0 nil)
                                                                   :children "Reset"})]})
                        (jsx/jsx "input" #js {:type "text"
                                              :value (str n)
                                              :onInput (events/data-handler 3 [:counter/set events/value-placeholder] 0 nil)})
                        (jsx/jsx "div"
                                 #js {:className "stars"
                                      :children
                                      (let [arr #js []]
                                        (doseq [i (range (min 5 (max 0 n)))]
                                          (.push arr (jsx/jsx "span" #js {:className "star" :children "*"} i)))
                                        arr)})
                        (if locked?
                          (jsx/jsx "p" #js {:className "warn" :children "Locked"})
                          nil)
                        (if (neg? n)
                          (jsx/jsx "p" #js {:className "neg" :children "negative"})
                          (jsx/jsx "p" #js {:className "pos" :children "non-negative"}))]})))

(defn counter*-memo$host [^js props]
  (viewcell/render-events "hand/counter" (fn [] (counter*-committed props))))

;; counter declares slots n/step/locked? — match the generated straight-line
;; rf= comparator over exactly those three slots.
(def counter*-memo
  (react/memo
   counter*-memo$host
   (fn [^js prev ^js next]
     (and (eq/rf= (unchecked-get prev "n") (unchecked-get next "n"))
          (eq/rf= (unchecked-get prev "step") (unchecked-get next "step"))
          (eq/rf= (unchecked-get prev "locked?") (unchecked-get next "locked?"))))))

(defn todo-row* [^js props]
  (let [todo (unchecked-get props "todo")
        {:keys [id label done? priority]} todo]
    (jsx/jsxs "li"
              #js {:className (if done? "todo-item done" "todo-item")
                   "data-priority" (name priority)
                   "aria-hidden" false
                   :children
                   #js [(jsx/jsx "input" #js {:type "checkbox"
                                              :checked done?
                                              :onChange (fn [e] (dispatch! [:todo/toggle id (unchecked-get (unchecked-get e "target") "checked")]))})
                        (jsx/jsx "span" #js {:className "label" :children label})
                        (if (= priority :high)
                          (jsx/jsx "span" #js {:className "flag" :children "HIGH"})
                          nil)]})))

;; `defview` memoizes every compiled view AND gives it committed-event
;; ownership. Match both policies in the G-1 denominator so the keyed-list gate
;; measures lowering overhead, not the cost of crossing a React.memo boundary
;; or running the per-render event capture (see the committed-event note above).
;; The generated todo-row comparator has exactly one slot (`todo`) and uses
;; rf=; keep this spelling visibly parallel to emit-cljs/comparator-form.
(defn todo-row*-committed [^js props]
  (let [todo (unchecked-get props "todo")
        {:keys [id label done? priority]} todo]
    (jsx/jsxs "li"
              #js {:className (if done? "todo-item done" "todo-item")
                   "data-priority" (name priority)
                   "aria-hidden" false
                   :children
                   #js [(jsx/jsx "input" #js {:type "checkbox"
                                              :checked done?
                                              :onChange (events/data-handler 0 [:todo/toggle id events/checked-placeholder] 0 nil)})
                        (jsx/jsx "span" #js {:className "label" :children label})
                        (if (= priority :high)
                          (jsx/jsx "span" #js {:className "flag" :children "HIGH"})
                          nil)]})))

(defn todo-row*-memo$host [^js props]
  (viewcell/render-events "hand/todo-row" (fn [] (todo-row*-committed props))))

(def todo-row*-memo
  (react/memo
   todo-row*-memo$host
   (fn [^js prev ^js next]
     (eq/rf= (unchecked-get prev "todo")
             (unchecked-get next "todo")))))

(defn todo-list* [^js props]
  (let [title (unchecked-get props "title")
        todos (unchecked-get props "todos")]
    (jsx/jsxs "section"
              #js {:className "todos"
                   :children
                   #js [(jsx/jsx "h2" #js {:children title})
                        (jsx/jsx "ul"
                                 #js {:className "todo-ul"
                                      :children
                                      (let [arr #js []]
                                        (doseq [t todos]
                                          (.push arr (jsx/jsx todo-row* #js {:todo t} (:id t))))
                                        arr)})
                        (jsx/jsxs "p" #js {:className "count"
                                           :children #js [(count todos) " items"]})]})))

(defn todo-list*-memo$render [^js props]
  ;; Deliberately duplicate the tiny list body: sharing a helper would add a
  ;; call to both hand baselines that the compiled view does not perform.
  (let [title (unchecked-get props "title")
        todos (unchecked-get props "todos")]
    (jsx/jsxs "section"
              #js {:className "todos"
                   :children
                   #js [(jsx/jsx "h2" #js {:children title})
                        (jsx/jsx "ul"
                                 #js {:className "todo-ul"
                                      :children
                                      (let [arr #js []]
                                        (doseq [t todos]
                                          (.push arr (jsx/jsx todo-row*-memo #js {:todo t} (:id t))))
                                        arr)})
                        (jsx/jsxs "p" #js {:className "count"
                                           :children #js [(count todos) " items"]})]})))

(def todo-list*-memo
  ;; The compiled parent is also a defview. Match its two-slot comparator as
  ;; well as its memoized row boundary; otherwise the denominator still omits
  ;; one of the component boundaries whose lowering it is meant to isolate.
  (react/memo
   todo-list*-memo$render
   (fn [^js prev ^js next]
     (and (eq/rf= (unchecked-get prev "title")
                  (unchecked-get next "title"))
          (eq/rf= (unchecked-get prev "todos")
                  (unchecked-get next "todos"))))))

(defn status-panel* [^js props]
  (let [state   (unchecked-get props "state")
        message (unchecked-get props "message")
        retries (unchecked-get props "retries")]
    (jsx/jsxs "div"
              #js {:className "status"
                   :children
                   #js [(cond
                          (= state :loading)
                          (jsx/jsx "div" #js {:className "spinner" :role "status" "aria-label" "loading"
                                              :children "Loading..."})
                          (= state :error)
                          (jsx/jsxs "div" #js {:className "error"
                                               :children
                                               #js [(jsx/jsx "strong" #js {:children "Error: "})
                                                    message
                                                    (if (pos? retries)
                                                      (jsx/jsxs "span" #js {:className "retries"
                                                                            :children #js ["(retry " retries ")"]})
                                                      nil)
                                                    (jsx/jsx "button" #js {:onClick (fn [_e] (dispatch! [:status/retry]))
                                                                           :children "Retry"})]})
                          (= state :empty)
                          (jsx/jsx "div" #js {:className "empty" :children "Nothing here yet."})
                          :else
                          (jsx/jsx "div" #js {:className "ok"
                                              :children (jsx/jsx "span" #js {:className "msg" :children message})}))
                        (if (= state :error)
                          (jsx/jsx "hr" #js {})
                          nil)]})))

;; status-panel declares slots state/message/retries — match the generated
;; straight-line rf= comparator over exactly those three slots. Its ONE event
;; site (the retry button) is conditional on the `:error` branch, so — like the
;; compiled view — the `data-handler` call runs only when that branch renders
;; (see the committed-event note above).
(defn status-panel*-committed [^js props]
  (let [state   (unchecked-get props "state")
        message (unchecked-get props "message")
        retries (unchecked-get props "retries")]
    (jsx/jsxs "div"
              #js {:className "status"
                   :children
                   #js [(cond
                          (= state :loading)
                          (jsx/jsx "div" #js {:className "spinner" :role "status" "aria-label" "loading"
                                              :children "Loading..."})
                          (= state :error)
                          (jsx/jsxs "div" #js {:className "error"
                                               :children
                                               #js [(jsx/jsx "strong" #js {:children "Error: "})
                                                    message
                                                    (if (pos? retries)
                                                      (jsx/jsxs "span" #js {:className "retries"
                                                                            :children #js ["(retry " retries ")"]})
                                                      nil)
                                                    (jsx/jsx "button" #js {:onClick (events/data-handler 0 [:status/retry] 0 nil)
                                                                           :children "Retry"})]})
                          (= state :empty)
                          (jsx/jsx "div" #js {:className "empty" :children "Nothing here yet."})
                          :else
                          (jsx/jsx "div" #js {:className "ok"
                                              :children (jsx/jsx "span" #js {:className "msg" :children message})}))
                        (if (= state :error)
                          (jsx/jsx "hr" #js {})
                          nil)]})))

(defn status-panel*-memo$host [^js props]
  (viewcell/render-events "hand/status-panel" (fn [] (status-panel*-committed props))))

(def status-panel*-memo
  (react/memo
   status-panel*-memo$host
   (fn [^js prev ^js next]
     (and (eq/rf= (unchecked-get prev "state") (unchecked-get next "state"))
          (eq/rf= (unchecked-get prev "message") (unchecked-get next "message"))
          (eq/rf= (unchecked-get prev "retries") (unchecked-get next "retries"))))))
