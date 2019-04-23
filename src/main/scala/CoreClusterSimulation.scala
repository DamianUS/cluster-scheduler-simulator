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

import dynamic.neuralnetwork.SimpleNN
import dynamic.{DynamicScheduler, DynamicSimulator}
import efficiency.DistributionCache
import efficiency.ordering_cellstate_resources_policies.{BasicLoadSorter, CellStateResourcesSorter, NoSorter}
import efficiency.pick_cellstate_resources._
import efficiency.power_off_policies.PowerOffPolicy
import efficiency.power_on_policies.PowerOnPolicy

import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.collection.mutable.IndexedSeq
import scala.collection.mutable.ListBuffer
import org.apache.commons.math.distribution.ExponentialDistributionImpl
import org.apache.commons.math3.distribution.WeibullDistribution
import stackelberg.StackelbergAgent

import scala.util.Random

/**
  * A simple, generic, discrete event simulator. A modified version of the
  * basic discrete event simulator code from Programming In Scala, pg 398,
  * http://www.amazon.com/Programming-Scala-Comprehensive-Step-step/dp/0981531601
  */
abstract class Simulator(logging: Boolean = false) {
  var totalRuntime = 0.0
  type Action = () => Unit

  case class WorkItem(time: Double, action: Action)

  implicit object WorkItemOrdering extends Ordering[WorkItem] {
    def compare(a: WorkItem, b: WorkItem) = {
      if (a.time < b.time) 1
      else if (a.time > b.time) -1
      else 0
    }
  }

  protected var curtime: Double = 0.0 // simulation time, in seconds
  protected var agenda = new collection.mutable.PriorityQueue[WorkItem]()

  def agendaSize = agenda.size

  def currentTime: Double = curtime

  def log(s: => String) {
    if (!logging) {
      null
    } else {
      println(curtime + " " + s)
    }
  }

  def afterDelay(delay: Double)(block: => Unit) {
    val item = WorkItem(currentTime + delay, () => block)
    agenda.enqueue(item)
    // log("inserted new WorkItem into agenda to run at time " + (currentTime + delay))
  }

  private def next() {
    val item = agenda.dequeue()
    curtime = item.time
    item.action()
  }

  /**
    * Run the simulation for {@code runTime} virtual (i.e., simulated)
    * seconds or until {@code wallClockTimeout} seconds of execution
    * time elapses.
    *
    * @return true if simulation ran till runTime or completion, and false
    *         if simulation timed out.
    */
  def run(runTime: Option[Double] = None,
          wallClockTimeout: Option[Double] = None): Boolean = {
    afterDelay(0) {
      println("*** Simulation started, time = " + currentTime + ". ***")
    }
    // Record wall clock time at beginning of simulation run.
    val startWallTime = java.util.Calendar.getInstance().getTimeInMillis()

    def timedOut: Boolean = {
      val currWallTime = java.util.Calendar.getInstance().getTimeInMillis()
      if (wallClockTimeout.exists((currWallTime - startWallTime) / 1000.0 > _)) {
        println("Execution timed out after %f seconds, ending simulation now."
          .format((currWallTime - startWallTime) / 1000.0))
        true
      } else {
        false
      }
    }

    runTime match {
      case Some(rt) =>
        while (!agenda.isEmpty && currentTime <= rt && !timedOut) next()
      case None =>
        while (!agenda.isEmpty && !timedOut) next()
    }
    println("*** Simulation finished running, time = " + currentTime + ". ***")
    !timedOut
  }
}

abstract class ClusterSimulatorDesc(val runTime: Double) {
  def newSimulator(constantThinkTim: Double,
                   perTaskThinkTime: Double,
                   blackListPercent: Double,
                   schedulerWorkloadsToSweepOver: Map[String, Seq[String]],
                   workloadToSchedulerMap: Map[String, Seq[String]],
                   cellStateDesc: CellStateDesc,
                   workloads: Seq[Workload],
                   prefillWorkloads: Seq[Workload],
                   logging: Boolean = false,
                   cellStateResourcesSorter: CellStateResourcesSorter,
                   cellStateResourcesPicker: CellStateResourcesPicker,
                   powerOnPolicy: PowerOnPolicy,
                   powerOffPolicy: PowerOffPolicy,
                   securityLevel1Time: Double,
                   securityLevel2Time: Double,
                   securityLevel3Time: Double,
                   stackelbergStrategy: StackelbergAgent): ClusterSimulator
}

/**
  * A simulator to compare different cluster scheduling architectures
  * (single agent, dynamically partitioned,and replicated state), based
  * on a set of input parameters that define the schedulers being used
  * and the workload being played.
  *
  * @param schedulers             A Map from schedulerName to Scheduler, should
  *                               exactly one entry for each scheduler that is registered with
  *                               this simulator.
  * @param workloadToSchedulerMap A map from workloadName to Seq[SchedulerName].
  *                               Used to determine which scheduler each job is assigned.
  */
