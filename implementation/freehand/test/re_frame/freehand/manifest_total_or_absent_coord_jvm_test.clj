(ns re-frame.freehand.manifest-total-or-absent-coord-jvm-test
  "rf2-drpa3.92 — a compiled manifest's `:source-coord` is TOTAL OR ABSENT,
  and a wrapper-generated `v/defview` stays legal.

  A compiled declaration publishes coordinates so a manifest fact and a
  build-log line can name the same lexical position. A `v/defview` GENERATED
  by a wrapper macro carries no reader line or column unless the macro copies
  `(meta &form)` onto the form it emits — and a wrapper is a reasonable thing
  for a programmer to write. Two answers were possible and they are not equal:
  REFUSE the declaration so a partial coordinate cannot arise, or stop
  over-promising and let the field be absent when nothing anchored it. This
  suite pins the second.

  So the law is one sentence with no middle: a `:source-coord` that is present
  is a whole `{:file :line :column}` whose file is truthful and whose line and
  column are positive; one that could not be whole is absent — the KEY itself,
  not a nil under it, so `contains?` and destructuring answer without a reader
  having to tell a missing coordinate from a partial one.

  The declarations here are SYNTHESIZED forms macroexpanded at runtime rather
  than written at top level, because that is the only way to model in a test
  what a wrapper macro really emits: a bare `(list …)` carries no reader
  metadata, a `with-meta` form carries the line and column a disciplined
  wrapper copies. Top-level declarations elsewhere in the corpus
  (`re-frame.freehand.manifest-source-coord-jvm-test`, the census suites) carry
  the reader's own coordinates and pin the TOTAL side against a real file."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]))

(def ^:private roster-keys
  [:subscriptions :events :slots :html-sites :frame-ops :crossings])

(defn- expand
  "Macroexpand `form` in this namespace, answering the expansion or throwing.
  `*ns*` is bound because macroexpand resolves the head against the namespace
  it runs IN."
  [form]
  (binding [*ns* (the-ns 're-frame.freehand.manifest-total-or-absent-coord-jvm-test)]
    (macroexpand form)))

(defn- manifest-of
  "The manifest a synthesized declaration publishes, read through the PUBLIC
  `v/manifest` off the declared value. The expansion is EVALUATED — a manifest
  is what the declaration carries, so reading it off anything less than a live
  declaration would be reading the compiler's intermediate rather than its
  published answer."
  [form]
  (binding [*ns* (the-ns 're-frame.freehand.manifest-total-or-absent-coord-jvm-test)]
    (v/manifest @(eval (expand form)))))

(defn- entries
  "Every roster entry of `manifest`, flattened — the population the
  total-or-absent law quantifies over."
  [manifest]
  (mapcat #(get manifest %) roster-keys))

(defn- total-coord?
  [c]
  (and (map? c)
       (= #{:file :line :column} (set (keys c)))
       (string? (:file c))
       (pos-int? (:line c))
       (pos-int? (:column c))))

(v/defview coord-probe-child
  "The crossing's target. Nothing about it is under test; it exists so the
  synthesized subjects below have an internal boundary to mount."
  {:compiled true}
  [_]
  [:span.child])

(defn- probe-body
  "The declaration's body forms, BUILT at runtime — the way a wrapper macro
  builds what it emits. Nothing here is read from this file, so no inner form
  carries a reader position of its own and every site's coordinate is the
  declaration's to give or withhold. (Clojure's reader anchors lists and not
  vectors, so the `sub` call is the one form that would otherwise locate
  itself.)

  Three site kinds — a subscription, a committed handler and a crossing — so
  the law below is quantified over rosters of different provenance."
  []
  [[:div.probe
    [:span (list 're-frame.freehand/sub [:probe/value])]
    [:button {:on-click [:probe/pressed]} "go"]
    ['coord-probe-child {}]]])

(defn- declaration
  [vname]
  (apply list `v/defview vname {:compiled true} '[_] (probe-body)))

;; ---------------------------------------------------------------------------
;; The generated declaration is LEGAL
;; ---------------------------------------------------------------------------

(deftest a-wrapper-generated-compiled-defview-expands
  (testing "A compiled v/defview whose form carries no reader line or column —
            the shape a wrapper macro emits when it does not preserve
            (meta &form) — expands like any other declaration. The compiled
            tier does not refuse a legal authoring pattern to protect a promise
            it can simply stop making."
    (is (seq? (expand (declaration 'generated-view)))
        "the generated declaration expands rather than throwing")))

(deftest an-unanchored-declaration-omits-source-coord-everywhere
  (testing "Its manifest publishes no PARTIAL coordinate anywhere. The
            declaration carried no reader position for a site to inherit, so
            every roster entry omits the field — the key absent, not nil under
            it."
    (let [m (manifest-of (declaration 'generated-manifest-view))
          es (entries m)]
      (is (seq es) "the probe body really does populate rosters")
      (doseq [entry es]
        (is (not (contains? entry :source-coord))
            (str "an unanchored entry omits the field entirely: " (pr-str entry)))
        (is (vector? (:path entry))
            "while :path — which is always knowable — still rides every entry")))))

;; ---------------------------------------------------------------------------
;; The anchored declaration is unchanged
;; ---------------------------------------------------------------------------

(deftest a-wrapper-that-copies-the-caller-metadata-publishes-total-coordinates
  (testing "The control that makes the absence above mean something. A wrapper
            that copies (meta &form) supplies a truthful call site, and every
            roster entry then carries a WHOLE {:file :line :column} — the
            existing behaviour, unchanged."
    (let [form (with-meta (declaration 'anchored-manifest-view)
                          {:line 4242 :column 7})
          m    (manifest-of form)
          es   (entries m)]
      (is (seq es))
      (doseq [entry es]
        (is (total-coord? (:source-coord entry))
            (str "a whole coordinate rides " (pr-str (dissoc entry :query))))))))

(deftest an-inherited-coordinate-is-the-declarations-own
  (testing "A site the reader anchored nowhere inherits the DECLARATION's
            position rather than inventing one — the widest true statement, and
            the reason absence is reserved for the case where even that is
            unavailable."
    (let [m (manifest-of (with-meta (declaration 'inherited-coord-view)
                                    {:line 4242 :column 7}))]
      (doseq [entry (entries m)]
        (is (= {:line 4242 :column 7}
               (select-keys (:source-coord entry) [:line :column]))
            "every site inherits the synthesized declaration's line and column")))))

;; ---------------------------------------------------------------------------
;; No third shape
;; ---------------------------------------------------------------------------

(deftest no-manifest-publishes-a-partial-coordinate
  (testing "The whole law, quantified over both declarations at once: a
            coordinate is a total map or the key is not there. A `{:file}` with
            no line — the shape this Bead was filed against — is neither."
    (doseq [[label m] [["unanchored" (manifest-of (declaration 'law-generated-view))]
                       ["anchored"   (manifest-of (with-meta (declaration 'law-anchored-view)
                                                             {:line 11 :column 3}))]]
            entry (entries m)]
      (is (or (not (contains? entry :source-coord))
              (total-coord? (:source-coord entry)))
          (str label " — total or absent, never partial: "
               (pr-str (:source-coord entry)))))))

(deftest an-interpreted-generated-declaration-is-unaffected
  (testing "An interpreted declaration publishes no manifest, so it promises no
            coordinate. A wrapper emitting one was always legal and stays so —
            stated here because the refusal this replaces was compiled-only and
            a reader is entitled to see the interpreted side pinned too."
    (let [form (list `v/defview 'interpreted-generated '[_] '[:div])]
      (is (seq? (expand form)) "it expands")
      (is (nil? (manifest-of form))
          "and carries no manifest to be partial about"))))
