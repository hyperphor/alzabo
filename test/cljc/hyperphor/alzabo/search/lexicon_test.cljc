(ns hyperphor.alzabo.search.lexicon-test
  (:require [hyperphor.alzabo.search.lexicon :as lex]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

;;; Regression schema for design/TODO.md: "purp" should match designs.primary_purpose,
;;; via both the underscore-split field name and the kind-level :doc text.
(def test-schema
  {:kinds
   {:designs
    {:fields {:primary_purpose {:type :designs.primary_purpose :cardinality :one}}
     :doc "A study's design methodology (allocation, masking/blinding, intervention model, primary purpose)"}
    :gene
    {:fields {:lead-gene {:type :string}}}}
   :enums {}})

(def dict (lex/merged-dict test-schema))

(deftest underscore-splits-into-words
  (is (seq (lex/lookup "purp" dict))))

(deftest kind-doc-is-indexed
  (is (some #{'(kind :designs)} (lex/lookup "purp" dict))))

(deftest hyphen-splitting-still-works
  (is (= '((prop :gene :lead-gene)) (lex/lookup "lead" dict))))

;;; PascalCase kind names (e.g. from OWL imports): "lobe" should find every *Lobe kind, ahead of
;;; kinds that only mention lobes in their docs, even with more than 20 matches.
(def lobe-schema
  {:kinds (merge (into {} (for [i (range 25)]
                            [(keyword (str "Thing" i)) {:fields {} :doc "Part of the planning lobe."}]))
                 {:PlanningLobe {:fields {}}
                  :TimeLobe {:fields {:startsAt {:type :instant}}}})
   :enums {}})

(def lobe-dict (lex/merged-dict lobe-schema))

(deftest camel-case-splits-into-words
  (is (= '((kind :PlanningLobe) (kind :TimeLobe)) (take 2 (lex/lookup "lobe" lobe-dict))))
  (is (= '((prop :TimeLobe :startsAt)) (lex/lookup "starts" lobe-dict))))

(deftest whole-name-still-matches
  (is (= '(kind :PlanningLobe) (first (lex/lookup "planninglobe" lobe-dict)))))

(deftest names-starting-with-the-prefix-first
  (let [d (lex/merged-dict {:kinds (merge (into {} (for [i (range 25)] [(keyword (str "Allocation" i "Planning")) {:fields {}}]))
                                          {:PlanningLobe {:fields {}}})})]
    (is (= '(kind :PlanningLobe) (first (lex/lookup "planning" d))))))
