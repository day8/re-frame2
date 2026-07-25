(ns re-frame.freehand.manifest-roster-coverage-cljs-test
  "EVERY ROSTER, NOT MOST OF THEM — the non-vacuity half of `FH-STRUCT-010`.

  The census suite asserts that every manifest roster entry carries the keys
  the fixture requires, including a whole `:source-coord`. Over the census
  that row inspects two rosters: `:events` and `:crossings` are the only ones
  a `v/defview` census declaration can populate, and a per-entry contract
  asserted over zero entries is the loudest kind of green there is. Deleting
  the coordinate from subscription-site recording left both gates passing.

  This suite closes that. The fixture's `:roster-coverage` table names every
  roster the manifest carries, states how many entries the proof inspects, and
  states the SURFACE it inspects them through — and each of those three facts
  is asserted, so a roster cannot be covered by an empty inspection and cannot
  be dropped from the table without failing.

  ## Two surfaces, and the gap named rather than hidden

  `:subscriptions`, `:events`, `:html-sites` and `:crossings` are read back
  through `v/manifest`, the highest public surface there is. `:slots` cannot be
  proven there, for the ordinary reason: `v/slot` IS published — it sits on the
  door and carries an api-manifest row — and its roster is empty because no
  census declaration happens to use a slot. So it is proven one tier down, over
  the same analyzer a `v/defview` body goes through — and the gap is asserted,
  not assumed: an `:analyzer` roster must be EMPTY across the public census, so
  the day one becomes populated its fixture row has to move deliberately.

  TWO rows have moved that way, and both moves were forced by this suite going
  red rather than noticed by a reader. `:subscriptions` sat on the analyzer tier
  not because `v/sub` was unpublished — it always was — but because the compiled
  lowering resolved it against a runtime namespace that did not exist, so no
  census declaration carrying a subscription could LOAD; the runtime landed and
  the census gained a sub-bearing declaration. `:html-sites` sat there because
  `v/html` had no published var behind it at all, so no declaration COULD carry
  one; rf2-rrosy published the verb and lowered it on all four rendering paths,
  and the census gained `trusted-markup`.

  ## Which host proves which half

  Site RECORDING is host-neutral — the analyzer is pure and resolution is
  injected — so the analyzer-tier rosters are proven to carry coordinates
  under `clojure -M:test` and `npm run test:freehand` alike. The manifest
  PROJECTION that turns a site index into rosters is macro-expansion
  machinery and exists on the JVM only, so the projection arm is JVM-only.
  Between them, dropping a coordinate from either the recording or the
  projection of any of the five rosters fails a named test here.

  Exact reader-dependent positions stay where they belong — the JVM-only
  `re-frame.freehand.manifest-source-coord-jvm-test` — because Clojure's
  reader anchors lists alone while ClojureScript's anchors every collection,
  so one cross-host table of positions would have to assert something false on
  one host. What is host-neutral is the TOTAL shape: a whole coordinate with
  real, positive values, which is exactly what this suite pins."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.manifest-views :as mv]
            #?(:clj [re-frame.freehand.compiler :as compiler])))

(def struct-010 (conf/fixture :FH-STRUCT-010))

(def ^:private coverage (:roster-coverage struct-010))
(def ^:private required (:required-keys struct-010))
(def ^:private coord-keys (:source-coord-keys struct-010))

;; ---------------------------------------------------------------------------
;; The analyzer tier — the rosters the public census cannot populate yet
;; ---------------------------------------------------------------------------

(def ^:private resolver
  "The injected resolution stub — the probe body below is analysed against it
  rather than against production resolution, which is what makes it
  host-neutral. `slot` resolves to the var the door really publishes. `sub` and
  `html` are deliberately absent: both are public, both lower, and both rosters
  are proven through `v/manifest` over the census like `:events` is."
  (fn [sym]
    (case sym
      slot {:fqn 're-frame.freehand/slot :meta {}}
      nil)))

(def ^:private probe-source
  "The stand-in declaration position every probe site inherits.

  The templates below are stripped of reader anchors on purpose, so each site
  takes the TOTAL fallback — the arm of the coordinate law that IS host-neutral,
  and the only one a cross-host suite can assert identically. A template left
  anchored would report this file's own reader positions against a synthetic
  file name, which is a pair no source would agree with."
  {:file "app/probe.cljc" :line 12 :column 3})

(defn- unanchored
  "`form` with every reader anchor removed, so the coordinate each site
  reports is the declaration's rather than the template literal's own."
  [form]
  (walk/postwalk (fn [x] (if (some? (meta x)) (with-meta x nil) x)) form))

(def ^:private analyzer-probes
  "roster -> the site-index key the projection reads it from, and one body
  carrying EXACTLY the number of sites the fixture declares. Hand-countable, so
  a count that moves is an edit someone made rather than a number that drifted."
  {:slots {:site-key :slots :template '[:ul (slot row-renderer item)]}})

(defn- probe-env []
  (-> (env/make-env {:host      #?(:clj :clj :cljs :cljs)
                     :ns-sym    'app.probe
                     :self      'probe
                     :self-id   :app.probe/probe
                     :source    probe-source
                     :resolver  resolver})
      (assoc :self-children? false :self-closed-keys nil :hooks-region? true)))

(defn- analyzed
  "-> {:ast … :sites …} for one probe template, analyzed the way a `v/defview`
  body is analyzed."
  [template]
  (let [e   (probe-env)
        ast (ana/analyze-view-body e (list (unanchored template)))]
    {:ast ast :sites @(:sites e)}))

;; ---------------------------------------------------------------------------
;; The coverage table itself
;; ---------------------------------------------------------------------------

(deftest the-coverage-table-names-every-roster-with-a-positive-count
  (testing "The table is the guard against the defect this suite exists for, so
            it is guarded in turn: it must name exactly the rosters the
            per-entry shape row governs, and no roster may claim coverage from
            zero entries. Both halves of a vacuous row — a missing roster and an
            empty one — are refused here."
    (is (= (set (keys required)) (set (keys coverage)))
        "the coverage table names every roster :required-keys governs and no other")
    (doseq [[roster {:keys [surface entries]}] coverage]
      (is (pos-int? entries)
          (str roster " — coverage is a POSITIVE entry count, never zero"))
      (is (contains? #{:public :analyzer} surface)
          (str roster " — the surface is one of the two this suite can read")))))

(deftest the-public-door-gap-is-asserted-rather-than-assumed
  (testing "A roster proven one tier below `v/manifest`, because no census
            declaration uses the published form it records. That is a GAP,
            and a gap stated in a comment is a gap that outlives its cause — so
            it is stated executably: a roster the fixture marks `:analyzer` must
            be empty across the whole public census, and the day one becomes
            populated this test reds until its fixture row moves."
    (doseq [[roster {:keys [surface]}] coverage
            :when (= :analyzer surface)]
      (is (empty? (mapcat #(get (v/manifest %) roster) (vals mv/by-name)))
          (str roster " — no census declaration can populate it through the "
               "public door today; when one can, move its :surface to :public")))))

;; ---------------------------------------------------------------------------
;; Per-roster coordinate shape — the row made non-vacuous
;; ---------------------------------------------------------------------------

(defn- coordinate-is-whole-and-positive
  "The host-neutral coordinate law, asserted over one roster entry."
  [label entry]
  (let [coord (:source-coord entry)]
    (is (= coord-keys (set (keys coord)))
        (str label " — its :source-coord is a whole {:file :line :column}"))
    (is (string? (:file coord))
        (str label " — naming the file the compiler read"))
    (is (seq (:file coord))
        (str label " — a real path, never a blank stand-in"))
    (is (pos-int? (:line coord))
        (str label " — with a real line, never a placeholder"))
    (is (pos-int? (:column coord))
        (str label " — and a real column"))))

(deftest public-door-rosters-carry-the-declared-entries-and-their-coordinates
  (testing "The two rosters `v/manifest` reaches: the census yields exactly the
            entry count the fixture declares — so an emptied census reds rather
            than passing over nothing — and every entry carries the required
            keys and a whole coordinate."
    (doseq [[roster {:keys [surface entries]}] coverage
            :when (= :public surface)]
      (let [found (vec (mapcat #(get (v/manifest %) roster) (vals mv/by-name)))]
        (is (= entries (count found))
            (str roster " — exactly the declared number of entries across the census"))
        (doseq [entry found]
          (is (= (get required roster) (set (keys entry)))
              (str roster " — every entry carries exactly the required keys"))
          (coordinate-is-whole-and-positive (str "public " roster) entry))))))

(deftest analyzer-tier-rosters-record-a-coordinate-on-every-site
  (testing "The rosters the census cannot populate, proven where they ARE
            populated and on BOTH hosts: the analyzer is pure, so the site index
            it builds is host-neutral. Each probe body carries exactly the
            declared number of sites, and every one of them records the whole
            coordinate — which is the fact that was deletable in silence."
    (doseq [[roster {:keys [surface entries]}] coverage
            :when (= :analyzer surface)]
      (let [{:keys [site-key template]} (get analyzer-probes roster)
            sites (get (:sites (analyzed template)) site-key)]
        (is (some? site-key) (str roster " — the fixture's roster has a probe"))
        (is (= entries (count sites))
            (str roster " — the probe body carries exactly the declared sites"))
        (doseq [site sites]
          (coordinate-is-whole-and-positive (str "recorded " roster) site))))))

#?(:clj
   (deftest analyzer-tier-rosters-project-the-coordinate-into-the-manifest
     (testing "Recording a coordinate and PROJECTING it are two facts, and the
               projection is the half a `select-keys` can drop. It is
               macro-expansion machinery and so exists on the JVM alone — which
               is enough, because a roster that lost its coordinate on either
               host lost it in one shared analyzer. Each probe's manifest roster
               carries the declared entries with exactly the required keys."
       (doseq [[roster {:keys [surface entries]}] coverage
               :when (= :analyzer surface)]
         (let [{:keys [ast sites]} (analyzed (:template (get analyzer-probes roster)))
               manifest (compiler/structural-manifest :app.probe/probe ast sites)
               found    (get manifest roster)]
           (is (= entries (count found))
               (str roster " — the projection carries every recorded site"))
           (doseq [entry found]
             (is (= (get required roster) (set (keys entry)))
                 (str roster " — every projected entry carries exactly the required keys"))
             (coordinate-is-whole-and-positive (str "projected " roster) entry)))))))