class ClusterSimulator(val cellState: CellState,
                       val schedulers: Map[String, Scheduler],
                       val workloadToSchedulerMap: Map[String, Seq[String]],
                       val workloads: Seq[Workload],
                       prefillWorkloads: Seq[Workload],
                       logging: Boolean = false,
                       monitorUtilization: Boolean = true,
                       monitoringPeriod: Double = 300.0,
                       cellStateResourcesSorter: CellStateResourcesSorter,
                       cellStateResourcesPicker: CellStateResourcesPicker,
                       powerOnPolicy: PowerOnPolicy,
                       powerOffPolicy: PowerOffPolicy,
                       val securityLevel1Time: Double,
                       val securityLevel2Time: Double,
                       val securityLevel3Time: Double,
                       val stackelbergStrategy: StackelbergAgent)
  extends Simulator(logging) {
  assert(schedulers.size > 0, "At least one scheduler must be provided to" +
    "scheduler constructor.")
  assert(workloadToSchedulerMap.size > 0, "No workload->scheduler map setup.")
  workloadToSchedulerMap.values.foreach(schedulerNameSeq => {
    schedulerNameSeq.foreach(schedulerName => {
      val contains: Boolean = schedulers.contains(schedulerName)
      assert(contains, ("Workload-Scheduler map points to a scheduler, " +
        "%s, that is not registered").format(schedulerName))
    })
  })

  var jobCache = Seq[Tuple2[Double, Job]]()

  def addCacheJob(tuple: Tuple2[Double, Job]): Unit = {
    if (jobCache.size > 0)
      assert(tuple._1 >= jobCache.last._1, "Intentando añadir un job a la cache cuyo tiempo de inicio es anterior al ultimo encontrado")
    jobCache = jobCache :+ tuple
    if (jobCache.size > 100) {
      jobCache = jobCache.drop(1)
    }
  }

  val sorter = cellStateResourcesSorter
  val picker = cellStateResourcesPicker
  val powerOn = powerOnPolicy
  var powerOff = powerOffPolicy
  val stackelberg = stackelbergStrategy
  // Set up a pointer to this simulator in the cellstate.
  cellState.simulator = this
  // Set up a pointer to this simulator in each scheduler.
  schedulers.values.foreach(_.simulator = this)

  class PrefillScheduler
    extends Scheduler(name = "prefillScheduler",
      constantThinkTimes = Map[String, Double](),
      perTaskThinkTimes = Map[String, Double](),
      numMachinesToBlackList = 0) {}

  private val prefillScheduler = new PrefillScheduler
  prefillScheduler.simulator = this
  // Prefill jobs that exist at the beginning of the simulation.
  // Setting these up is similar to loading jobs that are part
  // of the simulation run; they need to be scheduled onto machines
  println("Prefilling cell-state with %d workloads."
    .format(prefillWorkloads.length))
  prefillWorkloads.foreach(workload => {
    println("Prefilling cell-state with %d jobs from workload %s."
      .format(workload.numJobs, workload.name))
    var i = 0
    workload.getJobs.foreach(job => {
      i += 1
      // println("Prefilling %d %s job id - %d."
      //         .format(i, workload.name, job.id))
      if (job.cpusPerTask > cellState.cpusPerMachine ||
        job.memPerTask > cellState.memPerMachine) {
        println(("IGNORING A JOB REQUIRING %f CPU & %f MEM PER TASK " +
          "BECAUSE machines only have %f cpu / %f mem.")
          .format(job.cpusPerTask, job.memPerTask,
            cellState.cpusPerMachine, cellState.memPerMachine))
      } else {
        val claimDeltas = prefillScheduler.scheduleJob(job, cellState)
        // assert(job.numTasks == claimDeltas.length,
        //        "Prefill job failed to schedule.")
        cellState.scheduleEndEvents(claimDeltas)
      }
    })
    println(("After prefill, common cell state now has %.2f%% (%.2f) " +
      "cpus and %.2f%% (%.2f) mem occupied.")
      .format(cellState.totalOccupiedCpus / cellState.totalCpus * 100.0,
        cellState.totalOccupiedCpus,
        cellState.totalOccupiedMem / cellState.totalMem * 100.0,
        cellState.totalOccupiedMem))
  })

  // Set up workloads
  workloads.foreach(workload => {
    var numSkipped, numLoaded = 0
    /*workload.getJobs.filter( p => p.submitted >= 21600.0).take(10).foreach(p => println(p));
    workload.getJobs.filter( p => p.submitted >= 136800.0).take(10).foreach(p => println(p));*/

    workload.getJobs.foreach(job => {
      val scheduler = getSchedulerForWorkloadName(job.workloadName)
      if (scheduler == None) {
        log(("Warning, skipping a job fom a workload type (%s) that has " +
          "not been mapped to any registered schedulers. Please update " +
          "a mapping for this scheduler via the workloadSchedulerMap param.")
          .format(job.workloadName))
        numSkipped += 1
      } else {
        // Schedule the task to get submitted to its scheduler at its
        // submission time.

        // assert(job.cpusPerTask * job.numTasks <= cellState.totalCpus + 0.000001 &&
        //        job.memPerTask * job.numTasks <= cellState.totalMem + 0.000001,
        //        ("The cell (%f cpus, %f mem) is not big enough to hold job %d " +
        //        "all at once which requires %f cpus and %f mem in total.")
        //        .format(cellState.totalCpus,
        //                cellState.totalMem,
        //                job.id,
        //                job.cpusPerTask * job.numTasks,
        //                job.memPerTask * job.numTasks))
        if (job.cpusPerTask * job.numTasks > cellState.totalCpus + 0.000001 ||
          job.memPerTask * job.numTasks > cellState.totalMem + 0.000001) {
          println(("WARNING: The cell (%f cpus, %f mem) is not big enough " +
            "to hold job id %d all at once which requires %f cpus " +
            "and %f mem in total.").format(cellState.totalCpus,
            cellState.totalMem,
            job.id,
            job.cpusPerTask * job.numTasks,
            job.memPerTask * job.numTasks))
        }
        afterDelay(job.submitted - currentTime) {
          scheduler.foreach(_.addJob(job))
        }
        numLoaded += 1
      }
    })
    println("Loaded %d jobs from workload %s, and skipped %d.".format(
      numLoaded, workload.name, numSkipped))
  })
  var roundRobinCounter = 0

  // If more than one scheduler is assigned a workload, round robin across them.
  def getSchedulerForWorkloadName(workloadName: String): Option[Scheduler] = {
    workloadToSchedulerMap.get(workloadName).map(schedulerNames => {
      // println("schedulerNames is %s".format(schedulerNames.mkString(" ")))
      roundRobinCounter += 1
      val name = schedulerNames(roundRobinCounter % schedulerNames.length)
      // println("Assigning job from workload %s to scheduler %s"
      //         .format(workloadName, name))
      schedulers(name)
    })
  }

  // Track utilization due to resources actually being accepted by a
  // framework/scheduler. This does not include the time resources spend
  // tied up while they are pessimistically locked (e.g. while they are
  // offered as part of a Mesos resource-offer). That type of utilization
  // is tracked separately below.
  def avgCpuUtilization: Double = sumCpuUtilization / numMonitoringMeasurements

  def avgMemUtilization: Double = sumMemUtilization / numMonitoringMeasurements

  // Track "utilization" of resources due to their being pessimistically locked
  // (i.e. while they are offered as part of a Mesos resource-offer).
  def avgCpuLocked: Double = sumCpuLocked / numMonitoringMeasurements

  def avgMemLocked: Double = sumMemLocked / numMonitoringMeasurements

  def avgCpuOn: Double = (sumMachinesOn / numMonitoringMeasurements) * cellState.cpusPerMachine

  def avgMemOn: Double = (sumMachinesOn / numMonitoringMeasurements) * cellState.memPerMachine

  def avgCpuOff: Double = (sumMachinesOff / numMonitoringMeasurements) * cellState.cpusPerMachine

  def avgMemOff: Double = (sumMachinesOff / numMonitoringMeasurements) * cellState.memPerMachine

  def avgCpuTurningOff: Double = (sumMachinesTurningOff / numMonitoringMeasurements) * cellState.cpusPerMachine

  def avgMemTurningOff: Double = (sumMachinesTurningOff / numMonitoringMeasurements) * cellState.memPerMachine

  def avgCpuTurningOn: Double = (sumMachinesTurningOn / numMonitoringMeasurements) * cellState.cpusPerMachine

  def avgMemTurningOn: Double = (sumMachinesTurningOn / numMonitoringMeasurements) * cellState.memPerMachine

  def avgMachinesOccupied: Double = (sumMachinesOccupied.toDouble / numMonitoringMeasurements.toDouble)

  def avgMachinesOn: Double = (sumMachinesOn.toDouble / numMonitoringMeasurements.toDouble)

  var sumCpuUtilization: Double = 0.0
  //Used in the measurement Dynamic Scheduling
  var measuredSchedulingStrategy: Array[String] = Array[String]()
  //Used in the measurement
  var measuredCpuUtilization: Array[Double] = Array[Double]()
  //Cpu on totally idle machines
  var measuredCpuTotallyIdle: Array[Double] = Array[Double]()
  //Cpu on partially idle machines
  var measuredCpuPartiallyIdle: Array[Double] = Array[Double]()
  var sumMemUtilization: Double = 0.0
  var measuredMemUtilization: Array[Double] = Array[Double]()
  //Mem on totally idle machines
  var measuredMemTotallyIdle: Array[Double] = Array[Double]()
  //Cpu on partially idle machines
  var measuredMemPartiallyIdle: Array[Double] = Array[Double]()
  var sumCpuLocked: Double = 0.0
  var measuredCpuLocked: Array[Double] = Array[Double]()
  var sumMemLocked: Double = 0.0
  var measuredMemLocked: Array[Double] = Array[Double]()
  var numMonitoringMeasurements: Long = 0
  var sumMachinesOn = 0
  var measuredMachinesOn: Array[Double] = Array[Double]()
  var sumMachinesOff = 0
  var measuredMachinesOff: Array[Double] = Array[Double]()
  var sumMachinesTurningOn = 0
  var measuredMachinesTurningOn: Array[Double] = Array[Double]()
  var sumMachinesTurningOff = 0
  var measuredMachinesTurningOff: Array[Double] = Array[Double]()
  var totalMachinePowerStates = Seq[Array[Int]]()
  var sumMachinesOccupied = 0
  var sumCpuIdle = 0.0
  var sumMemIdle = 0.0
  //Heterogeneous machines power profile
  val totalMeasurements = (86400.0 * 7 / monitoringPeriod).toInt + 2
  val totalPower = Array.ofDim[Double](cellState.numMachines, totalMeasurements) //Matrix of numMachines
  val currentPower = Array.ofDim[Double](cellState.numMachines, totalMeasurements) //Matrix of numMachines


  var runtime = 0.0
  val powerPerCpuOn = 108
  val powerPerCpuIdle = 50
  val powerTurnedOff = 5
  val powerBooting = 110
  val powerShutting = 110

  def totalEnergyConsumed: Double = {
    /*
    //Old way, all machines homogeneous in terms of energy consumption
    val energyOn = (sumCpuUtilization.toDouble / numMonitoringMeasurements.toDouble) * powerPerCpuOn * runtime
    val energyIdle = ( ((sumMachinesOn * cellState.cpusPerMachine) - (sumCpuUtilization)).toDouble / numMonitoringMeasurements.toDouble) * powerPerCpuIdle * runtime
    val energyOff = (sumMachinesOff.toDouble / numMonitoringMeasurements.toDouble) * powerTurnedOff * runtime
    val energyTurnOn = (sumMachinesTurningOn.toDouble / numMonitoringMeasurements.toDouble) * powerBooting * runtime
    val energyTurnOff = (sumMachinesTurningOff.toDouble / numMonitoringMeasurements.toDouble) * powerShutting * runtime
    energyOn + energyIdle + energyOff + energyTurnOff + energyTurnOn
    */
    val avgPowerPerMachine = new Array[Double](cellState.numMachines)
    for (machineID <- 0 until cellState.numMachines) {
      avgPowerPerMachine(machineID) = totalPower(machineID).sum / totalPower(machineID).length
    }
    avgPowerPerMachine.sum * runtime / 3600000
  }

  def totalCurrentEnergyConsumed: Double = {
    /*
    //Old way, all machines homogeneous in terms of energy consumption
    val energyOn = (sumCpuUtilization.toDouble / numMonitoringMeasurements.toDouble) * powerPerCpuOn * runtime
    val energyIdle = ( ((cellState.numMachines * cellState.cpusPerMachine * numMonitoringMeasurements) - (sumCpuUtilization)).toDouble / numMonitoringMeasurements.toDouble) * powerPerCpuIdle * runtime
    energyOn + energyIdle
    */
    val avgPowerPerMachine = new Array[Double](cellState.numMachines)
    for (machineID <- 0 until cellState.numMachines) {
      avgPowerPerMachine(machineID) = currentPower(machineID).sum / currentPower(machineID).length
    }
    avgPowerPerMachine.sum * runtime / 3600000
  }

  def shuttingDownsPerMachineAvg: Double = if (cellState.powerOffs.filter(_ != null).length > 0) cellState.powerOffs.filter(_ != null).map(_.length).sum.toDouble / cellState.powerOffs.filter(_ != null).length.toDouble else 0.0

  def maxShuttingDowns: Int = if (cellState.powerOffs.filter(_ != null).length > 0) cellState.powerOffs.filter(_ != null).map(_.length).max else 0

  def minShuttingDowns: Int = if (cellState.powerOffs.filter(_ != null).length < cellState.powerOffs.length) 0 else cellState.powerOffs.filter(_ != null).map(_.length).min

  def shuttingDownsPerMachineArray: Array[Seq[Double]] = cellState.powerOffs.filter(_ != null)

  def shuttingDownsPerMachinePercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val powerOffs = cellState.powerOffs.filter(_ != null)
    if (powerOffs.length > 0) {
      val powerOffsArray = new Array[Int](powerOffs.length)
      powerOffs.map(_.length).copyToArray(powerOffsArray)
      util.Sorting.quickSort(powerOffsArray)
      val result = powerOffsArray(((powerOffsArray.length - 1) * percentile).toInt)
      println(("Looking up number of shutting downs per machine " +
        "percentile value at position %d of %d: %f.")
        .format(((powerOffsArray.length) * percentile).toInt,
          powerOffsArray.length,
          result.toDouble))
      result
    } else {
      0.0
    }
  }

  def timeShuttedDownPerCycleAvg: Double = if (cellState.powerOffs.filter(_ != null).length > 0) cyclesShuttedDownPerMachineArray.flatten.sum / cyclesShuttedDownPerMachineArray.flatten.length else 0.0

  def maxTimeShuttedDownPerCycle: Double = if (cellState.powerOffs.filter(_ != null).length > 0) cyclesShuttedDownPerMachineArray.flatten.max else 0.0

  def minTimeShuttedDownPerCycle: Double = if (cellState.powerOffs.filter(_ != null).length > 0) cyclesShuttedDownPerMachineArray.flatten.min else 0.0

  def cyclesShuttedDownPerMachineArray: Array[Seq[Double]] = {
    var timeShuttedDownPerMachine = Array[Seq[Double]]()
    for (i <- 0 to cellState.numMachines - 1) {
      if (cellState.powerOffs(i) != null) {
        assert(cellState.powerOffs(i).length > 0, "No tiene ningún apagado y ha entrado en que tiene")
        if (cellState.powerOffs(i).length == 1) {
          assert(cellState.powerOns(i) == null || cellState.powerOns(i).length == 1, "número de apagados/encendidos no acordes")
        }
        else {
          assert(cellState.powerOns(i).length == cellState.powerOffs(i).length || cellState.powerOns(i).length == cellState.powerOffs(i).length - 1, "número de apagados/encendidos no acordes")
        }
        var machineShuttingTimes = Seq[Double]()
        //timeShuttedDownPerMachine = timeShuttedDownPerMachine :+  Seq[Double]()
        for (j <- 0 to cellState.powerOffs(i).length - 1) {
          if (cellState.powerOns(i) != null && cellState.powerOns(i).length > j) {
            //Exists a power on after this power off
            machineShuttingTimes = machineShuttingTimes :+ (cellState.powerOns(i)(j) - cellState.powerOffs(i)(j))
          }
          else {
            //Does not exists a power on after this power off
            machineShuttingTimes = machineShuttingTimes :+ (currentTime - cellState.powerOffs(i)(j))
          }
        }
        timeShuttedDownPerMachine = timeShuttedDownPerMachine :+ machineShuttingTimes
      }
    }
    timeShuttedDownPerMachine
  }

  def timeShuttedDownPerCyclePercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val powerOffs = cellState.powerOffs.filter(_ != null)
    if (powerOffs.length > 0) {
      val timeShuttedDownArray = new Array[Double](cyclesShuttedDownPerMachineArray.flatten.length)
      cyclesShuttedDownPerMachineArray.flatten.copyToArray(timeShuttedDownArray)
      util.Sorting.quickSort(timeShuttedDownArray)
      val result = timeShuttedDownArray(((timeShuttedDownArray.length - 1) * percentile).toInt)
      println(("Looking up time that machines are keep shutted down per cycle " +
        "percentile value at position %d of %d: %f.")
        .format(((timeShuttedDownArray.length) * percentile).toInt,
          timeShuttedDownArray.length,
          result))
      result
    } else {
      0.0
    }
  }

  def timeShuttedDownPerMachineArray: Seq[Double] = {
    cellState.powerOffs.filter(_ != null).map(_.sum)
  }

  def timeShuttedDownPerMachineAvg: Double = if (cellState.powerOffs.filter(_ != null).length > 0) timeShuttedDownPerMachineArray.sum / timeShuttedDownPerMachineArray.length else 0.0

  def maxTimeShuttedDownPerMachine: Double = if (cellState.powerOffs.filter(_ != null).length > 0) timeShuttedDownPerMachineArray.max else 0.0

  def minTimeShuttedDownPerMachine: Double = if (cellState.powerOffs.filter(_ != null).length > 0) timeShuttedDownPerMachineArray.min else 0.0

  def timeShuttedDownPerMachinePercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val powerOffs = cellState.powerOffs.filter(_ != null)
    if (powerOffs.length > 0) {
      val timeShuttedDownArray = new Array[Double](timeShuttedDownPerMachineArray.length)
      timeShuttedDownPerMachineArray.copyToArray(timeShuttedDownArray)
      util.Sorting.quickSort(timeShuttedDownArray)
      val result = timeShuttedDownArray(((timeShuttedDownArray.length - 1) * percentile).toInt)
      println(("Looking up job time that machines are keep shutted down " +
        "percentile value at position %d of %d: %f.")
        .format(((timeShuttedDownArray.length) * percentile).toInt,
          timeShuttedDownArray.length,
          result))
      result
    } else {
      0.0
    }
  }

  def totalPowerOffNumber: Int = {
    cellState.powerOffs.filter(_ != null).map(_.length).sum
  }

  def totalPowerOnNumber: Int = {
    cellState.powerOns.filter(_ != null).map(_.length).sum
  }

  def totalEnergySaved: Double = {
    totalCurrentEnergyConsumed - totalEnergyConsumed
  }

  def percEnergyConsumedVsCurrent: Double = {
    totalEnergyConsumed / totalCurrentEnergyConsumed
  }

  def secondsWastedPerKwhSaved: Double = {
    var totalSecondsWasted = 0.0
    schedulers.map(_._2).foreach(totalSecondsWasted += _.totalWastedTimeSchedulingPowering)
    if (totalSecondsWasted > 0 && totalEnergySaved > 0) {
      totalSecondsWasted / (totalEnergySaved)
    }
    else {
      0.0
    }
  }

  def totalSecondsWasted: Double = {
    var totalSecondsWasted = 0.0
    schedulers.map(_._2).foreach(totalSecondsWasted += _.totalWastedTimeSchedulingPowering)
    totalSecondsWasted
  }

  def kwhSavedPerShutting: Double = {
    if (totalPowerOffNumber > 0 && totalEnergySaved > 0.0) {
      totalEnergySaved / totalPowerOffNumber
    }
    else {
      0.0
    }
  }

  def changeResourceManager: Unit = {
    val windowSize = 10
    val jobCacheLength = cellState.simulator.jobCache.length
    if(jobCacheLength > 1){
      val pastTuples = if (jobCacheLength > windowSize+1) jobCache.slice(jobCacheLength-(windowSize+1), jobCacheLength) else jobCache
      val arraySize = if (pastTuples.length > 0) pastTuples.length-1 else 0
      val interArrival = new Array[Double](arraySize)
      for(i <- 1 to pastTuples.length-1){
        interArrival(i-1) = (pastTuples(i)._1 - pastTuples(i-1)._1)
      }
      val interArrivalMean = interArrival.sum / interArrival.size.toDouble
      //val chosenStrategy = this.asInstanceOf[DynamicSimulator].strategies(Random.nextInt(this.asInstanceOf[DynamicSimulator].strategies.length))
      val chosenStrategy = SimpleNN.classify(cellState.totalOccupiedCpus / cellState.totalCpus, interArrivalMean)
      //println("strategy: "+chosenStrategy+" inter: "+interArrivalMean )
      //val chosenStrategy = "Mesos"
      schedulers.values.foreach(scheduler => scheduler.asInstanceOf[DynamicScheduler].chooseStrategy(chosenStrategy))
      /*if(numMonitoringMeasurements % 100 == 0){
        println("Changes: "+ schedulers.values.head.asInstanceOf[DynamicScheduler].iter)
      }*/
      //schedulers.values.foreach(scheduler => scheduler.asInstanceOf[DynamicScheduler].markedStrategy=chosenStrategy)
    }

  }

  def measureUtilization: Unit = {
    val totalOccupiedCpus = cellState.totalOccupiedCpus
    val totalOccupiedMem = cellState.totalOccupiedMem
    val numMachinesOccupied = cellState.numMachinesOccupied
    val numMachinesOn = cellState.numberOfMachinesOn
    val numMachinesOff = cellState.numberOfMachinesOff
    val numMachinesTurningOn = cellState.numberOfMachinesTurningOn
    val numMachinesTurningOff = cellState.numberOfMachinesTurningOff
    val cpuLocked = cellState.totalLockedCpus
    val memLocked = cellState.totalLockedMem
    val totallyIdleCpu = (numMachinesOn - numMachinesOccupied) * cellState.cpusPerMachine.toDouble / cellState.totalCpus.toDouble
    val totallyIdleMem = (numMachinesOn - numMachinesOccupied) * cellState.memPerMachine.toDouble / cellState.totalMem.toDouble
    measuredCpuUtilization = measuredCpuUtilization :+ totalOccupiedCpus.toDouble / cellState.totalCpus.toDouble
    measuredCpuTotallyIdle = measuredCpuTotallyIdle :+ totallyIdleCpu
    measuredCpuPartiallyIdle = measuredCpuPartiallyIdle :+ (numMachinesOn * cellState.cpusPerMachine - totalOccupiedCpus - totallyIdleCpu).toDouble / cellState.totalCpus.toDouble
    measuredMemUtilization = measuredMemUtilization :+ totalOccupiedMem.toDouble / cellState.totalMem.toDouble
    measuredMemTotallyIdle = measuredMemTotallyIdle :+ totallyIdleMem
    measuredMemPartiallyIdle = measuredMemPartiallyIdle :+ (numMachinesOn * cellState.memPerMachine - totalOccupiedMem - totallyIdleMem).toDouble / cellState.totalMem.toDouble
    measuredMachinesOff = measuredMachinesOff :+ numMachinesOff.toDouble / cellState.numMachines.toDouble
    measuredMachinesOn = measuredMachinesOn :+ numMachinesOn.toDouble / cellState.numMachines.toDouble
    measuredMachinesTurningOff = measuredMachinesTurningOff :+ numMachinesTurningOff.toDouble / cellState.numMachines.toDouble
    measuredMachinesTurningOn = measuredMachinesTurningOn :+ numMachinesTurningOn.toDouble / cellState.numMachines.toDouble
    measuredCpuLocked = measuredCpuLocked :+ cpuLocked.toDouble / cellState.totalCpus.toDouble
    measuredMemLocked = measuredMemLocked :+ memLocked.toDouble / cellState.totalMem.toDouble
    sumCpuUtilization += totalOccupiedCpus
    sumMemUtilization += totalOccupiedMem
    sumCpuLocked += cpuLocked
    sumMemLocked += memLocked
    sumMachinesOn += numMachinesOn
    sumMachinesOff += numMachinesOff
    sumMachinesTurningOff += numMachinesTurningOff
    sumMachinesTurningOn += numMachinesTurningOn
    sumMachinesOccupied += numMachinesOccupied
    for (machineID <- 0 until cellState.numMachines) {
      var totalMachinePower = powerBooting * cellState.machinesEnergy(machineID) //We assume the machine is turning on (less frequent case)
      var currentMachinePower = powerPerCpuIdle * cellState.cpusPerMachine * cellState.machinesEnergy(machineID)
      if (cellState.machinePowerState(machineID) == 0) {
        //Off
        totalMachinePower = powerTurnedOff * cellState.machinesEnergy(machineID)
        currentMachinePower = powerPerCpuIdle * cellState.cpusPerMachine * cellState.machinesEnergy(machineID)
      }
      else if (cellState.machinePowerState(machineID) == 1) {
        //On
        totalMachinePower = (cellState.allocatedCpusPerMachine(machineID) * powerPerCpuOn + cellState.availableCpusPerMachine(machineID) * powerPerCpuIdle) * cellState.machinesEnergy(machineID)
        currentMachinePower = totalMachinePower
      }
      else if (cellState.machinePowerState(machineID) == 2) {
        //Turning off
        totalMachinePower = powerShutting * cellState.machinesEnergy(machineID)
        currentMachinePower = powerPerCpuIdle * cellState.cpusPerMachine * cellState.machinesEnergy(machineID)
      }
      totalPower(machineID)(numMonitoringMeasurements.toInt) = totalMachinePower
      currentPower(machineID)(numMonitoringMeasurements.toInt) = currentMachinePower
    }
    //println(this.currentTime)
    if(this.isInstanceOf[DynamicSimulator]){
      measuredSchedulingStrategy = measuredSchedulingStrategy :+ schedulers.values.head.asInstanceOf[DynamicScheduler].chosenStrategy.name
      changeResourceManager
      //println("post " + this.currentTime.toString)
    }
    numMonitoringMeasurements += 1
    //totalMachinePowerStates = totalMachinePowerStates :+ cellState.machinePowerState
    log("Avg cpu utilization (adding measurement %d of %f): %f."
      .format(numMonitoringMeasurements,
        cellState.totalOccupiedCpus,
        avgCpuUtilization))
    //Temporary: print utilization throughout the day.
    // if (numMonitoringMeasurements % 1000 == 0) {
    //   println("%f - Current cluster utilization: %f %f cpu , %f %f mem"
    //           .format(currentTime,
    //                   cellState.totalOccupiedCpus,
    //                   cellState.totalOccupiedCpus / cellState.totalCpus,
    //                   cellState.totalOccupiedMem,
    //                   cellState.totalOccupiedMem / cellState.totalMem))
    //   println(("%f - Current cluster utilization from locked resources: " +
    //            "%f cpu, %f mem")
    //            .format(currentTime,
    //                    cellState.totalLockedCpus,
    //                    cellState.totalLockedCpus/ cellState.totalCpus,
    //                    cellState.totalLockedMem,
    //                    cellState.totalLockedMem / cellState.totalMem))
    // }
    log("Avg mem utilization: %f.".format(avgMemUtilization))
    // Only schedule a monitoring event if the simulator has
    // more (non-monitoring) events to play. Else this will cause
    // the simulator to run forever just to keep monitoring.
    if (!agenda.isEmpty) {
      afterDelay(monitoringPeriod) {
        measureUtilization
      }
    }
  }

  override
  def run(runTime: Option[Double] = None,
          wallClockTimeout: Option[Double] = None): Boolean = {
    assert(currentTime == 0.0, "currentTime must be 0 at simulator run time.")
    totalRuntime = runTime.getOrElse(0.0)
    schedulers.values.foreach(scheduler => {
      assert(scheduler.jobQueueSize == 0,
        "Schedulers are not allowed to have jobs in their " +
          "queues when we run the simulator.")
    })
    // Optionally, start the utilization monitoring loop.
    if (monitorUtilization) {
      afterDelay(0.0) {
        measureUtilization
      }
    }
    runtime = runTime.getOrElse(0.0)
    super.run(runTime, wallClockTimeout)
  }
}

