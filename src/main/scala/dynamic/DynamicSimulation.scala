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

package dynamic

import ClusterSchedulingSimulation._
import dynamic.normalization.MesosCapableScheduler
import dynamic.strategies.{MesosStrategy, OmegaStrategy, RMStrategy}
import efficiency.ordering_cellstate_resources_policies.CellStateResourcesSorter
import efficiency.pick_cellstate_resources.CellStateResourcesPicker
import efficiency.power_off_policies.PowerOffPolicy
import efficiency.power_on_policies.PowerOnPolicy
import stackelberg.StackelbergAgent

import scala.collection.mutable.HashMap

class DynamicSimulatorDesc(
                            val schedulerDescs: Seq[DynamicSchedulerDesc],
                            runTime: Double,
                            val conflictMode: String,
                            val transactionMode: String,
                            val strategies: List[String],
                            val allocatorConstantThinkTime: Double)
  extends ClusterSimulatorDesc(runTime){
  override
  def newSimulator(constantThinkTime: Double,
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
                   stackelbergStrategy: StackelbergAgent): ClusterSimulator = {
    assert(blackListPercent >= 0.0 && blackListPercent <= 1.0)
    assert(strategies.length > 0)
    var schedulers = HashMap[String, DynamicScheduler]()
    // Create schedulers according to experiment parameters.
    println("Creating %d schedulers.".format(schedulerDescs.length))
    schedulerDescs.foreach(schedDesc => {
      // If any of the scheduler-workload pairs we're sweeping over
      // are for this scheduler, then apply them before
      // registering it.
      var constantThinkTimes = HashMap[String, Double](
        schedDesc.constantThinkTimes.toSeq: _*)
      var perTaskThinkTimes = HashMap[String, Double](
        schedDesc.perTaskThinkTimes.toSeq: _*)
      var newBlackListPercent = 0.0
      if (schedulerWorkloadsToSweepOver
        .contains(schedDesc.name)) {
        newBlackListPercent = blackListPercent
        schedulerWorkloadsToSweepOver(schedDesc.name)
          .foreach(workloadName => {
            constantThinkTimes(workloadName) = constantThinkTime
            perTaskThinkTimes(workloadName) = perTaskThinkTime
          })
      }
      println("Creating new scheduler %s".format(schedDesc.name))
      schedulers(schedDesc.name) =
        new DynamicScheduler(schedDesc.name,
          constantThinkTimes.toMap,
          perTaskThinkTimes.toMap,
          math.floor(newBlackListPercent *
            cellStateDesc.numMachines.toDouble).toInt)
    })

    val cellState = new CellState(cellStateDesc.numMachines,
      cellStateDesc.cpusPerMachine,
      cellStateDesc.memPerMachine,
      conflictMode,
      transactionMode,
      machinesHet = cellStateDesc.machinesHet,
      machEn = cellStateDesc.machEn,
      machPerf = cellStateDesc.machPerf,
      machSec = cellStateDesc.machSec)
    println("Creating new DynamicSimulator with schedulers %s."
      .format(schedulers.values.map(_.toString).mkString(", ")))
    println("Setting DynamicSimulator(%s, %s)'s common cell state to %d"
      .format(conflictMode,
        transactionMode,
        cellState.hashCode))

    val allocator =
      new MesosAllocator(allocatorConstantThinkTime)

    new DynamicSimulator(cellState,
      schedulers.toMap,
      workloadToSchedulerMap,
      workloads,
      prefillWorkloads,
      allocator,
      logging,
      cellStateResourcesSorter = cellStateResourcesSorter,
      cellStateResourcesPicker = cellStateResourcesPicker,
      powerOnPolicy = powerOnPolicy,
      powerOffPolicy = powerOffPolicy,
      securityLevel1Time = securityLevel1Time,
      securityLevel2Time = securityLevel2Time,
      securityLevel3Time = securityLevel3Time,
      stackelbergStrategy = stackelbergStrategy,
      strategies = strategies)
  }
}

/**
  * A simple subclass of SchedulerDesc for extensibility to
  * for symmetry in the naming of the type so that we don't
  * have to use a SchedulerDesc for an OmegaSimulator.
  */
class DynamicSchedulerDesc(name: String,
                           constantThinkTimes: Map[String, Double],
                           perTaskThinkTimes: Map[String, Double])
  extends SchedulerDesc(name,
    constantThinkTimes,
    perTaskThinkTimes)

class DynamicSimulator(cellState: CellState,
                       override val schedulers: Map[String, DynamicScheduler],
                       workloadToSchedulerMap: Map[String, Seq[String]],
                       workloads: Seq[Workload],
                       prefillWorkloads: Seq[Workload],
                       var allocator: MesosAllocator,
                       logging: Boolean = false,
                       monitorUtilization: Boolean = true,
                       cellStateResourcesSorter: CellStateResourcesSorter,
                       cellStateResourcesPicker: CellStateResourcesPicker,
                       powerOnPolicy: PowerOnPolicy,
                       powerOffPolicy: PowerOffPolicy,
                       securityLevel1Time: Double,
                       securityLevel2Time: Double,
                       securityLevel3Time: Double,
                       stackelbergStrategy: StackelbergAgent,
                       val strategies: List[String] = ("Omega" :: "Mesos" :: Nil))
  extends ClusterSimulator(cellState,
    schedulers,
    workloadToSchedulerMap,
    workloads,
    prefillWorkloads,
    logging,
    monitorUtilization,
    cellStateResourcesSorter = cellStateResourcesSorter,
    cellStateResourcesPicker = cellStateResourcesPicker,
    powerOnPolicy = powerOnPolicy,
    powerOffPolicy = powerOffPolicy,
    securityLevel1Time = securityLevel1Time,
    securityLevel2Time = securityLevel2Time,
    securityLevel3Time = securityLevel3Time,
    stackelbergStrategy = stackelbergStrategy) {
  // Set up a pointer to this simulator in each scheduler.
  schedulers.values.foreach(_.dynamicSimulator = this)
  schedulers.values.foreach(_.chooseStrategy(strategies(0)))
  allocator.simulator = this
}

