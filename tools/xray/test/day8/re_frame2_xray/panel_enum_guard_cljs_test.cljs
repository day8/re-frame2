(ns day8.re-frame2-xray.panel-enum-guard-cljs-test
  "SINGLE-SOURCE GUARD for the Xray panel enumeration (rf2-rapnr;
  absorbs the guard-half of rf2-7b1z4; follow-on to the rf2-3nbl5.2
  API-governance keystone + rf2-jhn46's #2786 Xray manifest rows + the
  rf2-2mtte CLJS enumeration probe).

  ## What this guards

  `day8.re-frame2-xray.panel-enum/panel-enum` is the ONE authoritative
  enumeration of the mountable Xray panel set. Two other projections of
  \"the set of panels\" must agree with it, or this test goes RED:

    PROJECTION A — THE MOUNT FACADE. The live public `mount-<panel>!`
      vars of `day8.re-frame2-xray.panels`. Reconciled BIDIRECTIONALLY:
        - a `mount-*!` facade fn with no enum entry → RED (an orphan
          mount fn the enum + spec never declared);
        - an enum `:mount` name with no live facade fn → RED (a renamed
          / removed fn the enum still claims).

    PROJECTION B — THE XRAY API SPEC. The `mount-<panel>!` names the
      spec enumerates in `007-UX-IA.md` §Mountable surface inventory +
      `008-Embedding-Contract.md`. Reconciled BIDIRECTIONALLY:
        - a spec-named `mount-*!` with no enum entry → RED (the spec
          drifted ahead / kept a stale name — e.g. the pre-fix
          `mount-views!` ghost);
        - an enum `:mount` name the spec never mentions → RED (a panel
          shipped but undocumented).

  The third projection — the api-manifest `:cljs-only` rows for
  `day8.re-frame2-xray.panels` — is reconciled against the LIVE vars by
  the rf2-2mtte CLJS probe and the rf2-gkp0t `xray_spec_check`. Because
  PROJECTION A pins the live facade to the enum, the manifest inherits
  enum coherence transitively; this guard adds the enum↔facade and
  enum↔spec edges those checks did not cover.

  ## Mechanism

  Both extra projections are read at COMPILE time so the test stays a
  headless pure-data reconciliation (no DOM, no runtime filesystem):
    - the live facade surface via `emit-ns-publics` (the rf2-2mtte
      analyzer enumeration macro);
    - the spec references via `emit-spec-mount-fn-names` (the JVM-side
      slurp macro in `panel_enum_spec_refs.clj`).

  ## Proving RED-on-drift

  Add a `mount-<panel>!` to ONE projection but not the enum (a new
  facade fn, or a new spec mention) → the corresponding bidirectional
  assertion below fails. Add an enum entry whose `:mount` no facade fn
  / no spec line carries → the reverse direction fails. Reverting the
  drift returns the test to green."
  (:require-macros [re-frame.api-manifest.cljs-publics :refer [emit-ns-publics]]
                   [day8.re-frame2-xray.panel-enum-spec-refs
                    :refer [emit-spec-mount-fn-names]])
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.set :as set]
            [day8.re-frame2-xray.panel-enum :as panel-enum]
            ;; Force the analyzer to analyse the facade ns before
            ;; `emit-ns-publics` expands so the macro reads a real
            ;; surface. The alias is unused at runtime (the surface is
            ;; read at compile time) but the require is load-bearing.
            [day8.re-frame2-xray.panels]))

;; ---------------------------------------------------------------------------
;; The three projections, captured as plain sets.
;; ---------------------------------------------------------------------------

(def ^:private enum-mount-names
  "PROJECTION SOURCE — the single source of truth's mount-fn set."
  panel-enum/mount-fn-names)

