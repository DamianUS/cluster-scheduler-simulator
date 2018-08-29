package dynamic.strategies

import ClusterSchedulingSimulation.{ClaimDelta, Job, Offer}
import dynamic.{DynamicScheduler, DynamicSimulator}

class MesosStrategy(sched : DynamicScheduler) extends RMStrategy {
  override val name: String = "Mesos"
  override var scheduler: DynamicScheduler = sched


  override
  def addJob(job: Job) = {
    assert(scheduler.dynamicSimulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
    scheduler.dynamicSimulator.log("========================================================")
    scheduler.dynamicSimulator.log("addJOB: CellState total usage: %fcpus (%.1f%s), %fmem (%.1f%s)."
      .format(scheduler.dynamicSimulator.cellState.totalOccupiedCpus,
        scheduler.dynamicSimulator.cellState.totalOccupiedCpus /
          scheduler.dynamicSimulator.cellState.totalCpus * 100.0,
        "%",
        scheduler.dynamicSimulator.cellState.totalOccupiedMem,
        scheduler.dynamicSimulator.cellState.totalOccupiedMem /
          scheduler.dynamicSimulator.cellState.totalMem * 100.0,
        "%"))
    //super.addJob(job)
    scheduler.pendingQueue.enqueue(job)
    scheduler.dynamicSimulator.log("Enqueued job %d of workload type %s."
      .format(job.id, job.workloadName))
    scheduler.dynamicSimulator.allocator.requestOffer(scheduler)
  }

  def handleNextResourceOffer(): Unit = {
    // We essentially synchronize access to this scheduling logic
    // via the scheduling variable. We aren't protecting this from real
    // parallelism, but rather from discrete-event-simlation style parallelism.
    if(!scheduler.scheduling && !scheduler.offerQueue.isEmpty) {
      scheduler.scheduling = true
      val offer = scheduler.offerQueue.dequeue()
      // Use this offer to attempt to schedule jobs.
      scheduler.dynamicSimulator.log("------ In %s.resourceOffer(offer %d).".format(name, offer.id))
      val offerResponse = collection.mutable.ListBuffer[ClaimDelta]()
      var aggThinkTime: Double = 0.0
      var lastJobScheduled : Option[Job] = None
      // TODO(andyk): add an efficient method to CellState that allows us to
      //              check the largest slice of available resources to decode
      //              if we should keep trying to schedule or not.
      while (offer.cellState.availableCpus > 0.000001 &&
        offer.cellState.availableMem > 0.000001 &&
        !scheduler.pendingQueue.isEmpty) {
        val job = scheduler.pendingQueue.dequeue
        lastJobScheduled = Some(job)
        job.updateTimeInQueueStats(scheduler.dynamicSimulator.currentTime)
        val jobThinkTime = scheduler.getThinkTime(job)
        aggThinkTime += jobThinkTime
        job.numSchedulingAttempts += 1
        job.numTaskSchedulingAttempts += job.unscheduledTasks

        // Before calling the expensive scheduleJob() function, check
        // to see if one of this job's tasks could fit into the sum of
        // *all* the currently free resources in the offers' cell state.
        // If one can't, then there is no need to call scheduleJob(). If
        // one can, we call scheduleJob(), though we still might not fit
        // any tasks due to fragmentation.
        if (offer.cellState.availableCpus > job.cpusPerTask &&
          offer.cellState.availableMem > job.cpusPerTask) {
          // Schedule the job using the cellstate in the ResourceOffer.
          val claimDeltas = scheduler.scheduleJob(job, offer.cellState)
          if(claimDeltas.length > 0) {
            scheduler.numSuccessfulTransactions += 1
            scheduler.recordUsefulTimeScheduling(job,
              jobThinkTime,
              job.numSchedulingAttempts == 1)
            scheduler.dynamicSimulator.log(("Setting up job %d to accept at least " +
              "part of offer %d. About to spend %f seconds " +
              "scheduling it. Assigning %d tasks to it.")
              .format(job.id, offer.id, jobThinkTime,
                claimDeltas.length))
            offerResponse ++= claimDeltas
            job.unscheduledTasks -= claimDeltas.length
          } else {
            scheduler.dynamicSimulator.log(("Rejecting all of offer %d for job %d, " +
              "which requires tasks with %f cpu, %f mem. " +
              "Not counting busy time for this sched attempt.")
              .format(offer.id,
                job.id,
                job.cpusPerTask,
                job.memPerTask))
            scheduler.numNoResourcesFoundSchedulingAttempts += 1
          }
        } else {
          scheduler.dynamicSimulator.log(("Short-path rejecting all of offer %d for " +
            "job %d because a single one of its tasks " +
            "(%f cpu, %f mem) wouldn't fit into the sum " +
            "of the offer's private cell state's " +
            "remaining resources (%f cpu, %f mem).")
            .format(offer.id,
              job.id,
              job.cpusPerTask,
              job.memPerTask,
              offer.cellState.availableCpus,
              offer.cellState.availableMem))
        }

        var jobEventType = "" // Set this conditionally below; used in logging.
        // If job is only partially scheduled, put it back in the pendingQueue.
        if (job.unscheduledTasks > 0) {
          scheduler.dynamicSimulator.log(("Job %d is [still] only partially scheduled, " +
            "(%d out of %d its tasks remain unscheduled) so " +
            "putting it back in the queue.")
            .format(job.id,
              job.unscheduledTasks,
              job.numTasks))
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
            //FIXME: Tenemos que tener en cuenta las máquinas que se están encendiendo?
            if((scheduler.dynamicSimulator.cellState.numberOfMachinesOn) < scheduler.dynamicSimulator.cellState.numMachines){
              scheduler.recordWastedTimeSchedulingPowering(job, scheduler.dynamicSimulator.cellState.powerOnTime/4+0.1)
              scheduler.dynamicSimulator.afterDelay(scheduler.dynamicSimulator.cellState.powerOnTime/4+0.1) {
                scheduler.addJob(job)
              }
            }
            else{
              scheduler.dynamicSimulator.afterDelay(1) {
                scheduler.addJob(job)
              }
            }
          }
          job.lastEnqueued = scheduler.dynamicSimulator.currentTime
        } else {
          // All tasks in job scheduled so not putting it back in pendingQueue.
          jobEventType = "fully-scheduled"
        }
        if (!jobEventType.equals("")) {
          // Print some stats that we can use to generate CDFs of the job
          // # scheduling attempts and job-time-till-scheduled.
          // println("%s %s %d %s %d %d %f"
          //         .format(Thread.currentThread().getId(),
          //                 name,
          //                 hashCode(),
          //                 jobEventType,
          //                 job.id,
          //                 job.numSchedulingAttempts,
          //                 simulator.currentTime - job.submitted))
        }
      }

      if (scheduler.pendingQueue.isEmpty) {
        // If we have scheduled everything, notify the allocator that we
        // don't need resources offers until we request them again (which
        // we will do when another job is added to our pendingQueue.
        // Do this before we reply to the offer since the allocator may make
        // its next round of offers shortly after we respond to this offer.
        scheduler.dynamicSimulator.log(("After scheduling, %s's pending queue is " +
          "empty, canceling outstanding " +
          "resource request.").format(name))
        scheduler.dynamicSimulator.allocator.cancelOfferRequest(scheduler)
      } else {
        scheduler.dynamicSimulator.log(("%s's pending queue still has %d jobs in it, but " +
          "for some reason, they didn't fit into this " +
          "offer, so it will patiently wait for more " +
          "resource offers.").format(name, scheduler.pendingQueue.size))
      }

      // Send our response to this offer.
      scheduler.dynamicSimulator.afterDelay(aggThinkTime) {
        scheduler.dynamicSimulator.log(("Waited %f seconds of aggThinkTime, now " +
          "responding to offer %d with %d responses after.")
          .format(aggThinkTime, offer.id, offerResponse.length))
        respondToOffer(offer, offerResponse, lastJobScheduled.getOrElse(null))
      }
      // Done with this offer, see if we have another one to handle.
      scheduler.scheduling = false
      if(scheduler.chosenStrategy.name=="Mesos"){
        handleNextResourceOffer()
      }
    }
  }


