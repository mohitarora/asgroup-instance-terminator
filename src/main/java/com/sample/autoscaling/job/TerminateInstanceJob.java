package com.sample.autoscaling.job;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsync;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.sample.autoscaling.rules.RuleHandler;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * This class represents Terminate Instance job, run() method of this class is scheduled to be called at regular
 * intervals. This job will retrieve all the auto scaling groups in account and apply defined rules to decide if an
 * instance can be terminated in the auto scaling group or not. If instance can be terminated in an auto scaling group,
 * this job will terminate the instance as well.
 */
@Component
public class TerminateInstanceJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerminateInstanceJob.class);

    @Autowired
    private AmazonAutoScalingAsync autoScalingClient;

    @Autowired
    private RuleHandler ruleHandler;

    /**
     * Used to retrieve the sequence number.
     */
    @Autowired
    private BoundValueOperations<String, Long> jobSequenceValueOps;

    /**
     * This method will be called as per the timing configured by cron expression and will initiate the complete job.
     */
    @Scheduled(cron = "${cron.job.schedule}")
    public void run() throws InterruptedException {
        Long jobSequenceNumber = jobSequenceValueOps.increment(1);
        LOGGER.info("Starting Instance Termination #{} job at {}", jobSequenceNumber, DateTime.now());

        List<AutoScalingGroup> allAutoScalingGroups = getAllAutoScalingGroups();

        // If Auto Scaling groups are retrieved, run the instance termination rules against auto scaling group in
        // parallel.
        if (!CollectionUtils.isEmpty(allAutoScalingGroups)) {

            //Create a count down latch to block the scheduler thread
            final CountDownLatch countDownLatch = new CountDownLatch(allAutoScalingGroups.size());

            for (final AutoScalingGroup autoScalingGroup : allAutoScalingGroups) {
                //Each Auto Scaling Group will be handled asynchronously.
                ruleHandler.applyRules(jobSequenceNumber, autoScalingGroup, countDownLatch);
            }
            // Wait for all the auto scaling groups to be processed. All of the auto scaling groups will be processed
            // asynchronously
            countDownLatch.await();
        }

        LOGGER.info("Finished Instance Termination job # {} at {}", jobSequenceNumber, DateTime.now());
    }


    /**
     * Method to retrieve all the auto scaling groups of account.
     *
     * @return List of auto scaling groups.
     */
    public List<AutoScalingGroup> getAllAutoScalingGroups() {
        DescribeAutoScalingGroupsResult autoScalingGroupsResult = null;
        try {
            //This is a blocking call because this is base to start doing work asynchronously beyond this point .
            autoScalingGroupsResult = autoScalingClient.describeAutoScalingGroups();
        }
        catch (AmazonServiceException ase) {
            LOGGER.error("Request was rejected with an error response. Error Message:{}, HTTP Status Code:{}, " +
                "AWS Error Code:{}, Error Type:{}, Request ID:{}", ase.getMessage(), ase.getStatusCode(),
                ase.getErrorCode(), ase.getErrorType(), ase.getRequestId());
        }
        catch (AmazonClientException ace) {
            LOGGER.error("Error Message: {}", ace.getMessage());
        }
        return autoScalingGroupsResult.getAutoScalingGroups();
    }

}
