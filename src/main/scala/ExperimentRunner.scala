/**
  * Copyright (c) 2013, Regents of the University of California
  * All rights reserved.
  *
  * Redistribution and use in source and binary forms, with or without
  * modification, are permitted provided that the following conditions are met:
  *
  * Redistributions of source code must retain the above copyright notice, this
  * list of conditions and the following disclaimer.  Redistributions in binary
  * form must reproduce the above copyright notice, this list of conditions and the
  * following disclaimer in the documentation and/or other materials provided with
  * the distribution.  Neither the name of the University of California, Berkeley
  * nor the names of its contributors may be used to endorse or promote products
  * derived from this software without specific prior written permission.  THIS
  * SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
  * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
  * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
  * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
  * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
  * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
  * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  */

package ClusterSchedulingSimulation

import efficiency.ordering_cellstate_resources_policies.CellStateResourcesSorter
import efficiency.pick_cellstate_resources.CellStateResourcesPicker
import efficiency.power_off_policies.PowerOffPolicy
import efficiency.power_on_policies.PowerOnPolicy

import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer
import ClusterSimulationProtos._
import java.io._

import stackelberg.{NoStackelberg, StackelbergAgent}

/**
  * An experiment represents a series of runs of a simulator,
  * across ranges of paramters. Exactly one of {L, C, Lambda}
  * can be swept over per experiment, i.e. only one of
  * avgJobInterarrivalTimeRange, constantThinkTimeRange, and
  * perTaskThinkTimeRange can have size greater than one in a
  * single Experiment instance.
  */
