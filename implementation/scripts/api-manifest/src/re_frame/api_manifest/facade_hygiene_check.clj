(ns re-frame.api-manifest.facade-hygiene-check
  "Public-facade manifest-hygiene gate (rf2-gqa7yv — the enforcement half of
  the rf2-ebbpia pre-alpha facade-REMOVAL discipline).

  THE INVARIANT. NO `:facade? true` row in spec/api-manifest.edn may name a
  re-frame.core facade export whose EP-0013 -> EP-0023 disposition marks it
  SUPERSEDED / retained-for-compat. The ADD side of the facade is already
  governed (spec/Conventions.md §Facade policy: diff-time classify-and-justify,
  backed by the api-manifest drift-check). This is the missing SYMMETRIC
  removal rule — the gate that catches a superseded export that STAYS
  `:facade? true` instead of being demoted to its home namespace or removed.

  THE RULE IT ENFORCES (rf2-ebbpia, Mike-ruled 2026-06-18). A superseded or
  accidental public facade export has exactly TWO fates, never three: it
  either (a) becomes INTERNAL (its own namespace, tier :implementation /
  :internal-public, LEAVING the facade and the `:facade? true` manifest row),
  or (b) is REMOVED OUTRIGHT. There is no retained-for-migration facade tier;
  the deprecated tier stays empty in pre-alpha. Migration data/diagnostics, if
  retained, live behind re-frame.migration (its own ns), NEVER re-exported
  from re-frame.core. A correctly-resolved disposition therefore produces NO
  `:facade? true` row — so the gate is GREEN on a clean tree, and the allowlist
  (facade-hygiene-allowlist.edn) is expected to be near-empty.

  THE JOIN. The disposition data already exists as INSPECTABLE DATA in
  `re-frame.migration/migration-map` ({surface-kw {:status … :replacement …
  :guidance …}}), the EP-0013 -> EP-0023 surface dispositions. We load it on
  the JVM (the api-manifest classpath carries day8/re-frame2) rather than
  copying it, so the gate tracks the live source. Each migration-map entry
  whose KEY is `:rf`-namespaced (`:rf/app`, `:rf/install!`, …) names a facade
  export by its keyword's NAME (`:rf/install!` -> the `install!` export). For
  each such entry whose `:status` is in the superseded set, we FAIL if a
  `:facade? true` manifest row carries that bare var name (unless the
  (var, status) pair is on the checked-in allowlist). Entries keyed in OTHER
  namespaces (`:rf.realm/frame-address`, `:rf.realm/scoped-query`) name
  conceptual surfaces, not a single facade var, so they contribute no
  candidate.

  WHY THIS GENERALISES pl97nd.5. The narrow EP-0023 retired-vocab grep
  (scripts/check_retired_composition_vocab.py) is a DOC-SURFACE gate: it scans
  markdown fenced code for a retired spelling reappearing as live,
  copy-pasteable teaching API. This gate is the MANIFEST-DATA counterpart: it
  joins the same dispositions against the generated manifest to catch a
  superseded export staying on the facade. The two are COMPLEMENTARY (different
  surfaces — teaching prose vs. the manifest), not duplicative; the grep keeps
  the doc teaching surface clean, this keeps the facade-export set clean. Both
  are driven from the same EP-0013 -> EP-0023 retirement; neither subsumes the
  other.

  Invoked exactly like the sibling checks (gen --check, api-md-check):
    clojure -M -m re-frame.api-manifest.facade-hygiene-check  ; (from this dir)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.api-manifest.gen :as gen]))

