(ns day8.re-frame2-xray.panels.hicasso-causal-native-island-dom-cljs-test
  "THE CAUSAL SLICE OVER A SUBJECT PAST THE FENCE (rf2-t2d3).

  `hicasso_causal_cljs_test` mounts interpreted Hicasso and nothing else:
  `rf.hicasso/defview` boundaries reading `rf.hicasso/sub`. Every link it evidences, and
  every link it labels `:host-opaque`, would read exactly the same on a
  repository with no native tier in it — which is what CHECKPOINT 3
  (`rf2-hic-038`, row 7) named when it scored the row's deciding
  evidence, *one causal trace with an opaque foreign subtree*, unmet.
  This namespace supplies the subject that was missing, and then says
  plainly what having it does and does not prove.

  ## The three rows, and the order they have to be read in

  | row | what it establishes |
  |---|---|
  | [[a-native-island-is-a-first-class-causal-subject]] | an `rf.hicasso.native/use-sub` read is a slice subject on the same four seams a boundary's read is: links 1-4 evidenced, and the advisor names AND times it |
  | [[the-inner-tree-is-opaque-and-a-foreign-subtree-contributes-nothing]] | neither island markup nor a rendered foreign subtree reaches ANY of the four reads — and the foreign subtree's absence is proved against a control showing it really rendered |
  | [[host-opacity-does-not-mean-a-foreign-subtree-was-crossed]] | THE REFUSAL. Links 5-7 are identical over this subject and over an interpreted-only one, so `:host-opaque` does not encode a crossing and cannot be read as one |

  ## Why the third row is the one that matters

  The bead asked for *a control distinguishing opaque-because-foreign
  from opaque-because-React-owns-paint*. There is none to build, and the
  reason is structural rather than unfinished: **the projection holds no
  tree**. `causal/slice` is a pure function of four evidence envelopes and
  a trace window, and not one of them carries a node, a child, an element
  or a component — `re-frame.hicasso.tool`'s own naming projection says so
  at the producer (`:basis :opaque`, and *the runtime mints no boundary
  identity and keeps no view registry*). A boundary is identified by the
  EDGE SET it holds. So there is no subtree for a label to attach to, and
  `causal/link-host` reads only the static `causal/host-opaque-links`
  roster: links 5, 6 and 7 are constants.

  That makes the honest reading of row 7's phrase the narrow one. Xray
  names and times the boundary and observes the island's reads — those
  are witnessed below, over a genuine raw-React island. What it does NOT
  do is report an opacity that is ABOUT a foreign subtree, and the third
  row asserts that in the only form the claim can take: identical output
  over two subjects that differ precisely in whether a foreign subtree
  was crossed, with a non-vacuity control proving the two slices are
  otherwise different slices.

  ## The subjects are real, and one of them is not ours at all

  The island is a raw React function component — `react/createElement`
  and `rf.hicasso.native/use-sub`, mounted through `rf.hicasso/defhost` — so its read is a real
  React hook against the collector's own entry cache. The foreign
  subtree is a plain React function component with its own `useState`,
  reached through the `[:>]` raw escape — *`defhost` with the declaration
  erased* (`impl/codec` §HD-011). Nothing in it is Hicasso's, which is
  what makes its absence from the rosters the fact this file needs.

  ## Browser lane

  Hooks need a fiber. Per `docs/design/hicasso/product/lanes/testing-xray.md`
  foreign regions are mounted-test territory and there is no fake hook
  dispatcher, ever — so every row here needs a real React DOM and the ns
  takes the `-dom-cljs-test` suffix that selects `:browser-test`. The
  `:node-test` build compiles it too (`cljs-test$` matches), where each row
  degrades to a STATED skip rather than to a false green.

  Normative owner: `tools/xray/spec/028-Hicasso-Advisor.md`."
  (:require [cljs.test :refer [deftest is testing use-fixtures async]]
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso-advisor :as advisor]
            [day8.re-frame2-xray.panels.hicasso-causal :as causal]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]
            [day8.re-frame2-xray.panels.hicasso-reads :as reads]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.native :as rf.hicasso.native]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]))

(def ^:private app-frame ::causal-native-app)

;; Registered ABOVE `use-fixtures`: the reset fixture captures its
;; baseline when the `use-fixtures` form is EVALUATED, so a registration
;; written below it is erased before the first row runs.

