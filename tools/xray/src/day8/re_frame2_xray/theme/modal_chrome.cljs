(ns day8.re-frame2-xray.theme.modal-chrome
  "Shared modal-chrome scaffold.

  ALL EIGHT of Xray's modal surfaces — Settings, Filter edit-popup,
  Share, Mute manager, App-DB segment-inspector, EDN-inspector popup,
  the Cancellation-cascade popover and the Command palette — render
  the SAME two-`:div` scaffold:

      [:div BACKDROP            ;; full-inset overlay, click-outside dismiss
       [:div DIALOG …content…]] ;; the WAI-ARIA dialog box

  and the SAME boring-but-load-bearing wiring on each:

    * the `data-rf-xray-modal-positioning` attribute derived from the
      `:rf.xray/modal-positioning` opt;
    * a click-to-dismiss `:on-click` on the backdrop + a
      `(.stopPropagation %)` `:on-click` on the dialog so clicks on
      the dialog body don't bubble out and close it;
    * the WAI-ARIA modal contract on the dialog — `a11y/dialog-attrs`
      (role + aria-modal + accessible name) merged with the
      `a11y/dialog-ref` focus-trap callback (focus-on-open, Tab-trap,
      restore-on-close).

  ## Design — slots, not flags

  This ns extracts ONLY that genuinely-identical scaffold. Everything
  that varies between modals stays a value the caller SUPPLIES — never
  a conditional inside this helper:

    * the backdrop's dim colour / blur / flex-alignment / padding /
      z-index → the caller passes the fully-computed `:backdrop-style`
      map (each modal keeps its own `backdrop-style` fn);
    * the dialog box size / chrome → the caller passes `:dialog-style`;
    * the dialog content (header / body / footer) → the `children`
      slot;
    * the accessible name → `:label` OR `:labelled-by` (forwarded to
      `a11y/dialog-attrs` verbatim);
    * the Esc / mnemonic key handling → `:on-backdrop-key-down` /
      `:on-dialog-key-down` (some modals trap Esc on the backdrop, some
      on the dialog, some on both, Settings adds tab mnemonics, the
      edit-popup handles keys on its input instead — every shape is a
      caller-supplied fn, not a flag);
    * per-modal `data-*` markers / extra props → `:backdrop-extra` /
      `:dialog-extra` (merged last so the caller can override anything,
      including the testid).

  ## The palette is a caller too

  The command palette (`palette/view`) renders through this scaffold.
  Its apparent divergences all land cleanly on
  the existing slots — no per-palette flag, no new slot:

    * always-`:fixed` (no `:rf.xray/modal-positioning` subscribe) → it
      passes the literal `:positioning :fixed`;
    * Esc handled inside its `:auto-focus` text input → it passes NO
      chrome keydown handler (the edit-popup pattern);
    * the `data-rf-xray-mode \"palette\"` marker → `:dialog-extra`;
    * its hand-rolled combobox/listbox ARIA stays in the `children`
      (the dialog-level role/aria-modal/accessible-name comes from
      `a11y/dialog-attrs` via `:label`, exactly the contract its
      `palette/aria-cljs-test` asserts).

  The chrome gives the palette the `a11y/dialog-ref`
  focus trap. The trap intercepts only Tab/Shift+Tab, and the palette's
  sole focusable is the input (rows drive a virtual
  `aria-activedescendant` cursor, not real focus), so Tab wraps to the
  input and the arrow-key navigation is untouched.

  ## Tab-index

  `dialog-ref`'s focus-on-open fallback focuses the dialog ROOT when
  the dialog has no focusable child, which requires the root to carry a
  `tab-index`. Modals supply their own value (`\"-1\"` for the
  text-style modals, `0` for the popovers) via `:dialog-tab-index`; the
  backdrop's optional `:backdrop-tab-index` mirrors the popovers' `-1`
  root."
  (:require [day8.re-frame2-xray.theme.a11y :as a11y]))

