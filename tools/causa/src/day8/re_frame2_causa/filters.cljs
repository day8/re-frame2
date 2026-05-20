(ns day8.re-frame2-causa.filters
  "Facade for Causa's IN/OUT auto-filter subsystem (rf2-ak4ms).

  Per the canonical Causa panel-facade pattern (mirrored from
  `palette.cljs`): the facade owns the `reg-view` Modal wrapper for
  the edit popup + an `install!` fn that wires every filter-side
  registration through the Causa registry.

  ## What lives here

  - `Modal` — `reg-view` mounting the edit popup. Mounted at the
    shell-view root so the popup overlays the chrome and panels;
    short-circuits to nil when `:rf.causa/edit-popup-open?` is false.
    Mounting at the shell root also keeps the popup's subscribes
    inside the `:rf/causa` frame-provider's React context.
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
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.filters.edit-popup :as edit-popup]
            [day8.re-frame2-causa.filters.matcher :as matcher]
            [day8.re-frame2-causa.filters.persistence :as persistence]
            [day8.re-frame2-causa.filters.typed-predicates :as typed]
            [day8.re-frame2-causa.spine-filters :as spine-filters]))

;; ---- Modal --------------------------------------------------------------

(rf/reg-view Modal
  "The edit popup. Renders only when `:rf.causa/edit-popup-open?` is
  true; closed-state is a single subscribe + a `when`. Per rf2-in6l2
  `reg-view`-registered so the body's subscribes route through the
  React-context tier to `:rf/causa`."
  []
  (when @(rf/subscribe [:rf.causa/edit-popup-open?])
    (edit-popup/popup-view)))

;; ---- hydration ---------------------------------------------------------