  def respondToOffer(offer: Offer, claimDeltas: Seq[ClaimDelta], requestJob: Job) = {
    scheduler.checkRegistered
    scheduler.dynamicSimulator.log(("------Scheduler %s responded to offer %d with " +
      "%d claimDeltas.")
      .format(offer.scheduler.name, offer.id, claimDeltas.length))

    // Look up, unapply, & discard the saved deltas associated with the offerid.
    // This will cause the framework to stop being charged for the resources that
    // were locked while he made his scheduling decision.
    assert(scheduler.dynamicSimulator.allocator.offeredDeltas.contains(offer.id),
      "Allocator received response to offer that is not on record.")
    scheduler.dynamicSimulator.allocator.offeredDeltas.remove(offer.id).foreach(savedDeltas => {
      savedDeltas.foreach(_.unApply(cellState = scheduler.dynamicSimulator.cellState,
        locked = true))
    })
    scheduler.dynamicSimulator.log("========================================================")
    scheduler.dynamicSimulator.log("AFTER UNAPPLYING SAVED DELTAS")
    scheduler.dynamicSimulator.log("CellState total usage: %fcpus (%.1f%s), %fmem (%.1f%s)."
      .format(scheduler.dynamicSimulator.cellState.totalOccupiedCpus,
        scheduler.dynamicSimulator.cellState.totalOccupiedCpus /
          scheduler.dynamicSimulator.cellState.totalCpus * 100.0,
        "%",
        scheduler.dynamicSimulator.cellState.totalOccupiedMem,
        scheduler.dynamicSimulator.cellState.totalOccupiedMem /
          scheduler.dynamicSimulator.cellState.totalMem * 100.0,
        "%"))
    scheduler.dynamicSimulator.log("Committing all %d deltas that were part of response %d "
      .format(claimDeltas.length, offer.id))
    // commit() all deltas that were part of the offer response, don't use
    // the option of having cell state create the end events for us since we
    // want to add code to the end event that triggers another resource offer.
    if (claimDeltas.length > 0) {
      val commitResult = scheduler.dynamicSimulator.cellState.commit(claimDeltas, false)
      assert(commitResult.conflictedDeltas.length == 0,
        "Expecting no conflicts, but there were %d."
          .format(commitResult.conflictedDeltas.length))

      // Create end events for all tasks committed.
      commitResult.committedDeltas.foreach(delta => {
        scheduler.dynamicSimulator.afterDelay(delta.duration) {
          delta.unApply(scheduler.dynamicSimulator.cellState)
          scheduler.dynamicSimulator.log(("A task started by scheduler %s finished. " +
            "Freeing %f cpus, %f mem. Available: %f cpus, %f " +
            "mem. Also, triggering a new batched offer round.")
            .format(delta.scheduler.name,
              delta.cpus,
              delta.mem,
              scheduler.dynamicSimulator.cellState.availableCpus,
              scheduler.dynamicSimulator.cellState.availableMem))
          //FIXME:Esto tiene sentido?
          if(scheduler.chosenStrategy.name=="Mesos"){
            scheduler.dynamicSimulator.allocator.schedBuildAndSendOffer()
          }
        }
      })
    }
    //TODO: Buen sitio para la lógica de encender
    if(scheduler.dynamicSimulator.cellState.numberOfMachinesOn < scheduler.dynamicSimulator.cellState.numMachines){
      scheduler.dynamicSimulator.powerOn.powerOn(scheduler.dynamicSimulator.cellState, requestJob, "mesos")
    }
    if(scheduler.chosenStrategy.name=="Mesos"){
      scheduler.dynamicSimulator.allocator.schedBuildAndSendOffer()
    }
  }


