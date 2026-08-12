(ns re-frame.hicasso.overlay
  "OVERLAYS — the optional module, and the posture it exists to state
  (rf2-hic-052).

      (ns my.app
        (:require [re-frame.hicasso :as h]
                  [re-frame.hicasso.overlay :as overlay]))

      (h/defview filter-menu [{:keys [id]}]
        (let [trigger (str \"filter-\" id \"-trigger\")]
          [:div.filter
           [:button {:id            trigger
                     :aria-haspopup \"menu\"
                     :aria-expanded (h/sub [:filter-menu/open? id])
                     :on-click      [:filter-menu/toggled id]}
            \"Filter\"]
           [overlay/popover {:open?      (h/sub [:filter-menu/open? id])
                             :on-dismiss [:filter-menu/dismissed id]
                             :anchor     trigger
                             :placement  :bottom-start}
            [:ul {:role \"menu\"}
             [:li [:button {:role \"menuitem\"
                            :on-click [:filter/applied id :unread]}
                   \"Unread\"]]]]]))

  ## The posture

  **The top layer, the focus trap, the inertness, the LIFO stack, the
  light dismiss and the anchor tracking all belong to the browser. This
  module owns exactly one thing about them, and that thing is the
  IMPERATIVE CALL.**

  Every part of an overlay has an owner already. `<dialog>` owns modality
  — the page behind it goes inert and focus cannot leave it, enforced by
  the engine and not by a key handler. `popover` owns light dismiss and
  the auto stack. The top layer owns paint order, so no ancestor's
  `overflow`, `transform` or `z-index` can clip or out-stack an overlay
  and no z-index ladder is needed to try. CSS anchor positioning owns
  where a panel sits, and keeps owning it as the anchor moves. Exactly one
  gap sits between those owners and React: **entering the top layer is an
  imperative call.** An element does not get there by having an attribute
  — `<dialog open>` is a non-modal dialog in ordinary flow, and a
  `popover` element that nothing calls `showPopover` on is display:none.
  So this module calls `showModal` / `showPopover` at the one moment React
  offers before paint, and calls the inverse before React takes the node
  away. That is the whole module.

  So the things it does **not** build are worth naming, because a module
  called `overlay` invites every one of them: there is no portal, no
  focus-trap loop, no `document` key listener, no outside-click listener,
  no z-index policy, no scroll or resize listener, no `ResizeObserver`, no
  measurement of any kind, and no positioning engine. Each of those is a
  reimplementation of something the platform now does better, and every
  one of them is a failure mode the corpus actually shipped. This is not a
  floating-UI library and is not on its way to becoming one.

  ## What follows from the posture, and is witnessed

  `re-frame.hicasso.overlay-dom-cljs-test` is the witness; each claim
  below names the row that holds it, and the browser lane is where they
  are decided — the node lane has no top layer, so every claim about one
  degrades to a stated skip there.

  **The application owns the open flag, and nothing else can.** `:open?`
  false renders nil, so a closed overlay has no element, no listener, no
  anchor claim and no rendered body. An overlay opens because app-db says
  so and for no other reason, and the platform's own dismissal is routed
  back as an ordinary intent — which is what `:on-dismiss` is. Where there
  is no `:on-dismiss` the platform is told not to dismiss at all
  (`closedby=\"none\"`, `popover=\"manual\"`), so the two-owners state is
  not merely discouraged, it is unreachable.

  **Zero idle listeners, and zero cost when closed.** The module adds
  nothing to `window`, to `document` or to any ancestor — not while
  closed, not while open, not while the page scrolls under an anchored
  panel. Its listeners are the ones React attaches to the overlay element
  itself for `cancel` / `toggle`, and they leave with the node.

  **Anchor tracking is priced at zero because it is not work.**
  `:placement` becomes a CSS `position-area` against an anchor named on
  the trigger, and the engine recomputes it. The module does not measure,
  so there is nothing to re-measure on scroll.

  **The stack is the platform's, so nesting is LIFO for free.** A second
  overlay paints over the first whatever the DOM order and whatever the
  z-index; closing it puts the first back on top. A popover opened inside
  another popover's subtree leaves its parent open, and an unrelated one
  dismisses it — the auto stack's own rule, inherited rather than
  imitated.

  **Focus returns without a restore handler.** The module closes an
  overlay through the platform's door while its node is still connected,
  which is what makes `<dialog>`'s own focus restoration run. A node
  merely dropped from the document restores nothing, and that is the
  difference between this and the same code written with an effect.

  **Absent when unused.** `re-frame.hicasso` does not reach this
  namespace, so an application that never requires it carries none of it.
  `hicasso/scripts/check_optional_module_reachability.py` is what keeps
  that true, and it fails the moment the public door imports the module or
  its engine.

  ## Focus: the one-shot, and the recipe

  Focus is a one-shot — something done once when an overlay opens, not a
  value mirrored in app-db. Do not put the focused element in app-db.

  **Initial focus** is `:autofocus true` on the control that should have
  it, and the platform's own dialog-focusing steps are what honour it. The
  spelling matters and the witness is what settled it: Hicasso's attribute
  grammar camelCases a hyphenated key, so `:auto-focus` reaches React as
  `autoFocus`, which React applies by calling `.focus()` during the commit
  and WITHOUT emitting the attribute — one child-first commit before
  `showModal` runs its own focusing steps and moves focus somewhere else.
  `:autofocus`, unhyphenated, reaches the DOM as the attribute the
  platform reads. A modal with no autofocus target focuses its first
  focusable control.

  **Restoration** needs nothing from the author. See the posture above.

  ## The options

  | option | both | meaning |
  |---|---|---|
  | `:open?` | ✓ | whether the overlay exists at all |
  | `:on-dismiss` | ✓ | the intent the platform's own dismissal dispatches |
  | `:label` | ✓ | the accessible name, as `aria-label` |
  | `:anchor` | popover | the DOM id of the trigger to position against |
  | `:placement` | popover | a compass word — see [[re-frame.hicasso.impl.overlay/position-areas]] |
  | `:light-dismiss?` | modal | whether a backdrop click dismisses (default false) |

  Every other key is an ordinary attribute and reaches the element
  unrenamed, so `:class`, `:style`, `:role`, `:id`, `:data-*` and the
  platform's own event attributes all work exactly as they do at a native
  tag.

  ## Two things this bead deliberately did not do

  **`:exit-ms` is not here.** Retaining a leaving node for its exit
  animation is `re-frame.hicasso.motion`'s subject and already has a
  machine, a terminal bound and a witness. Composing the two is worth
  doing and is not this bead's; a second retention clock inside this
  module would be the duplicate `motion` exists to prevent.

  **A missing `:anchor` does not refuse.** The naming ledger's row 30
  reserves `:rf.error/hicasso-overlay-anchor-missing` for it and
  `docs/design/hicasso/product/complaints.md` carries it as a RESERVED
  spelling, explicitly provisional until the naming packet (rf2-hic-065)
  sits. Minting it also requires a Spec 009 catalogue row, which is
  outside this bead's surface. So an `:anchor` that names no element is a
  no-op today: the panel opens in the top layer at the UA's default
  position, visibly unanchored rather than silently mispositioned."
  (:require [re-frame.hicasso.impl.overlay :as impl-overlay]))

(def ^{:doc "`overlay/popover` — an anchored, light-dismissable panel on
  the browser's own top layer.

      [overlay/popover {:open?      (h/sub [:menu/open? id])
                        :on-dismiss [:menu/dismissed id]
                        :anchor     trigger-id
                        :placement  :bottom-start}
       [:ul {:role \"menu\"} …]]

  A legal hiccup head, marked the same way a `defview` product is — though
  it is not a Hicasso *reactive* boundary: it reads no subscription and
  holds no cell. `:open?` false renders nothing at all.

  `:anchor` is the DOM id of the trigger; the module gives that element a
  generated CSS anchor name while the panel is open and puts back whatever
  it found on the way out. `:placement` is the compass word that becomes a
  `position-area` against it. With `:on-dismiss` the panel is a
  `popover=\"auto\"` and takes its place in the platform's LIFO stack;
  without one it is `popover=\"manual\"` and dismisses for nothing, because
  a dismissal with nowhere to go is how an open flag acquires a second
  owner. [[re-frame.hicasso.impl.overlay/popover]]."}
  popover impl-overlay/popover)

(def ^{:doc "`overlay/modal` — a blocking dialog on the browser's own top
  layer, opened with `showModal`.

      [overlay/modal {:open?      (h/sub [:invoice/confirm-delete? id])
                      :on-dismiss [:invoice/delete-cancelled id]
                      :label      \"Confirm deletion\"}
       [:h2 \"Delete this invoice?\"]
       [:button {:autofocus true :on-click [:invoice/deleted id]} \"Delete\"]]

  A legal hiccup head, marked the same way a `defview` product is. `:open?`
  false renders nothing at all.

  Modality is the engine's: the rest of the document is inert, focus
  cannot leave the dialog, and `::backdrop` is a real CSS selector. Escape
  dispatches `:on-dismiss`; a backdrop click does so only with
  `:light-dismiss? true`, because a destructive confirmation must not go
  away on a stray click. Without `:on-dismiss` the dialog honours no close
  request at all. [[re-frame.hicasso.impl.overlay/modal]]."}
  modal impl-overlay/modal)