class Experiment(
                  name: String,
                  // Workloads setup.
                  workloadToSweepOver: String,
                  avgJobInterarrivalTimeRange: Option[Seq[Double]] = None,
                  workloadDescs: Seq[WorkloadDesc],
                  // Schedulers setup.
                  schedulerWorkloadsToSweepOver: Map[String, Seq[String]],
                  constantThinkTimeRange: Seq[Double],
                  perTaskThinkTimeRange: Seq[Double],
                  blackListPercentRange: Seq[Double],
                  // Workload -> scheduler mapping setup.
                  schedulerWorkloadMap: Map[String, Seq[String]],
                  // Simulator setup.
                  simulatorDesc: ClusterSimulatorDesc,
                  logging: Boolean = true,
                  outputDirectory: String = "experiment_results",
                  // Map from workloadName -> max % of cellState this prefill workload
                  // can account for. Any prefill workload generator with workloadName
                  // that is not contained in any of these maps will have no prefill
                  // generated for this experiment, and any with name that is in multiple
                  // of these maps will use the first limit that actually kicks in.
                  prefillCpuLimits: List[Map[String, Double]] = List(Map()),
                  prefillMemLimits: Map[String, Double] = Map(),
                  // Default simulations to 10 minute timeout.
                  simulationTimeout: Double = 60.0*10000000000.0,
                  cellStateResourcesSorterList: Seq[CellStateResourcesSorter],
                  cellStateResourcesPickerList: Seq[CellStateResourcesPicker],
                  powerOnPolicies: Seq[PowerOnPolicy],
                  powerOffPolicies: Seq[PowerOffPolicy],
                  level1SecurityTimes: Seq[Double] = (0.0 :: Nil),
                  level2SecurityTimes: Seq[Double] = (0.0 :: Nil),
                  level3SecurityTimes: Seq[Double] = (0.0 :: Nil),
                  stackelbergStrategies: Seq[StackelbergAgent] = (NoStackelberg :: Nil)) extends Runnable {
  prefillCpuLimits.foreach(p => p.values.foreach(l => assert(l >= 0.0 && l <= 1.0)))
  prefillMemLimits.values.foreach(l => assert(l >= 0.0 && l <= 1.0))

  var parametersSweepingOver = 0
  avgJobInterarrivalTimeRange.foreach{opt: Seq[Double] => {
    if (opt.length > 1) {
      parametersSweepingOver += 1
    }
  }}
  if (constantThinkTimeRange.length > 1) {parametersSweepingOver += 1}
  if (perTaskThinkTimeRange.length > 1) {parametersSweepingOver += 1}
  // assert(parametersSweepingOver <= 1)

  override
  def toString = name

  def run() {
    var currentRun = 0
    // Create the output directory if it doesn't exist.
    (new File(outputDirectory)).mkdirs()
    val output =
      new java.io.FileOutputStream("%s/%s-%.0f.protobuf"
        .format(outputDirectory,
          name,
          simulatorDesc.runTime))

    val experimentResultSet = ExperimentResultSet.newBuilder()

    // Parameter sweep over workloadDescs
    workloadDescs.foreach(workloadDesc => {
      println("\nSet workloadDesc = %s %s"
        .format(workloadDesc.cell, workloadDesc.assignmentPolicy))

      // Save Experiment level stats into protobuf results.
      val experimentEnv = ExperimentResultSet.ExperimentEnv.newBuilder()
      experimentEnv.setCellName(workloadDesc.cell)
      experimentEnv.setWorkloadSplitType(workloadDesc.assignmentPolicy)
      experimentEnv.setIsPrefilled(
        workloadDesc.prefillWorkloadGenerators.length > 0)
      experimentEnv.setRunTime(simulatorDesc.runTime)
      experimentEnv.setNumMachines(workloadDesc.cellStateDesc.numMachines)
      experimentEnv.setHeterogeneousMachines(workloadDesc.cellStateDesc.machinesHet)

      // Parameter sweep over lambda.
      // If we have a range for lambda, loop over it, else
      // we just loop over a list holding a single element: None
      val jobInterarrivalRange = avgJobInterarrivalTimeRange match {
        case Some(paramsRange) => paramsRange.map(Some(_))
        case None => List(None)
      }

      println("\nSet up avgJobInterarrivalTimeRange: %s\n"
        .format(jobInterarrivalRange))
      jobInterarrivalRange.foreach(avgJobInterarrivalTime => {
        if (avgJobInterarrivalTime.isEmpty) {
          println("Since we're not in a labmda sweep, not overwriting lambda.")
        } else {
          println("Curr avgJobInterarrivalTime: %s\n"
            .format(avgJobInterarrivalTime))
        }

        // Set up a list of workloads
        var commonWorkloadSet = ListBuffer[Workload]()
        var newAvgJobInterarrivalTime: Option[Double] = None
        workloadDesc.workloadGenerators.foreach(workloadGenerator => {
          if (workloadToSweepOver.equals(
            workloadGenerator.workloadName)) {
            // Only update the workload interarrival time if this is the
            // workload we are supposed to sweep over. If this is not a
            // lambda parameter sweep then updatedAvgJobInterarrivalTime
            // will remain None after this line is executed.
            newAvgJobInterarrivalTime = avgJobInterarrivalTime
          }
          println("Generating new Workload %s for window %f seconds long."
            .format(workloadGenerator.workloadName, simulatorDesc.runTime))
          val newWorkload =
            workloadGenerator
              .newWorkload(timeWindow = simulatorDesc.runTime,
                updatedAvgJobInterarrivalTime = newAvgJobInterarrivalTime)
          commonWorkloadSet.append(newWorkload)
        })

        // Parameter sweep over L.
        perTaskThinkTimeRange.foreach(perTaskThinkTime => {
          println("\nSet perTaskThinkTime = %f".format(perTaskThinkTime))

          // Parameter sweep over C.
          constantThinkTimeRange.foreach(constantThinkTime => {
            println("\nSet constantThinkTime = %f".format(constantThinkTime))

            // Parameter sweep over BlackListPercent (of cellstate).
            blackListPercentRange.foreach(blackListPercent => {
              println("\nSet blackListPercent = %f".format(blackListPercent))

              cellStateResourcesPickerList.foreach(cellStateResourcesPicker => {
                println("\nSet picking strategy "+cellStateResourcesPicker.name)

                cellStateResourcesSorterList.foreach(cellStateResourcesSorter => {
                  println ("\nSet sorting strategy "+cellStateResourcesSorter.name)

                  powerOnPolicies.foreach(powerOnPolicy => {
                    println("\nSet power on policy "+powerOnPolicy.name)

                    powerOffPolicies.foreach(powerOffPolicy => {
                        println ("\nSet power off strategy "+powerOffPolicy.name)

                      level1SecurityTimes.foreach(level1SecurityTime => {
                        println ("\nSet security level 1 time: "+level1SecurityTime)

                        level2SecurityTimes.foreach(level2SecurityTime => {
                          println ("\nSet security level 2 time: "+level2SecurityTime)

                          level3SecurityTimes.foreach(level3SecurityTime => {
                            println("\nSet security level 3 time: " + level3SecurityTime)
                            stackelbergStrategies.foreach(stackelbergStrategy => {
                              println("\nSet stackelber strategy: " + stackelbergStrategy.name)
                              prefillCpuLimits.foreach(prefillLimits => {
                                println("\nSet prefill: " + prefillLimits.toString())
                                // Generate preFill workloads. The simulator doesn't modify
                                // these workloads like it does the workloads that are played during
                                // the simulation.
                                var prefillWorkloads = List[Workload]()
                                workloadDesc.prefillWorkloadGenerators
                                  .filter(wlGen => {
                                    prefillLimits.contains(wlGen.workloadName) ||
                                      prefillMemLimits.contains(wlGen.workloadName)
                                  }).foreach(wlGen => {
                                  val cpusMaxOpt = prefillLimits.get(wlGen.workloadName).map(i => {
                                    i * workloadDesc.cellStateDesc.numMachines *
                                      workloadDesc.cellStateDesc.cpusPerMachine
                                  })

                                  val memMaxOpt = prefillMemLimits.get(wlGen.workloadName).map(i => {
                                    i * workloadDesc.cellStateDesc.numMachines *
                                      workloadDesc.cellStateDesc.memPerMachine
                                  })
                                  println(("Creating a new prefill workload from " +
                                    "%s with maxCPU %s and maxMem %s")
                                    .format(wlGen.workloadName, cpusMaxOpt, memMaxOpt))
                                  val newWorkload = wlGen.newWorkload(simulatorDesc.runTime,
                                    maxCpus = cpusMaxOpt,
                                    maxMem = memMaxOpt)
                                  for(job <- newWorkload.getJobs) {
                                    assert(job.submitted == 0.0)
                                  }
                                  prefillWorkloads ::= newWorkload
                                })
                                /*var numBatchTasks = 0
                      var numServiceTasks = 0
                      var numCpuBatch = 0.0
                      var numCpuService = 0.0
                      var numMemBatch = 0.0
                      var numMemService = 0.0
                      val pw = new PrintWriter(new File("/Users/dfernandez/IdeaProjects/efficiency-cluster-scheduler-simulator/experiment_results/datosjobs.csv"));
                      val sb = new StringBuilder();
                      sb.append("submitted,");
                      sb.append("numtasks,");
                      sb.append("taskduration,");
                      sb.append("jobcpu,");
                      sb.append("jobram,");
                      sb.append("tipo,");
                      sb.append('\n');*/
                                // Make a copy of the workloads that this run of the simulator
                                // will modify by using them to track statistics.
                                val workloads = ListBuffer[Workload]()
                                commonWorkloadSet.foreach(workload => {
                                  /*workload.getJobs.foreach(job => {
                          sb.append(f"${job.submitted}%1.2f")
                          sb.append(",")
                          sb.append(job.numTasks)
                          sb.append(",")
                          sb.append(f"${job.taskDuration}%1.2f")
                          sb.append(",")
                          sb.append(f"${job.numTasks * job.cpusPerTask}%1.2f")
                          sb.append(",")
                          sb.append(f"${job.numTasks * job.memPerTask}%1.2f")
                          sb.append(",")
                          sb.append(workload.name)
                          sb.append('\n');
                          if (workload.name == "Batch") {
                            numBatchTasks += job.numTasks
                            numCpuBatch += job.numTasks * job.cpusPerTask
                            numMemBatch += job.numTasks * job.memPerTask
                          }
                          else if (workload.name == "Service") {
                            numServiceTasks += job.numTasks
                            numCpuService += job.numTasks * job.cpusPerTask
                            numMemService += job.numTasks * job.memPerTask
                          }
                        })*/

                                  workloads.append(workload.copy)
                                })
                                /*pw.write(sb.toString());
                      pw.close();*/
                                // Setup and and run the simulator.
                                val simulator =
                                simulatorDesc.newSimulator(constantThinkTime,
                                  perTaskThinkTime,
                                  blackListPercent,
                                  schedulerWorkloadsToSweepOver,
                                  schedulerWorkloadMap,
                                  workloadDesc.cellStateDesc,
                                  workloads,
                                  prefillWorkloads,
                                  logging,
                                  cellStateResourcesSorter,
                                  cellStateResourcesPicker,
                                  powerOnPolicy,
                                  powerOffPolicy,
                                  level1SecurityTime,
                                  level2SecurityTime,
                                  level3SecurityTime,
                                  stackelbergStrategy.duplicate())

                                println("Running simulation with run().")
                                val success: Boolean = simulator.run(Some(simulatorDesc.runTime),
                                  Some(simulationTimeout))
                                if (success) {
                                  currentRun += 1
                                  println("Terminado el experimento " + currentRun)
                                  // Simulation did not time out, so record stats.
                                  /**
                                    * Capture statistics into a protocolbuffer.
                                    */
                                  val experimentResult =
                                    ExperimentResultSet.ExperimentEnv.ExperimentResult.newBuilder()
                                  experimentResult.setCellStateAvgCpuUtilization(
                                    simulator.avgCpuUtilization / simulator.cellState.totalCpus)
                                  experimentResult.setCellStateAvgMemUtilization(
                                    simulator.avgMemUtilization / simulator.cellState.totalMem)
                                  experimentResult.setStackelberg(stackelbergStrategy.name)

                                  experimentResult.setCellStateAvgCpuLocked(
                                    simulator.avgCpuLocked / simulator.cellState.totalCpus)
                                  experimentResult.setCellStateAvgMemLocked(
                                    simulator.avgMemLocked / simulator.cellState.totalMem)
                                  //Measurement stats
                                  for (i <- 0 to simulator.measuredMachinesOn.length - 1) {
                                    val measurement = ExperimentResultSet.ExperimentEnv.ExperimentResult.Measurement.newBuilder()
                                    measurement.setCpuUtilization(simulator.measuredCpuUtilization(i))
                                    measurement.setCpuTotallyIdle(simulator.measuredCpuTotallyIdle(i))
                                    measurement.setCpuPartiallyIdle(simulator.measuredCpuPartiallyIdle(i))
                                    measurement.setMpuLocked(simulator.measuredCpuLocked(i))
                                    measurement.setMemUtilization(simulator.measuredMemUtilization(i))
                                    measurement.setMemTotallyIdle(simulator.measuredMemTotallyIdle(i))
                                    measurement.setMemPartiallyIdle(simulator.measuredMemPartiallyIdle(i))
                                    measurement.setMemLocked(simulator.measuredMemLocked(i))
                                    measurement.setMachinesOff(simulator.measuredMachinesOff(i))
                                    measurement.setMachinesOn(simulator.measuredMachinesOn(i))
                                    measurement.setMachinesTurningOff(simulator.measuredMachinesTurningOff(i))
                                    measurement.setMachinesTurningOn(simulator.measuredMachinesTurningOn(i))
                                    experimentResult.addMeasurements(measurement)
                                  }


                                  //EfficiencyStats

                                  val efficiencyStats = ExperimentResultSet.ExperimentEnv.ExperimentResult.EfficiencyStats.newBuilder()
                                  efficiencyStats.setAvgNumberMachinesOff(simulator.sumMachinesOff.toDouble / simulator.numMonitoringMeasurements.toDouble)
                                  efficiencyStats.setAvgNumberMachinesOn(simulator.sumMachinesOn.toDouble / simulator.numMonitoringMeasurements.toDouble)
                                  efficiencyStats.setAvgNumberMachinesTurningOff(simulator.sumMachinesTurningOff.toDouble / simulator.numMonitoringMeasurements.toDouble)
                                  efficiencyStats.setAvgNumberMachinesTurningOn(simulator.sumMachinesTurningOn.toDouble / simulator.numMonitoringMeasurements.toDouble)
                                  efficiencyStats.setAvgShuttingsPerMachine(simulator.shuttingDownsPerMachineAvg)
                                  efficiencyStats.setAvgTimeShuttedDownPerCycle(simulator.timeShuttedDownPerCycleAvg)
                                  efficiencyStats.setAvgTimeShuttedDownPerMachine(simulator.timeShuttedDownPerMachineAvg)
                                  efficiencyStats.setCurrentEnergyConsumed(simulator.totalCurrentEnergyConsumed)
                                  efficiencyStats.setKwhSavedPerShutting(simulator.kwhSavedPerShutting)
                                  efficiencyStats.setMaxShuttingsPerMachine(simulator.maxShuttingDowns)
                                  efficiencyStats.setMaxTimeShuttedDownPerCycle(simulator.maxTimeShuttedDownPerCycle)
                                  efficiencyStats.setMaxTimeShuttedDownPerMachine(simulator.maxTimeShuttedDownPerMachine)
                                  efficiencyStats.setMinShuttingsPerMachine(simulator.minShuttingDowns)
                                  efficiencyStats.setMinTimeShuttedDownPerCycle(simulator.minTimeShuttedDownPerCycle)
                                  efficiencyStats.setMinTimeShuttedDownPerMachine(simulator.minTimeShuttedDownPerMachine)
                                  val pOff = ExperimentResultSet.ExperimentEnv.ExperimentResult.EfficiencyStats.PowerOffPolicy.newBuilder()
                                  pOff.setName(powerOffPolicy.name)
                                  efficiencyStats.setPowerOffPolicy(pOff)
                                  val pOn = ExperimentResultSet.ExperimentEnv.ExperimentResult.EfficiencyStats.PowerOnPolicy.newBuilder()
                                  pOn.setName(powerOnPolicy.name)
                                  efficiencyStats.setPowerOnPolicy(pOn)
                                  efficiencyStats.setShuttingsPerMachine90Percentile(simulator.shuttingDownsPerMachinePercentile(0.9))
                                  efficiencyStats.setShuttingsPerMachine99Percentile(simulator.shuttingDownsPerMachinePercentile(0.99))
                                  efficiencyStats.setTimeShuttedDownPerCycle90Percentile(simulator.timeShuttedDownPerCyclePercentile(0.9))
                                  efficiencyStats.setTimeShuttedDownPerCycle99Percentile(simulator.timeShuttedDownPerCyclePercentile(0.99))
                                  efficiencyStats.setTimeShuttedDownPerMachine90Percentile(simulator.timeShuttedDownPerMachinePercentile(0.9))
                                  efficiencyStats.setTimeShuttedDownPerMachine99Percentile(simulator.timeShuttedDownPerMachinePercentile(0.99))
                                  efficiencyStats.setTotalEnergyConsumed(simulator.totalEnergyConsumed)
                                  efficiencyStats.setTotalEnergySaved(simulator.totalEnergySaved)
                                  efficiencyStats.setTotalPowerOffNumber(simulator.totalPowerOffNumber)
                                  efficiencyStats.setPickingPolicy(simulator.picker.name)
                                  experimentResult.setEfficiencyStats(efficiencyStats)
                                  // Save repeated stats about workloads.
                                  workloads.foreach(workload => {

                                    val workloadStats = ExperimentResultSet.
                                      ExperimentEnv.
                                      ExperimentResult.
                                      WorkloadStats.newBuilder()
                                    workloadStats.setWorkloadName(workload.name)
                                    workloadStats.setNumJobs(workload.numJobs)
                                    workloadStats.setNumJobsScheduled(
                                      workload.getJobs.filter(_.numSchedulingAttempts > 0).length)
                                    workloadStats.setJobThinkTimes90Percentile(
                                      workload.jobUsefulThinkTimesPercentile(0.9))
                                    workloadStats.setAvgJobQueueTimesTillFirstScheduled(
                                      workload.avgJobQueueTimeTillFirstScheduled)
                                    workloadStats.setAvgJobQueueTimesTillFullyScheduled(
                                      workload.avgJobQueueTimeTillFullyScheduled)
                                    workloadStats.setJobQueueTimeTillFirstScheduled90Percentile(
                                      workload.jobQueueTimeTillFirstScheduledPercentile(0.9))
                                    workloadStats.setJobQueueTimeTillFullyScheduled90Percentile(
                                      workload.jobQueueTimeTillFullyScheduledPercentile(0.9))
                                    workloadStats.setNumSchedulingAttempts90Percentile(
                                      workload.numSchedulingAttemptsPercentile(0.9))
                                    workloadStats.setNumSchedulingAttempts99Percentile(
                                      workload.numSchedulingAttemptsPercentile(0.99))
                                    workloadStats.setNumTaskSchedulingAttempts90Percentile(
                                      workload.numTaskSchedulingAttemptsPercentile(0.9))
                                    workloadStats.setNumTaskSchedulingAttempts99Percentile(
                                      workload.numTaskSchedulingAttemptsPercentile(0.99))
                                    //Makespan
                                    workloadStats.setAvgMakespan(workload.avgJobMakespan)
                                    workloadStats.setMakespan90Percentile(workload.jobMakespanPercentile(0.9))
                                    //Nuevo
                                    val numJobs0 = workload.getJobs.filter(_.security == 0).length
                                    val numJobs1 = workload.getJobs.filter(_.security == 1).length
                                    val numJobs2 = workload.getJobs.filter(_.security == 2).length
                                    val numJobs3 = workload.getJobs.filter(_.security == 3).length
                                    val numJobs4 = workload.getJobs.filter(_.security == 4).length
                                    val numJobs5 = workload.getJobs.filter(_.security == 5).length

                                    workloadStats.setNumJobsSecurity0(numJobs0)
                                    workloadStats.setNumJobsSecurity1(numJobs1)
                                    workloadStats.setNumJobsSecurity2(numJobs2)
                                    workloadStats.setNumJobsSecurity3(numJobs3)
                                    workloadStats.setNumJobsSecurity4(numJobs4)
                                    workloadStats.setNumJobsSecurity5(numJobs5)
                                    println("trabajos con makespanlog > 0 : " + workload.getJobs.filter(job => job.makespanLogArray.size > 0))
                                    if (workload.name == "Batch") {
                                      val jobsNotNull = workload.getJobs.filter(job => job.makespanLogArray.size > 0)

                                      val makespanLogs = new ListBuffer[ListBuffer[Double]]()
                                      jobsNotNull.map(job => job.makespanLogArray).foreach(makespanLogs ++= _)


                                      for (i <- 0 until 500) {
                                        val epoch = ExperimentResultSet.
                                          ExperimentEnv.
                                          ExperimentResult.
                                          WorkloadStats
                                          .Epoch.
                                          newBuilder()
                                        epoch.setNumberEpoch(i)
                                        val fitness = makespanLogs.filter(_.length > i)
                                        val fitnessAvg = fitness.map(x => x(i)).sum / fitness.length
                                        epoch.setFintessAvg(fitnessAvg)
                                        workloadStats.addEpochs(epoch)
                                      }

                                      val makespansEpoch0 = makespanLogs.filter(_.length > 0)
                                      val makespansEpoch0Avg = makespansEpoch0.map(x => x(0)).sum / makespansEpoch0.length

                                      val makespansEpoch100 = makespanLogs.filter(_.length > 99)
                                      val makespansEpoch100Avg = makespansEpoch100.map(x => x(99)).sum / makespansEpoch100.length

                                      val makespansEpoch500 = makespanLogs.filter(_.length > 499)
                                      val makespansEpoch500Avg = makespansEpoch500.map(x => x(499)).sum / makespansEpoch500.length

                                      val makespansEpoch1000 = makespanLogs.filter(_.length > 999)
                                      val makespansEpoch1000Avg = makespansEpoch1000.map(x => x(999)).sum / makespansEpoch1000.length

                                      val makespansEpoch2000 = makespanLogs.filter(_.length > 1999)
                                      val makespansEpoch2000Avg = makespansEpoch2000.map(x => x(1999)).sum / makespansEpoch2000.length


                                      if (makespansEpoch0.length > 0) workloadStats.setAvgMakespanEpoch0(makespansEpoch0Avg) else workloadStats.setAvgMakespanEpoch0(0.0)
                                      if (makespansEpoch100.length > 0) workloadStats.setAvgMakespanEpoch100(makespansEpoch100Avg) else workloadStats.setAvgMakespanEpoch100(0.0)
                                      if (makespansEpoch500.length > 0) workloadStats.setAvgMakespanEpoch500(makespansEpoch500Avg) else workloadStats.setAvgMakespanEpoch500(0.0)
                                      if (makespansEpoch1000.length > 0) workloadStats.setAvgMakespanEpoch1000(makespansEpoch1000Avg) else workloadStats.setAvgMakespanEpoch1000(0.0)
                                      if (makespansEpoch2000.length > 0) workloadStats.setAvgMakespanEpoch2000(makespansEpoch2000Avg) else workloadStats.setAvgMakespanEpoch2000(0.0)
                                    }
                                    experimentResult.addWorkloadStats(workloadStats)
                                  })
                                  // Record workload specific details about the parameter sweeps.
                                  experimentResult.setSweepWorkload(workloadToSweepOver)
                                  experimentResult.setAvgJobInterarrivalTime(
                                    avgJobInterarrivalTime.getOrElse(
                                      workloads.filter(_.name == workloadToSweepOver)
                                        .head.avgJobInterarrivalTime))

                                  // Save repeated stats about schedulers.
                                  simulator.schedulers.values.foreach(scheduler => {
                                    val schedulerStats =
                                      ExperimentResultSet.
                                        ExperimentEnv.
                                        ExperimentResult.
                                        SchedulerStats.newBuilder()
                                    schedulerStats.setSchedulerName(scheduler.name)
                                    schedulerStats.setUsefulBusyTime(
                                      scheduler.totalUsefulTimeScheduling)
                                    schedulerStats.setWastedBusyTime(
                                      scheduler.totalWastedTimeScheduling)
                                    // Per scheduler metrics bucketed by day.
                                    // Use floor since days are zero-indexed. For example, if the
                                    // simulator only runs for 1/2 day, we should only have one
                                    // bucket (day 0), so our range should be 0 to 0. In this example
                                    // we would get floor(runTime / 86400) = floor(0.5) = 0.
                                    val daysRan = math.floor(simulatorDesc.runTime / 86400.0).toInt
                                    println("Computing daily stats for days 0 through %d."
                                      .format(daysRan))
                                    (0 to daysRan).foreach {
                                      day: Int => {
                                        val perDayStats =
                                          ExperimentResultSet.
                                            ExperimentEnv.
                                            ExperimentResult.
                                            SchedulerStats.
                                            PerDayStats.newBuilder()
                                        perDayStats.setDayNum(day)
                                        // Busy and wasted time bucketed by day.
                                        perDayStats.setUsefulBusyTime(
                                          scheduler.dailyUsefulTimeScheduling.getOrElse(day, 0.0))
                                        println(("Writing dailyUsefulScheduling(day = %d) = %f for " +
                                          "scheduler %s")
                                          .format(day,
                                            scheduler
                                              .dailyUsefulTimeScheduling
                                              .getOrElse(day, 0.0),
                                            scheduler.name))
                                        perDayStats.setWastedBusyTime(
                                          scheduler.dailyWastedTimeScheduling.getOrElse(day, 0.0))
                                        // Counters bucketed by day.
                                        perDayStats.setNumSuccessfulTransactions(
                                          scheduler.dailySuccessTransactions.getOrElse[Int](day, 0))
                                        perDayStats.setNumFailedTransactions(
                                          scheduler.dailyFailedTransactions.getOrElse[Int](day, 0))

                                        schedulerStats.addPerDayStats(perDayStats)
                                      }
                                    }

                                    assert(scheduler.perWorkloadUsefulTimeScheduling.size ==
                                      scheduler.perWorkloadWastedTimeScheduling.size,
                                      "the maps held by Scheduler to track per workload " +
                                        "useful and wasted time should be the same size " +
                                        "(Scheduler.addJob() should ensure this).")
                                    scheduler.perWorkloadUsefulTimeScheduling.foreach {
                                      case (workloadName, workloadUsefulBusyTime) => {
                                        val perWorkloadBusyTime =
                                          ExperimentResultSet.
                                            ExperimentEnv.
                                            ExperimentResult.
                                            SchedulerStats.
                                            PerWorkloadBusyTime.newBuilder()
                                        perWorkloadBusyTime.setWorkloadName(workloadName)
                                        perWorkloadBusyTime.setUsefulBusyTime(workloadUsefulBusyTime)
                                        perWorkloadBusyTime.setWastedBusyTime(
                                          scheduler.perWorkloadWastedTimeScheduling(workloadName))

                                        schedulerStats.addPerWorkloadBusyTime(perWorkloadBusyTime)
                                      }
                                    }
                                    // Counts of sched-level job transaction successes, failures,
                                    // and retries.
                                    schedulerStats.setNumSuccessfulTransactions(
                                      scheduler.numSuccessfulTransactions)
                                    schedulerStats.setNumFailedTransactions(
                                      scheduler.numFailedTransactions)
                                    schedulerStats.setNumNoResourcesFoundSchedulingAttempts(
                                      scheduler.numNoResourcesFoundSchedulingAttempts)
                                    schedulerStats.setNumRetriedTransactions(
                                      scheduler.numRetriedTransactions)
                                    schedulerStats.setNumJobsTimedOutScheduling(
                                      scheduler.numJobsTimedOutScheduling)
                                    // Counts of task transaction successes and failures.
                                    schedulerStats.setNumSuccessfulTaskTransactions(
                                      scheduler.numSuccessfulTaskTransactions)
                                    schedulerStats.setNumFailedTaskTransactions(
                                      scheduler.numFailedTaskTransactions)

                                    schedulerStats.setIsMultiPath(scheduler.isMultiPath)
                                    schedulerStats.setNumJobsLeftInQueue(scheduler.jobQueueSize)
                                    schedulerStats.setFailedFindVictimAttempts(
                                      scheduler.failedFindVictimAttempts)

                                    experimentResult.addSchedulerStats(schedulerStats)
                                  })
                                  // Record scheduler specific details about the parameter sweeps.
                                  schedulerWorkloadsToSweepOver
                                    .foreach { case (schedName, workloadNames) => {
                                      workloadNames.foreach(workloadName => {
                                        val schedulerWorkload =
                                          ExperimentResultSet.
                                            ExperimentEnv.
                                            ExperimentResult.
                                            SchedulerWorkload.newBuilder()
                                        schedulerWorkload.setSchedulerName(schedName)
                                        schedulerWorkload.setWorkloadName(workloadName)
                                        experimentResult.addSweepSchedulerWorkload(schedulerWorkload)
                                      })
                                    }
                                    }

                                  experimentResult.setConstantThinkTime(constantThinkTime)
                                  experimentResult.setPerTaskThinkTime(perTaskThinkTime)
                                  experimentResult.setSecurity1Time(level1SecurityTime)
                                  experimentResult.setSecurity2Time(level2SecurityTime)
                                  experimentResult.setSecurity3Time(level3SecurityTime)
                                  // Save our results as a protocol buffer.
                                  experimentEnv.addExperimentResult(experimentResult.build())


                                  /**
                                    * TODO(andyk): Once protocol buffer support is finished,
                                    * remove this.
                                    */

                                  // Create a sorted list of schedulers and workloads to compute
                                  // a lot of the stats below, so that the we can be sure
                                  // which column is which when we print the stats.
                                  val sortedSchedulers = simulator
                                    .schedulers.values.toList.sortWith(_.name < _.name)
                                  val sortedWorkloads = workloads.toList.sortWith(_.name < _.name)

                                  // Sorted names of workloads.
                                  var workloadNames = sortedWorkloads.map(_.name).mkString(" ")

                                  // Count the jobs in each workload.
                                  var numJobs = sortedWorkloads.map(_.numJobs).mkString(" ")

                                  // Count the jobs in each workload that were actually scheduled.
                                  val numJobsScheduled = sortedWorkloads.map(workload => {
                                    workload.getJobs.filter(_.numSchedulingAttempts > 0).length
                                  }).mkString(" ")

                                  // Sorted names of Schedulers.
                                  val schedNames = sortedSchedulers.map(_.name).mkString(" ")

                                  // Calculate per scheduler successful, failed, retried
                                  // transaction conflict rates.
                                  val schedSuccessfulTransactions = sortedSchedulers.map(sched => {
                                    sched.numSuccessfulTransactions
                                  }).mkString(" ")
                                  val schedFailedTransactions = sortedSchedulers.map(sched => {
                                    sched.numFailedTransactions
                                  }).mkString(" ")
                                  val schedNoResorucesFoundSchedAttempt = sortedSchedulers.map(sched => {
                                    sched.numNoResourcesFoundSchedulingAttempts
                                  }).mkString(" ")
                                  val schedRetriedTransactions = sortedSchedulers.map(sched => {
                                    sched.numRetriedTransactions
                                  }).mkString(" ")

                                  // Calculate per scheduler task transaction and conflict rates
                                  val schedSuccessfulTaskTransactions = sortedSchedulers.map(sched => {
                                    sched.numSuccessfulTaskTransactions
                                  }).mkString(" ")
                                  val schedFailedTaskTransactions = sortedSchedulers.map(sched => {
                                    sched.numFailedTaskTransactions
                                  }).mkString(" ")

                                  val schedNumJobsTimedOutScheduling = sortedSchedulers.map(sched => {
                                    sched.numJobsTimedOutScheduling
                                  }).mkString(" ")

                                  // Calculate per scheduler aggregate (useful + wasted) busy time.
                                  val schedBusyTimes = sortedSchedulers.map(sched => {
                                    println(("calculating busy time for sched %s as " +
                                      "(%f + %f) / %f = %f.")
                                      .format(sched.name,
                                        sched.totalUsefulTimeScheduling,
                                        sched.totalWastedTimeScheduling,
                                        simulator.currentTime,
                                        (sched.totalUsefulTimeScheduling +
                                          sched.totalWastedTimeScheduling) /
                                          simulator.currentTime))
                                    (sched.totalUsefulTimeScheduling +
                                      sched.totalWastedTimeScheduling) / simulator.currentTime
                                  }).mkString(" ")

                                  // Calculate per scheduler aggregate (useful + wasted) busy time.
                                  val schedUsefulBusyTimes = sortedSchedulers.map(sched => {
                                    sched.totalUsefulTimeScheduling / simulator.currentTime
                                  }).mkString(" ")

                                  // Calculate per scheduler aggregate (useful + wasted) busy time.
                                  val schedWastedBusyTimes = sortedSchedulers.map(sched => {
                                    sched.totalWastedTimeScheduling / simulator.currentTime
                                  }).mkString(" ")

                                  // Calculate per-scheduler per-workload useful + wasted busy time.
                                  val perWorkloadSchedBusyTimes = sortedSchedulers.map(sched => {
                                    // Sort by workload name.
                                    val sortedSchedulingTimes =
                                      sched.perWorkloadUsefulTimeScheduling.toList.sortWith(_._1 < _._1)
                                    sortedSchedulingTimes.map(nameTimePair => {
                                      (nameTimePair._2 +
                                        sched.perWorkloadWastedTimeScheduling(nameTimePair._1)) /
                                        simulator.currentTime
                                    }).mkString(" ")
                                  }).mkString(" ")

                                  // Calculate 90%tile per-workload time-scheduling for
                                  // scheduled jobs.
                                  // sortedWorkloads is a ListBuffer[Workload]
                                  // Workload.jobs is a ListBuffer[Job].
                                  val jobThinkTimes90Percentile = sortedWorkloads.map(workload => {
                                    workload.jobUsefulThinkTimesPercentile(0.9)
                                  }).mkString(" ")

                                  // Calculate the average time jobs spent in scheduler's queue before
                                  // its first task was first scheduled.
                                  val avgJobQueueTimesTillFirstScheduled = sortedWorkloads.map(workload => {
                                    workload.avgJobQueueTimeTillFirstScheduled
                                  }).mkString(" ")

                                  // Calculate the average time jobs spent in scheduler's queue before
                                  // its final task was scheduled..
                                  val avgJobQueueTimesTillFullyScheduled = sortedWorkloads.map(workload => {
                                    workload.avgJobQueueTimeTillFullyScheduled
                                  }).mkString(" ")

                                  // Calculate the 90%tile per-workload jobQueueTime*-s for
                                  // scheduled jobs.
                                  val jobQueueTimeTillFirstScheduled90Percentile =
                                  sortedWorkloads.map(workload => {
                                    workload.jobQueueTimeTillFirstScheduledPercentile(0.9)
                                  }).mkString(" ")

                                  val jobQueueTimeTillFullyScheduled90Percentile =
                                    sortedWorkloads.map(workload => {
                                      workload.jobQueueTimeTillFullyScheduledPercentile(0.9)
                                    }).mkString(" ")

                                  val numSchedulingAttempts90Percentile =
                                    sortedWorkloads.map(workload => {
                                      workload.numSchedulingAttemptsPercentile(0.9)
                                    }).mkString(" ")

                                  val numSchedulingAttempts99Percentile =
                                    sortedWorkloads.map(workload => {
                                      workload.numSchedulingAttemptsPercentile(0.99)
                                    }).mkString(" ")

                                  val numSchedulingAttemptsMax =
                                    sortedWorkloads.map(workload => {
                                      workload.getJobs.map(_.numSchedulingAttempts).max
                                    }).mkString(" ")

                                  val numTaskSchedulingAttempts90Percentile =
                                    sortedWorkloads.map(workload => {
                                      workload.numTaskSchedulingAttemptsPercentile(0.9)
                                    }).mkString(" ")

                                  val numTaskSchedulingAttempts99Percentile =
                                    sortedWorkloads.map(workload => {
                                      workload.numTaskSchedulingAttemptsPercentile(0.99)
                                    }).mkString(" ")

                                  val numTaskSchedulingAttemptsMax =
                                    sortedWorkloads.map(workload => {
                                      workload.getJobs.map(_.numTaskSchedulingAttempts).max
                                    }).mkString(" ")

                                  // Per-scheduler stats.
                                  val schedulerIsMultiPaths = sortedSchedulers.map(sched => {
                                    if (sched.isMultiPath) "1"
                                    else "0"
                                  }).mkString(" ")
                                  val schedulerJobQueueSizes =
                                    sortedSchedulers.map(_.jobQueueSize).mkString(" ")

                                  val prettyLine = ("cell: %s \n" +
                                    "assignment policy: %s \n" +
                                    "on policy: %s \n" +
                                    "off policy: %s \n" +
                                    "runtime: %f \n" +
                                    "avg cpu util: %f \n" +
                                    "avg mem util: %f \n" +
                                    "num workloads %d \n" +
                                    "workload names: %s \n" +
                                    "numjobs: %s \n" +
                                    "num jobs scheduled: %s \n" +
                                    "perWorkloadSchedBusyTimes: %s \n" +
                                    "jobThinkTimes90Percentile: %s \n" +
                                    "avgJobQueueTimesTillFirstScheduled: %s \n" +
                                    "avgJobQueueTimesTillFullyScheduled: %s \n" +
                                    "jobQueueTimeTillFirstScheduled90Percentile: %s \n" +
                                    "jobQueueTimeTillFullyScheduled90Percentile: %s \n" +
                                    "numSchedulingAttempts90Percentile: %s \n" +
                                    "numSchedulingAttempts99Percentile: %s \n" +
                                    "numSchedulingAttemptsMax: %s \n" +
                                    "numTaskSchedulingAttempts90Percentile: %s \n" +
                                    "numTaskSchedulingAttempts99Percentile: %s \n" +
                                    "numTaskSchedulingAttemptsMax: %s \n" +
                                    "simulator.schedulers.size: %d \n" +
                                    "schedNames: %s \n" +
                                    "schedBusyTimes: %s \n" +
                                    "schedUsefulBusyTimes: %s \n" +
                                    "schedWastedBusyTimes: %s \n" +
                                    "schedSuccessfulTransactions: %s \n" +
                                    "schedFailedTransactions: %s \n" +
                                    "schedNoResorucesFoundSchedAttempt: %s \n" +
                                    "schedRetriedTransactions: %s \n" +
                                    "schedSuccessfulTaskTransactions: %s \n" +
                                    "schedFailedTaskTransactions: %s \n" +
                                    "schedNumJobsTimedOutScheduling: %s \n" +
                                    "schedulerIsMultiPaths: %s \n" +
                                    "schedulerNumJobsLeftInQueue: %s \n" +
                                    "workloadToSweepOver: %s \n" +
                                    "avgJobInterarrivalTime: %f \n" +
                                    "constantThinkTime: %f \n" +
                                    "perTaskThinkTime %f").format(
                                    workloadDesc.cell, // %s
                                    workloadDesc.assignmentPolicy, // %s
                                    simulator.powerOn.name, // %s
                                    simulator.powerOff.name, // %s
                                    simulatorDesc.runTime, // %f
                                    simulator.avgCpuUtilization /
                                      simulator.cellState.totalCpus, // %f
                                    simulator.avgMemUtilization /
                                      simulator.cellState.totalMem, // %f
                                    workloads.length, // %d
                                    workloadNames, // %s
                                    numJobs, // %s
                                    numJobsScheduled, // %s
                                    perWorkloadSchedBusyTimes, // %s
                                    jobThinkTimes90Percentile, // %s
                                    avgJobQueueTimesTillFirstScheduled, // %s
                                    avgJobQueueTimesTillFullyScheduled, // %s
                                    jobQueueTimeTillFirstScheduled90Percentile, // %s
                                    jobQueueTimeTillFullyScheduled90Percentile, // %s
                                    numSchedulingAttempts90Percentile, // %s
                                    numSchedulingAttempts99Percentile, // %s
                                    numSchedulingAttemptsMax, // %s
                                    numTaskSchedulingAttempts90Percentile, // %s
                                    numTaskSchedulingAttempts99Percentile, // %s
                                    numTaskSchedulingAttemptsMax, // %s
                                    simulator.schedulers.size, // %d
                                    schedNames, // %s
                                    schedBusyTimes, // %s
                                    schedUsefulBusyTimes, // %s
                                    schedWastedBusyTimes, // %s
                                    schedSuccessfulTransactions, // %s
                                    schedFailedTransactions, // %s
                                    schedNoResorucesFoundSchedAttempt, // %s
                                    schedRetriedTransactions, // %s
                                    schedSuccessfulTaskTransactions, // %s
                                    schedFailedTaskTransactions, // %s
                                    schedNumJobsTimedOutScheduling, // %s
                                    schedulerIsMultiPaths, // %s
                                    schedulerJobQueueSizes,
                                    workloadToSweepOver, // %s
                                    avgJobInterarrivalTime.getOrElse(
                                      workloads.filter(_.name == workloadToSweepOver) // %f
                                        .head.avgJobInterarrivalTime),
                                    constantThinkTime, // %f
                                    perTaskThinkTime) // %f

                                  println(prettyLine + "\n")
                                } else { // if (success)
                                  println("Simulation timed out.")
                                }
                              }) //prefill
                            })//stackelberg
                          })//security3
                        })//security2
                      })//security1
                    }) // power on
                  })// power off
                }) // sorting
              })// picking
            }) // blackListPercent
          }) // C
        }) // L
      }) // lambda
      experimentResultSet.addExperimentEnv(experimentEnv)
    }) // WorkloadDescs
    experimentResultSet.build().writeTo(output)
    output.close()
  }
}
