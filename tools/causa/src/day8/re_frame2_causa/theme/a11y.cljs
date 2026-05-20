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
    - `focus-on-mount-ref` — a React `:ref` callback that focuses the
      first focusable element inside a dialog when it mounts. Handles
      the common case where the dialog itself needs to be focused
      (so Esc / arrow-keys / Tab work from a known starting point);
      falls back to focusing the dialog root if no focusable child
      is found.

  ## Why a `:ref` callback rather than `useEffect` / lifecycle

  The dialog views in this codebase are plain Reagent fns — no
  `r/create-class`, no `useEffect`. A `:ref` callback is the smallest
  surface that hooks into the mount lifecycle without restructuring
  the call site. React invokes the ref with the DOM node on mount
  and `nil` on unmount; we only act on the mount call.

  ## What does NOT live here (yet)

    - Focus RESTORE on close — capturing `document.activeElement` on
      open and re-focusing it on close. Tracked as audit finding #8
      (P2, separate bead).
    - Focus TRAP — preventing Tab from leaking to chrome beneath the
      modal. The current behaviour relies on the modal's backdrop +
      Esc handler to be the user's primary exit; a full trap is the
      next rev."
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

(def ^:private focusable-selector
  "CSS selector matching elements that can receive keyboard focus by
  default. Used by `focus-on-mount-ref` to find the first focusable
  descendant of a freshly-mounted dialog.

  Excludes elements with `disabled` (attribute selector) and matches
  the WAI-ARIA APG dialog pattern's canonical focusable set."
  (str/join ","
            ["a[href]"
             "button:not([disabled])"
             "input:not([disabled])"
             "select:not([disabled])"
             "textarea:not([disabled])"
             "[tabindex]:not([tabindex=\"-1\"])"]))

(defn focus-on-mount-ref
  "Build a React `:ref` callback that focuses the first focusable
  descendant of the mounted dialog node — or the dialog node itself
  if none is found.

  Returns a function suitable for use as `:ref` on the dialog's outer
  `:div`. The callback handles unmount (node `nil`) as a no-op so it
  never throws after the dialog closes.

  Idempotent on remount — each mount triggers the focus call once.

  ## Why focus the dialog node as a fallback

  WAI-ARIA APG's dialog pattern says focus must land *inside* the
  dialog on open. When the dialog has no focusable child (e.g. an
  empty-state mute manager), focusing the dialog root with
  `tab-index=\"-1\"` keeps Esc / arrow-keys functional and gives
  screen readers a programmatic focus target.

  Caller must set `:tab-index \"-1\"` on the dialog div so the
  fallback `.focus()` call succeeds.

  Pre-alpha caveat: this does NOT trap focus — Tab can still leak to
  chrome beneath. Trap is the next rev (rf2-7389r follow-on)."
  []
  (fn [^js node]
    (when node
      (let [focusable (.querySelector node focusable-selector)
            target    (or focusable node)]
        (try
          (.focus target)
          (catch :default _ nil))))))
