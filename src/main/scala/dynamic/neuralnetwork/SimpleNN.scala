package dynamic.neuralnetwork

import breeze.linalg.DenseMatrix

import scala.collection.mutable.ArrayBuffer

object SimpleNN {

  val sigmoid = new SigmoidFunction(1)
  val neuralnetwork: NeuralNetwork = new FeedForwardNeuralNetwork(List(2, 10, 1), sigmoid, 0.2)
  val maxUtilization = 0.8945
  val minUtilization = 0.2741
  val maxInterArrival = 206.6170
  val minInterArrival = 0.9083
  val spreadUtilization = maxUtilization - minUtilization
  val spreadInterArrival = maxInterArrival - minInterArrival


  {
    val weightInputs = Array(0.011190105378687587,-0.08434274779897205,-0.202398242592499,0.29501224579408386,-0.35122179550682314,0.8090237100717032,0.2443619860275958,-0.1614125079732906,0.3020015975470881,-0.7810058414814665,0.5102701650148854,0.31771113788458405,0.33619693221279506,-0.3074162347859737,-0.46696746746536455,0.04533618122362818,-0.5504490738003761,-0.799052287659506,-0.020405333654646127,0.2747801040397965,-0.004327379404863021,-0.441133909860387,0.1596651260078601,-1.1155408494870929,-0.2675410430020164,-1.1700104893282253,-0.9432302333162453,0.30348001123464763,-1.1071080608143287,-1.0911024337093285,-1.5045726019257444,2.287966577439836,0.18855822846496448)
    val weightOutputs = Array(-1.211941111734196,0.9052422874138409,-0.15226462743920166,1.1003953272694587,0.9587535448741134,-0.07380991677074977,1.0748704251462673,1.2327689734523153,1.5529406435371613,-2.189634214313649,-0.03449402258612176)
    neuralnetwork.setWeights(ArrayBuffer(DenseMatrix.create(11, 3, weightInputs), DenseMatrix.create(1, 11, weightOutputs)))
  }

  def classify(utilization: Double, interArrival: Double): String = {
    //Normalization, not the minimum code, but understandable
    var util = utilization
    var inter = interArrival
    //println(util + " " + inter)
    if(utilization < minUtilization){
      util = minUtilization
    }
    else if(utilization > maxUtilization){
      util = maxUtilization
    }

    if(interArrival < minInterArrival){
      inter = minInterArrival
    }
    else if(interArrival > maxInterArrival){
      inter = maxInterArrival
    }

    if (spreadUtilization != 0) {
      util = (util - minUtilization) / spreadUtilization
    }

    if (spreadInterArrival != 0) {
      inter = (inter - minInterArrival) / spreadInterArrival
    }
    val result = neuralnetwork.classify(ArrayBuffer(util, inter)).map(sigmoid.customRound(_))
    if (result(0) == 1) "Mesos" else "Omega"
  }

}
