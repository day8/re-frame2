(ns re-frame.marks-test
  "Unit + integration tests for Spec 015 — Data Classification.

  Covers:
    - `add-marks` / `set-marks` API + additive-vs-replace semantics
    - per-registration mark extraction across the 7 reg-* sites
    - emit-time trace projection (event / fx / cofx / sub)
    - sub auto-propagation + opt-out
    - schema-attached marks union with `add-marks` / `set-marks`"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.frame-classification :as frame-class]
            [re-frame.marks :as marks]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace-tooling/clear-listeners!)
  (marks/clear-marks!)
  (marks/clear-sub-output-marks!)
  (elision/clear-warning-cache!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-gjq3ow): `init!` no longer synthesises `:rf/default`, and
  ;; the marks / elision surfaces are frame-scoped — `add-marks` /
  ;; `set-marks` write into a frame's runtime-db elision slot (a no-op on an
  ;; unregistered frame), and the zero-arity `elide-wire-value` /
  ;; `project-trace-event` projections resolve the carried scope (failing
  ;; closed under none). Register `:rf/default` (so the explicit-frame calls
  ;; have a container) and pin it as the ambient scope (so the zero-arity
  ;; projection calls carry a stamp) — the carried-invariant equivalent of
  ;; wrapping the body in `(with-frame :rf/default …)`.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (trace-tooling/register-listener! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- add-marks / set-marks API ------------------------------------------

(deftest set-marks-writes-into-elision-registry
  (marks/set-marks :rf/default
    {[:user :ssn]      :sensitive
     [:auth :token]    :sensitive
     [:docs :csv-upload] :large})
  (let [decls-s (elision/sensitive-declarations :rf/default)
        decls-l (elision/declarations :rf/default)]
    (is (contains? decls-s [:user :ssn]))
    (is (contains? decls-s [:auth :token]))
    (is (= :marks (:source (get decls-s [:user :ssn]))))
    (is (contains? decls-l [:docs :csv-upload]))
    (is (= :marks (:source (get decls-l [:docs :csv-upload]))))))

(deftest add-marks-writes-into-elision-registry
  (marks/add-marks :rf/default
    {[:user :ssn]      :sensitive
     [:docs :csv-upload] :large})
  (let [decls-s (elision/sensitive-declarations :rf/default)
        decls-l (elision/declarations :rf/default)]
    (is (contains? decls-s [:user :ssn]))
    (is (= :marks (:source (get decls-s [:user :ssn]))))
    (is (contains? decls-l [:docs :csv-upload]))))

(deftest add-marks-returns-frame-id
  (is (= :rf/default (marks/add-marks :rf/default {[:x] :sensitive})))
  (rf/reg-frame :other {:doc "test"})
  (is (= :other (marks/add-marks :other {[:y] :sensitive}))))

(deftest set-marks-returns-frame-id
  (is (= :rf/default (marks/set-marks :rf/default {[:x] :sensitive}))))

(deftest set-marks-replaces-not-merges
  ;; Spec 015 §App-db marks: set-marks REPLACES the previous set
  (marks/set-marks :rf/default {[:a] :sensitive [:b] :sensitive})
  (marks/set-marks :rf/default {[:c] :sensitive})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:c]))
    (is (not (contains? decls [:a])))
    (is (not (contains? decls [:b])))))

(deftest add-marks-merges-not-replaces
  ;; Spec 015 §App-db marks: add-marks MERGES additively
  (marks/add-marks :rf/default {[:a] :sensitive [:b] :sensitive})
  (marks/add-marks :rf/default {[:c] :sensitive})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:a]) "first add survives second add")
    (is (contains? decls [:b]) "first add survives second add")
    (is (contains? decls [:c]) "second add lands")))

(deftest add-marks-last-write-wins-per-path
  ;; A path appearing in two add-marks calls — last call's mark wins
  (marks/add-marks :rf/default {[:p] :sensitive})
  (marks/add-marks :rf/default {[:p] :large})
  (let [s (elision/sensitive-declarations :rf/default)
        l (elision/declarations :rf/default)]
    ;; After the second add-marks, [:p] is now :large; it should
    ;; still appear in the sensitive map because add-marks does
    ;; not retract previously-added paths. This is the documented
    ;; "additive" semantic. Authors who want full replacement use
    ;; set-marks.
    (is (contains? s [:p]) "additive — prior sensitive entry remains")
    (is (contains? l [:p]) "new large entry added")))

(deftest set-marks-after-add-marks-replaces
  ;; set-marks called after add-marks fully replaces — clears prior :marks entries
  (marks/add-marks :rf/default {[:a] :sensitive [:b] :large})
  (marks/set-marks :rf/default {[:c] :sensitive})
  (let [s (elision/sensitive-declarations :rf/default)
        l (elision/declarations :rf/default)]
    (is (contains? s [:c]))
    (is (not (contains? s [:a])))
    (is (not (contains? l [:b])))))

(deftest add-marks-preserves-frame-sourced
  ;; EP-0015 §8: durable app-db classification is frame-owned
  ;; (:source :frame). add-marks (:source :marks) must not drop it — the
  ;; two sources union at lookup, the same invariant that formerly held for
  ;; schema-sourced declarations.
  (frame-class/install! :rf/default
    (frame-class/validate+extract :rf/default
      {:sensitive {:app-db [[:auth :password]]}}))
  (marks/add-marks :rf/default {[:user :ssn] :sensitive})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:user :ssn]) "add-marks path present")
    (is (contains? decls [:auth :password]) "frame-sourced path preserved")
    (is (= :frame (:source (get decls [:auth :password]))))))