/**
  * While an Omega Scheduler has jobs in its job queue, it:
  * 1: Syncs with cell state by getting a new copy of common cell state
  * 2: Schedules the next job j in the queue, using getThinkTime(j) seconds
  *    and assigning creating and applying one delta per task in the job.
  * 3: submits the job to CellState
  * 4: if any tasks failed to schedule: insert job at back of queue
  * 5: rolls back its changes
  * 6: repeat, starting at 1
  */
class DynamicScheduler(name: String,
                       constantThinkTimes: Map[String, Double],
                       perTaskThinkTimes: Map[String, Double],
                       numMachinesToBlackList: Double = 0)
  extends Scheduler(name,
    constantThinkTimes,
    perTaskThinkTimes,
    numMachinesToBlackList) with MesosCapableScheduler {
  println("scheduler-id-info: %d, %s, %d, %s, %s"
    .format(Thread.currentThread().getId(),
      name,
      hashCode(),
      constantThinkTimes.mkString(";"),
      perTaskThinkTimes.mkString(";")))
  // TODO(andyk): Clean up these <subclass>Simulator classes
  //              by templatizing the Scheduler class and having only
  //              one simulator of the correct type, instead of one
  //              simulator for each of the parent and child classes.
  var dynamicSimulator: DynamicSimulator = null
  val offerQueue = new collection.mutable.Queue[Offer]
  //TODO: Inicializar
  var chosenStrategy: RMStrategy = null
  val omegaStrategy: OmegaStrategy = new OmegaStrategy(this)
  val mesosStrategy: MesosStrategy = new MesosStrategy(this)
  var iter = 0

  def chooseStrategy(name: String): Unit ={
      assert(name == "" || name == "Omega" || name == "Mesos", "The dynamic strategies supported are Mesos or Omega")
      if(name=="Omega") {
        if(chosenStrategy == null){
          chosenStrategy = omegaStrategy
        }
        else if (chosenStrategy.name == "Mesos") {
          chosenStrategy.asInstanceOf[MesosStrategy].removeOffers();
          chosenStrategy = omegaStrategy
          scheduling=false
          start();
          iter+=1
        }
        //println("Pongo Omega en el scheduler "+this.name)
      }
      else if (name == "Mesos") {
        if(chosenStrategy == null){
          chosenStrategy = mesosStrategy
        }
        else if (chosenStrategy.name == "Omega") {
          chosenStrategy = mesosStrategy
          scheduling=false
          start();
          iter+=1
        }
      }


    /*if(chosenStrategy == null){
      chosenStrategy = omegaStrategy
    }
    else if(iter%100 == 0){
      if(chosenStrategy.name == "Omega"){
        chosenStrategy = mesosStrategy
        //scheduling=false
        //start();
      }
      else{
        chosenStrategy.asInstanceOf[MesosStrategy].removeOffers();
        chosenStrategy = omegaStrategy
        //scheduling=false
        //start();
      }
    }*/
  }


  override
  def checkRegistered = {
    super.checkRegistered
    assert(dynamicSimulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
  }


  def resourceOffer(offer: Offer): Unit = {
    offerQueue.enqueue(offer)
    if(chosenStrategy.name == "Mesos") {
      chosenStrategy.asInstanceOf[MesosStrategy].handleNextResourceOffer()
    }
    else{
      mesosStrategy.removeOffers()
    }
  }


  // We define this method to because mesosallocator needs to know hoy many resources must power on depending on job's needs
  def nextJob() : Job = {
    pendingQueue(0);
  }

  def incrementDailycounter(counter: HashMap[Int, Int]) = {
    val index: Int = math.floor(simulator.currentTime / 86400).toInt
    val currCount: Int = counter.getOrElse(index, 0)
    counter(index) = currCount + 1
  }

  // When a job arrives, start scheduling, or make sure we already are.
  override
  def addJob(job: Job) = {
    assert(simulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
    super.addJob(job)
    chosenStrategy.addJob(job)
  }

  def start() = {
    if(pendingQueue.length > 0){
      if(chosenStrategy.name == "Mesos"){
        dynamicSimulator.allocator.requestOffer(this)
      }
      else if(chosenStrategy.name == "Omega"){
        chosenStrategy.asInstanceOf[OmegaStrategy].handleJob(pendingQueue.dequeue)
      }
    }
  }

  /**
    * Schedule job and submit a transaction to common cellstate for
    * it. If not all tasks in the job are successfully committed,
    * put it back in the pendingQueue to be scheduled again.
    */

}
