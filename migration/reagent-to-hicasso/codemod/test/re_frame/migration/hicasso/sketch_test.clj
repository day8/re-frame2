(ns re-frame.migration.hicasso.sketch-test
  "**The suggested declaration, round-tripped** (rf2-vi11).

  The report's `:defhost` sketch is the one thing in the artefact a
  migrator is invited to PASTE, and for the tool's whole life it printed a
  declaration the destination would refuse:

      (h/defhost btn Btn
        {:callbacks {:on-pick :fn}})

  `:fn` is not one of the three contracts `defhost` accepts, so pasting
  what the tool suggested threw
  `:rf.error/hicasso-unknown-callback-contract` at mint. At a string head
  it was worse — `(h/defhost \"button\" \"button\" …)` is not a form the
  READER takes, let alone the door.

  It survived because nothing ever read it back. The golden corpus asserts
  the report's `:entries` and stops there, so the suggestions block — the
  half of the report that tells a migrator what to WRITE — was ungated.
  This namespace closes that: it builds the real report artefact over
  every corpus case and round-trips every sketch it finds.

  ## What \"acceptable to the door\" is asserted to mean

  `mint-host!` is `.cljs` and this JVM cannot call it, so the door is
  asserted through its rules rather than through its code:

  1. the sketch READS — one form, no reader error;
  2. it is `(h/defhost <name> <component> …)` with a name `def` will take;
  3. every contract it names is in [[dest/callback-contracts]], which
     `shared-rule-test` holds equal to the door's own roster;
  4. and — the assertion that is not vacuous — FILLING the scaffold
     yields a `:callbacks` map of exactly this site's positions, each
     mapped to a real contract. The sketch leaves the contracts blank
     because the tool cannot know them; [[filling-the-scaffold-mints]]
     is what proves the blanks are blanks in the right places."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.codemod :as cm]
            [re-frame.migration.hicasso.dest :as dest]
            [re-frame.migration.hicasso.report :as report]))

;; ---------------------------------------------------------------------------
;; The corpus, through the REAL report builder
;; ---------------------------------------------------------------------------

