(ns re-frame.story.play.dom
  "DOM-side helpers for the play runner's `:click`, `:type`, and
  `:assert-dom` steps.

  The DOM is impure / browser-only; we abstract the calls behind a
  tiny dispatcher so the rest of the runner stays pure-data and
  testable on the JVM. CLJS branches use `js/document.querySelector`
  + synthetic events; JVM branches return `:no-dom` sentinels (every
  helper is a no-op on JVM).

  The runner-events ns consults `dom-available?` before driving a DOM
  step; on JVM (or in a CLJS REPL with no `js/document`) a DOM step
  records `{:passed? false :skipped? true :message \"no DOM\"}` rather
  than throwing."
  (:require [clojure.string :as str]))

;; ---- environment probe --------------------------------------------------

(defn dom-available?
  "True iff a DOM is reachable. JVM → false; CLJS in a node-runtime
  build → false; CLJS in a browser → true."
  []
  #?(:clj  false
     :cljs (boolean
             (and (exists? js/document)
                  (some? (.-querySelector js/document))))))

;; ---- selector resolution -----------------------------------------------

(defn query
  "Look up a single element matching `selector`. Returns the DOM node
  or nil. JVM → nil."
  [selector]
  #?(:clj  nil
     :cljs (when (dom-available?)
             (try (.querySelector js/document selector)
                  (catch :default _ nil)))))

(defn query-all
  "Return a JS Array of all elements matching `selector`. JVM → []."
  [selector]
  #?(:clj  []
     :cljs (when (dom-available?)
             (try
               (let [nl (.querySelectorAll js/document selector)]
                 (if nl
                   (vec (array-seq nl))
                   []))
               (catch :default _ [])))))

;; ---- visibility ---------------------------------------------------------

(defn visible?
  "True iff `node` exists AND is currently visible (non-zero layout
  box + not display:none / visibility:hidden). The bead asks for a
  visible/hidden distinction — we use the classical `offsetWidth +
  offsetHeight > 0` heuristic which works for most stories without
  bringing in a full styling pass."
  [node]
  #?(:clj  false
     :cljs (boolean
             (when node
               (let [w (or (.-offsetWidth node)  0)
                     h (or (.-offsetHeight node) 0)]
                 (or (pos? w) (pos? h)))))))

(defn text-content
  "Return the `textContent` of `node` (trimmed), or nil. JVM → nil."
  [node]
  #?(:clj  nil
     :cljs (when node
             (some-> (.-textContent node)
                     str
                     str/trim))))

;; ---- synthetic events --------------------------------------------------

#?(:cljs
   (defn- make-mouse-event
     "Build a synthetic `MouseEvent` of `type` ('click' etc.) suitable
     for dispatch via `node.dispatchEvent`."
     [type]
     (try
       (js/MouseEvent. type #js {:bubbles    true
                                 :cancelable true
                                 :view       js/window})
       (catch :default _
         (let [e (.createEvent js/document "MouseEvent")]
           (.initMouseEvent e type true true js/window
                            0 0 0 0 0
                            false false false false 0 nil)
           e)))))

(defn click!
  "Dispatch a synthetic click event at `node` (or the node matched by
  `selector`). Returns true on success, false on no-such-node or
  no-DOM. JVM → false."
  ([selector-or-node]
   (click! selector-or-node nil))
  ([selector-or-node _opts]
   #?(:clj  false
      :cljs (let [node (cond
                         (string? selector-or-node) (query selector-or-node)
                         (some?  selector-or-node)  selector-or-node
                         :else                      nil)]
              (if (some? node)
                (do (.dispatchEvent node (make-mouse-event "click"))
                    true)
                false)))))

#?(:cljs
   (defn- make-input-event
     "Build a synthetic `InputEvent` of `type` ('input' / 'change')."
     [type]
     (try
       (js/InputEvent. type #js {:bubbles    true
                                 :cancelable true})
       (catch :default _
         (try (js/Event. type #js {:bubbles true :cancelable true})
              (catch :default _
                (let [e (.createEvent js/document "Event")]
                  (.initEvent e type true true)
                  e)))))))

#?(:cljs
   (defn native-value-setter
     "Return the `value` SETTER declared on `node`'s DOM PROTOTYPE
     (`HTMLInputElement` / `HTMLTextAreaElement` / `HTMLSelectElement` …),
     skipping any own-property setter a framework installed on the node
     ITSELF. Returns nil when the chain declares no `value` setter.

     WHY THIS EXISTS (rf2-7aqo). React tracks a controlled input's last
     value by redefining `value` as an OWN accessor on the node
     (`trackValueOnNode`): its setter records the incoming value in the
     tracker and THEN forwards to the native setter. A plain
     `(set! (.-value node) text)` therefore leaves the tracker holding the
     text we just wrote, so React's `updateValueIfChanged` reports 'value
     did not change' and DROPS the synthetic `input`/`change` events —
     the DOM shows the text while the component's `on-change` never
     fires. Writing through the PROTOTYPE setter leaves the tracker stale,
     which is exactly the state a real keystroke produces, so React
     synthesises the change and the controlled component sees it.

     This reads a standard property descriptor; it never mutates React's
     private tracker fields. Walking the chain (rather than naming
     `js/HTMLInputElement`) keeps textarea / select / custom elements
     working through one code path and degrades to nil — and hence to the
     plain assignment below — in a runtime with no such descriptor."
     [node]
     (loop [proto (js/Object.getPrototypeOf node)]
       (when (some? proto)
         (let [d (js/Object.getOwnPropertyDescriptor proto "value")]
           (if (and (some? d) (fn? (.-set d)))
             (.-set d)
             (recur (js/Object.getPrototypeOf proto))))))))

