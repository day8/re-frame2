;;;; tests/runtime/machine_describe_test.clj
;;;;
;;;; Babashka-runnable pin for the preload's MACHINE DOOR — `machine-describe`
;;;; and `machines-list`, the two fns the MCP `handler-meta` / `list-handlers`
;;;; tools call for the virtual `:machine` kind (rf2-kuky.29).
;;;;
;;;; Why this test exists:
;;;;
;;;; 1. A machine spec carries FN VALUES nested under `:guards` and
;;;;    `:actions` (Spec 005). `pr-str` of a Function emits
;;;;    `#object[Function …]` — unreadable EDN, which the MCP result codec
;;;;    tags `:unserializable`, hiding the whole spec behind a preview. A
;;;;    top-level `dissoc :handler-fn` cannot reach a nested fn, so the door
;;;;    has to run the same recursive `strip-fns` walk `registrar-describe`
;;;;    does. That is the same defect rf2-f8s9g6 fixed for the resources
;;;;    kinds, one door along.
;;;;
;;;; 2. `machines-list` must SORT. The MCP tool documents one stable id
;;;;    vector across every kind, `registrar-list` sorts, and machines are
;;;;    the one kind whose ids arrive in registration order.
;;;;
;;;; The second half of this file is BEHAVIOURAL, not structural: it reads
;;;; `fn-slot-sentinel` and `strip-fns` out of the preload's own source,
;;;; evaluates them, and runs the real walk over a real machine spec. The
;;;; preload as a whole cannot be loaded here (it requires the re-frame
;;;; runtime and targets a browser), but those two forms are plain Clojure,
;;;; so the assertion is over the shipped code rather than over a copy of it.
;;;;
;;;; Run: bb tests/runtime/machine_describe_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns machine-describe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.edn :as edn]
            [runtime-support :as rt]))

