(ns hyperphor.alzabo.datagen.jazz
  "Jazz-specific data generators for Alzabo schemas"
  (:require [hyperphor.alzabo.datagen :as datagen]
            [clojure.string :as str]))

;;; Jazz-specific data sets

(def jazz-musician-names
  ["Miles Davis" "John Coltrane" "Charlie Parker" "Duke Ellington" "Billie Holiday"
   "Louis Armstrong" "Ella Fitzgerald" "Thelonious Monk" "Bill Evans" "Art Tatum"
   "Count Basie" "Benny Goodman" "Dizzy Gillespie" "Sarah Vaughan" "Lester Young"
   "Coleman Hawkins" "Art Blakey" "Max Roach" "Charles Mingus" "Wynton Marsalis"])

(def jazz-band-names
  ["The Miles Davis Quintet" "Art Blakey's Jazz Messengers" "The Modern Jazz Quartet"
   "Weather Report" "Return to Forever" "The Crusaders" "Spyro Gyra" "Steps Ahead"
   "The Yellowjackets" "Fourplay" "The Pat Metheny Group" "The Brecker Brothers"
   "The Manhattan Transfer" "Tito Puente Orchestra" "The Count Basie Orchestra"])

(def jazz-song-titles
  ["Take Five" "So What" "Blue in Green" "All Blues" "Round Midnight"
   "Giant Steps" "My Favorite Things" "Summertime" "Body and Soul" "Autumn Leaves"
   "Sweet Georgia Brown" "Caravan" "Take the A Train" "Mood Indigo" "Satin Doll"])

(def jazz-album-titles
  ["Kind of Blue" "A Love Supreme" "Time Out" "Bitches Brew" "Blue Train"
   "The Shape of Jazz to Come" "Mingus Ah Um" "Waltz for Debby" "Head Hunters"
   "Heavy Weather" "In a Silent Way" "Moanin'" "Giant Steps" "Birth of the Cool"])

(def jazz-venues
  ["Blue Note" "Village Vanguard" "Jazz at Lincoln Center" "Preservation Hall"
   "Cotton Club" "Birdland" "The Apollo Theater" "Ronnie Scott's" "Montreux Jazz Festival"])

(def jazz-genres
  ["Bebop" "Cool Jazz" "Hard Bop" "Free Jazz" "Fusion" "Swing" "Dixieland"
   "Latin Jazz" "Smooth Jazz" "Acid Jazz" "Nu Jazz" "Avant-garde Jazz"])

(def jazz-record-labels
  ["Blue Note Records" "Columbia Records" "Prestige Records" "Atlantic Records"
   "Verve Records" "ECM Records" "CTI Records" "Impulse! Records" "Concord Jazz"])

(def instrument-names
  {:BRASS ["Trumpet" "Cornet" "Flugelhorn" "Trombone" "French Horn" "Tuba"]
   :WOODWIND ["Saxophone" "Alto Saxophone" "Tenor Saxophone" "Baritone Saxophone"
              "Clarinet" "Bass Clarinet" "Flute" "Piccolo" "Oboe" "Bassoon"]
   :STRING ["Guitar" "Electric Guitar" "Bass Guitar" "Upright Bass" "Violin" "Viola" "Cello" "Harp"]
   :PERCUSSION ["Drums" "Vibraphone" "Marimba" "Xylophone" "Timpani" "Congas" "Bongos"]
   :KEYBOARD ["Piano" "Electric Piano" "Hammond Organ" "Synthesizer" "Harpsichord"]
   :VOICE ["Vocals" "Lead Vocals" "Backing Vocals"]
   :ELECTRONIC ["Synthesizer" "Electric Piano" "Electronic Drums" "Sampler"]})

;;; Jazz-specific field generators

(defn generate-jazz-name
  "Generate a realistic jazz-related name using LLM or fallback to predefined names"
  [context]
  (or (datagen/call-llm (str "Generate a realistic " context " name. Return only the name, nothing else."))
      (datagen/random-element (case context
                                "jazz musician" jazz-musician-names
                                "jazz band" jazz-band-names
                                "jazz song" jazz-song-titles
                                "jazz album" jazz-album-titles
                                "jazz venue" jazz-venues
                                "record label" jazz-record-labels
                                [context]))))

;;; Jazz schema-specific field generators
;;; These register specific generators for jazz schema field types

;; Reference type handlers for jazz entities
(defmethod datagen/generate-field-value :Musician
  [field-def kind field-name schema]
  (datagen/generate-reference :Musician (:required? field-def)))

(defmethod datagen/generate-field-value :Band
  [field-def kind field-name schema]
  (datagen/generate-reference :Band (:required? field-def)))

(defmethod datagen/generate-field-value :Song
  [field-def kind field-name schema]
  (datagen/generate-reference :Song (:required? field-def)))

(defmethod datagen/generate-field-value :Recording
  [field-def kind field-name schema]
  (datagen/generate-reference :Recording (:required? field-def)))

(defmethod datagen/generate-field-value :Genre
  [field-def kind field-name schema]
  (datagen/generate-reference :Genre (:required? field-def)))

(defmethod datagen/generate-field-value :RecordLabel
  [field-def kind field-name schema]
  (datagen/generate-reference :RecordLabel (:required? field-def)))

(defmethod datagen/generate-field-value :Instrument
  [field-def kind field-name schema]
  (datagen/generate-reference :Instrument (:required? field-def)))

(defmethod datagen/generate-field-value :Venue
  [field-def kind field-name schema]
  (datagen/generate-reference :Venue (:required? field-def)))

;; Jazz-specific enum handlers
(defmethod datagen/generate-field-value :InstrumentCategory
  [field-def kind field-name schema]
  (datagen/random-element (keys (get-in schema [:enums :InstrumentCategory :values]))))

(defmethod datagen/generate-field-value :RecordingFormat
  [field-def kind field-name schema]
  (datagen/random-element (keys (get-in schema [:enums :RecordingFormat :values]))))

;; Note: Jazz-specific string field handlers would need a different approach
;; since we can't dispatch on both type and context anymore.
;; These would need to be handled in the main :string method by checking
;; the kind and field-name parameters, or through a separate mechanism.
