(ns hyperphor.alzabo.search.lexicon
  (:require [clojure.string :as str]
            [hyperphor.multitool.nlp :as nlp]))
  
(defn add-def-word [dict word def]
  (let [word (str/lower-case word)]
    (assoc dict word (cons def (get dict word '())))))

(defn name-words
  "Words of a name, split at - and _ and camelCase boundaries, plus the whole name."
  [s]
  (distinct (cons s (remove str/blank? (str/split s #"[-_]|(?<=[a-z0-9])(?=[A-Z])")))))

(defn add-def-key [dict key def]
  (reduce (fn [dict word]
            (add-def-word dict word def))
          dict
          (name-words (name key))))

(defn add-def-text [dict text def]
  (if text
    (reduce (fn [dict word]
              (add-def-word dict word def))
            dict
            (nlp/tokens text))
    dict))

(defn kind-dict [kinds dict]
  (reduce (fn [dict [kindname kinddef]]
            (reduce (fn [dict prop]
                      (-> dict
                          (add-def-key prop (list 'prop kindname prop))
                          (add-def-text (get-in kinddef [:fields prop :doc]) (list 'prop kindname prop))))
                    (-> dict
                        (add-def-key kindname (list 'kind kindname))
                        (add-def-text (:doc kinddef) (list 'kind kindname)))
                    (keys (:fields kinddef))))
          dict
          kinds))

(defn enum-dict [enums dict]
  (reduce (fn [dict [enum {:keys [values]}]]
            (reduce (fn [dict [v doc]]
                      (-> dict
                          (add-def-key v (list 'enum-value enum v doc))
                          (add-def-text doc (list 'enum-value enum v doc)))) 
                    (add-def-key dict enum (list 'enum enum))
                    values))
          dict
          enums))

(defn merged-dict [{:keys [kinds enums]}]
  (->> {}
       (kind-dict kinds)
       (enum-dict enums)))

(defn- def-name
  "The name a lexicon entry is for: the kind, field, enum or enum value."
  [[type & params]]
  (name (case type
          (kind enum) (first params)
          (prop enum-value) (second params))))

(defn- match-rank
  "0 if def's name starts with prefix, 1 if a word of it does, 2 if only its doc text matched."
  [prefix def]
  (let [n (def-name def)]
    (cond (str/starts-with? (str/lower-case n) prefix) 0
          (some #(str/starts-with? (str/lower-case %) prefix) (name-words n)) 1
          :else 2)))

;;; This is a slow algorithm; if the lexicon gets bigger, use a trie or similar data structure
(defn lookup [prefix lexicon]
  (if (empty? prefix)
    []
    (let [prefix (str/lower-case prefix)
          raw (filter (fn [[word defs]]
                        (= prefix (subs word 0 (min (count word) (count prefix)))))
                      lexicon)]
      ;; Entries whose own name matches before those matched only by doc text; then kinds, enums and
      ;; props (whose symbols happen to sort that way), then by name.
      (take 20 (sort-by (juxt #(match-rank prefix %) first def-name)
                        (distinct (mapcat identity (vals raw))))))))
