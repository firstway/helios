package com.spotify.helios.servicescommon.coordination;

import com.spotify.helios.servicescommon.RiemannFacade;
import com.yammer.dropwizard.lifecycle.Managed;
import com.yammer.metrics.core.HealthCheck;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ZooKeeperHealthChecker extends HealthCheck
    implements Managed, PathChildrenCacheListener, Runnable {
  private static final String UNKNOWN = "UNKNOWN";

  private final ScheduledExecutorService scheduler;
  private final PathChildrenCache cache;
  private final RiemannFacade facade;
  private final TimeUnit timeUnit;
  private final int interval;

  private AtomicReference<String> reasonString = new AtomicReference<String>(UNKNOWN);

  public ZooKeeperHealthChecker(final ZooKeeperClient zooKeeperClient, final String path,
                                final RiemannFacade facade, final TimeUnit timeUnit,
                                final int interval) {
    super("zookeeper");
    this.scheduler = Executors.newScheduledThreadPool(1);
    this.cache = new PathChildrenCache(zooKeeperClient.getCuratorFramework(), path, true, false,
        scheduler);
    this.facade = facade.stack("zookeeper-connection");
    this.timeUnit = timeUnit;
    this.interval = interval;

    cache.getListenable().addListener(this);
  }

  @Override
  public void run() {
    String reason = reasonString.get();
    if (UNKNOWN.equals(reasonString.get())) {
      return; // don't report anything until we get a known status
    }

    if (reason != null) {
      facade.event()
          .state("critical")
          .metric(0.0)
          .ttl(timeUnit.toSeconds(interval * 3))
          .tags("zookeeper", "connection")
          .description(reason)
          .send();
    } else {
      facade.event()
          .state("ok")
          .metric(1.0)
          .tags("zookeeper", "connection")
          .ttl(timeUnit.toSeconds(interval * 3))
          .send();
    }
  }

  private void setState(String newState) {
    if ((reasonString.get() == null) != (newState == null)) {
      reasonString.set(newState);
      run();
    }
  }

  @Override
  public void childEvent(CuratorFramework curator, PathChildrenCacheEvent event)
      throws Exception {
    switch (event.getType()) {
      case INITIALIZED:
      case CONNECTION_RECONNECTED:
        setState(null);
        break;

      case CHILD_ADDED:
      case CHILD_REMOVED:
      case CHILD_UPDATED:
        // If we get any of these, clearly we're connected.
        setState(null);
        break;

      case CONNECTION_LOST:
        setState("CONNECTION_LOST");
      case CONNECTION_SUSPENDED:
        setState("CONNECTION_SUSPENDED");
        break;

    }
  }

  @Override
  public void start() throws Exception {
    cache.start();

    scheduler.scheduleAtFixedRate(this, 0, interval, timeUnit);
  }

  @Override
  public void stop() throws Exception {
    scheduler.shutdownNow();
  }

  @Override
  protected Result check() throws Exception {
    if (reasonString.get() == null) {
      return Result.healthy();
    } else {
      return Result.unhealthy(reasonString.get());
    }
  }
}