class SchedulerDesc(val name: String,
                    val constantThinkTimes: Map[String, Double],
                    val perTaskThinkTimes: Map[String, Double])

/**
  * A Scheduler maintains {@code Job}s submitted to it in a queue, and
  * attempts to match those jobs with resources by making "job scheduling
  * decisions", which take a certain amount of "scheduling time".
  * A simulator is responsible for interacting with a Scheduler, e.g.,
  * by deciding which workload types should be assigned to which scheduler.
  * A Scheduler must accept Jobs associated with any workload type (workloads
  * are identified by name), though it can do whatever it wants with those
  * jobs, include, optionally, dropping them on the floor, or handling jobs
  * from different workloads differently, which concretely, means taking
  * a different amount of "scheuduling time" to schedule jobs from different
  * workloads.
  *
  * @param simulator             The simulator this scheduler is running inside of.
  * @param name                  Unique name that this scheduler is known by for the purposes
  *                              of jobs being assigned to.
  * @param contstantThinkTimes   Map from workloadNames to constant times,
  *                              in seconds, this scheduler uses to schedule each job.
  * @param perTaskThinkTimes     Map from workloadNames to times, in seconds,
  *                              this scheduler uses to schedule each task that is assigned to
  *                              a scheduler of that name.
  * @param numMachineToBlackList a positive number representing how many
  *                              machines (chosen randomly) this scheduler should ignore when
  *                              making scheduling decisions.
  */