(deftest set-marks-preserves-frame-sourced
  ;; EP-0015 §8: set-marks (:source :marks, replace) must preserve the
  ;; frame-owned (:source :frame) declarations — it only replaces its own.
  (frame-class/install! :rf/default
    (frame-class/validate+extract :rf/default
      {:sensitive {:app-db [[:auth :password]]}}))
  (marks/set-marks :rf/default {[:a] :sensitive})
  (marks/set-marks :rf/default {[:b] :sensitive})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:b]))
    (is (not (contains? decls [:a])))
    (is (contains? decls [:auth :password]))))

;; ---- per-registration mark extraction -----------------------------------

(deftest reg-event-db-return-stashes-marks
  (rf/reg-event :evt
    {:sensitive [[:password]] :large [[:upload]]}
    (fn [{:keys [db]} _] {:db db}))
  (let [m (marks/marks-for :event :evt)]
    (is (= [[:password]] (:sensitive m)))
    (is (= [[:upload]] (:large m)))))

(deftest reg-event-fx-return-stashes-marks
  (rf/reg-event :evt
    {:sensitive [[:totp]]}
    (fn [_ _] {}))
  (is (= [[:totp]] (:sensitive (marks/marks-for :event :evt)))))

(deftest reg-event-with-interceptor-stashes-marks
  (rf/reg-interceptor* :evt/ctx-probe {:before (fn [ctx] ctx)})
  (rf/reg-event :evt
    {:large [[:body :blob]]
     :interceptors [:evt/ctx-probe]}
    (fn [_ _] {}))
  (is (= [[:body :blob]] (:large (marks/marks-for :event :evt)))))

(deftest reg-sub-stashes-marks-and-overrides
  (rf/reg-sub :s1
    {:sensitive [[:hashed]] :rf.egress/output-sensitivity :rf.egress/public}
    (fn [_ _] {:hashed "abc"}))
  (let [m (marks/marks-for :sub :s1)]
    (is (= [[:hashed]] (:sensitive m)))
    (is (= :rf.egress/public (:output-sensitivity m))
        "the derived-output declassify claim is stashed as :output-sensitivity")))

(deftest reg-sub-rejects-sensitive-boolean-overload
  ;; The rejected `:sensitive?` declassify/force spelling on a derived (sub)
  ;; output throws at registration — EP-0015 issue 9, Spec 015:425.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/bad-marks"
        (rf/reg-sub :s/rejected
          {:sensitive? false}
          (fn [_ _] {}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/bad-marks"
        (rf/reg-sub :s/rejected2
          {:sensitive? true}
          (fn [_ _] {})))))

(deftest reg-sub-rejects-unknown-output-sensitivity
  ;; Fail-closed: an unknown enum value throws (the enum is closed; a typo is
  ;; never a silent permissive inherit).
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/bad-marks"
        (rf/reg-sub :s/typo
          {:rf.egress/output-sensitivity :rf.egress/publik}
          (fn [_ _] {})))))

(deftest reg-fx-stashes-marks
  (rf/reg-fx :myfx
    {:sensitive [[:body :password]] :large [[:body :upload]]}
    (fn [_ _]))
  (let [m (marks/marks-for :fx :myfx)]
    (is (= [[:body :password]] (:sensitive m)))
    (is (= [[:body :upload]] (:large m)))))

(deftest reg-cofx-stashes-marks-whole-value
  (rf/reg-cofx :auth/jwt
    {:sensitive [[]]}
    (fn [] "jwt"))
  (is (= [[]] (:sensitive (marks/marks-for :cofx :auth/jwt)))))

;; ---- malformed :sensitive / :large rejection (rf2-y7l5t5) ----------------
;;
;; A `:sensitive` / `:large` declaration is a vector of output-path vectors
;; (`[[:user :ssn]]`; `[[]]` marks the whole value). The prior `coerce-paths`
;; SILENTLY `(filter vector?)`-DROPPED any non-vector entry and coerced a
;; non-collection whole to `[]`, so a typo (`:sensitive :token`, `"blob"`, a
;; map, or a bare-keyword / nested-non-vector entry) registered with NO error
;; and NO mark — the author believed a path was protected when it was NOT
;; (EP-0005 armed-trap, marks-ingestion side). It now REJECTS LOUDLY with
;; `:rf.error/bad-marks` before the marks table mutates — mirroring the
;; flows-side fix (rf2-cgk0wb / `:rf.error/flow-bad-marks`).

(defn- bad-marks-data
  "Return the ex-data of the throw from registering `:probe` as an event with
  `meta`, or nil if it did not throw. The marks are now VALIDATED fail-loud at
  the reg-* boundary (rf2-ehexnw) before the registrar write, so a malformed
  declaration throws here and `:probe` never lands; on success `:probe` is a
  no-op event registration the per-test reset rolls back."
  [meta]
  (try
    (rf/reg-event :probe meta (fn [_ _] nil))
    nil
    (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest register-marks-rejects-bare-keyword-whole
  (testing ":sensitive :token (a bare keyword, not a vector-of-paths) is rejected"
    (let [data (bad-marks-data {:sensitive :token})]
      (is (some? data) "reg-event threw at the marks-validation boundary")
      (is (= :rf.error/bad-marks (:rf.error/id data)) ":rf.error/id discriminator")
      (is (= 'rf/reg-marks (:where data)))
      (is (= :fix-registration (:recovery data)))
      (is (= :sensitive (:bad-key data)) ":bad-key names the offending key")
      (is (= :token (:bad-value data)) ":bad-value names the non-vector whole")
      (is (nil? (marks/marks-for :event :probe)) "no marks landed on rejection"))))

(deftest register-marks-rejects-string-whole
  (testing ":large \"blob\" (a string) is rejected with :bad-value"
    (let [data (bad-marks-data {:large "blob"})]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= :large (:bad-key data)))
      (is (= "blob" (:bad-value data)))
      (is (nil? (marks/marks-for :event :probe))))))

(deftest register-marks-rejects-map-whole
  (testing ":sensitive {…} (a map) is rejected with :bad-value"
    (let [data (bad-marks-data {:sensitive {:user :ssn}})]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= :sensitive (:bad-key data)))
      (is (= {:user :ssn} (:bad-value data)))
      (is (nil? (marks/marks-for :event :probe))))))

