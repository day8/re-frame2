(ns day8.re-frame2-causa.theme.a11y
  "Shared accessibility helpers (rf2-7389r).

  Causa ships six modal surfaces (Settings, Share, Spine-filter mute
  manager, Filter edit-popup, Cancellation-cascade popover, App-DB
  segment-inspector popover). The P2 a11y audit (`ai/findings/2026-
  05-20-causa-story-a11y-audit.md` Finding #3) flagged that none of
  them carry the WAI-ARIA modal contract end-to-end — `role=\"dialog\"`
  + `aria-modal=\"true\"` + an accessible name + focus capture on
  open.

  Rather than copy the contract into every modal (drift risk — the
  audit observed exactly this drift already, with each modal sliding
  away from a coherent ARIA story), this ns extracts the small bits
  the six call sites share.

  ## What lives here

    - `dialog-attrs` — the canonical ARIA attribute map a dialog
      wrapper merges into its `:div` props. Pure data; one source of
      truth for the role / aria-modal / accessible-name shape.
    - `dialog-ref` — a React `:ref` callback that wires the full
      WAI-ARIA APG modal-dialog focus contract end-to-end:

        1. **Focus capture on open** — focuses the first focusable
           element inside the dialog when it mounts (falls back to the
           dialog root, which the caller marks `tab-index=\"-1\"`).
        2. **Focus TRAP** — while the dialog is open, `Tab` /
           `Shift+Tab` cycle within the dialog's focusable set rather
           than leaking to the chrome beneath. Tab from the last
           focusable wraps to the first; Shift+Tab from the first wraps
           to the last (the APG dialog recipe).
        3. **Focus RESTORE on close** — captures `document.activeElement`
           at open and re-focuses it on unmount, so closing the dialog
           returns the keyboard user to where they were (the opener
           button), instead of dropping focus to `<body>`.

  ## Why a `:ref` callback rather than `useEffect` / lifecycle

  The dialog views in this codebase are plain Reagent fns — no
  `r/create-class`, no `useEffect`. A `:ref` callback is the smallest
  surface that hooks into the mount lifecycle without restructuring
  the call site. React invokes the ref with the DOM node on mount
  and `nil` on unmount; `dialog-ref` keys both: the mount call captures
  the opener + installs the trap listener; the unmount call tears the
  listener down + restores focus.

  Each `dialog-ref` call returns a FRESH closure carrying its own
  capture/listener state, so a re-mount (close-then-reopen) starts a
  clean lifecycle and never restores a stale opener."
  (:require [clojure.string :as str]))

;; ---- pure-data attrs ----------------------------------------------------

(defn dialog-attrs
  "Build the canonical ARIA attribute map a modal dialog merges into
  its outer `:div` props. `opts` may carry:

      :label        — accessible name (string). Used when there is no
                      visible heading the dialog can reference by id.
      :labelled-by  — DOM id of a heading element inside the dialog.
                      Preferred over `:label` when the dialog has a
                      visible title (screen readers will read the
                      heading text).
      :describedby  — DOM id of a description element (optional).

  Returns a map with `:role`, `:aria-modal`, and one of
  `:aria-label` / `:aria-labelledby` set. Suitable for `(merge …)`
  into the caller's existing prop map.

  Pure data → map; JVM-portable so the .cljc-naming pattern can pick
  up these tests on both runners."
  [{:keys [label labelled-by describedby]}]
  (cond-> {:role       "dialog"
           :aria-modal "true"}
    labelled-by  (assoc :aria-labelledby labelled-by)
    (and label
         (not labelled-by))
    (assoc :aria-label label)
    describedby  (assoc :aria-describedby describedby)))

;; ---- focus capture ref --------------------------------------------------

(def focusable-selector
  "CSS selector matching elements that can receive keyboard focus by
  default. Used by `dialog-ref` to find the focusable descendants of a
  mounted dialog (for both the focus-on-open landing target and the
  Tab-trap cycle endpoints).

  Excludes elements with `disabled` (attribute selector) and matches
  the WAI-ARIA APG dialog pattern's canonical focusable set."
  (str/join ","
            ["a[href]"
             "button:not([disabled])"
             "input:not([disabled])"
             "select:not([disabled])"
             "textarea:not([disabled])"
             "[tabindex]:not([tabindex=\"-1\"])"]))

;; ---- pure trap math -----------------------------------------------------

(defn trap-wrap-target
  "Pure helper computing where a `Tab` keystroke should land when it
  would otherwise leak out of a modal dialog's focusable cycle.

  Given the dialog's ordered `focusables` vector, the currently-focused
  `active` element, and whether `shift?` is held, return the element to
  redirect focus to — or `nil` when no redirect is needed (the browser's
  native Tab moves focus correctly inside the cycle).

  Wrap rules (WAI-ARIA APG dialog pattern):

    - `Tab` from the LAST focusable → wrap to the FIRST.
    - `Shift+Tab` from the FIRST focusable → wrap to the LAST.
    - `Tab` / `Shift+Tab` when focus is OUTSIDE the dialog's focusable
      set (e.g. focus sits on the `tab-index=-1` dialog root, or on an
      element not in `focusables`) → pull focus to the boundary element
      (first for Tab, last for Shift+Tab) so the trap re-captures it.
    - Single focusable → that element is both first and last, so any
      Tab wraps back to it.

  Returns nil for the in-between case (focus on a non-boundary
  focusable) so the caller leaves the native Tab alone. Pure data →
  element-or-nil; JVM-runnable so the wrap math is tested without DOM."
  [focusables active shift?]
  (let [n (count focusables)]
    (when (pos? n)
      (let [first-el (nth focusables 0)
            last-el  (nth focusables (dec n))
            idx      (loop [i 0]
                       (cond
                         (= i n)                  nil
                         (= active (nth focusables i)) i
                         :else                    (recur (inc i))))]
        (cond
          ;; focus outside the cycle → pull to the boundary
          (nil? idx)              (if shift? last-el first-el)
          ;; Shift+Tab off the first → wrap to last
          (and shift? (zero? idx))     last-el
          ;; Tab off the last → wrap to first
          (and (not shift?) (= idx (dec n))) first-el
          ;; mid-cycle → let the browser handle it
          :else                   nil)))))

;; ---- the full modal focus-contract ref ----------------------------------

(defn- node-list->vec
  "Coerce a DOM `NodeList` (from `querySelectorAll`) to a CLJS vector,
  preserving document order. Guards `nil` (no DOM) → empty vector."
  [^js node-list]
  (if node-list
    (into [] (for [i (range (.-length node-list))]
               (.item node-list i)))
    []))

(defn dialog-ref
  "Build a React `:ref` callback wiring the full WAI-ARIA APG modal
  focus contract — capture-on-open, Tab-trap, restore-on-close — onto
  the dialog's outer `:div`.

  React invokes the ref with the DOM node on mount and `nil` on
  unmount. This closure keeps per-instance state so both calls are
  handled:

  ## On mount (node non-nil)

    1. Captures `document.activeElement` — the element that had focus
       when the dialog opened (typically the opener button). Stashed so
       the unmount call can restore it.
    2. Focuses the first focusable descendant, or the dialog root
       itself when there is none (e.g. an empty-state mute manager).
       The root must carry `tab-index=\"-1\"` for that fallback to take.
    3. Installs a `keydown` listener on the dialog node that intercepts
       `Tab` / `Shift+Tab` and wraps focus inside the dialog's
       focusable set (see `trap-wrap-target`). `preventDefault` stops
       the native focus move only when a wrap is needed.

  ## On unmount (node nil)

    1. Removes the keydown listener.
    2. Restores focus to the captured opener (when it is still in the
       document and focusable), so closing the dialog returns the
       keyboard user to where they were — not to `<body>`.

  Tolerant of a DOM-less runtime (Node test target): when
  `js/document` is absent it degrades to a no-op ref, so the same view
  fn renders on both the browser-test and node-test builds."
  []
  (let [opener   (atom nil)     ;; element focused before the dialog opened
        listener (atom nil)     ;; the installed keydown handler (for teardown)
        node*    (atom nil)]    ;; the dialog node (closure over remove-listener)
    (fn [^js node]
      (when (exists? js/document)
        (if node
          ;; ---- mount ------------------------------------------------------
          (do
            (reset! node* node)
            ;; Capture the opener for restore-on-close. Only an element
            ;; OUTSIDE the dialog is a valid opener — if focus already
            ;; sits inside (an `:auto-focus` input ran before this ref),
            ;; the original opener is no longer derivable, so we record
            ;; nil and skip restore rather than restoring into the
            ;; closed dialog's stale subtree.
            (let [active (.-activeElement js/document)
                  inside? (and active (not= active node) (.contains node active))]
              (reset! opener (when-not inside? active))
              ;; (1)+(2) land focus inside the dialog — UNLESS something
              ;; inside already holds focus (e.g. the filter edit-popup's
              ;; `:auto-focus` pattern field). Respecting a pre-focused
              ;; descendant keeps the caller's intended landing target
              ;; while still installing the trap + restore below.
              (when-not inside?
                (let [target (or (.querySelector node focusable-selector) node)]
                  (try (.focus target) (catch :default _ nil)))))
            ;; (3) install the Tab trap
            (let [handler
                  (fn [^js e]
                    (when (= "Tab" (.-key e))
                      (let [focusables (node-list->vec
                                         (.querySelectorAll node focusable-selector))]
                        ;; No focusable child → keep focus pinned on the
                        ;; dialog root (which is tab-index=-1) so Tab can't
                        ;; escape to the chrome beneath.
                        (if (empty? focusables)
                          (do (.preventDefault e)
                              (try (.focus node) (catch :default _ nil)))
                          (when-let [target (trap-wrap-target
                                              focusables
                                              (.-activeElement js/document)
                                              (.-shiftKey e))]
                            (.preventDefault e)
                            (try (.focus target) (catch :default _ nil)))))))]
              (reset! listener handler)
              (.addEventListener node "keydown" handler)))
          ;; ---- unmount ----------------------------------------------------
          (do
            (when-let [n @node*]
              (when-let [h @listener]
                (.removeEventListener n "keydown" h)))
            (reset! listener nil)
            (reset! node* nil)
            ;; restore focus to the opener if it is still attached +
            ;; focusable (a removed/replaced opener is skipped rather
            ;; than throwing).
            (let [prev @opener
                  body (.-body js/document)]
              (when (and prev
                         (.-focus prev)
                         body
                         (.contains body prev))
                (try (.focus prev) (catch :default _ nil))))
            (reset! opener nil)))))))