(rf/reg-sub ::island (fn [db _] (:island db)))
(rf/reg-sub ::shell  (fn [db _] (:shell db)))
(rf/reg-sub ::plain  (fn [db _] (:plain db)))

(rf/reg-event ::seed (fn [_ [_ db]] {:db db}))
(rf/reg-event ::bump
              (fn [{:keys [db]} _]
                {:db (-> db
                         (update :island inc)
                         (update :shell inc)
                         (update :plain inc))}))

;; ---------------------------------------------------------------------------
;; The subjects
;; ---------------------------------------------------------------------------

(defonce ^:private !foreign-runs (atom 0))

(defn- Foreign
  "A FOREIGN React component. Not a `defview`, not an island reading
  through `rf.hicasso.native/use-sub`, no `rf.hicasso/sub` — it reads nothing of the
  application's, and Hicasso knows only that React was handed a function.

  It holds its own `useState` so a row can prove it really rendered and
  really re-rendered, which is what turns its absence from every roster
  into evidence rather than into a mount that never happened."
  [^js _props]
  (swap! !foreign-runs inc)
  (let [[local set-local] (react/useState 0)]
    (react/createElement
      "div" #js {"className" "foreign" "data-testid" "foreign-root"}
      (react/createElement
        "span" #js {"className" "foreign-depth-1"}
        (react/createElement "b" #js {"className" "foreign-depth-2"}
                             (str "foreign-" local)))
      (react/createElement "button"
                           #js {"className" "foreign-nudge"
                                "onClick"   (fn [_] (set-local inc))}
                           "nudge"))))

(defn- island
  "PAST THE FENCE. A raw React function component reading a real
  subscription through a real hook, with its own nested React markup —
  the `react/createElement` tree below is what the tool tier must not be
  able to see."
  [^js _props]
  (let [v (rf.hicasso.native/use-sub [::island])]
    (react/createElement
      "div" #js {"className" "island" "data-testid" "island-root"}
      (react/createElement
        "span" #js {"className" "island-depth-1"}
        (react/createElement "b" #js {"className" "island-depth-2"} (str v))))))

(rf.hicasso/defhost island-host
  "The declared crossing to the island — `defhost` names it, and the
  island is React on the far side."
  island)

(rf.hicasso/defview crossing-boundary
  "One interpreted boundary whose subtree crosses the fence TWICE: a
  raw-React island below it, and a foreign React component beside that."
  [_]
  [:div.crossing
   [:u.shell (str (rf.hicasso/sub [::shell]))]
   [island-host {}]
   [:> Foreign]])

(rf.hicasso/defview interpreted-boundary
  "The arm with no native tier anywhere under it — the subject the landed
  causal slice already has, mounted here so the third row can compare
  against it on ONE runtime rather than across two files."
  [_]
  [:div.interpreted [:i.plain (str (rf.hicasso/sub [::plain]))]])

(rf.hicasso/defview page
  "Both arms on one page, so every reading below is taken from one
  runtime and one turn of the four rosters. Reads nothing itself, and so
  claims the census's empty edge set — which is the fourth row every
  count below expects."
  [_]
  [:div [crossing-boundary {}] [interpreted-boundary {}]])

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

;; The CORE fixture rather than `make-xray-runtime-fixture`, for
;; `:ambient-frame nil` alone: the Xray fixture does not thread it, and a
;; dynamic-var frame left in ambient scope would let an island that failed
;; to resolve its own React context answer from `:rf/default` instead —
;; which is a frame miss reading as a green row. Everything else that
;; fixture does is `reset-all!`, called here.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (reset! !foreign-runs 0)
                      (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "a native-tier causal subject needs a real React DOM — " why)))

(defn- wait-until!
  "Poll `pred?` on real timers. Answers a promise of true, or of false
  once `budget-ms` has elapsed.

  `useSyncExternalStore` calls `subscribe` from a passive effect React
  flushes AFTER the commit, so an island that has not reached it holds no
  cell and appears in no roster. A row that read the rosters on the line
  after `root!` would be reading an unsubscribed component, and would
  stay green through a hook that never subscribed at all."
  ([pred?] (wait-until! pred? 3000))
  ([pred? budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (cond
                     (pred?)                    (js/setTimeout (fn [] (resolve true)) 16)
                     (< deadline (js/Date.now)) (resolve false)
                     :else                      (js/setTimeout tick 4)))]
           (tick)))))))

