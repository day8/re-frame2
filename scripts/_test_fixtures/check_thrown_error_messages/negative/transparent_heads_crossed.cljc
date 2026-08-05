(ns fixture.transparent-heads-crossed)

;; NEGATIVE (rf2-n6ijg) — one case per `_TRANSPARENT_HEADS` entry, each placing
;; that head between a conformant `let`-bound message and the `throw` it guards.
;; Every one must stay GREEN: a head that introduces no bindings cannot change
;; what the message symbol means, which is the whole claim the roster makes.
;; Zero findings expected.
;;
;; Delete any head below from `_TRANSPARENT_HEADS` and its case fires, because
;; the crossing then falls through to the fail-closed default. Thirteen of the
;; nineteen entries had no case in either direction before this file; the
;; pre-existing `bypass_let_bound_guarded.cljc` reaches six of them
;; (do/if/when/cond/try/finally) as a side effect of the shapes it was written
;; for, and keeps doing so — this file is the roster's own home.
;;
;; The bodies are minimal by design: each `defn` exists to place one head
;; between the binding and the throw. Several of the entries — `not` and the
;; threading macros especially — are in the roster as fail-open safety rather
;; than because they commonly wrap a throw, so the smallest form that nests the
;; throw inside them is the honest fixture, not a plausible-looking one.

;; `do`
(defn crossing-do
  [reason]
  (let [msg (str reason " [:rf.error/transparent-do]")]
    (do
      (prn :about-to-throw)
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-do})))))

;; `throw` — the head every case crosses on its way in, stated once here too.
(defn crossing-throw
  [reason]
  (let [msg (str reason " [:rf.error/transparent-throw]")]
    (throw (ex-info msg {:rf.error/id :rf.error/transparent-throw}))))

;; `if`
(defn crossing-if
  [ok? reason]
  (let [msg (str reason " [:rf.error/transparent-if]")]
    (if ok?
      nil
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-if})))))

;; `if-not`
(defn crossing-if-not
  [ok? reason]
  (let [msg (str reason " [:rf.error/transparent-if-not]")]
    (if-not ok?
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-if-not}))
      nil)))

;; `when`
(defn crossing-when
  [bad? reason]
  (let [msg (str reason " [:rf.error/transparent-when]")]
    (when bad?
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-when})))))

;; `when-not`
(defn crossing-when-not
  [ok? reason]
  (let [msg (str reason " [:rf.error/transparent-when-not]")]
    (when-not ok?
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-when-not})))))

;; `cond`
(defn crossing-cond
  [reason]
  (let [msg (str reason " [:rf.error/transparent-cond]")]
    (cond
      :else (throw (ex-info msg {:rf.error/id :rf.error/transparent-cond})))))

;; `condp`
(defn crossing-condp
  [k reason]
  (let [msg (str reason " [:rf.error/transparent-condp]")]
    (condp = k
      :unsupported (throw (ex-info msg {:rf.error/id :rf.error/transparent-condp}))
      nil)))

;; `case`
(defn crossing-case
  [k reason]
  (let [msg (str reason " [:rf.error/transparent-case]")]
    (case k
      :unsupported (throw (ex-info msg {:rf.error/id :rf.error/transparent-case}))
      nil)))

;; `try` and `finally` — two entries, two cases.
(defn crossing-try
  [reason]
  (let [msg (str reason " [:rf.error/transparent-try]")]
    (try
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-try}))
      (catch #?(:clj Exception :cljs js/Error) _ nil))))

(defn crossing-finally
  [reason]
  (let [msg (str reason " [:rf.error/transparent-finally]")]
    (try
      nil
      (finally
        (throw (ex-info msg {:rf.error/id :rf.error/transparent-finally}))))))

;; `and`
(defn crossing-and
  [ok? reason]
  (let [msg (str reason " [:rf.error/transparent-and]")]
    (and ok?
         (throw (ex-info msg {:rf.error/id :rf.error/transparent-and})))))

;; `or`
(defn crossing-or
  [value reason]
  (let [msg (str reason " [:rf.error/transparent-or]")]
    (or value
        (throw (ex-info msg {:rf.error/id :rf.error/transparent-or})))))

;; `not`
(defn crossing-not
  [reason]
  (let [msg (str reason " [:rf.error/transparent-not]")]
    (not (throw (ex-info msg {:rf.error/id :rf.error/transparent-not})))))

;; `->` — "get it, or throw" is the shape this actually takes.
(defn crossing-thread-first
  [ctx reason]
  (let [msg (str reason " [:rf.error/transparent-thread-first]")]
    (-> ctx
        :items
        (or (throw (ex-info msg {:rf.error/id :rf.error/transparent-thread-first}))))))

;; `->>`
(defn crossing-thread-last
  [ctx reason]
  (let [msg (str reason " [:rf.error/transparent-thread-last]")]
    (->> ctx
         :items
         (or (throw (ex-info msg {:rf.error/id :rf.error/transparent-thread-last}))))))

;; `some->`
(defn crossing-some-thread-first
  [ctx reason]
  (let [msg (str reason " [:rf.error/transparent-some-thread-first]")]
    (some-> ctx
            :items
            (or (throw (ex-info msg {:rf.error/id :rf.error/transparent-some-thread-first}))))))

;; `some->>`
(defn crossing-some-thread-last
  [ctx reason]
  (let [msg (str reason " [:rf.error/transparent-some-thread-last]")]
    (some->> ctx
             :items
             (or (throw (ex-info msg {:rf.error/id :rf.error/transparent-some-thread-last}))))))

;; `doto`
(defn crossing-doto
  [ctx reason]
  (let [msg (str reason " [:rf.error/transparent-doto]")]
    (doto ctx
      (throw (ex-info msg {:rf.error/id :rf.error/transparent-doto})))))
