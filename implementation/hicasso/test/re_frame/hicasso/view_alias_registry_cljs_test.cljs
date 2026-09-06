(ns re-frame.hicasso.view-alias-registry-cljs-test
  "THE AUTHORING-TIME ALIAS — what `h/defview` publishes to core's `:view`
  registrar, and what it deliberately does not.

  A Story variant names its subject with a KEYWORD and resolves it
  through the framework registrar. Without the alias `h/defview` computes
  a name, computes a coordinate, hands both to
  `impl.collector/mint-view!` and registered nothing — so a Hicasso
  boundary was the one view in the repository a keyword could not reach.
  It now publishes one entry, and the shape of that entry is the whole
  claim:

  | slot | what it is |
  |---|---|
  | the id | `(keyword \"<ns>\" \"<sym>\")` — `reg-view`'s own derivation |
  | `:ns` / `:file` / `:line` / `:column` | the coordinate, at the top level, where `reg-view` puts it |
  | `:doc` | the author's docstring, when they wrote one |
  | `:hicasso/component` | the minted head, `identical?` to what the `def` binds |
  | `:executable-key` | `:hicasso/component` — where this entry's executable identity lives, so the registrar's `:different-fn?` tells a real reload from an idempotent one |
  | `:handler-fn` | **absent** |

  ## The peer registration is the point of comparison, not decoration

  `core-peer` below is an ordinary `rf/reg-view` in THIS namespace, and
  every row that says *the same as `reg-view`* asserts against it rather
  than against a remembered shape. The id derivation and the coordinate
  placement are claims about agreement between two macros, and an
  assertion that names only one of them cannot see the day they diverge.

  ## Why no adapter is installed

  This file's fixture installs none, deliberately, and that makes the whole
  namespace the adapter-timing witness constraint 3 asks for. Core's view
  registration consults the `:adapter/wrap-view` hook AT REGISTRATION TIME
  (`re-frame.views/apply-adapter-wrap-view`), and a `defview` is declared at
  namespace load — routinely before `rf/init!` has installed anything. The
  declarations here are made at exactly that moment, with no adapter present,
  and the rows below assert the entry is intact anyway. Nothing mounts, so
  nothing needs one.

  ## What this file does NOT assert

  Production absence. `re-frame.hicasso.error-source-coord-elision-prod-test`
  owns that, because it is the one suite compiled under `:advanced` with
  `goog.DEBUG=false`, and asserting absence in a dev build would assert
  nothing at all."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The declarations — ABOVE `use-fixtures`, and that is load-bearing
;; ---------------------------------------------------------------------------
;;
;; `make-reset-runtime-fixture` pins its stable baseline when the
;; `use-fixtures` form is EVALUATED, and folds that baseline back over the
;; live registrar before each test. A sibling namespace's fixture pins a
;; baseline that predates this file's load and would otherwise strand these
;; registrations in the shared `:node-test` bundle — which is the run-order
;; hazard the fixture's own docstring describes. Declared above it, they are
;; part of this file's baseline and are reinstated before every row.

(rf.hicasso/defview aliased-row
  "A DOCUMENTED boundary — the `:doc` half of the slot needs one that has a
  docstring to carry."
  [{:keys [label]}]
  [:li label])

(rf.hicasso/defview undocumented-row
  [_]
  [:li "no docstring"])

(rf/reg-view core-peer
  "The comparison. An ordinary core view in this same namespace, so the id
  derivation and the coordinate placement can be asserted as AGREEMENT
  rather than as remembered shapes."
  [_]
  [:li "core"])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- slot
  "The registration metadata for `id` under the `:view` kind, read back
  through the public seam a tool would use."
  [id]
  (rf/handler-meta :view id))

(def ^:private coord-keys [:ns :file :line :column])

;; ---------------------------------------------------------------------------
;; The id — `reg-view`'s derivation, spelled once
;; ---------------------------------------------------------------------------

(deftest the-entry-appears-under-the-id-reg-view-would-have-derived
  (testing "`::aliased-row` IS `(keyword \"<ns>\" \"<sym>\")`, so an author
            who knows how to name a core view already knows how to name a
            Hicasso one"
    (is (some? (slot ::aliased-row)))
    (is (= "re-frame.hicasso.view-alias-registry-cljs-test"
           (namespace ::aliased-row))))

  (testing "the peer proves it is ONE convention and not two that happen to
            agree today — both ids are this namespace qualifying the
            declaration's own symbol"
    (is (some? (slot ::core-peer)))
    (is (= (namespace ::aliased-row) (namespace ::core-peer)))))

