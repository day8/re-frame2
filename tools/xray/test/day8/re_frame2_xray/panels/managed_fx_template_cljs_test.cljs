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
;; WHY THIS IS NOT A FRESCO DOM ROW. A DOM row would mount the panel
;; through the codec, which is not reachable for this file today: the
;; value `inspect` returns is `[ei/edn-inspector …]`, a `reg-view` head,
;; which the codec also refuses — `views/edn_inspector.cljs` says it
;; outright, "Only `edn-inspector-view` is a head in a Fresco body" — and
;; the panel's own mount, `panels/ManagedFxList`, is still a `reg-view`.
;; So the repair removes ONE of two blocking classes; the other is the
;; migration's, and the day it happens these four calls become
;; `edn/inspect-view`. Asserting only what is actually true here is the
;; point: a row claiming Fresco-readiness would be the hollow one.
;;
;; AND WHAT THIS ROW CANNOT DO, STATED PLAINLY BECAUSE THE FIRST DRAFT OF
;; IT LIED. All four sites sit inside sections `record-panel` builds with
;; `:expanded? false`, and `theme/section/section-row` renders its body
;; under `(when expanded? …)` — nothing in this tree wires a click, so the
;; `▶` glyph opens nothing and a collapsed body is computed and then
;; DISCARDED. The four vectors have therefore never appeared in any
;; rendered tree, which is the blind spot all three tiers of coverage
;; shared. So a revert to `[edn/inspect …]` would NOT turn this row red,
;; and the row does not claim it would: the absence assertion is a FLOOR
;; against a future head placed somewhere reachable, and the control above
;; it is what stops the floor reading as a proof. Measured, not assumed —
;; the draft that asserted the returned inspector was present in the tree
;; read zero, and that is how the discard was found. rf2-fcy5 carries what
;; would make these four gradeable.

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
