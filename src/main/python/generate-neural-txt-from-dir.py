import sys, os, re
import logging
import collections
from collections import defaultdict
import cluster_simulation_protos_pb2
import numpy

logging.basicConfig(level=logging.DEBUG)

def usage():
    print "usage: generate-neural-txt-from-dir.py <input_directory_name> <optional: base name for output files. (defaults to inputdir)>"
    sys.exit(1)

logging.debug("len(sys.argv): " + str(len(sys.argv)))

if len(sys.argv) < 2:
    logging.error("Not enough arguments provided.")
    usage()

try:
    input_dir_name = sys.argv[1]
    # Start optional args.
    if len(sys.argv) == 3:
        outfile_name_base = str(sys.argv[2])
    else:
        #make the output files the same as the input but add .txt to end
        outfile_name_base = os.path.join(input_dir_name, "neural.txt")

except:
    usage()

#input_dir_name = "/Users/damianfernandez/IdeaProjects/cluster-scheduler-simulator/experiment_results/prueba-4"
#outfile_name_base = os.path.join(input_dir_name, "neural.txt")

logging.info("Input directory: %s" % input_dir_name)

protobufs_to_iterate = {}

for filename in os.listdir(input_dir_name):
    if filename.endswith(".protobuf"):
        if "mesos" in filename:
            protobufs_to_iterate["mesos"] = os.path.join(input_dir_name, filename)
        elif "omega" in filename:
            protobufs_to_iterate["omega"] = os.path.join(input_dir_name, filename)
        elif "monolithic" in filename:
            protobufs_to_iterate["monolithic"] = os.path.join(input_dir_name, filename)
        continue
    else:
        continue

result_dict = collections.OrderedDict()

for scheduler, protobuf in protobufs_to_iterate.iteritems():

    # Read in the ExperimentResultSet.
    experiment_result_set = cluster_simulation_protos_pb2.ExperimentResultSet()
    infile = open(protobuf, "rb")
    experiment_result_set.ParseFromString(infile.read())
    infile.close()

    logging.debug("Processing %d experiment envs."
                  % len(experiment_result_set.experiment_env))
    for env in experiment_result_set.experiment_env:

        logging.debug("Handling experiment env (%s %s)."
                      % (env.cell_name, env.workload_split_type))
        logging.debug("Processing %d experiment results."
                      % len(env.experiment_result))
        prev_l_val = -1.0
        for exp_result in env.experiment_result:
            logging.debug("Handling experiment result with C = %f and L = %f."
                          % (exp_result.constant_think_time,
                             exp_result.per_task_think_time))
            params = {}
            params["resource_utilization"] = exp_result.cell_state_avg_cpu_utilization
            params["inter_arrival"] = exp_result.avg_job_interarrival_time
            logging.debug("Processing %d workload stats."
                          % len(exp_result.workload_stats))
            for workload_stat in exp_result.workload_stats:
                params[workload_stat.workload_name + "_" + "num_jobs_total"] = workload_stat.num_jobs
                params[workload_stat.workload_name + "_" + "num_jobs_scheduled"] = workload_stat.num_jobs_scheduled
                params[workload_stat.workload_name + "_" + "queue_first"] = workload_stat.avg_job_queue_times_till_first_scheduled
                params[workload_stat.workload_name + "_" + "queue_fully"] = workload_stat.avg_job_queue_times_till_fully_scheduled
                params[workload_stat.workload_name + "_" + "makespan"] = workload_stat.avg_makespan
                params[workload_stat.workload_name + "_" + "makespan_90p"] = workload_stat.makespan_90_percentile
                params[workload_stat.workload_name + "_" + "cpu_util"] = workload_stat.avg_cpu_util
                params[workload_stat.workload_name + "_" + "mem_util"] = workload_stat.avg_mem_util
                params[workload_stat.workload_name + "_" + "duration"] = workload_stat.avg_duration
                params[workload_stat.workload_name + "_" + "inter_arrival"] = workload_stat.avg_interarrival
                params[workload_stat.workload_name + "_" + "tasks"] = workload_stat.avg_tasks


            for param_name, value in params.iteritems():
                if param_name in result_dict:
                    if scheduler in result_dict[param_name]:
                        result_dict[param_name][scheduler].append(value)
                    else:
                        #dict1 = result_dict[param_name]
                        #dict1[scheduler] = [value]
                        result_dict[param_name][scheduler] = [value]
                else:
                    sched_dict = {scheduler: [value]}
                    result_dict[param_name] = sched_dict


#print(str(result_dict))
# Create output files.
# One output file for each unique (cell_name, scheduler_name, metric) tuple.
no_numpty_list = []
val_matrix = None
header_row = ""
body_rows = ""
for param_name, dict in result_dict.iteritems():
    for scheduler_name, values in dict.iteritems():
        header_row += param_name+"_"+scheduler_name+" "
        if val_matrix is None:
            val_matrix = numpy.empty([len(result_dict)*len(dict), len(values)])
        numpy.append(val_matrix, values)
        no_numpty_list.append(values)
header_row += "\n"
results_mat = map(list, zip(*no_numpty_list))
for r in results_mat:
    for c in r:
        body_rows += str(c) + " "
    body_rows += "\n"
# val_matrix = val_matrix.transpose()
#
# for x in xrange(val_matrix.shape[0]):
#     for y in xrange(val_matrix.shape[1]):
#         body_rows += str(val_matrix[x][y])+" "
#     body_rows += "\n"


logging.info("Creating output file: %s" % outfile_name_base)
outfile = open(outfile_name_base, "w")

outfile.write(header_row)
outfile.write(body_rows)
outfile.close()
