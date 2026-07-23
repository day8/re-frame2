(ns re-frame.freehand.slot-views
  "The INTERPRETED half of the render-slot / props-forwarding parity corpus.

  Every declaration here has a byte-identical twin in
  [[re-frame.freehand.slot-views-compiled]] carrying `{:compiled true}` and
  nothing else. The parity suite renders both against the same calls and
  compares the trees, so a body that means one thing interpreted and another
  compiled has nowhere to hide.

  JVM-only (`.clj`) deliberately: the compiled twins lower render slots and
  forwarded prop maps through the STRUCTURAL emitter, which is the host both
  modes share today. The compiled React lowering of these forms lands with the
  compiled browser runtime and brings its own suite."
  (:require [re-frame.freehand :as v]))

(v/defview cell
  "An ordinary declared child, mounted from inside a render-fn body — so the
  corpus proves a slot renders a BOUNDARY and not merely markup."
  [{:keys [label]}]
  [:td.cell label])

(v/defview inline-slot
  "The slot the compiler can see whole: the render-fn is lexically visible at
  the invocation, so both front ends lower it from the same source."
  [{:keys [label]}]
  [:tbody [:tr (v/slot (v/render-fn [r] [:td r]) label)]])

(v/defview inline-slot-nullary
  "A slot with no parameters — content, not a function of anything."
  [_]
  [:div (v/slot (v/render-fn [] [:span.static "static"]))])

(v/defview inline-slot-binary
  "Two arguments, in order."
  [{:keys [label]}]
  [:div (v/slot (v/render-fn [a b] [:span a "-" b]) label "tail")])

(v/defview inline-slot-among-siblings
  "The rendered output participates in the surrounding children exactly like
  any other child — no slot node, no wrapper."
  [{:keys [label]}]
  [:ul [:li "first"] (v/slot (v/render-fn [r] [:li r]) label) [:li "last"]])

(v/defview inline-slot-absent
  "An ABSENT slot renders nothing, and the siblings close over the gap — a
  component may offer content it does not require."
  [{:keys [row]}]
  [:tr [:td "before"] (v/slot row "a") [:td "after"]])

(v/defview seam
  "The library seam: content arrives as a PROP and is invoked per row."
  [{:keys [rows row]}]
  [:tbody (for [r rows] [:tr {:key r} (v/slot row r)])])

(v/defview table
  "The caller, supplying the seam's content. Caller and seam are declared in
  the same MODE, which is what the crossing law requires: an interpreted
  render-fn body answers markup, a compiled one answers a node (D010)."
  [{:keys [rows]}]
  [seam {:rows rows :row (v/render-fn [r] [cell {:label r}])}])

(v/defview spread-card
  "`v/spread` on an element carrying `.class` sugar — the shape a fold that
  replaced rather than composed would silently break."
  [{:keys [attrs]}]
  [:div.card (v/spread attrs {:title "forwarded"})])

(v/defview spread-base-only
  "The one-argument spelling: forward the map and nothing else."
  [{:keys [attrs]}]
  [:section (v/spread attrs)])

(v/defview safe-input
  "The bounded forward: a component owns the controlled pair and its own
  handler, and the consumer's attrs fold under them."
  [{:keys [caller]}]
  [:input.field (v/spread-safe {:value "owned" :on-change [:field/changed]}
                               caller)])

(v/defview safe-input-classy
  "`:class` is the ONE key that composes rather than losing — owned first."
  [{:keys [caller]}]
  [:span.badge (v/spread-safe {:class "is-owned"} caller)])

(def by-name
  "The corpus, keyed by the name the parity table names it under."
  {:inline-slot            inline-slot
   :inline-slot-nullary    inline-slot-nullary
   :inline-slot-binary     inline-slot-binary
   :inline-slot-among-siblings inline-slot-among-siblings
   :inline-slot-absent     inline-slot-absent
   :table                  table
   :spread-card            spread-card
   :spread-base-only       spread-base-only
   :safe-input             safe-input
   :safe-input-classy      safe-input-classy})
