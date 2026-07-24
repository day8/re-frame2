(ns re-frame.freehand.binding-plan-host-faithful-jvm-test
  "rf2-vxgfnd.283 — the ONE host-faithful binding plan. The analyzer's scope
  walk and the header's declared slots must derive their binding order from a
  single plan that reproduces the host `destructure` `bes` transformation, so
  neither drifts from the other or from what native destructuring — the code
  both emitters emit — actually binds.

  These fixtures compare the plan against REAL `clojure.core/destructure`
  expansion (macro-level, JVM), and against a REAL eval'd native binding. The
  CLJS `destructure` is byte-for-byte the same transformation run on the same
  JVM maps at macro-expansion time, so a match here is a match on both hosts.

  The rows that compared a per-slot CLJS PROPERTY-READ emission against this
  plan went with the second CLJS emitter that produced it: the surviving
  browser emitter binds the declaration's own parameter vector with the host's
  own destructuring, exactly as the JVM one does, so there is no second binding
  order left to drift."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.binding-plan :as bp]
            [re-frame.freehand.compiler.header :as header]))

(defn- real-local-order
  "The user locals `clojure.core/destructure` binds for map `pat`, in order —
  the ground truth the plan must reproduce (gensym scaffolding dropped)."
  [pat]
  (->> (destructure [pat 'the-map])
       (partition 2)
       (map first)
       (remove #(re-find #"^(map__|vec__|seq__|first__|p__|the-map)" (name %)))
       vec))

(defn- plan-order
  "The shared binding plan's local order for map `pat` (`:as` excluded — it
  binds first, before the `bes` walk)."
  [pat]
  (ana/header-binding-order pat))

(defn- native-local
  "The user local `clojure.core/destructure` binds for name `nm` in map `pat` —
  WITH the metadata the host preserves via `with-meta` (the ground truth)."
  [pat nm]
  (->> (destructure [pat 'the-map])
       (partition 2)
       (map first)
       (some #(when (and (symbol? %) (= (name nm) (name %))) %))))

(defn- plan-local
  "The canonical binding plan's `:local-pattern` for local name `nm` in map
  `pat` — WITH its metadata (the plan must retain what the host preserves)."
  [pat nm]
  (some #(when (and (symbol? %) (= (name nm) (name %)))
           %)
        (ana/header-binding-order pat)))

;; ---------------------------------------------------------------------------
;; The plan reproduces the host bes order
;; ---------------------------------------------------------------------------

(deftest plan-matches-real-clojure-destructure
  (testing "explicit + group, array-map regime (source order)"
    (doseq [pat '[{:keys [a b c d e f g h]}                 ; 8 = array-map
                  {ex1 :e1 :keys [ka kb] ex2 :e2}           ; explicit around a group
                  {p1 :k1 p2 :k2 :keys [a b] :strs [s1 s2] :syms [y1 y2]}]]
      (is (= (real-local-order pat) (plan-order pat))
          (str "plan reproduces host order for " (pr-str pat)))))
  (testing "mixed :keys/:strs/:syms follow SOURCE group order (not a fixed rank)"
    (let [pat '{:syms [y1 y2] :keys [k1 k2] :strs [s1 s2]}]
      (is (= (real-local-order pat) (plan-order pat)))
      (is (= '[y1 y2 k1 k2 s1 s2] (plan-order pat))
          "syms-first source order, not keys<strs<syms rank")
      (is (not= '[k1 k2 s1 s2 y1 y2] (plan-order pat))
          "the invented rank order would silently regress this")))
  (testing "a group directive is re-assoc'd AFTER the surviving explicit entries"
    ;; rf2-4xpah — the JVM half of the CLJS suite's distinguishable-order
    ;; fixture (`interleaved-order-view`). Written source order is a, b, c; the
    ;; host `dissoc`s the `:keys` directive and re-`assoc`s its local, so the
    ;; binding order is a, c, b. The CLJS suite asserts that same order from
    ;; three DISTINCT `:or` markers on the real host; this pins the expected
    ;; literal against REAL `clojure.core/destructure`, so neither side can
    ;; drift into agreeing on a wrong order.
    (let [pat '{a :aa :keys [b] c :cc}]
      (is (= (real-local-order pat) (plan-order pat)))
      (is (= '[a c b] (plan-order pat))
          "the group's local lands last, not in its written position")
      (is (not= '[a b c] (plan-order pat))
          "written source order would be the regression")))
  (testing "the 8→9 entry map-representation threshold"
    (is (= '[a b c d e f g h] (plan-order '{:keys [a b c d e f g h]}))
        "8 entries stay a PersistentArrayMap in source order")
    (let [nine '{:keys [a b c d e f g h i]}]
      (is (= (real-local-order nine) (plan-order nine))
          "9 entries promote to a PersistentHashMap — reproduce that order")
      (is (not= '[a b c d e f g h i] (plan-order nine))
          "the 9-entry order is hash-driven, NOT source order")))
  (testing "the bead counterexample binds target before its sub default"
    (let [pat '{:keys [sub a b c d e f g h target] :or {target sub}}]
      (is (= (real-local-order pat) (plan-order pat)))
      (is (< (.indexOf ^clojure.lang.PersistentVector (plan-order pat) 'target)
             (.indexOf ^clojure.lang.PersistentVector (plan-order pat) 'sub))
          "target is bound before sub, so its default reaches the outer var"))))

;; ---------------------------------------------------------------------------
;; rf2-qa5wkd — colliding headers COLLAPSE through the one canonical plan
;;
;; The order tests above use DISTINCT locals, so they cannot catch a header
;; whose entries bind the SAME local (`{:keys [x] x :foo}`) or a qualified
;; group local (`{:keys [acct/id]}`). Those are where a plan that only reorders
;; parse-order entries (or re-derives them, stripping the namespace) diverges
;; from the host: it would keep two entries (silent last-wins on :foo) or read a
;; bare :id, while native destructuring collapses to one winning slot. These
;; fixtures pin the collapse against REAL `clojure.core/destructure` and a REAL
;; eval'd native binding (the code both emitters emit).
;; ---------------------------------------------------------------------------

(defn- host-local->key
  "{simple-local -> host lookup KEYWORD} from real `clojure.core/destructure`
  (the ground truth both emitters must reproduce; gensym scaffolding dropped)."
  [pat]
  (->> (destructure [pat 'the-map])
       (partition 2)
       (keep (fn [[l init]]
               (when (and (symbol? l)
                          (not (re-find #"^(map__|vec__|seq__|first__|p__|the-map)"
                                        (name l)))
                          (seq? init) (= 'clojure.core/get (first init)))
                 [l (nth init 2)])))
       (into {})))

(defn- planned
  "{:locals [pattern...] :slot-of {sym slot-str}} from the header's FULL host
  binding plan — `:binding-units`, in host order, which is what an emitter
  consumes. `:locals` keeps duplicates so a header that binds one local through
  TWO host units is visible; `:slot-of` is last-wins, so it reports the slot the
  surviving binding reads."
  [argv]
  (let [units (:binding-units (header/parse-header argv))]
    {:locals  (mapv :pattern units)
     :slot-of (into {}
                    (keep (fn [{:keys [pattern slot]}]
                            (when (symbol? pattern) [pattern slot])))
                    units)}))

(deftest explicit-keys-collision-collapses-to-one-host-slot
  (testing "same local from :keys and an explicit entry collapses to ONE binding, both source orders"
    (doseq [pat '[{:keys [x] x :foo}     ; :keys before explicit
                  {x :foo :keys [x]}]]   ; explicit before :keys
      (let [host (host-local->key pat)
            {:keys [locals slot-of]} (planned [pat])]
        (is (= {'x :x} host)
            (str ":keys wins the host lookup slot for " (pr-str pat)))
        (is (= '[x] locals)
            (str "x is bound EXACTLY ONCE — not duplicated for " (pr-str pat)))
        (is (= (header/slot-name (host 'x)) (slot-of 'x))
            (str "the plan reads the host's winning slot :x, NEVER a silent "
                 "last-wins on :foo, for " (pr-str pat)))
        (is (= "x" (slot-of 'x))
            (str "the one read targets slot \"x\" for " (pr-str pat))))))
  (testing "the collapsed slot is the ONLY declared slot (no dead :foo in the comparator/manifest)"
    (is (= [:x] (:slots (header/parse-header '[{:keys [x] x :foo}])))
        "the dead explicit :foo entry does not survive as a declared slot")))

(deftest qualified-keys-local-keeps-the-host-lookup-key
  (testing "a qualified :keys symbol reads its QUALIFIED slot, never the bare name"
    (let [pat '{:keys [acct/id]}
          host (host-local->key pat)
          {:keys [locals slot-of]} (planned [pat])]
      (is (= {'id :acct/id} host) "host reads :acct/id")
      (is (= '[id] locals) "binds the name-only local id")
      (is (= "acct/id" (slot-of 'id))
          "the plan reads slot \"acct/id\", NEVER bare \"id\"")
      (is (= (header/slot-name (host 'id)) (slot-of 'id)))
      (is (= [:acct/id] (:slots (header/parse-header [pat])))
          "the declared slot keeps the qualified key")))
  (testing "namespaced group vs explicit collision keeps the host's winning slot"
    (let [pat '{:acct/keys [id] id :other}
          host (host-local->key pat)
          {:keys [locals slot-of]} (planned [pat])]
      (is (= {'id :acct/id} host) ":acct/keys wins over the explicit :other")
      (is (= '[id] locals) "collapsed to one binding")
      (is (= (header/slot-name (host 'id)) (slot-of 'id)))
      (is (= "acct/id" (slot-of 'id))))))

(deftest qualified-group-name-collision-collapses-no-dead-slot
  ;; rf2-7imr5y — the residual collision the `bes`-map collapse does NOT catch:
  ;; a QUALIFIED :keys group local (`ns/x`) whose bare name collides an explicit
  ;; local (`x :other`). The two never share a `bes` KEY (`ns/x` ≠ `x`), so
  ;; `assoc-binding-units` emits TWO units binding the same name-stripped local
  ;; `x` via host `let` last-wins. Uncollapsed, that left a DEAD `:other` slot in
  ;; the comparator/manifest; `header/collapse-entries` now folds it to the one
  ;; host-winning entry `x <- :ns/x`.
  (testing "binding VALUE stays host-faithful (x resolves to :ns/x, not :other)"
    (let [pat '{:keys [ns/x] x :other}
          host (host-local->key pat)
          {:keys [locals slot-of]} (planned [pat])]
      (is (= {'x :ns/x} host)
          "host last-wins binds x <- :ns/x; the explicit :other is dead")
      ;; rf2-nedxb — the EXECUTABLE emission runs every host binding unit, so both
      ;; the shadowed `:other` read and the winning `:ns/x` read are emitted (host
      ;; `let` last-wins). Only the DERIVED slots (below) collapse to the winner.
      (is (= '[x x] locals)
          "x is planned for BOTH host units — the later :ns/x is the visible winner")
      (is (= "ns/x" (slot-of 'x))
          "the winning read is the host's slot \"ns/x\", never dead \"other\"")
      (is (= (header/slot-name (host 'x)) (slot-of 'x)))))
  (testing "the collapsed slot is the ONLY declared slot — no dead :other"
    (let [h (header/parse-header '[{:keys [ns/x] x :other}])]
      (is (= [{:key :ns/x :slot "ns/x" :pattern 'x}] (:entries h))
          "ONE entry, the winning :ns/x — never two entries for one local")
      (is (= [:ns/x] (:slots h))
          "the dead-shadowed :other does NOT survive as a declared slot")
      (is (not (some #{:other} (:slots h)))
          "no :other in the comparator/manifest slot set")))
  (testing "the :or default rides the winning entry, not the dead one"
    (let [h (header/parse-header '[{:keys [ns/x] x :other :or {x 9}}])]
      (is (= [{:key :ns/x :slot "ns/x" :pattern 'x :default 9}] (:entries h))
          "one entry: winning slot :ns/x carrying the x default")
      (is (= [:ns/x] (:slots h)))))
  (testing "real JVM native destructuring agrees — :ns/x wins, :other is dead"
    (let [f (eval (list 'fn '[{:keys [ns/x] x :other}] 'x))]
      (is (= 1 (f {:ns/x 1 :other 2})) "present :ns/x wins; :other is dead")
      (is (nil? (f {:other 2}))         ":other never reaches x — absent :ns/x → nil"))
    (let [g (eval (list 'fn '[{:keys [ns/x] x :other :or {x 9}}] 'x))]
      (is (= 1 (g {:ns/x 1 :other 2})) "present :ns/x wins over :other and the default")
      (is (= 9 (g {:other 2}))         "absent :ns/x → the :or default, never :other"))))

(deftest real-jvm-native-binding-agrees-present-absent-defaulted
  ;; The JVM emitter emits `(let [<raw pattern> props] …)` — native
  ;; destructuring. Eval it and confirm the collapsed slot + default behave
  ;; exactly as the CLJS-side entry (winning key :x, default 9) claims.
  (let [pat '{:keys [x] x :foo :or {x 9}}
        f   (eval (list 'fn [pat] 'x))   ; (fn [<pattern>] x) — native destructuring
        en  (first (:entries (header/parse-header [pat])))]
    (testing "the CLJS-side entry names the collapsed winning slot + default"
      (is (= :x (:key en)))
      (is (= "x" (:slot en)))
      (is (= 9 (:default en))))
    (testing "real native destructuring reads :x (never the collapsed-away :foo)"
      (is (= 1 (f {:x 1 :foo 2})) "present :x wins; :foo is dead")
      (is (= 9 (f {:foo 2}))      "absent :x → the :or default, not :foo")
      (is (nil? (f {:x nil}))     "present-nil :x stays nil (default is absent-only")
      (is (= 5 (f {:x 5})))))
  (testing "a genuine collision with no :keys shadow still reads the host slot"
    (let [pat '{:keys [x] x :foo}
          f   (eval (list 'fn [pat] 'x))]
      (is (= :a (f {:x :a :foo :b})) "reads :x")
      (is (nil? (f {:foo :b}))       "absent :x, no default → nil, never :foo"))))

;; ---------------------------------------------------------------------------
;; rf2-4xpah — the NESTED / partially overlapping collapse, JVM half
;;
;; PR #6228 repaired the nested shadow (`{[x] :other :keys [ns/x]}`) and added
;; `bp/pattern-locals`, but pinned both ONLY on the compiled-CLJS lane. These
;; fixtures pair that suite: the new primitive gets direct unit coverage, and
;; the two collapse shapes are checked against REAL JVM native destructuring —
;; the exact code the JVM emitter emits — so the two hosts cannot agree on a
;; value neither host actually binds.
;; ---------------------------------------------------------------------------

(deftest pattern-locals-are-the-locals-the-host-binds
  (testing "simple + sequential patterns"
    (is (= '#{x} (bp/pattern-locals 'x)))
    (is (= #{}   (bp/pattern-locals '&)) "the rest MARKER is not a local")
    (is (= '#{x} (bp/pattern-locals '[x])))
    (is (= '#{a b more all} (bp/pattern-locals '[a b & more :as all]))
        "element patterns, the rest pattern and :as all bind")
    (is (= '#{a b c} (bp/pattern-locals '[a [b [c]]])) "nesting recurses"))
  (testing "associative patterns — group locals arrive NAME-STRIPPED"
    (is (= '#{x} (bp/pattern-locals '{:keys [ns/x]}))
        ":keys [ns/x] binds x, exactly as the plan's group locals arrive")
    (is (= '#{x p whole}
           (bp/pattern-locals '{:keys [ns/x] p :other :as whole :or {x 1}}))
        "group locals, explicit sub-patterns and :as bind; :or and lookup keys do not")
    (is (= '#{u v} (bp/pattern-locals '{[u v] :other}))
        "an explicit entry's nested pattern recurses")))

(deftest nested-and-partial-overlap-collapse-agrees-with-native-destructuring
  (testing "NESTED: [x] <- :other is fully reclaimed, so :other is a dead slot"
    (let [argv '[{[x] :other :keys [ns/x]}]
          h    (header/parse-header argv)]
      (is (= [:ns/x] (:slots h))
          "the dead :other does not reach the comparator / manifest / schema slots")
      (is (= 2 (count (:binding-units h)))
          "yet the EXECUTABLE plan keeps both units — every initializer still runs")
      (is (= '[[x] x] (:locals (planned argv)))
          (str "so the CLJS emitter emits the nested [x] <- :other unit AND the "
               "winning x <- :ns/x, in that order — the later symbol shadows "
               "the nested pattern's x by host let last-wins")))
    ;; ground truth — what the JVM emitter's native `let` destructuring binds
    (let [f (eval (list 'fn '[{[x] :other :keys [ns/x]}] 'x))]
      (is (= 1 (f {:ns/x 1 :other [9]}))
          "present :ns/x wins; the nested [x] <- :other read is dead")
      (is (nil? (f {:other [9]}))
          "absent :ns/x -> nil, never the shadowed 9 — matches the compiled-CLJS render")))
  (testing "PARTIAL overlap: b still reads :other, so :other stays a live slot"
    (let [h (header/parse-header '[{[a b] :other :keys [ns/a]}])]
      (is (= [:other :ns/a] (:slots h))
          ":other survives because b is still a final visible local"))
    (let [f (eval (list 'fn '[{[a b] :other :keys [ns/a]}] '[a b]))]
      (is (= [1 8] (f {:ns/a 1 :other [7 8]}))
          "a is the group's :ns/a; b is the executed [a b] <- :other second slot")))
  (testing "SCHEMA + CLOSED :props follow the collapsed slots for these shapes too"
    (is (= [:ns/x :extra]
           (header/declared-slots (header/parse-header '[{[x] :other :keys [ns/x]}])
                                  (header/props-schema-keys [:map [:extra :any]])))
        "the dead :other is absent from the declared-slot order the schema extends")
    (is (= [:other :ns/a]
           (header/declared-slots (header/parse-header '[{[a b] :other :keys [ns/a]}])
                                  nil))
        "a partial overlap keeps the slot its surviving local reads")))

;; ---------------------------------------------------------------------------
;; rf2-gvuoo — group-symbol METADATA survives the canonical plan
;;
;; `assoc-binding-units` reconstructed a group local with `(symbol (name bb))`,
;; discarding authored symbol metadata. Real `clojure.core/destructure`
;; preserves it via `(with-meta (symbol nil (name bb)) (meta bb))`. Symbol
;; equality ignores metadata, so the plan-order tests above false-green; losing
;; `^js` and other portable type hints changes Closure interop inference,
;; warnings and advanced-build codegen. These fixtures compare the metadata
;; DIRECTLY against the host's, so a `(symbol (name …))` regression fails.
;; ---------------------------------------------------------------------------

(deftest group-symbol-metadata-survives-the-canonical-plan
  (testing "native clojure.core/destructure preserves ^js on the group local (ground truth)"
    (is (= {:tag 'js} (meta (native-local '{:keys [^js node]} 'node)))
        "the host binds `node` carrying its authored {:tag js}"))
  (testing "the canonical binding plan retains the SAME metadata as the host"
    (let [host (meta (native-local '{:keys [^js node]} 'node))
          plan (meta (plan-local   '{:keys [^js node]} 'node))]
      (is (= {:tag 'js} plan)
          "^js survives on the plan's :local-pattern, not a bare (symbol (name bb))")
      (is (= host plan)
          "plan metadata equals the host's preserved metadata")))
  (testing "the hint reaches the binding unit an emitter consumes (advanced interop)"
    (is (= {:tag 'js}
           (meta (:pattern (first (:binding-units
                                   (header/parse-header ['{:keys [^js node]}]))))))
        "^js rides the host binding unit, so it reaches the emitted binding and
         Closure interop inference holds"))
  (testing "a QUALIFIED group local keeps its hint through the name-strip"
    ;; {:keys [^js acct/node]} binds the name-only local `node`, still ^js-tagged
    (is (= {:tag 'js} (meta (native-local '{:keys [^js acct/node]} 'node)))
        "host name-strips acct/node → node, keeping the hint")
    (is (= {:tag 'js} (meta (plan-local   '{:keys [^js acct/node]} 'node)))
        "the plan name-strips the same way and keeps the hint")
    (is (= [:acct/node] (:slots (header/parse-header ['{:keys [^js acct/node]}])))
        "the qualified lookup key is unchanged by metadata preservation"))
  (testing "unhinted group symbols + explicit locals retain current behavior"
    (is (nil? (meta (native-local '{:keys [plain]} 'plain))) "host: no meta invented")
    (is (nil? (meta (plan-local   '{:keys [plain]} 'plain))) "plan: no meta invented")
    (is (nil? (meta (:pattern (first (:binding-units
                                      (header/parse-header ['{:keys [plain]}]))))))
        "binding unit: no meta invented")
    ;; an explicit {^js ex :e1} local was never rewritten, so it already kept meta
    (is (= {:tag 'js} (meta (native-local '{^js ex :e1} 'ex)))  "host keeps explicit ^js")
    (is (= {:tag 'js} (meta (plan-local   '{^js ex :e1} 'ex)))  "plan keeps explicit ^js")))

;; ---------------------------------------------------------------------------
;; rf2-nedxb — executable HOST EVAL SEMANTICS survive header collapse
;;
;; Two properties the collapse must not quietly change, because both emitters
;; hand the raw pattern to native destructuring and inherit them:
;;
;;   1. A `:or` default is `get`'s third argument — a function arg, so the host
;;      evaluates it as part of the lookup even when the slot is PRESENT.
;;   2. A qualified-group/explicit same-local collision (`{:keys [ns/x] x :other}`)
;;      is TWO host binding units (both name-strip to `x`, host `let` last-wins);
;;      native evaluates BOTH initializers before the later wins. The collapsed
;;      projection (comparator/manifest/slots) keeps only the winner, but the
;;      EXECUTABLE plan must retain every host unit.
;;
;; These fixtures eval the real native binding and compare effect COUNT, order,
;; throws and final value against real `clojure.core/destructure`, and read the
;; executable/collapsed split straight off the header plan.
;; ---------------------------------------------------------------------------

(def calls (atom 0))                        ; :or default side-effect counter
(def marks (atom []))                       ; :or default evaluation-order log
(defn- mk [tag] (swap! marks conj tag) tag)

(defn- run-native
  "Evaluate the emitted binding path — native `let` destructuring of the raw
  pattern — on keyword-keyed `props`. Returns {local -> final value}; the
  ground truth."
  [pat props]
  (binding [*ns* (find-ns 're-frame.freehand.binding-plan-host-faithful-jvm-test)]
    (let [locals (->> (destructure [pat 'the-map]) (partition 2) (map first)
                      (remove #(re-find #"^(map__|vec__|seq__|first__|p__|the-map)"
                                        (name %)))
                      distinct vec)
          f (eval `(fn [m#] (let [~pat m#]
                              ~(into {} (map (fn [s] [(list 'quote s) s])) locals))))]
      (f props))))

(deftest or-default-evaluates-once-present-and-absent-like-the-host
  (let [pat '{:keys [x] :or {x (swap! calls inc)}}]
    (testing "native: the :or default is an eager get-arg — evaluated once whether present or absent"
      (reset! calls 0)
      (is (= {'x 5} (run-native pat {:x 5})))
      (is (= 1 @calls) "present -> default STILL evaluated once (get's 3rd arg is eager)")
      (reset! calls 0)
      (is (= {'x 1} (run-native pat {})))
      (is (= 1 @calls) "absent -> default evaluated once, becomes the value"))
    (testing "the collapsed entry carries that default onto the winning slot"
      (let [en (first (:entries (header/parse-header [pat])))]
        (is (= :x (:key en)))
        (is (= '(swap! calls inc) (:default en))))))
  (testing "a throwing default is evaluated even when the slot is PRESENT"
    (let [pat '{:keys [x] :or {x (throw (ex-info "boom" {}))}}]
      (is (thrown? clojure.lang.ExceptionInfo (run-native pat {:x 5}))
          "native: the eager get-arg throws even though :x is present"))))

(deftest shadowed-initializers-all-execute-with-the-later-winning
  (let [pat  '{:keys [ns/x] x :other :or {x (mk :d)}}
        argv [pat]]
    (testing "the split: executable plan keeps BOTH host units; derived slots keep only the winner"
      (let [h (header/parse-header argv)]
        (is (= 2 (count (:binding-units h)))
            "the uncollapsed executable plan carries both host binding units")
        (is (= [:ns/x] (:slots h))
            "the collapsed projection keeps only the host-winning slot")
        (is (= [{:key :ns/x :slot "ns/x" :pattern 'x :default '(mk :d)}] (:entries h))
            "derived `:entries` remain the single collapsed winner")))
    (testing "native evaluates BOTH initializers (default per unit); :ns/x wins"
      (reset! marks [])
      (is (= {'x 1} (run-native pat {:ns/x 1 :other 2})))
      (is (= [:d :d] @marks) "two get-calls each evaluate the :or default -> 2, in host order")
      (reset! marks [])
      (is (= {'x :d} (run-native pat {})))
      (is (= [:d :d] @marks) "both defaults evaluated; the winner :ns/x is absent -> its default value")))
  (testing "without a default, the dead :other read still happens but :ns/x is the visible winner"
    (let [pat '{:keys [ns/x] x :other}]
      (is (= {'x nil} (run-native pat {:other 2}))
          "native: :other read is shadowed by absent :ns/x")
      (is (= {'x 1} (run-native pat {:ns/x 1 :other 2})) "present :ns/x wins")
      (is (= '[x x] (:locals (planned [pat])))
          "the executable plan retains both shadowed units")
      (is (= "ns/x" (get (:slot-of (planned [pat])) 'x))
          "and the surviving binding reads the host's winning slot"))))
