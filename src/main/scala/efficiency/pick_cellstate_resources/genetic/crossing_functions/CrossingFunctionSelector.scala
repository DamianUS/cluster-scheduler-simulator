package efficiency.pick_cellstate_resources.genetic.crossing_functions

import ClusterSchedulingSimulation._
import efficiency.pick_cellstate_resources.genetic.fitness_functions.FitnessFunction

import scala.collection.mutable.{IndexedSeq, ListBuffer}

/**
 * Created by dfernandez on 11/1/16.
 */
trait CrossingFunctionSelector {
  /**
    * Selects the required number of candidates from the population with
    * the probability of selecting any particular candidate being proportional
    * to that candidate's fitness score.  Selection is with replacement (the same
    * candidate may be selected multiple times).
    * @param population The candidates to select from.
    * @param naturalFitnessScores True if higher fitness scores indicate fitter
    * individuals, false if lower fitness scores indicate fitter individuals.
    * @param selectionSize The number of selections to make.
    * @return The selected candidates.
    */
  def select(population : ListBuffer[ListBuffer[Int]], naturalFitnessScores : Boolean = false, selectionSize : Int, fitnessFunction : FitnessFunction, job: Job, cellState: CellState): ListBuffer[ListBuffer[Int]]



  val name : String
}