(defn modal-chrome
  "Render the shared backdrop + dialog scaffold around `children`.

  `opts` (every divergent bit is a value the caller supplies — see the
  ns docstring's slots-not-flags note):

    REQUIRED
      :positioning      — the `:rf.xray/modal-positioning` value
                          (`:fixed` / `:absolute`); stamped onto the
                          backdrop as `data-rf-xray-modal-positioning`.
      :backdrop-style   — the fully-computed backdrop style map.
      :dialog-style     — the fully-computed dialog style map.
      :on-dismiss       — 0-arg fn run on backdrop click (click-outside
                          dismiss).

    ACCESSIBLE NAME (one of)
      :label            — string accessible name (no visible title).
      :labelled-by      — DOM id of a visible heading inside `children`.
                          (Both forwarded verbatim to `a11y/dialog-attrs`.)

    OPTIONAL
      :backdrop-testid  — backdrop `:data-testid`.
      :dialog-testid    — dialog `:data-testid`.
      :on-backdrop-key-down / :on-dialog-key-down
                        — keydown handlers (nil = no handler attached).
      :backdrop-tab-index / :dialog-tab-index
                        — tab-index values (nil = attribute omitted).
      :dialog-ref       — the dialog `:ref`. Defaults to a fresh
                          `(a11y/dialog-ref)`; a caller (or a test) may
                          pass its own, or `false` to omit the ref.
      :backdrop-extra / :dialog-extra
                        — extra prop maps merged LAST onto the backdrop
                          / dialog (so a caller can add `data-rf-xray-
                          mode`, override the testid, etc.).

  Returns the `[:div backdrop [:div dialog children…]]` hiccup.
  `children` is zero or more hiccup forms spliced into the dialog after
  the merged props."
  [{:keys [positioning backdrop-style dialog-style on-dismiss
           label labelled-by backdrop-testid dialog-testid
           on-backdrop-key-down on-dialog-key-down
           backdrop-tab-index dialog-tab-index
           backdrop-extra dialog-extra]
    :as   opts}
   & children]
  ;; `:dialog-ref` defaults to a fresh `(a11y/dialog-ref)`. A caller may
  ;; pass its own callback, or `nil`/`false` to omit the ref entirely
  ;; (the key-present-but-falsey case — distinct from the key-absent
  ;; default). Hence `contains?` rather than reading the destructured
  ;; binding, which can't tell "absent" from "nil".
  (let [the-ref        (if (contains? opts :dialog-ref)
                         (:dialog-ref opts)
                         (a11y/dialog-ref))
        backdrop-props (cond-> {:data-rf-xray-modal-positioning
                                (name (or positioning :fixed))
                                ;; Click on the backdrop (outside the
                                ;; dialog) dismisses. `stopPropagation`
                                ;; keeps the click from bubbling past the
                                ;; overlay to shell chrome beneath — the
                                ;; edn-inspector stacked-popup case relies
                                ;; on it, and it is a no-op-to-correct for
                                ;; the single-mount modals (the backdrop is
                                ;; the outermost node of each mount).
                                :on-click (fn [^js e]
                                            (.stopPropagation e)
                                            (when on-dismiss (on-dismiss)))
                                :style    backdrop-style}
                         backdrop-testid            (assoc :data-testid backdrop-testid)
                         on-backdrop-key-down       (assoc :on-key-down on-backdrop-key-down)
                         (some? backdrop-tab-index) (assoc :tab-index backdrop-tab-index)
                         backdrop-extra             (merge backdrop-extra))
        dialog-props   (cond-> (merge (a11y/dialog-attrs {:label       label
                                                          :labelled-by labelled-by})
                                      {:on-click (fn [^js e] (.stopPropagation e))
                                       :style    dialog-style})
                         the-ref                  (assoc :ref the-ref)
                         dialog-testid            (assoc :data-testid dialog-testid)
                         on-dialog-key-down       (assoc :on-key-down on-dialog-key-down)
                         (some? dialog-tab-index) (assoc :tab-index dialog-tab-index)
                         dialog-extra             (merge dialog-extra))]
    [:div backdrop-props
     (into [:div dialog-props] children)]))
