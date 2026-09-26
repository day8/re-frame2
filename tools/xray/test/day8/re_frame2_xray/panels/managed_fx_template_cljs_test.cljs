(ns day8.re-frame2-xray.panels.managed-fx-template-cljs-test
  "Smoke render tests for the managed-fx wire-boundary diff template.

  Pure hiccup; the test asserts the structural shape of the panel
  per-surface without booting a substrate. The data-testid attributes
  the template carries make the assertions deterministic without DOM
  introspection."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [reagent.core :as r]
            ;; The HD-016 row's positive control is the
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
   :overridden?     false})

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
  (testing "An HTTP record draws REQUEST and REPLY TARGET, and NOTHING that
            would describe an outcome. WIRE TIMING, RESPONSE and APP-DB SLICE
            are absent rather than empty: the issuing event-bundle carries one
            HTTP fact — the request went out — so a section drawn over nothing
            could only mislead. A RESPONSE row reading '(no response payload
            yet)' would promise a reply that never arrives here."
    ;; `:res nil` — an unjoined HTTP record carries no response summary
    ;; (RESPONSE is drawn exactly when the join found one).
    (let [r   (assoc (record {:surface :http :fx-id :rf.http/managed
                              :status :issued
                              :handler [:user/loaded]})
                     :res nil)
          out (template/record-panel r)
          ids (set (testids out))]
      (is (contains? ids "rf-xray-managed-fx-record-http-99"))
      (is (contains? ids "rf-xray-managed-fx-header-http"))
      (is (contains? ids "rf-xray-managed-fx-surface-http"))
      (is (contains? ids "rf-xray-managed-fx-status-issued"))
      (is (contains? ids "rf-xray-managed-fx-section-request"))
      (is (contains? ids "rf-xray-managed-fx-section-handler"))
      (is (not (contains? ids "rf-xray-managed-fx-section-wire")))
      (is (not (contains? ids "rf-xray-managed-fx-section-response")))
      (is (not (contains? ids "rf-xray-managed-fx-section-app-db")))))

  (testing "CONTROL — a NON-HTTP record draws all five sections, so the
            absences above are a statement about the HTTP surface and not about
            the renderer having lost three sections"
    (let [ids (set (testids (template/record-panel
                              (record {:surface :websocket :fx-id :rf.ws/connect
                                       :status :ok}))))]
      (doseq [s ["request" "wire" "response" "handler" "app-db"]]
        (is (contains? ids (str "rf-xray-managed-fx-section-" s))
            (str "the websocket record keeps its " s " section"))))))

(deftest record-panel-http-with-a-same-bundle-failure-draws-response
  (testing "The one outcome an HTTP record CAN witness is a synchronous
            request-body-prep failure, which runs inside the fx handler's own
            stack. When one is attributed to the record, RESPONSE comes back —
            it is the section that carries the failure tags."
    (let [r   (assoc (record {:surface :http :fx-id :rf.http/managed
                              :status :error})
                     :failure {:kind :rf.http/transport
                               :tags {:stage :request-prep :request-id :req-2}})
          ids (set (testids (template/record-panel r)))]
      (is (contains? ids "rf-xray-managed-fx-section-response")
          "a failure brings RESPONSE back for HTTP")
      (is (contains? ids "rf-xray-managed-fx-status-error"))
      (is (not (contains? ids "rf-xray-managed-fx-section-wire"))
          "but a failure says nothing about wire timing")
      (is (not (contains? ids "rf-xray-managed-fx-section-app-db"))))))

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

;; ---- React keys reaching the renderer ------------------------------------

