(ns re-frame.migration.fresco.sketch-test
  "Round-trip the declarations a migrator is invited to paste."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.codemod :as rf.migration.fresco.codemod]
            [re-frame.migration.fresco.report :as rf.migration.fresco.report]))

(defn- corpus-inputs []
  (->> (.listFiles (io/file "test" "corpus"))
       (filter #(.isDirectory ^java.io.File %))
       (sort-by #(.getName ^java.io.File %))
       (keep (fn [dir]
               (first (filter #(str/starts-with? (.getName ^java.io.File %) "input.")
                              (.listFiles ^java.io.File dir)))))))

(defn- components
  []
  (let [results (mapv #(rf.migration.fresco.codemod/scan-string (slurp %) (str "src/app/" (.getName ^java.io.File %)))
                      (corpus-inputs))]
    (get-in (rf.migration.fresco.report/build {:entries          (vec (mapcat :entries results))
                                               :suggestions      (vec (mapcat :suggestions results))
                                               :files-scanned    (count results)
                                               :files-changed    0
                                               :sites-total      (reduce + 0 (map :sites results))
                                               :sites-left-alone (reduce + 0 (map :left-alone results))})
            [:suggestions :components])))

(def ^:private listed-row
  #"(?m)^;;   (\S+)$")

(deftest every-sketch-is-one-bare-declaration
  (let [cs (components)]
    (is (seq cs) "a corpus that suggested nothing would make every assertion here vacuous")
    (doseq [{:keys [head defhost event-slots fn-slots]} cs]
      (testing (str "the sketch for " head)
        (is (not (string? (edn/read-string head)))
            "a string head is a native tag, which W6 rewrites; there is no host to declare")
        (let [form (edn/read-string defhost)]
          (is (= 'h/defhost (first form)))
          (is (= 3 (count form))
              "name and component, and no options: a declaration carries nothing about callbacks"))
        (is (= (distinct (concat event-slots fn-slots))
               (map second (re-seq listed-row defhost)))
            "every prop the site carries a function or an event spelling at is listed to check")))))

(defn- sketch-for [head]
  (->> (components) (filter #(= head (:head %))) first :defhost))

(deftest the-sketch-reads-the-way-it-is-meant-to
  (testing "ordinary symbol head"
    (is (= (str "(h/defhost bar js/Foo.Bar)\n"
                ";; check the carrier at each call site — see :caution\n"
                ";;   :on-change\n"
                ";;   :on-render-row")
           (sketch-for "js/Foo.Bar"))))

  (testing "expression head"
    (is (= (str "(h/defhost your-host (.-Provider ctx))\n"
                ";; check the carrier at each call site — see :caution\n"
                ";;   :on-close")
           (sketch-for "(.-Provider ctx)")))))