;; ---------------------------------------------------------------------------
;; The coordinate — where `reg-view` stores it
;; ---------------------------------------------------------------------------

(deftest the-coordinate-is-stored-the-way-reg-view-stores-it
  (let [alias-coords (select-keys (slot ::aliased-row) coord-keys)
        peer-coords  (select-keys (slot ::core-peer)   coord-keys)]

    (testing "the same coord keys, at the same level of the same map — a
              consumer needs no second seam for Hicasso"
      (is (= (set (keys peer-coords)) (set (keys alias-coords)))))

    (testing "and they describe THIS file"
      (is (= 're-frame.hicasso.view-alias-registry-cljs-test (:ns alias-coords)))
      (is (re-find #"view_alias_registry_cljs_test\.cljs$" (:file alias-coords)))
      (is (pos-int? (:line alias-coords)))
      (is (pos-int? (:column alias-coords))))))

(deftest the-docstring-rides-the-slot-and-its-absence-is-honest
  (testing "a documented declaration carries its `:doc`, so the registrar's
            `:rf.warning/missing-doc` nudge cannot fire against a view that
            IS documented"
    (is (string? (:doc (slot ::aliased-row))))
    (is (re-find #"DOCUMENTED boundary" (:doc (slot ::aliased-row)))))

  (testing "an undocumented one carries none — the slot reports what the
            author wrote rather than manufacturing a value"
    (is (nil? (:doc (slot ::undocumented-row))))))

;; ---------------------------------------------------------------------------
;; The head, and the `:handler-fn` that is not there
;; ---------------------------------------------------------------------------

(deftest the-entry-carries-the-minted-head-itself
  (testing "`:hicasso/component` is the very value the `def` binds — nothing
            wrapped it on the way into the registrar"
    (is (identical? aliased-row (:hicasso/component (slot ::aliased-row))))
    (is (fn? (:hicasso/component (slot ::aliased-row))))))

(deftest the-entry-has-no-handler-fn-and-rf-view-answers-nil
  (testing "a Hicasso boundary is a React component, not a hiccup-returning
            render fn, so the slot has no `:handler-fn` and `rf/view`'s
            contract stays honest by answering nil"
    (is (not (contains? (slot ::aliased-row) :handler-fn)))
    (is (nil? (rf/view ::aliased-row))))

  (testing "the peer is the control: `rf/view` resolves perfectly well in
            this process, so the nil above is the ENTRY's shape and not a
            broken lookup"
    (is (some? (:handler-fn (slot ::core-peer))))
    (is (some? (rf/view ::core-peer)))))

;; ---------------------------------------------------------------------------
;; The adapter-timing witness (constraint 3)
;; ---------------------------------------------------------------------------

(deftest the-slot-is-written-adapter-neutrally
  (testing "no adapter is installed — the state a `defview` is declared in
            when its namespace loads before `rf/init!`"
    (is (nil? (rf/current-adapter))))

  (testing "the absence of `:handler-fn` IS the proof the adapter path was
            never entered: `re-frame.views/reg-view*` is the only route to
            `apply-adapter-wrap-view`, and it ALWAYS stores the wrapper it
            builds under that key. A slot without one was never near it"
    (is (not (contains? (slot ::aliased-row) :handler-fn))))

  (testing "and a registration made HERE, with no adapter installed, is
            whole — same id, same coord keys, same untouched head"
    (let [head #(:hicasso/component (slot ::aliased-row))
          before (head)]
      (rf.hicasso.impl.collector/publish-view-alias!
        ::aliased-row (select-keys (slot ::aliased-row) coord-keys) before)
      (is (identical? before (head)))
      (is (not (contains? (slot ::aliased-row) :handler-fn))))))

;; ---------------------------------------------------------------------------
;; Hot reload — the registrar's own replacement, and nothing added for it
;; ---------------------------------------------------------------------------

(deftest re-declaring-replaces-the-entry-rather-than-accumulating
  ;; ClojureScript cannot re-evaluate a macro at runtime, so a save is
  ;; driven at the seam the expansion itself calls: the `(when
  ;; debug-enabled? (publish-view-alias! …))` form is the entire
  ;; registration half of `defview`, and re-running it with a fresh head is
  ;; exactly what a reloaded namespace does.
  (let [reloaded  (fn reloaded-row [_] nil)
        coords    (select-keys (slot ::aliased-row) coord-keys)
        before    (count (rf/registrations :view))]

    (rf.hicasso.impl.collector/publish-view-alias! ::aliased-row coords reloaded)

    (testing "one entry, not two — the registrar replaces the slot behind
              the id it already holds"
      (is (= before (count (rf/registrations :view)))))

    (testing "and it is the FRESH head that is reachable, so a story
              resolving late picks up the reloaded view"
      (is (identical? reloaded (:hicasso/component (slot ::aliased-row)))))

    (testing "the shape is unchanged across the reload — still no
              `:handler-fn`, still the coordinate"
      (is (not (contains? (slot ::aliased-row) :handler-fn)))
      (is (= coords (select-keys (slot ::aliased-row) coord-keys))))))

;; ---------------------------------------------------------------------------
;; What the reload TELLS a tool — the `:different-fn?` discriminator
;; ---------------------------------------------------------------------------
;;
;; Replacing the entry is half the contract; the other half is what
;; `register!` says about the replacement, and the row above never looked.
;; `:different-fn?` is derived by default from `:handler-fn`, and this entry
;; has none by design — so the default derivation compares nil with nil and
;; calls a real component rotation idempotent. A hot-reload consumer branching
;; on the tag would decline to refresh on every Hicasso view edit. The slot
;; names `:hicasso/component` as its executable identity under
;; `:executable-key`, and `re-frame.registrar/executable-identity` reads the
;; key the registration named.
;;
;; The HOOK is the seam asserted here rather than the trace bus, and it
;; covers both surfaces: `register!` binds `different?` ONCE and hands the
;; same value to every replacement hook and to the
;; `:rf.registry/handler-replaced` `:tags`. A hook also fires on EVERY
;; re-registration, where the trace emit is additionally dedup-by-shape gated
;; — so the hook can witness the idempotent row below, and the trace cannot.

(defonce ^:private recorded
  ;; nil except while a row below is recording. The registrar has no
  ;; remove-hook door, so the hook installed beside it lives for the whole
  ;; process and MUST stay inert for every other namespace in the shared
  ;; `:node-test` bundle.
  (atom nil))

(defonce ^:private recording-hook
  (do (rf.registrar/add-replacement-hook!
        (fn [m]
          (when @recorded
            (swap! recorded conj (select-keys m [:kind :id :different-fn?])))))
      true))

(defn- while-recording
  "Run `f`, answering the replacement-hook calls it provoked. The
  `finally` is what keeps a failing row from leaving the hook live for
  every namespace that loads after this one."
  [f]
  (reset! recorded [])
  (try (f)
       @recorded
       (finally (reset! recorded nil))))

(deftest a-reloaded-head-is-reported-as-a-real-change-not-an-idempotent-reload
  (let [coords (select-keys (slot ::aliased-row) coord-keys)
        head   #(:hicasso/component (slot ::aliased-row))]

    (testing "the slot names where its executable identity lives, which is
              what lets the registrar tell the two cases apart without
              learning anything about Hicasso"
      (is (= :hicasso/component (:executable-key (slot ::aliased-row))))
      (is (contains? (slot ::aliased-row) (:executable-key (slot ::aliased-row)))))

    (testing "a save that changes the boundary's body mints a NEW head, and
              the replacement is reported as a real change — the tag a
              devtool refreshes on"
      (let [rotated (fn rotated-row [_] nil)
            calls   (while-recording
                      #(rf.hicasso.impl.collector/publish-view-alias! ::aliased-row coords rotated))]
        (is (= 1 (count calls)) "exactly one replacement, not zero and not two")
        (is (= {:kind :view :id ::aliased-row :different-fn? true}
               (first calls)))))

    (testing "and it is a DISCRIMINATOR, not a constant: re-publishing the
              head already in the slot is the idempotent reload it looks
              like, and still says so"
      (let [same  (head)
            calls (while-recording
                    #(rf.hicasso.impl.collector/publish-view-alias! ::aliased-row coords same))]
        (is (= 1 (count calls)) "the hook fires on every re-registration")
        (is (= {:kind :view :id ::aliased-row :different-fn? false}
               (first calls)))))))