(deftest register-marks-rejects-bare-keyword-entry
  (testing ":sensitive [:token] (a bare-keyword entry, not a subpath vector) is rejected"
    (let [data (bad-marks-data {:sensitive [:token]})] ; should be [[:token]]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= :sensitive (:bad-key data)))
      (is (= [:token] (:bad-entries data)) ":bad-entries names the malformed entry")
      (is (nil? (marks/marks-for :event :probe))))))

(deftest register-marks-rejects-nested-non-vector-entry
  (testing ":large [[:a [:b]]] (a non-scalar path element) is rejected"
    (let [data (bad-marks-data {:large [[:a [:b]]]})]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= :large (:bad-key data)))
      (is (= [[:a [:b]]] (:bad-entries data)) ":bad-entries names the malformed entry")
      (is (nil? (marks/marks-for :event :probe)))))
  (testing ":large [{}] (a map entry) is rejected"
    (let [data (bad-marks-data {:large [{}]})]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= [{}] (:bad-entries data))))))

(deftest register-marks-names-only-the-malformed-entry
  (testing ":bad-entries carries ONLY the malformed entries, not the well-formed ones"
    (let [data (bad-marks-data {:sensitive [[:ok] :bad [:also :ok]]})]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= [:bad] (:bad-entries data))
          "the two well-formed vector entries are not listed"))))

