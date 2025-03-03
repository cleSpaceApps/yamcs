package org.yamcs.yarch;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.yamcs.logging.Log;

/**
 * Streams are means to transport tuples.
 *
 */
public abstract class Stream {

    // states
    public static final int SETUP = 0;
    public static final int RUNNING = 1;
    public static final int QUITTING = 2;

    protected String name;
    protected TupleDefinition outputDefinition;
    final protected Collection<StreamSubscriber> subscribers = new ConcurrentLinkedQueue<>();

    protected volatile int state = SETUP;

    protected Log log;

    protected YarchDatabaseInstance ydb;
    private volatile AtomicLong dataCount = new AtomicLong();
    private volatile AtomicInteger subscriberCount = new AtomicInteger();
    private ExceptionHandler handler;

    protected Stream(YarchDatabaseInstance ydb, String name, TupleDefinition definition) {
        this.name = name;
        this.outputDefinition = definition;
        this.ydb = ydb;
        log = new Log(getClass(), ydb.getName());
        log.setContext(name);
    }

    /**
     * Start emitting tuples.
     */
    public abstract void start();

    public TupleDefinition getDefinition() {
        return outputDefinition;
    }

    public void emitTuple(Tuple tuple) {
        dataCount.incrementAndGet();
        for (StreamSubscriber s : subscribers) {
            try {
                s.onTuple(this, tuple);
            } catch (Exception e) {
                if (handler != null) {
                    handler.handle(tuple, s, e);
                } else {
                    log.warn("Exception received when emitting tuple to subscriber " + s + ": {}", e);
                    throw e;
                }
            }
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String streamName) {
        this.name = streamName;
    }

    public void addSubscriber(StreamSubscriber s) {
        subscribers.add(s);
        subscriberCount.incrementAndGet();
    }

    public void removeSubscriber(StreamSubscriber s) {
        subscribers.remove(s);
        subscriberCount.decrementAndGet();
    }

    public ColumnDefinition getColumnDefinition(String colName) {
        return outputDefinition.getColumn(colName);
    }

    /**
     * Closes the stream by: send the streamClosed signal to all subscribed clients
     */
    public final void close() {
        if (state == QUITTING) {
            return;
        }
        state = QUITTING;

        ydb.removeStream(name);
        log.debug("Closed stream {} num emitted tuples: {}", name, getDataCount());
        doClose();
        for (StreamSubscriber s : subscribers) {
            s.streamClosed(this);
        }
    }

    protected abstract void doClose();

    public int getState() {
        return state;
    }

    public long getDataCount() {
        return dataCount.get();
    }

    public int getSubscriberCount() {
        return subscriberCount.get();
    }

    public Collection<StreamSubscriber> getSubscribers() {
        return Collections.unmodifiableCollection(subscribers);
    }

    public void exceptionHandler(ExceptionHandler h) {
        this.handler = h;
    }

    @Override
    public String toString() {
        return name;
    }

    public static interface ExceptionHandler {
        public void handle(Tuple tuple, StreamSubscriber s, Throwable t);
    }
}