(defn- corpus-inputs []
  (->> (.listFiles (io/file "test" "corpus"))
       (filter #(.isDirectory ^java.io.File %))
       (sort-by #(.getName ^java.io.File %))
       (keep (fn [dir]
               (first (filter #(str/starts-with? (.getName ^java.io.File %) "input.")
                              (.listFiles ^java.io.File dir)))))))

(defn- corpus-report
  "The artefact the CLI writes, over the whole corpus at once — so this
  suite exercises `report/build` rather than a private helper, and sees
  the sketches exactly as a migrator does."
  []
  (let [results (mapv #(cm/scan-string (slurp %) (str "src/app/" (.getName ^java.io.File %)))
                      (corpus-inputs))]
    (report/build {:entries          (vec (mapcat :entries results))
                   :suggestions      (vec (mapcat :suggestions results))
                   :files-scanned    (count results)
                   :files-changed    0
                   :sites-total      (reduce + 0 (map :sites results))
                   :sites-left-alone (reduce + 0 (map :left-alone results))})))

(defn- components [] (get-in (corpus-report) [:suggestions :components]))

(def ^:private contracts (set dest/callback-contracts))

;; ---------------------------------------------------------------------------
;; The pin is only worth something if it has something to read
;; ---------------------------------------------------------------------------

(deftest the-corpus-emits-sketches
  (testing "a corpus that produced no suggestion block would pass every
            assertion below and mean nothing — which is the shape of the
            hole `:fn` lived in"
    (let [cs (components)]
      (is (<= 3 (count cs))
          "the corpus must exercise the suggestion path — see
           test/corpus/the-suggested-declaration")
      (is (every? #(seq (:defhost %)) cs)
          "every suggested component carries a sketch"))))

;; ---------------------------------------------------------------------------
;; 1–3. The sketch is a declaration the door would take
;; ---------------------------------------------------------------------------

(defn- read-one
  "Read the sketch as data. `clojure.edn` and not `read-string`: a report
  is text from a consumer's tree and nothing here should be able to run."
  [sketch]
  (edn/read-string sketch))

(deftest every-sketch-is-acceptable-to-the-door
  (doseq [{:keys [head defhost]} (components)]
    (testing (str "the sketch for " head)
      (let [form (read-one defhost)
            [op nm _component opts] form]

        (testing "reads as one `h/defhost` form"
          (is (list? form))
          (is (= 'h/defhost op))
          (is (<= 3 (count form)) "head, name and component at least"))

        (testing "names the host with a symbol `def` will take — the old
                  sketch lower-cased the head text, which at a string head
                  produced `(h/defhost \"button\" …)`"
          (is (symbol? nm) (str "the name position is not a symbol: " (pr-str nm)))
          (is (nil? (namespace nm)) (str "a qualified name cannot be `def`ed: " nm))
          (is (not (str/includes? (name nm) "."))
              (str "a dotted name cannot be `def`ed: " nm)))

        (testing "names no contract outside the door's roster — this is
                  the assertion `:fn` failed"
          (doseq [[slot contract] (:callbacks opts)]
            (is (contains? contracts contract)
                (str "the sketch declares " (pr-str slot) " as " (pr-str contract)
                     ", which the door refuses; the contracts are "
                     (str/join ", " (map pr-str dest/callback-contracts))))))

        (testing "declares no structural slot — `key` and `ref` are
                  refused at mint in every spelling"
          (doseq [slot (keys (:callbacks opts))]
            (is (not (contains? #{"key" "ref"} (dest/canonical-slot slot)))
                (str "the sketch declares the structural slot " (pr-str slot)))))))))

;; ---------------------------------------------------------------------------
;; 4. The scaffold fills — the assertion that is not vacuous
;; ---------------------------------------------------------------------------

(def ^:private commented-row
  "A scaffold row: an indented `;;` carrying exactly one token, which is a
  position. The roster sentence on the first line carries several words
  and never matches."
  #"(?m)^(\s*);; (\S+)$")

(defn- fill
  "What a migrator does to the sketch: uncomment every position row and
  give each one a contract."
  [sketch contract]
  (str/replace sketch commented-row (str "$1$2 " (pr-str contract))))

(deftest filling-the-scaffold-mints
  (doseq [{:keys [head defhost event-slots fn-slots]} (components)
          contract dest/callback-contracts]
    (testing (str "the sketch for " head " filled with " contract)
      (let [slots (mapv edn/read-string (distinct (concat event-slots fn-slots)))
            opts  (nth (read-one (fill defhost contract)) 3)]

        (is (= (set slots) (set (keys (:callbacks opts))))
            (str "the filled sketch declares " (pr-str (keys (:callbacks opts)))
                 " where the site uses " (pr-str slots)))

        (is (seq (:callbacks opts))
            "a filled scaffold that declared nothing would make every
             assertion here vacuous")

        (is (every? contracts (vals (:callbacks opts)))
            "every filled position carries a contract the door accepts")))))

;; ---------------------------------------------------------------------------
;; 5. A native tag is not a host
;; ---------------------------------------------------------------------------

(deftest a-string-head-is-never-suggested
  (testing "W6 rewrites `[:> \"input\" …]` to `[:input …]`; there is no
            host at a native tag, and the sketch that used to be offered
            there — `(h/defhost \"input\" \"input\" …)` — was not even
            readable"
    (doseq [{:keys [head]} (components)]
      (is (not (string? (edn/read-string head)))
          (str "a string head was offered a declaration: " head)))))

;; ---------------------------------------------------------------------------
;; 6. The text itself, where a reader will look for it
;; ---------------------------------------------------------------------------

(defn- sketch-for [head]
  (->> (components) (filter #(= head (:head %))) first :defhost))

(deftest the-sketch-reads-the-way-it-is-meant-to
  (testing "an ordinary symbol head"
    (is (= (str "(h/defhost bar js/Foo.Bar\n"
                "  {:callbacks {;; each position takes :event, :handler or :render — see :caution\n"
                "               ;; :on-change\n"
                "               ;; :on-render-row\n"
                "               }})")
           (sketch-for "js/Foo.Bar"))))

  (testing "an expression head takes the placeholder name, and the
            component position is the head verbatim"
    (is (= (str "(h/defhost your-host (.-Provider ctx)\n"
                "  {:callbacks {;; each position takes :event, :handler or :render — see :caution\n"
                "               ;; :on-close\n"
                "               }})")
           (sketch-for "(.-Provider ctx)")))))