abstract class Scheduler(val name: String,
                         constantThinkTimes: Map[String, Double],
                         perTaskThinkTimes: Map[String, Double],
                         numMachinesToBlackList: Double) {
  assert(constantThinkTimes.size == perTaskThinkTimes.size)
  assert(numMachinesToBlackList >= 0)
  var pendingQueue = new collection.mutable.Queue[Job]
  // This gets set when this scheduler is added to a Simulator.
  // TODO(andyk): eliminate this pointer and make the scheduler
  //              more functional.
  // TODO(andyk): Clean up these <subclass>Simulator classes
  //              by templatizing the Scheduler class and having only
  //              one simulator of the correct type, instead of one
  //              simulator for each of the parent and child classes.
  var simulator: ClusterSimulator = null
  var scheduling: Boolean = false

  // Job transaction stat counters.
  var numSuccessfulTransactions: Int = 0
  var numFailedTransactions: Int = 0
  var numRetriedTransactions: Int = 0
  var dailySuccessTransactions = HashMap[Int, Int]()
  var dailyFailedTransactions = HashMap[Int, Int]()
  var numJobsTimedOutScheduling: Int = 0
  // Task transaction stat counters.
  var numSuccessfulTaskTransactions: Int = 0
  var numFailedTaskTransactions: Int = 0
  var numNoResourcesFoundSchedulingAttempts: Int = 0
  // When trying to place a task, count the number of machines we look at
  // that the task doesn't fit on. This is a sort of wasted work that
  // causes the simulation to go slow.
  var failedFindVictimAttempts: Int = 0
  // Keep a cache of candidate pools around, indexed by their length
  // to avoid the overhead of the Array.range call in our inner scheduling
  // loop.
  val candidatePoolCache = HashMap[Int, IndexedSeq[Int]]()
  var totalUsefulTimeScheduling = 0.0 // in seconds
  var totalWastedTimeScheduling = 0.0 // in seconds
  var totalWastedTimeSchedulingPowering = 0.0 // in seconds
  var firstAttemptUsefulTimeScheduling = 0.0 // in seconds
  var firstAttemptWastedTimeScheduling = 0.0 // in seconds
  var dailyUsefulTimeScheduling = HashMap[Int, Double]()
  var dailyWastedTimeScheduling = HashMap[Int, Double]()
  var dailyWastedTimeSchedulingPowering = HashMap[Int, Double]()

  // Also track the time, in seconds, spent scheduling broken out by
  // workload type. Note that all Schedulers (even, e.g., SinglePath
  // schedulers) can handle jobs from multiple workload generators.
  var perWorkloadUsefulTimeScheduling = HashMap[String, Double]()
  var perWorkloadWastedTimeScheduling = HashMap[String, Double]()
  var perWorkloadWastedTimeSchedulingPowering = HashMap[String, Double]()
  val randomNumberGenerator = new util.Random(Seed())
  //var pastJobs = new mutable.LinkedHashMap[Long, Tuple3[Double, Job, Boolean]]()
  var pastJobs = new mutable.LinkedHashMap[Long, Tuple2[Double, Job]]()
  val numMachinesBlackList = numMachinesToBlackList

  override
  def toString = {
    name
  }

  def checkRegistered = {
    assert(simulator != null, "You must assign a simulator to a " +
      "Scheduler before you can use it.")
  }


  // Add a job to this scheduler's job queue.
  def addJob(job: Job): Unit = {
    // Make sure the perWorkloadTimeSchuduling Map has a key for this job's
    // workload type, so that we still print something for statistics about
    // that workload type for this scheduler, even if this scheduler never
    // actually gets a chance to schedule a job of that type.
    perWorkloadUsefulTimeScheduling(job.workloadName) =
      perWorkloadUsefulTimeScheduling.getOrElse(job.workloadName, 0.0)
    perWorkloadWastedTimeScheduling(job.workloadName) =
      perWorkloadWastedTimeScheduling.getOrElse(job.workloadName, 0.0)
    job.lastEnqueued = simulator.currentTime
    //pastJobs.getOrElseUpdate(job.id, (simulator.currentTime, job.copy(), false))
    pastJobs.getOrElseUpdate(job.id, addPastJob(job))
  }

  def addPastJob(job: Job): Tuple2[Double, Job] = {
    val tuple = (simulator.currentTime, job)
    simulator.addCacheJob(tuple)
    tuple
  }

  /**
    * Creates and applies ClaimDeltas for all available resources in the
    * provided {@code cellState}. This is intended to leave no resources
    * free in cellState, thus it doesn't use minCpu or minMem because that
    * could lead to leaving fragmentation. I haven't thought through
    * very carefully if floating point math could cause a problem here.
    */
  def scheduleAllAvailable(cellState: CellState,
                           locked: Boolean): Seq[ClaimDelta] = {
    val claimDeltas = collection.mutable.ListBuffer[ClaimDelta]()
    for (mID <- 0 to cellState.numMachines - 1) {
      val cpusAvail = cellState.availableCpusPerMachine(mID)
      val memAvail = cellState.availableMemPerMachine(mID)
      if (cellState.isMachineOn(mID) && (cpusAvail > 0.0001 || memAvail > 0.0001)) {
        // Create and apply a claim delta.
        assert(mID >= 0 && mID < cellState.machineSeqNums.length)
        //TODO(andyk): Clean up semantics around taskDuration in ClaimDelta
        //             since we want to represent offered resources, not
        //             tasks with these deltas.
        val claimDelta = new ClaimDelta(this,
          mID,
          cellState.machineSeqNums(mID),
          -1.0,
          cpusAvail,
          memAvail)
        claimDelta.apply(cellState, locked)
        claimDeltas += claimDelta
      }
    }
    claimDeltas
  }

  /**
    * Given a job and a cellstate, find machines that the tasks of the
    * job will fit into, and allocate the resources on that machine to
    * those tasks, accounting those resoures to this scheduler, modifying
    * the provided cellstate (by calling apply() on the created deltas).
    *
    * Implements the following randomized first fit scheduling algorithm:
    * while(more machines in candidate pool and more tasks to schedule):
    * candidateMachine = random machine in pool
    * if(candidate machine can hold at least one of this jobs tasks):
    * create a delta assigning the task to that machine
    * else:
    * remove from candidate pool
    *
    * @param  machineBlackList an array of of machineIDs that should be
    *                          ignored when scheduling (i.e. tasks will
    *                          not be scheduled on any of them).
    * @return List of deltas, one per task, so that the transactions can
    *         be played on some other cellstate if desired.
    */
  def scheduleJob(job: Job,
                  cellState: CellState): Seq[ClaimDelta] = {
    assert(simulator != null)
    assert(cellState != null)
    assert(job.cpusPerTask >= 0.00001 && job.memPerTask >= 0.00001, "Job con cpu o ram muy pequeña")
    assert(job.cpusPerTask <= cellState.cpusPerMachine,
      "Looking for machine with %f cpus, but machines only have %f cpus."
        .format(job.cpusPerTask, cellState.cpusPerMachine))
    assert(job.memPerTask <= cellState.memPerMachine,
      "Looking for machine with %f mem, but machines only have %f mem."
        .format(job.memPerTask, cellState.memPerMachine))
    /*
        val claimDeltas = collection.mutable.ListBuffer[ClaimDelta]()

        // Cache candidate pools in this scheduler for performance improvements.
        var candidatePool =
          candidatePoolCache.getOrElseUpdate(cellState.numberOfMachinesOn,
            Array.range(0, cellState.numMachines))

        var numRemainingTasks = job.unscheduledTasks
        var remainingCandidates = math.max(0, cellState.numberOfMachinesOn - numMachinesToBlackList).toInt
        simulator.sorter.orderResources(cellState)
        while(numRemainingTasks > 0 && remainingCandidates > 0) {
          val pickResult = simulator.picker.pickResource(cellState, job, candidatePool, remainingCandidates);
          remainingCandidates = pickResult._3
          candidatePool = pickResult._4
          failedFindVictimAttempts += pickResult._2
          if(pickResult._1 > -1){
            val currMachID = pickResult._1
            assert(currMachID >= 0 && currMachID < cellState.machineSeqNums.length)
            val claimDelta = new ClaimDelta(this,
              currMachID,
              cellState.machineSeqNums(currMachID),
              if (cellState.machinesHeterogeneous && job.workloadName == "Batch") job.taskDuration * cellState.machinesPerformance(currMachID) else job.taskDuration,
              job.cpusPerTask,
              job.memPerTask,
              job = job)
            claimDelta.apply(cellState = cellState, locked = false)
            claimDeltas += claimDelta
            numRemainingTasks -= 1
          }
        }
        */
    return simulator.picker.schedule(cellState, job, this, simulator)
  }

  def cleanPastJobs(size: Int): Unit = {
    if (pastJobs.size > size)
      pastJobs = pastJobs.slice(pastJobs.size - (size + 1), pastJobs.size - 1)
  }

  def jobQueueSize: Long = pendingQueue.size

  def isMultiPath: Boolean =
    constantThinkTimes.values.toSet.size > 1 ||
      perTaskThinkTimes.values.toSet.size > 1

  def addDailyTimeScheduling(counter: HashMap[Int, Double],
                             timeScheduling: Double) = {
    val index: Int = math.floor(simulator.currentTime / 86400).toInt
    val currAmt: Double = counter.getOrElse(index, 0.0)
    counter(index) = currAmt + timeScheduling
  }

  def recordUsefulTimeScheduling(job: Job,
                                 timeScheduling: Double,
                                 isFirstSchedAttempt: Boolean): Unit = {
    assert(simulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
    // Scheduler level stats.
    totalUsefulTimeScheduling += timeScheduling
    addDailyTimeScheduling(dailyUsefulTimeScheduling, timeScheduling)
    if (isFirstSchedAttempt) {
      firstAttemptUsefulTimeScheduling += timeScheduling
    }
    simulator.log("Recorded %f seconds of %s useful think time, total now: %f."
      .format(timeScheduling, name, totalUsefulTimeScheduling))
    // Job/workload level stats.
    job.usefulTimeScheduling += timeScheduling
    simulator.log("Recorded %f seconds of job %s useful think time, total now: %f."
      .format(timeScheduling, job.id, simulator.workloads.filter(_.name == job.workloadName).head.totalJobUsefulThinkTimes))
    // Also track per-path (i.e., per workload) scheduling times
    perWorkloadUsefulTimeScheduling(job.workloadName) =
      perWorkloadUsefulTimeScheduling.getOrElse(job.workloadName, 0.0) +
        timeScheduling
  }

  def recordWastedTimeSchedulingPowering(job: Job,
                                         timeScheduling: Double): Unit = {
    assert(simulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
    // Scheduler level stats.
    totalWastedTimeSchedulingPowering += timeScheduling
    addDailyTimeScheduling(dailyWastedTimeSchedulingPowering, timeScheduling)
    simulator.log("Recorded %f seconds of %s wasted think time because of machines are turned off, total now: %f."
      .format(timeScheduling, name, totalWastedTimeSchedulingPowering))
    // Job/workload level stats.
    job.wastedTimeSchedulingPowering += timeScheduling
    simulator.log("Recorded %f seconds of job %s wasted think time because of machines are turned off, total now: %f."
      .format(timeScheduling, job.id, simulator.workloads.filter(_.name == job.workloadName).head.totalJobWastedThinkTimesPowering))
    // Also track per-path (i.e., per workload) scheduling times
    perWorkloadWastedTimeSchedulingPowering(job.workloadName) =
      perWorkloadWastedTimeSchedulingPowering.getOrElse(job.workloadName, 0.0) +
        timeScheduling
  }

  def recordWastedTimeScheduling(job: Job,
                                 timeScheduling: Double,
                                 isFirstSchedAttempt: Boolean): Unit = {
    assert(simulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
    // Scheduler level stats.
    totalWastedTimeScheduling += timeScheduling
    addDailyTimeScheduling(dailyWastedTimeScheduling, timeScheduling)
    if (isFirstSchedAttempt) {
      firstAttemptWastedTimeScheduling += timeScheduling
    }
    // Job/workload level stats.
    job.wastedTimeScheduling += timeScheduling
    // Also track per-path (i.e., per workload) scheduling times
    perWorkloadWastedTimeScheduling(job.workloadName) =
      perWorkloadWastedTimeScheduling.getOrElse(job.workloadName, 0.0) +
        timeScheduling
  }

  /**
    * Computes the time, in seconds, this scheduler requires to make
    * a scheduling decision for {@code job}.
    *
    * @param job the job to determine this schedulers think time for
    */
  def getThinkTime(job: Job): Double = {
    assert(constantThinkTimes.contains(job.workloadName))
    assert(perTaskThinkTimes.contains(job.workloadName))
    constantThinkTimes(job.workloadName) +
      perTaskThinkTimes(job.workloadName) * job.unscheduledTasks.toFloat
  }
}

class ClaimDelta(val scheduler: Scheduler,
                 val machineID: Int,
                 val machineSeqNum: Int,
                 val duration: Double,
                 val cpus: Double,
                 val mem: Double,
                 val job: Job = null) {
  /**
    * Claim {@code cpus} and {@code mem} from {@code cellState}.
    * Increments the sequenceNum of the machine with ID referenced
    * by machineID.
    */
  def apply(cellState: CellState, locked: Boolean): Unit = {
    this.synchronized {
      cellState.machineSeqNums(machineID) += 1
      cellState.assignResources(scheduler, machineID, cpus, mem, locked)
      if (!locked && job.timeStarted == 0.0) {
        assert(job != null, "Job nulo cuando aplica un claimDelta no locked")
        job.timeStarted = scheduler.simulator.currentTime
      }
    }
    // Mark that the machine has changed, used for testing for conflicts
    // when using optimistic concurrency.
  }

  def unApply(cellState: CellState, locked: Boolean = false): Unit = {
    this.synchronized {
      cellState.freeResources(scheduler, machineID, cpus, mem, locked)
    }
    if (!locked && (job.timeFinished == 0.0 || job.timeFinished < scheduler.simulator.currentTime)) {
      assert(job != null, "Job nulo cuando libera un claimDelta no locked")
      job.timeFinished = scheduler.simulator.currentTime
    }
  }
}

class CellStateDesc(val numMachines: Int,
                    val cpusPerMachine: Double,
                    val memPerMachine: Double,
                    val machinesHet: Boolean = false,
                    val machPerf: Array[Double],
                    val machSec: Array[Int],
                    val machEn: Array[Double]) {

}


class CellState(val numMachines: Int,
                val cpusPerMachine: Double,
                val memPerMachine: Double,
                val conflictMode: String,
                val transactionMode: String,
                val powerOnTime: Double = 30.0,
                val powerOffTime: Double = 10.0,
                val mustPopulateLoadFactor: Boolean = true,
                val machinesHet: Boolean = false,
                val machPerf: Array[Double] = null,
                val machSec: Array[Int] = null,
                val machEn: Array[Double] = null) {
  assert(conflictMode.equals("resource-fit") ||
    conflictMode.equals("sequence-numbers"),
    "conflictMode must be one of: {'resource-fit', 'sequence-numbers'}, " +
      "but it was %s.".format(conflictMode))
  assert(transactionMode.equals("all-or-nothing") ||
    transactionMode.equals("incremental"),
    "conflictMode must be one of: {'resource-fit', 'sequence-numbers'}, " +
      "but it was %s.".format(conflictMode))
  var simulator: ClusterSimulator = null
  // An array where value at position k is the total cpus that have been
  // allocated for machine k.
  val allocatedCpusPerMachine = new Array[Double](numMachines)
  val allocatedMemPerMachine = new Array[Double](numMachines)
  val machineSeqNums = new Array[Int](numMachines)
  // An array where index 0 is the highest loaded machine and n-1 is the lowest
  // loaded machine. Values are Machine IDs
  var machinesLoad = new Array[Int](numMachines)
  var machinesLoadFactor = populateMachinesLoadFactorMap()
  var machinesLoadOrdered = true;
  val machinesHeterogeneous = machinesHet
  //val machinesPerformance = Array.fill[Double](numMachines)(Random.nextDouble() * (1.5) + 0.5)
  val machinesPerformance = machPerf
  val machinesPerformanceOrdered = populateMachinesPerformance()
  //val machinesSecurity = Array.fill[Int](numMachines)(Random.nextInt(6))
  val machinesSecurity = machSec
  val machinesSecurityMap = populateMachinesSecurity()
  //val machinesEnergy = Array.fill[Double](numMachines)(Random.nextDouble() * (1.5) + 0.5)
  val machinesEnergy = machEn

  def populateMachinesLoadFactorMap(): HashMap[Int, Double] = {
    val map = HashMap[Int, Double]()
    if (mustPopulateLoadFactor) {
      for (a <- 0 to numMachines - 1) {
        map.put(a, 0.0)
      }
      machinesLoad = map.keySet.toArray
    }
    map
  }

  def populateMachinesPerformance(): Array[Int] = {
    val map = HashMap[Int, Double]()
    if (machinesHeterogeneous) {
      for (a <- 0 to numMachines - 1) {
        map.put(a, machinesPerformance(a))
      }
    }
    map.toSeq.sortBy(_._2).map(_._1).toArray
  }

  def populateMachinesSecurity(): HashMap[Int, ListBuffer[Int]] = {
    val map = HashMap.empty[Int, ListBuffer[Int]]
    for (mID <- 0 until numMachines) {
      val machines = map.getOrElseUpdate(machinesSecurity(mID), ListBuffer.empty[Int])
      machines += mID
    }
    map
  }

  // Map from scheduler name to number of cpus assigned to that scheduler.
  val occupiedCpus = HashMap[String, Double]()
  val occupiedMem = HashMap[String, Double]()
  // Map from scheduler name to number of cpus locked while that scheduler
  // makes scheduling decisions about them, i.e., while resource offers made
  // to that scheduler containing those amount of resources is pending.
  val lockedCpus = HashMap[String, Double]()
  val lockedMem = HashMap[String, Double]()

  // These used to be functions that sum the values of occupiedCpus,
  // occupiedMem, lockedCpus, and lockedMem, but its much faster to use
  // scalars that we update in assignResources and freeResources.
  var totalOccupiedCpus = 0.0
  var totalOccupiedMem = 0.0
  var totalLockedCpus = 0.0
  var totalLockedMem = 0.0

  // Efficiency vars

  //Estados: 0 Apagado, 1 encendido, 2 apagando, 3 encendiendo
  // array donde el índice es el número de la máquina y el valor una Lista con los tiempos donde se empieza el apagado/encendido. Con esto podemos, además de saber el número de apagados, saber la distribución temporal de los mismos.
  val powerOffs = new Array[Seq[Double]](numMachines);
  val powerOns = new Array[Seq[Double]](numMachines);
  // array donde el indice es el id de la maquina y 0-1 es apagado/encendido (por defecto todos encendidos)
  val machinePowerState = Array.fill[Int](numMachines)(1)

  def isMachineOn(machineID: Int): Boolean = {
    machinePowerState(machineID) == 1
  }

  def isMachineOff(machineID: Int): Boolean = {
    machinePowerState(machineID) == 0
  }

  var numberOfMachinesOn = numMachines
  var numberOfMachinesOff = 0
  var numberOfMachinesTurningOn = 0
  var numberOfMachinesTurningOff = 0

  def totalCpus = numMachines * cpusPerMachine

  def totalMem = numMachines * memPerMachine

  def onCpus = numberOfMachinesOn * cpusPerMachine

  def onMem = numberOfMachinesOn * memPerMachine

  def availableCpus = onCpus - (totalOccupiedCpus + totalLockedCpus)

  def availableMem = onMem - (totalOccupiedMem + totalLockedMem)

  def numMachinesOccupied: Int = {
    // FIXME: Teniendo en cuenta que una task siempre va a necesitar tanto CPU como RAM, con contar el número de máquinas que tienen cpus ocupados es suficiente
    allocatedCpusPerMachine.count(_ > 0.0)
  }

  def powerOnMachine(machineID: Int) = {
    if (machinePowerState(machineID) == 0 || machinePowerState(machineID) == 2) {
      machineSeqNums(machineID) += 1
      if (machinePowerState(machineID) == 0)
        numberOfMachinesOff -= 1
      else if (machinePowerState(machineID) == 2)
        numberOfMachinesTurningOff -= 1
      machinePowerState(machineID) = 3
      numberOfMachinesTurningOn += 1
      if (powerOns(machineID) == null)
        powerOns(machineID) = mutable.Seq[Double]()
      powerOns(machineID) = powerOns(machineID) :+ simulator.currentTime
      simulator.afterDelay(powerOnTime) {
        numberOfMachinesTurningOn -= 1
        machinePowerState(machineID) = 1
        numberOfMachinesOn += 1
      }
      val cpuLoad = simulator.cellState.allocatedCpusPerMachine(machineID) / (simulator.cellState.cpusPerMachine)
      val memLoad = simulator.cellState.allocatedMemPerMachine(machineID) / (simulator.cellState.memPerMachine)
      simulator.sorter.updateLoadFactor(machineID, this)
    }
    assert(numberOfMachinesOff + numberOfMachinesOn + numberOfMachinesTurningOn + numberOfMachinesTurningOff == numMachines, "El número de máquinas no es correcto tras encender una máquina")
  }

  def powerOffMachine(machineID: Int) = {
    assert(allocatedCpusPerMachine(machineID) <= 0.00001 && allocatedMemPerMachine(machineID) <= 0.00001, ("Intentando apagar una máquina en uso con ID %d ").format(machineID))
    machineSeqNums(machineID) += 1
    if (machinePowerState(machineID) == 1 || machinePowerState(machineID) == 3) {
      if (machinePowerState(machineID) == 1)
        numberOfMachinesOn -= 1
      else if (machinePowerState(machineID) == 3)
        numberOfMachinesTurningOn -= 1
      machinePowerState(machineID) = 2
      numberOfMachinesTurningOff += 1
      if (powerOffs(machineID) == null)
        powerOffs(machineID) = mutable.Seq[Double]()
      powerOffs(machineID) = powerOffs(machineID) :+ simulator.currentTime
      simulator.afterDelay(powerOffTime) {
        numberOfMachinesTurningOff -= 1
        machinePowerState(machineID) = 0
        numberOfMachinesOff += 1
      }
    }
    simulator.sorter.updateLoadFactor(machineID, this)
    assert(numberOfMachinesOff + numberOfMachinesOn + numberOfMachinesTurningOn + numberOfMachinesTurningOff == numMachines, "El número de máquinas no es correcto tras apagar una máquina")
  }

  // Convenience methods to see how many cpus/mem are available on a machine.
  def availableCpusPerMachine(machineID: Int) = {
    assert(machineID <= allocatedCpusPerMachine.length - 1,
      "There is no machine with ID %d.".format(machineID))
    cpusPerMachine - allocatedCpusPerMachine(machineID)
  }

  def availableMemPerMachine(machineID: Int) = {
    assert(machineID <= allocatedMemPerMachine.length - 1,
      "There is no machine with ID %d.".format(machineID))
    memPerMachine - allocatedMemPerMachine(machineID)
  }

  /**
    * Allocate resources on a machine to a scheduler.
    *
    * @param locked Mark these resources as being pessimistically locked
    *               (i.e. while they are offered as part of a Mesos
    *               resource-offer).
    */
  def assignResources(scheduler: Scheduler,
                      machineID: Int,
                      cpus: Double,
                      mem: Double,
                      locked: Boolean) = {
    // Track the resources used by this scheduler.
    // assert(cpus <= availableCpus + 0.000001,
    //        ("Attempting to assign more CPUs (%f) than " +
    //         "are currently available in CellState (%f).")
    //        .format(cpus, availableCpus))
    // assert(mem <= availableMem + 0.000001,
    //        ("Attempting to assign more mem (%f) than " +
    //         "is currently available in CellState (%f).")
    //        .format(mem, availableMem))
    assert(isMachineOn(machineID), "Assigning resources to a powered off machine")
    if (locked) {
      lockedCpus(scheduler.name) = lockedCpus.getOrElse(scheduler.name, 0.0) + cpus
      lockedMem(scheduler.name) = lockedMem.getOrElse(scheduler.name, 0.0) + mem
      assert(lockedCpus(scheduler.name) <= totalCpus + 0.000001)
      assert(lockedMem(scheduler.name) <= totalMem + 0.000001)
      totalLockedCpus += cpus
      totalLockedMem += mem
    } else {
      occupiedCpus(scheduler.name) = occupiedCpus.getOrElse(scheduler.name, 0.0) + cpus
      occupiedMem(scheduler.name) = occupiedMem.getOrElse(scheduler.name, 0.0) + mem
      assert(occupiedCpus(scheduler.name) <= totalCpus + 0.000001)
      assert(occupiedMem(scheduler.name) <= totalMem + 0.000001)
      totalOccupiedCpus += cpus
      totalOccupiedMem += mem
    }
    // Also track the per machine resources available.
    assert(availableCpusPerMachine(machineID) >= cpus,
      ("Scheduler %s (%d) tried to claim %f cpus on machine %d in cell " +
        "state %d, but it only has %f unallocated cpus right now.")
        .format(scheduler.name,
          scheduler.hashCode,
          cpus,
          machineID,
          hashCode(),
          availableCpusPerMachine(machineID)))
    assert(availableMemPerMachine(machineID) >= mem,
      ("Scheduler %s (%d) tried to claim %f mem on machine %d in cell " +
        "state %d, but it only has %f mem unallocated right now.")
        .format(scheduler.name,
          scheduler.hashCode,
          mem,
          machineID,
          hashCode(),
          availableMemPerMachine(machineID)))
    allocatedCpusPerMachine(machineID) += cpus
    allocatedMemPerMachine(machineID) += mem
    if (!locked) {
      if (simulator == null) {
        //Omega, Mesos
        scheduler.simulator.sorter.updateLoadFactor(machineID, this)
      }
      else {
        //MOnolithic
        simulator.sorter.updateLoadFactor(machineID, this)
      }
    }
  }

  // Release the specified number of resources used by this scheduler.
  def freeResources(scheduler: Scheduler,
                    machineID: Int,
                    cpus: Double,
                    mem: Double,
                    locked: Boolean) = {
    assert(isMachineOn(machineID), "Freeing resources in a powered off machine")
    if (locked) {
      assert(lockedCpus.contains(scheduler.name))
      val safeToFreeCpus: Boolean = lockedCpus(scheduler.name) >= (cpus - 0.001)
      assert(safeToFreeCpus,
        "%s tried to free %f cpus, but was only occupying %f."
          .format(scheduler.name, cpus, lockedCpus(scheduler.name)))
      assert(lockedMem.contains(scheduler.name))
      val safeToFreeMem: Boolean = lockedMem(scheduler.name) >= (mem - 0.001)
      assert(safeToFreeMem,
        "%s tried to free %f mem, but was only occupying %f."
          .format(scheduler.name, mem, lockedMem(scheduler.name)))
      lockedCpus(scheduler.name) = lockedCpus(scheduler.name) - cpus
      lockedMem(scheduler.name) = lockedMem(scheduler.name) - mem
      totalLockedCpus -= cpus
      totalLockedMem -= mem
    } else {
      assert(occupiedCpus.contains(scheduler.name))
      val safeToFreeCpus: Boolean = occupiedCpus(scheduler.name) >= (cpus - 0.001)
      assert(safeToFreeCpus,
        "%s tried to free %f cpus, but was only occupying %f."
          .format(scheduler.name, cpus, occupiedCpus(scheduler.name)))
      assert(occupiedMem.contains(scheduler.name))
      val safeToFreeMem: Boolean = occupiedMem(scheduler.name) >= (mem - 0.001)
      assert(safeToFreeMem,
        "%s tried to free %f mem, but was only occupying %f."
          .format(scheduler.name, mem, occupiedMem(scheduler.name)))
      occupiedCpus(scheduler.name) = occupiedCpus(scheduler.name) - cpus
      occupiedMem(scheduler.name) = occupiedMem(scheduler.name) - mem
      totalOccupiedCpus -= cpus
      totalOccupiedMem -= mem
    }
    // Also track the per machine resources available.
    assert(availableCpusPerMachine(machineID) + cpus <=
      cpusPerMachine + 0.000001)
    assert(availableMemPerMachine(machineID) + mem <=
      memPerMachine + 0.000001)
    allocatedCpusPerMachine(machineID) -= cpus
    allocatedMemPerMachine(machineID) -= mem
    //TODO : Metemos la llamada a apagar máquinas sólo a las que han sido usadas, no a las locked
    if (!locked) {
      simulator.powerOff.powerOff(this, machineID)
      simulator.sorter.updateLoadFactor(machineID, this)
    }
  }

  /**
    * Return a copy of this cell state in its current state.
    */
  def copy: CellState = {
    val newCellState = new CellState(numMachines,
      cpusPerMachine,
      memPerMachine,
      conflictMode,
      transactionMode,
      mustPopulateLoadFactor = false,
      machinesHet = this.machinesHet,
      machEn = machinesEnergy,
      machSec = machinesSecurity,
      machPerf = machinesPerformance)
    Array.copy(src = allocatedCpusPerMachine,
      srcPos = 0,
      dest = newCellState.allocatedCpusPerMachine,
      destPos = 0,
      length = numMachines)
    Array.copy(src = allocatedMemPerMachine,
      srcPos = 0,
      dest = newCellState.allocatedMemPerMachine,
      destPos = 0,
      length = numMachines)
    Array.copy(src = machineSeqNums,
      srcPos = 0,
      dest = newCellState.machineSeqNums,
      destPos = 0,
      length = numMachines)
    Array.copy(src = machinesLoad,
      srcPos = 0,
      dest = newCellState.machinesLoad,
      destPos = 0,
      length = numMachines)
    Array.copy(src = machinePowerState,
      srcPos = 0,
      dest = newCellState.machinePowerState,
      destPos = 0,
      length = numMachines)
    newCellState.machinesLoadFactor = HashMap[Int, Double]()
    newCellState.machinesLoadFactor ++= machinesLoadFactor
    newCellState.machinesLoadOrdered = machinesLoadOrdered
    newCellState.occupiedCpus ++= occupiedCpus
    newCellState.occupiedMem ++= occupiedMem
    newCellState.lockedCpus ++= lockedCpus
    newCellState.lockedMem ++= lockedMem
    newCellState.totalOccupiedCpus = totalOccupiedCpus
    newCellState.totalOccupiedMem = totalOccupiedMem
    newCellState.totalLockedCpus = totalLockedCpus
    newCellState.totalLockedMem = totalLockedMem
    newCellState.numberOfMachinesOff = numberOfMachinesOff
    newCellState.numberOfMachinesOn = numberOfMachinesOn
    newCellState.numberOfMachinesTurningOff = numberOfMachinesTurningOff
    newCellState.numberOfMachinesTurningOn = numberOfMachinesTurningOn
    //newCellState.simulator = simulator
    newCellState
  }

  case class CommitResult(committedDeltas: Seq[ClaimDelta],
                          conflictedDeltas: Seq[ClaimDelta])

  /**
    * Attempt to play the list of deltas, return any that conflicted.
    */
  def commit(deltas: Seq[ClaimDelta],
             scheduleEndEvent: Boolean = false): CommitResult = {
    var rollback = false // Track if we need to rollback changes.
    var appliedDeltas = collection.mutable.ListBuffer[ClaimDelta]()
    var conflictDeltas = collection.mutable.ListBuffer[ClaimDelta]()

    def commitNonConflictingDeltas: Unit = {
      deltas.foreach(d => {
        if (causesConflict(d)) {
          simulator.log("delta (%s mach-%d seqNum-%d) caused a conflict."
            .format(d.scheduler, d.machineID, d.machineSeqNum))
          conflictDeltas += d
          if (transactionMode == "all-or-nothing") {
            rollback = true
            return
          } else if (transactionMode == "incremental") {

          } else {
            sys.error("Invalid transactionMode.")
          }
        } else {
          d.apply(cellState = this, locked = false)
          appliedDeltas += d
        }
      })
    }

    commitNonConflictingDeltas
    // Rollback if necessary.
    if (rollback) {
      simulator.log("Rolling back %d deltas.".format(appliedDeltas.length))
      appliedDeltas.foreach(d => {
        d.unApply(cellState = this, locked = false)
        conflictDeltas += d
        appliedDeltas -= d
      })
    }

    if (scheduleEndEvent) {
      scheduleEndEvents(appliedDeltas)
    }
    new CommitResult(appliedDeltas, conflictDeltas)
  }

  // Create an end event for each delta provided. The end event will
  // free the resources used by the task represented by the ClaimDelta.
  def scheduleEndEvents(claimDeltas: Seq[ClaimDelta]) {
    assert(simulator != null, "Simulator must be non-null in CellState.")
    claimDeltas.foreach(appliedDelta => {
      simulator.afterDelay(appliedDelta.duration) {
        appliedDelta.unApply(simulator.cellState)
        simulator.log(("A task started by scheduler %s finished. " +
          "Freeing %f cpus, %f mem. Available: %f cpus, %f mem.")
          .format(appliedDelta.scheduler.name,
            appliedDelta.cpus,
            appliedDelta.mem,
            availableCpus,
            availableMem))
      }
    })
  }

  /**
    * Tests if this delta causes a transaction conflict.
    * Different test scheme is used depending on conflictMode.
    */
  def causesConflict(delta: ClaimDelta): Boolean = {
    conflictMode match {
      case "sequence-numbers" => {
        // Use machine sequence numbers to test for conflicts.
        if (!simulator.cellState.isMachineOn(delta.machineID) || delta.machineSeqNum != machineSeqNums(delta.machineID)) {
          simulator.log("Sequence-number conflict occurred " +
            "(sched-%s, mach-%d, seq-num-%d, cpus-%f, mem-%f)."
              .format(delta.scheduler,
                delta.machineID,
                delta.machineSeqNum,
                delta.cpus,
                delta.mem))
          true
        } else {
          false
        }
      }
      case "resource-fit" => {
        // Check if the machine is currently short of resources,
        // regardless of whether sequence nums have changed.
        if (!simulator.cellState.isMachineOn(delta.machineID) || availableCpusPerMachine(delta.machineID) < delta.cpus ||
          availableMemPerMachine(delta.machineID) < delta.mem) {
          simulator.log("Resource-aware conflict occurred " +
            "(sched-%s, mach-%d, cpus-%f, mem-%f)."
              .format(delta.scheduler,
                delta.machineID,
                delta.cpus,
                delta.mem))
          true
        } else {
          false
        }
      }
      case _ => {
        sys.error("Unrecognized conflictMode %s.".format(conflictMode))
        true // Should never be reached.
      }
    }
  }
}

/**
  * @param submitted the time the job was submitted in seconds
  * @param cpus      number of cpus required by this job
  * @param ram       amount of ram, in GB, required by this job
  */
case class Job(id: Long,
               submitted: Double,
               numTasks: Int,
               var taskDuration: Double,
               workloadName: String,
               cpusPerTask: Double,
               memPerTask: Double,
               heterogeneousTasks: Boolean = false) {
  var unscheduledTasks: Int = numTasks
  // Time, in seconds, this job spent waiting in its scheduler's queue
  var timeInQueueTillFirstScheduled: Double = 0.0
  var timeInQueueTillFullyScheduled: Double = 0.0
  // Timestamp last inserted into scheduler's queue, used to compute timeInQueue.
  var lastEnqueued: Double = 0.0
  var lastSchedulingStartTime: Double = 0.0
  // Number of scheduling attempts for this job (regardless of how many
  // tasks were successfully scheduled in each attempt).
  var numSchedulingAttempts: Long = 0
  // Number of "task scheduling attempts". I.e. each time a scheduling
  // attempt is made for this job, we increase this counter by the number
  // of tasks that still need to be be scheduled (at the beginning of the
  // scheduling attempt). This is useful for analyzing the impact of no-fit
  // events on the scheduler busytime metric.
  var numTaskSchedulingAttempts: Long = 0
  var usefulTimeScheduling: Double = 0.0
  var wastedTimeScheduling: Double = 0.0
  var wastedTimeSchedulingPowering: Double = 0.0
  var turnOnRequests: Seq[Double] = Seq[Double]()
  val tasksPerformance = if (heterogeneousTasks) Array.fill[Double](numTasks)(Random.nextDouble() * (1.5) + 0.5) else Array.fill[Double](numTasks)(1)
  val security = Random.nextInt(4)
  var makespanLogArray = new ListBuffer[ListBuffer[Double]]()

  var timeStarted: Double = 0.0
  var timeFinished: Double = 0.0

  //TODO: remove this static total simulation time
  def makespan = if (timeFinished == 0.0) 86400.0 * 7 - timeStarted else timeFinished - timeStarted

  def cpusStillNeeded: Double = cpusPerTask * unscheduledTasks

  def memStillNeeded: Double = memPerTask * unscheduledTasks

  // Calculate the maximum number of this jobs tasks that can fit into
  // the specified resources
  def numTasksToSchedule(cpusAvail: Double, memAvail: Double): Int = {
    if (cpusAvail == 0.0 || memAvail == 0.0) {
      return 0
    } else {
      val cpusChoppedToTaskSize = cpusAvail - (cpusAvail % cpusPerTask)
      val memChoppedToTaskSize = memAvail - (memAvail % memPerTask)
      val maxTasksThatWillFitByCpu = math.round(cpusChoppedToTaskSize / cpusPerTask)
      val maxTasksThatWillFitByMem = math.round(memChoppedToTaskSize / memPerTask)
      val maxTasksThatWillFit = math.min(maxTasksThatWillFitByCpu,
        maxTasksThatWillFitByMem)
      math.min(unscheduledTasks, maxTasksThatWillFit.toInt)
    }
  }

  def updateTimeInQueueStats(currentTime: Double) = {
    // Every time part of this job is partially scheduled, add to
    // the counter tracking how long it spends in the queue till
    // its final task is scheduled.
    timeInQueueTillFullyScheduled += currentTime - lastEnqueued
    // If this is the first scheduling done for this job, then make a note
    // about how long the job waited in the queue for this first scheduling.
    if (numSchedulingAttempts == 0) {
      timeInQueueTillFirstScheduled += currentTime - lastEnqueued
    }
  }
}

/**
  * A class that holds a list of jobs, each of which is used to record
  * statistics during a run of the simulator.
  *
  * Keep track of avgJobInterarrivalTime for easy reference later when
  * ExperimentRunner wants to record it in experiment result protos.
  */
class Workload(val name: String,
               private val jobs: ListBuffer[Job] = ListBuffer()) {
  def getJobs: Seq[Job] = jobs.toSeq

  def addJob(job: Job) = {
    assert(job.workloadName == name)
    jobs.append(job)
  }

  def addJobs(jobs: Seq[Job]) = jobs.foreach(addJob)

  def numJobs: Int = jobs.length

  def cpus: Double = jobs.map(j => {
    j.numTasks * j.cpusPerTask
  }).sum

  def mem: Double = jobs.map(j => {
    j.numTasks * j.memPerTask
  }).sum

  // Generate a new workload that has a copy of the jobs that
  // this workload has.
  def copy: Workload = {
    val newWorkload = new Workload(name)
    jobs.foreach(job => {
      newWorkload.addJob(job.copy())
    })
    newWorkload
  }

  def totalJobUsefulThinkTimes: Double = jobs.map(_.usefulTimeScheduling).sum

  def totalJobWastedThinkTimes: Double = jobs.map(_.wastedTimeScheduling).sum

  def totalJobWastedThinkTimesPowering: Double = jobs.map(_.wastedTimeSchedulingPowering).sum

  def jobInterArrivalTimesArray: Array[Double] = {
    val submittedTimesArray = new Array[Double](jobs.length)
    jobs.map(_.submitted).copyToArray(submittedTimesArray)
    util.Sorting.quickSort(submittedTimesArray)
    submittedTimesArray
  }

  def avgJobInterarrivalTime: Double = {
    val submittedTimesArray = new Array[Double](jobs.length)
    jobs.map(_.submitted).copyToArray(submittedTimesArray)
    util.Sorting.quickSort(submittedTimesArray)
    // pass along (running avg, count)
    var sumInterarrivalTime = 0.0
    for (i <- 1 to submittedTimesArray.length - 1) {
      sumInterarrivalTime += submittedTimesArray(i) - submittedTimesArray(i - 1)
    }
    sumInterarrivalTime / submittedTimesArray.length
  }

  def jobUsefulThinkTimesPercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val scheduledJobs = jobs.filter(_.numSchedulingAttempts > 0).toList
    // println("Setting up thinkTimesArray of length " +
    //         scheduledJobs.length)
    if (scheduledJobs.length > 0) {
      val thinkTimesArray = new Array[Double](scheduledJobs.length)
      scheduledJobs.map(job => {
        job.usefulTimeScheduling
      }).copyToArray(thinkTimesArray)
      util.Sorting.quickSort(thinkTimesArray)
      //println(thinkTimesArray.deep.toSeq.mkString("-*-"))
      // println("Looking up think time percentile value at position " +
      //         ((thinkTimesArray.length-1) * percentile).toInt)
      thinkTimesArray(((thinkTimesArray.length - 1) * percentile).toInt)
    } else {
      -1.0
    }
  }

  def avgJobQueueTimeTillFirstScheduled: Double = {
    // println("Computing avgJobQueueTimeTillFirstScheduled.")
    val scheduledJobs = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduledJobs.length > 0) {
      val queueTimes = scheduledJobs.map(_.timeInQueueTillFirstScheduled).sum
      queueTimes / scheduledJobs.length
    } else {
      -1.0
    }
  }

  def avgJobQueueTimeTillFullyScheduled: Double = {
    // println("Computing avgJobQueueTimeTillFullyScheduled.")
    val scheduledJobs = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduledJobs.length > 0) {
      val queueTimes = scheduledJobs.map(_.timeInQueueTillFullyScheduled).sum
      queueTimes / scheduledJobs.length
    } else {
      -1.0
    }
  }

  def jobQueueTimeTillFirstScheduledPercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val scheduled = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduled.length > 0) {
      val queueTimesArray = new Array[Double](scheduled.length)
      scheduled.map(_.timeInQueueTillFirstScheduled)
        .copyToArray(queueTimesArray)
      util.Sorting.quickSort(queueTimesArray)
      val result =
        queueTimesArray(((queueTimesArray.length - 1) * percentile).toInt)
      println(("Looking up job queue time till first scheduled " +
        "percentile value at position %d of %d: %f.")
        .format(((queueTimesArray.length) * percentile).toInt,
          queueTimesArray.length,
          result))
      result
    } else {
      -1.0
    }
  }

  def avgJobMakespan: Double = {
    // println("Computing avgJobQueueTimeTillFullyScheduled.")
    val scheduledJobs = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduledJobs.length > 0) {
      val makespans = scheduledJobs.map(_.makespan).sum
      makespans / scheduledJobs.length
    } else {
      -1.0
    }
  }


  def jobMakespanPercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val scheduled = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduled.length > 0) {
      val makespansArray = new Array[Double](scheduled.length)
      scheduled.map(_.makespan)
        .copyToArray(makespansArray)
      util.Sorting.quickSort(makespansArray)
      val result =
        makespansArray(((makespansArray.length - 1) * percentile).toInt)
      println(("Looking up job queue time till first scheduled " +
        "percentile value at position %d of %d: %f.")
        .format(((makespansArray.length) * percentile).toInt,
          makespansArray.length,
          result))
      result
    } else {
      -1.0
    }
  }

  def jobQueueTimeTillFullyScheduledPercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    val scheduled = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduled.length > 0) {
      val queueTimesArray = new Array[Double](scheduled.length)
      scheduled.map(_.timeInQueueTillFullyScheduled)
        .copyToArray(queueTimesArray)
      util.Sorting.quickSort(queueTimesArray)
      val result = queueTimesArray(((queueTimesArray.length - 1) * percentile).toInt)
      println(("Looking up job queue time till fully scheduled " +
        "percentile value at position %d of %d: %f.")
        .format(((queueTimesArray.length) * percentile).toInt,
          queueTimesArray.length,
          result))
      result
    } else {
      -1.0
    }
  }

  def numSchedulingAttemptsPercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    println("largest 200 job scheduling attempt counts: " +
      jobs.map(_.numSchedulingAttempts)
        .sorted
        .takeRight(200)
        .mkString(","))
    val scheduled = jobs.filter(_.numSchedulingAttempts > 0)
    if (scheduled.length > 0) {
      val schedulingAttemptsArray = new Array[Long](scheduled.length)
      scheduled.map(_.numSchedulingAttempts).copyToArray(schedulingAttemptsArray)
      util.Sorting.quickSort(schedulingAttemptsArray)
      val result = schedulingAttemptsArray(((schedulingAttemptsArray.length - 1) * 0.9).toInt)
      println(("Looking up num job scheduling attempts " +
        "percentile value at position %d of %d: %d.")
        .format(((schedulingAttemptsArray.length) * percentile).toInt,
          schedulingAttemptsArray.length,
          result))
      result
    } else {
      -1.0
    }
  }

  def numTaskSchedulingAttemptsPercentile(percentile: Double): Double = {
    assert(percentile <= 1.0 && percentile >= 0)
    println("largest 200 task scheduling attempt counts: " +
      jobs.map(_.numTaskSchedulingAttempts)
        .sorted
        .takeRight(200)
        .mkString(","))
    val scheduled = jobs.filter(_.numTaskSchedulingAttempts > 0)
    if (scheduled.length > 0) {
      val taskSchedulingAttemptsArray = new Array[Long](scheduled.length)
      scheduled.map(_.numTaskSchedulingAttempts).copyToArray(taskSchedulingAttemptsArray)
      util.Sorting.quickSort(taskSchedulingAttemptsArray)
      val result = taskSchedulingAttemptsArray(((taskSchedulingAttemptsArray.length - 1) * 0.9).toInt)
      println(("Looking up num task scheduling attempts " +
        "percentile value at position %d of %d: %d.")
        .format(((taskSchedulingAttemptsArray.length) * percentile).toInt,
          taskSchedulingAttemptsArray.length,
          result))
      result
    } else {
      -1.0
    }
  }
}

