(ns hyperphor.alzabo.schema
  (:require [clojure.spec.alpha :as s]
            [hyperphor.multitool.core :as u]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [clojure.set :as set]
            ))


(def numeric-primitives #{:long :float :number :bigint})
(def primitives (set/union
                 numeric-primitives
                 #{:string :boolean :instant :keyword}))

;;; Schema spec

(s/def ::type (s/or :key keyword?       ;TODO check that these are actual types (kinds or primitives)
                    :vec (s/coll-of ::type  :kind vector?) ;for heterogenous tuples
                    :map (s/keys :req-un [*])              ;for homogenous tuples
                    ))

(s/def ::cardinality #{:one :many})
(s/def ::doc string?)

;;; Note: this insane rigamarole is so schema can actually detect undefined keys in a map. Should use it eslewhere.
(defmacro strict-keys [& {:keys [req req-un opt opt-un]}]
  (let [;; Extract the actual keyword names for validation
        req-key-names (when req (set req))
        opt-key-names (when opt (set opt))
        req-un-key-names (when req-un (set (map #(keyword (name %)) req-un)))
        opt-un-key-names (when opt-un (set (map #(keyword (name %)) opt-un)))
        
        ;; Combine all allowed keys
        all-keys (set/union (or req-key-names #{})
                           (or opt-key-names #{})
                           (or req-un-key-names #{})
                           (or opt-un-key-names #{}))
        
        ;; Required keys for validation
        required-keys (set/union (or req-key-names #{})
                                (or req-un-key-names #{}))]
    
    `(s/and
       (s/keys ~@(when req [:req req])
               ~@(when req-un [:req-un req-un])
               ~@(when opt [:opt opt])
               ~@(when opt-un [:opt-un opt-un]))
       ;; Only allow specified keys
       #(set/subset? (set (keys %)) ~all-keys)
       ;; Ensure all required keys are present
       ~@(when (seq required-keys)
           [`#(set/subset? ~required-keys (set (keys %)))]))))

(s/def ::field (strict-keys :req-un []
                            :opt-un [::type ::cardinality ::required? ::unique? ::component?
                                     ::doc ;TODO ::examples, ::generator
                                     ::index ::attribute
                                     ::min ::max]))


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
    (if (nil? (s/explain-data ::schema schema)) ;TODO for some reason even when schema valid, s/valid? fails, but this woorks
      schema
      (throw (ex-info "Schema invalid" {:explanation (s/explain-str ::schema schema)})))))

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

;;; Schema introspection utilities

(defn kinds
  "Get all kind names from a schema"
  [schema]
  (keys (get schema :kinds)))

(defn enums
  "Get all enum names from a schema"
  [schema]
  (keys (:enums schema)))

(defn kind-def
  [schema kind]
  (assoc (get-in schema [:kinds kind]) :id kind))

(defn is-reference-type?
  "Check if a type is a reference to another kind"
  [type-name schema]
  (contains? (set (kinds schema)) type-name))

(defn is-enum-type?
  "Check if a type is an enum"
  [type-name schema]
  (contains? (set (enums schema)) type-name))

(u/defn-memoized inverse-fields
  [schema]
  (reduce-kv (fn [acc kind kdf]
               (reduce-kv (fn [acc field fdf]
                            (assoc-in acc [(:type fdf) field] #_kind (assoc fdf :type kind)))
                          acc
                          (:fields kdf)))
             {}
             schema))

