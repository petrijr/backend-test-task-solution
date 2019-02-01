# Intertrust Backend Engineer Test Task

The goal of the test task is to create a reactive system, using Scala and Akka, optionally also kafka and Akka 
Persistence to alert wind park operators of any problems.

### Input Data

There are two input files, stored under `src/main/resources`:

##### movements.csv

This file contains information about technician movements between ships and offshore turbines. Each movement is either 
an entrance onto a ship or a turbine or an exit from a ship or a turbine. First column has the date the movement took 
place, the second column is the object the movement applies to, third is identifier of technician making the movement 
and the final column is the movement type.

##### turbines.csv

The file contains information about the state of wind turbines. First column is date/time of the status update, second 
is turbine id, third is turbine power generation in megawatts and the final column is either `Working` or `Broken`, 
signifying the state of the turbine.

### Alerts

An `TurbineAlert` should be sent to the `AlertsActor` if any of following problems occur:
- turbine stops working
- turbine has been in a `Broken` state for more than 4 hours and no technician has entered the turbine yet to fix it
- technician exits a turbine without having repaired the turbine; the condition here is that if the turbine continues 
in a `Broken` state for more than 3 minutes after a technician has exited the turbine

An `MovementAlert` should be sent to the `AlertsActor` if any of following problems occur:
- technician moves onto a turbine without having exited a ship
- technician exits a turbine without having entered the turbine
- other similar logical inconsistencies

### Skeleton Implementation

We have implemented parsers for the two input files for you - `MovementEventParser` and `TurbineEventsParser`, and a 
very simple `AlertsActor` that logs the alerts it receives.
There is also a half-baked `Simulator` object that you should finish.

### Final Remarks

- The timestamps in the two files cover a range of about 7 days worth of timestamps. The program should have a 
configurable speedup to simulate time passing faster and should work correctly with any reasonable speedup factor.
- The program must stream input events and not read all of them into memory at once.
- Extra credit if the software is resilient to crashes - meaning that if it is interrupted at any point during 
processing, it will continue from where it left off, reprocessing as little messages as possible, but it must not miss 
or duplicate any notifications due to crash/restart.
- You are also free to use any databases or libraries or any other components, as long as they are freely available for 
us to install for testing.
- **Please make sure your application works, is tested and your code is clean.**