(def superseded-statuses
  "The disposition `:status` values that mark a facade export as superseded /
   retained-for-compat — the statuses that MUST NOT coexist with a
   `:facade? true` manifest row. `:publicly-replaced` (the public name moved
   to the EP-0023 replacement), `:re-expressed` (the concept survives under
   image vocabulary), `:retained-internal` (kept as an implementation /
   migration / tooling surface, NOT the taught public vocabulary). A future
   per-EP disposition table may add `:removed` / `:superseded` markers; they
   belong here too, which is why the gate reads this set rather than the three
   literals inline."
  #{:publicly-replaced :re-expressed :retained-internal :removed :superseded})

(def ^:private migration-map-sym
  "The inspectable disposition data the gate joins against. Resolved at
   runtime so the gate tracks the LIVE source (re-frame.migration is on the
   api-manifest classpath via day8/re-frame2) rather than copying it."
  're-frame.migration/migration-map)

(def ^:private allowlist-file
  (delay (io/file (System/getProperty "user.dir") "facade-hygiene-allowlist.edn")))

(def ^:private min-dispositions
  "Non-vacuous floor for the joined disposition source (rf2-utvst class). The
   live migration-map carries 8 entries (6 of them `:rf`-namespaced facade
   names). If the join recovers ZERO superseded `:rf`-named dispositions the
   gate would report a vacuous OK no matter how many superseded exports sit on
   the facade — a migration-map ns rename / shape change / require failure
   would silently disarm the gate. This floor (set below the live count) trips
   only on a near-total collapse, never on ordinary migration-map churn."
  1)

;; ---------------------------------------------------------------------------
;; Pure pieces (unit-testable with synthetic inputs).
;; ---------------------------------------------------------------------------

(defn superseded-facade-names
  "From a `migration-map` value, the `{facade-var-name -> status}` map of
   facade exports a superseded disposition names. Only entries whose KEY is in
   the `rf` namespace name a single facade var (the keyword's NAME is the
   export name); a `:rf.realm/…`-keyed entry names a conceptual surface, not a
   var, so it is skipped. Only `superseded` statuses contribute."
  [migration-map superseded]
  (reduce-kv
    (fn [acc surface-kw {:keys [status]}]
      (if (and (= "rf" (namespace surface-kw))
               (contains? superseded status))
        (assoc acc (name surface-kw) status)
        acc))
    {}
    migration-map))

(defn reconcile
  "Pure reconciler. Returns `[problems stale-allowlist]`:

   `problems`        — `{:var :status :namespace :tier}` for each `:facade? true`
                       manifest row whose bare var name has a superseded
                       disposition AND is NOT allowlisted (the gate FAILS).
   `stale-allowlist` — allowlist `{:var :status}` entries that matched no
                       violation (a cleared transitional exception that must be
                       removed so the allowlist does not rot).

   `rows`      — manifest `:vars` rows (each `{:namespace :var :facade? :tier …}`).
   `name->status` — `{facade-var-name -> superseded-status}` from
                    `superseded-facade-names`.
   `allow`     — set of `[var status]` pairs from the checked-in allowlist."
  [{:keys [rows name->status allow]}]
  (let [facade-rows (filter :facade? rows)
        problems    (keep
                      (fn [{:keys [namespace var tier]}]
                        (when-let [status (get name->status var)]
                          (when-not (contains? allow [var status])
                            {:var var :status status
                             :namespace namespace :tier tier})))
                      facade-rows)
        ;; which (var, status) pairs the facade actually presents (allowlisted
        ;; or not) — an allowlist entry naming a pair NOT present is stale.
        present     (set (for [{:keys [var]} facade-rows
                               :let [status (get name->status var)]
                               :when status]
                           [var status]))
        stale       (filter #(not (contains? present (vec %))) allow)]
    [(vec problems) (vec stale)]))

(defn floor-violation?
  "True when the joined superseded-disposition map recovered fewer than the
   non-vacuous floor — the join collapsed (migration-map rename / shape change
   / require failure) and an OK verdict would be vacuous."
  [name->status]
  (< (count name->status) min-dispositions))

;; ---------------------------------------------------------------------------
;; IO + entry point.
;; ---------------------------------------------------------------------------

(defn- read-allowlist
  "The checked-in allowlist as a set of `[var status]` pairs. Each EDN entry
   is `{:var <str> :status <kw> :reason <str>}`."
  []
  (let [f @allowlist-file]
    (if (.exists ^java.io.File f)
      (with-open [r (io/reader f)]
        (->> (edn/read (java.io.PushbackReader. r))
             (map (fn [{:keys [var status]}] [var status]))
             set))
      #{})))

(defn- live-migration-map
  "Resolve + deref the live disposition data, requiring its ns. Kept behind a
   fn so a require/resolve failure surfaces as a loud error, not a silent nil
   that would vacuum-green the gate."
  []
  (require (symbol (namespace migration-map-sym)))
  (if-let [v (resolve migration-map-sym)]
    @v
    (throw (ex-info (str "facade-hygiene-check: " migration-map-sym
                         " did not resolve — the disposition source moved or "
                         "was renamed. The gate cannot run without it.")
                    {:sym migration-map-sym}))))

(defn check!
  "Validate that no `:facade? true` manifest row names a superseded facade
   export. Returns true (prints OK) when clean; false (prints a report) on any
   violation, a stale allowlist entry, or a collapsed disposition join."
  []
  (let [rows         (:vars (gen/read-committed-manifest))
        migration    (live-migration-map)
        name->status (superseded-facade-names migration superseded-statuses)
        allow        (read-allowlist)
        [problems stale] (reconcile {:rows rows
                                     :name->status name->status
                                     :allow allow})]
    (cond
      (floor-violation? name->status)
      (do (binding [*out* *err*]
            (println (format (str "DRIFT: facade-hygiene disposition join collapsed — only %d "
                                  "superseded `:rf`-named disposition(s) recovered from "
                                  "re-frame.migration/migration-map, below the non-vacuous "
                                  "floor of %d.")
                             (count name->status) min-dispositions))
            (println "  An OK verdict would be vacuous (the gate would pass no matter how")
            (println "  many superseded exports sit on the facade). Likely a migration-map")
            (println "  rename / shape change / require failure. Investigate before GREEN."))
          false)

      (or (seq problems) (seq stale))
      (do (binding [*out* *err*]
            (when (seq problems)
              (println (str "DRIFT: spec/api-manifest.edn carries `:facade? true` row(s) for "
                            "SUPERSEDED facade export(s)."))
              (println "Per the pre-alpha facade-REMOVAL discipline (spec/Conventions.md")
              (println "§Removing or demoting a facade export), a superseded export must either")
              (println "become INTERNAL (its home namespace, tier :implementation /")
              (println ":internal-public, :facade? false — off the facade) or be REMOVED")
              (println "outright. There is no retained-for-migration facade tier. Demote or")
              (println "remove the export + regenerate the manifest. Violations:")
              (doseq [{:keys [var status namespace tier]} problems]
                (println (format "  `%s` (ns %s, tier %s) — disposition %s but still :facade? true"
                                 var namespace tier status))))
            (when (seq stale)
              (println "STALE allowlist: facade-hygiene-allowlist.edn names exemption(s) that")
              (println "match no current violation — the transitional case has cleared. Remove")
              (println "them so the allowlist does not rot into a permanent escape hatch:")
              (doseq [[var status] stale]
                (println (format "  `%s` (status %s)" var status)))))
          false)

      :else
      (do (println (format (str "OK: facade-hygiene clean — %d superseded `:rf`-named "
                                "disposition(s) joined against the manifest; no "
                                "`:facade? true` row names a superseded export.")
                           (count name->status)))
          true))))

(defn -main [& _]
  (System/exit (if (check!) 0 1)))
