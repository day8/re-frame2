(ns re-frame.freehand.projection-roster-cljs-test
  "FH-EVENT-001, the two DEMONSTRATED-NEED members of the closed
  projection roster.

  `::v/value`, `::v/checked` and `::v/key` are the form-control reads the
  roster started with. `::v/scroll-top` and `::v/new-state` are here
  because two independent components bent their designs around their
  absence: a windowed table had to assemble its scroll offset inside a
  `v/event` body, and a top-layer overlay could not read a toggle's new
  state at all and counted reports instead. Both are the same shape as
  `::v/value` — one shallow scalar off the event target — and the roster
  stays CLOSED around them.

  What the rows below prove is the PROPERTY closedness buys, not merely
  that a keyword resolves:

    * the authored intent is DATA, so a tool and a test read it by
      equality — asserted against the `v/event` spelling, which records
      as the opaque marker and can be read by neither;
    * the two front ends agree, so a declaration cannot mean one thing
      interpreted and another under `{:compiled true}`;
    * and a toggle report carries its OWN state, so reconciling one is a
      function of what the platform said rather than of how many times
      it said something. That distinction is the whole bug: counting
      reports is right for one overlay and wrong for a nested pair.

  Runs on both hosts from one `.cljc`. The cross-host rows name
  `events/payload-map` explicitly — the structural host fires no native
  event, so a callback's payload IS the map a test supplies — and the
  ClojureScript-only reading of a live event object stays in
  `events-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.analyze-accept-cljs-test :refer [mk-env]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.events :as events]))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private table-key [:ledger :q3])

(defn- analyzed
  "Analyze `template` and answer its ONE event site from both directions —
  `:handler` as the AST carries it (what the emitters lower) and `:site` as
  the manifest records it (what a tool reads)."
  [template]
  (let [e   (mk-env)
        ast (ana/analyze e template)]
    {:handler (first (get-in ast [:props :events]))
     :site    (first (:events @(:sites e)))}))

(defn- fired
  "Commit one site carrying `intent` and fire it once per payload in
  `payloads`, answering the vector of events that reached dispatch.

  `events/payload-map` is named rather than defaulted because this row
  runs on BOTH hosts: the ClojureScript default reads a live event
  object, and a Clojure map is not one."
  [intent payloads]
  (let [seen  (atom [])
        owner (events/owner :acme/probe)
        cand  (events/candidate owner)
        proxy (events/site cand :probe/site intent events/payload-map)]
    (events/commit! cand (fn [ev] (swap! seen conj ev)))
    (doseq [p payloads] (proxy p))
    @seen))

;; ---------------------------------------------------------------------------
;; The intent is DATA — which is what the roster exists to keep true
;; ---------------------------------------------------------------------------

(deftest fh-event-001-the-two-new-members-keep-the-site-inspectable
  (testing "Per FH-EVENT-001: a site that projects a roster member
            records its intent VERBATIM — the manifest a tool reads
            carries the authored vector, so the component's intent is
            assertable by equality before anything mounts. The same two
            intents written through `v/event` record as the OPAQUE
            marker, which is exactly the cost admitting these members
            removes."
    (let [scroll '[:acme.table/scrolled [:ledger :q3] :re-frame.freehand/scroll-top]
          toggle '[:acme.menu/toggle-reported :format :re-frame.freehand/new-state]]
      (let [{:keys [handler site]} (analyzed '[:div {:on-scroll [:acme.table/scrolled
                                                                 [:ledger :q3]
                                                                 :re-frame.freehand/scroll-top]}])]
        (is (= scroll (:form handler)) "the scroll intent lowers to itself")
        (is (= scroll (:handler site)) "and the manifest records that data verbatim"))
      (let [{:keys [handler site]} (analyzed '[:div {:on-toggle [:acme.menu/toggle-reported
                                                                 :format
                                                                 :re-frame.freehand/new-state]}])]
        (is (= toggle (:form handler)) "the toggle intent lowers to itself")
        (is (= toggle (:handler site)) "and the manifest records that data verbatim")))
    (let [{:keys [site]} (analyzed '[:div {:on-scroll (event [e]
                                                        [:acme.table/scrolled
                                                         [:ledger :q3]
                                                         (.. e -target -scrollTop)])}])]
      (is (= :opaque (:handler site))
          "the v/event spelling of the SAME intent is opaque to the manifest"))
    (let [{:keys [site]} (analyzed '[:div {:on-toggle (event [e]
                                                        [:acme.menu/toggle-reported
                                                         :format
                                                         (.-newState e)])}])]
      (is (= :opaque (:handler site))
          "and so is the v/event spelling of the toggle intent"))))

(deftest fh-event-001-the-two-front-ends-agree-on-the-new-members
  (testing "Per FH-EVENT-001: the compiled lowering IS the interpreted
            site plan for both new members, so a declaration cannot mean
            one thing interpreted and another under `{:compiled true}`.
            The plan is compared as a VALUE — a lowering that produced a
            different but well-formed plan would render perfectly and be
            wrong."
    (doseq [[note template] [["scroll offset"
                              '[:div {:on-scroll [:acme.table/scrolled
                                                  [:ledger :q3]
                                                  :re-frame.freehand/scroll-top]}]]
                             ["toggle new state"
                              '[:div {:on-toggle [:acme.menu/toggle-reported
                                                  :format
                                                  :re-frame.freehand/new-state]}]]]]
      (let [authored (get-in template [1 (if (= "scroll offset" note) :on-scroll :on-toggle)])
            {:keys [handler]} (analyzed template)]
        (is (= (events/event-plan authored)
               (events/event-plan (:form handler)))
            (str note " — the compiled lowering is the interpreted plan"))
        (is (= :event-vector (:role (events/event-plan (:form handler))))
            (str note " — and it is the plain vector form, not a callback"))))))

;; ---------------------------------------------------------------------------
;; `::v/scroll-top` — the offset a windowed table windows on
;; ---------------------------------------------------------------------------

(deftest fh-event-001-a-scroll-offset-reaches-dispatch-as-ordinary-data
  (testing "Per FH-EVENT-001: a scroll site's authored vector materializes
            the live offset into its argument position, on the structural
            host, with no reader conditional and no host object anywhere
            near the intent. The OFFSET is the assertion: a projection
            that read the wrong scalar would still dispatch a well-formed
            vector."
    (is (= [[:acme.table/scrolled table-key 3200]]
           (fired [:acme.table/scrolled table-key :re-frame.freehand/scroll-top]
                  [{:re-frame.freehand/scroll-top 3200}]))
        "the offset the payload carried, at the position the author wrote")
    (is (= [[:acme.table/scrolled table-key 0]
            [:acme.table/scrolled table-key 3200]
            [:acme.table/scrolled table-key 640]]
           (fired [:acme.table/scrolled table-key :re-frame.freehand/scroll-top]
                  [{:re-frame.freehand/scroll-top 0}
                   {:re-frame.freehand/scroll-top 3200}
                   {:re-frame.freehand/scroll-top 640}]))
        "and each firing reads its OWN payload — the intent is a value the
         view holds, not a snapshot of one render")))

(deftest fh-event-001-a-callback-with-no-scroll-offset-dispatches-nothing
  (testing "Per FH-EVENT-001: the new members carry the same law as the
            first three — a site asking a callback that reports no scroll
            offset is a TYPED error and nothing is dispatched, rather
            than a `nil` argument reaching a handler that would window on
            it."
    (let [seen  (atom [])
          owner (events/owner :acme/probe)
          cand  (events/candidate owner)
          proxy (events/site cand :probe/site
                  [:acme.table/scrolled table-key :re-frame.freehand/scroll-top]
                  events/payload-map)]
      (events/commit! cand (fn [ev] (swap! seen conj ev)))
      (is (= :rf.error/view-missing-payload
             (conf/caught-id #(proxy {:re-frame.freehand/value "typed"})))
          "a callback offering a value but no offset")
      (is (= [] @seen) "and nothing is dispatched"))))

;; ---------------------------------------------------------------------------
;; `::v/new-state` — the toggle report that reconciles itself
;; ---------------------------------------------------------------------------
;;
;; The top-layer runtime tells an author to reconcile `:on-toggle` "with
;; ordinary event intent". Before `::v/new-state` there was no spelling
;; for the report's own state, so a library had to infer a dismissal by
;; COUNTING reports — correct for a single overlay, and unsound for a
;; nested pair, because opening a nested pair delivers more than two
;; reports for one opening (observed as :outer, :inner, :outer, with the
;; outer pair still open at the end of it).
;;
;; The two reducers below are the contrast, and they are deliberately
;; both here: a fix that merely stopped the counting library from closing
;; would be a fix to the library, and the defect is in the grammar.

(defn- state-keyed
  "Reconcile a transcript by reading each report's OWN state. The shape
  `::v/new-state` makes writable."
  [reports]
  (reduce (fn [open [which state]] (assoc open which (= "open" state)))
          {}
          reports))

(defn- counting
  "Reconcile a transcript by COUNTING reports per control and treating the
  second as the dismissal — the workaround a library is forced into when
  the report's state has no spelling."
  [reports]
  (:open (reduce (fn [acc [which _]]
                   (let [n (inc (get-in acc [:seen which] 0))]
                     (-> acc
                         (assoc-in [:seen which] n)
                         (assoc-in [:open which] (= 1 n)))))
                 {:seen {} :open {}}
                 reports)))

(defn- transcript
  "Fire an outer and a nested inner toggle site with `reports`, and answer
  the `[which state]` pairs that reached dispatch. The sites carry
  ORDINARY EVENT VECTORS — which is the claim — so the transcript is
  produced by the substrate rather than assumed."
  [reports]
  (let [seen  (atom [])
        owner (events/owner :acme/nested-menu)
        cand  (events/candidate owner)
        outer (events/site cand :outer
                [:acme.menu/toggle-reported :outer :re-frame.freehand/new-state]
                events/payload-map)
        inner (events/site cand :inner
                [:acme.menu/toggle-reported :inner :re-frame.freehand/new-state]
                events/payload-map)]
    (events/commit! cand (fn [ev] (swap! seen conj ev)))
    (doseq [[which state] reports]
      ((case which :outer outer :inner inner)
       {:re-frame.freehand/new-state state}))
    (mapv (fn [[_ which state]] [which state]) @seen)))

(deftest fh-event-001-a-toggle-report-carries-its-own-state
  (testing "Per FH-EVENT-001: `::v/new-state` puts the platform's own
            report INSIDE the dispatched vector, so the state is data on
            both hosts. Nested sites stay independent — each site
            projects its own report — which is what the counting
            workaround could not express."
    (is (= [[:outer "open"] [:inner "open"] [:outer "open"]]
           (transcript [[:outer "open"] [:inner "open"] [:outer "open"]]))
        "every report reaches dispatch carrying the state it reported")
    (is (= [[:acme.menu/toggle-reported :outer "closed"]]
           (fired [:acme.menu/toggle-reported :outer :re-frame.freehand/new-state]
                  [{:re-frame.freehand/new-state "closed"}]))
        "and a dismissal is the word the platform used, not an inference")))

(deftest fh-event-001-a-nested-pair-reconciles-where-counting-collapses-it
  (testing "Per FH-EVENT-001, and the defect that admitted this member:
            opening a NESTED PAIR delivers more than two toggle reports
            for one opening, and the pair is still open at the end of
            them. Reconciling on the report's own state leaves both
            halves open; COUNTING reports reads the outer's second report
            as a dismissal and collapses the pair. The outcome is
            asserted for both reducers over the same transcript, because
            'the pair survives' is only a claim if the alternative is
            shown to fail."
    (let [;; The order the mounted pilot observed, each report now
          ;; carrying the state the platform put on it.
          observed (transcript [[:outer "open"] [:inner "open"] [:outer "open"]])]
      (is (= {:outer true :inner true} (state-keyed observed))
          "state-keyed: the pair survives its own opening")
      (is (= {:outer false :inner true} (counting observed))
          "counting: the outer collapses on its second report"))
    (let [;; The other shape the same finding allows — the outer closed
          ;; and re-shown while the inner went up. The pair still ends
          ;; open, and counting is wrong about it just the same.
          reshown (transcript [[:outer "open"] [:inner "open"]
                               [:outer "closed"] [:outer "open"]])]
      (is (= {:outer true :inner true} (state-keyed reshown))
          "state-keyed: a close followed by a re-show ends open")
      (is (= {:outer false :inner true} (counting reshown))
          "counting: still collapsed, and now for the wrong report"))
    (let [;; NON-VACUITY for the reducer itself: a genuine dismissal
          ;; closes. A reconciler that never closed would pass both rows
          ;; above and be useless.
          dismissed (transcript [[:outer "open"] [:outer "closed"]])]
      (is (= {:outer false} (state-keyed dismissed))
          "state-keyed: the platform said closed, so the control closes")
      (is (= {:outer false} (counting dismissed))
          "and this is the single-overlay case the two reducers agree on"))))