case class WorkloadDesc(cell: String,
                        assignmentPolicy: String,
                        // getJob(0) is called 
                        workloadGenerators: List[WorkloadGenerator],
                        cellStateDesc: CellStateDesc,
                        prefillWorkloadGenerators: List[WorkloadGenerator] =
                        List[WorkloadGenerator]()) {
  assert(!cell.contains(" "), "Cell names cannot have spaces in them.")
  assert(!assignmentPolicy.contains(" "),
    "Assignment policies cannot have spaces in them.")
  assert(prefillWorkloadGenerators.length ==
    prefillWorkloadGenerators.map(_.workloadName).toSet.size)
}

object UniqueIDGenerator {
  var counter = 0

  def getUniqueID(): Int = {
    counter += 1
    counter
  }
}

/**
  * A threadsafe Workload factory.
  */
trait WorkloadGenerator {
  val workloadName: String

  /**
    * Generate a workload using this workloadGenerator's parameters
    * The timestamps of the jobs in this workload should fall in the range
    * [0, timeWindow].
    *
    * @param maxCpus                       The maximum number of cpus that the workload returned
    *                                      can contain.
    * @param maxMem                        The maximum amount of mem that the workload returned
    *                                      can contain.
    * @param updatedAvgJobInterarrivalTime if non-None, then the interarrival
    *                                      times of jobs in the workload returned
    *                                      will be approximately this value.
    */
  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload
}

