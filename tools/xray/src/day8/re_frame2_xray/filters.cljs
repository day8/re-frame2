(ns day8.re-frame2-xray.filters
  "Facade for Xray's IN/OUT auto-filter subsystem.

  Per the canonical Xray panel-facade pattern (mirrored from
  `palette.cljs`): the facade owns the `reg-view` Modal wrapper for
  the edit popup + an `install!` fn that wires every filter-side
  registration through the Xray registry.

  ## What lives here

  - `Modal` — `reg-view` mounting the edit popup. Mounted at the
    shell-view root so the popup overlays the chrome and panels;
    short-circuits to nil when `:rf.xray/edit-popup-open?` is false.
    Mounting at the shell root also keeps the popup's subscribes
    inside the `:rf/xray` frame-provider's React context.
  - `install!` — orchestrator that installs the subs / events / fxs
    + the persistence fx + the load-time hydration.

  ## What does NOT live here

  - The ribbon pill cluster is in `filters/pills.cljs`; the shell's
    `ribbon-filter-pills` reg-view in `shell.cljs` delegates to it
    so the cluster carries the ribbon's frame-context.
  - Pattern matching is in `filters/matcher.cljc` (JVM-portable).
  - localStorage round-trip is in `filters/persistence.cljs`."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.filters.edit-popup :as edit-popup]
            [day8.re-frame2-xray.filters.hidden :as hidden]
            [day8.re-frame2-xray.filters.matcher :as matcher]
            [day8.re-frame2-xray.filters.persistence :as persistence]
            [day8.re-frame2-xray.filters.typed-predicates :as typed]
            [day8.re-frame2-xray.spine-filters :as spine-filters]))

;; ---- L2 visible-row predicate -------------------------------------------
;;
;; Mirrors `shell/l2-event-bundle-visible?` — kept local so the
;; `:rf.xray/hidden-by-filters` sub below can count over the SAME
;; visible-row set the L2 list renders without a shell ↔ filters require
;; cycle (the shell already requires this ns transitively). The
;; `:ungrouped` bucket carries `:dispatch-id :ungrouped`; it only renders
;; in L2 when the power-user opt-in is on, so it is excluded
;; from the hidden-count's visible set by default — keeping N consistent
;; with the rows the user sees.

(defn- l2-visible?
  [event-bundle show-ungrouped?]
  (if (= :ungrouped (:dispatch-id event-bundle))
    (boolean show-ungrouped?)
    true))

;; ---- Modal --------------------------------------------------------------

(rf/reg-view Modal
  "The edit popup. Renders only when `:rf.xray/edit-popup-open?` is
  true; closed-state is a single subscribe + a `when`.
  `reg-view`-registered so the body's subscribes route through the
  React-context tier to `:rf/xray`.

  Threads the reg-view-injected frame-aware `dispatch`
  into `popup-view` so deferred `:on-*` handlers land on the
  surrounding instance frame, not a `{:frame :rf/xray}` literal."
  []
  (when @(rf/subscribe [:rf.xray/edit-popup-open?])
    (edit-popup/popup-view dispatch)))

;; ---- hydration ---------------------------------------------------------

(defn hydrate!
  "Drive the localStorage / seed / empty hydration order per spec/018
  §7:

    1. localStorage value (the user's last-session pill set);
    2. host-supplied seed via `(xray-config/configure! {:rf.xray/filters …})`
       — used only when localStorage is empty so a seed never clobbers
       a user's hand-tuned set;
    3. registry default empty shape `{:in [] :out []}` per
       'Empty defaults' (first-session honesty).

  Re-entrant. Safe to call from `install!` (preload-time, before the
  frame is registered) AND from `mount.cljs/ensure-xray-frame!`
  (first open, frame registered). Both invocations converge on the
  same slot because:

  - the load + seed reads are pure;
  - the hydrate dispatch is a wholesale `(assoc db :active-filters …)`
    so re-running with the same source produces the same slot;
  - the frame guard short-circuits the pre-mount call without losing
    state — the seed lives in the config atom, the localStorage value
    lives in localStorage; both are still readable at second call.

  Returns nil. No-op when no source has any pills."
  []
  (let [loaded (persistence/load)
        seed   (config/get-filter-seed)
        chosen (cond
                 (or (seq (:in loaded)) (seq (:out loaded))) loaded
                 (and seed (or (seq (:in seed)) (seq (:out seed)))) seed
                 :else nil)]
    (when (and chosen
               ;; require the :rf/xray frame to exist; if not, the
               ;; first `ensure-xray-frame!` call will re-invoke
               ;; this fn.
               (some? (frame/frame :rf/xray)))
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/hydrate-filters chosen]))
      nil)))