(defn type!
  "Simulate the user typing `text` into the input matched by
  `selector-or-node`. Writes the value through the node's NATIVE
  prototype setter (see `native-value-setter` — a direct
  `(set! (.-value node) …)` is swallowed by React's controlled-input
  value tracker) and dispatches an `input` event + a `change` event so
  reagent / React / plain-DOM `onChange` handlers observe the new value.
  Returns true on success. JVM → false."
  [selector-or-node text]
  #?(:clj  false
     :cljs (let [node (cond
                        (string? selector-or-node) (query selector-or-node)
                        (some?  selector-or-node)  selector-or-node
                        :else                      nil)]
             (if (some? node)
               (do (if-let [setter (native-value-setter node)]
                     (.call setter node text)
                     ;; No prototype-declared setter (a plain object stand-in
                     ;; in a test harness, an exotic element): fall back to
                     ;; the ordinary assignment so the plain-DOM path keeps
                     ;; working.
                     (set! (.-value node) text))
                   ;; Reagent's onChange is wired to the React synthetic
                   ;; `change` event but reads from the underlying DOM
                   ;; `input` event; we dispatch both to be friendly to
                   ;; both substrates + react.
                   (.dispatchEvent node (make-input-event "input"))
                   (.dispatchEvent node (make-input-event "change"))
                   true)
               false))))

(defn focus!
  "Focus the element matched by `selector-or-node` and dispatch a
  synthetic `focus` + `focusin` event so handlers wired to either
  observe it. Returns true on success, false on no-such-node or no-DOM.
  JVM → false.

  `[:focus selector]` is a DOM step (spec/017 §Script step grammar): a
  headless runner refuses it with `:cannot-run` (the capability registry
  requires `:dom`); under a `:dom` / `:browser` runner it runs here."
  [selector-or-node]
  #?(:clj  false
     :cljs (let [node (cond
                        (string? selector-or-node) (query selector-or-node)
                        (some?  selector-or-node)  selector-or-node
                        :else                      nil)]
             (if (some? node)
               (do
                 ;; Native `.focus()` moves the document's active element;
                 ;; the synthetic events make the transition observable to
                 ;; handlers bound to either `focus` (non-bubbling) or
                 ;; `focusin` (bubbling).
                 (try (.focus node) (catch :default _ nil))
                 (try (.dispatchEvent node (js/Event. "focus"   #js {:bubbles false :cancelable false}))
                      (catch :default _ nil))
                 (try (.dispatchEvent node (js/Event. "focusin" #js {:bubbles true  :cancelable false}))
                      (catch :default _ nil))
                 true)
               false))))

;; ---- assertion helpers --------------------------------------------------

(defn assert-visible
  "Returns `{:passed? bool ... }`. `mode` is `:visible` or `:hidden`.
  JVM → `{:passed? false :skipped? true :message ...}`."
  [selector mode]
  (cond
    (not (dom-available?))
    {:passed?  false
     :skipped? true
     :message  (str "no DOM — cannot assert " (name mode) " for "
                    (pr-str selector))}

    :else
    (let [node       (query selector)
          present?   (some? node)
          rendered?  (and present? (visible? node))]
      (case mode
        :visible
        (if rendered?
          {:passed? true}
          {:passed? false
           :expected :visible
           :actual   (cond
                       (not present?) :missing
                       :else          :hidden)
           :message  (str "expected " (pr-str selector) " to be visible, "
                          (if present? "but it was hidden" "but it was missing"))})

        :hidden
        (if (or (not present?) (not rendered?))
          {:passed? true}
          {:passed? false
           :expected :hidden
           :actual   :visible
           :message  (str "expected " (pr-str selector) " to be hidden, "
                          "but it was visible")})

        ;; unknown mode
        {:passed? false
         :message (str "unknown :assert-dom mode " (pr-str mode))}))))

(defn assert-text
  "Returns `{:passed? bool ...}` for a `:text` assertion. JVM →
  `{:passed? false :skipped? true ...}`."
  [selector expected-text]
  (cond
    (not (dom-available?))
    {:passed?  false
     :skipped? true
     :message  (str "no DOM — cannot assert text for " (pr-str selector))}

    :else
    (let [node (query selector)]
      (cond
        (nil? node)
        {:passed? false
         :expected expected-text
         :actual   :missing
         :message  (str "expected " (pr-str selector) " text-content = "
                        (pr-str expected-text) ", but selector matched no node")}

        :else
        (let [got (text-content node)]
          (if (= expected-text got)
            {:passed? true}
            {:passed?  false
             :expected expected-text
             :actual   got
             :message  (str "expected " (pr-str selector) " text-content = "
                            (pr-str expected-text) ", got " (pr-str got))}))))))
