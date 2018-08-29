package dynamic.strategies
import ClusterSchedulingSimulation.Job
import dynamic.{DynamicScheduler}

class MonolithicStrategy(sched : DynamicScheduler) extends RMStrategy {
  override val name: String = "Monolithic"
  override var scheduler: DynamicScheduler = sched

  override def addJob(job: Job): Unit = {
    job.lastEnqueued = scheduler.simulator.currentTime
    scheduler.pendingQueue.enqueue(job)
    scheduler.simulator.log("enqueued job " + job.id)
    if (!scheduler.scheduling)
      scheduleNextJobAction()
  }

  def scheduleNextJobAction(): Unit = {
    assert(scheduler.simulator != null, "This scheduler has not been added to a " +
      "simulator yet.")
    if (!scheduler.scheduling && !scheduler.pendingQueue.isEmpty) {
      scheduler.scheduling = true
      val job = scheduler.pendingQueue.dequeue
      job.updateTimeInQueueStats(scheduler.simulator.currentTime)
      job.lastSchedulingStartTime = scheduler.simulator.currentTime
      val thinkTime = scheduler.getThinkTime(job)
      scheduler.simulator.log("getThinkTime returned " + thinkTime)
      scheduler.simulator.afterDelay(thinkTime) {
        scheduler.simulator.log(("Scheduler %s finished scheduling job %d. " +
          "Attempting to schedule next job in scheduler's " +
          "pendingQueue.").format(name, job.id))
        job.numSchedulingAttempts += 1
        job.numTaskSchedulingAttempts += job.unscheduledTasks
        val claimDeltas = scheduler.scheduleJob(job, scheduler.simulator.cellState)
        if (claimDeltas.length > 0) {
          scheduler.simulator.cellState.scheduleEndEvents(claimDeltas)
          job.unscheduledTasks -= claimDeltas.length
          scheduler.simulator.log("scheduled %d tasks of job %d's, %d remaining."
            .format(claimDeltas.length, job.id, job.unscheduledTasks))
          scheduler.numSuccessfulTransactions += 1
          scheduler.recordUsefulTimeScheduling(job,
            thinkTime,
            job.numSchedulingAttempts == 1)
        } else {
          scheduler.simulator.log(("No tasks scheduled for job %d (%f cpu %f mem) " +
            "during this scheduling attempt, not recording " +
            "any busy time. %d unscheduled tasks remaining.")
            .format(job.id,
              job.cpusPerTask,
              job.memPerTask,
              job.unscheduledTasks))
        }
        var jobEventType = "" // Set this conditionally below; used in logging.
        // If the job isn't yet fully scheduled, put it back in the queue.
        if (job.unscheduledTasks > 0) {
          //println(("LLega el job %d con %d tareas restantes sin schedulear del total %d").format(job.id, job.unscheduledTasks, job.numTasks))
          scheduler.simulator.log(("Job %s didn't fully schedule, %d / %d tasks remain " +
            "(shape: %f cpus, %f mem). Putting it " +
            "back in the queue").format(job.id,
            job.unscheduledTasks,
            job.numTasks,
            job.cpusPerTask,
            job.memPerTask))
          // Give up on a job if (a) it hasn't scheduled a single task in
          // 100 tries or (b) it hasn't finished scheduling after 1000 tries.
          if ((job.numSchedulingAttempts > 100 &&
            job.unscheduledTasks == job.numTasks) ||
            job.numSchedulingAttempts > 1000) {
            println(("Abandoning job %d (%f cpu %f mem) with %d/%d " +
              "remaining tasks and %d security level, after %d scheduling " +
              "attempts.").format(job.id,
              job.cpusPerTask,
              job.memPerTask,
              job.unscheduledTasks,
              job.numTasks,
              job.security,
              job.numSchedulingAttempts))
            scheduler.numJobsTimedOutScheduling += 1
            jobEventType = "abandoned"
          } else {
            //FIXME: Tenemos que tener en cuenta las máquinas que se están encendiendo?
            if ((scheduler.simulator.cellState.numberOfMachinesOn) < scheduler.simulator.cellState.numMachines) {
              scheduler.recordWastedTimeSchedulingPowering(job, scheduler.simulator.cellState.powerOnTime / 4 + 0.1)
              scheduler.simulator.afterDelay(scheduler.simulator.cellState.powerOnTime / 4 + 0.1) {
                addJob(job)
              }
            }
            else {
              scheduler.simulator.afterDelay(1) {
                addJob(job)
              }
            }
          }
        } else {
          // All tasks in job scheduled so don't put it back in pendingQueue.
          jobEventType = "fully-scheduled"
        }
        //TODO: Stackelberg
        scheduler.simulator.stackelberg.play(claimDeltas, scheduler.simulator.cellState, job, scheduler, scheduler.simulator)
        if (scheduler.simulator.cellState.numberOfMachinesOn < scheduler.simulator.cellState.numMachines) {
          scheduler.simulator.powerOn.powerOn(scheduler.simulator.cellState, job, "monolithic")
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
        scheduler.scheduling = false
        scheduleNextJobAction()
      }
      scheduler.simulator.log("Scheduler named '%s' started scheduling job %d "
        .format(name, job.id))
    }
  }

  override def start(): Unit = {}

}
