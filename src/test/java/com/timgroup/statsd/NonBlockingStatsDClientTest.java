package com.timgroup.statsd;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;

import java.net.SocketException;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class NonBlockingStatsDClientTest {

    private static final int STATSD_SERVER_PORT = 17254;
    private final NonBlockingStatsDClient client = new NonBlockingStatsDClient("my.prefix", "localhost", STATSD_SERVER_PORT);
    private DummyStatsDServer server;

    @Before
    public void start() throws SocketException {
        server = new DummyStatsDServer(STATSD_SERVER_PORT);
    }

    @After
    public void stop() throws Exception {
        client.stop();
        server.close();
    }

    @Test(timeout=5000L) public void
    sends_counter_value_to_statsd() throws Exception {


        client.count("mycount", 24);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mycount:24|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_value_to_statsd_with_null_tags() throws Exception {


        client.count("mycount", 24, (java.lang.String[]) null);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mycount:24|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_value_to_statsd_with_empty_tags() throws Exception {


        client.count("mycount", 24);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mycount:24|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_value_to_statsd_with_tags() throws Exception {


        client.count("mycount", 24, "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mycount,baz,foo=bar:24|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_increment_to_statsd() throws Exception {


        client.incrementCounter("myinc");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.myinc:1|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_increment_to_statsd_with_tags() throws Exception {


        client.incrementCounter("myinc", "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.myinc,baz,foo=bar:1|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_decrement_to_statsd() throws Exception {


        client.decrementCounter("mydec");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mydec:-1|c"));
    }

    @Test(timeout=5000L) public void
    sends_counter_decrement_to_statsd_with_tags() throws Exception {


        client.decrementCounter("mydec", "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mydec,baz,foo=bar:-1|c"));
    }

    @Test(timeout=5000L) public void
    sends_gauge_to_statsd() throws Exception {


        client.recordGaugeValue("mygauge", 423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge:423|g"));
    }

    @Test(timeout=5000L) public void
    sends_large_double_gauge_to_statsd() throws Exception {


        client.recordGaugeValue("mygauge", 123456789012345.67890);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge:123456789012345.67|g"));
    }

    @Test(timeout=5000L) public void
    sends_exact_double_gauge_to_statsd() throws Exception {


        client.recordGaugeValue("mygauge", 123.45678901234567890);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge:123.456789|g"));
    }

    @Test(timeout=5000L) public void
    sends_double_gauge_to_statsd() throws Exception {


        client.recordGaugeValue("mygauge", 0.423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge:0.423|g"));
    }

    @Test(timeout=5000L) public void
    sends_gauge_to_statsd_with_tags() throws Exception {


        client.recordGaugeValue("mygauge", 423, "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge,baz,foo=bar:423|g"));
    }

    @Test(timeout=5000L) public void
    sends_double_gauge_to_statsd_with_tags() throws Exception {


        client.recordGaugeValue("mygauge", 0.423, "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge,baz,foo=bar:0.423|g"));
    }

    @Test(timeout=5000L) public void
    sends_histogram_to_statsd() throws Exception {


        client.recordHistogramValue("myhistogram", 423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.myhistogram:423|h"));
    }

    @Test(timeout=5000L) public void
    sends_double_histogram_to_statsd() throws Exception {


        client.recordHistogramValue("myhistogram", 0.423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.myhistogram:0.423|h"));
    }

    @Test(timeout=5000L) public void
    sends_histogram_to_statsd_with_tags() throws Exception {


        client.recordHistogramValue("myhistogram", 423, "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.myhistogram,baz,foo=bar:423|h"));
    }

    @Test(timeout=5000L) public void
    sends_double_histogram_to_statsd_with_tags() throws Exception {


        client.recordHistogramValue("myhistogram", 0.423, "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.myhistogram,baz,foo=bar:0.423|h"));
    }

    @Test(timeout=5000L) public void
    sends_timer_to_statsd() throws Exception {


        client.recordExecutionTime("mytime", 123);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mytime:123|ms"));
    }

    /**
     * A regression test for <a href="https://github.com/indeedeng/java-dogstatsd-client/issues/3">this i18n number formatting bug</a>
     * @throws Exception
     */
    @Test public void
    sends_timer_to_statsd_from_locale_with_unamerican_number_formatting() throws Exception {

        Locale originalDefaultLocale = Locale.getDefault();

        // change the default Locale to one that uses something other than a '.' as the decimal separator (Germany uses a comma)
        Locale.setDefault(Locale.GERMANY);

        try {


            client.recordExecutionTime("mytime", 123, "foo=bar", "baz");
            server.waitForMessage();

            assertThat(server.messagesReceived(), contains("my.prefix.mytime,baz,foo=bar:123|ms"));
        } finally {
            // reset the default Locale in case changing it has side-effects
            Locale.setDefault(originalDefaultLocale);
        }
    }


    @Test(timeout=5000L) public void
    sends_timer_to_statsd_with_tags() throws Exception {


        client.recordExecutionTime("mytime", 123, "foo=bar", "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mytime,baz,foo=bar:123|ms"));
    }


    @Test(timeout=5000L) public void
    sends_gauge_mixed_tags() throws Exception {

        final NonBlockingStatsDClient empty_prefix_client = new NonBlockingStatsDClient("my.prefix", "localhost", STATSD_SERVER_PORT, new String[] {"instance=foo", "app=bar"});
        empty_prefix_client.gauge("value", 423, "baz");
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.value,app=bar,instance=foo,baz:423|g"));
    }

    @Test(timeout=5000L) public void
    sends_gauge_constant_tags_only() throws Exception {

        final NonBlockingStatsDClient empty_prefix_client = new NonBlockingStatsDClient("my.prefix", "localhost", STATSD_SERVER_PORT, new String[] {"instance:foo", "app:bar"});
        empty_prefix_client.gauge("value", 423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.value,app:bar,instance:foo:423|g"));
    }

    @Test(timeout=5000L) public void
    sends_gauge_empty_prefix() throws Exception {

        final NonBlockingStatsDClient empty_prefix_client = new NonBlockingStatsDClient("", "localhost", STATSD_SERVER_PORT);
        empty_prefix_client.gauge("top.level.value", 423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("top.level.value:423|g"));
    }

    @Test(timeout=5000L) public void
    sends_gauge_null_prefix() throws Exception {

        final NonBlockingStatsDClient null_prefix_client = new NonBlockingStatsDClient(null, "localhost", STATSD_SERVER_PORT);
        null_prefix_client.gauge("top.level.value", 423);
        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("top.level.value:423|g"));
    }

    @Test(timeout=5000L) public void
    sends_nan_gauge_to_statsd() throws Exception {
        client.recordGaugeValue("mygauge", Double.NaN);

        server.waitForMessage();

        assertThat(server.messagesReceived(), contains("my.prefix.mygauge:NaN|g"));
    }

    @Test(timeout=5000L) public void
    tagWithNoPrefix() throws Exception{
    	String tagString = client.tagString( new String[]{"a=b", "c=d"}, null );
    	assertEquals( ",c=d,a=b", tagString );
    }

    @Test(timeout=5000L) public void
    tagWithPrefix() throws Exception{
    	String tagString = client.tagString( new String[]{"a=b", "c=d"}, ",x=y" );
    	assertEquals( ",x=y,c=d,a=b", tagString );
    }

    @Test(timeout=5000L) public void
    noTagWithPrefix() throws Exception{
    	String tagString = client.tagString( new String[]{}, ",x=y" );
    	assertEquals( ",x=y", tagString );
    }

    @Test(timeout=5000L) public void
    noTagWithNoPrefix() throws Exception{
    	String tagString = client.tagString( new String[]{}, "" );
    	assertEquals( "", tagString );
    }
}
