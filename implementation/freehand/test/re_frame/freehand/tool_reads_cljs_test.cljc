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
  on the JVM alone.

  ## Posture split (rf2-74a89)

  Both halves of this slice are DEV-ONLY, and the docstring above already
  says why the index is: `v/defview` emits its `registry/register!` call
  behind `interop/debug-enabled?` precisely so Closure folds the call, its
  descriptor argument and every path into the index out of a release bundle.
  The three reads are gated at the door too (`tool.cljc` §DEV-ONLY). So under
  `-Dre-frame.debug=false` the index is empty and every read answers nil.

  What that does NOT elide is the manifest. `v/manifest` is the total,
  value-taking read — the one read `tool.cljc` exempts from the gate — and it
  is byte-identical in both postures, because the analyzer ran at macro
  expansion and its rosters are compile-time facts. The three id-taking reads
  are PROJECTIONS of it. That is the whole shape of the split here:

    - every assertion reading `registry/*` or `tool/read-view-*` is kept
      VERBATIM inside a `(when interop/debug-enabled? …)` arm marked
      `rf2-74a89`, negatives included — `an-undeclared-id-answers-nil-from-
      every-read` and `(nil? (registry/lookup :nobody/declared-this))` are
      satisfied under the gate by reads that answer nil for EVERY id, which
      is the vacuous absence this lane exists to close;
    - `the-census-manifest-is-the-substrate-the-three-reads-project` asserts
      the SAME census facts through `v/manifest`, in both postures: two
      subscription sites, four event sites, the five per-site facts rf2-z0blg
      made the manifest publish, and the interpreted declaration's absent
      analysis. When a projection and its substrate disagree it is the
      substrate that is right, and this is the row that says what it holds;
    - `the-index-holds-one-row-per-declaration-not-a-history` needs no arm at
      all. `registry/register!` is the index's own door and is ungated — only
      the CALL SITE inside the expansion carries the gate — so the
      replace-not-append law is driven through explicit registrations and
      holds in both postures;
    - `the-registration-is-gated-in-the-expansion` is unchanged and is the
      decisive production fact: the gate is IN the emitted form, where
      Closure can fold it, rather than one function call deeper;
    - and `neither-the-index-nor-its-reads-survive-the-production-gate`
      states the elision as a positive fact, through the real load-time gate
      rather than a `with-redefs` rebind, which cannot reach one
      (rf2-9c2jf)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.registry :as registry]
            [re-frame.freehand.tool :as tool]
            [re-frame.interop :as interop]))

;; ---------------------------------------------------------------------------
;; The census — one compiled declaration carrying every roster under test,
;; and one interpreted declaration that carries no analysis at all
;; ---------------------------------------------------------------------------

(v/defview basket-row
  "A child declaration [[basket]] mounts, so the parent's event roster carries a
  COMPONENT-PROP committed callback beside its DOM handler sites. That is the
  arm whose `:sync?` was absent rather than false until rf2-z0blg, which the
  per-entry key law below could not have caught over a census without one."
  {:compiled true}
  [{:keys [label on-remove]}]
  [:li [:button {:on-click on-remove} label]])

(v/defview basket
  "A COMPILED declaration with a literal subscription, a captured-local
  subscription, and one event site of each honesty class — a literal event
  vector, a vector carrying a captured local, and an opaque committed callback
  at a child's prop — so every honesty arm below has a real entry to read, and
  each split is a discrimination rather than a tautology."
  {:compiled true}
  [{:keys [row-id]}]
  [:section.basket
   [:p (v/sub [:basket/total])]
   [:p (v/sub [:basket/line row-id])]
   [:button {:on-click [:basket/clear]} "Clear"]
   [:button {:on-click [:basket/checkout 7]} "Buy"]
   [:button {:on-click [:basket/remove row-id]} "Remove"]
   [basket-row {:label "Line" :on-remove (v/event [_] [:basket/removed row-id])}]])

(v/defview print-button
  "A COMPILED declaration whose one event site is spelled as the listener
  OPTIONS map. That is the SECOND of the two spellings carrying an event vector,
  so it is the second arm of the handler projection — held apart from [[basket]]
  so the cardinality and split rows there stay about the classes they name."
  {:compiled true}
  [{:keys [label]}]
  [:button {:on-click {:event [:basket/print] :prevent-default true}} label])

(v/defview plain-list
  "The paved path: interpreted, so there is no analysis to report and no roster
  that could be honestly claimed complete."
  [{:keys [title]}]
  [:ul [:li title]])

(def ^:private basket-id
  ::basket)