(defn- react-key
  "The key REACT actually receives for one hiccup node. `r/as-element`
  runs Reagent's own `key-from-vec` (meta first, then the props map),
  so this reads the rendered element rather than the authoring shape.

  Asserting on `(meta node)` instead would be a HOLLOW GATE: the key
  rides the attribute map, so `(meta node)` reads nil whether or not a
  key reaches React."
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
  (testing "`^{:key …}` reader meta on the `(record-panel …)`
            CALL form would attach to the source list, so the returned
            vector would carry no key and React would receive none. The key
            rides the `:section` attribute map, which Reagent reads via
            props and Fresco's codec reads as a literal `:key`."
    (let [recs [(record {:surface :http :fx-id :rf.http/managed :status :ok :http-status 200})
                (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
          kids (record-panel-nodes (template/records-list recs))
          ks   (mapv react-key kids)]
      (is (= 2 (count kids)))
      ;; The composed value: surface "-" origin-event-id "-" fx-id.
      (is (= [":http-99-:rf.http/managed" ":flow-99-:rf.fx/reg-flow"] ks))
      (is (every? some? ks) "every panel reaches React with a key")
      (is (= 2 (count (distinct ks))) "sibling keys are distinct")
      ;; The contract surface stays observable in the hiccup itself.
      (is (= ks (mapv #(:key (second %)) kids))))))

;; ---- HD-016: `edn/inspect` is CALLED, never a hiccup head ----------------
;;
;; `views/edn-widget/inspect` is a plain `defn`. Under Reagent a plain
;; function in hiccup head position is a form-1 component and renders
;; happily; under Fresco a plain function in head position is a LOUD ERROR
;; by design (HD-016), and on this panel's render path there is no error
;; boundary above it — the throw escapes and React unmounts the entire Xray
;; root, which presents as a panel that never appears rather than as an
;; error. The whole mistake is one token wide.
;;
;; The instrument is Fresco's OWN head classifier rather than a shape
;; assertion of ours, so the zero below is the renderer's answer and not
;; a claim about what we think the renderer would say.
;;
;; BOTH BLOCKING CLASSES ARE CLEAR. `inspect` is CALLED, never headed. The
;; value it returns heads `[ei/edn-inspector …]`, a `reg-view` head, which
;; the codec refuses for the same reason it refuses a plain `defn` —
;; `views/edn_inspector.cljs` says it outright, "Only `edn-inspector-view`
;; is a head in a Fresco body". `panels/ManagedFxList` is an
;; `rf.fresco/defview`, so the four sites call `edn/inspect-view` and the
;; tree this file folds is codec-clean all the way down. The
;; every-section-open row below states that in the codec's own words.
;;
;; WHAT THIS ROW CANNOT DO. It walks the DEFAULT tree, where three of the
;; five sections are shut. `theme/section/section-row` renders its body
;; under `(when expanded? …)`, so a collapsed body is computed (it is a
;; positional argument) and then DISCARDED — three of the four inspector
;; sites are therefore absent from the tree this row walks. So an
;; `[edn/inspect …]` head in one of those three would NOT turn THIS row red,
;; and it does not claim it would: the absence assertion is a FLOOR, and the
;; controls above it are what stop the floor reading as a proof. Measured,
;; not assumed — asserting the returned inspector present in this tree
;; reads zero.
;;
;; `no-plain-fn-in-head-position-with-every-section-open` restates this floor
;; over the FULLY OPEN tree — where all four sites really are present, as it
;; asserts before reading the absence. Keep both: this row grades what the
;; operator sees on first paint, that one grades what a click reveals.
;;
;; THE PRIMITIVE IS NOT AT FAULT. `section-row` is non-interactive BY
;; DESIGN — `theme/section.cljc` states the contract in terms: "No
;; interactivity. Click-to-toggle wiring is the caller's responsibility." —
;; and it is shared with `panels/fresco` and `panels/module_view`. The panel
;; does the caller's half.

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
  (testing "a plain function in hiccup head position is a loud
            error under Fresco (HD-016) and the throw escapes with no error
            boundary above this render path. `edn/inspect` is called, never
            headed. This row is the FLOOR for the panel's REACHABLE tree — see the
            section comment for what it deliberately does not claim."
    ;; BOTH SHAPES THE PANEL CAN DRAW: an `:http` record draws REQUEST and
    ;; REPLY TARGET only, while the other four surfaces draw all five
    ;; sections. The narrow one alone is too small a tree for this row's
    ;; population control — the `(< 20 …)` floor below — to hold, and that
    ;; control refuses a sample too small to make the absence meaningful. So
    ;; the sample covers both shapes rather than the floor being lowered.
    (let [http-rec (record {:surface :http
                            :fx-id   :rf.http/managed
                            :status  :issued
                            :handler [:user/profile-loaded {:id 1}]})
          ws-rec   (record {:surface     :websocket
                            :fx-id       :rf.ws/connect
                            :status      :ok
                            :handler     [:user/profile-loaded {:id 1}]})
          heads    (concat (hiccup-heads (template/record-panel http-rec))
                           (hiccup-heads (template/record-panel ws-rec)))]
      ;; Three controls, because the assertion below is an ABSENCE and an
      ;; absence is what a dead instrument also reports.
      (is (= :invalid (rf.fresco.impl.codec/head-kind edn-widget/inspect))
          "Fresco's own classifier grades the plain fn an invalid head")
      (is (< 20 (count heads))
          "the walker reached a populated tree, so a zero below means absence")
      (is (seq (hiccup-heads (template/record-panel http-rec)))
          "and the NARROWED http panel is really in the sample, so the row
           did not quietly become a statement about websocket alone")
      ;; No plain function anywhere in head position — stated over the
      ;; whole reachable tree rather than named against `inspect` alone,
      ;; so a DIFFERENT facade helper (`ei/mini`, `edn/inspect-inline`)
      ;; put in head position here is caught too.
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

(deftest app-db-section-never-warns
  (testing "There is no amber 'app-db wasn't updated' warning, on any
            surface or in any state. A warning firing whenever the status is
            :ok and the path list is empty would fire on every successful
            record — production wires no diff feed, so the list is empty on
            every record — telling the author their handler was broken and
            guessing at the cause. Even a genuinely unchanged app-db is
            frequently correct."
    (let [measured-empty (record {:surface :websocket :fx-id :rf.ws/connect
                                  :status :ok :paths []})
          combined       (visible-text (template/record-panel measured-empty))]
      (is (not (str/includes? combined "app-db wasn't updated")))
      (is (not (str/includes? combined "Likely a")))
      (is (str/includes? combined "no app-db changes in this event-bundle")
          "measured-and-empty says so, plainly")
      ;; silent-by-default: no internal F-code in user-visible prose
      (is (not (re-find #"F\.\d" combined))
          "user-visible text leaks an internal F-code")))

  (testing "An UNTRACKED record — `:paths-touched` nil, which is what the
            production 1-arity produces — says it is untracked rather than
            claiming nothing changed. The two are different facts, and a
            warning would conflate them."
    (let [untracked (assoc (record {:surface :websocket :fx-id :rf.ws/connect
                                    :status :ok})
                           :paths-touched nil)
          combined  (visible-text (template/record-panel untracked))]
      (is (str/includes? combined "not tracked"))
      (is (not (str/includes? combined "app-db wasn't updated")))
      (is (not (str/includes? combined "no app-db changes in this event-bundle"))
          "untracked must not read as measured-and-empty")))

  (testing "CONTROL — a record with real paths lists them, so the
            assertions above are about the warning and not about the section
            having gone blank"
    (let [combined (visible-text
                     (template/record-panel
                       (record {:surface :websocket :fx-id :rf.ws/connect
                                :status :ok :paths [[:users 42]]})))]
      (is (str/includes? combined ":users"))
      (is (not (str/includes? combined "app-db wasn't updated"))))))

;; ---- chrome leak guard: no bead IDs / spec citations in user-facing text ----
;;
;; Per the silent-by-default policy (Conventions.md
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

;; ---- section disclosure --------------------------------------------------
;;
;; `:expanded?` LITERALS would leave REQUEST, RESPONSE and REPLY TARGET
;; drawing a `▶` that nothing could operate, with payloads that never reach
;; a rendered tree. `theme/section/section-row` is non-interactive by design
;; and shared with two other panels, so the state and the click are the
;; panel's.

(def ^:private disclosure-record
  "A WEBSOCKET record, deliberately, because the disclosure machinery is
  surface-independent and every row below needs all FIVE sections to exist.

  An HTTP record draws two sections, so using one here would quietly
  convert these rows from 'the toggle works' into 'HTTP draws three fewer
  sections', which is `record-panel-http-smoke`'s job and not theirs."
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
  "Every node in a hiccup tree, payload values included. Walked structurally
  rather than through `rf.test-helpers/expand-tree`, which rebuilds nested
  vectors with `mapv` — that would substitute its own vectors for the ones
  under test, and strips reader metadata besides.

  IT DESCENDS INTO MAP VALUES, and that is what keeps \"payload values
  included\" true. A Fresco boundary takes ONE PROPS MAP
  (`[ei/edn-inspector-view {:value v …}]`), so the payload sits behind a
  map key, out of reach of a vectors-and-seqs walk. Without this arm the
  walk returns the props map itself and never looks inside it — an absence
  that reads exactly like a payload the panel failed to render, which is
  the direction that matters here, since every caller below asserts
  PRESENCE. `body-shown?` and `testids` are a different walker that reads
  the attribute map by position and never descends into one."
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
            open and REQUEST + RESPONSE + REPLY TARGET shut on first
            paint. A nil override map is that first-paint state.

            This row is a FLOOR for the defaults, not a gate for the
            disclosure — literals painting the defaults would pass it too.
            The gate is the toggle rows below."
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
  (testing "the disclosure's core claim. A section starts collapsed, the
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
  (testing "The other two collapsed-by-default sections."
    (let [opened (template/record-panel noop-dispatch (open-all disclosure-record)
                                        disclosure-record)
          shut   (template/record-panel noop-dispatch nil disclosure-record)]
      (is (some #{{:ok true}} (tree-nodes opened))
          "the response payload reaches the tree")
      (is (not (some #{{:ok true}} (tree-nodes shut))))
      (is (some #{[:user/loaded {:id 1}]} (tree-nodes opened))
          "the configured reply target reaches the tree")
      (is (not (some #{[:user/loaded {:id 1}]} (tree-nodes shut)))))))

(deftest the-reply-target-section-carries-no-focus-affordance
  (testing "There is no '→ focus event ↗' button, open or shut.
            Dispatching `:rf.xray/focus-event` with the ISSUING record's own
            dispatch-id and frame — the event-bundle already in focus — would
            advertise a pivot to where the response landed and re-focus the
            panel you were looking at."
    (let [opened (template/record-panel noop-dispatch (open-all disclosure-record)
                                        disclosure-record)
          shut   (template/record-panel noop-dispatch nil disclosure-record)]
      (is (not (contains? (set (testids opened)) "rf-xray-managed-fx-focus-handler")))
      (is (not (contains? (set (testids shut)) "rf-xray-managed-fx-focus-handler")))
      ;; Control, taken from the target: the section itself is there and
      ;; opens, so the absence above is the button's and not the whole
      ;; section having vanished.
      (is (contains? (set (testids opened)) "rf-xray-managed-fx-section-handler-body")
          "control: the section opens")))

  (testing "and opening it dispatches nothing of its own — the only dispatch a
            reply-target section can make is its own disclosure toggle"
    (let [seen (atom [])
          tree (template/record-panel #(swap! seen conj %) (open-all disclosure-record)
                                      disclosure-record)]
      (is (some? tree))
      (is (empty? @seen)
          (str "rendering dispatches nothing — " (pr-str @seen))))))

(deftest reply-target-renders-the-unified-reply-to-key
  (testing "`:reply-to` is the app-facing unified reply-target key, and a
            request written that way must not read as having no reply target.
            The record's `:handler` is whatever `args-handler-event` resolved,
            so this row grades the RENDERING of a resolved target; the
            resolution itself is graded in the helpers test."
    (let [r   (record {:surface :http :fx-id :rf.http/managed
                       :status :issued
                       :handler [:checkout/reply]})
          ids (set (testids (template/record-panel noop-dispatch (open-all r) r)))
          txt (visible-text (template/record-panel noop-dispatch (open-all r) r))]
      (is (contains? ids "rf-xray-managed-fx-section-handler"))
      (is (not (str/includes? txt "no reply target configured")))))

  (testing "and a record with NO configured target says so, naming the keys
            that would have supplied one"
    (let [r   (record {:surface :http :fx-id :rf.http/managed
                       :status :issued})
          txt (visible-text (template/record-panel noop-dispatch (open-all r) r))]
      (is (str/includes? txt "no reply target configured"))
      (is (str/includes? txt ":reply-to")
          "the unified key is named first, not only the routing sugar"))))

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
  (testing "The disclosure runs both ways — a `▼` on WIRE TIMING and
            APP-DB SLICE TOUCHED that could not be closed would be the same
            dead affordance in the opposite direction."
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
            click inside an opened payload — the edn-inspector's chevrons
            among them — would bubble to the wrapper and
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

;; ---- HD-016 on the tree the disclosure MAKES reachable --------------------
;;
;; `no-plain-fn-sits-in-hiccup-head-position` above is a floor over the
;; DEFAULT tree, and its own comment says plainly what it cannot do: with
;; three sections shut, the four inspector sites are constructed as
;; positional arguments and then discarded by `section-row`'s `(when
;; expanded? …)`, so they never appear in the tree it walks. The disclosure
;; is exactly what makes them reachable, so the same floor is restated over
;; the FULLY OPEN tree, where all four are really present.

(deftest no-plain-fn-in-head-position-with-every-section-open
  (testing "the four inspector sites sit inside sections that are
            collapsed by default, out of reach of a default-tree row. With
            every section open they are in the rendered tree, and a plain fn
            in head position is an HD-016 throw that escapes with no error
            boundary above this panel — a panel that never appears rather
            than an error."
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
      ;; THE DISCRIMINATOR IS THE CODEC.
      ;;
      ;; Neither of the two obvious shapes works. `(empty? (filter fn? heads))`
      ;; — what the DEFAULT-tree row above can use, because the default tree
      ;; carries no inspector at all — is wrong here in the permissive
      ;; direction AND the strict one: a reg-view head IS a fn value on CLJS
      ;; (`build-frame-aware-view` returns `(with-meta (fn …) {:contextType …})`,
      ;; a `cljs.core.MetaFn`), and so is a Fresco boundary, by
      ;; `codec/boundary-head?`'s own definition. And `(some? (meta head))`
      ;; reads NIL on a boundary: a boundary is a plain React function
      ;; component carrying a JS own-property and no Clojure metadata at all.
      ;;
      ;; `head-kind` answers without a branch, because it is the renderer's
      ;; own question: a plain `defn` and a reg-view head both grade
      ;; `:invalid`, a boundary grades `:boundary`, a native tag `:tag`. Any
      ;; of the four call sites spelled `edn/inspect` rather than
      ;; `edn/inspect-view` puts an `:invalid` head in this tree and reds the
      ;; row — which is the whole point, since that spelling is silent under
      ;; Reagent and unmounts the Xray root under Fresco.
      (is (= :invalid (rf.fresco.impl.codec/head-kind
                        (first (edn-widget/inspect {:probe 1} "probe"))))
          "the Reagent head `edn/inspect` returns grades :invalid")
      (is (= :boundary (rf.fresco.impl.codec/head-kind
                         (first (edn-widget/inspect-view {:probe 1} "probe"))))
          "the Fresco head these sites carry grades :boundary")
      (let [kinds (frequencies (map rf.fresco.impl.codec/head-kind heads))]
        (is (pos? (get kinds :boundary 0))
            "the inspector heads are in the tree, so the absence below is not vacuous")
        (is (not-any? #(identical? edn-widget/inspect %) heads)
            "`edn/inspect` is a plain defn and is never itself a hiccup head")
        (is (zero? (get kinds :invalid 0))
            (str "every head in the rendered panel is one Fresco accepts — " kinds))))))

;; ---- the app-db path rows key through the ATTRIBUTE MAP ------------------
;;
;; `app-db-slice-section` writes its per-path keys into the `[:li …]`
;; ATTRIBUTE MAP. Reagent reads meta THEN props, so `^{:key i}` reader META
;; on the vector would answer the same key either way under Reagent;
;; Fresco's codec reads a literal `:key` from a native tag's attrs and reads
;; Clojure metadata NOWHERE, so with reader meta every row in the list would
;; lose its key, with nothing on screen to say so.
;;
;; THE OBVIOUS INSTRUMENT IS HOLLOW HERE, which is the whole reason this row
;; exists beside `records-list-keys-reach-react` rather than inside it. That
;; row reads `(.-key (r/as-element node))` — exactly right for ITS defect
;; (meta on a CALL form, dead on every substrate) and BLIND to this one,
;; because Reagent reads meta AND props and answers the same key either
;; way. Measured on `views/resizable_table.cljs`: with reader-meta keys the
;; Reagent assertion PASSED while the Fresco assertion on the very next line
;; read `[nil nil nil]`. The door that can tell meta from attrs is the
;; codec's own.

(defn- emitted-key
  "The React key the FRESCO codec commits for one hiccup node — the
  hiccup→element door a boundary's children actually cross, rather than
  whatever the authoring vector happens to be carrying.

  HEAD + ATTRS ONLY, which is this tree's idiom for the Fresco door (see
  `panels/machine_inspector_view_cljs_test`'s `fresco-key`). The key is
  read off the attribute map by both renderers, so dropping the subtree
  cannot change the answer; what it buys is that the codec lowers children
  EAGERLY, so a whole-node call raises HD-016
  `:rf.error/fresco-bad-head` the moment anything below the node is a
  plain fn in head position. That is a head defect rather than a key
  defect, and letting it throw here would hide the key answer behind it.

  On these rows the whole-node form ANSWERS — the `[:li]` subtree is one
  `[:span]` — and reads `[nil nil nil]` with `^{:key …}` reader meta
  rather than raising. The subvec is taken anyway, so a child with a fn
  head cannot turn this row's answer into an exception about something
  else.

  `subvec` DROPS VECTOR METADATA, which would matter if the metadata-vs-attrs
  discrimination lived in a Reagent/Fresco PAIR — subvec both doors and the
  meta is gone before Reagent sees it, so both read nil and the pair stops
  saying which fault it is. It does not matter here: `r/as-element` is
  HOLLOW for this sub-case (Reagent reads meta AND props and answers the
  same key either way), so this row carries no Reagent door at all and the
  discrimination is carried by the two structural assertions below — the
  key is IN the attrs map, and NO reader meta survives on the row."
  [node]
  (.-key (rf.fresco.impl.codec/as-element (subvec node 0 2))))

(deftest app-db-path-rows-key-through-the-fresco-codec
  (testing "the `[:li]` path rows write their key into the ATTRIBUTE
            MAP, which is the only spelling Fresco reads. Reader meta instead
            makes `:key` absent from the attrs and the codec's key nil, and
            both halves below go red."
    ;; A WEBSOCKET record: the path rows live in the APP-DB SLICE section, and
    ;; an HTTP record does not draw one. The `:key` contract
    ;; under test is surface-independent.
    (let [r   (record {:surface :websocket :fx-id :rf.ws/connect
                       :status :ok
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
                       :status :issued})          ;; an unjoined HTTP record
              (record {:surface :websocket :fx-id :rf.ws/connect
                       :status :ok :paths []})    ;; measured-and-empty app-db slice
              (assoc (record {:surface :websocket :fx-id :rf.ws/connect
                              :status :ok})
                     :paths-touched nil)          ;; untracked app-db slice
              (assoc (record {:surface :http :fx-id :rf.http/managed
                              :status :error :http-status 500})
                     :failure {:kind :rf.http/http-5xx
                               :tags {:status 500}})
              (record {:surface :websocket :fx-id :rf.ws/connect :status :ok})
              (record {:surface :machine-invoke :fx-id :rf.machine/spawn :status :ok})
              (record {:surface :ssr-fx :fx-id :rf.server/set-status :status :ok})
              (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})
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
;; An override replaces the HANDLER; a delegating override really issues a
;; request. So the pill says OVERRIDE rather than STUB, names the
;; replacement, and claims nothing about I/O — no "instead of running for
;; real" — while the status beside it carries what the capture evidences.

(defn- override-node [panel]
  (first (filter #(and (vector? %) (map? (second %))
                       (= "rf-xray-managed-fx-override" (:data-testid (second %))))
                 (hiccup-vectors panel))))

(deftest override-pill-names-the-replacement
  (testing "a redirected record draws OVERRIDE, its target in the tooltip"
    (let [panel (template/record-panel
                  (assoc (record {:surface :http :fx-id :rf.http/managed :status :overridden})
                         :overridden? true :override-to :app/fake-http
                         :override-from :rf.http/managed))
          pill  (override-node panel)
          title (:title (second pill))]
      (is (some? pill))
      (is (= "OVERRIDE" (visible-text pill)))
      (is (str/includes? title ":app/fake-http"))
      (is (not (str/includes? title "for real"))
          "the tooltip claims nothing about whether real I/O happened")
      (is (not (str/includes? (visible-text panel) "STUB")))))
  (testing "a function override says so; it rides beside ANY status, OK included"
    (let [pill (override-node
                 (template/record-panel
                   (assoc (record {:surface :http :fx-id :rf.http/managed :status :ok
                                   :http-status 200})
                          :overridden? true :override-to :re-frame.fx/fn-value
                          :override-from :rf.http/managed)))]
      (is (some? pill))
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
      (is (contains? ids "rf-xray-managed-fx-status-error"))
      (is (str/includes? txt "ERROR 500"))
      (is (str/includes? txt "2 attempts"))
      (is (str/includes? txt "elapsed 850ms"))
      (is (contains? ids "rf-xray-managed-fx-section-wire") "an elapsed brings WIRE TIMING")
      (is (contains? ids "rf-xray-managed-fx-section-response"))
      (is (not (contains? ids "rf-xray-managed-fx-section-app-db")))
      (is (not (contains? ids "rf-xray-managed-fx-no-completion")))
      (is (some? link) "a delivered reply draws the link")
      ((:on-click (second link)) nil)
      (is (= [[:rf.xray/focus-event 41 :rf/default]] @seen)
          "the link focuses the REPLY bundle, never the issuing one")))

  (testing "CANCELLED and STALE are their own statuses, not ERROR"
    (let [c (joined-http-record :cancelled {:cancel-cause :actor-destroyed})
          s (joined-http-record :stale {:cancel-cause :rf.http/superseded})]
      (is (contains? (set (testids (template/record-panel c))) "rf-xray-managed-fx-status-cancelled"))
      (is (str/includes? (visible-text (template/record-panel c)) "CANCELLED"))
      (is (contains? (set (testids (template/record-panel s))) "rf-xray-managed-fx-status-stale"))
      (is (str/includes? (visible-text (template/record-panel s)) "STALE"))
      (is (not (contains? (set (testids (template/record-panel s))) "rf-xray-managed-fx-reply-link"))
          "no link without a delivered reply")))

  (testing "an issued row with no terminal row says so, and never 'in flight'"
    (let [r   (assoc (joined-http-record :issued {}) :completion :none)
          ids (set (testids (template/record-panel r)))
          txt (visible-text (template/record-panel r))]
      (is (contains? ids "rf-xray-managed-fx-no-completion"))
      (is (str/includes? txt "no completion in this capture"))
      (is (not (str/includes? (str/lower-case txt) "in flight")))
      (is (not (contains? ids "rf-xray-managed-fx-section-wire")))
      (is (not (contains? ids "rf-xray-managed-fx-section-response")))))

  (testing "CONTROL — an unjoined record draws none of it"
    (let [r   (assoc (joined-http-record :issued {}) :completion nil)
          ids (set (testids (template/record-panel r)))]
      (is (not (contains? ids "rf-xray-managed-fx-no-completion")))
      (is (not (contains? ids "rf-xray-managed-fx-attempts")))
      (is (not (contains? ids "rf-xray-managed-fx-reply-link"))))))

;; ---- the per-mount qualifier ---------------------------------------------
;;
;; THIS IS THE NODE LANE'S HALF AND IT IS NOT THE WHOLE CLAIM. The rows
;; below grade the template's PURE COMPOSITION — that a named mount qualifies
;; both ids the widget keys on, that an unnamed one composes the plain
;; record-keyed ids, and what `instance-token` refuses. What they cannot see
;; is what a shared identity actually breaks: two REAL mounts sharing one
;; memoised ref callback and one ResizeObserver, and the survivor being
;; released when its sibling detaches. That needs a real React commit and
;; lives in `panels/managed_fx_mount_instance_id_dom_cljs_test`, under
;; `npm run test:browser`.
;;
;; BOTH IDS COME FROM ONE STRING HERE. `edn-widget/inspect-view` builds the
;; boundary's `:mount-id` as `"rf-xray-inspect-" + node-key` and its
;; `:panel-id` as `:rf.xray.inspect/<node-key>`, and this panel passes no
;; `:site-id` — so the widget's `effective-id` falls back to the mount-id
;; and one qualifier separates lifecycle, width and expansion together.
;; That is why there is one qualifier here where `app-db-diff` needs two.

(defn- inspect-view-props
  "Every `edn-inspector-view` props map in a hiccup tree, in document order.
  Identified by the `:mount-id` key the boundary REQUIRES, so this finds the
  widgets rather than a shape we assumed they had."
  [node]
  (->> (hiccup-vectors node)
       (map second)
       (filter #(and (map? %) (contains? % :mount-id)))))

(defn- widget-mount-ids [node] (mapv :mount-id (inspect-view-props node)))
(defn- widget-panel-ids [node] (mapv #(get-in % [:opts :panel-id]) (inspect-view-props node)))

(deftest instance-token-normalises-and-refuses
  (testing "nil and blank name no instance; a keyword's NAMESPACE
            is part of the name (the property Reagent's `[:>]` crossing would
            otherwise drop); and the fn is idempotent, which is what lets the
            bridge and the boundary both call it and compose one answer."
    (is (nil? (template/instance-token nil)))
    (is (nil? (template/instance-token "")))
    (is (= "left" (template/instance-token "left")))
    (is (= "left" (template/instance-token :left)))
    (is (= "left/list" (template/instance-token :left/list))
        "the namespace survives — `:left/list` and `:right/list` are two
         instances, not one")
    (is (= "left/list" (template/instance-token (template/instance-token :left/list)))
        "idempotent: a non-blank string answers itself")
    (doseq [bad [{:a 1} [:left] 42]]
      (is (thrown? js/Error (template/instance-token bad))
          (str "a shape that could not be stable across renders is refused: "
               (pr-str bad))))))

(deftest unnamed-record-panel-composes-the-ids-it-always-did
  (testing "a mount that names no instance composes the plain ids
            below, and those ids must be stable: they key the operator's
            expansion state and the measured column width."
    (let [tree (template/record-panel noop-dispatch (open-all disclosure-record)
                                      disclosure-record)
          rk   (h/record-key disclosure-record)]
      ;; Control, taken from the target: the open tree really carries the
      ;; widgets, so the equalities below are about content and not emptiness.
      (is (= 3 (count (widget-mount-ids tree)))
          (str "control: REQUEST, RESPONSE and HANDLER each emitted a widget — "
               (pr-str (widget-mount-ids tree))))
      (is (= [(str "rf-xray-inspect-managed-fx/" rk "/req")
              (str "rf-xray-inspect-managed-fx/" rk "/res")
              (str "rf-xray-inspect-managed-fx/" rk "/handler")]
             (widget-mount-ids tree)))
      (is (= [(keyword (str "rf.xray.inspect/managed-fx/" rk "/req"))
              (keyword (str "rf.xray.inspect/managed-fx/" rk "/res"))
              (keyword (str "rf.xray.inspect/managed-fx/" rk "/handler"))]
             (widget-panel-ids tree))))))

(deftest a-named-record-panel-qualifies-both-widget-ids
  (testing "naming the mount splices the instance in after the
            panel's own prefix and before the record key, so the qualifier
            names the MOUNT while `record-key` names the record inside
            it. Both ids move together because both are built from the one
            node-key — qualifying only the lifecycle key would leave two
            mounts sharing one expansion identity."
    (let [expanded (open-all disclosure-record)
          plain    (template/record-panel noop-dispatch expanded nil disclosure-record)
          named    (template/record-panel noop-dispatch expanded "left" disclosure-record)
          kw       (template/record-panel noop-dispatch expanded :left/list disclosure-record)]
      (is (seq (widget-mount-ids plain))
          "control: the unnamed tree carries widgets to compare against")
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
               (pr-str (widget-panel-ids named))))
      (is (empty? (filter (set (widget-mount-ids plain)) (widget-mount-ids named)))
          "no id survives from the unnamed mount to the named one")
      (is (every? #(str/starts-with? % "rf-xray-inspect-managed-fx/left/list/")
                  (widget-mount-ids kw))
          (str "a keyword instance keeps its namespace — "
               (pr-str (widget-mount-ids kw))))
      (is (= (widget-mount-ids named)
             (widget-mount-ids (template/record-panel noop-dispatch expanded "left"
                                                     disclosure-record)))
          "stable across renders — an identity, not a per-render nonce"))))

(deftest record-identity-and-disclosure-survive-the-qualifier
  (testing "the qualifier composes WITH `record-key`; it does not
            replace it. The React key and the disclosure toggle are that
            identity's two consumers and BOTH stay untouched, or naming a
            mount would remount every row and orphan the operator's open
            sections."
    (let [recs    [(record {:surface :http :fx-id :rf.http/managed
                            :status :ok :http-status 200})
                   (record {:surface :flow :fx-id :rf.fx/reg-flow :status :ok})]
          plain   (template/records-list noop-dispatch nil nil recs)
          named   (template/records-list noop-dispatch nil "left" recs)
          keys-of #(mapv react-key (record-panel-nodes %))]
      (is (every? some? (keys-of plain))
          "control: the unnamed list really carries React keys")
      (is (= (keys-of plain) (keys-of named))
          (str "the React keys are identical — " (pr-str (keys-of named))))
      ;; The disclosure toggle carries `rec-key` in its dispatch payload and
      ;; `body-shown?` reads the testid that payload is keyed to, so an
      ;; override map built from `record-key` alone must open a NAMED
      ;; mount's sections too.
      (let [rec    (first recs)
            opened (template/record-panel noop-dispatch (open-all rec) "left" rec)]
        (is (body-shown? opened :request)
            "a `record-key`-keyed override opens a named mount's
             REQUEST — disclosure is deliberately shared between two lists
             of the same records")))))

(deftest records-list-threads-the-instance-to-every-record
  (testing "the 4-arity reaches every record, not just the first."
    (let [recs  [(record {:surface :http :fx-id :rf.http/managed
                          :status :ok :http-status 200})
                 (record {:surface :websocket :fx-id :rf.ws/connect :status :ok})]
          named (template/records-list noop-dispatch
                                       (into {} (mapcat open-all recs))
                                       "right" recs)
          ids   (widget-mount-ids named)]
      (is (< 1 (count ids))
          (str "control: more than one widget emitted — " (pr-str ids)))
      (is (every? #(str/starts-with? % "rf-xray-inspect-managed-fx/right/") ids)
          (str "every widget in the list carries the mount's name — "
               (pr-str ids)))
      (is (every? (fn [rec] (some #(str/includes? % (h/record-key rec)) ids))
                  recs)
          (str "and each record's own key is inside them, so the "
               "qualifier composed WITH `record-key` rather than replacing "
               "it — " (pr-str ids))))))