  override def start(): Unit = {
    scheduler.dynamicSimulator.allocator.schedBuildAndSendOffer()
  }

  def removeOffers(): Unit = {
    //println("Entra en removeOffers "+simulator.currentTime.toString)
    //for (offer <- offerQueue.filter(_.scheduler == this)) {
    for (offer <- scheduler.offerQueue.dequeueAll(_.scheduler == this)) {
      //println("Había offers pendientes "+simulator.currentTime.toString)
      scheduler.dynamicSimulator.allocator.offeredDeltas.remove(offer.id).foreach(savedDeltas => {
        savedDeltas.foreach(_.unApply(cellState = scheduler.dynamicSimulator.cellState,
          locked = true))
      })
    }
    scheduler.dynamicSimulator.allocator.cancelOfferRequest(scheduler)
  }


  /*override
  def addJob(job: Job) = {
    scheduler.dynamicSimulator.log("========================================================")
    scheduler.dynamicSimulator.log("addJOB: CellState total usage: %fcpus (%.1f%s), %fmem (%.1f%s)."
      .format(scheduler.dynamicSimulator.cellState.totalOccupiedCpus,
        scheduler.dynamicSimulator.cellState.totalOccupiedCpus /
          scheduler.dynamicSimulator.cellState.totalCpus * 100.0,
        "%",
        scheduler.dynamicSimulator.cellState.totalOccupiedMem,
        scheduler.dynamicSimulator.cellState.totalOccupiedMem /
          scheduler.dynamicSimulator.cellState.totalMem * 100.0,
        "%"))
    scheduler.pendingQueue.enqueue(job)
    scheduler.dynamicSimulator.log("Enqueued job %d of workload type %s."
      .format(job.id, job.workloadName))
    scheduler.dynamicSimulator.allocator.requestOffer(scheduler)
  }


  def handleNextResourceOffer(): Unit = {
    // We essentially synchronize access to this scheduling logic
    // via the scheduling variable. We aren't protecting this from real
    // parallelism, but rather from discrete-event-simlation style parallelism.
    if(!scheduler.scheduling && !scheduler.offerQueue.isEmpty) {
      scheduler.scheduling = true
      val offer = scheduler.offerQueue.dequeue()
      // Use this offer to attempt to schedule jobs.
      scheduler.dynamicSimulator.log("------ In %s.resourceOffer(offer %d).".format(name, offer.id))
      val offerResponse = collection.mutable.ListBuffer[ClaimDelta]()
      var aggThinkTime: Double = 0.0
      var lastJobScheduled : Option[Job] = None
      // TODO(andyk): add an efficient method to CellState that allows us to
      //              check the largest slice of available resources to decode
      //              if we should keep trying to schedule or not.
      while (offer.cellState.availableCpus > 0.000001 &&
        offer.cellState.availableMem > 0.000001 &&
        !scheduler.pendingQueue.isEmpty) {
        val job = scheduler.pendingQueue.dequeue
        lastJobScheduled = Some(job)
        job.updateTimeInQueueStats(scheduler.dynamicSimulator.currentTime)
        val jobThinkTime = scheduler.getThinkTime(job)
        aggThinkTime += jobThinkTime
        job.numSchedulingAttempts += 1
        job.numTaskSchedulingAttempts += job.unscheduledTasks

        // Before calling the expensive scheduleJob() function, check
        // to see if one of this job's tasks could fit into the sum of
        // *all* the currently free resources in the offers' cell state.
        // If one can't, then there is no need to call scheduleJob(). If
        // one can, we call scheduleJob(), though we still might not fit
        // any tasks due to fragmentation.
        if (offer.cellState.availableCpus > job.cpusPerTask &&
          offer.cellState.availableMem > job.cpusPerTask) {
          // Schedule the job using the cellstate in the ResourceOffer.
          val claimDeltas = scheduler.scheduleJob(job, offer.cellState)
          if(claimDeltas.length > 0) {
            scheduler.numSuccessfulTransactions += 1
            scheduler.recordUsefulTimeScheduling(job,
              jobThinkTime,
              job.numSchedulingAttempts == 1)
            scheduler.dynamicSimulator.log(("Setting up job %d to accept at least " +
              "part of offer %d. About to spend %f seconds " +
              "scheduling it. Assigning %d tasks to it.")
              .format(job.id, offer.id, jobThinkTime,
                claimDeltas.length))
            offerResponse ++= claimDeltas
            job.unscheduledTasks -= claimDeltas.length
          } else {
            scheduler.dynamicSimulator.log(("Rejecting all of offer %d for job %d, " +
              "which requires tasks with %f cpu, %f mem. " +
              "Not counting busy time for this sched attempt.")
              .format(offer.id,
                job.id,
                job.cpusPerTask,
                job.memPerTask))
            scheduler.numNoResourcesFoundSchedulingAttempts += 1
          }
        } else {
          scheduler.dynamicSimulator.log(("Short-path rejecting all of offer %d for " +
            "job %d because a single one of its tasks " +
            "(%f cpu, %f mem) wouldn't fit into the sum " +
            "of the offer's private cell state's " +
            "remaining resources (%f cpu, %f mem).")
            .format(offer.id,
              job.id,
              job.cpusPerTask,
              job.memPerTask,
              offer.cellState.availableCpus,
              offer.cellState.availableMem))
        }

        var jobEventType = "" // Set this conditionally below; used in logging.
        // If job is only partially scheduled, put it back in the pendingQueue.
        if (job.unscheduledTasks > 0) {
          scheduler.dynamicSimulator.log(("Job %d is [still] only partially scheduled, " +
            "(%d out of %d its tasks remain unscheduled) so " +
            "putting it back in the queue.")
            .format(job.id,
              job.unscheduledTasks,
              job.numTasks))
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
            //FIXME: Tenemos que tener en cuenta las máquinas que se están encendiendo?
            if((scheduler.dynamicSimulator.cellState.numberOfMachinesOn) < scheduler.dynamicSimulator.cellState.numMachines){
              scheduler.recordWastedTimeSchedulingPowering(job, scheduler.dynamicSimulator.cellState.powerOnTime/4+0.1)
              scheduler.dynamicSimulator.afterDelay(scheduler.dynamicSimulator.cellState.powerOnTime/4+0.1) {
                scheduler.addJob(job)
              }
            }
            else{
              scheduler.dynamicSimulator.afterDelay(1) {
                scheduler.addJob(job)
              }
            }
          }
          job.lastEnqueued = scheduler.dynamicSimulator.currentTime
        } else {
          // All tasks in job scheduled so not putting it back in pendingQueue.
          jobEventType = "fully-scheduled"
        }
        if (!jobEventType.equals("")) {
          // Print some stats that we can use to generate CDFs of the job
          // # scheduling attempts and job-time-till-scheduled.
          // println("%s %s %d %s %d %d %f"
          //         .format(Thread.currentThread().getId(),
          //                 name,
          //                 hashCode(),
          //                 jobEventType,
          //                 job.id,
          //                 job.numSchedulingAttempts,
          //                 simulator.currentTime - job.submitted))
        }
      }

      if (scheduler.pendingQueue.isEmpty || scheduler.chosenStrategy != "Mesos") {
        // If we have scheduled everything, notify the allocator that we
        // don't need resources offers until we request them again (which
        // we will do when another job is added to our pendingQueue.
        // Do this before we reply to the offer since the allocator may make
        // its next round of offers shortly after we respond to this offer.
        scheduler.dynamicSimulator.log(("After scheduling, %s's pending queue is " +
          "empty, canceling outstanding " +
          "resource request.").format(name))
        scheduler.dynamicSimulator.allocator.cancelOfferRequest(scheduler)
      } else {
        scheduler.dynamicSimulator.log(("%s's pending queue still has %d jobs in it, but " +
          "for some reason, they didn't fit into this " +
          "offer, so it will patiently wait for more " +
          "resource offers.").format(name, scheduler.pendingQueue.size))
      }

      // Send our response to this offer.
      scheduler.dynamicSimulator.afterDelay(aggThinkTime) {
        scheduler.dynamicSimulator.log(("Waited %f seconds of aggThinkTime, now " +
          "responding to offer %d with %d responses after.")
          .format(aggThinkTime, offer.id, offerResponse.length))
        respondToOffer(offer, offerResponse, lastJobScheduled.getOrElse(null))
      }
      // Done with this offer, see if we have another one to handle.
      scheduler.scheduling = false
      if(scheduler.chosenStrategy.name == "Mesos"){
        handleNextResourceOffer()
      }
      else{
        removeOffers();
      }
    }
  }


  def respondToOffer(offer: Offer, claimDeltas: Seq[ClaimDelta], requestJob: Job) = {
    scheduler.checkRegistered
    scheduler.dynamicSimulator.log(("------Scheduler %s responded to offer %d with " +
      "%d claimDeltas.")
      .format(offer.scheduler.name, offer.id, claimDeltas.length))

    // Look up, unapply, & discard the saved deltas associated with the offerid.
    // This will cause the framework to stop being charged for the resources that
    // were locked while he made his scheduling decision.
    assert(scheduler.dynamicSimulator.allocator.offeredDeltas.contains(offer.id),
      "Allocator received response to offer that is not on record.")
    scheduler.dynamicSimulator.allocator.offeredDeltas.remove(offer.id).foreach(savedDeltas => {
      savedDeltas.foreach(_.unApply(cellState = scheduler.dynamicSimulator.cellState,
        locked = true))
    })
    scheduler.dynamicSimulator.log("========================================================")
    scheduler.dynamicSimulator.log("AFTER UNAPPLYING SAVED DELTAS")
    scheduler.dynamicSimulator.log("CellState total usage: %fcpus (%.1f%s), %fmem (%.1f%s)."
      .format(scheduler.dynamicSimulator.cellState.totalOccupiedCpus,
        scheduler.dynamicSimulator.cellState.totalOccupiedCpus /
          scheduler.dynamicSimulator.cellState.totalCpus * 100.0,
        "%",
        scheduler.dynamicSimulator.cellState.totalOccupiedMem,
        scheduler.dynamicSimulator.cellState.totalOccupiedMem /
          scheduler.dynamicSimulator.cellState.totalMem * 100.0,
        "%"))
    scheduler.dynamicSimulator.log("Committing all %d deltas that were part of response %d "
      .format(claimDeltas.length, offer.id))
    // commit() all deltas that were part of the offer response, don't use
    // the option of having cell state create the end events for us since we
    // want to add code to the end event that triggers another resource offer.
    if (claimDeltas.length > 0) {
      val commitResult = scheduler.dynamicSimulator.cellState.commit(claimDeltas, false)
      assert(commitResult.conflictedDeltas.length == 0,
        "Expecting no conflicts, but there were %d."
          .format(commitResult.conflictedDeltas.length))

      // Create end events for all tasks committed.
      commitResult.committedDeltas.foreach(delta => {
        scheduler.dynamicSimulator.afterDelay(delta.duration) {
          delta.unApply(scheduler.dynamicSimulator.cellState)
          scheduler.dynamicSimulator.log(("A task started by scheduler %s finished. " +
            "Freeing %f cpus, %f mem. Available: %f cpus, %f " +
            "mem. Also, triggering a new batched offer round.")
            .format(delta.scheduler.name,
              delta.cpus,
              delta.mem,
              scheduler.dynamicSimulator.cellState.availableCpus,
              scheduler.dynamicSimulator.cellState.availableMem))
          if(scheduler.chosenStrategy == "Mesos"){
            scheduler.dynamicSimulator.allocator.schedBuildAndSendOffer()
          }
          //scheduler.chosenStrategy.start()
        }
      })
    }
    //TODO: Buen sitio para la lógica de encender
    if(scheduler.dynamicSimulator.cellState.numberOfMachinesOn < scheduler.dynamicSimulator.cellState.numMachines){
      scheduler.dynamicSimulator.powerOn.powerOn(scheduler.dynamicSimulator.cellState, requestJob, "mesos")
    }

    if(scheduler.chosenStrategy == "Mesos"){
      scheduler.dynamicSimulator.allocator.schedBuildAndSendOffer()
    }
    //scheduler.chosenStrategy.start()
  }

  override def start(): Unit = {
    scheduler.dynamicSimulator.allocator.schedBuildAndSendOffer()
  }*/
}