(def ^:private options-id
  ::print-button)

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
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The whole
    ;; deftest is about what `v/defview`'s own gated call site records, and
    ;; the `nil` row travels inside it because under the gate EVERY id
    ;; answers nil.
    (when interop/debug-enabled?
      (is (= basket (registry/lookup basket-id))
          "the compiled declaration resolves to the declaration value itself")
      (is (= plain-list (registry/lookup plain-id))
          "and so does the interpreted one — both lowerings are inspectable")
      (is (nil? (registry/lookup :nobody/declared-this))
          "an id nothing declared answers nil rather than throwing")
      (is (contains? (registry/view-ids) basket-id)
          "and the roster a sweep enumerates carries it"))))

(deftest the-index-holds-one-row-per-declaration-not-a-history
  (testing "Re-registration REPLACES. A reloaded declaration is the SAME view,
            and an index that appended would be the cumulative store
            rf2-drpa3.167 ruled out — under a different name. Asserted over the
            roster's cardinality, which an appending index could not keep.

            No posture arm (rf2-74a89): `registry/register!` is the index's own
            door and is ungated — the gate is on the CALL SITE inside
            `v/defview`'s expansion, which is what
            `the-registration-is-gated-in-the-expansion` checks. So the law is
            driven through explicit registrations here and holds in both
            postures; the seeding row below is what makes the count start from
            a known state under the gate, where nothing declared above is in
            the index."
    (registry/register! basket-id basket)
    (let [before (count (registry/view-ids))]
      (is (pos? before) "the seeded row is there to be replaced")
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
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The MANIFEST
    ;; inside the envelope is posture-independent and is asserted directly by
    ;; `the-census-manifest-is-the-substrate-the-three-reads-project`; the
    ;; envelope around it is the gated read's.
    (when interop/debug-enabled?
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
             cannot drift, because there is only one projection")))))

(deftest a-compiled-declaration-claims-static-proof-and-no-loss
  (testing "The analyzer walked the whole body and the grammar is finite, so the
            roster is every site there CAN be: complete for `:possible-sites`,
            and lossless."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [r (tool/read-view-manifest basket-id)]
        (is (= :possible-sites (:scope r)))
        (is (= :static-proof (:basis r)))
        (is (true? (:complete? r)))
        (is (nil? (:loss r)))))))

(deftest an-interpreted-declaration-reports-the-absence-of-analysis
  (testing "The arm the projection vocabulary exists for. An interpreted body
            has no analysis step, so what it could reach is not merely uncounted
            — it is unknowable without running it. `:complete? true :loss nil`
            here would be a clean bill of health nobody issued."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The FACT the
    ;; vocabulary is reporting — that an interpreted declaration carries no
    ;; manifest at all — is posture-independent and is asserted by
    ;; `the-census-manifest-is-the-substrate-the-three-reads-project`.
    (when interop/debug-enabled?
      (let [r (tool/read-view-manifest plain-id)]
        (is (some? r) "an interpreted declaration is still readable")
        (is (= :interpreted (:lowering r)))
        (is (nil? (:manifest r)) "with no manifest, because there is no analysis")
        (is (= :opaque (:basis r)))
        (is (false? (:complete? r)))
        (is (= {:reason :no-static-analysis :dropped :unknown} (:loss r))
            "and the loss NAMED, with an explicit :unknown — an absent :dropped
             reads as none, which is the one shape this schema exists to refuse")))))

(deftest an-undeclared-id-answers-nil-from-every-read
  (testing "Absence is absence. A tool sweeping ids it was handed cannot assume
            each one names a view in THIS build, and a read that invented an
            empty answer would report a view that does not exist as a view with
            nothing in it."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). Under the gate
    ;; all three reads answer nil for every id, declared or not, so this row
    ;; would hold without the reads telling one from the other. Its production
    ;; counterpart is
    ;; `neither-the-index-nor-its-reads-survive-the-production-gate`, which
    ;; asserts the same nil about ids that ARE declared — a claim about the
    ;; gate rather than about the id.
    (when interop/debug-enabled?
      (doseq [read-fn [tool/read-view-manifest
                       tool/read-view-dependencies
                       tool/read-view-event-sites]]
        (is (nil? (read-fn :nobody/declared-this)))))))

;; ---------------------------------------------------------------------------
;; read-view-dependencies — the literal/dynamic split
;; ---------------------------------------------------------------------------

(deftest read-view-dependencies-projects-the-declared-subscription-sites
  (testing "SITES, not reads: one entry per lexical `v/sub`, read from the
            manifest and so available before anything mounts."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The two SITES
    ;; and their `:sid` / `:path` are manifest facts, asserted in both
    ;; postures by `the-census-manifest-is-the-substrate-the-three-reads-
    ;; project`; `:dynamic?` is minted by this read and gated with it.
    (when interop/debug-enabled?
      (let [r (tool/read-view-dependencies basket-id)]
        (is (= :view-dependencies (:read r)))
        (is (= 2 (count (:subscriptions r)))
            "exactly the two `v/sub` sites the body carries")
        (doseq [site (:subscriptions r)]
          (is (string? (:sid site)) "under the compiler-minted stable site id")
          (is (vector? (:path site)) "with its deterministic occurrence coordinate")
          (is (contains? site :dynamic?)
              "and its query-shape honesty stated on the entry, never left to be
               inferred from whether :query happens to be present"))))))

(deftest a-literal-query-is-projected-as-the-value-it-is
  (testing "A fully-literal query IS the authored runtime shape, so it is safe
            to show verbatim."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [sites   (:subscriptions (tool/read-view-dependencies basket-id))
            literal (first (remove :dynamic? sites))]
        (is (some? literal) "the body carries a literal-query site")
        (is (= [:basket/total] (:query literal))
            "shown as data, because that is what it is")))))

(deftest a-captured-local-is-dynamic-and-shows-only-what-is-known
  (testing "`(v/sub [:basket/line row-id])` closes over a template local, so its
            query does not exist until the view renders. Showing the form as
            though it were the query would be handing a reader source code where
            they asked for data — but the HEAD is genuinely known, so it is
            still shown."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [sites   (:subscriptions (tool/read-view-dependencies basket-id))
            dynamic (first (filter :dynamic? sites))]
        (is (some? dynamic) "the body carries a captured-local site")
        (is (not (contains? dynamic :query))
            "no :query is offered, because there is no query yet")
        (is (= :basket/line (:query-id dynamic))
            "the literal query-id IS known and is shown — absence is reported
             where it exists, not spread over the parts that are certain")))))

(deftest the-literal-dynamic-split-is-a-discrimination
  (testing "Non-vacuity for the two rows above: the census carries one of each,
            so a projection that answered `:dynamic? true` to everything — or
            `false` to everything — cannot pass both."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [sites (:subscriptions (tool/read-view-dependencies basket-id))]
        (is (= #{true false} (set (map :dynamic? sites))))))))

(deftest an-interpreted-declaration-has-no-dependency-roster-to-claim
  (testing "Same honesty as the manifest read: an empty roster, and the reason
            it is empty, rather than an empty roster presented as a survey."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). `(= [] …)` over
    ;; a read that answered nil is the vacuous negative, so it travels inside.
    (when interop/debug-enabled?
      (let [r (tool/read-view-dependencies plain-id)]
        (is (= [] (:subscriptions r)))
        (is (false? (:complete? r)))
        (is (= :no-static-analysis (:reason (:loss r))))))))

;; ---------------------------------------------------------------------------
;; read-view-event-sites — complete roster, narrower entries, said so
;; ---------------------------------------------------------------------------

(deftest read-view-event-sites-projects-every-declared-event-site
  (testing "The ROSTER is complete and lossless: every event site the grammar
            can see is here."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The four sites
    ;; are a manifest fact and are counted in both postures by
    ;; `the-census-manifest-is-the-substrate-the-three-reads-project`.
    (when interop/debug-enabled?
      (let [r (tool/read-view-event-sites basket-id)]
        (is (= :view-event-sites (:read r)))
        (is (= 4 (count (:event-sites r)))
            "exactly the four event sites the body carries — three `:on-click`
             handlers and one committed callback at the child's prop")
        (is (true? (:complete? r)))
        (is (nil? (:loss r)))))))

(deftest an-event-site-states-the-closed-set-of-facts-it-carries
  (testing "The analyzer records `:prop`, `:classification`, `:serializable?`,
            `:sync?` and `:handler` per site, and the manifest now PUBLISHES all
            five (rf2-z0blg) — so a row says what a view dispatches, not only
            where from. `:site-facts` names the closed set of facts a row states,
            with `:event-id` beside `:handler` for the honesty split.

            Asserted as an EQUALITY against the entries themselves, so neither
            side can drift: a manifest that stopped publishing a fact reds here,
            and so does one that starts publishing a fact this set does not
            name."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). `:site-facts`
    ;; and `:event-id` are minted by this read; the five facts the MANIFEST
    ;; publishes are asserted in both postures by
    ;; `the-census-manifest-is-the-substrate-the-three-reads-project`, which
    ;; is the half that would red if a manifest stopped publishing one.
    (when interop/debug-enabled?
      (let [r (tool/read-view-event-sites basket-id)]
        (is (= #{:sid :source-coord :path :prop :classification :serializable?
                 :sync? :handler :event-id}
               (:site-facts r)))
        (doseq [site (:event-sites r)]
          (is (= (:site-facts r) (set (keys site)))
              "every entry states exactly the facts the read says it states"))))))

(deftest a-literal-handler-is-projected-as-the-event-vector-it-is
  (testing "A fully-literal handler IS the vector that will dispatch, so it is
            safe to show verbatim — the same rule a fully-literal query gets."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [sites (:event-sites (tool/read-view-event-sites basket-id))
            site  (first (filter #(= :basket/clear (:event-id %)) sites))]
        (is (some? site) "the body carries a literal-handler site")
        (is (= [:basket/clear] (:handler site))
            "shown as data, because that is what it is")
        (is (= :on-click (:prop site)) "under the prop the author wrote it at")
        (is (= :vector (:classification site)))
        (is (true? (:serializable? site)))
        (is (false? (:sync? site))
            "a `:button` `:on-click` is not the controlled-input synchronous
             door — the fact is STATED, not left to be assumed from absence")))))

(deftest a-captured-local-in-an-event-vector-shows-only-what-is-known
  (testing "`[:basket/remove row-id]` closes over a template local, so the vector
            that will dispatch does not exist until the view renders. Showing the
            form would be handing a reader source code where they asked for data
            — but the event-id is genuinely known, so it is still shown."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [sites (:event-sites (tool/read-view-event-sites basket-id))
            site  (first (filter #(= :basket/remove (:event-id %)) sites))]
        (is (some? site) "the body carries a captured-local handler site")
        (is (= :opaque (:handler site))
            "no handler value is offered, because there is no event vector yet")
        (is (= :vector (:classification site))
            "and the classification keeps it apart from a callback body: this IS
             an event vector, with one argument that is not known yet")))))

(deftest an-options-map-handler-carries-the-vector-it-wraps
  (testing "The listener OPTIONS map is the other spelling that carries an event
            vector. A fully-literal one is projected verbatim — options included,
            because `:prevent-default` is part of what the site DOES — and the
            `:event-id` is read from the vector the map wraps rather than from
            the map's own keys, which have no head to name."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [r     (tool/read-view-event-sites options-id)
            sites (:event-sites r)
            site  (first sites)]
        (is (= 1 (count sites)) "one event site, hand-countable from the body")
        (is (= :options (:classification site)))
        (is (= {:event [:basket/print] :prevent-default true} (:handler site))
            "the authored options map, verbatim")
        (is (= :basket/print (:event-id site))
            "read from the wrapped vector, not from the map")
        (is (true? (:serializable? site)))
        (is (= (:site-facts r) (set (keys site)))
            "and the closed key set holds over this classification too")))))

(deftest an-opaque-callback-claims-nothing-about-its-body
  (testing "A `v/event` body is CODE. It carries no event vector, so there is no
            event-id either — and that absence is STATED rather than left as a
            missing key a reader has to interpret. It is also the component-prop
            arm, whose `:sync?` was absent rather than false until rf2-z0blg."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). `(nil?
    ;; (:event-id site))` travels inside with the rest: over a read that
    ;; answered nil, `site` is nil and every key of it is too.
    (when interop/debug-enabled?
      (let [sites (:event-sites (tool/read-view-event-sites basket-id))
            site  (first (filter #(= :ui-event (:classification %)) sites))]
        (is (some? site) "the body mounts a child with a committed callback prop")
        (is (= :on-remove (:prop site)))
        (is (= :opaque (:handler site)))
        (is (nil? (:event-id site)) "no event vector, so no head to name")
        (is (false? (:serializable? site)))
        (is (false? (:sync? site))
            "a component prop is not a DOM controlled-input door site — false is
             the fact, and stating it is what makes the row's key set whole")))))

(deftest the-handler-honesty-split-is-a-discrimination
  (testing "Non-vacuity for the three rows above: the census carries one site of
            each class, so a projection that answered `:opaque` to everything —
            or showed every recorded handler verbatim — cannot pass all three."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring). The CENSUS this
    ;; discriminates over is a manifest fact and is counted in both postures
    ;; by `the-census-manifest-is-the-substrate-the-three-reads-project`; the
    ;; honesty projection over it is this read's, and gated with it.
    (when interop/debug-enabled?
      (let [sites (:event-sites (tool/read-view-event-sites basket-id))]
        (is (= 2 (count (remove #(= :opaque (:handler %)) sites)))
            "two literal handlers, projected verbatim")
        (is (= 1 (count (filter #(and (= :opaque (:handler %)) (some? (:event-id %)))
                                sites)))
            "one dynamic event vector — opaque handler, known event-id")
        (is (= 1 (count (filter #(and (= :opaque (:handler %)) (nil? (:event-id %)))
                                sites)))
            "and one callback body — opaque handler, and no event-id to know")))))

(deftest an-interpreted-declaration-has-no-event-roster-to-claim
  (testing "The third read's honesty arm, asserted rather than assumed to
            follow from its siblings'."
    ;; rf2-74a89 — dev-instrumentation arm (see ns docstring).
    (when interop/debug-enabled?
      (let [r (tool/read-view-event-sites plain-id)]
        (is (= [] (:event-sites r)))
        (is (= :opaque (:basis r)))
        (is (= :unknown (:dropped (:loss r))))))))

;; ---------------------------------------------------------------------------
;; rf2-74a89 — the SUBSTRATE the three reads project, and what survives the gate
;; ---------------------------------------------------------------------------

(deftest the-census-manifest-is-the-substrate-the-three-reads-project
  (testing "`v/manifest` is the total, value-taking read — the one read
            `tool.cljc` exempts from the dev gate — and it is byte-identical in
            both postures, because the analyzer ran at macro expansion and its
            rosters are compile-time facts. Every id-taking read above is a
            PROJECTION of this value, so this is where the census itself is
            asserted: two subscription sites, four event sites, and the five
            per-site facts rf2-z0blg made the manifest publish.

            Which makes it the half that reds when a manifest STOPS publishing
            a fact — the failure the gated `:site-facts` equality is there to
            catch in dev, and could not catch in a production build. Asserted
            as key-set equalities for the same reason it is asserted there: so
            neither side can drift into decoration."
    (let [m (v/manifest basket)]
      (is (= 2 (count (:subscriptions m)))
          "exactly the two `v/sub` sites the body carries")
      (is (= 4 (count (:events m)))
          "and the four event sites — three `:on-click` handlers and one
           committed callback at the child's prop")
      (doseq [site (:subscriptions m)]
        (is (= #{:sid :query :source-coord :path} (set (keys site)))
            "a subscription site states exactly the facts the analyzer knows"))
      (doseq [site (:events m)]
        (is (= #{:sid :source-coord :path :prop :classification :serializable?
                 :sync? :handler}
               (set (keys site)))
            "and an event site the five rf2-z0blg made it publish, beside its
             site id, coordinate and path"))
      (is (= #{:vector :ui-event} (set (map :classification (:events m))))
          "the census really does carry more than one class, so the honesty
           split the gated read applies over it is a discrimination")
      (is (= #{true false} (set (map :serializable? (:events m))))
          "and more than one serializability, for the same reason"))
    (is (= 1 (count (:events (v/manifest print-button))))
        "the options-map spelling is one site, hand-countable from the body")
    (is (nil? (v/manifest plain-list))
        "and an INTERPRETED declaration carries no manifest at all — the fact
         `an-interpreted-declaration-reports-the-absence-of-analysis` reports
         through the gated read, stated here at its source")))

(deftest neither-the-index-nor-its-reads-survive-the-production-gate
  (testing "The elision as a positive fact, witnessed through the REAL
            load-time gate rather than a `with-redefs` rebind, which cannot
            reach one (rf2-9c2jf).

            Asked about ids that are genuinely DECLARED in this build — and
            that nothing in this namespace registers explicitly, so the answer
            is the gate's and not a test's — the index answers nil and all
            three reads answer nil. That is what makes
            `an-undeclared-id-answers-nil-from-every-read` a dev-arm row: under
            the gate every id answers nil, so nil says nothing about the id.

            Under the gate this is also the whole of the bundle-size claim the
            expansion test makes structurally: an index that held a row per
            DECLARATION in a production build would keep every descriptor —
            manifest, render fn and all — reachable from a live registry."
    (if interop/debug-enabled?
      (do
        (is (contains? (registry/view-ids) plain-id)
            "dev: a declared view is in the index")
        (is (some? (tool/read-view-manifest options-id))
            "and the reads answer for it"))
      (do
        (is (nil? (registry/lookup plain-id))
            "production: a DECLARED view does not resolve — the gated
             registration call site never ran")
        (is (not (contains? (registry/view-ids) options-id))
            "nor is it in the roster a sweep enumerates")
        (doseq [read-fn [tool/read-view-manifest
                         tool/read-view-dependencies
                         tool/read-view-event-sites]]
          (is (nil? (read-fn options-id))
              "and every read is inert for it — nil, never an empty roster
               wearing a completeness claim"))))))

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