;; ---- install -----------------------------------------------------------

(defn- close-popup
  "Pure reducer — reset the popup slots."
  [db]
  (-> db
      (assoc :edit-popup-open? false)
      (assoc :edit-popup-trigger nil)
      (assoc :edit-popup-draft nil)))

(defn- append-typed-pill
  "Append a typed pill to `mode`'s bucket; no-op when an equivalent
  pill already exists. Used by the `:rf.xray/filter-by-*` typed-add
  events — the right-click affordances on the Machines /
  managed-fx panels dispatch through this so a double-add collapses
  to one pill rather than piling duplicates."
  [db mode pill]
  (let [bucket   (get-in db [:active-filters mode] [])
        present? (some #(= pill %) bucket)]
    (if present?
      db
      (update-in db [:active-filters mode] (fnil conj []) pill))))

(defn install!
  "Idempotent install for the filter subsystem's Xray-side surface.
  Wires:

    - Subs:   `:rf.xray/active-filters` (already in registry; we read
              + extend it here too), `:rf.xray/filtered-event-bundles`,
              `:rf.xray/edit-popup-open?`, `:rf.xray/edit-popup-
              trigger`, `:rf.xray/edit-popup-draft`.

    - Events: `:rf.xray/open-edit-popup`, `:rf.xray/close-edit-popup`,
              `:rf.xray/edit-popup-set-mode`,
              `:rf.xray/edit-popup-set-pattern`,
              `:rf.xray/save-edit-popup`,
              `:rf.xray/delete-edit-popup`,
              `:rf.xray/hide-event-type`,
              `:rf.xray/hydrate-filters`,
              + typed-add events:
              `:rf.xray/filter-by-machine`,
              `:rf.xray/filter-by-http-correlation`,
              `:rf.xray/filter-by-fx`.

    - Effects: `:rf.xray.filters/persist` — localStorage write fx.

    - Side-effect: hydrate `:active-filters` from localStorage at
      first install via a no-history dispatch.

  Called from `registry/register-xray-handlers!` after the
  `:rf.xray/active-filters` slot is registered (so the sub-graph
  resolves in declaration order)."
  []
  ;; ---- fx ---------------------------------------------------------------
  (persistence/install-fx!)

  ;; ---- subs -------------------------------------------------------------
  ;;
  ;; Spec/018 §6 sub-graph: the matter-of-fact path is `:rf.xray/
  ;; event-bundles → :rf.xray/filtered-event-bundles`. Every consumer of the
  ;; event-bundle list (event-list, scrubber, palette, Issues counter)
  ;; reads `:rf.xray/filtered-event-bundles`; raw `:rf.xray/event-bundles`
  ;; stays available for unfiltered totals.
  ;;
  ;; Per spec/018 §3 Frame dropdown: the frame picker is
  ;; ALSO a data-layer filter. The picker's selection lives on the
  ;; spine's `:focus :frame` slot (written by `:rf.xray/set-frame`);
  ;; we read the raw slot here (not the composed `:rf.xray/focus` sub)
  ;; to avoid the cycle composed-focus → event-bundles → filtered-event-bundles
  ;; → composed-focus. nil slot = no frame filter active (multi-frame
  ;; app, picker has not been touched, OR a single-frame app whose
  ;; picker collapses to a flat label).
  ;; Pill matching routes through the typed-predicate dispatcher
  ;; so each pill's `:kind` selects the per-kind event-bundle
  ;; matcher (`:event-id-pattern` delegates to the event-id
  ;; matcher; `:machine` / `:http-correlation` / `:fx` walk the
  ;; event-bundle's trace-events for the matching tag). Pills
  ;; persisted under `{:pattern <kw-or-str>}` hydrate as
  ;; `:event-id-pattern` via `canonicalise-pill`.
  ;; The muted-event-ids set rides at the END of the
  ;; filter chain so right-click → 'Mute :event-id' strips the row
  ;; from L2 regardless of any IN-pill state. (An IN pill says 'keep
  ;; only these'; mute says 'never these'. Composition is OUT-like
  ;; semantically, but the mute set is a separate slot so it persists
  ;; independently of the pill set and the unmute manager has a clean
  ;; surface to enumerate.)
  ;; The frame scope reads the dedicated
  ;; `:rf.xray/view-scope-frame` slot (a single defaulted VIEW SCOPE),
  ;; NOT the spine's `[:focus :frame]` (which epoch auto-alignment + the
  ;; focus-step walk also write — reading it there would silently re-scope
  ;; the list to whatever epoch the user stepped onto). The frame is applied
  ;; as a view scope FIRST, then the pill chain + mutes (the actual
  ;; filters) compose on top.
  ;;
  ;; The frameless `:ungrouped` pseudo-event-bundle is frame-agnostic: it is
  ;; preserved through the view scope ONLY when the `show-ungrouped?`
  ;; opt-in is on (so it can be REVEALED under a scope); with the opt-in
  ;; off the strict frame filter drops it, matching the default
  ;; silent-by-default L2 list (no frameless bucket leaks into the data
  ;; layer when nobody asked for it).
  (rf/reg-sub :rf.xray/filtered-event-bundles
    :<- [:rf.xray/event-bundles]
    :<- [:rf.xray/active-filters]
    :<- [:rf.xray/view-scope-frame]
    :<- [:rf.xray/muted-event-ids]
    :<- [:rf.xray/show-ungrouped?]
    (fn [[event-bundles filters view-scope-frame muted show-ungrouped?] _query]
      (-> event-bundles
          (cond->
            show-ungrouped?       (matcher/filter-event-bundles-by-view-scope view-scope-frame)
            (not show-ungrouped?) (matcher/filter-event-bundles-by-frame view-scope-frame))
          (typed/filter-event-bundles filters)
          (spine-filters/filter-event-bundles muted))))

  ;; The 'N events hidden by filters' message model. Composes the raw +
  ;; filtered event-bundle lists and the active filter state into the pure
  ;; `hidden/summary` record the events ribbon renders against. Counts
  ;; over the L2 visible-row set (`l2-visible?`) so N matches the rows
  ;; the user sees.
  ;;
  ;; FRAME IS A VIEW SCOPE, NOT A FILTER: the count BASELINE is computed
  ;; WITHIN the selected frame — `raw` is first scoped to the view-scope
  ;; frame, then pills/mutes are measured against that scope. This means
  ;; switching frames NEVER inflates "hidden" — each frame's events count
  ;; only against their own frame's baseline, so picking a frame never
  ;; makes another frame's events look "hidden by filters". The summary
  ;; carries no `:frame` cause; only pill/mute suppression is the cause
  ;; and the count.
  (rf/reg-sub :rf.xray/hidden-by-filters
    :<- [:rf.xray/event-bundles]
    :<- [:rf.xray/filtered-event-bundles]
    :<- [:rf.xray/active-filters]
    :<- [:rf.xray/view-scope-frame]
    :<- [:rf.xray/muted-event-ids]
    :<- [:rf.xray/show-ungrouped?]
    (fn [[raw filtered filters view-scope-frame muted show-ungrouped?] _query]
      (let [;; Scope the raw baseline to the VIEW-SCOPE frame so frame
            ;; selection is a view scope, not counted as suppression.
            frame-scoped (matcher/filter-event-bundles-by-view-scope raw view-scope-frame)
            raw-n        (count (filterv #(l2-visible? % show-ungrouped?) frame-scoped))
            filtered-n   (count (filterv #(l2-visible? % show-ungrouped?) filtered))]
        (hidden/summary raw-n filtered-n
                        {:filters filters
                         :muted   muted}))))

  ;; Popup state — three slots so the open / trigger / draft tiers
  ;; are individually subscribable.
  (rf/reg-sub :rf.xray/edit-popup-open?
    (fn [db _query]
      (boolean (get db :edit-popup-open?))))

  (rf/reg-sub :rf.xray/edit-popup-trigger
    (fn [db _query]
      (get db :edit-popup-trigger)))

  (rf/reg-sub :rf.xray/edit-popup-draft
    (fn [db _query]
      (get db :edit-popup-draft)))

  ;; ---- events: popup open / close / mutate -----------------------------
  ;;
  ;; The trigger payload tells the popup whether it's editing an
  ;; existing pill (`:source :pill`), adding from the trailing `+`
  ;; (`:source :add`), or adding from a right-click context menu
  ;; (`:source :context`). The draft is initialised from the trigger
  ;; so 'click pill → edit' arrives pre-populated and 'right-click
  ;; row → add' arrives with the event-id pre-filled.

  (rf/reg-event :rf.xray/open-edit-popup
    (fn [{:keys [db]} [_ {:keys [source mode idx pill] :as trigger}]]
      {:db (let [draft (-> (edit-popup/pill->draft (or pill {}))
                      (assoc :mode (or mode :in)))]
        (-> db
            (assoc :edit-popup-open? true)
            (assoc :edit-popup-trigger
                   {:source source
                    :mode   (or mode :in)
                    :idx    idx
                    :pill   pill})
            (assoc :edit-popup-draft draft)))}))

  (rf/reg-event :rf.xray/close-edit-popup
    (fn [{:keys [db]} _event]
      {:db (close-popup db)}))

  (rf/reg-event :rf.xray/edit-popup-set-mode
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ mode]]
      {:db (assoc-in db [:edit-popup-draft :mode] mode)}))

  (rf/reg-event :rf.xray/edit-popup-set-pattern
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ pattern]]
      {:db (assoc-in db [:edit-popup-draft :pattern] (or pattern ""))}))

  ;; ---- events: save / delete -------------------------------------------
  ;;
  ;; Save and Delete both mutate the live `:active-filters` slot and
  ;; close the popup. Persistence is bound by the `:rf.xray.filters/
  ;; persist` fx so the post-mutation slot lands in localStorage in
  ;; one place (no fx-per-handler duplication).

  (rf/reg-event :rf.xray/save-edit-popup
    (fn [{:keys [db]} _event]
      (let [draft  (get db :edit-popup-draft)
            trig   (get db :edit-popup-trigger)
            mode   (or (:mode draft) :in)
            pill   (edit-popup/draft->pill draft)
            valid? (some? (:pattern pill))]
        (if (not valid?)
          ;; Pattern empty — keep the popup open so the user can fix
          ;; the input. The Apply button is also disabled in this
          ;; state, but a programmatic dispatch (test path) lands here.
          {:db db}
          (let [editing-existing? (= :pill (:source trig))
                old-mode          (:mode trig)
                ;; If the user flipped IN ↔ OUT we delete from the old
                ;; bucket and append to the new; if they edited a pill
                ;; in place we replace at the same idx.
                same-mode?        (= mode old-mode)
                idx               (:idx trig)
                without-old
                (if (and editing-existing? (some? idx))
                  (update-in db [:active-filters old-mode]
                             (fn [v]
                               (let [v (or v [])]
                                 (vec (concat (subvec v 0 (min idx (count v)))
                                              (subvec v (min (inc idx) (count v))))))))
                  db)
                next-db
                (if (and editing-existing? same-mode? (some? idx))
                  ;; Replace at the original index so pill order is
                  ;; preserved across an edit.
                  (update-in db [:active-filters mode]
                             (fn [v]
                               (let [v (or v [])]
                                 (vec (concat (subvec v 0 (min idx (count v)))
                                              [pill]
                                              (subvec v (min (inc idx) (count v))))))))
                  ;; New pill or mode-flip — append to the target
                  ;; bucket of the without-old base.
                  (update-in without-old [:active-filters mode]
                             (fnil conj []) pill))
                final-db (close-popup next-db)]
            {:db final-db
             :fx [[:rf.xray.filters/persist (get final-db :active-filters)]]})))))

  (rf/reg-event :rf.xray/delete-edit-popup
    (fn [{:keys [db]} _event]
      (let [trig (get db :edit-popup-trigger)
            mode (:mode trig)
            idx  (:idx trig)]
        (if (and (= :pill (:source trig)) (some? idx) (some? mode))
          (let [next-db (-> db
                            (update-in [:active-filters mode]
                                       (fn [v]
                                         (let [v (or v [])]
                                           (vec (concat (subvec v 0 (min idx (count v)))
                                                        (subvec v (min (inc idx) (count v))))))))
                            (close-popup))]
            {:db next-db
             :fx [[:rf.xray.filters/persist (get next-db :active-filters)]]})
          {:db (close-popup db)}))))

  ;; ---- clear-all ------------------------------------------------------
  ;;
  ;; One-click reset behind the events ribbon's `Clear Filters` button.
  ;; Resets every suppressing FILTER so the filtered list snaps back to
  ;; the (frame-scoped) raw list:
  ;;
  ;;   1. IN/OUT pills        → `{:in [] :out []}` (+ persist)
  ;;   2. muted event-ids     → `:rf.xray/clear-muted-event-ids` (+ its
  ;;      own persist fx)
  ;;
  ;; The FRAME is a view SCOPE, not a filter — Clear Filters must NOT
  ;; change the frame; the picker's selection survives a Clear Filters.
  ;;
  ;; Pills are reset inline (this handler already owns the
  ;; `:active-filters` slot + persist fx); the mute reset routes through
  ;; its owning event via `:dispatch` so its persistence /
  ;; instrumentation stays in one place.
  (rf/reg-event :rf.xray/clear-all-filters
    (fn [{:keys [db]} _event]
      (let [cleared {:in [] :out []}]
        {:db (assoc db :active-filters cleared)
         :fx [[:rf.xray.filters/persist cleared]
              [:dispatch [:rf.xray/clear-muted-event-ids]]]})))

  ;; ---- right-click row → OUT filter shortcut --------------------------
  ;;
  ;; Spec/018 §7 Right-click event-row → context menu carries an
  ;; 'Always hide this event-type' item; this is the canonical
  ;; lowering. The event-row's on-context-menu handler dispatches
  ;; this event; the popup opens pre-populated (so the user can fine-
  ;; tune or simply hit Apply). Pre-alpha we open the popup rather
  ;; than silently appending — the user sees what's about to land in
  ;; the OUT bucket and can cancel.

  (rf/reg-event :rf.xray/hide-event-type
    (fn [{:keys [db]} [_ event-id]]
      {:db (let [pill {:pattern (when event-id (str event-id))}]
        (-> db
            (assoc :edit-popup-open? true)
            (assoc :edit-popup-trigger
                   {:source :context :mode :out :pill pill})
            (assoc :edit-popup-draft
                   (-> (edit-popup/pill->draft pill)
                       (assoc :mode :out)))))}))

  ;; ---- typed-predicate add events -------------------------------------
  ;;
  ;; Right-click affordances on the Machines / managed-fx panels fire
  ;; these events to append a typed-predicate IN pill directly — no
  ;; popup round-trip because the pattern is fully determined by the
  ;; clicked row (machine-id / correlation-id / fx-id). The user can
  ;; remove the pill via the standard `×` button on the pill cluster.
  ;;
  ;; Each event idempotently appends via the file-level
  ;; `append-typed-pill` helper — a duplicate add (same params)
  ;; collapses to a no-op so multiple right-click → add chains don't
  ;; pile up redundant pills.

  (rf/reg-event :rf.xray/filter-by-machine
    (fn [{:keys [db]} [_ machine-id]]
      (let [pill    {:kind :machine :params {:machine-id machine-id}}
            next-db (append-typed-pill db :in pill)]
        {:db next-db
         :fx [[:rf.xray.filters/persist (get next-db :active-filters)]]})))

  (rf/reg-event :rf.xray/filter-by-http-correlation
    (fn [{:keys [db]} [_ correlation-id]]
      (let [pill    {:kind :http-correlation
                     :params {:correlation-id correlation-id}}
            next-db (append-typed-pill db :in pill)]
        {:db next-db
         :fx [[:rf.xray.filters/persist (get next-db :active-filters)]]})))

  (rf/reg-event :rf.xray/filter-by-fx
    (fn [{:keys [db]} [_ fx-id]]
      (let [pill    {:kind :fx :params {:fx-id fx-id}}
            next-db (append-typed-pill db :in pill)]
        {:db next-db
         :fx [[:rf.xray.filters/persist (get next-db :active-filters)]]})))

  ;; ---- load: hydrate from localStorage --------------------------------

  (rf/reg-event :rf.xray/hydrate-filters
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ filters]]
      {:db (assoc db :active-filters
                (or filters {:in [] :out []}))}))

  ;; NO hydrate on install. The IN/OUT pills are a TRANSIENT
  ;; exploration filter — they reset to unfiltered on every page load so
  ;; a fresh session never silently carries a stale filter. The slot
  ;; starts at its registry default `{:in [] :out []}` and
  ;; `mount.cljs`'s `::reset-transient-filters` first-mount hook clears
  ;; the stale localStorage slot so storage matches. `hydrate!` / `load`
  ;; remain as the data layer (exercised by the persistence round-trip
  ;; tests + reachable by hosts that opt back in), but init does not
  ;; restore.
  nil)
