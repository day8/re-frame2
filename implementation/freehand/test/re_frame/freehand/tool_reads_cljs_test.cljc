(ns re-frame.freehand.tool-reads-cljs-test
  "rf2-lvvl2 increment 1 — the ID-TAKING tool reads, and the dev-only
  declared-view index that makes an id resolvable at all.

  ## The gap these close

  Every Freehand reader beneath the tool tier takes a declaration VALUE.
  `v/manifest` and `tool/view-manifest` are asked about a view by code holding
  one. An MCP inspector holds nothing: it arrives over a wire with
  `:app.people/people-list`. Before this slice there was no way to get from that
  keyword to the declaration, so the whole view-id-oriented half of the Tool-Pair
  read surface had no producer.

  ## What is asserted, and what deliberately is not

  These are DECLARATION reads. They answer what CAN happen — the finite lexical
  sites the compiler saw — and nothing about what DID. So the suite pins the
  four-axis honesty (`:scope` / `:basis` / `:complete?` / `:loss`) hard, because
  that is where a declaration read could lie: an INTERPRETED declaration has no
  analysis step, and an empty roster reported as complete-and-lossless would be
  claiming it found nothing where what it means is that it never looked.

  Nothing here asserts an occurrence, a mount, a generation or a count. Those
  are current-occurrence reads (rf2-xftdv) and the bounded `explain-render`
  (rf2-cpfbg), with their own state and their own suites. The index this reads
  through holds ONE row per declaration, which is asserted below — a
  redeclaration replaces rather than appends, because an index that appended
  would be a history store wearing an index's name.

  ## Cross-host on purpose

  The manifest is built at macro expansion — on the JVM for both hosts — but the
  reads are what a BROWSER tool calls, so the door has to be a namespace
  ClojureScript can compile and run. Proving it only under `clojure -M:test`
  would leave the one host that matters to Pair unexercised, which is exactly
  the defect rf2-hytu5 fixed for the value-taking reader. The expansion-shape
  row is JVM-only, and says so: `expand-defview` is macro machinery and exists
  on the JVM alone."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.registry :as registry]
            [re-frame.freehand.tool :as tool]))

;; ---------------------------------------------------------------------------
;; The census — one compiled declaration carrying every roster under test,
;; and one interpreted declaration that carries no analysis at all
;; ---------------------------------------------------------------------------

(v/defview basket
  "A COMPILED declaration with a literal subscription, a captured-local
  subscription and two event sites — so every honesty arm below has a real
  entry to read, and the literal/dynamic split is a discrimination rather than
  a tautology."
  {:compiled true}
  [{:keys [row-id]}]
  [:section.basket
   [:p (v/sub [:basket/total])]
   [:p (v/sub [:basket/line row-id])]
   [:button {:on-click [:basket/clear]} "Clear"]
   [:button {:on-click [:basket/checkout 7]} "Buy"]])

(v/defview plain-list
  "The paved path: interpreted, so there is no analysis to report and no roster
  that could be honestly claimed complete."
  [{:keys [title]}]
  [:ul [:li title]])

(def ^:private basket-id
  ::basket)

(def ^:private plain-id
  ::plain-list)

;; ---------------------------------------------------------------------------
;; The index — a declaration resolves by id, and holds one row
;; ---------------------------------------------------------------------------

(deftest a-declaration-records-itself-under-its-view-id
  (testing "The fact the whole slice rests on: a `v/defview` puts itself in the
            dev-only index as it is declared, so an inspector that ATTACHES
            LATE — to an application that finished loading long before it
            arrived — can still resolve an id it was handed over a wire."
    (is (= basket (registry/lookup basket-id))
        "the compiled declaration resolves to the declaration value itself")
    (is (= plain-list (registry/lookup plain-id))
        "and so does the interpreted one — both lowerings are inspectable")
    (is (nil? (registry/lookup :nobody/declared-this))
        "an id nothing declared answers nil rather than throwing")
    (is (contains? (registry/view-ids) basket-id)
        "and the roster a sweep enumerates carries it")))

(deftest the-index-holds-one-row-per-declaration-not-a-history
  (testing "Re-registration REPLACES. A reloaded declaration is the SAME view,
            and an index that appended would be the cumulative store
            rf2-drpa3.167 ruled out — under a different name. Asserted over the
            roster's cardinality, which an appending index could not keep."
    (let [before (count (registry/view-ids))]
      (registry/register! basket-id basket)
      (registry/register! basket-id basket)
      (is (= before (count (registry/view-ids)))
          "two more registrations of one id add no rows")
      (is (= basket (registry/lookup basket-id))
          "and the row still answers the declaration"))))

