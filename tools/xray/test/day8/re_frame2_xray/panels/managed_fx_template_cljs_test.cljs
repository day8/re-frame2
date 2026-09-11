(ns day8.re-frame2-xray.panels.managed-fx-template-cljs-test
  "Smoke render tests for the managed-fx wire-boundary diff template
  (rf2-uyp86, parent rf2-5aw5v).

  Pure hiccup; the test asserts the structural shape of the panel
  per-surface without booting a substrate. The data-testid attributes
  the template carries make the assertions deterministic without DOM
  introspection."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [reagent.core :as r]
            ;; rf2-twil — the HD-016 row's positive control is the
            ;; renderer's own head classifier, so the codec is the
            ;; instrument (same reason `cancellation_cascade_cljs_test`
            ;; requires it).
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.views.edn-widget :as edn-widget]
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
   :stubbed?        false})

;; ---- recursive hiccup walker ------------------------------------------

(defn- testids
  "Collect every :data-testid in a hiccup tree. Used to assert the
  template's section structure exists without walking the DOM."
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

;; ---- per-surface smoke render ------------------------------------------

(deftest record-panel-http-smoke
  (let [r   (record {:surface :http :fx-id :rf.http/managed
                     :status :ok :http-status 200
                     :handler [:user/loaded]
                     :paths [[:users 42]]})
        out (template/record-panel r)
        ids (set (testids out))]
    (is (contains? ids "rf-xray-managed-fx-record-http-99"))
    (is (contains? ids "rf-xray-managed-fx-header-http"))
    (is (contains? ids "rf-xray-managed-fx-surface-http"))
    (is (contains? ids "rf-xray-managed-fx-status-ok"))
    (is (contains? ids "rf-xray-managed-fx-section-request"))
    (is (contains? ids "rf-xray-managed-fx-section-wire"))
    (is (contains? ids "rf-xray-managed-fx-section-response"))
    (is (contains? ids "rf-xray-managed-fx-section-handler"))
    (is (contains? ids "rf-xray-managed-fx-section-app-db"))))

(deftest record-panel-machine-invoke-smoke
  (let [r   (record {:surface :machine-invoke :fx-id :rf.machine/spawn
                     :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-xray-managed-fx-header-machine-invoke"))
    (is (contains? ids "rf-xray-managed-fx-surface-machine-invoke"))))

(deftest record-panel-ssr-fx-smoke
  (let [r   (record {:surface :ssr-fx :fx-id :rf.server/set-status
                     :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-xray-managed-fx-header-ssr-fx"))
    (is (contains? ids "rf-xray-managed-fx-surface-ssr-fx"))))

(deftest record-panel-flow-smoke
  (let [r   (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-xray-managed-fx-header-flow"))
    (is (contains? ids "rf-xray-managed-fx-surface-flow"))))

(deftest record-panel-websocket-smoke
  (let [r   (record {:surface :websocket :fx-id :rf.ws/connect :status :ok})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-xray-managed-fx-header-websocket"))
    (is (contains? ids "rf-xray-managed-fx-surface-websocket"))))

;; ---- error-status renders error styling -------------------------------

(deftest record-panel-error-surfaces-status-error-testid
  (let [r   (assoc (record {:surface :http :fx-id :rf.http/managed
                            :status :error :http-status 500})
                    :failure {:kind :rf.http/http-5xx
                              :tags {:status 500 :body "oops"}})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-xray-managed-fx-status-error"))))

;; ---- cross-link wiring -------------------------------------------------
;;
;; HANDLER DISPATCHED is collapsed by default; the focus button lives
;; inside the section body. The tests assert the structural surface
;; (section header is always rendered; body shows up once the section
;; is expanded). The button visibility itself rides on the panel's
;; default-collapse state and is exercised by the gallery
;; (panel-gallery isn't in scope for rf2-uyp86).

(deftest handler-section-header-present-when-handler-present
  (let [r   (record {:surface :http :fx-id :rf.http/managed
                     :status :ok :http-status 200
                     :handler [:user/loaded {:id 1}]})
        ids (set (testids (template/record-panel r)))]
    (is (contains? ids "rf-xray-managed-fx-section-handler"))
    (is (contains? ids "rf-xray-managed-fx-section-handler-header"))))

;; ---- records-list ------------------------------------------------------

(deftest records-list-renders-one-panel-per-record
  (let [recs [(record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
              (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
        out  (template/records-list recs)
        ids  (set (testids out))]
    (is (contains? ids "rf-xray-managed-fx-list"))
    (is (contains? ids "rf-xray-managed-fx-record-http-99"))
    (is (contains? ids "rf-xray-managed-fx-record-flow-99"))))

(deftest records-list-nil-for-empty-records
  (is (nil? (template/records-list []))))

;; ---- React keys reaching the renderer (rf2-hxfy) ------------------------

(defn- react-key
  "The key REACT actually receives for one hiccup node. `r/as-element`
  runs Reagent's own `key-from-vec` (meta first, then the props map),
  so this reads the rendered element rather than the authoring shape.

  Asserting on `(meta node)` instead would be a HOLLOW GATE: it reads
  nil both BEFORE and AFTER this repair, because the key now rides the
  attribute map — which is the whole point of rf2-hxfy."
  [node]
  (.-key (r/as-element node)))

(defn- record-panel-nodes
  "The per-record panels `records-list` emits, taken from the RAW tree.
  Walked structurally rather than through `rf.test-helpers/expand-tree`,
  which rebuilds nested vectors with `mapv` and would strip the very
  key-bearing shape under test."
  [out]
  (vec (nth out 3)))

(deftest records-list-keys-reach-react
  (testing "rf2-hxfy — `^{:key …}` reader meta on the `(record-panel …)`
            CALL form attached to the source list, so the returned vector
            carried no key and React received none (measured before the
            repair: `.-key` nil for every panel). The key now rides the
            `:section` attribute map, which Reagent reads via props and
            Fresco's codec reads as a literal `:key`."
    (let [recs [(record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
                (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
          kids (record-panel-nodes (template/records-list recs))
          ks   (mapv react-key kids)]
      (is (= 2 (count kids)))
      ;; The composed value is unchanged from the pre-repair call site:
      ;; surface "-" origin-event-id "-" fx-id.
      (is (= [":http-99-:rf.http/managed" ":flow-99-:rf.fx/reg-flow"] ks))
      (is (every? some? ks) "every panel reaches React with a key")
      (is (= 2 (count (distinct ks))) "sibling keys are distinct")
      ;; The contract surface stays observable in the hiccup itself.
      (is (= ks (mapv #(:key (second %)) kids))))))

(deftest records-list-keys-are-stable-across-renders
  (testing "rf2-hxfy — a key that changes value between renders is worse
            than no key, so the same record must key identically twice."
    (let [recs [(record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
                (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
          once  (mapv react-key (record-panel-nodes (template/records-list recs)))
          twice (mapv react-key (record-panel-nodes (template/records-list recs)))]
      ;; Guard the guard: `[nil nil]` is trivially stable, so assert
      ;; presence here too rather than letting this pass on absence.
      (is (every? some? once))
      (is (= once twice)))))

;; ---- HD-016: `edn/inspect` is CALLED, never a hiccup head (rf2-twil) -------
;;
;; `views/edn-widget/inspect` is a plain `defn`. Under Reagent a plain
;; function in hiccup head position is a form-1 component, so the four
;; `[edn/inspect …]` sites this file used to carry rendered happily; under
;; Fresco a plain function in head position is a LOUD ERROR by design
;; (HD-016), and on this panel's render path there is no error boundary
;; above it — the throw escapes and React unmounts the entire Xray root,
;; which presents as a panel that never appears rather than as an error.
;; That is the rf2-qhoj P1, and it was one token wide there too.
;;
;; The instrument is Fresco's OWN head classifier rather than a shape
;; assertion of ours, so the zero below is the renderer's answer and not
;; a claim about what we think the renderer would say.
;;
;; BOTH BLOCKING CLASSES ARE NOW CLEARED (rf2-fcy5). rf2-twil removed the
;; first — `inspect` is CALLED, never headed. The second was the head inside
;; the value it returns: `[ei/edn-inspector …]` is a `reg-view` head, which
;; the codec refuses for the same reason it refuses a plain `defn` —
;; `views/edn_inspector.cljs` says it outright, "Only `edn-inspector-view`
;; is a head in a Fresco body". That one could not be repaired until the
;; panel's own mount was a boundary, because a Fresco head in a Reagent
;; position is the mirror failure; `panels/ManagedFxList` is an
;; `rf.fresco/defview` now, so the four sites call `edn/inspect-view` and
;; the tree this file folds is codec-clean all the way down. The
;; every-section-open row below states that in the codec's own words.
;;
;; WHAT THIS ROW CANNOT DO, STATED PLAINLY BECAUSE THE FIRST DRAFT OF IT
;; LIED. It walks the DEFAULT tree, where three of the five sections are
;; shut. `theme/section/section-row` renders its body under `(when expanded?
;; …)`, so a collapsed body is computed (it is a positional argument) and
;; then DISCARDED — three of the four `edn/inspect` sites are therefore
;; absent from the tree this row walks, which is the blind spot all three
;; tiers of coverage shared. So a revert to `[edn/inspect …]` in one of those
;; three would NOT turn THIS row red, and it does not claim it would: the
;; absence assertion is a FLOOR, and the controls above it are what stop the
;; floor reading as a proof. Measured, not assumed — the draft that asserted
;; the returned inspector was present in the tree read zero, and that is how
;; the discard was found.
;;
;; THE GAP IS NOW COVERED ELSEWHERE IN THIS FILE. rf2-s6m6 wired the
;; disclosure, so the sections can be opened, and
;; `no-plain-fn-in-head-position-with-every-section-open` restates this floor
;; over the FULLY OPEN tree — where all four sites really are present, as it
;; asserts before reading the absence. Keep both: this row grades what the
;; operator sees on first paint, that one grades what a click reveals.
;;
;; THE PRIMITIVE IS NOT AT FAULT, AND SAYING SO MATTERS because the first
;; version of this comment blamed it. `section-row` is non-interactive BY
;; DESIGN — `theme/section.cljc` states the contract in terms: "No
;; interactivity. Click-to-toggle wiring is the caller's responsibility." —
;; and it is shared with `panels/fresco` and `panels/module_view`. The panel
;; simply passed literals and never did the caller's half; rf2-s6m6 did it,
;; in the panel, leaving the primitive untouched.

(defn- hiccup-vectors
  "Every hiccup vector in `node`, root included, walked structurally.
  Deliberately NOT through `rf.test-helpers/expand-tree`, which rebuilds
  nested vectors with `mapv` — it would substitute its own vectors for
  the ones under test and `identical?` below would answer about the
  rebuild rather than about the panel."
  [node]
  (cond
    (vector? node) (cons node (mapcat hiccup-vectors (rest node)))
    (seq? node)    (mapcat hiccup-vectors node)
    :else          nil))

(defn- hiccup-heads [node]
  (map first (hiccup-vectors node)))

(deftest no-plain-fn-sits-in-hiccup-head-position
  (testing "rf2-twil — a plain function in hiccup head position is a loud
            error under Fresco (HD-016) and the throw escapes with no error
            boundary above this render path. `edn/inspect` was this tree's
            only head-position user of the widget facade; it is called now.
            This row is the FLOOR for the panel's REACHABLE tree — see the
            section comment for what it deliberately does not claim."
    (let [r     (record {:surface     :http
                         :fx-id       :rf.http/managed
                         :status      :ok
                         :http-status 200
                         :handler     [:user/profile-loaded {:id 1}]})
          heads (hiccup-heads (template/record-panel r))]
      ;; Two controls, because the assertion below is an ABSENCE and an
      ;; absence is what a dead instrument also reports.
      (is (= :invalid (rf.fresco.impl.codec/head-kind edn-widget/inspect))
          "Fresco's own classifier grades the plain fn an invalid head")
      (is (< 20 (count heads))
          "the walker reached a populated tree, so a zero below means absence")
      ;; No plain function anywhere in head position — stated over the
      ;; whole reachable tree rather than named against `inspect` alone,
      ;; so a DIFFERENT facade helper (`ei/mini`, `edn/inspect-inline`)
      ;; put in head position here is caught too. That is the mistake
      ;; rf2-qhoj actually made, one file over.
      (is (empty? (filter fn? heads))
          "no plain function is in head position in the rendered panel"))))

;; ---- "app-db wasn't updated" highlight: OK status + empty paths-touched ----

(defn- visible-text
  "Walk a hiccup tree and concatenate every string node. Used to
  assert on the rendered prose without booting a DOM."
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
  :data-tooltip attribute value. Used to assert tooltips don't leak
  internal references (bead IDs, spec citations, F-codes)."
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

(deftest app-db-section-flags-empty-slice-on-ok-status
  (testing "When status is :ok but paths-touched is empty, the panel
            renders the 'app-db wasn't updated' warning instead of the
            bare '(no changes)' caption."
    (let [r        (record {:surface :http :fx-id :rf.http/managed
                            :status :ok :http-status 200
                            :paths []})
          combined (visible-text (template/record-panel r))]
      (is (str/includes? combined "app-db wasn't updated"))
      ;; silent-by-default: no internal F-code in user-visible prose
      (is (not (re-find #"F\.\d" combined))
          "user-visible warning text leaks an internal F-code"))))

;; ---- chrome leak guard: no bead IDs / spec citations in user-facing text ----
;;
;; Per rf2-6lp7k + the silent-by-default policy (Conventions.md
;; §Silent-by-default), no internal reference (bead IDs `rf2-*`,
;; F-codes `F.\d`, "Spec N" citations, "spec/0NN" paths) may appear
;; in user-facing chrome (rendered text or tooltips). These tests
;; render every surface variant + the canonical edge cases and
;; assert the negative.

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

;; ---- section disclosure (rf2-s6m6) ---------------------------------------
;;
;; The defect: all five `:expanded?` values were LITERALS, so REQUEST,
;; RESPONSE and HANDLER DISPATCHED drew a `▶` that nothing could operate and
;; their payloads never reached a rendered tree. `theme/section/section-row`
;; is not at fault and is unchanged — it is non-interactive by design and
;; shared with two other panels — so the state and the click are the panel's.

(def ^:private disclosure-record
  (record {:surface :http :fx-id :rf.http/managed
           :status  :ok   :http-status 200
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
  "Every node in a hiccup tree, payload values included. Walked structurally
  rather than through `rf.test-helpers/expand-tree`, which rebuilds nested
  vectors with `mapv` — that would substitute its own vectors for the ones
  under test, and strips reader metadata besides.

  rf2-fcy5 — IT DESCENDS INTO MAP VALUES, and that is what keeps \"payload
  values included\" true. A Reagent component took its value as a POSITIONAL
  argument (`[ei/edn-inspector v opts]`), so a vectors-and-seqs walk reached
  it; a Fresco boundary takes ONE PROPS MAP (`[ei/edn-inspector-view
  {:value v …}]`), so the payload now sits behind a map key. Without this
  arm the walk still returns the props map itself and simply never looks
  inside it — an absence that reads exactly like a payload the panel failed
  to render, which is the direction that matters here, since every caller
  below asserts PRESENCE. `body-shown?` and `testids` are unaffected: they
  are a different walker that reads the attribute map by position and never
  descends into one."
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

(deftest sections-paint-at-their-documented-defaults
  (testing "`record-panel`'s docstring promises WIRE + APP-DB SLICE TOUCHED
            open and REQUEST + RESPONSE + HANDLER DISPATCHED shut on first
            paint. A nil override map is that first-paint state.

            This row is a FLOOR for the defaults, not a gate for the repair —
            it passed before it too, because the pre-repair literals painted
            the same thing. The gate is the toggle rows below."
    (let [tree (template/record-panel disclosure-record)
          ids  (set (testids tree))]
      ;; every section draws its header, open or shut
      (doseq [s section-ids]
        (is (contains? ids (str (section-testid s) "-header"))
            (str (name s) " draws a header")))
      (is (not (body-shown? tree :request)))
      (is (not (body-shown? tree :response)))
      (is (not (body-shown? tree :handler)))
      (is (body-shown? tree :wire))
      (is (body-shown? tree :app-db)))))

(deftest opening-a-collapsed-section-puts-its-payload-in-the-tree
  (testing "rf2-s6m6 — the deliverable. A section starts collapsed, the
            expansion state changes, and the PAYLOAD is present afterwards.

            Asserting the body testid alone would be half a gate: the
            container can appear while the payload does not. Both are
            asserted, and both are asserted ABSENT first, so neither reads
            as present on a tree that never had it."
    (let [rk     (h/record-key disclosure-record)
          shut   (template/record-panel noop-dispatch nil disclosure-record)
          opened (template/record-panel noop-dispatch
                                        {(h/expansion-key rk :request) true}
                                        disclosure-record)
          req    {:method :get :url "/x"}]
      (is (not (body-shown? shut :request)))
      (is (not (some #{req} (tree-nodes shut)))
          "the request payload is absent while the section is shut")
      (is (body-shown? opened :request))
      (is (some #{req} (tree-nodes opened))
          "the request payload reaches the tree once the section is open")
      ;; the sibling sections are untouched by opening this one
      (is (not (body-shown? opened :response)))
      (is (not (body-shown? opened :handler))))))

(deftest opening-response-and-handler-puts-their-payloads-in-the-tree
  (testing "The other two sections the defect hid. HANDLER DISPATCHED also
            carries the '→ focus event ↗' cross-link button, which was
            unreachable for the same reason."
    (let [rk     (h/record-key disclosure-record)
          opened (template/record-panel noop-dispatch (open-all disclosure-record)
                                        disclosure-record)
          shut   (template/record-panel noop-dispatch nil disclosure-record)]
      (is (some #{{:ok true}} (tree-nodes opened))
          "the response payload reaches the tree")
      (is (not (some #{{:ok true}} (tree-nodes shut))))
      (is (some #{[:user/loaded {:id 1}]} (tree-nodes opened))
          "the dispatched handler vector reaches the tree")
      (is (not (some #{[:user/loaded {:id 1}]} (tree-nodes shut))))
      (is (contains? (set (testids opened)) "rf-xray-managed-fx-focus-handler")
          "the cross-link button is reachable once HANDLER DISPATCHED opens")
      (is (not (contains? (set (testids shut)) "rf-xray-managed-fx-focus-handler"))))))

(deftest opening-response-on-a-failure-record-surfaces-the-failure-tags
  (testing "The failure branch of RESPONSE is a distinct unreachable payload —
            it is what the F.3 / F.7 diagnostics are read from."
    (let [r      (assoc (record {:surface :http :fx-id :rf.http/managed
                                 :status :error :http-status 500})
                        :failure {:kind :rf.http/http-5xx
                                  :tags {:status 500 :body "oops"}})
          opened (template/record-panel noop-dispatch (open-all r) r)
          shut   (template/record-panel noop-dispatch nil r)]
      (is (some #{{:status 500 :body "oops"}} (tree-nodes opened)))
      (is (not (some #{{:status 500 :body "oops"}} (tree-nodes shut)))))))

(deftest a-default-open-section-can-be-shut
  (testing "The disclosure runs both ways — WIRE TIMING and APP-DB SLICE
            TOUCHED drew a `▼` that could not be closed, the same dead
            affordance in the opposite direction."
    (let [rk   (h/record-key disclosure-record)
          tree (template/record-panel noop-dispatch
                                      {(h/expansion-key rk :wire)   false
                                       (h/expansion-key rk :app-db) false}
                                      disclosure-record)]
      (is (not (body-shown? tree :wire)))
      (is (not (body-shown? tree :app-db)))
      ;; and the stored `false` did not leak onto the sections that share
      ;; the record key
      (is (not (body-shown? tree :request))))))

(deftest disclosure-state-is-per-record
  (testing "An event-bundle can carry several managed-fx records. Opening
            REQUEST on one must not open it on its siblings — which is why
            the override key carries the record identity and not just the
            section id."
    (let [a         (record {:surface :http :fx-id :rf.http/managed
                             :status :ok :http-status 200})
          b         (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})
          overrides {(h/expansion-key (h/record-key a) :request) true}]
      (is (not= (h/record-key a) (h/record-key b))
          "the two fixture records really do have distinct keys")
      (is (body-shown? (template/record-panel noop-dispatch overrides a) :request))
      (is (not (body-shown? (template/record-panel noop-dispatch overrides b) :request))))))

(deftest each-section-header-dispatches-its-own-toggle
  (testing "The caller's half of `section-row`'s contract — 'Click-to-toggle
            wiring is the caller's responsibility'. Each section's wrapper
            carries an :on-click dispatching the panel's toggle for
            [record-key section-id].

            Reading the handler off the tree and CALLING it is what makes
            this a gate: a deleted :on-click is nil, and calling nil throws."
    (doseq [s section-ids]
      (let [seen   (atom [])
            tree   (template/record-panel #(swap! seen conj %) nil disclosure-record)
            node   (first (filter #(= (str (section-testid s) "-toggle")
                                      (:data-testid (second %)))
                                  (hiccup-vectors tree)))]
        (is (some? node) (str (name s) " has a toggle wrapper"))
        ((:on-click (second node)) nil)
        (is (= [[:rf.xray/managed-fx-toggle-section
                 (h/record-key disclosure-record) s]]
               @seen)
            (str (name s) " dispatches its own toggle"))))))

(deftest a-click-inside-an-open-payload-does-not-collapse-the-section
  (testing "The wrapper has to enclose the whole section, because
            `section-row` renders its own header and there is no inner header
            node to hang the handler on. Without a stop on the body, every
            click inside an opened payload — the edn-inspector's chevrons,
            the '→ focus event ↗' button — would bubble to the wrapper and
            shut the section the operator just opened."
    (let [tree  (template/record-panel noop-dispatch (open-all disclosure-record)
                                       disclosure-record)
          inner (first (filter #(= "rf-xray-managed-fx-section-request-body-inner"
                                   (:data-testid (second %)))
                               (hiccup-vectors tree)))
          stopped (atom false)]
      (is (some? inner) "the open body carries the propagation stop")
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

;; ---- HD-016 on the tree the disclosure MAKES reachable (rf2-s6m6) ---------
;;
;; `no-plain-fn-sits-in-hiccup-head-position` above is a floor over the
;; DEFAULT tree, and its own comment says plainly what it cannot do: with
;; three sections shut, the four `edn/inspect` sites are constructed as
;; positional arguments and then discarded by `section-row`'s `(when
;; expanded? …)`, so they never appear in the tree it walks. Wiring the
;; disclosure is exactly what makes them reachable — the risk this bead
;; carries — so the same floor is restated over the FULLY OPEN tree, where
;; all four are really present.

(deftest no-plain-fn-in-head-position-with-every-section-open
  (testing "rf2-twil repaired four `edn/inspect` head-position sites that no
            tier of coverage could reach, because they sat inside collapsed
            sections. With every section open they are in the rendered tree,
            and a plain fn in head position is an HD-016 throw that escapes
            with no error boundary above this panel — a panel that never
            appears rather than an error (the rf2-qhoj shape)."
    (let [tree  (template/record-panel noop-dispatch (open-all disclosure-record)
                                       disclosure-record)
          heads (hiccup-heads tree)]
      ;; The tree really is open, so the absence below is not vacuous.
      (doseq [s section-ids]
        (is (body-shown? tree s) (str (name s) " is open")))
      ;; And the inspector call sites really are in it — this is the half the
      ;; default-tree row could not assert.
      (is (some #{{:method :get :url "/x"}} (tree-nodes tree)))
      (is (some #{[:user/loaded {:id 1}]} (tree-nodes tree)))
      ;; Controls, because the assertions below are ABSENCES.
      (is (= :invalid (rf.fresco.impl.codec/head-kind edn-widget/inspect))
          "Fresco's own classifier grades the plain fn an invalid head")
      (is (< 30 (count heads))
          "the walker reached a populated tree, so a zero below means absence")
      ;; rf2-fcy5 — THE DISCRIMINATOR IS THE CODEC, and it is the only
      ;; instrument that survives BOTH sides of this migration.
      ;;
      ;; Neither of the two obvious shapes does. `(empty? (filter fn? heads))`
      ;; — what the DEFAULT-tree row above can use, because the default tree
      ;; carries no inspector at all — is wrong here in the permissive
      ;; direction AND the strict one: a reg-view head IS a fn value on CLJS
      ;; (`build-frame-aware-view` returns `(with-meta (fn …) {:contextType …})`,
      ;; a `cljs.core.MetaFn`), and so is a Fresco boundary, by
      ;; `codec/boundary-head?`'s own definition. And `(some? (meta head))` —
      ;; what this row asserted while the heads were reg-views — reads NIL on
      ;; the correct code now: a boundary is a plain React function component
      ;; carrying a JS own-property and no Clojure metadata at all.
      ;;
      ;; `head-kind` answers both eras without a branch, because it is the
      ;; renderer's own question: a plain `defn` and a reg-view head both grade
      ;; `:invalid`, a boundary grades `:boundary`, a native tag `:tag`. A
      ;; revert of any of the four call sites from `edn/inspect-view` to
      ;; `edn/inspect` puts an `:invalid` head back in this tree and reds the
      ;; row — which is the whole point, since that revert is silent under
      ;; Reagent and unmounts the Xray root under Fresco.
      (is (= :invalid (rf.fresco.impl.codec/head-kind
                        (first (edn-widget/inspect {:probe 1} "probe"))))
          "the Reagent head these sites used to carry grades :invalid")
      (is (= :boundary (rf.fresco.impl.codec/head-kind
                         (first (edn-widget/inspect-view {:probe 1} "probe"))))
          "the Fresco head they carry now grades :boundary")
      (let [kinds (frequencies (map rf.fresco.impl.codec/head-kind heads))]
        (is (pos? (get kinds :boundary 0))
            "the inspector heads are in the tree, so the absence below is not vacuous")
        (is (not-any? #(identical? edn-widget/inspect %) heads)
            "`edn/inspect` is a plain defn and is never itself a hiccup head")
        (is (zero? (get kinds :invalid 0))
            (str "every head in the rendered panel is one Fresco accepts — " kinds))))))

;; ---- RULING 2: the app-db path rows key through the ATTRIBUTE MAP ---------
;;
;; rf2-fcy5 — `app-db-slice-section` wrote its per-path keys as `^{:key i}`
;; reader META on the `[:li …]` vector. Reagent reads meta THEN props and so
;; returned the same key either way, which is why that spelling survived this
;; long; Fresco's codec reads a literal `:key` from a native tag's attrs and
;; reads Clojure metadata NOWHERE, so on the migration every row in the list
;; would have lost its key, with nothing on screen to say so.
;;
;; THE OBVIOUS INSTRUMENT IS HOLLOW HERE, which is the whole reason this row
;; exists beside `records-list-keys-reach-react` rather than inside it. That
;; row reads `(.-key (r/as-element node))` — exactly right for ITS defect
;; (meta on a CALL form, dead on every substrate) and BLIND to this one,
;; because Reagent reads meta AND props and answers the same key before and
;; after. Measured under rf2-k97c.3 on `views/resizable_table.cljs`: on
;; reverted source the Reagent assertion PASSED while the Fresco assertion on
;; the very next line read `[nil nil nil]`. The door that can tell meta from
;; attrs is the codec's own.

(defn- emitted-key
  "The React key the FRESCO codec commits for one hiccup node — the
  hiccup→element door a boundary's children actually cross, rather than
  whatever the authoring vector happens to be carrying."
  [node]
  (.-key (rf.fresco.impl.codec/as-element node)))

(deftest app-db-path-rows-key-through-the-fresco-codec
  (testing "rf2-fcy5 / RULING 2 — the one `^{:key …}` metadata site in this
            file writes its key into the `[:li]` ATTRIBUTE MAP now, which is
            the only spelling Fresco reads. Reverting it to reader meta makes
            `:key` absent from the attrs and the codec's key nil, and both
            halves below go red."
    (let [r   (record {:surface :http :fx-id :rf.http/managed
                       :status :ok :http-status 200
                       :paths [[:users 42] [:users 43] [:session :token]]})
          lis (->> (tree-nodes (template/record-panel r))
                   (filter #(and (vector? %) (= :li (first %))))
                   vec)]
      ;; Control: the walker really reached the rows, so a nil key below is
      ;; the codec's answer about a key rather than an empty selection.
      (is (= 3 (count lis)) "one :li per touched path")
      (let [ks (mapv emitted-key lis)]
        (is (every? some? ks) "every path row reaches React with a key")
        (is (= 3 (count (distinct ks))) "sibling keys are distinct"))
      ;; And the contract surface stays observable in the hiccup itself.
      (is (every? #(contains? (second %) :key) lis)
          "the key rides the attribute map")
      (is (every? #(nil? (meta %)) lis)
          "no `^{:key …}` reader meta survives on these rows"))))

(deftest user-facing-text-carries-no-internal-refs
  (let [recs [(record {:surface :http :fx-id :rf.http/managed
                       :status :ok :http-status 200
                       :handler [:user/loaded] :paths [[:users 42]]})
              (record {:surface :http :fx-id :rf.http/managed
                       :status :ok :http-status 200
                       :paths []})   ;; app-db-wasn't-updated warning path
              (assoc (record {:surface :http :fx-id :rf.http/managed
                              :status :error :http-status 500})
                     :failure {:kind :rf.http/http-5xx
                               :tags {:status 500}})
              (record {:surface :websocket :fx-id :rf.ws/connect :status :ok})
              (record {:surface :machine-invoke :fx-id :rf.machine/spawn :status :ok})
              (record {:surface :ssr-fx :fx-id :rf.server/set-status :status :ok})
              (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})
              (-> (record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
                  (assoc :stubbed? true))
              (-> (record {:surface :http :fx-id :rf.http/managed :status :cancelled})
                  (assoc :cancel-cause :upstream-cancelled))]]
    (doseq [r recs]
      (let [panel (template/record-panel r)
            label (str (:surface r) "/" (:status r))]
        (assert-no-internal-refs! (str "visible-text[" label "]") (visible-text panel))
        (assert-no-internal-refs! (str "tooltip-text[" label "]") (tooltip-text panel))))))
