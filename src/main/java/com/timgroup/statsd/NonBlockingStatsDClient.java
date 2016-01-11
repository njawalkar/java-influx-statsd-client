package com.timgroup.statsd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A simple StatsD client implementation facilitating metrics recording.
 *
 * <p>Upon instantiation, this client will establish a socket connection to a StatsD instance
 * running on the specified host and port. Metrics are then sent over this connection as they are
 * received by the client.
 * </p>
 *
 * <p>Three key methods are provided for the submission of data-points for the application under
 * scrutiny:
 * <ul>
 *   <li>{@link #incrementCounter} - adds one to the value of the specified named counter</li>
 *   <li>{@link #recordGaugeValue} - records the latest fixed value for the specified named gauge</li>
 *   <li>{@link #recordExecutionTime} - records an execution time in milliseconds for the specified named operation</li>
 *   <li>{@link #recordHistogramValue} - records a value, to be tracked with average, maximum, and percentiles</li>
 *   <li>{@link #recordEvent} - records an event</li>
 * </ul>
 * From the perspective of the application, these methods are non-blocking, with the resulting
 * IO operations being carried out in a separate thread. Furthermore, these methods are guaranteed
 * not to throw an exception which may disrupt application execution.
 * </p>
 *
 * <p>As part of a clean system shutdown, the {@link #stop()} method should be invoked
 * on any StatsD clients.</p>
 *
 * @author Tom Denley
 *
 */
public final class NonBlockingStatsDClient implements StatsDClient {

    private static final int PACKET_SIZE_BYTES = 1500;

    private static final StatsDClientErrorHandler NO_OP_HANDLER = new StatsDClientErrorHandler() {
        @Override public void handle(Exception e) { /* No-op */ }
    };

    /**
     * Because NumberFormat is not thread-safe we cannot share instances across threads. Use a ThreadLocal to
     * create one pre thread as this seems to offer a significant performance improvement over creating one per-thread:
     * http://stackoverflow.com/a/1285297/2648
     * https://github.com/indeedeng/java-dogstatsd-client/issues/4
     */
    private static final ThreadLocal<NumberFormat> NUMBER_FORMATTERS = new ThreadLocal<NumberFormat>() {
        @Override
        protected NumberFormat initialValue() {

            // Always create the formatter for the US locale in order to avoid this bug:
            // https://github.com/indeedeng/java-dogstatsd-client/issues/3
            NumberFormat numberFormatter = NumberFormat.getInstance(Locale.US);
            numberFormatter.setGroupingUsed(false);
            numberFormatter.setMaximumFractionDigits(6);

            // we need to specify a value for Double.NaN that is recognized by dogStatsD
            if (numberFormatter instanceof DecimalFormat) { // better safe than a runtime error
                final DecimalFormat decimalFormat = (DecimalFormat) numberFormatter;
                final DecimalFormatSymbols symbols = decimalFormat.getDecimalFormatSymbols();
                symbols.setNaN("NaN");
                decimalFormat.setDecimalFormatSymbols(symbols);
            }

            return numberFormatter;
        }
    };

    private final String prefix;
    private final DatagramChannel clientChannel;
    private final InetSocketAddress address;
    private final StatsDClientErrorHandler handler;
    private final String constantTagsRendered;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        final ThreadFactory delegate = Executors.defaultThreadFactory();
        @Override public Thread newThread(Runnable r) {
            Thread result = delegate.newThread(r);
            result.setName("StatsD-" + result.getName());
            result.setDaemon(true);
            return result;
        }
    });

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are consumed, guaranteeing
     * that failures in metrics will not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param hostname
     *     the host name of the targeted StatsD server
     * @param port
     *     the port of the targeted StatsD server
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(String prefix, String hostname, int port) throws StatsDClientException {
        this(prefix, hostname, port, null, NO_OP_HANDLER);
    }

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are consumed, guaranteeing
     * that failures in metrics will not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param hostname
     *     the host name of the targeted StatsD server
     * @param port
     *     the port of the targeted StatsD server
     * @param constantTags
     *     tags to be added to all content sent
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(String prefix, String hostname, int port, String... constantTags) throws StatsDClientException {
        this(prefix, hostname, port, constantTags, NO_OP_HANDLER);
    }

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are passed to the specified
     * handler and then consumed, guaranteeing that failures in metrics will
     * not affect normal code execution.
     *
     * @param prefix
     *     the prefix to apply to keys sent via this client
     * @param hostname
     *     the host name of the targeted StatsD server
     * @param port
     *     the port of the targeted StatsD server
     * @param constantTags
     *     tags to be added to all content sent
     * @param errorHandler
     *     handler to use when an exception occurs during usage
     * @throws StatsDClientException
     *     if the client could not be started
     */
    public NonBlockingStatsDClient(String prefix, String hostname, int port, String[] constantTags, StatsDClientErrorHandler errorHandler) throws StatsDClientException {
        if(prefix != null && prefix.length() > 0) {
          if(!prefix.endsWith( "." )){
            this.prefix = String.format("%s.", prefix);
          }
          else{
            this.prefix = prefix;
          }
        } else {
            this.prefix = "";
        }
        this.handler = errorHandler;

        /* Empty list should be null for faster comparison */
        if(constantTags != null && constantTags.length == 0) {
            constantTags = null;
        }

        if(constantTags != null) {
            this.constantTagsRendered = tagString(constantTags, null);
        } else {
            this.constantTagsRendered = null;
        }

        try {
            this.clientChannel = DatagramChannel.open();
            this.address = new InetSocketAddress(hostname, port);
        } catch (Exception e) {
            throw new StatsDClientException("Failed to start StatsD client", e);
        }
        this.executor.submit(new QueueConsumer());
    }

    /**
     * Cleanly shut down this StatsD client. This method may throw an exception if
     * the socket cannot be closed.
     */
    @Override
    public void stop() {
        try {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            handler.handle(e);
        }
        finally {
            if (clientChannel != null) {
                try {
                    clientChannel.close();
                }
                catch (IOException e) {
                    handler.handle(e);
                }
            }
        }
    }

    /**
     * Generate a suffix conveying the given tag list to the client
     */
    static String tagString(final String[] tags, final String prefix) {
        StringBuilder sb;
        if(prefix != null) {
            if(tags == null || tags.length == 0) {
                return prefix;
            }
            sb = new StringBuilder(prefix);
            sb.append(",");
        } else {
            if(tags == null || tags.length == 0) {
                return "";
            }
            sb = new StringBuilder(",");
        }

        for(int n=tags.length - 1; n>=0; n--) {
            sb.append(tags[n]);
            if(n > 0) {
                sb.append(",");
            }
        }
        return sb.toString();
    }

    /**
     * Generate a suffix conveying the given tag list to the client
     */
    String tagString(final String[] tags) {
        return tagString(tags, constantTagsRendered);
    }

    /**
     * Adjusts the specified counter by a given delta.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the counter to adjust
     * @param delta
     *     the amount to adjust the counter by
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void count(String aspect, long delta, String... tags) {
        send(String.format("%s%s%s:%d|c", prefix, aspect, tagString(tags), delta ));
    }

    /**
     * Increments the specified counter by one.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the counter to increment
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void incrementCounter(String aspect, String... tags) {
        count(aspect, 1, tags);
    }

    /**
     * Convenience method equivalent to {@link #incrementCounter(String, String[])}.
     */
    @Override
    public void increment(String aspect, String... tags) {
        incrementCounter(aspect, tags);
    }

    /**
     * Decrements the specified counter by one.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the counter to decrement
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void decrementCounter(String aspect, String... tags) {
        count(aspect, -1, tags);
    }

    /**
     * Convenience method equivalent to {@link #decrementCounter(String, String[])}.
     */
    @Override
    public void decrement(String aspect, String... tags) {
        decrementCounter(aspect, tags);
    }

    /**
     * Records the latest fixed value for the specified named gauge.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the gauge
     * @param value
     *     the new reading of the gauge
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordGaugeValue(String aspect, double value, String... tags) {
        /* Intentionally using %s rather than %f here to avoid
         * padding with extra 0s to represent precision */
        send(String.format("%s%s%s:%s|g", prefix, aspect, tagString(tags), NUMBER_FORMATTERS.get().format(value)));
    }

    /**
     * Convenience method equivalent to {@link #recordGaugeValue(String, double, String[])}.
     */
    @Override
    public void gauge(String aspect, double value, String... tags) {
        recordGaugeValue(aspect, value, tags);
    }


    /**
     * Records the latest fixed value for the specified named gauge.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the gauge
     * @param value
     *     the new reading of the gauge
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordGaugeValue(String aspect, long value, String... tags) {
        send(String.format("%s%s%s:%d|g", prefix, aspect, tagString(tags), value));
    }

    /**
     * Convenience method equivalent to {@link #recordGaugeValue(String, long, String[])}.
     */
    @Override
    public void gauge(String aspect, long value, String... tags) {
        recordGaugeValue(aspect, value, tags);
    }

    /**
     * Records an execution time in milliseconds for the specified named operation.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the timed operation
     * @param timeInMs
     *     the time in milliseconds
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordExecutionTime(String aspect, long timeInMs, String... tags) {
        send(String.format("%s%s%s:%d|ms", prefix, aspect, tagString(tags), timeInMs));
    }

    /**
     * Convenience method equivalent to {@link #recordExecutionTime(String, long, String[])}.
     */
    @Override
    public void time(String aspect, long value, String... tags) {
        recordExecutionTime(aspect, value, tags);
    }

    /**
     * Records a value for the specified named histogram.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the histogram
     * @param value
     *     the value to be incorporated in the histogram
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordHistogramValue(String aspect, double value, String... tags) {
        /* Intentionally using %s rather than %f here to avoid
         * padding with extra 0s to represent precision */
        send(String.format("%s%s%s:%s|h", prefix, aspect, tagString(tags), NUMBER_FORMATTERS.get().format(value)));
    }

    /**
     * Convenience method equivalent to {@link #recordHistogramValue(String, double, String[])}.
     */
    @Override
    public void histogram(String aspect, double value, String... tags) {
        recordHistogramValue(aspect, value, tags);
    }

    /**
     * Records a value for the specified named histogram.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect
     *     the name of the histogram
     * @param value
     *     the value to be incorporated in the histogram
     * @param tags
     *     array of tags to be added to the data
     */
    @Override
    public void recordHistogramValue(String aspect, long value, String... tags) {
        send(String.format("%s%s%s:%d|h", prefix, aspect, tagString(tags), value));
    }

    /**
     * Convenience method equivalent to {@link #recordHistogramValue(String, long, String[])}.
     */
    @Override
    public void histogram(String aspect, long value, String... tags) {
        recordHistogramValue(aspect, value, tags);
    }

    private void send(String message) {
        queue.offer(message);
    }

    public static final Charset MESSAGE_CHARSET = Charset.forName("UTF-8");

    private class QueueConsumer implements Runnable {
        private final ByteBuffer sendBuffer = ByteBuffer.allocate(PACKET_SIZE_BYTES);

        @Override public void run() {
            while(!executor.isShutdown()) {
                try {
                    String message = queue.poll(1, TimeUnit.SECONDS);
                    if(null != message) {
                        byte[] data = message.getBytes(MESSAGE_CHARSET);
                        if(sendBuffer.remaining() < (data.length + 1)) {
                            blockingSend();
                        }
                        if(sendBuffer.position() > 0) {
                            sendBuffer.put( (byte) '\n');
                        }
                        sendBuffer.put(data);
                        if(null == queue.peek()) {
                            blockingSend();
                        }
                    }
                } catch (Exception e) {
                    handler.handle(e);
                }
            }
        }

        private void blockingSend() throws IOException {
            int sizeOfBuffer = sendBuffer.position();
            sendBuffer.flip();
            int sentBytes = clientChannel.send(sendBuffer, address);
            sendBuffer.limit(sendBuffer.capacity());
            sendBuffer.rewind();

            if (sizeOfBuffer != sentBytes) {
                handler.handle(
                        new IOException(
                            String.format(
                                "Could not send stats [%s] to host [%s:%d]. Only sent %d bytes out of %d bytes",
                                sendBuffer.toString(),
                                address.getHostName(),
                                address.getPort(),
                                sentBytes,
                                sizeOfBuffer)));
            }
        }
    }
}