(defn hydrate!
  "Drive the localStorage / seed / empty hydration order per spec/018
  §7 + rf2-ak4ms:

    1. localStorage value (the user's last-session pill set);
    2. host-supplied seed via `(causa-config/configure! {:rf.causa/filters …})`
       — used only when localStorage is empty so a seed never clobbers
       a user's hand-tuned set;
    3. registry default empty shape `{:in [] :out []}` per
       'Empty defaults' (first-session honesty).

  Re-entrant. Safe to call from `install!` (preload-time, before the
  frame is registered) AND from `mount.cljs/ensure-causa-frame!`
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
               ;; require the :rf/causa frame to exist; if not, the
               ;; first `ensure-causa-frame!` call will re-invoke
               ;; this fn.
               (some? (frame/frame :rf/causa)))
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/hydrate-filters chosen]))
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
  pill already exists. Used by the `:rf.causa/filter-by-*` typed-add
  events (rf2-piye4) — the right-click affordances on the Machines /
  managed-fx panels dispatch through this so a double-add collapses
  to one pill rather than piling duplicates."
  [db mode pill]
  (let [bucket   (get-in db [:active-filters mode] [])
        present? (some #(= pill %) bucket)]
    (if present?
      db
      (update-in db [:active-filters mode] (fnil conj []) pill))))

(defn install!
  "Idempotent install for the filter subsystem's Causa-side surface.
  Wires:

    - Subs:   `:rf.causa/active-filters` (already in registry; we read
              + extend it here too), `:rf.causa/filtered-cascades`,
              `:rf.causa/edit-popup-open?`, `:rf.causa/edit-popup-
              trigger`, `:rf.causa/edit-popup-draft`.

    - Events: `:rf.causa/open-edit-popup`, `:rf.causa/close-edit-popup`,
              `:rf.causa/edit-popup-set-mode`,
              `:rf.causa/edit-popup-set-pattern`,
              `:rf.causa/edit-popup-toggle-scope`,
              `:rf.causa/save-edit-popup`,
              `:rf.causa/delete-edit-popup`,
              `:rf.causa/hide-event-type`,
              `:rf.causa/hydrate-filters`,
              + rf2-piye4 typed-add events:
              `:rf.causa/filter-by-machine`,
              `:rf.causa/filter-by-http-correlation`,
              `:rf.causa/filter-by-fx`.

    - Effects: `:rf.causa.filters/persist` — localStorage write fx.

    - Side-effect: hydrate `:active-filters` from localStorage at
      first install via a no-history dispatch.

  Called from `registry/register-causa-handlers!` after the legacy
  `:rf.causa/active-filters` slot is registered (so the sub-graph
  resolves in declaration order)."
  []
  ;; ---- fx ---------------------------------------------------------------
  (persistence/install-fx!)

  ;; ---- subs -------------------------------------------------------------
  ;;
  ;; Spec/018 §6 sub-graph: the matter-of-fact path is `:rf.causa/
  ;; cascades → :rf.causa/filtered-cascades`. Every consumer of the
  ;; cascade list (event-list, scrubber, palette, Issues counter)
  ;; reads `:rf.causa/filtered-cascades`; raw `:rf.causa/cascades`
  ;; stays available for unfiltered totals.
  ;;
  ;; Per spec/018 §3 Frame dropdown + rf2-oziyr: the frame picker is
  ;; ALSO a data-layer filter. The picker's selection lives on the
  ;; spine's `:focus :frame` slot (written by `:rf.causa/set-frame`);
  ;; we read the raw slot here (not the composed `:rf.causa/focus` sub)
  ;; to avoid the cycle composed-focus → cascades → filtered-cascades
  ;; → composed-focus. nil slot = no frame filter active (multi-frame
  ;; app, picker has not been touched, OR a single-frame app whose
  ;; picker collapses to a flat label).
  ;; Pill matching routes through the typed-predicate dispatcher
  ;; (rf2-piye4) so each pill's `:kind` selects the per-kind cascade
  ;; matcher (`:event-id-pattern` delegates to the existing event-id
  ;; matcher; `:machine` / `:http-correlation` / `:fx` walk the
  ;; cascade's trace-events for the matching tag). Pre-typed pills
  ;; persisted under `{:pattern <kw-or-str>}` hydrate as
  ;; `:event-id-pattern` via `canonicalise-pill` — no migration step
  ;; needed.
  ;; rf2-ikuwt — the muted-event-ids set rides at the END of the
  ;; filter chain so right-click → 'Mute :event-id' strips the row
  ;; from L2 regardless of any IN-pill state. (An IN pill says 'keep
  ;; only these'; mute says 'never these'. Composition is OUT-like
  ;; semantically, but the mute set is a separate slot so it persists
  ;; independently of the pill set and the unmute manager has a clean
  ;; surface to enumerate.)
  (rf/reg-sub :rf.causa/filtered-cascades
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/active-filters]
    :<- [:rf.causa/focus-slot]
    :<- [:rf.causa/muted-event-ids]
    (fn [[cascades filters focus-slot muted] _query]
      (-> cascades
          (matcher/filter-cascades-by-frame (:frame focus-slot))
          (typed/filter-cascades filters)
          (spine-filters/filter-cascades muted))))

  ;; Popup state — three slots so the open / trigger / draft tiers
  ;; are individually subscribable.
  (rf/reg-sub :rf.causa/edit-popup-open?
    (fn [db _query]
      (boolean (get db :edit-popup-open?))))

  (rf/reg-sub :rf.causa/edit-popup-trigger
    (fn [db _query]
      (get db :edit-popup-trigger)))

  (rf/reg-sub :rf.causa/edit-popup-draft
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

  (rf/reg-event-db :rf.causa/open-edit-popup
    (fn [db [_ {:keys [source mode idx pill] :as trigger}]]
      (let [draft (-> (edit-popup/pill->draft (or pill {}))
                      (assoc :mode (or mode :in)))]
        (-> db
            (assoc :edit-popup-open? true)
            (assoc :edit-popup-trigger
                   {:source source
                    :mode   (or mode :in)
                    :idx    idx
                    :pill   pill})
            (assoc :edit-popup-draft draft)))))

  (rf/reg-event-db :rf.causa/close-edit-popup
    (fn [db _event]
      (close-popup db)))

  (rf/reg-event-db :rf.causa/edit-popup-set-mode
    {:rf.trace/no-emit? true}
    (fn [db [_ mode]]
      (assoc-in db [:edit-popup-draft :mode] mode)))

  (rf/reg-event-db :rf.causa/edit-popup-set-pattern
    {:rf.trace/no-emit? true}
    (fn [db [_ pattern]]
      (assoc-in db [:edit-popup-draft :pattern] (or pattern ""))))

  (rf/reg-event-db :rf.causa/edit-popup-toggle-scope
    {:rf.trace/no-emit? true}
    (fn [db [_ scope-key]]
      (let [current (or (get-in db [:edit-popup-draft :scope]) #{:event-id})
            next    (if (contains? current scope-key)
                      (disj current scope-key)
                      (conj current scope-key))]
        (assoc-in db [:edit-popup-draft :scope] next))))

  ;; ---- events: save / delete -------------------------------------------
  ;;
  ;; Save and Delete both mutate the live `:active-filters` slot and
  ;; close the popup. Persistence is bound by the `:rf.causa.filters/
  ;; persist` fx so the post-mutation slot lands in localStorage in
  ;; one place (no fx-per-handler duplication).

  (rf/reg-event-fx :rf.causa/save-edit-popup
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
             :fx [[:rf.causa.filters/persist (get final-db :active-filters)]]})))))

  (rf/reg-event-fx :rf.causa/delete-edit-popup
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
             :fx [[:rf.causa.filters/persist (get next-db :active-filters)]]})
          {:db (close-popup db)}))))

  ;; ---- right-click row → OUT filter shortcut --------------------------
  ;;
  ;; Spec/018 §7 Right-click event-row → context menu carries an
  ;; 'Always hide this event-type' item; this is the canonical
  ;; lowering. The event-row's on-context-menu handler dispatches
  ;; this event; the popup opens pre-populated (so the user can fine-
  ;; tune or simply hit Apply). Pre-alpha we open the popup rather
  ;; than silently appending — the user sees what's about to land in
  ;; the OUT bucket and can cancel.

  (rf/reg-event-db :rf.causa/hide-event-type
    (fn [db [_ event-id]]
      (let [pill {:pattern (when event-id (str event-id))
                  :scope   #{:event-id}}]
        (-> db
            (assoc :edit-popup-open? true)
            (assoc :edit-popup-trigger
                   {:source :context :mode :out :pill pill})
            (assoc :edit-popup-draft
                   (-> (edit-popup/pill->draft pill)
                       (assoc :mode :out)))))))

  ;; ---- typed-predicate add events (rf2-piye4) -------------------------
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

  (rf/reg-event-fx :rf.causa/filter-by-machine
    (fn [{:keys [db]} [_ machine-id]]
      (let [pill    {:kind :machine :params {:machine-id machine-id}}
            next-db (append-typed-pill db :in pill)]
        {:db next-db
         :fx [[:rf.causa.filters/persist (get next-db :active-filters)]]})))

  (rf/reg-event-fx :rf.causa/filter-by-http-correlation
    (fn [{:keys [db]} [_ correlation-id]]
      (let [pill    {:kind :http-correlation
                     :params {:correlation-id correlation-id}}
            next-db (append-typed-pill db :in pill)]
        {:db next-db
         :fx [[:rf.causa.filters/persist (get next-db :active-filters)]]})))

  (rf/reg-event-fx :rf.causa/filter-by-fx
    (fn [{:keys [db]} [_ fx-id]]
      (let [pill    {:kind :fx :params {:fx-id fx-id}}
            next-db (append-typed-pill db :in pill)]
        {:db next-db
         :fx [[:rf.causa.filters/persist (get next-db :active-filters)]]})))

  ;; ---- load: hydrate from localStorage --------------------------------

  (rf/reg-event-db :rf.causa/hydrate-filters
    {:rf.trace/no-emit? true}
    (fn [db [_ filters]]
      (assoc db :active-filters
                (or filters {:in [] :out []}))))

  ;; Hydrate on install. The actual logic lives in `hydrate!` below
  ;; so `ensure-causa-frame!` (in mount.cljs) can call it again on
  ;; first open — the production order is preload-time install
  ;; (`:rf/causa` frame not yet registered) then keypress-time
  ;; `ensure-causa-frame!` (frame registered). Either call path
  ;; converges on the same slot.
  (hydrate!)
  nil)
