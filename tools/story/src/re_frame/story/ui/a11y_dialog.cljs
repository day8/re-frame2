(ns re-frame.story.ui.a11y-dialog
  "Shared a11y helpers for Story's modal dialogs (rf2-p1ai7).

  Modal dialogs need a coherent a11y posture:

  - role='dialog' + aria-modal='true' on the modal wrapper.
  - Accessible name via aria-labelledby (preferred — visible label is
    the title) or aria-label.
  - Focus moves into the dialog on mount.
  - Focus is trapped: Tab cycles forward to the first focusable on
    overflow; Shift-Tab cycles back to the last on underflow.
  - Escape closes the dialog.
  - Focus returns to the element that opened the dialog on close.

  Story's command palette and help overlay already do the role + aria
  bits inline; this ns centralises the focus-trap + return-focus + key
  handling so the three dialogs covered by rf2-p1ai7 (recorder
  assertion picker, recorder save dialog, recorder export dialog,
  save-variant dialog — all four since the save flows share
  `review-dialog`) get the same posture without per-dialog drift.

  Per the pre-alpha posture: each affordance is complete or not at all
  — a focus trap without Escape, or without return-focus, is half-
  shipped. This helper bundles all three so callers can't get it
  partially right.

  ## Surface

  - `[focus-trap opts child-hiccup]` — a Reagent class-3 component
    that wraps its child hiccup with:
    - window-level keydown listener (Escape → on-close).
    - capture-phase Tab listener on its root node (Tab cycles).
    - focus-on-mount to the first focusable descendant (or
      `:initial-focus-ref` when supplied).
    - focus-return on unmount to the previously focused element.

    `opts` is a map with `:on-close` (required, zero-arg fn) +
    optional `:initial-focus-ref` (an atom/ref the dialog populates
    with the element to focus on mount).

    `child-hiccup` is the dialog's hiccup as-is — passed eagerly (not
    via a thunk) so tests can string-traverse the rendered tree
    without invoking the class component's render lifecycle.

  ## Why a class-3 wrapper

  We need the underlying DOM node post-mount to compute the focusable
  set and install the capture-phase Tab handler. Function components
  don't give us a stable ref handle without `r/with-let` + a manual
  ref-callback, which a CLJS-side class wrapper handles more cleanly."
  (:require [reagent.core :as r]))

(def ^:private focusable-selector
  "CSS selector matching elements that participate in the Tab order.
  Excludes `[tabindex='-1']` so roving-focus list items don't trap the
  cycle; explicitly includes `[tabindex='0']` for non-natively-focusable
  elements that opt in."
  (str "a[href], "
       "button:not([disabled]), "
       "input:not([disabled]), "
       "select:not([disabled]), "
       "textarea:not([disabled]), "
       "[tabindex='0']"))

(defn- focusable-descendants
  "Return a JS array of focusable DOM nodes inside `root-el`. Order is
  document order, matching the natural Tab traversal."
  [^js root-el]
  (when (and root-el (some? (.-querySelectorAll root-el)))
    (let [nodes (.querySelectorAll root-el focusable-selector)]
      (array-seq nodes))))

(defn- focus-element!
  "Focus `el` defensively — wrap in try/catch since hidden elements,
  detached nodes, and JSDOM corner cases throw."
  [^js el]
  (when el
    (try
      (.focus el)
      (catch :default _ nil))))