(def ^:private facade-mount-names
  "PROJECTION A — the live public `mount-<panel>!` vars of
  `day8.re-frame2-xray.panels`, captured at compile time. Filtered to
  the `mount-*!` shape (the ns has other publics — `render-panel!` is
  private; `ManagedFxList` is a reg-view; the mount fns are the panel
  facade)."
  (into #{}
        (comp (map first)
              (filter #(re-matches #"mount-[a-z][a-z0-9-]*!" %)))
        (emit-ns-publics day8.re-frame2-xray.panels)))

(def ^:private spec-mount-names
  "PROJECTION B — the `mount-<panel>!` names the Xray API spec
  enumerates, captured at compile time."
  (emit-spec-mount-fn-names))

;; ---------------------------------------------------------------------------
;; Sanity — none of the projections is silently empty (a vacuously-green
;; guard is worse than no guard).
;; ---------------------------------------------------------------------------

(deftest projections-are-non-empty
  (testing "each projection enumerated at least one mount fn — guards
            against a dropped require / unanalysed ns / unreadable spec
            making a reconciliation vacuously green"
    (is (seq enum-mount-names)   "the single-source enum is non-empty")
    (is (seq facade-mount-names) "the live facade surface enumerated mount fns")
    (is (seq spec-mount-names)   "the spec enumerated mount fns")))

;; ---------------------------------------------------------------------------
;; PROJECTION A — facade ⟺ enum.
;; ---------------------------------------------------------------------------

(deftest facade-matches-enum
  (testing "every live `mount-<panel>!` facade fn is declared in the
            single-source enum, and every enum `:mount` resolves to a
            live facade fn — bidirectional. A drift either way is the
            facade + enum disagreeing on the set of panels."
    (let [orphan-fns   (set/difference facade-mount-names enum-mount-names)
          missing-fns  (set/difference enum-mount-names facade-mount-names)]
      (is (empty? orphan-fns)
          (str "Live `mount-<panel>!` facade fns with NO `panel-enum` "
               "entry (add them to day8.re-frame2-xray.panel-enum/panel-enum "
               "or remove the fn): " (sort orphan-fns)))
      (is (empty? missing-fns)
          (str "`panel-enum` `:mount` names with NO live facade fn in "
               "day8.re-frame2-xray.panels (renamed / removed — update the "
               "enum): " (sort missing-fns))))))

;; ---------------------------------------------------------------------------
;; PROJECTION B — spec ⟺ enum.
;; ---------------------------------------------------------------------------

(deftest spec-matches-enum
  (testing "every `mount-<panel>!` the Xray API spec names is declared
            in the single-source enum, and every enum `:mount` is named
            somewhere in the spec — bidirectional. A drift either way is
            the spec inventory + enum disagreeing on the set of panels."
    (let [stale-spec   (set/difference spec-mount-names enum-mount-names)
          undocumented (set/difference enum-mount-names spec-mount-names)]
      (is (empty? stale-spec)
          (str "Xray spec (007-UX-IA.md / 008-Embedding-Contract.md) "
               "names `mount-<panel>!` fns with NO `panel-enum` entry "
               "(a stale / drifted spec reference — reconcile the spec or "
               "the enum): " (sort stale-spec)))
      (is (empty? undocumented)
          (str "`panel-enum` `:mount` names the Xray spec never mentions "
               "(an undocumented panel — add it to 007-UX-IA.md §Mountable "
               "surface inventory or 008-Embedding-Contract.md): "
               (sort undocumented))))))

;; ---------------------------------------------------------------------------
;; Transitive closure — all three projections collapse to ONE set.
;; ---------------------------------------------------------------------------

(deftest all-projections-agree
  (testing "the facade, the spec, and the single-source enum all project
            the SAME mount-fn set — the single-source contract in one
            assertion"
    (is (= enum-mount-names facade-mount-names spec-mount-names)
        (str "Panel-enum projections disagree.\n"
             "  enum:   " (sort enum-mount-names) "\n"
             "  facade: " (sort facade-mount-names) "\n"
             "  spec:   " (sort spec-mount-names)))))

;; ---------------------------------------------------------------------------
;; Enum well-formedness — the source itself stays coherent.
;; ---------------------------------------------------------------------------

(deftest enum-entries-are-well-formed
  (testing "every panel-enum entry has a keyword :id, a `mount-*!`
            string, and a tier from the closed vocabulary; ids + mount
            names are unique"
    (doseq [{:keys [id mount tier] :as entry} panel-enum/panel-enum]
      (is (keyword? id) (str "entry :id is a keyword: " entry))
      (is (and (string? mount) (re-matches #"mount-[a-z][a-z0-9-]*!" mount))
          (str "entry :mount is a `mount-*!` string: " entry))
      (is (contains? #{:l3-tab :overlay :inline :spine :full-shell} tier)
          (str "entry :tier is from the closed vocabulary: " entry)))
    (is (= (count panel-enum/panel-enum) (count panel-enum/panel-ids))
        "panel :id values are unique")
    (is (= (count panel-enum/panel-enum) (count panel-enum/mount-fn-names))
        "panel :mount values are unique")))
