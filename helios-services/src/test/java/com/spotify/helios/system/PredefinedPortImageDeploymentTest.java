/**
 * Copyright (C) 2014 Spotify AB
 */

package com.spotify.helios.system;

import com.google.common.collect.ImmutableMap;

import com.spotify.helios.common.Client;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.PortMapping;

import org.junit.Test;

import static com.spotify.helios.common.descriptors.AgentStatus.Status.UP;
import static com.spotify.helios.common.descriptors.Goal.START;
import static com.spotify.helios.common.descriptors.TaskStatus.State.RUNNING;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class PredefinedPortImageDeploymentTest extends SystemTestBase {

  @Test
  public void test() throws Exception {
    final String agentName = "foobar";
    startDefaultMaster();

    final Client client = defaultClient();

    startDefaultAgent(agentName);

    // Create a job using an image exposing port 80 but without mapping it
    final Job job1 = Job.newBuilder()
        .setName(PREFIX + "wordpress")
        .setVersion("v1")
        .setImage("jbfink/wordpress")
        .setCommand(DO_NOTHING_COMMAND)
        .build();
    final JobId jobId1 = job1.getId();
    client.createJob(job1).get();

    // Create a job using an image exposing port 80 and map it to a specific external port
    final Job job2 = Job.newBuilder()
        .setName(PREFIX + "wordpress")
        .setVersion("v2")
        .setImage("jbfink/wordpress")
        .setCommand(DO_NOTHING_COMMAND)
        .setPorts(ImmutableMap.of("tcp", PortMapping.of(80, EXTERNAL_PORT)))
        .build();
    final JobId jobId2 = job2.getId();
    client.createJob(job2).get();

    // Wait for agent to come up
    awaitAgentRegistered(client, agentName, WAIT_TIMEOUT_SECONDS, SECONDS);
    awaitAgentStatus(client, agentName, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Deploy the jobs on the agent
    client.deploy(Deployment.of(jobId1, START), agentName).get();
    client.deploy(Deployment.of(jobId2, START), agentName).get();

    // Wait for the jobs to run
    awaitJobState(client, agentName, jobId1, RUNNING, LONG_WAIT_MINUTES, MINUTES);
    awaitJobState(client, agentName, jobId2, RUNNING, LONG_WAIT_MINUTES, MINUTES);
  }
}
