import csv
import os
import json

img_dir = "./"
list_of_imgs = []
listSubmitTime = []
listRunningTime = []
for img in sorted(os.listdir(img_dir)):
    if img.endswith('.jhist'):
        list_of_imgs.append(img)

for filename in list_of_imgs:
    with open(filename) as infile:
        for i, line in enumerate(infile):
            if i == 4:
                p = json.loads(line)
                submitTime = (
                    p['event']['org.apache.hadoop.mapreduce.jobhistory.JobSubmitted']['submitTime'])
            if i == 2:
                p = json.loads(line)
                startAMTime = (
                    p['event']['org.apache.hadoop.mapreduce.jobhistory.AMStarted']['startTime'])   
        p = json.loads(line)
        try:
            finishTime = (
                p['event']['org.apache.hadoop.mapreduce.jobhistory.JobFinished']['finishTime'])
        except:
            finishTime = (
                p['event']['org.apache.hadoop.mapreduce.jobhistory.JobUnsuccessfulCompletion']['finishTime'])
        elapsedTime = (finishTime - submitTime) / 1000
        runningTime = (finishTime - startAMTime) / 1000
        listSubmitTime.append(elapsedTime)
        listRunningTime.append(runningTime)

with open('out.csv', 'wb') as outfile:
    writer = csv.writer(outfile, delimiter=',')
    with open('job_file_Scaled.csv', 'rb') as csvfile:
        spamreader = csv.reader(csvfile, delimiter=',')
        for index, row in enumerate(spamreader):
            if index == 0:
                continue
            # Controls the number of jobs you want to test in the trace
            if index == 101:
                break
            writer.writerow(row + [listSubmitTime[index - 1], listRunningTime[index - 1]])