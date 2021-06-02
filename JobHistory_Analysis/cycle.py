import os
import json
import csv
import numpy as np
jobs = {}

list_of_imgs = []
list_of_elapsedTime = []
img_dir = "./"
for img in os.listdir(img_dir):
    if img.endswith('.jhist'):
        list_of_imgs.append(img)

for index, filename in enumerate(list_of_imgs):
    #If testing on trace, this needs to be modified
    if index % 3 == 0:
        key = "wc_0copy"
    elif index % 3 == 1:
        key = "wc_1copy"
    elif index % 3 == 2:
        key = "wc_2copy"
    if key not in jobs:
        jobs[key] = []
    with open(filename) as infile:
        for i, line in enumerate(infile):
            if i == 2:
                p = json.loads(line)
                startTime = (
                    p['event']['org.apache.hadoop.mapreduce.jobhistory.AMStarted']['startTime'])
        p = json.loads(line)
        finishTime = (
            p['event']['org.apache.hadoop.mapreduce.jobhistory.JobFinished']['finishTime'])
        elapsedTime = (finishTime - startTime) / 1000
        jobs[key].append(elapsedTime)
with open('out2.csv', 'wb') as csvfile:
    writer = csv.writer(csvfile, delimiter=',')
    writer.writerow(["Job Type", "Cycle #0", "Cycle #1", "Cycle #2", "Cycle #3", "Cycle #4", "Cycle #5",
                     "Cycle #6", "Cycle #7", "Avg. Elapsed Time", "Elapsed Time Variance", "Elapsed Time Range"])
    for key in sorted(jobs):
        writelist = [key]
        for i, item in enumerate(jobs[key]):
            if i < 8:
                writelist.append(item)
        print jobs[key]
        nplist = np.array(jobs[key])
        writelist.append('%.2f' % np.mean(nplist))
        writelist.append('%.2f' % np.std(nplist))
        writelist.append(max(jobs[key]) - min(jobs[key]))
        # print key+" #"+str(i)+" Elapsed Time:" + str(jobs[key][i]/1000)
        writer.writerow(writelist)