/**
  * Generates jobs at a uniform rate, of a uniform size.
  */
class UniformWorkloadGenerator(val workloadName: String,
                               initJobInterarrivalTime: Double,
                               tasksPerJob: Int,
                               jobDuration: Double,
                               cpusPerTask: Double,
                               memPerTask: Double,
                               heterogeneousTasks: Boolean = false)
  extends WorkloadGenerator {
  def newJob(submissionTime: Double): Job = {
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      tasksPerJob,
      jobDuration,
      workloadName,
      cpusPerTask,
      memPerTask,
      heterogeneousTasks)
  }

  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(timeWindow >= 0)
    val jobInterarrivalTime = updatedAvgJobInterarrivalTime
      .getOrElse(initJobInterarrivalTime)
    val workload = new Workload(workloadName)
    var nextJobSubmissionTime = 0.0
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      nextJobSubmissionTime += jobInterarrivalTime
    }
    workload
  }
}

/**
  * A thread-safe Workload factory. Generates workloads with jobs that have
  * interarrival rates, numTasks, and lengths sampled from exponential
  * distributions. Assumes that all tasks in a job are identical
  * (and so no per-task data is required).
  *
  * @param workloadName               the name that will be assigned to Workloads produced by
  *                                   this factory, and to the tasks they contain.
  * @param initAvgJobInterarrivalTime initial average inter-arrival time in
  *                                   seconds. This can be overriden by passing
  *                                   a non None value for
  *                                   updatedAvgJobInterarrivalTime to
  *                                   newWorkload().
  */
class ExpExpExpWorkloadGenerator(val workloadName: String,
                                 initAvgJobInterarrivalTime: Double,
                                 avgTasksPerJob: Double,
                                 avgJobDuration: Double,
                                 avgCpusPerTask: Double,
                                 avgMemPerTask: Double,
                                 heterogeneousTasks: Boolean = false)
  extends WorkloadGenerator {
  val numTasksGenerator =
    new ExponentialDistributionImpl(avgTasksPerJob.toFloat)
  val durationGenerator = new ExponentialDistributionImpl(avgJobDuration)

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = durationGenerator.sample()
    while (dur <= 0.0)
      dur = durationGenerator.sample()
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      // Use ceil to avoid jobs with 0 tasks.
      math.ceil(numTasksGenerator.sample().toFloat).toInt,
      dur,
      workloadName,
      avgCpusPerTask,
      avgMemPerTask,
      heterogeneousTasks)
  }

  /**
    * Synchronized so that Experiments, which can share this WorkloadGenerator,
    * can safely call newWorkload concurrently.
    */
  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(maxCpus == None)
    assert(maxMem == None)
    assert(timeWindow >= 0)
    // Create the job-interarrival-time number generator using the
    // parameter passed in, if any, else use the default parameter.
    val avgJobInterarrivalTime =
    updatedAvgJobInterarrivalTime.getOrElse(initAvgJobInterarrivalTime)
    var interarrivalTimeGenerator =
      new ExponentialDistributionImpl(avgJobInterarrivalTime)
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    var nextJobSubmissionTime = interarrivalTimeGenerator.sample()
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      nextJobSubmissionTime += interarrivalTimeGenerator.sample()
    }
    workload
  }
}

/**
  * An object for building and caching emperical distributions
  * from traces. Caches them because they are expensive to build
  * (require reading from disk) and are re-used between different
  * experiments.
  */
object DistCache {
  val distributions =
    collection.mutable.Map[(String, String), Array[Double]]()

  def getDistribution(workloadName: String,
                      traceFileName: String): Array[Double] = {
    distributions.getOrElseUpdate((workloadName, traceFileName),
      buildDist(workloadName, traceFileName))
  }

  def buildDist(workloadName: String, traceFileName: String): Array[Double] = {
    assert(workloadName.equals("Batch") || workloadName.equals("Service"))
    val dataPoints = new collection.mutable.ListBuffer[Double]()
    val refDistribution = new Array[Double](1001)

    println(("Reading tracefile %s and building distribution of " +
      "job data points based on it.").format(traceFileName))
    val traceSrc = io.Source.fromFile(traceFileName)
    val lines = traceSrc.getLines()
    var realDataSum: Double = 0.0
    lines.foreach(line => {
      val parsedLine = line.split(" ")
      // The following parsing code is based on the space-delimited schema
      // used in textfile. See the README for a description.
      // val cell: Double = parsedLine(1).toDouble
      // val allocationPolicy: String = parsedLine(2)
      val isServiceJob: Boolean = if (parsedLine(2).equals("1")) {
        true
      }
      else {
        false
      }
      val dataPoint: Double = parsedLine(3).toDouble
      // Add the job to this workload if its job type is the same as
      // this generator's, which we determine based on workloadName.
      if ((isServiceJob && workloadName.equals("Service")) ||
        (!isServiceJob && workloadName.equals("Batch"))) {
        dataPoints.append(dataPoint)
        realDataSum += dataPoint
      }
    })
    assert(dataPoints.length > 0,
      "Trace file must contain at least one data point.")
    println(("Done reading tracefile of %d jobs, average of real data " +
      "points was %f. Now constructing distribution.")
      .format(dataPoints.length,
        realDataSum / dataPoints.length))
    val dataPointsArray = dataPoints.toArray
    util.Sorting.quickSort(dataPointsArray)
    for (i <- 0 to 1000) {
      // Store summary quantiles.
      // 99.9 %tile = length * .999
      val index = ((dataPointsArray.length - 1) * i / 1000.0).toInt
      val currPercentile =
        dataPointsArray(index)
      refDistribution(i) = currPercentile
      // println("refDistribution(%d) = dataPointsArray(%d) = %f"
      //         .format(i, index, currPercentile))
    }
    refDistribution
  }
}

/**
  * A thread-safe Workload factory. Generates workloads with jobs that have
  * sizes and lengths sampled from exponential distributions. Assumes that
  * all tasks in a job are identical, so no per-task data is required.
  * Generates interarrival times by sampling from an emperical distribution
  * built from a tracefile containing the interarrival times of jobs
  * in a real cluster.
  */
class InterarrivalTimeTraceExpExpWLGenerator(val workloadName: String,
                                             traceFileName: String,
                                             avgTasksPerJob: Double,
                                             avgJobDuration: Double,
                                             avgCpusPerTask: Double,
                                             avgMemPerTask: Double,
                                             maxCpusPerTask: Double,
                                             maxMemPerTask: Double)
  extends WorkloadGenerator {
  assert(workloadName.equals("Batch") || workloadName.equals("Service"))
  // Build the distribution from the input trace textfile that we'll
  // use to generate random job interarrival times.
  val interarrivalTimes = new collection.mutable.ListBuffer[Double]()
  var refDistribution: Array[Double] =
    DistCache.getDistribution(workloadName, traceFileName)

  val numTasksGenerator =
    new ExponentialDistributionImpl(avgTasksPerJob.toFloat)
  val durationGenerator = new ExponentialDistributionImpl(avgJobDuration)
  val cpusPerTaskGenerator =
    new ExponentialDistributionImpl(avgCpusPerTask)
  val memPerTaskGenerator = new ExponentialDistributionImpl(avgMemPerTask)
  val randomNumberGenerator = new util.Random(Seed())

  /**
    * @param value quantile [0, 1] representing distribution quantile to return.
    */
  def getInterarrivalTime(value: Double): Double = {
    // Look up the two closest quantiles and interpolate.
    assert(value >= 0 || value <= 1, "value must be >= 0 and <= 1.")
    val rawIndex = value * (refDistribution.length - 1)
    val interpAmount = rawIndex % 1
    if (interpAmount == 0) {
      return refDistribution(rawIndex.toInt)
    } else {
      val below = refDistribution(math.floor(rawIndex).toInt)
      val above = refDistribution(math.ceil(rawIndex).toInt)
      return below + interpAmount * (below + above)
    }
  }

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = durationGenerator.sample()
    while (dur <= 0.0)
      dur = durationGenerator.sample()
    // Sample until we get task cpu and mem sizes that are small enough.
    var cpusPerTask = cpusPerTaskGenerator.sample()
    while (cpusPerTask >= maxCpusPerTask) {
      cpusPerTask = cpusPerTaskGenerator.sample()
    }
    var memPerTask = memPerTaskGenerator.sample()
    while (memPerTask >= maxMemPerTask) {
      memPerTask = memPerTaskGenerator.sample()
    }
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      // Use ceil to avoid jobs with 0 tasks.
      math.ceil(numTasksGenerator.sample().toFloat).toInt,
      dur,
      workloadName,
      cpusPerTask,
      memPerTask)
  }

  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(updatedAvgJobInterarrivalTime == None)
    assert(timeWindow >= 0)
    assert(maxCpus == None)
    assert(maxMem == None)
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    var nextJobSubmissionTime = getInterarrivalTime(randomNumberGenerator.nextFloat)
    var numJobs = 0
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      nextJobSubmissionTime += getInterarrivalTime(randomNumberGenerator.nextFloat)
      numJobs += 1
    }
    assert(numJobs == workload.numJobs)
    workload
  }
}

/**
  * A thread-safe Workload factory. Generates workloads with jobs that have
  * numTasks, duration, and interarrival_time set by sampling from an emperical
  * distribution built from a tracefile containing the interarrival times of
  * jobs in a real cluster. Task shapes are drawn from exponential distibutions.
  * All tasks in a job are identical, so no per-task data is required.
  */
