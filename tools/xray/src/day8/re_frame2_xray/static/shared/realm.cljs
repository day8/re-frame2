(ns day8.re-frame2-xray.static.shared.realm
  "Realm dimension for Xray's static-registry browse + handler-resolution
  (rf2-dfaey7, a15n62 / EP-0013).

  ## Why this exists

  Post-a15n62 the registrar is realm-aware: a realm owns the `(kind, id) →
  metadata` table it dispatches / subscribes / resolves against (EP-0013
  D1). The default-realm `(rf/registrations :kind)` call the static browse
  panels (schemas / flows / interceptors / routes / machines) make reads
  ONLY the default realm's table — so xray could not show WHICH realm owns a
  registration, nor flag a cross-realm id conflict (the same id registered
  in two realms). EP-0013 stage 8 added the map-shaped
  `(rf/registrations {:realm r :kind k})` query form + `rf/realm-ids` (the
  installed-realm enumeration) that this helper composes into the static
  surfaces.

  ## Absence is the default realm — single-realm renders unchanged

  The OVERWHELMING common case is a single-realm app: `(rf/realm-ids)`
  returns exactly `#{:rf.realm/default}`. In that case:

    - `multi-realm?` is false;
    - `realm-qualified-registrations` returns one `[default-realm-id
      registrations-map]` pair — the SAME map `(rf/registrations :kind)`
      already returned;
    - `realm-badge` / `realm-of` render NOTHING (no realm column appears).

  So a single-realm browse is byte-identical to the pre-rf2-dfaey7 surface;
  the realm dimension only MATERIALISES when a second realm is installed
  (multi-tenant / parallel-app / hermetic-test hosts). This is exactly the
  multi-realm-demand-gated posture the bead calls for.

  ## What it surfaces

    - `realm-of` — the owning `:rf.realm/id` of a registration row (nil ⇒
      the default realm; rendered as nothing in single-realm browse).
    - `cross-realm-ids` — the set of ids that appear in MORE THAN ONE realm
      under a kind (the id-conflict signal a multi-realm author wants).
    - `realm-badge` — a small chip rendering a row's owning realm, shown
      ONLY when the browse spans more than one realm.

  Pure-read + fail-soft: a browse catalogue must never throw on a runtime
  that can't answer (an older core without `realm-ids` / the map-shaped
  query form), so every registry touch is `try`-guarded and degrades to the
  default-realm path."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- realm enumeration ---------------------------------------------------

(def default-realm-id
  "The process default realm's id (Spec Runtime-Subsystems §realm). A
  registration carrying this realm (or nil) is the default realm — absence
  is the default, the documented a15n62 rule."
  :rf.realm/default)

(defn realm-ids
  "Return the set of installed realm ids via the public `(rf/realm-ids)`
  (EP-0013 stage 9, tier `:tooling`). Fail-soft: a core too old to expose
  it (or any throw) degrades to `#{default-realm-id}` so the browse always
  has at least the default realm. Always includes the default realm."
  []
  (try
    (let [ids (rf/realm-ids)]
      (if (and (set? ids) (seq ids))
        (conj ids default-realm-id)
        #{default-realm-id}))
    (catch :default _ #{default-realm-id})))

(defn multi-realm?
  "True when MORE THAN ONE realm is installed — the only condition under
  which the realm dimension materialises in a browse. Single-realm apps
  (the common case) read this false and render the pre-rf2-dfaey7 surface
  unchanged."
  []
  (> (count (realm-ids)) 1))

;; ---- realm-qualified registrations ---------------------------------------

(defn registrations-in-realm
  "Return `(rf/registrations {:realm realm-id :kind kind})` — the
  `{id metadata}` map registered under `kind` in `realm-id`'s OWN registrar
  (EP-0013 stage 8). Fail-soft: a core too old for the map-shaped form (or
  any throw) falls back to the default-realm `(rf/registrations kind)` for
  the default realm, and `{}` for any other realm (an old core has no other
  realm to read). Pure-read."
  [realm-id kind]
  (try
    (rf/registrations {:realm realm-id :kind kind})
    (catch :default _
      (if (= realm-id default-realm-id)
        (try (rf/registrations kind) (catch :default _ {}))
        {}))))

(defn realm-qualified-registrations
  "Return a vector of `[realm-id registrations-map]` pairs — one per
  installed realm — for `kind`. In the single-realm common case this is one
  pair, `[default-realm-id <the same map (rf/registrations kind) returned>]`,
  so callers that flatten it get the byte-identical default-realm browse.
  In a multi-realm app each realm contributes its OWN registrar's entries,
  so a tool can attribute every registration to its owning realm. Pure-read,
  fail-soft."
  [kind]
  (mapv (fn [realm-id] [realm-id (registrations-in-realm realm-id kind)])
        (sort (realm-ids))))

(defn realm-of
  "The owning realm-id to DISPLAY for a registration row, or nil when it is
  the default realm (absence is the default — nothing renders). Pure."
  [realm-id]
  (when (and (some? realm-id) (not= realm-id default-realm-id))
    realm-id))

(defn cross-realm-ids
  "Given `realm-qualified` (the `[realm-id reg-map]` pairs from
  `realm-qualified-registrations`), return the SET of ids that appear in
  MORE THAN ONE realm under this kind — the cross-realm id-conflict signal
  (EP-0013 §Realm Conformance: the same id may legally exist in two realms;
  a multi-realm author wants to SEE that). Empty in the single-realm case.
  Pure."
  [realm-qualified]
  (let [id->realms (reduce
                     (fn [acc [realm-id reg-map]]
                       (reduce (fn [a id] (update a id (fnil conj #{}) realm-id))
                               acc
                               (keys reg-map)))
                     {}
                     realm-qualified)]
    (->> id->realms
         (keep (fn [[id realms]] (when (> (count realms) 1) id)))
         set)))

;; ---- realm chip ----------------------------------------------------------

(defn realm-badge
  "Render a small chip naming a row's owning realm — shown ONLY when the
  browse spans more than one realm AND the row is NOT in the default realm
  (the default realm reads as 'unqualified', so it carries no chip even in a
  multi-realm browse — only the explicit, non-default realms get a label).

  `opts`:
    :testid     — the data-testid to stamp (required so each panel scopes it
                  under its own prefix).
    :conflict?  — true ⇒ paint the chip in the warning tone (a cross-realm
                  id conflict — this id also lives in another realm).

  Returns nil when there is nothing to render (single-realm browse, or the
  default realm), so a single-realm panel paints exactly as before."
  ([realm-id testid] (realm-badge realm-id testid {}))
  ([realm-id testid {:keys [conflict?]}]
   (when (and (multi-realm?) (realm-of realm-id))
     [:span {:data-testid testid
             :title       (str "owning realm " (pr-str realm-id)
                               (when conflict?
                                 " · this id also exists in another realm"))
             :style       {:display        "inline-flex"
                           :align-items    "center"
                           :margin-left    "6px"
                           :padding        "1px 5px"
                           :background     (:bg-3 tokens)
                           :color          (if conflict?
                                             (:warning tokens)
                                             (:text-tertiary tokens))
                           :border         (str "1px solid "
                                                (if conflict?
                                                  (:warning tokens)
                                                  (:text-tertiary tokens)))
                           :border-radius  "2px"
                           :font-family    (if (keyword? realm-id) mono-stack sans-stack)
                           :font-size      "9px"
                           :letter-spacing "0.3px"
                           :white-space    "nowrap"}}
      (str "realm " (if (keyword? realm-id) (name realm-id) (pr-str realm-id)))])))