(deftest reg-event-rejects-malformed-marks-no-stash
  ;; The rejection fires at the reg-* boundary (validate-marks! is called from
  ;; reg-event / reg-sub / reg-fx / reg-cofx BEFORE the registrar write).
  (testing "reg-event with a malformed :sensitive throws and lands nothing"
    (let [ex (try (rf/reg-event :malformed/evt
                    {:sensitive [:password]} ; should be [[:password]]
                    (fn [{:keys [db]} _] {:db db}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "reg-event threw")
      (is (= :rf.error/bad-marks (:rf.error/id (ex-data ex))))
      (is (nil? (marks/marks-for :event :malformed/evt))
          "no marks entry stashed for the rejected registration"))))

(deftest reg-sub-rejects-malformed-marks
  (testing "reg-sub with a malformed :large throws"
    (let [ex (try (rf/reg-sub :malformed/sub
                    {:large "blob"}
                    (fn [_ _] {}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/bad-marks (:rf.error/id (ex-data ex))))
      (is (nil? (marks/marks-for :sub :malformed/sub))))))

(deftest register-marks-accepts-whole-value-and-empty
  ;; Regression guard: the tightened validator must NOT reject the legitimate
  ;; whole-value mark `[[]]`, an empty declaration `[]`, or well-formed paths.
  (testing "[[]] whole-value, [] empty, and ordinary paths all register cleanly"
    (rf/reg-event :wv {:sensitive [[]] :large [[:docs :csv]]} (fn [_ _] nil))
    (let [m (marks/marks-for :event :wv)]
      (is (= [[]] (:sensitive m)) "[[]] whole-value mark accepted")
      (is (= [[:docs :csv]] (:large m)) "ordinary path accepted"))
    (rf/reg-event :empty {:sensitive []} (fn [_ _] nil))
    ;; An empty :sensitive normalises to [] (no paths); the entry is dropped on
    ;; read because it carries no marks — but it did NOT throw.
    (is (nil? (:sensitive (marks/marks-for :event :empty)))
        "an empty :sensitive vector is accepted (no throw, no paths)")))

;; ---- EP-0012 shared segment domain (rf2-94o54l.2 / .6) -------------------
;;
;; Mark-path segment validity is the SHARED `re-frame.path/segment?` domain,
;; not a private re-enumeration. The prior private `valid-mark-element?`
;; omitted UUID / instant / nil segments — perfectly valid `get-in` path
;; segments under the EP-0012 segment domain — so a `:sensitive [[:user
;; #uuid "…"]]` mark against a uuid-keyed map was silently rejected. These
;; tests pin that the shared segment types are now ACCEPTED.

(deftest register-marks-accepts-uuid-segment
  (testing "rf2-94o54l.2 — a UUID path segment (valid EP-0012 segment, was
            omitted by the private validator) is accepted"
    (let [uid (java.util.UUID/randomUUID)]
      (rf/reg-event :uuid-seg {:sensitive [[:invoices uid :total]]} (fn [_ _] nil))
      (is (= [[:invoices uid :total]] (:sensitive (marks/marks-for :event :uuid-seg)))
          "a UUID segment registers — shared segment domain, not the narrow private one"))))

(deftest register-marks-accepts-instant-segment
  (testing "rf2-94o54l.2 — an instant path segment (valid EP-0012 segment)
            is accepted"
    (let [inst (java.time.Instant/parse "2026-06-12T00:00:00Z")]
      (rf/reg-event :inst-seg {:large [[:audit inst :payload]]} (fn [_ _] nil))
      (is (= [[:audit inst :payload]] (:large (marks/marks-for :event :inst-seg)))
          "an instant segment registers"))))

(deftest register-marks-accepts-nil-segment
  (testing "rf2-94o54l.2 — a nil path segment (valid EP-0012 segment, a real
            map key) is accepted — distinct from the [[]] whole-value mark"
    (rf/reg-event :nil-seg {:sensitive [[:by-key nil :secret]]} (fn [_ _] nil))
    (is (= [[:by-key nil :secret]] (:sensitive (marks/marks-for :event :nil-seg)))
        "a nil segment registers — segment? admits nil")))

(deftest register-marks-still-rejects-composite-segment
  (testing "rf2-94o54l.6 — widening to the shared domain did NOT loosen the
            composite-segment rejection: a nested-vector element is still a
            loud :rf.error/bad-marks (segment? rejects composites)"
    (let [data (bad-marks-data {:sensitive [[:a [:b]]]})]
      (is (= :rf.error/bad-marks (:rf.error/id data)))
      (is (= [[:a [:b]]] (:bad-entries data))
          ":bad-entries names the composite-segment entry"))))

;; ---- add-marks / set-marks fail-closed on unknown mark (rf2-94o54l.6) ----
;;
;; `split-by-mark` used to SILENTLY DROP a mark value outside
;; `#{:sensitive :large}`, so a typoed mark (`:sensitivee`) made the author
;; believe a path was redacted while the declaration was quietly discarded —
;; a privacy footgun (Conventions §No silent swallow). It now FAILS CLOSED.

(deftest add-marks-rejects-unknown-mark-value
  (testing "rf2-94o54l.6 — a typoed mark value fails closed with
            :rf.error/bad-marks naming the offending path + mark"
    (let [ex (try (marks/add-marks :rf/default {[:user :ssn] :sensitivee})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "add-marks threw on the unknown mark")
      (let [data (ex-data ex)]
        (is (= :rf.error/bad-marks (:rf.error/id data)) ":rf.error/id discriminator")
        (is (= [:user :ssn] (:bad-path data)) ":bad-path names the offending path")
        (is (= :sensitivee (:bad-mark data)) ":bad-mark names the typoed value")
        (is (= #{:sensitive :large} (:valid data)) ":valid lists the closed mark set")))
    (is (empty? (elision/sensitive-declarations :rf/default))
        "no marks landed — the rejected declaration did not partially apply")))

(deftest set-marks-rejects-unknown-mark-value
  (testing "rf2-94o54l.6 — set-marks shares the split-by-mark chokepoint, so a
            typoed mark fails closed too"
    (let [ex (try (marks/set-marks :rf/default {[:docs :csv] :largeish})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/bad-marks (:rf.error/id (ex-data ex))))
      (is (= :largeish (:bad-mark (ex-data ex)))))))

(deftest add-marks-normalizes-seq-path-to-vector
  (testing "rf2-94o54l.2 — a non-vector seq path key normalizes to its
            canonical vector form in the elision registry"
    (marks/add-marks :rf/default {(list :user :ssn) :sensitive})
    (let [decls (elision/sensitive-declarations :rf/default)]
      (is (contains? decls [:user :ssn])
          "the seq path is stored under the canonical vector key")
      (is (every? vector? (keys decls))
          "every declaration key is a canonical vector"))))

;; ---- union-marks! is GONE (rf2-ehexnw) -----------------------------------
;;
;; The table-level additive-merge `union-marks!` and its sibling stash
;; `register-marks!` are DELETED: author marks are now DERIVED from the
;; registrar meta at `marks-for` read time, so re-registering a (kind, id)
;; REPLACES its marks (registrar slot semantics) — there is no per-(kind, id)
;; "merge into the existing entry" operation any more. The schema-vs-author
;; UNION (the only union that mattered) survives via the separate
;; `machine-id->schema-marks` table + the read-time merge in `marks-for`,
;; exercised by the `machine-schema-marks-*` tests below. The deleted
;; `union-marks!` tests (creates-when-absent / unions-with-existing / dedups /
;; noop-without-paths + the output-sensitivity monotone-OR cases) tested that
;; removed table-level merge; the monotone-OR union helpers they exercised are
;; still covered through the schema-vs-author path. The malformed-rejection
;; that `union-marks-rejects-malformed` covered is now covered at the reg-*
;; boundary by `reg-sub-rejects-malformed-marks` / `reg-event-rejects-
;; malformed-marks-no-stash` (the only ingestion path that remains).

;; ---- machine schema marks: order-independent union (rf2-qpibk0) ----------
;; Schema-sourced machine marks live in a SEPARATE table that `marks-for
;; :event <id>` unions with the REGISTRAR-DERIVED author `:event` marks at read
;; time, so the schema-vs-author composition is order-independent and a
;; re-registration of the `:event` entry can never drop schema-derived marks.
;; The author side is now expressed by registering the (machine) id as an event
;; with the author marks in its reg meta (rf2-ehexnw) — the registrar holds it,
;; `marks-for` derives it.

(deftest machine-schema-marks-union-with-author-marks-at-read-time
  (marks/declare-machine-schema-marks! :m/auth {:sensitive [[:data :token]]
                                                :large     [[:data :blob]]})
  (rf/reg-event :m/auth {:sensitive [[:data :session-id]]} (fn [_ _] nil))
  (let [m (marks/marks-for :event :m/auth)]
    (is (= #{[:data :token] [:data :session-id]} (set (:sensitive m)))
        "schema-sourced and author-sourced sensitive paths union at read time")
    (is (= #{[:data :blob]} (set (:large m)))
        "schema-sourced large path present")))

(deftest machine-schema-marks-order-independent-author-after-schema
  (marks/declare-machine-schema-marks! :m/o1 {:sensitive [[:data :token]]})
  (rf/reg-event :m/o1 {:sensitive [[:data :extra]]} (fn [_ _] nil))
  (is (= #{[:data :token] [:data :extra]}
         (set (:sensitive (marks/marks-for :event :m/o1))))
      "schema-first then author yields the union"))

(deftest machine-schema-marks-order-independent-schema-after-author
  (rf/reg-event :m/o2 {:sensitive [[:data :extra]]} (fn [_ _] nil))
  (marks/declare-machine-schema-marks! :m/o2 {:sensitive [[:data :token]]})
  (is (= #{[:data :token] [:data :extra]}
         (set (:sensitive (marks/marks-for :event :m/o2))))
      "author-first then schema yields the IDENTICAL union (order-independent)"))

(deftest re-registering-event-entry-does-not-drop-schema-marks
  ;; A bare-meta re-registration REPLACES the author `:event` registrar slot,
  ;; but the schema-sourced marks live in a separate table and survive — the
  ;; order-independence invariant.
  (marks/declare-machine-schema-marks! :m/o3 {:sensitive [[:data :token]]})
  (rf/reg-event :m/o3 {:sensitive [[:data :extra]]} (fn [_ _] nil))
  ;; Bare-meta re-registration clears the author marks (registrar slot replace).
  (rf/reg-event :m/o3 (fn [_ _] nil))
  (is (= #{[:data :token]} (set (:sensitive (marks/marks-for :event :m/o3))))
      "schema-sourced [:data :token] survives the author-entry clear"))

(deftest clear-machine-schema-marks-drops-only-schema-entry
  (marks/declare-machine-schema-marks! :m/c1 {:sensitive [[:data :token]]})
  (rf/reg-event :m/c1 {:sensitive [[:data :extra]]} (fn [_ _] nil))
  (marks/clear-machine-schema-marks! :m/c1)
  (let [m (marks/marks-for :event :m/c1)]
    (is (= #{[:data :extra]} (set (:sensitive m)))
        "clearing the schema entry leaves the author-sourced marks intact")))

(deftest declare-machine-schema-marks-nil-clears-entry
  (marks/declare-machine-schema-marks! :m/c2 {:sensitive [[:data :token]]})
  (marks/declare-machine-schema-marks! :m/c2 nil)
  (is (nil? (marks/marks-for :event :m/c2))
      "declaring nil clears the schema entry (and no author entry exists)"))

;; ---- emit-time projection ----------------------------------------------

(deftest event-arg-sensitive-path-redacts-in-trace
  ;; Spec 015 conformance fixture #1
  (rf/reg-event :auth/log-in
    {:sensitive [[:password]]}
    (fn [_ _] {}))
  (let [traces (collect-traces! :evt-redact)]
    (rf/dispatch-sync [:auth/log-in {:email "a@b.c" :password "secret"}])
    (let [dispatched (filter #(= :rf.event/dispatched (:operation %)) @traces)]
      (is (seq dispatched))
      (let [ev (-> dispatched first :tags :rf.event/v)]
        (is (= :rf/redacted (get-in ev [1 :password])))
        (is (= "a@b.c" (get-in ev [1 :email])))))
    (trace-tooling/unregister-listener! :evt-redact)))

(deftest fx-args-sensitive-path-redacts-in-trace
  (rf/reg-fx :myfx
    {:sensitive [[:headers :authorization]]}
    (fn [_ _]))
  (rf/reg-event :send
    (fn [_ _] {:fx [[:myfx {:headers {:authorization "Bearer xyz"
                                      :other "ok"}}]]}))
  (let [traces (collect-traces! :fx-redact)]
    (rf/dispatch-sync [:send])
    (let [handled (filter #(= :rf.fx/handled (:operation %)) @traces)]
      (is (seq handled))
      (let [args (-> handled first :tags :rf.fx/args)]
        (is (= :rf/redacted (get-in args [:headers :authorization])))
        (is (= "ok" (get-in args [:headers :other])))))
    (trace-tooling/unregister-listener! :fx-redact)))

(deftest large-path-emits-marker
  (rf/reg-event :evt
    {:large [[:blob]]}
    (fn [_ _] {}))
  (let [traces (collect-traces! :large-marker)
        big    (apply str (repeat 500 "X"))]
    (rf/dispatch-sync [:evt {:blob big :other "small"}])
    (let [dispatched (filter #(= :rf.event/dispatched (:operation %)) @traces)
          ev         (-> dispatched first :tags :rf.event/v)
          slot       (get-in ev [1 :blob])]
      (is (elision/marker? slot))
      (is (= "small" (get-in ev [1 :other])))
      (is (= [:blob] (get-in slot [:rf.size/large-elided :path])))
      (is (pos-int? (get-in slot [:rf.size/large-elided :bytes]))))
    (trace-tooling/unregister-listener! :large-marker)))

(deftest empty-path-redacts-whole-arg
  (rf/reg-event :evt
    {:sensitive [[]]}
    (fn [_ _] {}))
  (let [traces (collect-traces! :whole)]
    (rf/dispatch-sync [:evt {:a 1 :b 2}])
    (let [ev (-> (filter #(= :rf.event/dispatched (:operation %)) @traces)
                 first :tags :rf.event/v)]
      (is (= :rf/redacted (nth ev 1))))
    (trace-tooling/unregister-listener! :whole)))

;; ---- :rf.event/db (t1/t2 pending-db snapshot) redaction (rf2-6773q) ------
;;
;; The `:rf.event/db-pending` (t1) and `:rf.event/db-pending-post-flow`
;; (t2) trace events stamp the FULL pending `app-db` under `:tags
;; :rf.event/db` (router `flows-after-interceptor`, rf2-ta0y7). Because
;; the slot carries the whole db, its declared paths are the FRAME's
;; app-db elision registry — schema `:sensitive?` / `:large?` plus
;; `add-marks` / `set-marks` — rooted at the db root. The marks
;; chokepoint routes the slot through `elide-wire-value` so sensitive
;; slots elide BEFORE the snapshot reaches any trace listener / epoch
;; sink. NB: the elision declaration registry lives in the runtime-db
;; partition (`[:rf.runtime/elision]`, EP-0001), so an app-db replacement no
;; longer wipes it; we still seed, then mark, then write into the marked path
;; (mirrors `sub-auto-propagates-when-app-db-has-sensitive-marks`).

(deftest db-pending-sensitive-app-db-path-redacts
  (rf/reg-event :db/seed (fn [{:keys [db]} _] {:db {:user {:ssn nil :name "A"}}}))
  (rf/dispatch-sync [:db/seed])
  (marks/set-marks :rf/default {[:user :ssn] :sensitive})
  (rf/reg-event :db/write-ssn
    (fn [{:keys [db]} _] {:db (assoc-in db [:user :ssn] "123-45-6789")}))
  (let [traces (collect-traces! :db-sensitive)]
    (rf/dispatch-sync [:db/write-ssn])
    (let [[t1] (filterv #(= :rf.event/db-pending (:operation %)) @traces)
          db   (-> t1 :tags :rf.event/db)]
      (is (some? t1) ":rf.event/db-pending fired")
      (is (= :rf/redacted (get-in db [:user :ssn]))
          "the sensitive app-db slot is redacted in the t1 db snapshot")
      (is (= "A" (get-in db [:user :name]))
          "an unmarked sibling slot passes through unchanged"))
    (trace-tooling/unregister-listener! :db-sensitive)))

(deftest db-pending-large-app-db-path-emits-marker
  (rf/reg-event :db/seed (fn [{:keys [db]} _] {:db {:docs {:csv nil :note "ok"}}}))
  (rf/dispatch-sync [:db/seed])
  (marks/set-marks :rf/default {[:docs :csv] :large})
  (let [big (apply str (repeat 500 "X"))]
    (rf/reg-event :db/write-csv
      (fn [{:keys [db]} _] {:db (assoc-in db [:docs :csv] big)}))
    (let [traces (collect-traces! :db-large)]
      (rf/dispatch-sync [:db/write-csv])
      (let [[t1] (filterv #(= :rf.event/db-pending (:operation %)) @traces)
            slot (-> t1 :tags :rf.event/db (get-in [:docs :csv]))]
        (is (some? t1) ":rf.event/db-pending fired")
        (is (elision/marker? slot)
            "the large app-db slot elides to an :rf.size/large-elided marker")
        (is (= [:docs :csv] (get-in slot [:rf.size/large-elided :path])))
        (is (= "ok" (-> t1 :tags :rf.event/db (get-in [:docs :note])))
            "an unmarked sibling slot passes through unchanged"))
      (trace-tooling/unregister-listener! :db-large))))

(deftest db-pending-unmarked-db-passes-through-by-reference
  ;; No marks / declarations on the frame → the t1 `:rf.event/db` stamp
  ;; is the SAME persistent reference the handler returned (rf2-ta0y7's
  ;; copy-free posture). The marks chokepoint must NOT rebuild the value
  ;; when there is nothing to elide.
  (let [payload {:counter 1 :nested {:k :v}}]
    (rf/reg-event :db/return-shared (fn [{:keys [db]} _] {:db payload}))
    (let [traces (collect-traces! :db-unmarked)]
      (rf/dispatch-sync [:db/return-shared])
      (let [[t1] (filterv #(= :rf.event/db-pending (:operation %)) @traces)]
        (is (some? t1) ":rf.event/db-pending fired")
        (is (identical? payload (-> t1 :tags :rf.event/db))
            "unmarked db snapshot is the same reference (no walk, no copy)"))
      (trace-tooling/unregister-listener! :db-unmarked))))

(deftest db-pending-frame-sensitive-app-db-path-redacts
  ;; EP-0015 §8: frame-owned `:sensitive {:app-db …}` classification (not
  ;; add-marks) drives the t1 db-snapshot redaction — the chokepoint reads
  ;; the frame elision registry via `elide-wire-value`.
  (frame-class/install! :rf/default
    (frame-class/validate+extract :rf/default
      {:sensitive {:app-db [[:auth :token]]}}))
  (rf/reg-event :db/login
    (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "Bearer xyz")}))
  (let [traces (collect-traces! :db-schema-sensitive)]
    (rf/dispatch-sync [:db/login])
    (let [[t1] (filterv #(= :rf.event/db-pending (:operation %)) @traces)]
      (is (some? t1) ":rf.event/db-pending fired")
      (is (= :rf/redacted (-> t1 :tags :rf.event/db (get-in [:auth :token])))
          "the frame-declared sensitive slot is redacted in the db snapshot"))
    (trace-tooling/unregister-listener! :db-schema-sensitive)))

;; ---- :rf.view/render-args (view render args/props) redaction (rf2-rpgq8) -
;;
;; The `:rf.view/rendered` op stamps the view's positional render args/props
;; under `:rf.view/render-args` (a vector). A view is not a handler with
;; per-registration marks, so — like `:rf.event/db` — the declared paths are
;; the FRAME's app-db elision registry. The chokepoint (`project-view-
;; rendered-tags`, wired into `project-trace-event`) routes EACH positional
;; arg through `elide-wire-value` against that registry.

(deftest view-render-args-frame-sensitive-path-redacts
  (frame-class/install! :rf/default
    (frame-class/validate+extract :rf/default
      {:sensitive {:app-db [[:auth :password]]}}))
  (let [projected (marks/project-trace-event
                    {:operation :rf.view/rendered
                     :op-type   :rf.view
                     :tags      {:rf.view/id          :sample/view
                                 :rf.view/render-args [{:auth {:password "hunter2"
                                                               :username "ada"}}
                                                       {:public "ok"}]
                                 :frame               :rf/default}})
        args (get-in projected [:tags :rf.view/render-args])]
    (is (= :rf/redacted (get-in (first args) [:auth :password]))
        "the frame-declared sensitive leaf inside a render arg is redacted")
    (is (= "ada" (get-in (first args) [:auth :username]))
        "a non-sensitive sibling leaf is preserved")
    (is (= {:public "ok"} (second args))
        "a second positional arg with no sensitive path passes through")))

(deftest view-render-args-untouched-without-declarations
  ;; No marks / declarations on the frame → the render-args vector passes
  ;; through by reference (the chokepoint must not walk when there is
  ;; nothing to elide — mirrors the :rf.event/db copy-free posture).
  (let [args [{:any "data"} 42]
        projected (marks/project-trace-event
                    {:operation :rf.view/rendered
                     :op-type   :rf.view
                     :tags      {:rf.view/id          :plain/view
                                 :rf.view/render-args args
                                 :frame               :rf/default}})]
    (is (identical? args (get-in projected [:tags :rf.view/render-args]))
        "no declarations → render-args is the same reference (no walk)")))

;; ---- redact-with-paths primitive ---------------------------------------

(deftest redact-with-paths-walks-nested
  (let [v {:user {:ssn "123-45-6789" :name "Alice"}
           :auth {:token "abc" :method :jwt}}
        out (marks/redact-with-paths v [[:user :ssn] [:auth :token]] [])]
    (is (= :rf/redacted (get-in out [:user :ssn])))
    (is (= "Alice" (get-in out [:user :name])))
    (is (= :rf/redacted (get-in out [:auth :token])))
    (is (= :jwt (get-in out [:auth :method])))))

(deftest redact-with-paths-noop-when-no-paths
  (let [v {:a 1 :b 2}]
    (is (identical? v (marks/redact-with-paths v [] [])))))

(deftest redact-with-paths-empty-path-whole-value
  (is (= :rf/redacted (marks/redact-with-paths {:x 1} [[]] []))))

(deftest redact-with-paths-missing-path-noop
  (let [v {:user {:name "Alice"}}
        out (marks/redact-with-paths v [[:user :ssn] [:nope :nope]] [])]
    ;; Missing leaves are no-op per Spec 015 §What gets a sentinel
    (is (= "Alice" (get-in out [:user :name])))
    (is (nil? (get-in out [:user :ssn])))))

;; ---- sub auto-propagation ----------------------------------------------

(deftest sub-auto-propagates-when-app-db-has-sensitive-marks
  ;; Spec 015 conformance fixture #3 (variant — propagation flag set)
  ;; NB: the elision declaration registry lives in the runtime-db partition
  ;; (`[:rf.runtime/elision]`, EP-0001), so an app-db replacement no longer
  ;; wipes it; seed first, then mark, as for schema-driven elision.
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:user {:ssn "X" :name "A"}}}))
  (rf/dispatch-sync [:seed])
  (marks/set-marks :rf/default {[:user :ssn] :sensitive})
  (rf/reg-sub :all-users (fn [db _] (:user db)))
  @(rf/subscribe [:all-users])
  ;; Layer-1 sub with sensitive declarations present → propagation flag set
  (is (true? (marks/sub-output-sensitive? :rf/default :all-users))))

(deftest sub-explicit-public-clears-propagation
  ;; Spec 015 §Derived sensitivity — the conformance
  ;; `derived-output-declassified-public.edn` fixture: a sub reading a
  ;; sensitive input with `:rf.egress/output-sensitivity :rf.egress/public`
  ;; DECLASSIFIES — its output is NOT marked sensitive despite the sensitive
  ;; input (replaces the rejected `:sensitive? false` spelling).
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:user {:ssn "X" :name "A"}}}))
  (rf/dispatch-sync [:seed])
  (marks/set-marks :rf/default {[:user :ssn] :sensitive})
  (rf/reg-sub :safe
    {:rf.egress/output-sensitivity :rf.egress/public}
    (fn [db _] (get-in db [:user :name])))
  @(rf/subscribe [:safe])
  (is (false? (marks/sub-output-sensitive? :rf/default :safe))))

(deftest sub-force-marked
  ;; :rf.egress/output-sensitivity :rf.egress/sensitive forces sensitive even
  ;; with no sensitive upstream input.
  (rf/reg-sub :synthetic
    {:rf.egress/output-sensitivity :rf.egress/sensitive}
    (fn [_ _] "public-input-derived-secret"))
  @(rf/subscribe [:synthetic])
  (is (true? (marks/sub-output-sensitive? :rf/default :synthetic))))

(deftest public-declassification-claims-audit
  ;; EP-0015 issue 9: every `:rf.egress/output-sensitivity :rf.egress/public`
  ;; claim is enumerable as a STANDING audit surface (the declassification
  ;; analogue of `:rf.scope/global`). A `:sensitive` / `:inherit` sub does NOT
  ;; appear; a `:public` one does.
  (rf/reg-sub :pub/a
    {:rf.egress/output-sensitivity :rf.egress/public}
    (fn [_ _] "ok"))
  (rf/reg-sub :pub/forced
    {:rf.egress/output-sensitivity :rf.egress/sensitive}
    (fn [_ _] "secret"))
  (rf/reg-sub :pub/inheriting
    (fn [_ _] "plain"))
  (let [claims (marks/public-declassification-claims)]
    (is (some #(= {:kind :sub :id :pub/a} %) claims)
        "the :public-declassified sub is enumerated in the audit")
    (is (not (some #(= :pub/forced (:id %)) claims))
        "a force-:sensitive sub is NOT a declassification claim")
    (is (not (some #(= :pub/inheriting (:id %)) claims))
        "an :inherit (default) sub is NOT a declassification claim")))

(deftest sub-inherit-default-propagates
  ;; Spec 015 §Derived sensitivity — the conformance
  ;; `derived-output-inherits-sensitivity.edn` fixture: a sub reading a
  ;; sensitive input with NO `:rf.egress/output-sensitivity` (the `:inherit`
  ;; default) propagates — its output IS marked sensitive.
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:user {:ssn "X" :name "A"}}}))
  (rf/dispatch-sync [:seed])
  (marks/set-marks :rf/default {[:user :ssn] :sensitive})
  (rf/reg-sub :inheriting
    (fn [db _] (get-in db [:user :name])))
  @(rf/subscribe [:inheriting])
  (is (true? (marks/sub-output-sensitive? :rf/default :inheriting))
      "a layer-1 sub over a frame with sensitive declarations inherits sensitivity by default"))

;; ---- composed: subs propagation through layer-2 ------------------------

(deftest layer-2-inherits-from-input-sub
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:user {:ssn "X" :name "A"}}}))
  (rf/dispatch-sync [:seed])
  (marks/set-marks :rf/default {[:user :ssn] :sensitive})
  (rf/reg-sub :u (fn [db _] (:user db)))
  (rf/reg-sub :uname
    :<- [:u]
    (fn [u _] (:name u)))
  @(rf/subscribe [:uname])
  ;; Layer-2 picks up sensitive from layer-1 input :u
  (is (true? (marks/sub-output-sensitive? :rf/default :u)))
  (is (true? (marks/sub-output-sensitive? :rf/default :uname))))

;; ---- per-sub-path declaration --------------------------------------------

(deftest per-sub-path-declaration-via-projection
  ;; The `:rf.sub/run` trace shape carries `{:rf.sub/id :rf.sub/query-v :frame}` — not
  ;; the computed value (sub recompute keeps the value in the reaction).
  ;; Per-sub-path declarations apply downstream: wherever a tool lifts the
  ;; sub's value into an observable surface (Xray sub panel, MCP wire),
  ;; the consumer calls `marks/redact-with-paths` with the sub's declared
  ;; paths. This test exercises that contract via the public primitive.
  (rf/reg-sub :compound
    {:sensitive [[:hashed]]}
    (fn [_ _] {:hashed "secret" :public "ok"}))
  (let [m (marks/marks-for :sub :compound)
        v {:hashed "secret" :public "ok"}
        out (marks/redact-with-paths v (:sensitive m) (or (:large m) []))]
    (is (= :rf/redacted (:hashed out)))
    (is (= "ok" (:public out)))))

;; ---- cofx -------------------------------------------------------------

(deftest cofx-injected-value-redacted-in-coeffects
  (rf/reg-cofx :auth/jwt
    {:sensitive [[]]}
    (fn [] "real-jwt"))
  (rf/reg-event :uses-jwt
    {:rf.cofx/requires [:auth/jwt]}
    (fn [_ _] {}))
  ;; Cofx mark stashed
  (is (= [[]] (:sensitive (marks/marks-for :cofx :auth/jwt))))
  ;; Trace projection covers tags-with-:coeffects; verified through the
  ;; redact-with-paths primitive — the integration over a real trace
  ;; event also exercises this path.
  (let [coeffects-tag {:auth/jwt "real-jwt"}
        projected (marks/project-trace-event
                    {:operation :rf.event/some-emit
                     :op-type :rf.event
                     :tags {:rf.event/coeffects coeffects-tag :frame :rf/default}})]
    (is (= :rf/redacted (get-in projected [:tags :rf.event/coeffects :auth/jwt])))))

(deftest cofx-run-value-redacted-by-marks-chokepoint
  ;; rf2-hhh92 / rf2-sepqgg: the per-cofx success op `:rf.cofx/run` carries
  ;; the supplier's PRODUCED value (the coeffect that egresses) under
  ;; `:rf.cofx/value`; the marks chokepoint (`project-cofx-run-tags`, wired
  ;; into `project-trace-event`) redacts it against the cofx's declared
  ;; marks — mirroring `:rf.fx/handled`'s `:rf.fx/args` redaction for the
  ;; standalone-value op. This is the chokepoint-primitive proof; the
  ;; end-to-end proof (a real ambient supplier producing a sensitive value,
  ;; redacted before any listener sees the run op) lives in
  ;; `re-frame.cofx-test/cofx-run-sensitive-produced-value-is-redacted-end-to-end`.
  (rf/reg-cofx :auth/jwt2
    {:sensitive [[:token]]}
    (fn [v] v))
  (is (= [[:token]] (:sensitive (marks/marks-for :cofx :auth/jwt2))))
  (let [projected (marks/project-trace-event
                    {:operation :rf.cofx/run
                     :op-type   :rf.cofx
                     :tags      {:rf.cofx/id    :auth/jwt2
                                 :rf.cofx/value {:token "secret" :public "ok"}
                                 :rf.cofx/elapsed-ms 0.5
                                 :frame         :rf/default}})]
    (is (= :rf/redacted (get-in projected [:tags :rf.cofx/value :token]))
        "the sensitive sub-path of the cofx value is redacted on :rf.cofx/run")
    (is (= "ok" (get-in projected [:tags :rf.cofx/value :public]))
        "non-sensitive sub-paths pass through")
    (is (= 0.5 (get-in projected [:tags :rf.cofx/elapsed-ms]))
        ":rf.cofx/elapsed-ms is not touched by the marks chokepoint")))

(deftest cofx-run-value-untouched-without-marks
  ;; A cofx with no declared marks: the value passes through unredacted.
  (rf/reg-cofx :plain/cofx
    (fn [v] v))
  (let [projected (marks/project-trace-event
                    {:operation :rf.cofx/run
                     :op-type   :rf.cofx
                     :tags      {:rf.cofx/id    :plain/cofx
                                 :rf.cofx/value {:any "data"}
                                 :frame         :rf/default}})]
    (is (= {:any "data"} (get-in projected [:tags :rf.cofx/value]))
        "no marks → the cofx value is not redacted")))

;; ---- registrar interaction ---------------------------------------------

(deftest re-registration-clears-prior-marks
  (rf/reg-event :evt {:sensitive [[:a]]} (fn [_ _] {}))
  (is (= [[:a]] (:sensitive (marks/marks-for :event :evt))))
  ;; Re-register without marks — clears stashed entry
  (rf/reg-event :evt (fn [_ _] {}))
  (is (nil? (marks/marks-for :event :evt))))

(deftest re-registration-replaces-marks
  (rf/reg-event :evt {:sensitive [[:a]]} (fn [_ _] {}))
  (rf/reg-event :evt {:sensitive [[:b]]} (fn [_ _] {}))
  (is (= [[:b]] (:sensitive (marks/marks-for :event :evt)))))
