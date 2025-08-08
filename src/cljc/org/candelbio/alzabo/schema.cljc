(ns org.candelbio.alzabo.schema
  (:require [clojure.spec.alpha :as s]
            [org.candelbio.multitool.core :as u]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            ))

;;; TODO need a required/optional flag or equivalent.
;;; TODO Schema validation isn't working, it didn't detect when I was stupidly using :description instead of :doc

(def primitives #{:long :float :string :boolean :instant :keyword ;Datomic
                  :number :bigint                                 ;other
                  })

;;; Schema spec

(s/def ::type (s/or :key keyword?       ;TODO check that these are actual types (kinds or primitives)
                    :vec (s/coll-of ::type  :kind vector?) ;for heterogenous tuples
                    :map (s/keys :req-un [*])              ;for homogenous tuples
                    ))

(s/def ::cardinality #{:one :many})
(s/def ::doc string?)

;;; I want to say that ONLY these keys are allowed, which would catch some
;;; errors. But apparently that is unClojurish or something?
(s/def ::field (s/keys :req-un []
                       :opt-un [::type ::cardinality ::doc ::unique ::index ::attribute]))

(s/def ::fields (s/map-of keyword? ::field))

(s/def ::kind (s/keys :req-un [::fields] ;::inherits, but CANDEL not using that.
                      :opt-un [::doc
                               ::uri
                               ::reference?])) ;TODO reference is too CANDEL-specific, replace with a kind-labels or tags or something
                      
(s/def ::kinds (s/map-of keyword? ::kind :conform-keys? true))

;;; Enum :values is a map of keywords (db/ident) to a doc string
(s/def ::values (s/map-of keyword? string?))

(s/def ::enum (s/keys :req-un [::values]
                      :opt-un [::doc]))

(s/def ::enums (s/map-of keyword? ::enum :conform-keys? true))

(s/def ::version string?)

(s/def ::uri (s/or :string string? :key keyword?))

(s/def ::schema (s/keys :req-un [::kinds]
                        :opt-un [::enums ::version ::title]))

(defn validate-schema [schema]
  (if (s/valid? ::schema schema)
    schema
    (throw (ex-info "Schema invalid" {:explanation (s/explain-str ::schema schema)}))))

;;; One in multitool is broken
(defn strip-chars
  "Removes every character of a given set from a string"
  [removed s]
  (apply str (remove #((set removed) %) s)))

(defn clean-string
  [s]
  (strip-chars "()," s))

;;; Kebab, but handle some strings separately
(defn safe-kebab-case
  [s]
  (if (= \- (last s))
    s
    (csk/->kebab-case s)))

(defn reduce-old-candel-shit
  [s]
  (if-let [m (re-matches #"\:.*/(.*)" s)]
    (second m)
    s))

(defn clean-enum-value
  [v]
  (-> v
      name
      clean-string
      reduce-old-candel-shit
      safe-kebab-case
      #_ keyword))

(defn humanize
  [term]
  (when term
    (-> term
        name
        (str/replace "_" " "))))

;;; → Multitool - this is the cheap-ass way to do BK's 2-way structs. Not efficient of course
(defn struct-parent
  [struct thing]
  (u/walk-find-path 
   #(= % thing) struct))



(defn infer-enums
  [s]
  (let [new-enums (atom [])
        ns
        (clojure.walk/postwalk
         (fn [thing]
           (if (and (map-entry? thing)
                    (= :enumerated (:type (second thing))))
             (let [[field fd] thing
                   values (:values fd)
                   kind (let [[_ path] (struct-parent s thing)]
                          (second (reverse path)))
                   enum (u/keyword-conc kind field "enum")]
               (swap! new-enums
                      conj
                      [enum {:values (zipmap (map (comp keyword clean-enum-value) values)
                                             (map humanize values))}])
               [field (-> fd
                          (assoc :type enum)
                          (dissoc :values))])
             thing))
         s)]
    (update ns :enums merge (into {} @new-enums))))


#?
(:clj
 (defn read-schema
   [source]
   (-> source
       slurp
       read-string
       infer-enums
       validate-schema)))
