(ns ents.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ents.core :as ec]
            [ents.ffi :as f]))

(def Position (ec/component Position [:x :float] [:y :float]))
(def Velocity (ec/component Velocity [:x :float] [:y :float]))
(def Health   (ec/component Health [:hp :int32]))
(def my-pos   (ec/component my-pos [:x :float]))

(deftest world-lifecycle
  (ec/with-world w
    (is (number? w))))

(deftest entity-naming
  (ec/with-world w
    (let [e (ec/entity! w :player)]
      (is (pos? e))
      (is (= e (ec/ent w :player)))
      (is (= "player" (ec/ent-name-of w :player))))
    (is (zero? (ec/ent w :missing)))))

(deftest tags-and-components
  (ec/with-world w
    (let [e (ec/entity! w :player :tagged Position {:x 1.5 :y 2.5})]
      (is (ec/has? w e :tagged))
      (is (ec/has? w e Position))
      (is (= {:x 1.5 :y 2.5} (ec/get-c w e Position))))
    (ec/remove! w :player :tagged)
    (is (not (ec/has? w :player :tagged)))))

(deftest set-roundtrip
  (ec/with-world w
    (ec/entity! w :e Position {:x 0 :y 0})
    (ec/set-c! w :e Position {:x 10.5 :y -2.25})
    (is (= {:x 10.5 :y -2.25} (ec/get-c w :e Position)))
    (ec/set-c! w :e Health {:hp 42})
    (is (= {:hp 42} (ec/get-c w :e Health)))))

(deftest pairs
  (ec/with-world w
    (ec/entity! w :parent)
    (ec/entity! w :child [:child-of :parent])
    (is (ec/has? w :child [:child-of :parent]))
    (is (= (ec/ent w :parent) (ec/target w :child :child-of)))))

(deftest anon-entities
  (ec/with-world w
    (let [a (ec/anon! w Position {:x 3 :y 4})]
      (is (pos? a))
      (is (= {:x 3.0 :y 4.0} (ec/get-c w a Position))))))

(deftest query-basic
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1} Velocity {:x 1 :y 0})
    (ec/entity! w :b Position {:x 2 :y 2})
    (is (= 2 (ec/count-q w "Position")))
    (is (= 1 (ec/count-q w "Position, Velocity")))
    (let [rows (ec/query w [Position Velocity])]
      (is (= 1 (count rows)))
      (is (= (ec/ent w :a) (:e (first rows))))
      (is (= 1.0 (get-in (first rows) [:Position :x]))))))