(def ^:private defn-form rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(defn- calls? [form sym]
  ;; True when `form` invokes `sym` as the head of any sub-list.
  (form-contains? (fn [node] (and (seq? node) (= sym (first node)))) form))

(def ^:private machine-describe-form (defn-form 'machine-describe))
(def ^:private machines-list-form    (defn-form 'machines-list))

;; ---------------------------------------------------------------------------
;; The door exists, and it is PUBLIC.
;;
;; Public matters: the MCP tools reach these by name inside an eval form
;; shipped over nREPL. A `defn-` is spelled identically and is unreachable.
;; ---------------------------------------------------------------------------

(deftest machine-door-fns-are-present
  (is (some? machine-describe-form)
      (str "preload/re_frame2_pair/runtime.cljs must define `machine-describe` "
           "— the runtime surface the MCP `handler-meta {kind \"machine\"}` tool "
           "calls."))
  (is (some? machines-list-form)
      (str "preload/re_frame2_pair/runtime.cljs must define `machines-list` "
           "— the runtime surface the MCP `list-handlers {kind \"machine\"}` tool "
           "calls.")))

(deftest machine-door-fns-are-public
  (is (= 'defn (first machine-describe-form))
      "machine-describe must be a PUBLIC defn — an eval form cannot reach a defn-")
  (is (= 'defn (first machines-list-form))
      "machines-list must be a PUBLIC defn — an eval form cannot reach a defn-"))

;; ---------------------------------------------------------------------------
;; machine-describe strips fns.
;; ---------------------------------------------------------------------------

(deftest machine-describe-strips-nested-fns
  ;; Matched as a bare SYMBOL rather than a call head: the body threads
  ;; (`-> spec (dissoc …) strip-fns`), exactly as `registrar-describe` does,
  ;; so the walker never appears at the head of a list.
  (is (form-contains? (fn [node] (= 'strip-fns node)) machine-describe-form)
      (str "machine-describe MUST run `strip-fns` over the spec it returns. A "
           "machine spec's `:guards` / `:actions` are maps of FN VALUES (Spec "
           "005), and `pr-str` of a Function emits `#object[Function …]` — the "
           "MCP result codec then tags the WHOLE response `:unserializable` and "
           "the caller loses the spec entirely. This is rf2-f8s9g6's defect one "
           "door along; see rf2-kuky.29.")))

(deftest machine-describe-dissocs-handler-fn
  (is (form-contains? (fn [node]
                        (and (seq? node)
                             (= 'dissoc (first node))
                             (some #(= :handler-fn %) (rest node))))
                      machine-describe-form)
      (str "machine-describe should drop the top-level `:handler-fn` slot for "
           "the same reason `registrar-describe` does — the raw Function ref is "
           "unreadable EDN on the MCP wire.")))

(deftest machine-describe-still-reports-a-miss
  (is (form-contains? (fn [node] (= :not-a-machine node)) machine-describe-form)
      (str "machine-describe must keep returning a structured "
           "`{:ok? false :reason :not-a-machine :id id}` on a miss — the MCP "
           "handler-meta tool renames that reason to `:not-registered` so the "
           "miss envelope is uniform across kinds, and a nil would instead "
           "surface as `:unexpected-shape`.")))

;; ---------------------------------------------------------------------------
;; machines-list sorts.
;; ---------------------------------------------------------------------------

(deftest machines-list-sorts-its-ids
  (is (form-contains? (fn [node] (= 'sort node)) machines-list-form)
      (str "machines-list must SORT the id vector. `registrar-list` sorts, and "
           "tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md documents "
           "`list-handlers` as returning a stable sorted vector for every kind "
           "— machines are the one kind whose ids arrive in registration order, "
           "so an unsorted door made the two branches of that tool disagree.")))

;; ---------------------------------------------------------------------------
;; BEHAVIOURAL — the shipped strip-fns walk over a real machine spec.
;;
;; `fn-slot-sentinel` and `strip-fns` are read out of the preload source and
;; evaluated here, so what runs below is the code that ships, not a copy. The
;; rest of the preload cannot be loaded (it requires the re-frame runtime), but
;; these two forms are plain Clojure.
;; ---------------------------------------------------------------------------

(def ^:private sentinel-form
  ;; A plain `def`, not a `defn`, so `rt/defn-named` does not see it.
  (some (fn [form]
          (when (and (seq? form)
                     (= 'def (first form))
                     (= 'fn-slot-sentinel (second form)))
            form))
        rt/all-forms))

(def ^:private strip-fns-fn
  (let [sentinel sentinel-form
        walker   (rt/defn-named 'strip-fns)]
    (when (and sentinel walker)
      (binding [*ns* (create-ns 'machine-describe-test.shipped)]
        (refer-clojure)
        (eval sentinel)
        (eval walker)
        @(ns-resolve 'machine-describe-test.shipped 'strip-fns)))))

(def ^:private machine-spec-with-fns
  "A machine spec shaped like Spec 005's: fn values nested one level down under
  `:guards` and `:actions`, beside the serializable structure a caller wants."
  {:initial :idle
   :states  {:idle {:on {:go :running}} :running {}}
   :data    {:retries 0}
   :guards  {:can-go? (fn [_ _] true)}
   :actions {:log! (fn [_ _] nil)}})

(deftest shipped-strip-fns-is-loadable
  (is (some? sentinel-form)
      "fn-slot-sentinel must be a top-level def in the preload — nothing to substitute without it")
  (is (some? strip-fns-fn)
      (str "strip-fns must be readable and evaluable out of the preload source. "
           "If this fails the two assertions below prove nothing, so it is "
           "asserted rather than assumed.")))

(deftest shipped-strip-fns-replaces-guard-and-action-fns
  (let [stripped (strip-fns-fn machine-spec-with-fns)]
    (is (= :rf/fn (get-in stripped [:guards :can-go?]))
        "a fn-valued :guards entry arrives as the readable :rf/fn sentinel")
    (is (= :rf/fn (get-in stripped [:actions :log!]))
        "a fn-valued :actions entry arrives as the readable :rf/fn sentinel")
    (is (= {:idle {:on {:go :running}} :running {}} (:states stripped))
        "the serializable structure around the fns is untouched")
    (is (= {:retries 0} (:data stripped))
        "…including the :data map")))

(deftest shipped-strip-fns-output-round-trips-as-edn
  ;; The whole point: the response has to survive `pr-str` → read on the MCP
  ;; wire. A raw Function would print as `#object[Function …]` and the read
  ;; below would throw, which is what the codec turns into `:unserializable`.
  (let [stripped (strip-fns-fn machine-spec-with-fns)
        printed  (pr-str stripped)]
    (is (not (clojure.string/includes? printed "#object"))
        "no raw Function survives into the printed EDN")
    (is (= stripped (edn/read-string printed))
        "the stripped spec round-trips through EDN unchanged")))

(let [{:keys [fail error]} (run-tests 'machine-describe-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
