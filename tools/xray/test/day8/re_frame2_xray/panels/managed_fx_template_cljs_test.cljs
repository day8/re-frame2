(ns day8.re-frame2-xray.panels.managed-fx-template-cljs-test
  "Render tests for the managed-fx wire-boundary diff template.

  Pure hiccup; the test asserts the structural shape of the panel
  per-surface without booting a substrate. The data-testid attributes
  the template carries make the assertions deterministic without DOM
  introspection."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [reagent.core :as r]
            ;; Fresco's own head classifier and key reader are the instruments
            ;; for the HD-016 and key rows.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.panels.managed-fx-template :as template]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]))

;; ---- fixture record builder --------------------------------------------

(defn- record
  [{:keys [surface fx-id status http-status wire handler paths]}]
  {:surface         surface
   :fx-id           fx-id
   :req             {:method :get :url "/x"}
   :wire            wire
   :res             {:ok true}
   :handler         handler
   :status          status
   :phase           :completed
   :correlation-id  :corr-1
   :cancel-cause    nil
   :http-status     http-status
   :duration-ms     250
   :failure         nil
   :paths-touched   (or paths [])
   :origin-event-id 99
   :dispatch-id     7
   :frame           :rf/default
   :overridden?     false})

;; ---- recursive hiccup walker ------------------------------------------

(defn- testids
  "Collect every :data-testid in a hiccup tree."
  [node]
  (cond
    (and (vector? node) (map? (second node)))
    (let [attrs (second node)
          tid   (:data-testid attrs)
          kids  (drop 2 node)]
      (concat (when tid [tid])
              (mapcat testids kids)))

    (vector? node)
    (mapcat testids (drop 1 node))

    (seq? node)
    (mapcat testids node)

    :else []))

;; ---- per-surface sections ----------------------------------------------

(deftest record-panel-http-smoke
  (testing "An unjoined HTTP record draws REQUEST and REPLY TARGET and NOTHING
            that would describe an outcome: the issuing bundle carries one HTTP
            fact, so a section drawn over nothing could only mislead."
    (let [r   (assoc (record {:surface :http :fx-id :rf.http/managed
                              :status :issued
                              :handler [:user/loaded]})
                     :res nil)
          ids (set (testids (template/record-panel r)))]
      (is (contains? ids "rf-xray-managed-fx-section-request"))
      (is (not (contains? ids "rf-xray-managed-fx-section-wire")))
      (is (not (contains? ids "rf-xray-managed-fx-section-response")))
      (is (not (contains? ids "rf-xray-managed-fx-section-app-db"))))))

;; ---- React keys reaching the renderer ------------------------------------

(defn- react-key
  "The key REACT actually receives for one hiccup node, through Reagent's own
  `key-from-vec` (`(meta node)` would read nil whether or not a key reaches React)."
  [node]
  (.-key (r/as-element node)))

(defn- record-panel-nodes
  "The per-record panels `records-list` emits, taken from the RAW tree."
  [out]
  (vec (nth out 3)))