;; ---------------------------------------------------------------------------
;; read-view-manifest
;; ---------------------------------------------------------------------------

(deftest read-view-manifest-answers-the-manifest-inside-the-projection
  (testing "The manifest rides VERBATIM under `:manifest` — the declaration's
            own data, not a second projection of it — inside the envelope a
            consumer validates before parsing anything."
    (let [r (tool/read-view-manifest basket-id)]
      (is (some? r) "a declared compiled view answers")
      (is (= :re-frame.freehand.evidence/v1 (:schema r))
          "stamped with the schema version a consumer checks FIRST")
      (is (= :view-manifest (:read r))
          "and with WHICH read answered, so two reads' answers cannot be confused")
      (is (= basket-id (:view-id r)))
      (is (= :compiled (:lowering r))
          "the lowering is NAMED, never inferred from how much the evidence claims")
      (is (= (v/manifest basket) (:manifest r))
          "one value published twice — the tool read and the application read
           cannot drift, because there is only one projection"))))

(deftest a-compiled-declaration-claims-static-proof-and-no-loss
  (testing "The analyzer walked the whole body and the grammar is finite, so the
            roster is every site there CAN be: complete for `:possible-sites`,
            and lossless."
    (let [r (tool/read-view-manifest basket-id)]
      (is (= :possible-sites (:scope r)))
      (is (= :static-proof (:basis r)))
      (is (true? (:complete? r)))
      (is (nil? (:loss r))))))

(deftest an-interpreted-declaration-reports-the-absence-of-analysis
  (testing "The arm the projection vocabulary exists for. An interpreted body
            has no analysis step, so what it could reach is not merely uncounted
            — it is unknowable without running it. `:complete? true :loss nil`
            here would be a clean bill of health nobody issued."
    (let [r (tool/read-view-manifest plain-id)]
      (is (some? r) "an interpreted declaration is still readable")
      (is (= :interpreted (:lowering r)))
      (is (nil? (:manifest r)) "with no manifest, because there is no analysis")
      (is (= :opaque (:basis r)))
      (is (false? (:complete? r)))
      (is (= {:reason :no-static-analysis :dropped :unknown} (:loss r))
          "and the loss NAMED, with an explicit :unknown — an absent :dropped
           reads as none, which is the one shape this schema exists to refuse"))))

(deftest an-undeclared-id-answers-nil-from-every-read
  (testing "Absence is absence. A tool sweeping ids it was handed cannot assume
            each one names a view in THIS build, and a read that invented an
            empty answer would report a view that does not exist as a view with
            nothing in it."
    (doseq [read-fn [tool/read-view-manifest
                     tool/read-view-dependencies
                     tool/read-view-event-sites]]
      (is (nil? (read-fn :nobody/declared-this))))))

;; ---------------------------------------------------------------------------
;; read-view-dependencies — the literal/dynamic split
;; ---------------------------------------------------------------------------

(deftest read-view-dependencies-projects-the-declared-subscription-sites
  (testing "SITES, not reads: one entry per lexical `v/sub`, read from the
            manifest and so available before anything mounts."
    (let [r (tool/read-view-dependencies basket-id)]
      (is (= :view-dependencies (:read r)))
      (is (= 2 (count (:subscriptions r)))
          "exactly the two `v/sub` sites the body carries")
      (doseq [site (:subscriptions r)]
        (is (string? (:sid site)) "under the compiler-minted stable site id")
        (is (vector? (:path site)) "with its deterministic occurrence coordinate")
        (is (contains? site :dynamic?)
            "and its query-shape honesty stated on the entry, never left to be
             inferred from whether :query happens to be present")))))

(deftest a-literal-query-is-projected-as-the-value-it-is
  (testing "A fully-literal query IS the authored runtime shape, so it is safe
            to show verbatim."
    (let [sites   (:subscriptions (tool/read-view-dependencies basket-id))
          literal (first (filter (complement :dynamic?) sites))]
      (is (some? literal) "the body carries a literal-query site")
      (is (= [:basket/total] (:query literal))
          "shown as data, because that is what it is"))))

(deftest a-captured-local-is-dynamic-and-shows-only-what-is-known
  (testing "`(v/sub [:basket/line row-id])` closes over a template local, so its
            query does not exist until the view renders. Showing the form as
            though it were the query would be handing a reader source code where
            they asked for data — but the HEAD is genuinely known, so it is
            still shown."
    (let [sites   (:subscriptions (tool/read-view-dependencies basket-id))
          dynamic (first (filter :dynamic? sites))]
      (is (some? dynamic) "the body carries a captured-local site")
      (is (not (contains? dynamic :query))
          "no :query is offered, because there is no query yet")
      (is (= :basket/line (:query-id dynamic))
          "the literal query-id IS known and is shown — absence is reported
           where it exists, not spread over the parts that are certain"))))

(deftest the-literal-dynamic-split-is-a-discrimination
  (testing "Non-vacuity for the two rows above: the census carries one of each,
            so a projection that answered `:dynamic? true` to everything — or
            `false` to everything — cannot pass both."
    (let [sites (:subscriptions (tool/read-view-dependencies basket-id))]
      (is (= #{true false} (set (map :dynamic? sites)))))))

(deftest an-interpreted-declaration-has-no-dependency-roster-to-claim
  (testing "Same honesty as the manifest read: an empty roster, and the reason
            it is empty, rather than an empty roster presented as a survey."
    (let [r (tool/read-view-dependencies plain-id)]
      (is (= [] (:subscriptions r)))
      (is (false? (:complete? r)))
      (is (= :no-static-analysis (:reason (:loss r)))))))

;; ---------------------------------------------------------------------------
;; read-view-event-sites — complete roster, narrower entries, said so
;; ---------------------------------------------------------------------------

(deftest read-view-event-sites-projects-every-declared-event-site
  (testing "The ROSTER is complete and lossless: every event site the grammar
            can see is here."
    (let [r (tool/read-view-event-sites basket-id)]
      (is (= :view-event-sites (:read r)))
      (is (= 2 (count (:event-sites r)))
          "exactly the two `:on-click` sites the body carries")
      (is (true? (:complete? r)))
      (is (nil? (:loss r))))))

(deftest an-event-site-says-which-facts-it-can-state
  (testing "The analyzer records `:prop`, `:classification`, `:serializable?`,
            `:sync?` and the authored `:handler` per site, and the manifest
            projection drops all five (rf2-z0blg). So a reader seeing no
            `:handler` is seeing a fact this build does not publish, NOT a site
            with no handler — and `:site-facts` is what makes the difference
            legible from the answer rather than from a changelog.

            Asserted as an EQUALITY against the entries themselves, so the key
            cannot drift into a decoration: if the manifest starts publishing
            the classification and `:site-facts` is not updated, this reds."
    (let [r (tool/read-view-event-sites basket-id)]
      (is (= #{:sid :source-coord :path} (:site-facts r)))
      (doseq [site (:event-sites r)]
        (is (= (:site-facts r) (set (keys site)))
            "every entry states exactly the facts the read says it states")))))

(deftest an-interpreted-declaration-has-no-event-roster-to-claim
  (testing "The third read's honesty arm, asserted rather than assumed to
            follow from its siblings'."
    (let [r (tool/read-view-event-sites plain-id)]
      (is (= [] (:event-sites r)))
      (is (= :opaque (:basis r)))
      (is (= :unknown (:dropped (:loss r)))))))

;; ---------------------------------------------------------------------------
;; The dev gate — visible in the expansion, which is where it can fold away
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest the-registration-is-gated-in-the-expansion
     (testing "The index must not ship. The gate is emitted INTO the declaration
               rather than hidden inside `register!`, because a call site guarded
               by `re-frame.interop/debug-enabled?` is what Closure folds to
               `false` under `:advanced` — taking the call, its descriptor
               argument and every path into the index with it. A gate one
               function call deeper would leave the call site, and therefore the
               namespace, alive in the bundle.

               Checked over the emitted FORM (macro machinery, so JVM-only): the
               expansion names both the gate and the registrar, and names the
               declaration entry exactly ONCE — a two-armed `if` would emit the
               whole declaration twice into every bundle."
       (let [expanded (v/expand-defview {:line 1 :column 1}
                                        "app/probe.cljs"
                                        'app.probe
                                        'probe
                                        '([_] [:div.probe]))
             symbols  (set (filter symbol? (tree-seq coll? seq expanded)))
             text     (pr-str expanded)]
         (is (contains? symbols 're-frame.interop/debug-enabled?)
             "the expansion carries the dev gate itself, not a call to something
              that checks one")
         (is (contains? symbols 're-frame.freehand.registry/register!)
             "and the registration it guards")
         (is (= 1 (count (re-seq #"declare-view" text)))
             "the declaration is constructed exactly once — the gate wraps the
              registration, never a second copy of the entry")))))
