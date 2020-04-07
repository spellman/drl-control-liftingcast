## Integration: Controlling LiftingCast from DRL for the 2018 USA Powerlifting Raw National Championship


#### This program is part of an integration to automate the role of scorekeeper in order to eliminate small but cumulative delays from a powerlifting meet.


* [Background](#background)
* [Problem](#problem)
* [Solution](#solution)
* [Considerations](#considerations)
* [Legacy](#legacy)


### Background
Digital Referee Lights (DRL) (https://www.squatdobbins.com/what-is-drl) is a hardware and software system of hand-held controllers, Raspberry Pi server, and display software by which referees in powerlifting and strongman control the event clocks and indicate successful or failed lifts. It is the International Powerlifting Federation standard for international competition and was also used at the 2020 Arnold Sports Festival Strongman competition.

LiftingCast (https://liftingcast.com) is the most widely used powerlifting scoring system and meet-direction software. While LiftingCast includes referee-controller views, they are less user-friendly than DRL and, in practice, referees are asked to use them on their own mobile devices, making device battery life a problem. LiftingCast is a React + Redux + PouchDB app with a Ruby on Rails backend and CouchDB database. At the time of this project there was a minimal Rails API and nearly all functionality was implemented in the web client, whose UI was driven by the PouchDB changes feed.


### Problem
When the LiftingCast referee views were not used (see above), the program relied on a human scorekeeper to manually input the referees' decisions for whether each lift was successful or failed and the cause of failure.

Additionally, the official acting as scorekeeper concurrently fulfills another role: after each lifter's attempt, the lifter submits their next-attempt weight to the official, who enters it into the scoring program.

The meet advances from one lifter to the next only when the scorekeeper advances the scoring program. Any distraction therefore results in a delay of the meet. With 9 attempts for each of ~1,500 lifters over 4 days, small delays become significant. The meet director requested a way to speed up the meet.


### Solution
We can automate input of the referees' decisions to LiftingCast. The referees push the buttons on their respective DRL controllers to indicate their decisions. DRL then displays appropriate graphics on a monitor for the crowd and the scorekeeper to see.  
Instead of asking the scorekeeper to watch the DRL display and input the decisions into the scoring program, Scott Dobbins (creator of DRL) and I sent the decisions directly from DRL to LiftingCast by updating the appropriate CouchDB documents.

Since both DRL and LiftingCast can display the clock and the referees' decisions, we decided to have them both display this information, synced up as well as they could be. (DRL has the information immediately; LiftingCast is delayed (usually less than a second) while our integration processes the information and writes to CouchDB, CouchDB replicates to a LiftingCast client's PouchDB, the PouchDB changes feed is processed, and the UI is updated.)

Scott modified DRL to communicate each set of referee decisions to the Clojure program in this project.

_This program reads from and writes directly to the LiftingCast database in order to make the complete series of updates that LiftingCast's own web client would make._ That is, we
* Start, stop, or reset the LiftingCast clock based on the chief referee's inputs to DRL.
* Reset the LiftingCast clock for the next lifter after each attempt.
* Turn on the indicator lights to express the referees' decisions.
* Turn off the lights after a delay.
* Determine the next lifter.
* Update the current lifter to be the next lifter.


### Considerations
There was significant risk in using this program because it ran live at the 2018 USA Powerlifting Raw National Championship (https://www.youtube.com/watch?v=4SQym_2HH8s&feature=youtu.be, https://usapl.liftingdatabase.com/competitions-view?id=2187). Scott worked as scorekeeper in order to oversee the operation of the program and intervene if it crashed. If the program wrote incorrect or incomplete data to the LiftingCast database, however, then all LiftingCast clients could have become unresponsive, both at the event and for remote users. While a lower-profile meet would have been a great testing ground, we built this integration with a small amount of lead time in response to demand from the Raw Nationals meet director.

I wrote a previous integration (as a Python module used by DRL) for 2018 USA Powerlifting Collegiate Nationals that pulled LiftingCast data into DRL in order to use the DRL display as a custom score display. It used a replicated CouchDB instance running on the same Raspberry Pi as DRL. CouchDB kept crashing and being unable to restart, requiring multiple reinstallations on the various Pis during the competition. Therefore, in this project I interacted with the remote LiftingCast CouchDB instead of using bi-directional replication with a local CouchDB.

This project went from idea to running at a live event in about two weeks. While DRL is written in Python, I opted to use a separate Clojure process for this part of the integration because I know Clojure well and, since data corruption was really not an option, I wanted to develop and test with the runtime checking that Clojure spec (https://clojure.org/about/spec) provides. I specced all of the incoming and outgoing data and much of the intermediate data.

We ended up using HTTP to send data from DRL to the Clojure program:
* I originally exposed a socket REPL and had DRL send EDN to it. This worked well on a laptop but crashed intermittently on the Raspberry Pi.
* We had DRL overwrite a file, which Clojure watched and read on modification: https://github.com/spellman/drl-control-liftingcast/tree/with-file. Writing the file from a Clojure REPL in development yielded the expected coordination but when we ran this on the Pi, something caused multiple reads on the Clojure side for every write from the DRL side. (We suspected something in the interaction of the Python writer and the Java file watcher or something in the Pi's / Raspbian's file system cache but we didn't have time to investigate.)
* We tried using Redis as a queue from DRL to Clojure: https://github.com/spellman/drl-control-liftingcast/tree/with-redis. This required forking the Python Hotqueue Redis client (https://github.com/spellman/hotqueue/commit/41465654011a50fd9799510940921fa3e52fae9d) in order to make its push direction work with the Clojure client's pull direction (https://github.com/threatgrid/redismq). This again worked in development but we seem to have run into a firewall issue on the Pi that prevented communication with Redis. With limited time, we scrapped this approach as well.
* I embedded a Jetty server into the Clojure program: https://github.com/spellman/drl-control-liftingcast/tree/master (master). It ran on a background thread and validated inputs against the spec and put valid inputs on a buffered core.async (https://github.com/clojure/core.async) channel. In a go-loop, I took requests from that channel and processed them, writing modified documents to the LiftingCast CouchDB at the proper times to cause the desired updates to the LiftingCast website UI.


### Legacy
Scott and I proved the auto-scorekeeper concept with this program and its (proprietary) complementary addition to DRL. Scott and the author of LiftingCast subsequently coordinated to add an officially supported version of this concept via a Flask server in DRL and long-polling from LiftingCast. This program was thereby made obsolete.
