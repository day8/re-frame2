(ns day8.re-frame2-xray.panels.module-view-helpers
  "Pure-data helpers for Xray's Module-view tab — the EP-0013 disposition-6
  demand-trigger surface (rf2-wtg9z4).

  ## What this view shows

  EP-0013 introduces *app values* (immutable descriptions of a program,
  composed from feature *modules*) and *runtime realms* (the containers a
  program is installed into). The Module-view is the disposition-6 NAMED
  DEMAND TRIGGER: the view that would inspect, per module, the three
  public descriptor facts EP-0013 disposition 6 rules —

    - **ownership** (`:owns`) — the app-db paths / routes / resources a
      module declares it owns;
    - **capability requirements** (`:requires`) — the `:rf.capability/*`
      a module's install demands;
    - **EP-0015 classification metadata** — the durable data
      classification a module's surfaces carry;

  plus, as the trigger itself, **descriptor provenance** (which module
  owns a handler / sub / path; source coordinates).

  ## The provenance graduation (why this slice is a SCAFFOLD)

  EP-0013 keeps descriptor PROVENANCE / module metadata INTERNAL until a
  Module-view demands it — and this is that view. But the graduation is a
  CORE slice, not a tooling slice: the per-module facts above live on a
  CONSTRUCTED app value's `:modules` map (`rf/app` / `rf/module`), and a
  RUNNING realm exposes no public read of its installed app value.

    - `re-frame.realm/installed-app` is INTERNAL (no `rf/*` re-export);
    - even the internal `app-value` PROJECTION over a realm's registrar
      carries NO `:modules`, NO `:owns`, NO `:requires` (load-order
      registrations have no module — those facts exist only on a
      constructed app value);
    - the public inspectors `rf/app-registrations` / `rf/app-owns` /
      `rf/app-requires` operate on an app value you ALREADY HOLD — there
      is no public seam to obtain a running realm's app value to feed
      them.

  So the per-module provenance / ownership / capability / classification
  inspection the bead names CANNOT be read from a running process today.
  Per the rf2-wtg9z4 brief, the view does NOT silently expand core scope
  to invent the seam — a follow-up bead is filed for the public
  realm→installed-app provenance read surface, and THIS slice ships the
  parts the genuinely-public seams support:

    - the **realm / frame address space** — `rf/realm-ids` (the installed
      realms) × `rf/frame-realm` (each frame's realm), the (realm, frame)
      address EP-0013 disposition 3 documents;
    - per realm, its **frames** and a per-realm capability surface WHEN a
      constructed app value is available to the tooling (it is not, in a
      pure load-order app — so the module section reads a calm
      awaiting-core-seam caption rather than fabricating data).

  When the core provenance seam graduates (the follow-up bead), the
  module section fills in from it with no reshape here — the row shapes
  below already carry the `:owns` / `:requires` / `:classification` /
  `:provenance` slots, defaulted to nil/empty until the seam supplies
  them.

  ## Zero-ceremony (single-realm unchanged)

  In a single-realm process `rf/realm-ids` returns exactly
  `#{:rf.realm/default}`. The view still renders (a Module-view is a
  browse surface, not an event-coupled lens), but it surfaces ONE realm
  with no realm-grouping ceremony — the (realm) dimension is implicit,
  exactly as the single-frame app never spells a frame. `multi-realm?`
  drives whether the realm dimension is foregrounded.

  ## Why a separate `.cljc` ns

  Mirrors every other Xray panel: the panel view paints + dispatches; the
  pure data → data projection (realm address-space assembly, the
  module-row shape, the single/multi-realm classification) lives here so
  it runs under the JVM unit-test target (`clojure -M:test`)."
  (:require [clojure.string :as str]))

;; ---- realm / frame address space (EP-0013 disposition 3) ----------------

(defn realm-frames
  "Group `frame-ids` by the realm each belongs to, using `realm-of` (a
  `frame-id → realm-id` resolver, normally `rf/frame-realm`). Returns
  `{realm-id #{frame-id …}}`. A frame whose `realm-of` resolves to nil
  falls into the `:rf.realm/default` bucket (absence = default realm, the
  EP-0013 D1 rule). Pure data → data; JVM-testable."
  [frame-ids realm-of]
  (reduce
    (fn [acc fid]
      (let [rid (or (realm-of fid) :rf.realm/default)]
        (update acc rid (fnil conj #{}) fid)))
    {}
    frame-ids))

(defn project-realm-row
  "Project one realm into the Module-view's realm-row shape:

      {:realm          <realm-id>
       :frames         [<frame-id> …]   ;; sorted for stable render
       :frame-count    <int>
       ;; provenance slots — AWAITING the core realm→installed-app seam
       ;; (rf2-wtg9z4 follow-up rf2-imquoq). nil/empty until it graduates; the row
       ;; shape already carries them so the fill-in is a no-reshape change.
       :modules        nil
       :requires       #{}
       :owns           nil
       :classification nil}

  `realm-id` is the realm; `frames` is the set of frame-ids in that realm
  (from `realm-frames`). Pure data → data; JVM-testable."
  [realm-id frames]
  {:realm          realm-id
   :frames         (vec (sort-by str frames))
   :frame-count    (count frames)
   :modules        nil
   :requires       #{}
   :owns           nil
   :classification nil})

(defn project-module-view
  "Top-level projection — produce every slot the Module-view needs from
  the (realm, frame) address space (EP-0013 disposition 3). Pure data →
  data; JVM-testable.

  `realm-ids` is the set of installed realm ids (`rf/realm-ids`);
  `frame-ids` is the set/seq of live frame ids (`rf/frame-ids`);
  `realm-of` resolves a frame's realm (`rf/frame-realm`).

  Returns:

      {:realms        [{:realm … :frames … :frame-count … …} …]  ;; per realm,
                                     ;; sorted by realm-id for stable render
       :realm-count   <int>
       :multi-realm?  <bool>        ;; >1 realm → the realm dimension is
                                    ;; foregrounded; single-realm renders
                                    ;; with the (realm) dimension implicit
       :provenance-available? false ;; rf2-wtg9z4 — the per-module
                                    ;; provenance seam has not graduated
                                    ;; (no public realm→installed-app read);
                                    ;; the view shows the awaiting-seam
                                    ;; caption while this is false}

  Every installed realm appears even when it holds no frames (a realm can
  exist with zero frames). Pure data → data."
  [realm-ids frame-ids realm-of]
  (let [by-realm (realm-frames frame-ids realm-of)
        ;; Every installed realm, plus any realm a frame resolves into
        ;; that somehow isn't in realm-ids (defensive — the two should
        ;; agree, but a stale frame must never strand the view).
        all-rids (into (set realm-ids) (keys by-realm))
        realms   (->> all-rids
                      (sort-by str)
                      (mapv (fn [rid]
                              (project-realm-row rid (get by-realm rid #{})))))]
    {:realms                realms
     :realm-count           (count realms)
     :multi-realm?          (> (count realms) 1)
     ;; rf2-wtg9z4 — flipped to true by the follow-up bead that ships the
     ;; public realm→installed-app provenance read seam.
     :provenance-available? false}))

;; ---- awaiting-seam caption (rf2-wtg9z4) ---------------------------------

(def awaiting-provenance-caption
  "The calm caption the module section renders while the per-module
  provenance seam has not graduated (rf2-wtg9z4 follow-up rf2-imquoq). Names the
  facts the view WILL surface so the operator understands the surface is
  scaffolded, not broken. Pure data → string."
  (str "Per-module ownership, capability requirements, classification, "
       "and descriptor provenance are not yet readable from a running "
       "process — they await a public realm→installed-app read seam "
       "(EP-0013 disposition 6 graduation)."))

(defn realm-summary-line
  "A one-line plain-language summary for a realm row — `<realm-id> · N
  frame(s)`. Pure data → string; JVM-testable."
  [{:keys [realm frame-count] :as _realm-row}]
  (str realm " · " frame-count " frame" (when (not= 1 frame-count) "s")))