class TraceWLGenerator(val workloadName: String,
                       interarrivalTraceFileName: String,
                       tasksPerJobTraceFileName: String,
                       jobDurationTraceFileName: String,
                       avgCpusPerTask: Double,
                       avgMemPerTask: Double,
                       maxCpusPerTask: Double,
                       maxMemPerTask: Double)
  extends WorkloadGenerator {
  assert(workloadName.equals("Batch") || workloadName.equals("Service"))
  // Build the distributions from the input trace textfile that we'll
  // use to generate random job interarrival times.
  var interarrivalDist: Array[Double] =
  DistCache.getDistribution(workloadName, interarrivalTraceFileName)
  var tasksPerJobDist: Array[Double] =
    DistCache.getDistribution(workloadName, tasksPerJobTraceFileName)
  var jobDurationDist: Array[Double] =
    DistCache.getDistribution(workloadName, jobDurationTraceFileName)
  val cpusPerTaskGenerator =
    new ExponentialDistributionImpl(avgCpusPerTask)
  val memPerTaskGenerator = new ExponentialDistributionImpl(avgMemPerTask)
  val randomNumberGenerator = new util.Random(Seed())

  /**
    * @param value [0, 1] representing distribution quantile to return.
    */
  def getQuantile(empDistribution: Array[Double],
                  value: Double): Double = {
    // Look up the two closest quantiles and interpolate.
    assert(value >= 0 || value <= 1, "value must be >= 0 and <= 1.")
    val rawIndex = value * (empDistribution.length - 1)
    val interpAmount = rawIndex % 1
    if (interpAmount == 0) {
      return empDistribution(rawIndex.toInt)
    } else {
      val below = empDistribution(math.floor(rawIndex).toInt)
      val above = empDistribution(math.ceil(rawIndex).toInt)
      return below + interpAmount * (below + above)
    }
  }

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = 0.0
    while (dur <= 0.0)
      dur = getQuantile(jobDurationDist, randomNumberGenerator.nextFloat)
    // Use ceil to avoid jobs with 0 tasks.
    val numTasks =
      math.ceil(getQuantile(tasksPerJobDist, randomNumberGenerator.nextFloat).toFloat).toInt
    assert(numTasks != 0, "Jobs must have at least one task.")
    // Sample until we get task cpu and mem sizes that are small enough.
    var cpusPerTask = cpusPerTaskGenerator.sample()
    while (cpusPerTask >= maxCpusPerTask) {
      cpusPerTask = cpusPerTaskGenerator.sample()
    }
    var memPerTask = memPerTaskGenerator.sample()
    while (memPerTask >= maxMemPerTask) {
      memPerTask = memPerTaskGenerator.sample()
    }
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      numTasks,
      dur,
      workloadName,
      cpusPerTask,
      memPerTask)
  }

  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(updatedAvgJobInterarrivalTime == None)
    assert(timeWindow >= 0)
    assert(maxCpus == None)
    assert(maxMem == None)
    // Reset the randomNumberGenerator using the global seed so that
    // the same workload will be generated each time newWorkload is
    // called with the same parameters. This will ensure that
    // Experiments run in different threads will get the same
    // workloads and be a bit more fair to compare to each other.
    randomNumberGenerator.setSeed(Seed())
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    var nextJobSubmissionTime = getQuantile(interarrivalDist, randomNumberGenerator.nextFloat)
    var numJobs = 0
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      nextJobSubmissionTime += getQuantile(interarrivalDist, randomNumberGenerator.nextFloat)
      numJobs += 1
    }
    assert(numJobs == workload.numJobs)
    workload
  }
}

/**
  * A thread-safe Workload factory. Generates workloads with jobs that have
  * numTasks, duration, and interarrival_time set by sampling from an emperical
  * distribution built from a tracefile containing the interarrival times of
  * jobs in a real cluster. Task shapes are drawn from empirical distibutions
  * built from a prefill trace file. All tasks in a job are identical, so no
  * per-task data is required.
  */
class TraceAllWLGenerator(val workloadName: String,
                          interarrivalTraceFileName: String,
                          tasksPerJobTraceFileName: String,
                          jobDurationTraceFileName: String,
                          prefillTraceFileName: String,
                          maxCpusPerTask: Double,
                          maxMemPerTask: Double)
  extends WorkloadGenerator {
  assert(workloadName.equals("Batch") || workloadName.equals("Service"))
  // Build the distributions from the input trace textfile that we'll
  // use to generate random job interarrival times.
  var interarrivalDist: Array[Double] =
  DistCache.getDistribution(workloadName, interarrivalTraceFileName)
  var tasksPerJobDist: Array[Double] =
    DistCache.getDistribution(workloadName, tasksPerJobTraceFileName)
  var jobDurationDist: Array[Double] =
    DistCache.getDistribution(workloadName, jobDurationTraceFileName)
  val cpusPerTaskDist: Array[Double] =
    PrefillJobListsCache.getCpusPerTaskDistribution(workloadName,
      prefillTraceFileName)
  val memPerTaskDist: Array[Double] =
    PrefillJobListsCache.getMemPerTaskDistribution(workloadName,
      prefillTraceFileName)
  val randomNumberGenerator = new util.Random(Seed())

  /**
    * @param value [0, 1] representing distribution quantile to return.
    */
  def getQuantile(empDistribution: Array[Double],
                  value: Double): Double = {
    // Look up the two closest quantiles and interpolate.
    assert(value >= 0 || value <= 1, "value must be >= 0 and <= 1.")
    val rawIndex = value * (empDistribution.length - 1)
    val interpAmount = rawIndex % 1
    if (interpAmount == 0) {
      return empDistribution(rawIndex.toInt)
    } else {
      val below = empDistribution(math.floor(rawIndex).toInt)
      val above = empDistribution(math.ceil(rawIndex).toInt)
      return below + interpAmount * (below + above)
    }
  }

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with duration 0.
    var dur = 0.0
    while (dur <= 0.0)
      dur = getQuantile(jobDurationDist, randomNumberGenerator.nextFloat)
    // Use ceil to avoid jobs with 0 tasks.
    val numTasks = math.ceil(getQuantile(tasksPerJobDist,
      randomNumberGenerator.nextFloat).toFloat).toInt
    assert(numTasks != 0, "Jobs must have at least one task.")
    // Sample from the empirical distribution until we get task
    // cpu and mem sizes that are small enough.
    var cpusPerTask = 0.7 * getQuantile(cpusPerTaskDist,
      randomNumberGenerator.nextFloat).toFloat
    while (cpusPerTask >= maxCpusPerTask) {
      cpusPerTask = 0.7 * getQuantile(cpusPerTaskDist,
        randomNumberGenerator.nextFloat).toFloat
    }
    var memPerTask = 0.7 * getQuantile(memPerTaskDist,
      randomNumberGenerator.nextFloat).toFloat
    while (memPerTask >= maxMemPerTask) {
      memPerTask = 0.7 * getQuantile(memPerTaskDist,
        randomNumberGenerator.nextFloat).toFloat
    }
    // println("cpusPerTask is %f, memPerTask %f.".format(cpusPerTask, memPerTask))
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      numTasks,
      dur,
      workloadName,
      cpusPerTask,
      memPerTask)
  }

  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(timeWindow >= 0)
    assert(maxCpus == None)
    assert(maxMem == None)
    // Reset the randomNumberGenerator using the global seed so that
    // the same workload will be generated each time newWorkload is
    // called with the same parameters. This will ensure that
    // Experiments run in different threads will get the same
    // workloads and be a bit more fair to compare to each other.
    randomNumberGenerator.setSeed(Seed())
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    var nextJobSubmissionTime = getQuantile(interarrivalDist,
      randomNumberGenerator.nextFloat)
    var numJobs = 0
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      // For this type of WorkloadGenerator in which interarrival rate is
      // sampled from an empirical distribution, the
      // updatedAvgJobInterarrivalTime parameter represents a scaling factor
      // for the value sampled from the distribution.
      nextJobSubmissionTime += updatedAvgJobInterarrivalTime.getOrElse(1.0) *
        getQuantile(interarrivalDist,
          randomNumberGenerator.nextFloat)
      numJobs += 1
    }
    assert(numJobs == workload.numJobs)
    workload
  }
}


class TraceReadWLGenerator(val workloadName: String,
                           traceFileName: String,
                           maxCpusPerTask: Double,
                           maxMemPerTask: Double)
  extends WorkloadGenerator {
  assert(workloadName.equals("Batch") || workloadName.equals("Service"))
  // Build the distributions from the input trace textfile that we'll
  // use to generate random job interarrival times.
  // Traza:

  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(timeWindow >= 0)
    assert(maxCpus == None)
    assert(maxMem == None)
    val workload = new Workload(workloadName)

    println(("Reading tracefile %s and building the workload").format(traceFileName))
    val traceSrc = io.Source.fromFile(traceFileName)
    val lines = traceSrc.getLines()
    lines.foreach(line => {
      val parsedLine = line.split(" ")
      // Definir formato de traza: Columna 1: Submission time, Columna 2: Num tasks, Columna 3: duración, Columna 4: CpusPerTask, Columna 5: Mem Per task. Separados por espacios. Cada línea un job
      val job = Job(UniqueIDGenerator.getUniqueID(),
        parsedLine(0).toDouble,
        parsedLine(1).toInt,
        parsedLine(2).toDouble,
        workloadName,
        parsedLine(3).toDouble,
        parsedLine(4).toDouble)
      workload.addJob(job)
    })
    workload
  }
}


class DailyExpExpExpWorkloadGenerator(val workloadName: String,
                                      initAvgJobInterarrivalTime: Double,
                                      avgTasksPerJob: Double,
                                      avgJobDuration: Double,
                                      avgCpusPerTask: Double,
                                      avgMemPerTask: Double,
                                      heterogeneousTasks: Boolean = false)
  extends WorkloadGenerator {
  val numTasksGenerator =
    new ExponentialDistributionImpl(avgTasksPerJob.toFloat)
  val durationGenerator = new ExponentialDistributionImpl(avgJobDuration)

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = durationGenerator.sample()
    while (dur <= 0.0)
      dur = durationGenerator.sample()
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      // Use ceil to avoid jobs with 0 tasks.
      math.ceil(numTasksGenerator.sample().toFloat).toInt,
      dur,
      workloadName,
      avgCpusPerTask,
      avgMemPerTask,
      heterogeneousTasks)
  }

  /**
    * Synchronized so that Experiments, which can share this WorkloadGenerator,
    * can safely call newWorkload concurrently.
    */
  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(maxCpus == None)
    assert(maxMem == None)
    assert(timeWindow >= 0)
    // Create the job-interarrival-time number generator using the
    // parameter passed in, if any, else use the default parameter.
    val avgJobInterarrivalTime =
    updatedAvgJobInterarrivalTime.getOrElse(initAvgJobInterarrivalTime)
    var interarrivalTimeGenerator =
      new ExponentialDistributionImpl(avgJobInterarrivalTime)
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    val interArrivalSample = interarrivalTimeGenerator.sample()
    var nextJobSubmissionTime = interArrivalSample / (getCoeff(interArrivalSample))
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      val interArrivalSampleIter = interarrivalTimeGenerator.sample()
      nextJobSubmissionTime += interArrivalSampleIter / (getCoeff(nextJobSubmissionTime + interArrivalSample))
    }
    workload
  }

  def modifyAmplitude(timeCoefficients: List[Double], amplitude: Double): List[Double] = {
    timeCoefficients.map(x => if (x < 1) x/amplitude else x * amplitude)
  }

  def getCoeff(submissionTime: Double): Double = {
    var timeCofficients = (0.7 :: 0.5 :: 0.4 :: 0.5 :: 0.5 :: 0.5 :: 0.4 :: 0.4 :: 0.4 :: 2.6 :: 1.8 :: 0.7 :: 0.4 :: 1.8 :: 2.1 :: 2.5 :: 0.7 :: 0.4 :: 0.7 :: 1.8 :: 2.1 :: 1.6 :: 1.7 :: 0.9 :: Nil)
    //timeCofficients = modifyAmplitude(timeCofficients, 2)
    val secondsSubmission = submissionTime % 86400.0
    val coeffIndex = (secondsSubmission / 3600.0).floor.toInt
    /*val random = new Random();
    var correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    while (correctedCoeff <= 0.0){
      correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    }*/
    val correctedCoeff = timeCofficients(coeffIndex);

    correctedCoeff
  }
}


class WeeklyExpExpExpWorkloadGenerator(val workloadName: String,
                                      initAvgJobInterarrivalTime: Double,
                                      avgTasksPerJob: Double,
                                      avgJobDuration: Double,
                                      avgCpusPerTask: Double,
                                      avgMemPerTask: Double,
                                      heterogeneousTasks: Boolean = false)
  extends WorkloadGenerator {
  val numTasksGenerator =
    new ExponentialDistributionImpl(avgTasksPerJob.toFloat)
  val durationGenerator = new ExponentialDistributionImpl(avgJobDuration)

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = durationGenerator.sample()
    while (dur <= 0.0)
      dur = durationGenerator.sample()
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      // Use ceil to avoid jobs with 0 tasks.
      math.ceil(numTasksGenerator.sample().toFloat).toInt,
      dur,
      workloadName,
      avgCpusPerTask,
      avgMemPerTask,
      heterogeneousTasks)
  }

  /**
    * Synchronized so that Experiments, which can share this WorkloadGenerator,
    * can safely call newWorkload concurrently.
    */
  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(maxCpus == None)
    assert(maxMem == None)
    assert(timeWindow >= 0)
    // Create the job-interarrival-time number generator using the
    // parameter passed in, if any, else use the default parameter.
    val avgJobInterarrivalTime =
    updatedAvgJobInterarrivalTime.getOrElse(initAvgJobInterarrivalTime)
    var interarrivalTimeGenerator =
      new ExponentialDistributionImpl(avgJobInterarrivalTime)
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    val interArrivalSample = interarrivalTimeGenerator.sample()
    var nextJobSubmissionTime = interArrivalSample / (getCoeff(interArrivalSample))
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      val interArrivalSampleIter = interarrivalTimeGenerator.sample()
      nextJobSubmissionTime += interArrivalSampleIter / (getCoeff(nextJobSubmissionTime + interArrivalSample))
    }
    workload
  }

  def modifyAmplitude(timeCoefficients: List[Double], amplitude: Double): List[Double] = {
    timeCoefficients.map(x => if (x < 1) x/amplitude else x * amplitude)
  }

  def getCoeff(submissionTime: Double): Double = {
    //var timeCofficients = (0.2 :: 0.2 :: 0.2 :: 2.2 :: 2.2 :: 2.2 :: 2.2 :: Nil)
    var timeCofficients = (2.2 :: 2.2 :: 0.7 :: 0.5 :: 0.4 :: 0.6 :: 0.6 :: Nil)
    //var timeCofficients = (2.2 :: 0.6 :: 2.2 :: 0.6 :: 2.2 :: 0.6 :: 0.6 :: Nil)
    //timeCofficients = modifyAmplitude(timeCofficients, 2)
    val secondsSubmission = submissionTime % 604800.0
    val coeffIndex = (secondsSubmission / 86400.0).floor.toInt
    /*val random = new Random();
    var correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    while (correctedCoeff <= 0.0){
      correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    }*/
    val correctedCoeff = timeCofficients(coeffIndex);

    correctedCoeff
  }
}