(defonce ^:private !minted (atom []))

(defn- release-minted!
  "Release every root this row minted. Rides the single trailing step both
  arms reach, so a rejection cannot leave a live React root standing in
  the document for the next namespace to inherit."
  []
  (run! rf.hicasso.impl.mount/release! @!minted)
  (reset! !minted [])
  nil)

(defn- report-failure!
  "Report a rejection against `label`. It does NOT finish the row and it
  tears nothing down — the single trailing step owns both, for the arm
  that never received a handle as much as for the one that did."
  [label]
  (fn [e]
    (is false (str label " — " (.-message e)))
    nil))

(defn- mount-page!
  "Seat the frame, mount both arms, and answer a promise of the handle
  once THREE cells are held — the shell's, the island's and the
  interpreted arm's. Waiting on the cell table rather than on the DOM is
  the point: only a commit populates it, and the island's `subscribe`
  runs in a passive effect after that commit."
  []
  (rf/make-frame {:id app-frame})
  (rf/with-frame app-frame (rf/dispatch-sync [::seed {:island 1 :shell 10 :plain 100}]))
  (let [container (rf.hicasso.impl.mount/fresh-container!)
        handle    (rf.hicasso.impl.mount/root! container app-frame [page {}])]
    (swap! !minted conj handle)
    (-> (wait-until! #(= 3 (count (keys @rf.hicasso.impl.collector/!cells))))
        (.then (fn [ok?]
                 (when-not ok?
                   (throw (ex-info (str "expected three cells; have "
                                        (pr-str (keys @rf.hicasso.impl.collector/!cells)))
                                   {})))
                 handle)))))

(defn- interact!
  "One real dispatch through the real router, which moves app-db, wakes
  every cell that reads it, and lands in the frame's Spec 009 ring."
  []
  (rf/with-frame app-frame (rf/dispatch-sync [::bump]))
  (rf.hicasso.impl.mount/settle!)
  nil)

(defn- evidence! [] (reads/evidence))
(defn- windows! [e] (or (reads/trace-windows e) {}))

(defn- key-holding
  "The census key of the boundary whose read set is exactly `sub-ids` —
  the tool tier's own identity, which is the projected EDGE SET and never
  a name, because the runtime mints no boundary identity to be named.
  Each element is `[frame-id sub-id projected-query]`."
  [e sub-ids]
  (first (for [row  (get-in e [:mounted-boundaries :boundaries])
               :let [k (get-in row [:boundary :key])]
               :when (= (set sub-ids) (into #{} (map second) k))]
           k)))

(defn- slice-for [e w bk] (causal/slice {:envelopes e :windows w :boundary-key bk}))

(defn- link [s id] (first (filter #(= id (:id %)) (:links s))))

(defn- host-links
  "Links 5, 6 and 7, whole."
  [s]
  (mapv #(link s %) [:bodies-run :react-commit :paint]))

(defn- sub-ids-of
  "The sub-ids link 3 says moved at this boundary. Link 2's roster is the
  DISPATCH's — frame-wide, and the same for every boundary in one bundle
  — so it is link 3 that answers *which reads were this subject's*."
  [s]
  (into #{} (map :sub-id) (get-in (link s :values-changed) [:holds :latest-reads])))

(defn- deep-strings
  "Every string anywhere in `x`, plus every keyword and symbol printed —
  the haystack an opacity claim has to search. A projection that leaked a
  tag, a class or a component name would put it here."
  [x]
  (let [acc (volatile! [])]
    ((fn walk [v]
       (cond
         (string? v)  (vswap! acc conj v)
         (keyword? v) (vswap! acc conj (str v))
         (symbol? v)  (vswap! acc conj (str v))
         (map? v)     (do (run! walk (keys v)) (run! walk (vals v)))
         (coll? v)    (run! walk v)
         :else        nil))
     x)
    @acc))

(defn- mentions?
  [x needle]
  (boolean (some #(string/includes? % needle) (deep-strings x))))

;; ---------------------------------------------------------------------------
;; W1. An island's read is a slice subject on all four seams
;; ---------------------------------------------------------------------------

(deftest a-native-island-is-a-first-class-causal-subject
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (mount-page!)
          (.then
            (fn [handle]
              (interact!)
              (let [container (:container handle)
                    e         (evidence!)
                    w         (windows! e)
                    ik        (key-holding e #{::island})]

                (testing "POSITIVE CONTROL — the island rendered the value it read
                          through the hook, so the subject really exists"
                  (is (= "2" (some-> (.querySelector ^js container ".island-depth-2")
                                     .-textContent))))

                (testing "and it is a census row of its own: a ONE-read edge set,
                          which is what an island reading one key is"
                  (is (some? ik) "the island's read set is in the mounted census")
                  (is (= 1 (count ik))))

                (testing "the slice takes it as its subject and evidences links 1-4"
                  (let [s (slice-for e w ik)]
                    (is (= 7 (:total s)))
                    (doseq [id [:event :subs-recomputed :values-changed
                                :boundaries-notified]]
                      (is (true? (:evidenced? (link s id)))
                          (str id " must be evidenced over a native subject — the "
                               "four seams do not know hooks exist, and that is "
                               "the claim")))
                    (is (contains? (set (:holds (link s :subs-recomputed))) ::island)
                        "the dispatch's roster names the island's own subscription")
                    (is (= #{::island} (sub-ids-of s))
                        (str "and link 3 — the boundary-scoped one — says the read "
                             "that moved HERE was the island's, and only it"))
                    (is (seq (get-in (link s :boundaries-notified) [:holds :readers]))
                        "with the reverse edge naming a reader for it")

                    (testing "while 5-7 stay host-opaque, as they do everywhere"
                      (doseq [l (host-links s)]
                        (is (false? (:evidenced? l)))
                        (is (= :host-opaque (:basis l)))
                        (is (string? (:authority l)))))

                    (testing "and the envelope still refuses to claim the chain"
                      (is (false? (:complete? s)))
                      (is (= :uncorrelated (:reason (:loss s)))))))

                (testing "the advisor NAMES and TIMES the island — row 7's own two
                          verbs, over a subject past the fence"
                  (let [adv (advisor/advise e (advisor/sub-timing w))
                        row (first (filter #(= ik (get-in % [:boundary :key]))
                                           (:rows adv)))]
                    (is (some? row) "the island is ranked, not skipped")
                    (is (string? (:label row)) "NAMED — by the edge set it holds")
                    (is (number? (get-in row [:axes :time :ms]))
                        "TIMED — `:rf.sub/elapsed-ms` on the read it made")
                    (is (false? (get-in row [:advice :native?]))
                        (str "and the advisor still refuses the native ladder — an "
                             "island already IS that route, and nothing here "
                             "measured lowering, React or layout")))))))
          (.catch (report-failure! "native island as causal subject"))
          (.then (fn [_] (release-minted!) (done)))))))

;; ---------------------------------------------------------------------------
;; W2. The inner tree is opaque, and a foreign subtree contributes nothing
;; ---------------------------------------------------------------------------

(deftest the-inner-tree-is-opaque-and-a-foreign-subtree-contributes-nothing
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (mount-page!)
          (.then
            (fn [handle]
              (interact!)
              (let [container (:container handle)
                    e         (evidence!)
                    w         (windows! e)
                    ck        (key-holding e #{::shell})
                    s         (slice-for e w ck)]

                (testing "POSITIVE CONTROL — the foreign subtree really rendered,
                          and re-renders on React state the runtime knows nothing of"
                  (is (some? (.querySelector ^js container "[data-testid='foreign-root']")))
                  (is (= "foreign-0"
                         (.-textContent (.querySelector ^js container ".foreign-depth-2"))))
                  (let [before @!foreign-runs]
                    (.click (.querySelector ^js container ".foreign-nudge"))
                    (rf.hicasso.impl.mount/settle!)
                    (is (< before @!foreign-runs)
                        (str "it is a live React component with its own state, so "
                             "its absence from the rosters below is OPACITY and "
                             "not a mount that never happened"))))

                (testing "the boundary above both crossings is still named and evidenced"
                  (is (some? ck))
                  (is (true? (:evidenced? (link s :event))))
                  (is (= #{::shell} (sub-ids-of s))
                      (str "and what moved AT IT is its own read — the island's "
                           "belongs to the island's edge set, not to the boundary "
                           "that renders it")))

                (testing "and NOTHING of either subtree reaches any of the four reads"
                  (doseq [needle ["island-depth" "island-root" "foreign-depth"
                                  "foreign-root" "foreign-nudge" "Foreign"
                                  "createElement" "className"]]
                    (is (not (mentions? e needle))
                        (str "the evidence bundle must not carry " needle
                             " — the producer holds edge sets and epochs, never a node"))
                    (is (not (mentions? s needle))
                        (str "and neither must the slice carry " needle))))

                (testing "the foreign subtree claims no census row and no reverse edge"
                  (is (= 4 (count (get-in e [:mounted-boundaries :boundaries])))
                      (str "four read sets are held — the page's empty one, the "
                           "shell's, the island's and the interpreted arm's — and "
                           "the foreign component adds none, because it reads "
                           "nothing of the application's"))
                  (is (= 3 (count (get-in e [:read-attribution :edges])))
                      "and three cells, for the same reason"))

                (testing "the producer names VIEWS, never a tree — a row's :views
                          is declared names with source coordinates, or the
                          explicit unknown"
                  (doseq [row (get-in e [:mounted-boundaries :boundaries])]
                    (let [views (:views row)]
                      (is (or (hh/unknown? views)
                              (and (vector? views)
                                   (every? (fn [v] (and (string? (:view v))
                                                        (or (hh/unknown? (:source v))
                                                            (map? (:source v)))))
                                           views)))
                          (str "a boundary is identified by the edge set it holds "
                               "and named by the views that rendered it; there is "
                               "no tree here to be opaque ABOUT — got "
                               (pr-str views)))))))))
          (.catch (report-failure! "inner-tree opacity"))
          (.then (fn [_] (release-minted!) (done)))))))

;; ---------------------------------------------------------------------------
;; W3. THE REFUSAL — host opacity is not a crossing
;; ---------------------------------------------------------------------------

(deftest host-opacity-does-not-mean-a-foreign-subtree-was-crossed
  ;; The control the bead asked for, and its answer is a refusal. Two
  ;; slices on ONE runtime: one over a boundary whose subtree crosses the
  ;; fence twice, one over a boundary with no native tier under it at all.
  ;; If `:host-opaque` carried anything about a crossing, links 5-7 would
  ;; differ. They are identical, field for field — so the phrase "opaque
  ;; foreign subtree" cannot be read off this output, and a checklist row
  ;; deciding on it is deciding on something the projection never computes.
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (mount-page!)
          (.then
            (fn [_handle]
              (interact!)
              (let [e        (evidence!)
                    w        (windows! e)
                    crossing (slice-for e w (key-holding e #{::shell}))
                    plain    (slice-for e w (key-holding e #{::plain}))]

                (testing "NON-VACUITY — the two slices really are different slices"
                  (is (not= (get-in crossing [:scope :boundary])
                            (get-in plain [:scope :boundary])))
                  (is (= #{::shell} (sub-ids-of crossing)))
                  (is (= #{::plain} (sub-ids-of plain))
                      (str "their boundary-scoped links differ, so the equality "
                           "below is a fact about links 5-7 and not about the two "
                           "slices being one slice")))

                (testing "and yet links 5-7 are IDENTICAL across the crossing"
                  (is (= (host-links crossing) (host-links plain))
                      (str "`:host-opaque` means React owns commit and paint for "
                           "ANY boundary. It does not mean a foreign subtree was "
                           "crossed, and it never has — a native tier absent from "
                           "the repository entirely would produce these same three "
                           "links")))

                (testing "which is a property of the PROJECTION and not of this
                          run: `link-host` reads only the static roster"
                  (is (= 3 (count causal/host-opaque-links)))
                  (is (= (mapv :label causal/host-opaque-links)
                         (mapv :label (host-links crossing)))
                      (str "the three host links are CONSTANTS — no argument of "
                           "`slice` reaches them, so no subject can change them"))
                  (is (= (mapv :says causal/host-opaque-links)
                         (mapv :says (host-links plain)))
                      "including the prose, which names an authority per absence")))))
          (.catch (report-failure! "host opacity is not a crossing"))
          (.then (fn [_] (release-minted!) (done)))))))