(deftest with-query-macro
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (ec/entity! w :b Position {:x 2 :y 2})
    (let [xs (ec/with-query w [p Position e :ent]
               [e (:x p)])]
      (is (= 2 (count xs)))
      (is (= #{1.0 2.0} (set (map second xs)))))))

(deftest system-progress
  (ec/with-world w
    (ec/entity! w :mover Position {:x 0 :y 0} Velocity {:x 1.5 :y 0})
    (ec/system! w :move [p Position v Velocity e :ent]
      (ec/set-c! w e Position {:x (+ (:x p) (:x v))
                               :y (+ (:y p) (:y v))}))
    (ec/progress w 0.016)
    (is (= {:x 1.5 :y 0.0} (ec/get-c w :mover Position)))))

(deftest system-dt-binding
  (ec/with-world w
    (ec/entity! w :m Position {:x 0 :y 0} Velocity {:x 10 :y 0})
    (ec/system! w :move [p Position v Velocity e :ent dt :dt]
      (ec/set-c! w e Position {:x (+ (:x p) (* (:x v) dt))}))
    (ec/progress w 0.5)
    (is (= 5.0 (:x (ec/get-c w :m Position))))))

(deftest observer-on-add
  (ec/with-world w
    (let [seen (atom [])]
      ;; OnAdd fires when the component is first added; flecs docs note the
      ;; value is not yet assigned there, so we assert on the fact of the
      ;; event, not its payload (OnSet carries the value, see below).
      (ec/observer! w :saw-health [h Health :events #{:add}]
        (swap! seen conj (:hp h)))
      (ec/entity! w :mob Health {:hp 7})
      (is (= 1 (count @seen))))))

(deftest observer-on-set-carries-value
  (ec/with-world w
    (let [seen (atom [])]
      (ec/observer! w :saw-health-set [h Health :events #{:set}]
        (swap! seen conj (:hp h)))
      (ec/entity! w :mob Health {:hp 7})
      (is (= [7] @seen)))))

(deftest observer-event-binding
  (ec/with-world w
    (let [events (atom [])]
      (ec/observer! w :watch
                    [h Health ev :event :events #{:add :remove}]
        (swap! events conj ev))
      (ec/entity! w :mob Health {:hp 1})
      (ec/remove! w :mob Health)
      (is (= 2 (count @events))))))

(deftest delete-entities
  (ec/with-world w
    (ec/entity! w :gone Position {:x 1 :y 1})
    (is (= 1 (ec/count-q w "Position")))
    (ec/delete! w :gone)
    (is (= 0 (ec/count-q w "Position")))
    (is (zero? (ec/ent w :gone)))))

;; --- round 1: misuse must throw, never abort the process ---------------------

(deftest missing-entity-throws
  (ec/with-world w
    (is (thrown? Exception (ec/get-c w :nope Position)))
    (is (thrown? Exception (ec/set-c! w :nope Position {:x 1})))
    (is (thrown? Exception (ec/add! w :nope :tag)))
    (is (thrown? Exception (ec/delete! w :nope)))
    (is (thrown? Exception (ec/target w :nope :rel)))
    (is (thrown? Exception (ec/has? w :nope Position)))
    (is (thrown? Exception (ec/ent-name-of w :nope)))
    (let [e (ec/anon! w)]
      (ec/delete! w e)
      (is (thrown? Exception (ec/get-c w e Position)))
      (is (false? (ec/alive? w e))))
    (is (false? (ec/alive? w :nope)))
    (is (false? (ec/valid? w :nope)))))

(deftest unknown-ids-are-absent
  (ec/with-world w
    (ec/entity! w :e)
    (is (false? (ec/has? w :e :never-made)))
    (is (false? (ec/has? w :e [:never-rel :never-tgt])))
    (is (nil? (ec/remove! w :e :never-made)))
    (is (nil? (ec/target w :e :never-rel)))
    (is (true? (ec/has? w (ec/entity! w :t :tag) :tag)))))

(deftest system-exception-propagates
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (let [n (atom 0)]
      (ec/system! w :s [p Position]
        (when (= 1 (swap! n inc)) (throw (ex-info "boom" {}))))
      (is (thrown-with-msg? Exception #"boom" (ec/progress w 0.1)))
      (ec/progress w 0.1)
      (is (= 2 @n)))))

(deftest observer-exception-propagates
  (ec/with-world w
    (ec/observer! w :o [h Health :events #{:set}]
      (when (= 13 (:hp h)) (throw (ex-info "unlucky" {}))))
    (is (thrown-with-msg? Exception #"unlucky" (ec/entity! w :m Health {:hp 13})))
    (ec/set-c! w :m Health {:hp 1})
    (is (= {:hp 1} (ec/get-c w :m Health)))))

(deftest callback-errors-stay-in-their-world
  ;; two worlds driven at once: one whose system always throws must not leak
  ;; its pending error into the other's progress, or make it skip rows
  (let [n 2000
        bad (ec/make-world)
        good (ec/make-world)
        ran (atom 0)]
    (try
      (ec/entity! bad :a Position {:x 1 :y 1})
      (ec/entity! good :a Position {:x 1 :y 1})
      (ec/system! bad :s [p Position] (throw (ex-info "bad world" {})))
      (ec/system! good :s [p Position] (swap! ran inc))
      (let [drive (fn [w] (future
                            (loop [i 0 errs []]
                              (if (< i n)
                                (recur (inc i)
                                       (try (ec/progress w 0.016) errs
                                            (catch Exception e (conj errs (ex-message e)))))
                                errs))))
            fb (drive bad)
            fg (drive good)]
        (is (= n (count @fb)))
        (is (every? #{"bad world"} @fb))
        (is (= [] @fg))
        (is (= n @ran)))
      (finally
        (ec/destroy! bad)
        (ec/destroy! good)))))

(deftest callback-error-cleared-when-driver-throws
  ;; if the call into flecs throws after a callback caught an error, the
  ;; callback's error is the one thrown and it is not left pending, which
  ;; would make every later callback of the world skip its rows
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (let [n (atom 0)
          progress f/progress]
      (ec/system! w :s [p Position]
        (when (= 1 (swap! n inc)) (throw (ex-info "boom" {}))))
      (with-redefs [f/progress (fn [w dt] (progress w dt) (throw (ex-info "native" {})))]
        (is (thrown-with-msg? Exception #"boom" (ec/progress w 0.1))))
      (ec/progress w 0.1)
      (is (= 2 @n)))))

(deftest redefine-system
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1} Velocity {:x 5 :y 5})
    (let [seen (atom [])]
      (ec/system! w :s [p Position] (swap! seen conj :v1))
      (ec/system! w :s [v Velocity] (swap! seen conj (:x v)))
      (ec/progress w 0.1)
      (is (= [5.0] @seen)))))

(deftest redefine-observer
  (ec/with-world w
    (let [seen (atom [])]
      (ec/observer! w :o [h Health] (swap! seen conj :v1))
      (ec/observer! w :o [h Health] (swap! seen conj (:hp h)))
      (ec/entity! w :m Health {:hp 3})
      (is (= [3] @seen)))))

(deftest observer-bad-events
  (ec/with-world w
    (is (thrown? Exception (ec/observer! w :o [h Health :events #{:on-set}] nil)))))

(deftest run-system-default-dt
  (ec/with-world w
    (ec/entity! w :a Position {:x 0 :y 0})
    (let [n (atom 0)]
      (ec/system! w :s [p Position] (swap! n inc))
      (ec/run-system! w :s)
      (ec/run-system! w :s 0.5)
      (is (= 2 @n)))))

;; --- round 2: data correctness ------------------------------------------------

(def Stats (ec/component Stats [:a :uint16] [:b :uint32] [:c :uint8] [:d :int8]))
(def Flag (ec/component Flag [:on :bool]))
(def CTypes (ec/component CTypes [:c :char] [:s :short] [:us :ushort] [:i :int]
                          [:ui :uint] [:l :long] [:ul :ulong] [:z :size_t]
                          [:sz :ssize_t] [:b :byte] [:p :pointer]))
(def Wide (ec/component Wide [:u :uint64] [:i :int64] [:d :double]))
(def Shapes (ec/component Shapes [:v [:array :float 3]]
                          [:pos [:struct [[:x :float] [:y :float]]]] [:n :int32]))

;; flecs' own Component component, by path and by C symbol, as a C module's
;; components would be defined from ents
(def CoreComponent (ec/component flecs.core.Component [:size :int32] [:alignment :int32]))
(def ByCSymbol (ec/component EcsComponent [:size :int32] [:alignment :int32]))
(def CoreTooSmall (ec/component flecs.core.Component [:size :int32]))
(def CoreMisaligned (ec/component flecs.core.Component [:both :int64]))
;; shares only its short name with flecs.core.Poly
(def ShortPoly (ec/component Poly [:v :int32]))
(def BySymbolTooSmall (ec/component EcsComponent [:size :int32]))

(deftest partial-set-zeroes-new-component
  (ec/with-world w
    (dotimes [_ 50] (ec/anon! w Position {:x 99999.5 :y 12345.5}))
    (doseq [e (map :e (ec/query w [Position]))] (ec/delete! w e))
    (let [e (ec/anon! w Position {:x 1})]
      (is (= {:x 1.0 :y 0.0} (ec/get-c w e Position))))))

(deftest unsigned-fields
  (ec/with-world w
    (let [e (ec/anon! w Stats {:a 40000 :b 3000000000 :c 200 :d -5})]
      (is (= {:a 40000 :b 3000000000 :c 200 :d -5} (ec/get-c w e Stats))))))

(deftest failed-set-writes-nothing
  ;; a value that can't be written leaves the component as it was, with no
  ;; OnSet, rather than changing the fields written before it
  (ec/with-world w
    (let [sets (atom 0)]
      (ec/observer! w :watch [s Stats :events #{:set}] (swap! sets inc))
      (ec/entity! w :e Stats {:a 1 :c 1})
      (reset! sets 0)
      (doseq [m [{:a 99 :c 300}    ; out of range for :uint8
                 {:a 99 :mana 3}   ; unknown field
                 {:a 99 :c :lots}]] ; not a number
        (is (thrown? Exception (ec/set-c! w :e Stats m)) (pr-str m))
        (is (= {:a 1 :b 0 :c 1 :d 0} (ec/get-c w :e Stats)) (pr-str m)))
      (is (= 0 @sets))
      (testing "a component the entity lacks isn't added"
        (ec/entity! w :f)
        (is (thrown? Exception (ec/set-c! w :f Stats {:a 1 :c 300})))
        (is (false? (ec/has? w :f Stats)))
        (is (thrown? Exception (ec/entity! w :g Stats {:a 1 :c 300})))
        (is (zero? (ec/ent w :g)) "entity! doesn't create the entity either")))))

(deftest failed-entity-applies-nothing
  ;; every arg is checked before the entity is created or any arg applied
  (ec/with-world w
    (let [sets (atom 0)]
      (ec/observer! w :o [p Position :events #{:set}] (swap! sets inc))
      (testing "a new entity isn't created"
        (is (thrown? Exception (ec/entity! w :e :tagged Position {:x 1} Stats {:c 300})))
        (is (zero? (ec/ent w :e)))
        (is (zero? (ec/ent w :tagged)) "a tag named only in the failed call isn't created"))
      (testing "an existing entity is left as it was"
        (ec/entity! w :f)
        (is (thrown? Exception (ec/entity! w :f :tagged [:likes :g] Position {:x 1} Stats {:c 300})))
        (is (thrown-with-msg? Exception #"one of flecs' own"
                              (ec/entity! w :f Position {:x 1} CoreComponent)))
        (is (false? (ec/has? w :f Position)))
        (is (false? (ec/has? w :f :tagged)))
        (is (false? (ec/has? w :f [:likes :g]))))
      (testing "anon!"
        (is (thrown? Exception (ec/anon! w Position {:x 1} Stats {:c 300}))))
      (is (= 0 (ec/count-q w "Position")))
      (is (= 0 @sets)))))

(deftest entity-args-edges
  (ec/with-world w
    (ec/entity! w :f)
    (testing "a flecs component as a pair's relationship, with a new target"
      (is (thrown-with-msg? Exception #"one of flecs' own"
                            (ec/entity! w :f [CoreComponent :newtarget])))
      (is (zero? (ec/ent w :newtarget))))
    (testing "a failed anon! doesn't create an entity"
      ;; components registered first, so checking registers nothing new
      (ec/comp-id w Position)
      (ec/comp-id w Stats)
      (let [a (ec/anon! w)
            _ (is (thrown? Exception (ec/anon! w Position {:x 1} Stats {:c 300})))
            b (ec/anon! w)]
        (is (= (inc a) b))))
    (testing "a bad name or dead entity throws before any arg is applied"
      (is (thrown-with-msg? Exception #"reserved" (ec/entity! w :* :newtag Position {:x 1})))
      (let [d (ec/anon! w)]
        (ec/delete! w d)
        (is (thrown-with-msg? Exception #"no such entity" (ec/entity! w d :newtag))))
      (is (zero? (ec/ent w :newtag))))
    (testing "the same component twice merges"
      (is (= {:x 1.0 :y 2.0}
             (ec/get-c w (ec/entity! w :g Position {:x 1} Position {:y 2}) Position))))
    (testing "nil args are skipped, so optional args can be written with when"
      (let [h (ec/entity! w :h :t1 nil :t2 (when false :boss) Health {:hp 5} nil)]
        (is (ec/has? w h :t1))
        (is (ec/has? w h :t2))
        (is (= {:hp 5} (ec/get-c w h Health))))
      (let [a (ec/anon! w nil :t3 nil)]
        (is (ec/has? w a :t3)))))
  (testing "a bad entity! in a system body changes nothing"
    (ec/with-world w
      (ec/entity! w :a Position {:x 0 :y 0})
      (ec/system! w :s [p Position e :ent]
        (ec/entity! w e :sys-tag Health {:hp 1} Stats {:c 300}))
      (is (thrown? Exception (ec/progress w 0.1)))
      (is (false? (ec/has? w :a :sys-tag)))
      (is (false? (ec/has? w :a Health)))))
  (testing "known limit: an observer that throws while args are applied"
    ;; checking can't foresee an observer's throw, so the args before it
    ;; stay applied
    (ec/with-world w
      (ec/observer! w :o [h Health :events #{:add}] (throw (ex-info "observer boom" {})))
      (is (thrown-with-msg? Exception #"observer boom" (ec/entity! w :k :early Health {:hp 1})))
      (is (ec/has? w :k :early)))))

(deftest set-c-field-errors
  ;; set-c! names the field it can't write, and writes nothing
  (ec/with-world w
    (ec/entity! w :e Health {:hp 1})
    (let [err (fn [c m]
                (try (ec/set-c! w :e c m) nil
                     (catch Exception e [(ex-message e) (ex-data e)])))]
      (is (= ["unknown field :mana for component Health"
              {:component "Health" :field :mana}]
             (err Health {:hp 2 :mana 3})))
      (is (= ["unknown field \"hp\" for component Health"
              {:component "Health" :field "hp"}]
             (err Health {"hp" 3})))
      (is (= ["field :v of component Shapes is an array or struct; set-c! writes scalar fields only"
              {:component "Shapes" :field :v :type [:array :float 3]}]
             (err Shapes {:n 1 :v [1.0 2.0 3.0]})))
      (is (= ["field :pos of component Shapes is an array or struct; set-c! writes scalar fields only"
              {:component "Shapes" :field :pos :type [:struct [[:x :float] [:y :float]]]}]
             (err Shapes {:n 1 :pos {:x 1.0 :y 2.0}}))))
    (is (= {:hp 1} (ec/get-c w :e Health)))
    (is (false? (ec/has? w :e Shapes)))))

(deftest bad-value-skips-on-add
  ;; values are checked before the component is added, so a bad value
  ;; throws its own error and OnAdd observers never run
  (ec/with-world w
    (let [adds (atom 0)]
      (ec/observer! w :o [h Health :events #{:add}]
        (swap! adds inc)
        (throw (ex-info "observer boom" {})))
      (ec/entity! w :x)
      (is (thrown-with-msg? Exception #"Number" (ec/set-c! w :x Health {:hp :lots})))
      (is (= 0 @adds))
      (is (false? (ec/has? w :x Health))))))

(deftest on-add-observer-writes-kept
  ;; set-c! adds the component (OnAdd) before writing its values, so a
  ;; field an OnAdd observer sets and set-c! doesn't is kept
  (ec/with-world w
    (ec/observer! w :o [s Stats e :ent :events #{:add}]
      (ec/set-c! w e Stats {:b 7}))
    (ec/entity! w :e)
    (ec/set-c! w :e Stats {:a 1})
    (is (= {:a 1 :b 7 :c 0 :d 0} (ec/get-c w :e Stats)))))

(deftest set-c-empty-map
  ;; an empty or nil map adds the component zeroed and still fires OnSet
  (ec/with-world w
    (let [sets (atom 0)]
      (ec/observer! w :o [h Health] (swap! sets inc))
      (ec/entity! w :e)
      (ec/set-c! w :e Health {})
      (is (= {:hp 0} (ec/get-c w :e Health)))
      (ec/set-c! w :e Health nil)
      (is (= {:hp 0} (ec/get-c w :e Health)))
      (is (= 2 @sets)))))

(deftest wide-values
  ;; the widest values of the 64-bit types survive the offset writes
  (ec/with-world w
    (let [vs {:u 18446744073709551615N :i -9223372036854775808 :d 1.5e300}
          e (ec/anon! w Wide vs)]
      (is (= vs (ec/get-c w e Wide)))
      (ec/set-c! w e Wide {:u 0 :i 9223372036854775807})
      (is (= {:u 0 :i 9223372036854775807 :d 1.5e300} (ec/get-c w e Wide))))))

(deftest reuse-registered-component
  ;; a component flecs or a C module already registered, named by path or
  ;; by C symbol, is reused rather than redefined, which aborted the process
  (ec/with-world w
    (let [id (ec/ent w "flecs.core.Component")]
      (is (pos? id))
      (is (= id (ec/comp-id w CoreComponent)))
      (is (= id (ec/comp-id w ByCSymbol)))
      (is (= {:size 8 :alignment 4} (ec/get-c w Position CoreComponent)))
      (is (= {:size 8 :alignment 4} (ec/get-c w Position ByCSymbol)))))
  (testing "a short name that matches a flecs.core component is a new component"
    (ec/with-world w
      (let [id (ec/comp-id w ShortPoly)]
        (is (not= (ec/ent w "flecs.core.Poly") id))
        (is (= "Poly" (ec/ent-name-of w id)))
        (ec/entity! w :e ShortPoly {:v 3})
        (is (= {:v 3} (ec/get-c w :e ShortPoly))))))
  (testing "a different layout is refused, naming both"
    (ec/with-world w
      ;; nothing cached: flecs' registration refuses it
      (is (thrown-with-msg? Exception #"size 8 and alignment 4.*size 4 and alignment 4"
                            (ec/comp-id w CoreTooSmall))))
    (ec/with-world w
      ;; a matching definition cached: comp-id's cache check refuses it
      (ec/comp-id w CoreComponent)
      (is (thrown-with-msg? Exception #"size 8 and alignment 4.*size 4 and alignment 4"
                            (ec/comp-id w CoreTooSmall)))
      (is (thrown-with-msg? Exception #"size 8 and alignment 4.*size 8 and alignment 8"
                            (ec/comp-id w CoreMisaligned))))))

(deftest component-name-in-use
  ;; flecs can't make an entity that's already in use as a tag or as a
  ;; pair's relationship into a component with a size. Removing the tag
  ;; doesn't help: flecs still counts it as in use.
  (doseq [[label use] [["a tag" (fn [w n] (ec/entity! w :t (keyword n)))]
                       ["a relationship" (fn [w n] (ec/entity! w :t [(keyword n) :x]))]
                       ["a removed tag" (fn [w n]
                                          (ec/entity! w :t (keyword n))
                                          (ec/remove! w :t (keyword n)))]]]
    (testing label
      (ec/with-world w
        (let [C (ec/component InUse [:v :int32])]
          (use w "InUse")
          (is (thrown-with-msg? Exception #"component InUse can't be registered: InUse is already in use as a tag or relationship"
                                (ec/entity! w :y C {:v 3})))
          (is (= (ec/ent w :InUse) (:entity (ex-data (try (ec/comp-id w C) (catch Exception e e))))))
          (testing "the world carries on"
            (ec/entity! w :z Position {:x 1 :y 2})
            (is (= {:x 1.0 :y 2.0} (ec/get-c w :z Position))))))))
  (testing "used only as a pair target, the name can be a component"
    (ec/with-world w
      (let [Liked (ec/component Liked [:v :int32])]
        (ec/entity! w :t [:likes :Liked])
        (ec/entity! w :y Liked {:v 1})
        (is (= {:v 1} (ec/get-c w :y Liked)))
        (is (ec/has? w :t [:likes Liked]))))))

(deftest redefined-layout-refused
  ;; at the REPL, re-evaluating a component's def with another field while a
  ;; world is alive gives a record whose layout no longer matches the one
  ;; flecs registered; using it would read and write past flecs' storage
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 2})
    (let [Wider (ec/component Position [:x :float] [:y :float] [:z :float])
          Same (ec/component Position [:x :float] [:y :float])
          msg #"component Position is already registered with size 8 and alignment 4, but this definition has size 12 and alignment 4"]
      (is (thrown-with-msg? Exception msg (ec/get-c w :a Wider)))
      ;; only :x, which is inside the registered 8 bytes, so the unfixed
      ;; code can't write past them
      (is (thrown-with-msg? Exception msg (ec/set-c! w :a Wider {:x 3})))
      (is (thrown-with-msg? Exception msg (ec/with-query w [p Wider] p)))
      (is (= {:x 1.0 :y 2.0} (ec/get-c w :a Position)))
      (testing "re-evaluating an identical def is fine"
        (is (= {:x 1.0 :y 2.0} (ec/get-c w :a Same)))
        (ec/set-c! w :a Same {:y 5})
        (is (= {:x 1.0 :y 5.0} (ec/get-c w :a Position)))))))

(deftest registered-component-edges
  (testing "a definition by C symbol is checked even when one by path is cached"
    (ec/with-world w
      (ec/comp-id w CoreComponent)
      (is (thrown-with-msg? Exception #"size 8 and alignment 4.*size 4 and alignment 4"
                            (ec/comp-id w BySymbolTooSmall)))))
  (testing "a plain entity with the component's name becomes the component"
    (ec/with-world w
      (let [Named (ec/component Named [:v :int32])
            e (ec/entity! w :Named)]
        (is (= e (ec/comp-id w Named)))
        (ec/entity! w :x Named {:v 2})
        (is (= {:v 2} (ec/get-c w :x Named))))))
  (testing "registration flecs refuses, with nothing to reuse"
    (ec/with-world w
      (let [Unregistered (ec/component Unregistered [:v :int32])]
        (with-redefs [f/component* (fn [& _] 0)]
          (is (= ["component registration failed" {:component "Unregistered"}]
                 (try (ec/comp-id w Unregistered) nil
                      (catch Exception e [(ex-message e) (ex-data e)]))))))))
  (testing "registered components are found again once ents' id cache is lost"
    ;; as reloading ents.core does (#11)
    (ec/with-world w
      (let [p (ec/comp-id w Position)
            c (ec/comp-id w CoreComponent)]
        (ec/entity! w :a Position {:x 1 :y 2})
        (swap! @#'ec/*comp-ids dissoc w)
        (is (= p (ec/comp-id w Position)))
        (is (= c (ec/comp-id w CoreComponent)))
        (is (= {:x 1.0 :y 2.0} (ec/get-c w :a Position)))))))

(deftest query-reused-component
  ;; queries, systems and observers read a reused component like any other
  (ec/with-world w
    (let [pos (ec/comp-id w Position)
          rows (ec/with-query w [c CoreComponent e :ent] [e c])
          ran (atom nil)
          seen (atom [])]
      (is (some #{[pos {:size 8 :alignment 4}]} rows))
      (is (< 1 (count rows)))
      (ec/system! w :s [c CoreComponent e :ent]
        (when (= e pos) (reset! ran c)))
      (ec/progress w 0.1)
      (is (= {:size 8 :alignment 4} @ran))
      (ec/observer! w :o [c CoreComponent e :ent :events #{:set}]
        (swap! seen conj [e c]))
      (let [h (ec/comp-id w Health)]
        (is (some #{[h {:size 4 :alignment 4}]} @seen))))))

(deftest flecs-components-not-written
  ;; flecs' own components can be read, but writing one would corrupt flecs
  (ec/with-world w
    (doseq [c [CoreComponent ByCSymbol]]
      (is (thrown-with-msg? Exception #"one of flecs' own"
                            (ec/set-c! w Position c {:size 99}))
          (:name c)))
    (is (= {:size 8 :alignment 4} (ec/get-c w Position CoreComponent)))
    (ec/entity! w :e)
    (is (thrown-with-msg? Exception #"one of flecs' own"
                          (ec/entity! w :e CoreComponent {:size 1})))
    (is (false? (ec/has? w :e CoreComponent)))
    (testing "components ents registers are written as usual"
      (ec/set-c! w :e Health {:hp 1})
      (is (= {:hp 1} (ec/get-c w :e Health))))))

(deftest flecs-entities-protected
  ;; adding or removing one of flecs' own data components, or deleting
  ;; anything flecs owns, corrupts flecs or aborts the process
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 2})
    (ec/entity! w :e)
    (doseq [[label f] [["add!" #(ec/add! w :e CoreComponent)]
                       ["add! as a pair's relationship" #(ec/add! w :e [CoreComponent :x])]
                       ["entity!" #(ec/entity! w :e CoreComponent)]
                       ["remove!" #(ec/remove! w Position CoreComponent)]
                       ["delete! a component" #(ec/delete! w CoreComponent)]
                       ["delete! by path" #(ec/delete! w "flecs.core.Component")]
                       ["delete! a tag" #(ec/delete! w :ChildOf)]]]
      (is (thrown-with-msg? Exception #"one of flecs' own" (f)) label))
    (is (false? (ec/has? w :e CoreComponent)))
    (is (= {:size 8 :alignment 4} (ec/get-c w Position CoreComponent)))
    (testing "flecs' tags and traits stay usable"
      (ec/entity! w :p :Prefab)
      (is (ec/has? w :p :Prefab))
      (ec/remove! w :p :Prefab)
      (is (false? (ec/has? w :p :Prefab)))
      (let [kid (ec/entity! w :kid [:ChildOf :a])]
        (is (= (ec/ent w :a) (ec/target w kid :ChildOf))))
      (let [Gravity (ec/component Gravity [:g :float])]
        (ec/add! w Gravity :Singleton)
        (is (ec/has? w Gravity :Singleton))))))

(deftest registered-layouts
  ;; flecs is given the size and alignment jolt.ffi computes for the layout
  (ec/with-world w
    (let [Mixed (ec/component Mixed [:a :uint8] [:b :double])]
      (doseq [[c align] [[Flag 1] [Health 4] [Stats 4] [Wide 8] [CTypes 8] [Mixed 8]]]
        (is (= {:size (:size c) :alignment align} (ec/get-c w c CoreComponent))
            (:name c))))))

(deftest bool-fields
  ;; a :bool stores by jolt truthiness: nil and false store false, anything
  ;; else (0 included) stores true
  (ec/with-world w
    (let [e (ec/anon! w Flag {:on true})
          stored (fn [v] (ec/set-c! w e Flag {:on v}) (:on (ec/get-c w e Flag)))]
      (is (= {:on true} (ec/get-c w e Flag)))
      (is (= [{:on true}] (ec/with-query w [f Flag] f)))
      (is (false? (stored false)))
      (is (true? (stored true)))
      (is (false? (stored nil)))
      (is (true? (stored 0)))
      (is (true? (stored "no"))))))

(deftest c-type-fields
  (ec/with-world w
    (let [vs {:c \A :s -300 :us 40000 :i -70000 :ui 4000000000 :l -5000000000
              :ul 5000000000 :z 7 :sz -7 :b 100 :p 12345}
          e (ec/anon! w CTypes vs)]
      (is (= vs (ec/get-c w e CTypes)))
      (is (= [vs] (ec/with-query w [c CTypes] c)))
      (testing "integer fields truncate doubles, as the sized integer types do"
        (ec/set-c! w e CTypes {:s -2.7 :us 2.7 :i -2.7 :ui 2.7 :l -2.7 :ul 2.7
                               :z 2.7 :sz -2.7 :b 2.7})
        (is (= (merge vs {:s -2 :us 2 :i -2 :ui 2 :l -2 :ul 2 :z 2 :sz -2 :b 2})
               (ec/get-c w e CTypes)))))))

(deftest inherited-fields
  (ec/with-world w
    (let [pos (ec/comp-id w Position)
          isa (f/lookup w "flecs.core.IsA")]
      (f/add-id! w pos (f/pair-id (f/lookup w "flecs.core.OnInstantiate")
                                  (f/lookup w "flecs.core.Inherit")))
      (ec/entity! w :base Position {:x 7 :y 7})
      (dotimes [_ 3] (ec/anon! w [isa :base]))
      (is (= (repeat 4 {:x 7.0 :y 7.0})
             (ec/with-query w [p Position] p))))))

(deftest optional-term-binds-nil
  ;; an optional term has no column on tables without the component; its
  ;; binding is nil there. Systems and observers decode through the same path.
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (ec/entity! w :b Position {:x 2 :y 2} Velocity {:x 5 :y 5})
    (is (= #{[1.0 nil] [2.0 {:x 5.0 :y 5.0}]}
           (set (ec/iter-rows w "Position, ?Velocity" [Position Velocity :ent]
                              (fn [[p v _]] [(:x p) v])))))))

(deftest non-component-spec-first
  ;; a non-component spec ahead of a component keeps its place, so the
  ;; component still decodes from its own field
  (ec/with-world w
    (let [a (ec/entity! w :a Position {:x 1 :y 2})]
      (is (= [[a {:x 1.0 :y 2.0}]]
             (ec/with-query w [e :ent p Position] [e p]))))))

(deftest kebab-case-component-names
  ;; flecs' query DSL can't parse a "-" in an identifier
  (ec/with-world w
    (let [a (ec/entity! w :a my-pos {:x 1})
          ran (atom [])
          seen (atom [])]
      (is (= [[a 1.0]] (ec/with-query w [p my-pos e :ent] [e (:x p)])))
      (is (= [{:e a :my-pos {:x 1.0}}] (ec/query w [my-pos])))
      (is (= "my-pos" (ec/ent-name-of w my-pos)))
      (is (ec/has? w a :my-pos))
      (ec/system! w :s [p my-pos] (swap! ran conj (:x p)))
      (ec/progress w 0.1)
      (is (= [1.0] @ran))
      (ec/observer! w :o [p my-pos] (swap! seen conj (:x p)))
      (ec/set-c! w :a my-pos {:x 2})
      (is (= [2.0] @seen)))))

(deftest generated-query-errors-name-components
  ;; a query built from specs names its components by id (#491), so a
  ;; rejected one carries the names those ids stand for
  (ec/with-world w
    (let [term (str "#" (ec/comp-id w my-pos))
          data (fn [thunk] (try (thunk) nil (catch Exception e (ex-data e))))
          it-new f/it-new]
      (with-redefs [f/it-new (fn [w _] (it-new w "NoSuchThing"))]
        (is (= {term "my-pos"}
               (:terms (data #(ec/with-query w [p my-pos] p)))
               (:terms (data #(ec/query w [my-pos]))))))
      (with-redefs [f/system* (fn [& _] 0)
                    f/observer* (fn [& _] 0)]
        (is (= {term "my-pos"}
               (:terms (data #(ec/system! w :s [p my-pos] nil)))
               (:terms (data #(ec/observer! w :o [p my-pos] nil)))))))))

(deftest hand-written-query-error-data
  ;; a hand-written expr is reported as written, with no :terms
  (ec/with-world w
    (is (= {:expr "NoSuchThing"}
           (try (ec/iter-rows w "NoSuchThing" [] identity) nil
                (catch Exception e (ex-data e)))))))

(deftest with-query-evaluates-specs-once
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (let [n (atom 0)]
      (ec/with-query w [p (do (swap! n inc) Position)] p)
      (is (= 1 @n)))))

(deftest deferred-add-in-system
  (ec/with-world w
    (ec/entity! w :a Position {:x 1 :y 1})
    (ec/system! w :s [p Position e :ent]
      (when-not (ec/has? w e Velocity)
        (ec/set-c! w e Velocity {:x 2})))
    (ec/progress w 0.1)
    (is (= {:x 2.0 :y 0.0} (ec/get-c w :a Velocity)))))

(deftest partial-sets-in-system-merge
  ;; in a system flecs queues the add of a new component, so a second
  ;; partial set-c! has to merge into the queued value, not start from zero
  (ec/with-world w
    (ec/entity! w :a Position {:x 0 :y 0})
    (ec/system! w :s [p Position e :ent]
      (ec/set-c! w e Position {:x 1})
      (ec/set-c! w e Position {:y 2})
      (ec/set-c! w e Velocity {:x 3})
      (ec/set-c! w e Velocity {:y 4}))
    (ec/progress w 0.1)
    (is (= {:x 1.0 :y 2.0} (ec/get-c w :a Position)))
    (is (= {:x 3.0 :y 4.0} (ec/get-c w :a Velocity)))))

(deftest on-set-fires-once-per-set
  (ec/with-world w
    (let [seen (atom [])]
      (ec/observer! w :o [h Health] (swap! seen conj (:hp h)))
      (ec/entity! w :m Health {:hp 1})
      (ec/set-c! w :m Health {:hp 2})
      (is (= [1 2] @seen)))))

;; --- round 3: resources and cleanup --------------------------------------------

(defn- callables [w] (get @@#'ec/*callables w))

(deftest callables-released
  (let [w (ec/make-world)]
    (ec/system! w :s [p Position] nil)
    (ec/system! w :s [p Position] nil)
    (ec/observer! w :o [h Health] nil)
    (is (= 2 (count (callables w))))
    (ec/destroy! w)
    (is (nil? (callables w)))))

(deftest count-q-bad-expr
  (ec/with-world w
    (is (thrown? Exception (ec/count-q w "NoSuchThing")))))

(deftest names-are-literal
  (ec/with-world w
    (let [a (ec/entity! w :a.b) b (ec/entity! w :a_b) c (ec/entity! w :a/b)]
      (is (= 3 (count (distinct [a b c]))))
      (is (= "a.b" (ec/ent-name-of w :a.b)))
      (is (= a (ec/ent w :a.b))))))

(deftest pair-removal
  (ec/with-world w
    (ec/entity! w :bob [:likes :alice])
    (is (ec/has? w :bob [:likes :alice]))
    (ec/remove! w :bob [:likes :alice])
    (is (false? (ec/has? w :bob [:likes :alice])))))

(deftest phases-order
  (ec/with-world w
    (ec/entity! w :a Position {:x 0 :y 0})
    (let [order (atom [])]
      (ec/system! w :late [p Position :phase :on-store] (swap! order conj :store))
      (ec/system! w :early [p Position :phase :on-load] (swap! order conj :load))
      (ec/system! w :mid [p Position] (swap! order conj :update))
      (ec/progress w 0.1)
      (is (= [:load :update :store] @order))
      (is (thrown? Exception (ec/system! w :bad [p Position :phase :nope] nil))))))

(deftest reserved-names-rejected
  (ec/with-world w
    (doseq [k [:* :_ :$ :this :#5 :#abc (keyword "")]]
      (is (thrown-with-msg? Exception #"reserved|empty" (ec/entity! w k)) (str k))
      (is (thrown-with-msg? Exception #"reserved|empty" (ec/ent w k)) (str k)))
    (is (thrown-with-msg? Exception #"empty" (ec/entity! w "")))
    (is (thrown-with-msg? Exception #"reserved" (ec/system! w :* [p Position] nil)))
    (testing "digit and punctuated names are ordinary entities"
      (let [a (ec/entity! w :5) b (ec/entity! w :123) c (ec/entity! w :a$b)]
        (is (= 3 (count (distinct [a b c]))))
        (is (not= 5 a))
        (is (= "5" (ec/ent-name-of w :5)))))
    (testing "flecs builtins resolve by name"
      (let [parent (ec/entity! w :parent)
            kid (ec/entity! w :kid [:ChildOf :parent])]
        (is (= parent (ec/target w kid :ChildOf)))
        ;; a child is named by its path from the root
        (is (= kid (ec/ent w "parent.kid")))))))
