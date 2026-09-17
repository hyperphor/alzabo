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