(deftest records-list-keys-reach-react
  (testing "`^{:key …}` reader meta on the `(record-panel …)` CALL form would
            attach to the source list and React would receive no key. The key
            rides the `:section` attribute map, which Reagent reads via props
            and Fresco's codec reads as a literal `:key`."
    (let [recs [(record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
                (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
          kids (record-panel-nodes (template/records-list recs))
          ks   (mapv react-key kids)]
      (is (= [":http-99-:rf.http/managed" ":flow-99-:rf.fx/reg-flow"] ks))
      (is (= ks (mapv #(:key (second %)) kids))))))

;; ---- hiccup heads ----------------------------------------------------------

(defn- hiccup-vectors
  "Every hiccup vector in `node`, root included, walked structurally rather
  than through `rf.test-helpers/expand-tree`, which rebuilds nested vectors."
  [node]
  (cond
    (vector? node) (cons node (mapcat hiccup-vectors (rest node)))
    (seq? node)    (mapcat hiccup-vectors node)
    :else          nil))

(defn- hiccup-heads [node]
  (map first (hiccup-vectors node)))

;; ---- "app-db wasn't updated" highlight: OK status + empty paths-touched ----

(defn- visible-text
  "Walk a hiccup tree and concatenate every string node."
  [node]
  (let [acc  (atom [])
        walk (fn walk [n]
               (cond
                 (string? n) (swap! acc conj n)
                 (vector? n) (doseq [c (drop 1 n)] (walk c))
                 (seq? n)    (doseq [c n] (walk c))))]
    (walk node)
    (apply str @acc)))

(defn- tooltip-text
  "Walk a hiccup tree and concatenate every :title / :aria-label /
  :data-tooltip attribute value."
  [node]
  (let [acc  (atom [])
        walk (fn walk [n]
               (cond
                 (and (vector? n) (map? (second n)))
                 (let [attrs (second n)]
                   (doseq [k [:title :aria-label :data-tooltip]]
                     (when-let [v (get attrs k)]
                       (swap! acc conj v)))
                   (doseq [c (drop 2 n)] (walk c)))

                 (vector? n) (doseq [c (drop 1 n)] (walk c))
                 (seq? n)    (doseq [c n] (walk c))))]
    (walk node)
    (str/join " " @acc)))

(deftest app-db-section-never-warns
  (testing "There is no amber 'app-db wasn't updated' warning: production wires
            no diff feed, so it would fire on every successful record."
    (let [measured-empty (record {:surface :websocket :fx-id :rf.ws/connect
                                  :status :ok :paths []})
          combined       (visible-text (template/record-panel measured-empty))]
      (is (not (str/includes? combined "app-db wasn't updated")))
      (is (str/includes? combined "no app-db changes in this event-bundle")
          "measured-and-empty says so, plainly")))

  (testing "An UNTRACKED record — `:paths-touched` nil, which is what
            production produces — says it is untracked rather than claiming
            nothing changed."
    (let [untracked (assoc (record {:surface :websocket :fx-id :rf.ws/connect
                                    :status :ok})
                           :paths-touched nil)
          combined  (visible-text (template/record-panel untracked))]
      (is (str/includes? combined "not tracked"))
      (is (not (str/includes? combined "app-db wasn't updated")))
      (is (not (str/includes? combined "no app-db changes in this event-bundle"))
          "untracked must not read as measured-and-empty"))))

;; ---- chrome leak guard: no bead IDs / spec citations in user-facing text ----
;;
;; Per the silent-by-default policy (Conventions.md §Silent-by-default), no
;; internal reference may appear in rendered text or tooltips.

(def ^:private internal-ref-patterns
  [[#"rf2-[a-z0-9]+"  "bead id"]
   [#"F\.\d"          "F-code"]
   [#"Spec \d"        "Spec citation"]
   [#"spec/0\d\d"     "spec-path citation"]])

(defn- assert-no-internal-refs!
  [label text]
  (doseq [[pat kind] internal-ref-patterns]
    (is (not (re-find pat text))
        (str label " leaks an internal " kind ": " (pr-str (re-find pat text))))))

;; ---- section disclosure --------------------------------------------------

(def ^:private disclosure-record
  "A WEBSOCKET record, because every disclosure row needs all FIVE sections to
  exist and an HTTP record draws two."
  (record {:surface :websocket :fx-id :rf.ws/connect
           :status  :ok
           :handler [:user/loaded {:id 1}]
           :paths   [[:users 42]]}))

(def ^:private section-ids [:request :wire :response :handler :app-db])

(defn- section-testid [section-id]
  (str "rf-xray-managed-fx-section-" (name section-id)))

(defn- body-shown?
  "Did `section-id`'s BODY reach the tree? `section-row` renders the body
  div under `(when expanded? …)`, so its testid is present exactly when the
  section is open."
  [tree section-id]
  (contains? (set (testids tree)) (str (section-testid section-id) "-body")))

(defn- tree-nodes
  "Every node in a hiccup tree, payload values included: it descends into map
  values, because a Fresco boundary's payload sits in its ONE props map."
  [node]
  (cond
    (vector? node) (cons node (mapcat tree-nodes node))
    (seq? node)    (cons node (mapcat tree-nodes node))
    (map? node)    (cons node (mapcat tree-nodes (vals node)))
    :else          [node]))

(defn- open-all
  "Override map opening every section of one record."
  [rec]
  (let [rk (h/record-key rec)]
    (into {} (for [s section-ids] [(h/expansion-key rk s) true]))))

(defn- noop-dispatch [_])

(deftest opening-a-collapsed-section-puts-its-payload-in-the-tree
  (testing "a section starts collapsed, the expansion state changes, and the
            PAYLOAD is present afterwards"
    (let [rk     (h/record-key disclosure-record)
          shut   (template/record-panel noop-dispatch nil disclosure-record)
          opened (template/record-panel noop-dispatch
                                        {(h/expansion-key rk :request) true}
                                        disclosure-record)
          req    {:method :get :url "/x"}]
      (is (not (some #{req} (tree-nodes shut)))
          "the request payload is absent while the section is shut")
      (is (some #{req} (tree-nodes opened))
          "the request payload reaches the tree once the section is open"))))

(deftest opening-response-on-a-failure-record-surfaces-the-failure-tags
  (testing "The failure branch of RESPONSE is a distinct payload — it is what
            the failure diagnostics are read from."
    (let [r      (assoc (record {:surface :http :fx-id :rf.http/managed
                                 :status :error :http-status 500})
                        :failure {:kind :rf.http/http-5xx
                                  :tags {:status 500 :body "oops"}})
          opened (template/record-panel noop-dispatch (open-all r) r)]
      (is (some #{{:status 500 :body "oops"}} (tree-nodes opened))))))

(deftest disclosure-state-is-per-record
  (testing "Opening REQUEST on one record must not open it on its siblings —
            which is why the override key carries the record identity."
    (let [a         (record {:surface :http :fx-id :rf.http/managed
                             :status :ok :http-status 200})
          b         (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})
          overrides {(h/expansion-key (h/record-key a) :request) true}]
      (is (body-shown? (template/record-panel noop-dispatch overrides a) :request))
      (is (not (body-shown? (template/record-panel noop-dispatch overrides b) :request))))))

(deftest a-section-header-dispatches-its-own-toggle
  (testing "The caller's half of `section-row`'s contract — 'Click-to-toggle
            wiring is the caller's responsibility'. Reading the handler off the
            tree and CALLING it is what makes this a gate: a deleted :on-click
            is nil, and calling nil throws."
    (let [seen (atom [])
          tree (template/record-panel #(swap! seen conj %) nil disclosure-record)
          node (first (filter #(= (str (section-testid :request) "-toggle")
                                  (:data-testid (second %)))
                              (hiccup-vectors tree)))]
      ((:on-click (second node)) nil)
      (is (= [[:rf.xray/managed-fx-toggle-section
               (h/record-key disclosure-record) :request]]
             @seen)))))

(deftest a-click-inside-an-open-payload-does-not-collapse-the-section
  (testing "The wrapper encloses the whole section, so without a stop on the
            body every click inside an opened payload would bubble to it and
            shut the section the operator just opened."
    (let [tree  (template/record-panel noop-dispatch (open-all disclosure-record)
                                       disclosure-record)
          inner (first (filter #(= "rf-xray-managed-fx-section-request-body-inner"
                                   (:data-testid (second %)))
                               (hiccup-vectors tree)))
          stopped (atom false)]
      ((:on-click (second inner))
       #js {:stopPropagation (fn [] (reset! stopped true))})
      (is (true? @stopped) "a click on the payload is stopped before the wrapper"))))

(deftest aria-expanded-agrees-with-the-rendered-body
  (testing "The wrapper announces the state the primitive paints, so the two
            cannot disagree — both are driven by the same resolved value."
    (let [rk   (h/record-key disclosure-record)
          tree (template/record-panel noop-dispatch
                                      {(h/expansion-key rk :request) true}
                                      disclosure-record)
          aria (fn [s] (->> (hiccup-vectors tree)
                            (filter #(= (str (section-testid s) "-toggle")
                                        (:data-testid (second %))))
                            first second :aria-expanded))]
      (is (= "true" (aria :request)))
      (is (= "true" (aria :wire)))
      (is (= "false" (aria :response)))
      (is (= "false" (aria :handler))))))

;; ---- HD-016: no plain fn in hiccup head position --------------------------
;;
;; Under Fresco a plain fn (or a `reg-view` head) in head position is a loud
;; error that escapes with no error boundary above this panel and unmounts the
;; Xray root. The four inspector sites sit in sections collapsed by default, so
;; the row grades the FULLY OPEN tree.

(deftest no-plain-fn-in-head-position-with-every-section-open
  (testing "every head in the fully open panel is one Fresco's codec accepts"
    (let [tree  (template/record-panel noop-dispatch (open-all disclosure-record)
                                       disclosure-record)
          kinds (frequencies (map rf.fresco.impl.codec/head-kind (hiccup-heads tree)))]
      (is (pos? (get kinds :boundary 0))
          "the inspector heads are in the tree, so the absence below is not vacuous")
      (is (zero? (get kinds :invalid 0))
          (str "every head in the rendered panel is one Fresco accepts — " kinds)))))

;; ---- the app-db path rows key through the ATTRIBUTE MAP ------------------
;;
;; Fresco's codec reads a literal `:key` from a native tag's attrs and reads
;; Clojure metadata nowhere, so `^{:key …}` reader meta would leave every row
;; keyless under Fresco while Reagent (meta THEN props) answered the same key
;; either way — which is why the instrument is the codec, not `r/as-element`.

(defn- emitted-key
  "The React key the FRESCO codec commits for one hiccup node. Head + attrs
  only, so a plain-fn head below the node cannot turn the key answer into an
  HD-016 throw."
  [node]
  (.-key (rf.fresco.impl.codec/as-element (subvec node 0 2))))

(deftest app-db-path-rows-key-through-the-fresco-codec
  (testing "the `[:li]` path rows write their key into the ATTRIBUTE MAP, the
            only spelling Fresco reads"
    (let [r   (record {:surface :websocket :fx-id :rf.ws/connect
                       :status :ok
                       :paths [[:users 42] [:users 43] [:session :token]]})
          lis (->> (tree-nodes (template/record-panel r))
                   (filter #(and (vector? %) (= :li (first %))))
                   vec)
          ks  (mapv emitted-key lis)]
      (is (every? some? ks) "every path row reaches React with a key")
      (is (= 3 (count (distinct ks))) "sibling keys are distinct"))))

(deftest user-facing-text-carries-no-internal-refs
  (let [recs [(record {:surface :websocket :fx-id :rf.ws/connect
                       :status :ok :paths []})    ;; measured-and-empty app-db slice
              (assoc (record {:surface :websocket :fx-id :rf.ws/connect
                              :status :ok})
                     :paths-touched nil)          ;; untracked app-db slice
              (-> (record {:surface :http :fx-id :rf.http/managed :status :overridden})
                  (assoc :overridden? true :override-to :app/fake-http
                         :override-from :rf.http/managed))
              (-> (record {:surface :http :fx-id :rf.http/managed :status :cancelled})
                  (assoc :cancel-cause :upstream-cancelled))]]
    (doseq [r recs]
      (let [panel (template/record-panel r)
            label (str (:surface r) "/" (:status r))]
        (assert-no-internal-refs! (str "visible-text[" label "]") (visible-text panel))
        (assert-no-internal-refs! (str "tooltip-text[" label "]") (tooltip-text panel))))))

;; ---- the OVERRIDE marker -------------------------------------------------
;;
;; An override replaces the HANDLER, so the pill names the replacement and
;; claims nothing about I/O; the status beside it carries what the capture
;; evidences.

(defn- override-node [panel]
  (first (filter #(and (vector? %) (map? (second %))
                       (= "rf-xray-managed-fx-override" (:data-testid (second %))))
                 (hiccup-vectors panel))))

(deftest override-pill-names-the-replacement
  (testing "a redirected record draws OVERRIDE, its target in the tooltip"
    (let [pill (override-node
                 (template/record-panel
                   (assoc (record {:surface :http :fx-id :rf.http/managed :status :overridden})
                          :overridden? true :override-to :app/fake-http
                          :override-from :rf.http/managed)))]
      (is (= "OVERRIDE" (visible-text pill)))
      (is (str/includes? (:title (second pill)) ":app/fake-http"))))
  (testing "a function override says so; it rides beside ANY status, OK included"
    (let [pill (override-node
                 (template/record-panel
                   (assoc (record {:surface :http :fx-id :rf.http/managed :status :ok
                                   :http-status 200})
                          :overridden? true :override-to :re-frame.fx/fn-value
                          :override-from :rf.http/managed)))]
      (is (str/includes? (:title (second pill)) "with a function"))))
  (testing "CONTROL — an unmarked record draws no pill"
    (is (nil? (override-node
                (template/record-panel
                  (record {:surface :http :fx-id :rf.http/managed :status :issued})))))))

;; ---- the joined HTTP record ----------------------------------------------
;;
;; The join itself is pinned on producer captures in
;; `managed_fx_http_join_cljs_test`; these rows grade only the RENDERING of
;; the fields it fills.

(defn- joined-http-record [status extra]
  (merge (assoc (record {:surface :http :fx-id :rf.http/managed :status status})
                :res nil :duration-ms nil :completion :joined)
         extra))

(deftest joined-http-record-draws-what-the-join-found
  (testing "an ERROR after a retry: the http status, the attempt count, the
            ELAPSED label, WIRE and RESPONSE, and the reply link"
    (let [seen (atom [])
          r    (joined-http-record :error
                 {:http-status 500 :attempts 2 :duration-ms 850
                  :wire        {:phases [[:issued 0] [:elapsed 850]] :total-ms 850}
                  :res         {:kind :rf.http/http-5xx :status 500}
                  :failure     {:kind :rf.http/http-5xx :tags {:kind :rf.http/http-5xx :status 500}}
                  :reply-link  {:dispatch-id 41 :frame :rf/default}})
          tree (template/record-panel #(swap! seen conj %) nil r)
          ids  (set (testids tree))
          txt  (visible-text tree)
          link (first (filter #(= "rf-xray-managed-fx-reply-link" (:data-testid (second %)))
                              (hiccup-vectors tree)))]
      (is (str/includes? txt "ERROR 500"))
      (is (str/includes? txt "2 attempts"))
      (is (str/includes? txt "elapsed 850ms"))
      (is (contains? ids "rf-xray-managed-fx-section-wire") "an elapsed brings WIRE TIMING")
      (is (contains? ids "rf-xray-managed-fx-section-response"))
      ((:on-click (second link)) nil)
      (is (= [[:rf.xray/focus-event 41 :rf/default]] @seen)
          "the link focuses the REPLY bundle, never the issuing one")))

  (testing "CANCELLED and STALE are their own statuses, not ERROR"
    (let [c (joined-http-record :cancelled {:cancel-cause :actor-destroyed})
          s (joined-http-record :stale {:cancel-cause :rf.http/superseded})]
      (is (str/includes? (visible-text (template/record-panel c)) "CANCELLED"))
      (is (str/includes? (visible-text (template/record-panel s)) "STALE"))))

  (testing "an issued row with no terminal row says so"
    (let [r   (assoc (joined-http-record :issued {}) :completion :none)
          txt (visible-text (template/record-panel r))]
      (is (str/includes? txt "no completion in this capture"))))

  (testing "CONTROL — an unjoined record draws no completion note"
    (let [r   (assoc (joined-http-record :issued {}) :completion nil)
          ids (set (testids (template/record-panel r)))]
      (is (not (contains? ids "rf-xray-managed-fx-no-completion"))))))

;; ---- the per-mount qualifier ---------------------------------------------
;;
;; These rows grade the template's PURE COMPOSITION of the inspector ids. What
;; a shared identity actually breaks — two real mounts sharing one memoised ref
;; callback and ResizeObserver — needs a real React commit and lives in
;; `panels/managed_fx_mount_instance_id_dom_cljs_test`. `edn-widget/inspect-view`
;; builds BOTH the `:mount-id` and the `:panel-id` from the one node-key, so one
;; qualifier separates lifecycle, width and expansion together.

(defn- inspect-view-props
  "Every `edn-inspector-view` props map in a hiccup tree, in document order,
  identified by the `:mount-id` key the boundary REQUIRES."
  [node]
  (->> (hiccup-vectors node)
       (map second)
       (filter #(and (map? %) (contains? % :mount-id)))))

(defn- widget-mount-ids [node] (mapv :mount-id (inspect-view-props node)))
(defn- widget-panel-ids [node] (mapv #(get-in % [:opts :panel-id]) (inspect-view-props node)))

(deftest instance-token-normalises-and-refuses
  (testing "nil and blank name no instance; a keyword's NAMESPACE is part of
            the name; the fn is idempotent, so the bridge and the boundary can
            both call it; a shape that could not be stable across renders is refused"
    (is (nil? (template/instance-token nil)))
    (is (nil? (template/instance-token "")))
    (is (= "left/list" (template/instance-token :left/list))
        "the namespace survives — `:left/list` and `:right/list` are two
         instances, not one")
    (is (= "left/list" (template/instance-token (template/instance-token :left/list)))
        "idempotent: a non-blank string answers itself")
    (is (thrown? js/Error (template/instance-token {:a 1})))))

(deftest unnamed-record-panel-composes-the-ids-it-always-did
  (testing "a mount that names no instance composes the plain ids below, and
            those ids must be stable: they key the operator's expansion state
            and the measured column width."
    (let [tree (template/record-panel noop-dispatch (open-all disclosure-record)
                                      disclosure-record)
          rk   (h/record-key disclosure-record)]
      (is (= [(str "rf-xray-inspect-managed-fx/" rk "/req")
              (str "rf-xray-inspect-managed-fx/" rk "/res")
              (str "rf-xray-inspect-managed-fx/" rk "/handler")]
             (widget-mount-ids tree)))
      (is (= [(keyword (str "rf.xray.inspect/managed-fx/" rk "/req"))
              (keyword (str "rf.xray.inspect/managed-fx/" rk "/res"))
              (keyword (str "rf.xray.inspect/managed-fx/" rk "/handler"))]
             (widget-panel-ids tree))))))

(deftest a-named-record-panel-qualifies-both-widget-ids
  (testing "naming the mount splices the instance in after the panel's own
            prefix and before the record key; both ids move together because
            both are built from the one node-key"
    (let [expanded (open-all disclosure-record)
          plain    (template/record-panel noop-dispatch expanded nil disclosure-record)
          named    (template/record-panel noop-dispatch expanded "left" disclosure-record)]
      (is (= (mapv #(str "rf-xray-inspect-managed-fx/left/"
                         (subs % (count "rf-xray-inspect-managed-fx/")))
                   (widget-mount-ids plain))
             (widget-mount-ids named))
          (str "the named ids are the unnamed ids with the instance spliced "
               "in: " (pr-str (widget-mount-ids named))))
      (is (= (mapv #(keyword (str "rf.xray.inspect/managed-fx/left/"
                                  (subs (str %)
                                        (count ":rf.xray.inspect/managed-fx/"))))
                   (widget-panel-ids plain))
             (widget-panel-ids named))
          (str "and so are the panel-ids, which key expansion and zoom: "
               (pr-str (widget-panel-ids named)))))))

(deftest record-identity-and-disclosure-survive-the-qualifier
  (testing "the qualifier composes WITH `record-key`; it does not replace it,
            or naming a mount would remount every row and orphan the
            operator's open sections"
    (let [recs    [(record {:surface :http :fx-id :rf.http/managed
                            :status :ok :http-status 200})
                   (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
          plain   (template/records-list noop-dispatch nil nil recs)
          named   (template/records-list noop-dispatch nil "left" recs)
          keys-of #(mapv react-key (record-panel-nodes %))]
      (is (= (keys-of plain) (keys-of named))
          (str "the React keys are identical — " (pr-str (keys-of named))))
      (let [rec    (first recs)
            opened (template/record-panel noop-dispatch (open-all rec) "left" rec)]
        (is (body-shown? opened :request)
            "a `record-key`-keyed override opens a named mount's REQUEST —
             disclosure is deliberately shared between two lists of the same
             records")))))

(deftest records-list-threads-the-instance-to-every-record
  (testing "the 4-arity reaches every record, not just the first."
    (let [recs  [(record {:surface :http :fx-id :rf.http/managed
                          :status :ok :http-status 200})
                 (record {:surface :websocket :fx-id :rf.ws/connect :status :ok})]
          named (template/records-list noop-dispatch
                                       (into {} (mapcat open-all recs))
                                       "right" recs)
          ids   (widget-mount-ids named)]
      (is (every? #(str/starts-with? % "rf-xray-inspect-managed-fx/right/") ids)
          (str "every widget in the list carries the mount's name — "
               (pr-str ids)))
      (is (every? (fn [rec] (some #(str/includes? % (h/record-key rec)) ids))
                  recs)
          (str "and each record's own key is inside them, so the "
               "qualifier composed WITH `record-key` rather than replacing "
               "it — " (pr-str ids))))))