(defn- handle-tab-cycle!
  "Capture-phase Tab handler. When Tab fires on the last focusable
  descendant, focus wraps to the first; Shift-Tab on the first wraps
  to the last. The cycle is rooted at `root-el` so the dialog's tab
  traversal never escapes its own subtree."
  [^js root-el ^js evt]
  (when (and (= "Tab" (.-key evt)) root-el)
    (let [focusables (vec (focusable-descendants root-el))]
      (when (seq focusables)
        (let [first-el (first focusables)
              last-el  (last focusables)
              active   (.-activeElement js/document)
              shift?   (.-shiftKey evt)]
          (cond
            (and shift? (or (= active first-el)
                            (not (some #(= active %) focusables))))
            (do
              (.preventDefault evt)
              (focus-element! last-el))

            (and (not shift?) (or (= active last-el)
                                  (not (some #(= active %) focusables))))
            (do
              (.preventDefault evt)
              (focus-element! first-el))

            :else nil))))))

(defn focus-trap
  "Reagent class-3 component that wraps a modal dialog's render output
  with focus management:

    - Captures the previously-focused element on mount; restores focus
      to it on unmount.
    - Focuses the first focusable descendant on mount (or
      `:initial-focus-ref` if the caller populated it).
    - Cycles Tab / Shift-Tab on the focusable descendants so focus
      never leaves the dialog.
    - Listens for Escape on window and invokes `:on-close`.

  `opts`:
    :on-close          — required zero-arg fn invoked on Escape.
    :initial-focus-ref — optional atom; deref'd on mount and (when
                         non-nil) focused in preference to the first
                         tab-stop. Useful for the assertion-picker's
                         vocabulary-list roving focus, where the
                         currently-active list item is the right
                         starting point.

  `child-hiccup` — the dialog's hiccup vector, passed eagerly. The
  child is spliced under the helper's wrapper div; the wrapper
  carries no visual styling (display:contents) so callers retain
  their own backdrop / panel layout."
  [_opts _child-hiccup]
  (let [root-ref     (atom nil)
        prev-active  (atom nil)
        key-handler  (atom nil)
        tab-handler  (atom nil)]
    (r/create-class
      {:display-name "rf-story-a11y-focus-trap"

       :component-did-mount
       (fn [this]
         (let [{:keys [on-close initial-focus-ref]} (-> this .-props .-argv second)]
           ;; Snapshot the currently-focused element so we can restore on close.
           (when (exists? js/document)
             (reset! prev-active (.-activeElement js/document)))
           ;; Window-level Escape handler. Use capture so we win over
           ;; any in-dialog handler that might preventDefault first.
           (let [f (fn [^js evt]
                     (when (= "Escape" (.-key evt))
                       (.preventDefault evt)
                       (.stopPropagation evt)
                       (on-close)))]
             (reset! key-handler f)
             (when (exists? js/window)
               (.addEventListener js/window "keydown" f true)))
           ;; Capture-phase Tab handler on the wrapper root. Capture so
           ;; the cycle decision happens before any inner handler.
           (let [f (fn [^js evt]
                     (handle-tab-cycle! @root-ref evt))]
             (reset! tab-handler f)
             (when-let [root @root-ref]
               (.addEventListener root "keydown" f true)))
           ;; Initial focus — prefer the caller's nominated ref, else
           ;; the first focusable descendant. Defer to a microtask so
           ;; the dialog's render is in the DOM.
           (when (exists? js/queueMicrotask)
             (js/queueMicrotask
               (fn []
                 (let [explicit (when initial-focus-ref @initial-focus-ref)
                       target   (or explicit
                                    (first (focusable-descendants @root-ref)))]
                   (focus-element! target)))))))

       :component-will-unmount
       (fn [_this]
         (when-let [f @key-handler]
           (when (exists? js/window)
             (.removeEventListener js/window "keydown" f true)))
         (when-let [f @tab-handler]
           (when-let [root @root-ref]
             (.removeEventListener root "keydown" f true)))
         ;; Return focus to the element that opened the dialog. Defer
         ;; to a microtask so React's unmount has settled.
         (when (exists? js/queueMicrotask)
           (let [target @prev-active]
             (js/queueMicrotask (fn [] (focus-element! target))))))

       :reagent-render
       (fn [_opts child-hiccup]
         [:div {:ref (fn [el] (reset! root-ref el))
                ;; display:contents so the wrapper participates in flex /
                ;; grid layouts identically to a fragment — the dialog's
                ;; own backdrop / panel decides the visual frame.
                :style {:display "contents"}}
          child-hiccup])})))
