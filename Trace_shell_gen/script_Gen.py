import csv
import random

################################### Used for default CapacityScheduler #####################################
# pi_command = "hadoopc jar ~/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-examples-2.6.4.jar pi"
# teragen_command = "hadoopc jar ~/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-examples-2.6.4.jar teragen -D mapred.map.tasks="
# wc_command = "hadoopc jar ~/hadoop/share/hadoop/mapreduce/hadoop-mapreduce-examples-2.6.4.jar wordcount"

################################### Used for SRPT without copies ###########################################
# pi_command = "hadoopc jar pi/0copy/QuasiMonteCarlo.jar QuasiMonteCarlo"
# teragen_command = "hadoopc jar terasort/TeraGen.jar TeraGen -D mapred.map.tasks="
# wc_command = "hadoopc jar wc/0copy/WordCount.jar WordCount"

################################### Used for SRPT without 1 copy ###########################################
pi_command = "hadoopc jar pi/1copy/QuasiMonteCarlo.jar QuasiMonteCarlo"
teragen_command = "hadoopc jar terasort/TeraGen.jar TeraGen -D mapred.map.tasks="
wc_command = "hadoopc jar wc/1copy/WordCount.jar WordCount"

################################### Used for SRPT with 2 copies ############################################
# pi_command = "hadoopc jar pi/2copy/QuasiMonteCarlo.jar QuasiMonteCarlo"
# teragen_command = "hadoopc jar terasort/TeraGen.jar TeraGen -D mapred.map.tasks="
# wc_command = "hadoopc jar wc/2copy/WordCount.jar WordCount"

pi_map = 1
pi_sample = 10000000
pi_coeff = float(750000000 / 11)
pi_intercept = -840909086
pi_count = 0

teragen_map = 1
teragen_size = 45000000
teragen_outdir = "teragen/"
teragen_count = 0
teragen_coeff = 598827.47
teragen_intercept = 14752931.32

wc_indir = "wordcount_input/*"
wc_outdir = "wc_output/"
wc_count = 0


last_time = 0
with open('1copy.sh', 'wb') as outfile:
    # outfile.write('hdfsc dfs -rmr wc_output\n')
    # outfile.write('hdfsc dfs -rmr teragen\n')
    # outfile.write('hdfsc dfs -mkdir wc_output\n')
    # outfile.write('hdfsc dfs -mkdir teragen\n')
    outfile.write('hdfsc dfs -rmr {1..500}\n')
    outfile.write('hdfsc dfs -rmr "/user/ubuntu/Q*" &\n')
    with open('job_file_Scaled.csv', 'rb') as csvfile:
        spamreader = csv.reader(csvfile, delimiter=',')
        for index, row in enumerate(spamreader):
            if index % 20 == 0 and index / 20 > 2:
                outfile.write(
                    'hdfsc dfs -rmr {'+str(index - 40)+'..'+str(index-20)+'}\n')
            if index == 0:
                continue
            # Controls the number of jobs you want to test in the trace
            if index == 101:
                break
            cur_time = int(row[0])
            if row[3] == '0':
            	pi_count += 1
                pi_map = int(row[1])
                pi_time = int(row[2])
                pi_sample = int(pi_coeff * pi_time + pi_intercept)
                outfile.write(
                    "sleep " + str((cur_time - last_time + 0.5)*20) + '\n')
                outfile.write(pi_command + ' ' + str(pi_map) +
                              #' ' + str(pi_sample) + ' &\n')
                              #' ' + str(pi_sample) + ' ' + str(pi_time) +' &\n')
                			  ' ' + str(pi_sample) + ' ' + str(pi_time) + ' ' + str(pi_count) + ' &\n')
            if row[3] == '1':
                teragen_map = int(row[1])
                teragen_time = int(row[2])
                teragen_size = int(
                    teragen_coeff * teragen_time + teragen_intercept)
                teragen_count += 1
                outfile.write(
                    "sleep " + str((cur_time - last_time + 0.5)*20) + '\n')
                #outfile.write(teragen_command+str(teragen_map)+' '+str(teragen_size)+' '+teragen_outdir+str(teragen_count)+' &\n')
                outfile.write(teragen_command + str(teragen_map) +
                              #' ' + str(teragen_size) + ' ' + str(index) + ' &\n')
                              #' ' + str(teragen_size) + ' ' + str(index) + ' ' + str(teragen_time) +' &\n')
                			  ' ' + str(teragen_size) + ' ' + str(index) + ' ' + str(teragen_time) + ' ' + str(teragen_count) + ' &\n')
            if row[3] == '2':
                wc_count += 1
                outfile.write(
                    "sleep " + str((cur_time - last_time + 0.5)*20) + '\n')
                if index % 5 == 0:
                    #outfile.write(wc_command + ' ' + wc_indir+'1' + ' ' +wc_outdir+str(wc_count)+' &\n')
                    outfile.write(wc_command + ' ' + wc_indir +
                                  #'1' + ' ' + str(index) + ' &\n')
                                  #'1' + ' ' + str(index) + ' ' + row[2] + ' &\n')
                    			  '1' + ' ' + str(index) + ' ' + row[2] + ' ' + str(wc_count) + ' &\n')
                else:
                    #outfile.write(wc_command + ' ' + wc_indir + ' ' +wc_outdir+str(wc_count)+' &\n')
                    outfile.write(wc_command + ' ' + wc_indir +
                                  #' ' + str(index) + ' &\n')
                                  #' ' + str(index) + ' ' + row[2] + ' &\n')
                    			  ' ' + str(index) + ' ' + row[2] + ' ' + str(wc_count) + ' &\n')
            last_time = cur_time
