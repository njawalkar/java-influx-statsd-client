java-influx-statsd-client
==================

A statsd client library implemented in Java.  Allows for Java applications to easily communicate with statsd.

This version is forked from the upstream [java-dogstatsd-client](https://github.com/indeedeng/java-dogstatsd-client) project, adding support for [InfluxDB](https://influxdb.com/blog/2015/11/03/getting_started_with_influx_statsd.html) style metric tags.

Usage
-----
```java
import com.timgroup.statsd.StatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;

public class Foo {

  private static final StatsDClient statsd = new NonBlockingStatsDClient(
    "my.prefix",                          /* prefix to any stats; may be null or empty string */
    "statsd-host",                        /* common case: localhost */
    8125,                                 /* port */
    new String[] {"tag=value"}            /* InfluxDB extension: Constant tags, always applied */
  );

  public static final void main(String[] args) {
    statsd.incrementCounter("foo");
    statsd.recordGaugeValue("bar", 100);
    statsd.recordGaugeValue("baz", 0.01); /* DataDog extension: support for floating-point gauges */
    statsd.recordHistogram("qux", 15)     /* DataDog extension: histograms */
    statsd.recordHistogram("qux", 15.5)   /* ...also floating-point */

    /* expects times in milliseconds
     */
    statsd.recordExecutionTime("bag", 25, "cluster=foo"); /* InfluxDB extension: cluster tag */
  }
}
```
