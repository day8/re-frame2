(ns re-frame.ui.bench.hand
  "G-1 HAND-WRITTEN baseline: the same four components written directly
  against react/jsx-runtime by a competent CLJS author — inline #js
  props literals, no manual hoisting (what Babel emits for JSX without
  the constant-elements plugin). Semantics must match the compiled
  views byte-for-byte on renderToStaticMarkup output; the bench runner
  asserts that before measuring."
  (:require ["react/jsx-runtime" :as jsx]))

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
