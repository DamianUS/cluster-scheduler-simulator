package dynamic.strategies

import ClusterSchedulingSimulation.{CellState, ClaimDelta, Job}
import dynamic.DynamicScheduler

class OmegaStrategy(sched : DynamicScheduler) extends RMStrategy {
  override val name: String = "Omega"
  override var scheduler: DynamicScheduler = sched
  var privateCellState: CellState = null

  // When a job arrives, start scheduling, or make sure we already are.
  override
  def addJob(job: Job) = {
    assert(job.unscheduledTasks > 0)
    scheduler.pendingQueue.enqueue(job)
    scheduler.simulator.log("Scheduler %s enqueued job %d of workload type %s."
      .format(name, job.id, job.workloadName))
    if (!scheduler.scheduling) {
      scheduler.simulator.log("Set %s scheduling to TRUE to schedule job %d."
        .format(name, job.id))
      scheduler.scheduling = true
      handleJob(scheduler.pendingQueue.dequeue)
    }
  }


  def handleJob(job: Job): Unit = {
    job.updateTimeInQueueStats(scheduler.simulator.currentTime)
    syncCellState
    val jobThinkTime = scheduler.getThinkTime(job)
    scheduler.dynamicSimulator.afterDelay(jobThinkTime) {
      job.numSchedulingAttempts += 1
      job.numTaskSchedulingAttempts += job.unscheduledTasks
      // Schedule the job in private cellstate.
      assert(job.unscheduledTasks > 0)
      val claimDeltas = scheduler.scheduleJob(job, privateCellState)
      // TODO : No puedo usar Commit result, así que...
      var commitedDelta = Seq[ClaimDelta]()
      var conflictedDelta = Seq[ClaimDelta]()
      scheduler.simulator.log(("Job %d (%s) finished %f seconds of scheduling " +
        "thinktime; now trying to claim resources for %d " +
        "tasks with %f cpus and %f mem each.")
        .format(job.id,
          job.workloadName,
          jobThinkTime,
          job.numTasks,
          job.cpusPerTask,
          job.memPerTask))
      if (claimDeltas.length > 0) {
        // Attempt to claim resources in common cellstate by committing
        // a transaction.
        scheduler.dynamicSimulator.log("Submitting a transaction for %d tasks for job %d."
          .format(claimDeltas.length, job.id))
        val commitResult = scheduler.dynamicSimulator.cellState.commit(claimDeltas, true)
        commitedDelta = commitResult.committedDeltas
        conflictedDelta = commitResult.conflictedDeltas
        job.unscheduledTasks -= commitResult.committedDeltas.length
        scheduler.dynamicSimulator.log("%d tasks successfully committed for job %d."
          .format(commitResult.committedDeltas.length, job.id))
        scheduler.numSuccessfulTaskTransactions += commitResult.committedDeltas.length
        scheduler.numFailedTaskTransactions += commitResult.conflictedDeltas.length
        if (job.numSchedulingAttempts > 1)
          scheduler.numRetriedTransactions += 1

        // Record job-level stats.
        if (commitResult.conflictedDeltas.length == 0) {
          scheduler.numSuccessfulTransactions += 1
          scheduler.incrementDailycounter(scheduler.dailySuccessTransactions)
          scheduler.recordUsefulTimeScheduling(job,
            jobThinkTime,
            job.numSchedulingAttempts == 1)
        } else {
          scheduler.numFailedTransactions += 1
          scheduler.incrementDailycounter(scheduler.dailyFailedTransactions)
          // omegaSimulator.log("adding %f seconds to wastedThinkTime counter."
          //                   .format(jobThinkTime))
          scheduler.recordWastedTimeScheduling(job,
            jobThinkTime,
            job.numSchedulingAttempts == 1)
          // omegaSimulator.log(("Transaction task CONFLICTED for job-%d on " +
          //                     "machines %s.")
          //                    .format(job.id,
          //                            commitResult.conflictedDeltas.map(_.machineID)
          //                            .mkString(", ")))
        }
      } else {
        scheduler.simulator.log(("Not enough resources of the right shape were " +
          "available to schedule even one task of job %d, " +
          "so not submitting a transaction.").format(job.id))
        scheduler.numNoResourcesFoundSchedulingAttempts += 1
      }

      var jobEventType = "" // Set this conditionally below; used in logging.
      // If the job isn't yet fully scheduled, put it back in the queue.
      if (job.unscheduledTasks > 0) {
        // Give up on a job if (a) it hasn't scheduled a single task in
        // 100 tries or (b) it hasn't finished scheduling after 1000 tries.
        if ((job.numSchedulingAttempts > 100 &&
          job.unscheduledTasks == job.numTasks) ||
          job.numSchedulingAttempts > 1000) {
          println(("Abandoning job %d (%f cpu %f mem) with %d/%d " +
            "remaining tasks, after %d scheduling " +
            "attempts.").format(job.id,
            job.cpusPerTask,
            job.memPerTask,
            job.unscheduledTasks,
            job.numTasks,
            job.numSchedulingAttempts))
          scheduler.numJobsTimedOutScheduling += 1
          jobEventType = "abandoned"
        } else {
          scheduler.simulator.log(("Job %d still has %d unscheduled tasks, adding it " +
            "back to scheduler %s's job queue.")
            .format(job.id, job.unscheduledTasks, name))
          if((scheduler.simulator.cellState.numberOfMachinesOn) < scheduler.simulator.cellState.numMachines){
            scheduler.recordWastedTimeSchedulingPowering(job, jobThinkTime + (scheduler.simulator.cellState.powerOnTime/4+0.1))
            scheduler.simulator.afterDelay(scheduler.simulator.cellState.powerOnTime/4+0.1) {
              scheduler.addJob(job)
            }
          }
          else{
            scheduler.simulator.afterDelay(1) {
              scheduler.addJob(job)
            }
          }
        }
      } else {
        // All tasks in job scheduled so don't put it back in pendingQueue.
        jobEventType = "fully-scheduled"
      }
      //TODO: Buen sitio para la lógica de encender
      if(scheduler.dynamicSimulator.cellState.numberOfMachinesOn < scheduler.dynamicSimulator.cellState.numMachines){
        scheduler.simulator.powerOn.powerOn(scheduler.dynamicSimulator.cellState, job, "omega", commitedDelta, conflictedDelta)
      }
      if (!jobEventType.equals("")) {
        // println("%s %s %d %s %d %d %f"
        //         .format(Thread.currentThread().getId(),
        //                 name,
        //                 hashCode(),
        //                 jobEventType,
        //                 job.id,
        //                 job.numSchedulingAttempts,
        //                 simulator.currentTime - job.submitted))
      }

      scheduler.dynamicSimulator.log("Set " + name + " scheduling to FALSE")
      scheduler.scheduling = false
      // Keep trying to schedule as long as we have jobs in the queue.
      if (scheduler.chosenStrategy.name=="Omega" && !scheduler.pendingQueue.isEmpty) {
        scheduler.scheduling = true
        handleJob(scheduler.pendingQueue.dequeue)
      }
      /*else if(scheduler.chosenStrategy.name == "Mesos"){
        scheduler.chosenStrategy.start()
      }*/
    }
  }

  def syncCellState {
    scheduler.checkRegistered
    privateCellState = scheduler.dynamicSimulator.cellState.copy
    scheduler.simulator.log("%s synced private cellstate.".format(name))
    // println("Scheduler %s (%d) has new private cell state %d"
    //         .format(name, hashCode, privateCellState.hashCode))
  }

  override def start(): Unit = {
    if(!scheduler.pendingQueue.isEmpty){
      handleJob(scheduler.pendingQueue.dequeue)
    }
  }

}
