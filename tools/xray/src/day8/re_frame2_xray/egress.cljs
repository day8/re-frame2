(ns day8.re-frame2-xray.egress
  "Xray's panel-local off-box safe-egress projection.

  INTERNAL to Xray. This namespace is not part of Xray's published API and
  carries no api-manifest row: it exists because a PANEL affordance puts a
  value the developer is looking at onto an off-box sink, and needs the
  framework's wire-elision walker with the off-box defaults already applied.

  ONE human value-egress affordance today (Security.md §Off-box egress): the
  command-palette `Snapshot app-db` verb — JS console + system clipboard
  (`palette/events.cljs`).

  Value inspectors carry no universal copy-value affordance — it is out of
  contract under the `spec/021` §10.5 B.9 lock. This namespace is the
  MUST-use gesture any FUTURE value-egress affordance inherits, so its
  fail-closed arms are pinned by tests beyond what the one live caller
  exercises. Static Machines' `Copy Mermaid` also writes to the clipboard but
  is NOT a value-egress site — `mermaid/emit` is value-free by contract, so
  that text rides `:rf.xray.fx/copy-to-clipboard` directly.

  Programmer/AI inspection of a running app is NOT this namespace's job:
  that seam is `re-frame2-pair.runtime` plus
  `tools/re-frame2-pair-mcp/`, which reads the framework's instrumentation
  directly. Xray owns the human panel; Pair owns the agent runtime.

  Fail-closed by construction. The framework door
  `re-frame.core/project-egress` takes NO default boundary — a caller must
  know to name one — and the UNSAFE call (`pr-str` the raw value you
  already hold) is shorter. Baking the boundary here makes the shortest
  call the safe one."
  (:require [re-frame.core :as rf]))

(defn egress-value
  "Project `value` for an off-box sink (console, clipboard) through the
  framework's egress door with the off-box BOUNDARY baked in.

  Named boundary: `:rf.egress/off-box-observability`, the profile whose
  `:rf.egress/*` floor is sensitive redacts, large elides, NO structural
  digests. It is deliberately NOT
  `:rf.egress/off-box-tool`, which names the MCP / AI tool wire rather than
  a human sink. Neither profile turns `:rf.egress/include-digests?` on;
  whether an Xray clipboard payload should carry digests is a separate
  policy question, not a side effect of naming a boundary. A frame-declared sensitive slot egresses as `:rf/redacted`
  and a large slot as the `:rf.size/large-elided` marker. Xray's panel
  affordances expose no opt-in argument — the snapshot path is ALWAYS the
  redacted, size-elided projection, and any future affordance inherits
  that.

  A caller MAY still overlay an explicit `:rf.egress/*` inclusion; per
  EP-0015 §10 the explicit key wins over the profile floor. The opts are
  read in the `:rf.egress/*` spelling — the ONE egress vocabulary.

  Optional `:path` — the ABSOLUTE app-db path the value sits at. The
  framework's `:sensitive` / `:large` declarations (EP-0025 commit-plane
  classification effects; Spec 015 §Data classification) are keyed by
  absolute path, so a SLICE egress'd in isolation must tell the walker where
  it lives or the declaration will not match. Defaults to `[]` — the value IS
  the walked root, which is what the whole-db snapshot passes.

  Optional `:frame` — the frame whose declarations govern. Naming it is the
  fail-closed gesture, and a panel affordance MUST use it. Without `:frame`
  the walk resolves the AMBIENT frame, which for an Xray affordance is the
  live `:rf/xray` chrome frame: that frame resolves, so the walk applies
  its (normally empty) declaration registry and ships the value RAW. Passing
  the inspected frame — even when it is `nil` or has since been destroyed —
  routes the nil/dead case to the frameless arm, which redacts the
  whole value to `:rf/redacted`. The key is forwarded VERBATIM:
  `project-egress` reads its `:frame` opt by PRESENCE, so an explicit nil
  is believed. The contract — no live caller today, and
  the shape any future panel affordance MUST take:

      (egress/egress-value v {:frame observed})   ; nil / dead ⇒ :rf/redacted

  Passing NO `:frame` key at all is still the right call where the caller has
  already established the frame itself — the palette snapshot resolves and
  validates the focused frame before it reads the db, and wraps the call in
  `(rf/with-frame tf …)`."
  ([value]
   (egress-value value nil))
  ([value {:keys [path] :as opts}]
   (rf/project-egress
     value
     (cond-> {:rf.egress/profile :rf.egress/off-box-observability}
       (seq path)              (assoc :path (vec path))
       ;; An explicitly-passed `:frame` is forwarded VERBATIM, nil
       ;; included: the door reads the opt by key presence, so an
       ;; explicit nil means "no governing frame" and takes its
       ;; fail-closed arm instead of falling through to the ambient frame.
       (contains? opts :frame) (assoc :frame (:frame opts))
       ;; EP-0015 §10 — an explicit `:rf.egress/*` inclusion the caller
       ;; passes OVERLAYS the profile floor (the override wins). Absent,
       ;; the profile's own false stands.
       (contains? opts :rf.egress/include-sensitive?)
       (assoc :rf.egress/include-sensitive? (:rf.egress/include-sensitive? opts))
       (contains? opts :rf.egress/include-large?)
       (assoc :rf.egress/include-large? (:rf.egress/include-large? opts))))))
