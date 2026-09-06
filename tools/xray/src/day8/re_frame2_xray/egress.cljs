(ns day8.re-frame2-xray.egress
  "Xray's panel-local off-box safe-egress projection.

  INTERNAL to Xray. This namespace is not part of Xray's published API and
  carries no api-manifest row: it exists because a PANEL affordance puts a
  value the developer is looking at onto an off-box sink, and needs the
  framework's wire-elision walker with the off-box defaults already applied.

  ONE human value-egress affordance today (Security.md §Off-box egress): the
  command-palette `Snapshot app-db` verb — JS console + system clipboard
  (`palette/events.cljs`).

  The universal `⎘` copy-value affordance that once rode every value
  inspector was RETIRED on 2026-09-04 (rf2-6r9j.24) — unreachable since the
  rf2-oqa60 rebuild, and out of contract under the `spec/021` §10.5 B.9 lock.
  This namespace stays the MUST-use gesture any FUTURE affordance inherits,
  so its fail-closed arms are kept and pinned by tests rather than deleted
  with it. Static Machines' `Copy Mermaid` also writes to the clipboard but
  is NOT a value-egress site — `mermaid/emit` is value-free by contract, so
  that text rides `:rf.xray.fx/copy-to-clipboard` directly.

  Programmer/AI inspection of a running app is NOT this namespace's job and
  never was: that seam is `re-frame2-pair.runtime` plus
  `tools/re-frame2-pair-mcp/`, which reads the framework's instrumentation
  directly. Xray owns the human panel; Pair owns the agent runtime.

  Fail-closed by construction. `re-frame.core/elide-wire-value` does NOT bake
  the off-box defaults — a caller must know to pass
  `:rf.size/include-sensitive? false` + `:rf.size/include-large? false`, and
  the UNSAFE call (`pr-str` the raw value you already hold) is shorter. Baking
  the defaults here makes the shortest call the safe one (rf2-rcogp)."
  (:require [re-frame.core :as rf]))

(defn egress-value
  "Project `value` for an off-box sink (console, clipboard) through the
  framework's wire-elision walker with the off-box defaults BAKED IN.

  Off-box defaults: `:include-sensitive?` and `:include-large?` are both
  `false`, so a frame-declared sensitive slot egresses as `:rf/redacted` and
  a large slot as the `:rf.size/large-elided` marker. Xray's panel
  affordances expose no opt-in argument — the snapshot path is ALWAYS the
  redacted, size-elided projection, and any future affordance inherits that.

  Optional `:path` — the ABSOLUTE app-db path the value sits at. The
  framework's `:sensitive` / `:large` declarations (EP-0025 commit-plane
  classification effects; Spec 015 §Data classification) are keyed by
  absolute path, so a SLICE egress'd in isolation must tell the walker where
  it lives or the declaration will not match. Defaults to `[]` — the value IS
  the walked root, which is what the whole-db snapshot passes.

  Optional `:frame` — the frame whose declarations govern. Naming it is the
  fail-closed gesture, and a panel affordance MUST use it. Without `:frame`
  the walker resolves the AMBIENT frame, which for an Xray affordance is the
  live `:rf/xray` chrome frame: that frame resolves, so the walker applies
  its (normally empty) declaration registry and ships the value RAW. Passing
  the inspected frame — even when it is `nil` or has since been destroyed —
  routes the nil/dead case to the walker's frameless arm, which redacts the
  whole value to `:rf/redacted` (rf2-7htk7). The key is forwarded VERBATIM:
  `elide-wire-value` reads its `:frame` opt by PRESENCE, so an explicit nil
  is believed. This ns once minted a host object to stand in for that nil,
  because the walker read nil as absence; the request is now sayable and the
  sentinel is gone (rf2-kuky.5). The contract — no live caller today, and
  the shape any future panel affordance MUST take:

      (egress/egress-value v {:frame observed})   ; nil / dead ⇒ :rf/redacted

  Passing NO `:frame` key at all is still the right call where the caller has
  already established the frame itself — the palette snapshot resolves and
  validates the focused frame before it reads the db, and wraps the call in
  `(rf/with-frame tf …)`."
  ([value]
   (egress-value value nil))
  ([value {:keys [include-sensitive? include-large? path] :as opts
           :or   {include-sensitive? false
                  include-large?     false}}]
   (rf/elide-wire-value value
                        (cond-> {:rf.size/include-sensitive? include-sensitive?
                                 :rf.size/include-large?     include-large?}
                          (seq path)              (assoc :path (vec path))
                          ;; An explicitly-passed `:frame` is forwarded
                          ;; VERBATIM, nil included: the walker reads the opt
                          ;; by key presence, so an explicit nil means "no
                          ;; governing frame" and takes its fail-closed arm
                          ;; instead of falling through to the ambient frame.
                          (contains? opts :frame) (assoc :frame (:frame opts))))))
