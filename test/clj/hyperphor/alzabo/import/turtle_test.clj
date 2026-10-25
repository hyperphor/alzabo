(ns hyperphor.alzabo.import.turtle-test
  (:require [hyperphor.alzabo.import.turtle :as sut]
            [hyperphor.alzabo.schema :as schema]
            [clojure.test :refer :all]))

(def schema (sut/ttl->schema "test/resources/turtle/bible-mini.ttl"))

(deftest test-kinds
  (is (= #{:Thing :Person :Prophet :Place :Event :Book} (set (keys (:kinds schema)))))
  (is (= "Bible mini ontology" (:title schema)))
  (is (= "A person mentioned in the text." (get-in schema [:kinds :Person :doc])))
  (is (= "http://example.org/bible#Person" (get-in schema [:kinds :Person :uri]))))

(deftest test-extends
  ;; External superclass (foaf:Person) is dropped
  (is (= :Thing (get-in schema [:kinds :Person :extends])))
  (is (= :Person (get-in schema [:kinds :Prophet :extends])))
  (is (nil? (get-in schema [:kinds :Book :extends])))
  (is (contains? (schema/all-fields schema :Prophet) :father)))

(deftest test-fields
  (let [field #(get-in schema [:kinds %1 :fields %2])]
    (is (= {:type :string :cardinality :one :doc "Primary name."} (field :Thing :name)))
    (is (= {:type :long :cardinality :one} (field :Person :age)))
    (is (= {:type :float :cardinality :many} (field :Place :latitude)))
    (is (= {:type :Person :cardinality :one} (field :Person :father)))
    (is (= {:type :Person :cardinality :many} (field :Event :participant)))
    (testing "union domain attaches to each member"
      (is (= :Book (:type (field :Person :mentionedIn))))
      (is (= :Book (:type (field :Place :mentionedIn)))))
    (testing "range outside the ontology falls back to :string"
      (is (= :string (:type (field :Person :homepage))))
      (is (= :string (:type (field :Person :knows)))))
    (testing "property without domain is dropped"
      (is (not-any? #(contains? (:fields %) :orphan) (vals (:kinds schema)))))))

(deftest test-valid
  (is (schema/validate-schema schema)))