class WeeklyDailyExpExpExpWorkloadGenerator(val workloadName: String,
                                       initAvgJobInterarrivalTime: Double,
                                       avgTasksPerJob: Double,
                                       avgJobDuration: Double,
                                       avgCpusPerTask: Double,
                                       avgMemPerTask: Double,
                                       heterogeneousTasks: Boolean = false)
  extends WorkloadGenerator {
  val numTasksGenerator =
    new ExponentialDistributionImpl(avgTasksPerJob.toFloat)
  val durationGenerator = new ExponentialDistributionImpl(avgJobDuration)

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = durationGenerator.sample()
    while (dur <= 0.0)
      dur = durationGenerator.sample()
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      // Use ceil to avoid jobs with 0 tasks.
      math.ceil(numTasksGenerator.sample().toFloat).toInt,
      dur,
      workloadName,
      avgCpusPerTask,
      avgMemPerTask,
      heterogeneousTasks)
  }

  /**
    * Synchronized so that Experiments, which can share this WorkloadGenerator,
    * can safely call newWorkload concurrently.
    */
  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(maxCpus == None)
    assert(maxMem == None)
    assert(timeWindow >= 0)
    // Create the job-interarrival-time number generator using the
    // parameter passed in, if any, else use the default parameter.
    val avgJobInterarrivalTime =
    updatedAvgJobInterarrivalTime.getOrElse(initAvgJobInterarrivalTime)
    var interarrivalTimeGenerator =
      new ExponentialDistributionImpl(avgJobInterarrivalTime)
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    val interArrivalSample = interarrivalTimeGenerator.sample()
    var nextJobSubmissionTime = interArrivalSample / (getCoeff(interArrivalSample))
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      val interArrivalSampleIter = interarrivalTimeGenerator.sample()
      nextJobSubmissionTime += interArrivalSampleIter / (getCoeff(nextJobSubmissionTime + interArrivalSample))
    }
    workload
  }

  def modifyAmplitude(timeCoefficients: List[Double], amplitude: Double): List[Double] = {
    timeCoefficients.map(x => if (x < 1) x/amplitude else x * amplitude)
  }

  def getCoeff(submissionTime: Double): Double = {
    //var timeCofficients = (0.2 :: 0.2 :: 0.2 :: 2.2 :: 2.2 :: 2.2 :: 2.2 :: Nil)
    var timeCofficientsWeek = (2.2 :: 2.2 :: 0.7 :: 0.5 :: 0.4 :: 0.6 :: 0.6 :: Nil)
    //var timeCofficients = (2.2 :: 0.6 :: 2.2 :: 0.6 :: 2.2 :: 0.6 :: 0.6 :: Nil)
    //timeCofficients = modifyAmplitude(timeCofficients, 2)
    val secondsSubmissionWeek = submissionTime % 604800.0
    val coeffIndexWeek = (secondsSubmissionWeek / 86400.0).floor.toInt


    var timeCofficientsDay = (0.7 :: 0.5 :: 0.4 :: 0.5 :: 0.5 :: 0.5 :: 0.4 :: 0.4 :: 0.4 :: 2.6 :: 1.8 :: 0.7 :: 0.4 :: 1.8 :: 2.1 :: 2.5 :: 0.7 :: 0.4 :: 0.7 :: 1.8 :: 2.1 :: 1.6 :: 1.7 :: 0.9 :: Nil)
    //timeCofficients = modifyAmplitude(timeCofficients, 2)
    val secondsSubmissionDay = submissionTime % 86400.0
    val coeffIndexDay = (secondsSubmissionDay / 3600.0).floor.toInt


    /*val random = new Random();
    var correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    while (correctedCoeff <= 0.0){
      correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    }*/
    val correctedCoeff = timeCofficientsWeek(coeffIndexWeek) * timeCofficientsDay(coeffIndexDay);
    correctedCoeff
  }
}

class DailyWeiWeiWeiWorkloadGenerator(val workloadName: String,
                                      initAvgJobInterarrivalTime: Double,
                                      avgTasksPerJob: Double,
                                      avgJobDuration: Double,
                                      avgCpusPerTask: Double,
                                      avgMemPerTask: Double,
                                      alpha: Tuple2[Double, Double] = (0.25, 24.0),
                                      heterogeneousTasks: Boolean = false)
  extends WorkloadGenerator {
  val numTasksGenerator =
    new ExponentialDistributionImpl(avgTasksPerJob.toFloat)
  val durationGenerator = new ExponentialDistributionImpl(avgJobDuration)

  def newJob(submissionTime: Double): Job = {
    // Don't allow jobs with zero tasks.
    var dur = durationGenerator.sample()
    while (dur <= 0.0)
      dur = durationGenerator.sample()
    Job(UniqueIDGenerator.getUniqueID(),
      submissionTime,
      // Use ceil to avoid jobs with 0 tasks.
      math.ceil(numTasksGenerator.sample().toFloat).toInt,
      dur,
      workloadName,
      avgCpusPerTask,
      avgMemPerTask,
      heterogeneousTasks)
  }

  /**
    * Synchronized so that Experiments, which can share this WorkloadGenerator,
    * can safely call newWorkload concurrently.
    */
  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(maxCpus == None)
    assert(maxMem == None)
    assert(timeWindow >= 0)
    // Create the job-interarrival-time number generator using the
    // parameter passed in, if any, else use the default parameter.
    val avgJobInterarrivalTime =
    updatedAvgJobInterarrivalTime.getOrElse(initAvgJobInterarrivalTime)
    var interarrivalTimeGenerator =
      new WeibullDistribution(alpha._1, avgJobInterarrivalTime/alpha._2)
    val workload = new Workload(workloadName)
    // create a new list of jobs for the experiment runTime window
    // using the current WorkloadGenerator.
    val interArrivalSample = interarrivalTimeGenerator.sample()
    var nextJobSubmissionTime = interArrivalSample / (getCoeff(interArrivalSample))
    while (nextJobSubmissionTime < timeWindow) {
      val job = newJob(nextJobSubmissionTime)
      assert(job.workloadName == workload.name)
      workload.addJob(job)
      val interArrivalSampleIter = interarrivalTimeGenerator.sample()
      nextJobSubmissionTime += interArrivalSampleIter / (getCoeff(nextJobSubmissionTime + interArrivalSample))
    }
    workload
  }

  def modifyAmplitude(timeCoefficients: List[Double], amplitude: Double): List[Double] = {
    timeCoefficients.map(x => if (x < 1) x/amplitude else x * amplitude)
  }

  def getCoeff(submissionTime: Double): Double = {
    var timeCofficients = (0.7 :: 0.5 :: 0.4 :: 0.5 :: 0.5 :: 0.5 :: 0.4 :: 0.4 :: 0.4 :: 2.6 :: 1.8 :: 0.7 :: 0.4 :: 1.8 :: 2.1 :: 2.5 :: 0.7 :: 0.4 :: 0.7 :: 1.8 :: 2.1 :: 1.6 :: 1.7 :: 0.9 :: Nil)
    //timeCofficients = modifyAmplitude(timeCofficients, 2)
    val secondsSubmission = submissionTime % 86400.0
    val coeffIndex = (secondsSubmission / 3600.0).floor.toInt
    /*val random = new Random();
    var correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    while (correctedCoeff <= 0.0){
      correctedCoeff = random.nextGaussian()*(timeCofficients(coeffIndex)/1.3)+timeCofficients(coeffIndex);
    }*/
    val correctedCoeff = timeCofficients(coeffIndex);

    correctedCoeff
  }
}



object PrefillJobListsCache {
  // Map from (workloadname, traceFileName) -> list of jobs.
  val jobLists =
    collection.mutable.Map[(String, String), Iterable[Job]]()
  val cpusPerTaskDistributions =
    collection.mutable.Map[String, Array[Double]]()
  val memPerTaskDistributions =
    collection.mutable.Map[String, Array[Double]]()

  def getOrLoadJobs(workloadName: String,
                    traceFileName: String): Iterable[Job] = {
    val cachedJobs = jobLists.getOrElseUpdate((workloadName, traceFileName), {
      val newJobs = collection.mutable.Map[String, Job]()
      val traceSrc = io.Source.fromFile(traceFileName)
      val lines: Iterator[String] = traceSrc.getLines()
      var numLines = 0
      lines.foreach(line => {
        numLines += 1
        val parsedLine = line.split(" ")
        // The following parsing code is based on the space-delimited schema
        // used in textfile. See the README Andy made for a description of it.

        // Parse the fields that are common between both (6 & 8 column)
        // row formats.
        val timestamp: Double = parsedLine(1).toDouble
        val jobID: String = parsedLine(2)
        val isHighPriority: Boolean = if (parsedLine(3).equals("1")) {
          true
        }
        else {
          false
        }
        val schedulingClass: Int = parsedLine(4).toInt
        // Label the job according to PBB workload split. SchedulingClass 0 & 1
        // are batch, 2 & 3 are service.
        // (isServiceJob == false) => this is a batch job.
        val isServiceJob = (isHighPriority && schedulingClass != 0 && schedulingClass != 1)
        // Add the job to this workload if its job type is the same as
        // this generator's, which we determine based on workloadName
        // and if we havne't reached the resource size limits of the
        // requested workload.
        if ((isServiceJob && workloadName.equals("PrefillService")) ||
          (!isServiceJob && workloadName.equals("PrefillBatch")) ||
          (workloadName.equals("PrefillBatchService"))) {
          if (parsedLine(0).equals("11")) {
            assert(parsedLine.length == 8, "Found %d fields, expecting %d"
              .format(parsedLine.length, 8))
            val numTasks: Int = parsedLine(5).toInt
            val cpusPerJob: Double = parsedLine(6).toDouble
            // The tracefile has memory in bytes, our simulator is in GB.
            val memPerJob: Double = parsedLine(7).toDouble / 1024 / 1024 / 1024
            val newJob = Job(UniqueIDGenerator.getUniqueID(),
              0.0, // Submitted
              numTasks,
              -1, // to be replaced w/ simulator.runTime
              workloadName,
              cpusPerJob / numTasks,
              memPerJob / numTasks)
            newJobs(parsedLine(2)) = newJob
            // Update the job/task duration for jobs that we have end times for.
          } else if (parsedLine(0).equals("12")) {
            assert(parsedLine.length == 6, "Found %d fields, expecting %d"
              .format(parsedLine.length, 8))
            assert(newJobs.contains(jobID), "Expect to find job %s in newJobs."
              .format(jobID))
            newJobs(jobID).taskDuration = timestamp
          } else {
            sys.error("Invalid trace event type code %s in tracefile %s"
              .format(parsedLine(0), traceFileName))
          }
        }
      })
      traceSrc.close()
      // Add the newly parsed list of jobs to the cache.
      println("loaded %d newJobs.".format(newJobs.size))
      newJobs.values
    })
    // Return a copy of the cached jobs since the job durations will
    // be updated according to the experiment time window.
    println("returning %d jobs from cache".format(cachedJobs.size))
    cachedJobs.map(_.copy())
  }

  /**
    * When we load jobs from a trace file, we fill in the duration for all jobs
    * that don't have an end event as -1, then we fill in the duration for all
    * such jobs.
    */
  def getJobs(workloadName: String,
              traceFileName: String,
              timeWindow: Double): Iterable[Job] = {
    val jobs = getOrLoadJobs(workloadName, traceFileName)
    // Update duration of jobs with duration set to -1.
    jobs.foreach(job => {
      if (job.taskDuration == -1)
        job.taskDuration = timeWindow
    })
    jobs
  }

  def buildDist(dataPoints: Array[Double]): Array[Double] = {
    val refDistribution = new Array[Double](1001)
    assert(dataPoints.length > 0,
      "dataPoints must contain at least one data point.")
    util.Sorting.quickSort(dataPoints)
    for (i <- 0 to 1000) {
      // Store summary quantiles. 99.9 %tile = length * .999
      val index = ((dataPoints.length - 1) * i / 1000.0).toInt
      val currPercentile =
        dataPoints(index)
      refDistribution(i) = currPercentile
    }
    refDistribution
  }

  def getCpusPerTaskDistribution(workloadName: String,
                                 traceFileName: String): Array[Double] = {
    //TODO(andyk): Fix this ugly hack of prepending "Prefill".
    val jobs = getOrLoadJobs("Prefill" + workloadName, traceFileName)
    println("Loaded or retreived %d %s jobs in tracefile %s."
      .format(jobs.size, "Prefill" + workloadName, traceFileName))
    cpusPerTaskDistributions.getOrElseUpdate(
      traceFileName, buildDist(jobs.map(_.cpusPerTask).toArray))
  }

  def getMemPerTaskDistribution(workloadName: String,
                                traceFileName: String): Array[Double] = {
    //TODO(andyk): Fix this ugly hack of prepending "Prefill".
    val jobs = getOrLoadJobs("Prefill" + workloadName, traceFileName)
    println("Loaded or retreived %d %s jobs in tracefile %s."
      .format(jobs.size, "Prefill" + workloadName, traceFileName))
    memPerTaskDistributions.getOrElseUpdate(
      traceFileName, buildDist(jobs.map(_.memPerTask).toArray))
  }
}

/**
  * Generates a pre-fill workload based on an input trace from a cluster.
  * Given a workloadName, it has hard coded rules to split up jobs
  * in the input file according to the PBB style split based on
  * the jobs' priority level and schedulingClass.
  */
class PrefillPbbTraceWorkloadGenerator(val workloadName: String,
                                       traceFileName: String)
  extends WorkloadGenerator {
  assert(workloadName.equals("PrefillBatch") ||
    workloadName.equals("PrefillService") ||
    workloadName.equals("PrefillBatchService"))

  def newWorkload(timeWindow: Double,
                  maxCpus: Option[Double] = None,
                  maxMem: Option[Double] = None,
                  updatedAvgJobInterarrivalTime: Option[Double] = None)
  : Workload = this.synchronized {
    assert(updatedAvgJobInterarrivalTime == None)
    assert(timeWindow >= 0)

    var joblist = PrefillJobListsCache.getJobs(workloadName,
      traceFileName,
      timeWindow)
    val workload = new Workload(workloadName)

    //TODO(andyk): Make this more functional.
    def reachedMaxCpu(currCpus: Double) = maxCpus.exists(currCpus >= _)

    def reachedMaxMem(currMem: Double) = maxMem.exists(currMem >= _)

    var iter = joblist.toIterator
    var numCpus = 0.0
    var numMem = 0.0
    var counter = 0
    while (iter.hasNext) {
      counter += 1
      val nextJob = iter.next
      numCpus += nextJob.numTasks * nextJob.cpusPerTask
      numMem += nextJob.numTasks * nextJob.memPerTask
      if (reachedMaxCpu(numCpus) || reachedMaxMem(numMem)) {
        println("reachedMaxCpu = %s, reachedMaxMem = %s"
          .format(reachedMaxCpu(numCpus), reachedMaxMem(numMem)))
        iter = Iterator()
      } else {
        // Use copy to be sure we don't share state between runs of the
        // simulator.
        workload.addJob(nextJob.copy())
        iter = iter.drop(0)
      }
    }
    println("Returning workload with %f cpus and %f mem in total."
      .format(workload.getJobs.map(j => {
        j.numTasks * j.cpusPerTask
      }).sum,
        workload.getJobs.map(j => {
          j.numTasks * j.memPerTask
        }).sum))
    workload
  }
}

